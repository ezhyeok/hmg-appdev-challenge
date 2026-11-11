package dev.shimpyo.engine.audit

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.CoachOutput
import dev.shimpyo.engine.DrivingSample
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.HighwaySim
import dev.shimpyo.engine.Jam
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.MIN
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.PromptPolicy
import dev.shimpyo.engine.QuietTracker
import dev.shimpyo.engine.RouteStop
import dev.shimpyo.engine.SEC
import dev.shimpyo.engine.TripProfile
import dev.shimpyo.engine.VoiceIntent
import dev.shimpyo.engine.adult
import dev.shimpyo.engine.child
import dev.shimpyo.engine.kst
import dev.shimpyo.engine.stop
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PromptPolicy·질문 흐름: 질문 중 음성 의도, 만료와 대답, 쉼 경계, id, 시계 역행 */
class AuditPromptTest {

    /** 귀성길 장면 A (EtaSemanticsTest와 같은 기하): 옥산 4.45km, 5.4km부터 18.4km·60분 정체, 죽암 24.1km */
    private fun holidaySim(): HighwaySim {
        val start = kst(2026, 9, 24, 14, 0)
        val engine = CoachEngine(
            profile = TripProfile(
                listOf(adult("driver"), child("kid", 7, label = "7살 아이")), tripStart = start - 46 * MIN,
                lastRestroomAt = mapOf("driver" to start - 55 * MIN, "kid" to start - 55 * MIN),
            ),
            random = Random(7),
        )
        return HighwaySim(
            engine, start, routeLengthM = 300 * KM,
            stops = listOf(
                RouteStop(4.45 * KM, stop("oksan", null, name = "옥산휴게소", facilities = Facilities(food = true))),
                RouteStop(24.1 * KM, stop("jukam", null, name = "죽암휴게소", facilities = Facilities(food = true))),
            ),
            jams = listOf(Jam(5.4 * KM, 18.4 * KM, 3_600.0)),
        )
    }

    @Test
    fun askingHowLongWhileALastChanceIsPendingDoesNotCancelIt() {
        // 회귀(감사 P1): 사용자 응답이 활성 질문을 '무시함'으로 내리고 20분 쉼을 걸어, 마지막 기회를 듣고 "얼마나 남았어?"라고 물으면
        // 진입로 전에 다시 묻지 않았다
        val sim = holidaySim()
        val first = sim.step()
        val lc = assertNotNull(first.prompt)
        assertEquals(PromptKind.LAST_CHANCE, lc.kind)
        val info = sim.step(intent = VoiceIntent.HowLong)
        assertEquals(PromptKind.INFO, info.prompt?.kind)
        var reasked = false
        sim.driveUntilPos(4.45 * KM - 2.0 * KM) { o -> if (o.prompt?.kind == PromptKind.LAST_CHANCE && o.prompt?.id != lc.id) reasked = true }
        assertTrue(reasked, "INFO 답 뒤 진입로 2km 전까지 마지막 기회를 다시 물어야 한다")
    }

    @Test
    fun acceptArrivingAfterExpiryStillCounts() {
        // 45초가 지나 내려간 질문에 늦게 "응"해도 받아들인다 (지금 맞다)
        val sim = holidaySim()
        val lc = assertNotNull(sim.step().prompt)
        sim.driveSeconds(50)
        assertNull(sim.last?.prompt, "만료로 내려갔다")
        sim.engine.onPromptResult(lc.id, true, sim.now)
        val out = sim.step()
        assertEquals("oksan", out.next?.stop?.id)
        assertEquals("NEXT_ACCEPTED", out.next?.reasons?.single()?.code)
    }

    @Test
    fun answerToAnUnknownPromptIdChangesNothing() {
        val sim = holidaySim()
        val lc = assertNotNull(sim.step().prompt)
        val out = sim.step(intent = VoiceIntent.Accept("p999"))
        assertEquals(lc.id, out.prompt?.id, "모르는 id의 대답은 활성 질문을 건드리지 않는다")
        assertNotEquals("NEXT_ACCEPTED", out.next?.reasons?.firstOrNull()?.code)
    }

    // ---------------- 질문 id ----------------

    private fun drive(e: CoachEngine, from: Long, seconds: Int): List<CoachOutput> = (0 until seconds).map { i ->
        val t = from + i * SEC
        e.update(CoachInput(now = t, gear = Gear.DRIVE, speedKph = 90f, samples = listOf(DrivingSample(t = t, speedKph = 90f, steeringDeg = 0f))))
    }

    @Test
    fun promptIdsAreNotReusedAfterANewTrip() {
        // 회귀(감사 P2): 새 여정이면 질문 id가 p1부터 다시 시작했다. 앱은 id가 바뀔 때만 말하므로 첫 질문이 묻히고, 옛 p1에 늦게 온 대답이 새 질문에 붙었다
        val start = kst(2026, 11, 11, 10, 0)
        fun profile(tripStart: Long) = TripProfile(listOf(adult("driver")), tripStart = tripStart, continuousDrivingMin = 115.0)
        val e = CoachEngine(profile = profile(start - 115 * MIN), random = Random(7))
        val first = drive(e, start, 120).firstNotNullOf { it.prompt }
        e.setProfile(profile(start + 2 * MIN))
        val second = drive(e, start + 3 * MIN, 120).firstNotNullOf { it.prompt }
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun promptIdsAreUniqueAcrossEngineInstances() {
        // 회귀(감사 P3): 앱은 시연 시작·끝, 학습 초기화 때 엔진을 새로 만든다. id가 엔진마다 p1부터라 다시 한 시연의 첫 질문이 묻힐 수 있었다
        val start = kst(2026, 11, 11, 10, 0)
        fun firstPrompt() = drive(
            CoachEngine(profile = TripProfile(listOf(adult("driver")), tripStart = start - 115 * MIN, continuousDrivingMin = 115.0), random = Random(7)),
            start, 120,
        ).firstNotNullOf { it.prompt }
        assertNotEquals(firstPrompt().id, firstPrompt().id)
    }

    // ---------------- 쉼 경계 ----------------

    @Test
    fun unansweredCooldownReleasesAtExactlyTwentyMinutesAfterExpiry() {
        val cfg = CoachConfig()
        val p = PromptPolicy(cfg, Random(1))
        val t0 = kst(2026, 11, 11, 10, 0)
        val none: (String) -> Double? = { null }
        val c = PromptPolicy.Candidate(PromptKind.CHECK_IN, "졸리세요?", level = Level.SOON)
        assertNotNull(p.decide(listOf(c), t0, t0, false, none, PersonalMemory()))
        val expiry = t0 + cfg.promptTtlS * SEC
        assertNull(p.decide(listOf(c), expiry, expiry, false, none, PersonalMemory()), "만료 순간 내리고 쉼 시작")
        val justBefore = expiry + 20 * MIN - SEC
        assertNull(p.decide(listOf(c), justBefore, justBefore, false, none, PersonalMemory()))
        val at = expiry + 20 * MIN
        assertEquals(PromptKind.CHECK_IN, p.decide(listOf(c), at, at, false, none, PersonalMemory())?.kind)
    }

    @Test
    fun aHigherLevelReleasesTheCooldownImmediately() {
        val p = PromptPolicy(CoachConfig(), Random(1))
        val t0 = kst(2026, 11, 11, 10, 0)
        val none: (String) -> Double? = { null }
        val soon = PromptPolicy.Candidate(PromptKind.CHECK_IN, "졸리세요?", level = Level.SOON)
        val id = assertNotNull(p.decide(listOf(soon), t0, t0, false, none, PersonalMemory())).id
        p.onResult(id, false, t0 + 5 * SEC, PersonalMemory())
        val later = t0 + 2 * MIN
        assertNull(p.decide(listOf(soon), later, later, false, none, PersonalMemory()), "거절 뒤 쉼")
        val now = PromptPolicy.Candidate(PromptKind.CHECK_IN, "졸리세요?", level = Level.NOW)
        assertNotNull(p.decide(listOf(now), later, later, false, none, PersonalMemory()), "단계가 오르면 바로")
    }

    // ---------------- 시계 역행 ----------------

    @Test
    fun brakingIsStillSeenAfterTheClockWentBack() {
        // 회귀(감사 T1): 조용한 때 기록이 앞으로만 움직여, 벽시계를 한 시간 되돌리면 그동안의 제동·큰 조향을 못 봤다
        val q = QuietTracker(CoachConfig())
        val t0 = kst(2026, 11, 11, 10, 0)
        q.onSample(DrivingSample(t = t0 + 3_600_000L, brake = true))
        q.onSample(DrivingSample(t = t0 + 1_000L, brake = true))
        assertTrue(q.quiet(t0 + 2_000L), "방금 밟은 제동 뒤 10초는 조용히")
    }
}
