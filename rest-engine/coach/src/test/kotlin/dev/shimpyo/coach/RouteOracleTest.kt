package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.Road
import dev.shimpyo.coach.fakes.TripNaviPort
import dev.shimpyo.coach.fakes.stop
import dev.shimpyo.engine.StopCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * requestTrip 보정 (RouteOracle). 처음으로 RouteOracle을 직접 지킨다 (구조 리뷰 S6).
 * 회귀: 실패를 기억하지 않아 응답 없는 내비가 갱신마다 6×20초를 잡고 경로·TBT 조회와 경유지 명령을 굶겼다
 * (서비스 감사 P2, 데이터 감사 #6), runCatching이 CancellationException을 삼켰다, 캐시 나이에 시연 가상 시각이 섞였다.
 */
class RouteOracleTest {

    private val anseong = stop("anseong", "안성휴게소(부산방향)", 40.0).c

    private class Fx(scope: TestScope, answer: suspend (Double, Double, String, Long) -> TripEstimate) {
        /** 가상 시간 + 손으로 더하는 시간 (캐시 나이를 시간 초과 없이 흘리려고) */
        var extraMs = 0L
        val clock = ElapsedClock { scope.testScheduler.currentTime + extraMs }
        val navi = TripNaviPort(answer)
        val oracle = RouteOracle(navi, clock)
    }

    private fun answering(distanceM: Double, etaS: Double = distanceM / 25.0): suspend (Double, Double, String, Long) -> TripEstimate =
        { _, _, _, _ -> TripEstimate(distanceM, etaS) }

    @Test
    fun freshCacheAnswersWithoutAskingAndSubtractsTheDistanceMoved() = runTest {
        val fx = Fx(this, answering(42_000.0, 1_680.0))
        val first = fx.oracle.refine(anseong, Road.car(0.0))!!
        assertEquals(42_000.0, first.routeDistanceM!!, 1e-6)
        assertEquals(1_680.0, first.etaS!!, 1e-6)
        assertEquals(true, first.directionOk)

        val second = fx.oracle.refine(anseong, Road.car(1.0))!!
        assertEquals("3km·5분 안이면 다시 묻지 않는다", 1, fx.navi.tripCalls)
        val moved = Geo.distanceM(Road.car(0.0).lat, Road.LON, Road.car(1.0).lat, Road.LON)
        assertEquals("움직인 만큼 뺀다", 42_000.0 - moved, second.routeDistanceM!!, 1e-6)
        assertEquals("ETA는 같은 비율로", 1_680.0 * (42_000.0 - moved) / 42_000.0, second.etaS!!, 1e-6)
    }

    @Test
    fun theCacheExpiresByElapsedTimeMovementAndHeadingReversal() = runTest {
        val fx = Fx(this, answering(42_000.0))
        fx.oracle.refine(anseong, Road.car(0.0))
        fx.extraMs += CandidateTuning.ORACLE_MAX_AGE_MS
        fx.oracle.refine(anseong, Road.car(0.0))
        assertEquals("5분이 지나면 다시 묻는다 (실제 시간 기준)", 2, fx.navi.tripCalls)
        fx.oracle.refine(anseong, Road.car(3.5))
        assertEquals("3km 넘게 움직이면 다시 묻는다", 3, fx.navi.tripCalls)
        fx.oracle.refine(anseong, Road.car(3.5, heading = 0f))
        assertEquals("방향이 뒤집혔으면(유턴) 다시 묻는다 (데이터 감사 #11)", 4, fx.navi.tripCalls)
        fx.oracle.refine(anseong, Road.car(3.5, heading = null))
        assertEquals("방향을 모르면 위치·나이로만 판단", 4, fx.navi.tripCalls)
    }

    @Test
    fun theDetourRatioDecidesDirection() = runTest {
        // 2026-11-11 측정: 안성휴게소 부산방향 경로 47.5km / 직선 44.6km, 서울방향 57.1km / 37.6km
        val busan = StopCandidate("b", "안성휴게소(부산방향)", anseong.kind, Road.latAtKm(44.6), Road.LON, anseong.facilities, parkable = true)
        val seoul = StopCandidate("s", "안성휴게소(서울방향)", anseong.kind, Road.latAtKm(37.6), Road.LON, anseong.facilities, parkable = true)
        val fx = Fx(this) { lat, _, _, _ -> TripEstimate(if (lat == busan.lat) 47_500.0 else 57_100.0, 1_800.0) }
        assertEquals(true, fx.oracle.refine(busan, Road.car())!!.directionOk)
        assertEquals("유턴이 필요하다", false, fx.oracle.refine(seoul, Road.car())!!.directionOk)
    }

    @Test
    fun aFailedRequestIsUnknownAndRetriedOnlyAfterTheRetryDelay() = runTest {
        var fail = true
        val fx = Fx(this) { _, _, _, _ -> if (fail) error("ROUTE_NOT_FOUND") else TripEstimate(42_000.0, 1_600.0) }
        assertNull("실패는 '모름'이다 (방향이 틀렸다는 뜻이 아니다)", fx.oracle.refine(anseong, Road.car()))
        fail = false
        assertNull("바로 다시 묻지 않는다", fx.oracle.refine(anseong, Road.car()))
        assertEquals(1, fx.navi.tripCalls)
        fx.extraMs += CandidateTuning.ORACLE_RETRY_MS
        assertNotNull(fx.oracle.refine(anseong, Road.car()))
        assertEquals(2, fx.navi.tripCalls)
    }

    @Test
    fun anUnresponsiveNaviIsPausedSoOneRefreshCostsOneTimeout() = runTest {
        var hang = true
        val fx = Fx(this) { _, _, _, _ -> if (hang) awaitCancellation() else TripEstimate(42_000.0, 1_600.0) }
        val others = (1..5).map { stop("o$it", "시설${it}휴게소", 10.0 * it).c }
        val start = currentTime
        assertNull(fx.oracle.refine(anseong, Road.car()))
        assertEquals("요청 하나는 시간 초과까지만 기다린다", CandidateTuning.ORACLE_REQUEST_TIMEOUT_MS, currentTime - start)
        others.forEach { assertNull(fx.oracle.refine(it, Road.car())) }
        assertEquals("응답 없는 내비에는 쉬는 동안 아무것도 묻지 않는다 (예전: 6×20초)", 1, fx.navi.tripCalls)
        assertEquals(CandidateTuning.ORACLE_REQUEST_TIMEOUT_MS, currentTime - start)

        // 쉬는 시간이 지나면 다시 묻고, 또 멈추면 두 배로 쉰다
        fx.extraMs += CandidateTuning.ORACLE_BACKOFF_MIN_MS
        assertNull(fx.oracle.refine(others[0], Road.car()))
        assertEquals(2, fx.navi.tripCalls)
        fx.extraMs += CandidateTuning.ORACLE_BACKOFF_MIN_MS
        assertNull("두 번째는 2배로 쉰다", fx.oracle.refine(others[1], Road.car()))
        assertEquals(2, fx.navi.tripCalls)
        fx.extraMs += CandidateTuning.ORACLE_BACKOFF_MIN_MS
        hang = false
        assertNotNull(fx.oracle.refine(others[1], Road.car()))
        assertEquals(3, fx.navi.tripCalls)

        // 답하면 쉬는 시간이 처음으로 돌아간다
        hang = true
        assertNull(fx.oracle.refine(others[2], Road.car()))
        fx.extraMs += CandidateTuning.ORACLE_BACKOFF_MIN_MS
        hang = false
        assertNotNull("처음 쉬는 시간(30초)만 쉰다", fx.oracle.refine(others[3], Road.car()))
    }

    @Test
    fun quickFailuresInARowPauseTheNaviToo() = runTest {
        val fx = Fx(this) { _, _, _, _ -> error("NETWORK") }
        val five = (1..5).map { stop("q$it", "시설${it}휴게소", 10.0 * it).c }
        five.forEach { fx.oracle.refine(it, Road.car()) }
        assertEquals("연달아 ${CandidateTuning.ORACLE_FAILS_FOR_BACKOFF}번 실패하면 쉰다", CandidateTuning.ORACLE_FAILS_FOR_BACKOFF, fx.navi.tripCalls)
    }

    @Test
    fun cancellationPropagatesAndIsNotCountedAsAFailure() = runTest {
        var hang = true
        val fx = Fx(this) { _, _, _, _ -> if (hang) awaitCancellation() else TripEstimate(42_000.0, 1_600.0) }
        val job = async { fx.oracle.refine(anseong, Road.car()) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue("바깥이 취소하면 그대로 취소된다", job.isCancelled)
        var thrown: Throwable? = null
        try {
            job.await()
        } catch (e: CancellationException) {
            thrown = e
        }
        assertNotNull(thrown)
        hang = false
        assertNotNull("취소는 실패로 세지 않아 바로 다시 묻는다", fx.oracle.refine(anseong, Road.car()))
        assertEquals(2, fx.navi.tripCalls)
    }

    @Test
    fun aTimeoutInsideThePortIsAFailureNotACancellation() = runTest {
        // 포트 안쪽의 withTimeout이 던지는 TimeoutCancellationException은 CancellationException이지만 우리 취소가 아니다
        val fx = Fx(this) { _, _, _, _ -> withTimeout(100) { awaitCancellation() } }
        assertNull("부른 쪽은 취소되지 않고 '모름'을 받는다", fx.oracle.refine(anseong, Road.car()))
        assertNull("실패로 기록됐다", fx.oracle.refine(anseong, Road.car()))
        assertEquals(1, fx.navi.tripCalls)
    }

    @Test
    fun disabledPassesThroughWithoutAsking() = runTest {
        val fx = Fx(this, answering(42_000.0))
        fx.oracle.enabled = false
        assertNull(fx.oracle.refine(anseong, Road.car()))
        assertEquals(0, fx.navi.tripCalls)
    }

    @Test
    fun clearForgetsTheCacheFailuresAndPause() = runTest {
        var hang = true
        val fx = Fx(this) { _, _, _, _ -> if (hang) awaitCancellation() else TripEstimate(42_000.0, 1_600.0) }
        assertNull(fx.oracle.refine(anseong, Road.car()))
        fx.oracle.clear()
        hang = false
        assertNotNull("시연 전환 등으로 비우면 바로 다시 묻는다", fx.oracle.refine(anseong, Road.car()))
        fx.oracle.clear()
        fx.oracle.refine(anseong, Road.car())
        assertEquals("캐시도 비운다", 3, fx.navi.tripCalls)
    }

    @Test
    fun aGarbageAnswerIsAFailure() = runTest {
        val fx = Fx(this, answering(Double.NaN))
        assertNull(fx.oracle.refine(anseong, Road.car()))
    }

    @Test
    fun oneRequestTimeoutFitsInsideTheRefreshBudget() {
        // 그래야 멈춘 내비를 한 번의 갱신 안에서 알아채고 쉰다 (예산이 먼저 끝나면 실패로 세지 못한다)
        assertTrue(CandidateTuning.ORACLE_REQUEST_TIMEOUT_MS < CandidateTuning.REFRESH_BUDGET_MS)
    }

    @Suppress("DEPRECATION")
    @Test
    fun theOldEntryPointKeepsItsContractForTheCurrentService() = runTest {
        val fx = Fx(this) { _, _, _, _ -> error("x") }
        val c = fx.oracle.refine(anseong, Road.car().lat, Road.LON, 0L)
        assertNull(c.routeDistanceM)
        assertNotNull("실패하면 직선거리만 채워 돌려준다", c.straightDistanceM)
    }
}
