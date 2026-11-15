package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoachEngineTest {
    private val t0 = kst(2026, 11, 13, 10, 0)

    private fun engine(vararg occ: Occupant, start: Long = t0, last: Map<String, Long> = emptyMap(), wake: Long? = null, mem: PersonalMemory = PersonalMemory()) =
        CoachEngine(profile = TripProfile(occ.toList(), tripStart = start, lastRestroomAt = last, driverWakeAt = wake), memory = mem, random = Random(7))

    /** 10초마다 update하며 kind 질문이 나올 때까지 운전 (최대 maxMin분). 나온 시각과 질문 */
    private fun CoachEngine.driveUntilPrompt(from: Long, kind: PromptKind, maxMin: Int, route: RouteState? = null): Pair<Long, Prompt?> {
        var t = from
        while (t < from + maxMin * MIN) {
            t += 10 * SEC
            val p = at(t, speed = 100f, route = route).prompt
            if (p?.kind == kind) return t to p
        }
        return t to null
    }

    /** 10초마다 update하며 minutes분 운전 (표본 없이) */
    private fun CoachEngine.driveFor(from: Long, minutes: Int, route: RouteState? = null, cabin: CabinState = CabinState()): Pair<Long, CoachOutput> {
        var t = from
        var out: CoachOutput? = null
        repeat(minutes * 6) { t += 10 * SEC; out = at(t, speed = 100f, route = route, cabin = cabin) }
        return t to out!!
    }

    @Test
    fun clockGoingBackwardsNeverRefillsGauges() {
        val e = engine(adult())
        val a = e.at(t0 + 30 * MIN)
        val b = e.at(t0 + 31 * MIN)
        val c = e.at(t0 + 31 * MIN - 3_600_000L) // 시계가 한 시간 뒤로
        val d = e.at(t0 + 32 * MIN - 3_600_000L)
        assertEquals(listOf(120, 119, 119, 118), listOf(a, b, c, d).map { it.restroom.minutes })
        val fat = listOf(a, b, c, d).map { it.fatigue.minutes!! }
        assertTrue(fat.zipWithNext().all { (x, y) -> y <= x }, "피로 남은 시간이 거꾸로 늘지 않는다: $fat")
    }

    @Test
    fun nullSignalsAndNoCandidatesAreHandled() {
        val e = engine(adult())
        val out = e.update(CoachInput(now = t0, gear = Gear.UNKNOWN))
        assertFalse(out.parked)
        assertNull(out.prompt)
        assertNull(out.next)
        assertNull(out.urgent)
        assertFalse(out.style.baselineReady)
        val out2 = e.update(CoachInput(now = t0 + SEC, gear = Gear.UNKNOWN, samples = listOf(DrivingSample(t0 + SEC))))
        assertNotNull(out2.restroom.minutes)
        val none = engine().update(CoachInput(now = t0, gear = Gear.DRIVE))
        assertNull(none.restroom.minutes, "탑승자가 없으면 화장실 게이지 없음")
        assertNotNull(none.fatigue.minutes)
    }

    @Test
    fun veryLongTripStaysBoundedAndSaturates() {
        val e = engine(adult(), child("kid", 8))
        var t = t0
        var out: CoachOutput? = null
        var n = 0
        while (t < t0 + 14 * 3_600_000L) {
            t += 10 * SEC
            out = e.update(
                CoachInput(
                    now = t, gear = Gear.DRIVE, speedKph = 100f,
                    samples = listOf(DrivingSample(t, 100f, 0.5f, false, TurnSignal.NONE, false)),
                ),
            )
            n++
        }
        assertTrue(n > 5_000)
        assertEquals(Level.NOW, out!!.fatigue.level)
        assertEquals(Level.NOW, out.restroom.level)
        assertTrue(e.memory().restroomIntervalsMin.values.all { it.size <= 20 })
    }

    @Test
    fun parkedFlagAndNewTripReset() {
        val e = engine(adult())
        val (t, out) = e.driveFor(t0, 50)
        assertEquals(70, out.fatigue.minutes)
        assertTrue(e.at(t + SEC, gear = Gear.PARK, speed = 0f).parked)
        // 같은 여정이면 이어 가고, 새 여정이면 새로 시작 (기억은 유지)
        e.setProfile(TripProfile(listOf(adult(), child()), tripStart = t0))
        assertEquals(70, e.at(t + 2 * SEC, speed = 0f, gear = Gear.PARK).fatigue.minutes)
        e.setProfile(TripProfile(listOf(adult()), tripStart = t + 3 * SEC))
        assertEquals(120, e.at(t + 4 * SEC, gear = Gear.PARK, speed = 0f).fatigue.minutes)
    }

    @Test
    fun howLongAnswersImmediately() {
        val e = engine(adult(), child("kid", 8, label = "첫째"))
        val cands = listOf(stop("a", 15_000.0, name = "안성휴게소"))
        val out = e.at(t0 + 30 * MIN, candidates = cands, intent = VoiceIntent.HowLong, route = RouteState(true, nextManeuverDistanceM = 100.0))
        assertEquals(PromptKind.INFO, out.prompt?.kind, "회전 직전이어도 물어본 말에는 답한다")
        val s = out.prompt!!.speech
        assertTrue(s.contains("첫째 기준 화장실 여유는 약 1시간 15분이에요") && s.contains("15km 앞 안성휴게소예요"), s)
    }

    @Test
    fun acceptedLastChanceBecomesNextAndCountsAsSuggestedStop() {
        val e = engine(adult(), child("kid", 5), last = mapOf("kid" to t0 - 30 * MIN, "me" to t0 - 40 * MIN))
        val cands = listOf(stop("a", 3_000.0, name = "기흥휴게소"), stop("b", 40_000.0, name = "안성휴게소"))
        val first = e.at(t0, candidates = cands)
        assertEquals(PromptKind.LAST_CHANCE, first.prompt?.kind)
        assertEquals("a", first.prompt?.stop?.id)
        assertEquals("LAST_CHANCE_GAP", first.next?.reasons?.single()?.code)
        val ok = e.at(t0 + SEC, candidates = cands, intent = VoiceIntent.Accept(first.prompt!!.id))
        assertEquals("a", ok.next?.stop?.id)
        assertEquals(listOf("NEXT_ACCEPTED") + first.prompt!!.reasons.map { it.code }, ok.next?.reasons?.map { it.code })
        assertNull(ok.prompt)
        // 그곳에 서서 문을 열고 4분
        var t = t0 + 2 * MIN
        e.at(t, gear = Gear.PARK, speed = 0f, door = true)
        repeat(4) { t += MIN; e.at(t, gear = Gear.PARK, speed = 0f) }
        val mem = e.memory()
        assertEquals(1, mem.restroomSuggestedMin["kid"]?.size, "앱이 권해서 들른 정차 → 0.5회 (ai-science.md 8-1)")
        assertNull(mem.restroomIntervalsMin["kid"])
        assertEquals(1, mem.restroomCensoredMin["me"]?.size)
        assertTrue(mem.promptStats.values.sumOf { it.accepted } == 1)
    }

    @Test
    fun urgentIsLimitedWhileDrivingAndPureWhenCalledDirectly() {
        val e = engine(adult())
        val cands = (1..6).map { stop("g$it", it * 500.0, kind = StopKind.GAS_STATION) }
        val input = CoachInput(now = t0, gear = Gear.DRIVE, speedKph = 30f, candidates = cands)
        assertEquals(5, e.urgent(input).size)
        assertEquals(150, e.at(t0, speed = 30f).restroom.minutes, "urgent()는 음성 요청을 기록하지 않는다")
        val driving = e.update(input.copy(now = t0 + SEC, intent = VoiceIntent.UrgentRestroom))
        assertEquals(2, driving.urgent?.size)
        assertEquals(PromptKind.URGENT_RESULT, driving.prompt?.kind)
        assertEquals(Level.NOW, driving.restroom.level)
        val parked = engine(adult()).update(input.copy(gear = Gear.PARK, speedKph = 0f, intent = VoiceIntent.UrgentRestroom))
        assertEquals(5, parked.urgent?.size)
        val nothing = engine(adult()).update(CoachInput(now = t0, gear = Gear.DRIVE, intent = VoiceIntent.UrgentRestroom))
        assertEquals(PromptKind.INFO, nothing.prompt?.kind)
        assertEquals(emptyList(), nothing.urgent)
    }

    @Test
    fun sleepyVoiceGivesStrongRestAdvice() {
        val e = engine(adult("me"), adult("partner"))
        val cands = listOf(stop("s", 6_000.0, kind = StopKind.DROWSY_SHELTER, name = "망향졸음쉼터"))
        val out = e.at(t0 + 20 * MIN, candidates = cands, intent = VoiceIntent.Sleepy)
        assertEquals(Level.NOW, out.fatigue.level)
        assertEquals(PromptKind.REST_SUGGESTION, out.prompt?.kind)
        val s = out.prompt!!.speech
        assertTrue(s.contains("15분 이내") && s.contains("운전을 바꾸") && s.contains("망향졸음쉼터"), s)
        assertTrue(out.fatigue.reasons.any { it.code == "FATIGUE_STRONG" })
        assertEquals("s", out.next?.stop?.id)
    }

    @Test
    fun checkInAtSoonAndAcceptMeansSleepy() {
        val e = engine(adult())
        val (t, ask) = e.driveUntilPrompt(t0, PromptKind.CHECK_IN, 110)
        assertNotNull(ask, "SOON이면 상태 확인을 묻는다")
        assertEquals(Level.SOON, e.at(t + SEC, speed = 100f).fatigue.level)
        assertEquals("1시간 40분째 운전 중이에요. 혹시 졸리세요?", ask.speech)
        val reply = e.at(t + 5 * SEC, speed = 100f, intent = VoiceIntent.Accept(ask.id))
        assertEquals(Level.NOW, reply.fatigue.level)
        assertEquals(PromptKind.REST_SUGGESTION, reply.prompt?.kind)
        assertTrue(reply.prompt!!.speech.contains("커피"))
    }

    @Test
    fun freshAirIsComfortNotFatigue() {
        val cabin = CabinState(hvacOn = true, recirculation = true, setTempC = 22f)
        val solo = engine(adult())
        val (_, s) = solo.driveFor(t0, 40, cabin = cabin)
        assertNull(s.cabinAction, "혼자이고 피로가 낮으면 권하지 않는다 (탑승 2명 이상, ai-science.md 9-2)")
        val duo = engine(adult(), adult("p"))
        val (t, d) = duo.driveFor(t0, 40, cabin = cabin)
        assertEquals(CabinAction.FRESH_AIR, d.cabinAction)
        assertEquals("CABIN_RECIRC", d.cabinReason?.code)
        assertTrue(d.fatigue.reasons.none { it.code.contains("RECIRC") })
        assertEquals(Level.OK, d.fatigue.level, "내기순환은 피로를 올리지 않는다")
        val auto = duo.update(CoachInput(now = t + 10 * SEC, gear = Gear.DRIVE, speedKph = 100f, cabin = cabin, cabinAutoAllowed = true))
        assertEquals(CabinAction.FRESH_AIR, auto.cabinAction)
        assertTrue(auto.prompt?.kind != PromptKind.FRESH_AIR, "자동 허용이면 묻지 않는다")
    }

    @Test
    fun quietWhenNextManeuverIsSecondsAway() {
        fun firstCheckIn(maneuverM: Double): Prompt? =
            engine(adult()).driveUntilPrompt(t0, PromptKind.CHECK_IN, 110, RouteState(true, nextManeuverDistanceM = maneuverM)).second
        assertNull(firstCheckIn(700.0), "100km/h로 25초 거리")
        assertNotNull(firstCheckIn(2_000.0))
    }

    @Test
    fun voiceInteractionHoldsEnginePromptsThirtySeconds() {
        val e = engine(adult(), child("kid", 5), last = mapOf("kid" to t0 - 30 * MIN))
        val cands = listOf(stop("a", 3_000.0), stop("b", 40_000.0))
        val info = e.at(t0, candidates = cands, intent = VoiceIntent.HowLong)
        assertEquals(PromptKind.INFO, info.prompt?.kind, "사용자 질문에 먼저 답한다")
        val within = listOf(10, 20, 29).map { e.at(t0 + it * SEC, candidates = cands) }
        assertTrue(within.none { it.prompt?.kind == PromptKind.LAST_CHANCE }, "음성 대화 뒤 30초는 먼저 말하지 않는다")
        val later = e.at(t0 + 31 * SEC, candidates = cands)
        assertEquals(PromptKind.LAST_CHANCE, later.prompt?.kind, "30초 뒤 마지막 기회가 정보 카드를 밀어낸다")
    }

    @Test
    fun defaultWakeTimeFeedsTheSleepyCurve() {
        // 00:10 출발, 기상 시각 모름 → 전날 07:00 기상으로 본다 (17시간) → 졸림 곡선이 가깝다
        val late = kst(2026, 11, 14, 0, 10)
        val night = engine(adult(), start = late).at(late)
        assertEquals(Level.WATCH, night.fatigue.level)
        assertTrue(night.fatigue.reasons.any { it.code == "FATIGUE_BODY_CLOCK" }, night.fatigue.reasons.toString())
        // 같은 시각이어도 23시에 일어났다고 알려 주면 졸림 곡선은 멀다
        val rested = engine(adult(), start = late, wake = late - 70 * MIN).at(late)
        assertEquals(Level.OK, rested.fatigue.level)
        // 05:00 출발은 04:00 기상으로 본다
        val early = kst(2026, 11, 14, 5, 0)
        assertEquals(Level.OK, engine(adult(), start = early).at(early).fatigue.level)
    }

    @Test
    fun lateNightPromptsSpeakAboutTheTimeNotDrivingMinutes() {
        val late = kst(2026, 11, 14, 1, 30) // 기상 시각 모름 → 전날 07:00, 18.5시간
        val e = engine(adult(), start = late)
        val cands = listOf(stop("s", 8_000.0, kind = StopKind.DROWSY_SHELTER, name = "졸음쉼터"))
        val out = e.at(late, speed = 90f, candidates = cands)
        assertEquals(Level.NOW, out.fatigue.level)
        assertEquals(PromptKind.REST_SUGGESTION, out.prompt?.kind)
        assertEquals("지금은 졸음이 오기 쉬운 시간이에요. 8km 앞 졸음쉼터에서 쉬어 갈까요?", out.prompt?.speech)
    }

    @Test
    fun noStopQuestionsWhileParked() {
        // 휴게소에 서 있는 동안(기어 P)에는 엔진이 먼저 "앞쪽 어디에 들를까요?"를 묻지 않는다 (몬테카를로에서 발견).
        // 게이지가 NOW여도 그렇다: 아이 게이지 5분으로 서 있다
        val cands = listOf(stop("next", 5_000.0, name = "다음휴게소"))
        val e = engine(adult(), child("kid", 5), last = mapOf("kid" to t0 - 55 * MIN))
        val parked = e.at(t0, gear = Gear.PARK, speed = 0f, candidates = cands)
        assertEquals(Level.NOW, parked.restroom.level)
        assertNull(parked.prompt, "이미 서 있다")
        assertNull(e.at(t0 + 20 * SEC, gear = Gear.PARK, speed = 0f, candidates = cands).prompt)
        val driving = e.at(t0 + 31 * SEC, speed = 80f, candidates = cands)
        assertEquals(PromptKind.LAST_CHANCE, driving.prompt?.kind, "출발하면 묻는다")
        // 탑승자가 말한 것에는 서 있어도 답한다 (AuditVoiceTest.aPassengersRestroomNeedIsAnsweredWhileParked)
        val asked = engine(adult(), child("kid", 5)).at(t0, gear = Gear.PARK, speed = 0f, candidates = cands, intent = VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM))
        assertEquals("next", asked.prompt?.stop?.id)

        val late = kst(2026, 11, 14, 1, 30) // 졸림 곡선 NOW
        val night = engine(adult(), start = late)
        val shelter = listOf(stop("s", 8_000.0, kind = StopKind.DROWSY_SHELTER, name = "졸음쉼터"))
        val p1 = night.at(late, gear = Gear.PARK, speed = 0f, candidates = shelter)
        assertEquals(Level.NOW, p1.fatigue.level)
        assertNull(p1.prompt, "쉬는 중에는 휴식 제안·상태 확인을 하지 않는다")
        val d1 = night.at(late + 2 * MIN, speed = 90f, candidates = shelter)
        assertEquals(PromptKind.REST_SUGGESTION, d1.prompt?.kind)
    }

    @Test
    fun planIsPureAndHandlesNoCandidates() {
        val e = engine(adult())
        val input = CoachInput(now = t0, gear = Gear.DRIVE, speedKph = 100f)
        val p = e.plan(input)
        assertTrue(p.stops.isEmpty())
        assertEquals("PLAN_NO_CANDIDATES", p.notes.single().code)
        assertEquals(p, e.plan(input))
        assertEquals(150, e.at(t0).restroom.minutes)
    }

    @Test
    fun onPromptResultWorksBetweenUpdates() {
        val e = engine(adult(), child("kid", 5), last = mapOf("kid" to t0 - 30 * MIN))
        val cands = listOf(stop("a", 3_000.0), stop("b", 40_000.0))
        val q = e.at(t0, candidates = cands).prompt!!
        e.onPromptResult(q.id, accepted = false, now = t0 + 2 * SEC)
        val out = e.at(t0 + 3 * SEC, candidates = cands)
        assertNull(out.prompt, "거절한 질문은 내려간다")
        assertEquals(1, e.memory().promptStats.values.sumOf { it.declined })
        e.onPromptResult("unknown", accepted = true, now = t0 + 4 * SEC) // 무시
        CoachEngine(profile = TripProfile(emptyList(), t0)).onPromptResult("x", true, t0) // 시작 전에도 안전
    }
}
