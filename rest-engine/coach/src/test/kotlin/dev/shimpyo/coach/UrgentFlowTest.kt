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
 * 닫힘 검증(2026-11-12)이 찾은 틈(리뷰 R-F1·R-F7a~d, UI 9b·9c·N4)을 여기서 규칙으로 지킨다.
 */
class UrgentFlowTest {
    private val wc = PromptFakes.stop("wc", "강남역 공중화장실", kind = StopKind.PUBLIC_RESTROOM)
    private val gas = PromptFakes.stop("gas", "역삼 주유소", kind = StopKind.GAS_STATION)
    private val oksan = PromptFakes.stop("oksan", "옥산휴게소")
    /** wc에서 북쪽으로 [m] 미터 떨어진 차 위치 */
    private fun at(m: Double) = Fix(wc.lat + m / 111_195.0, wc.lon, null)

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

    // ------------------------------------------------------------ 엔진에 알리기

    @Test
    fun aDismissedRequestIsNeverToldToTheEngine() {
        // 리뷰 R-F7b: 결과가 나온 틱과 엔진에 알리는 다음 틱 사이에 닫으면, 닫은 뒤에 엔진이 급해 답을 말했다
        val f = ready()
        f.dismiss(1L)
        assertFalse(f.takeEngineIntent())
    }

    @Test
    fun sayingItAgainSearchesAgainAndTellsTheEngineAgain() {
        // 리뷰 R-F7c: 요청이 열려 있는 동안 "화장실 급해"를 또 말하면 목록만 다시 찾고 아무 대답이 없었다
        val f = ready()
        assertTrue(f.takeEngineIntent())
        f.sayAgain()
        assertFalse("다시 찾은 결과로 알린다 (그 전에는 아직)", f.takeEngineIntent())
        assertEquals("다시 말하면 바로 다시 찾는다", UrgentFlow.Step.SEARCH, f.step(1_000, true, true))
        assertTrue(f.found(1_000, listOf(wc), ranked(wc)))
        assertTrue("엔진이 1등 장소를 다시 묻게 한다", f.takeEngineIntent())
        assertFalse(f.takeEngineIntent())
    }

    @Test
    fun anEngineResetSendsTheUrgentIntentAgainWhileTheRequestLives() {
        // 리뷰 R-F7d: 새 여정·기록 지우기로 엔진의 주행 상태가 새로 시작되면 쌓아 둔 급해 의도가 버려지는데 요청은 알렸다고 믿었다
        val told = ready()
        assertTrue(told.takeEngineIntent())
        told.onEngineReset()
        assertTrue("새 엔진은 급해를 모른다: 다시 알린다", told.takeEngineIntent())

        val queued = ready()
        queued.onEngineReset()
        assertTrue("아직 넣지 못한 의도도 남는다", queued.takeEngineIntent())
        assertFalse(queued.takeEngineIntent())

        val searching = UrgentFlow()
        searching.open(1L, 0L)
        searching.onEngineReset()
        assertFalse("결과 전이면 알릴 것이 없다 (결과가 나오면 알린다)", searching.takeEngineIntent())
        val closed = ready()
        closed.dismiss(1L)
        closed.onEngineReset()
        assertFalse("닫은 요청은 다시 알리지 않는다", closed.takeEngineIntent())
    }

    @Test
    fun theEngineIsNotAskedWhileAPickedStopIsOnItsWayToTheNavi() {
        // UI 닫힘 검증 9b: 결과가 뜨고 엔진 질문이 나오기 전(1초 안)에 1순위를 누르면, 안내를 시작한 그곳을 "바로 안내할까요?"로 또 물었다
        val f = ready()
        assertTrue("목록에서 골랐다", f.onGoToStarted(wc))
        assertFalse("내비 확인을 기다리는 동안 엔진에 급해 질문을 시키지 않는다", f.takeEngineIntent())
        assertTrue("성공: 요청이 끝난다", f.onGoToFinished(wc, ok = true, nowMs = 20_000))
        assertFalse("끝난 요청은 알리지 않는다", f.takeEngineIntent())

        val failed = ready()
        failed.onGoToStarted(wc)
        assertFalse("실패: 요청은 그대로다", failed.onGoToFinished(wc, ok = false, nowMs = 20_000))
        assertTrue(failed.active)
        assertTrue("미뤄 둔 급해를 이제 알린다 (다른 곳을 물어 줄 수 있다)", failed.takeEngineIntent())
    }

    // ------------------------------------------------------------ 요청이 끝나는 때

    @Test
    fun onlyAStopPickedFromThisRequestsListEndsIt() {
        // 리뷰 R-F7a: 먼저 보낸 들르기(계획 화면, 마지막 기회 카드)가 확인되는 순간 고르지도 않은 급해 화면이 닫혔다
        val f = ready()
        assertFalse("목록에 없는 곳", f.onGoToStarted(oksan))
        assertFalse(f.onGoToFinished(oksan, ok = true, nowMs = 5_000))
        assertTrue(f.active)
        assertNull(f.pinned)

        // 요청을 열기 전에 보낸 들르기는 목록에 있는 곳이라도 이 요청의 선택이 아니다
        val later = UrgentFlow()
        assertFalse(later.onGoToStarted(wc))
        later.open(1L, 1_000L)
        later.found(1_000L, listOf(wc, gas), ranked(wc, gas))
        assertFalse(later.onGoToFinished(wc, ok = true, nowMs = 5_000))
        assertTrue(later.active)
    }

    @Test
    fun aPickFromAListThatWasJustRefreshedStillCounts() {
        // 화면은 한 박자 늦은 목록을 보일 수 있다: 10초 갱신에서 빠진 곳을 눌러도 이 요청이 올렸던 곳이면 선택이다
        val f = ready(1L, wc, gas)
        f.found(10_000, listOf(gas), ranked(gas))
        assertTrue(f.onGoToStarted(wc))
        assertTrue(f.onGoToFinished(wc, ok = true, nowMs = 12_000))
        assertEquals(wc, f.pinned)
    }

    // ------------------------------------------------------------ 엔진 후보와 고정

    @Test
    fun theNearbyStopsJoinTheEngineCandidatesWhileTheRequestLives() {
        val f = ready()
        assertEquals(listOf(wc, gas), f.extras())
        f.dismiss(1L)
        assertTrue("닫으면 더하지 않는다", f.extras().isEmpty())
    }

    @Test
    fun aPickedStopStaysInTheCandidatesFromThePickEvenIfTheScreenIsClosed() {
        // UI 닫힘 검증 9c: 내비 확인(에뮬레이터 19.5초)을 기다리는 동안 화면을 닫으면 고정이 걸리지 않아 엔진이 고른 곳을 잊었다
        val f = ready()
        f.onGoToStarted(wc)
        assertTrue(f.dismiss(1L))
        assertEquals("넣는 중인 곳은 후보에 남는다", listOf(wc), f.extras())
        assertFalse("요청은 이미 닫혔다", f.onGoToFinished(wc, ok = true, nowMs = 20_000))
        assertEquals(wc, f.pinned)
        assertEquals(listOf(wc), f.extras())
    }

    @Test
    fun aFailedPickLeavesTheCandidatesAndKeepsTheStopChosenBefore() {
        val f = ready()
        f.onGoToStarted(wc)
        f.onGoToFinished(wc, ok = true, nowMs = 1_000)
        f.open(2L, 2_000L)
        f.found(2_000L, listOf(gas), ranked(gas))
        assertEquals("새 요청을 열어도 들르기로 한 곳은 그대로다", wc, f.pinned)
        f.onGoToStarted(gas)
        assertEquals(listOf(gas, wc, gas), f.extras())
        f.onGoToFinished(gas, ok = false, nowMs = 3_000)
        assertEquals("새로 고른 곳이 실패하면 앞서 들르기로 한 곳이 남는다", wc, f.pinned)
        assertEquals(listOf(gas, wc), f.extras())
        f.onGoToStarted(gas)
        f.onGoToFinished(gas, ok = true, nowMs = 4_000)
        assertEquals("성공하면 새로 고른 곳으로 바뀐다", gas, f.pinned)
    }

    @Test
    fun thePinEndsWhenTheEngineStopsRecommendingTheStop() {
        // 도착해 서면 엔진이 그 정차를 기록하고 추천을 내린다. 15초 유예 뒤 후보에서 뺀다
        val f = ready()
        f.onGoToStarted(wc)
        f.onGoToFinished(wc, ok = true, nowMs = 20_000)
        f.track(null, "wc", 60_000)
        f.track(null, null, 60_000 + UrgentFlow.PIN_GRACE_MS)
        assertEquals(wc, f.pinned)
        f.track(null, "other", 60_001 + UrgentFlow.PIN_GRACE_MS)
        assertNull(f.pinned)
        assertTrue(f.extras().isEmpty())
    }

    @Test
    fun thePinEndsOnceTheCarIsPastTheStopEvenIfTheEngineStillRecommendsIt() {
        // 리뷰 R-F1: 고정이 후보를 대 주므로 엔진은 지나친 곳을 90분 동안 추천했고, 그동안 다른 질문도 내지 않았다
        val f = ready()
        f.onGoToStarted(wc)
        f.onGoToFinished(wc, ok = true, nowMs = 0L)
        var t = 0L
        fun drive(m: Double) {
            t += 1_000
            f.track(at(m), "wc", t)
        }
        drive(3_000.0); drive(2_000.0); drive(900.0)
        drive(900.0 + UrgentFlow.PASSED_M - 5.0)
        assertEquals("가장 가까웠던 때보다 300m까지는 굽은 길로 본다", wc, f.pinned)
        drive(900.0 + UrgentFlow.PASSED_M + 5.0)
        assertNull("그보다 멀어지면 지나친 것이다", f.pinned)
    }

    @Test
    fun thePinEndsWhenTheCarReachedTheStopAndLeftIt() {
        val f = ready()
        f.onGoToStarted(wc)
        f.onGoToFinished(wc, ok = true, nowMs = 0L)
        f.track(at(400.0), "wc", 1_000)
        f.track(at(120.0), "wc", 2_000)
        f.track(at(UrgentFlow.ARRIVED_M - 5.0), "wc", 3_000)
        assertEquals("150m 안에 있는 동안은 도착해 있는 것이다", wc, f.pinned)
        f.track(at(UrgentFlow.ARRIVED_M + 10.0), "wc", 4_000)
        assertNull("들어갔다 나오면 떠난 것이다 (300m를 기다리지 않는다)", f.pinned)
    }

    @Test
    fun aStopOnItsWayToTheNaviIsNotDroppedForBeingPassedButItsClosestApproachCounts() {
        val f = ready()
        f.onGoToStarted(wc)
        f.track(at(100.0), null, 1_000)
        f.track(at(2_000.0), null, 2_000)
        assertEquals("넣는 중에는 빼지 않는다 (결과가 정한다)", listOf(wc, gas, wc), f.extras())
        f.onGoToFinished(wc, ok = true, nowMs = 3_000)
        f.track(at(2_100.0), "wc", 4_000)
        assertNull("넣는 동안 이미 지나쳤으면 다음 틱에 뺀다", f.pinned)
    }

    @Test
    fun withoutAPositionOnlyTheEngineRuleApplies() {
        val f = ready()
        f.onGoToStarted(wc)
        f.onGoToFinished(wc, ok = true, nowMs = 0L)
        for (i in 1..600) f.track(null, "wc", i * 1_000L)
        assertEquals(wc, f.pinned)
    }

    @Test
    fun aWorldSwitchEndsTheRequestAndThePins() {
        val f = ready()
        f.onGoToStarted(wc)
        f.onGoToFinished(wc, ok = true, nowMs = 0L)
        f.open(2L, 1_000L)
        f.found(1_000L, listOf(gas), ranked(gas))
        f.onGoToStarted(gas)
        f.reset()
        assertFalse(f.active)
        assertNull(f.pinned)
        assertTrue(f.extras().isEmpty())
        assertFalse("지난 세계의 급해를 새 엔진에 알리지 않는다", f.takeEngineIntent())
        assertFalse("지난 세계에서 보낸 들르기가 새 세계의 요청을 닫지 않는다", f.onGoToFinished(gas, ok = true, nowMs = 5_000))
        assertNull(f.pinned)
    }

    // ------------------------------------------------------------ 음성으로 연 요청의 번호 (UI 닫힘 검증 N4)

    @Test
    fun voiceOpenedIdsAreNegativeAndNeverRepeatedByALaterCore() {
        val first = UrgentFlow(seedMs = 5_000_000L)
        val a = first.openByVoice(0L)
        val b = first.openByVoice(1_000L)
        assertTrue("화면 번호(양수)와 겹치지 않는다", a < 0 && b < 0)
        assertEquals("요청마다 다른 번호", 2, setOf(a, b).size)
        // 서비스가 다시 떠서 만든 코어: 만든 때가 다르므로 번호가 겹치지 않는다 (화면은 닫은 번호를 기억한다)
        val second = UrgentFlow(seedMs = 5_004_000L)
        assertTrue(second.openByVoice(0L) !in setOf(a, b))
        assertTrue("시작점이 0이어도 음수", UrgentFlow().openByVoice(0L) < 0)
    }
}
