package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 서 있는 곳에서 쉬고 출발하는 계획.
 *
 * 회귀 (2026-11-13 에뮬레이터 정차 시연): 안성휴게소에 서 있고 7살 아이는 지금 화장실에 가야 하는데, 계획이
 * "지금 쉬어야 할 때예요. 연곡졸음쉼터에 바로 들르세요"라고 했다. 서 있는 곳은 경로 거리 0이라 계획 후보(거리 > 0)에서 빠졌고,
 * 계획은 지금 필요(게이지 0)를 그대로 첫 구간에 넣었다. 실제 주행에서도 길안내 없이 휴게소에 서면 같은 일이 생긴다.
 *
 * 이제 기어 P이고 300m 안에 화장실이 있는 곳이 있으면(엔진의 '서 있는 곳', 식사 정차 판정과 같은 반경) 그곳에서 화장실(필요하면
 * 15분 휴식·식사)을 마치고 출발한다고 보고 그 뒤를 계획한다. 서는 동안 모두 화장실에 다녀온다고 보는 정차 기록 규칙과 같다.
 */
class PlanFromHereTest {
    private val km = 1_000.0
    private val start = kst(2026, 11, 11, 12, 40)

    /** 운전자 + 7살 아이. [restroomMinAgo]분 전에 모두 화장실에 다녀왔고 [drivenMin]분째 달렸다 */
    private fun engine(restroomMinAgo: Int, drivenMin: Int = 110) = CoachEngine(
        profile = TripProfile(
            listOf(adult("driver"), child("kid", 7)),
            tripStart = start - drivenMin * MIN,
            lastRestroomAt = mapOf("driver" to start - restroomMinAgo * MIN, "kid" to start - restroomMinAgo * MIN),
            continuousDrivingMin = drivenMin.toDouble(),
        ),
        random = Random(7),
    )

    private val anseong = stop("anseong", 0.0, name = "안성휴게소", facilities = Facilities(food = true, fuel = true))
    private val ahead = listOf(
        stop("yeongok", 7.4 * km, kind = StopKind.DROWSY_SHELTER, name = "연곡졸음쉼터"),
        stop("manghyang", 20 * km, name = "망향휴게소", facilities = Facilities(food = true)),
        stop("cheonan", 45 * km, name = "천안호두휴게소", facilities = Facilities(food = true)),
    )

    private fun plan(e: CoachEngine, gear: Gear, candidates: List<StopCandidate>, speed: Float = 0f) = e.plan(
        CoachInput(
            now = start, gear = gear, speedKph = speed,
            route = RouteState(guiding = true, remainDistanceM = 300 * km, remainTimeS = 12_000.0, candidatesUntilM = 55 * km),
            candidates = candidates,
        ),
    )

    @Test
    fun parkedAtARestAreaWithAnOverdueChildTheChildGoesHereNotAtTheNextShelter() {
        val p = plan(engine(restroomMinAgo = 110), Gear.PARK, listOf(anseong) + ahead)
        val here = p.notes.firstOrNull { it.code == "PLAN_HERE_NOW" }
        assertTrue(here != null && "안성휴게소" in here.text, "서 있는 곳에서 쉬고 출발하라고 한다: ${p.notes}")
        assertTrue(p.notes.none { it.code == "PLAN_NOW" }, "앞 졸음쉼터로 '바로 들르세요'라고 하지 않는다: ${p.notes}")
        assertTrue(p.stops.none { it.stop.id == "anseong" }, "계획의 정차는 출발한 뒤의 곳이다 (서 있는 곳에 경유지를 넣지 않는다)")
        // 아이가 여기서 다녀오면 첫 구간이 다시 길어져, 7.4km 앞 졸음쉼터에 곧바로 다시 설 이유가 없다
        assertTrue(p.stops.firstOrNull()?.stop?.id != "yeongok", "출발 직후 다시 서지 않는다: ${p.stops.map { it.stop.id }}")
    }

    @Test
    fun theNoteSaysWhatToFinishHereAndForHowLong() {
        // 110분 달렸다: 2시간 리듬의 휴식 표시(90분)를 넘었으니 여기서 15분 쉬고, 점심때(12:40)라 식사도 여기서 한다
        val note = plan(engine(restroomMinAgo = 110), Gear.PARK, listOf(anseong) + ahead).notes.first { it.code == "PLAN_HERE_NOW" }
        assertTrue("화장실" in note.text && "휴식" in note.text && "식사" in note.text, note.text)
        assertTrue("분" in note.text, "얼마나 쉬는지: ${note.text}")
    }

    @Test
    fun whenNothingIsDueYetThePlanStillCountsTheStopHereAndSaysSo() {
        // 아이가 40분 전에 다녀왔다: 지금 급하지 않다. 그래도 서 있는 동안 화장실에 다녀온다고 보고 세운 계획이라는 것을 알린다
        val p = plan(engine(restroomMinAgo = 40, drivenMin = 40), Gear.PARK, listOf(anseong) + ahead)
        assertTrue(p.notes.any { it.code == "PLAN_HERE" && "안성휴게소" in it.text }, p.notes.toString())
        assertTrue(p.notes.none { it.code == "PLAN_HERE_NOW" || it.code == "PLAN_NOW" }, p.notes.toString())
    }

    @Test
    fun parkedWhereThereIsNoRestroomTheNearestOneIsStillNow() {
        // 화장실 없는 곳(갓길·주차장)에 섰다: 서 있는 곳이 아니다. 지금 필요하면 가장 가까운 화장실에 바로 들르라고 한다 (예전 동작)
        val lot = stop("lot", 0.0, kind = StopKind.OTHER, name = "공영주차장", facilities = Facilities(restroom = false))
        val p = plan(engine(restroomMinAgo = 110), Gear.PARK, listOf(lot) + ahead)
        assertTrue(p.notes.none { it.code.startsWith("PLAN_HERE") }, p.notes.toString())
        assertTrue(p.notes.any { it.code == "PLAN_NOW" && "연곡졸음쉼터" in it.text }, p.notes.toString())
    }

    @Test
    fun drivingNextToARestAreaIsNotStandingThere() {
        // 주행 중에는 300m 안의 휴게소도 '서 있는 곳'이 아니다
        val p = plan(engine(restroomMinAgo = 110), Gear.DRIVE, listOf(stop("anseong", 0.2 * km, name = "안성휴게소")) + ahead, speed = 90f)
        assertTrue(p.notes.none { it.code.startsWith("PLAN_HERE") }, p.notes.toString())
    }

    @Test
    fun aClosedRestroomNearbyIsNotARestroomHere() {
        val closed = stop("anseong", 0.0, name = "안성휴게소", openNow = false)
        val p = plan(engine(restroomMinAgo = 110), Gear.PARK, listOf(closed) + ahead)
        assertTrue(p.notes.none { it.code.startsWith("PLAN_HERE") }, p.notes.toString())
    }

    @Test
    fun withNothingAheadTheNoteStillSaysRestHere() {
        val p = plan(engine(restroomMinAgo = 110), Gear.PARK, listOf(anseong))
        assertTrue(p.notes.any { it.code == "PLAN_HERE_NOW" }, p.notes.toString())
        assertTrue(p.stops.isEmpty())
        assertNull(p.stops.firstOrNull())
    }

    @Test
    fun theStopHereIsNotCountedInThePlannedStops() {
        val p = plan(engine(restroomMinAgo = 110), Gear.PARK, listOf(anseong) + ahead)
        assertEquals(p.stops.sumOf { it.dwellMin }, p.totalStopMin, "정차 시간 합은 출발 뒤의 정차만 센다")
        assertFalse(p.stops.any { it.arriveInS <= 0.0 }, "출발 뒤 정차의 도착 시간은 0보다 크다")
    }
}
