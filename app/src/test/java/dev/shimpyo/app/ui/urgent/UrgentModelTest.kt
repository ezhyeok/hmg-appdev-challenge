package dev.shimpyo.app.ui.urgent

import dev.shimpyo.app.ui.GoToBusy
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.UrgentState
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind
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
        assertEquals(UrgentStatus.LOCATING, urgentView(7, emptySet(), coach(7, UrgentState.LOCATING), false, null)!!.status)
        assertEquals(UrgentStatus.SEARCHING, urgentView(7, emptySet(), coach(7, UrgentState.SEARCHING), false, null)!!.status)
        assertEquals(UrgentStatus.NO_LOCATION, urgentView(7, emptySet(), coach(7, UrgentState.NO_LOCATION), false, null)!!.status)
        assertEquals(UrgentStatus.NO_RESULT, urgentView(7, emptySet(), coach(7, UrgentState.NO_RESULT), false, null)!!.status)
        val ready = urgentView(7, emptySet(), coach(7, UrgentState.READY, listOf(r1)), false, null)!!
        assertEquals(UrgentStatus.READY, ready.status)
        assertEquals(listOf(r1), ready.results)
        // READY인데 목록이 비면 결과 없음
        assertEquals(UrgentStatus.NO_RESULT, urgentView(7, emptySet(), coach(7, UrgentState.READY, emptyList()), false, null)!!.status)
    }

    @Test
    fun beforeAckShowsLocatingThenNoResponseNeverInventsNoLocation() {
        // 코치가 아직 다른(앞) 번호를 보고 있고 진행 중도 아니다
        val idle = coach(3, UrgentState.IDLE)
        assertEquals(UrgentStatus.LOCATING, urgentView(7, emptySet(), idle, ackExpired = false, picking = null)!!.status)
        val late = urgentView(7, emptySet(), idle, ackExpired = true, picking = null)!!
        assertEquals(UrgentStatus.NO_RESPONSE, late.status)
        assertNotEquals(UrgentStatus.NO_LOCATION, late.status)
    }

    @Test
    fun coachEndingOurRequestClosesTheScreen() {
        // 경유지를 넣으면 코치가 요청을 끝낸다(IDLE, 같은 번호)
        assertNull(urgentView(7, emptySet(), coach(7, UrgentState.IDLE), false, picking = "u1"))
    }

    @Test
    fun dismissedRequestsLateResultDoesNotReopen() {
        // #7: 찾는 중에 닫았다. 코치가 아직 DismissUrgent를 처리하기 전에 결과를 냈다
        val late = coach(7, UrgentState.READY, listOf(r1))
        assertNull(urgentView(requested = null, dismissed = setOf(7L), state = late, ackExpired = false, picking = null))
        assertEquals(setOf(7L), settleDismissed(setOf(7L), late))
    }

    @Test
    fun closingBeforeTheCoachAcknowledgesDoesNotReopenOnTheLateAck() {
        // 보낸 직후 닫았다. 코치는 아직 앞 번호(3, IDLE)를 보이고 있다 → 닫은 번호를 잊으면 안 된다
        val before = coach(3, UrgentState.IDLE)
        val kept = settleDismissed(setOf(7L), before)
        assertEquals(setOf(7L), kept)
        // 코치가 늦게 Urgent(7)을 받아 LOCATING을 알린다: 닫은 화면이 다시 열리지 않는다
        assertNull(urgentView(null, kept, coach(7, UrgentState.LOCATING), false, null))
        assertEquals(setOf(7L), settleDismissed(kept, coach(7, UrgentState.IDLE)))
    }

    @Test
    fun coachInitiatedUrgentOpensTheScreen() {
        // 음성 "화장실 급해": 화면은 요청하지 않았지만 코치가 새 번호로 진행 중이다
        val v = urgentView(null, dismissed = setOf(3L), state = coach(9, UrgentState.SEARCHING), ackExpired = false, picking = null)!!
        assertEquals(9L, v.requestId)
        assertEquals(UrgentStatus.SEARCHING, v.status)
        assertNull(urgentView(null, emptySet(), coach(9, UrgentState.IDLE), false, null))
    }

    @Test
    fun serviceOffIsSaidPlainly() {
        // 한 번도 돈 적 없으면(차가운 시작, 저장된 요청 복원) '준비 중', 돌다가 멈췄으면 '멈춤'
        assertEquals(UrgentStatus.SERVICE_STARTING, urgentView(7, emptySet(), CoachUiState(serviceRunning = false), false, null)!!.status)
        val stopped = CoachUiState(serviceRunning = false, restroom = dev.shimpyo.engine.Gauge(30, dev.shimpyo.engine.Level.OK))
        assertEquals(UrgentStatus.SERVICE_OFF, urgentView(7, emptySet(), stopped, false, null)!!.status)
    }

    @Test
    fun legacyCoachWithoutRequestIds() {
        // 옛 CoachService: 번호 0, 상태 IDLE, 결과는 urgent 목록뿐
        assertEquals(UrgentStatus.LOCATING, urgentView(7, emptySet(), on, false, null)!!.status)
        assertEquals(UrgentStatus.READY, urgentView(7, emptySet(), on.copy(urgent = listOf(r1)), false, null)!!.status)
        assertEquals(UrgentStatus.NO_RESULT, urgentView(7, emptySet(), on.copy(urgent = emptyList()), false, null)!!.status)
        // 옛 코치는 위치가 없으면 결과 없이 끝난다. 응답 없는 코치와 구분할 수 없으니 '위치 없음'이라고 단정하지 않는다
        assertEquals(UrgentStatus.NO_RESPONSE, urgentView(7, emptySet(), on, ackExpired = true, picking = null)!!.status)
        // 고른 뒤 목록이 지워졌다 = 경유지를 넣었다
        assertNull(urgentView(7, emptySet(), on, false, picking = "u1"))
        // 음성으로 연 급해(번호 0)를 닫으면 목록이 지워질 때까지 다시 열지 않는다
        assertNull(urgentView(null, dismissed = setOf(0L), state = on.copy(urgent = listOf(r1)), ackExpired = false, picking = null))
        assertEquals(emptySet<Long>(), settleDismissed(setOf(0L), on))
    }

    @Test
    fun legacyVoiceAcceptClosesInsteadOfClaimingFailure() {
        // 감사(재검토) 1: 결과를 본 뒤 '응'으로 경유지를 넣으면 옛 코치는 목록을 지운다(고른 곳 없음, 8초 지남)
        assertNull(urgentView(7, emptySet(), on, ackExpired = true, picking = null, resultsSeen = true))
        // 결과를 보기 전이면 아직 찾는 중/응답 없음
        assertEquals(UrgentStatus.NO_RESPONSE, urgentView(7, emptySet(), on, ackExpired = true, picking = null, resultsSeen = false)!!.status)
    }

    @Test
    fun retryHidesTheReplacedRequestAndClosedIdsAllStayHidden() {
        // 다시 시도: 7을 닫은 번호에 넣고 8을 보낸다. 코치가 아직 7(NO_LOCATION)을 보이면 8의 '찾는 중'을 보인다
        val v = urgentView(8, setOf(7L), coach(7, UrgentState.NO_LOCATION), false, null)!!
        assertEquals(8L, v.requestId)
        assertEquals(UrgentStatus.LOCATING, v.status)
        // 7을 답 전에 닫고, 8을 보내고 또 닫았다: 7의 늦은 답도 8의 늦은 답도 다시 열지 않는다
        assertNull(urgentView(null, setOf(7L, 8L), coach(7, UrgentState.LOCATING), false, null))
        assertNull(urgentView(null, setOf(7L, 8L), coach(8, UrgentState.READY, listOf(r1)), false, null))
        // 닫은 번호는 최근 몇 개만 남긴다
        assertEquals(DISMISSED_KEEP, rememberDismissed((1L..20L).toSet(), 21L).size)
        assertTrue(21L in rememberDismissed((1L..20L).toSet(), 21L))
    }

    @Test
    fun coachInitiatedRequestTakingOverOursIsAdopted() {
        // 감사(재검토) 3: 우리 요청 7이 떠 있는데 음성 "화장실 급해"로 코치가 9를 시작했다 → 화면은 9를 따르고 번호도 9로 바꾼다
        val following = urgentView(7, emptySet(), coach(9, UrgentState.SEARCHING), false, null)!!
        assertEquals(9L, following.requestId)
        assertEquals(9L, adoptUrgentRequest(7, following, coach(9, UrgentState.SEARCHING)))
        // 9가 끝나면(IDLE) 닫힌다. 예전에는 7로 돌아가 '찾지 못했어요'를 보였다
        assertNull(urgentView(9, emptySet(), coach(9, UrgentState.IDLE), true, null))
        // 옛 코치(번호 0)는 바꾸지 않는다
        val legacy = urgentView(7, emptySet(), on.copy(urgent = listOf(r1)), false, null)!!
        assertEquals(7L, adoptUrgentRequest(7, legacy, on.copy(urgent = listOf(r1))))
    }

    @Test
    fun coreOpenedNegativeIdsAreFollowedAndStayClosedOnceDismissed() {
        // 코어가 음성 "화장실 급해"로 여는 번호는 음수이고 코어마다 겹치지 않는다 (닫힘 검증 UI-N4)
        val first = -1_731_000_001L
        val v = urgentView(null, emptySet(), coach(first, UrgentState.READY, listOf(r1)), false, null)!!
        assertEquals(first, v.requestId)
        assertEquals(first, adoptUrgentRequest(7, v, coach(first, UrgentState.READY, listOf(r1))))
        assertNull(urgentView(null, setOf(first), coach(first, UrgentState.READY, listOf(r1)), false, null))
        // 다시 뜬 코어의 다른 음수 번호는 닫은 번호가 아니다: 화면이 열린다
        val next = urgentView(null, setOf(first), coach(-1_731_000_777L, UrgentState.READY, listOf(r1)), false, null)!!
        assertEquals(UrgentStatus.READY, next.status)
    }

    @Test
    fun anotherQuestionIsShownOnTheUrgentScreenButNotItsOwn() {
        // UI 감사 #27: 급해 화면이 떠 있는 동안 온 다른 질문(마지막 기회 등)은 화면 위에 알린다. 급해 질문 자체는 결과 카드가 답이다
        val own = Prompt("u#1", PromptKind.URGENT_RESULT, "안내할까요?", r1.stop, Long.MAX_VALUE)
        val other = Prompt("lc#1", PromptKind.LAST_CHANCE, "들를까요?", r1.stop, Long.MAX_VALUE)
        assertNull(urgentQuestion(on.copy(prompt = own)))
        assertEquals(other, urgentQuestion(on.copy(prompt = other)))
        // 멈춘 코치의 남은 질문에는 받을 사람이 없다
        assertNull(urgentQuestion(on.copy(serviceRunning = false, prompt = other)))
        assertNull(urgentQuestion(on))
    }

    @Test
    fun aGoToForAStopThatIsNotOnTheListIsExplainedAboveIt() {
        // 닫힘 검증 UI-N2: 내비에 넣는 동안 결과 카드는 모두 꺼진다. 보이는 카드가 아닌 곳(질문에 '응'한 곳)을 넣는 중이면 이유를 한 줄로 보인다
        assertEquals("옥산휴게소를 내비에 넣는 중…", busyElsewhere(GoToBusy("oksan", "옥산휴게소(부산방향)"), listOf(r1)))
        // 보이는 카드를 넣는 중이면 그 카드가 '넣는 중…'을 보인다
        assertNull(busyElsewhere(GoToBusy("u1", r1.stop.name), listOf(r1)))
        assertNull(busyElsewhere(null, listOf(r1)))
    }

    @Test
    fun requestIdsAreUniqueAndNonZero() {
        val ids = (1..100).map { UrgentRequestIds.next() }
        assertEquals(100, ids.toSet().size)
        assertTrue(ids.all { it > 0 })
    }
}
