package dev.shimpyo.coach

import dev.shimpyo.coach.voice.YesNo
import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.Reason
import dev.shimpyo.engine.Recommendation
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.VoiceIntent

/*
 * 운전자에게 보이고 들리는 질문 하나의 상태 기계 (docs/design/coach-layer/prompt.md).
 * 순수 함수다: on(사건, 시각) → 효과 목록. 코루틴·입출력이 없고, CoachCore(액터)가 효과를 실행하고 결과를 사건으로 돌려준다.
 * 예전에는 CoachService의 activePrompt·lastPromptId·CoachStore.prompt·질문 코루틴 네 곳에 상태가 흩어져 엔진의 질문 교체·철회를
 * 따르지 못했다(서비스 감사 D1·D2, 구조 리뷰 D1·D2).
 */

/** 질문을 낸 쪽. 앱 질문(급해 결과)은 떠 있는 동안 엔진 질문보다 앞선다 */
enum class PromptOrigin { ENGINE, APP }

/** 화면에 뜬 질문 하나 */
data class PromptCard(
    /** 낸 쪽이 준 질문 그대로 (엔진에 알릴 때는 이 id를 쓴다) */
    val prompt: Prompt,
    /** 화면·음성이 대답과 함께 돌려보내는 id. 원래 id + 세대라 엔진을 바꾼 뒤 옛 카드의 대답이 새 질문에 닿지 않는다 */
    val key: String,
    val origin: PromptOrigin,
    /** 띄울 때의 추천 이유. 질문 도중 엔진 추천이 다음 곳으로 넘어가도 카드 표시는 그대로 둔다 (UI 감사 #6) */
    val reasons: List<Reason>,
    /** 띄운 실제 시각 (ElapsedClock) */
    val shownAtMs: Long,
    /** 이 실제 시각이 되면 거둔다 */
    val deadlineMs: Long,
) {
    /** 화면에 올릴 질문 (id = key) */
    val shown: Prompt get() = prompt.copy(id = key)
}

sealed interface PromptState {
    data object Idle : PromptState
    /** 카드가 떠 있고 음성(묻기·듣기)이 돌고 있다 */
    data class Asking(val card: PromptCard) : PromptState
    /** 카드가 떠 있고 음성은 끝났다 (대답을 못 알아들었거나 음성을 끔). 버튼이나 마이크로 대답을 받는다 */
    data class AwaitingTap(val card: PromptCard) : PromptState
}

/**
 * 사건이 일어난 때의 환경. 코어가 사건마다 만든다.
 * @property coachMs 코치 시각 (GoToResult.at에만 쓴다)
 * @property elapsedMs 실제 시각. 카드 수명은 이것으로만 잰다 (시연 배속과 무관, 구조 리뷰 B5)
 * @property speak 말해도 되는지 (설정의 음성 켜기 + TTS 연결)
 * @property listen 마이크를 열어도 되는지 (speak + STT 연결)
 */
data class PromptContext(val coachMs: Long, val elapsedMs: Long, val speak: Boolean, val listen: Boolean) {
    companion object {
        fun of(coachMs: Long, elapsedMs: Long, voiceEnabled: Boolean, ttsReady: Boolean, sttReady: Boolean): PromptContext {
            val speak = voiceEnabled && ttsReady
            return PromptContext(coachMs, elapsedMs, speak, speak && sttReady)
        }
    }
}

sealed interface PromptEvent {
    /** 코치 틱마다: 엔진의 이번 질문(CoachOutput.prompt), 추천(이유를 잡는 데), 정차 여부 */
    data class EngineTick(val offered: Prompt?, val next: Recommendation?, val parked: Boolean) : PromptEvent
    /** 앱이 만든 질문 (급해 결과). reasons: 그 장소의 순위 이유 (RankedStop.why) */
    data class AppPrompt(val prompt: Prompt, val reasons: List<Reason> = emptyList()) : PromptEvent
    /**
     * 음성 대답 (질문의 음성 작업이 끝났을 때, 또는 마이크 버튼으로 들은 말이 지금 질문의 대답일 때).
     * [other]: 같은 말 안의 다른 요청 (IntentParser.parse(text, occupants, activePromptId = null)). 예: "아니 화장실 급해"
     */
    data class Heard(val promptId: String, val answer: YesNo, val other: VoiceIntent? = null) : PromptEvent
    /** 화면 버튼 (UiCommand.Answer) */
    data class Tapped(val promptId: String, val accept: Boolean) : PromptEvent
    /** ScheduleExpiry 타이머 */
    data class Expired(val promptId: String) : PromptEvent
    /** 화면의 들르기 (급해 목록, 계획 화면, 실패 뒤 다시 시도) */
    data class GoTo(val stop: StopCandidate) : PromptEvent
    /** StartGoTo 실행 결과. [stop]: 코어가 실행에 쓴 지금 위치 기준 후보 (거리를 말하는 데), 없으면 요청한 후보 */
    data class GoToFinished(val requestId: Long, val outcome: GoToOutcome, val stop: StopCandidate? = null) : PromptEvent
    /** 세계(시연·실제)·엔진 교체, 기록 지우기 */
    data object Reset : PromptEvent
}

sealed interface PromptEffect {
    /** CoachUiState.prompt / promptReasons */
    data class Show(val prompt: Prompt?, val reasons: List<Reason> = emptyList()) : PromptEffect
    /** 질문의 음성 작업: 말하고 [listen]이면 듣는다. 끝나면 반드시 Heard(promptId, …)를 보낸다 (못 들었으면 UNKNOWN) */
    data class Ask(val promptId: String, val speech: String, val listen: Boolean) : PromptEffect
    /** 질문의 음성 작업을 취소하고 지금 말하기·듣기를 멈춘다 (VoicePort.stop) */
    data object CancelVoice : PromptEffect
    /** 대답을 받지 않는 말 (안내, 들르기 결과) */
    data class Speak(val text: String) : PromptEffect
    /** 이 실제 시각에 Expired(promptId)를 보낸다. 같은 id로 다시 오면 앞의 것을 바꾼다 */
    data class ScheduleExpiry(val promptId: String, val atElapsedMs: Long) : PromptEffect
    /** engine.onPromptResult(promptId, accept). promptId는 엔진이 준 원래 id */
    data class Report(val promptId: String, val accept: Boolean) : PromptEffect
    /** GoToRunner.run(stop, parked)를 실행하고 GoToFinished(requestId, …)를 보낸다 */
    data class StartGoTo(val requestId: Long, val stop: StopCandidate, val parked: Boolean) : PromptEffect
    /** 외기로 전환 (CabinPort.freshAir). announce면 바뀐 뒤 알린다 */
    data class FreshAir(val announce: Boolean) : PromptEffect
    /** 말로 한 다른 요청. 마이크 버튼으로 들은 말과 똑같이 처리한다 (급해, 졸려, 얼마나 남았어 …) */
    data class Forward(val intent: VoiceIntent) : PromptEffect
    /** CoachUiState.goToResult */
    data class PublishGoToResult(val result: GoToResult?) : PromptEffect
}

/**
 * 질문 상태 기계. CoachCore만 부른다(한 스레드). 규칙:
 *
 * 1. 엔진 질문은 엔진이 진실이다. 틱마다 엔진이 낸 질문이 새 id면 지금 음성을 멈추고 새 카드를 띄워 묻는다(같은 틱).
 *    엔진 카드가 떠 있는데 엔진이 null이나 다른 질문을 내면 카드를 내린다(지나친 곳에 경유지를 넣지 않는다).
 * 2. 앱 질문(급해 결과)은 떠 있는 동안 엔진 질문보다 앞선다. 끝나면 엔진이 아직 내는 질문을 다시 띄운다.
 * 3. P로 서면 주행 질문(PromptRules)은 거둔다. 거둔 질문은 출발해도 다시 띄우지 않는다.
 * 4. 안내(INFO)는 말만 하고 카드·마이크를 쓰지 않는다. 엔진의 질문 자리를 비우도록 바로 전달됐다고 알린다(Report true).
 * 5. 대답은 지금 카드의 key에만 반영한다. 대답·만료·정차로 닫은 질문은 엔진이 계속 내도 다시 띄우지 않는다.
 *    대답하면 Idle이 되므로 음성 '응'과 버튼이 함께 와도 두 번째는 무시된다(구조 리뷰 D7).
 * 6. 수락: 장소가 있으면 들르기를 실행하고, 내비 명령이 성공했을 때만 엔진에 수락을 알린다. 실패하면 GoToResult(FAILED)를 올리고
 *    화면의 다시 시도(같은 곳 GoTo)가 성공하면 그때 알린다. 환기는 공조 효과와 함께 바로 알린다. 장소 없는 질문은 바로 알린다.
 * 7. 카드 수명은 실제 시간이다: 엔진 질문 [ENGINE_CARD_MS], 앱 질문 [APP_CARD_MS]. 음성이 대답 없이 끝나면 적어도
 *    [MIN_TAP_MS]는 누를 수 있게 늘리되 수명 + [MIN_TAP_MS]를 넘지 않는다.
 * 8. 음성을 끄면 Ask·Speak를 내지 않는다(카드만).
 * 9. Reset: 음성을 멈추고 카드를 내리고 세대를 올린다. 이미 보낸 내비 명령의 결과는 알리되 옛 엔진에는 보고하지 않는다.
 */
class PromptMachine {
    var state: PromptState = PromptState.Idle
        private set

    /** 떠 있는 카드. 코어가 promptStop(카드 장소의 지금 거리)과 IntentParser의 activePromptId(= key)에 쓴다 */
    val current: PromptCard?
        get() = when (val s = state) {
            PromptState.Idle -> null
            is PromptState.Asking -> s.card
            is PromptState.AwaitingTap -> s.card
        }

    /** 실행 중인 들르기 (테스트·로그용) */
    val goToInFlight: StopCandidate? get() = inFlight?.stop
    val goToQueued: StopCandidate? get() = queued?.stop

    private var generation = 0
    /** 이 세대에서 닫은 질문 id (대답·만료·정차·대체된 앱 질문). 엔진이 계속 내도 다시 띄우지 않는다 */
    private val closed = LinkedHashSet<String>()
    /** 마지막 틱의 정차 여부. 모르면 주행으로 본다 (주행 중 화면을 여는 쪽으로 틀리지 않게) */
    private var lastParked = false

    // 들르기: 내비 명령은 한 번에 하나. 실행 중에 온 요청은 하나만 줄 세우고 새 요청이 앞의 대기 요청을 바꾼다
    private data class Link(val engineId: String, val generation: Int)
    private data class GoToRequest(val id: Long, val stop: StopCandidate, val link: Link?)

    private var nextRequestId = 1L
    private var inFlight: GoToRequest? = null
    private var queued: GoToRequest? = null
    /** 마지막으로 실패한 질문 수락 (장소 id → 질문). 화면의 다시 시도가 같은 곳이면 이어 붙인다 */
    private var retryLink: Pair<String, Link>? = null

    fun on(event: PromptEvent, ctx: PromptContext): List<PromptEffect> {
        val out = Effects()
        when (event) {
            is PromptEvent.EngineTick -> onTick(event, ctx, out)
            is PromptEvent.AppPrompt -> onAppPrompt(event, ctx, out)
            is PromptEvent.Heard -> onHeard(event, ctx, out)
            is PromptEvent.Tapped -> current?.takeIf { it.key == event.promptId }?.let { answer(it, event.accept, ctx, out) }
            is PromptEvent.Expired -> current?.takeIf { it.key == event.promptId && ctx.elapsedMs >= it.deadlineMs }?.let { close(it, out) }
            is PromptEvent.GoTo -> {
                val link = retryLink?.takeIf { it.first == event.stop.id && it.second.generation == generation }?.second
                requestGoTo(event.stop, link, out)
            }
            is PromptEvent.GoToFinished -> onGoToFinished(event, ctx, out)
            PromptEvent.Reset -> {
                generation++
                closed.clear()
                state = PromptState.Idle
                queued = null
                retryLink = null
                out += PromptEffect.CancelVoice
                out += PromptEffect.Show(null)
                out += PromptEffect.PublishGoToResult(null)
            }
        }
        return out.finish()
    }

    // ------------------------------------------------------------ 엔진 틱

    private fun onTick(e: PromptEvent.EngineTick, ctx: PromptContext, out: Effects) {
        lastParked = e.parked
        current?.let { card ->
            when {
                // 실제 수명 (만료 타이머를 잃어도 틱이 거둔다)
                ctx.elapsedMs >= card.deadlineMs -> close(card, out)
                PromptRules.withdrawWhenParked(card.prompt.kind, e.parked) -> close(card, out)
            }
        }
        val offered = e.offered
        if (offered != null && PromptRules.speakOnly(offered.kind)) {
            // 엔진 출력은 질문 하나라 이 틱에는 카드 질문이 살아 있는지 모른다. 카드는 그대로 두고 다음 틱에 판단한다
            if (offered.id !in closed) {
                announce(offered.speech, ctx, out)
                out += PromptEffect.Report(offered.id, true)
                markClosed(offered.id)
            }
            return
        }
        val card = current
        if (card != null) {
            if (card.origin == PromptOrigin.APP) return // 급해 결과가 앞선다. 엔진 질문은 끝난 뒤 다음 틱에 띄운다
            if (offered?.id == card.prompt.id) return
            // 엔진이 거뒀거나 다른 질문으로 바꿨다. 닫지는 않는다: 엔진이 다시 내면 그것이 엔진의 지금 질문이다
            withdraw(out)
        }
        if (offered == null || offered.id in closed || PromptRules.withdrawWhenParked(offered.kind, e.parked)) return
        val reasons = e.next?.takeIf { offered.stop != null && it.stop.id == offered.stop?.id }?.reasons.orEmpty()
        show(offered, PromptOrigin.ENGINE, reasons, ctx, out)
    }

    private fun onAppPrompt(e: PromptEvent.AppPrompt, ctx: PromptContext, out: Effects) {
        val p = e.prompt
        if (PromptRules.speakOnly(p.kind)) {
            announce(p.speech, ctx, out)
            return
        }
        val card = current
        if (card != null && card.origin == PromptOrigin.APP && card.prompt.id == p.id) return
        if (p.id in closed) return
        if (card != null) {
            withdraw(out)
            // 밀려난 앱 질문은 끝났다. 밀려난 엔진 질문은 엔진이 계속 내면 급해 카드가 끝난 뒤 다시 띄운다
            if (card.origin == PromptOrigin.APP) markClosed(card.prompt.id)
        }
        show(p, PromptOrigin.APP, e.reasons, ctx, out)
    }

    // ------------------------------------------------------------ 대답

    private fun onHeard(e: PromptEvent.Heard, ctx: PromptContext, out: Effects) {
        // 같은 말 안의 다른 요청은 질문이 이미 내려갔어도 운전자가 한 말이라 전한다. 수락·거절 의도는 yes/no로 이미 받았다
        val forward = e.other?.takeIf { it !is VoiceIntent.Accept && it !is VoiceIntent.Decline }
        val card = current?.takeIf { it.key == e.promptId }
        if (card != null) {
            // 대답이 왔으면 음성 작업은 끝났다 (멈출 음성이 없다)
            state = PromptState.AwaitingTap(card)
            when (e.answer) {
                YesNo.YES -> answer(card, true, ctx, out)
                YesNo.NO -> answer(card, false, ctx, out)
                YesNo.UNKNOWN -> {
                    val life = lifetime(card.origin)
                    val deadline = maxOf(card.deadlineMs, ctx.elapsedMs + MIN_TAP_MS).coerceAtMost(card.shownAtMs + life + MIN_TAP_MS)
                    val next = card.copy(deadlineMs = deadline)
                    state = PromptState.AwaitingTap(next)
                    if (deadline != card.deadlineMs) out += PromptEffect.ScheduleExpiry(card.key, deadline)
                }
            }
        }
        forward?.let { out += PromptEffect.Forward(it) }
    }

    private fun answer(card: PromptCard, accept: Boolean, ctx: PromptContext, out: Effects) {
        close(card, out)
        val engineId = card.prompt.id.takeIf { card.origin == PromptOrigin.ENGINE }
        if (!accept) {
            engineId?.let { out += PromptEffect.Report(it, false) }
            return
        }
        val stop = card.prompt.stop
        when {
            // 엔진에는 내비 명령이 성공한 뒤에 알린다 (onGoToFinished)
            stop != null -> requestGoTo(stop, engineId?.let { Link(it, generation) }, out)
            card.prompt.kind == PromptKind.FRESH_AIR -> {
                out += PromptEffect.FreshAir(announce = ctx.speak)
                engineId?.let { out += PromptEffect.Report(it, true) }
            }
            else -> engineId?.let { out += PromptEffect.Report(it, true) }
        }
    }

    // ------------------------------------------------------------ 들르기

    private fun requestGoTo(stop: StopCandidate, link: Link?, out: Effects) {
        // 같은 곳이 이미 실행 중이거나 줄 서 있으면 다시 보내지 않는다(두 번 누름). 새 요청이 질문 수락이면 그 질문으로 알린다
        inFlight?.takeIf { it.stop.id == stop.id }?.let {
            if (link != null) inFlight = it.copy(link = link)
            return
        }
        queued?.takeIf { it.stop.id == stop.id }?.let {
            if (link != null) queued = it.copy(link = link)
            return
        }
        val req = GoToRequest(nextRequestId++, stop, link)
        if (inFlight == null) start(req, out) else queued = req
    }

    private fun start(req: GoToRequest, out: Effects) {
        inFlight = req
        out += PromptEffect.PublishGoToResult(null) // 지난 결과(실패 카드 등)를 내린다
        out += PromptEffect.StartGoTo(req.id, req.stop, lastParked)
    }

    private fun onGoToFinished(e: PromptEvent.GoToFinished, ctx: PromptContext, out: Effects) {
        val req = inFlight?.takeIf { it.id == e.requestId } ?: return
        inFlight = null
        val stop = e.stop ?: req.stop
        out += PromptEffect.PublishGoToResult(GoToResult(stop, e.outcome, ctx.coachMs))
        announce(GoToPlanner.speech(e.outcome, stop), ctx, out)
        val link = req.link?.takeIf { it.generation == generation }
        if (e.outcome == GoToOutcome.FAILED) {
            if (link != null) retryLink = req.stop.id to link
        } else {
            link?.let { out += PromptEffect.Report(it.engineId, true) }
            if (retryLink?.first == req.stop.id) retryLink = null
            // 그곳으로 가자는 카드가 떠 있으면(계획 화면에서 같은 곳을 눌렀다) 이미 답한 것이다
            current?.takeIf { it.prompt.stop?.id == req.stop.id }?.let { card ->
                close(card, out)
                if (card.origin == PromptOrigin.ENGINE && card.prompt.id != link?.engineId) out += PromptEffect.Report(card.prompt.id, true)
            }
        }
        queued?.let {
            queued = null
            start(it, out)
        }
    }

    // ------------------------------------------------------------ 공통

    private fun show(p: Prompt, origin: PromptOrigin, reasons: List<Reason>, ctx: PromptContext, out: Effects) {
        val card = PromptCard(p, keyOf(p.id, generation), origin, reasons, ctx.elapsedMs, ctx.elapsedMs + lifetime(origin))
        out.show(card.shown, reasons)
        out += PromptEffect.ScheduleExpiry(card.key, card.deadlineMs)
        state = if (ctx.speak) {
            out += PromptEffect.Ask(card.key, p.speech, ctx.listen)
            PromptState.Asking(card)
        } else {
            PromptState.AwaitingTap(card)
        }
    }

    /** 떠 있는 카드를 내린다 (음성이 돌고 있으면 멈춘다). 닫지는 않는다 */
    private fun withdraw(out: Effects) {
        if (state is PromptState.Asking) out += PromptEffect.CancelVoice
        out.hide()
        state = PromptState.Idle
    }

    /** 카드를 내리고 그 질문을 닫는다 (다시 띄우지 않는다) */
    private fun close(card: PromptCard, out: Effects) {
        withdraw(out)
        markClosed(card.prompt.id)
    }

    private fun markClosed(id: String) {
        closed += id
        if (closed.size > CLOSED_MAX) closed.remove(closed.first())
    }

    private fun announce(text: String, ctx: PromptContext, out: Effects) {
        if (ctx.speak) out += PromptEffect.Speak(text)
    }

    private fun lifetime(origin: PromptOrigin) = if (origin == PromptOrigin.ENGINE) ENGINE_CARD_MS else APP_CARD_MS

    /** 효과 모음. 카드를 내린 뒤 같은 사건에서 새 카드를 띄우면 Show(null) 자리에 새 Show가 들어간다 */
    private class Effects {
        private val list = ArrayList<PromptEffect>(4)
        private var hideAt = -1

        operator fun plusAssign(e: PromptEffect) {
            list += e
        }

        fun hide() {
            if (hideAt < 0) {
                hideAt = list.size
                list += PromptEffect.Show(null)
            }
        }

        fun show(p: Prompt, reasons: List<Reason>) {
            val e = PromptEffect.Show(p, reasons)
            if (hideAt >= 0) list[hideAt] = e else list += e
            hideAt = -1
        }

        fun finish(): List<PromptEffect> = list
    }

    companion object {
        /**
         * 엔진 질문 카드의 실제 수명. 엔진의 질문 수명(CoachConfig.promptTtlS, 45초)과 같게 둬서 실제 주행에서는 두 수명이 함께 끝난다.
         * 시연 배속에서는 엔진 수명이 코치 시각으로 흐르므로(7.2배면 6초) 시연 엔진은 promptTtlS를 배속만큼 늘려 만들어야 한다
         * (CoachWorld 담당). 45초면 TTS(약 6~8초)와 듣기(6초) 뒤에도 30초 넘게 버튼을 누를 수 있고, 시속 100km로 1.25km라
         * 마지막 기회(2~5km 전)의 진입로 전에 끝난다.
         */
        val ENGINE_CARD_MS: Long = CoachConfig().promptTtlS * 1_000L
        /** 앱 급해 결과의 실제 수명 (예전 CoachService.urgent의 90초). 급해 화면에서 고를 시간을 준다 */
        const val APP_CARD_MS = 90_000L
        /** 음성이 대답 없이 끝난 뒤 적어도 이만큼은 버튼을 누를 수 있다 */
        const val MIN_TAP_MS = 10_000L
        private const val CLOSED_MAX = 32

        /** 화면·음성에 쓰는 질문 id. 세대가 다르면 같은 엔진 id라도 다른 key다 */
        fun keyOf(promptId: String, generation: Int): String = "$promptId#$generation"
    }
}
