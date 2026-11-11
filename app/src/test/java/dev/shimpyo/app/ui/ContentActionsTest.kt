package dev.shimpyo.app.ui

import dev.shimpyo.app.ui.components.OverlayState
import dev.shimpyo.app.ui.components.describeModel
import dev.shimpyo.app.ui.components.micModel
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.UiCommand
import dev.shimpyo.coach.VoiceState
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PitStopPlan
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.RankedStop
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 화면이 코치에 보내는 명령과 그 순서 (ShimpyoContent의 ContentActions). 순수 모델 시험은 '무엇을 보이는가'만 지켰고
 * '무엇을 보내는가'를 지키는 시험이 없었다 (닫힘 검증 UI-3·UI-9·UI-N2, 리뷰 R-F12, UI 감사 #24).
 */
class ContentActionsTest {

    private class RecordingHost(override val prefs: PrefsState = MemoryPrefs()) : AppHost {
        val sent = ArrayList<UiCommand>()
        override fun send(command: UiCommand) {
            sent += command
        }
        override fun clearRecords() = true
        override fun moveToBack() = Unit
        override val versionName = "test"
    }

    private class Rig(var state: CoachUiState = CoachUiState(serviceRunning = true), val ui: ContentState = ContentState(Screen.DRIVE)) {
        val host = RecordingHost()
        val overlays = OverlayState()
        val actions = ContentActions(ui, host, overlays) { state }
        val sent: List<UiCommand> get() = host.sent
    }

    private fun stop(id: String, name: String) = StopCandidate(id, name, StopKind.HIGHWAY_REST_AREA, 36.6, 127.3, Facilities(), true)
    private val oksan = stop("oksan", "옥산휴게소")
    private val jukam = stop("jukam", "죽암휴게소")

    // ------------------------------------------------------------ 들르기 (UI-3, UI-N2, #24)

    @Test
    fun retryFromTheFailedCardSendsOnlyTheGoTo() {
        // PromptMachine은 DismissGoToResult에서 '실패한 수락 → 다시 시도' 연결을 끊는다. 다시 시도가 Dismiss를 먼저 보내면 경유지는
        // 들어가도 엔진은 수락을 모른다 (rest-engine/coach …/closure/UiSeamOpenTest.aRetryAsTheFailedCardSendsIt…).
        // 코어는 새 들르기를 시작할 때 지난 결과를 스스로 내린다: 다시 시도는 GoTo 하나면 된다
        val rig = Rig()
        rig.actions.retryGoTo(oksan)
        assertEquals(listOf<UiCommand>(UiCommand.GoTo(oksan)), rig.sent)
    }

    @Test
    fun aSecondTapWhileAGoToIsBusySendsNothing() {
        // #24: 두 번 누르면 경유지 요청이 두 번 갔다. 보낸 직후(코치가 알리기 전)에도, 코치가 넣는 중일 때도 막는다
        val rig = Rig()
        rig.actions.goTo(oksan)
        rig.actions.goTo(oksan)
        rig.actions.goTo(jukam)
        rig.actions.retryGoTo(jukam)
        assertEquals(listOf<UiCommand>(UiCommand.GoTo(oksan)), rig.sent)
        assertEquals("oksan", rig.ui.goToSentId)

        // 화면이 보낸 것이 없어도 코치가 넣는 중이면(음성 '응'으로 시작한 들르기) 다른 곳을 보내지 않는다: 경유지가 둘 들어간다
        val busy = Rig(CoachUiState(serviceRunning = true, goToPending = oksan))
        busy.actions.goTo(jukam)
        busy.actions.retryGoTo(jukam)
        busy.actions.pick(RankedStop(jukam, 180.0, emptyList()))
        assertTrue(busy.sent.toString(), busy.sent.isEmpty())
        assertNull(busy.ui.goToSentId)
    }

    // ------------------------------------------------------------ 급해 결과 고르기 (UI-9)

    private fun urgentQuestion(about: StopCandidate) = Prompt("u#1", PromptKind.URGENT_RESULT, "가장 빠른 곳은 ${about.name}, 약 3분이에요. 안내할까요?", about, Long.MAX_VALUE)

    @Test
    fun pickingTheStopTheCoachAsksAboutAnswersThatQuestion() {
        val rig = Rig(CoachUiState(serviceRunning = true, prompt = urgentQuestion(oksan)))
        rig.actions.pick(RankedStop(oksan, 180.0, emptyList()))
        assertEquals(listOf<UiCommand>(UiCommand.Answer("u#1", true)), rig.sent)
        assertEquals("oksan", rig.ui.pickingId)
        // 한 번 고르면 결과가 올 때까지 다른 카드는 막는다
        rig.actions.pick(RankedStop(jukam, 240.0, emptyList()))
        assertEquals(1, rig.sent.size)
    }

    @Test
    fun pickingAnotherResultNeverDeclinesTheCoachsQuestion() {
        // UI-9: 2순위를 고르면 화면이 GoTo + 급해 질문 거절을 보냈다. 엔진은 거절만 알고 30초 뒤 다시 물었다.
        // 고른 것이 그 질문의 답이다(코치가 질문을 거둔다): 화면은 거절을 보내지 않는다
        val rig = Rig(CoachUiState(serviceRunning = true, prompt = urgentQuestion(oksan)))
        rig.actions.pick(RankedStop(jukam, 240.0, emptyList()))
        assertEquals(listOf<UiCommand>(UiCommand.GoTo(jukam)), rig.sent)
        // 질문이 뜨기 전(결과가 먼저 보인 1초 안)에 누른 것도 GoTo 하나다
        val early = Rig()
        early.actions.pick(RankedStop(oksan, 180.0, emptyList()))
        assertEquals(listOf<UiCommand>(UiCommand.GoTo(oksan)), early.sent)
    }

    @Test
    fun closingTheUrgentScreenDeclinesTheQuestionOnlyWhenNothingWasPicked() {
        // 고르지 않고 닫았다: 급해 질문에 '아니'다
        val closed = Rig(CoachUiState(serviceRunning = true, prompt = urgentQuestion(oksan)))
        closed.actions.closeUrgent(7L)
        assertEquals(listOf(UiCommand.Answer("u#1", false), UiCommand.DismissUrgent(7L)), closed.sent)
        assertEquals(setOf(7L), closed.ui.dismissedUrgent)

        // 다른 곳을 고르고 내비가 확인하는 동안 닫았다: 거절이 아니다 (고른 곳으로 가는 중이다)
        val picked = Rig(CoachUiState(serviceRunning = true, prompt = urgentQuestion(oksan)))
        picked.actions.pick(RankedStop(jukam, 240.0, emptyList()))
        picked.actions.closeUrgent(7L)
        assertEquals(listOf(UiCommand.GoTo(jukam), UiCommand.DismissUrgent(7L)), picked.sent)
        // 화면은 닫혔지만 들르기는 진행 중이다: 다른 화면의 넣기 버튼도 계속 막는다
        assertNull(picked.ui.pickingId)
        assertEquals("jukam", picked.ui.goToSentId)
    }

    // ------------------------------------------------------------ 급해 요청 (UI 감사 #7·#8)

    @Test
    fun theUrgentButtonSendsOneRequestAndRetryReplacesIt() {
        // 급해 버튼을 한 프레임 안에 두 번 눌러도 요청은 하나다
        val rig = Rig()
        rig.actions.requestUrgent()
        rig.actions.requestUrgent()
        val first = (rig.sent.single() as UiCommand.Urgent).requestId
        assertTrue("번호 0은 쓰지 않는다 (번호를 돌려주지 않는 옛 코치의 값)", first != 0L)
        assertEquals(first, rig.ui.urgentRequested)

        // [다시 시도]: 끝난 앞 요청을 코치에서도 정리하고 닫은 번호에 넣은 뒤 새 번호로 보낸다
        rig.actions.requestUrgent(replacing = first)
        val second = (rig.sent.last() as UiCommand.Urgent).requestId
        assertEquals(listOf(UiCommand.Urgent(first), UiCommand.DismissUrgent(first), UiCommand.Urgent(second)), rig.sent)
        assertTrue(second != first)
        assertEquals(second, rig.ui.urgentRequested)
        assertTrue(first in rig.ui.dismissedUrgent)
    }

    // ------------------------------------------------------------ 질문·마이크·화면 이동 (UI 감사 #12)

    @Test
    fun answersMicAndDismissSendTheirCommands() {
        val rig = Rig()
        val p = Prompt("q1", PromptKind.LAST_CHANCE, "들를까요?", oksan, Long.MAX_VALUE)
        rig.actions.answer(p, true)
        rig.actions.answer(p, false)
        rig.actions.dismissGoTo()
        assertEquals(listOf(UiCommand.Answer("q1", true), UiCommand.Answer("q1", false), UiCommand.DismissGoToResult), rig.sent)

        // 마이크: 쉬는 중이면 듣기, 말하거나 듣는 중이면 멈추기, 쓸 수 없으면 명령 없이 이유만 (예전에는 누를 때마다 듣기를 쌓았다)
        val mic = Rig()
        mic.actions.mic(micModel(VoiceState.IDLE, ttsAvailable = true, sttAvailable = true, voiceGuidance = true))
        mic.actions.mic(micModel(VoiceState.SPEAKING, ttsAvailable = true, sttAvailable = true, voiceGuidance = true))
        mic.actions.mic(micModel(VoiceState.LISTENING, ttsAvailable = true, sttAvailable = true, voiceGuidance = true))
        assertEquals(listOf(UiCommand.Listen, UiCommand.StopVoice, UiCommand.StopVoice), mic.sent)
        mic.actions.mic(micModel(VoiceState.IDLE, ttsAvailable = true, sttAvailable = false, voiceGuidance = true))
        assertEquals(3, mic.sent.size)
        assertEquals("지금은 음성 인식을 쓸 수 없어요", mic.overlays.toast?.message)
    }

    @Test
    fun theDemoPanelReturnsToTheScreenItWasOpenedFrom() {
        val rig = Rig(ui = ContentState(Screen.HISTORY))
        rig.actions.open(Screen.DEMO)
        assertEquals(Screen.DEMO, rig.ui.screen)
        assertEquals(Screen.HISTORY, rig.ui.demoReturn)
        rig.actions.open(Screen.PLAN)
        assertEquals(Screen.PLAN, rig.ui.screen)
        assertTrue("화면을 옮기는 것만으로는 코치에 아무것도 보내지 않는다", rig.sent.isEmpty())
    }

    // ------------------------------------------------------------ 계획 화면 (R-F12)

    @Test
    fun leavingThePlanScreenClosesThePlanOnceAndComingBackResumesIt() {
        // 리뷰 R-F12: 계획을 한 번 요청하면 화면을 떠나도 코치가 30초마다 다시 세웠다(10분에 30번). 떠날 때·주행을 시작할 때 닫는다
        val rig = Rig(ui = ContentState(Screen.PLAN))
        rig.actions.planVisibility(true)
        assertTrue("계획이 없으면 버튼을 눌러야 세운다", rig.sent.isEmpty())
        rig.actions.makePlan()
        rig.actions.planVisibility(true)
        assertEquals(listOf<UiCommand>(UiCommand.MakePlan), rig.sent)

        rig.state = rig.state.copy(plan = PitStopPlan(emptyList(), 0), planGeneration = 1L)
        rig.actions.planVisibility(false) // 다른 화면으로 갔거나 주행을 시작했다(주행 화면만 그린다)
        rig.actions.planVisibility(false)
        assertEquals(listOf(UiCommand.MakePlan, UiCommand.ClosePlan), rig.sent)
        assertFalse(rig.ui.planWanted)

        // 다시 열면 떠 있는 동안 코치가 계획을 고쳐 세우게 다시 요청한다 (UI 감사 #4: 계획이 낡은 채로 보였다)
        rig.actions.planVisibility(true)
        assertEquals(listOf(UiCommand.MakePlan, UiCommand.ClosePlan, UiCommand.MakePlan), rig.sent)
        assertTrue(rig.ui.planWanted)
        assertEquals(1L, rig.ui.planRequestedGen)
    }

    @Test
    fun leavingWhileThePlanIsStillBeingBuiltDropsTheWaitAndAsksAgainOnReturn() {
        // [계획 세우기]를 누르고 답이 오기 전에 화면을 떠났다(또는 앱이 가려졌다). 코치는 닫힌 요청의 답을 내지 않는다:
        // '세우는 중'을 내리지 않으면 6초 뒤 다른 화면에서 "계획을 세우지 못했어요"가 뜬다. 돌아오면 이어서 요청한다
        val rig = Rig(ui = ContentState(Screen.PLAN))
        rig.actions.makePlan()
        assertTrue(rig.ui.planRequested)
        rig.actions.planVisibility(false)
        assertEquals(listOf(UiCommand.MakePlan, UiCommand.ClosePlan), rig.sent)
        assertFalse("기다리던 표시를 내린다", rig.ui.planRequested)
        rig.actions.planVisibility(true)
        assertEquals(listOf(UiCommand.MakePlan, UiCommand.ClosePlan, UiCommand.MakePlan), rig.sent)
        assertTrue(rig.ui.planRequested)
        // 한 번만 잇는다: 다시 떠났다 돌아와도(계획이 아직 없고 기다리던 것도 없으면) 버튼을 눌러야 세운다
        rig.ui.planRequested = false
        rig.actions.planVisibility(false)
        rig.actions.planVisibility(true)
        assertEquals(listOf(UiCommand.MakePlan, UiCommand.ClosePlan, UiCommand.MakePlan, UiCommand.ClosePlan), rig.sent)
    }

    @Test
    fun aScreenThatNeverAskedForAPlanSendsNoClosePlan() {
        val rig = Rig()
        rig.actions.planVisibility(false)
        assertTrue(rig.sent.isEmpty())
    }

    // ------------------------------------------------------------ 탑승자 화면 (UI-12, U4)

    @Test
    fun describeByVoiceListensStopsOrExplains() {
        val rig = Rig()
        rig.actions.describe(describeModel(VoiceState.IDLE, ttsAvailable = true, sttAvailable = true, voiceGuidance = true))
        assertEquals(listOf<UiCommand>(UiCommand.DescribeOccupants), rig.sent)
        rig.actions.describe(describeModel(VoiceState.LISTENING, ttsAvailable = true, sttAvailable = true, voiceGuidance = true))
        assertEquals(listOf(UiCommand.DescribeOccupants, UiCommand.StopVoice), rig.sent)
        // 쓸 수 없으면 명령을 보내지 않고 이유를 알린다 (예전에는 눌러도 조용히 아무 일도 없었다)
        assertNull(rig.overlays.toast)
        rig.actions.describe(describeModel(VoiceState.IDLE, ttsAvailable = true, sttAvailable = false, voiceGuidance = true))
        assertEquals(2, rig.sent.size)
        assertEquals("지금은 음성 인식을 쓸 수 없어요", rig.overlays.toast?.message)
    }

    @Test
    fun occupantDemoAndRecordCommandsGoThroughTheSamePlace() {
        val rig = Rig()
        val list = listOf(Occupant("driver", OccupantType.ADULT, label = "운전자", intervalOverrideMin = 120.0))
        rig.actions.setOccupants(list)
        rig.actions.demo("holiday")
        assertEquals("시연 시작: 귀성길 정체", rig.overlays.toast?.message)
        rig.actions.demo(null)
        assertEquals("시연을 끝냈어요", rig.overlays.toast?.message)
        assertEquals(listOf(UiCommand.SetOccupants(list), UiCommand.Demo("holiday"), UiCommand.Demo(null)), rig.sent)
        // 기록 지우기는 호스트가 한다(서비스에 ClearRecords를 보낸다): 화면은 결과만 알린다
        rig.actions.clearRecords()
        assertEquals("기록을 지웠어요", rig.overlays.toast?.message)
        assertEquals(3, rig.sent.size)
    }

    @Test
    fun theAsleepToggleSendsSetAsleep() {
        val rig = Rig()
        rig.actions.setAsleep("kid", true)
        rig.actions.setAsleep("kid", false)
        assertEquals(listOf<UiCommand>(UiCommand.SetAsleep("kid", true), UiCommand.SetAsleep("kid", false)), rig.sent)
    }
}
