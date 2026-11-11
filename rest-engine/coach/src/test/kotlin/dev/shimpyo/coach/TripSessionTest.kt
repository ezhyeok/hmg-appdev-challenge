package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.InMemoryTripStore
import dev.shimpyo.coach.fakes.KID
import dev.shimpyo.coach.fakes.WorldClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * TripSession: 여정 기준점의 수명 (서비스 감사 D8·D12, 구조 리뷰 B7). 예전 서비스는 여정을 서비스 시작 때 한 번 정하고
 * 바꾸지 않았다: 주행 중 재시작하면 피로가 0부터, 하루를 넘겨 살아 있으면 어제 기상 시각, 나중에 탄 사람은 화장실 'NOW'.
 */
class TripSessionTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val seoul = ZoneId.of("Asia/Seoul")
    /** 2026-11-11 09:00 KST */
    private val t0 = Instant.parse("2026-11-11T00:00:00Z").toEpochMilli()

    private fun kst(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, seoul).toInstant().toEpochMilli()

    private fun open(clock: WorldClock, store: InMemoryTripStore, history: List<StopLog> = emptyList(), saved: TripHeartbeat? = store.stored) =
        TripSession.open(saved, history, clock.now(), clock, store)

    private fun TripSession.drive(clock: WorldClock, minutes: Int, stepMs: Long = 5_000L) {
        repeat((minutes * min / stepMs).toInt()) {
            clock.advance(stepMs)
            onTick(clock.now(), null)
        }
    }

    /** P로 [minutes]분. [doorAfterMin]분째에 문이 열린다. 돌려준 값은 마지막 정차 상태 */
    private fun TripSession.park(clock: WorldClock, minutes: Int, doorAfterMin: Int? = null, since: Long = clock.now(), stepMs: Long = 5_000L): ParkState {
        var p = ParkState(since, doorSeen = doorAfterMin != null && clock.now() - since >= doorAfterMin * min)
        onTick(clock.now(), p)
        repeat((minutes * min / stepMs).toInt()) {
            clock.advance(stepMs)
            if (doorAfterMin != null && !p.doorSeen && clock.now() - since >= doorAfterMin * min) p = p.copy(doorSeen = true)
            onTick(clock.now(), p)
        }
        return p
    }

    private fun heartbeat(savedAt: Long, continuous: Double = 50.0, park: ParkState? = null, tripStart: Long = t0, restroom: Long = t0 - 30 * min) =
        TripHeartbeat(savedAt, tripStart, mapOf("driver" to restroom), restroom, continuous, park)

    // ------------------------------------------------------------ 시작

    @Test
    fun firstStartIsANewTripWithTheRestroomUnknownSixtyMinutesBack() {
        val clock = WorldClock(t0)
        val store = InMemoryTripStore()
        val s = open(clock, store)
        assertEquals(TripOrigin.NEW, s.origin)
        assertEquals(t0, s.anchor.tripStart)
        assertEquals("모르면 출발 60분 전 (AI §9-1)", t0 - 60 * min, s.anchor.lastRestroomFor(listOf(DRIVER))["driver"])
        assertEquals(0.0, s.anchor.continuousDrivingMin, 0.0)
        assertNull(s.restoredPark)
        s.onTick(clock.now(), null)
        assertEquals("첫 틱에 바로 저장한다 (1분 안에 재시작해도 이어지게)", 1, store.saves.size)
    }

    @Test
    fun stickyRestartMidDriveResumesTheTripAndItsFatigue() {
        val clock = WorldClock(t0)
        val store = InMemoryTripStore()
        open(clock, store).drive(clock, 100)
        val hb = checkNotNull(store.stored)
        assertTrue("1분 안에 저장했다", clock.now() - hb.savedAt < min)
        clock.advance(20_000) // 프로세스 재시작
        val s = open(clock, store)
        assertEquals(TripOrigin.RESUMED, s.origin)
        assertEquals("D8: 같은 여정 (기상 추정·여정 정체성)", t0, s.anchor.tripStart)
        assertEquals("D8: 피로가 0부터 다시 세지 않는다. 재시작 동안도 주행", hb.continuousDrivingMin + (clock.now() - hb.savedAt) / 60_000.0, s.anchor.continuousDrivingMin, 1e-9)
        assertTrue(s.anchor.continuousDrivingMin >= 100.0)
        assertEquals("마지막 화장실도 이어진다", t0 - 60 * min, s.anchor.restroomFallbackAt)
        assertNull(s.restoredPark)
    }

    @Test
    fun restartWhileParkedRestoresTheParkAndItsDoor() {
        val clock = WorldClock(t0)
        val store = InMemoryTripStore()
        val s1 = open(clock, store)
        s1.drive(clock, 60)
        val parkedAt = clock.now()
        s1.park(clock, minutes = 2, doorAfterMin = 1)
        val hb = checkNotNull(store.stored)
        assertEquals("정차 시작·문 열림은 바로 저장한다", ParkState(parkedAt, doorSeen = true), hb.park)
        clock.advance(30_000)
        val s2 = open(clock, store)
        assertEquals(TripOrigin.RESUMED, s2.origin)
        assertEquals("D12: 정차가 이어진다 (감지기·엔진에 되돌린다)", ParkState(parkedAt, doorSeen = true), s2.restoredPark)
        assertEquals("정차 중에는 늘지 않는다", 60.0, s2.anchor.continuousDrivingMin, 0.1)
        assertEquals("아직 3분이 안 됐다", t0 - 60 * min, s2.anchor.restroomFallbackAt)
        s2.park(clock, minutes = 1, since = parkedAt, doorAfterMin = 0)
        assertEquals("재시작을 넘어 3분: 화장실 정차", parkedAt + 3 * min, s2.anchor.restroomFallbackAt)
    }

    @Test
    fun aSavedParkIsCreditedOnlyAsFarAsTheLastHeartbeatSawIt() {
        // 12분 전 P(문 열림), 2분 뒤 맥박을 쓰고 꺼졌다가 10분 뒤 다시 켜졌다. 예전에는 '지금까지 서 있었다'로 세어 화장실 시계를
        // 정차 시작 + 3분으로 당겼다. 차가 그 사이 떠났으면 그 정차는 2분짜리라 기록에도 없다: 게이지와 기록이 어긋났다
        // (서비스 닫힘 검증 N3). 맥박이 본 만큼만 세고, 차가 지금도 P면 첫 틱이 이어 센다
        val now = t0 + 3 * hour
        val clock = WorldClock(now)
        val since = now - 12 * min
        val hb = heartbeat(savedAt = now - 10 * min, continuous = 80.0, park = ParkState(since, doorSeen = true), restroom = t0)
        val s = TripSession.open(hb, emptyList(), now, clock, InMemoryTripStore())
        assertEquals(TripOrigin.RESUMED, s.origin)
        assertEquals(ParkState(since, doorSeen = true), s.restoredPark)
        assertEquals("본 것은 2분뿐이다: 화장실 정차로 세지 않는다", t0, s.anchor.restroomFallbackAt)
        assertEquals("휴식도 아니다", 80.0, s.anchor.continuousDrivingMin, 0.0)

        // (a) 차가 떠나 있었다: 정차는 마지막 맥박 때 끝났다
        clock.advance(1_000)
        s.onTick(clock.now(), null)
        assertEquals(t0, s.anchor.restroomFallbackAt)

        // (b) 차가 아직 P다: 못 본 10분도 서 있던 것이다
        val clock2 = WorldClock(now)
        val s2 = TripSession.open(hb, emptyList(), now, clock2, InMemoryTripStore())
        clock2.advance(1_000)
        s2.onTick(clock2.now(), ParkState(since, doorSeen = true))
        assertEquals("문 열림 + 3분: 화장실 정차", since + 3 * min, s2.anchor.restroomFallbackAt)
        clock2.advance(4 * min)
        s2.onTick(clock2.now(), ParkState(since, doorSeen = true))
        assertEquals("16분: 휴식", 0.0, s2.anchor.continuousDrivingMin, 0.0)

        // 맥박이 이미 본 화장실 정차·휴식은 그대로 센다
        val seen = heartbeat(savedAt = now - 5 * min, continuous = 0.0, park = ParkState(now - 25 * min, doorSeen = true), restroom = t0)
        val s3 = TripSession.open(seen, emptyList(), now, WorldClock(now), InMemoryTripStore())
        assertEquals(now - 25 * min + 3 * min, s3.anchor.restroomFallbackAt)
        assertEquals(0.0, s3.anchor.continuousDrivingMin, 0.0)
    }

    @Test
    fun headUnitOffForABreakKeepsTheTripCountsARestAndForgetsTheRestroom() {
        val clock = WorldClock(t0)
        val now = t0 + 3 * hour
        clock.wallMs = now
        // 달리던 중 마지막 맥박 뒤 30분 동안 꺼져 있었다 (시동 끔: 헤드유닛은 시동과 함께 꺼진다)
        val s = TripSession.open(heartbeat(savedAt = now - 30 * min, continuous = 90.0), emptyList(), now, clock, InMemoryTripStore())
        assertEquals(TripOrigin.RESUMED_AFTER_BREAK, s.origin)
        assertEquals("같은 여정", t0, s.anchor.tripStart)
        assertEquals("15분 넘게 서 있었다: 휴식", 0.0, s.anchor.continuousDrivingMin, 0.0)
        assertEquals("마지막 맥박부터 정차로 본다", ParkState(now - 30 * min, doorSeen = false), s.restoredPark)
        assertEquals("화장실은 모름: 60분 전 (저장값 t0-30분보다 늦다)", now - 60 * min, s.anchor.restroomFallbackAt)

        // 저장된 정차가 있으면 그 정차가 꺼진 동안에도 이어졌다
        val parked = ParkState(now - 25 * min, doorSeen = false)
        val s2 = TripSession.open(heartbeat(savedAt = now - 20 * min, park = parked), emptyList(), now, clock, InMemoryTripStore())
        assertEquals(TripOrigin.RESUMED_AFTER_BREAK, s2.origin)
        assertEquals(parked, s2.restoredPark)
        assertEquals(0.0, s2.anchor.continuousDrivingMin, 0.0)
    }

    @Test
    fun aLongParkStartsANewTrip() {
        val now = t0 + 10 * hour
        val clock = WorldClock(now)
        val long = TripSession.open(heartbeat(savedAt = now - 2 * min, park = ParkState(now - 4 * hour - 5 * min, true)), emptyList(), now, clock, null)
        assertEquals(TripOrigin.NEW, long.origin)
        assertEquals(now, long.anchor.tripStart)
        assertNull("새 여정은 옛 정차를 잇지 않는다", long.restoredPark)
        assertEquals(now - 60 * min, long.anchor.restroomFallbackAt)

        val offline = TripSession.open(heartbeat(savedAt = now - 5 * hour), emptyList(), now, clock, null)
        assertEquals("5시간 꺼져 있었다", TripOrigin.NEW, offline.origin)

        val almost = TripSession.open(heartbeat(savedAt = now - 2 * min, park = ParkState(now - 3 * hour - 59 * min, false)), emptyList(), now, clock, null)
        assertEquals("4시간 전이면 같은 여정 (정차는 휴식)", TripOrigin.RESUMED, almost.origin)
        assertEquals(t0, almost.anchor.tripStart)
        assertEquals(0.0, almost.anchor.continuousDrivingMin, 0.0)
    }

    // ------------------------------------------------------------ 살아 있는 동안

    @Test
    fun aLongParkStartsANewTripAtDeparture() {
        val clock = WorldClock(t0)
        val store = InMemoryTripStore()
        val s = open(clock, store)
        s.drive(clock, 60)
        s.park(clock, minutes = 5 * 60, doorAfterMin = 1, stepMs = 60_000L)
        assertEquals("서 있는 동안에는 여정을 바꾸지 않는다 (출발 시각이 새 여정 시작)", t0, s.anchor.tripStart)
        clock.advance(1_000)
        val departure = clock.now()
        val event = s.onTick(departure, null)
        val trip = (event as TripEvent.NewTrip).anchor
        assertEquals(departure, trip.tripStart)
        assertEquals(0.0, trip.continuousDrivingMin, 0.0)
        assertEquals("새 여정의 화장실은 모름 규칙", departure - 60 * min, trip.lastRestroomFor(listOf(DRIVER, KID))["kid"])
        assertEquals("새 여정은 바로 저장", departure, store.stored!!.tripStart)
    }

    @Test
    fun aParkShorterThanFourHoursIsARestNotANewTrip() {
        val clock = WorldClock(t0)
        val s = open(clock, InMemoryTripStore())
        s.drive(clock, 60)
        s.park(clock, minutes = 3 * 60, stepMs = 60_000L)
        clock.advance(1_000)
        assertNull(s.onTick(clock.now(), null))
        assertEquals(t0, s.anchor.tripStart)
        s.drive(clock, 10)
        assertEquals("휴식 뒤부터 다시 센다", 10.0, s.anchor.continuousDrivingMin, 0.1)
    }

    @Test
    fun dayRollover() {
        // (a) 자정을 넘겨 달리다 재시작: 같은 여정 (어제 아침부터 깨어 있었다)
        val start = kst(11, 23, 50)
        val clock = WorldClock(start)
        val store = InMemoryTripStore()
        open(clock, store).drive(clock, 13)
        clock.advance(30_000)
        val resumed = open(clock, store)
        assertEquals(TripOrigin.RESUMED, resumed.origin)
        assertEquals(start, resumed.anchor.tripStart)

        // (b) 밤새 세워 두고 아침에 출발: 새 여정, 시작 날짜가 다음 날이다 (기상 추정이 오늘 아침으로)
        val evening = WorldClock(kst(11, 20, 0))
        val s = open(evening, InMemoryTripStore())
        s.drive(evening, 120)
        s.park(evening, minutes = 9 * 60 + 30, stepMs = 60_000L)
        val event = s.onTick(evening.now(), null) as TripEvent.NewTrip
        assertEquals(LocalDate.of(2026, 11, 12), Instant.ofEpochMilli(event.anchor.tripStart).atZone(seoul).toLocalDate())

        // (c) 어젯밤 맥박으로 오늘 아침 시작: 새 여정
        val morning = kst(12, 8, 0)
        val next = TripSession.open(heartbeat(savedAt = kst(11, 22, 0), tripStart = kst(11, 20, 0)), emptyList(), morning, WorldClock(morning), null)
        assertEquals(TripOrigin.NEW, next.origin)
        assertEquals(morning, next.anchor.tripStart)
    }

    @Test
    fun restroomStopsMoveLastRestroomForwardForEveryoneIncludingLaterOccupants() {
        val clock = WorldClock(t0)
        val store = InMemoryTripStore()
        val s = open(clock, store)
        s.drive(clock, 90)
        val stopStart = clock.now() - 20 * min
        val saves = store.saves.size
        s.onStop(StopLog(start = stopStart, end = clock.now(), lat = null, lon = null, restroom = true, name = "안성휴게소"), clock.now())
        val expected = stopStart + 3 * min
        assertEquals("정차 시작 + 3분 (엔진이 잡는 시각의 하한)", mapOf("driver" to expected, "kid" to expected), s.anchor.lastRestroomFor(listOf(DRIVER, KID)))
        assertEquals("바로 저장", saves + 1, store.saves.size)

        s.onStop(StopLog(clock.now() - 5 * min, clock.now(), null, null, restroom = false), clock.now())
        s.onStop(StopLog(stopStart - 60 * min, stopStart - 40 * min, null, null, restroom = true), clock.now())
        assertEquals("화장실 아닌 정차·더 옛날 정차로 뒤로 가지 않는다", expected, s.anchor.restroomFallbackAt)
        s.onStop(StopLog(clock.now() + 3 * hour, clock.now() + 4 * hour, null, null, restroom = true), clock.now())
        assertEquals("미래 시각 기록(다른 세계의 시계)은 무시", expected, s.anchor.restroomFallbackAt)
    }

    @Test
    fun aRestroomStopWhileStillParkedIsAnchoredAndSavedAtOnce() {
        val clock = WorldClock(t0)
        val store = InMemoryTripStore()
        val s = open(clock, store)
        s.drive(clock, 30)
        val since = clock.now()
        s.park(clock, minutes = 4, doorAfterMin = 1)
        assertEquals(since + 3 * min, s.anchor.restroomFallbackAt)
        assertEquals("출발 전에 재시작해도 남게 바로 저장 (D12)", since + 3 * min, store.stored!!.restroomFallbackAt)
    }

    @Test
    fun futureDatedOrBrokenHistoryNeverBecomesTheLastRestroom() {
        val now = t0
        val demoLog = StopLog(start = now + 4 * hour, end = now + 4 * hour + 20 * min, lat = null, lon = null, restroom = true, name = "옥산휴게소")
        val mixed = StopLog(start = now - 10 * min, end = now - 3 * hour, lat = null, lon = null, restroom = true)
        val huge = StopLog(start = now - 30 * hour, end = now - 20 * min, lat = null, lon = null, restroom = true)
        val real = StopLog(start = now - 30 * min, end = now - 10 * min, lat = null, lon = null, restroom = true)
        assertEquals(listOf(real), StopHistory.sanitize(listOf(demoLog, mixed, huge, real), now))

        val s = TripSession.open(null, listOf(demoLog, mixed, huge), now, WorldClock(now), null)
        assertEquals("시연 시각 기록은 마지막 화장실이 되지 않는다", now - 60 * min, s.anchor.restroomFallbackAt)
        val s2 = TripSession.open(null, listOf(demoLog, real), now, WorldClock(now), null)
        assertEquals("최근 실제 화장실 정차는 쓴다", real.start + 3 * min, s2.anchor.restroomFallbackAt)
    }

    @Test
    fun anUntrustworthyHeartbeatStartsANewTrip() {
        val now = t0 + 5 * hour
        val clock = WorldClock(now)
        fun origin(h: TripHeartbeat) = TripSession.open(h, emptyList(), now, clock, null).origin
        assertEquals("저장 시각이 미래", TripOrigin.NEW, origin(heartbeat(savedAt = now + 10 * min)))
        assertEquals("출발이 저장 뒤", TripOrigin.NEW, origin(heartbeat(savedAt = now - min, tripStart = now + hour)))
        assertEquals("깨진 값", TripOrigin.NEW, origin(heartbeat(savedAt = now - min, continuous = Double.NaN)))
        assertEquals("미래의 화장실", TripOrigin.NEW, origin(heartbeat(savedAt = now - min, restroom = now + hour)))
        assertEquals("다른 판", TripOrigin.NEW, origin(heartbeat(savedAt = now - min).copy(version = 99)))
        val slightlyAhead = TripSession.open(heartbeat(savedAt = now + 30_000, continuous = 40.0), emptyList(), now, clock, null)
        assertEquals("NTP로 조금 앞선 맥박은 믿는다", TripOrigin.RESUMED, slightlyAhead.origin)
        assertEquals(40.0, slightlyAhead.anchor.continuousDrivingMin, 0.0)
    }

    @Test
    fun heartbeatIsSavedAtMostOnceAMinuteWhileNothingHappens() {
        val clock = WorldClock(t0)
        val store = InMemoryTripStore()
        val s = open(clock, store)
        s.drive(clock, 10, stepMs = 1_000L)
        assertEquals(10, store.saves.size)
        assertTrue(store.saves.zipWithNext().all { (a, b) -> b.savedAt - a.savedAt >= min })
        clock.advance(1_000)
        s.onTick(clock.now(), ParkState(clock.now(), false))
        assertEquals("정차 시작은 기다리지 않고 저장", 11, store.saves.size)
    }

    @Test
    fun continuousDrivingFollowsTheEngineRules() {
        val clock = WorldClock(t0)
        val s = open(clock, InMemoryTripStore())
        s.drive(clock, 30)
        clock.advance(10 * min) // 앱이 멈췄다 (또는 시연 중 실제 엔진이 멈춰 있었다)
        s.onTick(clock.now(), null)
        assertEquals("틱 사이가 길면 2분만 센다 (엔진 maxUpdateGapS)", 32.0, s.anchor.continuousDrivingMin, 0.01)
        s.park(clock, minutes = 10)
        assertEquals("정차 중에는 늘지 않는다", 32.0, s.anchor.continuousDrivingMin, 0.01)
        s.drive(clock, 1)
        assertEquals("10분 정차는 휴식이 아니다", 33.0, s.anchor.continuousDrivingMin, 0.01)
        clock.jumpWall(-hour)
        s.onTick(clock.now(), null)
        assertEquals("시계가 뒤로 가도 빼지 않는다", 33.0, s.anchor.continuousDrivingMin, 0.01)
        s.park(clock, minutes = 15)
        assertEquals("15분 정차는 휴식", 0.0, s.anchor.continuousDrivingMin, 0.0)
    }

    // ------------------------------------------------------------ 들르기로 한 곳 (서비스 닫힘 검증 N7)

    @Test
    fun theAcceptedStopIsSavedAtOnceAndResumedOnlyWithoutABreak() {
        // 주행 중 재시작하면 엔진의 '들르기로 한 곳'이 사라져 방금 답한 정차를 다시 물을 수 있었다. 맥박에 담아 이어받는다
        val clock = WorldClock(t0)
        val store = InMemoryTripStore()
        val s = open(clock, store)
        s.drive(clock, 30)
        val saves = store.saves.size
        val at = clock.now()
        s.onAccepted("kex:oksan", at)
        assertEquals("바로 저장한다", saves + 1, store.saves.size)
        assertEquals(AcceptedStop("kex:oksan", at), store.stored!!.accepted)

        clock.advance(20_000) // 프로세스 재시작
        val resumed = open(clock, store)
        assertEquals(TripOrigin.RESUMED, resumed.origin)
        assertEquals(AcceptedStop("kex:oksan", at), resumed.restoredAccepted)
        assertEquals("다음 맥박에도 남는다", AcceptedStop("kex:oksan", at), resumed.heartbeat(clock.now()).accepted)

        clock.advance(20 * min) // 15분 넘게 꺼져 있었다: 그사이 섰던 것이다
        val afterBreak = open(clock, store)
        assertEquals(TripOrigin.RESUMED_AFTER_BREAK, afterBreak.origin)
        assertNull(afterBreak.restoredAccepted)
        assertNull(afterBreak.accepted)
    }

    @Test
    fun theAcceptedStopEndsWithARestroomStopARestANewTripOrNinetyMinutes() {
        fun session(clock: WorldClock): TripSession = open(clock, InMemoryTripStore()).also {
            it.drive(clock, 10)
            it.onAccepted("kex:oksan", clock.now())
        }
        fun stop(clock: WorldClock, minutes: Int, restroom: Boolean) =
            StopLog(clock.now() - minutes * min, clock.now(), null, null, restroom = restroom)

        WorldClock(t0).let { c -> session(c).let { s -> s.onStop(stop(c, 5, restroom = true), c.now()); assertNull("화장실 정차", s.accepted) } }
        WorldClock(t0).let { c -> session(c).let { s -> s.onStop(stop(c, 16, restroom = false), c.now()); assertNull("휴식", s.accepted) } }
        WorldClock(t0).let { c ->
            session(c).let { s ->
                s.onStop(stop(c, 5, restroom = false), c.now())
                assertEquals("문도 열지 않은 5분 정차는 그곳에 들른 것이 아니다 (엔진도 수락한 곳을 내리지 않는다)", "kex:oksan", s.accepted?.stopId)
            }
        }
        WorldClock(t0).let { c ->
            session(c).let { s ->
                s.park(c, minutes = 5 * 60, stepMs = 60_000L)
                c.advance(1_000)
                assertTrue(s.onTick(c.now(), null) is TripEvent.NewTrip)
                assertNull("새 여정", s.accepted)
            }
        }
        WorldClock(t0).let { c ->
            val store = InMemoryTripStore()
            val s = open(c, store)
            s.onAccepted("kex:oksan", c.now())
            s.drive(c, 91)
            assertNull("90분이 지난 수락은 맥박에 담지 않는다 (엔진도 90분 뒤 내린다)", store.stored!!.accepted)
            c.advance(20_000)
            assertNull(open(c, store).restoredAccepted)
        }
    }

    @Test
    fun aHeartbeatWhoseAcceptedStopIsFromTheFutureIsNotTrusted() {
        val now = t0 + 5 * hour
        val hb = heartbeat(savedAt = now - min).copy(accepted = AcceptedStop("kex:oksan", now + hour))
        assertEquals(TripOrigin.NEW, TripSession.open(hb, emptyList(), now, WorldClock(now), null).origin)
        val old = heartbeat(savedAt = now - min).copy(accepted = AcceptedStop("kex:oksan", now - 2 * hour))
        val s = TripSession.open(old, emptyList(), now, WorldClock(now), null)
        assertEquals(TripOrigin.RESUMED, s.origin)
        assertNull("90분 넘은 수락은 이어받지 않는다", s.restoredAccepted)
    }

    @Test
    fun aSessionStartedFromAnAnchorKeepsCountingFromIt() {
        val clock = WorldClock(t0)
        val anchor = TripAnchor(t0 - 46 * min, mapOf("driver" to t0 - 46 * min), t0 - 46 * min, 46.0)
        val s = TripSession.started(anchor, t0, clock)
        s.drive(clock, 5)
        s.park(clock, minutes = 4, doorAfterMin = 0)
        s.flush(clock.now())
        assertEquals("시연 여정은 이어서 센다", 51.0, s.anchor.continuousDrivingMin, 0.1)
        assertEquals(t0 - 46 * min, s.anchor.tripStart)
    }
}
