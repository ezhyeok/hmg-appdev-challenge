package dev.shimpyo.app.ui

import dev.shimpyo.app.ui.components.StripMark
import dev.shimpyo.app.ui.components.StripSpanKind
import dev.shimpyo.coach.CandidatesState
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.PitStopPlan
import dev.shimpyo.engine.PlannedStop
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 경로 앞 정보의 거리 축 (restroomGaps, travelSeconds, stripData, livePlan) */
class RouteModelTest {

    private fun opp(id: String, km: Double?, restroom: Boolean = true) = StopCandidate(
        id, id, StopKind.HIGHWAY_REST_AREA, 0.0, 0.0, Facilities(restroom = restroom), true, routeDistanceM = km?.times(1_000.0),
    )

    @Test
    fun travelSecondsAddsOnlyCongestionExcess() {
        val jam = listOf(CongestionSegment(startDistanceM = 10_000.0, lengthM = 10_000.0, durationS = 1_800.0))
        // 0→30km, 20m/s: 1,500초. 정체 10km(평소 500초)가 1,800초로 → 2,800초
        assertEquals(2_800.0, travelSeconds(0.0, 30_000.0, jam, 20.0), 1.0)
        // 정체 절반만 겹침: 750 + (900 - 250)
        assertEquals(1_400.0, travelSeconds(0.0, 15_000.0, jam, 20.0), 1.0)
        // 거꾸로(b < a)는 0
        assertEquals(0.0, travelSeconds(5_000.0, 1_000.0, emptyList(), 20.0), 0.0)
    }

    @Test
    fun gapBetweenOpportunitiesIsMarkedCongestedOnlyWhenJamOverlaps() {
        val s = CoachUiState(
            guiding = true, remainDistanceM = 200_000.0, remainTimeS = 9_000.0,
            ahead = listOf(opp("a", 10.0), opp("b", 60.0), opp("c", 100.0)),
            congestion = listOf(CongestionSegment(70_000.0, 10_000.0, 2_400.0)),
        )
        val gaps = restroomGaps(s)
        assertEquals(false, gaps.single { it.startM == 10_000.0 }.congested)
        assertEquals(true, gaps.single { it.startM == 60_000.0 }.congested)
    }

    @Test
    fun gapFromCarToFirstOpportunityIsReported() {
        // #20: 60분 정체 한가운데, 첫 화장실은 정체 뒤 20km
        val s = CoachUiState(
            guiding = true, remainDistanceM = 200_000.0, remainTimeS = 9_000.0,
            ahead = listOf(opp("a", 20.0), opp("b", 30.0)),
            congestion = listOf(CongestionSegment(0.0, 18_000.0, 3_600.0)),
        )
        assertTrue(restroomGaps(s).toString(), restroomGaps(s).any { it.startM == 0.0 && it.minutes >= 60 && it.congested })
    }

    @Test
    fun noGapsWhenSomeRestroomCandidateHasUnknownRouteDistance() {
        // 경로 거리를 모르는 화장실이 그 사이에 있을 수 있다: 공백이라고 말하지 않는다
        val s = CoachUiState(ahead = listOf(opp("a", 10.0), opp("x", null), opp("b", 90.0)))
        assertTrue(restroomGaps(s).isEmpty())
    }

    @Test
    fun opportunitiesIgnoreUnknownDistanceNoRestroomAndBeyondDestination() {
        val s = CoachUiState(ahead = listOf(opp("a", null), opp("b", 5.0, restroom = false), opp("c", 3.0)))
        assertEquals(listOf("c"), opportunities(s).map { it.id })
        val guiding = CoachUiState(guiding = true, remainDistanceM = 20_000.0, ahead = listOf(opp("a", 10.0), opp("b", 35.0), opp("dest", 20.3)))
        assertEquals(listOf("a", "dest"), opportunities(guiding).map { it.id })
    }

    @Test
    fun congestionInsideJamUsesRemainingPart() {
        val s = CoachUiState(guiding = true, congestion = listOf(CongestionSegment(-5_000.0, 15_000.0, 1_800.0)))
        val span = stripData(s).spans.single { it.kind == StripSpanKind.CONGESTION }
        assertEquals(0.0, span.startM, 0.0)
        assertEquals(10_000.0, span.endM, 0.0)
        // 남은 2/3 구간의 지연 (30분 전체가 아니라)
        assertEquals("정체 20분", span.label)
        assertEquals("지금부터 10km", congestionDetail(CongestionSegment(-5_000.0, 15_000.0, 1_800.0)))
        assertEquals("경부 · 3km 앞부터 18km", congestionDetail(CongestionSegment(3_000.0, 18_000.0, 3_600.0, roadName = "경부")))
        // 이미 지나간 정체는 길이 0
        assertEquals(0.0, remainingCongestion(CongestionSegment(-20_000.0, 10_000.0, 600.0)).lengthM, 0.0)
    }

    @Test
    fun stripAxisAtArrivalDoesNotCollapse() {
        val s = CoachUiState(guiding = true, remainDistanceM = 0.0, ahead = listOf(opp("a", 5.0)))
        assertTrue(stripData(s).axisM >= 1_000.0)
    }

    @Test
    fun nearFocusKeepsRecommendedStopVisible() {
        val far = (1..8).map { opp("o$it", it * 15.0) }
        val s = CoachUiState(guiding = true, remainDistanceM = 300_000.0, ahead = far, next = null)
        val d = stripData(s, maxAxisM = 120_000.0, nearFocus = true)
        assertTrue(d.axisM in 30_000.0..120_000.0)
        assertTrue(d.stops.any { it.mark == StripMark.OPPORTUNITY && it.distanceM <= d.axisM })
    }

    @Test
    fun plannedPositionPrefersLiveCandidate() {
        // #4: 계획 때 52km였던 곳이 지금 ahead에서 2km면 계획 정차 위치도 2km
        val p = PlannedStop(opp("anseong", 52.0), setOf(Purpose.RESTROOM), 10, 2_700.0)
        val s = CoachUiState(guiding = true, plan = PitStopPlan(listOf(p), 10), ahead = listOf(opp("anseong", 2.0)))
        assertEquals(2_000.0, stripData(s).stops.single { it.mark == StripMark.PLANNED }.distanceM, 0.0)
        assertEquals(2_000.0, plannedDistance(p, s), 0.0)
        // 목록에 없으면 계획 때 값 (어림으로 표시)
        val gone = s.copy(ahead = emptyList())
        assertEquals(52_000.0, plannedDistance(p, gone), 0.0)
        assertTrue(livePlan(gone).single().estimated)
    }

    @Test
    fun plannedStopWithoutDistanceIsDrawnAtCarNotDropped() {
        // #4b: 경로 거리도 직선거리도 없고 도착 0초(PLAN_NOW) → 지금 위치. 예전에는 계획 화면에서 조용히 빠졌다
        val p = PlannedStop(opp("x", null), setOf(Purpose.RESTROOM), 5, arriveInS = 0.0)
        assertEquals(0.0, plannedDistance(p, CoachUiState()), 0.0)
        assertEquals(1, livePlan(CoachUiState(plan = PitStopPlan(listOf(p), 5))).size)
    }

    @Test
    fun livePlanDropsPassedStopsAndRenumbers() {
        val a = PlannedStop(opp("anseong", 52.0), setOf(Purpose.RESTROOM), 10, 2_700.0)
        val b = PlannedStop(opp("oksan", 118.0), setOf(Purpose.REST), 10, 5_400.0)
        val s = CoachUiState(guiding = true, remainDistanceM = 200_000.0, plan = PitStopPlan(listOf(a, b), 20), ahead = listOf(opp("oksan", 60.0)))
        val live = livePlan(s)
        assertEquals(listOf("oksan"), live.map { it.stop.id })
        assertEquals(1, live.single().number)
        assertFalse(live.single().estimated)
        // 앞 정차가 목록에 없어도 뒤 정차도 없으면(목록 범위 밖) 지나쳤다고 하지 않는다
        val bothGone = s.copy(ahead = emptyList())
        assertEquals(listOf("anseong", "oksan"), livePlan(bothGone).map { it.stop.id })
    }

    @Test
    fun stalePlanDistanceIsNotComparedWithRemainingDistance() {
        // 계획 때 52km였던 정차. 그 뒤 40km를 달려 남은 거리는 30km다. 후보 목록에는 없다(지나쳤다는 증거도 없음)
        val p = PlannedStop(opp("anseong", 52.0), setOf(Purpose.RESTROOM), 10, 2_700.0)
        val s = CoachUiState(guiding = true, remainDistanceM = 30_000.0, plan = PitStopPlan(listOf(p), 10), ahead = emptyList())
        assertEquals(listOf("anseong"), livePlan(s).map { it.stop.id })
        // 지금 후보 값이 목적지보다 멀면 뺀다
        assertTrue(livePlan(s.copy(ahead = listOf(opp("anseong", 45.0)))).isEmpty())
    }

    @Test
    fun livePlanEtaIsLive() {
        val p = PlannedStop(opp("anseong", 52.0), setOf(Purpose.RESTROOM), 10, arriveInS = 2_700.0)
        val s = CoachUiState(guiding = true, remainDistanceM = 150_000.0, remainTimeS = 6_000.0, ahead = listOf(opp("anseong", 10.0)), plan = PitStopPlan(listOf(p), 10))
        // 25m/s로 10km = 400초 (계획 때 2,700초가 아니라)
        assertEquals(400.0, livePlan(s).single().etaS!!, 1.0)
    }

    @Test
    fun namesAndPlaces() {
        assertEquals("안성휴게소", shortStopName("안성휴게소(부산방향)"))
        assertEquals("(부산방향)", shortStopName("(부산방향)"))
        assertEquals("청주IC–남이JC", congestionPlace(CongestionSegment(0.0, 1.0, 1.0, roadName = "경부", startName = "청주IC", endName = "남이JC")))
        assertEquals("경부", congestionPlace(CongestionSegment(0.0, 1.0, 1.0, roadName = "경부", startName = "시연A", endName = "")))
        assertNull(congestionPlace(CongestionSegment(0.0, 1.0, 1.0)))
    }

    @Test
    fun routeSpeedFallsBackAndClamps() {
        assertEquals(22.0, routeSpeedMps(CoachUiState()), 0.0)
        assertEquals(33.0, routeSpeedMps(CoachUiState(remainDistanceM = 100_000.0, remainTimeS = 100.0)), 0.0)
        assertEquals(5.0, routeSpeedMps(CoachUiState(remainDistanceM = 1_000.0, remainTimeS = 3_600.0)), 0.0)
    }

    @Test
    fun lastPlannedStopPassedWhenTheRouteListReachesBeyondIt() {
        // 재검토 #5: 길안내 중 경로 위 목록(TBT)이 계획 때 위치(52km)보다 멀리까지 오는데 그 정차가 없다 → 지나쳤다
        val a = PlannedStop(opp("anseong", 52.0), setOf(Purpose.RESTROOM), 10, 2_700.0)
        val s = CoachUiState(guiding = true, candidatesState = CandidatesState.READY, plan = PitStopPlan(listOf(a), 10), ahead = listOf(opp("b", 60.0)))
        assertTrue(livePlan(s).isEmpty())
        // 목록이 그만큼 멀리 오지 않으면 모른다: 남기되 계획 때 값임을 밝히고 지금 ETA는 없다
        val short = s.copy(ahead = listOf(opp("b", 30.0)))
        val kept = livePlan(short).single()
        assertNull(kept.etaS)
        assertEquals("계획 때 52km", plannedDistanceText(kept))
        // 길안내가 아니면(반경 후보) 목록이 빠짐없다고 볼 수 없어 추론하지 않는다
        assertEquals(1, livePlan(s.copy(guiding = false)).size)
    }

    @Test
    fun plannedDistanceTextNamesItsSource() {
        val p = PlannedStop(opp("x", null), setOf(Purpose.RESTROOM), 5, arriveInS = 0.0)
        assertEquals("지금", plannedDistanceText(livePlan(CoachUiState(plan = PitStopPlan(listOf(p), 5))).single()))
        val live = PlannedStop(opp("a", 52.0), setOf(Purpose.RESTROOM), 5, 100.0)
        val s = CoachUiState(plan = PitStopPlan(listOf(live), 5), ahead = listOf(opp("a", 2.0)))
        assertEquals("2km", plannedDistanceText(livePlan(s).single()))
        // 지금 후보에 거리가 하나도 없으면 계획 때 값 ("약"이 아니라 '계획 때'로)
        val noDist = s.copy(ahead = listOf(opp("a", null)))
        assertEquals("계획 때 52km", plannedDistanceText(livePlan(noDist).single()))
        assertNull(livePlan(noDist).single().etaS)
    }
}
