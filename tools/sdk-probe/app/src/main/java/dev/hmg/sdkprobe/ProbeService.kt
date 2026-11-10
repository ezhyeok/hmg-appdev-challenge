package dev.hmg.sdkprobe

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.ChargerOperator
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
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
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.Looper
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener

/**
 * Pleos Connect SDK 가용성 검증 서비스.
 *
 * 각 SDK의 핵심 호출을 한 번씩 수행하고 결과를 logcat(tag: PleosProbe)과 MainActivity 화면에
 * `PROBE|영역|항목|OK/FAIL/INFO|상세` 형식으로 남긴다. 리스너도 등록해 두므로 Virtual Console이나
 * adb로 차량 상태를 바꾸면 `event.*` 줄이 추가로 찍힌다.
 *
 * 포그라운드 서비스로 돌리는 이유: NaviHelper.requestRoute를 보내면 내비 앱이 화면 앞으로 오고,
 * Activity만 있는 앱은 백그라운드(cached) 프로세스가 되어 Android 14 freezer에 얼려진다
 * (logcat `ActivityManager: freezing <pid> dev.hmg.sdkprobe`). 얼어 있는 동안에는 SDK 이벤트를 받지 못한다.
 *
 * Vehicle/NaviHelper 호출은 서비스 연결을 기다리며 수 초간 호출 스레드를 막는다(에뮬레이터에서 측정).
 * 메인 스레드에서 부르면 ANR이 나므로 모든 SDK 호출은 worker 스레드에서 한다.
 */
class ProbeService : Service() {

    private val workerThread = HandlerThread("probe-worker").apply { start() }
    private val worker = Handler(workerThread.looper)

    // Vehicle 호출이 worker를 수십 초 막을 수 있어서, 내비 요청 간격이 뭉개지지 않게 스레드를 따로 둔다
    private val naviThread = HandlerThread("probe-navi").apply { start() }
    private val naviWorker = Handler(naviThread.looper)

    private var car: Car? = null
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    private var fused: FusedLocation? = null
    private var started = false

    private fun ok(area: String, name: String, detail: Any?) = ProbeLog.ok(area, name, detail)
    private fun info(area: String, name: String, detail: Any?) = ProbeLog.info(area, name, detail)
    private fun fail(area: String, name: String, e: Throwable) = ProbeLog.fail(area, name, e)

    /** 각 probe를 감싸서 한 항목의 예외가 나머지 검사를 막지 않게 하고, 300ms 넘게 걸리면 소요 시간을 남긴다. */
    private inline fun guard(area: String, name: String, block: () -> Unit) {
        val start = SystemClock.elapsedRealtime()
        try {
            block()
        } catch (t: Throwable) {
            fail(area, name, t)
        }
        val took = SystemClock.elapsedRealtime() - start
        if (took > 300) info(area, "$name.blockedMs", took)
    }

    // ---------------------------------------------------------------- 생명주기

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!started) {
            started = true
            info("probe", "start", "sdk-probe 0.5 (foreground service, navi thread)")
            worker.post { guard("aaos", "probe") { probeAaos() } }
            worker.post { guard("vehicle", "probe") { probeVehicle() } }
            naviWorker.post { guard("navi", "probe") { probeNavi() } }
            worker.post { guard("tts", "probe") { probeTts() } }
            worker.post { guard("stt", "probe") { probeStt() } }
            worker.post { guard("fused", "probe") { probeFused() } }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "SDK probe", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("SDK probe running")
            .build()
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
        workerThread.quitSafely()
        naviThread.quitSafely()
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
        guard("aaos", "register.PERF_VEHICLE_SPEED") {
            var last = Float.NaN
            val cb = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    val v = value.value as? Float ?: return
                    // 연속 속성이라 같은 값이 반복해서 오므로 값이 바뀔 때만 남긴다
                    if (v != last) {
                        last = v
                        ok("aaos", "event.PERF_VEHICLE_SPEED", v)
                    }
                }

                override fun onErrorEvent(propId: Int, zone: Int) =
                    info("aaos", "event.PERF_VEHICLE_SPEED.error", "prop=$propId zone=$zone")
            }
            pm.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            ok("aaos", "register.PERF_VEHICLE_SPEED", "registered")
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
        worker.postDelayed({ guard("vehicle", "calls") { vehicleCalls(v) } }, 1500)
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
                        { _, value ->
                            ok("vehicle", "set.hvacRecirculation", "requested=$target result=$value")
                            // 성공 콜백만으로는 실제 값이 바뀌었는지 알 수 없으므로 다시 읽어 보고 원래 값으로 되돌린다
                            worker.postDelayed({ verifyAndRestoreRecirculation(v, a, state ?: false) }, 2000)
                        },
                        { e -> fail("vehicle", "set.hvacRecirculation", e) },
                    )
                }
            },
            { e -> fail("vehicle", "get.hvacRecirculation", e) },
        )
    }

    private fun verifyAndRestoreRecirculation(v: Vehicle, a: SeatArea, original: Boolean) {
        val hvac = v.getHVAC()
        hvac.getHvacRecirculationState(
            a,
            { now ->
                ok("vehicle", "verify.hvacRecirculation", "original=$original now=$now changed=${now != original}")
                guard("vehicle", "restore.hvacRecirculation") {
                    hvac.setHvacRecirculation(
                        a,
                        original,
                        { _, value -> ok("vehicle", "restore.hvacRecirculation", "result=$value") },
                        { e -> fail("vehicle", "restore.hvacRecirculation", e) },
                    )
                }
            },
            { e -> fail("vehicle", "verify.hvacRecirculation", e) },
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

        override fun onTripInfo(tripInfo: TripInfo) = ok(
            "navi",
            "event.trip",
            "distance=${tripInfo.distance} eta=${tripInfo.eta} socAtDest=${tripInfo.estimatedSocAtDest} " +
                "energy=${tripInfo.energyConsumption} routePoints=${tripInfo.summary.routePoints.size} " +
                "traffic=${tripInfo.summary.trafficConditions.size}",
        )

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

        override fun onDestinationChanged() = ok("navi", "event.destinationChanged", "-")
        override fun onReRouteEnded() = ok("navi", "event.reRouteEnded", "-")
        override fun onRouteOptionChanged(routeOption: RouteOption) = ok("navi", "event.routeOptionChanged", routeOption)
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = ok("navi", "event.speedLimit", speedLimit)
        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) = ok("navi", "event.cameraAlert", cameraAlertList)
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) =
            ok("navi", "event.cautionPoint", cautionPointList)

        override fun onChargerOperatorInfo(chargerOperatorList: List<ChargerOperator>) =
            ok("navi", "event.chargerOperator", "count=${chargerOperatorList.size} ${chargerOperatorList.take(3)}")

        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) = ok("navi", "event.bookmark", bookmarkInfoList)
        override fun onBookMarkChanged() = ok("navi", "event.bookmarkChanged", "-")
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) =
            ok("navi", "event.recentDestination", recentDestinationInfoList)
    }

    private fun probeNavi() {
        val n = NaviHelper(this)
        navi = n
        n.initialize(
            onConnected = {
                ok("navi", "initialize", "connected")
                naviWorker.post {
                    guard("navi", "addListener") { n.addListener(naviListener) }
                    guard("navi", "get.currentLocation") { n.getCurrentLocationInfo() }
                    guard("navi", "get.destination") { n.getDestinationInfo() }
                    guard("navi", "get.routeState") { n.getRouteStateInfo() }
                }
                // 길안내 없이 거리·ETA·도착 시 SOC만 받을 수 있는지 (EV 플래너용)
                naviWorker.postDelayed({ guard("navi", "requestTrip") { requestTestTrip(n) } }, 2000)
                naviWorker.postDelayed({ guard("navi", "requestRoute") { requestTestRoute(n) } }, 8000)
                // 조회형 이벤트(onTBTInfo 등)는 내비가 먼저 보내지 않을 수 있으므로 get*을 직접 호출해 본다
                naviWorker.postDelayed({ pollNavi(n, "afterRoute") }, 16000)
                naviWorker.postDelayed({ guard("navi", "addWaypoint") { addTestWaypoint(n) } }, 20000)
                naviWorker.postDelayed({ pollNavi(n, "afterWaypoint") }, 30000)
                naviWorker.postDelayed({ guard("navi", "cancelRoute") { n.cancelRoute(); ok("navi", "cancelRoute", "sent") } }, 120000)
            },
            onFailed = { t -> fail("navi", "initialize", t) },
        )
    }

    private fun pollNavi(n: NaviHelper, tag: String) {
        info("navi", "poll", tag)
        guard("navi", "poll.routeState") { n.getRouteStateInfo() }
        guard("navi", "poll.tbt") { n.getTBTInfo() }
        guard("navi", "poll.waypoint") { n.getWaypointInfo() }
        guard("navi", "poll.destination") { n.getDestinationInfo() }
        guard("navi", "poll.chargerOperator") { n.getChargerOperatorInfo() }
    }

    private fun requestTestTrip(n: NaviHelper) {
        n.requestTrip(
            TripRequest(latitude = 37.5547, longitude = 126.9707, poiId = "", poiName = "서울역", poiSubId = ""),
        )
        ok("navi", "requestTrip", "sent 서울역")
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
        const val CHANNEL_ID = "probe"
        const val NOTIFICATION_ID = 1
    }
}
