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
            override fun nearUsable(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>, now: Long, limit: Int): List<StopCandidate> {
                searches++
                return index.nearUsable(lat, lon, heading, radiusM, kinds, now, limit)
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
        assertEquals("주차 불가·닫힘·뒤쪽은 빼고 가까운 순", listOf("ok") + (1..9).map { "more$it" }, n.map { it.id })
    }

    @Test
    fun theRoadLimitIsTrustedOnlyWhileFresh() {
        val l = RoadLimit(80, atMs = 10_000L)
        assertEquals(80, l.fresh(12_000L))
        assertEquals("내비가 5초 넘게 보내지 않았다", null, l.fresh(10_000L + RoadLimit.FRESH_MS + 1))
        assertEquals("0은 모름", null, RoadLimit(0, 10_000L).fresh(10_500L))
        assertEquals("받은 때보다 이른 시각(시계가 뒤로 감)", null, l.fresh(9_000L))
    }

    @Test
    fun aPassedStopLeavesBeforeTheNextRefresh() {
        // 감사 5: 검색 사이(250m·20초)에 지나친 곳이 '진행 방향'으로 남아 "100m 앞 … 들를까요?"라고 물을 수 있었다
        val s = NearbyStops(index)
        assertEquals("park", s.current(here, 0f, false, 40f, null, 0L, 0L).first().id)
        val past = Fix(here.lat + 700.0 / 111_000.0, here.lon, 0f) // 정자공원(600m)을 100m 지나쳤다. 검색은 아직이다(5초)
        val n = s.current(past, 0f, false, 40f, null, 0L, 5_000L)
        assertTrue("지나친 곳은 뺀다: ${n.map { it.id }}", n.none { it.id == "park" })
        // 서 있으면 빼지 않는다 (걸어서 간다)
        assertTrue(s.current(past, 0f, false, 0f, null, 0L, 6_000L).any { it.id == "park" })
    }

    @Test
    fun atAHighwayRestAreaNoVillagePlacesAreOffered() {
        // 감사 6B: 길안내 중 휴게소 주차장(차로 밖이라 지도 맞춤이 고속도로로 보지 않는다)에 서면 울타리 밖 마을 화장실을 냈다
        val atRestArea = FakeStopIndex(
            listOf(StopRow(StopCandidate("ra", "안성휴게소", StopKind.HIGHWAY_REST_AREA, here.lat + 100.0 / 111_000.0, here.lon, Facilities(), parkable = true))) +
                listOf(row("village", 1_600.0)),
        )
        assertTrue(NearbyStops(atRestArea).current(here, 0f, false, 0f, null, 0L, 0L).isEmpty())
    }

    @Test
    fun theDemoSceneDecidesNotTheSignals() {
        // 감사 8: 시연 차속은 Virtual Console 값이고 시연 경로는 직선이다. 도심 장면은 늘, 고속도로 장면은 넣지 않는다
        val onMotorway = motorway { _, _, _ -> true }
        assertEquals("도심 시연: 시속 100이어도", 3, NearbyStops(index).current(here, 0f, false, 100f, 80, 0L, 0L, NearbyMode.ALWAYS).size)
        assertEquals("도심 시연: 지도 맞춤과 관계없이", 3, NearbyStops(onMotorway).current(here, 0f, false, 30f, null, 0L, 0L, NearbyMode.ALWAYS).size)
        assertTrue("고속도로 시연: 시속 60이어도 넣지 않는다", NearbyStops(index).current(here, 0f, false, 60f, null, 0L, 0L, NearbyMode.NEVER).isEmpty())
    }

    @Test
    fun denseDowntownStillHasUsablePlacesOnRealData() {
        // 감사 4 (실제 stops.bin): 잠실에서 서쪽으로 가면 가장 가까운 48곳이 뒤쪽·닫힘·주차 불가라 목록이 비었다 (3km 안 쓸 곳 45곳)
        val table = dev.shimpyo.engine.stops.StopTable.decode(java.io.File("../../app/src/main/assets/data/stops.bin").readBytes())
        val repo = StopRepository { table }.also { kotlinx.coroutines.runBlocking { it.ensureLoaded() } }
        val weekday14 = java.time.ZonedDateTime.of(2026, 11, 13, 14, 0, 0, 0, java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
        val n = NearbyStops(repo).current(Fix(37.5133, 127.1001, 270f), 270f, false, 30f, 50, weekday14, 0L)
        assertTrue("잠실 서행에도 쓸 곳이 있다: ${n.size}", n.size >= 6)
        assertTrue(n.all { it.parkable && it.facilities.restroom && it.openNow != false })
    }

    // ---------------------------------------------------------------- 2026-11-13 검증

    @Test
    fun parkedARestroomBehindTheLastHeadingIsKept() {
        // 검증 4: 150m 지나쳐 세운 차 (마지막 방향은 북쪽). 서 있으면 걸어서 가니 뒤쪽 화장실도 '가까이에 화장실이 있어요'의 후보다
        val rows = FakeStopIndex(listOf(row("behind", -150.0), row("ahead", 900.0)))
        // StopRepository.nearUsable처럼 방향을 주면 뒤쪽(50m 밖, 100도 넘게)을 뺀다
        val behind = object : StopIndex by rows {
            override fun nearUsable(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>, now: Long, limit: Int) =
                rows.nearUsable(lat, lon, null, radiusM, kinds, now, limit).filter { c ->
                    heading == null || Geo.distanceM(lat, lon, c.lat, c.lon) <= 50.0 ||
                        Geo.angleDiff(Geo.bearingDeg(lat, lon, c.lat, c.lon), heading.toDouble()) <= 100.0
                }
        }
        val n = NearbyStops(behind).current(here, 0f, false, 0f, null, 0L, 0L)
        assertTrue("서 있으면 뒤쪽도: ${n.map { it.id }}", n.any { it.id == "behind" })
        // 달리기 시작하면 다시 찾는다 (서 있을 때 찾은 목록을 들고 가지 않는다)
        assertTrue(NearbyStops(behind).current(here, 0f, false, 30f, null, 0L, 0L).none { it.id == "behind" })
        val s = NearbyStops(behind)
        s.current(here, 0f, false, 0f, null, 0L, 0L)
        assertTrue("출발하면 곧바로 다시 찾는다", s.current(here, 0f, false, 30f, null, 0L, 1_000L).none { it.id == "behind" })
    }

    @Test
    fun aTurnDoesNotDropAFarPlace() {
        // 검증 2: 좌회전하는 동안 800m 앞 장소가 진행 방향 뒤로 잠깐 들어간다. 지나친 곳은 바로 뒤(300m 안)뿐이다
        val s = NearbyStops(FakeStopIndex(listOf(row("far", 800.0))))
        assertEquals(listOf("far"), s.current(here, 0f, false, 30f, null, 0L, 0L).map { it.id })
        assertEquals("방향이 180도 돌아도 800m 떨어진 곳은 지나친 것이 아니다", listOf("far"), s.current(here, 180f, false, 30f, null, 0L, 2_000L).map { it.id })
    }
}
