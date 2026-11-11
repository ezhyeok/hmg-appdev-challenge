package dev.shimpyo.coach

import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CandidateProvider의 판단 규칙 (순수 함수). 서비스 감사 스케치 FallbackCandidatesTest(D5, P5)와 TbtMatchChoiceTest(D6)를 옮겼다.
 */
class CandidateRulesTest {

    private fun c(id: String, straight: Double? = null, route: Double? = null) =
        StopCandidate(id, id, StopKind.HIGHWAY_REST_AREA, 36.0, 127.0, Facilities(), parkable = true, routeDistanceM = route, straightDistanceM = straight)

    private fun opp(km: Double) = RouteOpportunity("x", StopKind.HIGHWAY_REST_AREA, km * 1_000.0)

    // ------------------------------------------------------------ 같은 이름 고르기 (D6)

    private val busan = c("안성-부산방향")
    private val seoul = c("안성-서울방향")

    @Test
    fun bothRequestsFailedIsUnknownNotTheFirstMatch() {
        val m = listOf(CandidateRules.Measured(seoul, null, null), CandidateRules.Measured(busan, null, null))
        assertNull(CandidateRules.chooseMeasured(m, 47_000.0))
    }

    @Test
    fun onlyTheOppositeOneAnsweredIsRejectedAsImplausible() {
        val m = listOf(CandidateRules.Measured(busan, null, null), CandidateRules.Measured(seoul, 57_100.0, true))
        assertNull(CandidateRules.chooseMeasured(m, 47_000.0))
    }

    @Test
    fun bothAnsweredPicksTheClosestToTheTbtDistance() {
        val m = listOf(CandidateRules.Measured(seoul, 57_100.0, true), CandidateRules.Measured(busan, 47_500.0, true))
        assertEquals(busan, CandidateRules.chooseMeasured(m, 47_000.0))
    }

    @Test
    fun aUturnVerdictExcludesARowEvenIfItsDistanceIsClose() {
        val m = listOf(CandidateRules.Measured(seoul, 47_200.0, false), CandidateRules.Measured(busan, 48_000.0, true))
        assertEquals(busan, CandidateRules.chooseMeasured(m, 47_000.0))
    }

    @Test
    fun plausibleStraightDistanceWindow() {
        assertTrue("직선 45km, TBT 47km", CandidateRules.plausibleMatch(45_000.0, 47_000.0))
        assertFalse("직선 5km인데 TBT 40km: 다른 곳", CandidateRules.plausibleMatch(5_000.0, 40_000.0))
        assertFalse("직선이 경로보다 5km 넘게 멀 수 없다", CandidateRules.plausibleMatch(53_000.0, 47_000.0))
        assertTrue("가까운 시설은 하한 여유(2km)로 받는다", CandidateRules.plausibleMatch(300.0, 1_500.0))
        assertTrue("직선거리를 모르면 거르지 않는다", CandidateRules.plausibleMatch(null, 47_000.0))
    }

    @Test
    fun nameDecidesTheKindWhenTheTbtTypeDisagrees() {
        // 데이터: 휴게소 이름은 모두 '휴게소', 졸음쉼터는 모두 '졸음쉼터'를 담는다 (app/src/main/assets/data 확인)
        assertEquals(StopKind.DROWSY_SHELTER, CandidateRules.effectiveKind("옥산졸음쉼터(서울방향)", StopKind.HIGHWAY_REST_AREA))
        assertEquals(StopKind.DROWSY_SHELTER, CandidateRules.effectiveKind("오산쉼터", StopKind.HIGHWAY_REST_AREA))
        assertEquals(StopKind.HIGHWAY_REST_AREA, CandidateRules.effectiveKind("망향휴게소", StopKind.DROWSY_SHELTER))
        assertEquals("이름으로 모르면 TBT 종류", StopKind.HIGHWAY_REST_AREA, CandidateRules.effectiveKind("서울만남의광장", StopKind.HIGHWAY_REST_AREA))
    }

    // ------------------------------------------------------------ 범위 (D5, P5, 데이터 #7)

    @Test
    fun tbtCoverageShorterThanTheRouteIsAHorizon() {
        val four = listOf(10.0, 30.0, 50.0, 70.0).map(::opp)
        assertEquals(80_000.0, CandidateRules.routeHorizon(four, 80_000.0, 300_000.0)!!, 1e-9)
        val eleven = (1..11).map { opp(it * 10.0) }
        assertEquals("자른 목록: 11번째 기회", 110_000.0, CandidateRules.routeHorizon(eleven, 300_000.0, 300_000.0)!!, 1e-9)
        assertEquals("둘 다면 짧은 쪽", 80_000.0, CandidateRules.routeHorizon(eleven, 80_000.0, 300_000.0)!!, 1e-9)
        assertNull("목적지까지 닿고 자르지 않았으면 완전", CandidateRules.routeHorizon(listOf(opp(10.0)), 299_500.0, 300_000.0))
        assertEquals("남은 거리를 모르면 TBT 끝까지만 안다", 80_000.0, CandidateRules.routeHorizon(four, 80_000.0, null)!!, 1e-9)
    }

    @Test
    fun nearbyHorizonIsTheRadiusOrTheFirstUnrefinedStraightDistance() {
        val raw = (1..9).map { c("s$it", straight = it * 6_000.0) }
        assertEquals(42_000.0, CandidateRules.nearbyHorizon(raw, 6), 1e-9)
        assertEquals(CandidateTuning.AHEAD_RADIUS_M, CandidateRules.nearbyHorizon(raw.take(3), 6), 1e-9)
        assertEquals("보정 없이 다 넘기면 반경까지", CandidateTuning.AHEAD_RADIUS_M, CandidateRules.nearbyHorizon(raw, null), 1e-9)
    }

    @Test
    fun stopsPastTheDestinationAreDropped() {
        val list = listOf(c("a", 8_000.0, 9_000.0), c("b", 30_000.0, 40_000.0), c("u", 20_000.0, null), c("far", 30_000.0, null), c("none"))
        assertEquals(listOf("a", "u", "none"), CandidateRules.beforeDestination(list, 25_000.0).map { it.id })
        assertEquals(list, CandidateRules.beforeDestination(list, null))
    }

    // ------------------------------------------------------------ 어느 경로로 만들지

    private val car = Fix(37.0, 127.1, 180f)
    private fun guiding(received: Boolean, opps: List<RouteOpportunity> = emptyList(), coverage: Double? = 100_000.0, tbtEpoch: Int = 1) = NaviSnapshot(
        epoch = 1, mode = NaviMode.ROUTE_GUIDING, route = RouteState(guiding = true, remainDistanceM = 100_000.0),
        opportunities = opps, tbtReceived = received, tbtCoverageM = coverage, tbtEpoch = tbtEpoch,
    )

    @Test
    fun planFollowsDataLocationAndTbt() {
        assertEquals(CandidateRules.Plan.NoData, CandidateRules.plan(false, car, NaviSnapshot(), 0.0, 0))
        assertEquals(CandidateRules.Plan.NoLocation, CandidateRules.plan(true, null, NaviSnapshot(), 0.0, 0))
        assertEquals(CandidateRules.Plan.Nearby(null), CandidateRules.plan(true, car, NaviSnapshot(), 0.0, 0))
        assertTrue(CandidateRules.plan(true, car, guiding(true, listOf(opp(10.0))), 0.0, 0) is CandidateRules.Plan.Route)
        assertTrue("받았는데 비었다 = 경로 위에 없다", CandidateRules.plan(true, car, guiding(true), 0.0, 0) is CandidateRules.Plan.Route)
        assertEquals(CandidateRules.Plan.AwaitRoute(CandidateTuning.ROUTE_TBT_WAIT_MS - 1_000), CandidateRules.plan(true, car, guiding(false), 0.0, 1_000))
        assertEquals("다른 epoch의 TBT는 아직 안 받은 것", CandidateRules.Plan.AwaitRoute(CandidateTuning.ROUTE_TBT_WAIT_MS), CandidateRules.plan(true, car, guiding(true, tbtEpoch = 0), 0.0, 0))
        assertEquals("목록이 통째로 비면(닿는 거리 0) 아직 모르는 것", CandidateRules.Plan.AwaitRoute(CandidateTuning.ROUTE_TBT_WAIT_MS), CandidateRules.plan(true, car, guiding(true, coverage = 0.0), 0.0, 0))
        assertEquals(CandidateRules.Plan.Nearby(100_000.0), CandidateRules.plan(true, car, guiding(false), 0.0, CandidateTuning.ROUTE_TBT_WAIT_MS))
    }
}
