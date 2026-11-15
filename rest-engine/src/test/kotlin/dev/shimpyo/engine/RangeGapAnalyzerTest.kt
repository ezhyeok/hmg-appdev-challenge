package dev.shimpyo.engine

import kotlin.test.*

class RangeGapAnalyzerTest {
    @Test fun energyCoverageDoesNotBorrowTheLongerRestroomCoverage() {
        val route = RouteContext(CoachConfig()).apply { prepare(RouteState(true, congestionBasisKnown = true, remainDistanceM = 200_000.0,
            candidatesUntilM = 100_000.0, energyCandidatesUntilM = 8_000.0), 100f) }
        val opps = ArrayList<Opportunity>()
        route.opportunities(listOf(stop("ev", 4_500.0, facilities = Facilities(evCharger = true))), opps) { true }
        assertEquals(100_000.0, route.knownEndM)
        assertEquals(8_000.0, route.knownEnergyEndM)
        assertNull(GapAnalyzer(CoachConfig()).analyzeRange(opps, route, 40_000.0))
        val tight = assertNotNull(GapAnalyzer(CoachConfig()).analyzeRange(opps, route, 6_000.0))
        assertTrue(tight.gapLowerBound)
        assertFalse(tight.endIsDestination)
        assertEquals(8_000.0, tight.rangeEndM)
    }
    private val config = CoachConfig()
    private fun analyze(usable: Double?, distances: List<Double>, end: Double? = 200_000.0, horizon: Double? = null, jam: Boolean = false): GapAnalyzer.LastChance? {
        val route = RouteContext(config).apply { prepare(RouteState(true, congestionBasisKnown = true, remainDistanceM = end, candidatesUntilM = horizon,
            congestion = if (jam) listOf(CongestionSegment(8_000.0, 20_000.0, 7200.0)) else emptyList()), 100f) }
        val opps = ArrayList<Opportunity>()
        route.opportunities(distances.mapIndexed { i, d -> stop("$i", d, facilities = Facilities(evCharger = true)) }, opps) { RouteContext.isFuelOpportunity(it, EnergyKind.ELECTRIC) }
        return GapAnalyzer(config).analyzeRange(opps, route, usable)
    }
    @Test fun picksLastWithinUsableAndComputesTimeGap() {
        val lc = assertNotNull(analyze(40_000.0, listOf(6_000.0, 30_000.0, 100_000.0)))
        assertEquals("1", lc.opp.stop.id)
        assertEquals(GapAnalyzer.Trigger.RANGE, lc.trigger)
        assertEquals(70_000.0, lc.gapM)
        assertEquals(70_000.0 / (100 / 3.6), lc.gapS, 0.001)
    }
    @Test fun jamChangesTimeButNotDistanceDecision() {
        val free = assertNotNull(analyze(40_000.0, listOf(6_000.0, 100_000.0)))
        val jam = assertNotNull(analyze(40_000.0, listOf(6_000.0, 100_000.0), jam = true))
        assertEquals(free.opp.stop.id, jam.opp.stop.id)
        assertEquals(free.gapM, jam.gapM)
        assertTrue(jam.gapS > free.gapS)
    }
    @Test fun tightRangePicksFirstStillSafeExitIncludingEquality() {
        for (usable in listOf(-10_000.0, 0.0, 6_000.0)) {
            val lc = assertNotNull(analyze(usable, listOf(500.0, 6_000.0, 100_000.0)))
            assertEquals("1", lc.opp.stop.id)
            assertTrue(lc.rangeTight)
        }
    }
    @Test fun destinationOrHorizonWithinRangeIsNotEvidenceOfAGap() {
        assertNull(analyze(40_000.0, listOf(6_000.0, 100_000.0), end = 40_000.0))
        assertNull(analyze(40_000.0, listOf(6_000.0, 100_000.0), horizon = 30_000.0))
        assertNull(analyze(null, listOf(6_000.0, 100_000.0)))
    }
    @Test fun knownEmptyHorizonBoundsGapAndNeverUsesStopsBeyondIt() {
        val lc = assertNotNull(analyze(40_000.0, listOf(6_000.0, 100_000.0), horizon = 55_000.0))
        assertEquals(49_000.0, lc.gapM)
        assertTrue(lc.gapLowerBound)
        assertNull(analyze(60_000.0, listOf(6_000.0, 100_000.0), horizon = 55_000.0))
    }
    @Test fun noEndAndNoNextOpportunityCannotProveAGap() {
        assertNull(analyze(40_000.0, listOf(6_000.0), end = null))
    }
    @Test fun filterHonorsEnergyAndAvailability() {
        val gas = stop("gas", 6000.0, facilities = Facilities(fuel = true))
        val ev = stop("ev", 6000.0, facilities = Facilities(evCharger = true))
        assertTrue(RouteContext.isFuelOpportunity(gas, EnergyKind.FUEL))
        assertFalse(RouteContext.isFuelOpportunity(ev, EnergyKind.FUEL))
        assertTrue(RouteContext.isFuelOpportunity(ev, null))
        assertTrue(RouteContext.isFuelOpportunity(gas, null))
        assertFalse(RouteContext.isFuelOpportunity(ev.copy(openNow = false), EnergyKind.ELECTRIC))
        assertFalse(RouteContext.isFuelOpportunity(ev.copy(directionOk = false), EnergyKind.ELECTRIC))
    }
}
