package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.KID
import dev.shimpyo.coach.fakes.WorldClock
import dev.shimpyo.coach.fakes.seededEngines
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.CoachOutput
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * CoachWorld: 시연과 실제가 다른 것은 모두 세계가 정한다 (구조 리뷰 3.8, S11).
 * 실제 세계는 아무것도 바꾸지 않고(예전 demoOffLeavesInputsUntouched), 시연 세계는 DemoRun의 출력과 시연 정책을 낸다.
 */
class CoachWorldTest {
    private val min = 60_000L
    private val hour = 60 * min
    /** 2026-11-11 14:00 KST */
    private val clock = WorldClock(Instant.parse("2026-11-11T05:00:00Z").toEpochMilli())
    private val settings = FakeSettings(occupants = listOf(DRIVER, KID), sleepHours = 5f)
    private val real = CoachWorld.Real(clock)
    private fun demo(name: String = "holiday") = CoachWorld.Demo(checkNotNull(DemoRun.start(name, wallNow = clock.now(), elapsed = clock)))

    private val oksan = StopCandidate("oksan", "옥산휴게소(부산방향)", StopKind.HIGHWAY_REST_AREA, 36.657743, 127.369817, facilities = Facilities(), parkable = true)

    @Test
    fun realWorldIsIdentity() {
        val input = CoachInput(now = 1L, gear = Gear.DRIVE, route = RouteState(guiding = true, remainDistanceM = 1_000.0), candidates = listOf(oksan))
        assertSame(input, real.apply(input))
        val list = listOf(oksan)
        assertSame(list, real.candidates(list))
        val fix = Fix(37.0, 127.0, 90f)
        assertSame(fix, real.position(fix))
        assertNull(real.position(null))
        assertEquals(clock.now(), real.clock.now())
        assertEquals(listOf(DRIVER, KID), real.occupants(settings))
    }

    @Test
    fun realWorldPolicies() {
        assertNull(real.name)
        assertEquals(CandidatePolicy.REAL, real.candidatePolicy)
        assertTrue(real.oracleEnabled)
        assertTrue(real.persistsLearning)
        assertTrue(real.persistsHistory)
        assertTrue(real.persistsTrip)
        assertTrue(real.usesNaviLocationFallback)
    }

    @Test
    fun demoWorldPoliciesKeepTheDemoOutOfRealData() {
        val d = demo()
        assertEquals("holiday", d.name)
        assertEquals(CandidatePolicy.DEMO, d.candidatePolicy)
        assertFalse("시연은 requestTrip 대신 가상 경로 거리", d.oracleEnabled)
        assertFalse("서비스 감사 D15: 시연 학습은 memory.json에 쓰지 않는다", d.persistsLearning)
        assertFalse("구조 리뷰 B4: 시연 정차는 stops.json에 쓰지 않는다 (가상 시각 기록)", d.persistsHistory)
        assertFalse(d.persistsTrip)
        assertFalse("구조 리뷰 D3: 시연 중에는 내비 위치로 보충하지 않는다", d.usesNaviLocationFallback)
    }

    @Test
    fun demoOutputsComeFromTheRunAndTheRealPositionIsOnlyOverlaid() {
        val d = demo()
        clock.advance(10_000)
        val navi = RouteState(guiding = true, remainDistanceM = 383_000.0, nextManeuverDistanceM = 150.0)
        val input = CoachInput(now = d.clock.now(), gear = Gear.DRIVE, route = navi, candidates = listOf(oksan))
        assertEquals(d.run.apply(input), d.apply(input))
        assertEquals("시나리오 경로 (실제 내비 경로를 섞지 않는다)", 310_000.0 - 2_000.0, d.apply(input).route!!.remainDistanceM!!, 1e-6)
        assertEquals(d.run.candidates(listOf(oksan)), d.candidates(listOf(oksan)))
        val realFix = Fix(37.4, 127.1, 0f)
        assertEquals("시연 위치를 덧씌운다", d.run.position(), d.position(realFix))
        assertEquals("실제 위치를 몰라도 시연 위치", d.run.position(), d.position(null))
        assertEquals(d.run.now(), d.clock.now())
    }

    @Test
    fun occupantsComeFromTheWorld() {
        val d = demo()
        assertEquals("구조 리뷰 D9: 시연 탑승자 (설정이 아니라)", d.run.scenario.occupants, d.occupants(settings))
        val edited = d.withOccupants(listOf(DRIVER))
        assertEquals(listOf(DRIVER), edited.occupants(settings))
        assertSame("같은 시연, 같은 시각", d.run, edited.run)
        assertEquals("UI #15: 시연 탑승자는 설정에 쓰지 않는다", 0, settings.occupantWrites)
    }

    @Test
    fun profileIsBuiltFromTheAnchorInBothWorlds() {
        val anchor = TripAnchor(tripStart = 100L, lastRestroomAt = mapOf("driver" to 50L), restroomFallbackAt = 70L, continuousDrivingMin = 33.0)
        val p = real.profile(settings, anchor)
        assertEquals(listOf(DRIVER, KID), p.occupants)
        assertEquals(100L, p.tripStart)
        assertEquals("나중에 탄 아이는 여정이 아는 마지막 화장실", mapOf("driver" to 50L, "kid" to 70L), p.lastRestroomAt)
        assertEquals(33.0, p.continuousDrivingMin, 0.0)
        assertEquals("수면 시간은 설정에서", 5f, p.driverSleepHours)

        val d = demo()
        val dp = d.profile(settings, d.run.anchor)
        assertEquals(d.run.scenario.occupants, dp.occupants)
        assertEquals(d.run.anchor.tripStart, dp.tripStart)
        assertEquals(46.0, dp.continuousDrivingMin, 0.0)
        assertEquals(5f, dp.driverSleepHours)
        val baby = Occupant("baby", OccupantType.INFANT, label = "아기")
        val withBaby = d.withOccupants(d.run.scenario.occupants + baby).profile(settings, d.run.anchor)
        assertEquals("시연 중 탄 아기도 시연 여정의 마지막 화장실 ('NOW'가 아니다)", d.run.anchor.restroomFallbackAt, withBaby.lastRestroomAt["baby"])
        assertEquals("탑승자를 바꿔도 시연 여정 그대로 (D5)", dp.tripStart, withBaby.tripStart)
    }

    @Test
    fun newEngineReadsPersonalMemoryOnlyInTheRealWorld() {
        val learned = PersonalMemory(restroomIntervalsMin = mapOf("driver" to listOf(120.0)))
        val anchor = TripAnchor(clock.now(), emptyMap(), clock.now() - hour, 0.0)
        assertEquals(learned, real.newEngine(seededEngines(), settings, learned, anchor).memory())
        val d = demo()
        assertEquals("시연은 매번 빈 학습 상태로 같은 조건에서", PersonalMemory(), d.newEngine(seededEngines(), settings, learned, d.run.anchor).memory())
    }

    @Test
    fun anEnginePrimedWithAParkCountsTheStopFromItsStart() {
        // 재시작 13분 전에 P, 문 열림. 마지막 화장실 2시간 전, 연속 주행 100분. 재시작 뒤 3분 더 서 있다
        val now = clock.now()
        val anchor = TripAnchor(now - 3 * hour, emptyMap(), now - 2 * hour, continuousDrivingMin = 100.0)
        val park = ParkState(since = now - 13 * min, doorSeen = true)
        val one = FakeSettings(occupants = listOf(DRIVER))
        val primed = real.newEngine(seededEngines(), one, PersonalMemory(), anchor, park)
        val plain = real.newEngine(seededEngines(), one, PersonalMemory(), anchor)
        var p: CoachOutput? = null
        var q: CoachOutput? = null
        repeat(36) {
            val input = CoachInput(now = clock.now(), gear = Gear.PARK, speedKph = 0f)
            p = primed.update(input)
            q = plain.update(input)
            clock.advance(5_000)
        }
        val primedOut = checkNotNull(p)
        val plainOut = checkNotNull(q)
        assertTrue("D12: 재시작 전 문 열림 + 3분 → 화장실 시계가 돌아간다 (${primedOut.restroom.minutes} vs ${plainOut.restroom.minutes})",
            primedOut.restroom.minutes!! > plainOut.restroom.minutes!! + 60)
        assertTrue("D12: 재시작을 넘어 15분 정차 → 휴식 (${primedOut.fatigue.minutes} vs ${plainOut.fatigue.minutes})",
            primedOut.fatigue.minutes!! > plainOut.fatigue.minutes!! + 60)
        assertTrue(primedOut.parked)
    }
}
