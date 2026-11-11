package dev.shimpyo.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 화장실 필요 모델의 문맥 보정: 사람마다 시계를 유효 시간 τ로 돌린다 (docs/research/need-covariates.md 6장·8장).
 *
 *   τ += dt / m(지금),  S(t) = S0(τ),  배운 값도 τ (b += τ^k)
 * - 식사 정차 뒤 구간: m = 0.8 (구간 안에서 상수, 영아·반려견 제외)
 * - 밤(20~07시)에 잠든 아이: m = 1.5, 화면 게이지에만 (판단 마감에는 쓰지 않는다)
 *
 * 이 파일은 모델(RestroomNeedModel)의 수식만 본다. 정차·입력으로 배수가 걸리는 길은 ContextFactorEngineTest가 본다.
 */
class ContextFactorTest {
    private val cfg = CoachConfig()
    private val texts = CoachTexts()
    private val noon = kst(2026, 9, 24, 12, 0)
    private val nine = kst(2026, 9, 24, 21, 0)

    private fun model(
        people: List<Occupant>,
        start: Long,
        config: CoachConfig = cfg,
        last: Map<String, Long> = emptyMap(),
    ): RestroomNeedModel {
        val clock = EngineClock().apply { advance(start) }
        return RestroomNeedModel(config).also { it.sync(TripProfile(people, tripStart = start, lastRestroomAt = last), clock, PersonalMemory()) }
    }

    /**
     * park에 서서 3분 뒤 시계가 돌아가고(StopTracker), 20분에 식사 정차로 판정된 상황.
     * 대부분 출발 10분 뒤에 세운다: 그 앞 구간(13분)은 관측으로 남지 않아(20분 미만) 사전값 그대로의 숫자를 볼 수 있다
     */
    private fun RestroomNeedModel.mealStop(park: Long, factor: Double = 0.8, memory: PersonalMemory = PersonalMemory()): PersonalMemory {
        val mem = onRestroomStop(park + 3 * MIN, memory)
        onMealStop(park, park + 20 * MIN, factor)
        return mem
    }

    /** from부터 to까지 step마다 시계를 진행한다. 그동안 asleep에 든 사람이 잔다 (이 테스트에서는 엔진 시각 = 벽시계) */
    private fun RestroomNeedModel.run(from: Long, to: Long, asleep: Set<String> = emptySet(), step: Long = SEC) {
        var t = from
        advance(t, t, asleep)
        while (t < to) {
            t = minOf(t + step, to)
            advance(t, t, asleep)
        }
    }

    private fun RestroomNeedModel.who(id: String) = tracked.single { it.id == id }
    private fun Gauge.codes() = reasons.map { it.code }

    private fun approx(expected: Double, actual: Double, tol: Double, msg: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$msg expected $expected ± $tol but was $actual")

    // ------------------------------------------------------------ 시간 창

    @Test
    fun theNightWindowWrapsMidnightAndCountsOverlapToTheMillisecond() {
        val w = DailyWindow(LocalClock(SEOUL), 20, 7)
        assertFalse(w.contains(kst(2026, 9, 24, 19, 59, 59)))
        assertTrue(w.contains(kst(2026, 9, 24, 20, 0)))
        assertTrue(w.contains(kst(2026, 9, 25, 0, 0)))
        assertTrue(w.contains(kst(2026, 9, 25, 6, 59, 59)))
        assertFalse(w.contains(kst(2026, 9, 25, 7, 0)))
        assertFalse(w.contains(kst(2026, 9, 25, 13, 0)))

        assertEquals(60 * MIN, w.msToEdge(kst(2026, 9, 24, 19, 0)))
        assertEquals(11 * 60 * MIN, w.msToEdge(kst(2026, 9, 24, 20, 0)), "경계 위에서는 그다음 경계")
        assertEquals(13 * 60 * MIN, w.msToEdge(kst(2026, 9, 25, 7, 0)))

        assertEquals(30 * MIN, w.overlapMs(kst(2026, 9, 24, 19, 45), kst(2026, 9, 24, 20, 30)))
        assertEquals(30 * MIN + 500, w.overlapMs(kst(2026, 9, 25, 6, 29, 59) + 500, kst(2026, 9, 25, 7, 45)))
        assertEquals(11 * 60 * MIN, w.overlapMs(kst(2026, 9, 24, 12, 0), kst(2026, 9, 25, 12, 0)), "하루에 11시간")
        assertEquals(22 * 60 * MIN, w.overlapMs(kst(2026, 9, 24, 12, 0), kst(2026, 9, 26, 12, 0)))
        assertEquals(0L, w.overlapMs(kst(2026, 9, 24, 8, 0), kst(2026, 9, 24, 19, 0)))
        assertEquals(0L, w.overlapMs(kst(2026, 9, 24, 21, 0), kst(2026, 9, 24, 21, 0)), "빈 구간")
    }

    @Test
    fun aDayWindowAndAnEmptyWindow() {
        val day = DailyWindow(LocalClock(SEOUL), 9, 17)
        assertTrue(day.contains(kst(2026, 9, 24, 9, 0)))
        assertFalse(day.contains(kst(2026, 9, 24, 17, 0)))
        assertEquals(8 * 60 * MIN, day.overlapMs(kst(2026, 9, 24, 0, 0), kst(2026, 9, 25, 0, 0)))
        val none = DailyWindow(LocalClock(SEOUL), 5, 5)
        assertTrue(none.isEmpty)
        assertFalse(none.contains(kst(2026, 9, 24, 5, 0)))
        assertEquals(0L, none.overlapMs(kst(2026, 9, 24, 0, 0), kst(2026, 9, 26, 0, 0)))
    }

    // ------------------------------------------------------------ 식사 뒤 구간

    @Test
    fun aMealStopShortensTheIntervalThatStartedThere() {
        // need-covariates 2장: 30분 식사면 출발 때 27분이 지나 있고, 남은 중앙값이 123 → 93분
        val m = model(listOf(adult()), noon)
        val park = noon + 10 * MIN
        m.onRestroomStop(park + 3 * MIN, PersonalMemory())
        assertEquals(138, m.gauge(park + 15 * MIN, texts).minutes, "식사 정차로 판정되기 전: 150 − 12")
        m.onMealStop(park, park + 20 * MIN, 0.8)
        val g = m.gauge(park + 30 * MIN, texts)
        assertEquals(93, g.minutes, "150 × 0.8 − 27")
        assertEquals(listOf("RESTROOM_ELAPSED", "RESTROOM_AFTER_MEAL"), g.codes())
        assertEquals("식사 뒤라 평소보다 조금 일찍 잡았어요", g.reasons.last().text)
        assertTrue(g.reasons.first().text.contains("27분"), "지난 시간은 실제 시간 그대로: ${g.reasons.first().text}")
        approx(27 / 0.8, m.tauMin(m.who("me"), park + 30 * MIN), 1e-9, "유효 시간")
        // 연료계: 그 뒤로 1분에 1분씩 준다
        assertEquals(92, m.gauge(park + 31 * MIN, texts).minutes)
        assertEquals(33, m.gauge(park + 90 * MIN, texts).minutes)
        assertEquals(0, m.gauge(park + 3 * MIN + 120 * MIN, texts).minutes, "0.8 × 150 = 120분에 바닥")
    }

    @Test
    fun theEveningFactorIsWhateverTheStopWasGiven() {
        val m = model(listOf(adult()), noon)
        m.mealStop(noon + 10 * MIN, factor = 0.9)
        assertEquals(108, m.gauge(noon + 40 * MIN, texts).minutes, "150 × 0.9 − 27")
    }

    @Test
    fun infantsAndDogsAreNotGivenTheMealFactor() {
        val m = model(listOf(adult(), child("kid", 7), infant(), Occupant("dog", OccupantType.DOG)), noon)
        m.mealStop(noon + 10 * MIN)
        assertEquals(0.8, m.who("me").mealFactor)
        assertEquals(0.8, m.who("kid").mealFactor)
        assertEquals(1.0, m.who("baby").mealFactor, "영아의 시계는 수유·기저귀 간격이다")
        assertEquals(1.0, m.who("dog").mealFactor)
        val at = noon + 40 * MIN // 시계가 돌아간 지 27분
        approx(120.0 - 27, m.remainingMin(m.who("me"), at), 1e-6)
        approx(84.0 - 27, m.remainingMin(m.who("kid"), at), 1e-6, "105 × 0.8")
        approx(120.0 - 27, m.remainingMin(m.who("baby"), at), 1e-6, "카시트 상한 2시간 그대로")
        approx(150.0 - 27, m.remainingMin(m.who("dog"), at), 1e-6)
    }

    @Test
    fun theNextRestroomStopEndsTheMealInterval() {
        val quiet = cfg.copy(restroomMinObservedMin = 10_000.0) // 이 테스트는 학습 없이 시계만 본다
        val m = model(listOf(adult()), noon, quiet)
        m.mealStop(noon + 10 * MIN)
        assertEquals(0.8, m.who("me").mealFactor)
        val next = noon + 120 * MIN
        m.onRestroomStop(next, PersonalMemory())
        assertEquals(1.0, m.who("me").mealFactor)
        val g = m.gauge(next + 10 * MIN, texts)
        assertEquals(140, g.minutes)
        assertEquals(listOf("RESTROOM_ELAPSED"), g.codes())
    }

    @Test
    fun aStopThatDidNotResetTheClockGivesNoFactor() {
        // 정한 규칙: 식사 배수는 그 정차에서 시계가 돌아간 구간에만 건다. 문이 열리지 않아 시계가 그대로면(아무도 내리지 않았다)
        // 걸지 않는다. 그대로 흘러간 시계가 이미 이른 쪽이다
        val m = model(listOf(adult()), noon)
        val park = noon + 60 * MIN
        m.onMealStop(park, park + 25 * MIN, 0.8)
        assertEquals(1.0, m.who("me").mealFactor)
        assertEquals(150 - 85, m.gauge(park + 25 * MIN, texts).minutes)
        // 그 정차에서 뒤늦게 문이 열려 시계가 돌아가면 그때부터의 구간에 건다
        m.onRestroomStop(park + 26 * MIN, PersonalMemory())
        m.onMealStop(park, park + 27 * MIN, 0.8)
        assertEquals(0.8, m.who("me").mealFactor)
    }

    @Test
    fun aLaterStopWithoutAResetDoesNotCancelARunningMealInterval() {
        val m = model(listOf(adult()), noon)
        m.mealStop(noon + 10 * MIN)
        val second = noon + 100 * MIN // 차 안에서 쉰 긴 정차(문 열림 없음)
        m.onMealStop(second, second + 20 * MIN, 0.9)
        assertEquals(0.8, m.who("me").mealFactor, "앞 식사 정차에서 시작한 구간은 그대로")
    }

    @Test
    fun eachMealStopStartsItsOwnInterval() {
        // 점심(0.8) 뒤 저녁(0.9): 두 번째 정차에서 시계가 돌아가면 앞 배수는 풀리고 새 배수가 걸린다
        val quiet = cfg.copy(restroomMinObservedMin = 10_000.0)
        val m = model(listOf(adult()), noon, quiet)
        m.mealStop(noon + 10 * MIN)
        val dinner = noon + 8 * 60 * MIN
        m.onRestroomStop(dinner + 3 * MIN, PersonalMemory())
        assertEquals(1.0, m.who("me").mealFactor, "저녁 정차가 식사 정차로 판정되기 전")
        m.onMealStop(dinner, dinner + 20 * MIN, 0.9)
        assertEquals(0.9, m.who("me").mealFactor)
        assertEquals(108, m.gauge(dinner + 30 * MIN, texts).minutes)
    }

    @Test
    fun someoneWhoJoinsAfterTheMealStopHasAPlainInterval() {
        val clock = EngineClock().apply { advance(noon) }
        val m = RestroomNeedModel(cfg).also { it.sync(TripProfile(listOf(adult()), tripStart = noon), clock, PersonalMemory()) }
        m.mealStop(noon + 10 * MIN)
        clock.advance(noon + 50 * MIN)
        val joined = TripProfile(listOf(adult(), child("kid", 7)), tripStart = noon, lastRestroomAt = mapOf("kid" to noon + 45 * MIN))
        m.sync(joined, clock, PersonalMemory())
        m.onMealStop(noon + 10 * MIN, noon + 40 * MIN, 0.8) // 엔진은 서 있는 동안만 부르지만, 다시 불려도 구간이 그 정차에서 시작한 사람만
        assertEquals(0.8, m.who("me").mealFactor)
        assertEquals(1.0, m.who("kid").mealFactor)
    }

    @Test
    fun aFactorOfOneOrANonsenseFactorDoesNothing() {
        for (f in listOf(1.0, 0.0, -0.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            val m = model(listOf(adult()), noon)
            m.mealStop(noon + 10 * MIN, factor = f)
            assertEquals(1.0, m.who("me").mealFactor, "factor $f")
            assertEquals(123, m.gauge(noon + 40 * MIN, texts).minutes)
        }
    }

    // ------------------------------------------------------------ 학습은 유효 시간으로

    @Test
    fun a96MinuteIntervalAfterAMealIsLearnedAs120() {
        // 식사 뒤 96분 만에 선 것은 기준 조건의 120분과 같은 관측이다. 그대로 96분으로 배우면 식사가 잦은 사람의 기준 간격이 짧아진다
        val meal = model(listOf(adult()), noon)
        var mem = meal.mealStop(noon + 10 * MIN) // 출발 13분 만의 정차는 관측이 아니다
        assertTrue(mem.restroomIntervalsMin.isEmpty())
        val reset = noon + 13 * MIN
        mem = meal.onRestroomStop(reset + 96 * MIN, mem)
        assertEquals(listOf(120.0), mem.restroomIntervalsMin["me"])

        val plain = model(listOf(adult()), noon)
        var pm = plain.onRestroomStop(reset, PersonalMemory())
        pm = plain.onRestroomStop(reset + 120 * MIN, pm)
        assertEquals(pm, mem, "식사 없이 120분 만에 선 사람과 같은 기록")
        assertEquals(plain.who("me").posterior.a, meal.who("me").posterior.a)
        assertEquals(plain.who("me").posterior.b, meal.who("me").posterior.b)
        assertEquals(plain.who("me").thresholdMin, meal.who("me").thresholdMin)
    }

    @Test
    fun censoredSuggestedAndVoicedObservationsAlsoUseEffectiveTime() {
        // 아이가 게이지를 정하던 사람(사건), 어른은 중도절단
        val m = model(listOf(adult(), child("kid", 7)), noon)
        var mem = m.mealStop(noon + 10 * MIN)
        val reset = noon + 13 * MIN
        mem = m.onRestroomStop(reset + 60 * MIN, mem)
        assertEquals(listOf(75.0), mem.restroomIntervalsMin["kid"], "60 / 0.8")
        assertEquals(listOf(75.0), mem.restroomCensoredMin["me"])

        val s = model(listOf(adult()), noon)
        var sm = s.mealStop(noon + 10 * MIN)
        sm = s.onRestroomStop(reset + 40 * MIN, sm, suggested = true)
        assertEquals(listOf(50.0), sm.restroomSuggestedMin["me"])

        val v = model(listOf(adult()), noon)
        var vm = v.mealStop(noon + 10 * MIN)
        vm = v.onVoiced("me", Purpose.RESTROOM, reset + 80 * MIN, vm)
        assertEquals(listOf(100.0), vm.restroomIntervalsMin["me"])
        assertEquals(0, v.gauge(reset + 80 * MIN, texts).minutes, "말로 알리면 바로 0")
        assertEquals(listOf("RESTROOM_VOICED", "RESTROOM_PERSONAL"), v.gauge(reset + 80 * MIN, texts).codes())
    }

    @Test
    fun tooShortRealIntervalsAreStillNotObservations() {
        // 18분은 0.8로 나누면 22.5분이지만, '연달아 선 정차'를 거르는 기준은 실제 시간이다
        val m = model(listOf(adult()), noon)
        var mem = m.mealStop(noon + 10 * MIN)
        mem = m.onRestroomStop(noon + 13 * MIN + 18 * MIN, mem)
        assertTrue(mem.restroomIntervalsMin.isEmpty() && mem.restroomCensoredMin.isEmpty())
    }

    // ------------------------------------------------------------ 밤에 잠든 아이

    @Test
    fun aChildAsleepAtNightGetsALongerGaugeButTheSameDeadline() {
        val asleep = model(listOf(child("kid", 7)), nine)
        val awake = model(listOf(child("kid", 7)), nine)
        asleep.run(nine, nine, setOf("kid"))
        val g0 = asleep.gauge(nine, texts)
        assertEquals(157, g0.minutes, "105 × 1.5")
        assertEquals(listOf("RESTROOM_ELAPSED", "RESTROOM_ASLEEP"), g0.codes())
        assertEquals("7살 아이가 자는 동안은 여유를 조금 더 뒀어요", g0.reasons.last().text)

        asleep.run(nine, nine + 30 * MIN, setOf("kid"))
        awake.run(nine, nine + 30 * MIN)
        val at = nine + 30 * MIN
        approx(20.0, asleep.tauMin(asleep.who("kid"), at), 1e-9, "30분 자는 동안 20분만 흘렀다")
        assertEquals(127, asleep.gauge(at, texts).minutes, "(105 − 20) × 1.5")
        assertEquals(75, awake.gauge(at, texts).minutes)
        assertEquals("kid", asleep.gauge(at, texts).drivenBy)
        // 판단 마감은 깨어 있는 것으로 본다: 깨면 바로 화장실을 찾을 수 있다
        assertEquals(awake.deadlineMin(at, 0.3), asleep.deadlineMin(at, 0.3))
        assertEquals(awake.soloDeadlineMin(at, 0.3), asleep.soloDeadlineMin(at, 0.3))
        assertEquals(awake.deadlineMin(at, 0.3, fromReset = true), asleep.deadlineMin(at, 0.3, fromReset = true))
    }

    @Test
    fun fallingAsleepMidIntervalAndWakingLaterAccumulatesEffectiveTime() {
        val m = model(listOf(child("kid", 7)), nine)
        m.run(nine, nine + 30 * MIN) // 깨어서 30분
        assertEquals(75, m.gauge(nine + 30 * MIN, texts).minutes)
        m.run(nine + 30 * MIN, nine + 45 * MIN, setOf("kid")) // 잠든 지 15분
        approx(40.0, m.tauMin(m.who("kid"), nine + 45 * MIN), 1e-9)
        assertEquals(97, m.gauge(nine + 45 * MIN, texts).minutes, "(105 − 40) × 1.5")
        m.run(nine + 45 * MIN, nine + 75 * MIN, setOf("kid")) // 45분 자고
        m.run(nine + 75 * MIN, nine + 75 * MIN) // 깼다
        approx(60.0, m.tauMin(m.who("kid"), nine + 75 * MIN), 1e-9)
        val woke = m.gauge(nine + 75 * MIN, texts)
        assertEquals(45, woke.minutes, "깨면 남은 유효 시간 그대로")
        assertEquals(listOf("RESTROOM_ELAPSED"), woke.codes())
        m.run(nine + 75 * MIN, nine + 90 * MIN)
        approx(75.0, m.tauMin(m.who("kid"), nine + 90 * MIN), 1e-9, "30 + 45/1.5 + 15")
        assertEquals(30, m.gauge(nine + 90 * MIN, texts).minutes)
    }

    @Test
    fun aDaytimeNapChangesNothing() {
        val one = kst(2026, 9, 24, 13, 0)
        val nap = model(listOf(child("kid", 7)), one)
        val awake = model(listOf(child("kid", 7)), one)
        nap.run(one, one + 40 * MIN, setOf("kid"))
        awake.run(one, one + 40 * MIN)
        val g = nap.gauge(one + 40 * MIN, texts)
        assertEquals(awake.gauge(one + 40 * MIN, texts), g)
        assertEquals(65, g.minutes)
        assertEquals(listOf("RESTROOM_ELAPSED"), g.codes())
        approx(40.0, nap.tauMin(nap.who("kid"), one + 40 * MIN), 1e-12)
    }

    @Test
    fun aNappingChildHasExactlyTheAwakeNumbers() {
        // 낮잠은 배수가 없다: 남은 시간이 깨어 있는 아이와 비트까지 같아야 한다. 마지막 자리 하나라도 다르면 같은 또래 두 아이 중
        // '가장 급한 사람'이 초마다 바뀌어 게이지의 기준 탑승자와 이유 문구가 깜빡인다
        val one = kst(2026, 9, 24, 13, 0)
        val m = model(listOf(child("a", 7), child("b", 7)), one)
        m.run(one, one, setOf("b"))
        var t = one
        while (t < one + 100 * MIN) {
            t += 7 * SEC
            m.advance(t, t, setOf("b"))
            assertEquals(m.remainingMin(m.who("a"), t), m.remainingMin(m.who("b"), t), "t = ${(t - one) / 1000}초")
            assertEquals("a", m.gauge(t, texts).drivenBy)
        }
    }

    @Test
    fun crossingSevenInTheMorningTheGaugeKeepsFallingOneMinutePerMinute() {
        // 06시부터 자는 아이: 07시까지는 1.5배로 느리게, 그 뒤는 낮잠이라 그대로. 07시를 미리 아니까 게이지가 경계에서 튀지 않는다
        val six = kst(2026, 9, 25, 6, 0)
        val m = model(listOf(child("kid", 7)), six)
        m.run(six, six, setOf("kid"))
        assertEquals(125, m.gauge(six, texts).minutes, "07시까지 60분(유효 40분) + 남은 65분")
        m.run(six, six + 30 * MIN, setOf("kid"))
        assertEquals(95, m.gauge(six + 30 * MIN, texts).minutes)
        m.run(six + 30 * MIN, six + 60 * MIN - SEC, setOf("kid"))
        assertEquals(65, m.gauge(six + 60 * MIN - SEC, texts).minutes, "06:59:59")
        assertTrue("RESTROOM_ASLEEP" in m.gauge(six + 60 * MIN - SEC, texts).codes())
        m.run(six + 60 * MIN - SEC, six + 60 * MIN, setOf("kid"))
        val seven = m.gauge(six + 60 * MIN, texts)
        assertEquals(65, seven.minutes, "07:00:00에 뛰지 않는다")
        assertEquals(listOf("RESTROOM_ELAPSED"), seven.codes(), "낮에는 자고 있어도 늘려 잡지 않는다")
        approx(40.0, m.tauMin(m.who("kid"), six + 60 * MIN), 1e-9)
        m.run(six + 60 * MIN, six + 120 * MIN, setOf("kid"))
        approx(100.0, m.tauMin(m.who("kid"), six + 120 * MIN), 1e-9, "60/1.5 + 60")
        assertEquals(5, m.gauge(six + 120 * MIN, texts).minutes)
    }

    @Test
    fun crossingEightInTheEveningTheStretchStartsAtEight() {
        val start = kst(2026, 9, 24, 19, 30)
        val m = model(listOf(child("kid", 7)), start)
        m.run(start, start, setOf("kid"))
        val g = m.gauge(start, texts)
        assertEquals(142, g.minutes, "20시까지 30분 + (105 − 30) × 1.5")
        assertTrue("RESTROOM_ASLEEP" in g.codes(), "늘려 잡은 만큼 이유를 말한다")
        m.run(start, start + 30 * MIN, setOf("kid"))
        approx(30.0, m.tauMin(m.who("kid"), start + 30 * MIN), 1e-9, "20시 전 낮잠은 그대로 흐른다")
        assertEquals(112, m.gauge(start + 30 * MIN, texts).minutes)
        // 20시 전에 바닥나는 게이지는 늘지 않고 이유도 없다
        val early = kst(2026, 9, 24, 18, 0)
        val young = model(listOf(child("kid", 5)), early)
        young.run(early, early, setOf("kid"))
        assertEquals(60, young.gauge(early, texts).minutes)
        assertEquals(listOf("RESTROOM_ELAPSED"), young.gauge(early, texts).codes())
    }

    @Test
    fun theStretchIsTheSameHoweverOftenTheClockIsAdvanced() {
        // update가 1초마다 오든 한 번에 오든(앱 재시작, 드문 update) 같은 유효 시간이어야 한다
        val start = kst(2026, 9, 25, 5, 10)
        val end = start + 200 * MIN // 07시 경계를 지난다
        val taus = listOf(SEC, 7 * MIN + 13 * SEC, 200 * MIN).map { step ->
            val m = model(listOf(child("kid", 7)), start)
            m.run(start, end, setOf("kid"), step)
            m.tauMin(m.who("kid"), end)
        }
        approx(110 / 1.5 + 90, taus[0], 1e-9)
        approx(taus[0], taus[1], 1e-9)
        approx(taus[0], taus[2], 1e-9)
    }

    @Test
    fun unknownIdsAndPeopleWhoAreNotChildrenAreIgnored() {
        val m = model(listOf(adult(), child("kid", 7), infant()), nine)
        val plain = model(listOf(adult(), child("kid", 7), infant()), nine)
        m.run(nine, nine + 40 * MIN, setOf("ghost", "me", "baby"))
        plain.run(nine, nine + 40 * MIN)
        assertTrue(m.tracked.none { it.asleep }, "어른·영아·모르는 id는 잠든 아이가 아니다")
        assertEquals(plain.gauge(nine + 40 * MIN, texts), m.gauge(nine + 40 * MIN, texts))
        for (t in m.tracked) approx(40.0, m.tauMin(t, nine + 40 * MIN), 1e-12)
        // 10살 넘은 아이도 CHILD면 적용한다 (사전값만 성인 값)
        val teen = model(listOf(child("teen", 12)), nine)
        teen.run(nine, nine, setOf("teen"))
        assertEquals(225, teen.gauge(nine, texts).minutes)
    }

    @Test
    fun anotherOccupantCanBecomeTheOneWhoSetsTheGauge() {
        // 아이가 자면 아이 게이지(157)가 어른(150)보다 길어진다: 게이지는 여전히 가장 급한 사람의 값이고 이유는 그 사람 것만 붙는다
        val m = model(listOf(adult(), child("kid", 7)), nine)
        m.run(nine, nine, setOf("kid"))
        val g = m.gauge(nine, texts)
        assertEquals("me", g.drivenBy)
        assertEquals(150, g.minutes)
        assertEquals(listOf("RESTROOM_ELAPSED"), g.codes())
    }

    @Test
    fun whatAChildSleptThroughIsLearnedInEffectiveTime() {
        val m = model(listOf(child("kid", 5)), nine)
        m.run(nine, nine + 90 * MIN, setOf("kid"))
        val mem = m.onRestroomStop(nine + 90 * MIN, PersonalMemory())
        assertEquals(listOf(60.0), mem.restroomIntervalsMin["kid"], "자면서 지난 90분은 기준 조건의 60분")
        assertEquals(0.0, m.tauMin(m.who("kid"), nine + 90 * MIN), "새 구간은 0부터")
        m.run(nine + 90 * MIN, nine + 100 * MIN)
        approx(10.0, m.tauMin(m.who("kid"), nine + 100 * MIN), 1e-9, "앞 구간에서 덜 흐른 시간은 넘어오지 않는다")
    }

    @Test
    fun aRestroomStopResetsASleepingChildLikeEveryoneElse() {
        // 지금 규칙 그대로: 화장실 정차는 모두의 시계를 돌린다 (누가 내렸는지 엔진은 모른다). 잠든 아이 목록을 비우는 것은 앱의 일이다
        // (need-covariates 8장: 화장실 정차가 있으면 해제). 목록에 남아 있으면 새 구간도 늘려 잡는다
        val m = model(listOf(child("kid", 7)), nine)
        m.run(nine, nine + 60 * MIN, setOf("kid"))
        m.onRestroomStop(nine + 60 * MIN, PersonalMemory())
        m.run(nine + 60 * MIN, nine + 60 * MIN, setOf("kid"))
        assertEquals(0.0, m.tauMin(m.who("kid"), nine + 60 * MIN))
        assertTrue(m.who("kid").asleep)
        m.run(nine + 60 * MIN, nine + 90 * MIN, setOf("kid"))
        approx(20.0, m.tauMin(m.who("kid"), nine + 90 * MIN), 1e-9)
    }

    @Test
    fun theAsleepFactorOfOneTurnsItOff() {
        val off = cfg.copy(asleepIntervalFactor = 1.0)
        val m = model(listOf(child("kid", 7)), nine, off)
        m.run(nine, nine + 30 * MIN, setOf("kid"))
        assertFalse(m.who("kid").asleep)
        assertEquals(75, m.gauge(nine + 30 * MIN, texts).minutes)
        assertEquals(listOf("RESTROOM_ELAPSED"), m.gauge(nine + 30 * MIN, texts).codes())
    }

    // ------------------------------------------------------------ 둘이 함께, 마감, 상한

    @Test
    fun bothFactorsMultiply() {
        // 19:00 저녁 식사 정차(0.8) → 19:30 출발 → 20:00 아이가 잠듦 → 20:30
        val seven = kst(2026, 9, 24, 19, 0)
        val quiet = cfg.copy(restroomMinObservedMin = 10_000.0)
        val m = model(listOf(child("kid", 7)), seven - 20 * MIN, quiet)
        val twin = model(listOf(child("kid", 7)), seven - 20 * MIN, quiet) // 같은 식사, 자지 않는 아이
        for (x in listOf(m, twin)) x.mealStop(seven)
        val reset = seven + 3 * MIN
        m.run(reset, seven + 60 * MIN)
        approx(57 / 0.8, m.tauMin(m.who("kid"), seven + 60 * MIN), 1e-9)
        m.run(seven + 60 * MIN, seven + 90 * MIN, setOf("kid"))
        twin.run(reset, seven + 90 * MIN)
        val at = seven + 90 * MIN
        approx(57 / 0.8 + 30 / (0.8 * 1.5), m.tauMin(m.who("kid"), at), 1e-9)
        val g = m.gauge(at, texts)
        assertEquals(10, g.minutes, "(105 − 96.25) × 0.8 × 1.5 = 10.5")
        assertEquals(listOf("RESTROOM_ELAPSED", "RESTROOM_AFTER_MEAL", "RESTROOM_ASLEEP"), g.codes())
        assertEquals(0, twin.gauge(at, texts).minutes, "자지 않았다면 84 − 87 → 0")
        // 마감은 식사 배수만 받는다
        assertEquals(twin.deadlineMin(seven + 40 * MIN, 0.3), m.deadlineMin(seven + 40 * MIN, 0.3))
        assertEquals(twin.deadlineMin(at, 0.3), m.deadlineMin(at, 0.3))
    }

    @Test
    fun theGroupDeadlineAfterAMealHitsTheNominalProbabilityInEffectiveTime() {
        val m = model(listOf(adult("a1"), adult("a2")), noon)
        val plain = model(listOf(adult("a1"), adult("a2")), noon)
        m.mealStop(noon + 10 * MIN)
        val at = noon + 40 * MIN // 시계가 돌아간 지 27분
        val deadline = m.deadlineMin(at, 0.3)!!
        val anyNeed = 1.0 - m.tracked.fold(1.0) { s, t -> s * t.posterior.survival((27 + deadline) / 0.8) }
        approx(0.3, anyNeed, 1e-6, "마감에서 누군가 필요할 확률")
        val fromReset = plain.deadlineMin(noon, 0.3, fromReset = true)!! // 어른 둘 84.1분
        approx(0.8 * fromReset - 27, deadline, 1e-4, "기준 조건 마감 × 0.8 − 경과")
        approx(0.8 * m.who("a1").posterior.quantile(0.3) - 27, m.soloDeadlineMin(at, 0.3)!!, 1e-9)
        assertTrue(deadline < m.soloDeadlineMin(at, 0.3)!!)
        // 식사가 없던 때보다 이르다
        plain.onRestroomStop(noon + 13 * MIN, PersonalMemory())
        assertTrue(deadline < plain.deadlineMin(at, 0.3)!! - 10, "$deadline < ${plain.deadlineMin(at, 0.3)}")
    }

    @Test
    fun deadlinesFromAFreshResetIgnoreTheMealFactor() {
        // 피트스톱 계획의 '정차 뒤 구간'은 배수 1.0으로 둔다 (PitStopPlanner, SPEC 4.1)
        val m = model(listOf(adult(), child("kid", 7)), noon)
        val plain = model(listOf(adult(), child("kid", 7)), noon)
        m.mealStop(noon + 10 * MIN)
        assertEquals(0.8, m.who("kid").mealFactor)
        assertEquals(plain.deadlineMin(noon, 0.3, fromReset = true), m.deadlineMin(noon + 40 * MIN, 0.3, fromReset = true))
        assertEquals(plain.soloDeadlineMin(noon, 0.3, fromReset = true), m.soloDeadlineMin(noon + 40 * MIN, 0.3, fromReset = true))
    }

    @Test
    fun capsStayInRealMinutes() {
        // 어르신: 곡선 120 × 0.8 = 96분이 걷기 상한(120분)보다 먼저다 → 상한이 아니라 식사 뒤 곡선이 게이지를 정한다
        val m = model(listOf(Occupant("g", OccupantType.ELDERLY)), noon)
        m.mealStop(noon + 10 * MIN)
        val g = m.gauge(noon + 40 * MIN, texts)
        assertEquals(69, g.minutes, "96 − 27")
        assertEquals(listOf("RESTROOM_ELAPSED", "RESTROOM_AFTER_MEAL"), g.codes())
        approx(0.8 * m.who("g").posterior.quantile(0.3) - 27, m.deadlineMin(noon + 40 * MIN, 0.3)!!, 1e-9, "0.8 × 89.6분은 상한 전")

        // 평소 간격이 긴 어르신: 0.8을 곱해도 200분(마감은 149분)이라 상한 120분(실제 시간)이 그대로 정한다
        val long = model(listOf(Occupant("g", OccupantType.ELDERLY, intervalOverrideMin = 250.0)), noon)
        long.mealStop(noon + 10 * MIN)
        val capped = long.gauge(noon + 40 * MIN, texts)
        assertEquals(93, capped.minutes, "120 − 27")
        assertEquals(listOf("RESTROOM_ELAPSED", "RESTROOM_CAP"), capped.codes(), "게이지를 정하지 않는 배수는 이유로 내지 않는다")
        approx(120.0 - 27, long.deadlineMin(noon + 40 * MIN, 0.3)!!, 1e-9, "마감도 상한에서 멈춘다")
    }

    @Test
    fun awakePeopleGetNoTimeOfDayFactor() {
        // need-covariates 9장 1번: 밤에 소변이 주는 것은 잠 때문이다. 깨어 있는 사람의 곡선은 새벽 3시와 오후 3시가 같아야 한다
        // (밤에는 열린 화장실이 적어 늦게 잡는 쪽이 더 위험하다). 깨어 있는 아이도 같다
        val people = listOf(adult(), child("kid", 7), Occupant("g", OccupantType.ELDERLY))
        val readings = listOf(kst(2026, 9, 24, 15, 0), kst(2026, 9, 25, 3, 0), kst(2026, 9, 24, 19, 30), kst(2026, 9, 25, 6, 30)).map { start ->
            val m = model(people, start)
            m.run(start, start + 50 * MIN, step = MIN)
            Triple(m.gauge(start + 50 * MIN, texts), m.deadlineMin(start + 50 * MIN, 0.3), m.tracked.map { m.tauMin(it, start + 50 * MIN) })
        }
        for (r in readings.drop(1)) assertEquals(readings[0], r)
        assertEquals(55, readings[0].first.minutes)
    }

    @Test
    fun aCorrectedLastRestroomStartsAPlainInterval() {
        val start = noon
        val people = listOf(adult())
        val clock = EngineClock().apply { advance(start) }
        val m = RestroomNeedModel(cfg).also { it.sync(TripProfile(people, tripStart = start), clock, PersonalMemory()) }
        m.mealStop(start + 10 * MIN)
        clock.advance(start + 90 * MIN)
        // 같은 프로필을 다시 넣으면 그대로
        m.sync(TripProfile(people, tripStart = start), clock, PersonalMemory())
        assertEquals(0.8, m.who("me").mealFactor)
        // 사용자가 '방금 다녀왔다'고 고치면 그때부터 새 구간이고, 식사 정차에서 시작한 구간이 아니다
        m.sync(TripProfile(people, tripStart = start, lastRestroomAt = mapOf("me" to start + 80 * MIN)), clock, PersonalMemory())
        assertEquals(1.0, m.who("me").mealFactor)
        assertEquals(140, m.gauge(start + 90 * MIN, texts).minutes)
    }

    @Test
    fun withoutAnyFactorTheNumbersAreExactlyTheOldOnes() {
        // 배수가 없으면 유효 시간 = 지난 시간이고, 게이지·마감은 예전 식(문턱 − 경과)과 비트까지 같다
        val m = model(listOf(adult(), child("kid", 7), Occupant("g", OccupantType.ELDERLY), infant()), noon,
            last = mapOf("me" to noon - 41 * MIN - 7 * SEC, "kid" to noon - 13 * MIN))
        m.run(noon, noon + 37 * MIN + 11 * SEC, step = 61 * SEC)
        val at = noon + 37 * MIN + 11 * SEC
        for (t in m.tracked) {
            assertEquals(m.elapsedMin(t, at), m.tauMin(t, at))
            assertEquals(t.thresholdMin - m.elapsedMin(t, at), m.remainingMin(t, at))
            assertEquals(1.0, t.mealFactor)
        }
        val solo = m.tracked.minOf { minOf(it.posterior.quantile(0.3), it.capMin) - m.elapsedMin(it, at) }
        assertEquals(maxOf(0.0, solo), m.soloDeadlineMin(at, 0.3))
    }
}
