package dev.shimpyo.app.coach

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.TripRequest
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
        return NaviRouteStatus(
            mode = runCatching { NaviMode.valueOf(s.naviMode.name) }.getOrDefault(NaviMode.UNKNOWN),
            congestion = s.trafficStatus?.congestionItemList.orEmpty().map { c ->
                NaviCongestion(
                    offsetM = c.offset,
                    lengthM = c.distance,
                    // trafficCongestionTime 단위는 초 (2026-11-11 확인)
                    durationS = c.trafficCongestionTime.toDouble(),
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
