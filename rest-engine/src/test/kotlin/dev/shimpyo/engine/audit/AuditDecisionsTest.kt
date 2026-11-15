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
    private val route = RouteState(guiding = true, congestionBasisKnown = true, remainDistanceM = 200 * KM, remainTimeS = 200 * KM / V100, nextManeuverDistanceM = 50 * KM)

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
        // 일반 질문 두 번 뒤 마지막 기회용 한 번을 남긴다.
        val p = PromptPolicy(cfg, Random(1))
        var t = t0
        for (k in listOf(PromptKind.FRESH_AIR, PromptKind.CHECK_IN)) {
            val q = assertNotNull(p.ask(PromptPolicy.Candidate(k, k.name, level = Level.SOON), t), k.name)
            p.onResult(q.id, true, t + SEC, PersonalMemory())
            t += 2 * MIN
        }
        assertEquals(PromptKind.LAST_CHANCE, p.ask(lc("a", 2_100.0), t)?.kind, "일반 질문 두 번 뒤 예약된 마지막 기회")
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
            guiding = true, congestionBasisKnown = true, remainDistanceM = Double.NaN, remainTimeS = Double.POSITIVE_INFINITY, nextManeuverDistanceM = Double.NaN,
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
    fun manyAdultsAreAskedBeforeARealGapAndToldWhy() {
        // 정한 규칙(2026-11-12 시뮬레이션으로 다시 정함, GroupNeedTest): 판단 마감은 '누군가 필요해질 확률 0.3'(묶음),
        // 게이지는 가장 급한 한 사람. 8명이면 화장실 뒤 48분에 마감이 지나 게이지가 1시간 30분(OK)이어도 16분 공백 앞에서
        // 묻는다: 8명 중 누군가 그 16분 안에 필요해질 확률이 약 0.67이기 때문이다. 감사가 짚은 것은 '게이지가 넉넉한데 왜
        // 묻는지 알 수 없다'였으므로, 묶음 때문에 앞당긴 질문에는 그 이유(LAST_CHANCE_GROUP)를 함께 낸다.
        // (한 사람 기준 마감으로 바꿨을 때는 어른 둘의 명절 위기 비율이 12.0 → 14.1%로 올랐다)
        val short = RouteState(guiding = true, congestionBasisKnown = true, remainDistanceM = 60 * KM, remainTimeS = 60 * KM / V100, nextManeuverDistanceM = 50 * KM)
        val cands = listOf(stop("a", 3.5 * KM, name = "가휴게소"), stop("b", 30 * KM, name = "나휴게소"))
        val people = adults(8)
        val e = CoachEngine(
            profile = TripProfile(people, tripStart = t0 - 60 * MIN, lastRestroomAt = people.associate { it.id to t0 - 60 * MIN }),
            random = Random(7),
        )
        val outs = (0 until 60).map { s -> e.update(CoachInput(now = t0 + s * SEC, gear = Gear.DRIVE, speedKph = 100f, route = short, candidates = cands)) }
        assertEquals(Level.OK, outs.last().restroom.level, "게이지 90분")
        assertTrue(outs.any { it.prompt?.kind == PromptKind.LAST_CHANCE }, "16분 공백 앞에서 묻는다")
        val codes = outs.last().next?.reasons.orEmpty().map { it.code }
        assertEquals(listOf("LAST_CHANCE_GAP", "LAST_CHANCE_GROUP"), codes, "왜 묻는지 말한다")
    }

    @Test
    fun theGaugeAtTheDeadlineShowsHowFarTheGroupBringsItForward() {
        // 마감이 지나는 순간의 게이지: 혼자면 그 사람의 Q(0.5) − Q(0.3)(어른 약 38분), 여럿이면 더 넉넉해 보인다.
        // 이 차이가 LAST_CHANCE_GROUP 설명이 필요한 이유다
        fun gaugeWhenDeadlinePasses(n: Int): Int {
            val clock = EngineClock().apply { advance(t0) }
            val m = RestroomNeedModel(cfg).apply { sync(TripProfile(adults(n), tripStart = t0), clock, PersonalMemory()) }
            val minute = (0..300).first { m.deadlineMin(t0 + it * MIN, cfg.restroomDecisionProbability) == 0.0 }
            return m.gauge(t0 + minute * MIN, CoachTexts()).minutes!!
        }
        assertTrue(gaugeWhenDeadlinePasses(1) in 35..39, "어른 1명")
        assertTrue(gaugeWhenDeadlinePasses(2) in 64..68, "어른 2명 (감사 O6의 66분)")
        assertTrue(gaugeWhenDeadlinePasses(8) in 100..104, "어른 8명 (감사 O6의 102분)")
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
    fun stopAndGoInParkIsNotARestOrARestroomStop() {
        // 회귀(감사 S7 결정의 부작용, 닫힘 검증 2026-11-12): '3분 안에 30km/h를 넘지 않고 다시 P면 같은 정차'는 휴게소 안에서 옮겨 대는
        // 경우를 위한 규칙인데, 꽉 막힌 정체에서 P로 섰다가 기어가기를 되풀이하는 것(P 2분 → 시속 15km로 2분 → P 2분 …)도 한 정차로
        // 이어졌다. 첫 P 뒤 15분이면 '15분 휴식'으로 피로 게이지가 가득 찼고(20 → 118분), 그 사이 문이 한 번 열렸으면 화장실 시계도
        // 0으로 돌아갔다(25 → 80분). 정한 규칙: 그 정차를 시작한 뒤 움직인 거리가 300m를 넘으면 새 정차다
        val occ = listOf(adult("driver"), child("kid", 7))
        val e = CoachEngine(
            profile = TripProfile(occ, tripStart = t0 - 100 * MIN, lastRestroomAt = occ.associate { it.id to t0 - 80 * MIN }, continuousDrivingMin = 100.0),
            random = Random(7),
        )
        var t = t0
        var out = e.update(CoachInput(now = t, gear = Gear.DRIVE, speedKph = 15f))
        val fatigueBefore = assertNotNull(out.fatigue.minutes)
        val restroomBefore = assertNotNull(out.restroom.minutes)
        assertTrue(fatigueBefore <= 20 && restroomBefore <= 25, "전제: 피로 ${fatigueBefore}분, 화장실 ${restroomBefore}분")
        repeat(5) { cycle -> // 20분: P 2분, 기어가기 2분(500m)
            repeat(120) { k -> t += SEC; out = e.update(CoachInput(now = t, gear = Gear.PARK, speedKph = 0f, doorOpened = cycle == 0 && k == 30)) }
            repeat(120) { t += SEC; out = e.update(CoachInput(now = t, gear = Gear.DRIVE, speedKph = 15f)) }
        }
        assertTrue(
            out.fatigue.minutes!! <= fatigueBefore && out.restroom.minutes!! <= restroomBefore,
            "정체 속 가다 서다 20분 뒤: 피로 게이지 ${fatigueBefore}분 → ${out.fatigue.minutes}분 (15분 휴식으로 셌다), " +
                "화장실 게이지 ${restroomBefore}분 → ${out.restroom.minutes}분 (화장실 정차로 셌다)",
        )
        assertTrue(e.memory().restroomIntervalsMin.isEmpty() && e.memory().restroomCensoredMin.isEmpty(), "화장실 정차로 배우지도 않는다")
    }

    /** P [parkS]초 → [gear]·[kph]로 [moveS]초 → P. 1초마다 update. 돌려주는 값: 다시 선 뒤에도 같은 정차인지 (정차 시작 시각이 그대로인지) */
    private fun continuesAfterMoving(st: StopTracker, from: Long, parkS: Int, moveS: Int, kph: Float?, gear: Gear = Gear.DRIVE): Pair<Boolean, Long> {
        var t = from
        repeat(parkS) { st.update(t, Gear.PARK, 0f, false); t += SEC }
        val session = st.sessionStart
        repeat(moveS) { st.update(t, gear, kph, false); t += SEC }
        st.update(t, Gear.PARK, 0f, false)
        return (st.sessionStart == session) to t + SEC
    }

    @Test
    fun reParkingContinuesOnlyWithin300MetresOfTheStop() {
        // 시속 10km로 100초 = 278m: 같은 정차. 시속 12km로 100초 = 333m: 새 정차 (시간·속도 조건은 둘 다 맞는다)
        assertTrue(continuesAfterMoving(StopTracker(cfg), 0L, parkS = 600, moveS = 100, kph = 10f).first)
        assertTrue(!continuesAfterMoving(StopTracker(cfg), 0L, parkS = 600, moveS = 100, kph = 12f).first)
        // 거리는 그 정차를 시작한 뒤의 합이다: 180m씩 두 번 옮기면 두 번째는 새 정차 (정체 속에서 조금씩 기어가는 경우)
        val st = StopTracker(cfg)
        val (first, t1) = continuesAfterMoving(st, 0L, parkS = 300, moveS = 65, kph = 10f)
        assertTrue(first, "180m")
        val (second, t2) = continuesAfterMoving(st, t1, parkS = 300, moveS = 65, kph = 10f)
        assertTrue(!second, "합쳐 360m")
        // 새 정차에서는 거리를 처음부터 다시 센다
        assertTrue(continuesAfterMoving(st, t2, parkS = 300, moveS = 65, kph = 10f).first)
    }

    @Test
    fun withoutASpeedSignalAReParkContinuesOnlyWhenTheDistanceBoundIsSmall() {
        // 차속을 모르면 다시 대기로 볼 수 있는 가장 빠른 속도(30km/h)로 움직였다고 본다: 30초(250m)는 같은 정차, 60초(500m)는 새 정차.
        // 같은 정차라고 확인할 수 없으면 이어 세지 않는다 (게이지를 틀리게 채우는 쪽이 더 나쁘다)
        assertTrue(continuesAfterMoving(StopTracker(cfg), 0L, parkS = 600, moveS = 30, kph = null).first)
        assertTrue(!continuesAfterMoving(StopTracker(cfg), 0L, parkS = 600, moveS = 60, kph = null).first)
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
        val near = RouteState(guiding = true, congestionBasisKnown = true, remainDistanceM = 30 * KM, remainTimeS = 30 * KM / V100, nextManeuverDistanceM = 50 * KM)
        for (purpose in listOf(Purpose.FEEDING, Purpose.PET)) {
            val e = CoachEngine(profile = TripProfile(listOf(adult("driver")), tripStart = t0 - 30 * MIN), random = Random(7))
            e.update(CoachInput(now = t0, gear = Gear.DRIVE, speedKph = 100f, route = near, candidates = cands))
            val p = e.update(CoachInput(now = t0 + SEC, gear = Gear.DRIVE, speedKph = 100f, route = near, candidates = cands, intent = VoiceIntent.OccupantNeed(null, purpose))).prompt
            assertEquals("6km 앞 가휴게소에서 잠깐 쉬어 갈 수 있어요. 들를까요?", p?.speech, purpose.name)
        }
    }

    // ---------------- 게이지 NOW와 마지막 기회: 지금 필요한 것이 먼저다 (닫힘 검증 E2, UI 감사 #6) ----------------

    /**
     * 10km마다 휴게소(s1…s29)가 있는 길을 시속 100km로 [seconds]초 달린다. 아이 게이지 5분(NOW).
     * [jamAtKm]에서 20km·40분 정체가 시작하고 그 안의 휴게소는 없다. 질문에는 답하지 않는다
     */
    private fun nowDrive(jamAtKm: Int?, seconds: Int): List<CoachOutput> {
        val occ = listOf(adult("driver"), child("kid", 7))
        val e = CoachEngine(
            profile = TripProfile(occ, tripStart = t0 - 100 * MIN, lastRestroomAt = occ.associate { it.id to t0 - 100 * MIN }),
            random = Random(7),
        )
        val destM = 300 * KM
        val jam = jamAtKm?.let { CongestionSegment(startDistanceM = it * KM, lengthM = 20 * KM, durationS = 2_400.0) }
        var posM = 0.0
        return (0 until seconds).map { s ->
            val cands = (1..29).filter { jamAtKm == null || it * 10 !in jamAtKm..jamAtKm + 20 }
                .map { it to it * 10 * KM - posM }.filter { it.second > 0 }.map { (i, d) -> stop("s$i", d, name = "${i}번 휴게소") }
            val ahead = jam?.let { listOf(it.copy(startDistanceM = it.startDistanceM - posM)) }.orEmpty().filter { it.startDistanceM + it.lengthM > 0 }
            var jamLeft = 0.0
            var jamTime = 0.0
            for (j in ahead) {
                val left = j.lengthM + minOf(0.0, j.startDistanceM)
                jamLeft += left
                jamTime += j.durationS * left / j.lengthM
            }
            val r = RouteState(
                guiding = true, congestionBasisKnown = true, remainDistanceM = destM - posM, remainTimeS = (destM - posM - jamLeft) / V100 + jamTime,
                congestion = ahead, nextManeuverDistanceM = 100 * KM,
            )
            posM += V100
            e.update(CoachInput(now = t0 + s * SEC, gear = Gear.DRIVE, speedKph = 100f, route = r, candidates = cands))
        }
    }

    @Test
    fun aFarJamDoesNotSilenceTheNowRestroomQuestion() {
        // 회귀: 게이지가 NOW여도 마지막 기회가 하나라도 있으면 '가까운 화장실' 질문을 만들지 않았다. 정체 항목을 묶게 된 뒤(R1) 먼 정체 전
        // 마지막 기회가 흔해졌다: 81km 앞 정체 전 휴게소(8번)가 마지막 기회로 잡히면 그곳이 20km 안에 들어올 때까지(약 36분) 아무것도
        // 묻지 않고 10km마다 있는 휴게소를 지나쳤다. 정한 규칙: NOW는 '가장 가까운 화장실이 아닌' 마지막 기회보다 먼저다
        val free = nowDrive(jamAtKm = null, seconds = 60)
        val jammed = nowDrive(jamAtKm = 81, seconds = 60)
        assertEquals(Level.NOW, jammed.first().restroom.level)
        val control = assertNotNull(free.first().prompt, "대조: 정체가 없으면 첫 틱에 가까운 화장실을 묻는다")
        val asked = assertNotNull(jammed.first().prompt, "81km 앞 정체가 있어도 똑같이 묻는다")
        assertEquals("s1", asked.stop?.id)
        assertEquals(control.speech, asked.speech)
        assertTrue(asked.speech.startsWith("화장실 여유가 거의 없어요"), asked.speech)
        // 추천도 먼 마지막 기회(8번)가 아니라 지금 가장 빨리 갈 수 있는 화장실이다
        assertTrue(jammed.all { it.next?.stop?.id == "s1" && it.next?.reasons?.map { r -> r.code } == listOf("NEXT_NEAREST_RESTROOM") }, jammed.first().next.toString())
    }

    @Test
    fun whenTheNearestRestroomIsTheLastChanceTheLastChanceWordingWins() {
        // 가장 가까운 화장실(1번, 10km)이 정체 전 마지막 화장실이기도 하다: NOW 질문 대신 마지막 기회로 묻고, 이유도 마지막 기회다.
        // 예전에는 문장은 "정체 전 마지막 화장실"인데 추천의 이유가 NEXT_NEAREST_RESTROOM이어서 화면 카드에 '가까운 화장실'이 붙었다
        val outs = nowDrive(jamAtKm = 11, seconds = 60) // 질문은 45초 뒤 내려간다: 그 뒤의 추천도 같다
        assertEquals(Level.NOW, outs.first().restroom.level)
        val q = assertNotNull(outs.first().prompt)
        assertEquals("s1", q.stop?.id)
        assertEquals("앞에 40분 정체가 있어요. 다음 1번 휴게소가 정체 전 마지막 화장실이에요. 들를까요?", q.speech)
        assertTrue(outs.all { it.next?.stop?.id == "s1" && it.next?.reasons?.map { r -> r.code } == listOf("LAST_CHANCE_CONGESTION") }, outs.first().next.toString())
    }

    @Test
    fun theFarLastChanceIsAskedWithItsOwnWordingOnceItIsTheNearestRestroom() {
        // 81km 앞 정체까지 44분: 그동안 지나는 휴게소마다 NOW 질문(가까운 화장실)을 하고, 8번 휴게소가 가장 가까운 화장실이 되면
        // 그때는 "정체 전 마지막 화장실"로 묻는다. 그 질문이 뜬 틱의 추천 이유도 마지막 기회다
        val outs = nowDrive(jamAtKm = 81, seconds = 44 * 60)
        val firstAsk = LinkedHashMap<String, CoachOutput>()
        for (o in outs) o.prompt?.let { p -> firstAsk.putIfAbsent(p.id, o) }
        val byStop = firstAsk.values.groupBy { it.prompt!!.stop?.id }
        assertTrue(byStop.keys.containsAll(listOf("s1", "s2", "s3")), "지나는 휴게소마다 묻는다: ${byStop.keys}")
        assertTrue(byStop.filterKeys { it != "s8" }.values.flatten().all { it.prompt!!.speech.startsWith("화장실 여유가 거의 없어요") })
        val last = assertNotNull(byStop["s8"]?.singleOrNull(), "8번 휴게소는 한 번, 마지막 기회로 묻는다: ${byStop["s8"]?.map { it.prompt!!.speech }}")
        assertEquals("앞에 40분 정체가 있어요. 다음 8번 휴게소가 정체 전 마지막 화장실이에요. 들를까요?", last.prompt!!.speech)
        assertEquals(listOf("LAST_CHANCE_CONGESTION"), last.next?.reasons?.map { it.code })
        assertEquals("s8", last.next?.stop?.id)
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
