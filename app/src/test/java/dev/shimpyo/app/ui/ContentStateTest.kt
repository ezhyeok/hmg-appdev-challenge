package dev.shimpyo.app.ui

import androidx.compose.runtime.saveable.SaverScope
import dev.shimpyo.coach.GoToOutcome
import dev.shimpyo.coach.GoToResult
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 화면의 진행 중 상태가 액티비티를 다시 만들어도 남는지 (UI 감사 #14), 들르기 결과 토스트 (#3) */
class ContentStateTest {

    @Test
    fun inFlightStateSurvivesRecreation() {
        val before = ContentState(
            screen = Screen.PLAN, demoReturn = Screen.HISTORY, planRequested = true,
            urgentRequested = 42L, urgentDismissed = 41L, pickingId = "u1", goToPendingId = "ra-anseong", seenGoToAt = 99L,
        )
        val saved = with(ContentState.Saver) { SaverScope { true }.save(before) }!!
        val after = ContentState.Saver.restore(saved)!!
        assertEquals(Screen.PLAN, after.screen)
        assertEquals(Screen.HISTORY, after.demoReturn)
        assertEquals(true, after.planRequested)
        assertEquals(42L, after.urgentRequested)
        assertEquals(41L, after.urgentDismissed)
        assertEquals("u1", after.pickingId)
        assertEquals("ra-anseong", after.goToPendingId)
        assertEquals(99L, after.seenGoToAt)

        val empty = ContentState.Saver.restore(with(ContentState.Saver) { SaverScope { true }.save(ContentState(Screen.DRIVE)) }!!)!!
        assertNull(empty.urgentRequested)
        assertNull(empty.pickingId)
        assertNull(empty.goToPendingId)
    }

    private val anseong = StopCandidate("a", "안성휴게소(부산방향)", StopKind.HIGHWAY_REST_AREA, 0.0, 0.0, Facilities(), true)

    @Test
    fun goToToastOnlyWhenTheDriveCardDoesNotShowIt() {
        val added = GoToResult(anseong, GoToOutcome.WAYPOINT_ADDED, 1L)
        assertEquals("안성휴게소를 경유지로 넣었어요", goToToast(added, onDriveCard = false, nextStopId = null))
        // 주행 화면 카드가 같은 곳을 보이면(맥락 "경유지로 넣었어요") 토스트는 띄우지 않는다
        assertNull(goToToast(added, onDriveCard = true, nextStopId = "a"))
        assertEquals("안성휴게소를 경유지로 넣었어요", goToToast(added, onDriveCard = true, nextStopId = "other"))
        // 실패는 주행 화면에 다시 시도 카드가 있다
        assertNull(goToToast(added.copy(outcome = GoToOutcome.FAILED), onDriveCard = true, nextStopId = null))
        assertEquals("안성휴게소를 넣지 못했어요. 다시 시도해 주세요", goToToast(added.copy(outcome = GoToOutcome.FAILED), false, null))
        assertEquals("안성휴게소로 길안내를 시작했어요", goToToast(added.copy(outcome = GoToOutcome.ROUTE_STARTED), false, null))
        assertEquals("안성휴게소가 경로 위에 있어요. 진입로로 들어가세요", goToToast(added.copy(outcome = GoToOutcome.ON_ROUTE), false, null))
    }
}
