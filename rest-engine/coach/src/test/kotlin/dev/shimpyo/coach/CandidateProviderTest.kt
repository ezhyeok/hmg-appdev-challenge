package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.FakeStopIndex
import dev.shimpyo.coach.fakes.FakeTripEstimator
import dev.shimpyo.coach.fakes.Road
import dev.shimpyo.coach.fakes.StopRow
import dev.shimpyo.coach.fakes.TripAnswer
import dev.shimpyo.coach.fakes.TripNaviPort
import dev.shimpyo.coach.fakes.stop
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.StopKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 정차 후보 만들기 (CandidateProvider). 시나리오 표는 docs/design/coach-layer/candidates.md.
 * 회귀: 서비스 감사 D5(길안내 중 반경 후보에 범위가 없어 가짜 '마지막 기회'), D6(같은 이름 휴게소가 요청 실패 때 반대 방향으로),
 * 데이터 감사 #5·#7, 엔진 감사 '자른 목록을 완전한 목록으로 봄', UI 감사 #19(ahead가 20초마다만 움직임), 46981d8(지어낸 etaS),
 * 닫힘 검증 SV-N1(느려지면 같은 휴게소의 후보 id가 데이터 행에서 tbt:로 바뀌어 수락한 정차를 다시 물었다).
 */
class CandidateProviderTest {

    private class Fx(scope: TestScope, rows: List<StopRow>, val oracle: FakeTripEstimator = FakeTripEstimator()) {
        val clock = ElapsedClock { scope.testScheduler.currentTime }
        val odometer = Odometer(clock).also { it.advance(0f) }
        val stops = FakeStopIndex(rows)
        val provider = CandidateProvider(stops, oracle, odometer, clock)
        val snap: CandidateSnap get() = provider.snapshot.value
        val ids: List<String> get() = snap.list.map { it.id }

        /**
         * 이번 epoch를 한 번 보여 주고 TBT 기다림이 끝날 때까지 시간을 흘린다 (처음 보는 epoch는 그때부터 기다린다:
         * 앱을 켰을 때 이미 길안내 중이어도 TBT부터 기다린다)
         */
        suspend fun pastTbtWait(navi: NaviSnapshot) {
            provider.refresh(Road.car(), navi, CandidatePolicy.REAL)
            delay(CandidateTuning.ROUTE_TBT_WAIT_MS)
        }

        /** 차를 [m]만큼 움직인다 (1초에 최대 100m, 가상 시간이 흐른다) */
        suspend fun drive(m: Double) {
            odometer.advance(0f) // 그 전까지는 서 있었다 (주행 거리 적분은 직전 호출부터 잰다)
            var left = m
            while (left > 1e-9) {
                val step = minOf(left, 100.0)
                delay(1_000)
                odometer.advance((step * 3.6).toFloat())
                left -= step
            }
        }
    }

    private fun opp(name: String, km: Double, kind: StopKind = StopKind.HIGHWAY_REST_AREA) = RouteOpportunity(name, kind, km * 1_000.0)

    private fun guiding(
        opps: List<RouteOpportunity>,
        remainM: Double = 300_000.0,
        epoch: Int = 1,
        received: Boolean = true,
        coverageM: Double? = remainM,
        trusted: Boolean = true,
        tbtEpoch: Int = epoch,
        odometerM: Double = 0.0,
    ) = NaviSnapshot(
        epoch = epoch, mode = NaviMode.ROUTE_GUIDING, route = RouteState(guiding = true, remainDistanceM = remainM),
        routeOdometerM = odometerM, opportunities = opps, tbtReceived = received, tbtCoverageM = if (received) coverageM else null,
        tbtOdometerM = odometerM, tbtEpoch = tbtEpoch, odometerTrusted = trusted,
    )

    private val notGuiding = NaviSnapshot(epoch = 0, mode = NaviMode.SAFE_GUIDING, route = RouteState(guiding = false))

    // 경부선 남행: 안성휴게소는 양방향에 같은 이름으로 있다 (서울방향은 270m 동쪽, 차로 방위 0)
    private val anseongBusan = stop("anseong-busan", "안성휴게소(부산방향)", 45.0, laneHeading = 180f)
    private val anseongSeoul = stop("anseong-seoul", "안성휴게소(서울방향)", 45.0, laneHeading = 0f, lonOffset = 0.003)

    /** 남쪽으로 6km마다 하나씩 9곳 (반경 검색용) */
    private val nine = (1..9).map { stop("s$it", "시설${it}휴게소", it * 6.0) }

    // ------------------------------------------------------------ 길안내 TBT 경로

    @Test
    fun routeTbtNameMatchesTheDataRow() = runTest {
        val fx = Fx(this, listOf(stop("mh", "망향휴게소(부산방향)", 30.0)))
        fx.provider.refresh(Road.car(), guiding(listOf(opp("망향휴게소", 31.0))), CandidatePolicy.REAL)
        val s = fx.snap
        assertEquals(CandidatesState.READY, s.state)
        assertEquals(CandidateSource.ROUTE, s.source)
        assertEquals(1, s.naviEpoch)
        val c = s.list.single()
        assertEquals("데이터 행의 좌표·편의시설을 쓴다", "mh", c.id)
        assertEquals("거리는 TBT 누적 거리", 31_000.0, c.routeDistanceM!!, 1e-6)
        assertEquals("경로 위라 방향이 맞다", true, c.directionOk)
        assertNull("TBT는 ETA를 주지 않는다: 지어내지 않는다 (46981d8)", c.etaS)
        assertNull("TBT가 목적지까지 닿고 10곳 이하: 완전한 목록", s.untilM)
        assertTrue("이름이 하나면 내비에 묻지 않는다", fx.oracle.calls.isEmpty())
    }

    @Test
    fun laneHeadingPicksTheSameNameRowWithoutANaviRequest() = runTest {
        val fx = Fx(this, listOf(anseongSeoul, anseongBusan))
        fx.provider.refresh(Road.car(heading = 180f), guiding(listOf(opp("안성휴게소", 47.0))), CandidatePolicy.REAL)
        assertEquals("남행이면 부산방향", listOf("anseong-busan"), fx.ids)
        assertTrue("차로 방위로 골랐으니 requestTrip을 보내지 않는다 (데이터 감사 #5)", fx.oracle.calls.isEmpty())
    }

    @Test
    fun withoutHeadingAFailedRequestIsUnknownNotTheFirstGridMatch() = runTest {
        // 예전: 둘 다 실패하면 거리를 MAX로 보고 minBy가 첫 행(서울방향)을 골랐다 → 반대 방향 경유지 (D6)
        val fx = Fx(this, listOf(anseongSeoul, anseongBusan), FakeTripEstimator(TripAnswer.Unknown))
        fx.provider.refresh(Road.car(heading = null), guiding(listOf(opp("안성휴게소", 47.0))), CandidatePolicy.REAL)
        val c = fx.snap.list.single()
        assertEquals("방향을 모르면 좌표 없는 경로 위 시설로 둔다 (수락하면 내비 검색)", "tbt:안성휴게소", c.id)
        assertEquals(true, c.directionOk)
        assertEquals(2, fx.oracle.calls.size)
    }

    @Test
    fun withoutHeadingTheNaviDistanceClosestToTheTbtWins() = runTest {
        val oracle = FakeTripEstimator().apply {
            answers["anseong-busan"] = TripAnswer.Route(47_500.0)
            answers["anseong-seoul"] = TripAnswer.Route(57_100.0) // 유턴 경로 (2026-11-11 측정값)
        }
        val fx = Fx(this, listOf(anseongSeoul, anseongBusan), oracle)
        fx.provider.refresh(Road.car(heading = null), guiding(listOf(opp("안성휴게소", 47.0))), CandidatePolicy.REAL)
        val c = fx.snap.list.single()
        assertEquals("anseong-busan", c.id)
        assertEquals("거리는 여전히 TBT 값", 47_000.0, c.routeDistanceM!!, 1e-6)
        assertNull("requestTrip ETA를 섞지 않는다 (목록 안 ETA 출처를 하나로)", c.etaS)
    }

    @Test
    fun onlyTheOppositeRowAnsweringIsRejectedAsImplausible() = runTest {
        val oracle = FakeTripEstimator().apply {
            answers["anseong-busan"] = TripAnswer.Unknown
            answers["anseong-seoul"] = TripAnswer.Route(57_100.0)
        }
        val fx = Fx(this, listOf(anseongSeoul, anseongBusan), oracle)
        fx.provider.refresh(Road.car(heading = null), guiding(listOf(opp("안성휴게소", 47.0))), CandidatePolicy.REAL)
        assertEquals("TBT 47km와 10km 차이: 이 행이 아니다", listOf("tbt:안성휴게소"), fx.ids)
    }

    @Test
    fun unknownNameBecomesACoordinateLessTbtCandidate() = runTest {
        val fx = Fx(this, listOf(anseongBusan))
        val car = Road.car()
        fx.provider.refresh(car, guiding(listOf(opp("새길휴게소", 12.0))), CandidatePolicy.REAL)
        val c = fx.snap.list.single()
        assertEquals("tbt:새길휴게소", c.id)
        assertEquals(12_000.0, c.routeDistanceM!!, 1e-6)
        assertEquals(true, c.directionOk)
        assertNull(c.etaS)
        assertEquals("좌표는 차 위치로 채운다 (거리는 routeDistanceM만 쓴다)", car.lat, c.lat, 1e-9)
        assertTrue("휴게소면 먹을 곳이 있다", c.facilities.food)
    }

    @Test
    fun aSameNameFacilityFarCloserThanTheTbtDistanceIsNotIt() = runTest {
        // 같은 이름이 다른 노선에 있다 (대신졸음쉼터: 경부선과 광주원주선). 직선 5km인데 TBT는 40km 앞이라고 한다
        val fx = Fx(this, listOf(stop("daesin-other", "대신졸음쉼터", 5.0, kind = StopKind.DROWSY_SHELTER, laneHeading = null)))
        fx.provider.refresh(Road.car(), guiding(listOf(opp("대신졸음쉼터", 40.0, StopKind.DROWSY_SHELTER))), CandidatePolicy.REAL)
        assertEquals(listOf("tbt:대신졸음쉼터"), fx.ids)
    }

    @Test
    fun serviceAreaLabelledShelterMatchesTheShelterNotTheRestAreaWithTheSameBaseName() = runTest {
        // TBT가 졸음쉼터를 SERVICEAREA로 보내도 같은 기본 이름의 반대편 휴게소와 맞으면 안 된다 (데이터 감사 #5).
        // 종류는 extract가 이름의 시설어로 정한다 (StopNames.kindFor, 이름 규칙은 한 곳): TBT 응답에서 시작해 그 길을 그대로 탄다
        val rows = listOf(
            stop("oksan-rest", "옥산휴게소(서울방향)", 20.3, laneHeading = 0f, lonOffset = 0.003),
            stop("oksan-shelter", "옥산졸음쉼터(부산방향)", 20.0, kind = StopKind.DROWSY_SHELTER, laneHeading = 180f),
        )
        val fx = Fx(this, rows)
        val fromTbt = RouteOpportunities.extract(listOf(TbtItem(TbtType.SERVICE_AREA, 21_000.0, "옥산졸음쉼터(부산방향)")))
        assertEquals(StopKind.DROWSY_SHELTER, fromTbt.single().kind)
        fx.provider.refresh(Road.car(heading = null), guiding(fromTbt), CandidatePolicy.REAL)
        val c = fx.snap.list.single()
        assertEquals("oksan-shelter", c.id)
        assertEquals(StopKind.DROWSY_SHELTER, c.kind)
    }

    @Test
    fun moreThanTenOpportunitiesSetTheHorizonAtTheEleventh() = runTest {
        val fx = Fx(this, emptyList())
        val opps = (1..12).map { opp("휴게${it}휴게소", it * 10.0) }
        fx.provider.refresh(Road.car(), guiding(opps, remainM = 300_000.0), CandidatePolicy.REAL)
        assertEquals(CandidateTuning.ROUTE_OPP_MAX, fx.snap.list.size)
        assertEquals("11번째 기회까지는 넣은 것 말고 없다", 110_000.0, fx.snap.untilM!!, 1e-6)
    }

    @Test
    fun aTbtListShorterThanTheRouteSetsTheHorizonAtItsCoverage() = runTest {
        // 데이터 감사 #7, 서비스 감사 P5: 10곳 이하라고 완전한 목록이 아니다
        val fx = Fx(this, emptyList())
        val opps = (1..4).map { opp("휴게${it}휴게소", it * 10.0) }
        fx.provider.refresh(Road.car(), guiding(opps, remainM = 300_000.0, coverageM = 80_000.0), CandidatePolicy.REAL)
        assertEquals(4, fx.snap.list.size)
        assertEquals(80_000.0, fx.snap.untilM!!, 1e-6)

        fx.provider.refresh(Road.car(), guiding(opps, remainM = 300_000.0, coverageM = 299_500.0), CandidatePolicy.REAL)
        assertNull("마지막 안내가 목적지 직전이면 완전한 목록", fx.snap.untilM)
    }

    @Test
    fun anEmptyTbtMeansNoRestStopsOnTheRoute() = runTest {
        // D5b: TBT에 휴게시설이 없는데 반경 검색의 다른 노선 시설을 경로 위 기회로 넣으면 가짜 '마지막 기회'가 나온다
        val fx = Fx(this, nine, FakeTripEstimator(TripAnswer.Route(10_000.0)))
        fx.provider.refresh(Road.car(), guiding(emptyList(), remainM = 150_000.0), CandidatePolicy.REAL)
        val s = fx.snap
        assertEquals(CandidatesState.READY, s.state)
        assertTrue(s.list.isEmpty())
        assertNull("목적지까지 TBT가 닿았으니 정말 없다", s.untilM)
        assertEquals("반경 검색을 하지 않는다", 0, fx.stops.aheadCalls)
        assertTrue(fx.oracle.calls.isEmpty())
    }

    @Test
    fun nonFiniteTbtDistancesAreIgnored() = runTest {
        val fx = Fx(this, emptyList())
        fx.provider.refresh(Road.car(), guiding(listOf(opp("이상한휴게소", Double.NaN), opp("정상휴게소", 10.0))), CandidatePolicy.REAL)
        assertEquals(listOf("tbt:정상휴게소"), fx.ids)
    }

    @Test
    fun repeatedTbtNamesGetDistinctIds() = runTest {
        // 이름 없는 졸음쉼터가 '졸음쉼터'로만 두 번 올 수 있다. 같은 id면 엔진·화면이 둘을 섞는다
        val fx = Fx(this, emptyList())
        fx.provider.refresh(Road.car(), guiding(listOf(opp("졸음쉼터", 5.0, StopKind.DROWSY_SHELTER), opp("졸음쉼터", 15.0, StopKind.DROWSY_SHELTER))), CandidatePolicy.REAL)
        assertEquals(listOf("tbt:졸음쉼터", "tbt:졸음쉼터#2"), fx.ids)
    }

    // ------------------------------------------------------------ 한 번 고른 행은 그 경로에서 그 행이다 (SV-N1)

    @Test
    fun aRowConfirmedByTheLaneKeepsItsIdWhenTheHeadingIsLostAndTheNaviCannotMeasure() = runTest {
        // 달릴 때 차로 방위로 고른 행이, 정체에 들어 방위를 모르게 되고(P·저속) 내비도 못 재는 동안 좌표 없는 tbt:로 바뀌었다.
        // 엔진은 수락한 정차를 id로 따라가므로 60초 뒤 잊고 이미 경유지로 넣은 휴게소를 다시 물었다
        val fx = Fx(this, listOf(anseongSeoul, anseongBusan), FakeTripEstimator(TripAnswer.Unknown))
        val navi = guiding(listOf(opp("안성휴게소", 47.0)))
        fx.provider.refresh(Road.car(heading = 180f), navi, CandidatePolicy.REAL)
        assertEquals(listOf("anseong-busan"), fx.ids)

        repeat(3) { fx.provider.refresh(Road.car(heading = null), navi, CandidatePolicy.REAL) }
        assertEquals("방위를 잃어도 같은 후보다", listOf("anseong-busan"), fx.ids)
        assertTrue("이미 고른 행은 내비에 다시 묻지 않는다", fx.oracle.calls.isEmpty())
        assertEquals("직선거리는 지금 위치에서 다시 잰다", 45_000.0, fx.snap.list.single().straightDistanceM!!, 500.0)
    }

    @Test
    fun aRowChosenByNaviDistanceIsKeptWhenLaterRequestsFail() = runTest {
        val oracle = FakeTripEstimator().apply {
            answers["anseong-busan"] = TripAnswer.Route(47_500.0)
            answers["anseong-seoul"] = TripAnswer.Route(57_100.0)
        }
        val fx = Fx(this, listOf(anseongSeoul, anseongBusan), oracle)
        val navi = guiding(listOf(opp("안성휴게소", 47.0)))
        fx.provider.refresh(Road.car(heading = null), navi, CandidatePolicy.REAL)
        assertEquals(listOf("anseong-busan"), fx.ids)
        oracle.answers.clear() // 통신 음영: 내비가 더는 답하지 못한다
        oracle.calls.clear()
        fx.provider.refresh(Road.car(heading = null), navi, CandidatePolicy.REAL)
        assertEquals("내비 거리로 고른 행도 그 경로에서는 그 행이다", listOf("anseong-busan"), fx.ids)
        assertTrue(oracle.calls.isEmpty())
    }

    @Test
    fun aNewRouteOrModeResolvesAgain() = runTest {
        // 고른 행을 기억하는 것은 같은 경로(epoch) 안에서만이다: 되돌아가는 새 경로에서는 같은 이름이 반대편 시설일 수 있다
        val fx = Fx(this, listOf(anseongSeoul, anseongBusan), FakeTripEstimator(TripAnswer.Unknown))
        fx.provider.refresh(Road.car(heading = 180f), guiding(listOf(opp("안성휴게소", 47.0)), epoch = 1), CandidatePolicy.REAL)
        assertEquals(listOf("anseong-busan"), fx.ids)
        fx.provider.refresh(Road.car(heading = null), guiding(listOf(opp("안성휴게소", 47.0)), epoch = 2), CandidatePolicy.REAL)
        assertEquals("새 경로에서는 다시 고른다 (못 고르면 tbt:)", listOf("tbt:안성휴게소"), fx.ids)

        fx.provider.refresh(Road.car(heading = 180f), guiding(listOf(opp("안성휴게소", 47.0)), epoch = 2), CandidatePolicy.REAL)
        assertEquals("고를 수 있게 되면 데이터 행으로 올라간다", listOf("anseong-busan"), fx.ids)
        fx.provider.reset()
        fx.provider.refresh(Road.car(heading = null), guiding(listOf(opp("안성휴게소", 47.0)), epoch = 2), CandidatePolicy.REAL)
        assertEquals("모드 전환(reset)도 기억을 지운다", listOf("tbt:안성휴게소"), fx.ids)
    }

    @Test
    fun aRowPickedOnlyBecauseItsNameIsUniqueIsCheckedAgainOnceTheHeadingIsKnown() = runTest {
        // 데이터에 반대 차로 행만 있는 이름. 방위를 모를 때는 그 행뿐이라 임시로 쓰지만 확인한 것이 아니다:
        // 방위를 알면 차로 방위가 빼고, 그 뒤 방위를 다시 잃어도 뺀 행으로 돌아가지 않는다 (행 ↔ tbt:를 오가지 않는다)
        val fx = Fx(this, listOf(anseongSeoul), FakeTripEstimator(TripAnswer.Unknown))
        val navi = guiding(listOf(opp("안성휴게소", 47.0)))
        fx.provider.refresh(Road.car(heading = null), navi, CandidatePolicy.REAL)
        assertEquals("방위를 모르면 하나뿐인 행을 쓴다", listOf("anseong-seoul"), fx.ids)
        fx.provider.refresh(Road.car(heading = 180f), navi, CandidatePolicy.REAL)
        assertEquals("남행인 줄 알면 북행 차로 시설은 아니다", listOf("tbt:안성휴게소"), fx.ids)
        fx.provider.refresh(Road.car(heading = null), navi, CandidatePolicy.REAL)
        assertEquals("차로 방위가 뺀 행으로 되돌아가지 않는다", listOf("tbt:안성휴게소"), fx.ids)
    }

    @Test
    fun aRememberedRowIsNotUsedForAnotherFacilityWithTheSameName() = runTest {
        // 경로가 같은 이름을 두 번 지난다(내려갔다 돌아오는 경로). 기억한 행은 TBT 거리와 맞을 때만 쓴다
        val fx = Fx(this, listOf(anseongBusan))
        fx.provider.refresh(Road.car(heading = 180f), guiding(listOf(opp("안성휴게소", 47.0), opp("안성휴게소", 180.0))), CandidatePolicy.REAL)
        assertEquals(listOf("anseong-busan", "tbt:안성휴게소#2"), fx.ids)
        // 첫 번째를 지나 50km 지점: 남은 '안성휴게소'는 130km 앞인데 기억한 행은 5km 뒤에 있다
        fx.provider.refresh(Road.car(km = 50.0, heading = 180f), guiding(listOf(opp("안성휴게소", 130.0))), CandidatePolicy.REAL)
        assertEquals(listOf("tbt:안성휴게소"), fx.ids)
    }

    @Test
    fun aSameNamePairBeyondTheThirdIsStillResolvedWhenTheCarIsSlow() = runTest {
        // 닫힘 검증 SV-N1 (CandidateProvider.Budget): 갱신 하나의 requestTrip 한도(6)를 캐시가 바로 답한 것까지 셌다. 방위를 모를 때(P·저속)
        // 같은 이름 쌍은 두 번씩 쓰므로 앞 세 쌍만 고르고 네 번째부터는 내비가 멀쩡해도, 몇 번을 다시 만들어도 tbt: 후보로 남았다
        // (내비 요청은 처음 6번뿐이었다). 지금은 실제로 보낸 요청만 센다: 첫 갱신 6번, 다음 갱신에서 나머지 두 쌍 4번
        val rows = (1..5).flatMap { i ->
            listOf(
                stop("r$i-seoul", "휴게${i}휴게소(서울방향)", i * 10.0, laneHeading = 0f, lonOffset = 0.003),
                stop("r$i-busan", "휴게${i}휴게소(부산방향)", i * 10.0, laneHeading = 180f),
            )
        }
        val car = Road.car(heading = null) // 서 있거나 느려서 방위를 모른다
        val naviPort = TripNaviPort { lat, lon, _, _ ->
            val straight = (car.lat - lat) * 111_000.0
            // 부산방향은 직선의 1.05배, 서울방향(경도가 다르다)은 유턴만큼 10km 더
            TripEstimate(straight * 1.05 + if (lon > Road.LON + 0.001) 10_000.0 else 0.0, 600.0)
        }
        val clock = ElapsedClock { testScheduler.currentTime }
        val odometer = Odometer(clock).also { it.advance(0f) }
        val provider = CandidateProvider(FakeStopIndex(rows), RouteOracle(naviPort, clock), odometer, clock)
        val navi = NaviSnapshot(
            epoch = 1, mode = NaviMode.ROUTE_GUIDING, route = RouteState(guiding = true, remainDistanceM = 100_000.0),
            opportunities = (1..5).map { RouteOpportunity("휴게${it}휴게소", StopKind.HIGHWAY_REST_AREA, it * 10_500.0) },
            tbtReceived = true, tbtCoverageM = 100_000.0, tbtEpoch = 1, odometerTrusted = true,
        )
        provider.refresh(car, navi, CandidatePolicy.REAL)
        assertEquals("첫 갱신은 한도(6번)만큼만 묻는다: 세 쌍", 6, naviPort.tripCalls)
        assertEquals(listOf("r1-busan", "r2-busan", "r3-busan", "tbt:휴게4휴게소", "tbt:휴게5휴게소"), provider.snapshot.value.list.map { it.id })
        repeat(2) { provider.refresh(car, navi, CandidatePolicy.REAL) }
        assertEquals(
            "고른 쌍은 다시 묻지 않으니 한도가 남아 뒤 갱신에서 나머지 쌍도 고른다",
            (1..5).map { "r$it-busan" }, provider.snapshot.value.list.map { it.id },
        )
        assertEquals("내비에는 행마다 한 번씩만 물었다 (5쌍 × 2)", 10, naviPort.tripCalls)
    }

    @Test
    fun cacheAnswersDoNotUseUpTheRefreshBudgetInTheNearbySearch() = runTest {
        // 반경 검색도 같다: 캐시가 아는 후보는 한도에 세지 않으므로, 새로 물어야 하는 후보가 한도 안에서 내비에 간다
        val naviPort = TripNaviPort { lat, _, _, _ -> TripEstimate((Road.START_LAT - lat) * 111_000.0 * 1.1, 600.0) }
        val clock = ElapsedClock { testScheduler.currentTime }
        val odometer = Odometer(clock).also { it.advance(0f) }
        val provider = CandidateProvider(FakeStopIndex(nine), RouteOracle(naviPort, clock), odometer, clock)
        provider.refresh(Road.car(), notGuiding, CandidatePolicy.REAL)
        assertEquals(6, naviPort.tripCalls)
        provider.refresh(Road.car(), notGuiding, CandidatePolicy.REAL)
        assertEquals("같은 자리의 다음 갱신은 캐시로 끝난다", 6, naviPort.tripCalls)
        assertEquals(6, provider.snapshot.value.list.count { it.routeDistanceM != null })
    }

    // ------------------------------------------------------------ TBT를 기다리는 동안과 반경 검색 경로

    @Test
    fun aPendingTbtClaimsNothingThenFallsBackWithABoundedHorizon() = runTest {
        val fx = Fx(this, nine.take(3), FakeTripEstimator(TripAnswer.Route(10_000.0)))
        val navi = guiding(emptyList(), remainM = 300_000.0, received = false)
        fx.provider.refresh(Road.car(), navi, CandidatePolicy.REAL)
        assertEquals("아직 만든 목록이 없다", CandidatesState.LOADING_DATA, fx.snap.state)
        assertEquals(0.0, fx.provider.withHorizon(navi.liveRoute(0.0), navi, 0.0)!!.candidatesUntilM!!, 0.0)
        assertTrue("경로가 막 시작됐을 때 requestTrip을 보내지 않는다 (경로 취소 위험, CLAUDE.md)", fx.oracle.calls.isEmpty())
        assertEquals(0, fx.stops.aheadCalls)

        delay(CandidateTuning.ROUTE_TBT_WAIT_MS)
        fx.provider.refresh(Road.car(), navi, CandidatePolicy.REAL)
        assertEquals(CandidateSource.NEARBY, fx.snap.source)
        assertEquals(3, fx.snap.list.size)
        assertEquals("D5a: 반경 검색 목록은 반경까지만 안다", CandidateTuning.AHEAD_RADIUS_M, fx.snap.untilM!!, 1e-6)
    }

    @Test
    fun theFallbackTruncatedToSixSetsTheHorizonAtTheFirstUnrefinedStraightDistance() = runTest {
        val fx = Fx(this, nine)
        // 경로 거리 = 직선거리 × 1.1
        val raw = fx.stops.ahead(Road.car().lat, Road.LON, 180f, CandidateTuning.AHEAD_RADIUS_M, setOf(StopKind.HIGHWAY_REST_AREA, StopKind.DROWSY_SHELTER))
        raw.forEach { fx.oracle.answers[it.id] = TripAnswer.Route(it.straightDistanceM!! * 1.1) }
        val navi = guiding(emptyList(), remainM = 150_000.0, received = false)
        fx.pastTbtWait(navi)
        fx.provider.refresh(Road.car(), navi, CandidatePolicy.REAL)
        assertEquals(CandidateTuning.ORACLE_MAX, fx.snap.list.size)
        assertEquals("보정하지 않은 7번째의 직선거리 안에는 넣은 것 말고 없다 (경로 거리 ≥ 직선거리)", raw[6].straightDistanceM!!, fx.snap.untilM!!, 1e-6)
    }

    @Test
    fun theFallbackDropsStopsPastTheDestinationAndOnesNeedingAUturn() = runTest {
        val rows = listOf(stop("a", "가휴게소", 8.0), stop("c", "다휴게소", 12.0), stop("b", "나휴게소", 30.0), stop("d", "라휴게소", 35.0))
        val oracle = FakeTripEstimator().apply {
            answers["a"] = TripAnswer.Route(9_000.0)
            answers["c"] = TripAnswer.Route(30_000.0, directionOk = false)
            answers["b"] = TripAnswer.Route(40_000.0)
            answers["d"] = TripAnswer.Unknown
        }
        val fx = Fx(this, rows, oracle)
        val navi = guiding(emptyList(), remainM = 25_000.0, received = false)
        fx.pastTbtWait(navi)
        fx.provider.refresh(Road.car(), navi, CandidatePolicy.REAL)
        assertEquals("D5c: 목적지(25km) 뒤 시설과 유턴 시설은 뺀다. 보정 못 한 곳도 직선거리가 목적지보다 멀면 뺀다", listOf("a"), fx.ids)

        // 길안내가 아니면 목적지가 없으니 거리로 빼지 않는다
        fx.provider.refresh(Road.car(), notGuiding, CandidatePolicy.REAL)
        assertEquals(listOf("a", "b", "d"), fx.ids)
        val d = fx.snap.list.last()
        assertNull("보정 못 한 곳은 방향 모름 (틀림이 아니다)", d.directionOk)
        assertNull(d.routeDistanceM)
    }

    @Test
    fun movementDuringRefineIsSubtracted() = runTest {
        val rows = listOf(stop("a", "가휴게소", 10.0), stop("b", "나휴게소", 20.0))
        val oracle = FakeTripEstimator().apply {
            answers["a"] = TripAnswer.Route(10_500.0, etaS = 420.0)
            answers["b"] = TripAnswer.Route(21_000.0, etaS = 840.0)
        }
        val fx = Fx(this, rows, oracle)
        val navi = guiding(emptyList(), received = false, trusted = true)
        fx.pastTbtWait(navi)
        oracle.onCall = { fx.drive(500.0) } // 요청을 보낸 뒤 답이 오기 전에 500m 달린다
        fx.provider.refresh(Road.car(), navi, CandidatePolicy.REAL)
        val s = fx.snap
        assertEquals(1_000.0, s.odometerM, 0.01)
        assertEquals("a는 요청(0m) 뒤 1000m 달렸다", 9_500.0, s.list[0].routeDistanceM!!, 0.01)
        assertEquals("b는 요청(500m) 뒤 500m 달렸다", 20_500.0, s.list[1].routeDistanceM!!, 0.01)
        assertEquals("ETA는 내비 값을 같은 비율로 줄인다", 420.0 * 9_500.0 / 10_500.0, s.list[0].etaS!!, 0.01)
    }

    @Test
    fun aHangingNaviIsBoundedByTheRefreshBudget() = runTest {
        // 데이터 감사 #6, 서비스 감사 P2: 응답 없는 내비가 갱신마다 6×20초를 잡지 않는다
        val fx = Fx(this, nine, FakeTripEstimator(TripAnswer.Hang))
        val start = currentTime
        fx.provider.refresh(Road.car(), notGuiding, CandidatePolicy.REAL)
        assertTrue("갱신 하나가 예산 안에 끝난다", currentTime - start <= CandidateTuning.REFRESH_BUDGET_MS)
        assertEquals("예산을 다 쓰면 나머지는 묻지 않는다", 1, fx.oracle.calls.size)
        assertEquals("묻지 못한 후보도 방향 모름으로 남긴다", 6, fx.snap.list.size)
        assertTrue(fx.snap.list.all { it.directionOk == null && it.routeDistanceM == null && it.etaS == null })
    }

    @Test
    fun etaSComesOnlyFromTheNavi() = runTest {
        val oracle = FakeTripEstimator().apply {
            answers["s1"] = TripAnswer.Route(6_500.0, etaS = 300.0)
            answers["s2"] = TripAnswer.Unknown
        }
        val fx = Fx(this, nine.take(2), oracle)
        fx.provider.refresh(Road.car(), notGuiding, CandidatePolicy.REAL)
        assertEquals("requestTrip ETA는 그대로", 300.0, fx.snap.list[0].etaS!!, 1e-9)
        assertNull("보정 못 하면 비운다 (엔진이 정체로 계산한다)", fx.snap.list[1].etaS)

        fx.provider.refresh(Road.car(), notGuiding, CandidatePolicy.DEMO)
        assertTrue("시연은 ETA를 넣지 않는다", fx.snap.list.all { it.etaS == null })
    }

    // ------------------------------------------------------------ 시연 정책

    @Test
    fun demoPolicyIgnoresTheNaviRouteAndNeitherTrimsNorAsks() = runTest {
        val fx = Fx(this, nine, FakeTripEstimator(TripAnswer.Route(1_000.0)))
        // 실제 내비에 경로와 TBT가 있어도 시연은 시나리오 경로를 쓴다
        val realNavi = guiding(listOf(opp("망향휴게소", 31.0)), remainM = 20_000.0)
        fx.provider.refresh(Road.car(), realNavi, CandidatePolicy.DEMO)
        val s = fx.snap
        assertEquals(CandidateSource.NEARBY, s.source)
        assertEquals("자르지 않고 다 넘긴다 (시연 경로 위 시설은 CoachWorld가 고른다)", 9, s.list.size)
        assertTrue("requestTrip을 보내지 않는다", fx.oracle.calls.isEmpty())
        assertTrue("TBT 이름 맞추기를 하지 않는다", fx.stops.matchCalls.isEmpty())
        assertTrue(s.list.all { it.routeDistanceM == null && it.directionOk == null })
    }

    // ------------------------------------------------------------ 상태

    @Test
    fun statesBeforeTheFirstList() = runTest {
        val fx = Fx(this, nine.take(1))
        assertEquals(CandidatesState.LOADING_DATA, fx.snap.state)
        fx.stops.loaded = false
        fx.provider.refresh(Road.car(), notGuiding, CandidatePolicy.REAL)
        assertEquals(CandidatesState.LOADING_DATA, fx.snap.state)
        assertEquals("아무것도 주장하지 않는다", 0.0, fx.snap.untilM!!, 0.0)
        fx.stops.loaded = true
        fx.provider.refresh(null, notGuiding, CandidatePolicy.REAL)
        assertEquals(CandidatesState.NO_LOCATION, fx.snap.state)
        assertTrue(fx.snap.list.isEmpty())
        fx.provider.refresh(Road.car(), notGuiding, CandidatePolicy.REAL)
        assertEquals(CandidatesState.READY, fx.snap.state)
    }

    // ------------------------------------------------------------ 매초 투영

    @Test
    fun liveProjectionMatchesNaviPollAndKeepsTheSameListWhenNotMoving() = runTest {
        val oracle = FakeTripEstimator().apply { nine.forEach { answers[it.c.id] = TripAnswer.Route(it.c.id.drop(1).toInt() * 6_300.0) } }
        val fx = Fx(this, nine, oracle)
        val trusted = guiding(emptyList(), received = false, trusted = true)
        fx.pastTbtWait(trusted)
        fx.provider.refresh(Road.car(), trusted, CandidatePolicy.REAL)
        val s = fx.snap
        assertSame("움직이지 않았으면 같은 목록 (화면이 다시 그리지 않게)", s.list, fx.provider.live(trusted, s.odometerM))
        assertEquals(NaviPoll.advanceCandidates(s.list, 2_000.0), fx.provider.live(trusted, s.odometerM + 2_000.0))
        assertEquals("주행 거리를 못 믿으면 받은 거리 그대로", s.list, fx.provider.live(trusted.copy(odometerTrusted = false), s.odometerM + 2_000.0))
    }

    @Test
    fun horizonIsProjectedClampedAndOnlyForGuidance() = runTest {
        val fx = Fx(this, emptyList())
        val opps = (1..12).map { opp("휴게${it}휴게소", it * 10.0) }
        val navi = guiding(opps)
        fx.provider.refresh(Road.car(), navi, CandidatePolicy.REAL)
        val route = navi.liveRoute(0.0)
        assertEquals(110_000.0, fx.provider.withHorizon(route, navi, 0.0)!!.candidatesUntilM!!, 1e-6)
        assertEquals("달린 만큼 줄인다", 100_000.0, fx.provider.withHorizon(route, navi, 10_000.0)!!.candidatesUntilM!!, 1e-6)
        assertEquals("음수가 되지 않는다", 0.0, fx.provider.withHorizon(route, navi, 200_000.0)!!.candidatesUntilM!!, 0.0)
        val plain = RouteState(guiding = false)
        assertSame("길안내가 아니면 그대로", plain, fx.provider.withHorizon(plain, navi, 0.0))
        assertNull(fx.provider.withHorizon(null, navi, 0.0))

        fx.provider.refresh(Road.car(), guiding(opps.take(3)), CandidatePolicy.REAL)
        assertSame("완전한 목록이면 그대로", route, fx.provider.withHorizon(route, guiding(opps.take(3)), 0.0))
    }

    // ------------------------------------------------------------ 갱신 루프: epoch, 주기, 초기화

    @Test
    fun anEpochChangeRefreshesAndClaimsNothingUntilTheNewTbt() = runTest {
        val rows = listOf(stop("mh", "망향휴게소(부산방향)", 30.0), stop("cn", "천안휴게소(부산방향)", 60.0))
        val fx = Fx(this, rows, FakeTripEstimator(TripAnswer.Route(10_000.0)))
        var navi = guiding(listOf(opp("망향휴게소", 31.0)), epoch = 1)
        val job = launch { fx.provider.run { CandidateRequest(Road.car(), navi, CandidatePolicy.REAL) } }
        runCurrent()
        assertEquals(listOf("mh"), fx.ids)

        // 경로가 바뀌었다 (경유지 추가·재탐색). 새 경로의 TBT는 아직: 옛 TBT는 tbtEpoch 1
        navi = guiding(listOf(opp("망향휴게소", 31.0)), epoch = 2, received = false, tbtEpoch = 1)
        advanceTimeBy(1_100)
        assertEquals("목록은 남겨 둔다 (화면·수락한 정차가 깜빡이지 않게)", listOf("mh"), fx.ids)
        assertEquals("하지만 다른 경로에서 만든 목록으로는 아무것도 주장하지 않는다", 0.0,
            fx.provider.withHorizon(navi.liveRoute(0.0), navi, 0.0)!!.candidatesUntilM!!, 0.0)
        assertTrue(fx.oracle.calls.isEmpty())

        // 새 TBT가 왔다 → 바로 다시 만든다
        navi = guiding(listOf(opp("천안휴게소", 62.0)), epoch = 2)
        advanceTimeBy(1_100)
        assertEquals(listOf("cn"), fx.ids)
        assertEquals(2, fx.snap.naviEpoch)
        assertNull(fx.provider.withHorizon(navi.liveRoute(0.0), navi, 0.0)!!.candidatesUntilM)
        job.cancel()
    }

    @Test
    fun aTbtThatNeverArrivesFallsBackAfterTheWait() = runTest {
        val fx = Fx(this, nine.take(2), FakeTripEstimator(TripAnswer.Route(7_000.0)))
        val navi = guiding(emptyList(), received = false)
        val job = launch { fx.provider.run { CandidateRequest(Road.car(), navi, CandidatePolicy.REAL) } }
        runCurrent()
        assertEquals(CandidatesState.LOADING_DATA, fx.snap.state)
        advanceTimeBy(CandidateTuning.ROUTE_TBT_WAIT_MS + 1_100)
        assertEquals("기다림이 끝나면 주기를 기다리지 않고 반경 검색", CandidateSource.NEARBY, fx.snap.source)
        job.cancel()
    }

    @Test
    fun guidanceStartingWithoutAnEpochChangeStillWaitsForTheTbt() = runTest {
        // 방어: 내비 추적이 epoch를 올리지 않고 길안내만 켜도 경로 시작 직후 requestTrip을 보내지 않는다
        val fx = Fx(this, nine.take(2), FakeTripEstimator(TripAnswer.Route(7_000.0)))
        fx.provider.refresh(Road.car(), notGuiding.copy(epoch = 1), CandidatePolicy.REAL)
        val asked = fx.oracle.calls.size
        delay(CandidateTuning.ROUTE_TBT_WAIT_MS)
        fx.provider.refresh(Road.car(), guiding(emptyList(), epoch = 1, received = false), CandidatePolicy.REAL)
        assertEquals("TBT를 기다리는 동안 묻지 않는다", asked, fx.oracle.calls.size)
        assertEquals(0.0, fx.provider.withHorizon(RouteState(guiding = true, remainDistanceM = 300_000.0), guiding(emptyList(), epoch = 1, received = false), 0.0)!!.candidatesUntilM!!, 0.0)
    }

    @Test
    fun theLoopRefreshesOnItsPeriodAndOnTriggersOnly() = runTest {
        val fx = Fx(this, nine.take(2), FakeTripEstimator(TripAnswer.Route(7_000.0)))
        var pos: Fix? = null
        val job = launch { fx.provider.run { CandidateRequest(pos, notGuiding, CandidatePolicy.REAL) } }
        runCurrent()
        assertEquals(CandidatesState.NO_LOCATION, fx.snap.state)
        pos = Road.car()
        advanceTimeBy(1_100)
        assertEquals("위치가 생기면 바로", CandidatesState.READY, fx.snap.state)
        val after = fx.stops.aheadCalls
        advanceTimeBy(CandidatePolicy.REAL.periodMs - 2_000)
        assertEquals("바뀐 것이 없으면 주기 전에는 다시 만들지 않는다", after, fx.stops.aheadCalls)
        advanceTimeBy(3_000)
        assertEquals("주기마다", after + 1, fx.stops.aheadCalls)
        job.cancel()
    }

    @Test
    fun resetCancelsAnInFlightRefreshAndClearsTheList() = runTest {
        val fx = Fx(this, nine, FakeTripEstimator(TripAnswer.Route(7_000.0)))
        var policy = CandidatePolicy.REAL
        val job = launch { fx.provider.run { CandidateRequest(Road.car(), notGuiding, policy) } }
        runCurrent()
        assertEquals(6, fx.snap.list.size)

        // 다음 실제 갱신이 내비에서 멈춘 사이에 시연을 켠다
        fx.oracle.default = TripAnswer.Hang
        fx.oracle.answers.clear()
        advanceTimeBy(CandidatePolicy.REAL.periodMs + 100)
        val startedAt = currentTime
        policy = CandidatePolicy.DEMO
        fx.provider.reset()
        assertTrue("실제 후보가 시연 엔진에 한 틱도 들어가지 않게 바로 비운다 (B2)", fx.snap.list.isEmpty())
        assertEquals(CandidatesState.LOADING_DATA, fx.snap.state)
        assertEquals("모드가 바뀌면 경로 보정 캐시도 비운다", 1, fx.oracle.cleared)
        runCurrent()
        assertEquals("멈춘 갱신을 기다리지 않고 시연 목록", 9, fx.snap.list.size)
        assertEquals(startedAt, currentTime)
        advanceTimeBy(CandidateTuning.REFRESH_BUDGET_MS)
        assertEquals("취소된 실제 갱신이 나중에 덮어쓰지 않는다", 9, fx.snap.list.size)
        assertFalse(fx.snap.list.any { it.routeDistanceM != null })
        job.cancel()
    }
}
