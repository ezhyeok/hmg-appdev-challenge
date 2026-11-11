package dev.shimpyo.app.ui.plan

import dev.shimpyo.coach.CandidatesState
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.PitStopPlan
import dev.shimpyo.engine.PlannedStop
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.Reason
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 계획 화면: 타임라인(timeline)과 요약(planSummary). UI 감사 #4, #5 */
class PlanTimelineTest {

    private fun stop(id: String, km: Double?, eta: Double? = null) = StopCandidate(
        id, id, StopKind.HIGHWAY_REST_AREA, 0.0, 0.0, Facilities(), true, routeDistanceM = km?.times(1_000.0), etaS = eta,
    )

    private fun note(code: String, text: String = code) = Reason(code, text)

    // ---------------------------------------------------------------- 타임라인

    @Test
    fun orderAtSameDistanceIsNowStopGapCongestionDestination() {
        val s = CoachUiState(
            guiding = true, remainDistanceM = 100_000.0, remainTimeS = 4_000.0,
            ahead = listOf(stop("a", 0.0), stop("b", 80.0)),
            congestion = listOf(CongestionSegment(0.0, 20_000.0, 3_000.0)),
        )
        val kinds = timeline(s).map { it::class.simpleName }
        assertEquals(listOf("Now", "Stop", "Gap", "Congestion", "Stop", "Destination"), kinds)
    }

    @Test
    fun plannedStopEtaIsLiveNotPlanTime() {
        // 30분 전에 만든 계획: 안성 52km·45분. 지금 ahead(TBT)에서는 10km, etaS 없음
        val plan = PitStopPlan(listOf(PlannedStop(stop("anseong", 52.0), setOf(Purpose.RESTROOM), 10, arriveInS = 2_700.0)), 10)
        val s = CoachUiState(guiding = true, remainDistanceM = 150_000.0, remainTimeS = 6_000.0, ahead = listOf(stop("anseong", 10.0)), plan = plan)
        val row = timeline(s).filterIsInstance<TimelineRow.Stop>().single()
        assertEquals(10_000.0, row.distanceM, 0.0)
        assertTrue("ETA ${row.etaS}s is the plan-time value", row.etaS!! < 1_000.0)
        assertEquals(1, row.number)
    }

    @Test
    fun everyPlannedStopAppearsInTimeline() {
        // PLAN_NOW: 경로 거리를 모르고 도착 0초 → 지금 위치에 그린다 (예전에는 조용히 빠졌다)
        val p = PlannedStop(stop("here", null), setOf(Purpose.RESTROOM), 5, arriveInS = 0.0)
        val s = CoachUiState(plan = PitStopPlan(listOf(p), 5, listOf(Reason("PLAN_NOW", "지금 쉬어야 할 때예요. here에 바로 들르세요"))))
        val row = timeline(s).filterIsInstance<TimelineRow.Stop>().single { it.planned != null }
        assertEquals(0.0, row.distanceM, 0.0)
        assertTrue(row.estimated)
    }

    @Test
    fun passedPlannedStopIsNotShownAhead() {
        val plan = PitStopPlan(
            listOf(
                PlannedStop(stop("anseong", 52.0), setOf(Purpose.RESTROOM), 10, 2_700.0),
                PlannedStop(stop("oksan", 118.0), setOf(Purpose.REST), 10, 5_400.0),
            ),
            20,
        )
        val s = CoachUiState(guiding = true, remainDistanceM = 120_000.0, ahead = listOf(stop("oksan", 60.0)), plan = plan)
        assertEquals(listOf("oksan"), timeline(s).filterIsInstance<TimelineRow.Stop>().map { it.stop.id })
    }

    @Test
    fun noStopsAfterDestination() {
        // 길안내 중 TBT가 비어 반경 후보(60km)로 대신하면 목적지 뒤 휴게소가 섞일 수 있다
        val s = CoachUiState(guiding = true, remainDistanceM = 20_000.0, ahead = listOf(stop("a", 10.0), stop("b", 35.0)))
        val rows = timeline(s)
        val dest = rows.indexOfFirst { it is TimelineRow.Destination }
        assertTrue(rows.drop(dest + 1).none { it is TimelineRow.Stop })
    }

    @Test
    fun congestionInsideJamUsesRemainingPart() {
        val s = CoachUiState(guiding = true, congestion = listOf(CongestionSegment(-6_000.0, 18_000.0, 3_600.0)))
        val row = timeline(s).filterIsInstance<TimelineRow.Congestion>().single()
        assertEquals(0.0, row.distanceM, 0.0)
        // 남은 12km·40분 ("정체 1시간 · 0m 앞부터 18km"가 아니라)
        assertEquals(12_000.0, row.segment.lengthM, 0.0)
        assertEquals(2_400.0, row.segment.durationS, 1.0)
    }

    @Test
    fun emptyTimelineTextFollowsCandidatesState() {
        assertEquals("정차 데이터를 읽는 중이에요", noOpportunityText(CandidatesState.LOADING_DATA))
        assertEquals("위치를 확인하는 중이에요", noOpportunityText(CandidatesState.NO_LOCATION))
        assertEquals("이 경로에는 화장실 기회 정보가 없어요", noOpportunityText(CandidatesState.READY))
    }

    // ---------------------------------------------------------------- 요약 (#5)

    private fun summary(plan: PitStopPlan?, ahead: List<StopCandidate> = emptyList(), cs: CandidatesState = CandidatesState.READY) =
        planSummary(CoachUiState(plan = plan, ahead = ahead, candidatesState = cs))

    @Test
    fun noCandidatesIsNotNoStopNeeded() {
        val s = summary(PitStopPlan(emptyList(), 0, listOf(note("PLAN_NO_CANDIDATES", "경로 앞 정차 후보가 없어요"))))
        assertEquals("정차 후보를 찾지 못했어요", s.headline)
        // 첫 줄로 쓴 메모는 아래에 다시 보이지 않는다
        assertTrue(s.notes.isEmpty())
        assertEquals("위치를 확인하는 중이에요", summary(PitStopPlan(emptyList(), 0, listOf(note("PLAN_NO_CANDIDATES"))), cs = CandidatesState.NO_LOCATION).detail)
    }

    @Test
    fun partialPlanDoesNotClaimWholeTrip() {
        val s = summary(
            PitStopPlan(emptyList(), 0, listOf(note("PLAN_NONE_NEEDED", "앞의 후보까지는 쉬지 않고 가도 돼요"), note("PLAN_PARTIAL", "앞 30km 안의 휴게소까지만 계획했어요"))),
        )
        assertEquals("앞 후보까지는 쉬지 않아도 돼요", s.headline)
        assertEquals(listOf("앞 30km 안의 휴게소까지만 계획했어요"), s.notes.map { it.text })
        assertEquals("앞 후보까지는 쉬지 않아도 돼요", summary(PitStopPlan(emptyList(), 0, listOf(note("PLAN_NONE_NEEDED"), note("PLAN_NO_DESTINATION")))).headline)
    }

    @Test
    fun noneNeededToDestinationSaysSo() {
        assertEquals("목적지까지 쉬지 않아도 돼요", summary(PitStopPlan(emptyList(), 0, listOf(note("PLAN_NONE_NEEDED", "목적지까지 쉬지 않고 가도 돼요")))).headline)
    }

    @Test
    fun planNowLeadsAndGapAndFuelShortAreWarnings() {
        val here = stop("here", 0.5)
        val s = summary(
            PitStopPlan(
                listOf(PlannedStop(here, setOf(Purpose.RESTROOM), 5, 30.0)), 5,
                listOf(note("PLAN_NOW", "지금 쉬어야 할 때예요. here에 바로 들르세요"), note("PLAN_FUEL_SHORT", "주유할 곳이 모자라요"), note("PLAN_MEAL", "식사")),
            ),
            ahead = listOf(here),
        )
        assertEquals("지금 쉬어야 해요", s.headline)
        assertEquals("정차 1번 · 쉬는 시간 5분", s.detail)
        assertEquals(listOf(true, true, false), s.notes.map { it.warning })
    }

    @Test
    fun allPlannedStopsPassedAsksToRecalculate() {
        val a = PlannedStop(stop("a", 5.0), setOf(Purpose.RESTROOM), 5, 200.0)
        val s = planSummary(CoachUiState(guiding = true, remainDistanceM = 2_000.0, plan = PitStopPlan(listOf(a), 5), ahead = listOf(stop("a", 5.0))))
        // 목적지(2km)보다 먼 정차만 남았다(목적지를 바꿨다) → 보일 정차가 없다
        assertEquals("지금 경로에 남은 계획 정차가 없어요", s.headline)
    }

    @Test
    fun noPlanYet() {
        val s = summary(null, cs = CandidatesState.LOADING_DATA)
        assertEquals("아직 계획이 없어요", s.headline)
        assertEquals("정차 데이터를 읽는 중이에요", s.detail)
        assertNull(summary(null).detail)
        assertNotEquals("쉬지 않고 가도 돼요", s.headline)
        assertFalse(s.notes.any())
    }
}
