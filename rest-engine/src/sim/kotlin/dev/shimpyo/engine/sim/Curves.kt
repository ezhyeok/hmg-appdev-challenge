package dev.shimpyo.engine.sim

import dev.shimpyo.engine.CabinState
import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.DrivingSample
import dev.shimpyo.engine.DrivingStyleAnalyzer
import dev.shimpyo.engine.EngineClock
import dev.shimpyo.engine.FatigueModel
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.LocalClock
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.RestroomNeedModel
import dev.shimpyo.engine.TripProfile
import dev.shimpyo.engine.TurnSignal
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.random.Random

/** (A) 엔진 곡선: 화장실 필요 곡선, 개인화, 운전 패턴 편차, 피로 시계. 모두 엔진 클래스를 그대로 호출한다. */
internal object Curves {
    private const val MIN = 60_000L
    private val config = CoachConfig()
    private val tGrid = (0..240 step 5).toList()

    private fun kst(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, config.zoneId).toInstant().toEpochMilli()

    fun build(): Map<String, Any?> = obj(
        "generatedBy" to "rest-engine/src/sim (./gradlew -p rest-engine runSim), 엔진 코드 그대로 (커밋 0ee4de0 설정)",
        "units" to "시간은 분, 확률은 0~1",
        "needCdf" to needCdf(),
        "personalization" to personalization(),
        "drivingStyle" to drivingStyle(),
        "fatigue" to fatigue(),
    )

    // ------------------------------------------------------------ 1. 필요 곡선

    private fun model(occupants: List<Occupant>, t0: Long, memory: PersonalMemory = PersonalMemory()): RestroomNeedModel {
        val clock = EngineClock().apply { advance(t0) }
        return RestroomNeedModel(config).apply { sync(TripProfile(occupants, tripStart = t0), clock, memory) }
    }

    private fun needCdf(): Map<String, Any?> {
        val t0 = kst(2026, 9, 24, 9, 0)
        val types = listOf(
            "ADULT" to Occupant("x", OccupantType.ADULT),
            "ELDERLY" to Occupant("x", OccupantType.ELDERLY),
            "CHILD_4" to Occupant("x", OccupantType.CHILD, ageYears = 4),
            "CHILD_7" to Occupant("x", OccupantType.CHILD, ageYears = 7),
            "PREGNANT" to Occupant("x", OccupantType.PREGNANT),
            "OAB" to Occupant("x", OccupantType.OAB),
            "INFANT" to Occupant("x", OccupantType.INFANT),
        )
        val series = LinkedHashMap<String, Any?>()
        for ((name, o) in types) {
            val m = model(listOf(o), t0)
            val tr = m.tracked.single()
            val cap = tr.capMin.takeIf { it.isFinite() }
            series[name] = obj(
                "priorMedianMin" to m.priorMedianMin(o),
                "gauge50Min" to tr.thresholdMin,
                "decision30Min" to m.deadlineMin(t0, config.restroomDecisionProbability, fromReset = true),
                "quantile50UncappedMin" to tr.posterior.quantile(0.5),
                "quantile30UncappedMin" to tr.posterior.quantile(0.3),
                "capMin" to cap,
                "cdf" to tGrid.map { 1.0 - tr.posterior.survival(it.toDouble()) },
                "note" to when (o.type) {
                    OccupantType.INFANT -> "영아 곡선은 화장실이 아니라 수유·기저귀 간격(1~6개월 3시간). 정차 상한은 카시트 2시간(capMin)"
                    OccupantType.ELDERLY, OccupantType.PREGNANT -> "걷기 권고로 정차 간 상한 2시간(capMin). 게이지·판단 시각은 상한을 넘지 않는다"
                    else -> null
                },
            )
        }
        return obj(
            "description" to "마지막 화장실 이후 t분까지 필요해질 확률 P(need ≤ t) = 1 − S(t), 사전예측분포 S(t) = (b/(b + t^k))^a, " +
                "k = ${config.restroomWeibullShape}, a0 = ${config.restroomPriorStrength}, b0 = m0^k / (2^(1/a0) − 1). " +
                "gauge50 = 화면 게이지가 0이 되는 시각(필요 확률 0.5, 상한 적용), decision30 = 마지막 기회·계획 판단 시각(0.3)",
            "tMin" to tGrid,
            "series" to series,
        )
    }

    // ------------------------------------------------------------ 2. 개인화

    private fun personalization(): Map<String, Any?> {
        val k = config.restroomWeibullShape
        val trueMedian = 100.0
        val lambda = trueMedian / ln(2.0).pow(1.0 / k)
        fun drawFrom(r: Random): Double = lambda * (-ln(1.0 - r.nextDouble())).pow(1.0 / k)
        fun sampleMedian(xs: List<Double>): Double = xs.sorted().let { (it[5] + it[6]) / 2.0 }
        // 예시 경로: 시드 1부터 차례로 보며 12개 표본의 중앙값이 실제 중앙값 ±5% 안에 드는 첫 시드 (전형적인 한 사람)
        var exampleSeed = 1
        while (sampleMedian(Random(exampleSeed).let { r -> List(12) { drawFrom(r) } }) !in 95.0..105.0) exampleSeed++
        val intervals = Random(exampleSeed).let { r -> List(12) { drawFrom(r) } }

        // 앙상블: 같은 실제 분포를 따르는 가상의 성인 1,000명 (시드 1..1000), n번째 관측 뒤 사후 중앙값의 분포
        val people = 1_000
        val ens = Array(13) { DoubleArray(people) }
        for (pIdx in 0 until people) {
            val xs = Random(pIdx + 1).let { r -> List(12) { drawFrom(r) } }
            for (n in 0..12) {
                ens[n][pIdx] = dev.shimpyo.engine.NeedPosterior.posterior(
                    config.priorAdultMin, k, config.restroomPriorStrength, xs.take(n), null, null,
                    config.restroomSuggestedWeight, config.restroomMemoryMax,
                ).quantile(0.5)
            }
        }
        fun q(xs: DoubleArray, p: Double): Double = xs.sortedArray().let { it[((it.size - 1) * p).toInt()] }
        val ensemble = (0..12).map { n ->
            obj(
                "n" to n,
                "posteriorMedianMean" to ens[n].average(),
                "p10" to q(ens[n], 0.1),
                "p50" to q(ens[n], 0.5),
                "p90" to q(ens[n], 0.9),
                "gaugeAt60MinMean" to ens[n].map { maxOf(0.0, it - 60.0) }.average(),
            )
        }

        // 중도절단 3회: 목적지(45~110분)에 먼저 닿아 필요가 오기 전에 끝난 회차
        val crng = Random(77)
        val censored = ArrayList<Double>()
        while (censored.size < 3) {
            val need = lambda * (-ln(1.0 - crng.nextDouble())).pow(1.0 / k)
            val trip = 45.0 + 65.0 * crng.nextDouble()
            if (need > trip) censored += trip
        }

        val t0 = kst(2026, 9, 24, 9, 0)
        val me = Occupant("me", OccupantType.ADULT)
        fun row(n: Int, withCensored: Boolean): Map<String, Any?> {
            val mem = PersonalMemory(
                restroomIntervalsMin = mapOf("me" to intervals.take(n).reversed()),
                restroomCensoredMin = if (withCensored) mapOf("me" to censored.reversed()) else emptyMap(),
            )
            val m = model(listOf(me), t0, mem)
            val tr = m.tracked.single()
            return obj(
                "n" to n,
                "posteriorMedianMin" to tr.posterior.quantile(0.5),
                "gaugeAt60MinShown" to m.gauge(t0 + 60 * MIN, dev.shimpyo.engine.CoachTexts()).minutes,
                "decision30Min" to m.deadlineMin(t0, config.restroomDecisionProbability, fromReset = true),
                "a" to tr.posterior.a,
            )
        }
        fun cdfAfter(n: Int): List<Double> {
            val mem = PersonalMemory(restroomIntervalsMin = mapOf("me" to intervals.take(n).reversed()))
            val tr = model(listOf(me), t0, mem).tracked.single()
            return tGrid.map { 1.0 - tr.posterior.survival(it.toDouble()) }
        }
        val plain = (0..12).map { row(it, false) }
        val withC = (0..12).map { row(it, true) }
        return obj(
            "description" to "가상의 성인: 실제 간격은 와이블 k=2.5, 중앙값 ${trueMedian.toInt()}분 (엔진 사전 중앙값 ${config.priorAdultMin.toInt()}분). " +
                "간격 12개를 시드 고정으로 뽑아 하나씩 엔진의 베이즈 갱신(RestroomNeedModel)에 넣었다",
            "trueMedianMin" to trueMedian,
            "priorMedianMin" to config.priorAdultMin,
            "exampleSeed" to exampleSeed,
            "exampleSeedRule" to "시드 1부터 차례로, 12개 표본의 중앙값이 95~105분(실제 중앙값 ±5%)인 첫 시드. 한 사람의 전형적인 경로를 보이기 위한 것이고, " +
                "사람마다의 흩어짐은 ensemble에 있다",
            "intervalsMin" to intervals,
            "afterN" to plain,
            "cdf" to obj(
                "tMin" to tGrid,
                "n0" to cdfAfter(0),
                "n3" to cdfAfter(3),
                "n6" to cdfAfter(6),
                "n12" to cdfAfter(12),
                "truth" to tGrid.map { 1.0 - kotlin.math.exp(-(it / lambda).pow(k)) },
            ),
            "ensemble" to obj(
                "description" to "가상의 성인 1,000명(시드 1~1000), 사람마다 12개 간격. n번째 관측 뒤 사후 중앙값의 평균과 10·50·90분위. " +
                    "사전 강도 a0 = 3이라 12회 뒤에도 사전값(150) 쪽으로 약간 끌린다",
                "people" to people,
                "afterN" to ensemble,
            ),
            "censoredVariant" to obj(
                "censoredMin" to censored,
                "description" to "같은 12개 사건 + 중도절단 3회(필요가 오기 전에 목적지에 닿은 회차, 시드 77). 중도절단은 b에만 c^k를 더하고 a는 그대로라 " +
                    "중앙값을 늘리는 쪽으로만 움직인다",
                "afterN" to withC,
                "medianShiftAtN12Min" to ((withC.last()["posteriorMedianMin"] as Double) - (plain.last()["posteriorMedianMin"] as Double)),
                "medianShiftAtN0Min" to ((withC.first()["posteriorMedianMin"] as Double) - (plain.first()["posteriorMedianMin"] as Double)),
            ),
        )
    }

    // ------------------------------------------------------------ 3. 운전 패턴 편차

    private fun drivingStyle(): Map<String, Any?> {
        val start = kst(2026, 11, 13, 10, 0)
        val engine = CoachEngine(config, TripProfile(listOf(Occupant("me", OccupantType.ADULT)), tripStart = start), random = Random(5))
        val mirror = DrivingStyleAnalyzer(config) // 엔진 안의 분석기와 같은 입력 → 내부 지수이동평균 S를 읽기 위해
        val rnd = Random(424242)
        val minutes = ArrayList<Map<String, Any?>>()
        var firstAdvance: Int? = null
        var firstCheckIn: Int? = null
        var firstPersistent: Int? = null
        var firstOverThreshold: Int? = null
        var brakeAt = -1L
        val ucl = 3.0 * kotlin.math.sqrt(config.styleEwmaAlpha / (2.0 - config.styleEwmaAlpha))
        for (sec in 1..3600) {
            val t = start + sec * 1000L
            val minute = (sec - 1) / 60
            val degraded = minute >= 40
            // 조향: 앱이 보내는 앞바퀴/핸들 각을 0.1도 단위로 (부호는 무관)
            val steerRaw = if (!degraded) {
                1.2 * sin(2 * PI * sec / 24.0) + nextGaussian(rnd) * 0.25
            } else {
                3.5 * sin(2 * PI * sec / 5.0) + 1.5 * sin(2 * PI * sec / 13.0) + nextGaussian(rnd) * 0.8
            }
            val steer = round(steerRaw * 10.0) / 10.0
            var speed = if (!degraded) 100.0 + nextGaussian(rnd) * 0.7 else 100.0 + 4.0 * sin(2 * PI * sec / 45.0) + nextGaussian(rnd) * 2.0
            var brake = false
            // 50분에 급제동 한 번: 3초 동안 100 → 70km/h, 10초에 걸쳐 회복
            if (sec == 50 * 60) brakeAt = sec.toLong()
            if (brakeAt > 0) {
                val dt = sec - brakeAt
                if (dt in 0..2) { speed = 100.0 - 10.0 * (dt + 1); brake = true } else if (dt in 3..12) speed = 70.0 + 3.0 * (dt - 2)
            }
            val sample = DrivingSample(t, speed.toFloat(), steer.toFloat(), brake, TurnSignal.NONE, false)
            mirror.onSample(sample)
            // 상태 확인에는 대답하지 않는다 ("아니"라고 하면 20분 동안 패턴 편차를 빼므로, 편차가 계속 보이게)
            val out = engine.update(CoachInput(now = t, gear = Gear.DRIVE, speedKph = speed.toFloat(), samples = listOf(sample)))
            if (out.prompt?.kind == PromptKind.CHECK_IN && firstCheckIn == null) firstCheckIn = minute
            if (sec % 60 == 0) {
                val dev = out.style.deviation
                check(dev == mirror.deviation) { "엔진 안의 편차와 거울 분석기가 다르다: $dev vs ${mirror.deviation}" }
                val advanced = out.fatigue.reasons.any { it.code == "FATIGUE_STYLE" }
                val m = minute + 1
                if (advanced && firstAdvance == null) firstAdvance = m
                if (out.style.persistent && firstPersistent == null) firstPersistent = m
                if (dev != null && dev >= config.styleAdvanceDeviation && firstOverThreshold == null) firstOverThreshold = m
                minutes += obj(
                    "minute" to m,
                    "phase" to if (minute < 20) "baseline" else if (minute < 40) "normal" else "degraded",
                    "baselineReady" to out.style.baselineReady,
                    "ewmaS" to if (out.style.baselineReady) mirror.rawScore else null,
                    "deviation" to dev,
                    "persistent" to out.style.persistent,
                    "fatigueLevel" to out.fatigue.level.name,
                    "fatigueMinutes" to out.fatigue.minutes,
                    "advancedByStyle" to advanced,
                    "reasonCodes" to out.style.reasons.map { it.code },
                    "prompt" to out.prompt?.kind?.name,
                )
            }
        }
        return obj(
            "description" to "가상의 60분 고속도로 주행(100km/h, 10:00 출발, 1초 표본 = 앱 형식). 0~20분 기준 구간, 20~40분 평소, 40~60분 흐트러짐" +
                "(조향 반전·보정 크기·차속 변동 증가, 50분에 급제동 1회). 엔진은 정속 첫 ${config.styleWarmupMin}분을 준비 구간, " +
                "그다음 ${config.styleBaselineMin}분을 기준선으로 쓰므로 기준선은 25분 무렵 생긴다",
            "thresholds" to obj(
                "deviationAdvance" to config.styleAdvanceDeviation,
                "deviationStrong" to config.styleCheckInDeviation,
                "ewmaLambda" to config.styleEwmaAlpha,
                "ewmaUcl3Sigma" to ucl,
                "deviationFormula" to "deviation = clamp(S / (2·UCL), 0, 1) → 0.5 = 3σ 관리한계",
                "persistence" to "최근 5개 창 중 3개 이상 S_w ≥ 2",
            ),
            "events" to obj(
                "baselineReadyAtMinute" to minutes.firstOrNull { it["baselineReady"] == true }?.get("minute"),
                "deviationOverAdvanceAtMinute" to firstOverThreshold,
                "persistentAtMinute" to firstPersistent,
                "stageAdvancedAtMinute" to firstAdvance,
                "checkInPromptAtMinute" to firstCheckIn?.plus(1),
                "note" to "출발 직후 혼자 60분 주행이라 시간 기준 피로는 OK. 운전 패턴 변화가 지속되면 한 단계(OK→WATCH) 앞당기고 상태 확인을 묻는다. " +
                    "편차만으로는 휴식 권고(NOW)를 넘기지 않는다",
            ),
            "perMinute" to minutes,
        )
    }

    // ------------------------------------------------------------ 4. 피로 시계

    private fun fatigue(): Map<String, Any?> {
        val start = kst(2026, 11, 13, 13, 0)
        fun run(restAtMin: Int?): List<Map<String, Any?>> {
            val local = LocalClock(config.zoneId)
            val fm = FatigueModel(config, local)
            val wake = kst(2026, 11, 13, 7, 0) // 기상 시각을 모를 때 엔진 기본값 (13시 출발 → 07:00)
            fm.wakeMono = wake
            val engine = CoachEngine(config, TripProfile(listOf(Occupant("me", OccupantType.ADULT)), tripStart = start, driverSleepHours = 6f), random = Random(1))
            val rows = ArrayList<Map<String, Any?>>()
            val totalMin = if (restAtMin == null) 180 else 195
            var prev = start
            var parkStart = -1L
            var restDone = false
            for (m in 0..totalMin) {
                val t = start + m * MIN
                val parked = restAtMin != null && m > restAtMin && m <= restAtMin + 15
                val gear = if (parked) Gear.PARK else Gear.DRIVE
                val out = engine.update(CoachInput(now = t, gear = gear, speedKph = if (parked) 0f else 100f, cabin = CabinState()))
                // 거울 피로 모델: 엔진과 같은 순서로 호출 (주행 시간 적립 → 15분 정차면 휴식)
                if (m > 0) fm.advance(t - prev, !parked, t, t)
                if (parked && parkStart < 0) parkStart = t
                if (!restDone && parkStart >= 0 && t - parkStart >= config.restMinParkedMin * MIN) { fm.onRest(parkStart + config.restMinParkedMin * MIN); restDone = true }
                prev = t
                val e = fm.evaluate(t, t, null, false, false, 6f, dev.shimpyo.engine.CoachTexts())
                check(e.gauge.minutes == out.fatigue.minutes && e.gauge.level == out.fatigue.level) {
                    "엔진과 거울 피로 모델이 다르다 at $m: ${out.fatigue} vs ${e.gauge}"
                }
                if (m % 5 == 0 || (restAtMin != null && m in restAtMin..restAtMin + 16)) {
                    rows += obj(
                        "minute" to m,
                        "clock" to String.format(java.util.Locale.ROOT, "%02d:%02d", 13 + m / 60, m % 60),
                        "parked" to parked,
                        "drivingMin" to fm.drivingMin,
                        "rhythmMinLeft" to e.rhythmMin,
                        "sleepinessCurveMinLeft" to e.sleepyCurveMin.takeIf { it.isFinite() },
                        "kssPrior" to e.kssNow,
                        "gaugeMinutes" to out.fatigue.minutes,
                        "level" to out.fatigue.level.name,
                        "reasonCodes" to out.fatigue.reasons.map { it.code },
                    )
                }
            }
            return rows
        }
        return obj(
            "description" to "혼자 13:00 출발, 지난밤 수면 6시간(엔진 기준 '6시간 미만'이 아니므로 수면 가중 없음), 기상 시각 미입력 → 07:00. " +
                "휴식 리듬 시계(2시간, 14~16시 ×1.2)와 졸림 사전 곡선(Three-Process Model, KSS 6까지 남은 분). 게이지 = 둘 중 짧은 쪽",
            "continuous" to run(null),
            "restAt110" to obj(
                "description" to "110분에 15분 휴식(기어 P). 휴식은 리듬 시계만 0으로 하고, 졸림 곡선은 60분 동안 다시 묻지 않는다",
                "rows" to run(110),
            ),
            "note" to "낮 13~16시에는 Three-Process 곡선이 KSS 6에 한참 못 미쳐(7시 기상 기준 자정 무렵 6) 게이지는 리듬 시계가 정한다",
        )
    }
}

internal fun nextGaussian(r: Random): Double {
    var u1 = r.nextDouble()
    if (u1 < 1e-300) u1 = 1e-300
    val u2 = r.nextDouble()
    return kotlin.math.sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * PI * u2)
}
