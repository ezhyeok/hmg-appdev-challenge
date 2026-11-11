package dev.shimpyo.app.coach

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import android.util.Log
import dev.hmg.pleoskit.CarState
import dev.hmg.pleoskit.LocationKit
import dev.hmg.pleoskit.ReadSignal
import dev.hmg.pleoskit.VehicleKit
import dev.shimpyo.coach.CabinPort
import dev.shimpyo.coach.Fix
import dev.shimpyo.coach.PositionTracker
import dev.shimpyo.coach.SignalHub
import dev.shimpyo.engine.DrivingSample
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.TurnSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 공조 내기·외기 ([CabinPort]) = Vehicle SDK의 내기순환. 지원 여부는 capability로 확인한다
 * (차종마다 다르다, docs/contest/02_Pleos_개발가이드/pleos-connect/vehicle-app-planning-guide/api-compatibility-availability.md).
 */
class KitCabin(private val vehicle: VehicleKit) : CabinPort {
    @Volatile var area: SeatArea? = null
        private set

    override val freshAirSupported: Boolean get() = area != null

    /** capability 확인. 지원하는 영역이 없으면 외기 전환을 쓰지 않는다 */
    suspend fun connect(): SeatArea? = vehicle.hvacAreas().firstOrNull().also { area = it }

    override suspend fun freshAir(): Boolean = area?.let { vehicle.recirculation(it).setAndConfirm(false) } ?: false

    override suspend fun recirculate(): Boolean = area?.let { vehicle.recirculation(it).setAndConfirm(true) } ?: false
}

/**
 * 차량·위치 신호를 [SignalHub]와 [PositionTracker]에 모은다. 출처마다 따로 돌고, 연결이나 구독이 실패하면 2초부터 두 배씩
 * 최대 1분 간격으로 다시 한다 (서비스 감사 D13: 예전에는 한 번 실패하면 재시작할 때까지 그 신호가 없었다).
 * 권한을 나중에 받으면(UiCommand.PermissionsChanged) 서비스가 이 수집을 취소하고 새로 띄운다.
 * 위치는 시연 중에도 계속 받는다: 시연 위치는 CoachWorld가 덧씌울 뿐이다 (구조 리뷰 D3).
 */
class CarSignals(
    private val car: CarState,
    private val vehicle: VehicleKit,
    private val location: LocationKit,
    private val cabin: KitCabin,
    private val hub: SignalHub,
    private val positions: PositionTracker,
    private val now: () -> Long,
    private val onRestriction: (Boolean) -> Unit,
) {
    /** 취소될 때까지 돈다 */
    suspend fun run() = coroutineScope {
        launch { retrying("car") { collectCar() } }
        launch { retrying("vehicle") { collectVehicle() } }
        launch { retrying("location") { collectLocation() } }
    }

    private suspend fun collectCar() = coroutineScope {
        car.connect()
        launch { car.gear.collect { hub.gear = gearOf(it) } }
        launch { car.speedMps.collect { hub.speedKph = it?.times(3.6f) } }
        launch {
            car.drivingRestricted.collect { r ->
                hub.drivingRestricted = r
                onRestriction(r)
            }
        }
    }

    private suspend fun collectVehicle() = coroutineScope {
        vehicle.connect()
        cabin.connect()?.let { a ->
            watch(vehicle.recirculation(a)) { hub.recirculation = it }
            watch(vehicle.hvacPower(a)) { hub.hvacOn = it }
        }
        runCatching { vehicle.hvacTemperatureAreas().keys.firstOrNull() }.getOrNull()?.let { a ->
            watch(vehicle.hvacTemperatureC(a)) { hub.setTempC = it }
        }
        watch(vehicle.steeringAngle()) { v ->
            hub.steeringDeg = v
            if (v != null) hub.addSample(DrivingSample(t = now(), speedKph = hub.speedKph, steeringDeg = v))
        }
        watch(vehicle.brakePedal()) { hub.brake = it }
        watch(vehicle.turnSignal()) { v ->
            hub.turnSignal = when (v) {
                TurnSignalValue.LEFT -> TurnSignal.LEFT
                TurnSignalValue.RIGHT -> TurnSignal.RIGHT
                TurnSignalValue.OFF -> TurnSignal.NONE
                null -> null
            }
        }
        watch(vehicle.cruiseControl()) { c -> hub.cruiseActive = c?.let { it == CruiseControl.ACTIVATED } }
        runCatching { vehicle.doorAreas().keys }.getOrNull()?.forEach { area ->
            watch(vehicle.doorPosition(area)) { pos -> if ((pos ?: 0) > 0) hub.markDoorOpened() }
        }
    }

    private suspend fun collectLocation() {
        location.connect()
        location.updates().collect { l -> positions.onFused(Fix(l.latitude, l.longitude, l.heading.takeIf { it.isFinite() })) }
    }

    /** 신호 하나: 지금 값을 읽고 바뀔 때마다 받는다. 구독이 끊기면 다시 한다 */
    private fun <T : Any> kotlinx.coroutines.CoroutineScope.watch(signal: ReadSignal<T>, onValue: (T?) -> Unit) {
        launch {
            retrying(signal.name) {
                runCatching { onValue(signal.get()) }
                signal.changes().collect { onValue(it) }
            }
        }
    }

    /** [block]을 끝날 때까지 돌리고, 실패하거나 끝나면 간격을 두고 다시 한다. 30초 넘게 잘 돌았으면 간격을 처음으로 */
    private suspend fun retrying(name: String, block: suspend () -> Unit) {
        var wait = RETRY_MIN_MS
        while (true) {
            val startedAt = System.nanoTime()
            try {
                block()
                Log.w(TAG, "$name: stream ended, reconnecting")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$name failed: ${e.message ?: e.javaClass.simpleName} (retry in ${wait / 1000}s)")
            }
            if ((System.nanoTime() - startedAt) / 1_000_000 > HEALTHY_MS) wait = RETRY_MIN_MS
            delay(wait)
            wait = (wait * 2).coerceAtMost(RETRY_MAX_MS)
        }
    }

    companion object {
        private const val TAG = "Shimpyo"
        const val RETRY_MIN_MS = 2_000L
        const val RETRY_MAX_MS = 60_000L
        const val HEALTHY_MS = 30_000L

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
    }
}
