package dev.shimpyo.app.voice

import dev.hmg.pleoskit.SttKit
import dev.hmg.pleoskit.TtsKit
import dev.shimpyo.app.coach.CoachStore
import dev.shimpyo.coach.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 음성 입출력. 질문은 한 번에 하나만 한다 (말하는 중에 다른 질문이 끼지 않게 [Mutex]).
 * TTS/STT는 ON_DEVICE 모드라 앱 등록이 필요 없다.
 */
class VoiceAgent(private val tts: TtsKit, private val stt: SttKit) {
    private val lock = Mutex()
    var ttsReady = false
        private set
    var sttReady = false
        private set

    suspend fun connect() {
        ttsReady = runCatching { tts.connect() }.isSuccess
        sttReady = runCatching { stt.connect() }.isSuccess
        if (!ttsReady) CoachStore.update { copy(voice = VoiceState.UNAVAILABLE) }
    }

    suspend fun say(text: String): Boolean = lock.withLock { speakLocked(text) }

    /** 묻고 대답을 듣는다. 대답이 없거나 STT를 못 쓰면 null (화면 버튼으로 받는다) */
    suspend fun ask(text: String, listenMs: Long = 6_000): String? = lock.withLock {
        speakLocked(text)
        listenLocked(listenMs)
    }

    suspend fun listen(listenMs: Long = 8_000): String? = lock.withLock { listenLocked(listenMs) }

    fun stop() {
        runCatching { tts.stop() }
        runCatching { stt.stop() }
    }

    private suspend fun speakLocked(text: String): Boolean {
        if (!ttsReady) return false
        CoachStore.update { copy(voice = VoiceState.SPEAKING) }
        return try {
            tts.speak(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            CoachStore.update { copy(voice = VoiceState.IDLE) }
        }
    }

    private suspend fun listenLocked(listenMs: Long): String? {
        if (!sttReady) return null
        CoachStore.update { copy(voice = VoiceState.LISTENING) }
        return try {
            stt.listen(timeoutMs = listenMs)?.also { heard -> CoachStore.update { copy(lastHeard = heard) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            CoachStore.update { copy(voice = VoiceState.IDLE) }
        }
    }
}
