package dev.shimpyo.engine

import dev.shimpyo.engine.EngineMath.NONE
import kotlin.math.abs
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
 *   (다른 사람 때문에 선 정차, 여럿이 타서 앞당겨 권한 정차는 '적어도 t는 버텼다'는 중도절단이라 b만 더한다).
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
 *   "7살 아이 기준 49분"처럼 누구 때문인지와 함께 읽히는 값이다.
 *   필요가 시작돼도 말없이 참는 경우가 많아(배뇨의 65%는 요의 없이, AI §3-A) '아직 말이 없다'를 증거로 쓰지 않는다.
 * - 판단 마감(마지막 기회) = 앞으로 Δ분 안에 탑승자 중 **누군가** 필요해질 확률 P(Δ) = 1 − Π S_i(e_i + Δ)가
 *   0.3에 닿는 Δ (e_i: 마지막 화장실 뒤 지난 시간, 탑승자끼리 독립. AI §8-1 '탑승자 묶음', §8-2). 차는 한 사람만 필요해도
 *   서야 하므로, 긴 공백 앞에서 "다음 기회까지 버티지 못할 확률"은 한 사람이 아니라 묶음의 확률이다. 혼자 타면 그 사람의
 *   Q(0.3) − e와 같고 여럿이면 더 이르다 (방금 다녀온 어른 한 명 112분, 둘 84분, 넷 63분, 여덟 48분. GroupNeedTest가 지킨다).
 * - 묶음 확률은 그 판단에만 쓴다. 피트스톱 계획의 구간 상한은 한 사람 기준 마감([soloDeadlineMin])이다: 계획은 편한 정차 간격이고
 *   화장실이 자주 있는 길에서는 계획에 없던 정차의 값이 몇 분이다 (묶음으로 계획하면 게이지가 OK인데 '지금 쉬어야 해요'가 나온다).
 * - 게이지는 한 사람 기준, 마감은 묶음 기준인 이유 (2026-11-12 시뮬레이션, 조건마다 1,000 여정):
 *   둘 다 한 사람 기준이면 어른 둘이 탔을 때 '필요 확률 30%' 마감에서 실제로 누군가 필요할 확률이 51%(1 − 0.7²)가 되어
 *   명절 정체의 위기 비율이 14.1%로 오른다(묶음 마감은 12.0%). 둘 다 묶음 기준이면 위기는 같은데(11.9%) 게이지가 일찍 바닥나
 *   막히지 않는 길에서 질문이 1.7 → 3.1회로 늘고, 아이 동승 조건은 오히려 나빠진다(13.1 → 14.6%). 공백이 없는 길에서는
 *   마감이 일러도 물을 일이 없으므로 묶음 확률은 '긴 공백 앞'에서만 일한다.
 * - 그래서 여럿이 타면 게이지가 넉넉해 보이는데도 마지막 기회를 물을 수 있다(감사 O6: 어른 2명은 게이지 66분일 때 마감).
 *   그 경우는 이유에 LAST_CHANCE_GROUP('여럿이 타서')을 붙여 화면이 설명한다 (CoachEngine, [soloDeadlineMin]).
 *   그렇게 앞당겨 권한 정차에 서면 누구의 사건으로도 배우지 않는다 ([onRestroomStop]의 groupDriven).
 * - 정차 간 상한: 어르신·임신부 걷기 2시간, 영아 카시트 2시간은 확률과 상관없이 문턱의 상한으로 건다 (AI §9-1).
 * - 음성으로 필요를 알린 사람은 바로 0(NOW).
 *
 * 문맥 보정 (docs/research/need-covariates.md 6장·8장, 'NC'): 사람마다 시계를 유효 시간 τ로 돌린다.
 *   τ += dt / m(지금),  S(t) = S0(τ),  배운 값도 τ (b += τ^k)
 * 배수 m을 '필요해지는 시간'에 곱하는 가속수명 모형이라 위의 닫힌 식이 그대로 쓰이고, 기록을 τ로 남기므로 문맥이 그 사람의
 * 기준 간격을 한쪽으로 끌지 않는다. 배수가 없으면 τ = 지난 시간이고 모든 값이 보정 전과 같다(ContextFactorTest가 지킨다).
 * - 식사 정차 뒤 구간 ([Tracked.mealFactor], 기본 0.8): 그 정차에서 시계가 돌아간 사람의 구간 전체에 건다(영아·반려견 제외).
 *   구간 안에서 상수라 t를 t/m으로 바꾸는 것과 같다. 게이지와 판단 마감 모두에 쓴다. 다음 화장실에서 풀린다.
 *   문이 열리지 않아 시계가 돌아가지 않은 정차에는 걸지 않는다: 아무도 내리지 않았으면 먹고 마셨다고 볼 근거가 약하고,
 *   그대로 흘러간 시계가 이미 이른 쪽이다.
 * - 밤에 잠든 아이 ([Tracked.asleep], 기본 1.5): 잠든 밤 시간만큼 τ가 덜 흐른다(도중에 잠들고 깨도 누적). **화면 게이지에만** 쓴다.
 *   판단 마감은 깨어 있는 것으로 계산한다: 긴 공백 앞에서는 보수적으로, 깬 아이는 바로 화장실을 찾을 수 있다(NC §10-4).
 *   게이지는 '계속 잔다면 문턱에 닿기까지 실제 분'이다. 밤 시간대의 끝(07시)은 미리 아는 것이라 따라가므로 경계에서 튀지 않는다.
 * - 상한(걷기·카시트)은 실제 시간 그대로다. 피트스톱의 '정차 뒤 구간'(fromReset)은 배수 1.0으로 계산한다.
 * - 이번 구간의 배수와 잔 시간은 엔진 안에만 있다. 엔진을 다시 만들면(앱 재시작) 보정 없는 구간으로 돌아간다 (프로필이 실어 오지 않는다).
 */
internal class RestroomNeedModel(private val config: CoachConfig, local: LocalClock = LocalClock(config.zoneId)) {

    class Tracked internal constructor(occupant: Occupant, lastRestroom: Long) {
        var occupant: Occupant = occupant
            internal set

        /** 마지막 화장실 (엔진 시각) */
        var lastRestroom: Long = lastRestroom
            internal set

        /** 지금 반영한 프로필의 마지막 화장실 값 (벽시계, 없으면 null). 바뀌었는지 보려고 둔다 */
        internal var profileValue: Long? = null

        /** 엔진이 마지막으로 기록한 화장실 정차 (엔진 시각). 없으면 NONE */
        internal var stopAt: Long = NONE

        /** 음성으로 필요를 알린 시각. 없으면 NONE */
        var voicedAt: Long = NONE
            internal set

        var posterior: NeedPosterior = NeedPosterior(1.0, 1.0, 1.0, 0, 0, 0)
            internal set

        /** 정차 간 상한 (분). 없으면 무한대 */
        var capMin: Double = Double.POSITIVE_INFINITY
            internal set

        /** 화면 게이지 문턱 = min(Q(0.5), 상한). 화장실 직후의 '가득 찬 게이지' (분, 기준 조건) */
        var thresholdMin: Double = 0.0
            internal set

        /** 상한을 걸기 전의 게이지 문턱 Q(0.5) (분, 기준 조건) */
        internal var curveMin: Double = 0.0

        /** 이번 구간의 식사 배수. 1.0이면 없다. 시계가 돌아가면(다음 화장실) 1.0으로 돌아간다 */
        var mealFactor: Double = 1.0
            internal set

        /** 지금 자고 있다고 알려진 아이인지 (CoachInput.asleepOccupantIds). 배수는 밤 시간대에만 걸린다 */
        var asleep: Boolean = false
            internal set

        /** 이번 구간에서 잠 때문에 덜 흐른 시간 (ms) = ∫ (1 − 1/배수) dt. 한 번도 자지 않았으면 정확히 0 */
        internal var sleptSavedMs: Double = 0.0

        val id: String get() = occupant.id
        val voiced: Boolean get() = voicedAt != NONE

        /** 상한(걷기·카시트)이 게이지를 정하는지: 식사 뒤 곡선(실제 분)보다 상한이 먼저일 때 */
        val capBinding: Boolean get() = capMin <= curveMin * mealFactor
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
    private var cachedMeal = false
    private var cachedAsleep = false

    private val k get() = config.restroomWeibullShape

    // 잠든 아이: 밤 시간대와 배수 (1.0이면 끈다. 0 이하·NaN 같은 값도 끈다)
    private val night = DailyWindow(local, config.asleepNightStartHour, config.asleepNightEndHour)
    private val asleepFactor = saneFactor(config.asleepIntervalFactor)

    /** 잠으로 덜 흐른 시간을 여기까지 쌓았다 (엔진 시각)과 그때의 벽시계. [advance]가 한 번도 안 불렸으면 NONE */
    private var accruedMono = NONE
    private var accruedWall = 0L

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

    /**
     * 프로필을 반영한다. 새 탑승자는 프로필의 마지막 화장실(없으면 출발)부터 잰다.
     * 같은 id는 시계를 이어 가되, 프로필의 마지막 화장실 값이 바뀌었으면 사용자가 고친 것으로 보고 그 값을 따른다
     * (출발 뒤 "출발 전에 화장실 다녀오셨어요?"에 '아니'라고 답해 더 이른 시각이 오는 경우, README 1장).
     * 단 그 뒤 엔진이 화장실 정차를 기록했으면 정차가 이긴다. 같은 값을 다시 넣으면 아무것도 바꾸지 않는다.
     * 예전에는 앞으로만 옮겨서 더 이른 정정을 무시했다(감사 F1).
     */
    fun sync(profile: TripProfile, clock: EngineClock, memory: PersonalMemory) {
        val old = ArrayList(list)
        list.clear()
        for (o in profile.occupants) {
            val raw = profile.lastRestroomAt[o.id]
            val profileLast = clock.monoAt(raw ?: profile.tripStart)
            val prev = old.firstOrNull { it.id == o.id }
            val t = if (prev != null) {
                prev.occupant = o
                if (raw != prev.profileValue) {
                    prev.profileValue = raw
                    val last = if (prev.stopAt != NONE) maxOf(prev.stopAt, profileLast) else profileLast
                    // 사용자가 고친 시각부터는 새 구간이다: 식사 정차에서 시작한 구간도, 그 구간에서 잔 시간도 아니다
                    if (last != prev.lastRestroom) resetClock(prev, last)
                }
                prev
            } else {
                Tracked(o, profileLast).also { it.profileValue = raw }
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
            t.curveMin = t.posterior.quantile(p)
            t.thresholdMin = minOf(t.curveMin, t.capMin)
        }
        cached = null
    }

    /** 마지막 화장실 뒤 지난 실제 시간 (분) */
    fun elapsedMin(t: Tracked, mono: Long): Double = maxOf(0L, mono - t.lastRestroom) / 60_000.0

    /**
     * 유효 시간 τ (분): 기준 조건(낮, 최근 식사 없음)으로 환산한 '마지막 화장실 뒤 시간'. 화면 게이지와 학습이 쓴다.
     * 잠으로 덜 흐른 시간을 빼고 식사 배수로 나눈다. 배수가 없으면 지난 시간과 비트까지 같다
     */
    fun tauMin(t: Tracked, mono: Long): Double = (elapsedMin(t, mono) - t.sleptSavedMs / 60_000.0) / t.mealFactor

    /**
     * 게이지: 이 사람의 곡선이 문턱에 닿을 때까지 남은 실제 분 (상한 포함, 음성 요청이면 0).
     * 배수가 없으면 예전 식 '문턱 − 지난 시간'과 같다.
     */
    fun remainingMin(t: Tracked, mono: Long): Double {
        if (t.voiced) return 0.0
        val left = curveLeftMin(t, mono)
        val curve = if (t.asleep) asleepWallMin(left, wallAt(mono)) else left
        return minOf(curve, t.capMin - elapsedMin(t, mono))
    }

    /**
     * 곡선이 문턱에 닿기까지 더 흘러야 하는 시간 (분, 깨어 있을 때의 실제 분) = (Q(0.5) − τ) × 식사 배수.
     * 식사 배수는 구간 안에서 상수라 문턱을 그만큼 줄인 것과 같다: Q × m − (지난 시간 − 잠으로 덜 흐른 시간)
     */
    private fun curveLeftMin(t: Tracked, mono: Long): Double =
        t.curveMin * t.mealFactor - (elapsedMin(t, mono) - t.sleptSavedMs / 60_000.0)

    /**
     * 잠든 아이가 계속 잔다면, 깨어 있을 때 leftMin분에 해당하는 양이 흐르는 데 걸리는 실제 분.
     * 밤 시간대 안에서는 실제 1분에 1/배수 분씩만 흐른다. 시간대 경계는 미리 아는 것이라 따라간다:
     * '지금 배수 그대로'로 곱하면 07시에 게이지가 3분의 1만큼 한 번에 떨어진다 (연료계는 1분에 1분씩 줄어야 한다).
     */
    private fun asleepWallMin(leftMin: Double, wall: Long): Double {
        if (leftMin <= 0.0 || night.isEmpty) return leftMin
        var left = leftMin * 60_000.0
        var real = 0.0
        var t = wall
        var slowed = false // 밤 구간을 지났는지
        repeat(MAX_NIGHT_EDGES) {
            val inNight = night.contains(t)
            val span = night.msToEdge(t)
            val rate = if (inNight) 1.0 / asleepFactor else 1.0
            val gain = span * rate
            if (gain >= left) {
                // 밤 구간을 지나지 않았으면(낮잠) 받은 값을 그대로 돌려준다. ms로 바꿨다 되돌리면 마지막 자리가 달라질 수 있고,
                // 그러면 같은 또래 두 아이 중 '가장 급한 사람'이 초마다 바뀐다
                return if (slowed || inNight) (real + left / rate) / 60_000.0 else leftMin
            }
            if (inNight) slowed = true
            left -= gain
            real += span
            t += span
        }
        return (real + left) / 60_000.0
    }

    /** 엔진 시각 → 벽시계. 마지막 [advance] 때의 차이를 쓴다 (그 전이면 엔진 시각 = 벽시계) */
    private fun wallAt(mono: Long): Long = if (accruedMono == NONE) mono else accruedWall + (mono - accruedMono)

    /**
     * 시계를 지금까지 진행하고 이번 입력의 '잠든 사람'을 반영한다. 매 update에 한 번 부른다.
     * 지난 update부터 지금까지는 그때 알려진 상태(잠듦/깸)로 쌓고, 이번 목록은 지금부터 적용한다.
     * 아이(CHILD)만 받는다. 목록의 모르는 id, 어른·영아·반려견은 무시한다.
     */
    fun advance(mono: Long, wallNow: Long, asleepIds: Set<String>) {
        accrue(mono, wallNow)
        val on = asleepFactor != 1.0 && !night.isEmpty && asleepIds.isNotEmpty()
        for (t in list) {
            val a = on && t.occupant.type == OccupantType.CHILD && t.id in asleepIds
            if (a != t.asleep) {
                t.asleep = a
                cached = null
            }
        }
    }

    /**
     * 잠든 아이의 '덜 흐른 시간'을 to까지 쌓는다: 그 사이 밤 시간대와 겹친 실제 시간 × (1 − 1/배수).
     * 겹친 길이는 경계까지 정확히 재므로 update가 1초마다 오든 몇 분 만에 오든 같은 값이다.
     */
    private fun accrue(to: Long, wallAtTo: Long) {
        val from = accruedMono
        if (from != NONE && to > from) {
            for (t in list) {
                if (!t.asleep) continue
                val start = maxOf(from, t.lastRestroom) // 이 사람의 이번 구간 안쪽만
                if (to > start) t.sleptSavedMs += night.overlapMs(wallAtTo - (to - start), wallAtTo) * (1.0 - 1.0 / asleepFactor)
            }
        }
        if (from == NONE || to >= from) {
            accruedMono = to
            accruedWall = wallAtTo
        }
    }

    /** 시계를 at으로 돌린다. 새 구간에는 앞 구간의 문맥(식사 배수, 잔 시간)이 넘어오지 않는다 */
    private fun resetClock(t: Tracked, at: Long) {
        t.lastRestroom = at
        t.mealFactor = 1.0
        t.sleptSavedMs = 0.0
        cached = null
    }

    /**
     * 식사 정차: 이 정차([from, until], 엔진 시각) 동안 시계가 돌아간 사람의 이번 구간에 배수를 건다 (영아·반려견 제외).
     * 식사 정차 판정(20분)은 시계가 돌아간 때(3분)보다 늦게 오므로 구간의 처음부터 건다: 배수는 '정차 시작 3분에 시계를 돌리는'
     * 규칙 위에서 정한 값이다(NC §2 6행: 30분 식사 뒤 출발할 때 123 → 93분).
     * 이 정차에서 시계가 돌아가지 않은 사람은 그대로 둔다(클래스 설명). 엔진은 다음 정차까지 매 update 부르므로,
     * 문이 뒤늦게 열려 시계가 돌아가거나 프로필이 그 정차 안의 다른 시각으로 시계를 옮겨도 그 구간에 걸린다.
     * 그 정차가 끝난 뒤의 시각으로 돌아간 시계(다음 화장실 정차, 사용자가 고친 시각)는 범위 밖이라 걸리지 않는다.
     */
    fun onMealStop(from: Long, until: Long, factor: Double) {
        val f = saneFactor(factor)
        if (f == 1.0) return
        for (t in list) {
            if (t.occupant.type == OccupantType.INFANT || t.occupant.type == OccupantType.DOG) continue
            if (t.lastRestroom < from || t.lastRestroom > until) continue
            if (t.mealFactor != f) {
                t.mealFactor = f
                cached = null
            }
        }
    }

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
     * 한 사람씩만 볼 때의 마감 (분): 탑승자마다 필요 확률이 p에 닿을 때까지 남은 시간(정차 간 상한 포함) 중 가장 짧은 것.
     * 음성 요청이 있으면 0. [deadlineMin]의 위 한계다. 피트스톱 계획의 구간 상한이고, 묶음 확률 때문에 마지막 기회가
     * 앞당겨졌는지 가리는 데도 쓴다.
     * fromReset이면 모두 방금 화장실에 다녀온 상태(e_i = 0)로 계산한다. 탑승자가 없으면 null.
     * 마감의 유효 시간은 e_i / 식사 배수다 (잠든 아이 배수는 넣지 않는다). fromReset이면 배수 1.0.
     */
    fun soloDeadlineMin(mono: Long, p: Double, fromReset: Boolean = false): Double? {
        if (list.isEmpty()) return null
        var best = Double.POSITIVE_INFINITY
        for (t in list) {
            if (!fromReset && t.voiced) return 0.0
            val e = if (fromReset) 0.0 else elapsedMin(t, mono)
            val f = if (fromReset) 1.0 else t.mealFactor
            // 곡선은 유효 시간 Q(p)에, 곧 실제 시간 Q(p) × 배수에 닿는다. 상한은 실제 시간
            val single = minOf(t.posterior.quantile(p) * f, t.capMin) - e
            if (single < best) best = single
        }
        return maxOf(0.0, best)
    }

    /**
     * 판단용 마감 (분): 누군가 필요해질 확률 1 − Π S_i(e_i + Δ)가 p에 닿는 Δ (클래스 설명). 한 사람이면 [soloDeadlineMin]과
     * 같고, 여럿이면 그 이하다. 상한·음성 요청도 반영한다.
     * fromReset이면 모두 방금 화장실에 다녀온 상태로 계산한다 (피트스톱의 정차 뒤 구간). 탑승자가 없으면 null.
     */
    fun deadlineMin(mono: Long, p: Double, fromReset: Boolean = false): Double? {
        val hi = soloDeadlineMin(mono, p, fromReset) ?: return null
        if (hi <= 0.0 || list.size == 1) return hi
        // P(Δ)는 단조 증가하고 묶음 확률은 각자 확률 이상이므로 답은 [0, hi]에 있다.
        // 상한이 걸린 사람 때문에 hi에서도 p에 못 미치면 hi(상한)가 답이다
        if (anyNeedProb(mono, hi, fromReset) <= p) return hi
        if (anyNeedProb(mono, 0.0, fromReset) >= p) return 0.0
        var lo = 0.0
        var up = hi
        repeat(BISECTIONS) {
            val mid = (lo + up) / 2
            if (anyNeedProb(mono, mid, fromReset) < p) lo = mid else up = mid
        }
        return lo
    }

    /** 앞으로 deltaMin분 안에 탑승자 중 누군가 필요해질 확률 (독립 가정). 사람마다 유효 시간 (e_i + Δ) / 식사 배수로 본다 */
    private fun anyNeedProb(mono: Long, deltaMin: Double, fromReset: Boolean): Double {
        var surv = 1.0
        for (t in list) {
            val e = if (fromReset) 0.0 else elapsedMin(t, mono)
            val f = if (fromReset) 1.0 else t.mealFactor
            surv *= t.posterior.survival((e + deltaMin) / f)
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
     * [groupDriven](여럿이 타서 앞당겨 권한 마지막 기회를 수락해 선 정차)이면 모두 중도절단이다: 그 정차는 공백과 인원 때문에 선 것이지
     * 가장 급한 사람이 필요해서가 아니다. 그 사람의 0.5회 사건으로 배우면 어른 넷은 48분, 여덟은 24분 간격이 첫 탑승자의 기록이 되어
     * 그런 정차 다섯 번에 그 사람의 중앙값이 150 → 119분으로 줄었다 (닫힘 검증 E5b: 한 사람 기준 게이지가 묶음 시각 쪽으로 끌려갔다).
     * 이미 음성으로 관측을 남긴 사람은 다시 남기지 않는다.
     * 남기는 값은 유효 시간 τ다(식사 뒤 96분 → 120분). '연달아 선 정차'를 거르는 최소 간격은 실제 시간으로 본다.
     * [learn]이 false면 시계만 돌리고 관측을 남기지 않는다 (출발 전 정차: 앞 구간의 시작이 대개 가정한 값이다).
     */
    fun onRestroomStop(at: Long, memory: PersonalMemory, suggested: Boolean = false, groupDriven: Boolean = false, learn: Boolean = true): PersonalMemory {
        // 잠든 시간을 정차 시각까지 쌓고 잰다. 다시 대기로 이어진 정차는 at이 지난 update보다 3분까지 앞설 수 있는데, 그 사이
        // 잔 시간(유효 1분 미만)은 나누지 않고 앞 구간에 둔다
        if (accruedMono != NONE && at > accruedMono) accrue(at, wallAt(at))
        val driver = mostConstrained(at)
        var exact = memory.restroomIntervalsMin
        var sugg = memory.restroomSuggestedMin
        var censored = memory.restroomCensoredMin
        val max = config.restroomMemoryMax
        for (t in list) {
            val interval = (at - t.lastRestroom) / 60_000.0
            if (learn && !t.voiced && interval >= config.restroomMinObservedMin) {
                val tau = tauMin(t, at)
                when {
                    t !== driver || groupDriven -> censored = censored.prepend(t.id, tau, max)
                    suggested -> sugg = sugg.prepend(t.id, tau, max)
                    else -> exact = exact.prepend(t.id, tau, max)
                }
            }
            if (at > t.lastRestroom) resetClock(t, at)
            if (at > t.stopAt) t.stopAt = at
            t.voicedAt = NONE
        }
        val mem = memory.copy(restroomIntervalsMin = exact, restroomSuggestedMin = sugg, restroomCensoredMin = censored)
        refresh(mem)
        return mem
    }

    /**
     * 음성 요청("아이가 화장실 가고 싶대", "화장실 급해"). 그 사람을 NOW로 올리고 경과 시간을 사건으로 남긴다.
     * 누구인지 모르면 목적에 맞는 탑승자 중 가장 급한 사람으로 본다 (RESTROOM: 말할 수 있는 사람, FEEDING: 영아, PET: 반려견).
     * 맞는 사람이 없으면 아무에게도 남기지 않는다: 반려견만 설정했는데 운전자가 "화장실 급해"라고 하면 그건 목록에 없는
     * 운전자의 필요다. 예전에는 반려견의 기록으로 남아 반려견 게이지를 계속 줄였다(감사 O3).
     */
    fun onVoiced(occupantId: String?, purpose: Purpose, mono: Long, memory: PersonalMemory): PersonalMemory {
        val t = resolve(occupantId, purpose, mono) ?: return memory
        if (t.voiced) return memory
        val interval = elapsedMin(t, mono)
        t.voicedAt = mono
        cached = null
        if (interval < config.restroomMinObservedMin) return memory
        val mem = memory.copy(
            restroomIntervalsMin = memory.restroomIntervalsMin.prepend(t.id, tauMin(t, mono), config.restroomMemoryMax),
        )
        refresh(mem)
        return mem
    }

    /**
     * "방금 화장실 다녀왔어": 그 사람(모르면 모두)의 시계를 [at]으로 돌린다. 관측은 남기지 않는다 (앞 구간의 시작을 대개 모른다).
     * 정차 사건처럼 이후 프로필의 더 이른 값이 이 시각을 되돌리지 않게 stopAt도 옮긴다
     */
    fun restroomDone(occupantId: String?, at: Long) {
        val targets = occupantId?.let { id -> list.filter { it.id == id } }?.ifEmpty { null } ?: list
        for (t in targets) {
            if (accruedMono != NONE && at > accruedMono) accrue(at, wallAt(at))
            if (at > t.lastRestroom) resetClock(t, at)
            if (at > t.stopAt) t.stopAt = at
            t.voicedAt = NONE
        }
        cached = null
    }

    private fun resolve(occupantId: String?, purpose: Purpose, mono: Long): Tracked? {
        if (occupantId != null) list.firstOrNull { it.id == occupantId }?.let { return it }
        return when (purpose) {
            Purpose.FEEDING -> mostConstrained(mono) { it.occupant.type == OccupantType.INFANT }
            Purpose.PET -> mostConstrained(mono) { it.occupant.type == OccupantType.DOG }
            // "화장실 급해"는 말할 수 있는 사람의 요청 (영아·반려견이 아니다)
            else -> mostConstrained(mono) { it.occupant.type != OccupantType.DOG && it.occupant.type != OccupantType.INFANT }
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
        // 문맥 이유는 그 배수가 지금 보이는 게이지를 실제로 바꿀 때만 낸다 (말로 알렸거나 상한이 게이지를 정하면 내지 않는다)
        val byCurve = !b.voiced && !b.capBinding
        val afterMeal = byCurve && b.mealFactor != 1.0
        val asleep = byCurve && b.asleep && curveLeftMin(b, mono).let { abs(asleepWallMin(it, wallAt(mono)) - it) > ASLEEP_REASON_MIN_DIFF }
        val c = cached
        if (c != null && cachedId == b.id && cachedMinutes == minutes && cachedElapsed == elapsed &&
            cachedVoiced == b.voiced && cachedObs == obs && cachedMeal == afterMeal && cachedAsleep == asleep
        ) return c
        val name = texts.occupantName(b.occupant)
        val reasons = ArrayList<Reason>(4)
        if (b.voiced) {
            reasons += Reason("RESTROOM_VOICED", texts.reasonRestroomVoiced(name))
        } else {
            reasons += Reason("RESTROOM_ELAPSED", texts.reasonRestroomElapsed(name, elapsed))
            if (b.capBinding) reasons += Reason("RESTROOM_CAP", texts.reasonRestroomCap(b.occupant, b.capMin.toInt()))
            if (afterMeal) reasons += Reason("RESTROOM_AFTER_MEAL", texts.reasonRestroomAfterMeal())
            if (asleep) reasons += Reason("RESTROOM_ASLEEP", texts.reasonRestroomAsleep(name))
        }
        if (obs > 0) reasons += Reason("RESTROOM_PERSONAL", texts.reasonRestroomPersonal(name, obs))
        val g = Gauge(minutes, restroomLevel(minutes, config), reasons, drivenBy = b.id)
        cached = g
        cachedId = b.id
        cachedMinutes = minutes
        cachedElapsed = elapsed
        cachedVoiced = b.voiced
        cachedObs = obs
        cachedMeal = afterMeal
        cachedAsleep = asleep
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
        /** 이분법 횟수. 구간이 4시간이어도 오차가 0.001초 아래다 (마감은 1초에 한 번 계산한다) */
        private const val BISECTIONS = 24

        /** 잠든 아이의 남은 시간을 따라갈 때 넘는 시간대 경계 수 (하루에 둘). 남은 시간은 길어야 몇 시간이다 */
        private const val MAX_NIGHT_EDGES = 8

        /** 잠든 아이 이유를 내는 최소 차이 (분). 부동소수 오차로 이유가 깜빡이지 않게 */
        private const val ASLEEP_REASON_MIN_DIFF = 1e-6

        /** 배수로 쓸 수 있는 값만: 0 이하·NaN·무한대는 '없음'(1.0)으로 본다 */
        fun saneFactor(f: Double): Double = if (f.isFinite() && f > 0.0) f else 1.0

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
