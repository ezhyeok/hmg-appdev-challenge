package dev.shimpyo.engine.audit

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachTexts
import dev.shimpyo.engine.EngineClock
import dev.shimpyo.engine.RestroomNeedModel
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.CoachOutput
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.EngineMath.NONE
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.GapAnalyzer
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.InputSanitizer
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.MIN
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.PromptPolicy
import dev.shimpyo.engine.PromptStat
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.SEC
import dev.shimpyo.engine.StopTracker
import dev.shimpyo.engine.TripProfile
import dev.shimpyo.engine.VoiceIntent
import dev.shimpyo.engine.adult
import dev.shimpyo.engine.child
import dev.shimpyo.engine.kst
import dev.shimpyo.engine.stop
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 감사에서 '정하지 못함(RISK)'으로 남긴 것들을 정하고 고정한다. 각 테스트 위 주석이 정한 규칙이다.
 * 함께, 결함을 고치며 새로 생긴 규칙(정체 묶음의 경계, 사용자 응답 뒤 같은 곳 다시 묻지 않기 등)을 고정한다.
 */
class AuditDecisionsTest {
    private val cfg = CoachConfig()
    private val t0 = kst(2026, 11, 11, 10, 0)
    private val route = RouteState(guiding = true, remainDistanceM = 200 * KM, remainTimeS = 200 * KM / V100, nextManeuverDistanceM = 50 * KM)

    // ---------------- P9 시간당 상한: 낮은 질문이 마지막 기회를 막지 못한다 ----------------

    private val distances = HashMap<String, Double>()
    private val distanceOf: (String) -> Double? = { distances[it] }

    private fun PromptPolicy.ask(c: PromptPolicy.Candidate, t: Long) = decide(listOf(c), t, t, false, distanceOf, PersonalMemory())

    private fun lc(id: String, d: Double, level: Level = Level.WATCH): PromptPolicy.Candidate {
        distances[id] = d
        return PromptPolicy.Candidate(PromptKind.LAST_CHANCE, "질문", stop(id, d), d, level)
    }

    @Test
    fun lowPriorityPromptsDoNotStarveALastChance() {
        // 정한 규칙: 시간당 상한(3)은 자기와 같거나 높은 우선순위 질문만 센다. 환기·상태 확인·휴식이 한 시간에 셋 나왔어도
        // WATCH 단계의 마지막 기회는 묻는다 (예전에는 NOW만 상한에서 빠져, 한 시간 동안 막혔다)
        val p = PromptPolicy(cfg, Random(1))
        var t = t0
        for (k in listOf(PromptKind.FRESH_AIR, PromptKind.CHECK_IN, PromptKind.REST_SUGGESTION)) {
            val q = assertNotNull(p.ask(PromptPolicy.Candidate(k, k.name, level = Level.SOON), t), k.name)
            p.onResult(q.id, true, t + SEC, PersonalMemory())
            t += 2 * MIN
        }
        assertEquals(PromptKind.LAST_CHANCE, p.ask(lc("a", 2_100.0), t)?.kind, "낮은 질문 셋 뒤에도 마지막 기회")
    }

    @Test
    fun lastChancesAreStillCappedAmongThemselves() {
        // 같은 우선순위끼리는 그대로 시간당 3번. 네 번째 마지막 기회는 NOW 단계일 때만
        val p = PromptPolicy(cfg, Random(1))
        var t = t0
        for (id in listOf("a", "b", "c")) {
            val q = assertNotNull(p.ask(lc(id, 2_100.0), t), id)
            p.onResult(q.id, true, t + SEC, PersonalMemory())
            distances.remove(id) // 지나쳤다 (그곳의 쉼이 풀린다)
            t += 2 * MIN
        }
        assertNull(p.ask(lc("d", 2_100.0), t), "네 번째 WATCH 마지막 기회는 막힌다")
        assertEquals(PromptKind.LAST_CHANCE, p.ask(lc("d", 2_100.0, Level.NOW), t)?.kind, "NOW는 상한에서 뺀다")
    }

    // ---------------- P11 질문 대상이 목록에서 잠깐 빠질 때 ----------------

    @Test
    fun aShortCandidateDropoutKeepsTheActivePrompt() {
        // 정한 규칙: 마지막으로 본 거리에서 최고 속도(150km/h)로 달려도 아직 500m 밖이면 질문을 유지한다 (내비 목록이 깜빡인다)
        val p = PromptPolicy(cfg, Random(1))
        val q = assertNotNull(p.ask(lc("a", 2_100.0), t0))
        distances.remove("a")
        assertEquals(q.id, p.decide(emptyList(), t0 + SEC, t0 + SEC, false, distanceOf, PersonalMemory())?.id, "1초 빠짐: 유지")
        distances["a"] = 2_050.0
        assertEquals(q.id, p.decide(emptyList(), t0 + 2 * SEC, t0 + 2 * SEC, false, distanceOf, PersonalMemory())?.id, "돌아왔다: 같은 질문")
        distances.remove("a")
        assertNull(p.decide(emptyList(), t0 + 40 * SEC, t0 + 40 * SEC, false, distanceOf, PersonalMemory()), "38초 동안 빠짐: 지나쳤을 수 있다")
    }

    @Test
    fun aCandidateMissingRightBeforeItsExitIsDropped() {
        val p = PromptPolicy(cfg, Random(1))
        assertNotNull(p.ask(lc("a", 2_100.0), t0))
        distances["a"] = 600.0
        assertNotNull(p.decide(emptyList(), t0 + 30 * SEC, t0 + 30 * SEC, false, distanceOf, PersonalMemory()))
        distances.remove("a")
        assertNull(p.decide(emptyList(), t0 + 33 * SEC, t0 + 33 * SEC, false, distanceOf, PersonalMemory()), "600m에서 사라졌으면 지나가는 중")
    }

    // ---------------- P10 사용자 응답 둘이 한꺼번에 ----------------

    @Test
    fun twoUserAnswersDueTogetherAreBothGiven() {
        // 정한 규칙: 사용자 말에 대한 응답은 버리지 않는다. 같은 때 둘이면 하나를 먼저 하고 나머지는 지금 질문이 끝난 뒤에 한다
        val e = CoachEngine(
            profile = TripProfile(listOf(adult("driver")), tripStart = t0 - 105 * MIN, continuousDrivingMin = 105.0),
            random = Random(7),
        )
        val cands = listOf(stop("a", 6 * KM, name = "가휴게소", facilities = Facilities(food = true)), stop("b", 30 * KM, name = "나휴게소", facilities = Facilities(food = true)))
        var t = t0
        fun at(intent: VoiceIntent? = null): CoachOutput {
            t += SEC
            return e.update(CoachInput(now = t, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands, intent = intent))
        }
        var check = at().prompt
        repeat(60) { if (check?.kind != PromptKind.CHECK_IN) check = at().prompt }
        assertEquals(PromptKind.CHECK_IN, check?.kind, "피로 SOON → 상태 확인")
        e.onPromptResult(check!!.id, true, t + 500) // 버튼으로 "응, 졸려" → 휴식 응답이 생긴다
        val rest = assertNotNull(at(VoiceIntent.Hungry).prompt, "같은 때 \"배고파\"")
        assertEquals(PromptKind.REST_SUGGESTION, rest.kind)
        assertTrue(rest.speech.contains("졸릴 땐"), rest.speech)
        assertEquals(rest.id, at().prompt?.id, "식사 응답은 휴식 응답이 끝날 때까지 기다린다")
        val meal = at(VoiceIntent.Decline(rest.id)).prompt
        assertTrue(meal != null && meal.speech.contains("식사"), "휴식 응답에 답하자 식사 응답: $meal")
    }

    // ---------------- R23 NaN·무한대·음수 입력 ----------------

    @Test
    fun badNumbersNeverReachArrivalsOrGauges() {
        // 정한 규칙: 입력 경계(InputSanitizer)에서 NaN·무한대는 '모름', 그런 값이 든 정체 항목은 뺀다
        val bad = RouteState(
            guiding = true, remainDistanceM = Double.NaN, remainTimeS = Double.POSITIVE_INFINITY, nextManeuverDistanceM = Double.NaN,
            candidatesUntilM = Double.POSITIVE_INFINITY,
            congestion = listOf(
                CongestionSegment(startDistanceM = Double.NaN, lengthM = 5 * KM, durationS = 600.0),
                CongestionSegment(startDistanceM = 10 * KM, lengthM = Double.POSITIVE_INFINITY, durationS = 600.0),
                CongestionSegment(startDistanceM = 10 * KM, lengthM = 5 * KM, durationS = Double.NaN),
                jam(20.0, 5.0, 25.0),
            ),
        )
        val cands = listOf(
            stop("nanRoute", Double.NaN, straightM = 10 * KM),
            stop("infRoute", Double.POSITIVE_INFINITY),
            stop("negEta", 8 * KM, etaS = -5.0),
            stop("nanEta", 12 * KM, etaS = Double.NaN),
            stop("ok", 30 * KM, detourS = Double.NaN),
        )
        val e = CoachEngine(
            profile = TripProfile(listOf(adult("driver"), child("kid", 7)), tripStart = t0 - 80 * MIN, lastRestroomAt = mapOf("kid" to t0 - 80 * MIN)),
            random = Random(7),
        )
        val input = CoachInput(now = t0, gear = Gear.DRIVE, speedKph = Float.NaN, route = bad, candidates = cands, rangeKm = Double.NaN)
        val out = e.update(input)
        assertNotNull(out.restroom.minutes)
        assertNotNull(out.fatigue.minutes)
        val shown = listOfNotNull(out.next?.stop, out.prompt?.stop)
        assertTrue(shown.isNotEmpty(), "아이 게이지 23분: 추천이 나와야 한다")
        for (s in shown) assertTrue(s.etaS == null || s.etaS!!.isFinite(), "$s")
        val plan = e.plan(input)
        assertTrue(plan.stops.all { it.arriveInS.isFinite() }, plan.toString())
        assertTrue(e.urgent(input).all { it.totalTimeS.isFinite() })

        val clean = InputSanitizer.clean(input)
        assertEquals(1, clean.route!!.congestion.size, "값이 이상한 정체 항목은 뺀다")
        assertEquals(listOf(null, null, null, null), listOf(clean.speedKph, clean.route!!.remainDistanceM, clean.route!!.remainTimeS, clean.route!!.candidatesUntilM))
        assertNull(clean.candidates.single { it.id == "nanEta" }.etaS)
        assertEquals(0.0, clean.candidates.single { it.id == "ok" }.detourS)
        val good = CoachInput(now = t0, gear = Gear.DRIVE, speedKph = 90f, route = route, candidates = listOf(stop("a", 5 * KM)))
        assertSame(good, InputSanitizer.clean(good), "정상 입력은 그대로 (매초 새로 만들지 않는다)")
        assertEquals(30f, InputSanitizer.clean(good.copy(speedKph = -30f)).speedKph, "후진 차속은 음수로 온다: 크기만")
    }

    // ---------------- O6 탑승 인원이 많을 때 게이지와 판단 마감 ----------------

    private fun adults(n: Int) = (1..n).map { Occupant("p$it", OccupantType.ADULT) }

    @Test
    fun manyAdultsAreNotAskedWhileTheGaugeShowsPlentyOfTime() {
        // 정한 규칙: 판단 마감은 게이지와 같은 사람·같은 곡선(가장 급한 사람의 필요 확률 0.3). 예전 '누군가 필요해질 확률'은
        // 8명이면 화장실 뒤 48분에 마감이 지나 게이지가 1시간 42분(OK)인데 '지나면 16분 동안 화장실이 없어요'를 물었다
        val short = RouteState(guiding = true, remainDistanceM = 60 * KM, remainTimeS = 60 * KM / V100, nextManeuverDistanceM = 50 * KM)
        val cands = listOf(stop("a", 3.5 * KM, name = "가휴게소"), stop("b", 30 * KM, name = "나휴게소"))
        val people = adults(8)
        val e = CoachEngine(
            profile = TripProfile(people, tripStart = t0 - 60 * MIN, lastRestroomAt = people.associate { it.id to t0 - 60 * MIN }),
            random = Random(7),
        )
        val outs = (0 until 60).map { s -> e.update(CoachInput(now = t0 + s * SEC, gear = Gear.DRIVE, speedKph = 100f, route = short, candidates = cands)) }
        assertEquals(Level.OK, outs.last().restroom.level, "게이지 90분")
        assertTrue(outs.none { it.prompt?.kind == PromptKind.LAST_CHANCE }, outs.firstNotNullOfOrNull { it.prompt }.toString())
        assertTrue(outs.none { it.next?.reasons.orEmpty().any { r -> r.code.startsWith("LAST_CHANCE") } })
    }

    @Test
    fun theDeadlineTrailsTheGaugeByTheSameMarginForAnyGroupSize() {
        // 마감이 지나는 순간 게이지는 그 사람의 Q(0.5) − Q(0.3)(어른 약 38분)를 보인다. 1명이든 8명이든 같다
        fun gaugeWhenDeadlinePasses(n: Int): Int {
            val clock = EngineClock().apply { advance(t0) }
            val m = RestroomNeedModel(cfg).apply { sync(TripProfile(adults(n), tripStart = t0), clock, PersonalMemory()) }
            val minute = (0..300).first { m.deadlineMin(t0 + it * MIN, cfg.restroomDecisionProbability) == 0.0 }
            return m.gauge(t0 + minute * MIN, CoachTexts()).minutes!!
        }
        val one = gaugeWhenDeadlinePasses(1)
        assertTrue(one in 35..39, "어른 1명: $one")
        assertEquals(one, gaugeWhenDeadlinePasses(2))
        assertEquals(one, gaugeWhenDeadlinePasses(8))
    }

    // ---------------- S7·S8 정차 판정 ----------------

    @Test
    fun reParkingInsideTheRestAreaContinuesTheSameStop() {
        // 정한 규칙: P에서 나와 3분 안에 30km/h를 넘지 않고 다시 P면 같은 정차 (화장실 앞 → 식당 앞으로 옮겨 대기)
        val st = StopTracker(cfg)
        st.update(0, Gear.DRIVE, 80f, false)
        st.update(SEC, Gear.PARK, 0f, true)
        var rest = NONE
        var t = SEC
        fun step(gear: Gear, kph: Float) {
            val ev = st.update(t, gear, kph, false)
            if (ev.restAt != NONE) rest = ev.restAt
            t += 10 * SEC
        }
        while (t < SEC + 14 * MIN) step(Gear.PARK, 0f)
        repeat(6) { step(Gear.REVERSE, 5f) } // 1분 동안 옮겨 댄다
        while (t < SEC + 21 * MIN) step(Gear.PARK, 0f)
        assertEquals(SEC + 15 * MIN, rest, "14분 + 이동 1분 + 5분은 한 번의 15분 휴식")
    }

    @Test
    fun leavingTheRestAreaStartsANewStop() {
        val st = StopTracker(cfg)
        st.update(0, Gear.PARK, 0f, false)
        st.update(10 * MIN, Gear.DRIVE, 60f, false) // 본선 합류 속도
        st.update(11 * MIN, Gear.PARK, 0f, false)
        val ev = st.update(16 * MIN, Gear.PARK, 0f, false)
        assertEquals(NONE, ev.restAt, "다시 선 곳은 새 정차 (5분째)")
        assertEquals(5 * MIN, st.parkedForMs(16 * MIN))
    }

    @Test
    fun aSpeedGlitchWhileInParkDoesNotBreakTheRest() {
        // 정한 규칙: 기어 P는 차속보다 믿는다 (P에서는 움직일 수 없다. 늦은 기어 몇 초는 판정에 영향이 없고 잡음 한 번이 15분을 깨면 안 된다)
        val st = StopTracker(cfg)
        st.update(0, Gear.PARK, 0f, false)
        st.update(7 * MIN, Gear.PARK, 40f, false)
        assertTrue(st.parked)
        assertEquals(15 * MIN, st.update(15 * MIN, Gear.PARK, 0f, false).restAt)
    }

    // ---------------- 사용자 요청에 답한 정차를 거절하면 ----------------

    @Test
    fun aDeclinedAnswerToARestroomRequestIsNotReAskedForTheSamePlace() {
        // 정한 규칙: "아이가 화장실 가고 싶대"에 답한 곳을 거절하면, 엔진이 30초 뒤 같은 곳을 다시 묻지 않는다 (그곳을 지나면 풀린다)
        val cands = listOf(stop("a", 6 * KM, name = "가휴게소"), stop("b", 40 * KM, name = "나휴게소"))
        val e = CoachEngine(profile = TripProfile(listOf(adult("driver"), child("kid", 7)), tripStart = t0 - 30 * MIN), random = Random(7))
        e.update(CoachInput(now = t0, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands))
        val answer = assertNotNull(
            e.update(CoachInput(now = t0 + SEC, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands, intent = VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM))).prompt,
        )
        assertEquals("a", answer.stop?.id, "가장 가까운 화장실 (a 뒤 20분 공백이라 마지막 기회 문구로 답한다)")
        e.update(CoachInput(now = t0 + 2 * SEC, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands, intent = VoiceIntent.Decline(answer.id)))
        val later = (3..90).map { s -> e.update(CoachInput(now = t0 + s * SEC, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands)) }
        assertTrue(later.none { it.prompt?.stop?.id == "a" }, later.firstNotNullOfOrNull { it.prompt }.toString())
    }

    @Test
    fun feedingAndPetRequestsAreAnsweredEvenWithoutAMatchingOccupant() {
        // 정한 규칙(감사 V4): 말한 것에는 바로 답한다. 탑승자 목록에 아기·반려견이 없어도 다음 쉴 곳을 알려 준다
        val cands = listOf(stop("a", 6 * KM, name = "가휴게소"))
        val near = RouteState(guiding = true, remainDistanceM = 30 * KM, remainTimeS = 30 * KM / V100, nextManeuverDistanceM = 50 * KM)
        for (purpose in listOf(Purpose.FEEDING, Purpose.PET)) {
            val e = CoachEngine(profile = TripProfile(listOf(adult("driver")), tripStart = t0 - 30 * MIN), random = Random(7))
            e.update(CoachInput(now = t0, gear = Gear.DRIVE, speedKph = 100f, route = near, candidates = cands))
            val p = e.update(CoachInput(now = t0 + SEC, gear = Gear.DRIVE, speedKph = 100f, route = near, candidates = cands, intent = VoiceIntent.OccupantNeed(null, purpose))).prompt
            assertEquals("6km 앞 가휴게소에서 잠깐 쉬어 갈 수 있어요. 들를까요?", p?.speech, purpose.name)
        }
    }

    // ---------------- 정체 묶음의 경계 ----------------

    private val gap = GapAnalyzer(cfg)

    @Test
    fun jamItemsWithAShortFreeStretchJoinButALongOneDoesNot() {
        // 500m 빈 구간은 한 정체 (15 + 15분 → 30분 정체), 2km 빈 구간은 따로 (각 15분 < 민감 기준 20분)
        val joined = routeCtx(jam(10.0, 5.0, 15.0), jam(15.5, 5.0, 15.0))
        val lc = assertNotNull(gap.analyze(opps(joined, stop("a", 5 * KM), stop("b", 40 * KM)), joined, 30.0, true, null))
        assertEquals(GapAnalyzer.Trigger.CONGESTION, lc.trigger)
        approx(30 * 60.0 + 500 / V100, lc.congestionS, 1.0, "두 정체 + 사이 500m")
        val apart = routeCtx(jam(10.0, 5.0, 15.0), jam(17.0, 5.0, 15.0))
        val lc2 = gap.analyze(opps(apart, stop("a", 5 * KM), stop("b", 40 * KM)), apart, 30.0, true, null)
        assertTrue(lc2 == null || lc2.trigger != GapAnalyzer.Trigger.CONGESTION, lc2?.trigger.toString())
    }

    @Test
    fun aStopBetweenJamItemsSplitsThem() {
        // 사이 빈 구간에 휴게소가 있으면 두 정체는 따로다: 두 번째 25분 정체의 '정체 전 마지막'은 그 휴게소
        val ctx = routeCtx(jam(10.0, 5.0, 25.0), jam(15.6, 5.0, 25.0))
        val lc = assertNotNull(gap.analyze(opps(ctx, stop("between", 15.3 * KM), stop("b", 40 * KM)), ctx, 30.0, true, null))
        assertEquals("between", lc.opp.stop.id)
        assertEquals(GapAnalyzer.Trigger.CONGESTION, lc.trigger)
        approx(25 * 60.0, lc.congestionS, 1.0)
    }

    // ---------------- 마감이 지난 뒤의 게이지 규칙: 공백의 끝을 알아야 묻는다 ----------------

    @Test
    fun afterTheDeadlineAGapIsClaimedOnlyUpToAKnownStopOrTheDestination() {
        // 정한 규칙: 마감이 지난 뒤(가장 가까운 기회로 대신 고를 때)는 다음 기회나 목적지까지의 공백만 본다. 자른 목록의 아래 한계는 쓰지 않는다
        // (목록이 빠짐없다는 앱의 약속이 틀리면 지나는 휴게소마다 울린다. DemoScenarioTest 오후 졸음 시연, 천안호두 뒤 '26분 공백')
        val cut = routeCtx(horizonM = 55 * KM)
        assertNull(gap.analyze(opps(cut, stop("cheonan", 15 * KM)), cut, 0.0, false, null), "범위 끝까지의 아래 한계만으로는 묻지 않는다")
        val known = gap.analyze(opps(cut, stop("cheonan", 15 * KM), stop("next", 50 * KM)), cut, 0.0, false, null)
        assertEquals("cheonan", known?.opp?.stop?.id, "다음 기회를 알면 (21분 공백) 묻는다")
        val complete = routeCtx(destM = 60 * KM)
        assertEquals("cheonan", gap.analyze(opps(complete, stop("cheonan", 15 * KM)), complete, 0.0, false, null)?.opp?.stop?.id, "목적지까지 27분")
        // 마감 안에서는 예전처럼 아래 한계도 쓴다 (AuditGapTest.theHorizonLowerBoundStillFlagsARealGap)
        assertEquals("cheonan", gap.analyze(opps(cut, stop("cheonan", 15 * KM)), cut, 10.0, false, null)?.opp?.stop?.id)
    }

    // ---------------- 도착 시간의 단조 규칙 (내비 ETA 쪽) ----------------

    @Test
    fun naviEtasAreTrustedExceptWhenTheyRunBackwards() {
        val ctx = routeCtx()
        val list = opps(ctx, stop("a", 10 * KM, etaS = 1_200.0), stop("b", 15 * KM, etaS = 600.0), stop("c", 20 * KM, etaS = 1_500.0))
        assertEquals(listOf(1_200.0, 1_200.0, 1_500.0), list.map { it.arriveS }, "뒤 내비 ETA가 앞 내비 ETA보다 이르면 늦게 온 값이다")
        // 엔진 계산(정체 앞 8km = 288초 + …)이 뒤 내비 ETA보다 늦어도 내비 값은 그대로 (내비가 더 빠르다고 보면 엔진이 보수적일 뿐)
        val jammed = routeCtx(jam(5.0, 5.0, 30.0))
        val mixed = opps(jammed, stop("engine", 9 * KM), stop("navi", 12 * KM, etaS = 900.0))
        assertEquals(900.0, mixed.single { it.stop.id == "navi" }.arriveS)
    }

    @Test
    fun theEngineFillsTheSameArrivalOnEveryCardForAStop() {
        // 한 경로로 채운다: 같은 후보면 추천 카드와 질문 카드의 도착 시간이 같다
        val e = CoachEngine(profile = TripProfile(listOf(adult("driver"), child("kid", 7)), tripStart = t0 - 30 * MIN), random = Random(7))
        val cands = listOf(stop("a", 6 * KM, name = "가휴게소"), stop("b", 40 * KM, name = "나휴게소"))
        e.update(CoachInput(now = t0, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands))
        val out = e.update(CoachInput(now = t0 + SEC, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands, intent = VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM)))
        val p = assertNotNull(out.prompt?.stop)
        val n = assertNotNull(out.next?.stop)
        if (p.id == n.id) assertEquals(n.etaS, p.etaS)
        approx(6 * KM / V100, assertNotNull(p.etaS), 1.0)
    }

    // ---------------- 질문 id ----------------

    @Test
    fun promptIdsDifferBetweenEnginesBuiltAtTheSameMoment() {
        val ids = (1..3).map {
            val e = CoachEngine(profile = TripProfile(listOf(adult("driver")), tripStart = t0), random = Random(7))
            e.update(CoachInput(now = t0, gear = Gear.DRIVE, speedKph = 90f, intent = VoiceIntent.HowLong)).prompt!!.id
        }
        assertEquals(3, ids.toSet().size, ids.toString())
        assertNotEquals("p1", ids.first())
    }

    // ---------------- 시간당 상한과 사용자 응답 ----------------

    @Test
    fun userAnswersDoNotCountTowardTheHourlyCap() {
        val p = PromptPolicy(cfg, Random(1))
        repeat(5) { i ->
            val t = t0 + i * MIN
            p.decide(listOf(PromptPolicy.Candidate(PromptKind.INFO, "정보", userInitiated = true)), t, t, false, distanceOf, PersonalMemory())
        }
        val t = t0 + 10 * MIN
        val q = p.decide(listOf(PromptPolicy.Candidate(PromptKind.CHECK_IN, "졸리세요?", level = Level.SOON)), t, t, false, distanceOf, PersonalMemory(promptStats = mapOf("x" to PromptStat())))
        assertEquals(PromptKind.CHECK_IN, q?.kind)
    }
}
