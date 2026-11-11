package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.CoreRig
import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeNaviPort
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.Gyeongbu
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.Purpose
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 들르기(화면의 GoTo, 질문 수락)를 코어 끝까지: 내비에 넣는 중 표시, 실패 뒤 다시 시도, 성공한 곳을 엔진에 알리기.
 * 닫힘 검증(2026-11-12)의 서비스 D7·D9, UI N2. 엔진에 알리는 길(EngineAccepted)은 가짜(RecordingAccepted)로 본다:
 * 엔진의 noteAccepted가 들어오면 같은 호출이 엔진으로 간다.
 */
class CoachCoreGoToTest {

    /** 경부선 남행을 시속 100km로 달리는 차 (길안내 없음: 들르기는 새 길안내가 된다) */
    private fun drivingRig(scope: TestScope): CoreRig {
        val rig = CoreRig(scope, settings = FakeSettings(listOf(DRIVER)))
        rig.drive(100f)
        rig.fix(37.40, 127.10)
        rig.start()
        rig.run(2_000)
        return rig
    }

    private fun CoreRig.untilLastChanceCard() {
        drive(100f)
        start()
        command(UiCommand.Demo("holiday"))
        runUntil(150_000) { state.prompt?.kind == PromptKind.LAST_CHANCE } ?: error("카드가 뜨지 않았다: ${log.lines.takeLast(10)}")
    }

    // ------------------------------------------------------------ 넣는 중 (UI 닫힘 검증 N2)

    @Test
    fun theStopIsShownAsPendingWhileTheNaviConfirmsAndClearedWithTheResult() = runTest {
        // 화면은 결과를 12초만 기다렸는데 에뮬레이터의 내비 확인은 19.5초 걸렸다: 성공 전에 '결과를 받지 못했어요'가 뜨고 버튼이 다시 켜졌다.
        // 코치가 넣는 중인 곳을 올리고, 화면은 그 값이 있는 동안 기다린다 (시간은 코치가 잰다)
        val rig = drivingRig(this)
        rig.fakeNavi.latencyMs["startRoute"] = 20_000L
        assertNull(rig.state.goToPending)
        rig.command(UiCommand.GoTo(Gyeongbu.oksan))
        assertEquals("명령에 바로 넣는 중으로 올린다", "oksan", rig.state.goToPending?.id)
        rig.run(19_000)
        assertEquals("내비가 확인하는 동안 그대로", "oksan", rig.state.goToPending?.id)
        assertNull(rig.state.goToResult)
        rig.runUntil(5_000) { rig.state.goToResult != null } ?: error("결과 없음")
        assertNull("결과가 나오면 내린다", rig.state.goToPending)
        assertEquals(GoToOutcome.ROUTE_STARTED, rig.state.goToResult!!.outcome)
    }

    @Test
    fun aQueuedGoToBecomesThePendingOneWhenItStarts() = runTest {
        val rig = drivingRig(this)
        rig.fakeNavi.latencyMs["startRoute"] = 10_000L
        rig.fakeNavi.latencyMs["addWaypoint"] = 10_000L
        rig.command(UiCommand.GoTo(Gyeongbu.oksan))
        rig.command(UiCommand.GoTo(Gyeongbu.jukam)) // 줄을 선다 (내비 명령은 한 번에 하나)
        assertEquals("oksan", rig.state.goToPending?.id)
        rig.runUntil(15_000) { rig.state.goToResult != null } ?: error("첫 결과 없음")
        assertEquals("oksan", rig.state.goToResult!!.stop.id)
        assertEquals("앞의 결과와 함께 다음 들르기가 넣는 중이 된다", "jukam", rig.state.goToPending?.id)
        rig.runUntil(15_000) { rig.state.goToResult?.stop?.id == "jukam" } ?: error("둘째 결과 없음")
        assertNull(rig.state.goToPending)
    }

    @Test
    fun aGoToThatNeverAnswersStopsPendingWhenTheWatchdogFailsIt() = runTest {
        // 화면에는 자기 시간 제한이 없다: 끝은 코치의 감시(140초)가 보장한다
        val rig = drivingRig(this)
        rig.fakeNavi.faults["routeStatus"] = FakeNaviPort.Fault.HANG
        rig.command(UiCommand.GoTo(Gyeongbu.oksan))
        rig.run(60_000)
        assertEquals("oksan", rig.state.goToPending?.id)
        rig.runUntil(PromptMachine.GO_TO_WATCHDOG_MS) { rig.state.goToResult != null } ?: error("감시가 끝내지 않았다")
        assertEquals(GoToOutcome.FAILED, rig.state.goToResult!!.outcome)
        assertNull(rig.state.goToPending)
    }

    // ------------------------------------------------------------ 다시 시도 (서비스 닫힘 검증 D9, UI #3)

    @Test
    fun aRetryAsTheFailedCardSendsItStillTellsTheEngineTheStopWasAccepted() = runTest {
        // 주행 화면 실패 카드의 [다시 시도]는 DismissGoToResult 다음에 GoTo를 보낸다 (ContentActions.retryGoTo). 예전에는 닫기가
        // 다시 시도 연결을 끊어서 경유지는 들어가는데 엔진에는 수락을 알리지 않았다: 엔진은 그 질문을 무시당한 것으로 세고(쉼·학습)
        // 들르기로 한 곳을 모른 채 다른 휴식 질문을 낼 수 있었다. 닫은 뒤에도 2분 안의 같은 곳 GoTo는 그 질문의 다시 시도다
        val rig = CoreRig(this)
        rig.fakeNavi.mode = { NaviMode.ROUTE_GUIDING }
        rig.fakeNavi.faults["addWaypoint"] = FakeNaviPort.Fault.THROW
        rig.untilLastChanceCard()
        rig.command(UiCommand.Answer(rig.state.prompt!!.id, true))
        rig.runUntil(30_000) { rig.state.goToResult != null } ?: error("결과 없음")
        assertEquals("전제: 경유지 추가가 실패했다", GoToOutcome.FAILED, rig.state.goToResult!!.outcome)
        assertTrue("실패한 들르기는 엔진에 알리지 않는다", rig.accepted.notes.isEmpty())
        val stop = rig.state.goToResult!!.stop

        rig.fakeNavi.faults.remove("addWaypoint")
        rig.command(UiCommand.DismissGoToResult)
        rig.command(UiCommand.GoTo(stop))
        rig.runUntil(10_000) { rig.state.goToResult?.outcome == GoToOutcome.WAYPOINT_ADDED } ?: error("다시 시도 결과 없음")
        rig.run(2_000)
        assertEquals("다시 시도로 넣은 곳은 엔진이 아는 '들르기로 한 곳'이다", listOf("NEXT_ACCEPTED"), rig.state.next?.reasons?.map { it.code })
        assertEquals(listOf(stop.id), rig.accepted.notes.map { it.stopId })
    }

    // ------------------------------------------------------------ 엔진에 알리기 (서비스 닫힘 검증 D7)

    @Test
    fun aStopPickedOnThePlanScreenIsNotedToTheEngineAfterTheNaviConfirms() = runTest {
        // 엔진이 묻지 않은 곳을 화면에서 골랐다. 예전에는 엔진이 몰라서 그곳으로 가는 동안 다른 정차를 물었다
        val rig = drivingRig(this)
        rig.fakeNavi.latencyMs["startRoute"] = 5_000L
        rig.command(UiCommand.GoTo(Gyeongbu.oksan))
        rig.run(3_000)
        assertTrue("내비가 확인하기 전에는 알리지 않는다", rig.accepted.notes.isEmpty())
        rig.runUntil(10_000) { rig.state.goToResult != null } ?: error("결과 없음")
        val note = rig.accepted.notes.single()
        assertEquals("oksan", note.stopId)
        assertEquals("목적을 모르면 화장실이 있는 곳은 화장실", setOf(Purpose.RESTROOM), note.purposes)
        assertEquals("엔진 시각(코치 시각)으로 알린다", rig.clock.now(), note.now)
        rig.run(10_000)
        assertEquals("한 번만", 1, rig.accepted.notes.size)
    }

    @Test
    fun aConfirmedStopIsWrittenToTheTripHeartbeatAndAFailedOneIsNot() = runTest {
        // 주행 중 재시작해도 이어받게 여정 맥박에 적는다 (서비스 닫힘 검증 N7)
        val rig = drivingRig(this)
        rig.fakeNavi.faults["startRoute"] = FakeNaviPort.Fault.THROW
        rig.command(UiCommand.GoTo(Gyeongbu.oksan))
        rig.runUntil(30_000) { rig.state.goToResult != null } ?: error("결과 없음")
        assertNull("실패한 들르기는 적지 않는다", rig.trips.stored?.accepted)
        rig.fakeNavi.faults.remove("startRoute")
        rig.command(UiCommand.GoTo(Gyeongbu.oksan))
        rig.runUntil(10_000) { rig.state.goToResult?.outcome == GoToOutcome.ROUTE_STARTED } ?: error("결과 없음")
        assertEquals("oksan", rig.trips.stored?.accepted?.stopId)
        rig.command(UiCommand.GoTo(Gyeongbu.jukam))
        rig.runUntil(10_000) { rig.state.goToResult?.stop?.id == "jukam" } ?: error("결과 없음")
        assertEquals("새로 고른 곳이 대신한다", "jukam", rig.trips.stored?.accepted?.stopId)
    }

    @Test
    fun aFailedGoToIsNotNoted() = runTest {
        val rig = drivingRig(this)
        rig.fakeNavi.faults["startRoute"] = FakeNaviPort.Fault.THROW
        rig.command(UiCommand.GoTo(Gyeongbu.oksan))
        rig.runUntil(30_000) { rig.state.goToResult != null } ?: error("결과 없음")
        assertEquals(GoToOutcome.FAILED, rig.state.goToResult!!.outcome)
        assertTrue(rig.accepted.notes.isEmpty())
    }

    @Test
    fun anAcceptedEngineQuestionIsReportedAndNotedOnce() = runTest {
        // 엔진 질문의 수락은 질문 id로 알리고(학습·쉼), 고른 곳도 같은 길로 알린다 (엔진은 같은 곳이면 이미 아는 것으로 본다)
        val rig = CoreRig(this)
        rig.untilLastChanceCard()
        val asked = rig.state.prompt!!
        rig.command(UiCommand.Answer(asked.id, true))
        rig.runUntil(10_000) { rig.state.goToResult != null } ?: error("결과 없음")
        rig.run(2_000)
        assertEquals(listOf("NEXT_ACCEPTED"), rig.state.next?.reasons?.map { it.code })
        val note = rig.accepted.notes.single()
        assertEquals(asked.stop!!.id, note.stopId)
        assertTrue("엔진이 추천하던 목적을 넘긴다: ${note.purposes}", Purpose.RESTROOM in note.purposes)
    }

    @Test
    fun aGoToSentBeforeAWorldSwitchIsNotNotedToTheNewWorldsEngine() = runTest {
        val rig = drivingRig(this)
        rig.fakeNavi.latencyMs["startRoute"] = 20_000L
        rig.command(UiCommand.GoTo(Gyeongbu.oksan))
        rig.run(5_000)
        rig.command(UiCommand.Demo("holiday"))
        rig.runUntil(30_000) { rig.state.goToResult != null } ?: error("결과 없음")
        assertNotNull("이미 보낸 명령의 결과는 사실이라 알린다", rig.state.goToResult)
        assertNull(rig.state.goToPending)
        assertTrue("시연 엔진은 실제 세계에서 보낸 들르기를 모른다", rig.accepted.notes.isEmpty())
    }
}
