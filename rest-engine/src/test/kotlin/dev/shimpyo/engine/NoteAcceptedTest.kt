package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CoachEngine.noteAccepted]: 운전자가 화면에서 정차지를 직접 골랐고(급해 목록, 계획 화면, 실패 뒤 다시 시도) 내비 명령이 성공했다.
 *
 * 예전에는 엔진에 그것을 알릴 길이 질문 id(onPromptResult)뿐이라, 엔진이 물은 곳이 아닌 곳을 고르면 엔진은 그것을 모르고
 * 30초 뒤 다른 곳을 다시 물었다(서비스 감사 D7, UI 감사 #9: 급해 목록의 2순위를 골랐는데 엔진이 다른 주유소를 물었다).
 * 규칙:
 * - 그곳이 '들르기로 한 곳'이 된다(추천 NEXT_ACCEPTED). 유지·해제는 수락한 질문과 같다(후보에서 1분 넘게 사라지면, 90분이 지나면,
 *   화장실 정차·휴식이 기록되면 내린다. 서 있는 동안에는 내리지 않는다)
 * - 같은 곳을 묻던 질문이 떠 있으면 그 질문을 수락한 것이다
 * - 다른 곳을 묻던 화장실 질문은 그 선택으로 끝난다: 무시한 것으로 치지 않고(쉼·시간당 횟수 없음) 늦게 온 대답도 받지 않는다
 * - 그곳을 다시 묻지 않는다: 들르기로 한 곳이 있는 동안 엔진은 정차를 먼저 묻지 않고, 사용자의 말("화장실 급해" 등)에 답할 곳이
 *   바로 그곳이면 "…에 들르기로 했어요"라고만 답한다
 * - 그 뒤 그곳에 서면 '앱이 권해서 들른 정차'로 배운다
 */
class NoteAcceptedTest {
    private val t0 = kst(2026, 11, 13, 10, 0)
    private val km = 1_000.0
    private val v100 = 100 / 3.6
    private val route = RouteState(guiding = true, remainDistanceM = 200 * km, remainTimeS = 200 * km / v100, nextManeuverDistanceM = 50 * km)

    private val a = stop("a", 3 * km, name = "가휴게소")
    private val b = stop("b", 40 * km, name = "나휴게소")

    private fun engine(vararg occ: Occupant, sinceMin: Int = 30) = CoachEngine(
        profile = TripProfile(occ.toList(), tripStart = t0 - sinceMin * MIN, lastRestroomAt = occ.associate { it.id to t0 - sinceMin * MIN }),
        random = Random(7),
    )

    /** 아이 동승: 가휴게소(3km) 뒤 22분 공백이라 첫 틱에 가휴게소를 마지막 기회로 묻는다 */
    private fun family() = engine(adult("me"), child("kid", 5))

    private class Drive(val e: CoachEngine, var now: Long, val route: RouteState?) {
        fun tick(cands: List<StopCandidate>, intent: VoiceIntent? = null, gear: Gear = Gear.DRIVE, door: Boolean = false): CoachOutput {
            now += SEC
            return e.update(
                CoachInput(now = now, gear = gear, speedKph = if (gear == Gear.PARK) 0f else 100f, doorOpened = door, route = route, candidates = cands, intent = intent),
            )
        }

        fun ticks(n: Int, cands: List<StopCandidate>, gear: Gear = Gear.DRIVE): List<CoachOutput> = (0 until n).map { tick(cands, gear = gear) }
    }

    private fun CoachOutput.codes() = next?.reasons.orEmpty().map { it.code }

    // ------------------------------------------------------------ 들르기로 한 곳이 된다

    @Test
    fun aStopTheEngineNeverAskedAboutBecomesTheAcceptedStop() {
        val d = Drive(engine(adult("me")), t0, route)
        val before = d.tick(listOf(a, b))
        assertNull(before.prompt, "어른 혼자, 30분 전 화장실: 아직 물을 것이 없다")
        assertNotEquals("a", before.next?.stop?.id)
        d.e.noteAccepted(a, setOf(Purpose.RESTROOM, Purpose.MEAL), d.now)
        val out = d.tick(listOf(a, b))
        assertEquals("a", out.next?.stop?.id)
        assertEquals(listOf("NEXT_ACCEPTED"), out.codes())
        assertEquals(setOf(Purpose.RESTROOM, Purpose.MEAL), out.next?.purposes)
        assertEquals(3 * km / v100, assertNotNull(out.next?.stop?.etaS, "들르기로 한 곳 카드에도 엔진 도착 시간"), 1.0)
        assertNull(out.prompt)
    }

    @Test
    fun withoutPurposesItIsARestroomStop() {
        val d = Drive(engine(adult("me")), t0, route)
        d.tick(listOf(a, b))
        d.e.noteAccepted(a, emptySet(), d.now)
        assertEquals(setOf(Purpose.RESTROOM), d.tick(listOf(a, b)).next?.purposes)
    }

    @Test
    fun itWorksBeforeTheFirstUpdateAndANewTripForgetsIt() {
        // 앱이 다시 떠서 엔진을 새로 만든 뒤, 저장해 둔 '들르기로 한 곳'을 첫 update 전에 알려 줄 수 있다
        val e = engine(adult("me"))
        e.noteAccepted(a, setOf(Purpose.RESTROOM), t0)
        val d = Drive(e, t0, route)
        assertEquals(listOf("NEXT_ACCEPTED"), d.tick(listOf(a, b)).codes())
        e.setProfile(TripProfile(listOf(adult("me")), tripStart = d.now))
        assertNotEquals(listOf("NEXT_ACCEPTED"), d.tick(listOf(a, b)).codes(), "새 여정은 앞 여정의 들르기로 한 곳을 모른다")
    }

    // ------------------------------------------------------------ 떠 있는 질문

    @Test
    fun anActiveQuestionAboutTheSameStopCountsAsAccepted() {
        val d = Drive(family(), t0, route)
        val q = assertNotNull(d.tick(listOf(a, b)).prompt)
        assertEquals(PromptKind.LAST_CHANCE, q.kind)
        assertEquals("a", q.stop?.id)
        d.e.noteAccepted(a, setOf(Purpose.RESTROOM), d.now)
        val out = d.tick(listOf(a, b))
        assertNull(out.prompt, "같은 곳을 묻던 질문은 대답한 것이다")
        assertEquals(listOf("NEXT_ACCEPTED"), out.codes())
        assertEquals(1, d.e.memory().promptStats.values.sumOf { it.accepted }, "수락률 학습에 수락 1회 (물은 거리 구간)")
        assertEquals(0, d.e.memory().promptStats.values.sumOf { it.declined })
        // 뒤따라 온 같은 질문의 '응'(화면이 둘 다 보낸 경우)은 두 번 세지 않는다
        d.e.onPromptResult(q.id, true, d.now)
        assertEquals(1, d.e.memory().promptStats.values.sumOf { it.accepted })
    }

    @Test
    fun anotherActiveRestroomQuestionIsSupersededWithoutPenalty() {
        val d = Drive(family(), t0, route)
        val q = assertNotNull(d.tick(listOf(a, b)).prompt)
        assertEquals("a", q.stop?.id)
        // 운전자가 계획 화면에서 나휴게소를 골랐다
        d.e.noteAccepted(b, setOf(Purpose.RESTROOM), d.now)
        val out = d.tick(listOf(a, b))
        assertNull(out.prompt, "다른 곳을 묻던 화장실 질문은 내린다")
        assertEquals("b", out.next?.stop?.id)
        assertEquals(listOf("NEXT_ACCEPTED"), out.codes())
        // 늦게 온 대답(음성 '응'이 뒤늦게 인식됨)이 고른 곳을 뒤집지 않는다
        d.e.onPromptResult(q.id, true, d.now)
        assertEquals("b", d.tick(listOf(a, b)).next?.stop?.id)
        assertTrue(d.e.memory().promptStats.values.all { it.accepted == 0 && it.declined == 0 }, d.e.memory().promptStats.toString())

        // 무시한 것으로 치지 않는다: 나휴게소가 목록에서 사라져(지나쳤다) 들르기로 한 곳이 풀리면 가휴게소를 곧바로 다시 물을 수 있다.
        // 무시한 질문이었다면 20분 쉼이 걸려 묻지 않는다 (아래 대조)
        val later = d.ticks(90, listOf(a))
        val again = assertNotNull(later.firstNotNullOfOrNull { it.prompt }, "쉼이 걸리지 않았다")
        assertEquals("a", again.stop?.id)
        assertNotEquals(q.id, again.id)
    }

    @Test
    fun controlAnUnansweredQuestionIsNotAskedAgainForTwentyMinutes() {
        // 위 테스트의 대조: 같은 질문을 대답 없이 두면(45초 뒤 내려감) 그 뒤 90초 동안 다시 묻지 않는다
        val d = Drive(family(), t0, route)
        val q = assertNotNull(d.tick(listOf(a, b)).prompt)
        val later = d.ticks(140, listOf(a, b))
        assertTrue(later.none { it.prompt != null && it.prompt?.id != q.id }, later.firstNotNullOfOrNull { it.prompt?.takeIf { p -> p.id != q.id } }.toString())
    }

    @Test
    fun whileHeadingToTheChosenStopTheEngineAsksAboutNoOtherStop() {
        // 급해 목록에서 엔진의 1순위(가까운 주유소)가 아닌 가휴게소를 골랐다 (UI 감사 #9: 엔진은 거절만 알고 30초 뒤 다른 곳을 물었다)
        val gas = stop("gas", 2.2 * km, kind = StopKind.GAS_STATION, name = "주유소")
        val cands = listOf(gas, a, b)
        val d = Drive(family(), t0, route)
        d.tick(cands)
        val urgent = d.tick(cands, intent = VoiceIntent.UrgentRestroom)
        val question = assertNotNull(urgent.prompt)
        assertEquals(PromptKind.URGENT_RESULT, question.kind)
        assertEquals("gas", question.stop?.id)
        d.e.noteAccepted(a, setOf(Purpose.RESTROOM), d.now)
        val outs = d.ticks(60, cands)
        assertTrue(outs.all { it.prompt == null }, "고른 곳으로 가는 1분 동안 아무것도 묻지 않는다: ${outs.firstNotNullOfOrNull { it.prompt }}")
        assertTrue(outs.all { it.next?.stop?.id == "a" && it.codes() == listOf("NEXT_ACCEPTED") })
        assertEquals(Level.NOW, outs.last().restroom.level, "게이지는 여전히 NOW다 (말로 알렸다)")
    }

    // ------------------------------------------------------------ 그곳을 다시 묻지 않는다

    @Test
    fun anUrgentRequestWhoseBestPlaceIsTheChosenStopIsNotAskedAgain() {
        // UI 감사 #9(b): 급해 목록의 1순위를 먼저 눌러 안내가 시작된 뒤 급해 의도가 엔진에 닿으면 "…바로 안내할까요?"를 또 물었다
        val cands = listOf(a, b)
        val d = Drive(engine(adult("me")), t0, route)
        d.tick(cands)
        d.e.noteAccepted(a, setOf(Purpose.RESTROOM), d.now)
        val out = d.tick(cands, intent = VoiceIntent.UrgentRestroom)
        assertEquals("a", out.urgent?.firstOrNull()?.stop?.id, "급해 결과 목록은 그대로 낸다")
        val p = assertNotNull(out.prompt, "말한 것에는 답한다")
        assertEquals(PromptKind.INFO, p.kind)
        assertEquals("가휴게소에 들르기로 했어요. 3km 남았어요.", p.speech)
        assertNull(p.stop)
        assertEquals(listOf("NEXT_ACCEPTED"), out.codes())
        assertTrue(d.ticks(60, cands).none { it.prompt?.kind == PromptKind.URGENT_RESULT || it.prompt?.kind == PromptKind.LAST_CHANCE })
    }

    @Test
    fun anUrgentRequestWithAFasterPlaceThanTheChosenStopStillOffersIt() {
        // 들르기로 한 곳(40km 앞)보다 빠른 곳이 있으면 급해 요청에는 그곳으로 답한다. 수락하면 들르기로 한 곳이 바뀐다
        val cands = listOf(a, b)
        val d = Drive(engine(adult("me")), t0, route)
        d.tick(cands)
        d.e.noteAccepted(b, setOf(Purpose.RESTROOM), d.now)
        val out = d.tick(cands, intent = VoiceIntent.UrgentRestroom)
        assertEquals(PromptKind.URGENT_RESULT, out.prompt?.kind)
        assertEquals("a", out.prompt?.stop?.id)
        val after = d.tick(cands, intent = VoiceIntent.Accept(out.prompt!!.id))
        assertEquals("a", after.next?.stop?.id)
        assertEquals(listOf("NEXT_ACCEPTED"), after.codes())
    }

    @Test
    fun aRequestWhoseAnswerIsTheChosenStopIsAnsweredWithoutAskingAgain() {
        // "아이가 화장실 가고 싶대", "쉬고 싶어", "배고파": 답할 곳이 이미 들르기로 한 곳이면 '들를까요?'를 다시 묻지 않는다
        // (다시 물으면 '응'에 같은 곳으로 내비 명령이 한 번 더 간다)
        val food = stop("a", 6 * km, name = "가휴게소", facilities = Facilities(food = true))
        val cands = listOf(food, b)
        for (intent in listOf(VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM), VoiceIntent.WantRest, VoiceIntent.Hungry)) {
            val d = Drive(engine(adult("me"), child("kid", 7)), t0, route)
            d.tick(cands)
            d.e.noteAccepted(food, setOf(Purpose.RESTROOM), d.now)
            val p = assertNotNull(d.tick(cands, intent = intent).prompt, intent.toString())
            assertEquals(PromptKind.INFO, p.kind, intent.toString())
            assertEquals("가휴게소에 들르기로 했어요. 6km 남았어요.", p.speech)
        }
    }

    // ------------------------------------------------------------ 유지와 해제, 학습

    @Test
    fun theChosenStopIsDroppedOnceItHasBeenMissingForAMinuteWhileDriving() {
        val d = Drive(engine(adult("me")), t0, route)
        d.tick(listOf(a, b))
        d.e.noteAccepted(a, setOf(Purpose.RESTROOM), d.now)
        assertEquals(listOf("NEXT_ACCEPTED"), d.tick(listOf(a, b)).codes())
        val gone = d.ticks(59, listOf(b))
        assertTrue(gone.all { it.codes() == listOf("NEXT_ACCEPTED") }, "목록에서 잠깐 빠진 것(깜빡임)으로는 풀지 않는다")
        val after = d.ticks(5, listOf(b))
        assertNotEquals(listOf("NEXT_ACCEPTED"), after.last().codes(), "1분 넘게 없으면 지나친 것이다")
    }

    @Test
    fun stoppingAtTheChosenStopIsLearnedAsAnAppSuggestedStop() {
        // 엔진이 묻지 않은 곳(20분 전 화장실이라 아직 물을 것이 없다)을 골라 그곳에 서서 문을 열고 4분:
        // 가장 급한 사람은 '앱이 권해서 들른 정차'(0.5회), 나머지는 '적어도 그만큼 버텼다'
        val d = Drive(engine(adult("me"), child("kid", 7), sinceMin = 20), t0, route)
        assertNull(d.tick(listOf(a, b)).prompt)
        d.e.noteAccepted(a, setOf(Purpose.RESTROOM), d.now)
        d.ticks(10, listOf(a, b))
        d.tick(emptyList(), gear = Gear.PARK, door = true)
        val parked = d.ticks(4 * 60, emptyList(), gear = Gear.PARK)
        assertTrue(parked.take(170).all { it.codes() == listOf("NEXT_ACCEPTED") }, "서 있는 동안에는 목록에 없어도(도착하면 빠진다) 내리지 않는다")
        val mem = d.e.memory()
        assertEquals(1, mem.restroomSuggestedMin["kid"]?.size, mem.toString())
        assertNull(mem.restroomIntervalsMin["kid"])
        assertEquals(1, mem.restroomCensoredMin["me"]?.size)
        assertNotEquals(listOf("NEXT_ACCEPTED"), d.tick(listOf(b)).codes(), "정차가 기록되면 들르기로 한 곳은 끝났다")
    }
}
