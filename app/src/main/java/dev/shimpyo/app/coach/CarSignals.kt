package dev.shimpyo.app.coach

import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import dev.hmg.pleoskit.CarState
import dev.hmg.pleoskit.LocationKit
import dev.hmg.pleoskit.PleosSdkException
import dev.hmg.pleoskit.ReadSignal
import dev.hmg.pleoskit.VehicleKit
import dev.hmg.pleoskit.label
import dev.shimpyo.coach.CabinPort
import dev.shimpyo.coach.CoachLog
import dev.shimpyo.coach.ElapsedClock
import dev.shimpyo.coach.Fix
import dev.shimpyo.coach.PositionTracker
import dev.shimpyo.coach.SignalCollector
import dev.shimpyo.coach.SignalGroup
import dev.shimpyo.coach.SignalHub
import dev.shimpyo.coach.SignalLink
import dev.shimpyo.coach.SignalSource
import dev.shimpyo.coach.SignalWatch
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.TurnSignal
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 공조 내기·외기 ([CabinPort]) = Vehicle SDK의 내기순환. 지원 여부는 capability로 확인한다
 * (차종마다 다르다, docs/contest/02_Pleos_개발가이드/pleos-connect/vehicle-app-planning-guide/api-compatibility-availability.md).
 */
class KitCabin(private val vehicle: VehicleKit) : CabinPort {
    @Volatile var area: SeatArea? = null
        private set

    override val freshAirSupported: Boolean get() = area != null

    /** capability 확인. 지원하는 영역이 없으면 외기 전환을 쓰지 않는다. 조회가 실패하면 예외 (부른 쪽이 다시 묻는다) */
    suspend fun connect(): SeatArea? = vehicle.hvacAreas().firstOrNull().also { area = it }

    override suspend fun freshAir(): Boolean = area?.let { vehicle.recirculation(it).setAndConfirm(false) } ?: false

    override suspend fun recirculate(): Boolean = area?.let { vehicle.recirculation(it).setAndConfirm(true) } ?: false
}

/**
 * 차량·위치 신호를 [SignalHub]와 [PositionTracker]에 모은다. 여기는 무엇을 받는지만 적는다: 연결 셋(AAOS Car 서비스,
 * Pleos 차량 서비스, 위치 서비스)과 그 신호들. 끊기지 않게 받는 방법(다시 붙기, 묶음별 다시 묻기, 신호별 다시 걸기, 다시 읽기,
 * 연결 점검)은 coach 모듈의 [SignalCollector]가 한다 (SignalCollectorTest, docs/design/coach-layer/core.md 8장).
 *
 * 닫는 결함
 * - R-F5·SV-D13: Car 연결·기어 구독 실패를 삼켜 재시작까지 기어·차속이 없었다 → kit이 예외를 던지고 연결을 다시 한다.
 *   차속 권한을 늦게 받아도 차속 신호만 다시 걸려 받는다 (화면의 PermissionsChanged를 기다리지 않는다).
 * - R-F10: 공조 영역 조회가 실패하면 그 뒤의 문·조향·브레이크를 모두 잃었다 → 묶음마다 따로 묻는다.
 * - P8: Car 서비스가 죽으면 kit이 알려 주고 다시 붙는다. P12: 차량 서비스가 멈춰 값이 얼면 다시 읽기로 알아채 다시 붙는다.
 *
 * 권한을 나중에 받으면(UiCommand.PermissionsChanged) 서비스가 이 수집을 취소하고 새로 띄운다 (곧바로 받으려고).
 * 위치는 시연 중에도 계속 받는다: 시연 위치는 CoachWorld가 덧씌울 뿐이다 (구조 리뷰 D3).
 */
class CarSignals(
    private val car: CarState,
    private val vehicle: VehicleKit,
    private val location: LocationKit,
    private val cabin: KitCabin,
    private val hub: SignalHub,
    private val positions: PositionTracker,
    elapsed: ElapsedClock,
    log: CoachLog,
    private val onRestriction: (Boolean) -> Unit,
) {
    private val collector = SignalCollector(elapsed, log)

    /** 취소될 때까지 돈다 */
    suspend fun run() = coroutineScope {
        launch { collector.run(carLink()) }
        launch { collector.run(vehicleLink()) }
        launch { collector.run(locationLink()) }
        // 주행 제한은 kit이 연결·해제에 맞춰 스스로 바꾸는 상태다 (모르면 제한)
        launch { car.drivingRestricted.collect { onRestriction(it) } }
    }

    /** AAOS Car 서비스: 기어와 차속 */
    private fun carLink() = object : SignalLink {
        override val name = "car"

        override suspend fun connect(again: Boolean) {
            if (again) car.release()
            car.connect()
        }

        // Car 서비스가 죽으면 kit이 곧바로 알려 준다 (서비스 감사 P8)
        override suspend fun awaitLost() {
            car.connected.first { !it }
        }

        override val groups = listOf(
            SignalGroup.of(car.gear().source()) { hub.gear = gearOf(it) },
            // CAR_SPEED 권한이 없으면 구독이 실패한다: 이 신호만 간격을 두고 다시 걸어서, 권한을 받으면 그때 걸린다
            SignalGroup.of(car.speedMps().source()) { hub.speedKph = it?.times(3.6f) },
        )
    }

    /** Pleos 차량 서비스 (Vehicle SDK) */
    private fun vehicleLink() = object : SignalLink {
        override val name = "vehicle"

        override suspend fun connect(again: Boolean) {
            if (again) vehicle.reconnect() else vehicle.connect()
        }

        override val groups = listOf(
            // 지원 영역을 물어야 하는 신호는 묶음마다 따로 묻는다: 하나가 실패해도 나머지는 받는다 (리뷰 R-F10)
            SignalGroup("cabin") {
                val area = supported { cabin.connect() } ?: return@SignalGroup emptyList()
                listOf(
                    SignalWatch(vehicle.recirculation(area).source()) { hub.recirculation = it },
                    SignalWatch(vehicle.hvacPower(area).source()) { hub.hvacOn = it },
                )
            },
            SignalGroup("temperature") {
                val area = supported { vehicle.hvacTemperatureAreas().keys.firstOrNull() } ?: return@SignalGroup emptyList()
                listOf(SignalWatch(vehicle.hvacTemperatureC(area).source()) { hub.setTempC = it })
            },
            // 문이 열린 적이 있는지가 화장실 정차 판정의 근거다. 다시 읽었을 때 열려 있어도 열린 것으로 친다
            SignalGroup("doors") {
                supported { vehicle.doorAreas().keys }.orEmpty().map { area ->
                    SignalWatch(vehicle.doorPosition(area).source("door.${area.label()}")) { pos -> if ((pos ?: 0) > 0) hub.markDoorOpened() }
                }
            },
            // 표본 시각은 허브가 정한다 (리뷰 R-F6: 여기서 실제 시각으로 찍으면 시연의 가상 시각과 섞인다)
            SignalGroup.of(vehicle.steeringAngle().source()) { hub.addSteering(it) },
            SignalGroup.of(vehicle.brakePedal().source()) { hub.brake = it },
            SignalGroup.of(vehicle.turnSignal().source()) { v ->
                hub.turnSignal = when (v) {
                    TurnSignalValue.LEFT -> TurnSignal.LEFT
                    TurnSignalValue.RIGHT -> TurnSignal.RIGHT
                    TurnSignalValue.OFF -> TurnSignal.NONE
                    null -> null
                }
            },
            SignalGroup.of(vehicle.cruiseControl().source()) { c -> hub.cruiseActive = c?.let { it == CruiseControl.ACTIVATED } },
        )
    }

    /** 위치 서비스 (FusedLocation). FUSED_LOCATION 권한이 없으면 구독이 실패하고, 받으면 다시 걸 때 걸린다 */
    private fun locationLink() = object : SignalLink {
        override val name = "location"

        override suspend fun connect(again: Boolean) {
            if (again) location.reconnect() else location.connect()
        }

        override val groups = listOf(
            SignalGroup.of(SignalSource("fix", read = { location.lastKnown()?.toFix() }, changes = { location.updates().map { it.toFix() } })) { fix ->
                // 끊겨서 모르게 된 위치(null)는 넘기지 않는다: 오래된 위치는 PositionTracker가 스스로 가린다
                if (fix != null) positions.onFused(fix)
            },
        )
    }

    /** 이 차가 지원하지 않는 신호면 null (다시 묻지 않는다). 그 밖의 실패는 그대로 던져 다시 묻게 한다 */
    private suspend fun <T : Any> supported(ask: suspend () -> T?): T? = try {
        ask()
    } catch (e: PleosSdkException) {
        if (VehicleKit.notSupported(e)) null else throw e
    }

    companion object {
        // android.car.VehicleGear 값
        private const val GEAR_NEUTRAL = 1
        private const val GEAR_REVERSE = 2
        private const val GEAR_PARK = 4
        private const val GEAR_DRIVE = 8
        private const val GEAR_1 = 16

        fun gearOf(v: Int?): Gear = when (v) {
            null -> Gear.UNKNOWN
            GEAR_PARK -> Gear.PARK
            GEAR_DRIVE -> Gear.DRIVE
            GEAR_REVERSE -> Gear.REVERSE
            GEAR_NEUTRAL -> Gear.NEUTRAL
            else -> if (v >= GEAR_1) Gear.DRIVE else Gear.UNKNOWN
        }

        /** kit 신호 하나를 coach 계층의 신호 출처로 */
        private fun <T : Any> ReadSignal<T>.source(name: String = this.name) = SignalSource(name, read = { get() }, changes = { changes() })

        private fun LocationData.toFix() = Fix(latitude, longitude, heading.takeIf { it.isFinite() })
    }
}
