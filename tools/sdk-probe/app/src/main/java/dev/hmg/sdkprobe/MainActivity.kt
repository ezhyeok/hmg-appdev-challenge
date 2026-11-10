package dev.hmg.sdkprobe

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener

/**
 * Pleos Connect SDK 가용성 검증 앱.
 *
 * 실행하면 각 SDK의 핵심 호출을 한 번씩 수행하고 결과를 화면과 logcat(tag: PleosProbe)에
 * `PROBE|영역|항목|OK/FAIL/INFO|상세` 형식으로 남긴다. 리스너도 등록해 두므로 Virtual Console이나
 * adb로 차량 상태를 바꾸면 `event.*` 줄이 추가로 찍힌다.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var out: TextView

    private var car: Car? = null
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    private var fused: FusedLocation? = null

    // ---------------------------------------------------------------- 로그

    private fun log(area: String, name: String, status: String, detail: Any?) {
        val line = "PROBE|$area|$name|$status|$detail"
        Log.i(TAG, line)
        main.post { out.append(line + "\n") }
    }

    private fun ok(area: String, name: String, detail: Any?) = log(area, name, "OK", detail)
    private fun info(area: String, name: String, detail: Any?) = log(area, name, "INFO", detail)
    private fun fail(area: String, name: String, e: Throwable) =
        log(area, name, "FAIL", "${e.javaClass.simpleName}: ${e.message}")

    /** 각 probe를 감싸서 한 항목의 예외가 나머지 검사를 막지 않게 한다. */
    private inline fun guard(area: String, name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            fail(area, name, t)
        }
    }

    // ---------------------------------------------------------------- 생명주기

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setPadding(24, 24, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(out) })

        info("probe", "start", "sdk-probe 0.1")
        guard("aaos", "probe") { probeAaos() }
        guard("vehicle", "probe") { probeVehicle() }
        guard("navi", "probe") { probeNavi() }
        guard("tts", "probe") { probeTts() }
        guard("stt", "probe") { probeStt() }
        guard("fused", "probe") { probeFused() }
    }

    override fun onDestroy() {
        guard("probe", "release") {
            vehicle?.release()
            navi?.release()
            tts?.release()
            stt?.release()
            fused?.release()
            car?.disconnect()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- AAOS 표준 API

    private fun probeAaos() {
        val c = Car.createCar(this)
        car = c
        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

        guard("aaos", "get.GEAR_SELECTION") {
            ok("aaos", "get.GEAR_SELECTION", pm.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0).value)
        }
        guard("aaos", "get.PERF_VEHICLE_SPEED") {
            ok("aaos", "get.PERF_VEHICLE_SPEED", pm.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0).value)
        }
        guard("aaos", "register.GEAR_SELECTION") {
            val cb = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) =
                    ok("aaos", "event.GEAR_SELECTION", value.value)

                override fun onErrorEvent(propId: Int, zone: Int) =
                    info("aaos", "event.GEAR_SELECTION.error", "prop=$propId zone=$zone")
            }
            pm.registerCallback(cb, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            ok("aaos", "register.GEAR_SELECTION", "registered")
        }
        guard("aaos", "uxRestrictions") {
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            ok("aaos", "get.uxRestrictions", describe(ux.currentCarUxRestrictions))
            ux.registerListener { r -> ok("aaos", "event.uxRestrictions", describe(r)) }
        }
    }

    private fun describe(r: CarUxRestrictions) =
        "requiresDO=${r.isRequiresDistractionOptimization} active=0x${Integer.toHexString(r.activeRestrictions)}"

    // ---------------------------------------------------------------- Vehicle SDK

    // 모든 Vehicle 리스너는 VehicleListener.onFailed(e: Exception)도 구현해야 한다
    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) = ok("vehicle", "event.doorPosition.ROW_1_LEFT", pos)
        override fun onFailed(e: Exception) = fail("vehicle", "event.doorPosition.ROW_1_LEFT", e)
    }

    private val recircListener = object : HvacRecirculationStateListener {
        override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) =
            ok("vehicle", "event.hvacRecirculation", state)

        override fun onFailed(e: Exception) = fail("vehicle", "event.hvacRecirculation", e)
    }

    private fun probeVehicle() {
        val v = Vehicle(this)
        vehicle = v
        v.initialize()
        ok("vehicle", "initialize", "called")
        // initialize()는 결과 콜백이 없으므로 서비스 연결 시간을 두고 호출한다
        main.postDelayed({ guard("vehicle", "calls") { vehicleCalls(v) } }, 1500)
    }

    private fun vehicleCalls(v: Vehicle) {
        val door = v.getDoor()
        val leftFront = DoorArea(DoorValue.ROW_1_LEFT)
        door.checkDoorPositionCapabilities(
            { cfg, map -> ok("vehicle", "check.doorPosition", "$cfg areas=${map.size} $map") },
            { e -> fail("vehicle", "check.doorPosition", e) },
        )
        door.getCurrentDoorPosition(
            leftFront,
            { pos -> ok("vehicle", "get.doorPosition.ROW_1_LEFT", pos) },
            { e -> fail("vehicle", "get.doorPosition.ROW_1_LEFT", e) },
        )
        guard("vehicle", "register.doorPosition") {
            door.registerDoorPosition(leftFront, doorListener)
            ok("vehicle", "register.doorPosition", "registered")
        }

        val seat = v.getSeat()
        seat.checkSeatOccupancyCapabilities(
            { cfg, areas ->
                ok("vehicle", "check.seatOccupancy", "$cfg areas=${areas.size}")
                areas.forEachIndexed { i, a ->
                    seat.getCurrentSeatOccupancy(
                        a,
                        { occ -> ok("vehicle", "get.seatOccupancy[$i]", "$a -> $occ") },
                        { e -> fail("vehicle", "get.seatOccupancy[$i]", e) },
                    )
                }
            },
            { e -> fail("vehicle", "check.seatOccupancy", e) },
        )

        val hvac = v.getHVAC()
        hvac.checkHvacRecirculationCapabilities(
            { cfg, areas ->
                ok("vehicle", "check.hvacRecirculation", "$cfg areas=$areas")
                val a = areas.firstOrNull() ?: return@checkHvacRecirculationCapabilities
                hvacRecirculation(v, a)
            },
            { e -> fail("vehicle", "check.hvacRecirculation", e) },
        )

        val window = v.getWindow()
        window.checkWindowPositionCapabilities(
            { cfg, map ->
                ok("vehicle", "check.windowPosition", "$cfg areas=${map.size}")
                map.keys.firstOrNull()?.let { w ->
                    window.getCurrentWindowPosition(
                        w,
                        { pos -> ok("vehicle", "get.windowPosition[0]", "$w -> $pos") },
                        { e -> fail("vehicle", "get.windowPosition[0]", e) },
                    )
                }
            },
            { e -> fail("vehicle", "check.windowPosition", e) },
        )

        val ev = v.getEvBattery()
        ev.checkEvBatteryStateOfChargeCapabilities(
            { cfg -> ok("vehicle", "check.evSoc", cfg) },
            { e -> fail("vehicle", "check.evSoc", e) },
        )
        ev.getEvBatteryStateOfCharge(
            { soc -> ok("vehicle", "get.evSoc", soc) },
            { e -> fail("vehicle", "get.evSoc", e) },
        )

        v.getTurnSignal().getCurrentTurnSignalState(
            { s -> ok("vehicle", "get.turnSignal", s) },
            { e -> fail("vehicle", "get.turnSignal", e) },
        )
    }

    /** 내기순환 조회 → 구독 → 제어(set) 시도. set이 되는지가 Vehicle 제어 API 사용 가능 여부의 판단 근거. */
    private fun hvacRecirculation(v: Vehicle, a: SeatArea) {
        val hvac = v.getHVAC()
        hvac.getHvacRecirculationState(
            a,
            { state ->
                ok("vehicle", "get.hvacRecirculation", state)
                guard("vehicle", "register.hvacRecirculation") {
                    hvac.registerHvacRecirculationState(a, recircListener)
                    ok("vehicle", "register.hvacRecirculation", "registered")
                }
                val target = !(state ?: false)
                guard("vehicle", "set.hvacRecirculation") {
                    hvac.setHvacRecirculation(
                        a,
                        target,
                        { _, value -> ok("vehicle", "set.hvacRecirculation", "requested=$target result=$value") },
                        { e -> fail("vehicle", "set.hvacRecirculation", e) },
                    )
                }
            },
            { e -> fail("vehicle", "get.hvacRecirculation", e) },
        )
    }

    // ---------------------------------------------------------------- NaviHelper

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) =
            ok("navi", "event.currentLocation", currentLocationInfo)

        override fun onDestinationInfo(destinationInfo: DestinationInfo) =
            ok("navi", "event.destinationInfo", destinationInfo)

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) =
            ok("navi", "event.routeState", routeStateInfo)

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) =
            ok("navi", "event.routeStarted", routeStartInfo)

        override fun onRoutingCompleted() = ok("navi", "event.routingCompleted", "-")

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = ok("navi", "event.tbt", tbtInfoList)

        override fun onTripInfo(tripInfo: TripInfo) = ok("navi", "event.trip", tripInfo)

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) =
            ok("navi", "event.waypointInfo", waypointInfoList)

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) =
            ok("navi", "event.waypointChanged", waypointChangedInfo)

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) =
            ok("navi", "event.waypointArrived", waypointArrivedInfo)

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) =
            ok("navi", "event.destinationArrived", destinationArrivedInfo)

        override fun onRouteCancelled() = ok("navi", "event.routeCancelled", "-")

        override fun onDrivingInfo(drivingInfo: DrivingInfo) = ok("navi", "event.drivingInfo", drivingInfo)

        override fun onNavigationStatus(status: NavigationStatus) = ok("navi", "event.navigationStatus", status)

        override fun onError(errorCode: NaviErrorCode) = info("navi", "event.error", errorCode)
    }

    private fun probeNavi() {
        val n = NaviHelper(this)
        navi = n
        n.initialize(
            onConnected = {
                ok("navi", "initialize", "connected")
                n.addListener(naviListener)
                guard("navi", "get.currentLocation") { n.getCurrentLocationInfo() }
                guard("navi", "get.destination") { n.getDestinationInfo() }
                guard("navi", "get.routeState") { n.getRouteStateInfo() }
                main.postDelayed({ guard("navi", "requestRoute") { requestTestRoute(n) } }, 2000)
                main.postDelayed({ guard("navi", "addWaypoint") { addTestWaypoint(n) } }, 10000)
            },
            onFailed = { t -> fail("navi", "initialize", t) },
        )
    }

    private fun requestTestRoute(n: NaviHelper) {
        n.requestRoute(
            RouteInfo(
                latitude = 37.5547,
                longitude = 126.9707,
                poiName = "서울역",
                poiId = "",
                address = "서울특별시 중구 한강대로 405",
                poiSubId = "",
            ),
        )
        ok("navi", "requestRoute", "sent 서울역")
    }

    private fun addTestWaypoint(n: NaviHelper) {
        n.addWaypoint(
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
        ok("navi", "addWaypoint", "sent 서울시청")
    }

    // ---------------------------------------------------------------- TTS / STT (ON_DEVICE, 앱 등록 없이)

    private val ttsEvents = object : TtsEventListener {
        override fun onReady() = ok("tts", "event.ready", "-")
        override fun onStart() = ok("tts", "event.start", "-")
        override fun onDone() = ok("tts", "event.done", "-")
        override fun onStop() = ok("tts", "event.stop", "-")
        override fun onError(errorType: TtsErrorType) = info("tts", "event.error", errorType)
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private fun probeTts() {
        val t = TextToSpeech(this, TtsMode.ON_DEVICE)
        tts = t
        t.initialize(object : TtsInitListener {
            override fun onSuccess() {
                ok("tts", "initialize", "ON_DEVICE")
                guard("tts", "speak") {
                    t.addEventListener(ttsEvents)
                    t.setLocale(Locale.KOREA)
                    t.speak("플레오스 음성 합성 테스트입니다")
                    ok("tts", "speak", "requested")
                }
            }

            override fun onFailure(errorType: TtsErrorType) = info("tts", "initialize.failure", errorType)
        })
    }

    private fun probeStt() {
        val s = SpeechToText(this, SttMode.ON_DEVICE)
        stt = s
        s.initialize(object : SttInitListener {
            override fun onSuccess() = ok("stt", "initialize", "ON_DEVICE")
            override fun onFailure(errorType: SttErrorType) = info("stt", "initialize.failure", errorType)
        })
    }

    // ---------------------------------------------------------------- FusedLocation

    private fun probeFused() {
        val f = FusedLocation(this)
        fused = f
        val supported = f.isSupported()
        ok("fused", "isSupported", supported)
        if (!supported) return
        f.initialize()
        f.getLastKnownLocation(object : FusedLocationListener {
            override fun onChanged(location: LocationData?) = ok("fused", "get.lastKnownLocation", location)
            override fun onError(exception: Exception) = fail("fused", "get.lastKnownLocation", exception)
        })
    }

    private companion object {
        const val TAG = "PleosProbe"
    }
}
