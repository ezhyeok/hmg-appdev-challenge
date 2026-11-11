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
