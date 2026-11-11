package dev.shimpyo.engine

import dev.shimpyo.engine.EngineMath.NONE
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor

/**
 * 피로 게이지 = 두 시계 중 먼저 바닥나는 쪽까지 남은 분. docs/research/ai-science.md 1장, 8-3, 9-2.
 *
 * ① 휴식 리듬 시계 (시간-과업 피로): 마지막 15분 이상 휴식 뒤 연속 운전. 기본 2시간 (AI §2).
 *    그 시간에 운전한 1분을 가중해서 센다 — 02~06시 ×2(연속 1시간이면 NOW, AI §8-3 L1), 14~16시 ×1.2,
 *    깨어 있은 지 17시간 이상 ×1/0.8, 크루즈 단조로움 ×1/0.9. 지난밤 수면 6시간 미만이면 2시간 기준 자체를 ×0.8, 5시간 미만 ×0.8².
 *    시간대 경계에서 단계가 갑자기 뛰지 않게 적분으로 쌓는다.
 * ② 졸림 사전 곡선 (Three-Process Model, Åkerstedt 2004): KSS ≈ 10.6 − 0.6(S + C).
 *    S = 2.4 + (14.3 − 2.4)·e^(−0.0353·깨어 있은 시간), C = 2.5·cos(2π(시각 − 16.8)/24).
 *    KSS 사전값이 6에 닿을 때까지 남은 분. 인구 평균 곡선이고 개인 예측이 아니다.
 *    휴식은 시간-과업 피로만 줄이고 졸림은 못 없애므로(AI §2) ②는 휴식으로 0이 되지 않는다.
 *    대신 휴식 뒤 60분은 다시 묻지 않는다(카페인·짧은 낮잠 효과 약 1시간 뒤 재평가).
 *
 * 단계: 남은 30분 이하 WATCH, 20분 이하 SOON, 0 NOW(= 휴식 권고, AI의 L1).
 * 운전 패턴 편차(보조): 0.6 이상 + 편차 지속이면 한 단계 앞당기되 NOW를 넘지 않는다 (편차만으로 L1을 넘기지 않음).
 * "졸려"·상태 확인 "응": 휴식 전까지 NOW이고 '강한 권고'(커피 뒤 15분 이내 낮잠, 운전 교대).
 * 내기순환은 피로를 올리지 않는다 (환기 권유는 엔진이 따로 낸다).
 */
internal class FatigueModel(private val config: CoachConfig, private val local: LocalClock) {

    class Eval(
        val gauge: Gauge,
        /** 스타일·음성 반영 전 단계 */
        val baseLevel: Level,
        val advancedByStyle: Boolean,
        /** 강한 권고 (졸림 자기보고, KSS 사전값 ≥ 7, NOW + 강한 편차 지속) */
        val strong: Boolean,
        val kssNow: Double,
        /** 휴식 리듬 시계의 남은 분 */
        val rhythmMin: Double,
        /** 졸림 사전 곡선의 남은 분 (24시간 안에 닿지 않으면 무한대) */
        val sleepyCurveMin: Double,
        val recircMin: Int,
    )

    // ms 단위로 쌓는다 (분 단위 실수 누적은 120.0이 119.99999가 되는 경계 오차가 생긴다)
    private var drivingMs = 0L
    private var effMs = 0.0

    /** 마지막 휴식 이후 실제 주행 분 */
    val drivingMin: Double get() = drivingMs / 60_000.0

    /** 가중한 유효 운전 분 (휴식 리듬 시계) */
    val effMin: Double get() = effMs / 60_000.0

    var sleepyVoiced: Boolean = false

    /** 기상 시각 (엔진 시각). 엔진이 프로필에서 정한다 */
    var wakeMono: Long = NONE

    /** 지금 단조로운 주행인지 (크루즈 + 고속). 엔진이 매 update 정한다 */
    var monotony: Boolean = false

    private var recircSince = NONE
    private var reevalUntil = NONE

    // 졸림 곡선 캐시 (분 단위로만 다시 계산)
    private var curveCacheMinute = Long.MIN_VALUE
    private var curveCacheWake = Long.MIN_VALUE
    private var curveCacheValue = Double.POSITIVE_INFINITY

    private val boundaries: IntArray = buildList {
        add(0)
        add(1440)
        for (w in listOf(config.nightCircadianWindow, config.afternoonCircadianWindow)) {
            if (!w.isEmpty()) { add(w.first.coerceIn(0, 1440)); add((w.last + 1).coerceIn(0, 1440)) }
        }
    }.distinct().sorted().toIntArray()

    fun circadianRate(minuteOfDay: Int): Double = when (minuteOfDay) {
        in config.nightCircadianWindow -> config.nightCircadianFactor
        in config.afternoonCircadianWindow -> config.afternoonCircadianFactor
        else -> 1.0
    }

    private fun rate(minuteOfDay: Int, awakeH: Double, monotonyOn: Boolean): Double {
        var r = circadianRate(minuteOfDay)
        if (awakeH >= config.awakeLongHours) r /= config.awakeLongFactor
        if (monotonyOn) r /= config.monotonyFactor
        return r
    }

    fun awakeHours(mono: Long): Double = if (wakeMono == NONE) 0.0 else maxOf(0L, mono - wakeMono) / 3_600_000.0

    /** dtMs 동안 운전했으면 쌓는다. 가중은 그 구간의 가운데 시각으로 정한다 */
    fun advance(dtMs: Long, driving: Boolean, wallNow: Long, mono: Long) {
        if (!driving || dtMs <= 0L) return
        val ms = minOf(dtMs, config.maxUpdateGapS * 1000L)
        drivingMs += ms
        effMs += ms * rate(local.minuteOfDay(wallNow - ms / 2), awakeHours(mono - ms / 2), monotony)
    }

    /** 15분 이상 휴식: 휴식 리듬을 0으로, 졸림 곡선은 60분 뒤 다시 본다 */
    fun onRest(mono: Long) {
        drivingMs = 0L
        effMs = 0.0
        sleepyVoiced = false
        reevalUntil = mono + config.countermeasureReevalMin * 60_000L
    }

    fun onCabin(cabin: CabinState, mono: Long) {
        val hvac = cabin.hvacOn
        val rec = cabin.recirculation
        if (hvac == false || rec == false) recircSince = NONE
        else if (hvac == true && rec == true && recircSince == NONE) recircSince = mono
        // null은 모름: 직전 상태 유지
    }

    fun recircMin(mono: Long): Double = if (recircSince == NONE) 0.0 else maxOf(0L, mono - recircSince) / 60_000.0

    /** 지난밤 수면에 따른 휴식 리듬 배수: 6시간 미만 ×0.8, 5시간 미만 ×0.8² */
    fun sleepFactor(sleepHours: Float?): Double = when {
        sleepHours == null -> 1.0
        sleepHours < config.veryShortSleepHours -> config.shortSleepFactor * config.shortSleepFactor
        sleepHours < config.shortSleepHours -> config.shortSleepFactor
        else -> 1.0
    }

    /** KSS 사전값 (Three-Process Model 단순형) */
    fun kss(awakeH: Double, hourOfDay: Double): Double {
        val s = config.tpmLowerAsymptote + (config.tpmUpperAsymptote - config.tpmLowerAsymptote) * exp(-config.tpmDecayPerHour * awakeH)
        val c = config.tpmCircadianAmplitude * cos(2 * PI * (hourOfDay - config.tpmCircadianPeakHour) / 24.0)
        return 10.6 - 0.6 * (s + c)
    }

    /** 지금부터 KSS 사전값이 threshold에 닿을 때까지 분 (1분 간격으로 24시간 앞까지 본다). 닿지 않으면 무한대 */
    fun minutesUntilKss(threshold: Double, wallNow: Long, mono: Long): Double {
        val minuteKey = Math.floorDiv(wallNow, 60_000L)
        if (minuteKey == curveCacheMinute && wakeMono == curveCacheWake && threshold == config.tpmNowKss) return curveCacheValue
        val mod = local.minuteOfDay(wallNow)
        val awake0 = awakeHours(mono)
        var result = Double.POSITIVE_INFINITY
        for (m in 0..1440) {
            val h = ((mod + m) % 1440) / 60.0
            if (kss(awake0 + m / 60.0, h) >= threshold) { result = m.toDouble(); break }
        }
        if (threshold == config.tpmNowKss) {
            curveCacheMinute = minuteKey
            curveCacheWake = wakeMono
            curveCacheValue = result
        }
        return result
    }

    /** wallNow부터 유효 운전 effNeeded분이 쌓이는 데 걸리는 실제 분 (앞으로의 시간대·각성 시간 가중을 따라간다) */
    fun projectWallMin(effNeeded: Double, wallNow: Long, awakeNowH: Double, monotonyOn: Boolean): Double {
        if (effNeeded <= 0.0) return 0.0
        var remaining = effNeeded
        var elapsed = 0.0
        var mod = local.minuteOfDay(wallNow).toDouble()
        var awake = awakeNowH
        var guard = 0
        while (remaining > 1e-9 && guard++ < 400) {
            val r = rate(floor(mod).toInt().coerceIn(0, 1439), awake, monotonyOn)
            var next = 1440.0
            for (b in boundaries) if (b > mod) { next = b.toDouble(); break }
            var span = next - mod
            if (wakeMono != NONE && awake < config.awakeLongHours) span = minOf(span, (config.awakeLongHours - awake) * 60.0)
            if (span <= 1e-9) span = 1e-6
            val eff = span * r
            if (eff >= remaining) {
                elapsed += remaining / r
                remaining = 0.0
            } else {
                elapsed += span
                remaining -= eff
                mod += span
                if (mod >= 1440.0) mod -= 1440.0
                awake += span / 60.0
            }
        }
        return elapsed
    }

    /** 휴식 직후(wallStart, 그때 깨어 있은 시간 awakeH)부터 휴식 리듬이 바닥날 때까지 실제 분. 피트스톱 구간 상한 */
    fun fullRhythmWallMin(wallStart: Long, awakeH: Double, sleepHours: Float?): Double =
        projectWallMin(config.fatigueNowMin * sleepFactor(sleepHours), wallStart, awakeH, false)

    fun evaluate(
        wallNow: Long,
        mono: Long,
        deviation: Double?,
        persistent: Boolean,
        styleMuted: Boolean,
        sleepHours: Float?,
        texts: CoachTexts,
    ): Eval {
        val allowance = config.fatigueNowMin * sleepFactor(sleepHours)
        val awakeH = awakeHours(mono)
        val rhythm = projectWallMin(allowance - effMin, wallNow, awakeH, monotony)
        var curve = minutesUntilKss(config.tpmNowKss, wallNow, mono)
        if (reevalUntil != NONE && mono < reevalUntil) curve = maxOf(curve, (reevalUntil - mono) / 60_000.0)
        val kssNow = kss(awakeH, local.minuteOfDay(wallNow) / 60.0)
        var remaining = minOf(rhythm, curve)
        val watchBand = (config.fatigueNowMin - config.fatigueWatchMin).toDouble()
        val soonBand = (config.fatigueNowMin - config.fatigueSoonMin).toDouble()
        fun levelOf(min: Double) = when {
            min <= 0.0 -> Level.NOW
            min <= soonBand -> Level.SOON
            min <= watchBand -> Level.WATCH
            else -> Level.OK
        }
        val base = levelOf(remaining)
        var level = base
        var advanced = false
        val styleOn = deviation != null && persistent && !styleMuted
        if (styleOn && deviation!! >= config.styleAdvanceDeviation && level != Level.NOW) {
            level = Level.entries[level.ordinal + 1]
            advanced = true
            remaining = minOf(remaining, when (level) {
                Level.WATCH -> watchBand
                Level.SOON -> soonBand
                else -> 0.0
            })
        }
        if (sleepyVoiced) {
            level = Level.NOW
            remaining = 0.0
        }
        val strong = sleepyVoiced || kssNow >= config.tpmStrongKss ||
            (level == Level.NOW && styleOn && deviation!! >= config.styleCheckInDeviation)
        val minutes = floor(maxOf(0.0, remaining) + 1e-6).toInt()

        val reasons = ArrayList<Reason>(5)
        if (sleepyVoiced) reasons += Reason("FATIGUE_VOICED", texts.reasonFatigueVoiced())
        if (strong) reasons += Reason("FATIGUE_STRONG", texts.reasonFatigueStrong())
        if (advanced) reasons += Reason("FATIGUE_STYLE", texts.reasonFatigueStyle())
        val drove = floor(drivingMin).toInt()
        if (drove >= 1) reasons += Reason("FATIGUE_DRIVING", texts.reasonFatigueDriving(drove))
        if (curve < rhythm && wakeMono != NONE && curve <= watchBand) {
            reasons += Reason("FATIGUE_BODY_CLOCK", texts.reasonFatigueBodyClock(floor(awakeH).toInt()))
        }
        val mod = local.minuteOfDay(wallNow)
        if (mod in config.nightCircadianWindow) reasons += Reason("FATIGUE_NIGHT", texts.reasonFatigueNight())
        else if (mod in config.afternoonCircadianWindow) reasons += Reason("FATIGUE_AFTERNOON", texts.reasonFatigueAfternoon())
        if (wakeMono != NONE && awakeH >= config.awakeLongHours) reasons += Reason("FATIGUE_AWAKE_LONG", texts.reasonFatigueAwakeLong(floor(awakeH).toInt()))
        if (sleepHours != null && sleepHours < config.shortSleepHours) reasons += Reason("FATIGUE_SHORT_SLEEP", texts.reasonFatigueShortSleep(sleepHours))
        if (monotony) reasons += Reason("FATIGUE_MONOTONY", texts.reasonFatigueMonotony())

        return Eval(
            gauge = Gauge(minutes, level, reasons),
            baseLevel = base,
            advancedByStyle = advanced,
            strong = strong,
            kssNow = kssNow,
            rhythmMin = rhythm,
            sleepyCurveMin = curve,
            recircMin = floor(recircMin(mono)).toInt(),
        )
    }
}
