package dev.shimpyo.app.ui.drive

import dev.shimpyo.app.ui.Labels
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.theme.DriveLimits
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.GoToOutcome
import dev.shimpyo.coach.GoToResult
import dev.shimpyo.engine.*
import org.junit.Assert.*
import org.junit.Test

class EnergyDriveModelsTest {
    private val stop = StopCandidate("oksan", "신정체육공원 충전소", StopKind.HIGHWAY_REST_AREA, 36.0, 127.0,
        facilities = Facilities(evCharger = true), parkable = true, routeDistanceM = 3800.0)
    private val texts = CoachTexts()
    private val range = Reason("RANGE_LAST_CHANCE", texts.reasonRangeLastChance(11.5, 20, EnergyKind.ELECTRIC, false, false, 20.0))
    private val wc = Reason("LAST_CHANCE_CONGESTION", texts.reasonLastChanceCongestion(60))
    private val rest = Reason("FATIGUE_LAST_CHANCE_GAP", texts.reasonFatigueLastChanceGap(80, 40))
    private val state = CoachUiState(serviceRunning = true, energy = EnergyKind.ELECTRIC, rangeKm = 58.8)

    @Test fun acceptedEnergyStopKeepsPurposesAndDecisionChipsThroughNavigationConfirmation() {
        for (kind in listOf(EnergyKind.ELECTRIC, EnergyKind.FUEL, null)) {
            val word = if (kind == EnergyKind.ELECTRIC) "충전" else "주유"
            val reason = range.copy(text = texts.reasonRangeLastChance(11.5, 20, kind, false, false, 20.0))
            val reasons = listOf(wc, reason)
            val rec = Recommendation(stop, setOf(Purpose.RESTROOM, Purpose.FUEL), reasons)
            val asked = state.copy(energy = kind, next = rec, promptReasons = reasons,
                prompt = Prompt("q", PromptKind.LAST_CHANCE, texts.energyLastChance(stop.name, rec.purposes, kind), stop, Long.MAX_VALUE))
            val before = stopCard(asked, true)
            val accepted = asked.copy(prompt = null, promptReasons = emptyList(),
                next = rec.copy(reasons = listOf(Reason("NEXT_ACCEPTED", texts.reasonNextAccepted())) + reasons))
            val chosen = stopCard(accepted, true)
            assertEquals("화장실·$word · 들르기로 한 곳", chosen.context)
            assertEquals(before.chips, chosen.chips)
            assertNull(chosen.askingLevel)
            val pending = stopCard(accepted.copy(goToPending = stop), true)
            assertEquals("화장실·$word · 내비에 넣는 중…", pending.context)
            assertEquals(before.chips, pending.chips)
            for (outcome in listOf(GoToOutcome.WAYPOINT_ADDED, GoToOutcome.ROUTE_STARTED, GoToOutcome.ON_ROUTE, GoToOutcome.SEARCH_OPENED)) {
                val result = GoToResult(stop, outcome, 1L)
                val navigating = accepted.copy(goToResult = result)
                val card = stopCard(navigating, true)
                assertEquals("화장실·$word · ${goToContext(outcome).removePrefix("경로 위 · ")}", card.context)
                assertEquals(before.chips, card.chips)
                assertTrue(cardShowsGoTo(navigating, true, result))
            }
        }
    }

    @Test fun acceptedPurposesRemainVisibleWithoutEnergyReasonAndNeverComeFromAnotherStop() {
        val rec = Recommendation(stop, setOf(Purpose.REST, Purpose.FUEL), listOf(Reason("NEXT_ACCEPTED", texts.reasonNextAccepted())))
        val accepted = state.copy(next = rec)
        assertEquals("휴식·충전 · 들르기로 한 곳", stopCard(accepted, true).context)
        val other = stop.copy(id = "other", name = "다른 휴게소")
        val pending = stopCard(accepted.copy(goToPending = other), true)
        assertEquals(GOTO_PENDING_CONTEXT, pending.context)
        assertTrue(pending.chips.isEmpty())
        val restOnly = stopCard(state.copy(next = rec.copy(purposes = setOf(Purpose.REST))), true)
        assertEquals("휴식 · 들르기로 한 곳", restOnly.context)
        assertEquals(ShimpyoIcons.Rest, restOnly.icon)
    }

    @Test fun restrictedChipLengthNeverTruncatesARangeClaimIntoADifferentMeaning() {
        val rec = Recommendation(stop, setOf(Purpose.FUEL), listOf(range, wc))
        val card = stopCard(state.copy(next = rec), true, maxChars = 10)
        assertTrue(card.chips.all { it.length <= 10 })
        assertFalse(card.chips.any { it.endsWith("…") })
        assertTrue("정체 1시간" in card.chips)
    }

    @Test fun directEnergyRequestMatchesSpokenPurposeWithoutLastChanceOrBundleReasons() {
        for (kind in listOf(EnergyKind.ELECTRIC, EnergyKind.FUEL, null)) {
            val word = if (kind == EnergyKind.ELECTRIC) "충전" else "주유"
            val prompt = Prompt("fuel", PromptKind.REST_SUGGESTION, texts.fuelSuggestion(stop.name, 3800.0, kind), stop, Long.MAX_VALUE)
            // 질문이 떠 있는 동안 다음 추천이 다른 곳으로 넘어가도 질문의 문장을 따른다.
            val card = stopCard(state.copy(energy = kind, prompt = prompt, next = null), true)
            assertEquals("${word}할 곳", card.context)
            assertEquals(ShimpyoIcons.GasStation, card.icon)
            assertTrue(card.chips.isEmpty())
        }
        val restPrompt = Prompt("rest", PromptKind.REST_SUGGESTION, texts.restSuggestionWanted(stop.name, 3800.0), stop, Long.MAX_VALUE)
        assertEquals("쉬어 갈 곳", stopCard(state.copy(prompt = restPrompt), true).context)
        val namedMeal = stop.copy(name = "식사동 충전소")
        assertEquals("쉬어 갈 곳", stopCard(state.copy(prompt = restPrompt.copy(
            stop = namedMeal, speech = texts.restSuggestionWanted(namedMeal.name, 3800.0))), true).context)
        assertEquals("식사할 곳", stopCard(state.copy(prompt = restPrompt.copy(
            speech = texts.mealSuggestion(stop.name, 3800.0))), true).context)
    }

    @Test fun bundleQuestionKeepsHumanPurposeWhenRecommendationMovesOn() {
        for (human in listOf(Purpose.RESTROOM, Purpose.REST)) {
            val purposes = setOf(human, Purpose.FUEL)
            val prompt = Prompt("bundle", PromptKind.REST_SUGGESTION, texts.energyBundle(stop.name, purposes, EnergyKind.ELECTRIC), stop, Long.MAX_VALUE)
            val card = stopCard(state.copy(prompt = prompt, next = null,
                promptReasons = listOf(Reason("ENERGY_BUNDLE", texts.reasonEnergyBundle(EnergyKind.ELECTRIC)))), true)
            assertEquals("${if (human == Purpose.RESTROOM) "화장실" else "휴식"}·충전 함께", card.context)
        }
    }

    /** 도착 시 잔여 거리: 차의 현재 잔량이나 선택한 정차지부터의 간격과 혼동하지 않는다. */
    @Test fun rangeChipShowsRangeLeftAtTheNextEnergyStop() {
        assertEquals("충전 때 11km 남음", Labels.shortReason(range))
        for (energy in listOf(EnergyKind.ELECTRIC, EnergyKind.FUEL, null)) {
            val word = if (energy == EnergyKind.ELECTRIC) "충전" else "주유"
            fun chip(left: Double, lowerBound: Boolean = false, destination: Boolean = false) =
                Labels.shortReason(range.copy(text = texts.reasonRangeLastChance(left, 17, energy, lowerBound, destination, 20.0))).also {
                    assertNotNull(it)
                    assertTrue(it!!.length <= DriveLimits.MAX_CHIP_CHARS)
                }
            assertEquals("$word 때 11km 남음", chip(11.5))
            assertEquals("다음 $word 못 닿음", chip(0.0))
            assertEquals("다음 $word 못 닿음", chip(-1.0))
            assertEquals("도착 때 11km 남음", chip(11.5, destination = true))
            assertEquals("도착 못 함", chip(0.0, destination = true))
            assertEquals("도착 못 함", chip(-1.0, destination = true))
            assertEquals("17km $word 없음", chip(11.5, lowerBound = true))
        }
    }

    @Test fun rangeChipParsesWhitespaceAndFallsBackForMalformedOrOversizedTexts() {
        assertEquals("충전 때 11km 남음", Labels.shortReason(range.copy(text =
            "  여기를 지나면  다음 충전할 곳에서 주행가능거리가 11 km 남아요 (예비 20km 미만)  ")))
        assertEquals("충전 때 11km 남음", Labels.shortReason(range.copy(text = range.text.replace("예비 20km", "예비 17.5km"))))
        for (text in listOf("", "예비 20km 미만", "다음 충전 20km", "목적지까지 94km예요",
            range.text.replace("11km", "-11km"), range.text.replace("11km", "11.5km"),
            range.text.replace("11km", "999999999999999999999999999999km"),
            texts.reasonRangeLastChance(123.0, 17, EnergyKind.ELECTRIC, false, false, 200.0),
            texts.reasonRangeLastChance(123.0, 17, EnergyKind.ELECTRIC, false, true, 200.0),
            texts.reasonRangeLastChance(11.0, Int.MAX_VALUE, EnergyKind.ELECTRIC, true, false, 20.0),
            "확인된 경로 알 수 없음 동안 충전할 곳이 없어요")) {
            assertEquals(text, "주행거리 확인", Labels.shortReason(range.copy(text = text)))
        }
    }

    @Test fun tightAndBundleUseEnergyWording() {
        assertEquals("주행거리 빠듯", Labels.shortReason(Reason("RANGE_TIGHT", texts.reasonRangeTight())))
        assertEquals("충전하며 휴식", Labels.shortReason(Reason("ENERGY_BUNDLE", texts.reasonEnergyBundle(EnergyKind.ELECTRIC))))
        assertEquals("주유도 함께", Labels.shortReason(Reason("ENERGY_BUNDLE", texts.reasonEnergyBundle(EnergyKind.FUEL))))
    }

    @Test fun cardAndQuestionShareEnergyContextsForAllCombinations() {
        val cases = listOf(
            Triple(listOf(range), setOf(Purpose.FUEL), "충전 마지막 기회"),
            Triple(listOf(wc, range), setOf(Purpose.RESTROOM, Purpose.FUEL), "화장실·충전 마지막 기회"),
            Triple(listOf(rest, range), setOf(Purpose.REST, Purpose.FUEL), "휴식·충전 마지막 기회"),
            Triple(listOf(wc, rest, range), setOf(Purpose.RESTROOM, Purpose.REST, Purpose.FUEL), "화장실·휴식·충전 마지막 기회"),
        )
        for ((reasons, purposes, context) in cases) {
            val rec = Recommendation(stop, purposes, reasons)
            val recommended = stopCard(state.copy(next = rec), driving = true)
            assertEquals(context, recommended.context)
            if (Purpose.REST in purposes && Purpose.RESTROOM !in purposes) assertEquals(ShimpyoIcons.Rest, recommended.icon)
            assertTrue(recommended.chips.toString(), "충전 때 11km 남음" in recommended.chips)
            val p = Prompt("q", PromptKind.LAST_CHANCE, texts.energyLastChance(stop.name, purposes, EnergyKind.ELECTRIC), stop, Long.MAX_VALUE)
            val asked = stopCard(state.copy(next = rec, prompt = p, promptReasons = reasons), driving = true)
            assertEquals(context, asked.context)
            assertTrue(asked.chips.toString(), "충전 때 11km 남음" in asked.chips)
        }
        val only = stopCard(state.copy(next = Recommendation(stop, setOf(Purpose.FUEL), listOf(range))), true)
        assertEquals(ShimpyoIcons.GasStation, only.icon)
    }

    @Test fun fuelAndUnknownKindUseRefuellingAndTightReasonIsVisible() {
        for (kind in listOf(EnergyKind.FUEL, null)) {
            val s = state.copy(energy = kind, next = Recommendation(stop, setOf(Purpose.FUEL), listOf(range, Reason("RANGE_TIGHT", "빠듯"))))
            assertEquals("주유 마지막 기회", stopCard(s, true).context)
            assertTrue("주행거리 빠듯" in stopCard(s, true).chips)
        }
    }

    @Test fun bundleDoesNotClaimAnEnergyLastChanceAndIgnoresJamWordsInStopNames() {
        val bundle = Reason("ENERGY_BUNDLE", texts.reasonEnergyBundle(EnergyKind.ELECTRIC))
        val rec = Recommendation(stop, setOf(Purpose.RESTROOM, Purpose.FUEL), listOf(Reason("NEXT_NEAREST_RESTROOM", "가까워요"), bundle))
        val p = Prompt("q", PromptKind.LAST_CHANCE, texts.energyBundle(stop.name, rec.purposes, EnergyKind.ELECTRIC), stop, Long.MAX_VALUE)
        val card = stopCard(state.copy(next = rec, prompt = p, promptReasons = rec.reasons), true)
        assertEquals("화장실·충전 함께", card.context)
        assertFalse(card.context.contains("마지막") || card.context.contains("정체"))
        assertEquals("화장실·충전 함께", stopCard(state.copy(next = rec), true).context)
    }

    @Test fun rangeReadoutShowsRawCarKmAndHidesUnknownInvalidOrStoppedSignals() {
        val model = checkNotNull(energyReadout(state))
        assertEquals("58", model.value)
        assertEquals("km", model.unit)
        assertEquals("충전 주행가능거리", model.label)
        assertEquals("주유 주행가능거리", energyReadout(state.copy(energy = EnergyKind.FUEL))!!.label)
        assertEquals("차 주행가능거리", energyReadout(state.copy(energy = null))!!.label)
        assertEquals("0", energyReadout(state.copy(rangeKm = 0.0))!!.value)
        for (range in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) assertNull(energyReadout(state.copy(rangeKm = range)))
        assertNull(energyReadout(state.copy(serviceRunning = false)))
    }
}
