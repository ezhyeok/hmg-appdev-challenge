package dev.shimpyo.engine

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestroomNeedModelTest {
    private val cfg = CoachConfig()
    private val texts = CoachTexts()
    private val t0 = kst(2026, 9, 24, 9, 0)

    private fun model(occupants: List<Occupant>, memory: PersonalMemory = PersonalMemory(), last: Map<String, Long> = emptyMap()): Pair<RestroomNeedModel, EngineClock> {
        val clock = EngineClock().apply { advance(t0) }
        val m = RestroomNeedModel(cfg)
        m.sync(TripProfile(occupants, tripStart = t0, lastRestroomAt = last), clock, memory)
        return m to clock
    }

    private fun approx(expected: Double, actual: Double, tol: Double, msg: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$msg expected $expected ± $tol but was $actual")

    @Test
    fun gammaFunctionIsAccurate() {
        approx(1.0, EngineMath.gamma(1.0), 1e-12)
        approx(24.0, EngineMath.gamma(5.0), 1e-9)
        approx(0.8929795115692493, EngineMath.gamma(4.0 / 3.0), 1e-12, "Γ(4/3)")
        approx(kotlin.math.sqrt(Math.PI), EngineMath.gamma(0.5), 1e-12)
    }

    @Test
    fun priorPredictiveMedianEqualsProfileMedian() {
        val (m, _) = model(listOf(adult()))
        val t = m.tracked.single()
        // b0 = m0^k / (2^(1/a0) − 1) 로 두면 사전 예측 중앙값이 정확히 m0 (ai-science.md 8-1)
        approx(150.0, t.thresholdMin, 1e-6, "성인 중앙값 2.5시간")
        approx(0.5, t.posterior.survival(150.0), 1e-9)
        approx(3.0, t.posterior.a, 1e-12)
        val g = m.gauge(t0, texts)
        assertEquals(150, g.minutes)
        assertEquals(Level.OK, g.level)
        assertEquals("me", g.drivenBy)
    }

    @Test
    fun childPriorUsesAgeBucketsAndOverride() {
        val (m, _) = model(
            listOf(
                child("a", 4), child("b", 8), child("c", null), child("d", 2), child("e", 12),
                Occupant("f", OccupantType.ADULT, intervalOverrideMin = 75.0),
            ),
        )
        val th = m.tracked.associate { it.id to it.thresholdMin }
        approx(60.0, th.getValue("a"), 1e-6, "3~5세 1시간")
        approx(105.0, th.getValue("b"), 1e-6, "6~9세 1.75시간")
        approx(60.0, th.getValue("c"), 1e-6, "나이 모름 → 보수적")
        approx(60.0, th.getValue("d"), 1e-6, "3세 미만도 1시간")
        approx(150.0, th.getValue("e"), 1e-6, "10세 이상 → 성인")
        approx(75.0, th.getValue("f"), 1e-6, "사람별 지정 간격")
    }

    @Test
    fun capsBoundElderlyPregnantAndInfant() {
        val (m, _) = model(listOf(Occupant("g", OccupantType.ELDERLY), Occupant("p", OccupantType.PREGNANT), infant()))
        val th = m.tracked.associate { it.id to it.thresholdMin }
        approx(120.0, th.getValue("g"), 1e-6)
        approx(90.0, th.getValue("p"), 1e-6)
        approx(120.0, th.getValue("baby"), 1e-6, "수유 3시간이지만 카시트 2시간 상한")
        val (only, _) = model(listOf(infant()))
        val g = only.gauge(t0 + 10 * MIN, texts)
        assertEquals(110, g.minutes)
        assertTrue(g.reasons.any { it.code == "RESTROOM_CAP" && it.text.contains("카시트") }, g.reasons.toString())
    }

    @Test
    fun gaugeIsMinimumAcrossOccupantsAndRecordsDrivenBy() {
        val (m, _) = model(listOf(adult(), Occupant("grandma", OccupantType.ELDERLY), child("kid", 5)))
        val g = m.gauge(t0 + 30 * MIN, texts)
        assertEquals("kid", g.drivenBy)
        assertEquals(30, g.minutes) // 60 − 30
        assertEquals(Level.WATCH, g.level)
        assertEquals("RESTROOM_ELAPSED", g.reasons.first().code)
        assertTrue(g.reasons.first().text.contains("30분"), g.reasons.first().text)
    }

    @Test
    fun levelBoundariesFollowSpec() {
        assertEquals(Level.OK, restroomLevel(46, cfg))
        assertEquals(Level.WATCH, restroomLevel(45, cfg))
        assertEquals(Level.WATCH, restroomLevel(21, cfg))
        assertEquals(Level.SOON, restroomLevel(20, cfg))
        assertEquals(Level.SOON, restroomLevel(10, cfg))
        assertEquals(Level.NOW, restroomLevel(9, cfg))
        assertEquals(Level.NOW, restroomLevel(0, cfg))
    }

    @Test
    fun lastRestroomFromProfileIsUsed() {
        val (m, _) = model(listOf(adult()), last = mapOf("me" to t0 - 100 * MIN))
        assertEquals(50, m.gauge(t0, texts).minutes)
    }

    @Test
    fun posteriorFollowsWeightedConjugateUpdate() {
        val k = 2.5
        val b0 = NeedPosterior.priorB(150.0, k, 3.0)
        val p = NeedPosterior.posterior(150.0, k, 3.0, listOf(60.0, 90.0), listOf(75.0), listOf(200.0), 0.5, 20)
        approx(3.0 + 2.0 + 0.5, p.a, 1e-12, "사건 2회 + 권한 정차 0.5회")
        approx(b0 + 60.0.pow(k) + 90.0.pow(k) + 75.0.pow(k) + 200.0.pow(k), p.b, 1e-3)
        assertEquals(2, p.nExact)
        assertEquals(1, p.nSuggested)
        assertEquals(1, p.nCensored)
        // 분위수와 생존함수가 서로 역함수
        approx(0.7, p.survival(p.quantile(0.3)), 1e-9)
    }

    @Test
    fun shortObservationsShrinkAndLongOnesGrowTheGauge() {
        val prior = model(listOf(adult())).first.tracked.single().thresholdMin
        val short = model(listOf(adult()), PersonalMemory(restroomIntervalsMin = mapOf("me" to List(5) { 60.0 }))).first.tracked.single().thresholdMin
        val long = model(listOf(adult()), PersonalMemory(restroomIntervalsMin = mapOf("me" to List(5) { 260.0 }))).first.tracked.single().thresholdMin
        approx(103.4, short, 0.5, "손 계산 값 (a = 8, b = b0 + 5·60^2.5)")
        assertTrue(short < prior - 40, "짧은 간격 5번: $short < $prior")
        assertTrue(long > prior + 40, "긴 간격 5번: $long > $prior")
    }

    @Test
    fun suggestedStopsCountHalfAndCensoredOnlyLengthen() {
        val exact = model(listOf(adult()), PersonalMemory(restroomIntervalsMin = mapOf("me" to List(4) { 70.0 }))).first.tracked.single().thresholdMin
        val sugg = model(listOf(adult()), PersonalMemory(restroomSuggestedMin = mapOf("me" to List(4) { 70.0 }))).first.tracked.single().thresholdMin
        val prior = 150.0
        assertTrue(exact < sugg && sugg < prior, "권해서 들른 정차는 덜 줄인다: $exact < $sugg < $prior")
        val censored = model(listOf(child()), PersonalMemory(restroomCensoredMin = mapOf("kid" to listOf(30.0, 40.0)))).first
            .tracked.single().thresholdMin
        assertTrue(censored >= 60.0, "중도절단은 노출만 늘린다: $censored")
    }

    @Test
    fun onlyTheMostRecentNObservationsCount() {
        val many = List(100) { if (it < 20) 60.0 else 400.0 }
        val p = model(listOf(adult()), PersonalMemory(restroomIntervalsMin = mapOf("me" to many))).first.tracked.single().posterior
        assertEquals(20, p.nExact)
    }

    @Test
    fun groupDeadlineUsesDecisionProbability() {
        val (one, _) = model(listOf(adult()))
        val q03 = one.tracked.single().posterior.quantile(0.3)
        approx(q03, one.deadlineMin(t0, 0.3)!!, 1e-6, "한 사람이면 Q(0.3) − 경과")
        approx(q03 - 20, one.deadlineMin(t0 + 20 * MIN, 0.3)!!, 1e-6)
        assertTrue(q03 < 150.0, "판단(0.3)은 화면 게이지(0.5)보다 이르다")

        // 여럿이면 가장 급한 사람의 0.3 지점 (게이지를 정하는 사람과 같은 곡선). 예전 '누군가 필요해질 확률'은 인원이 늘수록
        // 게이지와 멀어졌다: 어른 2명이면 게이지 66분 OK일 때 마감이 지났다 (감사 O6, AuditOccupantsTest)
        val (two, _) = model(listOf(child("a", 5), child("b", 5)))
        val single = two.tracked.first().posterior.quantile(0.3)
        approx(single, two.deadlineMin(t0, 0.3)!!, 1e-6, "같은 두 아이면 한 아이의 Q(0.3)")
        val (mixed, _) = model(listOf(adult(), child("kid", 5)), last = mapOf("me" to t0 - 100 * MIN))
        val kid = mixed.tracked.single { it.id == "kid" }.posterior.quantile(0.3)
        val me = mixed.tracked.single { it.id == "me" }.posterior.quantile(0.3) - 100
        approx(minOf(kid, me), mixed.deadlineMin(t0, 0.3)!!, 1e-6, "각자 남은 시간 중 짧은 쪽")

        val (capped, _) = model(listOf(infant()))
        approx(120.0, capped.deadlineMin(t0, 0.3)!!, 1e-6, "카시트 상한이 확률보다 먼저")
        assertEquals(0.0, model(listOf(adult())).first.deadlineMin(t0 + 20 * 60 * MIN, 0.3))
        assertNull(model(emptyList()).first.deadlineMin(t0, 0.3))
    }

    @Test
    fun groupDeadlineFromResetIgnoresElapsedAndVoice() {
        val (m, _) = model(listOf(adult(), child("kid", 8)))
        m.onVoiced("kid", Purpose.RESTROOM, t0 + 50 * MIN, PersonalMemory())
        assertEquals(0.0, m.deadlineMin(t0 + 50 * MIN, 0.3))
        val reset = m.deadlineMin(t0 + 50 * MIN, 0.3, fromReset = true)!!
        assertTrue(reset > 50.0, "방금 다녀온 상태로 계산: $reset")
    }

    @Test
    fun voicedNeedPushesToNowAndRecordsObservationOnce() {
        val (m, _) = model(listOf(adult(), child("kid", 9)))
        var mem = PersonalMemory()
        mem = m.onVoiced("kid", Purpose.RESTROOM, t0 + 50 * MIN, mem)
        val g = m.gauge(t0 + 50 * MIN, texts)
        assertEquals(0, g.minutes)
        assertEquals(Level.NOW, g.level)
        assertEquals("kid", g.drivenBy)
        assertEquals("RESTROOM_VOICED", g.reasons.first().code)
        assertEquals(listOf(50.0), mem.restroomIntervalsMin["kid"])
        val again = m.onVoiced("kid", Purpose.RESTROOM, t0 + 55 * MIN, mem)
        assertEquals(mem, again, "같은 필요를 두 번 기록하지 않는다")
    }

    @Test
    fun voicedNeedWithoutIdGoesToMostConstrainedSpeakingOccupant() {
        val (m, _) = model(listOf(adult(), infant(), child("kid", 9)))
        m.onVoiced(null, Purpose.RESTROOM, t0 + 30 * MIN, PersonalMemory())
        assertEquals("kid", m.tracked.single { it.voiced }.id, "영아가 아니라 말할 수 있는 사람 중 가장 급한 아이")
        val (m2, _) = model(listOf(adult(), infant()))
        m2.onVoiced(null, Purpose.FEEDING, t0 + 30 * MIN, PersonalMemory())
        assertEquals("baby", m2.tracked.single { it.voiced }.id)
        val (m3, _) = model(listOf(adult()))
        val mem = m3.onVoiced(null, Purpose.PET, t0 + 30 * MIN, PersonalMemory())
        assertTrue(m3.tracked.none { it.voiced }, "반려견이 없으면 무시")
        assertEquals(PersonalMemory(), mem)
    }

    @Test
    fun restroomStopResetsEveryoneEventForDriverCensoredForOthers() {
        val (m, _) = model(listOf(adult(), child("kid", 5)))
        val at = t0 + 70 * MIN
        val mem = m.onRestroomStop(at, PersonalMemory())
        assertEquals(listOf(70.0), mem.restroomIntervalsMin["kid"], "게이지를 정하던 아이는 사건")
        assertEquals(listOf(70.0), mem.restroomCensoredMin["me"], "어른은 중도절단 (정차 때 배뇨는 대개 기회가 와서 한다)")
        assertNull(mem.restroomIntervalsMin["me"])
        val g = m.gauge(at, texts)
        assertEquals("kid", g.drivenBy)
        assertEquals(60, g.minutes, "시계는 0으로. 70분 관측 1회가 반영되어 문턱이 60.2분")
        assertEquals("RESTROOM_PERSONAL", g.reasons.last().code)
    }

    @Test
    fun suggestedStopIsRecordedAsHalfEvent() {
        val (m, _) = model(listOf(adult()))
        val mem = m.onRestroomStop(t0 + 80 * MIN, PersonalMemory(), suggested = true)
        assertEquals(listOf(80.0), mem.restroomSuggestedMin["me"])
        assertTrue(mem.restroomIntervalsMin.isEmpty())
        approx(3.5, m.tracked.single().posterior.a, 1e-12)
    }

    @Test
    fun voicedOccupantIsNotRecordedAgainAtTheStop() {
        val (m, _) = model(listOf(adult(), child("kid", 5)))
        var mem = m.onVoiced("kid", Purpose.RESTROOM, t0 + 40 * MIN, PersonalMemory())
        mem = m.onRestroomStop(t0 + 50 * MIN, mem)
        assertEquals(listOf(40.0), mem.restroomIntervalsMin["kid"])
        assertEquals(listOf(50.0), mem.restroomCensoredMin["me"])
        assertTrue(m.tracked.none { it.voiced }, "정차하면 음성 요청도 풀린다")
    }

    @Test
    fun tooShortIntervalsAreNotObservations() {
        val (m, _) = model(listOf(adult()))
        val mem = m.onRestroomStop(t0 + 10 * MIN, PersonalMemory())
        assertTrue(mem.restroomIntervalsMin.isEmpty() && mem.restroomCensoredMin.isEmpty())
    }

    @Test
    fun memoryListIsCappedMostRecentFirst() {
        val (m, _) = model(listOf(adult()))
        var mem = PersonalMemory(restroomIntervalsMin = mapOf("me" to List(20) { 100.0 }))
        m.refresh(mem)
        mem = m.onRestroomStop(t0 + 77 * MIN, mem)
        val list = mem.restroomIntervalsMin.getValue("me")
        assertEquals(20, list.size)
        assertEquals(77.0, list.first())
    }

    @Test
    fun veryLongElapsedClampsAtZeroNow() {
        val (m, _) = model(listOf(adult()))
        val g = m.gauge(t0 + 20 * 60 * MIN, texts)
        assertEquals(0, g.minutes)
        assertEquals(Level.NOW, g.level)
    }

    @Test
    fun voicedNeedExpiresAfterHold() {
        val (m, _) = model(listOf(adult()))
        m.onVoiced("me", Purpose.RESTROOM, t0 + 10 * MIN, PersonalMemory())
        m.tick(t0 + 10 * MIN + cfg.voicedNeedHoldMin * MIN + 1)
        assertTrue(m.tracked.none { it.voiced })
    }

    @Test
    fun noOccupantsGivesNullGauge() {
        val (m, _) = model(emptyList())
        val g = m.gauge(t0, texts)
        assertNull(g.minutes)
        assertEquals(Level.OK, g.level)
        assertEquals("RESTROOM_NONE", g.reasons.single().code)
    }

    @Test
    fun sensitiveCoverageAndHumanCount() {
        assertTrue(!model(listOf(adult())).first.sensitive)
        assertTrue(model(listOf(adult(), Occupant("p", OccupantType.PREGNANT))).first.sensitive)
        val (m, _) = model(listOf(Occupant("o", OccupantType.OAB), Occupant("i", OccupantType.IBS), Occupant("d", OccupantType.DOG)))
        assertEquals(15, m.preferredMaxGapMin(), "IBS 15분, 과민성 방광 20분 중 짧은 쪽")
        assertEquals(2, m.humanCount)
        assertNull(model(listOf(adult())).first.preferredMaxGapMin())
    }

    @Test
    fun syncKeepsClocksForSameIdAndAddsNewOccupants() {
        val (m, clock) = model(listOf(adult()))
        clock.advance(t0 + 60 * MIN)
        m.sync(TripProfile(listOf(adult(), child("kid", 9)), tripStart = t0), clock, PersonalMemory())
        val g = m.gauge(t0 + 60 * MIN, texts)
        // 어른은 60분 지남(90), 아이는 출발부터(105 − 60 = 45)
        assertEquals("kid", g.drivenBy)
        assertEquals(45, g.minutes)
    }
}
