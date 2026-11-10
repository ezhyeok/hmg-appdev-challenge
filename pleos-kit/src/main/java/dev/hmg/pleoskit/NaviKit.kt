package dev.hmg.pleoskit

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
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
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 내비 앱이 보내는 이벤트. [NaviKit.events]로 받는다. */
sealed interface NaviEvent {
    data class CurrentLocation(val info: CurrentLocationInfo) : NaviEvent
    data class Destination(val info: DestinationInfo) : NaviEvent
    data class RouteState(val info: RouteStateInfo) : NaviEvent
    data class Tbt(val list: List<TBTInfo>) : NaviEvent
    data class Waypoints(val list: List<WaypointInfo>) : NaviEvent
    data class Trip(val info: TripInfo) : NaviEvent
    data class Driving(val info: DrivingInfo) : NaviEvent
    data class Status(val status: NavigationStatus) : NaviEvent
    data class SpeedLimit(val kph: Int) : NaviEvent
    data class RouteStarted(val info: RouteStartInfo) : NaviEvent
    data object RoutingCompleted : NaviEvent
    data object RouteCancelled : NaviEvent
    data object ReRouteEnded : NaviEvent
    data object DestinationChanged : NaviEvent
    data class DestinationArrived(val info: DestinationArrivedInfo) : NaviEvent
    data class WaypointChanged(val info: WaypointChangedInfo) : NaviEvent
    data class WaypointArrived(val info: WaypointArrivedInfo) : NaviEvent
    data class Error(val code: NaviErrorCode) : NaviEvent
}

/**
 * NaviHelper SDK를 코루틴으로 감싼 진입점. 요청은 Pleos 내비 앱으로 전달되고 응답은 이벤트로 온다.
 *
 * 에뮬레이터에서 확인한 동작을 반영했다.
 * - 조회(`get*`)는 호출해야 응답 이벤트가 온다. 요청과 응답을 짝짓는 ID가 없으므로 한 번에 하나씩만 보낸다.
 * - 경로 요청을 연달아 보내면 시작된 경로가 곧바로 취소되는 일이 있었다. 명령 사이에 [COMMAND_GAP_MS]만큼 간격을 둔다.
 * - `onRouteStarted`는 앱에 오지 않았다. 길안내 시작 여부는 [routeState]의 `naviMode`로 확인한다.
 * - `requestRoute`를 보내면 내비 앱이 화면 앞으로 온다. 이벤트를 계속 받으려면 포그라운드 서비스에서 써야 한다.
 *
 * 권한: 조회는 `pleos.car.permission.NAVI_ROUTE`, 경로 변경은 `NAVI_ROUTE_SEARCH`.
 */
class NaviKit(context: Context) {

    /** SDK 원본 객체. kit에 없는 API(즐겨찾기, 최근 목적지, 화면 전환 등)에 쓴다. */
    val raw = NaviHelper(context.applicationContext)

    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 내비가 보내는 모든 이벤트. 구독하기 전에 온 이벤트는 받지 못한다. */
    val events: SharedFlow<NaviEvent> = _events

    private val mutex = Mutex()
    private val connectMutex = Mutex()
    private var lastCommandAt = 0L

    @Volatile
    private var connected = false

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = emit(NaviEvent.CurrentLocation(currentLocationInfo))
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = emit(NaviEvent.Destination(destinationInfo))
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = emit(NaviEvent.RouteState(routeStateInfo))
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = emit(NaviEvent.Tbt(tbtInfoList))
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = emit(NaviEvent.Waypoints(waypointInfoList))
        override fun onTripInfo(tripInfo: TripInfo) = emit(NaviEvent.Trip(tripInfo))
        override fun onDrivingInfo(drivingInfo: DrivingInfo) = emit(NaviEvent.Driving(drivingInfo))
        override fun onNavigationStatus(status: NavigationStatus) = emit(NaviEvent.Status(status))
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = emit(NaviEvent.SpeedLimit(speedLimit))
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = emit(NaviEvent.RouteStarted(routeStartInfo))
        override fun onRoutingCompleted() = emit(NaviEvent.RoutingCompleted)
        override fun onRouteCancelled() = emit(NaviEvent.RouteCancelled)
        override fun onReRouteEnded() = emit(NaviEvent.ReRouteEnded)
        override fun onDestinationChanged() = emit(NaviEvent.DestinationChanged)
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = emit(NaviEvent.DestinationArrived(destinationArrivedInfo))
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = emit(NaviEvent.WaypointChanged(waypointChangedInfo))
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = emit(NaviEvent.WaypointArrived(waypointArrivedInfo))
        override fun onError(errorCode: NaviErrorCode) = emit(NaviEvent.Error(errorCode))
    }

    private fun emit(event: NaviEvent) {
        _events.tryEmit(event)
    }

    /** 내비 앱에 연결하고 리스너를 등록한다. 다른 호출보다 먼저 한 번 부른다. */
    suspend fun connect(timeoutMs: Long = 30_000) = connectMutex.withLock {
        if (connected) return@withLock
        val ok = withTimeoutOrNull(timeoutMs) {
            withContext(SdkThreads.navi) {
                suspendCancellableCoroutine { cont ->
                    val done = AtomicBoolean(false)
                    raw.initialize(
                        onConnected = { if (done.compareAndSet(false, true)) cont.resume(Unit) },
                        onFailed = { t ->
                            if (done.compareAndSet(false, true)) {
                                cont.resumeWithException(PleosSdkException("NaviHelper.initialize", t.message ?: "failed", t))
                            }
                        },
                    )
                }
                raw.addListener(listener)
            }
        }
        if (ok == null) throw PleosSdkException("NaviHelper.initialize", "no response in ${timeoutMs}ms")
        connected = true
    }

    fun release() {
        SdkThreads.navi.executor.execute {
            runCatching { raw.removeListener(listener) }
            runCatching { raw.release() }
            connected = false
        }
    }

    // ------------------------------------------------------------ 조회 (NAVI_ROUTE)

    /** 내비가 보는 현재 위치와 주소. */
    suspend fun currentLocation(): CurrentLocationInfo =
        mutex.withLock { query<NaviEvent.CurrentLocation>("getCurrentLocationInfo") { raw.getCurrentLocationInfo() } }.info

    /** 현재 목적지. 길안내 중이 아니면 이름이 빈 값으로 온다. */
    suspend fun destination(): DestinationInfo =
        mutex.withLock { query<NaviEvent.Destination>("getDestinationInfo") { raw.getDestinationInfo() } }.info

    /** 내비 모드(`ROUTE_GUIDING`이면 길안내 중), 경로 옵션, 교통 요약. */
    suspend fun routeState(): RouteStateInfo = mutex.withLock { routeStateLocked() }

    /** 앞으로 남은 회전 안내 목록. 길안내 중이 아니면 빈 목록. */
    suspend fun tbt(): List<TBTInfo> = mutex.withLock { query<NaviEvent.Tbt>("getTBTInfo") { raw.getTBTInfo() } }.list

    /** 현재 경유지 목록. */
    suspend fun waypoints(): List<WaypointInfo> = mutex.withLock { waypointsLocked() }

    suspend fun isGuiding(): Boolean = routeState().naviMode == NaviMode.ROUTE_GUIDING

    /**
     * 길안내를 시작하지 않고 목적지까지의 거리(m), 소요 시간(초), 도착 시 예상 SOC(%)를 받는다.
     * 경로를 찾지 못하면 [PleosSdkException].
     */
    suspend fun tripEstimate(request: TripRequest, timeoutMs: Long = 30_000): TripInfo = mutex.withLock {
        val event = awaitEvent("requestTrip", timeoutMs, { it is NaviEvent.Trip || it is NaviEvent.Error }) {
            raw.requestTrip(request)
        }
        when (event) {
            is NaviEvent.Trip -> event.info
            is NaviEvent.Error -> throw PleosSdkException("requestTrip", event.code.name)
            else -> error("unreachable")
        }
    }

    // ------------------------------------------------------------ 경로 변경 (NAVI_ROUTE_SEARCH)

    /**
     * 목적지로 길안내를 시작한다. 내비 앱이 화면 앞으로 온다.
     * [confirmTimeoutMs] 안에 `naviMode`가 `ROUTE_GUIDING`이 되면 true.
     */
    suspend fun startRoute(destination: RouteInfo, confirmTimeoutMs: Long = 45_000): Boolean = mutex.withLock {
        command("requestRoute") { raw.requestRoute(destination) }
        awaitGuiding(true, confirmTimeoutMs)
    }

    /** 목적지와 경유지(최대 2개)를 한 번에 지정해 길안내를 시작한다. */
    suspend fun startRouteWithWaypoints(request: RouteWithWaypointsRequest, confirmTimeoutMs: Long = 45_000): Boolean =
        mutex.withLock {
            command("requestRouteWithWaypoints") { raw.requestRouteWithWaypoints(request) }
            awaitGuiding(true, confirmTimeoutMs)
        }

    /**
     * 길안내 중인 경로에 경유지를 추가한다. 경유지 목록에 반영되면 true.
     * 좌표와 이름은 앱이 직접 구해야 한다(NaviHelper에는 POI 검색이 없다).
     */
    suspend fun addWaypoint(waypoint: RequestWaypointInfo, confirmTimeoutMs: Long = 30_000): Boolean = mutex.withLock {
        val before = runCatching { waypointsLocked().size }.getOrDefault(0)
        command("addWaypoint") { raw.addWaypoint(waypoint) }
        pollUntil(confirmTimeoutMs) { waypointsLocked().size > before }
    }

    /** 경유지를 지운다. */
    suspend fun removeWaypoint(index: WaypointIndex) = mutex.withLock {
        command("removeWaypoint") { raw.removeWaypoint(index) }
    }

    /** 길안내를 끝낸다. `naviMode`가 `ROUTE_GUIDING`에서 벗어나면 true. */
    suspend fun cancelRoute(confirmTimeoutMs: Long = 20_000): Boolean = mutex.withLock {
        command("cancelRoute") { raw.cancelRoute() }
        awaitGuiding(false, confirmTimeoutMs)
    }

    // ------------------------------------------------------------ 내부

    private suspend fun routeStateLocked(): RouteStateInfo =
        query<NaviEvent.RouteState>("getRouteStateInfo") { raw.getRouteStateInfo() }.info

    private suspend fun waypointsLocked(): List<WaypointInfo> =
        query<NaviEvent.Waypoints>("getWaypointInfo") { raw.getWaypointInfo() }.list

    private suspend fun awaitGuiding(guiding: Boolean, timeoutMs: Long): Boolean =
        pollUntil(timeoutMs) { (routeStateLocked().naviMode == NaviMode.ROUTE_GUIDING) == guiding }

    /** [check]가 true를 줄 때까지 2초 간격으로 다시 확인한다. 조회 실패는 다음 시도로 넘긴다. */
    private suspend fun pollUntil(timeoutMs: Long, check: suspend () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (runCatching { check() }.getOrDefault(false)) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(2000)
        }
    }

    /** 경로를 바꾸는 요청을 보낸다. 직전 명령과 [COMMAND_GAP_MS] 이상 간격을 둔다. */
    private suspend fun command(name: String, send: () -> Unit) {
        val wait = lastCommandAt + COMMAND_GAP_MS - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        try {
            withContext(SdkThreads.navi) { send() }
        } catch (e: Exception) {
            throw PleosSdkException(name, e.message ?: e.javaClass.simpleName, e)
        } finally {
            lastCommandAt = SystemClock.elapsedRealtime()
        }
    }

    private suspend inline fun <reified E : NaviEvent> query(name: String, noinline send: () -> Unit): E =
        awaitEvent(name, QUERY_TIMEOUT_MS, { it is E }, send) as E

    /** 이벤트 구독을 먼저 걸고 요청을 보낸 뒤, [match]에 맞는 첫 이벤트를 기다린다. */
    private suspend fun awaitEvent(
        name: String,
        timeoutMs: Long,
        match: (NaviEvent) -> Boolean,
        send: () -> Unit,
    ): NaviEvent = coroutineScope {
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { _events.first(match) }
        try {
            withContext(SdkThreads.navi) { send() }
        } catch (e: Exception) {
            waiter.cancel()
            throw PleosSdkException(name, e.message ?: e.javaClass.simpleName, e)
        }
        val event = withTimeoutOrNull(timeoutMs) { waiter.await() }
        if (event == null) {
            waiter.cancel()
            throw PleosSdkException(name, "no response in ${timeoutMs}ms")
        }
        event
    }

    companion object {
        /** 경로 변경 명령 사이의 최소 간격. */
        const val COMMAND_GAP_MS = 3000L
        const val QUERY_TIMEOUT_MS = 15_000L
    }
}
