package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 화장실 질문(PromptKind.LAST_CHANCE)과 추천(CoachOutput.next)의 이유가 어긋나지 않는다.
 *
 * 화면은 질문 카드를 띄우는 틱의 추천에서 이유를 가져온다(같은 장소일 때만, coach PromptMachine). 그래서 엔진은
 * 화장실 질문이 떠 있는 동안 추천을 그 질문의 장소와 이유로 낸다. 예전에는 게이지가 NOW면 추천이 '가장 빠른 화장실'
 * (다른 곳일 수도 있다)이라 "정체 전 마지막 화장실이에요"라고 묻는 카드에 '가까운 화장실' 딱지가 붙었고(UI 감사 #6),
 * "아이가 화장실 가고 싶대"의 답은 마지막 기회와 구분할 이유 코드가 없었다(UI 감사 N5).
 *
 * 이유 코드:
 * - 마지막 기회 문구로 묻는 질문: LAST_CHANCE_CONGESTION / LAST_CHANCE_GAP / LAST_CHANCE_PREFERENCE (+ LAST_CHANCE_GROUP)
 * - 여유가 거의 없을 때의 가까운 화장실: NEXT_NEAREST_RESTROOM
 * - "화장실 가고 싶대"의 답: NEXT_REQUESTED_RESTROOM, 수유·반려견 요청의 답: NEXT_REQUESTED_CARE
 *   (그곳이 마지막 기회이기도 하면 마지막 기회 이유가 앞에 온다)
 */
class PromptReasonsTest {
    private val t0 = kst(2026, 11, 11, 10, 0)
    private val km = 1_000.0
    private val v100 = 100 / 3.6
    private val route = RouteState(guiding = true, congestionBasisKnown = true, remainDistanceM = 200 * km, remainTimeS = 200 * km / v100, nextManeuverDistanceM = 50 * km)

    private fun family(sinceMin: Int = 30) = CoachEngine(
        profile = TripProfile(
            listOf(adult("driver"), child("kid", 7)), tripStart = t0 - sinceMin * MIN,
            lastRestroomAt = mapOf("driver" to t0 - sinceMin * MIN, "kid" to t0 - sinceMin * MIN),
        ),
        random = Random(7),
    )

    /** 1초마다 같은 후보로 update. [say]: (몇 번째 초, 의도) */
    private fun CoachEngine.drive(cands: List<StopCandidate>, seconds: Int, r: RouteState = route, say: Pair<Int, VoiceIntent>? = null): List<CoachOutput> =
        (0 until seconds).map { s ->
            update(CoachInput(now = t0 + s * SEC, gear = Gear.DRIVE, speedKph = 100f, route = r, candidates = cands, intent = say?.takeIf { it.first == s }?.second))
        }

    private fun CoachOutput.codes() = next?.reasons.orEmpty().map { it.code }

    /** 질문이 떠 있는 모든 틱에서 추천이 같은 곳이고 이유가 [codes]인지 */
    private fun assertNextFollowsTheQuestion(outs: List<CoachOutput>, kind: PromptKind, stopId: String, codes: List<String>) {
        val asking = outs.filter { it.prompt?.kind == kind && it.prompt?.stop?.id == stopId }
        assertTrue(asking.size >= 5, "질문이 여러 틱 떠 있어야 비교가 뜻이 있다: ${asking.size}")
        for (o in asking) {
            assertEquals(stopId, o.next?.stop?.id, "질문 ${o.prompt?.speech} / 추천 ${o.next}")
            assertEquals(codes, o.codes(), o.prompt?.speech)
        }
    }

    // ------------------------------------------------------------ 탑승자 요청의 답 (UI 감사 N5)

    @Test
    fun theAnswerToARestroomRequestCarriesItsOwnReasonCode() {
        // 6km 앞 가휴게소 다음이 30km(14분 뒤)라 공백이 아니다: "다음 화장실은 …"으로 답한다
        val cands = listOf(stop("a", 6 * km, name = "가휴게소"), stop("b", 30 * km, name = "나휴게소"))
        val outs = family().drive(cands, seconds = 20, say = 1 to VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM))
        val answer = assertNotNull(outs[1].prompt)
        assertEquals(PromptKind.LAST_CHANCE, answer.kind)
        assertEquals("다음 화장실은 6km 앞 가휴게소예요. 들를까요?", answer.speech)
        assertNextFollowsTheQuestion(outs, PromptKind.LAST_CHANCE, "a", listOf("NEXT_REQUESTED_RESTROOM"))
        val reason = outs[1].next!!.reasons.single()
        assertTrue(reason.text.isNotBlank())
        assertEquals(setOf(Purpose.RESTROOM), outs[1].next!!.purposes)
    }

    @Test
    fun theAnswerToAFeedingOrPetRequestCarriesTheCareReasonCode() {
        val cands = listOf(stop("a", 6 * km, name = "가휴게소"), stop("b", 30 * km, name = "나휴게소"))
        for (purpose in listOf(Purpose.FEEDING, Purpose.PET)) {
            val e = CoachEngine(profile = TripProfile(listOf(adult("driver")), tripStart = t0 - 30 * MIN), random = Random(7))
            val outs = e.drive(cands, seconds = 20, say = 1 to VoiceIntent.OccupantNeed(null, purpose))
            assertEquals("6km 앞 가휴게소에서 잠깐 쉬어 갈 수 있어요. 들를까요?", outs[1].prompt?.speech, purpose.name)
            assertNextFollowsTheQuestion(outs, PromptKind.LAST_CHANCE, "a", listOf("NEXT_REQUESTED_CARE"))
        }
    }

    @Test
    fun aRequestedStopThatIsAlsoTheLastChanceKeepsTheLastChanceReasonFirst() {
        // 가휴게소 뒤 20분 공백: 마지막 기회 문구로 답하고, 이유는 마지막 기회 + 요청 (요청에 답한 카드라는 표시가 남는다)
        val gap = listOf(stop("a", 6 * km, name = "가휴게소"), stop("b", 40 * km, name = "나휴게소"))
        val byGap = family().drive(gap, seconds = 20, say = 1 to VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM))
        assertTrue(byGap[1].prompt!!.speech.contains("지나면"), byGap[1].prompt!!.speech)
        assertNextFollowsTheQuestion(byGap, PromptKind.LAST_CHANCE, "a", listOf("LAST_CHANCE_GAP", "NEXT_REQUESTED_RESTROOM"))

        // 정체 전 마지막 화장실 (게이지는 말한 순간 NOW): 예전에는 추천의 이유가 NEXT_NEAREST_RESTROOM뿐이었다 (UI 감사 #6)
        val jam = CongestionSegment(startDistanceM = 7 * km, lengthM = 18 * km, durationS = 3_600.0)
        val jammed = route.copy(remainTimeS = (200 - 18) * km / v100 + 3_600.0, congestion = listOf(jam))
        val byJam = family().drive(gap, seconds = 20, r = jammed, say = 1 to VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM))
        assertEquals(Level.NOW, byJam[1].restroom.level)
        assertTrue(byJam[1].prompt!!.speech.contains("정체 전 마지막 화장실"), byJam[1].prompt!!.speech)
        assertNextFollowsTheQuestion(byJam, PromptKind.LAST_CHANCE, "a", listOf("LAST_CHANCE_CONGESTION", "NEXT_REQUESTED_RESTROOM"))
    }

    // ------------------------------------------------------------ 엔진이 먼저 거는 화장실 질문 (UI 감사 #6)

    @Test
    fun theNowQuestionAndTheRecommendationAreAboutTheSamePlace() {
        // 아이 게이지 5분(NOW). 1km 앞 주유소가 가장 빠르지만 진입로가 너무 가까워 묻지 못하고, 질문은 6km 앞 가휴게소다.
        // 예전에는 질문이 떠 있는 동안에도 추천은 주유소여서 화면이 질문 카드의 이유를 채우지 못했다
        val cands = listOf(
            stop("gas", 1 * km, kind = StopKind.GAS_STATION, name = "주유소"),
            stop("a", 6 * km, name = "가휴게소"),
            stop("b", 16 * km, name = "나휴게소"),
        )
        val outs = family(sinceMin = 100).drive(cands, seconds = 20)
        assertEquals(Level.NOW, outs[0].restroom.level)
        assertTrue(outs[0].prompt!!.speech.startsWith("화장실 여유가 거의 없어요"), outs[0].prompt!!.speech)
        assertNextFollowsTheQuestion(outs, PromptKind.LAST_CHANCE, "a", listOf("NEXT_NEAREST_RESTROOM"))
    }

    @Test
    fun aLastChanceQuestionKeepsItsReasonOnTheRecommendationWhileItIsUp() {
        // 엔진이 먼저 건 마지막 기회: 질문이 떠 있는 동안 추천은 같은 곳, 같은 이유다 (차가 다가가도 그대로)
        val e = CoachEngine(
            profile = TripProfile(listOf(adult("driver"), child("kid", 5)), tripStart = t0, lastRestroomAt = mapOf("kid" to t0 - 30 * MIN, "driver" to t0 - 40 * MIN)),
            random = Random(7),
        )
        var pos = 0.0
        val outs = (0 until 40).map { s ->
            val cands = listOf(stop("a", 4.5 * km - pos, name = "가휴게소"), stop("b", 40 * km - pos, name = "나휴게소"))
            pos += v100
            e.update(CoachInput(now = t0 + s * SEC, gear = Gear.DRIVE, speedKph = 100f, route = route.copy(remainDistanceM = 200 * km - pos), candidates = cands))
        }
        assertNextFollowsTheQuestion(outs, PromptKind.LAST_CHANCE, "a", listOf("LAST_CHANCE_GAP"))
        // 추천의 거리는 그때의 값이다 (질문을 띄운 때의 값이 아니다)
        val asking = outs.filter { it.prompt?.stop?.id == "a" }
        assertTrue(asking.last().next!!.stop.routeDistanceM!! < asking.first().next!!.stop.routeDistanceM!! - 500.0)
    }
}
