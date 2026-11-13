package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.FakeNaviPort
import dev.shimpyo.coach.fakes.FakeNaviPort.Companion.dest
import dev.shimpyo.coach.fakes.FakeNaviPort.Fault
import dev.shimpyo.coach.fakes.RecordingLog
import dev.shimpyo.engine.RouteState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NaviTracker를 가상 시간으로 돌린다. 가짜 내비(FakeNaviPort)는 차의 주행 거리에 따라 답하고, 차는 코치 틱처럼 매초 주행 거리를 늘린다.
 * 시나리오 표는 docs/design/coach-layer/navi.md. 시각은 모두 ms (가상 시간 0부터).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NaviTrackerTest {

    private class Rig(val scope: TestScope) {
        val clock = ElapsedClock { scope.testScheduler.currentTime }
        val navi = FakeNaviPort(clock)
        val odo = Odometer(clock)
        var speedKph: Float? = 100f
        val log = RecordingLog()
        var fallback: LocationFallback? = null
        val tracker: NaviTracker by lazy { NaviTracker(navi, odo, { speedKph != null }, clock, log, fallback) }
        /** 내보낸 스냅샷 전부 (Unconfined 구독이라 중간 값을 놓치지 않는다) */
        val published = mutableListOf<NaviSnapshot>()
        val snap: NaviSnapshot get() = tracker.snapshot.value

        fun start(car: Boolean = true) {
            if (car) scope.backgroundScope.launch {
                while (true) {
                    odo.advance(speedKph)
                    delay(1_000)
                }
            }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { tracker.snapshot.collect { published += it } }
            scope.backgroundScope.launch { tracker.run() }
        }

        /** 내비가 차를 따라 움직이는 길안내: 남은 거리 = 전체 - 주행 거리 (+ [extra]) */
        fun guide(totalM: Double = 200_000.0, extra: () -> Double = { 0.0 }) {
            navi.mode = { NaviMode.ROUTE_GUIDING }
            navi.destination = { dest(totalM - odo.meters + extra()) }
        }

        /** 경로 위 [restAtM] 지점의 휴게소와 [turnAtM] 지점의 분기 (출발점 기준, 차가 다가간다). TBT 거리는 직전 항목부터 */
        fun tbtAhead(restAtM: Double, turnAtM: Double, name: String = "죽암휴게소") {
            navi.tbt = {
                var prev = odo.meters
                listOf(turnAtM to TbtItem(TbtType.OTHER, 0.0, "분기"), restAtM to TbtItem(TbtType.SERVICE_AREA, 0.0, name))
                    .sortedBy { it.first }
                    .map { (at, item) -> item.copy(distanceM = at - prev).also { prev = at } }
            }
        }
    }

    private fun every(stepMs: Long, fromMs: Long, toMs: Long) = (fromMs..toMs step stepMs).toList()

    // ------------------------------------------------------------ 조회 간격

    @Test
    fun notGuidingPollsTheRouteEvery15sAndNeverAsksTbtOrDestination() = runTest {
        val r = Rig(this)
        r.navi.mode = { NaviMode.NOT_WORKING }
        r.start()
        advanceTimeBy(60_001)
        assertEquals(every(15_000, 0, 60_000), r.navi.times("routeStatus"))
        assertEquals(0, r.navi.count("tbt"))
        assertEquals(0, r.navi.count("destination"))
        assertEquals(RouteState(guiding = false), r.snap.route)
        assertEquals("길안내가 시작된 적이 없으면 epoch는 0", 0, r.snap.epoch)
    }

    @Test
    fun safeGuidingHasNoRouteSoItIsIdle() = runTest {
        // SAFE_GUIDING은 경로 없는 안전운전 모드다. TBT·목적지를 조회하면 안 된다 (2026-11-11 감사)
        val r = Rig(this)
        r.navi.mode = { NaviMode.SAFE_GUIDING }
        r.start()
        advanceTimeBy(30_001)
        assertEquals(0, r.navi.count("tbt"))
        assertEquals(0, r.navi.count("destination"))
        assertEquals(NaviMode.SAFE_GUIDING, r.snap.mode)
        assertFalse(r.snap.guiding)
    }

    @Test
    fun aFrozenNaviIsAskedForTbtAtTheRoutePaceAfterTheFastWindow() = runTest {
        // 에뮬레이터: 내비는 판교에 서 있고(남은 거리 그대로) 차는 Virtual Console 차속으로 달린다 → 보정을 믿지 않는다.
        // 예전에는 이때 TBT를 5초마다 물었다(1분 20번). 내비가 서 있으면 TBT도 바뀌지 않으니 경로 조회만큼만 묻는다 (2026-11-12)
        val r = Rig(this)
        r.navi.mode = { NaviMode.ROUTE_GUIDING }
        r.navi.destination = { dest(200_000.0) }
        r.navi.tbt = { listOf(TbtItem(TbtType.OTHER, 30_000.0, "분기"), TbtItem(TbtType.SERVICE_AREA, 20_000.0, "죽암휴게소")) }
        r.start()
        advanceTimeBy(60_001)
        assertEquals("시작 30초는 5초, 내비가 서 있다고 안 뒤로는 15초", every(5_000, 0, 25_000) + listOf(40_000L, 55_000L), r.navi.times("tbt"))
        assertEquals(every(15_000, 0, 60_000), r.navi.times("routeStatus"))
        assertFalse(r.snap.odometerTrusted)
    }

    @Test
    fun aNaviThatMovesAtAnotherPaceIsStillAskedEvery5s() = runTest {
        // 보정을 못 믿지만 내비는 움직인다(차속이 틀림): TBT가 바뀌므로 예전처럼 5초
        val r = Rig(this)
        r.navi.mode = { NaviMode.ROUTE_GUIDING }
        r.navi.destination = { dest(200_000.0 - r.odo.meters * 0.3) }
        r.navi.tbt = { listOf(TbtItem(TbtType.OTHER, 30_000.0, "분기"), TbtItem(TbtType.SERVICE_AREA, 20_000.0, "죽암휴게소")) }
        r.start()
        advanceTimeBy(60_001)
        assertFalse(r.snap.odometerTrusted)
        assertEquals(every(5_000, 0, 60_000), r.navi.times("tbt"))
    }

    @Test
    fun aFrozenNaviThatStartsMovingIsAskedEvery5sAgain() = runTest {
        val r = Rig(this)
        r.navi.mode = { NaviMode.ROUTE_GUIDING }
        // 45초까지 서 있다가 움직인다 (터널을 나와 위치를 다시 잡음)
        r.navi.destination = { dest(if (r.scope.testScheduler.currentTime < 45_000) 200_000.0 else 200_000.0 - (r.odo.meters - 1_250.0) * 0.4) }
        r.start()
        advanceTimeBy(120_001)
        val t = r.navi.times("tbt")
        assertTrue("서 있는 동안 15초 ($t)", t.any { it == 40_000L } && t.none { it in 41_000..54_000 })
        assertTrue("다시 움직인 것을 안 뒤(60초 경로 조회)로는 5초 ($t)", t.filter { it > 60_000 }.zipWithNext().all { (a, b) -> b - a == 5_000L })
    }

    @Test
    fun trustedAndFarPollsTbtEvery20sAfterTheFastWindow() = runTest {
        val r = Rig(this)
        r.guide()
        r.tbtAhead(restAtM = 80_000.0, turnAtM = 60_000.0)
        r.start()
        advanceTimeBy(120_001)
        assertTrue("내비 남은 거리가 주행 거리만큼 줄었으니 믿는다", r.snap.odometerTrusted)
        val t = r.navi.times("tbt")
        assertEquals("길안내 시작 뒤 30초는 5초", every(5_000, 0, 25_000), t.filter { it < 30_000 })
        assertEquals("그 뒤 멀면 20초", listOf(45_000L, 65_000L, 85_000L, 105_000L), t.filter { it >= 30_000 })
    }

    @Test
    fun aNearOpportunityKeepsTbtAt5sEvenWhenTrusted() = runTest {
        val r = Rig(this)
        r.guide()
        r.tbtAhead(restAtM = 7_000.0, turnAtM = 60_000.0, name = "안성휴게소")
        r.start()
        advanceTimeBy(80_001)
        assertTrue(r.snap.odometerTrusted)
        assertEquals("다음 화장실이 8km 안이면 5초 (묻는 거리 2~5km 근처)", every(5_000, 0, 80_000), r.navi.times("tbt"))
    }

    @Test
    fun requestRefreshPollsAtOnceThenTbtEvery5sFor30s() = runTest {
        val r = Rig(this)
        r.guide()
        r.tbtAhead(restAtM = 80_000.0, turnAtM = 60_000.0)
        r.start()
        advanceTimeBy(72_000)
        val epoch = r.snap.epoch
        r.tracker.requestRefresh(routeChanged = false)
        advanceTimeBy(58_001) // → 130_001
        assertTrue("요청한 그 자리에서 경로를 읽는다", 72_000L in r.navi.times("routeStatus"))
        assertEquals(
            "바로 받고 30초는 5초, 그 뒤 다시 20초",
            listOf(72_000L, 77_000L, 82_000L, 87_000L, 92_000L, 97_000L, 117_000L),
            r.navi.times("tbt").filter { it in 72_000L..130_000L },
        )
        assertEquals("경로를 바꾸지 않은 새로 고침은 epoch를 늘리지 않는다", epoch, r.snap.epoch)
        assertTrue(r.snap.odometerTrusted)
    }

    @Test
    fun requestRefreshAfterChangingTheRouteStartsANewEpoch() = runTest {
        val r = Rig(this)
        r.guide()
        r.tbtAhead(restAtM = 80_000.0, turnAtM = 60_000.0)
        r.navi.latencyMs["tbt"] = 500
        r.start()
        advanceTimeBy(40_000)
        assertTrue(r.snap.odometerTrusted)
        assertTrue(r.snap.tbtReceived)
        r.tracker.requestRefresh() // 경유지를 넣었다
        runCurrent()
        val mid = r.snap
        assertEquals(2, mid.epoch)
        assertFalse("옛 TBT는 새 epoch에 쓰지 않는다", mid.tbtReceived)
        assertTrue(mid.liveOpportunities(r.odo.meters).isEmpty())
        assertFalse("새 경로에서 보정 신뢰를 다시 얻는다", mid.odometerTrusted)
        advanceTimeBy(501)
        assertTrue("TBT는 곧바로 다시 받는다", r.snap.tbtReceived)
        assertEquals(2, r.snap.tbtEpoch)
    }

    // ------------------------------------------------------------ epoch와 경로 변경

    @Test
    fun guidanceStartBumpsTheEpochAndFetchesTbtAtOnce() = runTest {
        val r = Rig(this)
        r.navi.mode = { NaviMode.SAFE_GUIDING }
        r.start()
        advanceTimeBy(20_000)
        assertEquals(0, r.snap.epoch)
        r.guide()
        r.navi.tbt = { listOf(TbtItem(TbtType.SERVICE_AREA, 9_000.0, "안성휴게소")) }
        r.tracker.requestRefresh() // startRoute가 성공했다
        runCurrent()
        assertEquals(1, r.snap.epoch)
        assertTrue(r.snap.guiding)
        assertEquals(listOf(20_000L), r.navi.times("tbt"))
        assertEquals(listOf("안성휴게소"), r.snap.opportunities.map { it.name })
        assertEquals("경로 시작을 봤으니 전체 거리를 이때 잡는다", 200_000.0 - r.odo.meters, r.tracker.congestion.routeTotalM!!, 30.0)
        assertTrue(r.tracker.congestion.routeOriginKnown)
    }

    @Test
    fun startingMidRouteDoesNotKnowTheOrigin() = runTest {
        // 서비스가 길안내 도중에 (다시) 켜졌다: 출발지에서 얼마나 왔는지 몰라 출발지 기준 정체 offset을 바꿀 수 없다
        val r = Rig(this)
        r.guide()
        r.start()
        runCurrent()
        assertEquals(1, r.snap.epoch)
        assertFalse(r.tracker.congestion.routeOriginKnown)
    }

    @Test
    fun anEventWhileIdleOnlyRefreshes() = runTest {
        val r = Rig(this)
        r.start()
        advanceTimeBy(7_000)
        r.navi.changes.tryEmit(NaviChange.ROUTE_CANCELLED)
        runCurrent()
        assertEquals(listOf(0L, 7_000L), r.navi.times("routeStatus"))
        assertEquals("바뀔 경로가 없으면 epoch를 늘리지 않는다", 0, r.snap.epoch)
    }

    @Test
    fun aBrokenRemainingDistanceIsUnknownNotZero() = runTest {
        val r = Rig(this)
        r.navi.mode = { NaviMode.ROUTE_GUIDING }
        r.navi.destination = { dest(Double.NaN) }
        r.start()
        runCurrent()
        assertTrue(r.snap.guiding)
        assertNull(r.snap.route!!.remainDistanceM)
        assertEquals("남은 거리를 못 읽었으면 5초 뒤 다시", 5_000L, run { advanceTimeBy(5_001); r.navi.times("routeStatus")[1] })
    }

    @Test
    fun aZeroRemainingDistanceWhileStillGuidingIsAGlitchNotTwoReroutes() = runTest {
        // 2026-11-12 에뮬레이터: 길안내 중인 내비가 목적지 응답 하나에서 남은 거리 0을 주고 다음 응답에서 383km로 돌아왔다.
        // 0을 값으로 믿으면 '3km 넘게 줄었다'(재탐색)와 '1km 넘게 늘었다'(재탐색)로 epoch가 두 번 올라 후보 범위가 0이 되고 TBT를 다시 받았다.
        // 길안내 모드인데 남은 거리가 0이면 도착이 아니다(도착하면 모드가 바뀐다): 못 읽은 것으로 본다
        val r = Rig(this)
        r.guide(totalM = 383_000.0, extra = {
            val t = r.scope.testScheduler.currentTime
            if (t in 25_000..35_000) -(383_000.0 - r.odo.meters) else 0.0
        })
        r.start()
        advanceTimeBy(60_001)
        assertEquals("epoch는 길안내 시작 한 번", 1, r.snap.epoch)
        assertTrue("남은 거리는 0이 아니다", r.snap.route!!.remainDistanceM!! > 300_000.0)
        assertTrue(r.published.none { it.route?.remainDistanceM == 0.0 })
    }

    @Test
    fun aRerouteBumpsTheEpochResetsCongestionAndNeverPairsTheOldTbt() = runTest {
        val r = Rig(this)
        // 40초에 출구를 놓쳐 남은 거리가 5km 늘었다 (이벤트 없이 남은 거리로만 안다)
        r.guide { if (r.scope.testScheduler.currentTime >= 40_000) 5_000.0 else 0.0 }
        r.navi.tbt = {
            if (r.scope.testScheduler.currentTime >= 40_000) listOf(TbtItem(TbtType.SERVICE_AREA, 9_000.0, "새휴게소"))
            else listOf(TbtItem(TbtType.SERVICE_AREA, 30_000.0, "옛휴게소"))
        }
        r.navi.latencyMs["tbt"] = 500
        r.start()
        advanceTimeBy(44_999)
        assertEquals(1, r.snap.epoch)
        assertTrue(r.snap.odometerTrusted)
        val totalBefore = r.tracker.congestion.routeTotalM
        advanceTimeBy(2) // 45_001: 경로 조회가 재탐색을 알아챘고 TBT는 받는 중
        val mid = r.snap
        assertEquals(2, mid.epoch)
        assertFalse(mid.odometerTrusted)
        assertFalse(mid.tbtReceived)
        assertTrue("옛 경로의 휴게소가 새 경로에 붙지 않는다", mid.liveOpportunities(r.odo.meters).isEmpty())
        assertTrue("정체 판정의 전체 거리를 재탐색 때 다시 잡는다", r.tracker.congestion.routeTotalM!! > totalBefore!!)
        advanceTimeBy(600)
        assertEquals(listOf("새휴게소"), r.snap.liveOpportunities(r.odo.meters).map { it.name })
        assertTrue(
            "어느 순간에도 epoch 2 스냅샷에 옛휴게소가 없다",
            r.published.filter { it.epoch == 2 }.none { s -> s.opportunities.any { it.name == "옛휴게소" } },
        )
    }

    @Test
    fun aDestinationChangeBumpsTheEpoch() = runTest {
        val r = Rig(this)
        r.guide()
        r.start()
        advanceTimeBy(20_000)
        assertEquals(1, r.snap.epoch)
        // 운전자가 목적지를 바꿨다 (남은 거리는 비슷해서 남은 거리로는 모른다)
        r.navi.destination = { dest(200_000.0 - r.odo.meters, lat = 35.1151, lon = 129.0415, name = "부산역") }
        advanceTimeBy(10_001) // 30_000 경로 조회
        assertEquals(2, r.snap.epoch)
        assertEquals("부산역", r.snap.destination?.name)
    }

    @Test
    fun aNaviEventRefreshesBeforeTheNextPeriod() = runTest {
        val r = Rig(this)
        r.guide()
        r.start()
        advanceTimeBy(7_000)
        runCurrent()
        r.navi.changes.tryEmit(NaviChange.REROUTED)
        runCurrent()
        assertEquals("15초를 기다리지 않는다", listOf(0L, 7_000L), r.navi.times("routeStatus"))
        assertEquals(2, r.snap.epoch)
        assertTrue(7_000L in r.navi.times("tbt"))
    }

    @Test
    fun aCancelEventClearsTheRouteAtOnce() = runTest {
        val r = Rig(this)
        r.guide()
        r.navi.tbt = { listOf(TbtItem(TbtType.SERVICE_AREA, 9_000.0, "안성휴게소")) }
        r.start()
        advanceTimeBy(7_000)
        r.navi.mode = { NaviMode.SAFE_GUIDING }
        r.navi.changes.tryEmit(NaviChange.ROUTE_CANCELLED)
        runCurrent()
        assertFalse(r.snap.guiding)
        assertTrue(r.snap.opportunities.isEmpty())
        assertEquals(listOf(0L, 7_000L), r.navi.times("routeStatus"))
    }

    @Test
    fun aTbtAnswerThatRacedARouteChangeIsDropped() = runTest {
        val r = Rig(this)
        r.guide()
        r.navi.tbt = { listOf(TbtItem(TbtType.SERVICE_AREA, 30_000.0, "옛휴게소")) }
        r.navi.latencyMs["tbt"] = 2_000
        r.start()
        advanceTimeBy(1_000) // 0초에 물은 TBT가 아직 오는 중
        r.navi.tbt = { listOf(TbtItem(TbtType.SERVICE_AREA, 12_000.0, "새휴게소")) }
        r.navi.changes.tryEmit(NaviChange.WAYPOINT_CHANGED)
        runCurrent()
        advanceTimeBy(2_000) // 3_000: 옛 응답이 2_000에 왔고 버려졌다. 2_000에 경로를 다시 읽고 새 TBT를 묻는 중
        assertEquals(2, r.snap.epoch)
        assertFalse(r.snap.tbtReceived)
        advanceTimeBy(1_001)
        assertEquals(listOf("새휴게소"), r.snap.opportunities.map { it.name })
        assertTrue("옛휴게소는 한 번도 나가지 않았다", r.published.none { s -> s.opportunities.any { it.name == "옛휴게소" } })
        assertTrue(r.log.lines.any { "tbt dropped" in it })
    }

    @Test
    fun aKnownRouteChangeBlocksTbtUntilTheRouteIsReadAgain() = runTest {
        // 경로가 바뀐 줄 아는데 경로 상태를 못 읽었으면, 새로 받은 TBT를 옛 epoch(옛 남은 거리·목적지)에 붙이지 않는다
        val r = Rig(this)
        r.guide()
        r.start()
        advanceTimeBy(7_000)
        val tbtBefore = r.navi.count("tbt")
        r.navi.faults["routeStatus"] = Fault.THROW
        r.navi.changes.tryEmit(NaviChange.WAYPOINT_CHANGED)
        advanceTimeBy(13_001) // → 20_001: 경로 조회 7, 12, 17초 모두 실패
        assertEquals(listOf(0L, 7_000L, 12_000L, 17_000L), r.navi.times("routeStatus"))
        assertEquals("경로를 다시 읽을 때까지 TBT를 묻지 않는다", tbtBefore, r.navi.count("tbt"))
        assertEquals(1, r.snap.epoch)
        r.navi.faults.remove("routeStatus")
        advanceTimeBy(2_000) // 22_000에 성공
        assertEquals(2, r.snap.epoch)
        assertTrue(22_000L in r.navi.times("tbt"))
    }

    @Test
    fun aBurstOfEventsIsCoalescedToOneRequestPerSecond() = runTest {
        val r = Rig(this)
        r.guide()
        r.start()
        advanceTimeBy(7_000)
        repeat(5) {
            r.navi.changes.tryEmit(NaviChange.REROUTED)
            advanceTimeBy(200)
        }
        advanceTimeBy(2_000)
        val routes = r.navi.times("routeStatus").filter { it >= 7_000 }
        assertTrue("1초 안에 경로 조회를 두 번 하지 않는다: $routes", routes.zipWithNext().all { (a, b) -> b - a >= NaviTracker.MIN_REQUEST_GAP_MS })
        assertEquals(listOf(7_000L, 8_000L), routes)
    }

    @Test
    fun leavingGuidanceClearsRouteTbtAndTrust() = runTest {
        val r = Rig(this)
        r.guide()
        r.tbtAhead(restAtM = 80_000.0, turnAtM = 60_000.0)
        r.start()
        advanceTimeBy(40_000)
        assertTrue(r.snap.odometerTrusted)
        assertTrue(r.snap.tbtReceived)
        r.navi.mode = { NaviMode.SAFE_GUIDING } // 도착 또는 취소 (이벤트 없이)
        advanceTimeBy(5_001) // 45_000 경로 조회
        val s = r.snap
        assertEquals(2, s.epoch)
        assertEquals(RouteState(guiding = false), s.route)
        assertNull(s.destination)
        assertTrue(s.opportunities.isEmpty())
        assertFalse(s.tbtReceived)
        assertNull(s.tbtCoverageM)
        assertNull(s.nextManeuverM)
        assertFalse(s.odometerTrusted)
        val tbtCalls = r.navi.count("tbt")
        advanceTimeBy(30_000)
        assertEquals("길안내가 끝나면 TBT를 묻지 않는다", tbtCalls, r.navi.count("tbt"))
    }

    // ------------------------------------------------------------ TBT 데이터

    @Test
    fun anEmptyTbtIsReceivedButCoversNothing() = runTest {
        val r = Rig(this)
        r.guide()
        r.navi.tbt = { emptyList() }
        r.navi.latencyMs["tbt"] = 1_000
        r.start()
        runCurrent()
        assertTrue(r.snap.guiding)
        assertFalse("받기 전: 경로 위 시설을 모른다", r.snap.tbtReceived)
        assertNull(r.snap.tbtCoverageM)
        advanceTimeBy(1_001)
        assertTrue("받았다", r.snap.tbtReceived)
        assertEquals("빈 목록은 아무 구간도 덮지 않는다 (경로 위에 없다는 뜻이 아니다)", 0.0, r.snap.tbtCoverageM!!, 0.0)
        assertTrue(r.snap.opportunities.isEmpty())
        assertNull(r.snap.nextManeuverM)
    }

    @Test
    fun aTbtListShorterThanTheRouteReportsItsCoverage() = runTest {
        val r = Rig(this)
        r.guide(totalM = 300_000.0)
        r.navi.tbt = {
            listOf(TbtItem(TbtType.OTHER, 30_000.0, "분기"), TbtItem(TbtType.SERVICE_AREA, 20_000.0, "A휴게소"), TbtItem(TbtType.OTHER, 30_000.0, "분기"))
        }
        r.start()
        runCurrent()
        assertEquals(80_000.0, r.snap.tbtCoverageM!!, 1e-9)
        val remain = r.snap.liveRoute(r.odo.meters)!!.remainDistanceM!!
        assertTrue("80km 뒤 220km는 모른다", r.snap.liveTbtCoverageM(r.odo.meters)!! < remain - 1_000.0)
    }

    @Test
    fun aBrokenTbtDistanceStopsReadingThere() = runTest {
        val r = Rig(this)
        r.guide()
        r.navi.tbt = {
            listOf(
                TbtItem(TbtType.OTHER, 1_000.0, "분기"),
                TbtItem(TbtType.SERVICE_AREA, 5_000.0, "A휴게소"),
                TbtItem(TbtType.OTHER, Double.NaN, "?"),
                TbtItem(TbtType.SERVICE_AREA, 3_000.0, "B휴게소"),
            )
        }
        r.start()
        runCurrent()
        assertEquals(listOf("A휴게소" to 6_000.0), r.snap.opportunities.map { it.name to it.routeDistanceM })
        assertEquals("깨진 항목 앞까지만 안다", 6_000.0, r.snap.tbtCoverageM!!, 1e-9)
        assertTrue(r.log.lines.any { it.startsWith("W") && "bad distance" in it })
    }

    // ------------------------------------------------------------ 실패

    @Test
    fun aFailingTbtDoesNotStopRoutePolling() = runTest {
        val r = Rig(this)
        r.guide()
        r.navi.faults["tbt"] = Fault.THROW
        r.start()
        advanceTimeBy(60_001)
        assertEquals(every(15_000, 0, 60_000), r.navi.times("routeStatus"))
        assertEquals("5초 뒤 다시", every(5_000, 0, 60_000), r.navi.times("tbt"))
        assertTrue(r.snap.guiding)
        assertFalse(r.snap.tbtReceived)
        assertEquals("같은 실패는 처음과 1분에 한 번쯤만 남긴다", 2, r.log.lines.count { "navi tbt failed" in it })
        r.navi.faults.remove("tbt")
        advanceTimeBy(5_000)
        assertTrue(r.snap.tbtReceived)
        assertTrue(r.log.lines.any { "tbt ok after" in it })
    }

    @Test
    fun onceReceivedTheTbtStaysForTheRestOfTheEpoch() = runTest {
        // 후보 담당과의 약속: tbtReceived는 epoch가 바뀔 때만 false로 돌아간다. 뒤의 실패·무응답은 지난 TBT를 지우지 않는다
        val r = Rig(this)
        r.guide()
        r.navi.tbt = { listOf(TbtItem(TbtType.SERVICE_AREA, 9_000.0, "안성휴게소"), TbtItem(TbtType.OTHER, 1_000.0, "분기")) }
        r.start()
        runCurrent()
        val epoch = r.snap.epoch
        assertEquals(10_000.0, r.snap.tbtCoverageM!!, 0.0)
        r.navi.faults["tbt"] = Fault.THROW
        advanceTimeBy(30_000)
        r.navi.faults["tbt"] = Fault.HANG
        advanceTimeBy(30_000)
        assertEquals(epoch, r.snap.epoch)
        assertTrue(r.published.filter { it.epoch == epoch }.dropWhile { !it.tbtReceived }.all { it.tbtReceived && it.tbtCoverageM == 10_000.0 })
        assertEquals(listOf("안성휴게소"), r.snap.opportunities.map { it.name })
    }

    @Test
    fun aFailingRouteCallRetriesIn5sAndKeepsTheLastSnapshot() = runTest {
        val r = Rig(this)
        r.guide()
        r.start()
        advanceTimeBy(14_000)
        val before = r.snap
        r.navi.faults["routeStatus"] = Fault.THROW
        advanceTimeBy(11_001) // → 25_001
        assertEquals(listOf(0L, 15_000L, 20_000L, 25_000L), r.navi.times("routeStatus"))
        assertEquals(before.route, r.snap.route)
        assertEquals(before.epoch, r.snap.epoch)
        assertTrue("경로 조회가 실패해도 TBT는 계속 받는다", r.navi.times("tbt").containsAll(listOf(15_000L, 20_000L, 25_000L)))
    }

    @Test
    fun aFailingDestinationCallStillStartsGuidanceAndKeepsTheLastRemainLater() = runTest {
        val r = Rig(this)
        r.guide()
        r.navi.faults["destination"] = Fault.THROW
        r.start()
        runCurrent()
        assertTrue("경로가 있는 모드면 길안내로 보고 TBT를 받는다", r.snap.guiding)
        assertNull(r.snap.route!!.remainDistanceM)
        assertTrue(r.snap.tbtReceived)
        r.navi.faults.remove("destination")
        advanceTimeBy(5_001)
        assertEquals("남은 거리를 못 읽었으면 5초 뒤 다시 읽는다", listOf(0L, 5_000L), r.navi.times("routeStatus"))
        val remain = r.snap.route!!.remainDistanceM!!
        assertEquals(1, r.snap.epoch)
        // 같은 경로에서 목적지 조회만 실패하면 지난 남은 거리를 그대로 쓴다 (엔진 입력이 null로 깜박이지 않게)
        r.navi.faults["destination"] = Fault.THROW
        advanceTimeBy(15_000)
        assertEquals(remain, r.snap.route!!.remainDistanceM!!, 0.0)
        assertEquals(1, r.snap.epoch)
    }

    @Test
    fun aHangingNaviCallTimesOutAndNeverBlocksRequests() = runTest {
        val r = Rig(this)
        r.guide()
        r.navi.faults["tbt"] = Fault.HANG
        r.start()
        advanceTimeBy(1_000)
        r.tracker.requestRefresh(routeChanged = false) // 막히지 않고 돌아온다 (suspend가 아니다)
        assertTrue("스냅샷은 언제든 읽힌다", r.snap.guiding)
        advanceTimeBy(19_001) // 20_000에 0초의 TBT가 시간 초과
        assertTrue("시간 초과 뒤 밀린 경로 조회를 한다", 20_000L in r.navi.times("routeStatus"))
        assertTrue(r.log.lines.any { "navi tbt failed" in it && "no answer" in it })
    }

    // ------------------------------------------------------------ 답 없는 내비: 지난 스냅샷을 언제까지 믿나 (서비스 감사 SV-N5)

    private fun silence(r: Rig, fault: Fault) {
        for (call in listOf("routeStatus", "destination", "tbt")) r.navi.faults[call] = fault
    }

    @Test
    fun aNaviSilentForAMinuteIsUnknownAndComesBackAsANewRoute() = runTest {
        // 예전: 내비가 답하지 않는 동안 마지막 길안내 스냅샷을 끝없이 썼다 (경로·목적지·TBT가 몇 분 전 것인데 엔진은 지금 것으로 안다)
        val r = Rig(this)
        r.guide()
        r.tbtAhead(restAtM = 30_000.0, turnAtM = 40_000.0)
        r.start()
        advanceTimeBy(20_000)
        assertTrue(r.snap.guiding && r.snap.tbtReceived)
        val epoch = r.snap.epoch

        silence(r, Fault.THROW) // 20초부터 내비가 모든 조회에 실패한다. 다음 경로 조회는 30초
        advanceTimeBy(69_000) // → 89초: 실패가 59초째
        assertTrue("1분이 안 됐으면 지난 스냅샷을 그대로 둔다 (잠깐의 실패에 엔진 입력이 깜박이지 않게)", r.snap.guiding)
        assertEquals(epoch, r.snap.epoch)
        advanceTimeBy(2_000) // → 91초: 30초에 시작한 실패가 1분을 넘었다
        assertNull("경로를 모른다 (길안내 아님과 다르다)", r.snap.route)
        assertEquals(NaviMode.UNKNOWN, r.snap.mode)
        assertFalse(r.snap.guiding)
        assertEquals("길안내가 끝난 것처럼 epoch를 올린다: 옛 경로에서 만든 것이 새 경로와 섞이지 않는다", epoch + 1, r.snap.epoch)
        assertTrue(r.snap.opportunities.isEmpty() && !r.snap.tbtReceived && r.snap.destination == null && !r.snap.odometerTrusted)
        val published = r.published.size

        advanceTimeBy(120_000) // 계속 답이 없다
        assertEquals("한 번만 알린다", 1, r.log.lines.count { "navi silent" in it })
        assertEquals("같은 '모름'을 다시 내보내지 않는다", published, r.published.size)
        assertTrue("길안내가 아니니 TBT를 묻지 않는다", r.navi.times("tbt").last() < 95_000L)
        assertTrue("경로 상태는 계속 물어 본다", r.navi.times("routeStatus").last() > 200_000L)

        r.navi.faults.clear() // 내비가 돌아왔다
        advanceTimeBy(6_000)
        assertTrue("다음 답에서 곧바로 회복한다", r.snap.guiding)
        assertEquals("새 경로로 다시 시작한다 (그 사이 무슨 일이 있었는지 모른다)", epoch + 2, r.snap.epoch)
        assertTrue(r.snap.tbtReceived)
        assertFalse("출발지 기준 정체는 넣지 않는다 (경로 시작을 못 봤다)", r.tracker.congestion.routeOriginKnown)
        assertTrue(r.log.lines.any { "navi answering again" in it })
    }

    @Test
    fun aHangingNaviBecomesUnknownToo() = runTest {
        // 무응답(호출마다 20초 시간 초과)도 같다: 실패 수가 아니라 실패가 이어진 시간으로 잰다
        val r = Rig(this)
        r.guide()
        r.start()
        advanceTimeBy(10_000)
        r.navi.faults["routeStatus"] = Fault.HANG // 15초의 경로 조회부터 답이 없다 (TBT는 답한다)
        advanceTimeBy(60_000) // → 70초: 15초에 시작한 실패가 55초째 (조회 15~35, 40~60이 시간 초과)
        assertTrue(r.snap.guiding)
        assertEquals(listOf(0L, 15_000L, 40_000L, 65_000L), r.navi.times("routeStatus"))
        advanceTimeBy(20_000) // → 90초: 65~85 조회가 시간 초과한 85초에 1분을 넘었다
        assertNull(r.snap.route)
        assertEquals(1, r.log.lines.count { "navi silent" in it })
    }

    @Test
    fun aShortOutageKeepsTheLastSnapshot() = runTest {
        val r = Rig(this)
        r.guide()
        r.start()
        advanceTimeBy(20_000)
        val epoch = r.snap.epoch
        silence(r, Fault.THROW)
        advanceTimeBy(45_000) // 30초부터 75초까지 실패 (45초)
        r.navi.faults.clear()
        advanceTimeBy(60_000)
        assertTrue(r.snap.guiding)
        assertEquals("1분 안에 돌아오면 같은 경로다", epoch, r.snap.epoch)
        assertTrue("모름을 내보낸 적이 없다", r.published.none { it.route == null && it.epoch > 0 })
        assertEquals(0, r.log.lines.count { "navi silent" in it })
        // 실패가 이어진 시간은 답을 받으면 처음부터 다시 잰다
        silence(r, Fault.THROW)
        advanceTimeBy(45_000)
        assertTrue(r.snap.guiding)
    }

    @Test
    fun whileNotGuidingASilentNaviIsUnknownTooAndComesBackWithoutANewEpoch() = runTest {
        val r = Rig(this)
        r.navi.mode = { NaviMode.SAFE_GUIDING }
        r.start()
        advanceTimeBy(10_000)
        assertEquals(RouteState(guiding = false), r.snap.route)
        silence(r, Fault.THROW)
        advanceTimeBy(80_000)
        assertNull("길안내가 시작됐는지도 모른다", r.snap.route)
        assertEquals("경로가 없었으니 epoch는 그대로", 0, r.snap.epoch)
        r.navi.faults.clear()
        advanceTimeBy(6_000)
        assertEquals(RouteState(guiding = false), r.snap.route)
        assertEquals(0, r.snap.epoch)
    }

    @Test
    fun aNaviThatNeverAnsweredIsReportedOnceAndHasNothingToForget() = runTest {
        val r = Rig(this)
        silence(r, Fault.THROW)
        r.start()
        advanceTimeBy(200_000)
        assertNull(r.snap.route)
        assertEquals(0, r.snap.epoch)
        assertEquals("처음 상태(모름) 그대로라 내보낼 것이 없다", 1, r.published.size)
        assertEquals(1, r.log.lines.count { "navi silent" in it })
    }

    @Test
    fun aFailingConnectRetriesWithBackoff() = runTest {
        val r = Rig(this)
        r.navi.connectFailures = 3
        r.start()
        advanceTimeBy(30_000)
        assertEquals("2초부터 두 배씩", listOf(0L, 2_000L, 6_000L, 14_000L), r.navi.times("connect"))
        assertEquals(14_000L, r.navi.times("routeStatus").first())
    }

    // ------------------------------------------------------------ 지금 도로의 제한속도 (NearbyStops, 2026-11-13)

    @Test
    fun theRoadSpeedLimitIsKeptWithWhenItCameAndDoesNotTouchTheSnapshot() = runTest {
        val r = Rig(this)
        r.start()
        advanceTimeBy(2_000)
        val snapshots = r.published.size
        r.navi.roadSpeedLimits.emit(80)
        runCurrent()
        assertEquals(RoadLimit(80, 2_000L), r.tracker.roadLimit.value)
        assertEquals(80, r.tracker.roadLimit.value?.fresh(r.clock.elapsedMs()))
        assertEquals("매초 오는 값이 경로 스냅샷을 새로 만들지 않는다", snapshots, r.published.size)
        advanceTimeBy(RoadLimit.FRESH_MS + 1)
        assertNull("내비가 보내지 않으면 곧 모르는 것으로 본다", r.tracker.roadLimit.value?.fresh(r.clock.elapsedMs()))
    }

    @Test
    fun aPortThatSendsNoLimitsIsSubscribedOnce() = runTest {
        // 제한속도를 보내지 않는 구현(빈 흐름): 끝난 흐름을 2초마다 다시 구독하지 않는다. 실패한 흐름은 다시 구독한다
        val clock = ElapsedClock { testScheduler.currentTime }
        var subscriptions = 0
        var failFirst = true
        val quiet = object : NaviPort by FakeNaviPort(clock) {
            override val roadSpeedLimits = kotlinx.coroutines.flow.flow<Int> {
                subscriptions++
                if (failFirst) {
                    failFirst = false
                    throw IllegalStateException("speed limits failed (fake)")
                }
            }
        }
        val tracker = NaviTracker(quiet, Odometer(clock), { true }, clock)
        backgroundScope.launch { tracker.run() }
        advanceTimeBy(30_000)
        assertEquals("실패한 흐름은 한 번 다시, 끝난 흐름은 그만", 2, subscriptions)
        assertNull(tracker.roadLimit.value)
    }

    // ------------------------------------------------------------ 위치 대체

    @Test
    fun theLocationFallbackAsksTheNaviOnlyWhileWanted() = runTest {
        val r = Rig(this)
        var wanted = true
        val got = mutableListOf<Pair<Fix, Long>>()
        r.fallback = object : LocationFallback {
            override fun wanted() = wanted
            override fun onFix(fix: Fix, requestedAtMs: Long) {
                got += fix to requestedAtMs
            }
        }
        r.start()
        advanceTimeBy(10_001)
        assertEquals(every(5_000, 0, 10_000), r.navi.times("currentLocation"))
        assertEquals(listOf(0L, 5_000L, 10_000L), got.map { it.second })
        wanted = false
        advanceTimeBy(30_000)
        assertEquals(3, r.navi.count("currentLocation"))
    }

    @Test
    fun withThePositionTrackerAStaleFusedFeedIsReplacedAboutEvery20s() = runTest {
        val r = Rig(this)
        val positions = PositionTracker(r.clock) { true } // 달리는 중, 위치 SDK는 아무것도 주지 않는다
        r.fallback = positions.naviFallback { true }
        r.start()
        advanceTimeBy(50_001)
        assertEquals(listOf(0L, 20_000L, 40_000L), r.navi.times("currentLocation"))
        assertEquals(Located.Source.NAVI, positions.current.value?.source)
    }

    // ------------------------------------------------------------ 일관성

    /**
     * 시작·재탐색·이벤트·실패·취소·다시 시작을 섞은 대본에서 내보낸 스냅샷이 모두 일관된지 본다.
     * 가짜 내비는 경로 번호를 목적지 이름과 TBT 이름에 붙인다. 옛 경로의 TBT가 새 경로와 짝이 되면 번호가 어긋난다.
     */
    @Test
    fun everyPublishedSnapshotIsInternallyConsistent() = runTest {
        val r = Rig(this)
        var routeNo = 0
        var total = 0.0
        fun newRoute(totalM: Double) {
            routeNo++
            total = totalM + r.odo.meters
        }
        r.navi.mode = { NaviMode.SAFE_GUIDING }
        r.navi.destination = { dest(total - r.odo.meters, name = "목적지#$routeNo", lat = 37.0 + routeNo * 0.01) }
        r.navi.tbt = {
            listOf(
                TbtItem(TbtType.OTHER, 2_000.0, "분기"),
                TbtItem(TbtType.SERVICE_AREA, 6_000.0, "휴게소#$routeNo"),
                TbtItem(TbtType.REST_AREA, 10_000.0, "졸음쉼터#$routeNo"),
            )
        }
        r.navi.latencyMs["tbt"] = 700
        r.start()
        advanceTimeBy(20_500)
        newRoute(150_000.0)
        r.navi.mode = { NaviMode.ROUTE_GUIDING }
        r.tracker.requestRefresh()
        advanceTimeBy(50_000) // 70_500: 신뢰를 얻었다
        newRoute(90_000.0) // 이벤트와 함께 재탐색
        r.navi.changes.tryEmit(NaviChange.REROUTED)
        advanceTimeBy(20_000)
        r.navi.faults["tbt"] = Fault.THROW
        advanceTimeBy(12_000)
        r.navi.faults.remove("tbt")
        advanceTimeBy(8_300)
        newRoute(60_000.0) // 목적지 변경, TBT를 묻는 도중에 이벤트
        r.navi.changes.tryEmit(NaviChange.DESTINATION_CHANGED)
        advanceTimeBy(25_000)
        r.navi.mode = { NaviMode.SAFE_GUIDING }
        r.navi.changes.tryEmit(NaviChange.ROUTE_CANCELLED)
        advanceTimeBy(20_000)
        newRoute(40_000.0) // 이벤트 없이 다시 길안내
        r.navi.mode = { NaviMode.SIMULATION_GUIDING }
        advanceTimeBy(40_000)

        assertTrue(r.published.size > 20)
        var lastEpoch = 0
        for (s in r.published) {
            val where = "snapshot $s"
            assertTrue("epoch는 줄지 않는다: $where", s.epoch >= lastEpoch)
            lastEpoch = s.epoch
            s.route?.let { assertEquals("route.guiding은 mode와 같다: $where", s.mode.hasRoute, it.guiding) }
            if (!s.guiding) {
                assertNull(where, s.destination)
                assertTrue(where, s.opportunities.isEmpty())
                assertFalse(where, s.tbtReceived)
                assertNull(where, s.tbtCoverageM)
                assertNull(where, s.nextManeuverM)
                assertFalse(where, s.odometerTrusted)
            }
            if (s.tbtReceived) assertEquals("받은 TBT는 이번 epoch의 것: $where", s.epoch, s.tbtEpoch)
            else {
                assertTrue(where, s.opportunities.isEmpty())
                assertNull(where, s.tbtCoverageM)
                assertNull(where, s.nextManeuverM)
            }
            val destNo = s.destination?.name?.substringAfter('#')?.toInt()
            val tbtNo = s.opportunities.firstOrNull()?.name?.substringAfter('#')?.toInt()
            if (destNo != null && tbtNo != null) assertTrue("옛 경로의 TBT가 새 경로와 짝이 되지 않는다: $where", tbtNo >= destNo)
        }
        assertEquals("다시 시작한 길안내까지 epoch: 시작, 재탐색, 목적지 변경, 취소, 다시 시작", 5, r.snap.epoch)
        assertTrue(r.snap.guiding)
        assertEquals("졸음쉼터#4", r.snap.opportunities.last().name)
    }
}
