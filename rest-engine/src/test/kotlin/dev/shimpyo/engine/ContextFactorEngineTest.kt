package dev.shimpyo.engine

import dev.shimpyo.engine.EngineMath.NONE
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 문맥 보정이 엔진에서 걸리는 길 (docs/research/need-covariates.md 8장, SPEC 4.1 '문맥 보정').
 *
 * - 식사 정차 = 기어 P 20분 이상 + (300m 안 가장 가까운 후보에 음식이 있다 / 수락한 정차의 목적에 식사가 있다 / "배고파"가 살아 있다)
 *   → 그 정차에서 시계가 돌아간 구간에 0.8 (20시 이후에 시작한 정차는 0.9)
 * - 잠든 아이 = CoachInput.asleepOccupantIds의 CHILD, 20~07시 → 화면 게이지에만 1.5
 *
 * 수식은 ContextFactorTest가 본다. 여기서는 정차·입력·질문·계획이 그 수식에 맞게 이어지는지 본다.
 */
class ContextFactorEngineTest {
    private val noon = kst(2026, 9, 24, 12, 0)
    private val km = 1_000.0

    /** 학습 없이 시계만 보는 설정: 긴 첫 구간이 관측으로 남아 문턱(150분)을 바꾸지 않게 한다 */
    private val noLearning = CoachConfig(restroomMinObservedMin = 10_000.0)
    private val factorsOff = CoachConfig(mealIntervalFactor = 1.0, mealIntervalFactorEvening = 1.0, asleepIntervalFactor = 1.0)

    /** 서 있는 곳의 후보 (앱이 위치 기준 직선거리로 넣은 경우) */
    private val foodHere = stop("anseong", null, name = "안성휴게소", facilities = Facilities(food = true), straightM = 80.0)
    private val shelterHere = stop("ipjang", null, kind = StopKind.DROWSY_SHELTER, name = "입장졸음쉼터", straightM = 60.0)
    private val foodAhead = stop("cheonan", null, name = "천안휴게소", facilities = Facilities(food = true))

    private fun engine(vararg people: Occupant, start: Long = noon, config: CoachConfig = CoachConfig()) =
        CoachEngine(config, TripProfile(people.toList(), tripStart = start), random = Random(7))

    /** 1초마다 엔진을 부르는 여정. 차는 [speedKph]로 달리고, 경로 위 후보는 지나치면 목록에서 빠진다 (길안내 중 앱이 넘기는 방식) */
    private class Trip(val engine: CoachEngine, start: Long) {
        var now = start
        var posM = 0.0
        var speedKph: Float? = 100f
        var asleep: Set<String> = emptySet()
        var road: List<Pair<Double, StopCandidate>> = emptyList()
        /** 이 거리 안으로 다가온 경로 위 후보는 목록에서 뺀다 (엔진이 가까이에서 한 번도 못 보게 할 때) */
        var hideWithinM = 0.0
        var destM: Double? = null
        var out: CoachOutput = engine.update(input(Gear.DRIVE, false, emptyList(), null))

        val ahead: List<StopCandidate>
            get() = road.filter { it.first - posM >= hideWithinM }.map { (at, c) -> c.copy(routeDistanceM = at - posM) }

        fun plan(): PitStopPlan = engine.plan(input(Gear.DRIVE, false, emptyList(), null))

        private fun input(gear: Gear, door: Boolean, here: List<StopCandidate>, intent: VoiceIntent?): CoachInput {
            val route = destM?.let {
                RouteState(guiding = true, remainDistanceM = it - posM, remainTimeS = (it - posM) / (100 / 3.6), nextManeuverDistanceM = 100_000.0)
            }
            return CoachInput(
                now = now, gear = gear, speedKph = if (gear == Gear.PARK) 0f else speedKph, doorOpened = door,
                route = route, candidates = here + ahead, intent = intent, asleepOccupantIds = asleep,
            )
        }

        fun step(gear: Gear = Gear.DRIVE, door: Boolean = false, here: List<StopCandidate> = emptyList(), intent: VoiceIntent? = null): CoachOutput {
            now += SEC
            if (gear != Gear.PARK) posM += (speedKph ?: 0f) / 3.6
            out = engine.update(input(gear, door, here, intent))
            return out
        }

        /** until까지 달린다 (마지막 update가 until) */
        fun driveUntil(until: Long): CoachOutput {
            while (now < until) step()
            return out
        }

        /** 다음 초부터 until까지 선다. 정차를 시작하고 doorAfter 뒤에 문이 열린다 (null이면 열리지 않는다) */
        fun parkUntil(until: Long, here: List<StopCandidate> = emptyList(), doorAfter: Long? = 5 * SEC): CoachOutput {
            val start = now + SEC
            while (now < until) step(Gear.PARK, door = doorAfter != null && now + SEC == start + doorAfter, here = here)
            return out
        }

        /** 출발 10분 뒤에 [minutes]분 서고 떠나는 순간의 출력. 그 앞 구간(13분)은 관측으로 남지 않는다 */
        fun stopFor(minutes: Int, here: List<StopCandidate> = emptyList(), doorAfter: Long? = 5 * SEC): CoachOutput {
            driveUntil(now + 10 * MIN)
            val park = now + SEC
            parkUntil(park + minutes * MIN - SEC, here, doorAfter)
            return step()
        }
    }

    private fun CoachOutput.codes() = restroom.reasons.map { it.code }

    // ------------------------------------------------------------ 식사 정차: 길이와 장소

    @Test
    fun anAdultLeavingA30MinuteMealStopAtAFoodPlaceHas93Minutes() {
        // need-covariates 2장: 시계는 정차 3분에 돌아가 출발 때 27분이 지나 있다. 150 × 0.8 − 27 = 93 (보정 전 123)
        val t = Trip(engine(adult()), noon)
        t.driveUntil(noon + 10 * MIN)
        val park = noon + 10 * MIN + SEC
        assertEquals(138, t.parkUntil(park + 15 * MIN, listOf(foodHere)).restroom.minutes, "15분째: 아직 식사 정차가 아니다 (150 − 12)")
        assertEquals(133, t.parkUntil(park + 20 * MIN - SEC, listOf(foodHere)).restroom.minutes)
        val judged = t.parkUntil(park + 20 * MIN, listOf(foodHere))
        assertEquals(103, judged.restroom.minutes, "20분: 시계가 돌아간 때부터의 구간 전체에 0.8 (120 − 17)")
        assertEquals(listOf("RESTROOM_ELAPSED", "RESTROOM_AFTER_MEAL"), judged.codes())
        t.parkUntil(park + 30 * MIN - SEC, listOf(foodHere))
        val leaving = t.step()
        assertEquals(93, leaving.restroom.minutes)
        assertEquals("식사 뒤라 평소보다 조금 일찍 잡았어요", leaving.restroom.reasons.single { it.code == "RESTROOM_AFTER_MEAL" }.text)
        assertEquals(63, t.driveUntil(t.now + 30 * MIN).restroom.minutes, "그 뒤로는 1분에 1분씩")
    }

    @Test
    fun aStopThatStartsAtOrAfterEightInTheEveningUsesTheWeakerFactor() {
        val late = Trip(engine(adult(), start = kst(2026, 9, 24, 19, 55)), kst(2026, 9, 24, 19, 55))
        assertEquals(108, late.stopFor(30, listOf(foodHere)).restroom.minutes, "20:05에 선 정차: 150 × 0.9 − 27")
        // 19:59:30에 시작한 정차는 대부분 20시 뒤에 먹어도 0.8이다 (정차를 시작한 시각으로 정한다)
        val before = kst(2026, 9, 24, 19, 49, 29)
        val early = Trip(engine(adult(), start = before), before)
        assertEquals(93, early.stopFor(30, listOf(foodHere)).restroom.minutes)
    }

    @Test
    fun shortStopsAndPlacesWithoutFoodGetNoFactor() {
        val plain = listOf("RESTROOM_ELAPSED")
        Trip(engine(adult()), noon).stopFor(15, listOf(foodHere)).let {
            assertEquals(138, it.restroom.minutes, "15분 정차")
            assertEquals(plain, it.codes())
        }
        // 20분에 1초 모자란 정차
        val t = Trip(engine(adult()), noon)
        t.driveUntil(noon + 10 * MIN)
        t.parkUntil(t.now + 20 * MIN - SEC, listOf(foodHere))
        assertEquals(plain, t.step().codes())

        Trip(engine(adult()), noon).stopFor(30, listOf(shelterHere)).let {
            assertEquals(123, it.restroom.minutes, "음식 없는 졸음쉼터")
            assertEquals(plain, it.codes())
        }
        assertEquals(123, Trip(engine(adult()), noon).stopFor(30).restroom.minutes, "어디인지 모르는 곳")
        val far = foodHere.copy(straightDistanceM = 450.0)
        assertEquals(123, Trip(engine(adult()), noon).stopFor(30, listOf(far)).restroom.minutes, "음식 있는 곳이 300m 밖")
    }

    @Test
    fun theNearestPlaceWithin300MetersDecides() {
        val foodFarther = foodHere.copy(straightDistanceM = 250.0)
        assertEquals(123, Trip(engine(adult()), noon).stopFor(30, listOf(foodFarther, shelterHere)).restroom.minutes, "졸음쉼터가 더 가깝다")
        val shelterFarther = shelterHere.copy(straightDistanceM = 280.0)
        assertEquals(93, Trip(engine(adult()), noon).stopFor(30, listOf(shelterFarther, foodHere)).restroom.minutes, "휴게소가 더 가깝다")
        // 경로 거리만 아는 후보도 같다 (시연: 선 곳의 후보가 경로 거리 0으로 남는다)
        val byRoute = foodHere.copy(straightDistanceM = null, routeDistanceM = 0.0)
        assertEquals(93, Trip(engine(adult()), noon).stopFor(30, listOf(byRoute)).restroom.minutes)
    }

    @Test
    fun aPlaceThatLeftTheListOnArrivalIsRemembered() {
        // 길안내 중에는 진입로를 지나는 순간 그 휴게소가 목록에서 빠진다. 엔진은 마지막으로 300m 안에서 본 후보를 기억한다
        val t = Trip(engine(adult()), noon)
        t.road = listOf(15 * km to foodAhead)
        t.driveUntil(noon + 9 * MIN + 2 * SEC) // 15km 지점 = 9분
        assertTrue(t.ahead.isEmpty(), "진입로를 지났고 목록에 없다")
        t.speedKph = 30f
        t.driveUntil(t.now + 40 * SEC) // 주차 칸까지 330m
        val park = t.now + SEC
        t.parkUntil(park + 30 * MIN - SEC)
        val leaving = t.step()
        assertEquals(93, leaving.restroom.minutes)
        assertTrue("RESTROOM_AFTER_MEAL" in leaving.codes())
    }

    @Test
    fun aPlaceDrivenPastIsNotWhereTheCarStopped() {
        val t = Trip(engine(adult()), noon)
        t.road = listOf(5 * km to foodAhead)
        t.driveUntil(noon + 9 * MIN) // 휴게소를 지나 10km 더 왔다
        val park = t.now + SEC
        t.parkUntil(park + 30 * MIN - SEC)
        assertEquals(123, t.step().restroom.minutes, "지나친 휴게소 10km 뒤의 정차")

        // 차속이 끊겨 얼마나 달렸는지 모르면 기억을 일찍 버린다 (고속으로 달린 것으로 본다)
        val blind = Trip(engine(adult()), noon)
        blind.road = listOf(15 * km to foodAhead)
        blind.driveUntil(noon + 9 * MIN + 2 * SEC)
        assertTrue(blind.ahead.isEmpty())
        blind.speedKph = null
        blind.driveUntil(blind.now + 2 * MIN)
        val p2 = blind.now + SEC
        blind.parkUntil(p2 + 30 * MIN - SEC)
        assertEquals(listOf("RESTROOM_ELAPSED"), blind.step().codes())
    }

    @Test
    fun movingTheCarInsideTheRestAreaKeepsItOneStop() {
        // 12분 서고, 주차 칸을 옮기고(1분, 시속 10km), 10분 더: 한 정차 23분이라 식사 정차다
        val t = Trip(engine(adult()), noon)
        t.driveUntil(noon + 10 * MIN)
        val park = t.now + SEC
        t.parkUntil(park + 12 * MIN - SEC, listOf(foodHere))
        t.speedKph = 10f
        repeat(60) { t.step(here = listOf(foodHere)) }
        t.parkUntil(park + 23 * MIN - SEC, listOf(foodHere))
        val leaving = t.step()
        assertEquals(100, leaving.restroom.minutes, "120 − 20")
        assertTrue("RESTROOM_AFTER_MEAL" in leaving.codes())
    }

    // ------------------------------------------------------------ 식사 정차: 문이 열리지 않았거나 늦게 열렸을 때

    @Test
    fun aLongStopWithoutADoorEventKeepsTheClockRunningAndAddsNoFactor() {
        // 정한 규칙: 시계가 돌아가지 않은 정차에는 식사 배수를 걸지 않는다 (아무도 내리지 않았다)
        val leaving = Trip(engine(adult()), noon).stopFor(30, listOf(foodHere), doorAfter = null)
        assertEquals(109, leaving.restroom.minutes, "출발부터 40분 1초째: 시계가 돌아간 적이 없다")
        assertEquals(listOf("RESTROOM_ELAPSED"), leaving.codes())
    }

    @Test
    fun aDoorThatOpensLateStartsTheMealIntervalThen() {
        val t = Trip(engine(adult(), config = noLearning), noon)
        val leaving = t.stopFor(40, listOf(foodHere), doorAfter = 25 * MIN)
        assertEquals(105, leaving.restroom.minutes, "25분에 시계가 돌아가고 그때부터 0.8: 120 − 15")
        assertTrue("RESTROOM_AFTER_MEAL" in leaving.codes())
    }

    @Test
    fun aStopSeenOnlyAtItsStartAndAgainMuchLaterStillCounts() {
        // 앱이 정차 중에 다시 시작하면 정차 시작 시각의 P 입력 한 번과 지금 입력만 온다 (CoachWorld.newEngine).
        // 그 한 번의 update에서 화장실 정차·휴식·식사 길이가 함께 판정돼도 순서와 상관없이 같은 결과여야 한다
        val e = engine(adult())
        e.update(CoachInput(now = noon, gear = Gear.DRIVE, speedKph = 100f))
        val park = noon + 10 * MIN
        e.update(CoachInput(now = park, gear = Gear.PARK, speedKph = 0f, doorOpened = true))
        val later = e.update(CoachInput(now = park + 25 * MIN, gear = Gear.PARK, speedKph = 0f, candidates = listOf(foodHere)))
        assertEquals(98, later.restroom.minutes, "시계는 3분에 돌아갔고(22분 전) 식사 정차다: 120 − 22")
        assertTrue("RESTROOM_AFTER_MEAL" in later.codes())
        // 떠나는 update에서야 알게 돼도 같다
        val e2 = engine(adult())
        e2.update(CoachInput(now = noon, gear = Gear.DRIVE, speedKph = 100f))
        e2.update(CoachInput(now = park, gear = Gear.PARK, speedKph = 0f, doorOpened = true, candidates = listOf(foodHere)))
        val leaving = e2.update(CoachInput(now = park + 25 * MIN, gear = Gear.DRIVE, speedKph = 20f))
        assertEquals(98, leaving.restroom.minutes)
        assertTrue("RESTROOM_AFTER_MEAL" in leaving.codes())
    }

    // ------------------------------------------------------------ 식사 정차: 수락한 정차, "배고파"

    /** "배고파" → 식사 제안 → "응". 수락한 정차의 목적에 식사가 들어간다 */
    private fun acceptMeal(t: Trip): Prompt {
        val asked = assertNotNull(t.step(intent = VoiceIntent.Hungry).prompt, "식사 제안")
        assertTrue(asked.speech.contains("식사"), asked.speech)
        val next = assertNotNull(t.step(intent = VoiceIntent.Accept(asked.id)).next)
        assertTrue(Purpose.MEAL in next.purposes, next.purposes.toString())
        return asked
    }

    @Test
    fun aStopAcceptedForAMealCountsEvenWhenThePlaceIsNotInTheList() {
        val t = Trip(engine(adult(), config = noLearning), noon)
        // 70km 앞 휴게소. 1km 앞에서 목록에서 빠지게 해 '300m 안의 후보'로는 알 수 없게 한다
        t.road = listOf(70 * km to foodAhead)
        t.hideWithinM = 1 * km
        acceptMeal(t)
        t.driveUntil(noon + 41 * MIN + 40 * SEC) // "배고파"는 30분 뒤 풀렸다. 후보는 16초 전에 빠졌다
        assertTrue(t.ahead.isEmpty())
        val park = t.now + SEC
        t.parkUntil(park + 30 * MIN - SEC)
        val leaving = t.step()
        assertEquals(93, leaving.restroom.minutes)
        assertTrue("RESTROOM_AFTER_MEAL" in leaving.codes())
    }

    @Test
    fun stoppingSomewhereElseBeforeTheAcceptedMealStopDoesNotCount() {
        val t = Trip(engine(adult(), config = noLearning), noon)
        t.road = listOf(100 * km to foodAhead)
        acceptMeal(t)
        t.driveUntil(noon + 35 * MIN) // 수락한 곳은 아직 41km 앞, "배고파"는 풀렸다
        val park = t.now + SEC
        t.parkUntil(park + 30 * MIN - SEC)
        val leaving = t.step()
        assertEquals(123, leaving.restroom.minutes)
        assertEquals(listOf("RESTROOM_ELAPSED"), leaving.codes())
    }

    @Test
    fun aStopWhileHungryIsStillHeldCounts() {
        val t = Trip(engine(adult()), noon)
        assertNotNull(t.step(intent = VoiceIntent.Hungry).prompt, "식사할 곳을 못 찾았다는 답")
        t.driveUntil(noon + 10 * MIN)
        val park = t.now + SEC
        t.parkUntil(park + 25 * MIN - SEC)
        val leaving = t.step()
        assertEquals(98, leaving.restroom.minutes, "120 − 22")
        assertTrue("RESTROOM_AFTER_MEAL" in leaving.codes())

        // 35분 전에 한 말은 풀렸다 (30분 유지)
        val stale = Trip(engine(adult(), config = noLearning), noon)
        stale.step(intent = VoiceIntent.Hungry)
        stale.driveUntil(noon + 35 * MIN)
        val p2 = stale.now + SEC
        stale.parkUntil(p2 + 25 * MIN - SEC)
        assertEquals(128, stale.step().restroom.minutes)
    }

    // ------------------------------------------------------------ 식사 뒤 구간의 끝, 학습, 마감

    @Test
    fun theNextRestroomStopClearsTheFactor() {
        val t = Trip(engine(adult(), config = noLearning), noon)
        assertEquals(93, t.stopFor(30, listOf(foodHere)).restroom.minutes)
        t.driveUntil(t.now + 40 * MIN)
        assertEquals(53, t.out.restroom.minutes)
        val park = t.now + SEC
        t.parkUntil(park + 5 * MIN - SEC)
        val leaving = t.step()
        assertEquals(148, leaving.restroom.minutes, "화장실 정차 뒤에는 보정 없는 구간")
        assertEquals(listOf("RESTROOM_ELAPSED"), leaving.codes())
    }

    @Test
    fun a96MinuteIntervalAfterAMealIsLearnedAs120() {
        // 식사 정차(시계가 R에 돌아감) → R + 96분에 다음 화장실 정차
        val meal = Trip(engine(adult()), noon)
        meal.stopFor(30, listOf(foodHere))
        val reset = noon + 10 * MIN + SEC + 3 * MIN
        meal.driveUntil(reset + 93 * MIN - SEC)
        meal.parkUntil(meal.now + 5 * MIN)
        assertEquals(listOf(120.0), meal.engine.memory().restroomIntervalsMin["me"])

        // 식사 없이 120분 만에 선 사람 (첫 정차 16분: 휴식은 되지만 식사 정차는 아니다)
        val plain = Trip(engine(adult()), noon)
        plain.stopFor(16, listOf(foodHere))
        plain.driveUntil(reset + 117 * MIN - SEC)
        plain.parkUntil(plain.now + 5 * MIN)
        val a = meal.engine.memory()
        val b = plain.engine.memory()
        assertEquals(b.restroomIntervalsMin, a.restroomIntervalsMin)
        assertEquals(b.restroomCensoredMin, a.restroomCensoredMin)
        assertEquals(b.restroomSuggestedMin, a.restroomSuggestedMin)
        // 같은 사후분포: 두 사람 모두 시계가 돌아간 지 2분, 같은 게이지
        assertEquals(plain.out.restroom.minutes, meal.out.restroom.minutes)
        assertTrue(meal.out.restroom.reasons.any { it.code == "RESTROOM_PERSONAL" })
    }

    @Test
    fun afterAMealTheEarlierDeadlineAsksALastChanceSooner() {
        // 어른 둘, 30분 식사 뒤 출발. 3.5km 앞 휴게소 다음은 47분 거리, 목적지는 60분 거리
        fun run(config: CoachConfig): List<CoachOutput> {
            val t = Trip(engine(adult("a1"), adult("a2"), config = config), noon)
            t.stopFor(30, listOf(foodHere))
            t.road = listOf(t.posM + 3.5 * km to stop("s1", null, name = "가휴게소"), t.posM + 78.5 * km to stop("s2", null, name = "나휴게소"))
            t.destM = t.posM + 100 * km
            return (0 until 50).map { t.step() }
        }
        // 식사 배수가 있으면 묶음 마감이 0.8 × 83 − 27 = 40분: 47분 공백을 못 넘긴다
        val withMeal = run(noLearning)
        val asked = assertNotNull(withMeal.firstNotNullOfOrNull { it.prompt }, "마지막 기회 질문")
        assertEquals(PromptKind.LAST_CHANCE, asked.kind)
        assertEquals("s1", asked.stop?.id)
        assertEquals(Level.OK, withMeal.last().restroom.level, "게이지(93분)는 넉넉하다")
        assertEquals(listOf("LAST_CHANCE_GAP", "LAST_CHANCE_GROUP"), withMeal.last().next!!.reasons.map { it.code })
        // 배수를 끄면 마감이 57분이라 나휴게소까지 가고, 그 뒤 목적지까지 13분은 공백이 아니다
        val without = run(noLearning.copy(mealIntervalFactor = 1.0, mealIntervalFactorEvening = 1.0))
        assertTrue(without.none { it.prompt != null }, without.firstNotNullOfOrNull { it.prompt }.toString())
        assertNull(without.last().next)
    }

    @Test
    fun thePlanUsesTheMealFactorForTheLegTheCarIsOn() {
        // 식사 뒤 출발: 판단 마감이 0.8 × 112 − 27 = 63분. 50분 거리 휴게소, 목적지는 80분 거리
        fun plan(config: CoachConfig): PitStopPlan {
            val t = Trip(engine(adult(), config = config), noon)
            t.stopFor(30, listOf(foodHere))
            val v = 100 / 3.6
            val input = CoachInput(
                now = t.now, gear = Gear.DRIVE, speedKph = 100f,
                candidates = listOf(stop("a", 50 * 60 * v, name = "가휴게소")),
                route = RouteState(guiding = true, remainDistanceM = 80 * 60 * v, remainTimeS = 80 * 60.0),
            )
            return t.engine.plan(input)
        }
        val withMeal = plan(noLearning)
        assertEquals(listOf("a"), withMeal.stops.map { it.stop.id }, "목적지(80분)까지는 못 간다: 가휴게소에 선다")
        assertTrue(withMeal.notes.none { it.code == "PLAN_GAP" || it.code == "PLAN_NOW" }, withMeal.notes.toString())
        val without = plan(noLearning.copy(mealIntervalFactor = 1.0))
        assertTrue(without.stops.isEmpty(), "배수가 없으면 마감 85분이라 그대로 간다: ${without.stops}")
    }

    @Test
    fun legsAfterAPlannedMealStopUseFactorOne() {
        // 정한 규칙: 계획의 '정차 뒤 구간'은 배수 1.0 (fromReset). 계획은 정차 뒤 시계를 출발 때 0으로 보는 근사라, 여기에 0.8만
        // 얹으면 계획이 실제보다 정밀해 보일 뿐이다 (SPEC 4.1). 11:40 출발, 20분 거리 휴게소에서 점심, 그 뒤 100분 구간
        val now = kst(2026, 9, 24, 11, 40)
        val e = CoachEngine(profile = TripProfile(listOf(adult()), tripStart = now))
        val v = 100 / 3.6
        val plan = e.plan(
            CoachInput(
                now = now, gear = Gear.DRIVE, speedKph = 100f,
                candidates = listOf(stop("lunch", 20 * 60 * v, name = "가휴게소", facilities = Facilities(food = true))),
                route = RouteState(guiding = true, remainDistanceM = 120 * 60 * v, remainTimeS = 120 * 60.0),
            ),
        )
        assertEquals(listOf("lunch"), plan.stops.map { it.stop.id })
        assertTrue(Purpose.MEAL in plan.stops.single().purposes)
        // 0.8을 걸었다면 100분 구간이 마감(112분 × 0.8 = 90분)을 넘어 '화장실 사이가 먼 구간'이라고 했을 것이다
        assertTrue(plan.notes.none { it.code == "PLAN_GAP" }, plan.notes.toString())
    }

    // ------------------------------------------------------------ 밤에 잠든 아이

    @Test
    fun aChildAsleepAtNightStretchesTheGaugeAndWakingStopsIt() {
        val nine = kst(2026, 9, 24, 21, 0)
        val t = Trip(engine(child("kid", 7, label = "첫째"), start = nine), nine)
        assertEquals(105, t.out.restroom.minutes)
        t.asleep = setOf("kid")
        val asleep = t.step()
        assertEquals(157, asleep.restroom.minutes, "남은 105분 × 1.5")
        assertEquals("kid", asleep.restroom.drivenBy)
        assertEquals(listOf("RESTROOM_ELAPSED", "RESTROOM_ASLEEP"), asleep.codes())
        assertEquals("첫째가 자는 동안은 여유를 조금 더 뒀어요", asleep.restroom.reasons.last().text)
        assertEquals(127, t.driveUntil(nine + 30 * MIN + SEC).restroom.minutes, "30분 자는 동안 20분만 흘렀다: (105 − 20) × 1.5")
        t.asleep = emptySet()
        val woke = t.step()
        assertEquals(84, woke.restroom.minutes, "깨면 남은 유효 시간 그대로 (105 − 20)")
        assertEquals(listOf("RESTROOM_ELAPSED"), woke.codes())
        assertEquals(54, t.driveUntil(t.now + 30 * MIN).restroom.minutes)
    }

    @Test
    fun aDaytimeNapUnknownIdsAndAdultsChangeNothing() {
        fun gauges(start: Long, asleep: Set<String>): List<Gauge> {
            val t = Trip(engine(adult(), child("kid", 7), start = start), start)
            t.asleep = asleep
            return listOf(t.driveUntil(start + 20 * MIN).restroom, t.driveUntil(start + 50 * MIN).restroom)
        }
        val one = kst(2026, 9, 24, 13, 0)
        val nine = kst(2026, 9, 24, 21, 0)
        assertEquals(gauges(one, emptySet()), gauges(one, setOf("kid")), "13시 낮잠")
        assertEquals(gauges(nine, emptySet()), gauges(nine, setOf("ghost", "me")), "모르는 id, 어른")
        assertTrue(gauges(nine, setOf("kid")) != gauges(nine, emptySet()), "밤에 잠든 아이만 달라진다")
    }

    @Test
    fun theSetSurvivesInputCleaning() {
        // 차속이 NaN이면 InputSanitizer가 입력을 새로 만든다. 그때도 잠든 사람 목록이 남아야 한다
        val nine = kst(2026, 9, 24, 21, 0)
        val e = engine(child("kid", 7), start = nine)
        e.update(CoachInput(now = nine, gear = Gear.DRIVE, speedKph = 100f))
        val out = e.update(CoachInput(now = nine + SEC, gear = Gear.DRIVE, speedKph = Float.NaN, asleepOccupantIds = setOf("kid")))
        assertEquals(157, out.restroom.minutes)
    }

    @Test
    fun theDecisionDeadlineIgnoresSleep() {
        // 가족(어른 + 7살), 마지막 화장실 45분 전, 21시. 3.5km 앞 휴게소 뒤로 40km(24분)마다 화장실
        val nine = kst(2026, 9, 24, 21, 0)
        fun run(asleep: Set<String>): Pair<List<CoachOutput>, PitStopPlan> {
            val people = listOf(adult(), child("kid", 7))
            val e = CoachEngine(
                profile = TripProfile(people, tripStart = nine - 45 * MIN, lastRestroomAt = people.associate { it.id to nine - 45 * MIN }),
                random = Random(7),
            )
            val t = Trip(e, nine)
            t.asleep = asleep
            t.road = generateSequence(3.5) { it + 40.0 }.takeWhile { it < 200.0 }.mapIndexed { i, d -> d * km to stop("s$i", null, name = "${i + 1}번 휴게소") }.toList()
            t.destM = 200 * km
            val outs = (0 until 60).map { t.step() }
            return outs to t.plan()
        }
        val (awake, awakePlan) = run(emptySet())
        val (asleep, asleepPlan) = run(setOf("kid"))
        assertEquals(59, awake.last().restroom.minutes, "아이 105 − 46")
        assertEquals(88, asleep.last().restroom.minutes, "자는 아이: 남은 59.3분 × 1.5")
        assertTrue("RESTROOM_ASLEEP" in asleep.last().codes())
        // 마지막 기회는 같은 때 같은 곳을 같은 이유로 묻는다: 깬 아이는 바로 화장실을 찾을 수 있다
        assertEquals(PromptKind.LAST_CHANCE, awake.firstNotNullOf { it.prompt }.kind)
        assertEquals(awake.map { it.prompt?.speech }, asleep.map { it.prompt?.speech })
        assertEquals(awake.map { it.next?.stop?.id to it.next?.reasons }, asleep.map { it.next?.stop?.id to it.next?.reasons })
        assertTrue(awakePlan.stops.isNotEmpty(), "계획에 정차가 있어야 비교가 뜻이 있다")
        assertEquals(awakePlan, asleepPlan)
    }

    @Test
    fun aSleepingChildAfterAnEveningMealGetsBothFactors() {
        // 19:50 출발 → 20:00 저녁 식사 정차 30분(0.9) → 출발하며 아이가 잠든다(1.5)
        val start = kst(2026, 9, 24, 19, 50) - SEC
        val t = Trip(engine(child("kid", 7), start = start), start)
        val leaving = t.stopFor(30, listOf(foodHere))
        assertEquals(67, leaving.restroom.minutes, "105 × 0.9 − 27 = 67.5")
        t.asleep = setOf("kid")
        val asleep = t.step()
        assertEquals(101, asleep.restroom.minutes, "남은 67.5분 × 1.5")
        assertEquals(listOf("RESTROOM_ELAPSED", "RESTROOM_AFTER_MEAL", "RESTROOM_ASLEEP"), asleep.codes())
        assertEquals(71, t.driveUntil(t.now + 30 * MIN).restroom.minutes)
    }

    // ------------------------------------------------------------ 배수를 끄면, 배수가 걸릴 일이 없으면

    @Test
    fun factorsOfOneLeaveEverythingAsBefore() {
        val meal = Trip(engine(adult(), config = factorsOff), noon).stopFor(30, listOf(foodHere))
        assertEquals(123, meal.restroom.minutes)
        assertEquals(listOf("RESTROOM_ELAPSED"), meal.codes())

        val nine = kst(2026, 9, 24, 21, 0)
        val t = Trip(engine(child("kid", 7), start = nine, config = factorsOff), nine)
        t.asleep = setOf("kid")
        val out = t.driveUntil(nine + 30 * MIN)
        assertEquals(75, out.restroom.minutes)
        assertEquals(listOf("RESTROOM_ELAPSED"), out.codes())
    }

    @Test
    fun aTripWithoutMealStopsOrSleepIsIdenticalWithFactorsOnOrOff() {
        // 15분 정차(휴식·화장실, 식사 아님)가 낀 낮 주행: 기본 설정과 배수를 끈 설정의 출력이 매초 같아야 한다
        fun run(config: CoachConfig): List<List<Any?>> {
            val t = Trip(engine(adult(), child("kid", 7), config = config), noon)
            t.road = generateSequence(20.0) { it + 30.0 }.takeWhile { it < 300.0 }
                .mapIndexed { i, d -> d * km to stop("s$i", null, name = "${i + 1}번 휴게소", facilities = Facilities(food = true)) }.toList()
            t.destM = 300 * km
            val outs = ArrayList<CoachOutput>()
            repeat(50 * 60) { outs += t.step() }
            repeat(15 * 60) { i -> outs += t.step(Gear.PARK, door = i == 5, here = listOf(foodHere)) }
            repeat(70 * 60) { outs += t.step() }
            return outs.map { listOf(it.restroom, it.fatigue, it.next, it.prompt?.kind, it.prompt?.speech, it.parked) }
        }
        val a = run(CoachConfig())
        val b = run(factorsOff)
        assertTrue(a.any { it[3] != null }, "질문이 한 번은 나오는 주행이어야 비교가 뜻이 있다")
        assertEquals(b, a)
    }

    // ------------------------------------------------------------ 정차 판정, 문구

    @Test
    fun theStopTrackerReportsTheMealLengthOncePerStop() {
        val st = StopTracker(CoachConfig())
        assertEquals(NONE, st.sessionStart)
        st.update(0, Gear.PARK, 0f, false)
        assertEquals(0L, st.sessionStart)
        assertEquals(NONE, st.update(20 * MIN - 1, Gear.PARK, 0f, false).mealLengthAt)
        assertEquals(20 * MIN, st.update(20 * MIN, Gear.PARK, 0f, false).mealLengthAt)
        assertEquals(NONE, st.update(21 * MIN, Gear.PARK, 0f, false).mealLengthAt, "한 정차에 한 번")

        // update가 드물어 출발할 때야 알게 돼도 낸다
        val sparse = StopTracker(CoachConfig())
        sparse.update(0, Gear.PARK, 0f, true)
        val ev = sparse.update(25 * MIN, Gear.DRIVE, 20f, false)
        assertEquals(3 * MIN, ev.restroomStopAt)
        assertEquals(15 * MIN, ev.restAt)
        assertEquals(20 * MIN, ev.mealLengthAt)

        // 주차 칸을 옮겨도 같은 정차다. 떠났다 다시 서면 새 정차다
        val moved = StopTracker(CoachConfig())
        moved.update(0, Gear.PARK, 0f, false)
        moved.update(12 * MIN, Gear.DRIVE, 10f, false)
        moved.update(13 * MIN, Gear.PARK, 0f, false)
        assertEquals(0L, moved.sessionStart)
        assertEquals(20 * MIN, moved.update(20 * MIN, Gear.PARK, 0f, false).mealLengthAt)
        moved.update(30 * MIN, Gear.DRIVE, 90f, false)
        moved.update(50 * MIN, Gear.PARK, 0f, false)
        assertEquals(50 * MIN, moved.sessionStart)
        assertEquals(70 * MIN, moved.update(70 * MIN, Gear.PARK, 0f, false).mealLengthAt)
    }

    @Test
    fun theReasonTextsPickTheRightParticle() {
        val texts = CoachTexts()
        assertEquals("식사 뒤라 평소보다 조금 일찍 잡았어요", texts.reasonRestroomAfterMeal())
        assertEquals("7살 아이가 자는 동안은 여유를 조금 더 뒀어요", texts.reasonRestroomAsleep("7살 아이"))
        assertEquals("막내딸이 자는 동안은 여유를 조금 더 뒀어요", texts.reasonRestroomAsleep("막내딸"))
    }
}
