package dev.shimpyo.app.ui.urgent

import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.UrgentState
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.RankedStop
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 급해 화면 상태(urgentView). 요청 번호로 코치의 진행 상태와 짝짓고, 닫은 요청의 늦은 결과는 다시 띄우지 않는다.
 * UI 감사 #7(닫아도 결과가 다시 나타남), #8(위치가 없으면 8초 뒤 지어낸 안내), #14(다시 만들어져도 이어짐)
 */
class UrgentModelTest {

    private val r1 = RankedStop(StopCandidate("u1", "역삼 공영주차장 화장실", StopKind.PUBLIC_PARKING, 0.0, 0.0, Facilities(), true), 190.0, emptyList())
    private val on = CoachUiState(serviceRunning = true)

    private fun coach(id: Long, st: UrgentState, results: List<RankedStop>? = null) = on.copy(urgentRequestId = id, urgentState = st, urgent = results)

    @Test
    fun followsCoachStatesForOurRequest() {
        assertEquals(UrgentStatus.LOCATING, urgentView(7, null, coach(7, UrgentState.LOCATING), false, null)!!.status)
        assertEquals(UrgentStatus.SEARCHING, urgentView(7, null, coach(7, UrgentState.SEARCHING), false, null)!!.status)
        assertEquals(UrgentStatus.NO_LOCATION, urgentView(7, null, coach(7, UrgentState.NO_LOCATION), false, null)!!.status)
        assertEquals(UrgentStatus.NO_RESULT, urgentView(7, null, coach(7, UrgentState.NO_RESULT), false, null)!!.status)
        val ready = urgentView(7, null, coach(7, UrgentState.READY, listOf(r1)), false, null)!!
        assertEquals(UrgentStatus.READY, ready.status)
        assertEquals(listOf(r1), ready.results)
        // READY인데 목록이 비면 결과 없음
        assertEquals(UrgentStatus.NO_RESULT, urgentView(7, null, coach(7, UrgentState.READY, emptyList()), false, null)!!.status)
    }

    @Test
    fun beforeAckShowsLocatingThenNoResponseNeverInventsNoLocation() {
        // 코치가 아직 다른(앞) 번호를 보고 있고 진행 중도 아니다
        val idle = coach(3, UrgentState.IDLE)
        assertEquals(UrgentStatus.LOCATING, urgentView(7, null, idle, ackExpired = false, picking = null)!!.status)
        val late = urgentView(7, null, idle, ackExpired = true, picking = null)!!
        assertEquals(UrgentStatus.NO_RESPONSE, late.status)
        assertNotEquals(UrgentStatus.NO_LOCATION, late.status)
    }

    @Test
    fun coachEndingOurRequestClosesTheScreen() {
        // 경유지를 넣으면 코치가 요청을 끝낸다(IDLE, 같은 번호)
        assertNull(urgentView(7, null, coach(7, UrgentState.IDLE), false, picking = "u1"))
    }

    @Test
    fun dismissedRequestsLateResultDoesNotReopen() {
        // #7: 찾는 중에 닫았다. 코치가 아직 DismissUrgent를 처리하기 전에 결과를 냈다
        val late = coach(7, UrgentState.READY, listOf(r1))
        assertNull(urgentView(requested = null, dismissed = 7, state = late, ackExpired = false, picking = null))
        // 코치가 그 요청을 끝내면 닫은 번호는 잊는다
        assertEquals(7L, settleDismissed(7, late))
        assertNull(settleDismissed(7, coach(7, UrgentState.IDLE)))
    }

    @Test
    fun coachInitiatedUrgentOpensTheScreen() {
        // 음성 "화장실 급해": 화면은 요청하지 않았지만 코치가 새 번호로 진행 중이다
        val v = urgentView(null, dismissed = 3, state = coach(9, UrgentState.SEARCHING), ackExpired = false, picking = null)!!
        assertEquals(9L, v.requestId)
        assertEquals(UrgentStatus.SEARCHING, v.status)
        assertNull(urgentView(null, null, coach(9, UrgentState.IDLE), false, null))
    }

    @Test
    fun serviceOffIsSaidPlainly() {
        assertEquals(UrgentStatus.SERVICE_OFF, urgentView(7, null, CoachUiState(serviceRunning = false), false, null)!!.status)
    }

    @Test
    fun legacyCoachWithoutRequestIds() {
        // 옛 CoachService: 번호 0, 상태 IDLE, 결과는 urgent 목록뿐
        assertEquals(UrgentStatus.LOCATING, urgentView(7, null, on, false, null)!!.status)
        assertEquals(UrgentStatus.READY, urgentView(7, null, on.copy(urgent = listOf(r1)), false, null)!!.status)
        assertEquals(UrgentStatus.NO_RESULT, urgentView(7, null, on.copy(urgent = emptyList()), false, null)!!.status)
        // 옛 코치는 위치가 없으면 결과 없이 끝난다
        assertEquals(UrgentStatus.NO_LOCATION, urgentView(7, null, on, ackExpired = true, picking = null)!!.status)
        // 고른 뒤 목록이 지워졌다 = 경유지를 넣었다
        assertNull(urgentView(7, null, on, false, picking = "u1"))
        // 음성으로 연 급해(번호 0)를 닫으면 목록이 지워질 때까지 다시 열지 않는다
        assertNull(urgentView(null, dismissed = 0, state = on.copy(urgent = listOf(r1)), ackExpired = false, picking = null))
        assertNull(settleDismissed(0, on))
    }

    @Test
    fun requestIdsAreUniqueAndNonZero() {
        val ids = (1..100).map { UrgentRequestIds.next() }
        assertEquals(100, ids.toSet().size)
        assertTrue(ids.all { it > 0 })
    }
}
