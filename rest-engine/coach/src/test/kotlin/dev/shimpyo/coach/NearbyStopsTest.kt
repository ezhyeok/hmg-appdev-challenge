package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.FakeStopIndex
import dev.shimpyo.coach.fakes.StopRow
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 일상 길의 가까운 정차 후보 (NearbyStops → 엔진 CoachInput.nearby, 2026-11-13 일상 확장) */
class NearbyStopsTest {
    private val here = Fix(37.3670, 127.1080, 0f) // 분당 정자동, 북쪽으로
    private fun row(id: String, dLatM: Double, kind: StopKind = StopKind.PUBLIC_RESTROOM, restroom: Boolean = true, parkable: Boolean = true) =
        StopRow(StopCandidate(id, id, kind, here.lat + dLatM / 111_000.0, here.lon, facilities = Facilities(restroom = restroom), parkable = parkable))

    private val index = FakeStopIndex(
        listOf(
            row("park", 600.0),
            row("gas", 1_200.0, kind = StopKind.GAS_STATION),
            row("lot", 900.0, kind = StopKind.PUBLIC_PARKING),
            row("rest-area", 1_500.0, kind = StopKind.HIGHWAY_REST_AREA),
            row("no-wc", 300.0, restroom = false),
            row("no-park", 400.0, parkable = false),
            row("far", 5_000.0),
        ),
    )

    @Test
    fun cityDrivingGetsTheNearbyEverydayStopsInOrder() {
        val n = NearbyStops(index).current(here, 0f, highwayRoute = false, speedKph = 40f, roadLimitKph = null, now = 0L, elapsedMs = 0L)
        assertEquals("화장실이 있고 차를 세울 수 있는 일상 정차 후보만, 가까운 순", listOf("park", "lot", "gas"), n.map { it.id })
        assertEquals(600.0, n.first().straightDistanceM!!, 2.0)
    }

    @Test
    fun onAHighwayItIsEmpty() {
        val s = NearbyStops(index)
        assertTrue("방향을 모를 때(출발 전) 길안내 경로 위에 휴게소가 보인다", s.current(here, null, highwayRoute = true, speedKph = 0f, roadLimitKph = null, now = 0L, elapsedMs = 0L).isEmpty())
        assertTrue("시속 80km 이상", s.current(here, 0f, highwayRoute = false, speedKph = 95f, roadLimitKph = null, now = 0L, elapsedMs = 0L).isEmpty())
    }

    @Test
    fun theSearchRunsOnlyAfterMovingOrAWhileButDistancesFollowTheCar() {
        val counting = object : StopIndex by index {
            var searches = 0
            override fun near(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>, now: Long, limit: Int): List<StopCandidate> {
                searches++
                return index.near(lat, lon, heading, radiusM, kinds, now, limit)
            }
        }
        val s = NearbyStops(counting)
        s.current(here, 0f, false, 40f, null, 0L, 0L)
        val moved = Fix(here.lat + 100.0 / 111_000.0, here.lon, 0f) // 100m 북쪽
        val n = s.current(moved, 0f, false, 40f, null, 0L, 5_000L)
        assertEquals("100m·5초로는 다시 찾지 않는다", 1, counting.searches)
        assertEquals("거리는 지금 위치로 다시 잰다", 500.0, n.first().straightDistanceM!!, 2.0)
        s.current(Fix(here.lat + 300.0 / 111_000.0, here.lon, 0f), 0f, false, 40f, null, 0L, 8_000L)
        assertEquals("250m 넘게 움직이면 다시 찾는다", 2, counting.searches)
        s.current(Fix(here.lat + 300.0 / 111_000.0, here.lon, 0f), 0f, false, 40f, null, 0L, 30_000L)
        assertEquals("20초가 지나면 다시 찾는다", 3, counting.searches)
    }

    @Test
    fun withoutDataOrPositionThereIsNothing() {
        assertTrue(NearbyStops(FakeStopIndex(emptyList(), loaded = false)).current(here, 0f, false, 40f, null, 0L, 0L).isEmpty())
        assertTrue(NearbyStops(index).current(null, null, false, 40f, null, 0L, 0L).isEmpty())
    }

    // ---------------------------------------------------------------- 길가 화장실에 갈 수 없는 길 (2026-11-13)

    /** [onMotorwayAt]이 참이면 고속도로 차로 위라고 답하는 표 (지도 맞춤 대신) */
    private fun motorway(onMotorwayAt: (Double, Double, Float) -> Boolean) = object : StopIndex by index {
        override fun onMotorway(lat: Double, lon: Double, heading: Float): Boolean = onMotorwayAt(lat, lon, heading)
    }

    @Test
    fun onAMotorwayCarriagewayRoadsideRestroomsAreNotOffered() {
        // 고속도로 정체(시속 20km, 길안내 없음): 600m 옆 공중화장실은 울타리 밖이라 갈 수 없다. 방향은 서 있어도 마지막 방향이 온다
        val s = NearbyStops(motorway { _, _, _ -> true })
        assertTrue(s.current(here, 0f, highwayRoute = false, speedKph = 20f, roadLimitKph = null, now = 0L, elapsedMs = 0L).isEmpty())
        assertTrue("서 있어도", s.current(here, 0f, highwayRoute = false, speedKph = 0f, roadLimitKph = null, now = 0L, elapsedMs = 0L).isEmpty())
    }

    @Test
    fun cityStreetsBeforeTheExpresswayStillGetNearbyStops() {
        // 부산 가는 길안내(경로 위에 휴게소가 보인다)지만 아직 시내 도로다: 방향을 알면 경로가 아니라 지금 위치로 판단한다
        val s = NearbyStops(motorway { _, _, _ -> false })
        assertEquals(listOf("park", "lot", "gas"), s.current(here, 0f, highwayRoute = true, speedKph = 40f, roadLimitKph = 50, now = 0L, elapsedMs = 0L).map { it.id })
    }

    @Test
    fun anUrbanExpresswayIsToldByItsSpeedLimit() {
        // 강변북로(제한 80)·내부순환로(70) 정체: 지도 맞춤 자료(고속도로만)에 없는 길이다. 내비가 알려 주는 제한속도로 가린다
        val s = NearbyStops(index)
        assertTrue("제한 80", s.current(here, 0f, highwayRoute = false, speedKph = 15f, roadLimitKph = 80, now = 0L, elapsedMs = 0L).isEmpty())
        assertTrue("제한 70", s.current(here, 0f, highwayRoute = false, speedKph = 15f, roadLimitKph = 70, now = 0L, elapsedMs = 0L).isEmpty())
        assertEquals("시내 간선 60", 3, s.current(here, 0f, highwayRoute = false, speedKph = 15f, roadLimitKph = 60, now = 0L, elapsedMs = 0L).size)
        assertEquals("제한속도를 모르면 다른 조건만 본다", 3, s.current(here, 0f, highwayRoute = false, speedKph = 15f, roadLimitKph = null, now = 0L, elapsedMs = 0L).size)
    }

    @Test
    fun unusablePlacesAreDroppedBeforeTheListIsCut() {
        // 도심: 가장 가까운 화장실 열 곳이 주차 불가다. 8곳을 먼저 자르면 쓸 곳이 하나도 남지 않았다
        val crowded = FakeStopIndex(
            (1..10).map { row("wc$it", 100.0 * it, parkable = false) } +
                listOf(
                    row("closed", 1_050.0).let { StopRow(it.c.copy(openNow = false)) },
                    row("behind", 1_080.0).let { StopRow(it.c.copy(directionOk = false)) },
                    row("ok", 1_100.0),
                ) + (1..9).map { row("more$it", 1_200.0 + 100.0 * it) },
        )
        val n = NearbyStops(crowded).current(here, 0f, highwayRoute = false, speedKph = 30f, roadLimitKph = null, now = 0L, elapsedMs = 0L)
        assertEquals("주차 불가·닫힘·뒤쪽은 빼고 가까운 순 8곳", listOf("ok") + (1..7).map { "more$it" }, n.map { it.id })
    }

    @Test
    fun theRoadLimitIsTrustedOnlyWhileFresh() {
        val l = RoadLimit(80, atMs = 10_000L)
        assertEquals(80, l.fresh(12_000L))
        assertEquals("내비가 5초 넘게 보내지 않았다", null, l.fresh(10_000L + RoadLimit.FRESH_MS + 1))
        assertEquals("0은 모름", null, RoadLimit(0, 10_000L).fresh(10_500L))
        assertEquals("받은 때보다 이른 시각(시계가 뒤로 감)", null, l.fresh(9_000L))
    }
}
