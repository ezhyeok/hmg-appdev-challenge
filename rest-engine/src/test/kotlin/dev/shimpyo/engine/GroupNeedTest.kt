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
        // 문서(SPEC 4.1, README 3-2)에 적은 수: 방금 다녀온 어른 한 명 112분, 둘 84분, 넷 63분, 여덟 48분
        approx(112.4, model(adults(1)).deadlineMin(t0, 0.3)!!, 0.1, "어른 한 명")
        approx(84.1, model(adults(2)).deadlineMin(t0, 0.3)!!, 0.1, "어른 둘")
        approx(63.4, model(adults(4)).deadlineMin(t0, 0.3)!!, 0.1, "어른 넷")
        approx(47.9, model(adults(8)).deadlineMin(t0, 0.3)!!, 0.1, "어른 여덟")
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

    // ------------------------------------------------------------ 묶음 이유는 '한 사람만 봤다면 묻지 않았을' 때만 (닫힘 검증 E5c)

    private val v100 = 100 / 3.6
    private val open = RouteState(guiding = true, remainDistanceM = 200 * km, remainTimeS = 200 * km / v100, nextManeuverDistanceM = 100 * km)

    private fun CoachEngine.at(route: RouteState, cands: List<StopCandidate>): CoachOutput =
        (0 until 3).map { s -> update(CoachInput(now = t0 + s * SEC, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands)) }.last()

    @Test
    fun theGroupReasonIsNotGivenWhenOnePersonAloneIsAskedAtTheSameStop() {
        // 회귀: 한 사람 기준 판정을 커버리지 규칙 없이 다시 했다. 과민성 방광 + 어른 셋: 그 사람 혼자여도 같은 곳에서 묻는데
        // (3.5km 앞 휴게소 뒤 28분 공백 > 기준 20분) '4명이 타서'가 붙었다
        val occ = listOf(Occupant("oab", OccupantType.OAB)) + adults(3)
        val cands = listOf(stop("s0", 3.5 * km)) + (0..14).map { stop("t$it", (50 + it * 10) * km) }
        val alone = engine(occ.take(1), sinceMin = 30).at(open, cands)
        assertEquals("s0", alone.next?.stop?.id, "혼자여도 s0에서 묻는다: ${alone.next?.reasons}")
        val group = engine(occ, sinceMin = 30).at(open, cands)
        assertEquals("s0", group.next?.stop?.id)
        assertEquals(listOf("LAST_CHANCE_GAP"), group.lastChanceCodes())
    }

    @Test
    fun theGroupReasonIsNotGivenWhenOnePersonAloneWouldBeAskedEarlier() {
        // 회귀: '한 사람 기준이면 이곳이 아니다'에 한 사람 기준이 더 앞인 경우도 들었다. 어른 둘, 화장실 60분 전: 묶음 판정은
        // 150km 앞 정체 전 휴게소(s4), 한 사람 판정은 80km 앞 공백 전 휴게소(s2). 묶음 때문에 '앞당겨진' 것이 아닌데 s4에 '2명이 타서'가 붙었다
        val destM = 300 * km
        val jam = CongestionSegment(startDistanceM = 151 * km, lengthM = 20 * km, durationS = 2_400.0)
        val route = RouteState(guiding = true, remainDistanceM = destM, remainTimeS = (destM - 20 * km) / v100 + 2_400.0, congestion = listOf(jam), nextManeuverDistanceM = 100 * km)
        val cands = listOf(stop("s0", 30 * km), stop("s1", 50 * km), stop("s2", 80 * km), stop("s3", 110 * km), stop("s4", 150 * km), stop("s5", 180 * km))
        val one = engine(adults(1), sinceMin = 60).at(route, cands)
        val two = engine(adults(2), sinceMin = 60).at(route, cands)
        assertEquals("s2", one.next?.stop?.id, "전제: 한 사람 기준은 더 앞")
        assertEquals("s4", two.next?.stop?.id)
        assertEquals(listOf("LAST_CHANCE_CONGESTION"), two.lastChanceCodes())
    }

    // ------------------------------------------------------------ 묶음 때문에 선 정차는 누구의 사건도 아니다 (닫힘 검증 E5b)

    /** 40km마다 휴게소가 있는 400km 길을 달리다 첫 마지막 기회를 수락하고 그곳에 5분 선다. (묶음 이유가 붙었는지, 배운 것) */
    private fun acceptFirstLastChanceAndStop(people: List<Occupant>): Pair<Boolean, PersonalMemory> {
        val e = CoachEngine(profile = TripProfile(people, tripStart = t0, lastRestroomAt = people.associate { it.id to t0 }), random = Random(1))
        var posM = 0.0
        var t = t0
        var target: Double? = null
        var groupDriven = false
        val destM = 400 * km
        for (s in 0 until 3 * 3600) {
            val cands = (1..9).map { it to it * 40 * km - posM }.filter { it.second > 0 }.map { (i, d) -> stop("s$i", d) }
            val r = RouteState(guiding = true, remainDistanceM = destM - posM, remainTimeS = (destM - posM) / v100, nextManeuverDistanceM = 100 * km)
            val o = e.update(CoachInput(now = t, gear = Gear.DRIVE, speedKph = 100f, route = r, candidates = cands))
            val p = o.prompt
            if (p != null && p.kind == PromptKind.LAST_CHANCE && target == null) {
                groupDriven = o.next?.reasons.orEmpty().any { it.code == "LAST_CHANCE_GROUP" }
                e.onPromptResult(p.id, true, t)
                target = posM + p.stop!!.routeDistanceM!!
            }
            t += SEC
            posM += v100
            if (target != null && posM >= target) {
                repeat(300) { k -> e.update(CoachInput(now = t, gear = Gear.PARK, speedKph = 0f, doorOpened = k == 5, route = r)); t += SEC }
                break
            }
        }
        return groupDriven to e.memory()
    }

    @Test
    fun aStopSuggestedBecauseOfTheGroupIsNotLearnedAsOnePersonsNeed() {
        // 회귀: 묶음 때문에 앞당겨 권한 정차도 '가장 급한 한 사람'(어른끼리면 늘 첫 번째 탑승자)의 사건 0.5회로 배웠다. 넷이 타면 48분,
        // 8명이면 24분 간격이 그 사람의 기록이 되어 다음 여정의 게이지가 150 → 141 → 133 → 128 → 123분으로 줄었다
        // (혼자면 150 → 145 → 141). 정한 규칙: 그런 정차는 모두에게 '적어도 그만큼 버텼다'(중도절단)이다
        val (groupDriven, m) = acceptFirstLastChanceAndStop(adults(4))
        assertTrue(groupDriven, "전제: 묶음 때문에 앞당겨진 마지막 기회를 수락했다")
        assertTrue(m.restroomSuggestedMin.isEmpty() && m.restroomIntervalsMin.isEmpty(), "권유 ${m.restroomSuggestedMin} 사건 ${m.restroomIntervalsMin}")
        assertEquals(setOf("a1", "a2", "a3", "a4"), m.restroomCensoredMin.keys, "모두 '버틴 시간'으로만")
        assertTrue(m.restroomCensoredMin.values.all { it.single() in 45.0..55.0 }, m.restroomCensoredMin.toString())
        // 대조: 혼자 타면 묶음이 아니다. 같은 길에서 수락한 마지막 기회는 그 사람의 '권해서 들른 정차' 0.5회다
        val (soloGroup, solo) = acceptFirstLastChanceAndStop(adults(1))
        assertTrue(!soloGroup)
        assertEquals(1, solo.restroomSuggestedMin["a1"]?.size, solo.toString())
    }

    @Test
    fun theModelRecordsAGroupDrivenStopAsCensoredForEveryone() {
        val m = model(adults(3))
        val at = t0 + 50 * MIN
        val plain = m.onRestroomStop(at, PersonalMemory(), suggested = true)
        assertEquals(setOf("a1"), plain.restroomSuggestedMin.keys, "묶음이 아니면 가장 급한 사람(동률이면 첫 사람)의 0.5회")
        val m2 = model(adults(3))
        val group = m2.onRestroomStop(at, PersonalMemory(), suggested = true, groupDriven = true)
        assertTrue(group.restroomSuggestedMin.isEmpty() && group.restroomIntervalsMin.isEmpty())
        assertEquals(mapOf("a1" to listOf(50.0), "a2" to listOf(50.0), "a3" to listOf(50.0)), group.restroomCensoredMin)
        // 중도절단은 게이지를 줄이지 않는다 (0.5회 사건이면 150 → 140분대로 준다)
        assertTrue(m2.gauge(at, texts).minutes!! >= 150, m2.gauge(at, texts).toString())
        assertTrue(m.gauge(at, texts).minutes!! < 150)
    }

    @Test
    fun aGroupDrivenLastChancePickedOnTheScreenIsNotLearnedAsOnePersonsNeedEither() {
        // 묶음 때문에 앞당겨진 마지막 기회(가휴게소 → 40km 공백)를 질문이 뜨기 전에 계획 화면에서 골랐다 (noteAccepted)
        val people = adults(4)
        val e = engine(people, sinceMin = 70)
        val cands = generateSequence(8.0) { it + 40.0 }.takeWhile { it < 200.0 }.mapIndexed { i, d -> stop("s$i", d * km, name = "${i + 1}번 휴게소") }.toList()
        val route = RouteState(guiding = true, remainDistanceM = 200 * km, remainTimeS = 7_200.0, nextManeuverDistanceM = 100 * km)
        var t = t0
        val first = e.update(CoachInput(now = t, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands))
        assertEquals(listOf("LAST_CHANCE_GAP", "LAST_CHANCE_GROUP"), first.lastChanceCodes())
        assertEquals(null, first.prompt, "8km 앞: 아직 묻는 거리가 아니다")
        e.noteAccepted(cands.first(), setOf(Purpose.RESTROOM), t)
        repeat(300) { k -> t += SEC; e.update(CoachInput(now = t, gear = Gear.PARK, speedKph = 0f, doorOpened = k == 5, route = route)) }
        val m = e.memory()
        assertTrue(m.restroomSuggestedMin.isEmpty() && m.restroomIntervalsMin.isEmpty(), m.toString())
        assertEquals(4, m.restroomCensoredMin.size)
    }

    // ------------------------------------------------------------ 계획은 한 사람 기준 마감으로 (닫힘 검증 E5a)

    @Test
    fun thePlanDoesNotSayRestNowWhileBothGaugesReadOk() {
        // 회귀: 피트스톱 계획이 첫 구간 상한으로 묶음 마감을 썼다. 어른 넷, 화장실 70분 전: 화장실 게이지 80분(OK), 피로 120분(OK)인데
        // 계획은 PLAN_NOW "지금 쉬어야 할 때예요"였다(묶음 마감 63분이 이미 지났다). 어른 8명은 50분, 둘은 90분에 같은 일이 생겼다
        val e = engine(adults(4), sinceMin = 70)
        val cands = (1..22).map { stop("s$it", it * 17.5 * km, name = "휴게소$it") }
        val route = RouteState(guiding = true, remainDistanceM = 400 * km, remainTimeS = 400 * km / v100)
        val input = CoachInput(now = t0, gear = Gear.PARK, speedKph = 0f, route = route, candidates = cands)
        val out = e.update(input)
        assertEquals(Level.OK, out.restroom.level)
        assertEquals(Level.OK, out.fatigue.level)
        val plan = e.plan(input)
        assertTrue(plan.notes.none { it.code == "PLAN_NOW" || it.code == "PLAN_GAP" }, "게이지 ${out.restroom.minutes}분·${out.fatigue.minutes}분인데 계획 메모 ${plan.notes.map { it.code }}")
        // 첫 정차는 한 사람 기준 마감(112 − 70 = 42분) 안의 가장 먼 휴게소다
        val firstMin = plan.stops.first().arriveInS / 60.0
        assertTrue(firstMin in 30.0..42.2, "첫 정차 ${firstMin}분 뒤")
    }

    @Test
    fun aFullCarPlansAsManyStopsAsOnePersonWould() {
        // 화장실이 자주 있는 길(17.5km마다)의 400km: 어른 한 명이든 여덟이든 정차 2번(구간 상한 112분). 묶음 마감(8명 48분)으로 세우면 5번이었다
        val cands = (1..22).map { stop("s$it", it * 17.5 * km, name = "휴게소$it") }
        val route = RouteState(guiding = true, remainDistanceM = 400 * km, remainTimeS = 400 * km / v100)
        fun stops(n: Int): List<String> {
            val e = engine(adults(n), sinceMin = 0)
            return e.plan(CoachInput(now = t0, gear = Gear.PARK, speedKph = 0f, route = route, candidates = cands)).stops.map { it.stop.id }
        }
        assertEquals(2, stops(1).size)
        assertEquals(stops(1), stops(8))
        assertEquals(stops(1), stops(2))
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
