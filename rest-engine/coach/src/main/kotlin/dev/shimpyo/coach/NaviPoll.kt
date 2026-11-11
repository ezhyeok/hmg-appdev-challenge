package dev.shimpyo.coach

import dev.shimpyo.engine.StopCandidate

/**
 * Pleos 내비 조회 간격과, 조회 사이의 거리 보정 (CoachService). 순수 계산이라 NaviPollTest로 지킨다.
 *
 * 예전에는 회전 안내(TBT)를 5초마다 받고 그 사이에는 같은 거리를 썼다(2026-11-11 측정: 경로가 없어도 1분에 TBT 12번,
 * 경로 상태·목적지 4번씩). 지금은 차속을 적분한 주행 거리로 매초 경로 거리를 줄이고, 다음 화장실·회전이 멀 때만 덜 자주
 * 받는다. 주행 거리 보정은 내비가 실제로 그만큼 나아갔다고 확인할 때만 쓴다([checkProgress]). 확인 전이나 어긋나면
 * 예전처럼 거리를 그대로 두고 5초마다 받는다(에뮬레이터의 내비는 판교에 서 있어 차속만으로 줄이면 내비와 어긋난다).
 */
object NaviPoll {
    /**
     * 경로가 있는 길안내인지. SAFE_GUIDING은 경로 없는 안전운전 모드다(SPEC 8장: 경로 전에는 SAFE_GUIDING, kit의 isGuiding도
     * ROUTE_GUIDING만 본다). 예전에는 이것도 길안내로 봐서 경로 없이 TBT·목적지를 계속 조회했고, 수락하면 startRoute 대신
     * addWaypoint를 불러 실패했다(2026-11-11 감사). SIMULATION_GUIDING은 내비의 모의 주행(경로 있음)이다.
     */
    fun isGuiding(mode: NaviMode?): Boolean = mode?.hasRoute == true

    /** 경로 상태(정체·길안내 여부)와 목적지 조회 간격 */
    const val ROUTE_MS = 15_000L
    /** 기본 TBT 간격 (예전과 같다) */
    const val NEAR_MS = 5_000L
    /** 다음 화장실 기회와 회전이 모두 [NEAR_M]보다 멀고 주행 거리 보정을 믿을 수 있을 때 */
    const val FAR_MS = 20_000L
    /** 묻는 거리(진입로 2~5km)보다 넉넉하게 */
    const val NEAR_M = 8_000.0
    /** 길안내 시작·경유지 추가·경로 재탐색 뒤 이만큼은 5초마다 받는다 (첫 응답이 비었거나 바뀌기 전 경로일 수 있다) */
    const val FAST_AFTER_RESET_MS = 30_000L

    /**
     * 다음 TBT 조회까지의 간격. 거리는 마지막 TBT 뒤 달린 만큼 줄인 값.
     * 5초로 받는 경우: 차속을 모르거나 주행 거리 보정을 아직 못 믿을 때, TBT가 비어 있을 때(길안내 중 경로에는 목적지라도 있다),
     * 경로가 막 바뀌었을 때, 다음 화장실·회전이 [NEAR_M] 안일 때.
     */
    fun tbtIntervalMs(
        nextOpportunityM: Double?, nextManeuverM: Double?, speedKnown: Boolean, trusted: Boolean, afterReset: Boolean,
        naviFrozen: Boolean = false,
    ): Long {
        if (afterReset) return NEAR_MS
        // 내비가 서 있다(남은 거리가 그대로인데 차는 달린다: 에뮬레이터, 위치를 잃은 내비). 내비 위치에서 잰 TBT도 그대로라
        // 경로 조회보다 자주 물을 까닭이 없다. 다시 움직이면 다음 경로 조회(15초 안)에서 알고 5초로 돌아간다 (2026-11-12)
        if (naviFrozen) return ROUTE_MS
        if (!speedKnown || !trusted) return NEAR_MS
        val nearest = listOfNotNull(nextOpportunityM, nextManeuverM).minOrNull() ?: return NEAR_MS
        return if (nearest < NEAR_M) NEAR_MS else FAR_MS
    }

    /** 경로 상태를 다시 받았을 때: 주행 거리 보정을 믿을지, 경로가 바뀌었는지 */
    data class Progress(val trusted: Boolean, val rerouted: Boolean)

    /**
     * 직전 경로 상태 뒤로 내비의 남은 거리가 줄어든 만큼(내비가 확인한 진행)과 주행 거리를 비교한다.
     * - 주행 거리가 300m 넘게 늘었는데 내비 진행이 그 절반도 안 되면 보정을 끈다(내비가 서 있거나 차속이 틀림).
     * - 남은 거리가 1km 넘게 늘거나, 주행 거리로 설명 못 할 만큼(3km 넘게) 줄면 경로가 바뀐 것으로 본다.
     * - 거의 서 있으면(300m 미만) 판단을 바꾸지 않는다.
     */
    fun checkProgress(prevRemainM: Double?, prevOdometerM: Double, remainM: Double?, odometerM: Double, wasTrusted: Boolean): Progress {
        if (prevRemainM == null || remainM == null) return Progress(wasTrusted, rerouted = false)
        val navi = prevRemainM - remainM
        val odo = odometerM - prevOdometerM
        val rerouted = remainM - prevRemainM > 1_000.0 || navi - odo > 3_000.0
        if (rerouted) return Progress(trusted = false, rerouted = true)
        if (odo < 300.0) return Progress(wasTrusted, rerouted = false)
        return Progress(trusted = navi >= 0.5 * odo, rerouted = false)
    }

    /**
     * 내비가 서 있는지: 직전 경로 조회 뒤 차가 300m 넘게 달렸는데 내비 남은 거리가 그대로(1m 안)면 그렇다. 남은 거리를 모르거나
     * 거의 서 있으면(300m 미만) 판단을 바꾸지 않는다 ([wasFrozen])
     */
    fun naviFrozen(prevRemainM: Double?, prevOdometerM: Double, remainM: Double?, odometerM: Double, wasFrozen: Boolean): Boolean {
        if (prevRemainM == null || remainM == null) return wasFrozen
        if (odometerM - prevOdometerM < 300.0) return wasFrozen
        return kotlin.math.abs(remainM - prevRemainM) < 1.0
    }

    /** TBT를 받은 뒤 [movedM]만큼 달렸으면 경로 위 기회의 거리를 줄인다. 지나친 곳(0 미만)은 뺀다 */
    fun advance(opportunities: List<RouteOpportunity>, movedM: Double): List<RouteOpportunity> {
        if (movedM <= 0.0) return opportunities
        return opportunities.mapNotNull { o -> (o.routeDistanceM - movedM).takeIf { it >= 0.0 }?.let { o.copy(routeDistanceM = it) } }
    }

    /**
     * 후보를 만든 뒤 [movedM]만큼 달렸으면 경로 거리를 줄이고(ETA도 같은 비율로) 지나친 곳은 뺀다. 다음 갱신(20초)까지
     * 엔진이 보는 거리를 최신으로 둔다. 경로 거리가 없는 후보는 그대로 둔다.
     */
    fun advanceCandidates(candidates: List<StopCandidate>, movedM: Double): List<StopCandidate> {
        if (movedM <= 0.0) return candidates
        return candidates.mapNotNull { c ->
            val d = c.routeDistanceM ?: return@mapNotNull c
            val left = d - movedM
            if (left < 0.0) return@mapNotNull null
            c.copy(routeDistanceM = left, etaS = c.etaS?.let { if (d > 1.0) it * left / d else it })
        }
    }

    /** 차속(km/h)으로 [dtMs] 동안 달린 거리(m). 긴 공백(5초 넘게 갱신 없음)은 5초까지만 센다 */
    fun distanceM(speedKph: Float?, dtMs: Long): Double =
        if (speedKph == null || speedKph <= 0f || dtMs <= 0) 0.0 else speedKph / 3.6 * dtMs.coerceAtMost(5_000L) / 1000.0
}
