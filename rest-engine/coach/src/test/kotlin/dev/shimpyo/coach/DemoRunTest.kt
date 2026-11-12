package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.WorldClock
import dev.shimpyo.coach.fakes.seededEngines
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.RouteState
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
    fun stopScenarioIsALunchStopAtAnseongThenRunsTenTimesFaster() {
        // 예전에는 시각 지정이 없어 실제 시각을 따랐다: 새벽에 시연하면 '깬 지 18시간+'·'졸음이 오기 쉬운 시간' 같은 밤 문구가
        // 나왔다(2026-11-13 캡처). 정차 시연은 점심때 안성휴게소에서 쉬는 장면이다 (식사 정차 보정도 보인다)
        val clock = WorldClock(wallStart)
        val run = checkNotNull(start("stop", clock))
        val expected = ZonedDateTime.of(2026, 11, 12, 12, 40, 0, 0, seoul).toInstant().toEpochMilli()
        assertEquals(expected, run.now())
        clock.advance(1_000)
        assertEquals(expected + 10_000, run.now())
        assertEquals("정차 시연은 움직이지 않는다", 0.0, run.travelledM(), 0.0)
    }

    @Test
    fun eachDrivingDemoNamesItsDestination() {
        // 시연 경로에 목적지 이름이 없으면 계획 화면이 '목적지 없음 · 남은 300km'라고 했다 (2026-11-13 캡처)
        val clock = WorldClock(wallStart)
        assertEquals("부산", checkNotNull(start("holiday", clock)).scenario.destinationName)
        assertEquals("부산", checkNotNull(start("stop", clock)).scenario.destinationName)
        assertEquals("대구", checkNotNull(start("fatigue", clock)).scenario.destinationName)
        assertEquals("도심 주행은 목적지 없이", null, checkNotNull(start("urgent", clock)).scenario.destinationName)
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

    // ------------------------------------------------------------ 경로 시간 (UI 감사 U2)

    /**
     * 엔진이 경로 상태에서 구하는 정체 밖 자유 속도 (RouteContext.prepare와 같은 식):
     * (남은 거리 − 경로 안 정체 길이) ÷ (남은 시간 − 경로 안 정체 통과 시간). 정체는 [0, 남은 거리]로 자른다
     */
    private fun freeSpeedMps(route: RouteState): Double {
        val remain = route.remainDistanceM!!
        var jamM = 0.0
        var jamS = 0.0
        for (j in route.congestion) {
            val from = maxOf(j.startDistanceM, 0.0)
            val to = minOf(j.startDistanceM + j.lengthM, remain)
            if (to <= from) continue
            jamM += to - from
            jamS += j.durationS * (to - from) / j.lengthM
        }
        return (remain - jamM) / (route.remainTimeS!! - jamS)
    }

    @Test
    fun routeTimeIsTheScenarioSpeedOutsideTheJamsPlusTheJams() {
        // 예전: 남은 시간 = 남은 거리 ÷ 25m/s (시나리오와 무관). 엔진은 거기서 정체 몫(귀성길 1시간)을 빼고 자유 속도를 구하므로
        // 시속 119km가 나왔고 화면이 "옥산휴게소 14km · 7분"을 보였다. 시나리오의 가상 속도는 실제 1초에 가는 거리 ÷ 시각 배속이다
        val expected = mapOf("holiday" to 100 / 3.6, "fatigue" to 90 / 3.6, "urgent" to 8.0, "stop" to 90 / 3.6)
        for ((name, speed) in expected) {
            // 정체 전, 정체 안(귀성길: 실제 97~197초), 정체를 지난 뒤
            for (afterS in listOf(0L, 30L, 90L, 150L, 400L)) {
                val clock = WorldClock(wallStart)
                val run = checkNotNull(start(name, clock))
                clock.advance(afterS * 1_000)
                val route = run.apply(CoachInput(now = run.now(), gear = Gear.DRIVE)).route!!
                assertEquals("$name ${afterS}초 뒤 엔진이 구하는 자유 속도 (m/s)", speed, freeSpeedMps(route), 1e-6)
            }
        }
    }

    @Test
    fun theHolidayRouteTimeCountsTheJamOnce() {
        val clock = WorldClock(wallStart)
        val run = checkNotNull(start("holiday", clock))
        val jam = run.scenario.congestion.single()
        fun route() = run.apply(CoachInput(now = run.now(), gear = Gear.DRIVE)).route!!
        // 출발: (310km − 정체 길이) ÷ 시속 100km + 정체 1시간
        assertEquals((310_000.0 - jam.lengthM) / (100 / 3.6) + 3_600.0, route().remainTimeS!!, 1e-6)
        // 정체 한가운데: 남은 정체는 절반만 센다
        clock.advance(((jam.startM + jam.lengthM / 2) / 200.0 * 1_000).toLong())
        val mid = route()
        val left = jam.startM + jam.lengthM - run.travelledM()
        assertEquals((mid.remainDistanceM!! - left) / (100 / 3.6) + 3_600.0 * left / jam.lengthM, mid.remainTimeS!!, 1e-6)
        // 정체를 지난 뒤: 정체 몫이 없다
        clock.advance(200_000)
        val after = route()
        assertTrue(after.congestion.isEmpty())
        assertEquals(after.remainDistanceM!! / (100 / 3.6), after.remainTimeS!!, 1e-6)
    }

    @Test
    fun theEngineArrivesAtTheScenarioSpeed() {
        // 엔진까지: 귀성길의 옥산휴게소(정체 앞)까지 엔진이 계산한 도착 시간 = 경로 거리 ÷ 시속 100km ("14km · 8분", 예전 7분)
        val clock = WorldClock(wallStart)
        val run = checkNotNull(start("holiday", clock))
        val world = CoachWorld.Demo(run)
        val engine = world.newEngine(seededEngines(), FakeSettings(occupants = listOf(DRIVER)), PersonalMemory(), run.anchor)
        val restArea = Facilities(restroom = true, food = true)
        val candidates = listOf(oksan.copy(facilities = restArea), jukam.copy(facilities = restArea))
        var checked = 0
        repeat(60) {
            val out = engine.update(world.apply(CoachInput(now = run.now(), gear = Gear.DRIVE, speedKph = 100f, candidates = world.candidates(candidates))))
            val next = out.next?.stop
            if (next?.id == "oksan" && next.routeDistanceM!! > 1_000.0) {
                assertEquals("옥산휴게소 ${next.routeDistanceM!!.toInt()}m", next.routeDistanceM!! / (100 / 3.6), next.etaS!!, 1.0)
                checked++
            }
            clock.advance(1_000)
        }
        assertTrue("옥산휴게소를 추천한 틱이 있어야 한다", checked > 0)
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
}
