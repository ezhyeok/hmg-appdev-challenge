package dev.shimpyo.app.voice

import dev.hmg.pleoskit.SpeechInput
import dev.hmg.pleoskit.SpeechOutput
import dev.shimpyo.coach.UiSink
import dev.shimpyo.coach.VoicePort
import dev.shimpyo.coach.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 음성 입출력 ([VoicePort]). 한 번에 하나만 말하거나 듣는다 (말하는 중에 다른 질문이 끼지 않게 [Mutex]).
 * TTS/STT는 ON_DEVICE 모드라 앱 등록이 필요 없다. 마이크 표시(VoiceState)는 [ui]로 올린다 (화면 상태는 코치 계층이 소유한다).
 *
 * 멈춘 말의 늦은 이벤트가 다음 말을 끝내는 문제(서비스 감사 P13)는 kit이 막는다: 멈춘 요청의 끝남 이벤트를 받고 나서 다음
 * 요청을 보낸다 (pleos-kit Turns.kt). 예전에는 여기서 멈춘 뒤 0.4초 쉬는 것으로 줄였다.
 *
 * 쉬는 상태의 마이크 표시: 듣기(STT)를 쓸 수 있으면 IDLE, 없으면 UNAVAILABLE. 화면은 UNAVAILABLE을 '들을 수 없음'으로 그린다
 * (MicModel). 예전에는 말하기(TTS)가 없을 때 UNAVAILABLE로 올려서 듣기만 되는 상태를 못 쓰게 했고, 한 번 말하거나 들으면
 * 무조건 IDLE로 돌려 UNAVAILABLE이 풀렸다 (리뷰).
 */
class VoiceAgent(private val tts: SpeechOutput, private val stt: SpeechInput, private val ui: UiSink) : VoicePort {
    private val lock = Mutex()
    @Volatile override var ttsReady = false
        private set
    @Volatile override var sttReady = false
        private set

    /** 각각 따로 붙인다. 하나라도 못 붙으면 예외를 던져 부른 쪽(서비스)이 간격을 두고 다시 부르게 한다 */
    override suspend fun connect() {
        if (!ttsReady) ttsReady = attached { tts.connect() }
        if (!sttReady) sttReady = attached { stt.connect() }
        // 말하거나 듣는 중이면 그 표시를 그대로 둔다
        ui.update { if (voice == VoiceState.IDLE || voice == VoiceState.UNAVAILABLE) copy(voice = idle()) else this }
        check(ttsReady && sttReady) { "voice connect: tts=$ttsReady stt=$sttReady" }
    }

    override suspend fun say(text: String): Boolean = lock.withLock { speakLocked(text) }

    /**
     * 묻고 대답을 듣는다. 대답이 없거나 STT를 못 쓰면 null (화면 버튼으로 받는다).
     * 질문을 끝까지 말하지 못했으면(멈췄거나 실패) 마이크를 열지 않는다: 다른 소리가 우리 말을 끊은 직후에 들으면 그 소리를 대답으로 듣는다.
     */
    override suspend fun ask(text: String, listenMs: Long): String? = lock.withLock {
        if (!speakLocked(text)) return@withLock null
        listenLocked(listenMs)
    }

    override suspend fun listen(listenMs: Long): String? = lock.withLock { listenLocked(listenMs) }

    /** 말하기·듣기를 멈춘다. 하는 중이 아니면 kit이 SDK를 부르지 않는다 */
    override fun stop() {
        runCatching { tts.stop() }
        runCatching { stt.stop() }
    }

    private fun idle() = if (sttReady) VoiceState.IDLE else VoiceState.UNAVAILABLE

    private suspend fun attached(connect: suspend () -> Unit): Boolean = try {
        connect()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** 끝까지 말했으면 true */
    private suspend fun speakLocked(text: String): Boolean {
        if (!ttsReady) return false
        ui.update { copy(voice = VoiceState.SPEAKING) }
        return try {
            tts.speak(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            ui.update { copy(voice = idle()) }
        }
    }

    private suspend fun listenLocked(listenMs: Long): String? {
        if (!sttReady) return null
        ui.update { copy(voice = VoiceState.LISTENING) }
        return try {
            stt.listen(timeoutMs = listenMs)?.also { heard -> ui.update { copy(lastHeard = heard) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            ui.update { copy(voice = idle()) }
        }
    }
}
