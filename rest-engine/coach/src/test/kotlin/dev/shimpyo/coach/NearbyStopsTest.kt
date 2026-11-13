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
        val n = NearbyStops(index).current(here, 0f, highwayRoute = false, speedKph = 40f, now = 0L, elapsedMs = 0L)
        assertEquals("화장실이 있고 차를 세울 수 있는 일상 정차 후보만, 가까운 순", listOf("park", "lot", "gas"), n.map { it.id })
        assertEquals(600.0, n.first().straightDistanceM!!, 2.0)
    }

    @Test
    fun onAHighwayItIsEmpty() {
        val s = NearbyStops(index)
        assertTrue("길안내 경로 위에 휴게소가 보인다", s.current(here, 0f, highwayRoute = true, speedKph = 40f, now = 0L, elapsedMs = 0L).isEmpty())
        assertTrue("시속 80km 이상", s.current(here, 0f, highwayRoute = false, speedKph = 95f, now = 0L, elapsedMs = 0L).isEmpty())
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
        s.current(here, 0f, false, 40f, 0L, 0L)
        val moved = Fix(here.lat + 100.0 / 111_000.0, here.lon, 0f) // 100m 북쪽
        val n = s.current(moved, 0f, false, 40f, 0L, 5_000L)
        assertEquals("100m·5초로는 다시 찾지 않는다", 1, counting.searches)
        assertEquals("거리는 지금 위치로 다시 잰다", 500.0, n.first().straightDistanceM!!, 2.0)
        s.current(Fix(here.lat + 300.0 / 111_000.0, here.lon, 0f), 0f, false, 40f, 0L, 8_000L)
        assertEquals("250m 넘게 움직이면 다시 찾는다", 2, counting.searches)
        s.current(Fix(here.lat + 300.0 / 111_000.0, here.lon, 0f), 0f, false, 40f, 0L, 30_000L)
        assertEquals("20초가 지나면 다시 찾는다", 3, counting.searches)
    }

    @Test
    fun withoutDataOrPositionThereIsNothing() {
        assertTrue(NearbyStops(FakeStopIndex(emptyList(), loaded = false)).current(here, 0f, false, 40f, 0L, 0L).isEmpty())
        assertTrue(NearbyStops(index).current(null, null, false, 40f, 0L, 0L).isEmpty())
    }
}
