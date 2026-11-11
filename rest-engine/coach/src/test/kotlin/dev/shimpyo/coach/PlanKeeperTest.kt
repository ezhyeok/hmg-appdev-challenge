package dev.shimpyo.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 계획을 언제 다시 세우고 번호를 언제 올리는지 (PlanKeeper, UI 감사 #4) */
class PlanKeeperTest {

    @Test
    fun nothingIsBuiltUntilAPlanIsRequested() {
        val p = PlanKeeper()
        assertFalse(p.due(0L, "k1"))
        assertFalse(p.due(10 * PlanKeeper.REFRESH_MS, "k2"))
        assertEquals(0L, p.generation)
    }

    @Test
    fun aRequestIsBuiltAtOnceAndRaisesTheGenerationOnce() {
        val p = PlanKeeper()
        p.request()
        assertTrue(p.due(1_000L, "k1", force = true))
        assertEquals(1L, p.built())
        assertFalse("같은 재료, 30초 안: 다시 세우지 않는다", p.due(2_000L, "k1"))
        p.request()
        assertTrue("같은 재료라도 새 요청에는 답한다", p.due(3_000L, "k1", force = true))
        assertEquals(2L, p.built())
    }

    @Test
    fun aShownPlanIsRebuiltWhenItsInputsChangeOrEveryThirtySecondsWithoutANewGeneration() {
        val p = PlanKeeper()
        p.request()
        p.due(0L, "k1", force = true)
        p.built()
        assertTrue("후보·경로가 바뀌었다", p.due(5_000L, "k2"))
        assertEquals("고쳐 세운 계획은 번호를 올리지 않는다", 1L, p.built())
        assertFalse(p.due(5_000L + PlanKeeper.REFRESH_MS - 1, "k2"))
        assertTrue("30초마다", p.due(5_000L + PlanKeeper.REFRESH_MS, "k2"))
        assertEquals(1L, p.built())
    }

    @Test
    fun aRequestBeforeTheFirstInputIsAnsweredOnTheNextCheck() {
        // 코어는 엔진 입력이 아직 없으면 due를 부르지 않는다. 다음 틱의 보통 점검(force 아님)에서 답하고 번호를 올린다
        val p = PlanKeeper()
        p.request()
        assertTrue(p.due(1_000L, "k1"))
        assertEquals(1L, p.built())
    }

    @Test
    fun aFailedBuildIsNotRetriedUntilTheNextPeriodAndTheRequestStaysOpen() {
        val p = PlanKeeper()
        p.request()
        assertTrue(p.due(0L, "k1", force = true)) // 코어: engine.plan이 실패해 built를 부르지 않았다
        assertFalse(p.due(1_000L, "k1"))
        assertTrue(p.due(PlanKeeper.REFRESH_MS, "k1"))
        assertEquals("늦게라도 요청에 답한 계획이 번호를 올린다", 1L, p.built())
    }

    @Test
    fun aWorldSwitchDropsTheOldPlanButKeepsAnUnansweredRequest() {
        val answered = PlanKeeper()
        answered.request()
        answered.due(0L, "k1", force = true)
        answered.built()
        answered.worldChanged()
        assertFalse("지난 세계의 계획은 새 세계에서 고쳐 세우지 않는다", answered.due(1_000L, "k9"))

        val waiting = PlanKeeper()
        waiting.request()
        waiting.worldChanged()
        assertTrue("아직 답하지 않은 요청은 새 세계에서 답한다", waiting.due(1_000L, "k9"))
        assertEquals(1L, waiting.built())
    }

    @Test
    fun aClosedPlanScreenStopsTheRebuildsUntilTheNextRequest() {
        // 리뷰 R-F12: 계획 화면을 떠났다는 신호가 없어 MakePlan 한 번에 10분 동안 계획을 30번 다시 세웠다
        val p = PlanKeeper()
        p.request()
        p.due(0L, "k1", force = true)
        p.built()
        p.stop()
        assertFalse("재료가 바뀌어도", p.due(5_000L, "k2"))
        assertFalse("30초가 지나도", p.due(10 * PlanKeeper.REFRESH_MS, "k2"))
        p.request()
        assertTrue(p.due(11 * PlanKeeper.REFRESH_MS, "k2", force = true))
        assertEquals(2L, p.built())

        val unanswered = PlanKeeper()
        unanswered.request()
        unanswered.stop()
        assertFalse("답하기 전에 화면을 떠났으면 세우지 않는다", unanswered.due(1_000L, "k1"))
        assertEquals("번호도 오르지 않는다", 0L, unanswered.generation)
    }

    @Test
    fun generationsStartAtTheSeedSoALaterCoreNeverRepeatsOne() {
        // 서비스 닫힘 검증 N8: 죽은 코어가 화면 상태에 1을 남기면 새 코어의 첫 계획도 1이라 화면의 '계획을 세우는 중'이 풀리지 않았다
        val first = PlanKeeper(seedMs = 5_000_000L)
        first.request()
        first.due(5_001_000L, "k", force = true)
        val a = first.built()
        first.request()
        first.due(5_002_000L, "k", force = true)
        val b = first.built()
        assertEquals("요청마다 1씩 오른다", a + 1, b)
        val second = PlanKeeper(seedMs = 5_060_000L) // 한참 뒤에 다시 뜬 코어
        second.request()
        second.due(5_061_000L, "k", force = true)
        val c = second.built()
        assertTrue("앞 코어의 번호와 겹치지 않고 더 크다: $a, $b → $c", c > b)
        assertTrue("0은 번호를 쓰지 않는 코치의 값이다", a > 0)
    }

    @Test
    fun anEngineResetRebuildsOnTheNextCheck() {
        val p = PlanKeeper()
        p.request()
        p.due(0L, "k1", force = true)
        p.built()
        p.engineChanged()
        assertTrue("새 여정·기록 지우기 뒤에는 같은 재료라도 다시 세운다", p.due(1_000L, "k1"))
        assertEquals(1L, p.built())
    }
}
