package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 잘린 후보 목록 (RouteState.candidatesUntilM). 회귀: 시연에서 경로 앞 후보를 몇 곳만 넣었더니 마지막 후보 뒤를
 * 목적지까지의 공백으로 봐서 '망향휴게소 · 이후 2시간 34분 공백'이라는 틀린 마지막 기회가 나왔다(2026-11-11 에뮬레이터).
 * 실제 길안내에서도 TBT 기회를 10곳까지만 넣으므로 같은 일이 생길 수 있었다.
 */
class CandidateHorizonTest {
    private val km = 1_000.0
    private val start = kst(2026, 11, 11, 15, 0)

    /** 어른 혼자, 마지막 화장실 90분 전 → 판단 마감이 약 22분 뒤(세 번째 후보 16분 뒤). 목적지는 250km(약 2시간 30분) 앞 */
    private fun output(candidatesUntilM: Double?): CoachOutput {
        val engine = CoachEngine(
            profile = TripProfile(listOf(adult("driver")), tripStart = start - 95 * MIN, lastRestroomAt = mapOf("driver" to start - 90 * MIN)),
            random = Random(7),
        )
        return engine.update(
            CoachInput(
                now = start,
                gear = Gear.DRIVE,
                speedKph = 100f,
                route = RouteState(guiding = true, remainDistanceM = 250 * km, remainTimeS = 9_000.0, nextManeuverDistanceM = 30 * km, candidatesUntilM = candidatesUntilM),
                // 앞 후보는 3곳만 넣었다 (실제로는 그 뒤에도 15km마다 휴게시설이 있다)
                candidates = listOf(
                    stop("a", 3.5 * km, name = "망향휴게소"),
                    stop("b", 15 * km, name = "천안호두휴게소"),
                    stop("c", 27 * km, name = "옥산휴게소"),
                ),
            ),
        )
    }

    @Test
    fun aCutListDoesNotInventAGapToTheDestination() {
        // 목록을 30km(네 번째 후보 자리)까지 넣었다고 알려 주면, 마지막 후보 뒤 공백은 3km(약 2분) 아래 한계뿐이다
        val withHorizon = output(candidatesUntilM = 30 * km)
        assertTrue(withHorizon.next?.reasons.orEmpty().none { it.code == "LAST_CHANCE_GAP" }, withHorizon.next.toString())
        assertNotEquals(PromptKind.LAST_CHANCE, withHorizon.prompt?.kind)
    }

    @Test
    fun thePlannerPlansOnlyWithinTheKnownRange() {
        val engine = CoachEngine(
            profile = TripProfile(listOf(adult("driver"), child("kid", 7)), tripStart = start - 110 * MIN, lastRestroomAt = mapOf("driver" to start - 20 * MIN, "kid" to start - 20 * MIN)),
            random = Random(7),
        )
        fun plan(until: Double?) = engine.plan(
            CoachInput(
                now = start, gear = Gear.PARK, speedKph = 0f,
                route = RouteState(guiding = true, remainDistanceM = 300 * km, remainTimeS = 12_000.0, candidatesUntilM = until),
                candidates = listOf(stop("a", 7 * km, name = "연곡졸음쉼터"), stop("b", 20 * km, name = "망향휴게소", facilities = Facilities(food = true)), stop("c", 45 * km, name = "천안호두휴게소", facilities = Facilities(food = true))),
            ),
        )
        val cut = plan(55 * km)
        assertTrue(cut.notes.none { it.code == "PLAN_GAP" }, "아는 범위 밖을 화장실 없는 구간으로 보지 않는다: ${cut.notes}")
        assertTrue(cut.notes.any { it.code == "PLAN_PARTIAL" && it.text.contains("55km") }, cut.notes.toString())
        // 다 넣은 목록이면(마지막 후보 뒤 255km에 화장실 없음) 예전처럼 공백을 알린다
        assertTrue(plan(null).notes.any { it.code == "PLAN_GAP" })
    }

    @Test
    fun anOverdueNeedSaysStopNowNotLongGap() {
        // 휴게소 100m 앞, 7살 아이는 이미 쉬어야 할 때가 지났다(마지막 화장실 110분 전). 가까운 곳에 들르는 것밖에 없다
        val engine = CoachEngine(
            profile = TripProfile(listOf(adult("driver"), child("kid", 7)), tripStart = start - 110 * MIN, lastRestroomAt = mapOf("driver" to start - 110 * MIN, "kid" to start - 110 * MIN)),
            random = Random(7),
        )
        val plan = engine.plan(
            CoachInput(
                now = start, gear = Gear.PARK, speedKph = 0f,
                route = RouteState(guiding = true, remainDistanceM = 300 * km, remainTimeS = 12_000.0, candidatesUntilM = 55 * km),
                candidates = listOf(stop("anseong", 0.1 * km, name = "안성휴게소", facilities = Facilities(food = true)), stop("b", 20 * km, name = "망향휴게소"), stop("c", 45 * km, name = "천안호두휴게소")),
            ),
        )
        assertTrue(plan.notes.none { it.code == "PLAN_GAP" }, plan.notes.toString())
        assertTrue(plan.notes.any { it.code == "PLAN_NOW" && it.text.contains("안성휴게소") }, plan.notes.toString())
    }

    @Test
    fun aCompleteListStillWarnsAboutARealGap() {
        // 목록이 목적지까지 다 넣은 것이면(null), 마지막 후보 뒤 2시간 넘는 공백은 진짜 공백이다 (예전 동작 그대로)
        val complete = output(candidatesUntilM = null)
        assertEquals("LAST_CHANCE_GAP", complete.next?.reasons?.single()?.code)
    }
}
