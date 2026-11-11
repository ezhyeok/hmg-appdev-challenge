package dev.shimpyo.engine

import dev.shimpyo.engine.EngineMath.NONE
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 운전 패턴 편차. SPEC 3.6, docs/research/ai-science.md 1장, 8-3, 9-2.
 * 졸음 판정기가 아니라 '이번 주행 초반의 나'와 비교한 보조 신호다. 혼자서는 피로를 휴식 권고(NOW) 위로 올리지 않는다.
 *
 * 조향 신호에 대해 아는 것 (에뮬레이터·문서 확인): 앞바퀴각일 수 있고 해상도를 모른다, Virtual Console과 부호가 반대다,
 * 값이 안 바뀌어도 약 3초마다 같은 값을 다시 보낸다. 그래서 절대 각도 기준을 쓰지 않고, 같은 값의 반복은 새 정보로 보지 않는다.
 *
 * 1. 1초 안팎 표본을 60초 창으로 묶고 정속 주행 표본만 쓴다: 70km/h 이상, 제동·방향지시등 없음, 그 뒤 10초 지남.
 * 2. 준비 구간: 정속 주행 첫 5분은 기준선에 넣지 않고, 이 차의 조향 '변화 크기'(같은 값 반복을 뺀 |Δ각| 중앙값)를 잰다.
 *    조향 반전 gap = max(0.1도, 2 × 그 중앙값). 차종·센서 단위가 달라도 같은 기준이 된다.
 * 3. 창마다 특징 4개
 *    - 조향 반전율 (회/분): 방향이 gap 이상 바뀐 횟수 (Markkula & Engström 2006 방식의 실시간 히스테리시스)
 *    - 조향 보정 크기: 값이 바뀐 표본들의 |Δ각| 평균
 *    - 차속 표준편차 (km/h): 크루즈가 꺼진 정속 표본만
 *    - 급제동 수 (회/창): 30km/h 이상에서 8km/h/s 이상 감속
 * 4. 기준선: 준비 뒤 정속 주행 20분(창 20개)의 특징별 중앙값·MAD.
 * 5. 창마다 강건 z = (x − 중앙값)/max(1.4826·MAD, 하한)를 [−2, 4]로 자르고, 가중 스토퍼 결합
 *    S_w = Σ w_j z_j / √Σ w_j² (정상이면 대략 N(0, 1)). 크루즈가 창의 절반 이상이면 조향 가중치를 절반으로.
 * 6. 지수이동평균 S ← (1 − λ)S + λ·S_w (λ = 0.2). 관리한계 UCL = 3·√(λ/(2 − λ)) = 1.0.
 *    편차 = clamp(S / (2·UCL), 0, 1): 0.5가 관리한계(3σ), 0.6부터 단계를 앞당길 수 있다.
 * 7. 편차 지속: 최근 5개 창 중 3개 이상이 S_w ≥ 2 (AI §9-2 "5분 안 트리거 3회 이상").
 */
internal class DrivingStyleAnalyzer(private val config: CoachConfig) {

    // ---- 창 누적값 ----
    private var winStart = NONE
    private var winSamples = 0
    private var winSteady = 0
    private var winCruise = 0
    private var steadyDurS = 0.0
    private var steerDurS = 0.0
    private var reversals = 0
    private var corrSum = 0.0
    private var corrN = 0
    private var spdN = 0
    private var spdSum = 0.0
    private var spdSumSq = 0.0
    private var hardBrakes = 0

    // ---- 표본 사이 연속성 ----
    private var lastT = NONE
    private var lastExcludeT = NONE
    private var prevSpeed = Double.NaN
    private var prevSteer = Double.NaN
    private var prevSteady = false
    private var inBrakeEvent = false

    // ---- 조향 반전 검출기 ----
    private var revInit = false
    private var revDir = 0
    private var revExt = 0.0
    private var revMin = 0.0
    private var revMax = 0.0

    // ---- 준비 구간 (조향 변화 크기) ----
    private val warmupWindows = maxOf(0, config.styleWarmupMin * 60 / config.styleWindowS)
    private var warmupDone = warmupWindows == 0
    private var warmupCount = 0
    private val warmupDeltas = DoubleArray(WARMUP_CAP)
    private var warmupDeltaN = 0
    private var winDeltaStart = 0

    /** 준비 구간의 조향 변화 크기 중앙값 (도). 모르면 NaN */
    var steerScale: Double = Double.NaN
        private set

    /** 조향 반전 gap (도) */
    var reversalGap: Double = config.steeringReversalGapDeg
        private set

    // ---- 기준선 ----
    private val baselineWindows = maxOf(1, config.styleBaselineMin * 60 / config.styleWindowS)
    private val base = Array(F) { DoubleArray(baselineWindows) }
    private val baseCount = IntArray(F)
    private var baseWindowCount = 0
    private val med = DoubleArray(F)
    private val scale = DoubleArray(F)
    private val enabled = BooleanArray(F)

    var baselineReady: Boolean = false
        private set

    // ---- 점수 ----
    private var score = 0.0
    private val zEwma = DoubleArray(F)
    private val feature = DoubleArray(F)
    private val recentFlags = BooleanArray(PERSIST_WINDOWS)
    private var recentIdx = 0
    private val ucl = 3.0 * sqrt(config.styleEwmaAlpha / (2.0 - config.styleEwmaAlpha))

    /** 기준선 이후 점수를 낸 창 수 (테스트·로그용) */
    var scoredWindows: Int = 0
        private set

    /** 0~1. 기준선 전에는 null */
    val deviation: Double?
        get() = if (!baselineReady) null else (score / (2.0 * ucl)).coerceIn(0.0, 1.0)

    /** 최근 5개 창 중 3개 이상이 크게 벗어났는지 */
    val persistent: Boolean
        get() = baselineReady && recentFlags.count { it } >= PERSIST_MIN

    fun onSample(s: DrivingSample) {
        val t = s.t
        if (lastT != NONE) {
            if (t < lastT - BACKJUMP_MS) {
                // 시계가 크게 뒤로 갔다: 진행 중인 창을 버리고 새로 시작
                discardWindow()
                breakContinuity()
                lastT = NONE
                lastExcludeT = NONE
            } else if (t <= lastT) {
                return // 중복·순서 바뀐 표본
            }
        }
        val dtS = if (lastT == NONE) Double.NaN else (t - lastT) / 1000.0
        val contiguous = !dtS.isNaN() && dtS <= MAX_GAP_S
        if (!dtS.isNaN() && dtS >= config.styleWindowS) closeWindow() // 긴 공백은 창을 닫는다
        if (!contiguous) breakContinuity()
        lastT = t
        if (winStart == NONE) {
            winStart = t
            winDeltaStart = warmupDeltaN
        }

        val v = s.speedKph?.toDouble() ?: Double.NaN
        val steer = s.steeringDeg?.toDouble() ?: Double.NaN

        // 급제동: 정속 여부와 상관없이 30km/h 이상에서의 감속
        if (contiguous && !v.isNaN() && !prevSpeed.isNaN() && dtS >= 0.3) {
            val decel = (prevSpeed - v) / dtS
            if (prevSpeed >= HARD_BRAKE_MIN_KPH && decel >= config.hardBrakeKphPerS) {
                if (!inBrakeEvent) { hardBrakes++; inBrakeEvent = true }
            } else {
                inBrakeEvent = false
            }
        }

        val signalOn = s.turnSignal != null && s.turnSignal != TurnSignal.NONE
        if (s.brake == true || signalOn) lastExcludeT = t
        val settled = lastExcludeT == NONE || t - lastExcludeT >= config.quietAfterBrakeOrSteerS * 1000L
        val steady = !v.isNaN() && v >= config.styleSteadyMinKph && settled
        winSamples++
        if (steady) {
            winSteady++
            if (s.cruiseActive == true) winCruise++
            if (contiguous && prevSteady) steadyDurS += dtS
            if (s.cruiseActive != true) {
                spdN++
                spdSum += v
                spdSumSq += v * v
            }
            if (!steer.isNaN()) {
                if (contiguous && prevSteady && !prevSteer.isNaN()) {
                    steerDurS += dtS
                    val d = abs(steer - prevSteer)
                    // 같은 값의 반복(약 3초마다 재전송)은 보정이 아니다
                    if (d > 0.0) {
                        corrSum += d
                        corrN++
                        if (!warmupDone && warmupDeltaN < WARMUP_CAP) warmupDeltas[warmupDeltaN++] = d
                    }
                }
                if (prevSteer.isNaN() || steer != prevSteer) reversalStep(steer)
            } else {
                revInit = false
            }
        } else {
            revInit = false
        }
        prevSpeed = v
        prevSteer = if (steady) steer else Double.NaN
        prevSteady = steady

        if (t - winStart >= config.styleWindowS * 1000L) closeWindow()
    }

    /** 휴식하면 점수를 0으로. 기준선은 이번 주행 동안 유지한다 */
    fun resetScore() {
        score = 0.0
        zEwma.fill(0.0)
        recentFlags.fill(false)
    }

    fun output(texts: CoachTexts): DrivingStyle {
        if (!baselineReady) {
            val doneMin = (warmupCount + baseWindowCount) * config.styleWindowS / 60
            val totalMin = config.styleWarmupMin + config.styleBaselineMin
            return DrivingStyle(
                deviation = null, baselineReady = false,
                reasons = listOf(Reason("STYLE_BASELINE", texts.reasonStyleBaseline(doneMin, totalMin))),
            )
        }
        val dev = deviation!!
        // 가장 크게 벗어난 특징 하나를 이유로 든다
        var top = -1
        var topZ = REASON_Z
        for (j in 0 until F) if (enabled[j] && zEwma[j] >= topZ) { top = j; topZ = zEwma[j] }
        val reasons = if (top < 0) emptyList() else listOf(
            when (top) {
                REV -> Reason("STYLE_STEERING_REVERSAL", texts.reasonStyleSteeringReversal())
                CORR -> Reason("STYLE_STEERING_MICRO", texts.reasonStyleSteeringMicro())
                SPEED -> Reason("STYLE_SPEED", texts.reasonStyleSpeed())
                else -> Reason("STYLE_BRAKE", texts.reasonStyleBrake())
            },
        )
        return DrivingStyle(deviation = dev, baselineReady = true, reasons = reasons, persistent = persistent)
    }

    // ------------------------------------------------------------------

    private fun reversalStep(x: Double) {
        if (!revInit) {
            revInit = true
            revDir = 0
            revExt = x
            revMin = x
            revMax = x
            return
        }
        val gap = reversalGap
        when {
            revDir == 0 -> {
                if (x < revMin) revMin = x
                if (x > revMax) revMax = x
                if (x - revMin >= gap) { revDir = 1; revExt = x } else if (revMax - x >= gap) { revDir = -1; revExt = x }
            }
            revDir > 0 -> if (x > revExt) revExt = x else if (revExt - x >= gap) { reversals++; revDir = -1; revExt = x }
            else -> if (x < revExt) revExt = x else if (x - revExt >= gap) { reversals++; revDir = 1; revExt = x }
        }
    }

    private fun breakContinuity() {
        prevSpeed = Double.NaN
        prevSteer = Double.NaN
        prevSteady = false
        inBrakeEvent = false
        revInit = false
    }

    private fun discardWindow() {
        winStart = NONE
        winSamples = 0
        winSteady = 0
        winCruise = 0
        steadyDurS = 0.0
        steerDurS = 0.0
        reversals = 0
        corrSum = 0.0
        corrN = 0
        spdN = 0
        spdSum = 0.0
        spdSumSq = 0.0
        hardBrakes = 0
    }

    private fun closeWindow() {
        if (winStart == NONE) return
        val minSteadyS = config.styleWindowS * MIN_STEADY_FRACTION
        val qualifies = winSteady * 2 >= winSamples && steadyDurS >= minSteadyS
        if (!qualifies) {
            // 준비 구간 표본으로 쓰지 않는다
            if (!warmupDone) warmupDeltaN = winDeltaStart
        } else if (!warmupDone) {
            warmupCount++
            if (warmupCount >= warmupWindows) finishWarmup()
        } else {
            feature[REV] = if (steerDurS >= MIN_STEER_S) reversals / (steerDurS / 60.0) else Double.NaN
            feature[CORR] = if (corrN >= MIN_CORRECTIONS) corrSum / corrN else Double.NaN
            feature[SPEED] = if (spdN >= MIN_POINTS) {
                val varSum = spdSumSq - spdSum * spdSum / spdN
                sqrt(maxOf(0.0, varSum / (spdN - 1)))
            } else Double.NaN
            feature[BRAKE] = hardBrakes.toDouble()
            val cruiseWindow = winCruise * 2 >= winSteady && winSteady > 0
            if (!baselineReady) addToBaseline() else scoreWindow(cruiseWindow)
        }
        discardWindow()
    }

    private fun finishWarmup() {
        warmupDone = true
        if (warmupDeltaN > 0) {
            steerScale = EngineMath.median(warmupDeltas, warmupDeltaN)
            reversalGap = maxOf(config.steeringReversalGapDeg, config.steeringGapFactor * steerScale)
        }
    }

    private fun addToBaseline() {
        for (j in 0 until F) {
            val x = feature[j]
            if (!x.isNaN()) base[j][baseCount[j]++] = x
        }
        baseWindowCount++
        if (baseWindowCount < baselineWindows) return
        var anyCore = false
        for (j in 0 until F) {
            val n = baseCount[j]
            enabled[j] = n >= MIN_BASELINE_VALUES
            if (!enabled[j]) continue
            med[j] = EngineMath.median(base[j], n)
            val robust = 1.4826 * EngineMath.mad(base[j], n, med[j])
            scale[j] = maxOf(robust, REL_FLOOR * abs(med[j]), absFloor(j))
            if (j != BRAKE) anyCore = true
        }
        if (anyCore) {
            baselineReady = true
        } else {
            // 조향·차속 특징이 하나도 없으면(값이 계속 null 등) 처음부터 다시 모은다
            baseCount.fill(0)
            baseWindowCount = 0
        }
    }

    /** 특징별 척도 하한. 조향은 이 차의 조향 변화 크기에 비례(단위 무관), 나머지는 고정값 */
    private fun absFloor(j: Int): Double = when (j) {
        REV -> 1.0
        CORR -> if (steerScale.isNaN() || steerScale <= 0.0) 0.2 else 0.25 * steerScale
        SPEED -> 0.5
        else -> 0.5
    }

    private fun scoreWindow(cruiseWindow: Boolean) {
        val a = config.styleEwmaAlpha
        var sum = 0.0
        var w2 = 0.0
        for (j in 0 until F) {
            val x = feature[j]
            if (!enabled[j] || x.isNaN()) continue
            val z = ((x - med[j]) / scale[j]).coerceIn(Z_MIN, Z_MAX)
            // 크루즈 중에는 조향 증거만 줄인다 (분모는 원래 가중치 그대로라야 실제로 덜 믿게 된다)
            val m = if (cruiseWindow && (j == REV || j == CORR)) config.cruiseSteeringWeight else 1.0
            sum += m * WEIGHT[j] * z
            w2 += WEIGHT[j] * WEIGHT[j]
            zEwma[j] = (1 - a) * zEwma[j] + a * z
        }
        if (w2 == 0.0) return
        val sw = sum / sqrt(w2)
        score = (1 - a) * score + a * sw
        recentFlags[recentIdx] = sw >= PERSIST_Z
        recentIdx = (recentIdx + 1) % PERSIST_WINDOWS
        scoredWindows++
    }

    /** 테스트용: 기준선 중앙값 */
    internal fun baselineMedian(j: Int): Double = med[j]

    /** 테스트용: 현재 지수이동평균 점수 */
    internal val rawScore: Double get() = score

    companion object {
        const val F = 4
        const val REV = 0
        const val CORR = 1
        const val SPEED = 2
        const val BRAKE = 3

        private val WEIGHT = doubleArrayOf(1.0, 1.0, 0.8, 0.5)
        private const val REL_FLOOR = 0.2
        private const val Z_MIN = -2.0
        private const val Z_MAX = 4.0
        private const val REASON_Z = 1.5
        private const val MIN_STEADY_FRACTION = 0.5
        private const val MIN_STEER_S = 20.0
        private const val MIN_POINTS = 15
        private const val MIN_CORRECTIONS = 5
        private const val MIN_BASELINE_VALUES = 5
        private const val MAX_GAP_S = 3.0
        private const val BACKJUMP_MS = 5_000L
        private const val HARD_BRAKE_MIN_KPH = 30.0
        private const val WARMUP_CAP = 2_000
        private const val PERSIST_WINDOWS = 5
        private const val PERSIST_MIN = 3
        private const val PERSIST_Z = 2.0
    }
}
