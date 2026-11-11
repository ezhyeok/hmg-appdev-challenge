package dev.shimpyo.coach

import dev.shimpyo.engine.RouteState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pleos 내비 조회를 혼자 맡아 [snapshot] 하나로 내놓는다 (예전 CoachService.collectNavi·refreshRoute, 구조 리뷰 3.4).
 *
 * 조회 간격 (NaviPoll이 정하고 NaviPollTest가 지킨다):
 * - 경로 상태(길안내 여부·정체)와 목적지: 15초. 실패하면 5초 뒤 다시.
 * - TBT: 경로가 있는 길안내 중에만. 5초, 다음 화장실·회전이 멀고 주행 거리 보정을 믿을 수 있으면 20초.
 *   길안내 시작·경로 변경·[requestRefresh] 뒤 30초는 5초. SAFE_GUIDING은 경로가 없으니 조회하지 않는다.
 * - 내비 이벤트(NaviChange)나 [requestRefresh]가 오면 주기를 기다리지 않고 곧바로 다시 읽는다 (서비스 감사 P4).
 *
 * epoch: 경로가 바뀌면 늘린다. 길안내 시작, 끝(취소·도착), 재탐색(남은 거리로 판정한 것과 내비 이벤트), 목적지 변경,
 * 경유지 변경, 경로를 바꾼 요청([requestRefresh]). 새 epoch의 스냅샷은 TBT를 비운 채 나가고 TBT를 곧바로 다시 받는다.
 * 옛 경로의 TBT는 새 경로와 짝이 되지 않는다 (구조 리뷰 D13):
 * - TBT를 조회하는 동안 경로 변경 이벤트가 오면 그 응답은 버린다.
 * - 경로 변경을 알았는데 경로 상태를 아직 다시 못 읽었으면 TBT를 조회하지 않는다.
 * - 스냅샷의 tbtEpoch가 epoch와 다르면 NaviSnapshot이 TBT 값을 쓰지 않는다.
 *
 * 답 없는 내비: 경로 상태 조회가 [ROUTE_STALE_MS](1분) 동안 이어서 실패하면 지난 스냅샷을 더 믿지 않고 '모름'(route = null)을 낸다.
 * 그 전의 잠깐 실패에는 지난 스냅샷을 그대로 둔다. 다시 답하면 곧바로 회복하고, 길안내 중이면 새 epoch로 시작한다 (서비스 감사 SV-N5).
 *
 * 스레드: [run]과 [pollOnce]는 한 코루틴(코치 디스패처)에서만 돈다. 아래 private 상태는 그 코루틴만 쓴다.
 * [requestRefresh]와 내비 이벤트는 원자 플래그와 CONFLATED 채널로만 루프에 알리므로 어느 스레드에서 불러도 되고 막히지 않는다.
 * 스냅샷은 한 번에 통째로 바꾼다. 읽는 쪽이 새 경로와 옛 TBT, 옛 목적지를 섞어 읽을 수 없다.
 *
 * [odometer]: 코치 틱이 차속으로 늘리는 주행 거리. [speedKnown]: 차속을 아는지 (모르면 주행 거리로 거리를 줄일 수 없다).
 * [locationFallback]: 위치 SDK가 끊겼을 때 내비 위치를 물을지 (PositionTracker.naviFallback). null이면 묻지 않는다.
 */
class NaviTracker(
    private val navi: NaviPort,
    private val odometer: Odometer,
    private val speedKnown: () -> Boolean,
    private val clock: ElapsedClock,
    private val log: CoachLog = CoachLog.NONE,
    private val locationFallback: LocationFallback? = null,
) {
    private val state = MutableStateFlow(NaviSnapshot())
    val snapshot: StateFlow<NaviSnapshot> = state.asStateFlow()

    // ---- 다른 코루틴이 보내는 요청. pollOnce가 시작할 때 한꺼번에 가져간다
    private val pending = AtomicInteger(0)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // ---- 여기부터는 run()/pollOnce() 코루틴만 쓴다
    internal val congestion = CongestionBasis()
    private var nextRouteAt = 0L
    /** 경로가 바뀌었다는 요청·이벤트를 받았고 아직 경로 상태를 다시 못 읽었다 */
    private var routeChangePending = false
    private var tbtForced = false
    /** 이번 epoch에서 마지막으로 TBT를 받은(요청한) 때. null이면 아직 못 받았다 */
    private var lastTbtAt: Long? = null
    private var tbtRetryAt: Long? = null
    /** 이때까지는 TBT를 5초마다 받는다 (NaviPoll.FAST_AFTER_RESET_MS) */
    private var fastUntil = Long.MIN_VALUE
    private var prevRemainM: Double? = null
    private var prevRemainOdometerM = 0.0
    /** 내비가 서 있다 (NaviPoll.naviFrozen): TBT를 경로 조회만큼만 묻는다 */
    private var naviFrozen = false
    private var lastRequestAt: Long? = null
    private val failures = HashMap<String, Int>()
    /** 경로 상태 조회가 이어서 실패하기 시작한 때 (첫 실패한 조회를 보낸 때). 답을 받으면 null */
    private var routeFailingSince: Long? = null
    /** 이번 실패에서 '모름'을 이미 냈다 (한 번만 내고 한 번만 알린다) */
    private var routeUnknown = false

    /**
     * 내비를 곧바로 다시 읽게 한다 (수락해서 경유지를 넣거나 길안내를 시작한 뒤). 경로·TBT를 바로 받고 30초는 TBT를 5초마다 받는다.
     * [routeChanged]: 경로를 바꾼 뒤라 옛 TBT를 쓰면 안 된다 (다음 경로 조회에서 epoch를 늘린다). 값만 새로 받으려면 false.
     * 어느 스레드에서 불러도 되고 기다리지 않는다.
     */
    fun requestRefresh(routeChanged: Boolean = true) = request(REFRESH or (if (routeChanged) CHANGED else 0))

    /** 내비가 먼저 보낸 변화 (run이 navi.changes에서 받아 부른다) */
    internal fun onChange(change: NaviChange) {
        log.i("navi event: $change")
        request(
            when (change) {
                // 경로 탐색 시작(경로는 아직), 경유지 도착(경로는 그대로 이어진다): 다시 읽기만
                NaviChange.ROUTE_STARTED, NaviChange.WAYPOINT_ARRIVED -> REFRESH
                NaviChange.ROUTING_COMPLETED, NaviChange.ROUTE_CANCELLED, NaviChange.REROUTED,
                NaviChange.DESTINATION_CHANGED, NaviChange.WAYPOINT_CHANGED, NaviChange.DESTINATION_ARRIVED -> REFRESH or CHANGED
            },
        )
    }

    private fun request(flags: Int) {
        pending.getAndUpdate { it or flags }
        wake.trySend(Unit)
    }

    /**
     * 연결하고(실패하면 2초부터 두 배씩 최대 1분 간격으로 다시, 서비스 감사 D13) 내비 이벤트를 받으면서 [pollOnce]를 되풀이한다.
     * 다음 조회 때까지 자다가 요청·이벤트가 오면 깬다. 취소될 때까지 돌아온다.
     */
    suspend fun run(): Unit = coroutineScope {
        connect()
        launch { watchChanges() }
        while (true) {
            try {
                pollOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("navi poll failed: ${e.message ?: e.javaClass.simpleName}")
            }
            withTimeoutOrNull(nextWakeInMs()) { wake.receive() }
            // 요청·이벤트가 몰려도 내비 조회는 1초에 한 번까지 (연달아 보내면 내비가 경로를 취소한 일이 있다, CLAUDE.md)
            lastRequestAt?.let { val gap = it + MIN_REQUEST_GAP_MS - clock.elapsedMs(); if (gap > 0) delay(gap) }
        }
    }

    /** 루프 한 번: 할 때가 된 조회만 하고 기다리지 않는다. 단위 테스트의 입구 */
    suspend fun pollOnce() {
        val now = clock.elapsedMs()
        val req = pending.getAndSet(0)
        if (req and CHANGED != 0) routeChangePending = true
        if (req and FAST != 0) fastUntil = maxOf(fastUntil, now + NaviPoll.FAST_AFTER_RESET_MS)
        if (req and TBT != 0) tbtForced = true
        if (req and ROUTE != 0 || now >= nextRouteAt) pollRoute(now)

        val s = state.value
        if (!s.guiding) {
            tbtForced = false
        } else if (!routeChangePending) {
            val t = clock.elapsedMs()
            val due = tbtDueAt(t, s)
            if (due != null && t >= due) pollTbt()
        }
        locationFallback?.let { if (it.wanted()) pollLocation(it) }
    }

    // ------------------------------------------------------------ 경로 상태

    private suspend fun pollRoute(startedAt: Long) {
        val status = call("routeStatus") { navi.routeStatus() }
        if (status == null) {
            val now = clock.elapsedMs()
            nextRouteAt = now + NaviPoll.NEAR_MS
            routeFailed(startedAt, now)
            return
        }
        val silentFor = routeFailingSince?.let { clock.elapsedMs() - it }
        val wasUnknown = routeUnknown
        routeFailingSince = null
        routeUnknown = false
        if (wasUnknown) log.i("navi answering again after ${silentFor!! / 1000}s")
        val prev = state.value
        if (status.mode != prev.mode) log.i("navi mode: ${prev.mode} -> ${status.mode}")
        if (!NaviPoll.isGuiding(status.mode)) {
            toIdle(prev, status.mode)
            nextRouteAt = startedAt + NaviPoll.ROUTE_MS
            return
        }
        val odoAt = odometer.meters // 내비는 요청 시점의 위치에서 잰다
        val dest = call("destination") { navi.destination() }
        // 길안내 모드인데 남은 거리 0은 도착이 아니라 깨진 응답이다(도착하면 모드가 바뀐다). 2026-11-12 에뮬레이터에서 응답 하나만
        // 0으로 왔다가 돌아와 재탐색이 두 번 잡혔다. 못 읽은 것으로 본다 (같은 경로면 지난 값을 둔다)
        val remain = dest?.remainDistanceM?.takeIf { it.isFinite() && it > 0.0 }
        val usable = remain != null
        nextRouteAt = startedAt + if (usable) NaviPoll.ROUTE_MS else NaviPoll.NEAR_MS

        val why = when {
            // 답이 없던 사이 무슨 일이 있었는지(재탐색, 경유지) 모른다: 같은 목적지여도 새 경로로 다시 시작한다
            wasUnknown -> "guidance seen again after the navi was silent"
            !prev.guiding -> "guidance started"
            routeChangePending -> "route changed (request or navi event)"
            usable && destinationMoved(prev.destination, dest) -> "destination changed"
            else -> null
        }
        if (why != null) {
            newEpoch(prev, status, if (usable) dest else null, remain, odoAt, why)
            return
        }
        // 같은 경로인데 목적지를 못 읽었다: 지난 값을 그대로 두고(liveRoute가 계속 줄여 쓴다) 5초 뒤 다시 읽는다
        if (!usable) return
        val p = NaviPoll.checkProgress(prevRemainM, prevRemainOdometerM, remain, odoAt, prev.odometerTrusted)
        if (p.rerouted) {
            newEpoch(prev, status, dest, remain, odoAt, "rerouted (remain ${prevRemainM?.toInt()} -> ${remain!!.toInt()} m)")
            return
        }
        val frozen = NaviPoll.naviFrozen(prevRemainM, prevRemainOdometerM, remain, odoAt, naviFrozen)
        if (frozen != naviFrozen) log.i(if (frozen) "navi frozen: tbt at the route pace" else "navi moving again: tbt every 5 s")
        naviFrozen = frozen
        prevRemainM = remain
        prevRemainOdometerM = odoAt
        publish(
            prev.copy(
                mode = status.mode,
                route = routeState(status, dest!!, remain, odoAt),
                routeOdometerM = odoAt,
                destination = dest,
                odometerTrusted = p.trusted,
            ),
        )
    }

    /** 경로가 바뀌었다: TBT·보정 신뢰·정체 판정을 새로 시작하고 TBT를 곧바로 받는다 */
    private fun newEpoch(prev: NaviSnapshot, status: NaviRouteStatus, dest: NaviDestination?, remain: Double?, odoAt: Double, why: String) {
        val epoch = prev.epoch + 1
        log.i("navi epoch $epoch: $why")
        routeChangePending = false
        naviFrozen = false
        // 경로 시작을 봤는지: 처음 조회부터 길안내 중이면(앱이 길안내 중간에 켜짐) 출발지를 모른다
        congestion.start(remain, originKnown = prev.route != null)
        prevRemainM = remain
        prevRemainOdometerM = odoAt
        fastUntil = maxOf(fastUntil, clock.elapsedMs() + NaviPoll.FAST_AFTER_RESET_MS)
        tbtForced = true
        lastTbtAt = null
        tbtRetryAt = null
        publish(
            NaviSnapshot(
                epoch = epoch,
                mode = status.mode,
                route = if (dest != null) routeState(status, dest, remain, odoAt) else RouteState(guiding = true, congestion = congestion.update(status.congestion, null, odoAt)),
                routeOdometerM = odoAt,
                destination = dest,
                tbtOdometerM = odoAt,
                tbtEpoch = epoch,
                odometerTrusted = false,
            ),
        )
    }

    /** 경로 없는 상태(SAFE_GUIDING·초기화 중 등): 경로·TBT·보정 신뢰를 모두 지운다 */
    private fun toIdle(prev: NaviSnapshot, mode: NaviMode) {
        if (prev.guiding) log.i("navi epoch ${prev.epoch + 1}: guidance ended")
        forgetRoute(prev, mode, RouteState(guiding = false))
    }

    /**
     * 경로 상태 조회가 실패했다. 실패가 [ROUTE_STALE_MS] 동안 이어졌으면 지난 스냅샷(길안내·목적지·TBT)을 더 믿지 않는다:
     * 경로를 모르는 상태(route = null, 길안내 아님과 다르다)를 한 번 내고 한 번 알린다. 예전에는 내비가 죽어도 몇 분 전 경로를
     * 지금 것으로 계속 내보냈다 (서비스 감사 SV-N5). 그 전까지는 지난 스냅샷을 둔다: 잠깐의 실패에 엔진 입력이 깜박이지 않게.
     * [startedAt]: 실패한 조회를 보낸 때. 무응답(호출마다 20초)과 빠른 실패(5초마다)를 같은 시간으로 재려고 실패 수가 아니라 시간으로 본다.
     */
    private fun routeFailed(startedAt: Long, now: Long) {
        val since = routeFailingSince ?: startedAt.also { routeFailingSince = it }
        if (routeUnknown || now - since < ROUTE_STALE_MS) return
        routeUnknown = true
        val prev = state.value
        log.w("navi silent for ${(now - since) / 1000}s: route unknown" + if (prev.guiding) " (epoch ${prev.epoch + 1})" else "")
        // 처음부터 답이 없었으면 스냅샷은 이미 '모름'이다
        if (prev.route != null) forgetRoute(prev, NaviMode.UNKNOWN, route = null)
    }

    /** 경로·목적지·TBT·보정 신뢰를 지운 스냅샷을 낸다. 길안내 중이었으면 epoch를 올린다 (옛 경로에서 만든 것이 다음 경로와 섞이지 않게) */
    private fun forgetRoute(prev: NaviSnapshot, mode: NaviMode, route: RouteState?) {
        val epoch = if (prev.guiding) prev.epoch + 1 else prev.epoch
        routeChangePending = false
        congestion.clear()
        prevRemainM = null
        naviFrozen = false
        tbtForced = false
        lastTbtAt = null
        tbtRetryAt = null
        val odo = odometer.meters
        publish(NaviSnapshot(epoch = epoch, mode = mode, route = route, routeOdometerM = odo, tbtOdometerM = odo, tbtEpoch = epoch))
    }

    private fun routeState(status: NaviRouteStatus, dest: NaviDestination, remain: Double?, odoAt: Double) = RouteState(
        guiding = true,
        remainDistanceM = remain,
        remainTimeS = dest.remainTimeS.takeIf { it.isFinite() && it >= 0.0 },
        congestion = congestion.update(status.congestion, remain, odoAt),
    )

    /** 목적지가 바뀌었는지. 좌표를 알면 좌표로(100m 넘게), 모르면 이름으로 */
    private fun destinationMoved(a: NaviDestination?, b: NaviDestination?): Boolean {
        if (a == null || b == null) return false
        val known = { d: NaviDestination -> d.lat.isFinite() && d.lon.isFinite() && !(d.lat == 0.0 && d.lon == 0.0) }
        if (known(a) && known(b)) return Geo.distanceM(a.lat, a.lon, b.lat, b.lon) > DESTINATION_MOVED_M
        return a.name.isNotBlank() && b.name.isNotBlank() && a.name.trim() != b.name.trim()
    }

    // ------------------------------------------------------------ TBT

    /** 다음 TBT 조회 시각. 길안내가 아니면 null */
    private fun tbtDueAt(now: Long, s: NaviSnapshot): Long? {
        if (!s.guiding) return null
        if (tbtForced) return now
        tbtRetryAt?.let { return it }
        val last = lastTbtAt ?: return now // 이번 경로의 TBT를 아직 못 받았다
        val odo = odometer.meters
        val interval = NaviPoll.tbtIntervalMs(
            nextOpportunityM = s.liveOpportunities(odo).minOfOrNull { it.routeDistanceM },
            nextManeuverM = s.liveRoute(odo)?.nextManeuverDistanceM,
            speedKnown = speedKnown(),
            trusted = s.odometerTrusted,
            afterReset = now < fastUntil,
            naviFrozen = naviFrozen,
        )
        return last + interval
    }

    private suspend fun pollTbt() {
        val startedAt = clock.elapsedMs()
        val asked = state.value
        val odoAt = odometer.meters
        val items = call("tbt") { navi.tbt() }
        if (items == null) {
            tbtRetryAt = clock.elapsedMs() + NaviPoll.NEAR_MS
            tbtForced = false
            return
        }
        val now = state.value
        // 묻는 사이 경로가 바뀌었다는 이벤트가 왔으면 이 응답은 옛 경로의 것일 수 있다. 버리고 곧 다시 받는다
        if (now.epoch != asked.epoch || !now.guiding || pending.get() and CHANGED != 0) {
            log.i("navi tbt dropped: route changed while asking")
            return
        }
        val d = TbtDigest.of(items)
        if (d.truncated) log.w("navi tbt: bad distance after ${TbtDigest.readable(items).size}/${items.size} items, read up to ${d.coverageM.toInt()} m")
        publish(
            now.copy(
                opportunities = d.opportunities,
                nextManeuverM = d.nextManeuverM,
                tbtReceived = true,
                tbtCoverageM = d.coverageM,
                tbtOdometerM = odoAt,
                tbtEpoch = now.epoch,
            ),
        )
        lastTbtAt = startedAt
        tbtRetryAt = null
        tbtForced = false
    }

    // ------------------------------------------------------------ 위치 대체

    private suspend fun pollLocation(fallback: LocationFallback) {
        val at = clock.elapsedMs()
        call("currentLocation") { navi.currentLocation() }?.let { fallback.onFix(it, at) }
    }

    // ------------------------------------------------------------ 연결·이벤트·호출

    /** 내비에 붙을 때까지: 실패하거나 [CONNECT_TIMEOUT_MS] 안에 답이 없으면 간격을 늘려 가며 다시 한다 (공용 Retry) */
    private suspend fun connect() {
        Retry.untilSuccess("navi connect", log, Backoff(CONNECT_RETRY_MS, CONNECT_RETRY_MAX_MS)) {
            withTimeout(CONNECT_TIMEOUT_MS) { navi.connect() }
        }
        log.i("navi connected")
    }

    private suspend fun watchChanges() {
        while (true) {
            try {
                navi.changes.collect { onChange(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("navi events failed: ${e.message ?: e.javaClass.simpleName}")
            }
            delay(CONNECT_RETRY_MS) // 흐름이 끝나거나 실패했으면 다시 구독한다
        }
    }

    /**
     * 내비 호출 하나. 실패·무응답은 null (호출한 쪽이 다시 시도할 때를 정한다). kit에 15초 시간 초과가 있지만, 포트가
     * 영영 답하지 않아도 이 루프가 멈추지 않게 여기서도 한 번 더 자른다.
     */
    private suspend fun <T : Any> call(name: String, block: suspend () -> T): T? {
        lastRequestAt = clock.elapsedMs()
        val result = try {
            withTimeoutOrNull(CALL_TIMEOUT_MS) { block() }
        } catch (e: TimeoutCancellationException) {
            null // 안쪽의 시간 초과: 실패로 본다 (이 코루틴이 취소된 것과 다르다)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(name, e.message ?: e.javaClass.simpleName)
            return null
        }
        if (result == null) {
            failed(name, "no answer in ${CALL_TIMEOUT_MS}ms")
            return null
        }
        failures.remove(name)?.let { log.i("navi $name ok after $it failures") }
        return result
    }

    /** 같은 실패가 5초마다 되풀이되므로 처음과 1분에 한 번쯤만 남긴다 */
    private fun failed(name: String, message: String) {
        val n = (failures[name] ?: 0) + 1
        failures[name] = n
        if (n == 1 || n % 12 == 0) log.w("navi $name failed ($n): $message")
    }

    private fun nextWakeInMs(): Long {
        val now = clock.elapsedMs()
        var due = nextRouteAt
        val s = state.value
        if (s.guiding && !routeChangePending) tbtDueAt(now, s)?.let { due = minOf(due, it) }
        // 길어도 5초마다 깨어 TBT 간격(가까워지면 짧아진다)과 위치 대체를 다시 본다
        return (due - now).coerceIn(MIN_WAIT_MS, NaviPoll.NEAR_MS)
    }

    private fun publish(s: NaviSnapshot) {
        state.value = s
    }

    companion object {
        /** kit의 조회 시간 초과(15초)보다 길게. 보통은 kit의 실패가 먼저 온다 */
        const val CALL_TIMEOUT_MS = 20_000L
        /** kit의 연결 시간 초과(30초)보다 길게 */
        const val CONNECT_TIMEOUT_MS = 45_000L
        const val CONNECT_RETRY_MS = 2_000L
        const val CONNECT_RETRY_MAX_MS = 60_000L
        const val MIN_REQUEST_GAP_MS = 1_000L
        const val MIN_WAIT_MS = 100L
        const val DESTINATION_MOVED_M = 100.0
        /**
         * 경로 상태 조회가 이만큼 이어서 실패하면 지난 스냅샷을 버린다. 시속 100km로 1.7km: 그 사이 휴게소 진입로 하나를 지날 수 있다.
         * 들르기 하나가 내비 잠금을 잡는 시간(경유지 확인 20~45초)보다 길어서, 그 기다림만으로는 넘지 않는다.
         */
        const val ROUTE_STALE_MS = 60_000L

        private const val ROUTE = 1
        private const val TBT = 2
        private const val FAST = 4
        private const val CHANGED = 8
        private const val REFRESH = ROUTE or TBT or FAST
    }
}
