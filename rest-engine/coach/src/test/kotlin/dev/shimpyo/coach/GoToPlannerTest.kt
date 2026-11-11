package dev.shimpyo.coach

import dev.shimpyo.coach.GoToPlanner.Action
import dev.shimpyo.coach.GoToPlanner.Failure
import dev.shimpyo.coach.fakes.PromptFakes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 들르기(수락) 실행. 지키는 것 (서비스 감사 D9, 구조 리뷰 D12/B8, UI 감사 #3):
 * - 경유지 추가와 새 길안내는 명령 직전에 다시 읽은 내비 모드로 고른다 (15초 묵은 값으로 사용자의 목적지를 덮어쓰지 않게)
 * - 모의 주행(SIMULATION_GUIDING)도 경로가 있는 길안내다
 * - 좌표 없는 경로 위 시설(tbt:)은 주행 중 내비 명령 없이 '경로 위'로 안내하고, 장소 검색은 정차 중에만 연다
 * - 보내지 못한 명령만 한 번 다시 시도한다. 보냈는데 확인이 안 된 명령은 다시 보내지 않는다 (경유지 두 번 추가 방지)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoToPlannerTest {
    private val oksan = PromptFakes.stop("kex:oksan", "옥산휴게소", routeDistanceM = 3_400.0)
    private val tbtStop = PromptFakes.stop("tbt:죽암휴게소", "죽암휴게소", lat = 0.0, lon = 0.0, routeDistanceM = 2_600.0)

    // ---------------------------------------------------------------- 순수 판단

    @Test
    fun chooseFromTheFreshMode() {
        assertEquals(Action.ADD_WAYPOINT, GoToPlanner.action(oksan, NaviMode.ROUTE_GUIDING, parked = false))
        assertEquals("내비 모의 주행도 경로가 있다 (B8)", Action.ADD_WAYPOINT, GoToPlanner.action(oksan, NaviMode.SIMULATION_GUIDING, parked = false))
        assertEquals(Action.START_ROUTE, GoToPlanner.action(oksan, NaviMode.SAFE_GUIDING, parked = false))
        assertEquals("내비 초기화 중: 시도해 보고 결과로 판단", Action.START_ROUTE, GoToPlanner.action(oksan, NaviMode.NOT_WORKING, parked = true))
        assertNull("모드를 모르면 새 길안내로 목적지를 덮어쓸 수 있어 보내지 않는다", GoToPlanner.action(oksan, null, parked = false))
        assertNull(GoToPlanner.action(oksan, NaviMode.UNKNOWN, parked = false))
    }

    @Test
    fun coordinatelessStopsNeedNoNaviCommandWhileDriving() {
        assertEquals(Action.ON_ROUTE, GoToPlanner.action(tbtStop, NaviMode.ROUTE_GUIDING, parked = false))
        assertEquals("TBT에서 온 후보라 경로가 있었다. 명령을 보내지 않으니 해가 없다", Action.ON_ROUTE, GoToPlanner.action(tbtStop, null, parked = false))
        assertNull("길안내가 끝났으면 '경로 위'라고 말할 수 없다", GoToPlanner.action(tbtStop, NaviMode.SAFE_GUIDING, parked = false))
        assertEquals("정차 중에만 장소 검색 화면을 연다", Action.OPEN_SEARCH, GoToPlanner.action(tbtStop, NaviMode.ROUTE_GUIDING, parked = true))
        assertFalse(GoToPlanner.needsMode(tbtStop, parked = true))
        assertTrue(GoToPlanner.needsMode(tbtStop, parked = false))
        assertTrue(GoToPlanner.needsMode(oksan, parked = true))
    }

    @Test
    fun retryOnlyWhatWasNotSentAndOnlyOnce() {
        assertTrue(GoToPlanner.retry(Failure.MODE_UNKNOWN, attempt = 1))
        assertTrue(GoToPlanner.retry(Failure.NOT_SENT, attempt = 1))
        assertFalse("한 번만", GoToPlanner.retry(Failure.NOT_SENT, attempt = 2))
        assertFalse("보냈는데 확인이 안 됐다: 늦게 들어갔을 수 있어 다시 보내지 않는다", GoToPlanner.retry(Failure.UNCONFIRMED, attempt = 1))
        assertFalse("경로가 없다는 것은 확인된 사실이다", GoToPlanner.retry(Failure.NO_ROUTE, attempt = 1))
    }

    @Test
    fun outcomesAndSpeech() {
        assertEquals(GoToOutcome.WAYPOINT_ADDED, GoToPlanner.outcome(Action.ADD_WAYPOINT))
        assertEquals(GoToOutcome.ROUTE_STARTED, GoToPlanner.outcome(Action.START_ROUTE))
        assertEquals(GoToOutcome.ON_ROUTE, GoToPlanner.outcome(Action.ON_ROUTE))
        assertEquals(GoToOutcome.SEARCH_OPENED, GoToPlanner.outcome(Action.OPEN_SEARCH))
        assertEquals("옥산휴게소를 경유지로 넣었어요", GoToPlanner.speech(GoToOutcome.WAYPOINT_ADDED, oksan))
        assertEquals("옥산휴게소로 안내할게요", GoToPlanner.speech(GoToOutcome.ROUTE_STARTED, oksan))
        assertEquals("죽암휴게소는 경로 위에 있어요, 2.6km 앞 진입로로 들어가세요", GoToPlanner.speech(GoToOutcome.ON_ROUTE, tbtStop))
        assertEquals("1km 이하는 100m 단위", "죽암휴게소는 경로 위에 있어요, 800m 앞 진입로로 들어가세요",
            GoToPlanner.speech(GoToOutcome.ON_ROUTE, tbtStop.copy(routeDistanceM = 760.0)))
        assertEquals("10km 이상은 정수", "죽암휴게소는 경로 위에 있어요, 12km 앞 진입로로 들어가세요",
            GoToPlanner.speech(GoToOutcome.ON_ROUTE, tbtStop.copy(routeDistanceM = 12_300.0)))
        assertEquals("거리를 모르면 말하지 않는다", "죽암휴게소는 경로 위에 있어요, 진입로로 들어가세요",
            GoToPlanner.speech(GoToOutcome.ON_ROUTE, tbtStop.copy(routeDistanceM = null)))
        assertEquals("내비에서 죽암휴게소를 찾아 두었어요. 화면에서 골라 주세요", GoToPlanner.speech(GoToOutcome.SEARCH_OPENED, tbtStop))
        assertEquals("내비에 넣지 못했어요. 화면에서 다시 눌러 주세요", GoToPlanner.speech(GoToOutcome.FAILED, oksan))
    }

    // ---------------------------------------------------------------- 실행 (가짜 내비, 가상 시간)

    @Test
    fun theSnapshotIsNeverConsultedOnlyTheFreshRead() = runTest {
        // 코어의 내비 스냅숏은 15초 전 '경로 없음'이었지만, 그 사이 사용자가 길안내를 시작했다
        val navi = PromptFakes.Navi(modes = listOf(NaviMode.SIMULATION_GUIDING))
        assertEquals(GoToOutcome.WAYPOINT_ADDED, GoToRunner(navi).run(oksan, parked = false))
        assertEquals(listOf("routeStatus", "addWaypoint:옥산휴게소"), navi.calls)
    }

    @Test
    fun startsARouteWhenNotGuiding() = runTest {
        val navi = PromptFakes.Navi(modes = listOf(NaviMode.SAFE_GUIDING))
        assertEquals(GoToOutcome.ROUTE_STARTED, GoToRunner(navi).run(oksan, parked = true))
        assertEquals(listOf("routeStatus", "startRoute:옥산휴게소"), navi.calls)
    }

    @Test
    fun aCommandThatWasNotSentIsRetriedOnceAfterTheCommandGap() = runTest {
        val navi = PromptFakes.Navi(modes = listOf(NaviMode.SAFE_GUIDING, NaviMode.ROUTE_GUIDING), commands = listOf(IllegalStateException("binder"), true))
        val outcome = GoToRunner(navi).run(oksan, parked = false)
        assertEquals("두 번째 시도는 다시 읽은 모드로 고른다", GoToOutcome.WAYPOINT_ADDED, outcome)
        assertEquals(listOf("routeStatus", "startRoute:옥산휴게소", "routeStatus", "addWaypoint:옥산휴게소"), navi.calls)
        assertEquals(GoToPlanner.RETRY_DELAY_MS, currentTime)
    }

    @Test
    fun twoFailedSendsAreFailed() = runTest {
        val navi = PromptFakes.Navi(commands = listOf(IllegalStateException("binder")))
        assertEquals(GoToOutcome.FAILED, GoToRunner(navi).run(oksan, parked = false))
        assertEquals(2, navi.calls.count { it.startsWith("startRoute") })
    }

    @Test
    fun anUnconfirmedWaypointIsNotSentAgain() = runTest {
        val navi = PromptFakes.Navi(modes = listOf(NaviMode.ROUTE_GUIDING), commands = listOf(false, true))
        assertEquals(GoToOutcome.FAILED, GoToRunner(navi).run(oksan, parked = false))
        assertEquals("경유지가 두 번 들어가지 않게", 1, navi.calls.count { it.startsWith("addWaypoint") })
    }

    @Test
    fun unknownModeSendsNothing() = runTest {
        val navi = PromptFakes.Navi(modes = listOf(IllegalStateException("query timeout"), NaviMode.UNKNOWN))
        assertEquals(GoToOutcome.FAILED, GoToRunner(navi).run(oksan, parked = false))
        assertEquals(listOf("routeStatus", "routeStatus"), navi.calls)
    }

    @Test
    fun modeReadFailureIsRetried() = runTest {
        val navi = PromptFakes.Navi(modes = listOf(IllegalStateException("query timeout"), NaviMode.ROUTE_GUIDING))
        assertEquals(GoToOutcome.WAYPOINT_ADDED, GoToRunner(navi).run(oksan, parked = false))
    }

    @Test
    fun onRouteSendsNoCommand() = runTest {
        val navi = PromptFakes.Navi(modes = listOf(NaviMode.ROUTE_GUIDING))
        assertEquals(GoToOutcome.ON_ROUTE, GoToRunner(navi).run(tbtStop, parked = false))
        assertEquals(listOf("routeStatus"), navi.calls)
    }

    @Test
    fun onRouteFailsWhenTheRouteIsGone() = runTest {
        val navi = PromptFakes.Navi(modes = listOf(NaviMode.SAFE_GUIDING))
        assertEquals(GoToOutcome.FAILED, GoToRunner(navi).run(tbtStop, parked = false))
        assertEquals("다시 읽어도 같다: 재시도하지 않는다", listOf("routeStatus"), navi.calls)
    }

    @Test
    fun searchIsOpenedOnlyWhenItReallyOpened() = runTest {
        val opened = PromptFakes.Navi(commands = listOf(true))
        assertEquals(GoToOutcome.SEARCH_OPENED, GoToRunner(opened).run(tbtStop, parked = true))
        assertEquals("정차 중 검색은 모드를 읽지 않는다", listOf("search:죽암휴게소"), opened.calls)

        val secondTime = PromptFakes.Navi(commands = listOf(false, true))
        assertEquals(GoToOutcome.SEARCH_OPENED, GoToRunner(secondTime).run(tbtStop, parked = true))

        val never = PromptFakes.Navi(commands = listOf(false))
        assertEquals("예전에는 검색을 못 열어도 찾아 두었다고 말했다", GoToOutcome.FAILED, GoToRunner(never).run(tbtStop, parked = true))
    }

    @Test
    fun aHangingNaviEndsAsFailedWithinTheBound() = runTest {
        val navi = PromptFakes.Navi(modes = listOf(PromptFakes.Navi.HANG))
        val r = async { GoToRunner(navi).run(oksan, parked = false) }
        advanceUntilIdle()
        assertEquals(GoToOutcome.FAILED, r.await())
        assertEquals(GoToPlanner.MAX_TOTAL_MS, currentTime)
    }

    @Test
    fun cancellationIsNotTurnedIntoAFailure() = runTest {
        // 코어가 세계를 바꾸며 실행을 취소하면 FAILED를 내지 않는다 (취소는 결과가 아니다)
        val navi = PromptFakes.Navi(modes = listOf(PromptFakes.Navi.HANG))
        var outcome: GoToOutcome? = null
        val job = launch { outcome = GoToRunner(navi).run(oksan, parked = false) }
        testScheduler.advanceTimeBy(1_000)
        job.cancel()
        advanceUntilIdle()
        assertNull(outcome)
    }

    @Test
    fun aPortsOwnTimeoutIsAFailureNotACancellation() = runTest {
        // 포트 안의 withTimeout이 낸 TimeoutCancellationException을 우리 취소로 보고 다시 던지면 결과가 영영 안 나온다
        val navi = PromptFakes.Navi(modes = listOf(NaviMode.SAFE_GUIDING), commands = listOf(PromptFakes.Navi.PORT_TIMEOUT, true))
        assertEquals(GoToOutcome.ROUTE_STARTED, GoToRunner(navi).run(oksan, parked = false))
        assertEquals(2, navi.calls.count { it.startsWith("startRoute") })
    }

    @Test
    fun otherExceptionsFromTheNaviAreFailuresNotCrashes() = runTest {
        val navi = PromptFakes.Navi(modes = listOf(NaviMode.ROUTE_GUIDING), commands = listOf(RuntimeException("x"), RuntimeException("y")))
        assertEquals(GoToOutcome.FAILED, GoToRunner(navi).run(oksan, parked = false))
    }
}
