package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.PromptFakes
import dev.shimpyo.engine.RankedStop
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 급해 흐름 (UrgentFlow): 요청의 진행, 엔진에 알릴 차례, 들르기로 한 곳의 고정. 코어 없이 순수하게 돌린다.
 * `currentBehavior_*`는 CoachCore에서 옮겨 온 그대로의 동작을 고정한 것이다: 닫힘 검증이 찾은 틈이라 다음 커밋들에서 뒤집는다.
 */
class UrgentFlowTest {
    private val wc = PromptFakes.stop("wc", "강남역 공중화장실", kind = StopKind.PUBLIC_RESTROOM)
    private val gas = PromptFakes.stop("gas", "역삼 주유소", kind = StopKind.GAS_STATION)
    private val oksan = PromptFakes.stop("oksan", "옥산휴게소")

    private fun ranked(vararg stops: StopCandidate) = stops.map { RankedStop(it, 60.0, emptyList()) }

    /** 결과까지 나온 요청 */
    private fun ready(id: Long = 1L, vararg stops: StopCandidate = arrayOf(wc, gas)): UrgentFlow {
        val f = UrgentFlow()
        f.open(id, 0L)
        assertEquals(UrgentFlow.Step.SEARCH, f.step(0L, hasPosition = true, dataLoaded = true))
        f.found(0L, stops.toList(), ranked(*stops))
        return f
    }

    // ------------------------------------------------------------ 요청의 진행 (UI 감사 #7·#8·#9)

    @Test
    fun waitsForALocationThenGivesUpWithNoLocationButStillSearchesIfOneArrives() {
        val f = UrgentFlow()
        f.open(1L, 0L)
        assertEquals(UrgentFlow.Step.NONE, f.step(1_000, hasPosition = false, dataLoaded = true))
        assertEquals(UrgentState.LOCATING, f.view!!.state)
        assertEquals(UrgentFlow.Step.NO_LOCATION, f.step(UrgentFlow.LOCATE_MS, hasPosition = false, dataLoaded = true))
        assertEquals(UrgentState.NO_LOCATION, f.view!!.state)
        assertEquals("한 번만 알린다", UrgentFlow.Step.NONE, f.step(UrgentFlow.LOCATE_MS + 1_000, hasPosition = false, dataLoaded = true))
        assertEquals("늦게라도 위치가 생기면 찾는다", UrgentFlow.Step.SEARCH, f.step(20_000, hasPosition = true, dataLoaded = true))
    }

    @Test
    fun waitsForTheStopTableAndGivesUpIfItNeverLoads() {
        val f = UrgentFlow()
        f.open(1L, 0L)
        assertEquals(UrgentFlow.Step.NONE, f.step(1_000, hasPosition = true, dataLoaded = false))
        assertEquals(UrgentState.SEARCHING, f.view!!.state)
        f.step(UrgentFlow.SEARCH_MS, hasPosition = true, dataLoaded = false)
        assertEquals(UrgentState.NO_RESULT, f.view!!.state)
    }

    @Test
    fun searchesAgainEveryTenSecondsAndTellsTheEngineOnlyOnce() {
        val f = UrgentFlow()
        f.open(1L, 0L)
        assertEquals(UrgentFlow.Step.SEARCH, f.step(0, true, true))
        assertTrue("처음이면 엔진에 알릴 차례", f.found(0, listOf(wc), ranked(wc)))
        assertEquals(UrgentState.READY, f.view!!.state)
        assertTrue(f.takeEngineIntent())
        assertFalse("한 번만", f.takeEngineIntent())
        assertEquals(UrgentFlow.Step.NONE, f.step(UrgentFlow.REFRESH_MS - 1, true, true))
        assertEquals(UrgentFlow.Step.SEARCH, f.step(UrgentFlow.REFRESH_MS, true, true))
        assertFalse(f.found(UrgentFlow.REFRESH_MS, emptyList(), emptyList()))
        assertEquals(UrgentState.NO_RESULT, f.view!!.state)
        assertFalse("다시 찾은 결과로는 엔진에 또 알리지 않는다", f.takeEngineIntent())
    }

    @Test
    fun theViewCarriesResultsOnlyOnceThereIsAnAnswer() {
        val f = UrgentFlow()
        assertNull("요청이 없으면 올릴 것도 없다", f.view)
        assertEquals(UrgentFlow.Step.NONE, f.step(0, true, true))
        f.open(7L, 0L)
        assertEquals(UrgentFlow.View(7L, UrgentState.LOCATING, null), f.view)
        f.found(0, listOf(wc), ranked(wc))
        assertEquals(UrgentFlow.View(7L, UrgentState.READY, ranked(wc)), f.view)
        f.found(10_000, emptyList(), emptyList())
        assertEquals("찾았는데 없으면 빈 목록 (모르는 것과 다르다)", UrgentFlow.View(7L, UrgentState.NO_RESULT, emptyList()), f.view)
    }

    @Test
    fun aNewRequestReplacesTheOldOneAndOnlyItsOwnIdDismissesIt() {
        val f = ready(1L)
        f.open(2L, 5_000L)
        assertEquals(UrgentFlow.View(2L, UrgentState.LOCATING, null), f.view)
        assertFalse("앞 번호의 닫기는 새 요청을 닫지 않는다", f.dismiss(1L))
        assertTrue(f.active)
        assertTrue(f.dismiss(2L))
        assertFalse(f.active)
        assertFalse("닫은 뒤의 결과는 버린다", f.found(6_000, listOf(wc), ranked(wc)))
        assertNull(f.view)
    }

    // ------------------------------------------------------------ 엔진 후보

    @Test
    fun theNearbyStopsJoinTheEngineCandidatesWhileTheRequestLives() {
        val f = ready()
        assertEquals(listOf(wc, gas), f.extras())
        f.dismiss(1L)
        assertTrue("닫으면 더하지 않는다", f.extras().isEmpty())
    }

    @Test
    fun aStopChosenFromTheListStaysInTheCandidatesAfterTheRequestEnds() {
        val f = ready()
        assertTrue("들르기로 했으니 요청은 끝난다", f.onGoToSucceeded(wc, 20_000))
        assertFalse(f.active)
        assertEquals(wc, f.pinned)
        assertEquals("고른 곳만 남는다 (경로 후보가 아니라서 빼면 엔진이 잊는다)", listOf(wc), f.extras())
        // 엔진이 그곳을 추천하는 동안은 남기고, 추천을 멈추고 15초가 지나면 뺀다
        f.onEngineNext("wc", 60_000)
        f.onEngineNext(null, 60_000 + UrgentFlow.PIN_GRACE_MS)
        assertEquals(wc, f.pinned)
        f.onEngineNext("other", 60_001 + UrgentFlow.PIN_GRACE_MS)
        assertNull(f.pinned)
        assertTrue(f.extras().isEmpty())
    }

    @Test
    fun aWorldSwitchEndsTheRequestAndThePin() {
        val f = ready()
        f.onGoToSucceeded(wc, 0L)
        f.open(2L, 1_000L)
        f.found(1_000L, listOf(gas), ranked(gas))
        f.reset()
        assertFalse(f.active)
        assertNull(f.pinned)
        assertTrue(f.extras().isEmpty())
        assertFalse("지난 세계의 급해를 새 엔진에 알리지 않는다", f.takeEngineIntent())
    }

    // ------------------------------------------------------------ 옮겨 온 그대로 (다음 커밋에서 뒤집는다)

    @Test
    fun currentBehavior_anySuccessfulGoToEndsTheRequest() {
        val f = ready()
        assertTrue("목록에 없는 곳의 들르기도 요청을 끝낸다 (리뷰 R-F7a)", f.onGoToSucceeded(oksan, 5_000))
        assertNull("목록에 없던 곳은 고정하지 않는다", f.pinned)
    }

    @Test
    fun currentBehavior_theQueuedEngineIntentSurvivesADismiss() {
        val f = ready()
        f.dismiss(1L)
        assertTrue("닫은 뒤에도 엔진에 급해를 알린다 (리뷰 R-F7b)", f.takeEngineIntent())
    }

    @Test
    fun currentBehavior_sayingItAgainOnlySearchesAgain() {
        val f = ready()
        f.takeEngineIntent()
        f.sayAgain()
        assertEquals("다시 말하면 바로 다시 찾는다", UrgentFlow.Step.SEARCH, f.step(1_000, true, true))
        assertFalse(f.found(1_000, listOf(wc), ranked(wc)))
        assertFalse("엔진에는 다시 알리지 않아 아무 대답이 없다 (리뷰 R-F7c)", f.takeEngineIntent())
    }

    @Test
    fun currentBehavior_anEngineResetDropsTheIntentForGood() {
        val f = ready()
        f.onEngineReset()
        assertFalse("엔진이 새로 시작하면 급해를 알리지 못한 채 끝난다 (리뷰 R-F7d)", f.takeEngineIntent())
        assertEquals(UrgentState.READY, f.view!!.state)
    }

    @Test
    fun currentBehavior_aPinTheEngineKeepsRecommendingIsNeverDropped() {
        val f = ready()
        f.onGoToSucceeded(wc, 0L)
        // 지나쳐서 22km 뒤에 있어도 엔진이 추천하는 한 남는다 (리뷰 R-F1: 고정이 후보를 대 주므로 엔진은 추천을 멈추지 않는다)
        for (t in 1..600) f.onEngineNext("wc", t * 1_000L)
        assertEquals(wc, f.pinned)
    }

    @Test
    fun currentBehavior_voiceOpenedIdsRestartFromMinusOne() {
        assertEquals(-1L, UrgentFlow().openByVoice(0L))
        val f = UrgentFlow()
        assertEquals("새 코어가 앞 코어의 번호를 또 쓴다 (UI 닫힘 검증 N4)", -1L, f.openByVoice(5_000L))
        assertEquals(-2L, f.openByVoice(6_000L))
    }
}
