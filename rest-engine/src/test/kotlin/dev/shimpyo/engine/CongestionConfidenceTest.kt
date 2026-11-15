package dev.shimpyo.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CongestionConfidenceTest {
    private val cfg = CoachConfig()

    @Test
    fun unknownBasisSuppressesCongestionClaimsButKeepsTbtGapAdvice() {
        val route = RouteState(true, remainDistanceM = 200_000.0, remainTimeS = 10_000.0,
            congestion = listOf(CongestionSegment(5_000.0, 30_000.0, 3_600.0)))
        fun analyze(known: Boolean): GapAnalyzer.LastChance? {
            val ctx = RouteContext(cfg).apply { prepare(route.copy(congestionBasisKnown = known), 100f) }
            val stops = listOf(stop("before", 3_000.0), stop("after", 100_000.0))
            val opps = ArrayList<Opportunity>()
            ctx.opportunities(stops, opps, RouteContext::isRestroomOpportunity)
            return GapAnalyzer(cfg).analyze(opps, ctx, 20.0, true, null)
        }
        assertEquals(GapAnalyzer.Trigger.CONGESTION, assertNotNull(analyze(true)).trigger)
        assertEquals(GapAnalyzer.Trigger.GAUGE, assertNotNull(analyze(false)).trigger)
    }

    @Test
    fun losingConfidenceWithdrawsTheOldJamQuestionAndItsAcceptedChip() {
        val t = kst(2026, 11, 15, 14, 0)
        val known = CoachInput(now = t, gear = Gear.DRIVE, speedKph = 100f,
            route = RouteState(true, remainDistanceM = 200_000.0, remainTimeS = 10_000.0,
                congestion = listOf(CongestionSegment(5_000.0, 30_000.0, 3_600.0)), congestionBasisKnown = true),
            candidates = listOf(stop("before", 2_100.0), stop("after", 100_000.0)))
        fun engine() = CoachEngine(profile = TripProfile(listOf(child(age = 7)), tripStart = t - 50 * MIN,
            lastRestroomAt = mapOf("kid" to t - 50 * MIN)), random = kotlin.random.Random(7))
        for (accept in listOf(false, true)) {
            val e = engine()
            val offered = e.update(known)
            val prompt = assertNotNull(offered.prompt)
            assertTrue(prompt.reasons.any { it.code == "LAST_CHANCE_CONGESTION" })
            if (accept) e.onPromptResult(prompt.id, true, t)
            val out = e.update(known.copy(now = t + SEC, route = known.route!!.copy(congestionBasisKnown = false)))
            assertTrue(out.prompt?.reasons.orEmpty().none { it.code.endsWith("CONGESTION") })
            assertTrue(out.next?.reasons.orEmpty().none { it.code.endsWith("CONGESTION") })
            if (accept) {
                assertEquals("before", out.next?.stop?.id)
                assertEquals(offered.next?.purposes, out.next?.purposes)
                assertEquals("NEXT_ACCEPTED", out.next?.reasons?.first()?.code)
            }
        }
    }

    @Test
    fun unavailableEmptyListDoesNotInferFreeSpeedFromWholeRouteEta() {
        val route = RouteState(true, remainDistanceM = 100_000.0, remainTimeS = 10_000.0)
        val ctx = RouteContext(cfg).apply { prepare(route, 90f) }
        assertTrue(!ctx.congestionBasisKnown)
        assertEquals(25.0, ctx.vFreeMps, 0.001)
        assertEquals(10_000.0, ctx.destinationS, "목적지 내비 ETA는 유지")
        ctx.prepare(route.copy(congestionBasisKnown = true), 90f)
        assertTrue(ctx.congestionBasisKnown)
        assertEquals(10.0, ctx.vFreeMps, 0.001)
    }
}
