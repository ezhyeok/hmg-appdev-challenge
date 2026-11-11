package dev.shimpyo.coach

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * 코치의 음성 작업을 모두 든다: 카드의 묻기, 마이크 버튼 듣기, 탑승자 말하기, LLM 해석, 대답 없는 말. 코어는 "무엇을 말하고
 * 들을지"만 정하고, 어떤 작업이 돌고 있는지·무엇을 취소할지는 여기서 본다. 코어 스레드에서만 부른다.
 *
 * 작업은 코어의 효과 스코프에서 돌고, 결과는 [done]으로 한 번 돌려준다. [done]은 효과 코루틴 안에서 불리므로 우편함에 메시지를
 * 넣는 일만 해야 한다(코어 상태를 직접 바꾸지 않는다). 취소된 작업은 결과를 돌려주지 않는다: 취소한 쪽이 뒷일을 안다.
 */
class VoiceDesk(private val voice: VoicePort, private val log: CoachLog = CoachLog.NONE) {
    /** 효과를 띄우는 곳 (CoachCore.run의 supervisorScope). run 전에는 null */
    var scope: CoroutineScope? = null

    private var askJob: Job? = null
    private var askKey: String? = null
    private var listenJob: Job? = null
    private var describeJob: Job? = null
    private var interpretJob: Job? = null
    private val speakJobs = ArrayList<Job>(4)

    /** 말하거나 듣거나 해석하는 중인지 (마이크 버튼: 바쁘면 멈추기) */
    val busy: Boolean
        get() {
            speakJobs.removeAll { !it.isActive }
            return askJob?.isActive == true || listenJob?.isActive == true || describeJob?.isActive == true ||
                interpretJob?.isActive == true || speakJobs.isNotEmpty()
        }

    /** 탑승자 말하기가 돌고 있는지 (두 번 누르기) */
    val describing: Boolean get() = describeJob?.isActive == true

    // ------------------------------------------------------------ 작업

    /**
     * 카드의 묻기: 말하고 [listen]이면 대답을 듣는다. 끝나면 반드시 [done]을 부른다 (못 들었거나 실패했으면 null).
     * 앞의 묻기는 취소한다 (카드는 한 번에 하나다)
     */
    fun ask(key: String, speech: String, listen: Boolean, done: (String?) -> Unit) {
        askJob?.cancel()
        askKey = key
        askJob = launch("ask", failed = null, done = done) {
            if (listen) voice.ask(speech) else {
                voice.say(speech)
                null
            }
        }
    }

    /** 그 카드의 묻기 결과가 코어에 도착했다 (작업이 끝났다) */
    fun askEnded(key: String) {
        if (askKey == key) {
            askJob = null
            askKey = null
        }
    }

    /** 대답을 받지 않는 말. 지금 말 뒤에 이어 말한다 (VoicePort가 차례를 세운다) */
    fun say(text: String) {
        speakJobs.removeAll { !it.isActive }
        speakJobs += launch("say", failed = false, done = {}) { voice.say(text) }
    }

    /** 마이크 버튼 듣기 */
    fun listen(done: (String?) -> Unit) {
        listenJob = launch("listen", failed = null, done = done) { voice.listen() }
    }

    /** 탑승자 말하기: [question]을 묻고 대답을 듣는다 */
    fun describe(question: String, done: (String?) -> Unit) {
        describeJob = launch("describe", failed = null, done = done) { voice.ask(question, listenMs = DESCRIBE_LISTEN_MS) }
    }

    /** 규칙으로 못 알아들은 말의 해석 (LLM). [failed]: 해석이 실패했을 때의 결과 */
    fun <T> interpret(name: String, failed: T, done: (T) -> Unit, block: suspend () -> T) {
        interpretJob = launch(name, failed, done, block)
    }

    // ------------------------------------------------------------ 멈추기

    /** 카드의 음성을 멈춘다 (기계의 CancelVoice: 카드를 내리거나 바꿀 때). 마이크 버튼 듣기도 멈춘다 */
    fun cancelCard() {
        askJob?.cancel()
        askJob = null
        askKey = null
        listenJob?.cancel()
        listenJob = null
        voice.stop()
    }

    /**
     * 모든 음성 작업을 멈춘다 (사용자가 멈추기를 눌렀다). 카드가 묻던 중이었으면 그 카드의 key를 돌려준다:
     * 코어가 '대답 없음'으로 알려 버튼으로 대답할 시간을 준다
     */
    fun stopAll(): String? {
        val key = askKey?.takeIf { askJob?.isActive == true }
        askJob?.cancel()
        askJob = null
        askKey = null
        listenJob?.cancel()
        describeJob?.cancel()
        interpretJob?.cancel()
        speakJobs.forEach { it.cancel() }
        speakJobs.clear()
        voice.stop()
        return key
    }

    // ------------------------------------------------------------ 띄우기

    /** 음성 작업 하나. 끝나면 [done]으로 결과를 돌려준다. 취소됐으면 돌려주지 않는다 */
    private fun <T> launch(name: String, failed: T, done: (T) -> Unit, block: suspend () -> T): Job {
        val s = checkNotNull(scope) { "CoachCore.run 전" }
        return s.launch {
            val result = try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                log.w("$name failed: ${x.message ?: x.javaClass.simpleName}")
                if (name != "ask") return@launch
                failed
            }
            ensureActive()
            done(result)
        }
    }

    companion object {
        /** 탑승자를 말하는 데 주는 시간 */
        const val DESCRIBE_LISTEN_MS = 8_000L
    }
}
