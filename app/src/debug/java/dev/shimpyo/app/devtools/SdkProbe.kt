package dev.shimpyo.app.devtools

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.listener.EventListener
import android.os.SystemClock
import android.util.Log
import dev.hmg.pleoskit.NaviEvent
import dev.hmg.pleoskit.NaviKit
import dev.hmg.pleoskit.SttKit
import dev.hmg.pleoskit.TtsKit
import dev.hmg.pleoskit.VehicleKit
import dev.shimpyo.app.coach.KitNaviPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType

/**
 * SDK 동작 실험 (debug 빌드 전용, `--es probe "<실험>"`). 문서에 없는 SDK 동작을 에뮬레이터에서 한 가지씩 확인하고
 * 결과를 logcat `PleosApp`에 `PROBE|실험|내용`으로 남긴다. 확인한 내용은 CLAUDE.md 'API 사용 시 주의'에 적는다.
 * kit의 규칙을 거치지 않고 SDK를 직접 부르는 실험이 섞여 있다 (kit의 규칙을 정하려고 보는 것이다).
 *
 * 실험
 * - `wp`: 경유지 목록
 * - `wp-add:<FIRST|SECOND>:<위도>,<경도>:<이름>`: 그 자리에 경유지를 넣고 20초 동안 목록과 이벤트를 본다
 * - `wp-rm:<FIRST|SECOND|ALL>`: 경유지를 뺀다
 * - `wp-set:<위도>,<경도>,<이름>;<위도>,<경도>,<이름>`: 목적지는 그대로 두고 경유지를 이 순서로 다시 정한다 (NaviKit.setWaypoints)
 * - `port-add:<위도>,<경도>:<이름>`: 앱이 쓰는 길(KitNaviPort.addWaypoint)로 넣는다. 순서 규칙(WaypointOrder)까지 본다.
 *   앱을 다시 시작하기 전까지 같은 포트를 쓴다 (이 앱이 넣은 경유지를 기억한다)
 * - `route:<위도>,<경도>:<이름>` / `route-cancel`: 길안내 시작 / 끝
 * - `tts:<stop-mid|stop-idle|stop-then-speak|stop-early>`: 말하기를 끊어 보고 이벤트 순서를 본다
 * - `stt:<stop-then-request>`: 듣기를 끊고 곧바로 다시 듣는다
 * - `veh-read`: 차량 신호 몇 개를 읽는다 (서비스가 죽었을 때 무엇이 오는지)
 * - `veh-reconnect`: Vehicle SDK를 놓고 다시 붙인 뒤 읽는다
 */
class SdkProbe(
    private val vehicle: () -> VehicleKit,
    private val navi: () -> NaviKit,
    private val tts: () -> TtsKit,
    private val stt: () -> SttKit,
) {
    private val t0 = SystemClock.elapsedRealtime()

    suspend fun run(command: String) {
        val parts = command.split(":", limit = 3)
        val name = parts[0]
        log(name, "start $command")
        try {
            when (name) {
                "wp" -> waypoints(name)
                "wp-add" -> addWaypoint(name, WaypointIndex.valueOf(parts[1]), parts[2])
                "wp-rm" -> removeWaypoint(name, WaypointIndex.valueOf(parts[1]))
                "wp-set" -> setWaypoints(name, command.substringAfter(":"))
                "port-add" -> portAdd(name, parts[1], parts.getOrElse(2) { "경유지" })
                "route" -> route(name, parts[1], parts.getOrElse(2) { "목적지" })
                "route-cancel" -> log(name, "cancelled=${connectedNavi().cancelRoute()}")
                "tts" -> ttsProbe(name, parts.getOrElse(1) { "stop-mid" })
                "stt" -> sttProbe(name)
                "veh-read" -> vehicleRead(name)
                "veh-reconnect" -> {
                    vehicle().reconnect()
                    log(name, "reconnected")
                    vehicleRead(name)
                }
                else -> log(name, "unknown probe")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log(name, "FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
        log(name, "end")
    }

    // ------------------------------------------------------------ 내비

    private suspend fun connectedNavi(): NaviKit = navi().also { it.connect() }

    private fun describe(list: List<WaypointInfo>) =
        list.joinToString(prefix = "[", postfix = "]") { "${it.name}@%.5f,%.5f ${it.remainDistance}m".format(it.latitude, it.longitude) }

    private suspend fun waypoints(name: String) {
        val n = connectedNavi()
        log(name, "mode=${n.routeState().naviMode} waypoints=${describe(n.waypoints())}")
    }

    private fun latLon(text: String): Pair<Double, Double> = text.split(",").let { it[0].toDouble() to it[1].toDouble() }

    /** kit의 확인 규칙을 거치지 않고 SDK에 바로 보낸다: 그 자리(index)에 넣으면 목록이 어떻게 되는지 본다 */
    private suspend fun addWaypoint(name: String, index: WaypointIndex, rest: String) = coroutineScope {
        val (coords, poi) = rest.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "경유지" } }
        val (lat, lon) = latLon(coords)
        val n = connectedNavi()
        val events = launch {
            n.events.collect { e ->
                when (e) {
                    is NaviEvent.WaypointChanged -> log(name, "event WaypointChanged index=${e.info.index} cmd=${e.info.cmdType}")
                    is NaviEvent.Error -> log(name, "event Error ${e.code}")
                    NaviEvent.ReRouteEnded, NaviEvent.RoutingCompleted, NaviEvent.RouteCancelled, NaviEvent.DestinationChanged -> log(name, "event $e")
                    else -> Unit
                }
            }
        }
        val before = n.waypoints()
        log(name, "before=${describe(before)}")
        withContext(Dispatchers.IO) {
            n.raw.addWaypoint(RequestWaypointInfo(latitude = lat, longitude = lon, poiName = poi, waypointIndex = index, poiId = "", address = "", poiSubId = ""))
        }
        log(name, "sent index=$index $poi")
        var last = before
        repeat(10) {
            delay(2_000)
            val now = runCatching { n.waypoints() }.getOrNull() ?: return@repeat
            if (now != last) {
                log(name, "t=${ts()} waypoints=${describe(now)}")
                last = now
            }
        }
        log(name, "after=${describe(last)} mode=${n.routeState().naviMode}")
        events.cancel()
    }

    private suspend fun setWaypoints(name: String, spec: String) {
        val n = connectedNavi()
        val list = spec.split(";").map { one ->
            val f = one.split(",", limit = 3)
            RequestWaypointInfo(latitude = f[0].toDouble(), longitude = f[1].toDouble(), poiName = f[2], waypointIndex = WaypointIndex.FIRST, poiId = "", address = "", poiSubId = "")
        }
        val d = n.destination()
        log(name, "before=${describe(n.waypoints())} destination=${d.name}@${d.latitude},${d.longitude} ${d.remainDistance}m")
        val ok = n.setWaypoints(RouteInfo(latitude = d.latitude, longitude = d.longitude, poiName = d.name, poiId = "", address = d.address, poiSubId = ""), list)
        val after = n.destination()
        log(name, "confirmed=$ok after=${describe(n.waypoints())} destination=${after.name} ${after.remainDistance}m mode=${n.routeState().naviMode}")
    }

    private suspend fun portAdd(name: String, coords: String, poi: String) {
        val (lat, lon) = latLon(coords)
        val n = connectedNavi()
        log(name, "before=${describe(n.waypoints())}")
        val started = SystemClock.elapsedRealtime()
        val added = port(n).addWaypoint(lat, lon, poi)
        log(name, "added=$added in ${SystemClock.elapsedRealtime() - started} ms after=${describe(n.waypoints())}")
    }

    private suspend fun removeWaypoint(name: String, index: WaypointIndex) {
        val n = connectedNavi()
        log(name, "before=${describe(n.waypoints())}")
        n.removeWaypoint(index)
        delay(6_000)
        log(name, "after=${describe(n.waypoints())}")
    }

    private suspend fun route(name: String, coords: String, poi: String) {
        val (lat, lon) = latLon(coords)
        val ok = connectedNavi().startRoute(RouteInfo(latitude = lat, longitude = lon, poiName = poi, poiId = "", address = "", poiSubId = ""))
        log(name, "guiding=$ok")
    }

    // ------------------------------------------------------------ 음성 (SDK 이벤트를 그대로 본다)

    private suspend fun ttsProbe(name: String, scenario: String) {
        val kit = tts().also { it.connect() }
        val raw = kit.raw
        val listener = object : EventListener {
            override fun onReady() = log(name, "t=${ms()} event Ready")
            override fun onStart() = log(name, "t=${ms()} event Start")
            override fun onDone() = log(name, "t=${ms()} event Done")
            override fun onStop() = log(name, "t=${ms()} event Stop")
            override fun onError(errorType: TtsErrorType) = log(name, "t=${ms()} event Error $errorType")
            override fun onUpdatedRms(rms: Double) = Unit
        }
        val long = "지금부터 조금 긴 문장을 말합니다. 하나, 둘, 셋, 넷, 다섯, 여섯, 일곱, 여덟, 아홉, 열."
        fun call(what: String, block: () -> Unit) {
            log(name, "t=${ms()} call $what")
            block()
        }
        withContext(Dispatchers.IO) {
            raw.addEventListener(listener)
            try {
                when (scenario) {
                    "stop-mid" -> {
                        call("speak A") { raw.speak(long) }
                        delay(3_500)
                        call("stop") { raw.stop() }
                        delay(5_000)
                    }
                    "stop-idle" -> {
                        call("stop (nothing playing)") { raw.stop() }
                        delay(4_000)
                    }
                    "stop-then-speak" -> {
                        call("speak A") { raw.speak(long) }
                        delay(3_500)
                        call("stop") { raw.stop() }
                        call("speak B") { raw.speak("두 번째 문장입니다.") }
                        delay(12_000)
                    }
                    "stop-early" -> {
                        call("speak A") { raw.speak(long) }
                        delay(200)
                        call("stop") { raw.stop() }
                        delay(1_000)
                        call("speak B") { raw.speak("두 번째 문장입니다.") }
                        delay(12_000)
                    }
                    else -> log(name, "unknown scenario $scenario")
                }
            } finally {
                raw.removeEventListener(listener)
            }
        }
    }

    private suspend fun sttProbe(name: String) {
        val kit = stt().also { it.connect() }
        val raw = kit.raw
        val listener = object : ResultListener {
            override fun onReady() = log(name, "t=${ms()} event Ready")
            override fun onStartedRecognition() = log(name, "t=${ms()} event Started")
            override fun onUpdated(stt: String, completed: Boolean) = log(name, "t=${ms()} event Updated '$stt' completed=$completed")
            override fun onEndedRecognition() = log(name, "t=${ms()} event Ended")
            override fun onError(errorType: SttErrorType) = log(name, "t=${ms()} event Error $errorType")
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdatedRms(rms: Double) = Unit
        }
        fun call(what: String, block: () -> Unit) {
            log(name, "t=${ms()} call $what")
            block()
        }
        withContext(Dispatchers.IO) {
            raw.addListener(listener)
            try {
                call("request 1") { raw.request() }
                delay(1_500)
                call("stop") { raw.stop() }
                call("request 2") { raw.request() }
                delay(3_000)
                call("stop") { raw.stop() }
                delay(3_000)
                call("stop (nothing running)") { raw.stop() }
                delay(3_000)
            } finally {
                raw.removeListener(listener)
            }
        }
    }

    // ------------------------------------------------------------ 차량

    private suspend fun vehicleRead(name: String) {
        val v = vehicle()
        suspend fun read(what: String, block: suspend () -> Any?) {
            val started = SystemClock.elapsedRealtime()
            val result = try {
                "OK ${block()}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "FAIL ${e.javaClass.simpleName}: ${e.message} cause=${e.cause?.javaClass?.name}: ${e.cause?.message}"
            }
            log(name, "$what -> $result (${SystemClock.elapsedRealtime() - started} ms)")
        }
        read("connect") { v.connect() }
        read("brakePedal") { v.brakePedal().get() }
        read("steeringAngle") { v.steeringAngle().get() }
        read("doorAreas") { v.doorAreas().keys.size }
        read("tripDrivingTime (unsupported on the emulator)") { v.tripDrivingTimeMin().get() }
    }

    private fun ms() = SystemClock.elapsedRealtime() - t0
    private fun ts() = (SystemClock.elapsedRealtime() - t0) / 1000

    private fun log(probe: String, msg: String) {
        Log.i(TAG, "PROBE|$probe|$msg")
    }

    private companion object {
        const val TAG = "PleosApp"

        /** 실험 사이에 이어 쓰는 포트 (앱의 포트와는 다른 객체다: 앱이 넣은 경유지는 모른다) */
        private var sharedPort: KitNaviPort? = null
        fun port(navi: NaviKit): KitNaviPort = sharedPort ?: KitNaviPort(navi).also { sharedPort = it }
    }
}
