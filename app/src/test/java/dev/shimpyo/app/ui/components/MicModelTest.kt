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

    @Test
    fun ttsDownButSttUpStillListens() {
        // 닫힘 검증 UI-12: VoiceAgent는 TTS가 안 붙으면 STT가 붙었어도 voice = UNAVAILABLE을 올린다. 예전 micModel은 UNAVAILABLE을
        // '인식 불가'로 읽어 사선 마이크를 보이고 Listen을 보내지 않았다. 코어는 STT만 되면 듣는다 (ui.md 3.6의 표)
        val m = micModel(VoiceState.UNAVAILABLE, ttsAvailable = false, sttAvailable = true, voiceGuidance = true)
        assertEquals(MicAction.LISTEN, m.action)
        assertEquals("음성 안내 꺼짐", m.status)
        assertNotEquals(MicIcon.MIC_OFF, m.icon)
        // 둘 다 되는데 표시만 UNAVAILABLE로 남은 경우(듣기 뒤 덮어쓰기 전)도 들을 수 있다
        assertEquals(MicAction.LISTEN, micModel(VoiceState.UNAVAILABLE, ttsAvailable = true, sttAvailable = true, voiceGuidance = true).action)
        // 인식이 안 되면 여전히 이유를 알린다
        assertEquals(MicAction.EXPLAIN, micModel(VoiceState.UNAVAILABLE, ttsAvailable = true, sttAvailable = false, voiceGuidance = true).action)
    }

    @Test
    fun describeByVoiceSaysWhyItCannotBeUsed() {
        // 탑승자 화면의 [말로 설정]: 듣기(STT)만 되면 쓸 수 있다. 인식을 쓸 수 없으면 버튼이 이유를 말한다
        // (닫힘 검증 UI-12: 예전에는 안 될 때 눌러도 조용히 아무 일도 없었다)
        val ok = describeModel(VoiceState.IDLE, ttsAvailable = true, sttAvailable = true, voiceGuidance = true)
        assertEquals(MicAction.LISTEN, ok.action)
        assertEquals("말로 설정", ok.label)
        assertNull(ok.reason)
        assertTrue(ok.available)

        val noStt = describeModel(VoiceState.IDLE, ttsAvailable = true, sttAvailable = false, voiceGuidance = true)
        assertEquals(MicAction.EXPLAIN, noStt.action)
        assertEquals("지금은 음성 인식을 쓸 수 없어요. 화면에서 골라 주세요", noStt.reason)
        assertEquals(false, noStt.available)

        // 말하기가 꺼져 있어도(설정의 음성 안내, TTS 연결 실패) 들을 수 있다: 코치가 질문을 화면 알림으로 보이고 듣는다.
        // 마이크와 같은 규칙이다 (TTS만 안 되는데 듣기까지 막으면 안 된다)
        val guidanceOff = describeModel(VoiceState.IDLE, ttsAvailable = true, sttAvailable = true, voiceGuidance = false)
        assertEquals(MicAction.LISTEN, guidanceOff.action)
        assertNull(guidanceOff.reason)
        val noTts = describeModel(VoiceState.UNAVAILABLE, ttsAvailable = false, sttAvailable = true, voiceGuidance = true)
        assertEquals(MicAction.LISTEN, noTts.action)
        assertNull(noTts.reason)

        // 묻거나 듣거나 해석하는 중이면 누르면 멈춘다 (마이크와 같은 규칙)
        assertEquals(MicAction.STOP, describeModel(VoiceState.LISTENING, true, true, true).action)
        assertEquals("듣는 중", describeModel(VoiceState.LISTENING, true, true, true).label)
        assertEquals("말하는 중", describeModel(VoiceState.SPEAKING, true, true, true).label)
        assertEquals("생각하는 중", describeModel(VoiceState.THINKING, true, true, true).label)
        assertEquals(MicAction.STOP, describeModel(VoiceState.SPEAKING, true, false, false).action)
    }
}
