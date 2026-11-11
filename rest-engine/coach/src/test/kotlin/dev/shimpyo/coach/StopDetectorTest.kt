package dev.shimpyo.coach

import dev.shimpyo.engine.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 정차 판정 (CoachService.trackStops를 옮긴 것). 먼저 지금 동작을 그대로 고정하고(currentBehavior_*), 틀린 것은 다음 커밋에서
 * 엔진 StopTracker와 같은 규칙으로 뒤집는다. 두 판정이 다르면 기록의 '화장실' 표시와 엔진이 배운 것이 어긋난다(구조 리뷰 §2.3e).
 */
class StopDetectorTest {
    private val min = 60_000L
    private val t0 = 1_000_000_000_000L

    @Test
    fun parkedThreeMinutesWithDoorIsARestroomStop() {
        val d = StopDetector()
        assertNull(d.onTick(t0, Gear.PARK, 0f, doorOpened = false))
        assertNull(d.onTick(t0 + min, Gear.PARK, 0f, doorOpened = true))
        assertEquals(ParkedStop(t0, t0 + 3 * min, doorOpened = true), d.onTick(t0 + 3 * min, Gear.DRIVE, 5f, doorOpened = false))
    }

    @Test
    fun exactlyThreeMinutesCountsAndJustUnderDoesNot() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        assertNull("2분 59초", d.onTick(t0 + 3 * min - 1_000, Gear.DRIVE, 5f, false))
        d.onTick(t0 + 10 * min, Gear.PARK, 0f, false)
        assertEquals(ParkedStop(t0 + 10 * min, t0 + 13 * min, doorOpened = false), d.onTick(t0 + 13 * min, Gear.DRIVE, 5f, false))
    }

    @Test
    fun doorOnTheFirstParkedTickCounts() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, doorOpened = true)
        assertTrue(d.onTick(t0 + 5 * min, Gear.DRIVE, 5f, false)!!.doorOpened)
    }

    @Test
    fun doorWhileDrivingIsNotAStopDoor() {
        val d = StopDetector()
        d.onTick(t0, Gear.DRIVE, 30f, doorOpened = true)
        d.onTick(t0 + min, Gear.PARK, 0f, false)
        assertFalse(d.onTick(t0 + 5 * min, Gear.DRIVE, 5f, false)!!.doorOpened)
    }

    @Test
    fun reverseEndsTheStopLikeTheEngine() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        assertEquals(ParkedStop(t0, t0 + 4 * min, false), d.onTick(t0 + 4 * min, Gear.REVERSE, 2f, false))
        assertNull("한 번만", d.onTick(t0 + 4 * min + 1_000, Gear.DRIVE, 5f, false))
    }

    @Test
    fun parkStateIsExposedWhileParked() {
        val d = StopDetector()
        assertNull(d.parkState)
        d.onTick(t0, Gear.PARK, 0f, false)
        assertEquals(ParkState(t0, doorAtMs = null), d.parkState)
        d.onTick(t0 + min, Gear.PARK, 0f, doorOpened = true)
        assertEquals(ParkState(t0, doorAtMs = t0 + min), d.parkState)
        d.onTick(t0 + 2 * min, Gear.PARK, 0f, doorOpened = true)
        assertEquals("첫 문 열림 시각을 둔다", ParkState(t0, doorAtMs = t0 + min), d.parkState)
        d.onTick(t0 + 5 * min, Gear.DRIVE, 5f, false)
        assertNull(d.parkState)
    }

    // ---------------------------------------------------------------- 엔진 StopTracker와 같은 규칙 (예전 동작을 뒤집음)

    @Test
    fun unknownGearWhileParkedKeepsTheStop() {
        // 예전: 기어 신호가 잠깐 끊기면(Car 서비스 재연결) 정차가 사라지고 다시 센 정차에는 문 열림이 빠졌다. 엔진은 정차를 유지했다
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, doorOpened = true)
        assertNull(d.onTick(t0 + 5 * min, Gear.UNKNOWN, null, false))
        assertTrue(d.parked)
        d.onTick(t0 + 6 * min, Gear.PARK, 0f, false)
        val s = d.onTick(t0 + 10 * min, Gear.DRIVE, 20f, false)!!
        assertEquals("처음 세운 시각부터", t0, s.start)
        assertTrue("문 열림을 잃지 않는다", s.doorOpened)
    }

    @Test
    fun unknownGearWithSpeedMeansMoving() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        assertEquals(ParkedStop(t0, t0 + 5 * min, false), d.onTick(t0 + 5 * min, Gear.UNKNOWN, 20f, false))
        assertFalse(d.parked)
    }

    @Test
    fun unknownGearSlowOrSpeedUnknownKeepsThePreviousState() {
        val d = StopDetector()
        d.onTick(t0, Gear.UNKNOWN, null, doorOpened = true)
        assertFalse("처음부터 모르면 정차로 단정하지 않는다", d.parked)
        d.onTick(t0 + min, Gear.DRIVE, 40f, false)
        d.onTick(t0 + 2 * min, Gear.UNKNOWN, 2f, doorOpened = true)
        assertFalse("달리던 중 신호가 끊겨도 정차가 아니다 (3km/h 이하라도)", d.parked)
        assertNull(d.onTick(t0 + 10 * min, Gear.DRIVE, 40f, false))
    }

    @Test
    fun doorOnTheDepartureTickCounts() {
        // 예전: P에서 D로 바뀐 틱에 함께 온 문 열림을 버렸다. 엔진은 그 문 열림으로 화장실 시계를 되돌렸다
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        assertTrue(d.onTick(t0 + 5 * min, Gear.DRIVE, 5f, doorOpened = true)!!.doorOpened)
    }

    @Test
    fun clockGoingBackwardsWhileParkedRecordsNothing() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, true)
        assertNull("거꾸로 간 시각으로 정차를 만들지 않는다", d.onTick(t0 - 10 * min, Gear.DRIVE, 5f, false))
    }

    // ---------------------------------------------------------------- 시계 전환 (서비스 감사 D3, 구조 리뷰 §2.4)

    private val real1000 = t0
    private val demo1400 = real1000 + 4 * 60 * min

    @Test
    fun parkedAcrossADemoStartDoesNotInventAFourHourStop() {
        val d = StopDetector()
        assertNull(d.onTick(real1000, Gear.PARK, 0f, doorOpened = true))
        // 예전: 실제 10:00에 세운 채 시연(14:00)을 켜고 D로 바꾸면 10:00→14:00 정차가 기록됐다
        d.rebase(demo1400)
        assertEquals(ParkState(demo1400, doorAtMs = null), d.parkState)
        assertNull("새 시계로는 3분이 안 됐다", d.onTick(demo1400 + 5_000, Gear.DRIVE, 40f, false))
    }

    @Test
    fun rebaseWhileDrivingChangesNothing() {
        val d = StopDetector()
        d.onTick(t0, Gear.DRIVE, 40f, false)
        d.rebase(demo1400)
        assertNull(d.parkState)
        assertNull(d.onTick(demo1400 + 1_000, Gear.DRIVE, 40f, false))
    }

    // ---------------------------------------------------------------- 재시작 (서비스 감사 D12)

    @Test
    fun restartWhileParkedResumesTheStop() {
        val parkedAt = t0
        val d = StopDetector()
        d.resume(ParkState(parkedAt, doorAtMs = parkedAt + min), now = parkedAt + 4 * min)
        assertEquals(ParkState(parkedAt, parkedAt + min), d.parkState)
        d.onTick(parkedAt + 4 * min, Gear.PARK, 0f, false)
        assertEquals(
            "다시 시작하기 전의 정차 시작과 문 열림이 남는다",
            ParkedStop(parkedAt, parkedAt + 6 * min, doorOpened = true),
            d.onTick(parkedAt + 6 * min, Gear.DRIVE, 5f, false),
        )
    }

    @Test
    fun resumeKeepsTheEarlierStartIfATickCameFirst() {
        val d = StopDetector()
        d.onTick(t0 + 4 * min, Gear.PARK, 0f, false)
        d.resume(ParkState(t0, doorAtMs = t0 + min), now = t0 + 4 * min)
        assertEquals(ParkState(t0, t0 + min), d.parkState)
    }

    @Test
    fun resumeFromTheFutureIsIgnored() {
        val d = StopDetector()
        d.resume(ParkState(t0 + 60 * min, doorAtMs = null), now = t0)
        assertNull("시연 시각 같은 미래 값은 버린다", d.parkState)
        d.resume(ParkState(t0 - min, doorAtMs = t0 + 5 * min), now = t0)
        assertEquals("미래의 문 열림도 버린다", ParkState(t0 - min, null), d.parkState)
    }
}
