package dev.shimpyo.coach

import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 스냅샷의 실시간 보정: 받은 뒤 달린 만큼 거리를 줄이되, 믿을 수 없거나 다른 epoch의 값은 쓰지 않는다 */
class NaviSnapshotTest {

    private val snap = NaviSnapshot(
        epoch = 3,
        mode = NaviMode.ROUTE_GUIDING,
        route = RouteState(guiding = true, remainDistanceM = 100_000.0, remainTimeS = 4_000.0, congestion = listOf(CongestionSegment(500.0, 1_000.0, 300.0), CongestionSegment(20_000.0, 2_000.0, 600.0))),
        routeOdometerM = 1_000.0,
        opportunities = listOf(RouteOpportunity("옥산휴게소", StopKind.HIGHWAY_REST_AREA, 4_000.0), RouteOpportunity("죽암휴게소", StopKind.HIGHWAY_REST_AREA, 25_000.0)),
        tbtReceived = true,
        tbtCoverageM = 80_000.0,
        nextManeuverM = 3_000.0,
        tbtOdometerM = 1_000.0,
        tbtEpoch = 3,
        odometerTrusted = true,
    )

    @Test
    fun trustedSnapshotsShrinkWithTheOdometer() {
        val live = snap.liveRoute(odometerM = 3_000.0)!!
        assertEquals(98_000.0, live.remainDistanceM!!, 1e-9)
        assertEquals(1_000.0, live.nextManeuverDistanceM!!, 1e-9)
        assertEquals("지나간 정체는 빠진다", listOf(18_000.0), live.congestion.map { it.startDistanceM })
        assertEquals(listOf(2_000.0, 23_000.0), snap.liveOpportunities(3_000.0).map { it.routeDistanceM })
        assertEquals(78_000.0, snap.liveTbtCoverageM(3_000.0)!!, 1e-9)
    }

    @Test
    fun untrustedSnapshotsKeepTheReceivedDistances() {
        val s = snap.copy(odometerTrusted = false)
        assertEquals(100_000.0, s.liveRoute(3_000.0)!!.remainDistanceM!!, 0.0)
        assertEquals(80_000.0, s.liveTbtCoverageM(3_000.0)!!, 0.0)
        assertEquals(listOf(4_000.0, 25_000.0), s.liveOpportunities(3_000.0).map { it.routeDistanceM })
    }

    @Test
    fun tbtFromAnotherEpochIsNeverUsed() {
        val s = snap.copy(epoch = 4)
        assertTrue(s.liveOpportunities(1_000.0).isEmpty())
        assertNull(s.liveRoute(1_000.0)!!.nextManeuverDistanceM)
        assertNull(s.liveTbtCoverageM(1_000.0))
    }

    @Test
    fun coverageIsUnknownUntilTheTbtArrives() {
        assertNull(NaviSnapshot(epoch = 1, tbtEpoch = 1).liveTbtCoverageM(0.0))
        assertEquals(0.0, NaviSnapshot(epoch = 1, tbtEpoch = 1, tbtReceived = true, tbtCoverageM = 0.0).liveTbtCoverageM(500.0)!!, 0.0)
    }
}
