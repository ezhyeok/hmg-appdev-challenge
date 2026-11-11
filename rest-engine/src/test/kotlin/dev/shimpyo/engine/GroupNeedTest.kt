package dev.shimpyo.engine

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 여럿이 탔을 때: 게이지는 가장 급한 한 사람, 판단 마감은 '누군가 필요해질 확률' 1 − Π S_i(e_i + Δ)
 * (RestroomNeedModel 클래스 설명, AI §8-1·§8-2).
 *
 * 막는 회귀 세 가지.
 * - 마감까지 한 사람 기준이던 때(2026-11-11 감사 수정 직후): 어른 둘이 타면 '필요 확률 30%' 마감에서 실제로 누군가
 *   필요할 확률이 51%였고, 시뮬레이션에서 어른 둘의 명절 위기 비율이 14.1%(묶음 마감 12.0%)로 올랐다.
 * - 게이지까지 묶음 기준으로 바꾸는 것: 막히지 않는 길에서 질문만 늘었다(어른 둘 1.7 → 3.1회, 위기는 둘 다 0%).
 * - 묶음 때문에 앞당겨 물으면서 설명이 없던 때(감사 O6): 게이지가 넉넉한데 왜 묻는지 화면이 말해야 한다.
 */
class GroupNeedTest {
    private val cfg = CoachConfig()
    private val texts = CoachTexts()
    private val t0 = kst(2026, 9, 24, 9, 0)
    private val km = 1_000.0

    private fun model(occupants: List<Occupant>, last: Map<String, Long> = emptyMap()): RestroomNeedModel {
        val clock = EngineClock().apply { advance(t0) }
        return RestroomNeedModel(cfg).also { it.sync(TripProfile(occupants, tripStart = t0, lastRestroomAt = last), clock, PersonalMemory()) }
    }

    private fun adults(n: Int) = (1..n).map { adult("a$it") }

    /** 모델의 사후분포로 직접 계산한 '누군가 필요해질 확률' */
    private fun anyNeed(m: RestroomNeedModel, at: Long, deltaMin: Double): Double =
        1.0 - m.tracked.fold(1.0) { s, t -> s * t.posterior.survival(m.elapsedMin(t, at) + deltaMin) }

    private fun approx(expected: Double, actual: Double, tol: Double, msg: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$msg expected $expected ± $tol but was $actual")

    // ------------------------------------------------------------ 모델

    @Test
    fun aloneTheDeadlineIsThatPersonsOwnCurve() {
        val m = model(adults(1))
        val t = m.tracked.single()
        approx(t.posterior.quantile(0.3), m.deadlineMin(t0, 0.3)!!, 1e-9)
        approx(t.posterior.quantile(0.3) - 20, m.deadlineMin(t0 + 20 * MIN, 0.3)!!, 1e-9)
        assertEquals(m.soloDeadlineMin(t0, 0.3), m.deadlineMin(t0, 0.3))
    }

    @Test
    fun theDeadlineIsWhereSomeoneNeedsItWithTheDecisionProbability() {
        // 한 사람 기준 마감이었다면 어른 둘에서 0.51, 넷에서 0.76이 나온다
        for (n in listOf(2, 4, 8)) {
            val m = model(adults(n))
            val deadline = m.deadlineMin(t0, 0.3)!!
            approx(0.3, anyNeed(m, t0, deadline), 1e-6, "어른 ${n}명의 마감에서 누군가 필요할 확률")
            assertTrue(deadline < m.soloDeadlineMin(t0, 0.3)!!, "여럿이면 한 사람 기준보다 이르다")
        }
        approx(83.0, model(adults(2)).deadlineMin(t0, 0.3)!!, 2.0, "어른 둘")
        approx(63.0, model(adults(4)).deadlineMin(t0, 0.3)!!, 2.0, "어른 넷")
        // 서로 다른 탑승자, 서로 다른 경과 시간
        val mixed = model(listOf(adult("me"), child("kid", 7)), last = mapOf("me" to t0 - 80 * MIN, "kid" to t0 - 30 * MIN))
        approx(0.3, anyNeed(mixed, t0, mixed.deadlineMin(t0, 0.3)!!), 1e-6)
    }

    @Test
    fun theGaugeStaysWithTheMostConstrainedPerson() {
        // 게이지는 인원이 늘어도 그대로다 (묶음 기준으로 바꾸면 막히지 않는 길에서 질문만 는다)
        for (n in listOf(1, 2, 4, 8)) assertEquals(150, model(adults(n)).gauge(t0, texts).minutes, "어른 ${n}명")
        val family = model(listOf(adult("me"), child("kid", 7)), last = mapOf("me" to t0 - 55 * MIN, "kid" to t0 - 55 * MIN))
        val g = family.gauge(t0, texts)
        assertEquals(50, g.minutes, "기획서 장면 A: 7살 아이 105 − 55")
        assertEquals("kid", g.drivenBy)
        // 같은 장면의 판단 마감은 운전자까지 쳐서 아이 혼자(약 24분)보다 이르다
        val deadline = family.deadlineMin(t0, 0.3)!!
        assertTrue(deadline in 11.0..15.0, "장면 A의 마감 약 13분: $deadline")
        approx(23.7, family.soloDeadlineMin(t0, 0.3)!!, 1.0)
    }

    @Test
    fun capsAndVoicedRequestsStillBind() {
        // 영아 카시트 2시간 상한: 확률이 문턱에 닿기 전이어도 상한에서 멈춘다
        val withInfant = model(listOf(adult("me"), infant("baby")))
        assertTrue(withInfant.deadlineMin(t0, 0.3)!! <= 120.0)
        assertTrue(withInfant.deadlineMin(t0, 0.3)!! <= withInfant.soloDeadlineMin(t0, 0.3)!!)
        // 한 사람이 말로 알리면 바로 0
        val m = model(adults(3))
        m.onVoiced("a2", Purpose.RESTROOM, t0 + 40 * MIN, PersonalMemory())
        assertEquals(0.0, m.deadlineMin(t0 + 40 * MIN, 0.3))
        assertEquals(0.0, m.soloDeadlineMin(t0 + 40 * MIN, 0.3))
        // 방금 다녀온 상태로 계산하면 말한 것과 경과 시간을 무시한다
        assertTrue(m.deadlineMin(t0 + 40 * MIN, 0.3, fromReset = true)!! > 40.0)
        // 한참 지나면 0, 탑승자가 없으면 null
        assertEquals(0.0, model(adults(2)).deadlineMin(t0 + 20 * 60 * MIN, 0.3))
        assertNull(model(emptyList()).deadlineMin(t0, 0.3))
        assertNull(model(emptyList()).soloDeadlineMin(t0, 0.3))
    }

    // ------------------------------------------------------------ 엔진: 묶음 때문에 묻는 것은 이유를 말한다

    private fun engine(people: List<Occupant>, sinceMin: Int) = CoachEngine(
        profile = TripProfile(people, tripStart = t0 - sinceMin * MIN, lastRestroomAt = people.associate { it.id to t0 - sinceMin * MIN }),
        random = Random(7),
    )

    /** 3.5km 앞 휴게소 뒤로 화장실이 [everyKm]마다 있는 길 (시속 100km, 목적지 200km 앞) */
    private fun drive(e: CoachEngine, everyKm: Double, seconds: Int = 60): List<CoachOutput> {
        val route = RouteState(guiding = true, remainDistanceM = 200 * km, remainTimeS = 7_200.0, nextManeuverDistanceM = 100 * km)
        val cands = generateSequence(3.5) { it + everyKm }.takeWhile { it < 200.0 }
            .mapIndexed { i, d -> stop("s$i", d * km, name = "${i + 1}번 휴게소") }.toList()
        return (0 until seconds).map { s -> e.update(CoachInput(now = t0 + s * SEC, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands)) }
    }

    private fun CoachOutput.lastChanceCodes(): List<String> = next?.reasons.orEmpty().map { it.code }.filter { it.startsWith("LAST_CHANCE") }

    @Test
    fun aLastChanceBroughtForwardByTheGroupSaysSo() {
        // 어른 넷, 마지막 화장실 70분 전: 한 사람만 보면 마감까지 42분이라 40분 공백을 넘길 수 있지만, 넷 중 누군가는 다르다
        val outs = drive(engine(adults(4), sinceMin = 70), everyKm = 40.0)
        val out = outs.last()
        assertEquals(Level.OK, out.restroom.level, "게이지는 80분으로 넉넉하다")
        assertEquals(listOf("LAST_CHANCE_GAP", "LAST_CHANCE_GROUP"), out.lastChanceCodes(), out.next.toString())
        val group = out.next!!.reasons.single { it.code == "LAST_CHANCE_GROUP" }
        assertTrue(group.text.contains("4명"), group.text)
        assertTrue(outs.any { it.prompt?.kind == PromptKind.LAST_CHANCE }, "첫 휴게소 2~5km 전에 묻는다")
    }

    @Test
    fun whenOnePersonAloneWouldTriggerItThereIsNoGroupReason() {
        // 같은 넷이라도 마지막 화장실 100분 전이면 한 사람만 봐도 40분 공백을 못 넘긴다: 묶음 설명은 붙이지 않는다
        assertEquals(listOf("LAST_CHANCE_GAP"), drive(engine(adults(4), sinceMin = 100), everyKm = 40.0).last().lastChanceCodes())
        // 혼자 타면 당연히 없다
        assertEquals(listOf("LAST_CHANCE_GAP"), drive(engine(adults(1), sinceMin = 100), everyKm = 40.0).last().lastChanceCodes())
    }

    @Test
    fun withoutAGapNobodyIsAskedHoweverManyPeople() {
        // 화장실이 10km(6분)마다 있으면 마감이 지나도 물을 공백이 없다. 묶음 확률은 긴 공백 앞에서만 일한다
        val outs = drive(engine(adults(8), sinceMin = 70), everyKm = 10.0)
        assertEquals(Level.OK, outs.last().restroom.level)
        assertTrue(outs.none { it.prompt?.kind == PromptKind.LAST_CHANCE }, outs.firstNotNullOfOrNull { it.prompt }.toString())
        assertTrue(outs.last().lastChanceCodes().isEmpty(), outs.last().next.toString())
    }
}
