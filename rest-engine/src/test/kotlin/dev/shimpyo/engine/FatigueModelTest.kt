package dev.shimpyo.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FatigueModelTest {
    private val cfg = CoachConfig()
    private val texts = CoachTexts()

    /** 기상 시각을 wake로 둔 모델. 엔진 시각 = 벽시계 (시계 점프 없음) */
    private fun model(wake: Long) = FatigueModel(cfg, LocalClock(SEOUL)).apply { wakeMono = wake }

    /** start부터 minutes분 동안 1초마다 운전 */
    private fun FatigueModel.drive(start: Long, minutes: Int): Long {
        var t = start
        repeat(minutes * 60) { t += SEC; advance(SEC, true, t, t) }
        return t
    }

    private fun FatigueModel.eval(t: Long, deviation: Double? = null, persistent: Boolean = false, muted: Boolean = false, sleep: Float? = null) =
        evaluate(t, t, deviation, persistent, muted, sleep, texts)

    private fun approx(e: Double, a: Double, tol: Double, msg: String = "") = assertTrue(abs(e - a) <= tol, "$msg expected $e±$tol but $a")

    private val day = kst(2026, 11, 13, 0, 0)
    private fun at(h: Int, m: Int = 0) = day + (h * 60 + m) * MIN

    @Test
    fun rhythmLevelsAtNeutralHour() {
        val m = model(wake = at(7))
        var t = m.drive(at(9), 89)
        var e = m.eval(t)
        assertEquals(Level.OK, e.gauge.level)
        assertEquals(31, e.gauge.minutes)
        t = m.drive(t, 1)
        assertEquals(Level.WATCH, m.eval(t).gauge.level)
        t = m.drive(t, 10)
        e = m.eval(t)
        assertEquals(Level.SOON, e.gauge.level)
        assertEquals(20, e.gauge.minutes)
        assertTrue(e.gauge.reasons.any { it.code == "FATIGUE_DRIVING" && it.text.contains("1시간 40분째") })
        t = m.drive(t, 20)
        e = m.eval(t)
        assertEquals(Level.NOW, e.gauge.level, "2시간 휴식 리듬")
        assertEquals(0, e.gauge.minutes)
        assertFalse(e.strong, "낮의 2시간은 '강한 권고'가 아니다")
    }

    @Test
    fun afternoonWindowCountsMinutesAsOnePointTwo() {
        val m = model(wake = at(7))
        val t = m.drive(at(13, 30), 60) // 13:30~14:30
        approx(60.0, m.drivingMin, 1e-9)
        approx(30.0 + 30.0 * 1.2, m.effMin, 1e-9)
        val e = m.eval(t)
        assertTrue(e.gauge.reasons.any { it.code == "FATIGUE_AFTERNOON" })
        assertEquals(floor((120.0 - m.effMin) / 1.2 + 1e-6).toInt(), e.gauge.minutes, "남은 54분이 1.2배로 쌓인다")
    }

    @Test
    fun nightWindowGivesNowAfterOneHour() {
        val m = model(wake = at(1)) // 막 일어나 출발: 졸림 곡선은 멀다
        val t = m.drive(at(2), 60)
        val e = m.eval(t)
        assertEquals(Level.NOW, e.gauge.level, "02~06시 + 연속 1시간 (ai-science.md 8-3 L1)")
        assertTrue(e.gauge.reasons.any { it.code == "FATIGUE_NIGHT" })
    }

    @Test
    fun threeProcessModelValues() {
        val m = model(wake = at(7))
        approx(2.44, m.kss(8.0, 15.0), 0.02, "오후 3시, 8시간 깨어 있음")
        approx(6.20, m.kss(18.0, 1.0), 0.02, "새벽 1시, 18시간")
        approx(7.22, m.kss(21.0, 4.0), 0.02, "새벽 4시, 21시간")
        // 07시 기상이면 KSS 6은 자정 넘어 00:30~00:40 무렵
        val minutes = m.minutesUntilKss(6.0, at(22), at(22))
        assertTrue(minutes in 150.0..160.0, "$minutes")
    }

    @Test
    fun sleepyCurveMakesLateNightNowWithoutDriving() {
        val m = model(wake = at(7))
        val t = day + 25 * 60 * MIN + 30 * MIN // 다음 날 01:30, 18.5시간 깨어 있음
        val e = m.eval(t)
        assertEquals(Level.NOW, e.gauge.level)
        assertEquals(0.0, e.sleepyCurveMin)
        assertTrue(e.gauge.reasons.any { it.code == "FATIGUE_AWAKE_LONG" && it.text.contains("18시간") }, e.gauge.reasons.toString())
        assertFalse(e.strong, "KSS 6.4는 권고, 7부터 강한 권고")
        val late = m.eval(day + 28 * 60 * MIN) // 04:00, 21시간
        assertTrue(late.strong, "KSS ≥ 7 → 커피·짧은 낮잠 권유")
        assertTrue(late.gauge.reasons.any { it.code == "FATIGUE_STRONG" })
    }

    @Test
    fun bodyClockReasonWhenCurveIsTheBindingClock() {
        val m = model(wake = at(7))
        val t = day + 24 * 60 * MIN + 10 * MIN // 00:10, KSS 6까지 약 25분
        val e = m.eval(t)
        assertTrue(e.sleepyCurveMin < e.rhythmMin)
        assertEquals(Level.WATCH, e.gauge.level)
        assertTrue(e.gauge.reasons.any { it.code == "FATIGUE_BODY_CLOCK" }, e.gauge.reasons.toString())
    }

    @Test
    fun restResetsRhythmButOnlyDefersSleepyCurve() {
        val m = model(wake = at(7))
        val t = day + 25 * 60 * MIN + 30 * MIN // 01:30, 이미 NOW
        m.drive(t - 30 * MIN, 30)
        assertEquals(Level.NOW, m.eval(t).gauge.level)
        m.onRest(t)
        assertEquals(0.0, m.drivingMin)
        val after = m.eval(t)
        assertEquals(60, after.gauge.minutes, "휴식은 졸림을 없애지 못한다: 60분 뒤 다시 본다")
        assertEquals(Level.OK, after.gauge.level)
        assertEquals(Level.NOW, m.eval(t + 61 * MIN).gauge.level)
    }

    @Test
    fun shortSleepStepsShortenTheRhythm() {
        val m = model(wake = at(7))
        val t = m.drive(at(9), 97)
        assertEquals(Level.NOW, m.eval(t, sleep = 5.5f).gauge.level, "6시간 미만 → 120 × 0.8 = 96분")
        assertEquals(Level.WATCH, m.eval(t, sleep = 7f).gauge.level)
        assertEquals(Level.WATCH, m.eval(t).gauge.level, "수면을 모르면 기본")
        val m2 = model(wake = at(7))
        val t2 = m2.drive(at(9), 77)
        assertEquals(Level.NOW, m2.eval(t2, sleep = 4.5f).gauge.level, "5시간 미만 → 120 × 0.64 = 76.8분")
        assertTrue(m2.eval(t2, sleep = 4.5f).gauge.reasons.any { it.code == "FATIGUE_SHORT_SLEEP" && it.text.contains("4.5시간") })
    }

    @Test
    fun awakeLongAndMonotonyWeighTheRhythm() {
        val m = model(wake = at(0))
        // 깨어 있은 지 18시간: 1분을 1/0.8분으로 → 120분 리듬이 96분
        approx(96.0, m.projectWallMin(120.0, at(10), 18.0, false), 1e-6)
        // 16.5시간에서 시작: 30분은 그대로, 나머지 90을 1.25배로 → 30 + 72
        approx(102.0, m.projectWallMin(120.0, at(10), 16.5, false), 1e-6)
        // 크루즈 단조로움 ×1/0.9 → 108분
        approx(108.0, m.projectWallMin(120.0, at(10), 3.0, true), 1e-6)
        // 시간대 경계를 넘는 투영: 13:50에서 10분 그대로, 110을 1.2배로
        approx(101.67, m.projectWallMin(120.0, at(13, 50), 3.0, false), 0.02)
        approx(0.0, m.projectWallMin(-5.0, at(13, 50), 3.0, false), 1e-12)
    }

    @Test
    fun styleAdvanceNeedsPersistenceAndRespectsMute() {
        val m = model(wake = at(7))
        val t = m.drive(at(9), 92) // WATCH, 남은 28분
        assertEquals(28, m.eval(t).gauge.minutes)
        val adv = m.eval(t, deviation = 0.65, persistent = true)
        assertEquals(Level.SOON, adv.gauge.level)
        assertEquals(20, adv.gauge.minutes, "SOON 단계 상한으로 줄인다")
        assertTrue(adv.advancedByStyle)
        assertTrue(adv.gauge.reasons.any { it.code == "FATIGUE_STYLE" })
        assertEquals(Level.WATCH, m.eval(t, deviation = 0.65, persistent = false).gauge.level, "지속되지 않으면 앞당기지 않는다")
        assertEquals(Level.WATCH, m.eval(t, deviation = 0.9, persistent = true, muted = true).gauge.level, "'아니'라고 답한 뒤 20분")
        val fresh = model(wake = at(7)).eval(at(9), deviation = 0.95, persistent = true)
        assertEquals(Level.WATCH, fresh.gauge.level, "편차만으로는 한 단계")
        val now = model(wake = at(7)).let { m2 -> val t2 = m2.drive(at(9), 121); m2.eval(t2, deviation = 0.9, persistent = true) }
        assertEquals(Level.NOW, now.gauge.level, "NOW는 더 오르지 않는다")
        assertTrue(now.strong, "NOW + 강한 편차 지속 → 강한 권고")
    }

    @Test
    fun sleepyVoiceIsStrongNowUntilRest() {
        val m = model(wake = at(7))
        val t = m.drive(at(9), 20)
        m.sleepyVoiced = true
        val e = m.eval(t)
        assertEquals(Level.NOW, e.gauge.level)
        assertEquals(0, e.gauge.minutes)
        assertTrue(e.strong)
        assertEquals("FATIGUE_VOICED", e.gauge.reasons.first().code)
        m.onRest(t)
        assertEquals(Level.OK, m.eval(t).gauge.level)
    }

    @Test
    fun parkedTimeDoesNotAccumulate() {
        val m = model(wake = at(7))
        var t = m.drive(at(9), 50)
        repeat(300) { t += SEC; m.advance(SEC, false, t, t) }
        approx(50.0, m.drivingMin, 1e-9)
    }

    @Test
    fun longUpdateGapIsCapped() {
        val m = model(wake = at(7))
        m.advance(3 * 60 * MIN, true, at(9), at(9)) // 앱이 3시간 멈췄다 돌아옴
        approx(cfg.maxUpdateGapS / 60.0, m.drivingMin, 1e-9)
        m.advance(-5 * MIN, true, at(9), at(9)) // 음수 간격은 무시
        approx(cfg.maxUpdateGapS / 60.0, m.drivingMin, 1e-9)
    }

    @Test
    fun recirculationIsTrackedButNeverRaisesFatigue() {
        val m = model(wake = at(7))
        val t = m.drive(at(9), 60)
        val before = m.eval(t)
        m.onCabin(CabinState(hvacOn = true, recirculation = true), t - 50 * MIN)
        m.onCabin(CabinState(hvacOn = null, recirculation = null), t - 20 * MIN) // 모름: 유지
        val after = evalAt(m, t)
        approx(50.0, m.recircMin(t), 1e-9)
        assertEquals(before.gauge.level, after.gauge.level)
        assertEquals(before.gauge.minutes, after.gauge.minutes)
        assertTrue(after.gauge.reasons.none { it.code.contains("RECIRC") }, "환기는 피로 이유가 아니다")
        m.onCabin(CabinState(hvacOn = true, recirculation = false), t)
        assertEquals(0.0, m.recircMin(t + 10 * MIN))
        m.onCabin(CabinState(hvacOn = false, recirculation = true), t + 11 * MIN)
        assertEquals(0.0, m.recircMin(t + 60 * MIN), "공조가 꺼져 있으면 세지 않는다")
    }

    private fun evalAt(m: FatigueModel, t: Long) = m.evaluate(t, t, null, false, false, null, texts)
}
