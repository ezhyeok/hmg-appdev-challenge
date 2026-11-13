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
    fun aBoardingStopIgnoresDoorsUntilTheDepartureIsAccepted() {
        // 타는 중 (2026-11-13 감사, 엔진 StopTracker와 같은 규칙): 탈 때 연 문은 화장실 정차의 문이 아니다
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, doorOpened = false, boardingIfNew = true)
        d.onTick(t0 + 30_000, Gear.PARK, 0f, doorOpened = true)
        assertFalse("탈 때의 문", d.parkState!!.doorSeen)
        assertFalse(d.onTick(t0 + 5 * min, Gear.DRIVE, FAST, false)!!.doorOpened)
        // 다음 정차: '응' 한 뒤에 연 문은 센다. 다시 대기(옮겨 대기)를 거쳐도 그 정차는 타는 중이다
        val e = StopDetector()
        e.onTick(t0, Gear.PARK, 0f, false, boardingIfNew = true)
        e.onTick(t0 + min, Gear.DRIVE, 5f, false)
        e.onTick(t0 + min + 20_000, Gear.PARK, 0f, false, boardingIfNew = false)
        e.onTick(t0 + 2 * min, Gear.PARK, 0f, doorOpened = true)
        assertFalse("다시 대기도 같은 타는 중의 정차", e.parkState!!.doorSeen)
        e.intendRestroom(t0 + 3 * min)
        e.onTick(t0 + 4 * min, Gear.PARK, 0f, doorOpened = true)
        assertTrue("'응' 뒤의 문", e.parkState!!.doorSeen)
        // 되돌린 정차도 타는 중이면 문을 세지 않는다
        val r = StopDetector()
        r.restore(ParkState(t0, doorSeen = false), t0 + min, boarding = true)
        r.onTick(t0 + 2 * min, Gear.PARK, 0f, doorOpened = true)
        assertFalse(r.parkState!!.doorSeen)
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
        // (틱이 1분 간격이라 지난 틱의 차속으로 1분을 간 것으로 센다: 시속 5km 83m + 시속 6km 100m)
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, doorOpened = true)
        assertNull(d.onTick(t0 + 14 * min, Gear.DRIVE, 5f, false))
        assertNull(d.onTick(t0 + 15 * min, Gear.DRIVE, 6f, false))
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
        // 주차장 안을 시속 2km로 3분(100m): 거리도 차속도 넘지 않으니 3분 창이 끝날 때 확정된다
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        assertNull(d.onTick(t0 + 5 * min, Gear.DRIVE, 2f, false))
        assertNull(d.onTick(t0 + 8 * min, Gear.DRIVE, 2f, false))
        assertEquals("끝은 P에서 나온 때", ParkedStop(t0, t0 + 5 * min, false), d.onTick(t0 + 8 * min + 1_000, Gear.DRIVE, 2f, false))
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

    // ---------------------------------------------------------------- 다시 대기는 300m 안에서만 (엔진 닫힘 검증 E4)

    /** [from] 다음 초부터 [seconds]초 동안 1초 틱을 넣는다. 낸 정차는 [out]에 모으고 마지막 틱 시각을 돌려준다 */
    private fun StopDetector.feed(from: Long, seconds: Int, gear: Gear, kph: Float?, out: MutableList<ParkedStop>): Long {
        var t = from
        repeat(seconds) {
            t += 1_000
            onTick(t, gear, kph, false)?.let { out += it }
        }
        return t
    }

    @Test
    fun movingLessThanThreeHundredMetresIsStillTheSameStop() {
        val d = StopDetector()
        val out = mutableListOf<ParkedStop>()
        d.onTick(t0, Gear.PARK, 0f, false)
        var t = d.feed(t0 + 10 * min - 1_000, 50, Gear.DRIVE, 20f, out) // 50초 동안 D: 다시 P가 되는 틱까지 278m
        t = d.feed(t, 1, Gear.PARK, 0f, out)
        assertTrue(out.isEmpty())
        assertEquals("같은 정차", ParkState(t0, doorSeen = false), d.parkState)
    }

    @Test
    fun creepingMoreThanThreeHundredMetresEndsTheStopWithoutWaitingForTheWindow() {
        // 정체 속에서 P로 섰다가 시속 20km로 기어간다: 3분 창도 시속 30km도 넘지 않았지만 300m를 넘는 틱(55초쯤)에 정차가 확정된다
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, true)
        val left = t0 + 5 * min
        assertNull(d.onTick(left, Gear.DRIVE, 20f, false))
        var at = 0
        var done: ParkedStop? = null
        for (sec in 1..70) {
            done = d.onTick(left + sec * 1_000L, Gear.DRIVE, 20f, false)
            if (done != null) {
                at = sec
                break
            }
        }
        assertEquals("끝은 P에서 나온 때", ParkedStop(t0, left, doorOpened = true), done)
        assertTrue("300m를 넘는 때는 54~56초 ($at)", at in 54..56)
        // 그 뒤 다시 서면 새 정차다
        d.onTick(left + 90_000, Gear.PARK, 0f, false)
        assertEquals(ParkState(left + 90_000, doorSeen = false), d.parkState)
    }

    @Test
    fun theDistanceAddsUpOverSeveralMovesAndStartsAgainWithANewStop() {
        val d = StopDetector()
        val out = mutableListOf<ParkedStop>()
        d.onTick(t0, Gear.PARK, 0f, false)
        var t = d.feed(t0 + 6 * min - 1_000, 54, Gear.DRIVE, 10f, out) // 150m
        t = d.feed(t, 5 * 60, Gear.PARK, 0f, out)
        assertEquals("150m 옮김: 같은 정차", ParkState(t0, doorSeen = false), d.parkState)
        val secondLeave = t + 1_000
        t = d.feed(t, 72, Gear.DRIVE, 10f, out) // 200m를 더해 350m
        assertEquals("두 번째 옮김에서 300m를 넘는 순간 앞 정차가 확정된다", listOf(ParkedStop(t0, secondLeave, false)), out)
        t = d.feed(t, 1, Gear.PARK, 0f, out)
        val newStop = t
        assertEquals("새 정차", ParkState(newStop, doorSeen = false), d.parkState)
        // 새 정차의 거리는 0부터: 100m 옮긴 것은 다시 대기다
        t = d.feed(t, 5 * 60, Gear.PARK, 0f, out)
        t = d.feed(t, 36, Gear.DRIVE, 10f, out)
        d.feed(t, 1, Gear.PARK, 0f, out)
        assertEquals(ParkState(newStop, doorSeen = false), d.parkState)
        assertEquals(1, out.size)
    }

    @Test
    fun anUnknownSpeedCountsAsThirtyKilometresAnHour() {
        // 차속을 모르는 동안은 다시 대기로 볼 수 있는 가장 빠른 속도로 갔다고 본다: 같은 정차라고 확인할 수 있을 때만 이어 센다
        val within = StopDetector()
        val out = mutableListOf<ParkedStop>()
        within.onTick(t0, Gear.PARK, 0f, false)
        var t = within.feed(t0 + 10 * min - 1_000, 30, Gear.DRIVE, null, out) // 250m로 본다
        within.feed(t, 1, Gear.PARK, 0f, out)
        assertEquals(ParkState(t0, doorSeen = false), within.parkState)

        val beyond = StopDetector()
        beyond.onTick(t0, Gear.PARK, 0f, false)
        t = beyond.feed(t0 + 10 * min - 1_000, 40, Gear.DRIVE, null, out) // 333m로 본다
        assertEquals(listOf(ParkedStop(t0, t0 + 10 * min, false)), out)
        beyond.feed(t, 1, Gear.PARK, 0f, out)
        assertEquals(ParkState(t + 1_000, doorSeen = false), beyond.parkState)
    }

    @Test
    fun speedOnTheTickTheCarParksAgainIsNoise() {
        // 다시 P가 된 틱에 함께 온 차속은 다시 대기의 최고 차속에 넣지 않는다 (P에서는 바퀴가 잠긴다, 엔진과 같다)
        val d = StopDetector()
        d.onTick(t0, Gear.PARK, 0f, false)
        d.onTick(t0 + 10 * min, Gear.DRIVE, SLOW, false)
        assertNull(d.onTick(t0 + 10 * min + 30_000, Gear.PARK, 45f, false))
        assertEquals(ParkState(t0, doorSeen = false), d.parkState)
    }

    @Test
    fun aRestoredStopCanStillBeReparkedWithinTheWindowFromTheLastSeenTime() {
        // 옮겨 대는 사이에 앱이 다시 떴다 (마지막 맥박 20초 전에는 P, 지금은 D): 떠난 것으로 판정하지만 다시 대기 창은 마지막으로 본
        // 때부터 열려 있다. 보지 못한 20초는 차속을 모르는 것으로 센다 (167m)
        val now = t0 + 140_000
        val seen = now - 20_000
        val d = StopDetector()
        val out = mutableListOf<ParkedStop>()
        d.restore(ParkState(t0, doorSeen = true), now = now, lastSeenMs = seen)
        var t = d.feed(now - 1_000, 20, Gear.DRIVE, SLOW, out) // 첫 틱이 판정, 그 뒤 19초 동안 53m
        assertEquals("엔진에는 마지막으로 본 때 P에서 나왔다고 알린다", seen, d.takeRestoredEnd())
        assertTrue("아직 확정하지 않는다", out.isEmpty())
        t = d.feed(t, 1, Gear.PARK, 0f, out)
        assertEquals("같은 정차", ParkState(t0, doorSeen = true), d.parkState)
        assertEquals(
            "선 시간에는 옮긴 시간도 든다",
            ParkedStop(t0, t + 5 * min, doorOpened = true),
            d.onTick(t + 5 * min, Gear.DRIVE, FAST, false),
        )
    }

    @Test
    fun aRestoredStopLeftSlowlyIsRecordedWhenTheWindowClosesOrAtOnceWhenItAlreadyHas() {
        // 보지 못한 사이가 40초면 시속 30km로 333m다: 판정하는 틱에 확정한다
        val far = StopDetector()
        far.restore(ParkState(t0, doorSeen = false), now = t0 + 5 * min + 40_000, lastSeenMs = t0 + 5 * min)
        assertEquals(ParkedStop(t0, t0 + 5 * min, false), far.onTick(t0 + 5 * min + 40_000, Gear.DRIVE, SLOW, false))

        // 방금 본 정차에서 천천히 떠났다: 300m를 넘거나 3분이 지날 때 확정한다. 끝은 마지막으로 본 때다
        val near = StopDetector()
        val out = mutableListOf<ParkedStop>()
        near.restore(ParkState(t0, doorSeen = false), now = t0 + 10 * min)
        near.feed(t0 + 10 * min, 3 * 60, Gear.DRIVE, 2f, out)
        assertTrue("3분 창 안이고 100m다", out.isEmpty())
        near.feed(t0 + 13 * min, 1, Gear.DRIVE, 2f, out)
        assertEquals(listOf(ParkedStop(t0, t0 + 10 * min, false)), out)
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

    // ---------------------------------------------------------------- 되돌린 정차의 판정은 기어를 알 때 (서비스 닫힘 검증 N2)

    @Test
    fun aRestoredStopWaitsForAKnownGearAndThenEndsAtTheLastSeenTimeIfTheCarHasLeft() {
        // 서비스가 켜지면 첫 틱(1초)이 Car 연결(기어 구독)보다 먼저 올 수 있다. 예전에는 그 틱의 UNKNOWN을 '아직 서 있다'로 읽고
        // 꺼져 있던 시간을 정차에 더했다: 같은 상황이 신호 도착 순서에 따라 10분 정차 / 40분 정차로 갈렸다
        val d = StopDetector()
        d.restore(ParkState(t0, doorSeen = true), now = t0 + 40 * min, lastSeenMs = t0 + 10 * min)
        assertTrue(d.restorePending)
        for (s in 1..3) {
            assertNull("기어를 모르는 틱은 판정을 미룬다", d.onTick(t0 + 40 * min + s * 1_000, Gear.UNKNOWN, null, false))
            assertTrue(d.restorePending)
            assertEquals("그동안 정차는 되돌린 그대로다", ParkState(t0, doorSeen = true), d.parkState)
            assertNull(d.takeRestoredEnd())
        }
        assertEquals(
            "기어를 첫 틱에 알았을 때와 같다: 마지막으로 본 때 끝났다",
            ParkedStop(t0, t0 + 10 * min, doorOpened = true),
            d.onTick(t0 + 40 * min + 4_000, Gear.DRIVE, 60f, false),
        )
        assertFalse(d.restorePending)
        assertNull(d.parkState)
        assertEquals("엔진도 같은 시각에 떠난 것으로 맞춘다", t0 + 10 * min, d.takeRestoredEnd())
        assertNull("한 번만", d.takeRestoredEnd())
    }

    @Test
    fun aRestoredStopSeenStillInParkCountsTheUnseenTimeToo() {
        val d = StopDetector()
        d.restore(ParkState(t0, doorSeen = false), now = t0 + 40 * min, lastSeenMs = t0 + 10 * min)
        assertNull(d.onTick(t0 + 40 * min + 1_000, Gear.UNKNOWN, null, false))
        assertNull(d.onTick(t0 + 40 * min + 2_000, Gear.PARK, 0f, false))
        assertFalse(d.restorePending)
        assertNull("서 있으면 끝난 시각이 없다", d.takeRestoredEnd())
        assertEquals(
            "못 본 30분도 서 있던 것이다",
            ParkedStop(t0, t0 + 45 * min, doorOpened = false),
            d.onTick(t0 + 45 * min, Gear.DRIVE, FAST, false),
        )
    }

    @Test
    fun aDoorOpenedWhileUndecidedBelongsToTheRestoredStop() {
        val left = StopDetector()
        left.restore(ParkState(t0, doorSeen = false), now = t0 + 20 * min, lastSeenMs = t0 + 5 * min)
        left.onTick(t0 + 20 * min + 1_000, Gear.UNKNOWN, null, doorOpened = true)
        assertTrue(left.onTick(t0 + 20 * min + 2_000, Gear.DRIVE, FAST, false)!!.doorOpened)
        val stayed = StopDetector()
        stayed.restore(ParkState(t0, doorSeen = false), now = t0 + 20 * min, lastSeenMs = t0 + 5 * min)
        stayed.onTick(t0 + 20 * min + 1_000, Gear.UNKNOWN, null, doorOpened = true)
        assertEquals(ParkState(t0, doorSeen = true), stayed.parkState)
        stayed.onTick(t0 + 20 * min + 2_000, Gear.PARK, 0f, false)
        assertTrue(stayed.onTick(t0 + 25 * min, Gear.DRIVE, FAST, false)!!.doorOpened)
    }

    @Test
    fun anUnknownGearWithSpeedMeansTheCarHasLeft() {
        val d = StopDetector()
        d.restore(ParkState(t0, doorSeen = true), now = t0 + 40 * min, lastSeenMs = t0 + 10 * min)
        assertEquals(ParkedStop(t0, t0 + 10 * min, true), d.onTick(t0 + 40 * min + 1_000, Gear.UNKNOWN, 50f, false))
        assertEquals(t0 + 10 * min, d.takeRestoredEnd())
    }

    @Test
    fun aGearThatArrivesElevenSecondsAfterARestartStillContinuesThePark() {
        // 2026-11-12 에뮬레이터: Car 서비스가 죽었다 살아난 뒤 프로세스 시작 11초 만에 기어가 왔다. 기다림이 10초였을 때는
        // 아직 P인데 0.8초 차이로 '떠났다'고 보고 화장실 아닌 10분 정차를 실제 기록에 썼다. 기어가 오면 그 값으로 정한다
        val d = StopDetector()
        val now = t0 + 40 * min
        d.restore(ParkState(t0, doorSeen = false), now = now, lastSeenMs = t0 + 10 * min)
        for (s in 1..10) assertNull(d.onTick(now + s * 1_000L, Gear.UNKNOWN, null, false))
        assertTrue(d.restorePending)
        assertNull("11초에 온 P: 그동안도 서 있었다", d.onTick(now + 11_000, Gear.PARK, 0f, false))
        assertFalse(d.restorePending)
        assertNull(d.takeRestoredEnd())
        assertEquals(t0, d.parkState?.since)
    }

    @Test
    fun withoutAGearForTheWholeWaitTheRestoredStopEndsAtTheLastSeenTime() {
        // 기어 신호가 끝내 오지 않는다 (Car 연결 실패): 보지 못한 시간을 선 것으로 세지 않는다. 코치도 더 기다리지 않고 엔진을 돌린다
        val d = StopDetector()
        val now = t0 + 40 * min
        d.restore(ParkState(t0, doorSeen = true), now = now, lastSeenMs = t0 + 10 * min)
        assertNull(d.onTick(now + StopDetector.RESTORE_WAIT_MS - 1_000, Gear.UNKNOWN, null, false))
        assertTrue(d.restorePending)
        assertEquals(ParkedStop(t0, t0 + 10 * min, true), d.onTick(now + StopDetector.RESTORE_WAIT_MS, Gear.UNKNOWN, null, false))
        assertFalse(d.restorePending)
        assertEquals(t0 + 10 * min, d.takeRestoredEnd())
        // 그 뒤 P 신호가 오면 그때부터 새 정차다
        val later = now + StopDetector.RESTORE_WAIT_MS + 10_000
        d.onTick(later, Gear.PARK, 0f, false)
        assertEquals(ParkState(later, doorSeen = false), d.parkState)
    }

    @Test
    fun restoringAgainStartsANewDecision() {
        val d = StopDetector()
        d.restore(ParkState(t0, doorSeen = false), now = t0 + 10 * min, lastSeenMs = t0 + 5 * min)
        d.onTick(t0 + 10 * min + 1_000, Gear.DRIVE, FAST, false)
        d.restore(null, now = t0 + 11 * min) // 세계 전환: 지난 판정은 지난 세계의 것이다
        assertNull(d.takeRestoredEnd())
        assertFalse(d.restorePending)
    }

    @Test
    fun withoutALastSeenTimeTheStopEndsAtTheRestore() {
        val d = StopDetector()
        d.restore(ParkState(t0, doorSeen = false), now = t0 + 10 * min)
        assertEquals(ParkedStop(t0, t0 + 10 * min, false), d.onTick(t0 + 10 * min + 1_000, Gear.DRIVE, FAST, false))
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
