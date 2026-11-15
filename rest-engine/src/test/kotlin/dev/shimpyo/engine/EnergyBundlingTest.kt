package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.*

class EnergyBundlingTest {
    private val now = kst(2026, 11, 14, 10, 0)
    private fun input(range: Double? = 80.0, energy: EnergyKind? = EnergyKind.ELECTRIC) = CoachInput(
        now, Gear.DRIVE, speedKph = 100f, rangeKm = range, energy = energy,
        route = RouteState(true, remainDistanceM = 200_000.0, remainTimeS = 7200.0),
        candidates = listOf(2_000.0, 40_000.0, 156_000.0).mapIndexed { i, d ->
            stop("S$i", d, facilities = Facilities(restroom = true, fuel = true, evCharger = true))
        },
    )
    private fun engine(driven: Double = 0.0) = CoachEngine(
        profile = TripProfile(listOf(adult()), now - (driven * MIN).toLong(), continuousDrivingMin = driven, lastRestroomAt = mapOf("me" to now)), random = Random(7))

    @Test fun fatigueNowBundlesAtNearestStopAndCardFollowsItsQuestion() {
        val out = engine(120.0).update(input())
        val p = assertNotNull(out.prompt)
        assertEquals(PromptKind.REST_SUGGESTION, p.kind)
        assertEquals("S0", p.stop?.id)
        assertTrue(p.reasons.any { it.code == "ENERGY_BUNDLE" })
        assertTrue("충전하는 동안 쉬어" in p.speech)
        assertEquals("S0", out.next?.stop?.id)
        assertEquals(setOf(Purpose.REST, Purpose.FUEL), out.next?.purposes)
        assertTrue(p.reasons.none { it.code.startsWith("FATIGUE_LAST_CHANCE") })
    }

    @Test fun sleepyAdviceSurvivesBundlingAndFuelWordingNeverSaysCharge() {
        val out = engine().update(input(energy = EnergyKind.FUEL).copy(intent = VoiceIntent.Sleepy))
        val p = assertNotNull(out.prompt)
        assertTrue("커피" in p.speech && "15분" in p.speech && "주유" in p.speech, p.speech)
        assertFalse("충전" in p.speech)
        assertTrue(p.reasons.any { it.code == "ENERGY_BUNDLE" })
        assertTrue(p.reasons.none { it.code.startsWith("FATIGUE_LAST_CHANCE") })
    }

    @Test fun nextRestCardCanBundleBeforeAnyQuestionAndDestinationWithinRangeDoesNotBundle() {
        val base = input().copy(candidates = input().candidates.map { it.copy(routeDistanceM = it.routeDistanceM!! + 10_000.0) })
        val out = engine(100.0).update(base)
        // 피로의 마지막 기회가 아니어도 NEXT_REST에 차량 목적을 붙인다.
        assertTrue(out.next!!.reasons.any { it.code == "ENERGY_BUNDLE" })
        val within = engine(100.0).update(base.copy(rangeKm = 400.0))
        assertFalse(Purpose.FUEL in within.next!!.purposes)
        assertTrue(within.next.reasons.none { it.code == "ENERGY_BUNDLE" })
    }

    @Test fun losingTheRangeWithdrawsAnAutomaticEnergyQuestion() {
        val e = engine()
        val low = input(30.0)
        val p = assertNotNull(e.update(low).prompt)
        assertTrue(p.reasons.any { it.code == "RANGE_LAST_CHANCE" })
        val unknown = e.update(low.copy(now = now + SEC, rangeKm = null))
        assertTrue(unknown.prompt?.reasons.orEmpty().none { it.code.startsWith("RANGE_") || it.code == "ENERGY_BUNDLE" })
        assertTrue(unknown.next?.reasons.orEmpty().none { it.code.startsWith("RANGE_") || it.code == "ENERGY_BUNDLE" })
    }

    @Test fun everyEnergyCombinationIsShortAndNamesTheMatchingAction() {
        val texts = CoachTexts()
        for (kind in EnergyKind.entries + listOf(null)) for (humans in listOf(emptySet(), setOf(Purpose.RESTROOM), setOf(Purpose.REST), setOf(Purpose.RESTROOM, Purpose.REST))) {
            val speech = texts.energyLastChance("옥산휴게소", humans + Purpose.FUEL, kind)
            assertTrue(speech.count { it == '.' || it == '?' } <= 2, speech)
            assertTrue(if (kind == EnergyKind.ELECTRIC) "충전" in speech && "주유" !in speech else "주유" in speech && "충전" !in speech)
        }
    }

    @Test fun parkedPlanPurposeWordingAlsoUsesTheEnergyKind() {
        val texts = CoachTexts()
        val purposes = setOf(Purpose.RESTROOM, Purpose.FUEL)
        for (kind in EnergyKind.entries + listOf(null)) {
            val notes = listOf(texts.planHereWhat(purposes, kind), texts.planHere("옥산휴게소", purposes, kind),
                texts.planHereNow("옥산휴게소", purposes, 20, kind))
            for (note in notes) assertTrue(if (kind == EnergyKind.ELECTRIC) "충전" in note && "주유" !in note else "주유" in note && "충전" !in note, note)
        }
    }
    @Test fun bundlingKeepsTheRestroomReasonAndOnlyChangesTheQuestion() {
        // 7살 아이, 옥산 같은 A 바로 뒤 1시간 정체. 화장실의 마지막 기회는 A, 차의 마지막 기회는 그 뒤 B라 질문은 A의 화장실 질문이다.
        // A에 충전기가 있고 정차 뒤 가용 거리가 100km 안이면 '충전하는 동안'을 붙이되, 정체라는 이유는 지우지 않는다
        // (2026-11-15 검토: 묶으면 문장을 통째로 바꿔 "앞에 1시간 정체가 있어요"가 사라졌다)
        val start = kst(2026, 11, 14, 14, 0)
        val e = CoachEngine(
            profile = TripProfile(listOf(adult("driver"), child("kid", age = 7)), tripStart = start - 40 * MIN, continuousDrivingMin = 40.0),
            random = Random(7),
        )
        val ev = Facilities(restroom = true, fuel = true, evCharger = true)
        val sim = HighwaySim(
            e, start, routeLengthM = 200_000.0,
            stops = listOf(RouteStop(6_000.0, stop("A", 6_000.0, facilities = ev)), RouteStop(40_000.0, stop("B", 40_000.0, facilities = ev)),
                RouteStop(156_000.0, stop("C", 156_000.0, facilities = ev))),
            jams = listOf(Jam(8_000.0, 20_000.0, 3_600.0)),
        )
        sim.transformInput = { it.copy(rangeKm = 110.0, energy = EnergyKind.ELECTRIC) }
        sim.driveUntilPos(5_400.0)
        val p = assertNotNull(sim.prompts.map { it.third }.firstOrNull { !it.userRequested && it.stop?.id == "A" }, sim.prompts.map { it.third.speech }.toString())
        assertTrue(p.reasons.any { it.code == "LAST_CHANCE_CONGESTION" }, p.reasons.toString())
        assertTrue(p.reasons.any { it.code == "ENERGY_BUNDLE" }, p.reasons.toString())
        assertTrue("정체" in p.speech && "충전하는 동안" in p.speech, p.speech)
        assertTrue(p.speech.count { it == '.' || it == '?' } <= 3, p.speech)
    }

    @Test fun bundleQuestionReplacesOnlyTheLastSentence() {
        val t = CoachTexts()
        assertEquals("앞에 1시간 정체가 있어요. 다음 A가 정체 전 마지막 화장실이에요. 충전하는 동안 화장실 다녀올까요?",
            t.withEnergyBundle("앞에 1시간 정체가 있어요. 다음 A가 정체 전 마지막 화장실이에요. 들를까요?", "A", setOf(Purpose.RESTROOM), EnergyKind.ELECTRIC))
        // 질문으로 끝나지 않는 말은 고칠 곳을 모르니 묶음 문장을 따로 낸다
        assertEquals(t.energyBundle("A", setOf(Purpose.REST), EnergyKind.FUEL), t.withEnergyBundle("가까이에 화장실이 있어요.", "A", setOf(Purpose.REST), EnergyKind.FUEL))
    }
}

