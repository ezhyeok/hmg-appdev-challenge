package dev.hmg.pleoskit

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener

/**
 * TextToSpeech SDK 래퍼. 기본은 ON_DEVICE 모드라 Playground 앱 등록(Client ID/Secret) 없이 쓸 수 있다.
 * 권한: `pleos.car.permission.TTS_SERVICE`.
 */
class TtsKit(
    context: Context,
    mode: TtsMode = TtsMode.ON_DEVICE,
    private val locale: Locale = Locale.KOREA,
) {
    /** SDK 원본 객체. SERVER/HYBRID 모드의 `registerApp`이나 오디오 존 설정에 쓴다. */
    val raw = TextToSpeech(context.applicationContext, mode)

    private sealed interface Event {
        data object Done : Event
        data object Stopped : Event
        data class Failed(val type: TtsErrorType) : Event
    }

    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutex = Mutex()
    private val connectMutex = Mutex()

    @Volatile
    private var connected = false

    private val listener = object : TtsEventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onDone() { events.tryEmit(Event.Done) }
        override fun onStop() { events.tryEmit(Event.Stopped) }
        override fun onError(errorType: TtsErrorType) { events.tryEmit(Event.Failed(errorType)) }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    suspend fun connect(timeoutMs: Long = 30_000) = connectMutex.withLock {
        if (connected) return@withLock
        val ok = withTimeoutOrNull(timeoutMs) {
            withContext(SdkThreads.voice) {
                suspendCancellableCoroutine { cont ->
                    val done = AtomicBoolean(false)
                    raw.initialize(object : TtsInitListener {
                        override fun onSuccess() {
                            if (done.compareAndSet(false, true)) cont.resume(Unit)
                        }

                        override fun onFailure(errorType: TtsErrorType) {
                            if (done.compareAndSet(false, true)) {
                                cont.resumeWithException(PleosSdkException("TextToSpeech.initialize", errorType.name))
                            }
                        }
                    })
                }
                raw.addEventListener(listener)
                raw.setLocale(locale)
            }
        }
        if (ok == null) throw PleosSdkException("TextToSpeech.initialize", "no response in ${timeoutMs}ms")
        connected = true
    }

    /**
     * [text]를 읽고, 재생이 끝날 때까지 기다린다. 한 번에 한 문장만 재생한다(나중 호출은 앞 문장이 끝난 뒤 시작).
     * 호출한 코루틴이 취소되면 재생도 멈춘다. 끝까지 재생했으면 true, 중간에 멈췄으면 false.
     */
    suspend fun speak(text: String, timeoutMs: Long = 60_000): Boolean = mutex.withLock {
        coroutineScope {
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { events.first() }
            try {
                withContext(SdkThreads.voice) { raw.speak(text) }
                when (val event = withTimeoutOrNull(timeoutMs) { waiter.await() }) {
                    Event.Done -> true
                    Event.Stopped -> false
                    is Event.Failed -> throw PleosSdkException("TextToSpeech.speak", event.type.name)
                    null -> {
                        stop()
                        throw PleosSdkException("TextToSpeech.speak", "not finished in ${timeoutMs}ms")
                    }
                }
            } catch (c: CancellationException) {
                stop()
                throw c
            } finally {
                waiter.cancel()
            }
        }
    }

    /** 재생 중인 음성을 멈춘다. */
    fun stop() {
        SdkThreads.voice.executor.execute { runCatching { raw.stop() } }
    }

    fun release() {
        SdkThreads.voice.executor.execute {
            runCatching { raw.removeEventListener(listener) }
            runCatching { raw.release() }
            connected = false
        }
    }
}

/**
 * SpeechToText SDK 래퍼. 기본은 ON_DEVICE 모드.
 * 권한: `pleos.car.permission.STT_SERVICE`.
 */
class SttKit(
    context: Context,
    mode: SttMode = SttMode.ON_DEVICE,
    private val locale: Locale = Locale.KOREA,
) {
    val raw = SpeechToText(context.applicationContext, mode)

    private sealed interface Event {
        data class Text(val text: String, val completed: Boolean) : Event
        data object Ended : Event
        data class Failed(val type: SttErrorType) : Event
    }

    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutex = Mutex()
    private val connectMutex = Mutex()

    @Volatile
    private var connected = false

    private val listener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) { events.tryEmit(Event.Text(stt, completed)) }
        override fun onEndedRecognition() { events.tryEmit(Event.Ended) }
        override fun onError(errorType: SttErrorType) { events.tryEmit(Event.Failed(errorType)) }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    suspend fun connect(timeoutMs: Long = 30_000) = connectMutex.withLock {
        if (connected) return@withLock
        val ok = withTimeoutOrNull(timeoutMs) {
            withContext(SdkThreads.voice) {
                suspendCancellableCoroutine { cont ->
                    val done = AtomicBoolean(false)
                    raw.initialize(object : SttInitListener {
                        override fun onSuccess() {
                            if (done.compareAndSet(false, true)) cont.resume(Unit)
                        }

                        override fun onFailure(errorType: SttErrorType) {
                            if (done.compareAndSet(false, true)) {
                                cont.resumeWithException(PleosSdkException("SpeechToText.initialize", errorType.name))
                            }
                        }
                    })
                }
                raw.addListener(listener)
                raw.setLocale(locale)
            }
        }
        if (ok == null) throw PleosSdkException("SpeechToText.initialize", "no response in ${timeoutMs}ms")
        connected = true
    }

    /**
     * 차량 마이크로 한 번 듣고 인식한 문장을 돌려준다. [timeoutMs] 안에 인식이 끝나지 않으면 그때까지의
     * 중간 결과를 돌려준다(아무 말도 없었으면 null).
     */
    suspend fun listen(timeoutMs: Long = 10_000): String? = mutex.withLock {
        recognize("SpeechToText.request", timeoutMs) { raw.request() }
    }

    /**
     * 마이크 대신 PCM 오디오(16kHz, 16bit, mono)를 넣어 인식한다. ON_DEVICE 모드에서만 동작한다.
     * 마이크가 없는 에뮬레이터에서 음성 대화 흐름을 시험할 때 쓴다.
     */
    suspend fun recognizePcm(pcm: ByteArray, timeoutMs: Long = 20_000): String? = mutex.withLock {
        recognize("SpeechToText.sendAudio", timeoutMs) {
            raw.sendAudio(pcm)
            raw.completeAudioSend()
        }
    }

    private suspend fun recognize(name: String, timeoutMs: Long, start: () -> Unit): String? = coroutineScope {
        var last: String? = null
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            events.first { event ->
                when (event) {
                    is Event.Text -> {
                        if (event.text.isNotBlank()) last = event.text
                        event.completed
                    }
                    Event.Ended, is Event.Failed -> true
                }
            }
        }
        try {
            withContext(SdkThreads.voice) { start() }
            val end = withTimeoutOrNull(timeoutMs) { waiter.await() }
            if (end is Event.Failed && last == null) throw PleosSdkException(name, end.type.name)
            if (end == null) stop()
            last
        } catch (c: CancellationException) {
            stop()
            throw c
        } finally {
            waiter.cancel()
        }
    }

    /** 듣기를 멈추고 마이크를 닫는다. */
    fun stop() {
        SdkThreads.voice.executor.execute { runCatching { raw.stop() } }
    }

    fun release() {
        SdkThreads.voice.executor.execute {
            runCatching { raw.removeListener(listener) }
            runCatching { raw.release() }
            connected = false
        }
    }
}
