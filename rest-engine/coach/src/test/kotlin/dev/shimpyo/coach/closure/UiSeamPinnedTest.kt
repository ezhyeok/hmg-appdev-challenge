package dev.shimpyo.coach.closure

import dev.shimpyo.coach.CandidatesState
import dev.shimpyo.coach.CoachWorld
import dev.shimpyo.coach.IntentInterpreter
import dev.shimpyo.coach.UiCommand
import dev.shimpyo.coach.VoiceState
import dev.shimpyo.coach.fakes.CoreRig
import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeNaviPort
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.FakeVoice
import dev.shimpyo.coach.fakes.KID
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.VoiceIntent
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 닫힘 검증(2026-11-12, UI 감사 담당): 화면(app/ui)이 계약(CoachState.kt)에서 기대하는 것 가운데 코어 쪽 테스트가 없던 것.
 * 지금 모두 통과한다. 화면은 코어보다 먼저 계약만 보고 만들어졌으므로, 코어가 이 약속을 어기면 화면 테스트는 그대로 통과한 채
 * 화면이 틀린다. 열려 있던 틈을 닫은 테스트는 CoachCoreUrgentTest·CoachCoreGoToTest·CoachCoreVoiceTest·CoachCoreTest에 있다. 감사 번호는 scratchpad/audit/ui/report.md.
 */
class UiSeamPinnedTest {

    private fun CoreRig.untilLastChanceCard() {
        runUntil(150_000) { state.prompt?.kind == PromptKind.LAST_CHANCE } ?: error("마지막 기회 카드가 뜨지 않았다: ${log.lines.takeLast(10)}")
    }

    private fun holiday(rig: CoreRig) {
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
    }

    // ------------------------------------------------------------ 질문 카드 (UI #1·#6)

    @Test
    fun theCardsDistanceShrinksEveryTickItsReasonsStayAndAWithdrawnCardLeavesNothingBehind() = runTest {
        // 화면(DriveModels.promptCard)은 promptStop의 거리를 그대로 보이고 promptReasons로 맥락 줄을 정한다.
        // 코어가 promptStop을 띄울 때 값으로 두면 '3.9km'에 멈추고(UI #1), 카드를 내릴 때 셋을 함께 지우지 않으면 지난 이유가 다음 카드에 붙는다
        val rig = CoreRig(this)
        holiday(rig)
        rig.untilLastChanceCard()
        val key = rig.state.prompt!!.id
        val d0 = checkNotNull(rig.state.promptStop?.routeDistanceM) { "띄울 때 promptStop이 없다" }
        assertEquals("띄울 때의 이유", listOf("LAST_CHANCE_CONGESTION"), rig.state.promptReasons.map { it.code })
        assertEquals("카드 장소와 promptStop은 같은 곳", rig.state.prompt!!.stop!!.id, rig.state.promptStop!!.id)
        val a0 = rig.state.ahead.first { it.id == "oksan" }.routeDistanceM!!
        rig.run(3_000)
        val d1 = checkNotNull(rig.state.promptStop?.routeDistanceM)
        // 시연 차는 1초에 200m지만 카드가 떠 있는 동안은 4배 느리다 (CoachWorld.QUESTION_PACE): 3초에 150m
        val moved = 3 * 200.0 * CoachWorld.QUESTION_PACE
        assertTrue("카드가 떠 있는 동안 1초에 50m: 3초 뒤 ${d0.toInt()} → ${d1.toInt()}", d1 <= d0 - moved + 30.0)
        // 앞쪽 후보(계획 타임라인·경로 띠)도 매초 준다 (UI #19: 예전에는 20초마다)
        assertTrue("ahead도 매 틱 줄어든다", rig.state.ahead.first { it.id == "oksan" }.routeDistanceM!! <= a0 - moved + 30.0)
        assertEquals(listOf("LAST_CHANCE_CONGESTION"), rig.state.promptReasons.map { it.code })
        assertEquals("같은 카드", key, rig.state.prompt!!.id)

        // 엔진이 거두거나(500m 안) 카드 수명이 끝난다: 카드·이유·장소가 함께 사라지고, 늦은 '들르기'는 아무 일도 하지 않는다
        rig.runUntil(70_000) { rig.state.prompt == null } ?: error("카드가 내려가지 않았다")
        assertTrue(rig.state.promptReasons.isEmpty())
        assertNull(rig.state.promptStop)
        rig.command(UiCommand.Answer(key, true))
        rig.run(3_000)
        assertTrue("지나친 곳으로 내비 명령을 보내지 않는다", rig.navi.commands.isEmpty())
        assertNull(rig.state.goToResult)
    }

    @Test
    fun aTapWhileTheVoiceStillAsksStopsTheVoiceAndRunsOneNaviCommand() = runTest {
        // 묻는 중(6초)에 [들르기]를 눌렀다. 음성은 바로 멈추고, 늦게 들린 '응'이 경유지를 한 번 더 넣지 않는다 (구조 리뷰 D7)
        val rig = CoreRig(this, voice = FakeVoice(answer = { "응" }, speakMs = 6_000))
        holiday(rig)
        rig.untilLastChanceCard()
        rig.run(2_000)
        val stops = rig.voice.stops
        rig.command(UiCommand.Answer(rig.state.prompt!!.id, true))
        assertNull("카드는 바로 내려간다", rig.state.prompt)
        assertTrue("묻던 음성을 멈춘다", rig.voice.stops > stops)
        rig.run(10_000)
        assertEquals("내비 명령은 한 번", 1, rig.navi.commands.size)
        assertEquals("다시 묻지 않는다", 1, rig.voice.asked.size)
        assertEquals(listOf("NEXT_ACCEPTED"), rig.state.next?.reasons?.map { it.code })
    }

    // ------------------------------------------------------------ 마이크 (UI #12)

    @Test
    fun stopVoiceKeepsTheCardForAButtonAndTheNextMicTapHearsItsAnswer() = runTest {
        // 화면(MicModel): 말하거나 듣는 중이면 StopVoice, 쉬는 중이면 Listen. 멈춘 뒤에도 카드는 남아 버튼·마이크로 답한다
        val rig = CoreRig(this, voice = FakeVoice(speakMs = 6_000))
        holiday(rig)
        rig.untilLastChanceCard()
        rig.run(1_000)
        val key = rig.state.prompt!!.id
        val stops = rig.voice.stops
        rig.command(UiCommand.StopVoice)
        assertTrue("VoicePort.stop을 부른다", rig.voice.stops > stops)
        assertEquals("카드는 그대로", key, rig.state.prompt?.id)
        rig.run(8_000)
        assertEquals("멈춘 뒤 적어도 10초는 누를 수 있다 (MIN_TAP_MS)", key, rig.state.prompt?.id)
        assertEquals("멈춘 질문을 다시 묻지 않는다", 1, rig.voice.asked.size)

        rig.voice.listens += "응"
        rig.command(UiCommand.Listen)
        rig.run(3_000)
        assertNull("마이크로 들은 '응'이 떠 있는 카드의 대답이 된다", rig.state.prompt)
        assertEquals(1, rig.navi.commands.size)
    }

    @Test
    fun aMicTapWhileTheCoachSpeaksStopsItAndDoesNotQueueAnotherListen() = runTest {
        // 예전(UI #12): 말하는 중에 마이크를 누르면 듣기를 하나 더 쌓았다. Listen은 바쁘면 멈추기다(화면 상태가 한 프레임 늦어도 안전)
        val rig = CoreRig(this, voice = FakeVoice(speakMs = 6_000))
        holiday(rig)
        rig.untilLastChanceCard()
        rig.run(1_000)
        rig.voice.listens += "졸려"
        val stops = rig.voice.stops
        rig.command(UiCommand.Listen)
        rig.run(5_000)
        assertTrue("멈춘다", rig.voice.stops > stops)
        assertEquals("듣기를 시작하지 않았다 (쌓아 둔 말이 그대로)", 1, rig.voice.listens.size)
        assertEquals(VoiceState.IDLE, rig.state.voice)
    }

    @Test
    fun theMicShowsThinkingWhileTheInterpreterWorksAndStopVoiceEndsIt() = runTest {
        // 화면은 THINKING이면 '생각하는 중' + 멈추기를 보인다. 예전에는 THINKING을 한 번도 올리지 않았다 (UI #12)
        val slow = object : IntentInterpreter {
            override val label = "느린 해석기"
            override suspend fun interpret(text: String, occupants: List<Occupant>, activePromptId: String?): VoiceIntent? {
                delay(5_000)
                return VoiceIntent.Sleepy
            }
            override suspend fun occupants(text: String): List<Occupant>? = null
        }
        val rig = CoreRig(this, interpreter = slow, settings = FakeSettings(listOf(DRIVER)))
        rig.voice.listens += "음 뭐랄까"
        rig.voice.listens += "음 글쎄"
        rig.park()
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.Listen)
        rig.run(3_000)
        assertEquals(VoiceState.THINKING, rig.state.voice)
        rig.run(4_000)
        assertEquals("해석이 끝나면 돌아온다", VoiceState.IDLE, rig.state.voice)
        // 서 있을 때 한 말("졸려")에도 엔진의 답이 카드로 뜨고 묻는다 (엔진 닫힘 검증 E6). 묻는 동안의 마이크 버튼은 멈추기라서,
        // 그 질문이 끝난 뒤(버튼 대기)에 다시 누른다
        rig.run(4_000)
        assertNotNull("전제: 말한 것의 답이 서 있어도 뜬다", rig.state.prompt)

        rig.command(UiCommand.Listen)
        rig.run(2_000)
        assertEquals(VoiceState.THINKING, rig.state.voice)
        rig.command(UiCommand.StopVoice)
        assertEquals("멈추면 바로 돌아온다", VoiceState.IDLE, rig.state.voice)
    }

    // ------------------------------------------------------------ 계획 (UI #4)

    @Test
    fun thePlanIsRebuiltWhileWantedSoAPassedStopLeavesItWithoutANewGeneration() = runTest {
        // 화면(RouteModel.livePlan)은 받은 계획을 지금 후보에 맞춰 보일 뿐이다. 계획을 다시 세우는 것은 코어의 일이다 (UI #4).
        // 기존 thePlanGenerationRisesOnlyWhenAPlanIsRequested는 번호만 본다: 계획을 다시 만들지 않아도 통과한다
        val rig = CoreRig(this)
        holiday(rig)
        rig.run(3_000)
        rig.command(UiCommand.MakePlan)
        rig.run(1_000)
        val first = checkNotNull(rig.state.plan).stops.first().stop.id
        // 시연 경로 시간이 시나리오 속도(시속 100km)와 맞은 뒤(U2)로는 첫 계획 정차가 정체 전 마지막 화장실인 옥산이다:
        // 천안호두에서 서면 정체 뒤 죽암까지 73분이라 아이의 마감을 넘는다 (예전 시속 119km 계산으로는 72분이라 천안호두였다)
        assertEquals("전제: 첫 계획 정차는 곧 지나칠 앞쪽 휴게소", "oksan", first)
        val gen = rig.state.planGeneration
        assertTrue("요청에 답한 계획에는 번호가 있다", gen > 0L)
        // 옥산 질문 카드가 떠 있는 동안은 시연이 4배 느리다 (CoachWorld.QUESTION_PACE): 카드 수명(약 55초)만큼 더 걸린다
        rig.runUntil(180_000) { rig.state.ahead.none { it.id == first } } ?: error("첫 정차를 지나치지 않았다")
        val late = rig.runUntil(35_000) { rig.state.plan!!.stops.none { it.stop.id == first } }
        assertNotNull("지나친 정차는 35초 안에 계획에서 빠진다 (30초 주기 또는 후보가 바뀔 때)", late)
        assertTrue("남은 계획 정차는 모두 앞쪽 후보에 있다", rig.state.plan!!.stops.all { p -> rig.state.ahead.any { it.id == p.stop.id } })
        assertEquals("고쳐 낸 계획은 번호를 올리지 않는다 ('계획을 세우는 중'이 요청 없이 풀리지 않게)", gen, rig.state.planGeneration)
    }

    // ------------------------------------------------------------ 세계 전환 (UI #15)

    @Test
    fun leavingADemoTakesItsCardVoiceAndResultDownAndAStaleAnswerDoesNothing() = runTest {
        // 예전(UI #15): 시연을 시작·종료해도 지난 질문이 남았다. 화면은 state.prompt가 null이 되는 것만으로 카드와 버튼을 내린다
        val rig = CoreRig(this, voice = FakeVoice(speakMs = 6_000))
        holiday(rig)
        rig.untilLastChanceCard()
        val key = rig.state.prompt!!.id
        val stops = rig.voice.stops
        rig.command(UiCommand.Demo(null))
        assertNull(rig.state.prompt)
        assertTrue(rig.state.promptReasons.isEmpty())
        assertNull(rig.state.promptStop)
        assertNull(rig.state.plan)
        assertNull(rig.state.demo)
        assertTrue("묻던 음성을 멈춘다", rig.voice.stops > stops)
        rig.command(UiCommand.Answer(key, true))
        rig.run(5_000)
        assertTrue("지난 세계의 카드에 한 대답으로 내비 명령을 보내지 않는다", rig.navi.commands.isEmpty())
    }

    // ------------------------------------------------------------ 화면이 버튼을 켜고 끄는 값 (UI #12·#16·#18)

    @Test
    fun voiceAndCabinAvailabilityFollowThePorts() = runTest {
        // 화면: sttAvailable이 false면 마이크가 이유를 알리고, cabinSupported가 false면 환기 권유에 [확인]만, 설정 스위치는 꺼 둔다
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.voice.sttReady = false
        rig.cabin.freshAirSupported = false
        rig.park()
        rig.start()
        rig.run(1_000)
        assertTrue(rig.state.ttsAvailable)
        assertFalse(rig.state.sttAvailable)
        assertFalse(rig.state.cabinSupported)
        // 인식이 안 되면 Listen은 아무것도 하지 않는다 (화면이 이유를 알린다)
        rig.voice.listens += "졸려"
        rig.command(UiCommand.Listen)
        rig.run(3_000)
        assertEquals(1, rig.voice.listens.size)

        rig.voice.sttReady = true
        rig.voice.ttsReady = false
        rig.cabin.freshAirSupported = true
        rig.run(1_000)
        assertFalse(rig.state.ttsAvailable)
        assertTrue(rig.state.sttAvailable)
        assertTrue(rig.state.cabinSupported)
    }

    @Test
    fun aCarWithoutRecirculationControlIsNeverSwitchedEvenWithAutoFreshAirOn() = runTest {
        // UI #16: 지원하지 않는 차에서 '외기로 전환'이 조용히 아무 일도 하지 않았다. 지금은 화면이 받지 않게 하고(cabinSupported),
        // 코어도 보내지 않는다. 같은 조건의 지원하는 차에서는 실제 400초 안에 한 번 바꾼다 (시나리오가 비어 있지 않다는 확인)
        fun switches(supported: Boolean): Int {
            val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER, KID), autoFreshAir = true))
            rig.cabin.freshAirSupported = supported
            rig.hub.hvacOn = true
            rig.hub.recirculation = true
            holiday(rig)
            rig.run(400_000)
            return rig.cabin.freshAirAt.size
        }
        assertTrue("지원하는 차: 자동 환기가 있었다", switches(supported = true) >= 1)
        assertEquals("지원하지 않는 차: 한 번도 보내지 않는다", 0, switches(supported = false))
    }

    @Test
    fun candidatesStateSaysWhyTheListIsEmptyBeforeItIsReady() = runTest {
        // 화면(DriveModels.emptyCard, PlanScreen.noOpportunityText): READY가 아니면 '화장실 정보가 없어요'라고 하지 않는다 (UI #18)
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.fakeNavi.faults["currentLocation"] = FakeNaviPort.Fault.THROW // 내비 위치로도 보충하지 못한다
        rig.drive(60f)
        rig.start()
        rig.run(3_000)
        assertEquals(CandidatesState.NO_LOCATION, rig.state.candidatesState)
        assertTrue(rig.state.ahead.isEmpty())
        rig.fix(37.40, 127.10, 180f)
        rig.runUntil(10_000) { rig.state.candidatesState == CandidatesState.READY } ?: error("위치를 받은 뒤 READY가 되지 않았다: ${rig.state.candidatesState}")
    }
}
