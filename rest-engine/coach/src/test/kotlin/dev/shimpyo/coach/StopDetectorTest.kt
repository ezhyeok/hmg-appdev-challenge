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

    // ---------------------------------------------------------------- 지금 동작 고정 (다음 커밋에서 뒤집는다)

    @Test
    fun currentBehavior_unknownGearWhileParkedDropsTheStop() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, doorOpened = true)
        assertNull("기어 신호가 잠깐 끊기면 정차가 사라진다", d.onTick(t0 + 5 * min, Gear.UNKNOWN, null, false))
        d.onTick(t0 + 6 * min, Gear.PARK, 0f, false)
        val s = d.onTick(t0 + 10 * min, Gear.DRIVE, 20f, false)!!
        assertEquals("새 정차로 다시 센다", t0 + 6 * min, s.start)
        assertFalse(s.doorOpened)
    }

    @Test
    fun currentBehavior_doorOnTheDepartureTickIsIgnored() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        assertFalse(d.onTick(t0 + 5 * min, Gear.DRIVE, 5f, doorOpened = true)!!.doorOpened)
    }
}
