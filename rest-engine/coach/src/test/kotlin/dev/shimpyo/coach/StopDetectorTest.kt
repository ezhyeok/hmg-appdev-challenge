package dev.shimpyo.coach

import dev.shimpyo.engine.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 정차 판정 (CoachService.trackStops를 옮긴 것). 처음 커밋은 예전 동작을 그대로 고정했고(currentBehavior_*), 다음 커밋들에서 엔진
 * StopTracker와 같은 규칙으로 바꿨다. 두 판정이 다르면 기록의 '화장실' 표시와 엔진이 배운 것이 어긋난다(구조 리뷰 §2.3e).
 * 출발 차속 [FAST](30km/h 초과)이면 그 틱에 정차가 확정되고, 느리면 다시 대기 창(3분)이 끝날 때 확정된다.
 */
class StopDetectorTest {
    private val min = 60_000L
    private val t0 = 1_000_000_000_000L
    private val FAST = 40f
    private val SLOW = 10f

    // ---------------------------------------------------------------- 기본

    @Test
    fun parkedThreeMinutesWithDoorIsARestroomStop() {
        val d = StopDetector()
        assertNull(d.onTick(t0, Gear.PARK, 0f, doorOpened = false))
        assertNull(d.onTick(t0 + min, Gear.PARK, 0f, doorOpened = true))
        assertEquals(ParkedStop(t0, t0 + 3 * min, doorOpened = true), d.onTick(t0 + 3 * min, Gear.DRIVE, FAST, doorOpened = false))
    }

    @Test
    fun exactlyThreeMinutesCountsAndJustUnderDoesNot() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        assertNull("2분 59초", d.onTick(t0 + 3 * min - 1_000, Gear.DRIVE, FAST, false))
        d.onTick(t0 + 10 * min, Gear.PARK, 0f, false)
        assertEquals(ParkedStop(t0 + 10 * min, t0 + 13 * min, doorOpened = false), d.onTick(t0 + 13 * min, Gear.DRIVE, FAST, false))
    }

    @Test
    fun doorOnTheFirstParkedTickCounts() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, doorOpened = true)
        assertTrue(d.onTick(t0 + 5 * min, Gear.DRIVE, FAST, false)!!.doorOpened)
    }

    @Test
    fun doorWhileDrivingIsNotAStopDoor() {
        val d = StopDetector()
        d.onTick(t0, Gear.DRIVE, 30f, doorOpened = true)
        d.onTick(t0 + min, Gear.PARK, 0f, false)
        assertFalse(d.onTick(t0 + 5 * min, Gear.DRIVE, FAST, false)!!.doorOpened)
    }

    @Test
    fun parkTrustsTheGearOverSpeed() {
        // P에서는 바퀴가 잠긴다. P + 차속은 잡음이다 (엔진 감사 S8)
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        assertNull(d.onTick(t0 + min, Gear.PARK, 25f, false))
        assertTrue(d.parked)
    }

    @Test
    fun parkStateIsExposedWhileParked() {
        val d = StopDetector()
        assertNull(d.parkState)
        d.onTick(t0, Gear.PARK, 0f, false)
        assertEquals(ParkState(t0, doorSeen = false), d.parkState)
        d.onTick(t0 + min, Gear.PARK, 0f, doorOpened = true)
        assertEquals(ParkState(t0, doorSeen = true), d.parkState)
        d.onTick(t0 + 5 * min, Gear.DRIVE, SLOW, false)
        assertNull("다시 대기를 기다리는 동안에도 차는 P가 아니다", d.parkState)
    }

    // ---------------------------------------------------------------- 엔진 StopTracker와 같은 규칙

    @Test
    fun unknownGearWhileParkedKeepsTheStop() {
        // 예전: 기어 신호가 잠깐 끊기면(Car 서비스 재연결) 정차가 사라지고 다시 센 정차에는 문 열림이 빠졌다. 엔진은 정차를 유지했다
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, doorOpened = true)
        assertNull(d.onTick(t0 + 5 * min, Gear.UNKNOWN, null, false))
        assertTrue(d.parked)
        d.onTick(t0 + 6 * min, Gear.PARK, 0f, false)
        val s = d.onTick(t0 + 10 * min, Gear.DRIVE, FAST, false)!!
        assertEquals("처음 세운 시각부터", t0, s.start)
        assertTrue("문 열림을 잃지 않는다", s.doorOpened)
    }

    @Test
    fun unknownGearWithSpeedMeansMoving() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        assertEquals(ParkedStop(t0, t0 + 5 * min, false), d.onTick(t0 + 5 * min, Gear.UNKNOWN, FAST, false))
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
        assertTrue(d.onTick(t0 + 5 * min, Gear.DRIVE, FAST, doorOpened = true)!!.doorOpened)
    }

    // ---------------------------------------------------------------- 다시 대기 (엔진 감사 S7)

    @Test
    fun reparkingWithinThreeMinutesIsTheSameStop() {
        // 휴게소에서 화장실 앞에 섰다가(14분) 식당 앞으로 옮겨 댔다(5분): 정차 하나, 19분 + 옮긴 시간
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, doorOpened = true)
        assertNull(d.onTick(t0 + 14 * min, Gear.DRIVE, SLOW, false))
        assertNull(d.onTick(t0 + 15 * min, Gear.DRIVE, 15f, false))
        assertNull(d.onTick(t0 + 16 * min, Gear.PARK, 0f, false))
        assertEquals(ParkState(t0, doorSeen = true), d.parkState)
        assertEquals(ParkedStop(t0, t0 + 21 * min, doorOpened = true), d.onTick(t0 + 21 * min, Gear.DRIVE, 60f, false))
    }

    @Test
    fun reversingIntoAnotherSpotIsTheSameStop() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        assertNull(d.onTick(t0 + 4 * min, Gear.REVERSE, 2f, false))
        assertNull(d.onTick(t0 + 4 * min + 30_000, Gear.PARK, 0f, false))
        assertEquals(ParkedStop(t0, t0 + 10 * min, false), d.onTick(t0 + 10 * min, Gear.DRIVE, FAST, false))
    }

    @Test
    fun aSlowDepartureIsRecordedWhenTheReparkWindowCloses() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        assertNull(d.onTick(t0 + 5 * min, Gear.DRIVE, SLOW, false))
        assertNull(d.onTick(t0 + 8 * min, Gear.DRIVE, 20f, false))
        assertEquals("끝은 P에서 나온 때", ParkedStop(t0, t0 + 5 * min, false), d.onTick(t0 + 8 * min + 1_000, Gear.DRIVE, 20f, false))
        assertNull("한 번만", d.onTick(t0 + 9 * min, Gear.DRIVE, 50f, false))
    }

    @Test
    fun speedAboveThirtyEndsTheStopAtOnce() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        assertNull(d.onTick(t0 + 5 * min, Gear.DRIVE, SLOW, false))
        assertEquals("본선 합류로 가속하면 다시 대기가 아니다", ParkedStop(t0, t0 + 5 * min, false), d.onTick(t0 + 5 * min + 20_000, Gear.DRIVE, 45f, false))
    }

    @Test
    fun parkingAgainAfterTheWindowIsANewStop() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        d.onTick(t0 + 5 * min, Gear.DRIVE, SLOW, false)
        assertEquals("앞 정차는 새 정차가 시작될 때 확정된다", ParkedStop(t0, t0 + 5 * min, false), d.onTick(t0 + 9 * min, Gear.PARK, 0f, false))
        assertEquals(ParkState(t0 + 9 * min, doorSeen = false), d.parkState)
    }

    @Test
    fun aDoorDuringTheGapIsNotAStopDoor() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        d.onTick(t0 + 5 * min, Gear.DRIVE, SLOW, false)
        d.onTick(t0 + 6 * min, Gear.DRIVE, 0f, doorOpened = true)
        assertFalse(d.onTick(t0 + 9 * min, Gear.DRIVE, 20f, false)!!.doorOpened)
    }

    // ---------------------------------------------------------------- 시계

    @Test
    fun clockGoingBackwardsCountsNoParkedTimeForThatStep() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, true)
        assertNull("거꾸로 간 한 걸음은 0으로 센다 (엔진 EngineClock과 같다)", d.onTick(t0 - 10 * min, Gear.DRIVE, FAST, false))
    }

    @Test
    fun aClockJumpBackWhileParkedDoesNotLoseTheStop() {
        // 예전: 정차 중 시각이 8분 뒤로 맞춰지면(NTP) 끝 - 시작이 2분이라 정차가 사라졌다. 엔진은 실제로 선 8분을 셌다
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, doorOpened = true)
        d.onTick(t0 + 2 * min, Gear.PARK, 0f, false)
        d.onTick(t0 - 6 * min, Gear.PARK, 0f, false) // 8분 뒤로
        d.onTick(t0 - 3 * min, Gear.PARK, 0f, false)
        assertEquals(
            "선 시간은 틱 사이 시간을 더한 8분, 끝은 지금 시계",
            ParkedStop(t0 - 8 * min, t0, doorOpened = true),
            d.onTick(t0, Gear.DRIVE, FAST, false),
        )
    }

    private val real1000 = t0
    private val demo1400 = real1000 + 4 * 60 * min

    // ---------------------------------------------------------------- 세계 전환 (WorldChange.restorePark, 서비스 감사 D3)

    @Test
    fun parkedAcrossADemoStartDoesNotInventAFourHourStop() {
        val d = StopDetector()
        assertNull(d.onTick(real1000, Gear.PARK, 0f, doorOpened = true))
        // 예전: 실제 10:00에 세운 채 시연(14:00)을 켜고 D로 바꾸면 10:00→14:00 정차가 기록됐다. 시연 세계는 정차 없이 시작한다
        d.restore(null, now = demo1400)
        assertNull(d.parkState)
        d.onTick(demo1400 + 1_000, Gear.PARK, 0f, false)
        assertEquals("시연 세계의 정차는 시연 시계로 센다", ParkState(demo1400 + 1_000, doorSeen = false), d.parkState)
        assertNull("새 시계로는 3분이 안 됐다", d.onTick(demo1400 + 5_000, Gear.DRIVE, FAST, false))
    }

    @Test
    fun demoOffRestoresTheRealStopWhileStillParked() {
        val d = StopDetector()
        d.onTick(real1000, Gear.PARK, 0f, doorOpened = true)
        d.restore(null, now = demo1400)
        d.onTick(demo1400 + 1_000, Gear.PARK, 0f, false)
        // 시연을 끈다: 실제 세계 시계(벽시계)는 계속 흘렀다. 실제 여정의 정차를 되돌린다
        d.restore(ParkState(real1000, doorSeen = true), now = real1000 + 20 * min)
        d.onTick(real1000 + 20 * min + 1_000, Gear.PARK, 0f, false)
        assertEquals(ParkedStop(real1000, real1000 + 30 * min, doorOpened = true), d.onTick(real1000 + 30 * min, Gear.DRIVE, FAST, false))
    }

    @Test
    fun demoOffWithTheCarNoLongerParkedEndsTheRealStopWhenTheDemoStarted() {
        // 시연 중 Virtual Console로 D를 넣었다. 시연을 끈 첫 틱이 D면 실제 정차는 시연을 켠 때 끝난 것으로 본다
        // (끈 때로 잡으면 시연 시간만큼의 가짜 실제 정차가 실제 기록에 들어간다)
        val d = StopDetector()
        d.restore(ParkState(real1000, doorSeen = true), now = real1000 + 20 * min, lastSeenMs = real1000 + 5 * min)
        assertEquals(ParkedStop(real1000, real1000 + 5 * min, doorOpened = true), d.onTick(real1000 + 20 * min + 1_000, Gear.DRIVE, FAST, false))
    }

    @Test
    fun restoringNothingWhileDrivingChangesNothing() {
        val d = StopDetector()
        d.onTick(t0, Gear.DRIVE, 40f, false)
        d.restore(null, now = demo1400)
        assertNull(d.parkState)
        assertNull(d.onTick(demo1400 + 1_000, Gear.DRIVE, 40f, false))
    }

    @Test
    fun restoreDropsAStopWaitingForRepark() {
        // 옛 세계의 정차다. 새 세계 기록에 넣지 않는다 (세계 전환 직전 3분 안에 천천히 떠난 실제 정차는 잃는다: 받아들인 손실)
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, true)
        d.onTick(t0 + 5 * min, Gear.DRIVE, SLOW, false)
        d.restore(null, now = demo1400)
        assertNull(d.onTick(demo1400 + 4 * min, Gear.DRIVE, 60f, false))
    }

    // ---------------------------------------------------------------- 재시작 (서비스 감사 D12, TripSession.restoredPark)

    @Test
    fun restartWhileParkedResumesTheStop() {
        val parkedAt = t0
        val d = StopDetector()
        d.restore(ParkState(parkedAt, doorSeen = true), now = parkedAt + 4 * min, lastSeenMs = parkedAt + 3 * min)
        assertEquals(ParkState(parkedAt, doorSeen = true), d.parkState)
        d.onTick(parkedAt + 4 * min, Gear.PARK, 0f, false)
        assertEquals(
            "다시 시작하기 전의 정차 시작과 문 열림이 남는다",
            ParkedStop(parkedAt, parkedAt + 6 * min, doorOpened = true),
            d.onTick(parkedAt + 6 * min, Gear.DRIVE, FAST, false),
        )
    }

    @Test
    fun departedWhileTheAppWasDownEndsTheStopAtTheLastSeenTime() {
        // 앱이 꺼진 사이 출발했으면 정차 끝은 마지막 맥박 때다 (재시작 시각까지 늘리지 않는다)
        val d = StopDetector()
        d.restore(ParkState(t0, doorSeen = true), now = t0 + 40 * min, lastSeenMs = t0 + 5 * min)
        assertEquals(ParkedStop(t0, t0 + 5 * min, doorOpened = true), d.onTick(t0 + 40 * min, Gear.DRIVE, SLOW, false))
    }

    @Test
    fun withoutALastSeenTimeTheStopEndsAtTheRestore() {
        val d = StopDetector()
        d.restore(ParkState(t0, doorSeen = false), now = t0 + 10 * min)
        assertEquals(ParkedStop(t0, t0 + 10 * min, false), d.onTick(t0 + 10 * min + 1_000, Gear.DRIVE, SLOW, false))
    }

    @Test
    fun restoreReplacesTheCurrentPark() {
        // 되돌리기는 합치기가 아니라 바꾸기다 (코어는 시작할 때 첫 틱 전에, 세계를 바꿀 때 바로 부른다)
        val d = StopDetector()
        d.onTick(t0 + 4 * min, Gear.PARK, 0f, false)
        d.restore(ParkState(t0, doorSeen = true), now = t0 + 4 * min)
        assertEquals(ParkState(t0, doorSeen = true), d.parkState)
    }

    @Test
    fun restoreFromTheFutureClearsThePark() {
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        d.restore(ParkState(t0 + 60 * min, doorSeen = false), now = t0)
        assertNull("시연 시각 같은 미래 값은 버린다", d.parkState)
        val d2 = StopDetector()
        d2.restore(ParkState(t0 - 4 * min, doorSeen = false), now = t0, lastSeenMs = t0 + 9 * min)
        assertEquals("미래의 마지막 확인은 지금으로", ParkedStop(t0 - 4 * min, t0, false), d2.onTick(t0 + min, Gear.DRIVE, FAST, false))
    }
}
