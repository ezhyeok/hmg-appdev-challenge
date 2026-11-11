package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.ManualClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실제 위치(위치 SDK + 내비 대체), 신선도, 방위 0.0 처리 */
class PositionTrackerTest {

    private val clock = ManualClock(1_000_000L)
    private var moving = false
    private val tracker = PositionTracker(clock) { moving }

    // 경부고속도로 기흥 부근, 남쪽으로 400m 떨어진 점
    private val giheung = Fix(37.2750, 127.1160, 190f)
    private val south400 = Fix(37.2714, 127.1160, null)

    @Test
    fun aFusedFixBecomesCurrentWithItsSourceAndElapsedTime() {
        tracker.onFused(giheung)
        val c = tracker.current.value!!
        assertEquals(giheung, c.fix)
        assertEquals(Located.Source.FUSED, c.source)
        assertEquals(1_000_000L, c.atElapsedMs)
    }

    @Test
    fun withoutAnyFixTheNaviIsAskedAtOnceEvenWhenParked() {
        assertTrue(tracker.needsNaviFallback())
    }

    @Test
    fun aStaticFusedFixWhileParkedIsNotStale() {
        // 서비스 감사 P6: 서 있으면 위치 SDK가 새 값을 안 보내도 위치는 그대로 맞다. 내비 위치(에뮬레이터는 판교)로 덮지 않는다
        tracker.onFused(giheung)
        clock.advance(10 * 60_000L)
        assertFalse(tracker.needsNaviFallback())
    }

    @Test
    fun movingWithoutUpdatesFor15sIsStale() {
        tracker.onFused(giheung)
        moving = true
        assertFalse(tracker.needsNaviFallback())
        clock.advance(15_000)
        assertFalse("15초까지는 기다린다", tracker.needsNaviFallback())
        clock.advance(1)
        assertTrue(tracker.needsNaviFallback())
    }

    @Test
    fun startingToMoveAfterALongParkGivesTheFusedFeed15s() {
        tracker.onFused(giheung)
        clock.advance(30 * 60_000L)
        assertFalse(tracker.needsNaviFallback())
        moving = true
        assertFalse("오래된 위치지만 막 출발했다: 위치 SDK가 새 값을 줄 시간을 준다", tracker.needsNaviFallback())
        clock.advance(10_000)
        assertFalse(tracker.needsNaviFallback())
        clock.advance(5_001)
        assertTrue("움직인 지 15초 넘게 새 위치가 없다", tracker.needsNaviFallback())
    }

    @Test
    fun aNaviAnswerIsDroppedWhenAFresherFusedFixArrivedMeanwhile() {
        moving = true
        val askedAt = clock.nowMs
        clock.advance(500)
        tracker.onFused(giheung)
        clock.advance(500)
        tracker.onNavi(Fix(37.39, 127.11, 45f), askedAt)
        assertEquals(Located.Source.FUSED, tracker.current.value!!.source)

        val askedAgain = clock.nowMs
        clock.advance(300)
        tracker.onNavi(Fix(37.39, 127.11, 45f), askedAgain)
        assertEquals(Located.Source.NAVI, tracker.current.value!!.source)
        assertEquals("받은 때가 신선도 기준", clock.nowMs, tracker.current.value!!.atElapsedMs)
    }

    @Test
    fun aNaviHeadingOfZeroIsUnknownNotNorth() {
        // 데이터 감사 #10: CurrentLocationInfo.heading 기본값이 0.0이다. 북쪽으로 보면 남쪽으로 가는 차의 앞쪽 후보가 뒤집힌다
        tracker.onFused(giheung) // 190도
        clock.advance(1_000)
        tracker.onNavi(giheung.copy(heading = 0f), clock.nowMs - 1)
        assertEquals("같은 자리면 직전 방위", 190f, tracker.current.value!!.fix.heading!!, 0f)

        moving = true
        clock.advance(15_000)
        tracker.onNavi(south400.copy(heading = 0f), clock.nowMs - 1)
        assertEquals("움직였으면 두 위치의 방위 (남쪽)", 180f, tracker.current.value!!.fix.heading!!, 1f)

        val fresh = PositionTracker(clock) { true }
        fresh.onNavi(giheung.copy(heading = 0f), clock.nowMs)
        assertNull("직전 위치가 없으면 모른다", fresh.current.value!!.fix.heading)
    }

    @Test
    fun aRealNaviHeadingIsKept() {
        tracker.onNavi(giheung.copy(heading = 187.5f), clock.nowMs)
        assertEquals(187.5f, tracker.current.value!!.fix.heading!!, 0f)
        tracker.onNavi(giheung.copy(heading = -90f), clock.nowMs)
        assertEquals("0~360으로 맞춘다", 270f, tracker.current.value!!.fix.heading!!, 0f)
    }

    @Test
    fun aFusedHeadingOfZeroCountsOnlyWhileMoving() {
        tracker.onFused(giheung)
        tracker.onFused(giheung.copy(heading = 0f))
        assertEquals("서 있을 때의 0은 방위 없음: 직전 방위", 190f, tracker.current.value!!.fix.heading!!, 0f)
        tracker.onFused(giheung.copy(heading = Float.NaN))
        assertEquals(190f, tracker.current.value!!.fix.heading!!, 0f)
        moving = true
        tracker.onFused(giheung.copy(heading = 0f))
        assertEquals("달리는 중의 0은 정북", 0f, tracker.current.value!!.fix.heading!!, 0f)
    }

    @Test
    fun anOldFixIsNotUsedToEstimateAHeading() {
        moving = true
        tracker.onFused(giheung.copy(heading = null))
        clock.advance(PositionTracker.BEARING_MAX_AGE_MS + 1)
        tracker.onNavi(south400.copy(heading = 0f), clock.nowMs)
        assertNull("그 사이 어디로 돌았는지 모른다", tracker.current.value!!.fix.heading)
    }

    @Test
    fun invalidCoordinatesAreIgnored() {
        tracker.onFused(giheung)
        tracker.onFused(Fix(Double.NaN, 127.0, 0f))
        tracker.onFused(Fix(0.0, 0.0, 0f))
        tracker.onNavi(Fix(91.0, 127.0, 10f), clock.nowMs)
        tracker.onNavi(Fix(37.0, Double.POSITIVE_INFINITY, 10f), clock.nowMs)
        assertEquals(giheung, tracker.current.value!!.fix)
    }

    @Test
    fun theWorldCanForbidTheNaviFallback() {
        // 시연: 시연 위치를 덧씌우므로 내비에 묻지 않는다 (CoachWorld.usesNaviLocationFallback = false)
        var allowed = false
        val fallback = tracker.naviFallback { allowed }
        assertFalse(fallback.wanted())
        allowed = true
        assertTrue(fallback.wanted())
        fallback.onFix(giheung, clock.nowMs)
        assertEquals(Located.Source.NAVI, tracker.current.value!!.source)
    }

    @Test
    fun movingMeansSpeedAboveWalkingPaceOrNotParkedWhenSpeedIsUnknown() {
        assertFalse(PositionTracker.isMoving(0f, parked = false)) // 신호 대기
        assertFalse(PositionTracker.isMoving(0.5f, parked = false))
        assertTrue(PositionTracker.isMoving(5f, parked = false))
        assertTrue(PositionTracker.isMoving(null, parked = false)) // 차속 권한 없음: 보수적으로
        assertFalse(PositionTracker.isMoving(null, parked = true))
    }
}
