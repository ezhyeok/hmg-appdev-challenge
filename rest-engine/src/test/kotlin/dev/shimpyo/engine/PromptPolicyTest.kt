package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptPolicyTest {
    private val cfg = CoachConfig()
    private val t0 = kst(2026, 11, 13, 10, 0)
    private val anchor = stop("anseong", null, name = "안성휴게소")
    private val other = stop("cheonan", null, name = "천안삼거리휴게소")

    /** 후보 id → 거리 (테스트가 바꾼다) */
    private val distances = HashMap<String, Double>()
    private val distanceOf: (String) -> Double? = { distances[it] }

    private fun policy(seed: Int = 1) = PromptPolicy(cfg, Random(seed))

    private fun lastChance(target: StopCandidate = anchor, d: Double, level: Level = Level.WATCH): PromptPolicy.Candidate {
        distances[target.id] = d
        return PromptPolicy.Candidate(PromptKind.LAST_CHANCE, "질문", target, d, level)
    }

    private fun simple(kind: PromptKind, level: Level = Level.SOON, user: Boolean = false) =
        PromptPolicy.Candidate(kind, kind.name, level = level, userInitiated = user)

    private fun PromptPolicy.ask(c: List<PromptPolicy.Candidate>, t: Long, quiet: Boolean = false, mem: PersonalMemory = PersonalMemory()) =
        decide(c, t, t, quiet, distanceOf, mem)

    /** 2~3km 구간을 압도적으로 좋아하는 기억 (톰프슨 샘플링이 사실상 그 구간을 고른다) */
    private val prefersNear = PersonalMemory(
        promptStats = mapOf(
            "LAST_CHANCE:2-3km" to PromptStat(accepted = 60),
            "LAST_CHANCE:3-4km" to PromptStat(declined = 60),
            "LAST_CHANCE:4-5km" to PromptStat(declined = 60),
        ),
    )

    @Test
    fun quietTimesBlockEngineQuestionsButNotUserResponses() {
        val p = policy()
        assertNull(p.ask(listOf(simple(PromptKind.CHECK_IN)), t0, quiet = true))
        val urgent = p.ask(listOf(simple(PromptKind.URGENT_RESULT, user = true)), t0, quiet = true)
        assertEquals(PromptKind.URGENT_RESULT, urgent?.kind, "사용자의 말에 대한 응답은 바로")
    }

    @Test
    fun oneActivePromptUntilAnsweredOrExpired() {
        val p = policy()
        val first = p.ask(listOf(simple(PromptKind.CHECK_IN)), t0)!!
        assertEquals(t0 + cfg.promptTtlS * SEC, first.expiresAt)
        val again = p.ask(listOf(simple(PromptKind.FRESH_AIR)), t0 + 40 * SEC)
        assertEquals(first.id, again?.id, "활성 질문을 계속 돌려준다")
        assertNull(p.ask(listOf(simple(PromptKind.CHECK_IN)), t0 + 50 * SEC), "만료 뒤 같은 종류는 쉼")
        val fresh = p.ask(listOf(simple(PromptKind.FRESH_AIR)), t0 + 90 * SEC)
        assertEquals(PromptKind.FRESH_AIR, fresh?.kind, "다른 종류는 30초 뒤 물을 수 있다")
    }

    @Test
    fun waitsThirtySecondsAfterAnyInteraction() {
        val p = policy()
        p.noteInteraction(t0)
        assertNull(p.ask(listOf(simple(PromptKind.CHECK_IN)), t0 + 29 * SEC))
        assertNotNull(p.ask(listOf(simple(PromptKind.CHECK_IN)), t0 + 30 * SEC))
        val id = p.active!!.prompt.id
        p.onResult(id, accepted = true, mono = t0 + 35 * SEC, memory = PersonalMemory())
        assertNull(p.ask(listOf(simple(PromptKind.FRESH_AIR)), t0 + 50 * SEC), "대답도 상호작용이다")
        assertNotNull(p.ask(listOf(simple(PromptKind.FRESH_AIR)), t0 + 66 * SEC))
    }

    @Test
    fun declineDoublesTheRestAndLevelIncreaseReleasesIt() {
        val p = policy()
        var t = t0
        val q = p.ask(listOf(simple(PromptKind.CHECK_IN, Level.WATCH)), t)!!
        p.onResult(q.id, accepted = false, mono = t + 5 * SEC, memory = PersonalMemory())
        t += 39 * MIN
        assertNull(p.ask(listOf(simple(PromptKind.CHECK_IN, Level.WATCH)), t), "첫 거절 → 40분")
        assertNotNull(p.ask(listOf(simple(PromptKind.CHECK_IN, Level.SOON)), t), "단계가 오르면 바로 풀린다")
        val q2 = p.active!!.prompt
        p.onResult(q2.id, accepted = false, mono = t + 5 * SEC, memory = PersonalMemory())
        assertNull(p.ask(listOf(simple(PromptKind.CHECK_IN, Level.SOON)), t + 59 * MIN), "두 번째 거절 → 60분 (상한)")
        assertNotNull(p.ask(listOf(simple(PromptKind.CHECK_IN, Level.SOON)), t + 61 * MIN))
    }

    @Test
    fun declinedPlaceIsReleasedOncePassed() {
        val p = policy()
        val q = p.ask(listOf(lastChance(d = 3_500.0)), t0, mem = prefersNear.copy(promptStats = emptyMap()))
            ?: p.ask(listOf(lastChance(d = 2_500.0)), t0)
        assertNotNull(q)
        p.onResult(q.id, accepted = false, mono = t0 + 5 * SEC, memory = PersonalMemory())
        assertNull(p.ask(listOf(lastChance(other, d = 2_500.0)), t0 + 2 * MIN), "거절한 곳이 아직 앞에 있다")
        distances.remove(anchor.id) // 지나쳤다
        val next = p.ask(listOf(lastChance(other, d = 2_500.0)), t0 + 3 * MIN)
        assertEquals("cheonan", next?.stop?.id, "다음 기회는 물어도 된다 (SPEC 4.6)")
    }

    @Test
    fun atMostThreeEnginePromptsPerHourExceptNowLevel() {
        // 상한은 같거나 높은 우선순위 질문만 센다(감사 P9): 높은 질문 셋 뒤의 낮은 질문은 막힌다.
        // 예전 순서(상태 확인·환기·휴식 뒤 마지막 기회가 막힘)는 낮은 질문이 핵심 질문을 막는 결함이라 AuditPromptPolicyTest로 옮겼다
        val p = policy()
        val kinds = listOf(PromptKind.LAST_CHANCE, PromptKind.REST_SUGGESTION, PromptKind.CHECK_IN, PromptKind.FRESH_AIR)
        var issued = 0
        var t = t0
        for (k in kinds) {
            val c = if (k == PromptKind.LAST_CHANCE) lastChance(d = 2_100.0, level = Level.WATCH) else simple(k, Level.SOON)
            val got = p.ask(listOf(c), t)
            if (got != null && got.kind == k) {
                issued++
                p.onResult(got.id, accepted = true, mono = t + SEC, memory = PersonalMemory())
            }
            t += 2 * MIN
        }
        assertEquals(3, cfg.maxPromptsPerHour)
        assertEquals(cfg.maxPromptsPerHour, issued, "네 번째는 막힌다")
        val now = p.ask(listOf(simple(PromptKind.REST_SUGGESTION, Level.NOW)), t)
        assertEquals(PromptKind.REST_SUGGESTION, now?.kind, "NOW 단계 휴식 권고는 상한에서 뺀다")
    }

    @Test
    fun lastChanceWaitsForTheSampledDistanceAndRecordsItsBucket() {
        val p = policy()
        assertNull(p.ask(listOf(lastChance(d = 6_000.0)), t0, mem = prefersNear), "창 밖")
        assertNull(p.ask(listOf(lastChance(d = 4_500.0)), t0 + 10 * SEC, mem = prefersNear), "고른 구간(2~3km)까지 기다린다")
        val q = p.ask(listOf(lastChance(d = 2_900.0)), t0 + 70 * SEC, mem = prefersNear)!!
        assertEquals(PromptKind.LAST_CHANCE, q.kind)
        val (mem, rec) = p.onResult(q.id, accepted = true, mono = t0 + 75 * SEC, memory = prefersNear)
        assertEquals("LAST_CHANCE:2-3km", rec?.statKey)
        assertEquals(61, mem.promptStats.getValue("LAST_CHANCE:2-3km").accepted)
    }

    @Test
    fun tooCloseIsNeverAskedButNowLevelDoesNotWaitForBucket() {
        val p = policy()
        assertNull(p.ask(listOf(lastChance(d = 1_500.0)), t0), "2km 안은 안전하게 빠지기 어렵다")
        val rest = stop("shelter", null, kind = StopKind.DROWSY_SHELTER)
        distances[rest.id] = 15_000.0
        val q = p.ask(listOf(PromptPolicy.Candidate(PromptKind.REST_SUGGESTION, "쉬어 갈까요?", rest, 15_000.0, Level.NOW)), t0 + 10 * SEC)
        assertEquals(PromptKind.REST_SUGGESTION, q?.kind, "NOW는 구간을 기다리지 않는다")
    }

    @Test
    fun urgentQuestionPreemptsLowerPriorityActivePrompt() {
        val p = policy()
        val fresh = p.ask(listOf(simple(PromptKind.FRESH_AIR, Level.WATCH)), t0)!!
        assertEquals(PromptKind.FRESH_AIR, fresh.kind)
        val within = p.ask(listOf(lastChance(d = 2_500.0), simple(PromptKind.FRESH_AIR)), t0 + 20 * SEC, mem = prefersNear)
        assertEquals(fresh.id, within?.id, "30초 안에는 밀어내지 않는다")
        val q = p.ask(listOf(lastChance(d = 2_300.0)), t0 + 32 * SEC, mem = prefersNear)
        assertEquals(PromptKind.LAST_CHANCE, q?.kind, "마지막 기회가 환기 질문을 밀어낸다")
        val (_, rec) = p.onResult(fresh.id, accepted = true, mono = t0 + 40 * SEC, memory = PersonalMemory())
        assertEquals(PromptKind.FRESH_AIR, rec?.kind, "밀려난 질문에 늦게 온 대답도 반영한다")
        assertEquals(PromptKind.LAST_CHANCE, p.active?.kind, "활성 질문은 그대로")
    }

    @Test
    fun freshAirRestsSixtyMinutes() {
        val p = policy()
        val q = p.ask(listOf(simple(PromptKind.FRESH_AIR, Level.WATCH)), t0)!!
        p.onResult(q.id, accepted = true, mono = t0 + SEC, memory = PersonalMemory())
        assertNull(p.ask(listOf(simple(PromptKind.FRESH_AIR, Level.WATCH)), t0 + 59 * MIN))
        assertNotNull(p.ask(listOf(simple(PromptKind.FRESH_AIR, Level.WATCH)), t0 + 61 * MIN))
    }

    @Test
    fun userInitiatedReplacesActiveAndIgnoresCap() {
        val p = policy()
        p.ask(listOf(simple(PromptKind.CHECK_IN)), t0)
        val info = p.ask(listOf(simple(PromptKind.INFO, user = true)), t0 + 5 * SEC)
        assertEquals(PromptKind.INFO, info?.kind)
        assertEquals(info?.id, p.active?.prompt?.id)
    }

    @Test
    fun answeringTwiceOrUnknownIdIsIgnored() {
        val p = policy()
        val q = p.ask(listOf(lastChance(d = 2_500.0)), t0, mem = prefersNear)!!
        val (m1, r1) = p.onResult(q.id, accepted = false, mono = t0 + SEC, memory = PersonalMemory())
        assertNotNull(r1)
        assertEquals(1, m1.promptStats.values.sumOf { it.declined })
        val (m2, r2) = p.onResult(q.id, accepted = true, mono = t0 + 2 * SEC, memory = m1)
        assertNull(r2)
        assertEquals(m1, m2)
        assertNull(p.onResult("nope", accepted = true, mono = t0, memory = m1).second)
    }

    @Test
    fun thompsonSamplingPrefersBucketsThatGetAccepted() {
        val p = policy(seed = 42)
        val edges = p.edgesFor(PromptKind.LAST_CHANCE)!!
        val mem = PersonalMemory(
            promptStats = mapOf(
                "LAST_CHANCE:2-3km" to PromptStat(accepted = 2, declined = 8),
                "LAST_CHANCE:3-4km" to PromptStat(accepted = 8, declined = 2),
                "LAST_CHANCE:4-5km" to PromptStat(accepted = 3, declined = 7),
            ),
        )
        val counts = IntArray(3)
        repeat(2_000) { counts[p.thompson(PromptKind.LAST_CHANCE, edges, mem)]++ }
        assertTrue(counts[1] > counts[0] && counts[1] > counts[2], counts.toList().toString())
        assertTrue(counts[1] > 1_400, "수락률이 높은 3~4km가 대부분: ${counts.toList()}")
        val flat = IntArray(3)
        repeat(3_000) { flat[p.thompson(PromptKind.LAST_CHANCE, edges, PersonalMemory())]++ }
        assertTrue(flat.all { it in 800..1_200 }, "기록이 없으면 고르게 탐색: ${flat.toList()}")
    }

    @Test
    fun bucketHelpers() {
        val e = doubleArrayOf(2_000.0, 3_000.0, 4_000.0, 5_000.0)
        assertEquals(0, PromptPolicy.bucketOf(2_100.0, e))
        assertEquals(1, PromptPolicy.bucketOf(3_500.0, e))
        assertEquals(2, PromptPolicy.bucketOf(9_000.0, e))
        assertEquals("REST_SUGGESTION:2-5km", PromptPolicy.statKey(PromptKind.REST_SUGGESTION, policy().edgesFor(PromptKind.REST_SUGGESTION)!!, 0))
    }

    @Test
    fun quietTrackerMarksBrakeSignalAndLargeSteer() {
        val q = QuietTracker(cfg)
        assertTrue(!q.quiet(t0))
        q.onSample(DrivingSample(t0, 100f, 0f, brake = true))
        assertTrue(q.quiet(t0 + 9 * SEC))
        assertTrue(!q.quiet(t0 + 11 * SEC))
        q.onSample(DrivingSample(t0 + 20 * SEC, 100f, 0f, turnSignal = TurnSignal.RIGHT))
        assertTrue(q.quiet(t0 + 25 * SEC))
        q.onSample(DrivingSample(t0 + 60 * SEC, 100f, 0f))
        q.onSample(DrivingSample(t0 + 61 * SEC, 100f, 20f))
        assertTrue(q.quiet(t0 + 65 * SEC), "1초에 20도 → 큰 조향")
        assertTrue(!q.quiet(t0 - 3_600_000L), "시계가 크게 뒤로 가면 조용한 때로 보지 않는다")
    }
}
