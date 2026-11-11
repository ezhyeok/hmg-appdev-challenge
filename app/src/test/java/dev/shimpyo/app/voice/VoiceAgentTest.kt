package dev.shimpyo.app.voice

import dev.hmg.pleoskit.SpeechInput
import dev.hmg.pleoskit.SpeechOutput
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.UiSink
import dev.shimpyo.coach.VoiceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 음성 입출력 (VoiceAgent). 가짜 TTS·STT로 돈다.
 * 회귀(2026-11-12 리뷰): 질문을 말하다 멈췄거나 말하지 못했는데도 마이크를 열었다. 한 번 듣고 나면 '음성 불가' 표시가 풀렸다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAgentTest {
    private class FakeTts : SpeechOutput {
        var connectError: Exception? = null

        /** speak가 돌려줄 값. null이면 예외 */
        var result: Boolean? = true
        var speakMs = 1_000L
        val spoken = mutableListOf<String>()
        var stops = 0

        override suspend fun connect() {
            connectError?.let { throw it }
        }

        override suspend fun speak(text: String): Boolean {
            spoken += text
            delay(speakMs)
            return result ?: throw IllegalStateException("TTS error")
        }

        override fun stop() {
            stops++
        }
    }

    private class FakeStt : SpeechInput {
        var connectError: Exception? = null
        var answer: String? = "응"
        var listens = 0
        var stops = 0

        override suspend fun connect() {
            connectError?.let { throw it }
        }

        override suspend fun listen(timeoutMs: Long): String? {
            listens++
            delay(500)
            return answer
        }

        override fun stop() {
            stops++
        }
    }

    private class Ui : UiSink {
        var state = CoachUiState()
        val voices = mutableListOf<VoiceState>()

        override fun update(transform: CoachUiState.() -> CoachUiState) {
            state = state.transform()
            if (voices.lastOrNull() != state.voice) voices += state.voice
        }
    }

    private val tts = FakeTts()
    private val stt = FakeStt()
    private val ui = Ui()
    private val agent = VoiceAgent(tts, stt, ui)

    @Test
    fun askSpeaksThenListens() = runTest {
        agent.connect()
        assertEquals("응", agent.ask("옥산휴게소에 들를까요?", listenMs = 6_000))
        assertEquals(listOf("옥산휴게소에 들를까요?"), tts.spoken)
        assertEquals(1, stt.listens)
        assertEquals(listOf(VoiceState.IDLE, VoiceState.SPEAKING, VoiceState.IDLE, VoiceState.LISTENING, VoiceState.IDLE), ui.voices)
        assertEquals("응", ui.state.lastHeard)
    }

    @Test
    fun theMicStaysClosedWhenTheQuestionWasStoppedHalfway() = runTest {
        // 내비 안내 음성 같은 다른 소리가 우리 말을 끊었다(TTS가 '멈춤'으로 끝났다). 그 직후에 들으면 그 소리를 대답으로 듣는다
        agent.connect()
        tts.result = false
        assertNull(agent.ask("쉬어 갈까요?", listenMs = 6_000))
        assertEquals("마이크를 열지 않는다", 0, stt.listens)
        assertEquals(VoiceState.IDLE, ui.state.voice)
    }

    @Test
    fun theMicStaysClosedWhenTheQuestionCouldNotBeSpoken() = runTest {
        agent.connect()
        tts.result = null // TTS 오류
        assertNull(agent.ask("쉬어 갈까요?", listenMs = 6_000))
        assertEquals(0, stt.listens)
        assertFalse(agent.say("안내"))
    }

    @Test
    fun cancellingAnAskNeverOpensTheMic() = runTest {
        agent.connect()
        val job = launch { agent.ask("쉬어 갈까요?", listenMs = 6_000) }
        advanceTimeBy(500)
        job.cancelAndJoin()
        advanceTimeBy(10_000)
        assertEquals(0, stt.listens)
        assertEquals("취소해도 표시는 쉬는 상태로 돌아온다", VoiceState.IDLE, ui.state.voice)
    }

    @Test
    fun withoutSpeechRecognitionTheMicIsUnavailableAndStaysSoAfterSpeaking() = runTest {
        stt.connectError = IllegalStateException("STT bind failed")
        val failure = runCatching { agent.connect() }.exceptionOrNull()
        assertTrue("못 붙은 것이 있으면 부른 쪽이 다시 부르게 예외", failure is IllegalStateException)
        assertTrue(agent.ttsReady)
        assertFalse(agent.sttReady)
        assertEquals(VoiceState.UNAVAILABLE, ui.state.voice)
        assertTrue(agent.say("환기를 위해 외기로 바꿨어요"))
        assertEquals("말하고 나서도 '들을 수 없음'이 풀리지 않는다", VoiceState.UNAVAILABLE, ui.state.voice)
        assertNull("들을 수 없으면 듣지 않는다", agent.listen(8_000))
        assertEquals(VoiceState.UNAVAILABLE, ui.state.voice)
        // 다시 붙으면 풀린다
        stt.connectError = null
        agent.connect()
        assertEquals(VoiceState.IDLE, ui.state.voice)
    }

    @Test
    fun withoutSpeechOutputListeningStillWorks() = runTest {
        // 말하기(TTS)만 못 붙었다: 마이크는 쓸 수 있다 (대답은 화면으로). 예전에는 이때 UNAVAILABLE로 올려 마이크를 막았다
        tts.connectError = IllegalStateException("TTS bind failed")
        runCatching { agent.connect() }
        assertFalse(agent.ttsReady)
        assertTrue(agent.sttReady)
        assertEquals(VoiceState.IDLE, ui.state.voice)
        assertEquals("응", agent.listen(8_000))
        assertFalse("말하지 못한다", agent.say("안내"))
        assertNull("질문을 말하지 못했으면 듣지 않는다", agent.ask("쉬어 갈까요?", 6_000))
        assertEquals(1, stt.listens)
    }

    @Test
    fun connectLeavesABusyMicIndicatorAlone() = runTest {
        stt.connectError = IllegalStateException("not yet")
        runCatching { agent.connect() }
        tts.speakMs = 5_000
        val speaking = async { agent.say("길게 말하는 중") }
        runCurrent()
        assertEquals(VoiceState.SPEAKING, ui.state.voice)
        stt.connectError = null
        agent.connect() // 서비스의 다시 시도가 말하는 중에 붙였다
        assertEquals("말하는 중 표시를 덮지 않는다", VoiceState.SPEAKING, ui.state.voice)
        assertTrue(speaking.await())
        assertEquals(VoiceState.IDLE, ui.state.voice)
    }

    @Test
    fun stopReachesBothEngines() {
        agent.stop()
        assertEquals(1, tts.stops)
        assertEquals(1, stt.stops)
    }
}
