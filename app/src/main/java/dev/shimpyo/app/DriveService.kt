package dev.hmg.pleosapp

import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import android.content.Intent
import android.util.Log
import dev.hmg.pleoskit.CarState
import dev.hmg.pleoskit.LocationKit
import dev.hmg.pleoskit.NaviEvent
import dev.hmg.pleoskit.NaviKit
import dev.hmg.pleoskit.PleosForegroundService
import dev.hmg.pleoskit.ReadSignal
import dev.hmg.pleoskit.SttKit
import dev.hmg.pleoskit.TtsKit
import dev.hmg.pleoskit.VehicleKit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * 주행 중 계속 살아 있는 서비스. kit에 연결하고 차량 신호를 [DriveState]에 모은다.
 * 기능 로직(아이디어가 정해지면 추가)은 [DriveState.snapshot]을 읽어 판단하고 kit으로 개입한다.
 */
class DriveService : PleosForegroundService() {

    override val notificationTitle = "Pleos App"

    private val car by lazy { CarState(this) }
    private val vehicle by lazy { VehicleKit(this) }
    private val navi by lazy { NaviKit(this) }
    private val tts by lazy { TtsKit(this) }
    private val stt by lazy { SttKit(this) }
    private val location by lazy { LocationKit(this) }

    private var selfTest: Job? = null

    override fun onServiceStarted() {
        serviceScope.launch { step("car") { collectCarState() } }
        serviceScope.launch { step("vehicle") { collectVehicleSignals() } }
        serviceScope.launch { step("navi") { collectNaviState() } }
    }

    /** `--ez selftest true`로 시작하면 kit 동작 점검을 한 번 돌린다(개발용). `--ez route true`면 길안내까지 시험한다. */
    override fun onCommand(intent: Intent?) {
        if (intent?.getBooleanExtra("selftest", false) != true || selfTest?.isActive == true) return
        val withRoute = intent.getBooleanExtra("route", false)
        selfTest = serviceScope.launch {
            KitSelfTest(vehicle, navi, tts, stt, location).run(withRoute)
        }
    }

    private suspend fun collectCarState() {
        car.connect()
        serviceScope.launch { car.gear.collect { v -> set("gear", v) { copy(gear = v) } } }
        serviceScope.launch { car.speedMps.collect { v -> set("speedKph", v?.times(3.6f)) { copy(speedKph = v?.times(3.6f)) } } }
        serviceScope.launch { car.drivingRestricted.collect { v -> set("drivingRestricted", v) { copy(drivingRestricted = v) } } }
    }

    private suspend fun collectVehicleSignals() {
        vehicle.connect()
        // 영역은 capability로 얻는다. 지원하지 않는 차종이면 해당 신호만 건너뛴다
        val hvacArea = runCatching { vehicle.hvacAreas().firstOrNull() }.getOrNull()
        if (hvacArea != null) {
            watch(vehicle.recirculation(hvacArea)) { copy(recirculation = it) }
            watch(vehicle.hvacPower(hvacArea)) { copy(hvacPower = it) }
        }
        val tempArea = runCatching { vehicle.hvacTemperatureAreas().keys.firstOrNull() }.getOrNull()
        if (tempArea != null) watch(vehicle.hvacTemperatureC(tempArea)) { copy(cabinTargetTempC = it) }
        watch(vehicle.cruiseControl()) { copy(cruise = it?.name) }
        watch(vehicle.tripDrivingTimeMin()) { copy(tripDrivingMin = it) }
        watch(vehicle.brakePedal()) { copy(brakePressed = it) }
        watch(vehicle.doorPosition(DoorArea(DoorValue.ROW_1_LEFT))) { copy(driverDoorOpen = it?.let { pos -> pos > 0 }) }
        watch(vehicle.evStateOfCharge()) { copy(stateOfCharge = it) }
    }

    private suspend fun collectNaviState() {
        navi.connect()
        serviceScope.launch {
            navi.events.filterIsInstance<NaviEvent.RouteState>().collect { e ->
                set("naviMode", e.info.naviMode) { copy(naviMode = e.info.naviMode.name) }
            }
        }
        // 길안내 시작·종료를 내비가 먼저 알려 주지 않는 경우가 있어 주기적으로 조회한다
        while (true) {
            runCatching { navi.routeState() }.onFailure { Log.w(TAG, "routeState failed: ${it.message}") }
            delay(15_000)
        }
    }

    /** 신호 하나를 구독해 [DriveState]에 반영한다. 한 신호가 실패해도 나머지는 계속 받는다. */
    private fun <T : Any> watch(signal: ReadSignal<T>, apply: DriveSnapshot.(T?) -> DriveSnapshot) {
        serviceScope.launch {
            step(signal.name) {
                signal.changes().collect { v -> set(signal.name, v) { apply(v) } }
            }
        }
    }

    private fun set(name: String, value: Any?, change: DriveSnapshot.() -> DriveSnapshot) {
        Log.i(TAG, "STATE|$name|$value")
        DriveState.update(change)
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "STATE|$name|FAILED ${e.message}")
        }
    }

    override fun onDestroy() {
        car.release()
        vehicle.release()
        navi.release()
        tts.release()
        stt.release()
        location.release()
        super.onDestroy()
    }

    companion object {
        const val TAG = "PleosApp"
    }
}
