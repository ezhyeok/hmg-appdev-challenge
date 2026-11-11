package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.WorldClock
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * DemoRun: 시연 한 번은 불변 값이다 (구조 리뷰 Q2/S2, 서비스 감사 P1).
 * 예전 DemoDirector는 scenario·startedAtReal·clockOffsetMs 세 필드를 따로 바꿔서, 다른 스레드가 새 시나리오와 옛 시작 시각을
 * 섞어 읽을 수 있었고, 날짜를 주입한 시계가 아니라 시스템 시계(LocalDate.now)로 정해 테스트가 날짜에 따라 달라졌다.
 */
class DemoRunTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    /** 2026-11-11 15:30 UTC = 2026-11-12 00:30 KST (UTC 날짜와 서울 날짜가 다르다) */
    private val wallStart = Instant.parse("2026-11-11T15:30:00Z").toEpochMilli()

    private fun start(name: String?, clock: WorldClock) = DemoRun.start(name, wallNow = clock.now(), elapsed = clock)

    private val oksan = StopCandidate("oksan", "옥산휴게소(부산방향)", StopKind.HIGHWAY_REST_AREA, 36.657743, 127.369817, facilities = Facilities(), parkable = true)
    private val jukam = StopCandidate("jukam", "죽암휴게소(부산방향)", StopKind.HIGHWAY_REST_AREA, 36.486810, 127.429261, facilities = Facilities(), parkable = true)

    @Test
    fun holidayStartsAtTwoPmOnTheSeoulDateOfTheInjectedClock() {
        val clock = WorldClock(wallStart)
        val run = checkNotNull(start("holiday", clock))
        val expected = ZonedDateTime.of(2026, 11, 12, 14, 0, 0, 0, seoul).toInstant().toEpochMilli()
        assertEquals("시연 시각은 주입한 실제 시각의 서울 날짜 14:00", expected, run.startedAt)
        assertEquals(expected, run.now())
    }

    @Test
    fun demoClockRunsAtTheScenarioTimeScale() {
        val clock = WorldClock(wallStart)
        val run = checkNotNull(start("holiday", clock))
        val t0 = run.now()
        clock.advance(10_000)
        assertEquals("귀성길은 7.2배속", t0 + 72_000, run.now())
        assertEquals("실제 1초에 200m", 2_000.0, run.travelledM(), 1e-9)
    }

    @Test
    fun stopScenarioStartsAtTheRealTimeWithoutAJumpThenRunsTenTimesFaster() {
        val clock = WorldClock(wallStart)
        val run = checkNotNull(start("stop", clock))
        assertEquals("시각 지정이 없으면 시작 순간은 실제 시각과 같다 (연속)", wallStart, run.now())
        clock.advance(1_000)
        assertEquals(wallStart + 10_000, run.now())
        assertEquals("정차 시연은 움직이지 않는다", 0.0, run.travelledM(), 0.0)
    }

    @Test
    fun wallClockJumpDoesNotMoveTheDemoCarOrItsClock() {
        // 예전에는 진행 거리와 시연 시각을 벽시계로 쟀다: NTP 보정 한 번에 시연 차가 순간 이동했다 (구조 리뷰 2.4)
        val clock = WorldClock(wallStart)
        val run = checkNotNull(start("holiday", clock))
        clock.advance(5_000)
        val now = run.now()
        val travelled = run.travelledM()
        clock.jumpWall(3_600_000)
        assertEquals(now, run.now())
        assertEquals(travelled, run.travelledM(), 0.0)
    }

    @Test
    fun noNameOrAnUnknownNameStartsNothing() {
        val clock = WorldClock(wallStart)
        assertNull(start(null, clock))
        assertNull(start("nope", clock))
        assertTrue(DemoScenario.NAMES.all { start(it, clock) != null })
    }

    @Test
    fun restartBeginsAgainFromTheScenarioStart() {
        val clock = WorldClock(wallStart)
        val first = checkNotNull(start("holiday", clock))
        val oksanAtStart = first.candidates(listOf(oksan)).single().routeDistanceM!!
        clock.advance(30_000)
        assertEquals(6_000.0, first.travelledM(), 1e-9)
        val second = checkNotNull(start("holiday", clock))
        assertEquals("다시 시작하면 처음 위치에서", 0.0, second.travelledM(), 0.0)
        assertEquals("다시 시작하면 다시 14:00", first.startedAt, second.startedAt)
        assertEquals("후보 거리도 새 시연 기준 (경로 위치 캐시가 시연마다 따로)", oksanAtStart, second.candidates(listOf(oksan)).single().routeDistanceM!!, 1e-6)
        assertEquals("앞선 시연은 그대로 흘러간다 (불변 값)", 6_000.0, first.travelledM(), 1e-9)
    }

    @Test
    fun anchorIsTheScenarioTripAtTheDemoStart() {
        val clock = WorldClock(wallStart)
        val holiday = checkNotNull(start("holiday", clock))
        val a = holiday.anchor
        assertEquals(holiday.startedAt - 46 * 60_000L, a.tripStart)
        assertEquals(46.0, a.continuousDrivingMin, 0.0)
        assertEquals(holiday.startedAt - 46 * 60_000L, a.restroomFallbackAt)
        assertEquals(mapOf("driver" to a.restroomFallbackAt, "kid" to a.restroomFallbackAt), a.lastRestroomFor(holiday.scenario.occupants))
        val fatigue = checkNotNull(start("fatigue", clock)).anchor
        assertEquals(95.0, fatigue.continuousDrivingMin, 0.0)
    }

    @Test
    fun positionStartsAtTheScenarioStart() {
        val clock = WorldClock(wallStart)
        val run = checkNotNull(start("holiday", clock))
        val p = run.position()
        assertEquals(36.7779, p.lat, 1e-9)
        assertEquals(127.2324, p.lon, 1e-9)
        assertNotNull("진행 방향이 있다", p.heading)
    }

    // ------------------------------------------------------------ 호환용 DemoDirector (옛 CoachService가 쓴다)

    @Test
    fun directorStartStopRestartIsConsistent() {
        var real = wallStart
        val director = DemoDirector { real }
        val started = checkNotNull(director.start("holiday"))
        assertTrue(director.active)
        assertEquals("holiday", director.name)
        assertFalse(director.oracleEnabled)
        assertEquals(2, started.occupants.size)
        assertEquals(46, started.drivenMin)
        val demoStart = director.now()
        assertTrue("시연 시각은 실제와 다르다", demoStart != real)

        assertNull("시연 끄기", director.start(null))
        assertFalse(director.active)
        assertNull(director.name)
        assertEquals("끄면 실제 시각", real, director.now())
        val input = CoachInput(now = real, gear = Gear.DRIVE)
        assertSame("끄면 입력을 그대로", input, director.apply(input))
        assertNull(director.position())

        real += 60_000
        checkNotNull(director.start("holiday"))
        assertEquals("다시 켜면 다시 14:00 (날짜만 실제 시각의 서울 날짜)", demoStart, director.now())
        checkNotNull(director.start("stop"))
        assertEquals(real, director.now())
    }

    @Test
    fun directorMatchesTheRunItWraps() {
        for (name in DemoScenario.NAMES) {
            var real = wallStart
            val director = DemoDirector { real }
            director.start(name)
            val run = checkNotNull(DemoRun.start(name, wallNow = real, elapsed = { real }))
            repeat(5) {
                assertEquals(name, run.now(), director.now())
                val pos = run.position()
                assertEquals(name, Triple(pos.lat, pos.lon, pos.heading), director.position())
                val input = CoachInput(now = real, gear = Gear.DRIVE, candidates = listOf(oksan, jukam))
                assertEquals(name, run.apply(input), director.apply(input))
                assertEquals(name, run.candidates(listOf(oksan, jukam)), director.candidates(listOf(oksan, jukam)))
                real += 1_000
            }
        }
    }
}
