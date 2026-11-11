package dev.shimpyo.app.ui.components

import dev.shimpyo.coach.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 마이크 버튼 (UI 감사 #12, screens.md 0.4) */
class MicModelTest {

    @Test
    fun tapWhileListeningSpeakingOrThinkingStops() {
        // 예전에는 듣거나 말하는 중에 누르면 듣기를 하나 더 쌓았다
        listOf(VoiceState.LISTENING, VoiceState.SPEAKING, VoiceState.THINKING).forEach { v ->
            assertEquals(v.name, MicAction.STOP, micModel(v, ttsAvailable = true, sttAvailable = true, voiceGuidance = true).action)
        }
        assertTrue(micModel(VoiceState.LISTENING, true, true, true).active)
        assertEquals("말하는 중", micModel(VoiceState.SPEAKING, true, true, true).status)
    }

    @Test
    fun idleListens() {
        val m = micModel(VoiceState.IDLE, ttsAvailable = true, sttAvailable = true, voiceGuidance = true)
        assertEquals(MicAction.LISTEN, m.action)
        assertNull(m.status)
    }

    @Test
    fun guidanceOffStillListensAndSaysSoHonestly() {
        // 예전: '음성 꺼짐'이라고 보이면서 실제로는 듣고 대답했다. 설정의 '음성 안내'는 말하기만 끈다
        val m = micModel(VoiceState.IDLE, ttsAvailable = true, sttAvailable = true, voiceGuidance = false)
        assertEquals(MicAction.LISTEN, m.action)
        assertEquals("음성 안내 꺼짐", m.status)
        assertNotEquals(MicIcon.MIC_OFF, m.icon)
    }

    @Test
    fun sttUnavailableExplainsInsteadOfDoingNothing() {
        // 예전: 인식만 안 될 때 눌러도 아무 일이 없었다
        val m = micModel(VoiceState.IDLE, ttsAvailable = true, sttAvailable = false, voiceGuidance = true)
        assertEquals(MicAction.EXPLAIN, m.action)
        assertEquals(MicIcon.MIC_OFF, m.icon)
        assertNotNull(m.explain)
        assertEquals("음성 불가", micModel(VoiceState.UNAVAILABLE, false, false, true).status)
        // 말하는 중이면 인식이 안 돼도 멈출 수는 있다
        assertEquals(MicAction.STOP, micModel(VoiceState.SPEAKING, true, false, true).action)
    }
}
