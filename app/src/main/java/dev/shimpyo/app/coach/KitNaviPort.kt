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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

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
        return NaviRouteStatus(
            mode = runCatching { NaviMode.valueOf(s.naviMode.name) }.getOrDefault(NaviMode.UNKNOWN),
            // trafficCongestionTime은 지나는 시간이 아니라 분 단위로 반올림한 늘어난 시간이다(최소 1분, 2026-11-12 실측).
            // 엔진이 쓰는 '지나는 시간'은 거리 ÷ 평균 속도(avrSpeed, km/h)로 구한다 (NaviCongestion.crossingS)
            congestion = s.trafficStatus?.congestionItemList.orEmpty().mapNotNull { c ->
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
            },
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

    override suspend fun addWaypoint(lat: Double, lon: Double, name: String): Boolean =
        navi.addWaypoint(RequestWaypointInfo(latitude = lat, longitude = lon, poiName = name, waypointIndex = WaypointIndex.FIRST, poiId = "", address = "", poiSubId = ""))

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
        }
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
}
