package dev.shimpyo.coach

import dev.shimpyo.coach.CandidateRules.Plan
import dev.shimpyo.coach.CandidateTuning.AHEAD_RADIUS_M
import dev.shimpyo.coach.CandidateTuning.CHECK_MS
import dev.shimpyo.coach.CandidateTuning.MATCH_SLACK_M
import dev.shimpyo.coach.CandidateTuning.PLANNED_KINDS
import dev.shimpyo.coach.CandidateTuning.REFRESH_BUDGET_MS
import dev.shimpyo.coach.CandidateTuning.ROUTE_OPP_MAX
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 엔진에 넣을 정차 후보를 만든다 (예전 CoachService의 candidateLoop·refreshCandidates·candidatesFromRoute·liveCandidates·
 * liveRouteWithHorizon). 결과는 [snapshot] 하나로 통째로 바꿔 끼운다. 규칙은 [CandidateRules], 조정값은 [CandidateTuning].
 *
 * 만드는 길 (CandidateRules.plan)
 * - 길안내 중이고 이번 경로(epoch)의 TBT를 받았으면 TBT의 경로 위 휴게소·졸음쉼터만 쓴다. 이름으로 공공데이터 행을 찾아
 *   좌표·편의시설을 붙이고, 같은 이름이 여럿이면 차로 방위(StopIndex.matchByName의 heading)로, 그래도 여럿이면 requestTrip 거리로
 *   고른다. 못 고르면 좌표 없는 tbt: 후보. 거리는 TBT 누적 거리, etaS는 비운다(엔진이 정체로 계산한다).
 *   한 번 확인한 행은 그 경로(epoch)가 끝날 때까지 그 행이다([resolved]): 엔진은 수락한 정차를 id로 따라간다.
 * - 경로가 바뀐 직후 TBT를 기다리는 동안은 목록을 바꾸지 않는다(범위는 0, withHorizon). 끝내 안 오면 반경 검색.
 * - 길안내가 없으면(또는 시연이면) 진행 방향 앞쪽 반경 검색. 실제면 가까운 순 [CandidatePolicy.oracleMax]곳을 requestTrip으로 보정하고
 *   유턴 시설과 목적지 뒤 시설을 뺀다.
 *
 * 스레드: 코치 디스패처 하나에서 쓴다. [run]이 유일한 갱신 루프이고(갱신은 그 자식 코루틴), [live]·[withHorizon]은 매 틱 부르는
 * 가벼운 순수 계산이다. [refresh]는 Mutex로 한 번에 하나만 돈다. [reset]은 진행 중인 갱신을 취소하고 그 결과를 버린다.
 */
class CandidateProvider(
    private val stops: StopIndex,
    private val oracle: TripEstimator,
    private val odometer: Odometer,
    /** 갱신 주기, TBT 기다림, 갱신 예산을 재는 실제 시간 */
    private val clock: ElapsedClock,
    private val log: CoachLog = CoachLog.NONE,
) {
    private val _snapshot = MutableStateFlow(CandidateSnap())
    val snapshot: StateFlow<CandidateSnap> = _snapshot.asStateFlow()

    private val refreshLock = Mutex()
    /** 마지막 갱신의 입력 요약. 바뀌면 바로 다시 만든다 */
    private var basis: Basis? = null
    /** 지금 경로(epoch, 길안내 여부)를 처음 본 때. TBT 기다림을 여기서부터 잰다 */
    private var seenRoute: Pair<Int, Boolean>? = null
    private var seenRouteAt = 0L
    /** [reset]마다 늘린다. 갱신이 끝났을 때 다르면 결과를 버린다 */
    private var generation = 0
    private var inFlight: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var lastLog: String? = null

    /**
     * 이번 내비 epoch에서 확인한 'TBT 기회 → 데이터 행' (서비스 감사 SV-N1). 차로 방위나 내비 거리로 확인한 행은 epoch가 끝날 때까지
     * 그 행이다. 예전에는 갱신마다 새로 골라서, 정체에 들어 방위를 모르게 되고(P·저속) 내비도 못 재면 같은 휴게소의 후보 id가
     * 데이터 행에서 좌표 없는 tbt:로 바뀌었고, 엔진이 수락한 정차를 잊고 다시 물었다. 다른 epoch(되돌아가는 새 경로)에서는 다시 고른다.
     */
    private val resolved = HashMap<OppKey, StopCandidate>()
    /** 이번 epoch에 차로 방위가 반대 차로라고 뺀 이름. 방위를 다시 모르게 돼도 그 행으로 돌아가지 않는다 (행 ↔ tbt:를 오가지 않게) */
    private val laneRejected = HashSet<OppKey>()
    private var resolvedEpoch = Int.MIN_VALUE

    // ------------------------------------------------------------ 갱신 루프

    /**
     * 갱신 루프. [CHECK_MS]마다 [request]를 읽고, 입력이 바뀌었거나(정책, 데이터, 위치 유무, epoch, 길안내, TBT) 주기가 됐거나
     * TBT 기다림이 끝났으면 다시 만든다. 갱신은 자식 코루틴이라 [reset]이 루프를 죽이지 않고 취소할 수 있다.
     */
    suspend fun run(request: () -> CandidateRequest): Unit = coroutineScope {
        while (true) {
            val req = request()
            if (isDue(req, clock.elapsedMs())) {
                val job = launch {
                    try {
                        refresh(req)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 한 번 실패해도 루프는 이어 간다 (예전에는 guard 하나로 루프 전체가 끝났다, 데이터 감사 #9)
                        log.w("candidates refresh failed: ${e.message}")
                    }
                }
                inFlight = job
                job.join()
                if (inFlight === job) inFlight = null
            }
            withTimeoutOrNull(CHECK_MS) { wake.receive() }
        }
    }

    /** 지금 다시 만들어야 하는지 */
    private fun isDue(req: CandidateRequest, now: Long): Boolean {
        noteRoute(req.navi, now)
        val b = basis ?: return true
        return b.key != keyOf(req) || now - b.atMs >= req.policy.periodMs || (b.retryAtMs != null && now >= b.retryAtMs)
    }

    // ------------------------------------------------------------ 갱신 하나

    suspend fun refresh(pos: Fix?, navi: NaviSnapshot, policy: CandidatePolicy) = refresh(CandidateRequest(pos, navi, policy))

    /** 후보를 한 번 만든다. 동시에 부르면 차례로 돈다. requestTrip은 [REFRESH_BUDGET_MS] 안에서만 쓴다 */
    suspend fun refresh(req: CandidateRequest) = refreshLock.withLock {
        val gen = generation
        val now = clock.elapsedMs()
        noteRoute(req.navi, now)
        val view = viewOf(req)
        val odo = odometer.meters
        val plan = CandidateRules.plan(stops.loaded, req.pos, view, odo, now - seenRouteAt)
        basis = Basis(keyOf(req), now, (plan as? Plan.AwaitRoute)?.let { now + it.retryInMs })
        val snap = when (plan) {
            Plan.NoData -> empty(CandidatesState.LOADING_DATA, req.navi.epoch, odo)
            Plan.NoLocation -> empty(CandidatesState.NO_LOCATION, req.navi.epoch, odo)
            // 다른 경로에서 만든 목록은 남겨 둔다 (화면·수락한 정차가 깜빡이지 않게). epoch가 달라 withHorizon이 범위를 0으로 둔다
            is Plan.AwaitRoute -> if (_snapshot.value.state == CandidatesState.READY) null else empty(CandidatesState.LOADING_DATA, req.navi.epoch, odo)
            is Plan.Route -> fromRoute(plan, req.pos!!, view, req.policy, odo, Budget(req.policy.oracleMax ?: 0, now + REFRESH_BUDGET_MS))
            is Plan.Nearby -> fromNearby(plan, req.pos!!, view, req.policy, odo, now + REFRESH_BUDGET_MS)
        }
        if (snap != null && gen == generation) {
            val out = snap.copy(naviEpoch = req.navi.epoch, naviGuiding = req.navi.guiding)
            _snapshot.value = out
            logIfChanged(out)
        }
    }

    /**
     * 모드(실제/시연)가 바뀔 때. 진행 중인 갱신을 취소하고 목록을 바로 비운다(옛 모드 후보가 새 엔진에 한 틱도 들어가지 않게, 구조 리뷰 B2),
     * 경로 보정 캐시도 비우고 곧바로 다시 만든다. 경로 변경(epoch)은 reset 없이 갱신 루프가 알아서 다시 만든다.
     */
    fun reset() {
        generation++
        inFlight?.cancel()
        basis = null
        forgetResolved(Int.MIN_VALUE)
        oracle.clear()
        _snapshot.value = CandidateSnap(odometerM = odometer.meters, untilM = 0.0, naviEpoch = -1, state = CandidatesState.LOADING_DATA)
        wake.trySend(Unit)
    }

    // ------------------------------------------------------------ 매 틱 투영

    /** 엔진·화면에 넣을 후보: 만든 뒤 달린 만큼 경로 거리(ETA도 같은 비율로)를 줄이고 지나친 곳은 뺀다. 움직이지 않았으면 같은 목록 */
    fun live(navi: NaviSnapshot, odometerM: Double): List<StopCandidate> {
        val s = _snapshot.value
        return NaviPoll.advanceCandidates(s.list, navi.moved(s.odometerM, odometerM))
    }

    /**
     * [route]에 후보를 넣은 범위(candidatesUntilM)를 더한다. 자른 목록의 마지막 후보 뒤를 목적지까지의 공백으로 보지 않게 한다.
     * 길안내가 아니면 그대로. 목록이 다른 경로(epoch·길안내 여부)에서 만든 것이면 0(아무것도 주장하지 않는다).
     */
    fun withHorizon(route: RouteState?, navi: NaviSnapshot, odometerM: Double): RouteState? {
        val r = route ?: return null
        if (!r.guiding) return r
        val s = _snapshot.value
        val until = if (s.naviEpoch != navi.epoch || s.naviGuiding != navi.guiding) 0.0 else {
            val u = s.untilM ?: return r
            (u - navi.moved(s.odometerM, odometerM)).coerceAtLeast(0.0)
        }
        return r.copy(candidatesUntilM = until)
    }

    // ------------------------------------------------------------ TBT 경로

    private suspend fun fromRoute(plan: Plan.Route, pos: Fix, navi: NaviSnapshot, policy: CandidatePolicy, odo: Double, budget: Budget): CandidateSnap {
        if (resolvedEpoch != navi.epoch) forgetResolved(navi.epoch)
        val list = ArrayList<StopCandidate>()
        val ids = HashSet<String>()
        val nameCount = HashMap<String, Int>()
        for (o in plan.opportunities.take(ROUTE_OPP_MAX)) {
            // 종류는 extract가 이름의 시설어로 이미 정했다 (StopNames.kindFor)
            val kind = o.kind
            val chosen = matchOpportunity(o, kind, pos, navi, odo, policy, budget)
            val n = (nameCount[o.name] ?: 0) + 1
            nameCount[o.name] = n
            val base = chosen ?: placeholder(o, kind, pos, n)
            if (!ids.add(base.id)) continue // 같은 시설이 두 번 오면 앞의 것만
            list += base.copy(routeDistanceM = o.routeDistanceM, directionOk = true, etaS = null)
        }
        return CandidateSnap(list, odo, plan.untilM, state = CandidatesState.READY, source = CandidateSource.ROUTE)
    }

    /**
     * TBT 기회 하나에 맞는 데이터 행. 못 고르면 null.
     * - 이번 epoch에 확인한 행이 있으면 그 행 (TBT 거리와 어긋나지 않을 때: 경로가 같은 이름의 다른 시설을 또 지날 수 있다).
     * - 방위를 알고 한 행만 남으면 차로 방위로 확인한 것이다. 여럿이면 내비 거리로 고르고, 고르면 확인한 것이다.
     * - 방위를 모르는데 한 행뿐이면 확인 없이 임시로 쓴다(기억하지 않는다). 방위를 알던 때 차로 방위가 뺀 이름이면 쓰지 않는다.
     */
    private suspend fun matchOpportunity(
        o: RouteOpportunity, kind: StopKind, pos: Fix, navi: NaviSnapshot, odo: Double, policy: CandidatePolicy, budget: Budget,
    ): StopCandidate? {
        val key = OppKey(o.name, kind)
        resolved[key]?.let { row ->
            val straight = Geo.distanceM(pos.lat, pos.lon, row.lat, row.lon)
            if (CandidateRules.plausibleMatch(straight, o.routeDistanceM)) return row.copy(straightDistanceM = straight)
        }
        val matches = namedRows(o, kind, pos, pos.heading)
        if (matches.isEmpty()) {
            // 방위 때문에 빠진 것인지 본다 (이름이 데이터에 없는 것과 가른다)
            if (pos.heading != null && namedRows(o, kind, pos, heading = null).isNotEmpty()) laneRejected += key
            return null
        }
        if (matches.size == 1) {
            if (pos.heading != null) return matches[0].also { resolved[key] = it }
            return matches[0].takeIf { key !in laneRejected }
        }
        // 차로 방위로도 못 골랐다(방향을 모르거나 데이터에 방위가 없다). 내비 거리와 TBT 거리를 맞춰 본다
        if (policy.oracleMax == null) return null
        val measured = matches.map { m ->
            val at = odometer.meters
            val r = budget.refine(m, pos)
            // TBT 거리는 갱신 시작(odo) 기준, requestTrip은 요청(at) 기준이다
            CandidateRules.Measured(m, r?.routeDistanceM?.plus(navi.moved(odo, at)), r?.directionOk)
        }
        return CandidateRules.chooseMeasured(measured, o.routeDistanceM)?.also { resolved[key] = it }
    }

    /** TBT 이름과 맞고 직선거리가 TBT 경로 거리와 어울리는 데이터 행 */
    private fun namedRows(o: RouteOpportunity, kind: StopKind, pos: Fix, heading: Float?): List<StopCandidate> =
        stops.matchByName(o.name, kind, pos.lat, pos.lon, o.routeDistanceM + MATCH_SLACK_M, heading)
            .filter { CandidateRules.plausibleMatch(it.straightDistanceM, o.routeDistanceM) }

    private fun forgetResolved(epoch: Int) {
        resolved.clear()
        laneRejected.clear()
        resolvedEpoch = epoch
    }

    /**
     * 데이터에서 못 찾은 경로 위 시설: 좌표 없이 넣는다(좌표 칸은 차 위치, 거리는 routeDistanceM만 쓴다). 수락하면 내비 장소 검색을 연다.
     * 같은 이름이 또 오면(이름 없는 '졸음쉼터') id 뒤에 순번을 붙인다.
     */
    private fun placeholder(o: RouteOpportunity, kind: StopKind, pos: Fix, n: Int) = StopCandidate(
        id = if (n == 1) "tbt:${o.name}" else "tbt:${o.name}#$n",
        name = o.name,
        kind = kind,
        lat = pos.lat,
        lon = pos.lon,
        facilities = Facilities(restroom = true, food = kind == StopKind.HIGHWAY_REST_AREA),
        parkable = true,
    )

    // ------------------------------------------------------------ 반경 검색 경로

    private suspend fun fromNearby(plan: Plan.Nearby, pos: Fix, navi: NaviSnapshot, policy: CandidatePolicy, odo: Double, deadline: Long): CandidateSnap {
        val raw = stops.ahead(pos.lat, pos.lon, pos.heading, AHEAD_RADIUS_M, PLANNED_KINDS).filter { it.facilities.restroom }
        val max = policy.oracleMax
            // 시연: 내비에 묻지 않고 다 넘긴다 (시연 경로 위 시설과 거리는 CoachWorld가 정한다). etaS는 넣지 않는다
            ?: return CandidateSnap(raw.map { it.copy(etaS = null) }, odo, CandidateRules.nearbyHorizon(raw, null), state = CandidatesState.READY, source = CandidateSource.NEARBY)
        val budget = Budget(max, deadline)
        // requestTrip은 후보마다 요청 시점의 위치에서 잰다: 요청 때 주행 거리를 같이 적어 두고 마지막에 같은 기준(base)으로 맞춘다
        val measured = raw.take(max).map { c -> odometer.meters to (budget.refine(c, pos) ?: c.copy(etaS = null)) }
        val base = odometer.meters
        val list = measured.mapNotNull { (at, c) ->
            if (c.directionOk == false) null else NaviPoll.advanceCandidates(listOf(c), navi.moved(at, base)).firstOrNull()
        }
        val sinceStart = navi.moved(odo, base)
        val until = (CandidateRules.nearbyHorizon(raw, max) - sinceStart).coerceAtLeast(0.0)
        val kept = CandidateRules.beforeDestination(list, plan.remainM?.let { it - sinceStart })
        return CandidateSnap(kept, base, until, state = CandidatesState.READY, source = CandidateSource.NEARBY)
    }

    // ------------------------------------------------------------ 도우미

    /**
     * 갱신 하나의 requestTrip 한도: 내비에 보내는 요청 [left]개, 시각 [deadline]까지. 넘으면 '모름'(null).
     * 캐시가 답한 것은 요청이 아니라 세지 않는다 (예전에는 세어서, 방위를 모를 때 같은 이름 쌍을 앞 세 쌍만 골랐다, SV-N1).
     */
    private inner class Budget(private var left: Int, private val deadline: Long) {
        suspend fun refine(c: StopCandidate, from: Fix): StopCandidate? {
            val remaining = deadline - clock.elapsedMs()
            // 시간이 다 됐어도 캐시가 아는 것은 쓴다 (내비에 묻지 않으니 기다리지 않는다)
            if (remaining <= 0) return oracle.refine(c, from) { false }
            return withTimeoutOrNull(remaining) { oracle.refine(c, from, ::spend) }
        }

        private fun spend(): Boolean = (left > 0).also { if (it) left-- }
    }

    /** 시연이면 내비 경로를 보지 않는다 (빈 스냅샷: 길안내 아님) */
    private fun viewOf(req: CandidateRequest): NaviSnapshot = if (req.policy.useRouteTbt) req.navi else NO_ROUTE

    private fun keyOf(req: CandidateRequest): Key {
        val v = viewOf(req)
        val tbt = if (v.guiding) TbtKey(v.tbtEpoch, v.tbtReceived, v.tbtOdometerM, v.tbtCoverageM, v.opportunities) else null
        return Key(req.policy, stops.loaded, req.pos != null, v.epoch, v.guiding, tbt)
    }

    /**
     * 경로가 바뀐 때를 적는다. epoch만 보지 않고 길안내 여부도 본다: 길안내가 시작됐는데 epoch가 그대로인 일이 생겨도
     * TBT를 기다리지 않고 경로 시작 직후 requestTrip을 보내지 않게 (방어)
     */
    private fun noteRoute(navi: NaviSnapshot, now: Long) {
        val route = navi.epoch to navi.guiding
        if (seenRoute == route) return
        seenRoute = route
        seenRouteAt = now
    }

    private fun empty(state: CandidatesState, epoch: Int, odo: Double) = CandidateSnap(emptyList(), odo, untilM = 0.0, naviEpoch = epoch, state = state)

    private fun logIfChanged(s: CandidateSnap) {
        val line = "candidates ${s.state} ${s.source} n=${s.list.size} until=${s.untilM?.let { "%.0f".format(it) }} epoch=${s.naviEpoch}"
        if (line != lastLog) log.i(line)
        lastLog = line
    }

    private data class TbtKey(val epoch: Int, val received: Boolean, val odometerM: Double, val coverageM: Double?, val opportunities: List<RouteOpportunity>)

    private data class Key(val policy: CandidatePolicy, val loaded: Boolean, val hasPos: Boolean, val epoch: Int, val guiding: Boolean, val tbt: TbtKey?)

    private data class Basis(val key: Key, val atMs: Long, val retryAtMs: Long?)

    /** TBT 기회를 가리키는 이름과 종류 (같은 경로 안에서 같은 시설을 가리킨다) */
    private data class OppKey(val name: String, val kind: StopKind)

    private companion object {
        val NO_ROUTE = NaviSnapshot()
    }
}
