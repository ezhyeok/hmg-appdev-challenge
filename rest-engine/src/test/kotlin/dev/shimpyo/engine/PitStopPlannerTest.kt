package dev.shimpyo.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PitStopPlannerTest {
    private val cfg = CoachConfig()
    private val texts = CoachTexts()
    private val planner = PitStopPlanner(cfg, texts)
    private val local = LocalClock(SEOUL)
    private val v = 100 / 3.6 // m/s

    /** 분 → 100km/h 경로 거리 */
    private fun km(min: Double) = min * 60 * v

    private fun ctx(destMin: Double?): RouteContext = RouteContext(cfg).apply {
        prepare(if (destMin == null) null else RouteState(true, remainDistanceM = km(destMin), remainTimeS = destMin * 60), 100f)
    }

    private fun opps(c: RouteContext, vararg s: StopCandidate) =
        ArrayList<Opportunity>().also { c.opportunities(s.toList(), it, RouteContext::isRestroomOpportunity) }

    private fun needs(
        firstMin: Double,
        fullMin: Double,
        fatigueMin: Double = 120.0,
        care: List<PitStopPlanner.Care> = emptyList(),
        rangeKm: Double? = null,
    ) = PitStopPlanner.Needs(
        firstLegMaxS = firstMin * 60, restroomFullS = fullMin * 60, fatigueFullS = { fatigueMin * 60 },
        drivenNowS = 0.0, restLabelS = cfg.fatigueWatchMin * 60.0, care = care, rangeM = rangeKm?.let { it * 1000 },
    )

    private val morning = kst(2026, 11, 13, 8, 0)

    @Test
    fun noStopWhenDestinationIsWithinLimits() {
        val c = ctx(60.0)
        val plan = planner.plan(opps(c, stop("a", km(20.0)), stop("b", km(40.0))), c, needs(90.0, 90.0), morning, local)
        assertTrue(plan.stops.isEmpty())
        assertEquals(0, plan.totalStopMin)
        assertEquals("PLAN_NONE_NEEDED", plan.notes.single().code)
    }

    @Test
    fun minimizesStopsUnderGapLimits() {
        val c = ctx(240.0)
        val s = (1..11).map { stop("s$it", km(it * 20.0)) }.toTypedArray() // 20분 간격
        val plan = planner.plan(opps(c, *s), c, needs(firstMin = 50.0, fullMin = 90.0), morning, local)
        // 첫 정차 ≤ 50분, 그다음 ≤ 90분, 목적지 240분 → 최소 3번 (예: 40, 120, 200)
        assertEquals(3, plan.stops.size, plan.stops.map { it.stop.id }.toString())
        var prev = 0.0
        var limit = 50.0
        val arrivals = plan.stops.map { (it.stop.routeDistanceM!! / v) / 60 }
        for (a in arrivals + 240.0) {
            assertTrue(a - prev <= limit + 1e-6, "구간 ${a - prev} > $limit")
            prev = a
            limit = 90.0
        }
        assertEquals(3 * cfg.restroomDwellMin, plan.totalStopMin)
        assertTrue(plan.stops.all { Purpose.RESTROOM in it.purposes && it.dwellMin == cfg.restroomDwellMin })
    }

    @Test
    fun arriveInIncludesEarlierDwell() {
        val c = ctx(200.0)
        val plan = planner.plan(opps(c, stop("a", km(60.0)), stop("b", km(140.0))), c, needs(80.0, 90.0), morning, local)
        assertEquals(listOf("a", "b"), plan.stops.map { it.stop.id })
        assertEquals(60.0 * 60, plan.stops[0].arriveInS, 1e-6)
        val before = cfg.restroomDwellMin + cfg.stopOverheadMin
        assertEquals((140.0 + before) * 60, plan.stops[1].arriveInS, 1e-6, "앞 정차 7분 + 진출입 5분이 더해진다")
    }

    @Test
    fun infeasibleGapStillReturnsBestEffortWithNote() {
        val c = ctx(300.0)
        val plan = planner.plan(opps(c, stop("a", km(30.0)), stop("b", km(200.0))), c, needs(60.0, 90.0), morning, local)
        assertEquals(listOf("a", "b"), plan.stops.map { it.stop.id })
        assertTrue(plan.notes.any { it.code == "PLAN_GAP" })
    }

    @Test
    fun noCandidatesAndNoDestination() {
        val c = ctx(null)
        assertEquals("PLAN_NO_CANDIDATES", planner.plan(emptyList(), c, needs(60.0, 90.0), morning, local).notes.single().code)
        val plan = planner.plan(opps(c, stop("a", 30_000.0), stop("b", 150_000.0)), c, needs(60.0, 90.0), morning, local)
        assertTrue(plan.notes.any { it.code == "PLAN_NO_DESTINATION" })
    }

    @Test
    fun prefersFoodStopInMealWindowAndMergesPurposes() {
        val c = ctx(180.0)
        val lunch = kst(2026, 11, 13, 11, 20)
        val plan = planner.plan(
            opps(
                c,
                stop("r1", km(20.0)),
                stop("r2", km(45.0), facilities = Facilities(food = true)),
                stop("r3", km(100.0)),
                stop("r4", km(140.0), facilities = Facilities(food = true)),
            ),
            c, needs(firstMin = 59.0, fullMin = 89.0), lunch, local,
        )
        assertEquals(listOf("r2", "r3"), plan.stops.map { it.stop.id })
        assertEquals(setOf(Purpose.RESTROOM, Purpose.MEAL), plan.stops[0].purposes)
        assertEquals(cfg.mealDwellMin, plan.stops[0].dwellMin)
        assertTrue(plan.notes.any { it.code == "PLAN_MEAL" && it.text.contains("r2") })
    }

    @Test
    fun noMealOutsideMealWindows() {
        val c = ctx(180.0)
        val plan = planner.plan(
            opps(c, stop("r1", km(20.0)), stop("r2", km(45.0), facilities = Facilities(food = true)), stop("r3", km(100.0))),
            c, needs(firstMin = 59.0, fullMin = 89.0), morning, local,
        )
        assertTrue(plan.stops.none { Purpose.MEAL in it.purposes })
    }

    @Test
    fun refuelsWhenRangeIsShort() {
        val c = ctx(180.0) // 300km
        val fuel = Facilities(fuel = true)
        val plan = planner.plan(
            opps(c, stop("a", km(40.0)), stop("b", km(80.0), facilities = fuel), stop("c", km(120.0)), stop("d", km(150.0), facilities = fuel)),
            c, needs(firstMin = 100.0, fullMin = 100.0, rangeKm = 200.0), morning, local,
        )
        // 남은 150km(여유 50km) 안에 주유해야 한다 → b(133km)
        val fuelStop = plan.stops.single { Purpose.FUEL in it.purposes }
        assertEquals("b", fuelStop.stop.id)
        assertTrue(plan.notes.none { it.code == "PLAN_GAP" })
    }

    @Test
    fun infantFeedingPrefersNursingFacilities() {
        val c = ctx(150.0)
        val care = listOf(
            PitStopPlanner.Care(Purpose.FEEDING, 60 * 60.0, 150 * 60.0, { it.facilities.diaperTable || it.facilities.nursingRoom }, PitStopPlanner.FEEDING_MISMATCH_PENALTY),
        )
        val plan = planner.plan(
            opps(c, stop("plain", km(50.0)), stop("nursing", km(60.0), facilities = Facilities(nursingRoom = true))),
            c, needs(firstMin = 90.0, fullMin = 148.0, care = care), morning, local,
        )
        assertEquals(listOf("nursing"), plan.stops.map { it.stop.id })
        assertTrue(Purpose.FEEDING in plan.stops.single().purposes)
        assertEquals(cfg.feedingDwellMin, plan.stops.single().dwellMin)
    }

    @Test
    fun deterministic() {
        val c = ctx(300.0)
        val s = (1..14).map { stop("s$it", km(it * 20.0), facilities = Facilities(food = it % 3 == 0)) }.toTypedArray()
        val a = planner.plan(opps(c, *s), c, needs(70.0, 90.0), kst(2026, 11, 13, 10, 0), local)
        val b = planner.plan(opps(c, *s), c, needs(70.0, 90.0), kst(2026, 11, 13, 10, 0), local)
        assertEquals(a, b)
    }

    @Test
    fun dwellIsMaxOfMergedPurposes() {
        assertEquals(cfg.mealDwellMin, planner.dwellMin(setOf(Purpose.RESTROOM, Purpose.MEAL, Purpose.REST)))
        assertEquals(cfg.restDwellMin, planner.dwellMin(setOf(Purpose.RESTROOM, Purpose.REST)))
        assertEquals(cfg.restroomDwellMin, planner.dwellMin(setOf(Purpose.RESTROOM)))
        assertEquals(0, planner.dwellMin(emptySet()))
    }
}
