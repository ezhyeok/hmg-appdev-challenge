package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.*

class RangeLastChanceTest {
    private val morning = kst(2026, 11, 14, 10, 0)
    private val afternoon = kst(2026, 11, 14, 15, 0)
    private fun rangeReason(
        range: Double = 36.0, nextM: Double? = 24_500.0, destinationM: Double = 200_000.0,
        horizonM: Double? = null, energy: EnergyKind? = EnergyKind.ELECTRIC, config: CoachConfig = CoachConfig(),
    ): String {
        val engine = CoachEngine(config = config, profile = TripProfile(listOf(adult()), tripStart = morning))
        val out = engine.update(CoachInput(morning, Gear.DRIVE, speedKph = 100f, rangeKm = range, energy = energy,
            route = RouteState(true, remainDistanceM = destinationM, candidatesUntilM = horizonM),
            candidates = listOfNotNull(4_500.0, nextM).mapIndexed { i, m ->
                stop("energy$i", m, facilities = Facilities(evCharger = true, fuel = true))
            }))
        return assertNotNull(out.next).reasons.single { it.code == "RANGE_LAST_CHANCE" }.text
    }

    @Test fun nextEnergyReasonShowsRangeLeftFromHereWithoutSubtractingReserveAgain() {
        assertEquals("여기를 지나면 다음 충전할 곳에서 주행가능거리가 11km 남아요 (예비 20km 미만)", rangeReason())
    }

    @Test fun nextEnergyAtOrBeyondRangeCannotBeReached() {
        for (range in listOf(24.5, 24.4)) {
            assertEquals("여기를 지나면 다음 충전할 곳까지 닿지 못해요", rangeReason(range = range))
        }
    }

    @Test fun destinationWithoutNextEnergyShowsRangeLeft() {
        // 목록을 자르지 않았거나 목적지까지 포함하면 RouteContext.horizonM은 NaN이다.
        for (horizon in listOf(null, 24_500.0)) {
            assertEquals("여기를 지나면 목적지에 닿으면 11km 남아요 (예비 20km 미만)",
                rangeReason(nextM = null, destinationM = 24_500.0, horizonM = horizon))
        }
    }

    @Test fun destinationAtOrBeyondRangeCannotBeReached() {
        for (range in listOf(24.5, 24.4)) {
            assertEquals("여기를 지나면 목적지까지 닿지 못해요",
                rangeReason(range = range, nextM = null, destinationM = 24_500.0))
        }
    }

    @Test fun horizonOnlyDescribesKnownGapEvenWhenNextEnergyIsBeyondIt() {
        for (range in listOf(36.0, 24.4)) {
            assertEquals("확인된 경로 20km 동안 충전할 곳이 없어요",
                rangeReason(range = range, nextM = 100_000.0, horizonM = 24_500.0))
        }
    }

    @Test fun energyWordAndReserveComeFromTheActualConfiguration() {
        val config = CoachConfig(electricReserveKm = 17.5, fuelReserveKm = 42.0)
        assertEquals("여기를 지나면 다음 충전할 곳에서 주행가능거리가 11km 남아요 (예비 17.5km 미만)",
            rangeReason(config = config))
        for (energy in listOf(EnergyKind.FUEL, null)) {
            assertEquals("여기를 지나면 다음 주유할 곳에서 주행가능거리가 11km 남아요 (예비 42km 미만)",
                rangeReason(energy = energy, config = config))
            assertEquals("여기를 지나면 다음 주유할 곳까지 닿지 못해요", rangeReason(range = 24.5, energy = energy))
            assertEquals("확인된 경로 20km 동안 주유할 곳이 없어요",
                rangeReason(nextM = 100_000.0, horizonM = 24_500.0, energy = energy))
        }
    }

    @Test fun positiveSubKilometerRangeIsNotReportedAsUnreachable() {
        assertEquals("여기를 지나면 다음 충전할 곳에서 주행가능거리가 0km 남아요 (예비 20km 미만)",
            rangeReason(range = 25.0))
    }
    private fun charger(id: String, m: Double) = stop(id, m, facilities = Facilities(restroom = true, evCharger = true))
    private fun sim(
        stops: List<StopCandidate> = listOf(charger("A", 6_000.0), charger("B", 106_000.0)),
        range: Double? = 60.0, crew: List<Occupant> = listOf(adult()), driven: Double = 0.0,
        start: Long = morning, dest: Double = 200_000.0, energy: EnergyKind? = EnergyKind.ELECTRIC,
    ): HighwaySim {
        val engine = CoachEngine(profile = TripProfile(crew, tripStart = start - (driven * MIN).toLong(), continuousDrivingMin = driven), random = Random(7))
        return HighwaySim(engine, start, routeLengthM = dest, stops = stops.map { RouteStop(it.routeDistanceM!!, it) }).apply {
            transformInput = { it.copy(rangeKm = range?.let { r -> (r - posM / 1000).coerceAtLeast(0.0) }, energy = energy) }
        }
    }
    private fun question(s: HighwaySim): Prompt {
        s.driveUntilPos(5_400.0)
        return assertNotNull(s.prompts.firstOrNull { it.third.kind == PromptKind.LAST_CHANCE }?.third)
    }

    @Test fun evAsksAtTheLastChargerAndTheCardAlreadyHasTheReason() {
        val s = sim(stops = listOf(charger("A", 12_000.0), charger("B", 112_000.0)))
        val next = assertNotNull(s.step().next)
        assertEquals("A", next.stop.id)
        assertEquals(setOf(Purpose.FUEL), next.purposes)
        assertTrue(next.reasons.any { it.code == "RANGE_LAST_CHANCE" })
        s.driveUntilPos(11_400.0)
        val p = s.prompts.first { it.third.kind == PromptKind.LAST_CHANCE }.third
        assertEquals("A", p.stop?.id)
        assertTrue("충전" in p.speech)
        assertFalse("주유" in p.speech)
    }

    @Test fun sheltersAndGasOnlyStationsAreNotEvOpportunities() {
        val s = sim(listOf(charger("A", 6_000.0), stop("gas", 30_000.0, facilities = Facilities(fuel = true)),
            stop("shelter", 35_000.0, kind = StopKind.DROWSY_SHELTER), charger("B", 106_000.0)))
        assertEquals("A", question(s).stop?.id)
    }

    @Test fun destinationWithinUsableRangeNeedsNoEnergyStop() {
        val s = sim(dest = 30_000.0)
        s.driveUntilPos(5_400.0)
        assertTrue(s.prompts.none { p -> p.third.reasons.any { it.code.startsWith("RANGE_") || it.code == "ENERGY_BUNDLE" } })
        assertNull(s.last!!.next)
    }

    @Test fun unknownRangeNeverAddsEnergyToHumanPromptsOrCards() {
        val s = sim(range = null, crew = listOf(adult(), child(age = 7)), driven = 70.0, start = afternoon)
        val p = question(s)
        assertFalse(Purpose.FUEL in s.last!!.next!!.purposes)
        assertFalse("충전" in p.speech || "주유" in p.speech)
        assertTrue(s.last!!.next!!.reasons.none { it.code.startsWith("RANGE_") || it.code == "ENERGY_BUNDLE" })
        assertTrue(s.engine.plan(CoachInput(s.now, Gear.DRIVE, candidates = s.candidates(), route = s.routeState(), energy = EnergyKind.ELECTRIC)).notes.none { it.code.startsWith("PLAN_FUEL") })
    }

    @Test fun restroomAndRangeMergeAtTheSameStop() {
        val s = sim(crew = listOf(child(age = 7)), stops = listOf(charger("A", 6_000.0), charger("B", 156_000.0)))
        val p = question(s)
        assertEquals(setOf(Purpose.RESTROOM, Purpose.FUEL), s.last!!.next!!.purposes)
        assertTrue(p.reasons.any { it.code.startsWith("LAST_CHANCE_") })
        assertTrue(p.reasons.any { it.code == "RANGE_LAST_CHANCE" })
        assertTrue("화장실도 충전도 되는 마지막 곳" in p.speech, p.speech)
        assertEquals(1, s.prompts.count { it.third.kind == PromptKind.LAST_CHANCE })
    }

    @Test fun allThreeDeadlinesMerge() {
        val s = sim(crew = listOf(adult(), child(age = 7)), driven = 70.0, start = afternoon,
            stops = listOf(charger("A", 6_000.0), charger("B", 156_000.0)))
        val p = question(s)
        assertEquals(setOf(Purpose.RESTROOM, Purpose.REST, Purpose.FUEL), s.last!!.next!!.purposes)
        assertTrue(p.reasons.any { it.code.startsWith("LAST_CHANCE_") })
        assertTrue(p.reasons.any { it.code.startsWith("FATIGUE_LAST_CHANCE") })
        assertTrue(p.reasons.any { it.code == "RANGE_LAST_CHANCE" })
        assertTrue("화장실" in p.speech && "쉬" in p.speech && "충전" in p.speech, p.speech)
    }

    @Test fun nearerRangeStopWinsThenHumanStopIsAskedAfterPassing() {
        val s = sim(stops = listOf(charger("A", 6_000.0), stop("rest", 20_000.0), charger("B", 156_000.0)),
            range = 30.0, crew = listOf(child(age = 7)))
        val first = question(s)
        assertEquals("A", first.stop?.id)
        s.engine.onPromptResult(first.id, false, s.now)
        s.driveUntilPos(19_400.0)
        assertEquals(listOf("A", "rest"), s.prompts.filter { it.third.kind == PromptKind.LAST_CHANCE }.map { it.third.stop?.id })
    }

    @Test fun shrinkingRangeDoesNotReaskADeclinedStop() {
        val s = sim()
        while (s.last?.prompt == null && s.posM < 5_400.0) s.step()
        val p = assertNotNull(s.last!!.prompt)
        s.engine.onPromptResult(p.id, false, s.now)
        s.transformInput = { it.copy(rangeKm = 20.0, energy = EnergyKind.ELECTRIC) }
        s.driveUntilPos(5_400.0)
        assertEquals(1, s.prompts.count { it.third.kind == PromptKind.LAST_CHANCE })
    }

    @Test fun fuelVoiceAndPlannerUseTheSameEnergyFilterAndWording() {
        val gas = stop("gas", 6_000.0, facilities = Facilities(fuel = true))
        val ev = charger("ev", 20_000.0)
        val s = sim(stops = listOf(gas, ev))
        val p = assertNotNull(s.step(VoiceIntent.OccupantNeed(null, Purpose.FUEL)).prompt)
        assertEquals("ev", p.stop?.id)
        assertTrue("충전" in p.speech && "주유" !in p.speech)
        val input = CoachInput(morning, Gear.DRIVE, route = s.routeState(), candidates = listOf(gas, ev), rangeKm = 60.0, energy = EnergyKind.ELECTRIC)
        val plan = s.engine.plan(input)
        assertEquals("ev", plan.stops.single { Purpose.FUEL in it.purposes }.stop.id)
        assertTrue(plan.notes.any { it.code == "PLAN_FUEL" && "충전" in it.text })
        val short = s.engine.plan(input.copy(candidates = listOf(gas)))
        assertTrue(short.stops.none { Purpose.FUEL in it.purposes })
        assertTrue(short.notes.any { it.code == "PLAN_FUEL_SHORT" && "충전" in it.text })
    }

    @Test fun bundlingOnlyInsideWindowAndBeforeDestinationAndWithTheRightFacility() {
        fun output(range: Double?, dest: Double = 250_000.0, ev: Boolean = true): CoachOutput {
            val s = sim(stops = listOf(charger("A", 6_000.0).copy(facilities = Facilities(restroom = true, evCharger = ev)), charger("B", 100_000.0), charger("C", 156_000.0)),
                range = range, crew = listOf(child(age = 7)), driven = 70.0, dest = dest)
            return s.step()
        }
        val inside = assertNotNull(output(125.0).next) // usable at A = 99 km; range last chance is elsewhere
        assertTrue(inside.reasons.any { it.code == "ENERGY_BUNDLE" })
        assertTrue(Purpose.FUEL in inside.purposes)
        for (o in listOf(output(126.0), output(200.0), output(null), output(125.0, ev = false), output(300.0))) {
            assertTrue(o.next?.reasons.orEmpty().none { it.code == "ENERGY_BUNDLE" })
        }
    }

    @Test fun nowRestroomBundlesWithoutChangingItsPriority() {
        val s = sim(crew = listOf(child(age = 7)), range = 80.0,
            stops = listOf(charger("A", 6_000.0), charger("B", 40_000.0), charger("C", 156_000.0)))
        val p = assertNotNull(s.step(VoiceIntent.OccupantNeed(null, Purpose.RESTROOM)).prompt)
        assertEquals("A", p.stop?.id)
        assertTrue(Purpose.FUEL in s.last!!.next!!.purposes)
        assertTrue(p.reasons.any { it.code == "ENERGY_BUNDLE" })
        assertTrue("충전하는 동안" in p.speech)
    }
}
