package dev.shimpyo.app.ui

import androidx.compose.runtime.saveable.SaverScope
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.GoToOutcome
import dev.shimpyo.coach.GoToResult
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 화면의 진행 중 상태가 액티비티를 다시 만들어도 남는지 (UI 감사 #14), 들르기 진행(UI-N2)과 결과 토스트 (#3) */
class ContentStateTest {

    private fun recreate(before: ContentState): ContentState {
        val saved = with(ContentState.Saver) { SaverScope { true }.save(before) }!!
        return ContentState.Saver.restore(saved)!!
    }

    @Test
    fun inFlightStateSurvivesRecreation() {
        val before = ContentState(
            screen = Screen.PLAN, demoReturn = Screen.HISTORY, planRequested = true,
            urgentRequested = 42L, dismissedUrgent = setOf(40L, 41L), pickingId = "u1", goToSentId = "ra-anseong", seenGoToAt = 99L,
            urgentResultsFor = 42L, ackExpiredFor = 42L, planRequestedGen = 3L, planWanted = true,
        )
        val after = recreate(before)
        assertEquals(Screen.PLAN, after.screen)
        assertEquals(Screen.HISTORY, after.demoReturn)
        assertEquals(true, after.planRequested)
        assertEquals(42L, after.urgentRequested)
        assertEquals(setOf(40L, 41L), after.dismissedUrgent)
        assertEquals(42L, after.urgentResultsFor)
        assertEquals(42L, after.ackExpiredFor)
        assertEquals(3L, after.planRequestedGen)
        assertEquals("u1", after.pickingId)
        assertEquals("ra-anseong", after.goToSentId)
        assertEquals(99L, after.seenGoToAt)
        assertEquals(true, after.planWanted)

        val empty = recreate(ContentState(Screen.DRIVE))
        assertNull(empty.urgentRequested)
        assertNull(empty.urgentResultsFor)
        assertNull(empty.ackExpiredFor)
        assertNull(empty.pickingId)
        assertNull(empty.goToSentId)
        assertEquals(emptySet<Long>(), empty.dismissedUrgent)
        assertFalse(empty.planWanted)
    }

    @Test
    fun aCoreOpenedUrgentRequestSurvivesRecreation() {
        // 닫힘 검증 UI-N4: 코어가 음성 "화장실 급해"로 연 요청은 음수 번호이고 화면은 그 번호를 자기 요청으로 삼는다(adoptUrgentRequest).
        // 예전 저장 형식은 '번호는 0 이상'이라 보고 null을 -1로 적어서, 다시 만들어지면 -1도 -2도 null로 돌아왔다
        val before = ContentState(screen = Screen.DRIVE, urgentRequested = -2L, urgentResultsFor = -2L, ackExpiredFor = -2L, dismissedUrgent = setOf(-1L))
        val after = recreate(before)
        assertEquals(-2L, after.urgentRequested)
        assertEquals(-2L, after.urgentResultsFor)
        assertEquals(-2L, after.ackExpiredFor)
        assertEquals(setOf(-1L), after.dismissedUrgent)
        // 어떤 번호든 그대로 돌아온다 (코어 번호는 경과 시간으로 정한 큰 음수일 수 있다)
        listOf(-1L, 0L, 1L, Long.MIN_VALUE, Long.MAX_VALUE).forEach { id ->
            assertEquals(id, recreate(ContentState(Screen.DRIVE, urgentRequested = id)).urgentRequested)
        }
    }

    private val anseong = StopCandidate("a", "안성휴게소(부산방향)", StopKind.HIGHWAY_REST_AREA, 0.0, 0.0, Facilities(), true)

    @Test
    fun theCoachDecidesHowLongAGoToIsBusy() {
        val on = CoachUiState(serviceRunning = true)
        // 코치가 넣는 중이면 화면이 보낸 것이 없어도(음성 '응', 다시 만들어진 화면) 바쁘다: 버튼을 막고 진행을 보인다
        assertEquals(GoToBusy("a", "안성휴게소(부산방향)"), goToBusy(on.copy(goToPending = anseong), sentId = null))
        assertEquals(GoToBusy("a", "안성휴게소(부산방향)"), goToBusy(on.copy(goToPending = anseong), sentId = "other"))
        // 보낸 직후: 코치가 받았다고 알리기 전에도 같은 요청을 또 보내지 않는다
        assertEquals(GoToBusy("a", null), goToBusy(on, sentId = "a"))
        assertNull(goToBusy(on, sentId = null))
        // 멈춘 코치에 남은 값은 믿지 않는다
        assertNull(goToBusy(on.copy(serviceRunning = false, goToPending = anseong), sentId = null))
        assertEquals("안성휴게소를 내비에 넣는 중…", goToProgressText(GoToBusy("a", "안성휴게소(부산방향)")))
        assertEquals("내비에 넣는 중…", goToProgressText(GoToBusy("a", null)))
    }

    @Test
    fun theScreenHasNoGoToResultTimeoutOfItsOwn() {
        // 닫힘 검증 UI-N2: 화면의 12초 제한이 성공(에뮬레이터에서 19.5초)보다 먼저 "결과를 받지 못했어요"를 띄우고 버튼을 다시 켰다.
        // 진행은 코치의 goToPending만 본다. 끝은 코치의 감시 타이머가 보장한다
        val src = File("src/main/java/dev/shimpyo/app/ui/ShimpyoContent.kt")
        assertTrue("소스를 찾지 못했다: ${src.absolutePath}", src.isFile)
        val text = src.readText()
        assertFalse("GOTO_TIMEOUT" in text)
        assertFalse("결과를 받지 못했어요" in text)
    }

    @Test
    fun goToToastOnlyWhenTheDriveCardDoesNotShowIt() {
        val added = GoToResult(anseong, GoToOutcome.WAYPOINT_ADDED, 1L)
        assertEquals("안성휴게소를 경유지로 넣었어요", goToToast(added, shownOnCard = false))
        // 주행 화면 카드가 지금 그 결과를 보이면(DriveModels.cardShowsGoTo) 토스트는 띄우지 않는다
        assertNull(goToToast(added, shownOnCard = true))
        assertEquals("안성휴게소를 넣지 못했어요. 다시 시도해 주세요", goToToast(added.copy(outcome = GoToOutcome.FAILED), false))
        assertEquals("안성휴게소로 길안내를 시작했어요", goToToast(added.copy(outcome = GoToOutcome.ROUTE_STARTED), false))
        assertEquals("안성휴게소가 경로 위에 있어요. 진입로로 들어가세요", goToToast(added.copy(outcome = GoToOutcome.ON_ROUTE), false))
    }
}
