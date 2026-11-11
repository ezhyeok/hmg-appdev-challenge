package dev.shimpyo.coach

import dev.shimpyo.coach.CandidateTuning.AHEAD_RADIUS_M
import dev.shimpyo.coach.CandidateTuning.COVERAGE_SLACK_M
import dev.shimpyo.coach.CandidateTuning.MATCH_MIN_SLACK_M
import dev.shimpyo.coach.CandidateTuning.MATCH_MIN_STRAIGHT_RATIO
import dev.shimpyo.coach.CandidateTuning.MATCH_SLACK_M
import dev.shimpyo.coach.CandidateTuning.MATCH_TOLERANCE_M
import dev.shimpyo.coach.CandidateTuning.MATCH_TOLERANCE_RATIO
import dev.shimpyo.coach.CandidateTuning.ROUTE_OPP_MAX
import dev.shimpyo.coach.CandidateTuning.ROUTE_TBT_WAIT_MS
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import kotlin.math.abs

/**
 * CandidateProvider의 판단 규칙. 모두 순수 함수라 CandidateRulesTest가 바로 지킨다.
 * 원칙: 엔진이 '마지막 기회' 같은 주장을 하려면 목록이 어디까지 완전한지(범위)가 맞아야 한다. 모르면 짧게 말한다
 * (범위를 너무 길게 잡으면 가짜 경고, 너무 짧게 잡으면 경고를 놓친다. 앞쪽이 더 나쁘다).
 */
object CandidateRules {

    /** 이번 갱신에서 후보를 어떻게 만들지 */
    sealed interface Plan {
        /** 정차 후보 표를 아직 읽지 않았다 */
        data object NoData : Plan
        /** 위치를 아직 모른다 */
        data object NoLocation : Plan
        /** 길안내 중이고 이번 경로의 TBT를 받았다: 그 기회만 쓴다 (비어 있으면 정말 경로 위에 없다) */
        data class Route(val opportunities: List<RouteOpportunity>, val untilM: Double?) : Plan
        /** 길안내 중인데 이번 경로의 TBT를 아직 못 받았다: 목록을 바꾸지 않고 [retryInMs] 뒤 다시 본다 */
        data class AwaitRoute(val retryInMs: Long) : Plan
        /** 반경 검색. [remainM]: 길안내 중이면 목적지까지 남은 거리 (그 뒤 시설은 뺀다) */
        data class Nearby(val remainM: Double?) : Plan
    }

    /**
     * 어느 경로로 만들지 정한다. [navi]는 이미 정책을 거친 값이다(시연이면 빈 스냅샷).
     * [epochAgeMs]: 지금 epoch를 처음 본 뒤 지난 실제 시간.
     */
    fun plan(loaded: Boolean, pos: Fix?, navi: NaviSnapshot, odometerM: Double, epochAgeMs: Long): Plan {
        if (!loaded) return Plan.NoData
        if (pos == null) return Plan.NoLocation
        if (!navi.guiding) return Plan.Nearby(remainM = null)
        val remain = navi.liveRoute(odometerM)?.remainDistanceM
        if (navi.tbtEpoch == navi.epoch && navi.tbtReceived) {
            // NaN 거리(데이터 감사 #7)는 뺀다. 닿는 거리를 모르면 마지막 기회까지만 안다고 본다
            val opps = navi.liveOpportunities(odometerM).filter { it.routeDistanceM.isFinite() && it.routeDistanceM >= 0.0 }
            val coverage = navi.tbtCoverageM?.let { it - navi.moved(navi.tbtOdometerM, odometerM) } ?: opps.lastOrNull()?.routeDistanceM
            // TBT가 통째로 비었으면(길안내 중 경로에는 목적지 안내라도 있다) 응답이 덜 된 것이라 아직 모른다
            if (coverage != null && coverage.isFinite() && coverage > 0.0) return Plan.Route(opps, routeHorizon(opps, coverage, remain))
        }
        if (epochAgeMs < ROUTE_TBT_WAIT_MS) return Plan.AwaitRoute(ROUTE_TBT_WAIT_MS - epochAgeMs)
        return Plan.Nearby(remainM = remain)
    }

    /**
     * TBT 경로의 범위. 기회를 [ROUTE_OPP_MAX]곳까지만 넣으면 첫 번째로 뺀 기회 위치까지가 아는 범위이고,
     * TBT 목록이 목적지보다 짧게 끝나면 그 끝까지가 아는 범위다. 둘 다 아니면 null(완전).
     * 남은 거리를 모르면 TBT 끝을 범위로 둔다 (완전하다고 말할 근거가 없다).
     */
    fun routeHorizon(opps: List<RouteOpportunity>, coverageM: Double, remainM: Double?): Double? {
        val cut = opps.getOrNull(ROUTE_OPP_MAX)?.routeDistanceM
        val short = coverageM.takeIf { remainM == null || it < remainM - COVERAGE_SLACK_M }
        return listOfNotNull(cut, short).minOrNull()
    }

    /**
     * 반경 검색의 범위. 경로 거리 ≥ 직선거리이므로, 경로 거리가 반경보다 짧은 시설은 모두 반경 검색에 들어 있다.
     * 가까운 순 [kept]곳만 넣었으면 첫 번째로 뺀 곳의 직선거리까지만 안다. [kept]가 null이면(시연) 다 넣었다.
     * 한계: 진행 방향 ±75도 밖으로 굽은 경로 위 시설은 반경 검색에 없다. 길안내 중에는 대개 TBT 경로를 쓰므로 잠깐이다.
     */
    fun nearbyHorizon(raw: List<StopCandidate>, kept: Int?): Double {
        val firstDropped = kept?.let { raw.getOrNull(it)?.straightDistanceM }
        return minOf(AHEAD_RADIUS_M, firstDropped ?: AHEAD_RADIUS_M)
    }

    /**
     * 목적지 뒤 시설을 뺀다 (D5c: 목적지 너머 시설이 '그다음 기회'가 되면 가짜 공백이 생긴다).
     * 보정한 곳은 경로 거리로, 못 한 곳은 직선거리로 판단한다(경로 거리 ≥ 직선거리라 직선거리가 넘으면 확실히 뒤다).
     */
    fun beforeDestination(list: List<StopCandidate>, remainM: Double?): List<StopCandidate> {
        if (remainM == null) return list
        return list.filter { c -> (c.routeDistanceM ?: c.straightDistanceM)?.let { it <= remainM } ?: true }
    }

    /**
     * TBT가 준 종류와 이름이 다르면 이름을 믿는다. TBT는 졸음쉼터를 SERVICEAREA로 보내기도 하고 extract는 그 종류를 그대로 둔다.
     * 그러면 '옥산졸음쉼터(서울방향)'가 같은 기본 이름의 반대편 '옥산휴게소(부산방향)'(0.3km)와 맞았다 (데이터 감사 #5).
     * 데이터의 휴게소 이름은 모두 '휴게소'를, 졸음쉼터는 모두 '졸음쉼터'를 담는다.
     */
    fun effectiveKind(name: String, tbtKind: StopKind): StopKind = when {
        name.contains("졸음쉼터") -> StopKind.DROWSY_SHELTER
        name.contains("휴게소") -> StopKind.HIGHWAY_REST_AREA
        name.contains("쉼터") -> StopKind.DROWSY_SHELTER
        else -> tbtKind
    }

    /** TBT 이름으로 찾은 행의 직선거리가 TBT 경로 거리와 어울리는지 (그 밖이면 다른 노선의 같은 이름 시설이다) */
    fun plausibleMatch(straightM: Double?, tbtRouteM: Double): Boolean {
        val s = straightM ?: return true
        return s <= tbtRouteM + MATCH_SLACK_M && s >= tbtRouteM * MATCH_MIN_STRAIGHT_RATIO - MATCH_MIN_SLACK_M
    }

    /** 같은 이름 행 하나를 requestTrip으로 잰 결과. [routeM]이 null이면 못 쟀다(모름) */
    data class Measured(val c: StopCandidate, val routeM: Double?, val directionOk: Boolean?)

    /**
     * 같은 이름 행이 여럿일 때 고른다 (서비스 감사 D6). 유턴이 필요하다고 나온 행은 뺀다. 못 잰 행은 '모름'이지 '가장 멂'이 아니다.
     * 잰 행 중 TBT 거리와 가장 가까운 행을 고르되 허용 오차를 넘으면 어느 것도 아니다. 고를 수 없으면 null
     * (부르는 쪽이 좌표 없는 tbt: 후보로 둔다: 반대 방향 좌표로 경유지를 넣는 것보다 내비 검색이 낫다).
     */
    fun chooseMeasured(measured: List<Measured>, tbtRouteM: Double): StopCandidate? {
        val best = measured
            .filter { it.routeM != null && it.directionOk != false }
            .minByOrNull { abs(it.routeM!! - tbtRouteM) } ?: return null
        val tolerance = MATCH_TOLERANCE_M + MATCH_TOLERANCE_RATIO * tbtRouteM
        return best.c.takeIf { abs(best.routeM!! - tbtRouteM) <= tolerance }
    }
}
