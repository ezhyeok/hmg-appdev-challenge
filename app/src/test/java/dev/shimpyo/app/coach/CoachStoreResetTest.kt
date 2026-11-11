package dev.shimpyo.app.coach

import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.GoToOutcome
import dev.shimpyo.coach.GoToResult
import dev.shimpyo.coach.UrgentState
import dev.shimpyo.coach.VoiceState
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.Reason
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 서비스가 끝나거나 다시 뜰 때 화면에 남는 것 (서비스 감사 D11, UI 감사 #18, 2026-11-12 닫힘 검증).
 * 진행 중이던 것(질문, 급해, 들르기 결과·진행, 알림, 자는 중 표시, 음성 표시)은 옛 서비스의 것이라 지운다.
 * 그대로 두면 서비스가 없는데 살아 있는 버튼이나 '내비에 넣는 중…'이 화면에 남는다.
 */
class CoachStoreResetTest {
    private val oksan = StopCandidate("oksan", "옥산휴게소", StopKind.HIGHWAY_REST_AREA, 36.6, 127.3, Facilities(), true)

    @After
    fun restore() {
        CoachStore.update { CoachUiState() }
    }

    @Test
    fun nothingInFlightSurvivesTheService() {
        CoachStore.update {
            copy(
                serviceRunning = true,
                prompt = Prompt("p1#1", PromptKind.LAST_CHANCE, "들를까요?", oksan, Long.MAX_VALUE), promptStop = oksan,
                promptReasons = listOf(Reason("LAST_CHANCE_GAP", "…")), goToResult = GoToResult(oksan, GoToOutcome.FAILED, 1L),
                goToPending = oksan, notice = "약 40분 남았어요", asleepOccupantIds = setOf("kid"),
                urgentState = UrgentState.READY, urgentRequestId = 7L, voice = VoiceState.LISTENING,
            )
        }
        CoachStore.resetTransient()
        val s = CoachStore.state.value
        assertFalse(s.serviceRunning)
        assertNull(s.prompt)
        assertNull(s.promptStop)
        assertTrue(s.promptReasons.isEmpty())
        assertNull(s.goToResult)
        assertNull("내비에 넣는 중이던 표시도 옛 서비스의 것이다", s.goToPending)
        assertNull(s.notice)
        assertTrue(s.asleepOccupantIds.isEmpty())
        assertNull(s.urgent)
        assertEquals(UrgentState.IDLE, s.urgentState)
        assertEquals(VoiceState.IDLE, s.voice)
        assertNull(s.plan)
    }
}
