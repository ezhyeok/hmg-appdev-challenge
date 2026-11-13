package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 출발 전 한마디 (일상 확장, 2026-11-13). 고속도로 휴게소만이 아니라 등원·학원·출퇴근·나들이처럼 매일의 주행에서 쓰이게,
 * 차가 서 있고(기어 P) 목적지가 정해졌을 때 도착까지 걸리는 시간이 화장실 게이지(가장 먼저 필요해지는 사람이 필요할 확률 50%)보다
 * 길면 출발 전에 다녀오라고 한 번 묻는다. 경로 위 화장실을 몰라도 된다: 내비의 남은 시간(정체 포함)과 사람 모델만 쓴다.
 * 7살 아이의 게이지는 화장실 뒤 105분에서 줄어든다 (1시간 전에 다녀왔으면 45분).
 *
 * 언제 묻나: 이번 여정에서 아직 출발하지 않았거나, 서 있는 동안 새 목적지를 정했을 때. 장거리 중 휴게소에 선 것만으로는 묻지 않는다
 * (그때는 계획 화면이 "여기서 쉬고 출발하세요"라고 한다, PlanFromHereTest). 한 정차에 한 번, 출발하면 거둔다.
 */
class DepartureCheckTest {
    private val km = 1_000.0
    private val start = kst(2026, 11, 13, 8, 10)

    /** 운전자 + 7살 아이. 모두 [restroomMinAgo]분 전에 화장실에 다녀왔고, 이번 여정에서 [drivenMin]분 달렸다 */
    private fun engine(restroomMinAgo: Int, drivenMin: Int = 0) = CoachEngine(
        profile = TripProfile(
            listOf(adult("driver"), child("kid", 7)),
            tripStart = start - drivenMin * MIN,
            lastRestroomAt = mapOf("driver" to start - restroomMinAgo * MIN, "kid" to start - restroomMinAgo * MIN),
            continuousDrivingMin = drivenMin.toDouble(),
        ),
        random = Random(7),
    )

    private fun route(remainKm: Double, tripMin: Int) =
        RouteState(guiding = true, remainDistanceM = remainKm * km, remainTimeS = tripMin * 60.0)

    private fun parked(e: CoachEngine, at: Long, route: RouteState?, door: Boolean = false) =
        e.update(CoachInput(now = at, gear = Gear.PARK, speedKph = 0f, route = route, doorOpened = door))

    @Test
    fun beforeALongTripTheChildIsToldToGoFirst() {
        // 아침 등원·나들이: 아이가 1시간 전에 다녀왔고(게이지 45분) 도착까지 70분(정체 포함)
        val out = parked(engine(restroomMinAgo = 60), start, route(48.0, 70))
        val p = assertNotNull(out.prompt, "출발 전에 묻는다")
        assertEquals(PromptKind.DEPARTURE, p.kind)
        assertTrue("출발 전" in p.speech && "1시간 10분" in p.speech && "7살 아이" in p.speech && "45분" in p.speech, p.speech)
        assertEquals(null, p.stop, "정차지를 묻는 질문이 아니다 (경유지를 넣지 않는다)")
    }

    @Test
    fun aShortTripOrNoDestinationIsLeftAlone() {
        assertEquals(null, parked(engine(restroomMinAgo = 60), start, route(12.0, 30)).prompt, "30분 길은 게이지(45분) 안이다")
        assertEquals(null, parked(engine(restroomMinAgo = 110), start, route(8.0, 15)).prompt, "아이가 지금 가야 해도 15분 길에는 묻지 않는다 (가다가 세울 곳이 많다)")
        assertEquals(null, parked(engine(restroomMinAgo = 60), start, null).prompt, "목적지가 없으면 도착 시간을 모른다")
        assertEquals(null, parked(engine(restroomMinAgo = 60), start, RouteState(guiding = false)).prompt)
    }

    @Test
    fun justBackFromTheRestroomThereIsNothingToSay() {
        // 방금 다녀왔다: 아이의 게이지(105분)가 90분 길보다 길다
        assertEquals(null, parked(engine(restroomMinAgo = 0), start, route(60.0, 90)).prompt)
    }

    @Test
    fun aRestAreaStopInTheMiddleOfATripDoesNotAskButANewDestinationDoes() {
        // 110분 달려 휴게소에 섰다 (길안내는 그대로). 아이는 지금 가야 하지만 그 안내는 계획 화면이 한다: 여기서는 묻지 않는다
        val e = engine(restroomMinAgo = 110, drivenMin = 110)
        assertNotEquals(PromptKind.DEPARTURE, parked(e, start, route(200.0, 150)).prompt?.kind)
        assertNotEquals(PromptKind.DEPARTURE, parked(e, start + 20 * SEC, route(200.0, 150)).prompt?.kind)
        // 서 있는 동안 새 목적지(남은 거리가 바뀌었다): 이제부터의 길이다
        assertEquals(PromptKind.DEPARTURE, parked(e, start + 40 * SEC, route(60.0, 70)).prompt?.kind)
    }

    @Test
    fun startingToDriveWithdrawsIt() {
        val e = engine(restroomMinAgo = 60)
        assertEquals(PromptKind.DEPARTURE, parked(e, start, route(48.0, 70)).prompt?.kind)
        val out = e.update(CoachInput(now = start + 5 * SEC, gear = Gear.DRIVE, speedKph = 12f, route = route(48.0, 70)))
        assertNotEquals(PromptKind.DEPARTURE, out.prompt?.kind, "달리기 시작하면 출발 전 질문은 뜻이 없다")
    }

    @Test
    fun oneAskPerStopAndADeclineIsRespected() {
        val e = engine(restroomMinAgo = 60)
        val p = assertNotNull(parked(e, start, route(48.0, 70)).prompt)
        e.onPromptResult(p.id, false, start + 3 * SEC)
        for (s in 1..12) assertNotEquals(PromptKind.DEPARTURE, parked(e, start + (5 + s * 10) * SEC, route(48.0, 70)).prompt?.kind, "거절했으면 이 정차에서 다시 묻지 않는다")
    }

    @Test
    fun goingToTheRestroomBeforeLeavingClearsIt() {
        // 수락하고 다녀왔다(문 열림, 서 있은 지 3분 넘음): 화장실 시계가 돌아가 다시 묻지 않는다
        val e = engine(restroomMinAgo = 60)
        val p = assertNotNull(parked(e, start, route(48.0, 70)).prompt)
        e.onPromptResult(p.id, true, start + 3 * SEC)
        parked(e, start + 10 * SEC, route(48.0, 70), door = true)
        for (m in 1..5) parked(e, start + m * MIN, route(48.0, 70))
        assertTrue(e.update(CoachInput(now = start + 6 * MIN, gear = Gear.PARK, route = route(48.0, 70))).restroom.minutes!! > 70, "다녀온 뒤에는 70분 길이 여유 안이다")
    }
}
