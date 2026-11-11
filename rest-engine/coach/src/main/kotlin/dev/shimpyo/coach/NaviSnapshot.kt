package dev.shimpyo.coach

import dev.shimpyo.engine.RouteState

/**
 * 차속을 적분한 주행 거리(m, 실제 시간 기준). 내비를 덜 자주 조회해도 그 사이 경로 거리를 매초 줄여 쓰는 데 쓴다 (NaviPoll).
 * 코치 틱 하나만 [advance]한다. 읽는 쪽(NaviTracker, CandidateProvider)은 같은 코치 디스패처에서 읽는다.
 */
class Odometer(private val clock: ElapsedClock) {
    // 쓰는 쪽은 코치 틱 하나지만 읽는 쪽이 다른 스레드에 있어도 찢어진 값을 보지 않게 (Double은 원자적 쓰기가 보장되지 않는다)
    @Volatile var meters = 0.0
        private set
    private var lastAt: Long? = null

    fun advance(speedKph: Float?) {
        val t = clock.elapsedMs()
        lastAt?.let { meters += NaviPoll.distanceM(speedKph, t - it) }
        lastAt = t
    }
}

/**
 * 내비에서 받은 값 한 묶음. NaviTracker가 통째로 바꿔 끼우므로 읽는 쪽이 새 경로와 옛 TBT를 섞어 읽지 않는다.
 * epoch는 길안내 시작·재탐색·목적지 변경·취소 때 늘어난다. 다른 epoch에 받은 TBT는 쓰지 않는다.
 * 거리 값은 각각 routeOdometerM / tbtOdometerM 시점의 것이고, [liveRoute]·[liveOpportunities]가 그 뒤 달린 만큼 줄인다.
 */
data class NaviSnapshot(
    val epoch: Int = 0,
    val mode: NaviMode = NaviMode.UNKNOWN,
    /** 엔진에 넣을 경로 상태 (길안내가 아니면 guiding=false, 모르면 null) */
    val route: RouteState? = null,
    val routeOdometerM: Double = 0.0,
    val destination: NaviDestination? = null,
    /** TBT의 경로 위 휴게소·졸음쉼터 (현재 위치부터 누적 경로 거리) */
    val opportunities: List<RouteOpportunity> = emptyList(),
    /**
     * 이번 epoch의 TBT 응답을 받았는지. 받았는데 [opportunities]가 비어 있으면 [tbtCoverageM]까지는 정말 경로 위에
     * 휴게시설이 없다. 받기 전(false)에는 경로 위 시설을 모른다 (빈 목록과 다르다)
     */
    val tbtReceived: Boolean = false,
    /**
     * TBT 목록이 닿는 경로 거리(마지막 항목까지의 누적, tbtOdometerM 시점). 목적지보다 짧으면 그 뒤는 모른다.
     * 빈 TBT면 0 (아무것도 모른다), 받기 전이면 null. 거리가 깨진 항목에서 읽기를 멈춘 경우도 거기까지다 (TbtDigest)
     */
    val tbtCoverageM: Double? = null,
    val nextManeuverM: Double? = null,
    val tbtOdometerM: Double = 0.0,
    val tbtEpoch: Int = 0,
    /** 내비가 확인한 진행과 주행 거리가 맞는지 (맞을 때만 주행 거리로 거리를 줄인다) */
    val odometerTrusted: Boolean = false,
) {
    val guiding: Boolean get() = route?.guiding == true

    /** [base] 이후 달린 거리. 주행 거리 보정을 믿을 수 없으면 0 (받은 거리를 그대로 쓴다) */
    fun moved(base: Double, odometerM: Double): Double = if (odometerTrusted) (odometerM - base).coerceAtLeast(0.0) else 0.0

    /** 엔진에 넣을 경로 상태: 마지막 조회 뒤 달린 만큼 남은 거리·정체 위치·다음 회전 거리를 줄인다 */
    fun liveRoute(odometerM: Double): RouteState? {
        val r = route ?: return null
        if (!r.guiding) return r
        val sinceRoute = moved(routeOdometerM, odometerM)
        val sinceTbt = moved(tbtOdometerM, odometerM)
        return r.copy(
            remainDistanceM = r.remainDistanceM?.let { (it - sinceRoute).coerceAtLeast(0.0) },
            congestion = if (sinceRoute <= 0.0) r.congestion
            else r.congestion.map { it.copy(startDistanceM = it.startDistanceM - sinceRoute) }.filter { it.startDistanceM + it.lengthM > 0 },
            nextManeuverDistanceM = if (tbtEpoch == epoch) nextManeuverM?.let { (it - sinceTbt).coerceAtLeast(0.0) } else null,
        )
    }

    /** 지금 경로 위 기회. 다른 epoch(바뀌기 전 경로)의 TBT면 비운다 */
    fun liveOpportunities(odometerM: Double): List<RouteOpportunity> =
        if (tbtEpoch != epoch) emptyList() else NaviPoll.advance(opportunities, moved(tbtOdometerM, odometerM))

    /**
     * 지금 위치에서 TBT가 닿는 경로 거리. 이번 epoch의 TBT를 아직 못 받았으면 null.
     * [liveRoute]의 남은 거리보다 짧으면 그 뒤 경로 위 시설은 모른다 (후보 범위 RouteState.candidatesUntilM에 쓴다)
     */
    fun liveTbtCoverageM(odometerM: Double): Double? =
        if (tbtEpoch != epoch || !tbtReceived) null else tbtCoverageM?.let { (it - moved(tbtOdometerM, odometerM)).coerceAtLeast(0.0) }
}

/** 위치 한 점과 받은 때(실제 시간)·출처. PositionTracker가 실제 위치만 담는다 (시연 위치는 CoachWorld가 덧씌운다) */
data class Located(val fix: Fix, val atElapsedMs: Long, val source: Source) {
    enum class Source { FUSED, NAVI }
}
