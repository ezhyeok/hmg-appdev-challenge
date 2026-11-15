package dev.shimpyo.app.coach

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.TrafficStatus
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.TripRequest
import android.util.Log
import dev.hmg.pleoskit.NaviEvent
import dev.hmg.pleoskit.NaviKit
import dev.shimpyo.coach.Fix
import dev.shimpyo.coach.NaviChange
import dev.shimpyo.coach.NaviCongestion
import dev.shimpyo.coach.NaviDestination
import dev.shimpyo.coach.NaviMode
import dev.shimpyo.coach.NaviPort
import dev.shimpyo.coach.NaviRouteStatus
import dev.shimpyo.coach.TbtItem
import dev.shimpyo.coach.TbtType
import dev.shimpyo.coach.TripEstimate
import dev.shimpyo.coach.WaypointResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 코치 계층의 [NaviPort]를 pleos-kit [NaviKit]으로 구현한다. SDK 데이터 클래스를 코치 도메인 타입(NaviModel.kt)으로
 * 바꾸는 일만 하고 판단은 하지 않는다. 단위: 거리 m, 시간 초.
 */
class KitNaviPort(private val navi: NaviKit) : NaviPort {

    override suspend fun connect() {
        navi.connect()
    }

    override suspend fun routeStatus(): NaviRouteStatus {
        val s = navi.routeState()
        s.trafficStatus?.let(::logCongestion)
        val raw = s.trafficStatus?.congestionItemList
        // trafficCongestionTime은 늘어난 시간이다. 통과 시간은 거리/평균 속도로 변환한다.
        val congestion = raw.orEmpty().mapNotNull { c ->
            NaviCongestion.fromNavi(
                offsetM = c.offset,
                lengthM = c.distance,
                delayS = c.trafficCongestionTime.toDouble(),
                avrSpeedKph = c.avrSpeed,
                type = c.trafficCongestionType.name,
                roadName = c.roadName,
                startName = c.startCrossName,
                endName = c.endCrossName,
            )
        }
        return NaviRouteStatus(
            mode = runCatching { NaviMode.valueOf(s.naviMode.name) }.getOrDefault(NaviMode.UNKNOWN),
            congestion = congestion,
            congestionAvailable = raw != null && congestion.size == raw.size,
        )
    }

    override suspend fun destination(): NaviDestination {
        val d = navi.destination()
        return NaviDestination(d.latitude, d.longitude, d.name, d.remainDistance.toDouble(), d.remainTime.toDouble())
    }

    override suspend fun tbt(): List<TbtItem> = navi.tbt().map { t ->
        TbtItem(
            type = when (t.type) {
                TurnType.TURN_ENTER_SERVICEAREA -> TbtType.SERVICE_AREA
                TurnType.TURN_RESTAREA -> TbtType.REST_AREA
                else -> TbtType.OTHER
            },
            distanceM = t.distance,
            description = t.description,
        )
    }

    override suspend fun currentLocation(): Fix {
        val c = navi.currentLocation()
        return Fix(c.latitude, c.longitude, c.heading.toFloat())
    }

    override suspend fun tripEstimate(lat: Double, lon: Double, name: String, timeoutMs: Long): TripEstimate {
        val t = navi.tripEstimate(TripRequest(lat, lon, "", name, ""), timeoutMs = timeoutMs)
        return TripEstimate(t.distance.toDouble(), t.eta.toDouble())
    }

    /**
     * 이 앱이 넣은 경유지 (이름 → 요청). 내비는 경유지의 좌표를 돌려주지 않아서(0.0), 순서를 다시 정할 때 좌표를 여기서 찾는다.
     * 프로세스가 사는 동안만 안다: 재시작 뒤에는 그 전에 넣은 경유지도 '밖에서 넣은 것'이다.
     */
    private val sent = ConcurrentHashMap<String, RequestWaypointInfo>()

    /**
     * 경유지 추가. Pleos 내비는 새 경유지를 늘 이미 있는 경유지 뒤에 붙인다 (waypointIndex와 상관없다, 2026-11-12 에뮬레이터).
     * 그래서 경유지가 하나 있는데 더 가까운 곳을 넣으면 먼 곳에 먼저 갔다가 되돌아오는 경로가 된다: 예전에 길안내 중
     * 급해 결과(판교의 주유소)를 넣었더니 47km 앞 휴게소 다음(99km)으로 들어갔고 앱은 '경유지로 넣었어요'라고 했다.
     * 경유지가 하나 있으면 새 곳까지의 거리를 재서 ([WaypointOrder])
     * - 그 경유지보다 뒤면 그대로 붙인다.
     * - 앞이고 그 경유지를 이 앱이 넣었으면(좌표를 안다) 목적지는 그대로 두고 경유지를 순서대로 다시 정한다 (NaviKit.setWaypoints).
     * - 앞인데 밖에서 넣은 경유지면(좌표를 모른다) 넣지 않는다(false): 지키지도 못하고, 붙이면 지나쳐 갔다 돌아온다.
     * 같은 곳이 이미 있으면 kit이 처리한다(true). 경유지가 이미 둘이면 보내지 않는다: 내비가 말없이 버린다(FULL, 2026-11-12).
     * 결과는 까닭과 함께 낸다(운전자에게 할 말이 다르다).
     */
    override suspend fun addWaypoint(lat: Double, lon: Double, name: String): WaypointResult {
        val request = RequestWaypointInfo(latitude = lat, longitude = lon, poiName = name, waypointIndex = WaypointIndex.FIRST, poiId = "", address = "", poiSubId = "")
        val list = runCatching { navi.waypoints() }.getOrNull()
        if (list != null && list.none { it.name == name } && list.size >= NaviKit.MAX_WAYPOINTS) {
            Log.i("Shimpyo", "waypoint $name: the navi already has ${list.size} waypoints (${list.joinToString { it.name }})")
            return WaypointResult.FULL
        }
        val only = list?.singleOrNull()
        if (only == null || only.name == name) return result(request, navi.addWaypoint(request))
        val newDistanceM = try {
            navi.tripEstimate(TripRequest(lat, lon, "", name, ""), timeoutMs = ORDER_TRIP_TIMEOUT_MS).distance.toDouble()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val existing = sent[only.name]
        val plan = WaypointOrder.plan(only.remainDistance.toDouble(), newDistanceM, existingKnown = existing != null)
        Log.i("Shimpyo", "waypoint $name: ${newDistanceM?.toLong()}m, existing ${only.name} ${only.remainDistance}m -> $plan")
        return when (plan) {
            WaypointOrder.Plan.APPEND -> result(request, navi.addWaypoint(request))
            WaypointOrder.Plan.INSERT_BEFORE -> {
                val d = navi.destination()
                val destination = RouteInfo(latitude = d.latitude, longitude = d.longitude, poiName = d.name, poiId = "", address = d.address, poiSubId = "")
                result(request, navi.setWaypoints(destination, listOf(request, existing!!)))
            }
            WaypointOrder.Plan.REFUSE -> WaypointResult.AHEAD_OF_OTHER
        }
    }

    private fun result(request: RequestWaypointInfo, added: Boolean): WaypointResult =
        if (remember(request, added)) WaypointResult.ADDED else WaypointResult.NOT_CONFIRMED

    private fun remember(request: RequestWaypointInfo, added: Boolean): Boolean {
        if (added) request.poiName?.let { sent[it] = request }
        return added
    }

    override suspend fun startRoute(lat: Double, lon: Double, name: String): Boolean =
        navi.startRoute(RouteInfo(latitude = lat, longitude = lon, poiName = name, poiId = "", address = "", poiSubId = ""))

    override suspend fun showPlaceSearch(keyword: String): Boolean = try {
        navi.showPlaceSearch(keyword)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    @Volatile private var lastCongestionLog: String? = null

    /**
     * 정체 확인 로그: 내비가 준 늘어난 시간(time)과 앱이 엔진에 넣는 지나는 시간(cross = distance × 3.6 / avrSpeed, NaviCongestion.crossingS).
     * 내용이 바뀔 때만, 앞 3개까지 남긴다 (2026-11-12 이 로그로 trafficCongestionTime이 지나는 시간이 아님을 확인했다)
     */
    private fun logCongestion(t: TrafficStatus) {
        val items = t.congestionItemList
        if (items.isEmpty()) return
        val sum = t.summary
        val line = buildString {
            append("congestion: summary dist=%.0fm time=%ds avr=%.1fkm/h cross=%s".format(sum.totalCongestionDist, sum.totalCongestionTime, sum.totalCongestionAvrSpeed, crossS(sum.totalCongestionDist, sum.totalCongestionAvrSpeed)))
            append(" n=${items.size}")
            items.take(3).forEach { c ->
                append(" | off=%.0fm dist=%.0fm time=%ds avr=%.1fkm/h cross=%s %s".format(c.offset, c.distance, c.trafficCongestionTime, c.avrSpeed, crossS(c.distance, c.avrSpeed), c.trafficCongestionType))
            }
        }
        if (line != lastCongestionLog) {
            lastCongestionLog = line
            Log.i("Shimpyo", line)
            // 모든 항목 (offset/거리/늘어난 시간/평균 속도): 엔진 도착 시간 영향을 서버에서 다시 계산할 수 있게
            Log.i("Shimpyo", "congestion items: " + items.joinToString(" ") { c -> "%.0f/%.0f/%d/%.1f".format(c.offset, c.distance, c.trafficCongestionTime, c.avrSpeed) })
        }
    }

    private companion object {
        /** 순서를 정하려고 새 경유지까지 거리를 잴 때 기다리는 시간 */
        const val ORDER_TRIP_TIMEOUT_MS = 15_000L
    }

    private fun crossS(distanceM: Double, avrKph: Double): String =
        NaviCongestion.crossingS(distanceM, avrKph, null)?.let { "%.0fs".format(it) } ?: "?"

    override val changes: Flow<NaviChange> = navi.events.mapNotNull { e ->
        when (e) {
            is NaviEvent.RouteStarted -> NaviChange.ROUTE_STARTED
            NaviEvent.RoutingCompleted -> NaviChange.ROUTING_COMPLETED
            NaviEvent.RouteCancelled -> NaviChange.ROUTE_CANCELLED
            NaviEvent.ReRouteEnded -> NaviChange.REROUTED
            NaviEvent.DestinationChanged -> NaviChange.DESTINATION_CHANGED
            is NaviEvent.WaypointChanged -> NaviChange.WAYPOINT_CHANGED
            is NaviEvent.WaypointArrived -> NaviChange.WAYPOINT_ARRIVED
            is NaviEvent.DestinationArrived -> NaviChange.DESTINATION_ARRIVED
            else -> null
        }
    }

    /** onCurrentRoadSpeedLimit (매초). 코치가 도시고속도로처럼 길가 화장실에 갈 수 없는 길을 가리는 데 쓴다 (NearbyStops) */
    override val roadSpeedLimits: Flow<Int> = navi.events.mapNotNull { (it as? NaviEvent.SpeedLimit)?.kph }
}

/**
 * 경유지가 하나 있을 때 새 경유지를 어떻게 넣을지 (순수 규칙, KitRulesTest). Pleos 내비는 새 경유지를 늘 뒤에 붙인다.
 */
object WaypointOrder {
    enum class Plan {
        /** 이미 있는 경유지 뒤다: 그대로 붙인다 */
        APPEND,
        /** 이미 있는 경유지보다 앞이고 그 좌표를 안다: 새 곳을 먼저, 있던 곳을 다음으로 다시 정한다 */
        INSERT_BEFORE,
        /** 앞인데 있던 경유지의 좌표를 모른다 (운전자가 내비에서 넣었다): 넣지 않는다 */
        REFUSE,
    }

    /** 이만큼은 앞이어야 '앞'으로 본다 (경로 거리와 직접 잰 거리의 차이, 휴게소 진입로) */
    const val BEFORE_MARGIN_M = 1_000.0

    /**
     * @param existingRemainM 있던 경유지까지 남은 경로 거리
     * @param newDistanceM 새 곳까지 잰 거리. 재지 못했으면 null: 순서를 알 수 없으니 예전처럼 붙인다
     * @param existingKnown 있던 경유지의 좌표를 아는지 (이 앱이 넣었는지)
     */
    fun plan(existingRemainM: Double, newDistanceM: Double?, existingKnown: Boolean): Plan = when {
        newDistanceM == null || newDistanceM.isNaN() -> Plan.APPEND
        newDistanceM >= existingRemainM - BEFORE_MARGIN_M -> Plan.APPEND
        existingKnown -> Plan.INSERT_BEFORE
        else -> Plan.REFUSE
    }
}
