package dev.shimpyo.app.devtools

import ai.pleos.playground.llm.LLM
import ai.pleos.playground.llm.constant.Mode
import ai.pleos.playground.llm.listener.ResultListener
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.TripRequest
import android.content.Context
import android.util.Log
import dev.hmg.pleoskit.LocationKit
import dev.hmg.pleoskit.NaviKit
import dev.hmg.pleoskit.VehicleKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 쉼표 설계 전에 에뮬레이터에서 확인할 것들 (docs/product/SPEC.md 8장). 개발용.
 * 로그: `VERIFY|항목|내용` (tag PleosApp). 외부에서 같은 시각에 geo fix와 Virtual Console 조작을 한다.
 *
 * 시간표 (시작 기준 초): 0 리스너 등록 → 5 경로 시작(서울역→부산역) → 20~110 10초마다 정체·TBT·남은 거리
 * → 35/45 휴게소 두 방향 trip → 55 경유지 추가 → 120 LLM ON_DEVICE → 170 경로 취소
 */
class SdkVerify(
    private val context: Context,
    private val vehicle: VehicleKit,
    private val navi: NaviKit,
    private val location: LocationKit,
) {
    private val t0 = System.currentTimeMillis()

    suspend fun run() = coroutineScope {
        log("start", "t0=$t0")
        launch { item("steering.listen") { vehicle.steeringAngle().changes().collect { log("steering.event", "t=${ts()} angle=$it") } } }
        launch { item("turnSignal.listen") { vehicle.turnSignal().changes().collect { log("turnSignal.event", "t=${ts()} state=$it") } } }
        launch {
            item("location.tick") {
                runCatching { location.connect() }
                repeat(20) {
                    val fused = runCatching { location.lastKnown() }.getOrNull()
                    val nav = runCatching { navi.currentLocation() }.getOrNull()
                    log("location", "t=${ts()} fused=${fused?.let { "${it.latitude},${it.longitude}" }} navi=${nav?.let { "${it.latitude},${it.longitude} idx=${it.currentRouteIndex} ${it.address}" }}")
                    delay(10_000)
                }
            }
        }

        item("navi.connect") { navi.connect() }
        delay(5_000)
        item("route.start") {
            navi.startRoute(RouteInfo(latitude = 35.1151, longitude = 129.0422, poiName = "부산역", poiId = "", address = "부산광역시 동구 중앙대로 206", poiSubId = ""))
        }
        launch {
            repeat(10) {
                delay(10_000)
                item("routeState") {
                    val s = navi.routeState()
                    val ts = s.trafficStatus
                    buildString {
                        append("t=${ts()} mode=${s.naviMode} routing=${s.isRouting} option=${s.routeOption}")
                        append(" summary=${ts?.summary}")
                        append(" items=${ts?.congestionItemList?.size} events=${ts?.trafficEventList?.size}")
                        ts?.congestionItemList?.take(8)?.forEachIndexed { i, c ->
                            append(" | #$i off=${c.offset} len=${c.distance} time=${c.trafficCongestionTime} type=${c.trafficCongestionType} spd=${c.avrSpeed} road=${c.roadName} ${c.startCrossName}->${c.endCrossName}")
                        }
                    }
                }
                item("destination") { navi.destination().let { "t=${ts()} remainDist=${it.remainDistance} remainTime=${it.remainTime} name=${it.name}" } }
                item("tbt") { navi.tbt().take(12).joinToString(" ; ") { "${it.type}@${it.distance.toInt()} ${it.description}" } }
            }
        }
        delay(30_000)
        // 경부선 안성휴게소 (OpenStreetMap 좌표). 부산 방향은 경로 위, 서울 방향은 유턴이 필요하다
        item("trip.anseong.toBusan") { navi.tripEstimate(TripRequest(37.01354, 127.14516, "", "안성휴게소(부산방향)", "")).let { "dist=${it.distance} eta=${it.eta} traffic=${it.summary.trafficConditions.size} points=${it.summary.routePoints.size}" } }
        item("route.afterTrip1") { navi.routeState().naviMode }
        delay(10_000)
        item("trip.anseong.toSeoul") { navi.tripEstimate(TripRequest(37.07616, 127.13158, "", "안성휴게소(서울방향)", "")).let { "dist=${it.distance} eta=${it.eta}" } }
        item("route.afterTrip2") { navi.routeState().naviMode }
        delay(10_000)
        item("waypoint.add") {
            navi.addWaypoint(RequestWaypointInfo(latitude = 37.01354, longitude = 127.14516, poiName = "안성휴게소(부산방향)", waypointIndex = WaypointIndex.FIRST, poiId = "", address = "경기도 안성시 원곡면", poiSubId = ""))
        }
        item("waypoint.list") { navi.waypoints().joinToString { "${it.name} remain=${it.remainDistance}m/${it.remainTime}s" } }
        item("route.afterWaypoint") { navi.routeState().naviMode }
        delay(65_000 - (System.currentTimeMillis() - t0 - 55_000).coerceAtLeast(0))
        item("llm.onDevice") { llmProbe() }
        delay(30_000)
        item("route.cancel") { navi.cancelRoute() }
        log("end", "t=${ts()}")
    }

    /** Pleos LLM SDK ON_DEVICE 모드 응답 여부 */
    private suspend fun llmProbe(): String {
        val llm = withContext(Dispatchers.IO) { LLM(context, Mode.ON_DEVICE).also { it.initialize() } }
        // getOnDeviceModelPromptsContents()는 문서에는 있지만 2.1.3.2 jar에는 없다
        val done = CompletableDeferred<String>()
        val sb = StringBuilder()
        withContext(Dispatchers.IO) {
            llm.generateContent("한 단어로만 답해. 졸음운전을 막는 가장 좋은 방법은?", object : ResultListener {
                override fun onResponse(response: String, completed: Boolean) {
                    sb.append(response)
                    if (completed) done.complete("OK ${sb.toString().take(200)}")
                }
                override fun onError(reason: String) {
                    done.complete("ERROR $reason")
                }
            })
        }
        val result = withTimeoutOrNull(40_000) { done.await() } ?: "TIMEOUT partial=${sb.toString().take(100)}"
        runCatching { llm.release() }
        return result
    }

    private fun ts() = (System.currentTimeMillis() - t0) / 1000

    private suspend fun item(name: String, block: suspend () -> Any?) {
        try {
            log(name, "OK ${block()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log(name, "FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun log(item: String, msg: String) {
        Log.i(TAG, "VERIFY|$item|$msg")
    }

    private companion object {
        const val TAG = "PleosApp"
    }
}
