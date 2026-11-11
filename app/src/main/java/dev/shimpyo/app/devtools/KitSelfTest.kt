package dev.hmg.pleosapp

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import android.util.Log
import dev.hmg.pleoskit.LocationKit
import dev.hmg.pleoskit.NaviKit
import dev.hmg.pleoskit.SttKit
import dev.hmg.pleoskit.TtsKit
import dev.hmg.pleoskit.VehicleKit
import dev.hmg.pleoskit.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 개발용 점검. pleos-kit의 각 기능을 에뮬레이터에서 한 번씩 실제로 불러 보고 결과를
 * logcat(tag: PleosApp)에 `TEST|항목|OK/FAIL|상세`로 남긴다. 앱 기능과는 무관하다.
 */
class KitSelfTest(
    private val vehicle: VehicleKit,
    private val navi: NaviKit,
    private val tts: TtsKit,
    private val stt: SttKit,
    private val location: LocationKit,
) {
    suspend fun run(withRoute: Boolean) = coroutineScope {
        scope = this
        Log.i(TAG, "TEST|start|INFO|withRoute=$withRoute")
        vehicle.connect()

        check("hvac.areas") { vehicle.hvacAreas().map { it.label() } }
        val hvacArea = runCatching { vehicle.hvacAreas().first() }.getOrNull()
        if (hvacArea != null) {
            check("recirculation.toggle") {
                val signal = vehicle.recirculation(hvacArea)
                val before = signal.get() ?: false
                val changed = signal.setAndConfirm(!before)
                val restored = signal.setAndConfirm(before)
                "before=$before changed=$changed restored=$restored"
            }
            check("hvacPower.get") { vehicle.hvacPower(hvacArea).get() }
        }
        check("temperature.set") {
            // 공조 전원이 꺼져 있으면 온도 속성을 쓸 수 없으므로 먼저 켠다
            if (hvacArea != null) vehicle.hvacPower(hvacArea).setAndConfirm(true)
            val (area, bounds) = vehicle.hvacTemperatureAreas().entries.first()
            val signal = vehicle.hvacTemperatureC(area)
            val before = signal.get()
            val target = if (before != null && before >= 24f) 22f else 25f
            val changed = signal.setAndConfirm(target, matches = VehicleKit.temperatureMatches)
            val restored = before?.let { signal.setAndConfirm(it, matches = VehicleKit.temperatureMatches) }
            "area=${area.label()} range=${bounds.minValue}..${bounds.maxValue} before=$before target=$target changed=$changed restored=$restored"
        }
        check("fanSpeed.get") {
            val (area, bounds) = vehicle.hvacFanSpeedAreas().entries.first()
            "area=${area.label()} range=${bounds.minValue}..${bounds.maxValue} now=${vehicle.hvacFanSpeed(area).get()}"
        }
        check("window.set") {
            val (area, bounds) = vehicle.windowAreas().entries.first()
            val signal = vehicle.windowPosition(area)
            val before = signal.get() ?: 0
            val target = if (before == bounds.minValue) (bounds.minValue + bounds.maxValue) / 5 else bounds.minValue
            val changed = signal.setAndConfirm(target, timeoutMs = 8000)
            val restored = signal.setAndConfirm(before, timeoutMs = 8000)
            "area=${area.label()} range=${bounds.minValue}..${bounds.maxValue} before=$before target=$target changed=$changed restored=$restored"
        }
        // 조명과 조향각은 kit에 전용 래퍼가 없는 API를 범용 어댑터(call, call2)로 부르는 예시이기도 하다
        check("seatBackrest.move") {
            val areas = vehicle.seatBackrestAreas()
            // 운전석이 있으면 운전석으로 시험한다
            val (area, bounds) = areas.entries.firstOrNull { it.key.label() == "ROW_1_LEFT" } ?: areas.entries.first()
            val position = vehicle.seatBackrestPosition(area)
            val seen = mutableListOf<Int?>()
            val watcher = scope.launch { position.changes().collect { seen += it } }
            val before = position.get()
            vehicle.moveSeatBackrest(area, MoveControl.BACKWARD); delay(3000)
            vehicle.moveSeatBackrest(area, MoveControl.STOP); delay(700)
            val afterBackward = position.get()
            vehicle.moveSeatBackrest(area, MoveControl.FORWARD); delay(3000)
            vehicle.moveSeatBackrest(area, MoveControl.STOP); delay(700)
            val afterForward = position.get()
            watcher.cancel()
            "area=${area.label()} range=${bounds.minValue}..${bounds.maxValue} before=$before afterBackward=$afterBackward afterForward=$afterForward events=$seen"
        }
        check("cabinLight.set") {
            val light = vehicle.raw.getLight()
            suspend fun switch() = vehicle.call<LightSwitch?>("getCabinLightSwitch") { ok, fail -> light.getCabinLightSwitch(ok, fail) }
            suspend fun set(v: LightSwitch) = vehicle.call<LightSwitch?>("setCabinLightSwitch") { ok, fail -> light.setCabinLightSwitch(v, ok, fail) }
            val before = switch()
            val target = if (before == LightSwitch.ON) LightSwitch.OFF else LightSwitch.ON
            set(target); delay(1500)
            val after = switch()
            before?.let { set(it) }
            "before=$before target=$target after=$after"
        }
        check("ambientLight.set") {
            val light = vehicle.raw.getLight()
            val (area, bounds) = vehicle.call2<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>("checkAmbientLightBrightnessCapabilities") { ok, fail ->
                light.checkAmbientLightBrightnessCapabilities(ok, fail)
            }.second.entries.first()
            suspend fun brightness() = vehicle.call<Int?>("getAmbientLightBrightness") { ok, fail -> light.getAmbientLightBrightness(area, ok, fail) }
            suspend fun set(v: Int) = vehicle.call2<SeatArea, Int>("setAmbientLightBrightness") { ok, fail -> light.setAmbientLightBrightness(area, v, ok, fail) }
            val before = brightness()
            val target = if (before == bounds.minValue) bounds.maxValue else bounds.minValue
            set(target); delay(1500)
            val after = brightness()
            before?.let { set(it) }
            "area=${area.label()} range=${bounds.minValue}..${bounds.maxValue} before=$before target=$target after=$after"
        }
        check("steeringAngle.get") {
            vehicle.call<Float?>("getCurrentSteeringWheelAngle") { ok, fail -> vehicle.raw.getSteeringWheel().getCurrentSteeringWheelAngle(ok, fail) }
        }
        check("door.areas") { vehicle.doorAreas().keys.map { it.label() } }
        check("seatOccupancy") {
            vehicle.seatOccupancyAreas().map { a -> "${a.label()}=${vehicle.seatOccupancy(a).get()}" }
        }
        check("cruise.get") { vehicle.cruiseControl().get() }
        check("tripDrivingTime.get") { vehicle.tripDrivingTimeMin().get() }
        check("tripCumulativeDrivingTime.get") {
            vehicle.call<Int?>("getTripCumulativeDrivingTime") { ok, fail -> vehicle.raw.getOdometer().getTripCumulativeDrivingTime(ok, fail) }
        }
        check("tripSinceRefillDrivingTime.get") {
            vehicle.call<Int?>("getTripSinceRefillDrivingTime") { ok, fail -> vehicle.raw.getOdometer().getTripSinceRefillDrivingTime(ok, fail) }
        }
        check("brakePedal.get") { vehicle.brakePedal().get() }
        check("soc.get") { vehicle.evStateOfCharge().get() }

        check("location.lastKnown") {
            if (!location.isSupported()) return@check "not supported"
            location.connect()
            location.lastKnown()
        }

        check("tts.speak") {
            tts.connect()
            tts.speak("플레오스 키트 점검입니다")
        }
        check("stt.listen") {
            stt.connect()
            stt.listen(timeoutMs = 6000)
        }

        check("navi.connect") { navi.connect() }
        check("navi.currentLocation") { navi.currentLocation().let { "${it.latitude},${it.longitude} ${it.address}" } }
        check("navi.routeState") { navi.routeState().naviMode }
        check("navi.tripEstimate") {
            val trip = navi.tripEstimate(TripRequest(37.5547, 126.9707, "", "서울역", ""))
            "distance=${trip.distance}m eta=${trip.eta}s socAtDest=${trip.estimatedSocAtDest}%"
        }
        if (withRoute) {
            check("navi.startRoute") {
                navi.startRoute(
                    RouteInfo(
                        latitude = 37.5547,
                        longitude = 126.9707,
                        poiName = "서울역",
                        poiId = "",
                        address = "서울특별시 중구 한강대로 405",
                        poiSubId = "",
                    ),
                )
            }
            check("navi.tbt") { navi.tbt().take(3).map { "${it.type}@${it.distance.toInt()}m" } }
            check("navi.addWaypoint") {
                navi.addWaypoint(
                    RequestWaypointInfo(
                        latitude = 37.5663,
                        longitude = 126.9779,
                        poiName = "서울시청",
                        waypointIndex = WaypointIndex.FIRST,
                        poiId = "",
                        address = "서울특별시 중구 세종대로 110",
                        poiSubId = "",
                    ),
                )
            }
            check("navi.waypoints") { navi.waypoints().map { it.name } }
            check("navi.cancelRoute") { navi.cancelRoute() }
        }
        Log.i(TAG, "TEST|end|INFO|-")
    }

    private lateinit var scope: CoroutineScope

    private suspend fun check(name: String, block: suspend () -> Any?) {
        try {
            Log.i(TAG, "TEST|$name|OK|${block()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "TEST|$name|FAIL|${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "PleosApp"
    }
}
