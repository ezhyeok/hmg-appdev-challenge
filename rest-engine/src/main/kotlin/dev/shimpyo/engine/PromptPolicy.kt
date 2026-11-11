package dev.shimpyo.engine

import java.util.EnumMap
import java.util.concurrent.atomic.AtomicLong
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
 * - 엔진이 먼저 거는 질문은 최근 60분에 3번까지. 이 상한은 자기와 같거나 높은 우선순위의 질문만 센다:
 *   환기·상태 확인 같은 낮은 질문 몇 번이 마지막 기회(이 앱의 핵심)를 한 시간 막지 못하게 (감사 P9). 높은 질문은 낮은 질문의 몫도 쓴다.
 *   NOW 단계 질문(지금 쉬기 권고, 화장실 여유 없음)은 상한에서 뺀다. 조용한 때·30초·쉼 규칙은 그대로 받는다.
 * - 활성 질문보다 우선순위가 높은 급한 질문(마지막 기회, NOW 단계)은 활성 질문을 밀어낸다. 진입로를 놓치지 않게.
 * - 사용자의 말에 대한 응답: 새로 들어온 말이면 바로 답하고, 대답 전의 엔진 질문을 끊는다. 끊긴 질문은 무시한 것으로 치지 않고
 *   물은 것도 되돌려(쉼·시간당 횟수) 응답이 끝나면 다시 물을 수 있다 ("얼마나 남았어?"가 마지막 기회를 지우던 것, 감사 P1).
 *   같은 때 응답이 둘이면 하나를 먼저 하고 나머지는 지금 질문이 끝난 뒤에 한다 (버리지 않는다, 감사 P10).
 * - 질문 대상이 후보 목록에서 잠깐 빠져도(내비 목록이 깜빡인다) 마지막으로 본 거리에서 최고 속도로 달려도 아직 500m 밖일
 *   동안은 질문을 유지한다. 예전에는 한 번 빠지면 바로 내리고 무시한 것으로 쳤다(감사 P11).
 * - 묻는 거리: 질문 종류 × 거리 구간마다 베타-베르누이 수락률 Beta(1 + 수락, 1 + 거절)를 두고, 대상마다 한 번
 *   톰프슨 샘플링으로 구간을 뽑아 그 구간 상한 안으로 들어오면 묻는다. 결과는 실제로 물은 거리의 구간에 쌓는다.
 *   마지막 기회: 2~3, 3~4, 4~5km / 휴식 제안: 2~5, 5~10, 10~20km.
 */
internal class PromptPolicy(
    private val config: CoachConfig,
    private val random: Random,
    private val ids: PromptIds = PromptIds("p"),
) {

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
        /** 사용자 응답일 때: 이번에 처음 내놓는 응답이면 true(활성 질문을 끊고 바로), 앞서 밀린 응답이면 false(지금 질문이 끝난 뒤) */
        val fresh: Boolean = true,
        /** 이 질문의 이유. 화장실 질문이 떠 있는 동안 엔진은 추천(CoachOutput.next)을 이 장소와 이유로 낸다 (CoachEngine.recommend) */
        val reasons: List<Reason> = emptyList(),
        /** 여럿이 타서 앞당겨진 마지막 기회인지 (GapAnalyzer.LastChance.groupDriven). 수락해서 그곳에 서면 누구의 사건으로도 배우지 않는다 */
        val groupDriven: Boolean = false,
        /**
         * 대답 없이 내려갔을 때 쉼을 걸지 (거절은 언제나 건다). 서 있는 동안(또는 움직이는지 모를 때) 답한 "다음 화장실은 …"은 false:
         * 달리다 대답 없이 지나간 답은 '그곳은 됐다'로 보지만, 서 있을 때의 무응답을 그렇게 보면 출발한 뒤 그곳을 묻지 못한다
         */
        val cooldownOnExpiry: Boolean = true,
    )

    class Issued internal constructor(
        val prompt: Prompt,
        val targetId: String?,
        val statKey: String?,
        val level: Level,
        val expiresMono: Long,
        val userInitiated: Boolean,
        val purposes: Set<Purpose>,
        val reasons: List<Reason> = emptyList(),
        val groupDriven: Boolean = false,
        val cooldownOnExpiry: Boolean = true,
    ) {
        val kind: PromptKind get() = prompt.kind
        var answered: Boolean = false
            internal set

        // 끊겼을 때 되돌릴 것: 물으면서 건 쉼, 그 전의 쉼, 시간당 횟수 기록
        internal var ownCooldown: Cooldown? = null
        internal var prevCooldown: Cooldown? = null
        internal var mark: IssueMark? = null

        // 대상이 목록에서 빠졌을 때 쓰는 마지막으로 본 거리 (m)와 시각
        internal var lastSeenD: Double = Double.NaN
        internal var lastSeenMono: Long = 0L
    }

    internal class Cooldown(val atMono: Long, val targetId: String?, val level: Level, val durationMs: Long)

    /** 시간당 상한용 기록: 언제, 어떤 우선순위의 질문을 물었는지 */
    internal class IssueMark(val mono: Long, val rank: Int)

    var active: Issued? = null
        private set

    private val recent = ArrayDeque<Issued>()
    private val cooldowns = EnumMap<PromptKind, Cooldown>(PromptKind::class.java)
    private val declines = EnumMap<PromptKind, Int>(PromptKind::class.java)
    private val issueMarks = ArrayDeque<IssueMark>()
    private val bucketChoice = HashMap<String, Int>()
    private var lastInteraction = Long.MIN_VALUE

    /** 마지막 [decide]가 새로 물은 후보 (없으면 null). 엔진이 어떤 사용자 응답을 했는지 알려고 쓴다 */
    var lastIssued: Candidate? = null
        private set

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
        lastIssued = null
        expireIfNeeded(mono, distanceOf)
        // 사용자 응답: 새 말이면 바로, 밀린 응답은 지금 질문이 끝난 뒤에
        for (c in cands) if (c.userInitiated && c.fresh) {
            active?.let { interrupt(it) }
            return issue(c, null, now, mono).prompt
        }
        if (active == null) for (c in cands) if (c.userInitiated) return issue(c, null, now, mono).prompt
        if (quiet) return active?.prompt
        if (lastInteraction != Long.MIN_VALUE && mono - lastInteraction < config.quietAfterInteractionS * 1000L) return active?.prompt
        while (issueMarks.isNotEmpty() && mono - issueMarks.first().mono >= HOUR_MS) issueMarks.removeFirst()
        if (bucketChoice.size > 64) bucketChoice.clear()

        for (c in cands) {
            if (c.userInitiated) continue
            val act = active
            // 활성 질문이 있으면 더 급한 질문(마지막 기회, NOW 단계)만 그것을 밀어낼 수 있다. 후보는 우선순위 순이다
            if (act != null && !(urgentKind(c) && rank(c.kind) < rank(act.kind))) break
            if (c.level != Level.NOW && issuedAtOrAbove(rank(c.kind)) >= config.maxPromptsPerHour) continue
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
        rec.mark = null // 대답한 질문은 끊긴 것으로 되돌리지 않는다
        if (active === rec) active = null
        noteInteraction(mono)
        var mem = memory
        val key = rec.statKey
        if (key != null) {
            val s = mem.promptStats[key] ?: PromptStat()
            val ns = if (accepted) s.copy(accepted = s.accepted + 1) else s.copy(declined = s.declined + 1)
            mem = mem.copy(promptStats = mem.promptStats + (key to ns))
        }
        if (booksCooldown(rec)) {
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

    /**
     * 운전자가 다른 정차지를 직접 골랐다 (CoachEngine.noteAccepted): 대답 전의 이 질문은 그 선택으로 끝났다.
     * 무시한 것으로 치지 않는다. 사용자의 말에 끊긴 질문처럼 물으면서 건 쉼과 시간당 횟수를 되돌리고([interrupt]),
     * 뒤늦게 오는 대답(늦게 인식된 '응', 화면이 함께 보낸 거절)은 받지 않는다.
     */
    fun supersede(a: Issued) {
        if (a.answered) return
        if (active === a) interrupt(a)
        a.answered = true
        a.mark = null
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
            if (d != null) {
                a.lastSeenD = d
                a.lastSeenMono = mono
                if (d < CANCEL_NEAR_M) expire = true // 이미 지나가는 중이면 의미가 없다
            } else {
                // 목록에서 빠졌다: 지나쳤을 수도, 목록이 잠깐 깜빡였을 수도 있다. 최고 속도로 달렸어도 아직 500m 밖이면 유지
                val reach = if (a.lastSeenD.isNaN()) 0.0 else a.lastSeenD - MAX_SPEED_MPS * (mono - a.lastSeenMono) / 1000.0
                if (reach < CANCEL_NEAR_M) expire = true
            }
        }
        if (expire) dropUnanswered(a, mono)
    }

    private fun dropUnanswered(a: Issued, mono: Long) {
        active = null
        if (!a.answered && booksCooldown(a) && a.cooldownOnExpiry) {
            cooldowns[a.kind] = Cooldown(mono, a.targetId, a.level, baseCooldownMin(a.kind) * 60_000L)
        }
    }

    /**
     * 사용자의 새 말이 대답 전의 질문을 끊었다. 엔진 질문이면 무시한 것으로 치지 않고, 물으면서 건 쉼과 시간당 횟수도 되돌린다
     * (응답이 끝나면 같은 질문을 다시 물을 수 있게. 이미 들은 내용이라도 진입로 앞에서 결정을 다시 받는 편이 낫다)
     */
    private fun interrupt(a: Issued) {
        active = null
        if (a.answered || a.userInitiated) return
        if (cooldowns[a.kind] === a.ownCooldown) {
            val prev = a.prevCooldown
            if (prev != null) cooldowns[a.kind] = prev else cooldowns.remove(a.kind)
        }
        a.mark?.let { issueMarks.remove(it) }
        a.mark = null
    }

    /**
     * 대답이 없거나 거절했을 때 쉼을 거는 질문: 엔진이 먼저 건 질문, 그리고 사용자 요청에 답하며 정차 장소를 물은 마지막 기회
     * ("아이가 화장실 가고 싶대" → "다음 화장실은 …"). 그곳을 거절했으면 엔진이 30초 뒤 같은 곳을 다시 묻지 않게 한다 (그곳을 지나면 풀린다)
     */
    private fun booksCooldown(a: Issued): Boolean =
        !a.userInitiated || (a.kind == PromptKind.LAST_CHANCE && a.targetId != null)

    /** 최근 60분에 물은 질문 중 우선순위가 r 이상(숫자가 작거나 같은)인 것의 수 */
    private fun issuedAtOrAbove(r: Int): Int {
        var n = 0
        for (m in issueMarks) if (m.rank <= r) n++
        return n
    }

    private fun issue(c: Candidate, statKey: String?, now: Long, mono: Long): Issued {
        val ttl = config.promptTtlS * 1000L
        val p = Prompt(id = ids.next(), kind = c.kind, speech = c.speech, stop = c.stop, expiresAt = now + ttl, userRequested = c.userInitiated)
        val iss = Issued(p, c.stop?.id, statKey, c.level, mono + ttl, c.userInitiated, c.purposes, c.reasons, c.groupDriven, c.cooldownOnExpiry)
        iss.lastSeenD = c.distanceM ?: Double.NaN
        iss.lastSeenMono = mono
        active = iss
        lastIssued = c
        recent.addFirst(iss)
        while (recent.size > RECENT_MAX) recent.removeLast()
        if (!c.userInitiated) {
            val mark = IssueMark(mono, rank(c.kind))
            issueMarks.addLast(mark)
            iss.mark = mark
            iss.prevCooldown = cooldowns[c.kind]
            val cd = Cooldown(mono, c.stop?.id, c.level, baseCooldownMin(c.kind) * 60_000L)
            cooldowns[c.kind] = cd
            iss.ownCooldown = cd
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
        /** 대상이 목록에서 빠졌을 때 지나쳤는지 어림하는 최고 속도 (m/s, 150km/h = RouteContext.MAX_V) */
        private const val MAX_SPEED_MPS = 150.0 / 3.6

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
        // 앞으로 간 사건은 받고, 시계가 크게(5초 넘게) 뒤로 갔으면 그 사건도 받는다. 예전에는 앞으로만 옮겨서
        // 시계를 한 시간 되돌리면 그동안의 제동·큰 조향을 못 보고 말을 걸 수 있었다(감사 T1)
        if (lastEventT == Long.MIN_VALUE || t > lastEventT || t < lastEventT - BACKJUMP_MS) lastEventT = t
    }

    /** now 기준 최근 quietAfterBrakeOrSteerS초 안에 사건이 있었는지 */
    fun quiet(now: Long): Boolean {
        if (lastEventT == Long.MIN_VALUE) return false
        val d = now - lastEventT
        return d >= -1_000L && d < config.quietAfterBrakeOrSteerS * 1000L
    }

    private companion object {
        /** DrivingStyleAnalyzer와 같은 시계 역행 판단 (ms) */
        const val BACKJUMP_MS = 5_000L
    }
}

/**
 * 질문 id. 앱은 id가 바뀔 때만 말하므로 id가 겹치면 질문이 묻힌다. 엔진 인스턴스마다 하나를 두고 새 여정에도 이어 쓴다.
 * 앞부분은 프로세스 시작 시각(ms) × 1000에서 엔진을 만들 때마다 하나씩 올린 값이라, 한 프로세스 안의 엔진끼리도,
 * 앱을 다시 띄운 뒤에도 겹치지 않는다 (시연을 다시 시작하면 엔진을 새로 만든다). 판단에는 쓰지 않는다(id 문자열에만).
 * 예전에는 엔진·여정마다 "p1"부터 다시 셌다(감사 P2, P3).
 */
internal class PromptIds(private val prefix: String) {
    private var n = 0L

    fun next(): String {
        n++
        return "$prefix$n"
    }

    companion object {
        private val seq = AtomicLong(System.currentTimeMillis() * 1_000L)

        fun forNewEngine(): PromptIds = PromptIds("p" + java.lang.Long.toString(seq.incrementAndGet(), 36) + "-")
    }
}
