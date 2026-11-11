package dev.shimpyo.app.ui.drive

import dev.shimpyo.coach.CandidatesState
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.engine.CoachTexts
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Gauge
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.Reason
import dev.shimpyo.engine.Recommendation
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 화장실 질문 카드(PromptKind.LAST_CHANCE)의 맥락 줄 (UI 감사 #6, 닫힘 검증 UI-6·UI-N5).
 * 엔진은 다섯 가지 문장을 이 종류로 낸다(CoachTexts): 정체 전 마지막, 공백 전 마지막, 여유가 거의 없을 때 가까운 곳,
 * "화장실 가고 싶대"의 답, 수유·반려견 요청의 답. 맥락 줄은 띄운 때의 이유(promptReasons)로 정하고, 이유가 마지막 기회를
 * 말하지 않으면 '마지막 기회'라고 하지 않는다. 입력은 코어가 실제로 낸 값이다
 * (rest-engine/coach …/closure/UiSeamOpenTest.aJamLastChanceAskedWhileTheGaugeIsNowIsStillPublishedAsAJamLastChance).
 */
class PromptContextTest {
    private val texts = CoachTexts()
    private val running = CoachUiState(serviceRunning = true, candidatesState = CandidatesState.READY, guiding = true, restroom = Gauge(0, Level.NOW))

    private fun stop(id: String, name: String, km: Double) = StopCandidate(
        id = id, name = name, kind = StopKind.HIGHWAY_REST_AREA, lat = 36.6, lon = 127.3,
        facilities = Facilities(), parkable = true, routeDistanceM = km * 1_000.0, directionOk = true,
    )

    private val oksan = stop("oksan", "옥산휴게소(부산방향)", 10.0)
    private val cheonan = stop("cheonan", "천안호두휴게소(부산방향)", 0.4)
    private val nearest = Reason("NEXT_NEAREST_RESTROOM", texts.reasonNextNearestRestroom())
    private fun rec(s: StopCandidate, vararg r: Reason) = Recommendation(s, setOf(Purpose.RESTROOM), r.toList())
    private val jamQuestion = Prompt("p1#1", PromptKind.LAST_CHANCE, texts.lastChanceCongestion(oksan.name, 60), oksan, Long.MAX_VALUE)

    @Test
    fun aJamLastChanceAskedWhileTheGaugeIsNowKeepsItsJamLabel() {
        // 코어가 실제로 낸 값: 문장은 "앞에 1시간 정체가 있어요. 다음 옥산휴게소가 정체 전 마지막 화장실이에요. 들를까요?",
        // promptReasons = [NEXT_NEAREST_RESTROOM] (엔진은 게이지가 NOW면 추천 이유를 이것 하나로 낸다). 맥락 줄이 '가까운 화장실'이었다
        val s = running.copy(prompt = jamQuestion, promptReasons = listOf(nearest), next = rec(oksan, nearest), promptStop = oksan)
        assertEquals("정체 전 마지막 화장실", stopCard(s, driving = true).context)
    }

    @Test
    fun emptyReasonsDoNotFollowTheLiveRecommendationInTheMiddleOfAQuestion() {
        // 띄우는 틱의 추천이 다른 곳(500m 안의 천안)이라 이유가 비었다 → 문장으로 '정체 전 마지막 화장실'.
        // 1초 뒤 추천이 옥산(NEXT_NEAREST_RESTROOM)으로 넘어온다 → '가까운 화장실'로 바뀌고 칩이 생겼다 (감사 #6의 증상)
        val shown = running.copy(prompt = jamQuestion, promptReasons = emptyList(), next = rec(cheonan, nearest), promptStop = oksan)
        val first = stopCard(shown, driving = true)
        assertEquals("정체 전 마지막 화장실", first.context)
        val aSecondLater = stopCard(shown.copy(next = rec(oksan, nearest)), driving = true)
        assertEquals("질문 도중 맥락 줄이 바뀐다", first.context, aSecondLater.context)
        assertEquals("질문 도중 칩이 바뀐다", first.chips, aSecondLater.chips)
    }

    @Test
    fun anAnswerToARestroomRequestIsNotCalledALastChanceBeforeAGap() {
        // 엔진은 "아이가 화장실 가고 싶대"의 답도 LAST_CHANCE 종류로 낸다. 문장은 "다음 화장실은 16km 앞 옥산휴게소예요. 들를까요?"이고
        // 공백도 정체도 없다. 이유가 비면(띄울 때 추천이 2km 안의 더 가까운 곳) '화장실 공백 전 마지막 기회'라고 했다: 사실이 아닌 말이다
        val p = Prompt("p2#1", PromptKind.LAST_CHANCE, texts.restroomRequested(oksan.name, 16_000.0), oksan, Long.MAX_VALUE)
        val s = running.copy(prompt = p, promptReasons = emptyList(), next = rec(cheonan, nearest), promptStop = oksan)
        val context = stopCard(s, driving = true).context
        assertNotEquals("화장실 공백 전 마지막 기회", context)
        assertEquals("요청하신 화장실", context)
    }

    @Test
    fun aCareStopAnswerIsNotCalledALastChance() {
        // 수유·반려견 요청의 답("…에서 잠깐 쉬어 갈 수 있어요. 들를까요?")도 LAST_CHANCE 종류다
        val p = Prompt("p3#1", PromptKind.LAST_CHANCE, texts.careStopSuggestion(oksan.name, 16_000.0), oksan, Long.MAX_VALUE)
        val s = running.copy(prompt = p, promptReasons = emptyList(), next = null, promptStop = oksan)
        val context = stopCard(s, driving = true).context
        assertTrue("'$context'", "마지막" !in context)
        assertEquals("요청하신 곳", context)
    }

    @Test
    fun requestedStopCodesLabelTheCardAndAreNotRepeatedAsChips() {
        // 엔진이 요청에 답한 곳에 붙이는 이유 코드 (UI-N5): 맥락 줄이 말하므로 같은 말을 칩으로 되풀이하지 않는다
        val p = Prompt("p2#1", PromptKind.LAST_CHANCE, texts.restroomRequested(oksan.name, 16_000.0), oksan, Long.MAX_VALUE)
        val asked = Reason("NEXT_REQUESTED_RESTROOM", "말씀하신 화장실이에요")
        val card = stopCard(running.copy(prompt = p, promptReasons = listOf(asked), promptStop = oksan), driving = true)
        assertEquals("요청하신 화장실", card.context)
        assertFalse(card.chips.toString(), "요청하신 화장실" in card.chips)
        val care = Prompt("p3#1", PromptKind.LAST_CHANCE, texts.careStopSuggestion(oksan.name, 16_000.0), oksan, Long.MAX_VALUE)
        val careCard = stopCard(running.copy(prompt = care, promptReasons = listOf(Reason("NEXT_REQUESTED_CARE", "말씀하신 곳이에요")), promptStop = oksan), driving = true)
        assertEquals("요청하신 곳", careCard.context)
        assertFalse(careCard.chips.toString(), "요청하신 곳" in careCard.chips)
    }

    @Test
    fun theRecommendationCardNamesARequestedStopWhateverTheReasonOrder() {
        // 질문이 내려간 뒤의 추천 카드도 같은 말로 부른다. 이유가 놓인 순서와 관계없다
        val asked = Reason("NEXT_REQUESTED_RESTROOM", "말씀하신 화장실이에요")
        val card = stopCard(running.copy(next = rec(oksan, nearest, asked)), driving = true)
        assertEquals("요청하신 화장실", card.context)
        assertFalse(card.chips.toString(), "요청하신 화장실" in card.chips)
        assertEquals("요청하신 곳", stopCard(running.copy(next = rec(oksan, Reason("NEXT_REQUESTED_CARE", "말씀하신 곳이에요"))), driving = true).context)
        // 그곳이 마지막 기회이기도 하면 마지막 기회가 먼저다 (질문 카드와 같은 순서)
        val jam = Reason("LAST_CHANCE_CONGESTION", texts.reasonLastChanceCongestion(60))
        assertEquals("정체 전 마지막 화장실", stopCard(running.copy(next = rec(oksan, asked, jam)), driving = true).context)
    }

    @Test
    fun aRequestAnsweredWithTheLastChanceStopKeepsTheLastChanceLabel() {
        // 요청에 답한 곳이 마지막 기회이기도 하면 엔진은 마지막 기회 문장으로 답한다: 이유에 LAST_CHANCE_*가 있으면 그것이 먼저다
        val reasons = listOf(Reason("NEXT_REQUESTED_RESTROOM", "말씀하신 화장실이에요"), Reason("LAST_CHANCE_CONGESTION", texts.reasonLastChanceCongestion(60)))
        val card = stopCard(running.copy(prompt = jamQuestion, promptReasons = reasons, promptStop = oksan), driving = true)
        assertEquals("정체 전 마지막 화장실", card.context)
        assertTrue(card.chips.toString(), "정체 1시간" in card.chips)
    }

    @Test
    fun aPlaceNameIsNotMistakenForAJam() {
        // 문장은 구절로 가린다: "신정체육공원"의 '정체' 때문에 요청의 답이 '정체 전 마지막 화장실'이 되면 안 된다
        val park = stop("park", "신정체육공원 화장실", 3.0)
        val p = Prompt("p7#1", PromptKind.LAST_CHANCE, texts.restroomRequested(park.name, 3_000.0), park, Long.MAX_VALUE)
        assertEquals("요청하신 화장실", stopCard(running.copy(prompt = p, promptStop = park), driving = true).context)
        val now = Prompt("p8#1", PromptKind.LAST_CHANCE, texts.restroomNowSuggestion(park.name, 3_000.0), park, Long.MAX_VALUE)
        assertEquals("가까운 화장실", stopCard(running.copy(prompt = now, promptStop = park), driving = true).context)
    }

    @Test
    fun theNowSuggestionIsANearRestroomNotALastChance() {
        // 게이지가 NOW라 가장 가까운 곳을 권한다("화장실 여유가 거의 없어요. … 들를까요?"): 공백도 정체도 아니다
        val p = Prompt("p4#1", PromptKind.LAST_CHANCE, texts.restroomNowSuggestion(oksan.name, 10_000.0), oksan, Long.MAX_VALUE)
        assertEquals("가까운 화장실", stopCard(running.copy(prompt = p, promptReasons = listOf(nearest), promptStop = oksan), driving = true).context)
        assertEquals("가까운 화장실", stopCard(running.copy(prompt = p, promptStop = oksan), driving = true).context)
    }

    @Test
    fun aLastChanceReasonAlwaysWinsAndAnUnknownSentenceClaimsNothing() {
        val gap = Prompt("p5#1", PromptKind.LAST_CHANCE, texts.lastChanceGap(oksan.name, 50), oksan, Long.MAX_VALUE)
        // 이유가 있으면 이유로, 없으면 엔진의 공백 문장("…를 지나면 50분 동안 화장실이 없어요")으로
        val byReason = running.copy(prompt = gap, promptReasons = listOf(Reason("LAST_CHANCE_GAP", texts.reasonLastChanceGap(50))), promptStop = oksan)
        assertEquals("화장실 공백 전 마지막 기회", stopCard(byReason, driving = true).context)
        assertEquals("화장실 공백 전 마지막 기회", stopCard(running.copy(prompt = gap, promptStop = oksan), driving = true).context)
        // 여럿이 타서 앞당긴 마지막 기회만 온 경우도 마지막 기회다
        val group = running.copy(prompt = gap, promptReasons = listOf(Reason("LAST_CHANCE_GROUP", texts.reasonLastChanceGroup(4))), promptStop = oksan)
        assertEquals("화장실 공백 전 마지막 기회", stopCard(group, driving = true).context)
        // 여럿이 타서 앞당긴 이유만 온 정체 질문은 문장을 따라 정체다 (이유가 정체인지 공백인지 말하지 않을 때)
        val groupJam = running.copy(prompt = jamQuestion, promptReasons = listOf(Reason("LAST_CHANCE_GROUP", texts.reasonLastChanceGroup(4))), promptStop = oksan)
        assertEquals("정체 전 마지막 화장실", stopCard(groupJam, driving = true).context)
        // 이유도 없고 아는 문장도 아니면 마지막 기회라고 하지 않는다
        val unknown = Prompt("p6#1", PromptKind.LAST_CHANCE, "들를까요?", oksan, Long.MAX_VALUE)
        val context = stopCard(running.copy(prompt = unknown, promptStop = oksan), driving = true).context
        assertTrue("'$context'", "마지막" !in context)
    }
}
