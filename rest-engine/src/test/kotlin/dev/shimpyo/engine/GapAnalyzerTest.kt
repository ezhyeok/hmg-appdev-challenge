package dev.shimpyo.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GapAnalyzerTest {
    private val cfg = CoachConfig()
    private val gap = GapAnalyzer(cfg)

    private fun approx(e: Double, a: Double, tol: Double, msg: String = "") = assertTrue(abs(e - a) <= tol, "$msg expected $e±$tol but $a")

    /** 100km/h 자유 속도, 목적지(기본 300km), 정체 목록 */
    private fun route(vararg jams: CongestionSegment, destM: Double = 300_000.0): RouteContext {
        val jamLen = jams.sumOf { it.lengthM }
        val jamTime = jams.sumOf { it.durationS }
        val rt = (destM - jamLen) / (100 / 3.6) + jamTime
        return RouteContext(cfg).apply {
            prepare(RouteState(guiding = true, remainDistanceM = destM, remainTimeS = rt, congestion = jams.toList()), 100f)
        }
    }

    private fun opps(ctx: RouteContext, vararg c: StopCandidate): List<Opportunity> =
        ArrayList<Opportunity>().also { ctx.opportunities(c.toList(), it, RouteContext::isRestroomOpportunity) }

    @Test
    fun ordersByRouteDistanceAndFallsBackToStraightDistance() {
        val ctx = route()
        val list = opps(
            ctx,
            stop("far", 30_000.0),
            stop("straight", null, straightM = 10_000.0), // 13km로 추정
            stop("near", 5_000.0),
            stop("unknown", null),
            stop("behind", -2_000.0),
            stop("closed", 1_000.0, openNow = false),
            stop("noParking", 1_000.0, parkable = false),
            stop("wrongWay", 1_000.0, directionOk = false),
            stop("noRestroom", 1_000.0, facilities = Facilities(restroom = false)),
        )
        assertEquals(listOf("near", "straight", "far"), list.map { it.stop.id })
        approx(13_000.0, list[1].distanceM, 1e-6)
        approx(5_000.0 / (100 / 3.6), list[0].arriveS, 1e-6)
        approx(100 / 3.6, ctx.vFreeMps, 1e-9, "정체 몫을 뺀 자유 속도")
    }

    @Test
    fun congestionDelayIsAddedOnlyBeyondTheJamStart() {
        val ctx = route(CongestionSegment(startDistanceM = 10_000.0, lengthM = 5_000.0, durationS = 1_800.0))
        val v = 100 / 3.6
        val list = opps(ctx, stop("before", 8_000.0), stop("inside", 12_500.0), stop("after", 20_000.0), stop("nav", 20_000.0, etaS = 999.0))
        val byId = list.associateBy { it.stop.id }
        approx(8_000 / v, byId.getValue("before").arriveS, 1e-6)
        approx(10_000 / v + 900.0, byId.getValue("inside").arriveS, 1e-6, "정체 절반 → 정체 시간 절반")
        approx(15_000 / v + 1_800.0, byId.getValue("after").arriveS, 1e-6)
        assertEquals(999.0, byId.getValue("nav").arriveS, "내비 ETA는 그대로")
    }

    @Test
    fun carInsideJamUsesRemainingPart() {
        val ctx = route(CongestionSegment(startDistanceM = -3_000.0, lengthM = 12_000.0, durationS = 2_400.0))
        assertEquals(1, ctx.jamN)
        assertEquals(0.0, ctx.jamStart[0])
        approx(9_000.0, ctx.jamEnd[0], 1e-9)
        approx(1_800.0, ctx.jamDur[0], 1e-9)
        val passed = route(CongestionSegment(startDistanceM = -20_000.0, lengthM = 5_000.0, durationS = 600.0))
        assertEquals(0, passed.jamN)
    }

    @Test
    fun noRouteFallsBackToSpeedThenHighwayAverage() {
        val a = RouteContext(cfg).apply { prepare(null, 90f) }
        approx(90 / 3.6, a.vFreeMps, 1e-9)
        val b = RouteContext(cfg).apply { prepare(null, 10f) }
        approx(cfg.highwayAvgSpeedKph / 3.6, b.vFreeMps, 1e-9)
        val c = RouteContext(cfg).apply { prepare(RouteState(guiding = false), null) }
        approx(cfg.highwayAvgSpeedKph / 3.6, c.vFreeMps, 1e-9)
        assertTrue(c.destS.isNaN())
    }

    @Test
    fun gaugeRulePicksLastOpportunityBeforeTheDecisionDeadline() {
        val ctx = route()
        // 100km/h: 10km = 6분
        val list = opps(ctx, stop("a", 10_000.0), stop("b", 40_000.0), stop("c", 100_000.0))
        val lc = gap.analyze(list, ctx, deadlineMin = 50.0, sensitive = false, preferredMaxGapMin = null)
        assertNotNull(lc)
        assertEquals("b", lc.opp.stop.id, "b(24분)까지는 버티고 c(60분)까지는 확률 0.3을 넘는다")
        assertEquals(GapAnalyzer.Trigger.GAUGE, lc.trigger)
        approx(36.0, lc.gapS / 60.0, 1e-6)
        assertNull(gap.analyze(list, ctx, deadlineMin = 190.0, sensitive = false, preferredMaxGapMin = null), "목적지(180분)가 마감 안이면 없다")
        val toDest = gap.analyze(list, ctx, deadlineMin = 170.0, sensitive = false, preferredMaxGapMin = null)
        assertEquals("c", toDest?.opp?.stop?.id, "마지막 후보 다음은 목적지(180분) > 170분")
    }

    @Test
    fun denseRestAreasAreNotAGap() {
        val ctx = route()
        // 15km(9분)마다 휴게소: 마감이 와도 '공백'이 아니다
        val dense = opps(ctx, *(1..20).map { stop("d$it", it * 15_000.0) }.toTypedArray())
        assertNull(gap.analyze(dense, ctx, deadlineMin = 30.0, sensitive = false, preferredMaxGapMin = null))
        // 사이에 30km 빈 곳이 있으면 그 앞이 마지막 기회
        val holey = opps(ctx, stop("a", 15_000.0), stop("b", 30_000.0), stop("c", 60_000.0))
        assertEquals("b", gap.analyze(holey, ctx, deadlineMin = 30.0, sensitive = false, preferredMaxGapMin = null)?.opp?.stop?.id)
    }

    @Test
    fun noReachableOpportunityOrNoOccupantsMeansNoLastChance() {
        val ctx = route()
        val list = opps(ctx, stop("a", 20_000.0))
        assertNull(gap.analyze(list, ctx, deadlineMin = 5.0, sensitive = false, preferredMaxGapMin = null))
        assertNull(gap.analyze(emptyList(), ctx, deadlineMin = 50.0, sensitive = true, preferredMaxGapMin = 30))
        assertNull(gap.analyze(list, ctx, deadlineMin = null, sensitive = true, preferredMaxGapMin = 15), "탑승자가 없으면 판단하지 않는다")
    }

    @Test
    fun congestionRuleUsesSensitiveThreshold() {
        val jam = CongestionSegment(startDistanceM = 30_000.0, lengthM = 10_000.0, durationS = 25 * 60.0)
        val ctx = route(jam)
        // a 6분, b 15분, c = 50km/100km/h + 25분 = 55분
        val list = opps(ctx, stop("a", 10_000.0), stop("b", 25_000.0), stop("c", 60_000.0))
        val sensitive = gap.analyze(list, ctx, deadlineMin = 50.0, sensitive = true, preferredMaxGapMin = null)
        assertNotNull(sensitive)
        assertEquals("b", sensitive.opp.stop.id, "정체 직전 마지막 기회")
        assertEquals(GapAnalyzer.Trigger.CONGESTION, sensitive.trigger)
        approx(25.0, sensitive.congestionS / 60.0, 1e-9)
        val adultOnly = gap.analyze(list, ctx, deadlineMin = 50.0, sensitive = false, preferredMaxGapMin = null)
        assertEquals(GapAnalyzer.Trigger.GAUGE, adultOnly?.trigger, "25분 정체는 성인 기준(30분) 미만이라 게이지 규칙만 남는다")
        assertEquals("b", adultOnly?.opp?.stop?.id)
    }

    @Test
    fun opportunityInsideJamOrLowProbabilitySuppressesCongestionRule() {
        val jam = CongestionSegment(startDistanceM = 30_000.0, lengthM = 10_000.0, durationS = 40 * 60.0)
        val ctx = route(jam, destM = 100_000.0) // 목적지 도착 94분
        val withInside = opps(ctx, stop("b", 25_000.0), stop("inJam", 35_000.0), stop("c", 60_000.0))
        val inside = gap.analyze(withInside, ctx, deadlineMin = 80.0, sensitive = true, preferredMaxGapMin = null)
        assertTrue(inside?.trigger != GapAnalyzer.Trigger.CONGESTION, "정체 안에 화장실이 있으면 정체 규칙은 아니다")
        val list = opps(ctx, stop("b", 25_000.0), stop("c", 60_000.0))
        // c 도착 70분 < 마감 170분: 정체 뒤 첫 기회까지 필요해질 확률이 0.3 미만 → 묻지 않는다
        assertNull(gap.analyze(list, ctx, deadlineMin = 170.0, sensitive = false, preferredMaxGapMin = null))
        val tight = gap.analyze(list, ctx, deadlineMin = 60.0, sensitive = false, preferredMaxGapMin = null)
        assertEquals("b", tight?.opp?.stop?.id)
        assertEquals(GapAnalyzer.Trigger.CONGESTION, tight?.trigger, "같은 곳이면 정체 이유가 더 구체적이다")
    }

    @Test
    fun coverageRuleForUrgencyConditions() {
        val ctx = route()
        val list = opps(ctx, stop("a", 10_000.0), stop("b", 30_000.0), stop("c", 90_000.0)) // b→c 36분
        val lc = gap.analyze(list, ctx, deadlineMin = 190.0, sensitive = true, preferredMaxGapMin = 30)
        assertEquals("b", lc?.opp?.stop?.id)
        assertEquals(GapAnalyzer.Trigger.PREFERENCE, lc?.trigger)
        // 45분 기준이면 b→c(36분)는 괜찮고, c→목적지(126분)가 길다
        val lc45 = gap.analyze(list, ctx, deadlineMin = 190.0, sensitive = true, preferredMaxGapMin = 45)
        assertEquals("c", lc45?.opp?.stop?.id)
        assertEquals(GapAnalyzer.Trigger.PREFERENCE, lc45?.trigger)
        assertNull(gap.analyze(list, ctx, deadlineMin = 190.0, sensitive = true, preferredMaxGapMin = 130))
        assertEquals(20, cfg.oabMaxOpportunityGapMin)
        assertEquals(15, cfg.ibsMaxOpportunityGapMin)
    }

    @Test
    fun earliestDecisionWinsAcrossRules() {
        val jam = CongestionSegment(startDistanceM = 50_000.0, lengthM = 10_000.0, durationS = 40 * 60.0)
        val ctx = route(jam)
        // a 12분, b 27분, c = 70km/100km/h + 40분 = 82분
        val list = opps(ctx, stop("a", 20_000.0), stop("b", 45_000.0), stop("c", 80_000.0))
        // 마감 20분: 정체 규칙은 b지만 b(27분)까지도 못 버틴다 → 지금 정해야 하는 a
        val lc = gap.analyze(list, ctx, deadlineMin = 20.0, sensitive = true, preferredMaxGapMin = null)
        assertEquals("a", lc?.opp?.stop?.id)
        assertEquals(GapAnalyzer.Trigger.GAUGE, lc?.trigger)
        val lc75 = gap.analyze(list, ctx, deadlineMin = 75.0, sensitive = true, preferredMaxGapMin = null)
        assertEquals("b", lc75?.opp?.stop?.id)
        assertEquals(GapAnalyzer.Trigger.CONGESTION, lc75?.trigger)
    }

    @Test
    fun askWindowIsTwoToFiveKm() {
        val ctx = route()
        fun lcAt(d: Double) = gap.analyze(opps(ctx, stop("x", d), stop("y", d + 100_000.0)), ctx, 40.0, false, null)!!
        assertTrue(!gap.inAskWindow(lcAt(5_500.0)))
        assertTrue(gap.inAskWindow(lcAt(5_000.0)))
        assertTrue(gap.inAskWindow(lcAt(2_000.0)))
        assertTrue(!gap.inAskWindow(lcAt(1_900.0)))
    }
}
