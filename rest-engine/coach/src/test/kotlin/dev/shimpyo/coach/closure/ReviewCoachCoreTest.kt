package dev.shimpyo.coach.closure

import dev.shimpyo.coach.GoToOutcome
import dev.shimpyo.coach.IntentInterpreter
import dev.shimpyo.coach.UiCommand
import dev.shimpyo.coach.UrgentState
import dev.shimpyo.coach.VoiceState
import dev.shimpyo.coach.fakes.CoreRig
import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.FakeVoice
import dev.shimpyo.coach.fakes.Gyeongbu
import dev.shimpyo.coach.fakes.InMemoryMemory
import dev.shimpyo.coach.fakes.KID
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.VoiceIntent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 독립 리뷰(2026-11-12)가 CoachCore에서 찾은 것. 지금 실패한다(고치면 통과). `// OPEN:`이 이유다.
 * 제품 코드는 고치지 않았다. CoreRig(서비스와 같은 조립, 가상 시간)로 끝까지 돌린다.
 */
class ReviewCoachCoreTest {

    /** 귀성길 시연에서 옥산휴게소 질문에 [reply]로 한 번만 답하는 음성 */
    private fun replyOnce(reply: String): FakeVoice {
        var first = true
        return FakeVoice(answer = { q -> if ("옥산" in q && first) reply.also { first = false } else null })
    }

    // ------------------------------------------------------------ 묻기 대답 안의 '다른 요청'

    @Test
    fun aSpokenYesThatNamesThePlaceIsOneAnswerNotAlsoANewRequest() = runTest {
        // OPEN: 카드 질문에 "응 휴게소 들르자"라고 답하면 수락(들르기)과 함께 같은 말이 WantRest로 다시 전달된다
        // (CoachCore.handle AskHeard → parser.parse(text, activePromptId = null) → PromptMachine.onHeard의 Forward).
        // 엔진은 그것을 새 요청으로 보고 다음 틱에 사용자 응답 질문을 또 낸다: 방금 수락한 운전자가 한 번 더 질문을 듣는다.
        // "응 화장실 가자"는 OccupantNeed(RESTROOM)로 전달돼 게이지를 NOW로 올리고 학습 간격까지 남긴다
        // (같은 경로: askingHowLongToTheRestroomDoesNotMarkANeedOrTeachTheModel).
        val rig = CoreRig(this, voice = replyOnce("응 휴게소 들르자"))
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        rig.runUntil(150_000) { rig.state.goToResult != null } ?: error("들르기 결과 없음: ${rig.log.lines.takeLast(10)}")
        assertEquals(GoToOutcome.ROUTE_STARTED, rig.state.goToResult!!.outcome)
        rig.run(20_000)
        assertEquals("수락한 뒤에는 다시 묻지 않는다: ${rig.voice.asked}", 1, rig.voice.asked.size)
    }

    @Test
    fun answeringNoNotUrgentDoesNotOpenUrgentMode() = runTest {
        // OPEN: "아니 안 급해"는 거절인데, 같은 말이 IntentParser에서 UrgentRestroom("급해" 포함)으로 읽혀 Forward된다.
        // 코어가 급해 요청(음수 번호)을 열고, 엔진은 급해 질문("바로 안내할까요?")을 또 묻는다. 거절한 운전자에게 정반대로 움직인다.
        val rig = CoreRig(this, voice = replyOnce("아니 안 급해"))
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        rig.runUntil(150_000) { rig.voice.asked.isNotEmpty() } ?: error("질문이 없다")
        rig.run(6_000)
        assertEquals("거절했는데 급해 요청이 열렸다 (번호 ${rig.state.urgentRequestId})", UrgentState.IDLE, rig.state.urgentState)
        assertEquals("거절 뒤에 또 묻는다: ${rig.voice.asked}", 1, rig.voice.asked.size)
    }

    @Test
    fun askingHowLongToTheRestroomDoesNotMarkANeedOrTeachTheModel() = runTest {
        // OPEN: 엔진 계약의 HowLong 예문 "화장실까지 얼마나 남았어?"가 IntentParser에서 OccupantNeed(null, RESTROOM)로 읽힌다
        // ('화장실'을 '얼마나 남'보다 먼저 본다, ReviewIntentParserTest). 코어는 그대로 엔진에 넘기고, 엔진은 운전자가 화장실이
        // 필요하다고 말한 것으로 본다: 게이지가 70분/OK에서 0분/NOW로 뛰고, 80분 간격이 관측으로 학습 파일에 저장되고,
        // 답 대신 "다음 화장실은 …에 들를까요?"를 묻는다.
        val memory = InMemoryMemory()
        val rig = CoreRig(this, memory = memory, settings = FakeSettings(listOf(DRIVER)))
        rig.voice.listens += "화장실까지 얼마나 남았어?"
        rig.drive(100f)
        rig.fix(37.40, 127.10)
        rig.start()
        rig.run(20 * 60_000L)
        assertEquals("전제: 여유가 있다", Level.OK, rig.state.restroom?.level)
        rig.command(UiCommand.Listen)
        rig.run(10_000)
        assertEquals("물어봤을 뿐인데 화장실이 필요하다고 말한 것이 됐다 (${rig.state.restroom?.reasons?.map { it.code }})", Level.OK, rig.state.restroom?.level)
        assertTrue("물어봤을 뿐인데 화장실 간격을 배웠다: ${memory.stored.restroomIntervalsMin}", memory.stored.restroomIntervalsMin.isEmpty())
        assertTrue("남은 시간을 답한다: said=${rig.voice.said} asked=${rig.voice.asked}", rig.voice.said.any { "여유" in it })
    }

    // ------------------------------------------------------------ 급해

    @Test
    fun dismissingRightAfterTheResultsAppearSaysNothing() = runTest {
        // OPEN: 결과가 나온 틱과 엔진에 급해를 알리는 다음 틱 사이(1초 안)에 닫으면, 이미 쌓아 둔 UrgentRestroom 의도가
        // pendingIntents에 남아 다음 틱에 엔진으로 간다(endUrgent가 지우지 않는다). 닫은 뒤에 엔진이 급해 답을 말한다.
        // core.md 4장 'DismissUrgent: 늦은 결과는 생길 수 없다'와 다르다 (LOCATING에서 닫는 경우만 테스트가 있다).
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.drive(30f)
        rig.fix(37.4979, 127.0276, 70f)
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.Urgent(5L))
        assertEquals("전제: 명령에 바로 결과가 나온다", UrgentState.READY, rig.state.urgentState)
        rig.command(UiCommand.DismissUrgent(5L))
        rig.run(5_000)
        assertTrue("닫은 뒤 말하지 않는다: said=${rig.voice.said} asked=${rig.voice.asked}", rig.voice.said.isEmpty() && rig.voice.asked.isEmpty())
        assertTrue("닫은 뒤 급해 카드가 없다", rig.state.prompt?.kind != PromptKind.URGENT_RESULT)
    }

    @Test
    fun aStopRequestedEarlierDoesNotCloseTheUrgentScreenWhenItCompletes() = runTest {
        // OPEN: onGoToSucceeded는 어떤 들르기든 성공하면 급해 요청을 끝낸다(u.near에 있는지는 고정에만 쓴다).
        // 계획 화면·마지막 기회 카드로 보낸 들르기가 내비 확인을 기다리는 동안(경유지 최대 30초, 새 길안내 최대 45초) 급해를 열면,
        // 앞 들르기가 확인되는 순간 고르지도 않은 급해 화면이 닫힌다. 세계 전환 전 들르기가 새 세계의 급해를 닫는 것도 같은 경로다.
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.fakeNavi.latencyMs["startRoute"] = 20_000L
        rig.drive(30f)
        rig.fix(37.4979, 127.0276, 70f)
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.GoTo(Gyeongbu.oksan))
        rig.run(2_000)
        rig.command(UiCommand.Urgent(8L))
        assertEquals("전제: 급해 결과가 떠 있다", UrgentState.READY, rig.state.urgentState)
        rig.runUntil(40_000) { rig.state.goToResult != null } ?: error("앞 들르기 결과 없음")
        assertEquals("oksan", rig.state.goToResult!!.stop.id)
        assertEquals("급해 목록에서 아무것도 고르지 않았다", UrgentState.READY, rig.state.urgentState)
    }

    @Test
    fun theUrgentStopIsNoLongerRecommendedOnceTheCarIsFarPastIt() = runTest {
        // OPEN: 급해에서 수락한 곳(pinned)은 직선거리만 아는 후보로 매 틱 엔진에 다시 들어간다(CoreRules.withExtras가 지금 위치로 다시 잰다).
        // 직선거리는 지나쳐도 0보다 크므로 엔진의 trackAccepted는 그곳을 '아직 앞에 있다'고 보고 90분(ACCEPT_HOLD_MS)까지 들고 있고,
        // trackPinned는 엔진이 추천을 멈춰야만 고정을 푼다. 그래서 들르지 않고 지나치면(3분 미만 정차 포함) 90분 동안 추천이 뒤에 있는
        // 그곳에 묶이고, 엔진은 accepted가 있는 동안 마지막 기회·지금 쉬기 질문을 내지 않는다.
        val rig = CoreRig(this, voice = FakeVoice(answer = { if ("바로 안내할까요" in it) "응" else null }), settings = FakeSettings(listOf(DRIVER)))
        rig.drive(30f)
        rig.fix(37.4979, 127.0276, 70f)
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.Urgent(3L))
        rig.runUntil(20_000) { rig.state.goToResult != null } ?: error("결과 없음: ${rig.log.lines.takeLast(10)}")
        rig.run(30_000)
        assertEquals("전제: 수락한 곳을 추천한다", "gangnam-wc", rig.state.next?.stop?.id)
        // 들르지 않고 20km 넘게 지나갔다
        rig.drive(80f)
        rig.fix(37.60, 127.25, 70f)
        rig.run(10 * 60_000L)
        assertNotEquals("10분 전에 22km 뒤로 지나친 곳을 아직 '들르기로 한 곳'으로 추천한다", "gangnam-wc", rig.state.next?.stop?.id)
    }

    @Test
    fun sayingImUrgentAgainWhileTheRequestIsOpenIsAnsweredAgain() = runTest {
        // OPEN: 급해 요청이 열려 있는 동안(화면을 닫지 않았다) "화장실 급해"를 다시 말하면 코어는 목록만 다시 찾는다
        // (dispatchIntent → UrgentSession.searchAgain). 엔진에는 요청마다 한 번만 알리므로(engineTold) 두 번째 말에는
        // 급해 질문도 안내도 없다: 운전자는 급하다고 다시 말했는데 아무 대답도 듣지 못한다.
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.voice.listens += listOf("화장실 급해", "화장실 급해")
        rig.drive(30f)
        rig.fix(37.4979, 127.0276, 70f)
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.Listen)
        rig.run(60_000) // 첫 급해 질문은 대답 없이 수명(45초)을 다한다. 요청은 열려 있다
        assertEquals("전제: 요청이 열려 있다", UrgentState.READY, rig.state.urgentState)
        assertTrue("전제: 첫 번째 말에는 급해 질문을 했다", rig.voice.asked.any { "바로 안내할까요" in it })
        val spoken = rig.voice.asked.size + rig.voice.said.size
        rig.command(UiCommand.Listen)
        rig.run(10_000)
        assertTrue("두 번째 \"화장실 급해\"에 아무 말도 하지 않는다", rig.voice.asked.size + rig.voice.said.size > spoken)
    }

    // ------------------------------------------------------------ 음성 안내 끔

    @Test
    fun withVoiceGuidanceOffASpokenQuestionStillGetsAnAnswerSomewhere() = runTest {
        // OPEN: 화면은 음성 안내를 꺼도 마이크로 듣게 한다(MicModel '말하기 (대답은 화면으로)'). 그런데 엔진의 답은 INFO(말만 하는 안내)라
        // PromptMachine.info가 ctx.speak == false면 아무 효과도 내지 않고 Report로 닫는다: 답이 소리로도 화면으로도 나가지 않는다.
        // 급해 '근처에 없음' 안내, '잘 못 알아들었어요'도 같다.
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER), voiceEnabled = false))
        rig.voice.listens += "얼마나 남았어"
        rig.drive(100f)
        rig.fix(37.40, 127.10)
        rig.start()
        rig.run(3_000)
        rig.command(UiCommand.Listen)
        var answered = false
        repeat(10) {
            rig.run(1_000)
            if (rig.state.prompt != null || rig.state.status != null || rig.voice.said.isNotEmpty()) answered = true
        }
        assertTrue("\"얼마나 남았어\"의 답이 소리로도 화면(prompt/status)으로도 나오지 않았다", answered)
    }

    // ------------------------------------------------------------ 탑승자 말하기

    @Test
    fun describingOnlyAHealthTypeWithoutConsentKeepsThePassengersAndSaysNoEmptySentence() = runTest {
        // OPEN: 동의 없이 "임산부 한 명"이라고 말하면 걸러진 목록이 운전자뿐인데도 그대로 적용한다: 타고 있던 아이가 지워지고,
        // OccupantPhrase.describe([운전자])가 빈 문자열이라 "로 맞췄어요"라고 말한다 (applySpokenOccupants).
        val rig = CoreRig(this, voice = FakeVoice(answer = { "임산부 한 명" }), settings = FakeSettings(listOf(DRIVER, KID), healthConsent = false))
        rig.park()
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.DescribeOccupants)
        rig.run(10_000)
        assertTrue("전제: 동의 안내를 말한다 ${rig.voice.said}", rig.voice.said.any { "동의" in it })
        assertTrue("주어 없는 문장을 말한다: ${rig.voice.said}", rig.voice.said.none { it.startsWith("로 맞췄어요") })
        assertEquals("받아들인 것이 없으면 탑승자를 바꾸지 않는다", listOf("driver", "kid"), rig.state.occupants.map { it.id })
    }

    @Test
    fun occupantsDescribedDuringADemoAreNotWrittenToTheRealSettingsWhenTheDemoEndsFirst() = runTest {
        // OPEN: 세계 전환(Reset → CancelVoice)은 카드의 묻기·듣기만 취소한다. 탑승자 말하기(describeJob)와 LLM 해석(interpretJob)은
        // 그대로 돌아서 결과가 전환 뒤에 도착하고, 그때의 세계에 적용된다: 시연 중에 물은 탑승자가 시연을 끈 뒤 실제 설정에 저장된다
        // (WorldSwitch.setOccupants의 Real 갈래). 반대로 실제에서 물은 답은 시연에만 들어가고 사라진다.
        val rig = CoreRig(this, voice = FakeVoice(answer = { "할머니랑 강아지" }, speakMs = 3_000), settings = FakeSettings(listOf(DRIVER)))
        rig.park()
        rig.start()
        rig.command(UiCommand.Demo("stop"))
        rig.run(2_000)
        rig.command(UiCommand.DescribeOccupants) // 질문을 말하는 데 3초
        rig.run(1_000)
        rig.command(UiCommand.Demo(null))
        rig.run(6_000)
        assertEquals("시연 중에 한 말이 실제 설정에 저장됐다: ${rig.settings.occupants.map { it.label }}", 0, rig.settings.occupantWrites)
    }

    // ------------------------------------------------------------ 효과 실패

    @Test
    fun anInterpreterThatThrowsDoesNotLeaveTheMicThinking() = runTest {
        // OPEN: 해석 효과는 결과를 보내는 줄에서 interpret를 부른다(send(Msg.Interpreted(text, interpreter.interpret(...)))).
        // interpret가 예외를 던지면 effect()가 로그만 남기고 Interpreted가 오지 않는다: 마이크 표시가 THINKING에 남고
        // '잘 못 알아들었어요'도 없다. 다른 효과(묻기·들르기·외기·저장)는 실패를 결과 메시지로 바꿔 보낸다.
        // 지금 구현체(PleosLlmInterpreter)는 예외를 삼켜서 실제로는 드물다.
        val broken = object : IntentInterpreter {
            override val label = "고장 난 해석기"
            override suspend fun interpret(text: String, occupants: List<Occupant>, activePromptId: String?): VoiceIntent? = error("llm crashed")
            override suspend fun occupants(text: String): List<Occupant>? = null
        }
        val rig = CoreRig(this, interpreter = broken, settings = FakeSettings(listOf(DRIVER)))
        rig.voice.listens += "창문 좀 열어 줄래"
        rig.drive(60f)
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.Listen)
        rig.run(5_000)
        assertNotEquals("해석이 실패했는데 '생각하는 중'에 남는다", VoiceState.THINKING, rig.state.voice)
        assertTrue("못 알아들었다고 알린다: ${rig.voice.said}", rig.voice.said.any { "못 알아들었어요" in it })
        assertNull(rig.state.prompt)
    }
}
