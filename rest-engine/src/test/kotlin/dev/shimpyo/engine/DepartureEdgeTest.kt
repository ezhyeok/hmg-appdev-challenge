package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 출발 전 한마디의 경계 상황 (2026-11-13 감사). 기본 동작은 DepartureCheckTest.
 * 감사가 찾은 것: 실제 길안내에서는 첫 회전이 대개 500m 안이라 '회전 앞에서는 묻지 않기'에 막혀 거의 묻지 못했다, 탈 때 연 문이
 * 화장실 정차로 기록되고 가정한 시각부터 잰 간격을 배웠다, 재시작하면 여정 중간의 정차를 출발 전으로 봤다, 재탐색을 새 목적지로 봤다 등.
 */
class DepartureEdgeTest {
    private val km = 1_000.0
    private val start = kst(2026, 11, 13, 8, 10)
    private val home = RouteDestination("고양 킨텍스", 37.6687, 126.7454)
    private val mart = RouteDestination("이마트 분당점", 37.3675, 127.1080)

    private fun engine(
        occupants: List<Occupant> = listOf(adult("driver"), child("kid", 7)),
        restroomMinAgo: Int = 60,
        drivenMin: Int = 0,
        tripStart: Long = start - drivenMin * MIN,
        tripMoved: Boolean = false,
    ) = CoachEngine(
        profile = TripProfile(
            occupants, tripStart = tripStart,
            lastRestroomAt = occupants.associate { it.id to start - restroomMinAgo * MIN },
            continuousDrivingMin = drivenMin.toDouble(), tripMoved = tripMoved,
        ),
        random = Random(7),
    )

    private fun route(remainKm: Double, tripMin: Int, dest: RouteDestination? = home, maneuverM: Double? = null) =
        RouteState(guiding = true, remainDistanceM = remainKm * km, remainTimeS = tripMin * 60.0, nextManeuverDistanceM = maneuverM, destination = dest)

    private fun parked(e: CoachEngine, at: Long, r: RouteState?, door: Boolean = false, intent: VoiceIntent? = null) =
        e.update(CoachInput(now = at, gear = Gear.PARK, speedKph = 0f, route = r, doorOpened = door, intent = intent))

    private fun drive(e: CoachEngine, at: Long, r: RouteState?, kph: Float = 60f) =
        e.update(CoachInput(now = at, gear = Gear.DRIVE, speedKph = kph, route = r))

    /** [from]부터 [toSec]초까지 10초마다 P로 넣고 출발 전 질문을 돌려준다 */
    private fun askedWhileParked(e: CoachEngine, from: Long, toSec: Int, r: RouteState?, door: Boolean = false): Prompt? {
        for (s in 0..toSec step 10) {
            val p = parked(e, from + s * SEC, r, door = door && s == 0).prompt
            if (p?.kind == PromptKind.DEPARTURE) return p
        }
        return null
    }

    // ---------------------------------------------------------------- 감사 1: 서 있을 때는 회전 거리가 막지 않는다

    @Test
    fun parkedWithTheFirstTurnNearbyStillAsks() {
        // 아파트 주차장을 나서면 첫 회전이 200m 안이다. 서 있는 운전자에게 '회전 앞에서는 말 걸지 않기'는 뜻이 없다
        assertNotNull(askedWhileParked(engine(), start, 60, route(48.0, 70, maneuverM = 200.0)), "회전이 200m 앞이어도 서 있으면 묻는다")
    }

    // ---------------------------------------------------------------- 감사 2: 탈 때 연 문은 화장실 정차가 아니다

    @Test
    fun boardingDoorIsNotARestroomStop() {
        // 시동 → 아이가 타며 문을 연다 → 4분 뒤 목적지 설정. 탈 때의 문 열림을 '화장실 정차'로 세면 게이지가 다시 차서 묻지 않았고,
        // 가정한 60분 전부터 잰 간격(63분)을 아이의 기록으로 배웠다
        val e = engine()
        parked(e, start, null)
        parked(e, start + 30 * SEC, null, door = true)
        for (s in 40..240 step 10) parked(e, start + s * SEC, null)
        assertTrue(e.memory().restroomIntervalsMin.isEmpty() && e.memory().restroomSuggestedMin.isEmpty(), "탈 때의 문 열림으로 배우지 않는다: ${e.memory()}")
        val p = askedWhileParked(e, start + 250 * SEC, 60, route(48.0, 70))
        assertNotNull(p, "탈 때 문이 열렸어도 출발 전에는 묻는다")
    }

    @Test
    fun acceptingAndThenOpeningADoorIsTheRestroomVisitButTeachesNothing() {
        // '다녀올게요' 뒤 문이 열리면 다녀온 것이다: 게이지가 찬다. 출발 전 마지막 화장실은 가정한 시각이라 그 간격은 배우지 않는다
        val e = engine()
        val p = assertNotNull(askedWhileParked(e, start, 30, route(48.0, 70)))
        e.onPromptResult(p.id, true, start + 40 * SEC)
        parked(e, start + 60 * SEC, route(48.0, 70), door = true)
        val out = parked(e, start + 4 * MIN, route(48.0, 70))
        assertTrue(out.restroom.minutes!! > 70, "다녀온 뒤에는 70분 길이 여유 안이다: ${out.restroom}")
        assertTrue(e.memory().restroomIntervalsMin.isEmpty() && e.memory().restroomSuggestedMin.isEmpty(), "가정한 출발 전 시각부터 잰 간격은 배우지 않는다")
    }

    // ---------------------------------------------------------------- 감사 3: 재시작해도 여정 중간의 정차는 출발 전이 아니다

    @Test
    fun aRestartAtARestAreaIsStillAMidTripStop() {
        // 110분 달려 휴게소에 섰고 20분 뒤 앱이 다시 떴다(연속 주행은 휴식으로 0). 엔진은 정차 시작 시각에 P 입력을 하나 받는다(경로 모름)
        val e = engine(restroomMinAgo = 110, drivenMin = 0, tripStart = start - 130 * MIN, tripMoved = true)
        e.update(CoachInput(now = start - 20 * MIN, gear = Gear.PARK, doorOpened = true))
        assertNull(askedWhileParked(e, start, 120, route(200.0, 150)), "같은 길안내가 이어지는 휴게소 정차다")
    }

    @Test
    fun withoutKnowingTheRouteAtParkTheFirstRouteSeenIsTheOneWeParkedWith() {
        // 학교 앞에 막 섰고(1분 전) 그때 앱이 다시 떴다: 경로를 아직 몰랐다(null). 처음 본 경로는 '서 있는 동안 새로 정한 목적지'가 아니다
        val e = engine(restroomMinAgo = 60, drivenMin = 0, tripStart = start - 40 * MIN, tripMoved = true)
        e.update(CoachInput(now = start - 1 * MIN, gear = Gear.PARK))
        parked(e, start - 50 * SEC, null)
        assertNull(askedWhileParked(e, start - 40 * SEC, 20, route(120.0, 100)))
        // 아이가 타고(문) 다른 목적지를 정하면 새 길이다 (화장실 정차로 세는 3분 전)
        parked(e, start, route(120.0, 100), door = true)
        assertNotNull(askedWhileParked(e, start + 10 * SEC, 40, route(90.0, 95, dest = mart)))
    }

    // ---------------------------------------------------------------- 감사 4: 새 목적지는 목적지로 가린다, 길에서 선 것은 정차가 아니다

    @Test
    fun aRerouteInAJamIsNotANewDestination() {
        // 고속도로 정체에서 P로 섰고 내비가 우회로로 재탐색했다 (남은 거리 +5km). 같은 목적지다
        val e = engine(restroomMinAgo = 50, drivenMin = 40)
        var t = start
        repeat(10) { drive(e, t, route(150.0, 200)); t += 10 * SEC }
        parked(e, t, route(150.0, 200)); t += 10 * SEC
        assertNull(askedWhileParked(e, t, 60, route(155.0, 215)), "재탐색은 새 목적지가 아니다")
    }

    @Test
    fun parkedOnTheRoadWithoutOpeningADoorIsNotAStopForDeparture() {
        // 달리다 신호에서 P로 두고 목적지를 넣었다. 아무도 내리지 않았다: 출발 전이 아니다
        val e = engine(restroomMinAgo = 50, drivenMin = 10)
        var t = start
        repeat(6) { drive(e, t, null, kph = 30f); t += 10 * SEC }
        parked(e, t, null); t += 10 * SEC
        assertNull(askedWhileParked(e, t, 60, route(48.0, 70)), "신호에서 P로 둔 것은 정차가 아니다")
    }

    @Test
    fun anErrandStopWithADoorAndANewDestinationAsksEvenAtASimilarDistance() {
        // 마트(주차 중 문 열림)에서 다음 목적지를 정했다. 남은 거리가 비슷해도(2km 안) 목적지가 다르면 새 길이다
        val e = engine(restroomMinAgo = 60, drivenMin = 15)
        var t = start
        repeat(6) { drive(e, t, route(49.0, 70, dest = mart), kph = 40f); t += 10 * SEC }
        parked(e, t, route(49.0, 70, dest = mart), door = true); t += 10 * SEC
        assertNotNull(askedWhileParked(e, t, 60, route(48.0, 70, dest = home)), "목적지가 바뀌었다")
    }

    // ---------------------------------------------------------------- 감사 6: 지금 다녀와도 별로 늘지 않으면 묻지 않는다

    @Test
    fun noNagRightAfterTheRestroom() {
        // 10분 전에 다녀온 아이(게이지 95분)에게 150분 길: 지금 다시 가도 10분밖에 늘지 않는다. 가는 길의 정차는 계획이 안내한다
        assertNull(askedWhileParked(engine(restroomMinAgo = 10), start, 60, route(200.0, 150)))
        // 방금 다녀온 어른 혼자, 170분 길
        assertNull(askedWhileParked(engine(listOf(adult("driver")), restroomMinAgo = 0), start, 60, route(250.0, 170)))
    }

    // ---------------------------------------------------------------- 감사 7: 0분이라고 말하지 않는다, 이미 말한 필요를 되풀이하지 않는다

    @Test
    fun aZeroGaugeIsNotSpokenAsZeroMinutes() {
        // 4살 아이, 마지막 화장실을 모른다(60분 전으로 본다): 게이지 0분
        val p = assertNotNull(askedWhileParked(engine(listOf(adult("driver"), child("kid", 4)), restroomMinAgo = 60), start, 30, route(15.0, 30)))
        assertFalse("0분쯤" in p.speech, p.speech)
        assertTrue("지금" in p.speech && "출발 전에 다녀오세요" in p.speech, p.speech)
    }

    @Test
    fun noDepartureAfterAVoicedNeedAtThisStop() {
        // "아이가 화장실 가고 싶대"에 이미 답했다. 30초 뒤 '출발 전에 다녀오세요'를 또 하지 않는다
        val e = engine()
        val o0 = parked(e, start, route(48.0, 70), intent = VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM))
        o0.prompt?.let { e.onPromptResult(it.id, true, start + SEC) }
        for (s in 2..120 step 2) assertNotEquals(PromptKind.DEPARTURE, parked(e, start + s * SEC, route(48.0, 70)).prompt?.kind, "${s}초")
    }

    // ---------------------------------------------------------------- 감사 9: 아기·반려견은 화장실이 아니다

    @Test
    fun anInfantIsAboutFeedingAndDiapers() {
        val p = assertNotNull(askedWhileParked(engine(listOf(adult("driver"), infant("baby")), restroomMinAgo = 60), start, 30, route(48.0, 70)))
        assertTrue(("수유" in p.speech || "기저귀" in p.speech) && "화장실을 찾을" !in p.speech, p.speech)
    }

    @Test
    fun aDogIsAboutAWalk() {
        // 운전자는 방금 다녀왔고 반려견은 2시간 전에 산책했다(게이지 30분): 130분 길
        val dog = Occupant("dog", OccupantType.DOG, label = "반려견")
        val e = CoachEngine(
            profile = TripProfile(
                listOf(adult("driver"), dog), tripStart = start,
                lastRestroomAt = mapOf("driver" to start, "dog" to start - 120 * MIN),
            ),
            random = Random(7),
        )
        val p = assertNotNull(askedWhileParked(e, start, 30, route(150.0, 130)))
        assertTrue("산책" in p.speech && "화장실을 찾을" !in p.speech, p.speech)
    }

    // ---------------------------------------------------------------- 감사 10: 끊긴 질문은 다시 묻는다

    @Test
    fun anInterruptedDepartureIsAskedAgain() {
        val e = engine()
        assertNotNull(askedWhileParked(e, start, 10, route(48.0, 70)))
        val o = parked(e, start + 20 * SEC, route(48.0, 70), intent = VoiceIntent.HowLong)
        assertEquals(PromptKind.INFO, o.prompt?.kind, "물음에 먼저 답한다")
        e.onPromptResult(o.prompt!!.id, true, start + 21 * SEC)
        assertNotNull(askedWhileParked(e, start + 30 * SEC, 120, route(48.0, 70)), "말에 끊긴 출발 전 질문은 다시 묻는다")
    }

    // ---------------------------------------------------------------- 감사 11: 질문이 제 이유를 싣는다

    @Test
    fun theDepartureQuestionCarriesItsReason() {
        val p = assertNotNull(askedWhileParked(engine(), start, 10, route(48.0, 70)))
        assertTrue(p.reasons.any { it.code == "DEPARTURE_TRIP" }, p.reasons.toString())
    }

    // ---------------------------------------------------------------- 감사 8: 움직이지 않은 기어 변경은 출발이 아니다

    @Test
    fun aGearBlipWithoutMovingKeepsTheCheck() {
        // P → D(0km/h) → P: 시연 전에 Virtual Console 기어가 D로 남아 있던 경우도 같다
        val e = engine()
        parked(e, start, null)
        drive(e, start + 10 * SEC, null, kph = 0f)
        drive(e, start + 20 * SEC, null, kph = 0f)
        assertNotNull(askedWhileParked(e, start + 30 * SEC, 60, route(48.0, 70)), "움직이지 않았으면 아직 출발 전이다")
    }

    // ---------------------------------------------------------------- 2026-11-13 검증

    @Test
    fun aVoicedNeedWhileBoardingTeachesNothing() {
        // 검증 5: 집 앞에서 "애가 화장실 가고 싶대": 출발 전 마지막 화장실은 가정한 60분 전이라 그 간격(60분)은 아이의 기록이 아니다
        val e = engine()
        parked(e, start, route(48.0, 70), intent = VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM))
        assertTrue(e.memory().restroomIntervalsMin.isEmpty(), "탈 때 말한 요청으로 배우지 않는다: ${e.memory().restroomIntervalsMin}")
        // 달리던 중에 말한 요청은 그대로 배운다
        val m = engine(restroomMinAgo = 90, drivenMin = 60)
        drive(m, start, null, kph = 80f)
        drive(m, start + SEC, null, kph = 80f).let { m.update(CoachInput(now = start + 2 * SEC, gear = Gear.DRIVE, speedKph = 80f, intent = VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM))) }
        assertTrue(m.memory().restroomIntervalsMin["kid"]?.isNotEmpty() == true, "달리는 중 요청: ${m.memory().restroomIntervalsMin}")
    }
}
