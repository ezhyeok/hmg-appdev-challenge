package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.PromptFakes
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 들르기로 한 곳의 고정 (StopPin): 엔진 후보에 넣는 때와 푸는 때. 코어 없이 순수하게 돌린다.
 * 지키는 것: 리뷰 R-F1(지나친 곳에 묶임), UI 닫힘 검증 9c(넣는 중에 화면을 닫음), 엔진의 약속(들르기로 한 곳은 같은 id로 후보에
 * 계속 있어야 한다: 급해 목록이 아닌 곳도, 엔진이 다른 곳을 묻는 동안에도).
 */
class StopPinTest {
    private val wc = PromptFakes.stop("wc", "강남역 공중화장실", kind = StopKind.PUBLIC_RESTROOM)
    private val gas = PromptFakes.stop("gas", "역삼 주유소", kind = StopKind.GAS_STATION)
    private val oksan = PromptFakes.stop("oksan", "옥산휴게소")
    /** wc에서 북쪽으로 [m] 미터 떨어진 차 위치 */
    private fun at(m: Double) = Fix(wc.lat + m / 111_195.0, wc.lon, null)

    @Test
    fun aStopIsInTheCandidatesFromTheMomentTheGoToStarts() {
        // UI 닫힘 검증 9c: 내비 확인(에뮬레이터 19.5초)을 기다리는 동안 급해 화면을 닫으면 고정이 걸리지 않아 엔진이 고른 곳을 잊었다.
        // 고정은 급해 요청과 따로 산다: 들르기를 시작할 때부터 후보에 넣는다
        val f = StopPin()
        assertTrue(f.extras().isEmpty())
        f.onGoToStarted(wc)
        assertEquals("넣는 중인 곳은 후보에 남는다", listOf(wc), f.extras())
        assertNull("아직 들르기로 한 곳은 아니다", f.pinned)
        f.onGoToFinished(wc, ok = true, nowMs = 20_000)
        assertEquals(wc, f.pinned)
        assertEquals(listOf(wc), f.extras())
    }

    @Test
    fun aFailedPickLeavesTheCandidatesAndKeepsTheStopChosenBefore() {
        val f = StopPin()
        f.onGoToStarted(wc)
        f.onGoToFinished(wc, ok = true, nowMs = 1_000)
        f.onGoToStarted(gas)
        assertEquals("새로 고른 곳을 넣는 동안 둘 다 후보다", listOf(wc, gas), f.extras())
        f.onGoToFinished(gas, ok = false, nowMs = 3_000)
        assertEquals("새로 고른 곳이 실패하면 앞서 들르기로 한 곳이 남는다", wc, f.pinned)
        assertEquals(listOf(wc), f.extras())
        f.onGoToStarted(gas)
        f.onGoToFinished(gas, ok = true, nowMs = 4_000)
        assertEquals("성공하면 새로 고른 곳으로 바뀐다", gas, f.pinned)
        assertEquals(listOf(gas), f.extras())
        // 같은 곳을 다시 넣는 동안(다시 시도) 두 번 넣지 않는다
        f.onGoToStarted(gas)
        assertEquals(listOf(gas), f.extras())
    }

    @Test
    fun anyStopWithItsOwnCoordinatesIsPinnedButNotARoutePlaceholder() {
        // 급해 목록이 아니어도(계획 화면, 질문 수락) 고정한다: 길안내 없이 고른 곳은 새 길안내의 목적지가 되어 경로 후보(TBT)에서 빠진다.
        // 고를 때의 경로 거리·도착 시간은 그때 경로의 값이라 떼고 넣는다 (지나친 뒤에도 '3.9km 앞'으로 남지 않게)
        val f = StopPin()
        val asked = oksan.copy(routeDistanceM = 3_900.0, etaS = 140.0)
        f.onGoToStarted(asked)
        f.onGoToFinished(asked, ok = true, nowMs = 1_000)
        assertEquals(oksan.copy(routeDistanceM = null, etaS = null), f.pinned)

        // 좌표 없는 경로 후보(tbt:)의 좌표는 만든 때의 차 위치다: 거리를 잴 수 없으니 고정하지 않는다. 앞서 들르기로 한 곳은 그대로 두고
        // 엔진의 추천이 넘어간 뒤 유예로 푼다
        val onRoute = PromptFakes.stop("tbt:죽암휴게소", "죽암휴게소")
        f.onGoToStarted(onRoute)
        assertEquals(listOf(f.pinned), f.extras())
        f.onGoToFinished(onRoute, ok = true, nowMs = 2_000)
        assertEquals("oksan", f.pinned?.id)
        f.track(null, onRoute.id, null, 2_000 + StopPin.GRACE_MS + 1)
        assertNull(f.pinned)
    }

    @Test
    fun aStopCarriedOverARestartIsPinnedAtOnce() {
        val f = StopPin()
        f.adopt(oksan.copy(routeDistanceM = 9_000.0), nowMs = 5_000)
        assertEquals("oksan", f.pinned?.id)
        assertNull(f.pinned?.routeDistanceM)
        f.track(null, null, null, 5_000 + StopPin.GRACE_MS)
        assertEquals("이어받은 때부터 유예를 센다", "oksan", f.pinned?.id)
        f.adopt(PromptFakes.stop("tbt:x", "x"), nowMs = 6_000)
        assertEquals("자리 표시 후보는 이어받지 않는다", "oksan", f.pinned?.id)
    }

    @Test
    fun thePinEndsWhenTheEngineStopsRecommendingTheStop() {
        // 도착해 서면 엔진이 그 정차를 기록하고 추천을 내린다. 15초 유예 뒤 후보에서 뺀다
        val f = StopPin()
        f.onGoToStarted(wc)
        f.onGoToFinished(wc, ok = true, nowMs = 20_000)
        f.track(null, "wc", null, 60_000)
        f.track(null, null, null, 60_000 + StopPin.GRACE_MS)
        assertEquals(wc, f.pinned)
        f.track(null, "other", null, 60_001 + StopPin.GRACE_MS)
        assertNull(f.pinned)
        assertTrue(f.extras().isEmpty())
    }

    @Test
    fun thePinIsKeptWhileTheEngineAsksAboutAnotherStop() {
        // 엔진의 추천은 떠 있는 화장실 질문의 장소를 따라간다 (들르기로 한 곳이 있어도, 질문 수명 45초까지). 그 동안은 엔진이 들르기로 한
        // 곳을 내린 것이 아니다: 여기서 고정을 풀면 그곳이 후보에서 빠지고 엔진은 1분 뒤 그곳을 잊는다
        val f = StopPin()
        f.onGoToStarted(wc)
        f.onGoToFinished(wc, ok = true, nowMs = 20_000)
        f.track(null, "wc", null, 60_000)
        // "화장실 급해"를 또 말했다: 엔진이 더 빠른 곳(gas)을 45초 동안 묻는다
        for (s in 1..45) f.track(null, "gas", "gas", 60_000 + s * 1_000L)
        assertEquals("질문이 떠 있는 동안은 그대로다", wc, f.pinned)
        assertTrue(wc in f.extras())
        // 대답 없이 끝났다: 추천이 들르기로 한 곳으로 돌아온다
        f.track(null, "wc", null, 106_000)
        assertEquals(wc, f.pinned)

        // 질문에 '응'이라고 해서 들르기로 한 곳이 바뀌었으면(추천이 다른 곳, 질문은 없다) 유예 뒤에 푼다
        for (s in 1..10) f.track(null, "gas", "gas", 106_000 + s * 1_000L)
        f.track(null, "gas", null, 117_000)
        assertEquals("질문이 끝난 때부터 유예를 센다", wc, f.pinned)
        f.track(null, "gas", null, 117_000 + StopPin.GRACE_MS)
        assertNull(f.pinned)
    }

    @Test
    fun aQuestionAboutSomethingElseDoesNotHoldThePin() {
        // 추천이 질문의 장소를 따라간 것이 아니면(질문에 장소가 없다, 질문과 추천이 다른 곳이다) 엔진이 그곳을 내린 것이다
        val f = StopPin()
        f.onGoToStarted(wc)
        f.onGoToFinished(wc, ok = true, nowMs = 20_000)
        f.track(null, "wc", null, 60_000)
        f.track(null, "gas", "oksan", 60_000 + StopPin.GRACE_MS)
        assertEquals(wc, f.pinned)
        f.track(null, "gas", "oksan", 60_001 + StopPin.GRACE_MS)
        assertNull(f.pinned)
    }

    @Test
    fun thePinEndsOnceTheCarIsPastTheStopEvenIfTheEngineStillRecommendsIt() {
        // 리뷰 R-F1: 고정이 후보를 대 주므로 엔진은 지나친 곳을 90분 동안 추천했고, 그동안 다른 질문도 내지 않았다
        val f = StopPin()
        f.onGoToStarted(wc)
        f.onGoToFinished(wc, ok = true, nowMs = 0L)
        var t = 0L
        fun drive(m: Double) {
            t += 1_000
            f.track(at(m), "wc", null, t)
        }
        drive(3_000.0); drive(2_000.0); drive(900.0)
        drive(900.0 + StopPin.PASSED_M - 5.0)
        assertEquals("가장 가까웠던 때보다 300m까지는 굽은 길로 본다", wc, f.pinned)
        drive(900.0 + StopPin.PASSED_M + 5.0)
        assertNull("그보다 멀어지면 지나친 것이다", f.pinned)
    }

    @Test
    fun thePinEndsWhenTheCarReachedTheStopAndLeftIt() {
        val f = StopPin()
        f.onGoToStarted(wc)
        f.onGoToFinished(wc, ok = true, nowMs = 0L)
        f.track(at(400.0), "wc", null, 1_000)
        f.track(at(120.0), "wc", null, 2_000)
        f.track(at(StopPin.ARRIVED_M - 5.0), "wc", null, 3_000)
        assertEquals("150m 안에 있는 동안은 도착해 있는 것이다", wc, f.pinned)
        f.track(at(StopPin.ARRIVED_M + 10.0), "wc", null, 4_000)
        assertNull("들어갔다 나오면 떠난 것이다 (300m를 기다리지 않는다)", f.pinned)
    }

    @Test
    fun aStopOnItsWayToTheNaviIsNotDroppedForBeingPassedButItsClosestApproachCounts() {
        val f = StopPin()
        f.onGoToStarted(wc)
        f.track(at(100.0), null, null, 1_000)
        f.track(at(2_000.0), null, null, 2_000)
        assertEquals("넣는 중에는 빼지 않는다 (결과가 정한다)", listOf(wc), f.extras())
        f.onGoToFinished(wc, ok = true, nowMs = 3_000)
        f.track(at(2_100.0), "wc", null, 4_000)
        assertNull("넣는 동안 이미 지나쳤으면 다음 틱에 뺀다", f.pinned)
    }

    @Test
    fun withoutAPositionOnlyTheEngineRuleApplies() {
        val f = StopPin()
        f.onGoToStarted(wc)
        f.onGoToFinished(wc, ok = true, nowMs = 0L)
        for (i in 1..600) f.track(null, "wc", null, i * 1_000L)
        assertEquals(wc, f.pinned)
    }

    @Test
    fun aWorldSwitchDropsThePinAndTheStopOnItsWay() {
        val f = StopPin()
        f.onGoToStarted(wc)
        f.onGoToFinished(wc, ok = true, nowMs = 0L)
        f.onGoToStarted(gas)
        f.reset()
        assertNull(f.pinned)
        assertTrue(f.extras().isEmpty())
        f.onGoToFinished(gas, ok = true, nowMs = 5_000)
        assertNull("지난 세계에서 보낸 들르기는 새 세계의 고정이 되지 않는다", f.pinned)
    }
}
