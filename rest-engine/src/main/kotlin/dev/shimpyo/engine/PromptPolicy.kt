package dev.shimpyo.engine

import java.util.EnumMap
import kotlin.math.abs
import kotlin.random.Random

/**
 * 언제 물을지 (타이밍 게이트). SPEC 4.6, docs/research/ai-science.md 5장, 8-5, 9-3.
 *
 * - 한 번에 질문 하나. 대답이 오거나 [CoachConfig.promptTtlS]가 지나면 내린다. 대답 없이 지나면 쉼은 걸되 학습에는 넣지 않는다.
 * - 묻지 않는 때 (엔진이 먼저 거는 질문만. 사용자의 말에 대한 응답 — 급해 결과, 정보, "쉬고 싶어" — 는 바로 답한다):
 *   다음 회전 500m 안 또는 지금 속도로 30초 안, 최근 10초 안의 제동·방향지시등·큰 조향(엔진이 판정),
 *   질문·음성 대화 뒤 30초 (음성 작업 뒤 인지 분산이 최대 27초 남는다, AAA 2015).
 * - 같은 종류는 물은 뒤 20분 쉰다. 거절하면 2배씩(40, 60분 상한). 단계가 오르면 바로 풀린다.
 *   마지막 기회는 그곳을 지나면 풀린다 (다음 공백은 새 결정이다, SPEC 4.6). 환기 권유는 60분.
 * - 엔진이 먼저 거는 질문은 최근 60분에 3번까지. 단 NOW 단계 질문(지금 쉬기 권고, 화장실 여유 없음)은 상한에서 뺀다
 *   (낮은 단계 질문 몇 번이 정작 중요한 질문을 한 시간 막지 않도록). 조용한 때·30초·쉼 규칙은 그대로 받는다.
 * - 활성 질문보다 우선순위가 높은 급한 질문(마지막 기회, NOW 단계)은 활성 질문을 밀어낸다. 진입로를 놓치지 않게.
 * - 묻는 거리: 질문 종류 × 거리 구간마다 베타-베르누이 수락률 Beta(1 + 수락, 1 + 거절)를 두고, 대상마다 한 번
 *   톰프슨 샘플링으로 구간을 뽑아 그 구간 상한 안으로 들어오면 묻는다. 결과는 실제로 물은 거리의 구간에 쌓는다.
 *   마지막 기회: 2~3, 3~4, 4~5km / 휴식 제안: 2~5, 5~10, 10~20km.
 */
internal class PromptPolicy(private val config: CoachConfig, private val random: Random) {

    class Candidate(
        val kind: PromptKind,
        val speech: String,
        val stop: StopCandidate? = null,
        val distanceM: Double? = null,
        /** 이 질문과 관련된 게이지 단계 (단계가 오르면 쉼이 풀린다) */
        val level: Level = Level.OK,
        /** 사용자의 말에 대한 응답이면 true: 조용한 때·쉼·횟수 제한을 받지 않는다 */
        val userInitiated: Boolean = false,
        val purposes: Set<Purpose> = emptySet(),
    )

    class Issued internal constructor(
        val prompt: Prompt,
        val targetId: String?,
        val statKey: String?,
        val level: Level,
        val expiresMono: Long,
        val userInitiated: Boolean,
        val purposes: Set<Purpose>,
    ) {
        val kind: PromptKind get() = prompt.kind
        var answered: Boolean = false
            internal set
    }

    private class Cooldown(val atMono: Long, val targetId: String?, val level: Level, val durationMs: Long)

    var active: Issued? = null
        private set

    private val recent = ArrayDeque<Issued>()
    private val cooldowns = EnumMap<PromptKind, Cooldown>(PromptKind::class.java)
    private val declines = EnumMap<PromptKind, Int>(PromptKind::class.java)
    private val issueTimes = ArrayDeque<Long>()
    private val bucketChoice = HashMap<String, Int>()
    private var counter = 0
    private var lastInteraction = Long.MIN_VALUE

    private val lastChanceEdges: DoubleArray = run {
        val n = config.askWindowNearM
        val f = maxOf(config.askWindowFarM, n + 1.0)
        val step = (f - n) / 3.0
        doubleArrayOf(n, n + step, n + 2 * step, f)
    }
    private val restEdges: DoubleArray = run {
        val n = config.askWindowNearM
        val far = maxOf(config.restAskFarM, n + 3.0)
        val e1 = if (5_000.0 in n..far) 5_000.0 else n + (far - n) / 3.0
        val e2 = if (10_000.0 in e1..far) 10_000.0 else e1 + (far - e1) / 2.0
        doubleArrayOf(n, e1, e2, far)
    }

    fun edgesFor(kind: PromptKind): DoubleArray? = when (kind) {
        PromptKind.LAST_CHANCE -> lastChanceEdges
        PromptKind.REST_SUGGESTION -> restEdges
        else -> null
    }

    /** 사용자의 음성 대화가 있었다 (30초 동안 먼저 말을 걸지 않는다) */
    fun noteInteraction(mono: Long) {
        if (mono > lastInteraction) lastInteraction = mono
    }

    /**
     * 후보(우선순위 순) 중 지금 물을 것을 고른다. 활성 질문이 있으면 그것을 계속 돌려준다 (같은 id = 이미 물은 질문).
     * distanceOf: 후보 id → 지금 경로 거리 (없거나 지나쳤으면 null)
     */
    fun decide(
        cands: List<Candidate>,
        now: Long,
        mono: Long,
        quiet: Boolean,
        distanceOf: (String) -> Double?,
        memory: PersonalMemory,
    ): Prompt? {
        expireIfNeeded(mono, distanceOf)
        for (c in cands) if (c.userInitiated) {
            active?.let { dropUnanswered(it, mono) }
            return issue(c, null, now, mono).prompt
        }
        if (quiet) return active?.prompt
        if (lastInteraction != Long.MIN_VALUE && mono - lastInteraction < config.quietAfterInteractionS * 1000L) return active?.prompt
        while (issueTimes.isNotEmpty() && mono - issueTimes.first() >= HOUR_MS) issueTimes.removeFirst()
        val capReached = issueTimes.size >= config.maxPromptsPerHour
        if (bucketChoice.size > 64) bucketChoice.clear()

        for (c in cands) {
            val act = active
            // 활성 질문이 있으면 더 급한 질문(마지막 기회, NOW 단계)만 그것을 밀어낼 수 있다. 후보는 우선순위 순이다
            if (act != null && !(urgentKind(c) && rank(c.kind) < rank(act.kind))) break
            if (capReached && c.level != Level.NOW) continue
            if (blocked(c, mono, distanceOf)) continue
            var statKey: String? = null
            val e = edgesFor(c.kind)
            val d = c.distanceM
            val stop = c.stop
            if (e != null && d != null && stop != null) {
                if (d < e[0]) continue // 너무 가까워 안전하게 빠지기 어렵다
                val maxD = if (c.level == Level.NOW) {
                    maxOf(e.last(), config.restAskFarM)
                } else {
                    val b = bucketChoice.getOrPut(choiceKey(c.kind, stop.id)) { thompson(c.kind, e, memory) }
                    e[b + 1]
                }
                if (d > maxD) continue // 고른 거리까지 기다린다
                if (d <= e.last()) statKey = statKey(c.kind, e, bucketOf(d, e))
            }
            act?.let { dropUnanswered(it, mono) }
            return issue(c, statKey, now, mono).prompt
        }
        return active?.prompt
    }

    /** 대답 반영. 이미 대답했거나 모르는 id면 무시한다. 바뀐 기억과 대상 질문을 돌려준다 */
    fun onResult(promptId: String, accepted: Boolean, mono: Long, memory: PersonalMemory): Pair<PersonalMemory, Issued?> {
        val rec = active?.takeIf { it.prompt.id == promptId }
            ?: recent.firstOrNull { it.prompt.id == promptId }
            ?: return memory to null
        if (rec.answered) return memory to null
        rec.answered = true
        if (active === rec) active = null
        noteInteraction(mono)
        var mem = memory
        val key = rec.statKey
        if (key != null) {
            val s = mem.promptStats[key] ?: PromptStat()
            val ns = if (accepted) s.copy(accepted = s.accepted + 1) else s.copy(declined = s.declined + 1)
            mem = mem.copy(promptStats = mem.promptStats + (key to ns))
        }
        if (!rec.userInitiated) {
            if (accepted) {
                declines.remove(rec.kind)
            } else {
                val n = (declines[rec.kind] ?: 0) + 1
                declines[rec.kind] = n
                val minutes = minOf(config.declineCooldownMaxMin.toLong(), baseCooldownMin(rec.kind) shl minOf(n, 6))
                cooldowns[rec.kind] = Cooldown(mono, rec.targetId, rec.level, minutes * 60_000L)
            }
        }
        return mem to rec
    }

    /** 테스트용: 톰프슨 샘플링으로 구간 하나를 뽑는다 */
    internal fun thompson(kind: PromptKind, edges: DoubleArray, memory: PersonalMemory): Int {
        var best = 0
        var bestV = -1.0
        for (b in 0 until edges.size - 1) {
            val s = memory.promptStats[statKey(kind, edges, b)]
            val v = random.nextBeta(1.0 + (s?.accepted ?: 0), 1.0 + (s?.declined ?: 0))
            if (v > bestV) { bestV = v; best = b }
        }
        return best
    }

    // ------------------------------------------------------------------

    private fun baseCooldownMin(kind: PromptKind): Long =
        if (kind == PromptKind.FRESH_AIR) config.freshAirCooldownMin.toLong() else config.declineCooldownMin.toLong()

    private fun blocked(c: Candidate, mono: Long, distanceOf: (String) -> Double?): Boolean {
        val cd = cooldowns[c.kind] ?: return false
        if (c.level > cd.level) return false // 단계가 올랐다
        if (mono - cd.atMono >= cd.durationMs) return false
        val t = cd.targetId
        if (t != null && c.kind == PromptKind.LAST_CHANCE) {
            val d = distanceOf(t)
            if (d == null || d <= 0.0) return false // 그곳을 지났다: 다음 공백은 물어도 된다
        }
        return true
    }

    private fun expireIfNeeded(mono: Long, distanceOf: (String) -> Double?) {
        val a = active ?: return
        var expire = mono >= a.expiresMono
        if (!expire && a.targetId != null &&
            (a.kind == PromptKind.LAST_CHANCE || a.kind == PromptKind.REST_SUGGESTION)
        ) {
            val d = distanceOf(a.targetId)
            if (d == null || d < CANCEL_NEAR_M) expire = true // 이미 지나가는 중이면 의미가 없다
        }
        if (expire) dropUnanswered(a, mono)
    }

    private fun dropUnanswered(a: Issued, mono: Long) {
        active = null
        if (!a.answered && !a.userInitiated) {
            cooldowns[a.kind] = Cooldown(mono, a.targetId, a.level, baseCooldownMin(a.kind) * 60_000L)
        }
    }

    private fun issue(c: Candidate, statKey: String?, now: Long, mono: Long): Issued {
        counter++
        val ttl = config.promptTtlS * 1000L
        val p = Prompt(id = "p$counter", kind = c.kind, speech = c.speech, stop = c.stop, expiresAt = now + ttl)
        val iss = Issued(p, c.stop?.id, statKey, c.level, mono + ttl, c.userInitiated, c.purposes)
        active = iss
        recent.addFirst(iss)
        while (recent.size > RECENT_MAX) recent.removeLast()
        if (!c.userInitiated) {
            issueTimes.addLast(mono)
            cooldowns[c.kind] = Cooldown(mono, c.stop?.id, c.level, baseCooldownMin(c.kind) * 60_000L)
        }
        noteInteraction(mono)
        c.stop?.let { bucketChoice.remove(choiceKey(c.kind, it.id)) }
        return iss
    }

    /** 시간이 지나면 의미가 없어지는 질문: 마지막 기회, NOW 단계 */
    private fun urgentKind(c: Candidate): Boolean = c.kind == PromptKind.LAST_CHANCE || c.level == Level.NOW

    /** 작을수록 우선 */
    private fun rank(kind: PromptKind): Int = when (kind) {
        PromptKind.URGENT_RESULT -> 0
        PromptKind.LAST_CHANCE -> 1
        PromptKind.REST_SUGGESTION -> 2
        PromptKind.CHECK_IN -> 3
        PromptKind.FRESH_AIR -> 4
        PromptKind.INFO -> 5
    }

    private fun choiceKey(kind: PromptKind, stopId: String) = "${kind.name}|$stopId"

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val RECENT_MAX = 8
        /** 질문 대상까지 이보다 가까워지면 대답이 없어도 내린다 (m) */
        const val CANCEL_NEAR_M = 500.0

        fun bucketOf(d: Double, edges: DoubleArray): Int {
            for (b in 0 until edges.size - 1) if (d <= edges[b + 1]) return b
            return edges.size - 2
        }

        /** "LAST_CHANCE:2-3km" */
        fun statKey(kind: PromptKind, edges: DoubleArray, b: Int): String =
            "${kind.name}:${km(edges[b])}-${km(edges[b + 1])}km"

        private fun km(m: Double): String {
            val k = m / 1000.0
            val r = Math.round(k * 10.0) / 10.0
            return if (abs(r - Math.round(r)) < 1e-9) Math.round(r).toString() else r.toString()
        }
    }
}

/** 최근 제동·방향지시등·큰 조향 시각을 기억한다 (묻지 않는 조건) */
internal class QuietTracker(private val config: CoachConfig) {
    private var lastEventT = Long.MIN_VALUE
    private var prevSteer = Float.NaN
    private var prevT = Long.MIN_VALUE

    fun onSample(s: DrivingSample) {
        if (s.brake == true) mark(s.t)
        if (s.turnSignal != null && s.turnSignal != TurnSignal.NONE) mark(s.t)
        val st = s.steeringDeg
        if (st != null) {
            val dt = s.t - prevT
            if (!prevSteer.isNaN() && dt in 1..2_000 && abs(st - prevSteer) >= config.largeSteerRateDeg) mark(s.t)
            prevSteer = st
            prevT = s.t
        }
    }

    private fun mark(t: Long) {
        if (lastEventT == Long.MIN_VALUE || t > lastEventT) lastEventT = t
    }

    /** now 기준 최근 quietAfterBrakeOrSteerS초 안에 사건이 있었는지 */
    fun quiet(now: Long): Boolean {
        if (lastEventT == Long.MIN_VALUE) return false
        val d = now - lastEventT
        return d >= -1_000L && d < config.quietAfterBrakeOrSteerS * 1000L
    }
}
