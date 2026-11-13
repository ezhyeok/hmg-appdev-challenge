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
        assertTrue(real.usesNaviRoadLimit)
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
        assertFalse("시연 위치는 내비가 보는 실제 도로와 다르다: 그 도로의 제한속도를 쓰지 않는다", d.usesNaviRoadLimit)
    }

    /** PT에서 발표자가 질문 카드를 설명하고 누를 시간 (7.2배속 귀성길에서 카드가 실제 13~25초 만에 지나갔다, 2026-11-13) */
    @Test
    fun aDemoSlowsWhileAQuestionIsUpAndTheRealWorldNeverDoes() {
        assertSame("실제 세계는 그대로", real, real.questionShown(true, clock.elapsedMs()))

        val d = demo().withOccupants(listOf(DRIVER))
        clock.advance(10_000)
        val slow = d.questionShown(true, clock.elapsedMs())
        assertEquals(CoachWorld.QUESTION_PACE, slow.run.pace, 0.0)
        assertEquals("시각이 이어진다", d.clock.now(), slow.clock.now())
        assertEquals("고친 탑승자도 그대로", listOf(DRIVER), slow.occupants(settings))
        assertSame("이미 느리면 같은 값", slow, slow.questionShown(true, clock.elapsedMs()))

        clock.advance(4_000)
        val normal = slow.questionShown(false, clock.elapsedMs())
        assertEquals(1.0, normal.run.pace, 0.0)
        assertEquals(slow.clock.now(), normal.clock.now())
        assertSame(normal, normal.questionShown(false, clock.elapsedMs()))
    }

    @Test
    fun theDemoShowsItsScenarioDestinationNotTheRealNavis() {
        assertEquals("부산", demo().destinationName("옥산휴게소"))
        assertEquals("실제 세계는 내비의 목적지", "옥산휴게소", real.destinationName("옥산휴게소"))
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

    @Test
    fun anEngineToldTheParkEndedCountsItOnlyUntilThen() {
        // 12분 전에 P(문 열림), 마지막 맥박은 그 2분 뒤, 지금은 달리고 있다. 엔진은 정차 시작의 P 입력으로 미리 채워져 있어서
        // 그대로 두면 다음 update에서 12분 정차로 세어 화장실 시계를 돌린다 (서비스 닫힘 검증 N3). 끝난 때를 알려 주면 2분 정차다
        val now = clock.now()
        val anchor = TripAnchor(now - 3 * hour, emptyMap(), now - 2 * hour, continuousDrivingMin = 100.0)
        val park = ParkState(since = now - 12 * min, doorSeen = true)
        val one = FakeSettings(occupants = listOf(DRIVER))
        val driving = CoachInput(now = now, gear = Gear.DRIVE, speedKph = 60f)

        val untold = real.newEngine(seededEngines(), one, PersonalMemory(), anchor, park)
        val told = real.newEngine(seededEngines(), one, PersonalMemory(), anchor, park)
        real.endPark(told, endedAt = now - 10 * min)
        val plain = real.newEngine(seededEngines(), one, PersonalMemory(), anchor)
        val a = untold.update(driving)
        val b = told.update(driving)
        val c = plain.update(driving)
        assertTrue("전제: 알려 주지 않으면 12분 화장실 정차로 센다 (${a.restroom.minutes} vs ${c.restroom.minutes})", a.restroom.minutes!! > c.restroom.minutes!! + 60)
        assertEquals("2분 정차는 화장실 정차가 아니다", c.restroom.minutes, b.restroom.minutes)
        assertEquals("휴식도 아니다", c.fatigue.minutes!!.toDouble(), b.fatigue.minutes!!.toDouble(), 3.0)
        assertFalse(b.parked)

        // 마지막 맥박 때 이미 문 열림 + 5분이었으면 화장실 정차다 (기록과 같다)
        val longer = real.newEngine(seededEngines(), one, PersonalMemory(), anchor, park)
        real.endPark(longer, endedAt = now - 7 * min)
        assertTrue(longer.update(driving).restroom.minutes!! > c.restroom.minutes!! + 60)
    }

    @Test
    fun tellingTheEngineTheParkEndedDoesNotSpendAQuestionNobodySaw() {
        // 끝난 때를 알리는 입력은 '달리는 중'이라 엔진이 질문을 낼 수 있다(피로 NOW면 휴식 권유). 그 질문은 아무도 보지 못한 채
        // 수명이 지나 '무시'로 세고 20분 쉼이 걸린다. 제동 표본을 실어 그 입력으로는 묻지 않게 한다
        val now = clock.now()
        val anchor = TripAnchor(now - 3 * hour, emptyMap(), now - 30 * min, continuousDrivingMin = 150.0)
        val one = FakeSettings(occupants = listOf(DRIVER))
        val engine = real.newEngine(seededEngines(), one, PersonalMemory(), anchor, ParkState(since = now - 12 * min, doorSeen = false))
        real.endPark(engine, endedAt = now - 10 * min)
        val out = engine.update(CoachInput(now = now, gear = Gear.DRIVE, speedKph = 90f))
        val p = checkNotNull(out.prompt) { "연속 150분: 지금 쉬라고 권해야 한다 (피로 ${out.fatigue.level})" }
        assertTrue("지금 낸 질문이다 (10분 전에 내고 버린 질문이 아니다): 만료 ${p.expiresAt - now}ms 뒤", p.expiresAt > now)
    }
}
