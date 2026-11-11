package dev.shimpyo.engine

import dev.shimpyo.engine.EngineMath.NONE
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round

/**
 * 한 사람의 '화장실이 필요해지는 시간' T의 사후예측분포. docs/research/ai-science.md 8-1.
 *
 * T ~ 와이블(형상 k 고정, 척도 λ). θ = λ^k에 역감마(= 1/θ에 감마) 켤레 사전분포를 두면 사후예측 생존함수가 닫힌 식이다.
 *   S(t) = (b / (b + t^k))^a,  분위수 Q(p) = (b((1 − p)^(−1/a) − 1))^(1/k)
 * - 사전: a0 = 3(가상 관측 3회), b0 = m0^k / (2^(1/a0) − 1). 그러면 사전 예측 중앙값이 프로필 중앙값 m0와 정확히 같다.
 * - 갱신: 그 사람 때문에 선 정차·음성 요청 a += 1, 앱이 권해서 들른 정차 a += 0.5, 모든 관측 b += t^k
 *   (다른 사람 때문에 선 정차는 '적어도 t는 버텼다'는 중도절단이라 b만 더한다).
 * 데이터가 적으면 사전값에 가깝고, 사건이 3회 쌓이면 개인 기록이 사전값과 같은 무게가 된다.
 */
internal class NeedPosterior(
    val k: Double,
    val a: Double,
    val b: Double,
    val nExact: Int,
    val nSuggested: Int,
    val nCensored: Int,
) {
    /** 마지막 화장실 뒤 t분까지 필요해지지 않을 확률 */
    fun survival(t: Double): Double = if (t <= 0.0) 1.0 else (b / (b + t.pow(k))).pow(a)

    /** S(t) = 1 − p가 되는 t (분) */
    fun quantile(p: Double): Double = (b * ((1.0 - p).pow(-1.0 / a) - 1.0)).pow(1.0 / k)

    /** 사전 중앙값 대비 개인 기록이 들어간 정도를 보이기 위한 관측 수 */
    val observations: Int get() = nExact + nSuggested

    companion object {
        fun priorB(medianMin: Double, k: Double, a0: Double): Double = medianMin.pow(k) / (2.0.pow(1.0 / a0) - 1.0)

        fun posterior(
            medianMin: Double,
            k: Double,
            a0: Double,
            exact: List<Double>?,
            suggested: List<Double>?,
            censored: List<Double>?,
            suggestedWeight: Double,
            maxEach: Int,
        ): NeedPosterior {
            var a = a0
            var b = priorB(medianMin, k, a0)
            var ne = 0
            var ns = 0
            var nc = 0
            if (exact != null) for (i in 0 until minOf(exact.size, maxEach)) {
                val t = exact[i]
                if (t > 0.0) { a += 1.0; b += t.pow(k); ne++ }
            }
            if (suggested != null) for (i in 0 until minOf(suggested.size, maxEach)) {
                val t = suggested[i]
                if (t > 0.0) { a += suggestedWeight; b += t.pow(k); ns++ }
            }
            if (censored != null) for (i in 0 until minOf(censored.size, maxEach)) {
                val c = censored[i]
                if (c > 0.0) { b += c.pow(k); nc++ }
            }
            return NeedPosterior(k, a, b, ne, ns, nc)
        }
    }
}

/** 화장실 게이지 단계. SPEC 4.1: >45 OK, 45~21 WATCH, 20~10 SOON, <10 NOW (경계 20은 SOON으로 본다) */
internal fun restroomLevel(minutes: Int, c: CoachConfig): Level = when {
    minutes > c.restroomWatchMin -> Level.OK
    minutes > c.restroomSoonMin -> Level.WATCH
    minutes >= c.restroomNowMin -> Level.SOON
    else -> Level.NOW
}

/**
 * 탑승자별 화장실 시계와 필요 곡선.
 *
 * - 게이지(화면) = 탑승자 중 '필요 확률 0.5까지 남은 분'이 가장 짧은 사람의 값 (SPEC 4.1). 연료계처럼 1분에 1분씩 준다.
 *   필요가 시작돼도 말없이 참는 경우가 많아(배뇨의 65%는 요의 없이, AI §3-A) '아직 말이 없다'를 증거로 쓰지 않는다.
 * - 판단(마지막 기회·피트스톱) = 탑승자끼리 독립으로 보고 '누군가 필요해질 확률' 1 − Π S_i(t_i + Δ)가 0.3에 닿는 Δ (AI §8-1, §8-2).
 * - 정차 간 상한: 어르신·임신부 걷기 2시간, 영아 카시트 2시간은 확률과 상관없이 문턱의 상한으로 건다 (AI §9-1).
 * - 음성으로 필요를 알린 사람은 바로 0(NOW).
 */
internal class RestroomNeedModel(private val config: CoachConfig) {

    class Tracked internal constructor(occupant: Occupant, lastRestroom: Long) {
        var occupant: Occupant = occupant
            internal set

        /** 마지막 화장실 (엔진 시각) */
        var lastRestroom: Long = lastRestroom
            internal set

        /** 음성으로 필요를 알린 시각. 없으면 NONE */
        var voicedAt: Long = NONE
            internal set

        var posterior: NeedPosterior = NeedPosterior(1.0, 1.0, 1.0, 0, 0, 0)
            internal set

        /** 정차 간 상한 (분). 없으면 무한대 */
        var capMin: Double = Double.POSITIVE_INFINITY
            internal set

        /** 화면 게이지 문턱 = min(Q(0.5), 상한). 화장실 직후의 '가득 찬 게이지' (분) */
        var thresholdMin: Double = 0.0
            internal set

        val id: String get() = occupant.id
        val voiced: Boolean get() = voicedAt != NONE
        val capBinding: Boolean get() = capMin <= posterior.quantile(0.5)
    }

    private val list = ArrayList<Tracked>()
    val tracked: List<Tracked> get() = list

    // 게이지 캐시: 값이 바뀔 때만 Gauge와 이유 문구를 새로 만든다
    private var cached: Gauge? = null
    private var cachedId: String? = null
    private var cachedMinutes = -1
    private var cachedElapsed = -1
    private var cachedVoiced = false
    private var cachedObs = -1

    private val k get() = config.restroomWeibullShape

    /** 사전 중앙값: 사람별 지정값이 있으면 그것, 없으면 유형별 값 */
    fun priorMedianMin(o: Occupant): Double {
        o.intervalOverrideMin?.let { if (it > 0.0) return it }
        return when (o.type) {
            OccupantType.ADULT -> config.priorAdultMin
            OccupantType.ELDERLY -> config.priorElderlyMin
            OccupantType.OAB -> config.priorOabMin
            OccupantType.IBS -> config.priorIbsMin
            OccupantType.PREGNANT -> config.priorPregnantMin
            OccupantType.INFANT -> config.priorInfantMin
            OccupantType.DOG -> config.priorDogMin
            OccupantType.CHILD -> {
                val age = o.ageYears
                when {
                    age == null || age <= 5 -> config.priorChildYoungMin
                    age <= 9 -> config.priorChildOlderMin
                    else -> config.priorAdultMin
                }
            }
        }
    }

    fun capMin(o: Occupant): Double = when (o.type) {
        OccupantType.ELDERLY, OccupantType.PREGNANT -> config.walkCapMin
        OccupantType.INFANT -> config.infantSeatCapMin
        else -> Double.POSITIVE_INFINITY
    }

    /** 프로필을 반영한다. 같은 id는 시계를 이어 가고, 새 탑승자는 프로필의 마지막 화장실(없으면 출발)부터 잰다 */
    fun sync(profile: TripProfile, clock: EngineClock, memory: PersonalMemory) {
        val old = ArrayList(list)
        list.clear()
        for (o in profile.occupants) {
            val profileLast = profile.lastRestroomAt[o.id]?.let(clock::monoAt)
            val prev = old.firstOrNull { it.id == o.id }
            val t = if (prev != null) {
                prev.occupant = o
                if (profileLast != null && profileLast > prev.lastRestroom) prev.lastRestroom = profileLast
                prev
            } else {
                Tracked(o, profileLast ?: clock.monoAt(profile.tripStart))
            }
            list += t
        }
        refresh(memory)
    }

    /** 기억(관측 간격)이 바뀌면 사후분포를 다시 계산한다 */
    fun refresh(memory: PersonalMemory) {
        val p = config.restroomNeedProbability.coerceIn(0.01, 0.99)
        for (t in list) {
            t.posterior = NeedPosterior.posterior(
                priorMedianMin(t.occupant), k, config.restroomPriorStrength,
                memory.restroomIntervalsMin[t.id], memory.restroomSuggestedMin[t.id], memory.restroomCensoredMin[t.id],
                config.restroomSuggestedWeight, config.restroomMemoryMax,
            )
            t.capMin = capMin(t.occupant)
            t.thresholdMin = minOf(t.posterior.quantile(p), t.capMin)
        }
        cached = null
    }

    fun elapsedMin(t: Tracked, mono: Long): Double = maxOf(0L, mono - t.lastRestroom) / 60_000.0

    fun remainingMin(t: Tracked, mono: Long): Double =
        if (t.voiced) 0.0 else t.thresholdMin - elapsedMin(t, mono)

    /** 남은 시간이 가장 짧은 탑승자 (동률이면 프로필 순서상 앞사람) */
    fun mostConstrained(mono: Long, eligible: ((Tracked) -> Boolean)? = null): Tracked? {
        var best: Tracked? = null
        var bestRem = Double.POSITIVE_INFINITY
        for (t in list) {
            if (eligible != null && !eligible(t)) continue
            val r = remainingMin(t, mono)
            if (best == null || r < bestRem) { best = t; bestRem = r }
        }
        return best
    }

    /**
     * 판단용 마감 (분): 누군가 필요해질 확률 1 − Π S_i(e_i + Δ)가 p에 닿는 Δ. 상한·음성 요청도 반영한다.
     * fromReset이면 모두 방금 화장실에 다녀온 상태(e_i = 0)로 계산한다 (피트스톱의 정차 뒤 구간). 탑승자가 없으면 null.
     */
    fun groupDeadlineMin(mono: Long, p: Double, fromReset: Boolean = false): Double? {
        if (list.isEmpty()) return null
        var hi = Double.POSITIVE_INFINITY
        for (t in list) {
            val e = if (fromReset) 0.0 else elapsedMin(t, mono)
            if (!fromReset && t.voiced) return 0.0
            val single = minOf(t.posterior.quantile(p), t.capMin) - e
            if (single < hi) hi = single
        }
        if (hi <= 0.0) return 0.0
        // 단조 증가하는 P(Δ)에서 이분법. 무리 전체 확률은 각자 확률 이상이므로 답은 [0, 개인 최솟값]에 있다
        var lo = 0.0
        var up = hi
        if (groupNeedProb(mono, up, fromReset) <= p) return up
        repeat(40) {
            val mid = (lo + up) / 2
            if (groupNeedProb(mono, mid, fromReset) < p) lo = mid else up = mid
        }
        return lo
    }

    private fun groupNeedProb(mono: Long, deltaMin: Double, fromReset: Boolean): Double {
        var surv = 1.0
        for (t in list) {
            val e = if (fromReset) 0.0 else elapsedMin(t, mono)
            surv *= t.posterior.survival(e + deltaMin)
        }
        return 1.0 - surv
    }

    /** 음성 요청 유지 시간이 지나면 푼다 (정차 판정을 놓쳤을 때 NOW에 갇히지 않게) */
    fun tick(mono: Long) {
        val hold = config.voicedNeedHoldMin * 60_000L
        for (t in list) if (t.voiced && mono - t.voicedAt > hold) { t.voicedAt = NONE; cached = null }
    }

    /**
     * 화장실 정차: 모두의 시계를 at으로 돌린다 (정차 때 배뇨는 대개 요의 없이 기회가 와서 한다).
     * 정차 시점에 게이지를 정하던 사람(가장 급한 사람)은 사건(앱이 권한 정차면 0.5회), 나머지는 중도절단 관측이다.
     * 이미 음성으로 관측을 남긴 사람은 다시 남기지 않는다.
     */
    fun onRestroomStop(at: Long, memory: PersonalMemory, suggested: Boolean = false): PersonalMemory {
        val driver = mostConstrained(at)
        var exact = memory.restroomIntervalsMin
        var sugg = memory.restroomSuggestedMin
        var censored = memory.restroomCensoredMin
        val max = config.restroomMemoryMax
        for (t in list) {
            val interval = (at - t.lastRestroom) / 60_000.0
            if (!t.voiced && interval >= config.restroomMinObservedMin) {
                when {
                    t !== driver -> censored = censored.prepend(t.id, interval, max)
                    suggested -> sugg = sugg.prepend(t.id, interval, max)
                    else -> exact = exact.prepend(t.id, interval, max)
                }
            }
            if (at > t.lastRestroom) t.lastRestroom = at
            t.voicedAt = NONE
        }
        val mem = memory.copy(restroomIntervalsMin = exact, restroomSuggestedMin = sugg, restroomCensoredMin = censored)
        refresh(mem)
        return mem
    }

    /**
     * 음성 요청("아이가 화장실 가고 싶대", "화장실 급해"). 그 사람을 NOW로 올리고 경과 시간을 사건으로 남긴다.
     * 누구인지 모르면 목적에 맞는 탑승자 중 가장 급한 사람으로 본다 (RESTROOM: 영아·반려견 제외, FEEDING: 영아, PET: 반려견).
     */
    fun onVoiced(occupantId: String?, purpose: Purpose, mono: Long, memory: PersonalMemory): PersonalMemory {
        val t = resolve(occupantId, purpose, mono) ?: return memory
        if (t.voiced) return memory
        val interval = elapsedMin(t, mono)
        t.voicedAt = mono
        cached = null
        if (interval < config.restroomMinObservedMin) return memory
        val mem = memory.copy(
            restroomIntervalsMin = memory.restroomIntervalsMin.prepend(t.id, interval, config.restroomMemoryMax),
        )
        refresh(mem)
        return mem
    }

    private fun resolve(occupantId: String?, purpose: Purpose, mono: Long): Tracked? {
        if (occupantId != null) list.firstOrNull { it.id == occupantId }?.let { return it }
        return when (purpose) {
            Purpose.FEEDING -> mostConstrained(mono) { it.occupant.type == OccupantType.INFANT }
            Purpose.PET -> mostConstrained(mono) { it.occupant.type == OccupantType.DOG }
            // "화장실 급해"는 말할 수 있는 사람의 요청: 영아·반려견은 다른 사람이 없을 때만
            else -> mostConstrained(mono) { it.occupant.type != OccupantType.DOG && it.occupant.type != OccupantType.INFANT }
                ?: mostConstrained(mono) { it.occupant.type != OccupantType.DOG }
                ?: mostConstrained(mono)
        }
    }

    fun gauge(mono: Long, texts: CoachTexts): Gauge {
        if (list.isEmpty()) {
            return cached?.takeIf { cachedId == null } ?: Gauge(
                minutes = null, level = Level.OK,
                reasons = listOf(Reason("RESTROOM_NONE", texts.reasonRestroomNoOccupants())),
            ).also { cached = it; cachedId = null }
        }
        val b = mostConstrained(mono)!!
        val minutes = floor(maxOf(0.0, remainingMin(b, mono)) + 1e-6).toInt() // 149.9999999 → 150
        val elapsed = floor(elapsedMin(b, mono)).toInt()
        val obs = b.posterior.observations
        val c = cached
        if (c != null && cachedId == b.id && cachedMinutes == minutes && cachedElapsed == elapsed &&
            cachedVoiced == b.voiced && cachedObs == obs
        ) return c
        val name = texts.occupantName(b.occupant)
        val reasons = ArrayList<Reason>(3)
        if (b.voiced) {
            reasons += Reason("RESTROOM_VOICED", texts.reasonRestroomVoiced(name))
        } else {
            reasons += Reason("RESTROOM_ELAPSED", texts.reasonRestroomElapsed(name, elapsed))
            if (b.capBinding) reasons += Reason("RESTROOM_CAP", texts.reasonRestroomCap(b.occupant, b.capMin.toInt()))
        }
        if (obs > 0) reasons += Reason("RESTROOM_PERSONAL", texts.reasonRestroomPersonal(name, obs))
        val g = Gauge(minutes, restroomLevel(minutes, config), reasons, drivenBy = b.id)
        cached = g
        cachedId = b.id
        cachedMinutes = minutes
        cachedElapsed = elapsed
        cachedVoiced = b.voiced
        cachedObs = obs
        return g
    }

    fun has(type: OccupantType): Boolean = list.any { it.occupant.type == type }

    fun firstOf(type: OccupantType): Tracked? = list.firstOrNull { it.occupant.type == type }

    /** 사람 수 (반려견 제외). 환기 권유 조건 */
    val humanCount: Int get() = list.count { it.occupant.type != OccupantType.DOG }

    /** 민감 탑승자(아이, 영아, 임신부, 어르신, 과민성 방광·장, 반려견)가 있는지. 정체 기준을 20분으로 낮춘다 */
    val sensitive: Boolean
        get() = list.any { it.occupant.type in SENSITIVE }

    /** 과민성 방광·장 탑승자의 커버리지: 화장실 기회 사이 최대 간격 (분). 없으면 null */
    fun preferredMaxGapMin(): Int? {
        var best: Int? = null
        for (t in list) {
            val v = when (t.occupant.type) {
                OccupantType.OAB -> config.oabMaxOpportunityGapMin
                OccupantType.IBS -> config.ibsMaxOpportunityGapMin
                else -> continue
            }
            best = if (best == null) v else minOf(best, v)
        }
        return best
    }

    companion object {
        val SENSITIVE: Set<OccupantType> = setOf(
            OccupantType.CHILD, OccupantType.INFANT, OccupantType.PREGNANT, OccupantType.ELDERLY,
            OccupantType.OAB, OccupantType.IBS, OccupantType.DOG,
        )
    }
}

/** 최근 것부터 max개만 남기고 맨 앞에 v를 넣는다. 저장용으로 소수 첫째 자리까지 반올림 */
internal fun Map<String, List<Double>>.prepend(id: String, v: Double, max: Int): Map<String, List<Double>> {
    val old = this[id].orEmpty()
    val out = ArrayList<Double>(minOf(old.size + 1, max))
    out += round(v * 10.0) / 10.0
    for (x in old) {
        if (out.size >= max) break
        out += x
    }
    return this + (id to out)
}
