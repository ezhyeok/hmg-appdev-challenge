package dev.shimpyo.coach

import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 내비 조회 간격과 조회 사이 거리 보정. 회귀 방지: 경로가 없어도 TBT를 5초마다 받던 것(2026-11-11 측정)을 줄이되,
 * 묻는 거리(진입로 2~5km) 근처에서는 예전처럼 5초마다 받아야 한다.
 */
class NaviPollTest {

    private fun interval(opp: Double?, turn: Double?, speedKnown: Boolean = true, trusted: Boolean = true, afterReset: Boolean = false) =
        NaviPoll.tbtIntervalMs(opp, turn, speedKnown, trusted, afterReset)

    @Test
    fun pollsLessOnlyWhenFarAndTheOdometerIsConfirmed() {
        assertEquals("멀고 주행 거리 보정을 믿으면 20초", NaviPoll.FAR_MS, interval(25_000.0, 40_000.0))
        assertEquals("화장실이 8km 안이면 5초", NaviPoll.NEAR_MS, interval(7_000.0, 40_000.0))
        assertEquals("회전이 가까워도 5초 (다음 회전 500m 안에는 묻지 않는 판단)", NaviPoll.NEAR_MS, interval(30_000.0, 1_000.0))
        assertEquals("TBT가 비었으면(길안내 중 경로에는 목적지라도 있다) 다시 받으려고 5초", NaviPoll.NEAR_MS, interval(null, null))
        assertEquals("차속을 모르면 거리를 못 줄이니 5초", NaviPoll.NEAR_MS, interval(30_000.0, 40_000.0, speedKnown = false))
        assertEquals("내비가 진행을 확인하기 전(에뮬레이터처럼 내비가 서 있으면)은 예전처럼 5초", NaviPoll.NEAR_MS, interval(30_000.0, 40_000.0, trusted = false))
        assertEquals("길안내 시작·경유지 추가·재탐색 뒤 30초는 5초", NaviPoll.NEAR_MS, interval(30_000.0, 40_000.0, afterReset = true))
    }

    @Test
    fun aFrozenNaviIsAskedForTbtOnlyAsOftenAsForTheRoute() {
        // 2026-11-12 에뮬레이터: 내비는 판교에 서 있어(남은 거리 그대로) TBT도 바뀌지 않는데 5초마다 물어 1분에 20번 요청했다.
        // 내비 위치에서 잰 TBT는 내비가 움직여야 바뀐다: 경로 조회(15초)보다 자주 물을 까닭이 없다
        assertEquals(NaviPoll.ROUTE_MS, NaviPoll.tbtIntervalMs(30_000.0, 80.0, speedKnown = true, trusted = false, afterReset = false, naviFrozen = true))
        assertEquals("경로가 막 바뀌었으면 그래도 5초", NaviPoll.NEAR_MS, NaviPoll.tbtIntervalMs(30_000.0, 80.0, true, false, afterReset = true, naviFrozen = true))
        assertEquals("내비가 움직이면 예전 규칙", NaviPoll.NEAR_MS, NaviPoll.tbtIntervalMs(30_000.0, 80.0, true, false, afterReset = false, naviFrozen = false))
        org.junit.Assert.assertTrue("남은 거리 그대로 + 차는 416m", NaviPoll.naviFrozen(200_000.0, 0.0, 200_000.0, 416.0, wasFrozen = false))
        org.junit.Assert.assertFalse("내비가 나아갔다", NaviPoll.naviFrozen(200_000.0, 0.0, 199_600.0, 416.0, wasFrozen = true))
        org.junit.Assert.assertTrue("거의 서 있으면 판단을 바꾸지 않는다", NaviPoll.naviFrozen(200_000.0, 0.0, 200_000.0, 100.0, wasFrozen = true))
        org.junit.Assert.assertFalse("남은 거리를 모르면 판단을 바꾸지 않는다", NaviPoll.naviFrozen(null, 0.0, 200_000.0, 416.0, wasFrozen = false))
    }

    @Test
    fun odometerIsTrustedOnlyWhenTheNaviConfirmsProgress() {
        // 15초 동안 400m 달렸고 내비 남은 거리도 380m 줄었다 → 믿는다
        assertEquals(NaviPoll.Progress(trusted = true, rerouted = false), NaviPoll.checkProgress(100_000.0, 0.0, 99_620.0, 400.0, wasTrusted = false))
        // 내비 남은 거리가 그대로(에뮬레이터 내비는 판교에 서 있다) → 보정을 끈다
        assertEquals(NaviPoll.Progress(trusted = false, rerouted = false), NaviPoll.checkProgress(100_000.0, 0.0, 100_000.0, 400.0, wasTrusted = true))
        // 거의 서 있으면 판단을 바꾸지 않는다
        assertEquals(NaviPoll.Progress(trusted = true, rerouted = false), NaviPoll.checkProgress(100_000.0, 0.0, 100_000.0, 100.0, wasTrusted = true))
        // 남은 거리가 1km 넘게 늘면 경로가 바뀌었다 (놓친 출구 재탐색, 경유지 추가)
        assertEquals(NaviPoll.Progress(trusted = false, rerouted = true), NaviPoll.checkProgress(100_000.0, 0.0, 104_000.0, 400.0, wasTrusted = true))
        // 주행 거리로 설명 못 할 만큼 줄어도 경로가 바뀌었다
        assertEquals(NaviPoll.Progress(trusted = false, rerouted = true), NaviPoll.checkProgress(100_000.0, 0.0, 90_000.0, 400.0, wasTrusted = true))
        // 처음(직전 값 없음)은 믿지 않은 채로
        assertEquals(NaviPoll.Progress(trusted = false, rerouted = false), NaviPoll.checkProgress(null, 0.0, 100_000.0, 0.0, wasTrusted = false))
    }

    @Test
    fun distancesShrinkWithTheOdometerAndPassedStopsDrop() {
        val opps = listOf(RouteOpportunity("옥산휴게소", StopKind.HIGHWAY_REST_AREA, 4_000.0), RouteOpportunity("죽암휴게소", StopKind.HIGHWAY_REST_AREA, 25_000.0))
        assertEquals(listOf(1_000.0, 22_000.0), NaviPoll.advance(opps, 3_000.0).map { it.routeDistanceM })
        assertEquals(listOf(20_000.0), NaviPoll.advance(opps, 5_000.0).map { it.routeDistanceM })
        assertEquals(opps, NaviPoll.advance(opps, 0.0))

        fun c(id: String, route: Double?, eta: Double? = null) = StopCandidate(id, id, StopKind.HIGHWAY_REST_AREA, 36.0, 127.0, Facilities(), parkable = true, routeDistanceM = route, etaS = eta)
        val cands = listOf(c("a", 500.0), c("b", 9_000.0, eta = 900.0), c("straight-only", null))
        val moved = NaviPoll.advanceCandidates(cands, 1_000.0)
        assertEquals(listOf("b" to 8_000.0, "straight-only" to null), moved.map { it.id to it.routeDistanceM })
        assertEquals("내비 ETA도 같은 비율로 줄인다", 800.0, moved.first().etaS!!, 1e-9)
    }

    @Test
    fun safeGuidingWithoutARouteIsNotGuiding() {
        // 경로 없는 안전운전 모드에서 TBT를 조회하거나 addWaypoint를 부르면 안 된다
        assertEquals(false, NaviPoll.isGuiding(NaviMode.SAFE_GUIDING))
        assertEquals(true, NaviPoll.isGuiding(NaviMode.ROUTE_GUIDING))
        assertEquals(true, NaviPoll.isGuiding(NaviMode.SIMULATION_GUIDING))
        assertEquals(false, NaviPoll.isGuiding(null))
    }

    @Test
    fun odometerIntegratesSpeedAndCapsGaps() {
        assertEquals(27.78, NaviPoll.distanceM(100f, 1_000L), 0.01)
        assertEquals("5초 넘는 공백은 5초까지만", NaviPoll.distanceM(100f, 5_000L), NaviPoll.distanceM(100f, 60_000L), 1e-9)
        assertEquals(0.0, NaviPoll.distanceM(null, 1_000L), 0.0)
        assertEquals(0.0, NaviPoll.distanceM(-3f, 1_000L), 0.0)
    }
}
