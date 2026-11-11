package dev.shimpyo.app.voice

import android.os.SystemClock
import dev.hmg.pleoskit.SttKit
import dev.hmg.pleoskit.TtsKit
import dev.shimpyo.coach.UiSink
import dev.shimpyo.coach.VoicePort
import dev.shimpyo.coach.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 음성 입출력 ([VoicePort]). 한 번에 하나만 말하거나 듣는다 (말하는 중에 다른 질문이 끼지 않게 [Mutex]).
 * TTS/STT는 ON_DEVICE 모드라 앱 등록이 필요 없다. 마이크 표시(VoiceState)는 [ui]로 올린다 (화면 상태는 코치 계층이 소유한다).
 *
 * TTS/STT 이벤트에는 요청 번호가 없다(서비스 감사 P13). 멈춘 말의 늦은 '끝남' 이벤트가 바로 다음 말을 끝낸 것으로 읽히지 않게,
 * [stop] 뒤 [SETTLE_MS] 안에는 다음 말·듣기를 조금 늦게 시작한다.
 */
class VoiceAgent(private val tts: TtsKit, private val stt: SttKit, private val ui: UiSink) : VoicePort {
    private val lock = Mutex()
    @Volatile override var ttsReady = false
        private set
    @Volatile override var sttReady = false
        private set
    @Volatile private var stoppedAt = 0L

    /** 각각 따로 붙인다. 하나라도 못 붙으면 예외를 던져 부른 쪽(서비스)이 간격을 두고 다시 부르게 한다 */
    override suspend fun connect() {
        if (!ttsReady) ttsReady = runCatching { tts.connect() }.isSuccess
        if (!sttReady) sttReady = runCatching { stt.connect() }.isSuccess
        ui.update { copy(voice = if (ttsReady) (if (voice == VoiceState.UNAVAILABLE) VoiceState.IDLE else voice) else VoiceState.UNAVAILABLE) }
        check(ttsReady && sttReady) { "voice connect: tts=$ttsReady stt=$sttReady" }
    }

    override suspend fun say(text: String): Boolean = lock.withLock { speakLocked(text) }

    /** 묻고 대답을 듣는다. 대답이 없거나 STT를 못 쓰면 null (화면 버튼으로 받는다) */
    override suspend fun ask(text: String, listenMs: Long): String? = lock.withLock {
        speakLocked(text)
        listenLocked(listenMs)
    }

    override suspend fun listen(listenMs: Long): String? = lock.withLock { listenLocked(listenMs) }

    override fun stop() {
        stoppedAt = SystemClock.elapsedRealtime()
        runCatching { tts.stop() }
        runCatching { stt.stop() }
    }

    private suspend fun settle() {
        val wait = stoppedAt + SETTLE_MS - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
    }

    private suspend fun speakLocked(text: String): Boolean {
        if (!ttsReady) return false
        settle()
        ui.update { copy(voice = VoiceState.SPEAKING) }
        return try {
            tts.speak(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            ui.update { copy(voice = VoiceState.IDLE) }
        }
    }

    private suspend fun listenLocked(listenMs: Long): String? {
        if (!sttReady) return null
        settle()
        ui.update { copy(voice = VoiceState.LISTENING) }
        return try {
            stt.listen(timeoutMs = listenMs)?.also { heard -> ui.update { copy(lastHeard = heard) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            ui.update { copy(voice = VoiceState.IDLE) }
        }
    }

    private companion object {
        const val SETTLE_MS = 400L
    }
}
