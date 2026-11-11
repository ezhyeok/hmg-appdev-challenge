package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** docs/product/SPEC.md 7장 시연 시나리오를 엔진 수준에서 재현한다 */
class ScenarioTest {
    private val km = 1_000.0

    // ------------------------------------------------------------------
    // 1. 귀성길 마지막 화장실: 아이 동승, 안성 뒤 40분 정체
    // ------------------------------------------------------------------

    private fun holidaySim(): HighwaySim {
        val start = kst(2026, 9, 24, 9, 0)
        val engine = CoachEngine(
            profile = TripProfile(listOf(adult(), child("kid", 5, label = "첫째")), tripStart = start),
            random = Random(7),
        )
        val stops = listOf(
            RouteStop(30 * km, stop("giheung", null, name = "기흥휴게소", facilities = Facilities(food = true))),
            RouteStop(60 * km, stop("anseong", null, name = "안성휴게소", facilities = Facilities(food = true))),
            RouteStop(85 * km, stop("cheonan", null, name = "천안삼거리휴게소")),
            RouteStop(105 * km, stop("manghyang", null, name = "망향휴게소")),
        )
        return HighwaySim(engine, start, stops = stops, jams = listOf(Jam(65 * km, 15 * km, 40 * 60.0)))
    }

    @Test
    fun holidayLastChanceIsAskedInsideTheWindowNotBefore() {
        val sim = holidaySim()
        val early = sim.driveUntilPos(40 * km)
        assertEquals("anseong", early.next?.stop?.id, "멀리서부터 추천 카드는 안성")
        assertEquals("LAST_CHANCE_CONGESTION", early.next?.reasons?.single()?.code)
        assertTrue(sim.prompts.none { it.third.kind == PromptKind.LAST_CHANCE }, "창(2~5km) 밖에서는 묻지 않는다")

        var asked: Prompt? = null
        var askedAt = 0.0
        while (asked == null && sim.posM < 60 * km) {
            val out = sim.step()
            if (out.prompt?.kind == PromptKind.LAST_CHANCE) { asked = out.prompt; askedAt = sim.posM }
        }
        assertNotNull(asked)
        assertEquals("anseong", asked.stop?.id)
        val distance = 60 * km - askedAt
        assertTrue(distance in 2_000.0..5_000.0, "진입로까지 $distance m")
        assertEquals("앞에 40분 정체가 있어요. 다음 안성휴게소가 정체 전 마지막 화장실이에요. 들를까요?", asked.speech)

        // 거절 → 쉼: 안성을 지나 정체에 들어갈 때까지 같은 질문 없음
        val declinedAt = sim.now
        sim.step(intent = VoiceIntent.Decline(asked.id))
        sim.driveUntilWall(declinedAt + 10 * MIN)
        val after = sim.prompts.filter { it.second > declinedAt && it.third.kind == PromptKind.LAST_CHANCE }
        assertTrue(after.isEmpty(), "거절 뒤 10분 안에 마지막 기회 질문 없음: $after")
        assertTrue(sim.posM > 60 * km, "안성을 지났다")
        assertEquals(1, sim.engine.memory().promptStats.values.sumOf { it.declined }, "수락률 학습에 거절 1회")

        // 정체 속에서 아이 게이지가 NOW로 오르면 다시 묻는다 (단계가 오르면 쉼이 풀린다)
        var now: Prompt? = null
        while (now == null && sim.now < declinedAt + 40 * MIN) {
            val out = sim.step()
            if (out.prompt?.kind == PromptKind.LAST_CHANCE) now = out.prompt
        }
        assertNotNull(now)
        assertEquals("cheonan", now.stop?.id)
        assertTrue(now.speech.startsWith("화장실 여유가 거의 없어요"), now.speech)
        assertEquals(Level.NOW, sim.last!!.restroom.level)
        assertEquals("kid", sim.last!!.restroom.drivenBy)
    }

    // ------------------------------------------------------------------
    // 2. 오후 졸음: 오후 2시대 + 연속 주행 + 조향 흔들림
    // ------------------------------------------------------------------

    private fun afternoonSim(seed: Int = 5): HighwaySim {
        val start = kst(2026, 11, 13, 13, 0)
        val engine = CoachEngine(profile = TripProfile(listOf(adult()), tripStart = start), random = Random(seed))
        val stops = (1..40).map { i ->
            RouteStop(i * 15 * km - 7 * km, stop("s$i", null, kind = StopKind.DROWSY_SHELTER, name = "졸음쉼터$i"))
        }
        return HighwaySim(engine, start, routeLengthM = 700 * km, stops = stops).apply {
            cabin = CabinState(hvacOn = true, recirculation = true, setTempC = 22f)
        }
    }

    private val t1410 = kst(2026, 11, 13, 14, 10)

    @Test
    fun afternoonDriftReachesSoonWithVentilationAndRestTarget() {
        val sim = afternoonSim()
        val before = sim.driveUntilWall(t1410)
        assertTrue(before.style.baselineReady, "준비 5분 + 기준선 20분이 끝났다")
        assertEquals(Level.OK, before.fatigue.level)
        assertTrue(before.style.deviation!! < 0.5)

        sim.steering = ::wobbleSteer // Virtual Console 조향 흔들림 재생
        val o = sim.driveUntilWall(kst(2026, 11, 13, 14, 22))
        assertEquals(Level.SOON, o.fatigue.level, o.fatigue.toString())
        assertTrue(o.fatigue.reasons.any { it.code == "FATIGUE_STYLE" })
        assertTrue(o.fatigue.reasons.any { it.code == "FATIGUE_AFTERNOON" })
        assertTrue(o.style.persistent && o.style.deviation!! >= 0.6)
        assertEquals(CabinAction.FRESH_AIR, o.cabinAction)
        assertEquals("CABIN_RECIRC", o.cabinReason?.code)
        assertTrue(o.next!!.purposes.contains(Purpose.REST), "휴식 추천 카드")
        assertEquals(StopKind.DROWSY_SHELTER, o.next!!.stop.kind)
        val checkIns = sim.prompts.filter { it.third.kind == PromptKind.CHECK_IN }
        assertTrue(checkIns.isNotEmpty(), "먼저 상태 확인을 물었다")
        assertTrue(checkIns.first().third.speech.endsWith("혹시 졸리세요?"))

        // 대답이 없고 흔들림이 이어지면 NOW에서 휴식 제안 (2시간 리듬 전에 앞당겨진다)
        sim.driveUntilWall(kst(2026, 11, 13, 14, 40))
        val rest = sim.prompts.firstOrNull { it.third.kind == PromptKind.REST_SUGGESTION }
        assertNotNull(rest, sim.prompts.map { it.third.kind }.toString())
        assertTrue(rest.third.speech.contains("졸음쉼터"), rest.third.speech)
        assertTrue(sim.prompts.count { it.third.kind != PromptKind.REST_SUGGESTION } <= 3, "NOW 전 질문은 시간당 3번 안: " +
            sim.prompts.map { java.time.Instant.ofEpochMilli(it.second).atZone(SEOUL).toLocalTime().toString() + " " + it.third.kind + " " + it.third.speech })
    }

    @Test
    fun afternoonCheckInYesTurnsIntoStrongRestAdvice() {
        val sim = afternoonSim()
        sim.driveUntilWall(t1410)
        sim.steering = ::wobbleSteer
        var ask: Prompt? = null
        while (ask == null && sim.now < t1410 + 15 * MIN) {
            val out = sim.step()
            if (out.prompt?.kind == PromptKind.CHECK_IN) ask = out.prompt
        }
        assertNotNull(ask)
        assertEquals("운전 패턴이 이번 주행 초반과 조금 달라요. 혹시 졸리세요?", ask.speech)
        val reply = sim.step(intent = VoiceIntent.Accept(ask.id))
        assertEquals(Level.NOW, reply.fatigue.level)
        assertEquals(PromptKind.REST_SUGGESTION, reply.prompt?.kind)
        val speech = reply.prompt!!.speech
        assertTrue(speech.contains("커피") && speech.contains("15분 이내") && speech.contains("졸음쉼터"), speech)
        assertEquals(reply.prompt!!.stop?.id, reply.next?.stop?.id)
    }

    @Test
    fun afternoonCheckInNoMutesTheDrivingPatternSignal() {
        val sim = afternoonSim()
        sim.driveUntilWall(t1410)
        sim.steering = ::wobbleSteer
        var ask: Prompt? = null
        while (ask == null && sim.now < t1410 + 15 * MIN) {
            val out = sim.step()
            if (out.prompt?.kind == PromptKind.CHECK_IN) ask = out.prompt
        }
        assertNotNull(ask)
        val reply = sim.step(intent = VoiceIntent.Decline(ask.id))
        assertEquals(Level.OK, reply.fatigue.level, "본인이 괜찮다고 하면 20분 동안 패턴 변화를 피로에 넣지 않는다")
        assertTrue(reply.fatigue.reasons.none { it.code == "FATIGUE_STYLE" })
        val declinedAt = sim.now
        sim.driveUntilWall(declinedAt + 10 * MIN)
        assertTrue(sim.prompts.none { it.second > declinedAt && it.third.kind == PromptKind.CHECK_IN })
    }

    // ------------------------------------------------------------------
    // 3. 도심 급해 모드: 밤 11시, 영아 동승
    // ------------------------------------------------------------------

    @Test
    fun nightUrgentWithInfantRanksParkableOpenDiaperPlaceFirst() {
        val night = kst(2026, 11, 13, 23, 0)
        val engine = CoachEngine(profile = TripProfile(listOf(adult(), infant()), tripStart = night - 30 * MIN), random = Random(1))
        val cands = listOf(
            stop("gas", 1_200.0, StopKind.GAS_STATION, name = "한빛주유소"),
            stop(
                "park", 1_600.0, StopKind.PUBLIC_RESTROOM, name = "중앙공원 공중화장실", openNow = null,
                facilities = Facilities(diaperTable = true, emergencyBell = true, cctv = true, open24h = true),
            ),
            stop("closed", 500.0, StopKind.OTHER, name = "편의점", openNow = false),
            stop("noParking", 400.0, StopKind.PUBLIC_RESTROOM, name = "역 화장실", parkable = false),
            stop("lot", 900.0, StopKind.PUBLIC_PARKING, name = "공영주차장", openNow = null, directionOk = null, facilities = Facilities(diaperTable = true)),
        )
        val input = CoachInput(now = night, gear = Gear.DRIVE, speedKph = 40f, candidates = cands, intent = VoiceIntent.UrgentRestroom)
        val out = engine.update(input)
        assertEquals(listOf("park", "gas"), out.urgent?.map { it.stop.id }, "주행 중에는 2곳만")
        val top = out.urgent!!.first()
        val codes = top.why.map { it.code }
        assertTrue(codes.containsAll(listOf("URGENT_TIME", "URGENT_OPEN_24H", "URGENT_SAFETY", "URGENT_DIAPER")), codes.toString())
        assertEquals(PromptKind.URGENT_RESULT, out.prompt?.kind)
        assertEquals("중앙공원 공중화장실까지 약 6분이에요. 바로 안내할까요?", out.prompt?.speech)
        assertEquals("park", out.next?.stop?.id)
        assertEquals(Level.NOW, out.restroom.level)
        assertEquals("me", out.restroom.drivenBy, "\"급해\"는 말한 어른의 요청으로 본다")
        val all = engine.urgent(input.copy(intent = null))
        assertEquals(listOf("park", "gas", "lot"), all.map { it.stop.id }, "닫힌 곳·주차 불가는 제외")
    }

    // ------------------------------------------------------------------
    // 4. 휴식 기록: P → 문 → 12분 → D
    // ------------------------------------------------------------------

    @Test
    fun parkDoorTwelveMinutesRecoversRestroomGaugeAndRecordsObservation() {
        val start = kst(2026, 11, 14, 10, 0)
        val engine = CoachEngine(profile = TripProfile(listOf(adult(), child("kid", 5)), tripStart = start), random = Random(3))
        val sim = HighwaySim(engine, start)
        val before = sim.driveSeconds(60 * 60)
        assertEquals(0, before.restroom.minutes)
        assertEquals("kid", before.restroom.drivenBy)
        assertEquals(60, before.fatigue.minutes)

        val parked = sim.parkSeconds(12 * 60, doorAtS = 30)
        assertTrue(parked.parked)
        val after = sim.step()
        assertTrue(after.restroom.minutes!! >= 45, "화장실 게이지 회복: ${after.restroom.minutes}")
        assertEquals(Level.OK, after.restroom.level)
        assertTrue(after.fatigue.minutes in 59..60, "12분은 휴식(15분)이 아니다: ${after.fatigue.minutes}")
        val mem = engine.memory()
        assertEquals(listOf(63.0), mem.restroomIntervalsMin["kid"], "출발 63분 뒤 화장실 정차 관측")
        assertEquals(listOf(63.0), mem.restroomCensoredMin["me"])

        sim.parkSeconds(16 * 60)
        val rested = sim.step()
        assertTrue(rested.fatigue.minutes!! >= 119, "15분 이상 쉬면 휴식 리듬이 처음부터: ${rested.fatigue.minutes}")
    }

    // ------------------------------------------------------------------
    // 5. 개인화: 짧은 간격이 반복되면 게이지가 사전값보다 짧아진다
    // ------------------------------------------------------------------

    @Test
    fun repeatedShortIntervalsShrinkTheGaugeAcrossTrips() {
        val t = kst(2026, 11, 10, 9, 0)
        val prior = CoachEngine(profile = TripProfile(listOf(adult()), tripStart = t)).update(CoachInput(now = t, gear = Gear.PARK))
        val learned = CoachEngine(
            profile = TripProfile(listOf(adult()), tripStart = t),
            memory = PersonalMemory(restroomIntervalsMin = mapOf("me" to List(5) { 60.0 })),
        ).update(CoachInput(now = t, gear = Gear.PARK))
        assertEquals(150, prior.restroom.minutes)
        assertEquals(103, learned.restroom.minutes)
        assertTrue(learned.restroom.reasons.any { it.code == "RESTROOM_PERSONAL" })

        var mem = PersonalMemory()
        val gaugesAtStart = ArrayList<Int>()
        repeat(4) { k ->
            val start = kst(2026, 11, 10 + k, 9, 0)
            val engine = CoachEngine(profile = TripProfile(listOf(adult()), tripStart = start), memory = mem, random = Random(k))
            gaugesAtStart += engine.update(CoachInput(now = start, gear = Gear.PARK)).restroom.minutes!!
            val sim = HighwaySim(engine, start, seed = k)
            sim.driveSeconds(60 * 60)
            sim.parkSeconds(5 * 60, doorAtS = 10)
            mem = engine.memory()
        }
        assertEquals(150, gaugesAtStart[0])
        assertTrue(gaugesAtStart.zipWithNext().all { (a, b) -> b < a }, "회차마다 줄어든다: $gaugesAtStart")
        assertEquals(4, mem.restroomIntervalsMin.getValue("me").size, "회차마다 정차 관측 1회")
    }

    // ------------------------------------------------------------------
    // 6. 피트스톱 플래너: 점심시간에 화장실 + 식사를 한 정차로
    // ------------------------------------------------------------------

    @Test
    fun pitStopPlanMergesRestroomAndMealAtLunch() {
        val now = kst(2026, 11, 15, 11, 20)
        val engine = CoachEngine(
            profile = TripProfile(listOf(adult(), child("kid", 8)), tripStart = now, lastRestroomAt = mapOf("kid" to now - 30 * MIN)),
        )
        val v = 100 / 3.6
        fun at(min: Double) = min * 60 * v
        val cands = listOf(
            stop("R1", at(20.0), name = "기흥휴게소"),
            stop("R2", at(42.0), name = "안성휴게소", facilities = Facilities(food = true)),
            stop("R3", at(100.0), name = "천안삼거리휴게소"),
            stop("R4", at(140.0), name = "옥산휴게소", facilities = Facilities(food = true)),
        )
        val input = CoachInput(
            now = now, gear = Gear.DRIVE, speedKph = 100f, candidates = cands,
            route = RouteState(guiding = true, remainDistanceM = at(180.0), remainTimeS = 180 * 60.0),
        )
        val plan = engine.plan(input)
        assertEquals(listOf("R2", "R3", "R4"), plan.stops.map { it.stop.id })
        val lunch = plan.stops.first()
        assertTrue(lunch.purposes.containsAll(setOf(Purpose.RESTROOM, Purpose.MEAL)), lunch.purposes.toString())
        assertEquals(35, lunch.dwellMin)
        assertEquals(42.0 * 60, lunch.arriveInS, 1.0)
        assertTrue(plan.notes.any { it.code == "PLAN_MEAL" && it.text.contains("안성휴게소") })
        assertTrue(plan.notes.none { it.code == "PLAN_GAP" })
        assertEquals(plan, engine.plan(input), "결정적이고 상태를 바꾸지 않는다")
    }
}
