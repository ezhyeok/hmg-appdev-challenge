package dev.shimpyo.engine

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrivingStyleAnalyzerTest {
    private val cfg = CoachConfig()
    private val texts = CoachTexts()

    /** 준비 5분 + 기준선 20분 */
    private val readyMin = cfg.styleWarmupMin + cfg.styleBaselineMin

    private class Feed(val a: DrivingStyleAnalyzer, var t: Long = 1_000_000L, val rnd: Random = Random(3))

    private fun Feed.run(
        seconds: Int,
        speed: (Long) -> Float? = { 100f + (rnd.nextGaussian() * 0.6).toFloat() },
        steer: (Long) -> Float? = { normalSteer(it, rnd) },
        stepMs: Long = SEC,
        brake: Boolean? = false,
        cruise: Boolean? = false,
        signal: TurnSignal? = TurnSignal.NONE,
    ) {
        var i = 0L
        while (i < seconds * SEC) {
            t += stepMs
            a.onSample(DrivingSample(t, speed(t / 1000), steer(t / 1000), brake, signal, cruise))
            i += stepMs
        }
    }

    @Test
    fun notReadyUntilWarmupAndBaselineMinutes() {
        val f = Feed(DrivingStyleAnalyzer(cfg))
        f.run((readyMin - 1) * 60)
        assertFalse(f.a.baselineReady)
        assertNull(f.a.deviation)
        val out = f.a.output(texts)
        assertEquals("STYLE_BASELINE", out.reasons.single().code)
        assertTrue(out.reasons.single().text.contains("/${readyMin}분"), out.reasons.single().text)
        f.run(2 * 60)
        assertTrue(f.a.baselineReady)
        assertNotNull(f.a.deviation)
    }

    @Test
    fun ignoresLowSpeedStopsBrakingAndSignals() {
        val f = Feed(DrivingStyleAnalyzer(cfg))
        f.run(30 * 60, speed = { 65f }) // 70km/h 미만
        f.run(10 * 60, speed = { 0f }) // 정차
        f.run(30 * 60, brake = true) // 계속 제동
        f.run(30 * 60, signal = TurnSignal.LEFT) // 계속 방향지시등
        assertFalse(f.a.baselineReady)
    }

    @Test
    fun excludesTenSecondsAfterBrakeOrSignal() {
        val f = Feed(DrivingStyleAnalyzer(cfg))
        // 매 15초마다 제동 1초 → 그 뒤 10초도 빠지므로 정속 표본은 창의 1/3 남짓 → 창을 쓰지 않는다
        var sec = 0
        f.run((readyMin + 5) * 60, brake = null, speed = { 100f })
        val ready = f.a.baselineReady
        assertTrue(ready, "제동이 없으면 준비된다")
        val g = Feed(DrivingStyleAnalyzer(cfg))
        repeat((readyMin + 5) * 4) {
            g.run(1, brake = true, speed = { 100f })
            g.run(14, speed = { 100f })
            sec += 15
        }
        assertFalse(g.a.baselineReady, "$sec 초 동안 제동 후 10초 규칙에 막혔다")
    }

    @Test
    fun nullSpeedIsIgnored() {
        val f = Feed(DrivingStyleAnalyzer(cfg))
        f.run(40 * 60, speed = { null })
        assertFalse(f.a.baselineReady)
    }

    @Test
    fun sparseSamplesAreIgnored() {
        val f = Feed(DrivingStyleAnalyzer(cfg))
        f.run(60 * 60, stepMs = 5 * SEC) // 5초에 한 번
        assertFalse(f.a.baselineReady, "정속 구간이 이어지지 않으면 창을 쓰지 않는다")
    }

    @Test
    fun nullSteeringStillUsesSpeedFeature() {
        val f = Feed(DrivingStyleAnalyzer(cfg))
        f.run((readyMin + 1) * 60, steer = { null })
        assertTrue(f.a.baselineReady)
        assertTrue(f.a.steerScale.isNaN(), "조향이 없으면 조향 척도도 없다")
        f.run(10 * 60, steer = { null })
        assertTrue(f.a.deviation!! < 0.5)
    }

    @Test
    fun noCoreFeatureMeansNoBaseline() {
        val f = Feed(DrivingStyleAnalyzer(cfg))
        // 조향 없음 + 크루즈(차속 변동 의미 없음) → 급제동 특징만 남는다 → 기준선으로 쓰지 않는다
        f.run(50 * 60, steer = { null }, cruise = true)
        assertFalse(f.a.baselineReady)
    }

    @Test
    fun steadyDrivingStaysBelowControlLimit() {
        val f = Feed(DrivingStyleAnalyzer(cfg))
        f.run((readyMin + 1) * 60)
        f.run(40 * 60)
        assertTrue(f.a.scoredWindows >= 35)
        assertTrue(f.a.deviation!! < 0.5, "평소대로면 관리한계(0.5) 아래: ${f.a.deviation}")
        assertFalse(f.a.persistent)
    }

    @Test
    fun wobbleRaisesDeviationPersistentlyAndExplainsSteering() {
        val f = Feed(DrivingStyleAnalyzer(cfg))
        f.run((readyMin + 1) * 60)
        f.run(60, steer = { wobbleSteer(it, f.rnd) })
        assertFalse(f.a.persistent, "한 창만으로는 '편차 지속'이 아니다")
        f.run(11 * 60, steer = { wobbleSteer(it, f.rnd) })
        val dev = f.a.deviation!!
        assertTrue(dev >= cfg.styleAdvanceDeviation, "12분 흔들림 → $dev")
        assertTrue(f.a.persistent)
        val out = f.a.output(texts)
        assertTrue(out.persistent)
        val reason = out.reasons.single()
        assertTrue(reason.code.startsWith("STYLE_STEERING"), reason.code)
        assertTrue(reason.text.contains("핸들") && reason.text.contains("최근 10분"), reason.text)
    }

    @Test
    fun cruiseHalvesSteeringWeight() {
        fun scoreWith(cruise: Boolean): Double {
            val f = Feed(DrivingStyleAnalyzer(cfg), rnd = Random(11))
            f.run((readyMin + 1) * 60, cruise = cruise)
            f.run(5 * 60, steer = { wobbleSteer(it, f.rnd) }, cruise = cruise)
            return f.a.rawScore
        }
        val off = scoreWith(false)
        val on = scoreWith(true)
        assertTrue(on < off, "크루즈 중에는 조향 특징을 덜 믿는다: $on < $off")
    }

    @Test
    fun deviationRecoversWhenDrivingNormalizesAndResetsAfterRest() {
        val f = Feed(DrivingStyleAnalyzer(cfg))
        f.run((readyMin + 1) * 60)
        f.run(12 * 60, steer = { wobbleSteer(it, f.rnd) })
        val high = f.a.deviation!!
        f.run(20 * 60)
        assertTrue(f.a.deviation!! < high / 2, "정상으로 돌아오면 지수이동평균이 내려간다")
        f.run(12 * 60, steer = { wobbleSteer(it, f.rnd) })
        f.a.resetScore()
        assertEquals(0.0, f.a.deviation)
        assertFalse(f.a.persistent)
    }

    @Test
    fun reversalGapAdaptsToThisCarsSteeringScale() {
        val f = Feed(DrivingStyleAnalyzer(cfg))
        // 8초 주기 삼각파 ±3: 변화 크기 1.5 → gap = 2 × 1.5 = 3. 주기마다 반전 2번 → 15회/분
        val tri = doubleArrayOf(-3.0, -1.5, 0.0, 1.5, 3.0, 1.5, 0.0, -1.5)
        f.run((readyMin + 1) * 60, steer = { tri[(it % 8).toInt()].toFloat() }, speed = { 100f })
        assertTrue(f.a.baselineReady)
        assertEquals(1.5, f.a.steerScale, 1e-9)
        assertEquals(3.0, f.a.reversalGap, 1e-9)
        val rev = f.a.baselineMedian(DrivingStyleAnalyzer.REV)
        assertTrue(abs(rev - 15.0) <= 1.0, "반전율 $rev")
        assertEquals(1.5, f.a.baselineMedian(DrivingStyleAnalyzer.CORR), 1e-9)
    }

    @Test
    fun sameShapeInSmallerUnitsGivesSameReversalRate() {
        // 앞바퀴각처럼 단위가 1/15로 작아도 상대 gap이라 같은 반전율
        val tri = doubleArrayOf(-3.0, -1.5, 0.0, 1.5, 3.0, 1.5, 0.0, -1.5)
        val f = Feed(DrivingStyleAnalyzer(cfg))
        f.run((readyMin + 1) * 60, steer = { (tri[(it % 8).toInt()] / 15.0).toFloat() }, speed = { 100f })
        assertTrue(abs(f.a.baselineMedian(DrivingStyleAnalyzer.REV) - 15.0) <= 1.0)
    }

    @Test
    fun repeatedIdenticalValuesAreNotCorrections() {
        // SDK처럼 같은 값을 3초씩 다시 보내는 신호: 0,0,0,2,2,2,0,0,0,-2,-2,-2
        val held = doubleArrayOf(0.0, 0.0, 0.0, 2.0, 2.0, 2.0, 0.0, 0.0, 0.0, -2.0, -2.0, -2.0)
        val f = Feed(DrivingStyleAnalyzer(cfg))
        f.run((readyMin + 1) * 60, steer = { held[(it % 12).toInt()].toFloat() }, speed = { 100f })
        assertTrue(f.a.baselineReady)
        assertEquals(2.0, f.a.steerScale, 1e-9, "반복을 빼면 변화 크기는 2도")
        assertEquals(2.0, f.a.baselineMedian(DrivingStyleAnalyzer.CORR), 1e-9, "반복이 보정 크기를 희석하지 않는다")
        // gap 4: +2 → −2 (4도)만 반전 → 12초에 2번 = 10회/분
        assertTrue(abs(f.a.baselineMedian(DrivingStyleAnalyzer.REV) - 10.0) <= 1.0, "${f.a.baselineMedian(DrivingStyleAnalyzer.REV)}")
    }

    @Test
    fun smallJitterBelowGapIsNotAReversal() {
        val f = Feed(DrivingStyleAnalyzer(cfg))
        val jitter = doubleArrayOf(0.0, 0.4, 0.0, 0.4) // 변화 0.4 → gap 0.8
        f.run((readyMin + 1) * 60, steer = { jitter[(it % 4).toInt()].toFloat() }, speed = { 100f })
        assertEquals(0.0, f.a.baselineMedian(DrivingStyleAnalyzer.REV))
    }

    @Test
    fun hardBrakeIsCountedOncePerEvent() {
        val f = Feed(DrivingStyleAnalyzer(cfg))
        f.run((readyMin + 1) * 60)
        assertTrue(f.a.baselineReady)
        // 매분 한 번 100 → 70km/h로 3초 동안 급감속 (10km/h/s), 다시 회복
        repeat(10) {
            var v = 100.0
            f.run(3, speed = { v -= 10.0; v.toFloat() })
            f.run(3, speed = { v += 10.0; v.toFloat() })
            f.run(54)
        }
        assertTrue(f.a.rawScore > 0.0)
    }

    @Test
    fun clockGoingBackwardsDoesNotBreakAnalysis() {
        val f = Feed(DrivingStyleAnalyzer(cfg))
        f.run(10 * 60)
        f.t -= 3_600_000L // 시계가 한 시간 뒤로
        f.run(20 * 60)
        assertTrue(f.a.baselineReady, "되돌아간 시각부터 다시 쌓는다")
        // 중복·순서 바뀐 표본은 무시
        val t = f.t
        f.a.onSample(DrivingSample(t - 500, 100f, 0f))
        f.a.onSample(DrivingSample(t, 100f, 0f))
    }
}
