package dev.shimpyo.engine.audit

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.CoachTexts
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.LocalClock
import dev.shimpyo.engine.PitStopPlanner
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.RouteContext
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.SEOUL
import dev.shimpyo.engine.kst
import dev.shimpyo.engine.stop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PitStopPlanner: 후보 0·1개, 목적지 너머 후보, 주유·EV 거리, 식사 시간대 */
class AuditPlannerTest {
    private val cfg = CoachConfig()
    private val planner = PitStopPlanner(cfg, CoachTexts())
    private val local = LocalClock(SEOUL)
    private val morning = kst(2026, 11, 13, 8, 0)

    private fun km(min: Double) = min * 60 * V100

    private fun ctx(destMin: Double?): RouteContext = RouteContext(cfg).apply {
        prepare(if (destMin == null) null else RouteState(true, remainDistanceM = km(destMin), remainTimeS = destMin * 60), 100f)
    }

    private fun needs(firstMin: Double, fullMin: Double, rangeKm: Double? = null) = PitStopPlanner.Needs(
        firstLegMaxS = firstMin * 60, restroomFullS = fullMin * 60, fatigueFullS = { 120 * 60.0 },
        drivenNowS = 0.0, restLabelS = cfg.fatigueWatchMin * 60.0, care = emptyList(), rangeM = rangeKm?.let { it * 1000 },
    )

    @Test
    fun aFuelShortfallIsNotCalledARestroomGap() {
        // 회귀(감사 L1): 주유 위반까지 모두 PLAN_GAP '화장실 사이가 먼 구간'으로 안내했다. 여기서는 20분마다 화장실이 있고 주유할 곳만 없다
        val c = ctx(destMin = 120.0)
        val s = (1..5).map { stop("s$it", km(it * 20.0)) }.toTypedArray()
        val plan = planner.plan(opps(c, *s), c, needs(firstMin = 90.0, fullMin = 90.0, rangeKm = 100.0), morning, local)
        assertTrue(plan.notes.none { it.code == "PLAN_GAP" }, plan.notes.toString())
        assertTrue(plan.notes.any { it.code.startsWith("PLAN_FUEL") }, "주유할 곳이 없다는 안내: ${plan.notes}")
    }

    // ---------------- 지금 맞는 경계 ----------------

    @Test
    fun noCandidatesSaysSo() {
        val c = ctx(60.0)
        val plan = planner.plan(emptyList(), c, needs(90.0, 90.0), morning, local)
        assertTrue(plan.stops.isEmpty())
        assertEquals("PLAN_NO_CANDIDATES", plan.notes.single().code)
    }

    @Test
    fun oneCandidatePastTheDestinationIsNeverPlanned() {
        val c = ctx(30.0)
        val plan = planner.plan(opps(c, stop("past", km(40.0))), c, needs(90.0, 90.0), morning, local)
        assertTrue(plan.stops.isEmpty(), plan.stops.toString())
        assertTrue(plan.notes.any { it.code == "PLAN_NONE_NEEDED" }, plan.notes.toString())
    }

    @Test
    fun oneCandidateAndAnOverdueFirstLegSaysStopNow() {
        val c = ctx(60.0)
        val plan = planner.plan(opps(c, stop("only", km(10.0))), c, needs(firstMin = 0.0, fullMin = 90.0), morning, local)
        assertEquals(listOf("only"), plan.stops.map { it.stop.id })
        assertTrue(plan.notes.any { it.code == "PLAN_NOW" }, plan.notes.toString())
    }

    @Test
    fun fuelStopIsMergedWithTheRestroomStop() {
        val c = ctx(destMin = 120.0)
        val s = arrayOf(stop("s1", km(20.0)), stop("gas", km(25.0), facilities = Facilities(fuel = true)), stop("s3", km(60.0)))
        val plan = planner.plan(opps(c, *s), c, needs(firstMin = 90.0, fullMin = 90.0, rangeKm = 100.0), morning, local)
        assertTrue(plan.stops.any { it.stop.id == "gas" && Purpose.FUEL in it.purposes }, plan.stops.toString())
        assertTrue(plan.notes.none { it.code == "PLAN_GAP" }, plan.notes.toString())
    }
}
