package dev.shimpyo.coach

import dev.shimpyo.coach.voice.Hangul
import dev.shimpyo.coach.voice.IntentParser
import dev.shimpyo.coach.voice.Spoken
import dev.shimpyo.coach.voice.parseSpoken
import dev.shimpyo.coach.voice.replyTo
import dev.shimpyo.engine.CabinAction
import dev.shimpyo.engine.CabinState
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.CoachOutput
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.PitStopPlan
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.VoiceIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.truncate

/**
 * 쉼표의 코치 액터 (docs/design/coach-layer/core.md). 우편함 하나, 코루틴 하나. 메시지를 하나씩 처리하고, 처리 중에는
 * 절대 일시 중단하지 않는다. 오래 걸리는 일(음성, 내비 명령, LLM 해석, 파일 저장)은 효과로 launch하고 결과를 다시 메시지로 받는다.
 * 그래서 코어의 상태(세계·엔진·질문·정차·여정·급해·계획)는 잠금 없이 한 스레드만 바꾼다. 예전 CoachService는 필드 30개를
 * 코루틴 12종이 함께 바꿨다(구조 리뷰 §0·D6·D7).
 *
 * 판단은 순수 부품이 하고 코어는 그 결과를 잇는다: 엔진, PromptMachine(질문), UrgentFlow(급해), VoiceDesk(음성 작업),
 * PlanKeeper(계획), StopRecorder(정차), WorldSwitch(세계·여정), CoreRules.kt의 작은 규칙들.
 *
 * 1초 틱: 신호 → 정차 판정(StopRecorder) → 여정(WorldSwitch.onTick) → 내비 스냅샷·후보 투영 → 세계가 입력을 바꿈 → 엔진 →
 * 질문 기계(PromptMachine) → 효과 실행 → 화면 상태 한 번 갱신.
 */
class CoachCore(private val d: CoachDeps) {

    /** 우편함에 들어오는 것. 화면 명령과 효과의 결과 */
    sealed interface Msg {
        data object Tick : Msg
        data class Command(val command: UiCommand) : Msg
        /** 질문 기계로 가는 사건 (만료 타이머, 들르기 결과) */
        data class MachineEvent(val event: PromptEvent) : Msg
        /** 카드의 묻기 음성 작업이 끝났다. [text]는 들은 말 (못 들었으면 null) */
        data class AskHeard(val key: String, val text: String?) : Msg
        // 아래 넷은 사용자가 말한 것의 결과다. [gen]: 그 말을 듣기 시작한 때의 세계 세대 (ActiveWorld.generation).
        // 세계·엔진이 바뀐 뒤에 온 결과는 버린다: 시연 중에 한 말이 실제 세계에 적용되지 않는다 (리뷰 R-F8)
        /** 마이크 버튼으로 들은 말 */
        data class Listened(val gen: Int, val text: String?) : Msg
        data class Interpreted(val gen: Int, val intent: VoiceIntent?) : Msg
        data class OccupantsHeard(val gen: Int, val text: String?) : Msg
        data class OccupantsInterpreted(val gen: Int, val list: List<Occupant>?) : Msg
        data class FreshAirDone(val ok: Boolean, val announce: Boolean) : Msg
        data class MemorySaved(val memory: PersonalMemory, val ok: Boolean) : Msg
    }

    private val ui get() = d.ui
    private val log get() = d.log

    private val mailbox = Channel<Msg>(Channel.UNLIMITED)
    private val tickQueued = AtomicBoolean(false)
    /** 효과를 띄우는 곳 (run의 supervisorScope). run 전에는 null */
    private var effects: CoroutineScope? = null

    private val worlds = WorldSwitch(d.engines, d.settings, d.memory, d.trips, wall = d.wall, elapsed = d.elapsed)
    private val machine = PromptMachine()
    private val recorder = StopRecorder(StopDetector(), d.history, d.stops, d.log)
    private val goTo = GoToRunner(d.navi, d.log)
    private val parser = IntentParser()
    private val desk = VoiceDesk(d.voice, d.log)
    private val urgent = UrgentFlow(seedMs = d.elapsed.elapsedMs())
    private val plans = PlanKeeper(seedMs = d.elapsed.elapsedMs())
    private val freshAir = FreshAirGate()
    private val memorySaver = MemorySaver()
    private val travel = TravelHeading()
    private val asleep = AsleepMarks()

    private var started = false
    private var lastInput: CoachInput? = null
    private var lastOutput: CoachOutput? = null
    /** 엔진은 update 한 번에 의도 하나를 받는다. 몰리면 다음 틱으로 넘긴다 */
    private val pendingIntents = ArrayDeque<VoiceIntent>()
    private val expiryJobs = HashMap<String, Job>()

    /** 시연을 처음 켠 실제 시각. 끌 때 실제 정차를 그 시각에 끝내는 데 쓴다 (StopDetector.restore의 lastSeenMs) */
    private var demoSinceWall: Long? = null
    /** 화면 알림(CoachUiState.notice)을 거둘 실제 시각. 알림이 없으면 null */
    private var noticeUntilMs: Long? = null
    /** 마지막으로 본 건강 정보 동의 (처음에는 모름). 꺼진 것을 보면 건강 유형 탑승자를 뺀다 */
    private var healthConsentSeen: Boolean? = null
    /** 되돌린 정차의 판정을 기다리는 동안 열린 문. 판정 뒤 첫 엔진 입력에 싣는다 (엔진도 그 문을 본다) */
    private var doorWhileWaiting = false

    /** 내비에 넣는 중인 들르기 (한 번에 하나다, PromptMachine). 어느 세계에서 시작했고 급해 목록에서 고른 것인지 */
    private class GoToStart(val generation: Int, val urgentPick: Boolean)
    private var goToStart: GoToStart? = null
    /** 이번 기계 호출에서 끝난 들르기 (결과와 그 시작 정보). 효과가 모두 끝난 뒤 처리한다 */
    private var goToDone: Pair<GoToResult, GoToStart?>? = null
    private var lastPlan: PitStopPlan? = null

    // ------------------------------------------------------------ 바깥 API

    /** 어느 스레드에서나. 기다리지 않는다 */
    fun send(msg: Msg) {
        mailbox.trySend(msg)
    }

    fun command(c: UiCommand) = send(Msg.Command(c))

    /**
     * 시작(파일 읽기는 IO에서) 후 우편함을 끝까지 처리한다. 취소될 때까지 돌아오지 않는다. 코치 디스패처(한 스레드)에서 부른다.
     * 시작 전에 온 명령은 우편함에 남아 있다가 시작 뒤 차례로 처리된다.
     */
    suspend fun run(): Unit = supervisorScope {
        effects = this
        desk.scope = this
        start()
        launch {
            while (true) {
                delay(TICK_MS)
                // 코어가 밀려도 틱은 하나만 쌓는다
                if (tickQueued.compareAndSet(false, true)) mailbox.trySend(Msg.Tick)
            }
        }
        for (m in mailbox) {
            if (m === Msg.Tick) tickQueued.set(false)
            try {
                handle(m)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 메시지 하나가 실패해도 액터는 이어 간다
                log.w("core: ${m.javaClass.simpleName} failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 서비스 종료 때 마지막 저장. 액터가 멈춘 뒤 코치 디스패처에서 부른다(같은 스레드라 상태를 안전하게 읽는다).
     * 여정 맥박은 TripStorePort가 넘겨받고, 학습 상태는 여기서 바로 쓴다 (짧다).
     */
    fun shutdown() {
        if (!started) return
        // 되돌린 정차를 아직 판정하지 못했으면 맥박을 새로 쓰지 않는다 (확인하지 못한 정차를 '지금까지 서 있었다'로 남기지 않는다)
        if (!recorder.restorePending) runCatching { worlds.flush() }.onFailure { log.w("trip flush failed: ${it.message}") }
        memorySaver.atShutdown(worlds.memoryToSave())?.let { m ->
            val ok = runCatching { d.memory.save(m.memory, m.generation) }.onFailure { log.w("memory save failed: ${it.message}") }.getOrDefault(false)
            memorySaver.finished(m.memory, ok)
        }
    }

    // ------------------------------------------------------------ 시작

    private suspend fun start() {
        val loaded = withContext(d.io) {
            Loaded(
                heartbeat = runCatching { d.trips.load() }.getOrNull(),
                memory = runCatching { d.memory.load() }.getOrElse { PersonalMemory() },
                history = runCatching { d.history.load() }.getOrElse { emptyList() },
            )
        }
        val change = worlds.open(loaded.heartbeat, loaded.memory, loaded.history)
        val a = change.active
        val now = a.clock.now()
        recorder.load(StopHistory.sanitize(loaded.history, now))
        // 정차 중 재시작: 마지막 맥박 때까지는 서 있었다 (StopDetector.restore)
        recorder.restore(a.session.restoredPark, now, lastSeenMs = loaded.heartbeat?.savedAt ?: now)
        memorySaver.loaded(loaded.memory)
        enterWorld(a)
        enforceHealthConsent()
        started = true
        val shown = shownHistory(now)
        ui.update {
            copy(serviceRunning = true, occupants = a.world.occupants(d.settings), history = shown, lastStop = shown.lastOrNull(), demo = null)
        }
        log.i("coach started: trip ${a.session.origin}, parked ${a.session.restoredPark != null}")
    }

    private class Loaded(val heartbeat: TripHeartbeat?, val memory: PersonalMemory, val history: List<StopLog>)

    // ------------------------------------------------------------ 메시지

    private fun handle(m: Msg) {
        when (m) {
            Msg.Tick -> tick()
            is Msg.Command -> onCommand(m.command)
            is Msg.MachineEvent -> onPromptEvent(m.event)
            is Msg.AskHeard -> {
                desk.askEnded(m.key)
                // 카드에 한 말은 대답 하나다 (그 카드가 아직 떠 있으면 그 장소 이름을 넘겨, 장소를 가리킨 말을 새 요청으로 읽지 않게 한다)
                val card = machine.current?.takeIf { it.key == m.key }
                val reply = parser.replyTo(m.text, occupants(), card?.prompt?.stop?.name)
                onPromptEvent(PromptEvent.Heard(m.key, reply.answer, reply.other))
            }
            is Msg.Listened -> if (current(m.gen)) onListened(m.text)
            is Msg.Interpreted -> {
                clearThinking()
                if (current(m.gen)) m.intent?.let(::dispatchIntent) ?: notUnderstood()
            }
            is Msg.OccupantsHeard -> if (current(m.gen)) onOccupantsHeard(m.text)
            is Msg.OccupantsInterpreted -> if (current(m.gen)) applySpokenOccupants(m.list)
            is Msg.FreshAirDone -> {
                freshAir.finished(d.elapsed.elapsedMs(), switched = m.ok)
                if (m.ok && m.announce) speak("환기를 위해 외기로 바꿨어요")
            }
            is Msg.MemorySaved -> memorySaver.finished(m.memory, m.ok)
        }
    }

    private fun onCommand(c: UiCommand) {
        when (c) {
            is UiCommand.Urgent -> {
                urgent.open(c.requestId, d.elapsed.elapsedMs())
                urgentOpened(c.requestId)
            }
            is UiCommand.DismissUrgent -> if (urgent.dismiss(c.requestId)) publishUrgentClosed()
            UiCommand.Listen -> if (desk.busy) stopVoice() else startListen()
            UiCommand.StopVoice -> stopVoice()
            UiCommand.MakePlan -> {
                plans.request()
                refreshPlan(force = true)
            }
            is UiCommand.Answer -> onPromptEvent(PromptEvent.Tapped(c.promptId, c.accept))
            is UiCommand.GoTo -> onPromptEvent(PromptEvent.GoTo(c.stop, urgentPick = urgent.lists(c.stop.id)))
            UiCommand.DismissGoToResult -> onPromptEvent(PromptEvent.DismissGoToResult)
            is UiCommand.SetOccupants -> setOccupants(c.occupants)
            UiCommand.DescribeOccupants -> describeOccupants()
            is UiCommand.Demo -> switchDemo(c.scenario)
            UiCommand.ClearRecords -> clearRecords()
            UiCommand.ProfileChanged -> worlds.profileChanged()
            // 신호 수집을 다시 잇는 일은 서비스가 한다 (코어는 신호의 출처를 모른다)
            UiCommand.PermissionsChanged -> Unit
            UiCommand.ClosePlan -> plans.stop()
            is UiCommand.SetAsleep -> setAsleep(c.occupantId, c.asleep)
        }
    }

    // ------------------------------------------------------------ 틱

    private fun tick() {
        val el = d.elapsed.elapsedMs()
        d.odometer.advance(d.hub.speedKph)
        enforceHealthConsent()
        var a = worlds.active
        val t = a.clock.now()
        val real = d.positions.current.value?.fix
        val pos = a.world.position(real)
        val gear = d.hub.gear
        val speed = d.hub.speedKph
        val heading = travel.update(real, speed, gear)
        val (samples, door) = d.hub.drain(t)

        recorder.onTick(t, gear, speed, door, pos, a.world.persistsHistory)?.let { r ->
            worlds.onStop(r.log, t)
            if (r.log.restroom) asleepChanged(asleep.onRestroomStop())
            val shown = StopHistory.sanitize(r.history, t)
            ui.update { copy(history = shown, lastStop = r.log) }
            log.i("stop recorded: ${(r.log.end - r.log.start) / 60_000} min restroom=${r.log.restroom} persisted=${r.persisted}")
        }
        // 되돌린 정차(재시작, 시연 끄기)가 아직 서 있는지 떠났는지는 기어를 알아야 정한다. 그때까지 여정과 엔진은 기다린다:
        // 감지기·여정·엔진이 같은 판정을 쓴다 (서비스 닫힘 검증 N2·N3). 보통 1~2초, 길어도 StopDetector.RESTORE_WAIT_MS
        val waiting = recorder.restorePending
        if (waiting) doorWhileWaiting = doorWhileWaiting || door
        // 자는 중 표시: 화장실 정차가 되거나 아침이 지나면 푼다 (엔진 입력을 만들기 전에)
        asleepChanged(asleep.onTick(t, recorder.parkState.takeUnless { waiting }))
        if (!waiting) {
            recorder.takeRestoredEnd()?.let { endedAt ->
                log.i("restored park ended ${(t - endedAt) / 1000}s ago")
                worlds.restoredParkEnded(endedAt)
            }
            worlds.onTick(t, recorder.parkState)?.let { change ->
                log.i("new trip")
                applyChange(change)
                a = worlds.active
            }
        }

        val navi = d.tracker.snapshot.value
        val odo = d.odometer.meters
        val ahead = a.world.candidates(d.candidates.live(navi, odo))
        val input = a.world.apply(
            CoachInput(
                now = t,
                gear = gear,
                speedKph = speed,
                samples = samples,
                doorOpened = door || (!waiting && doorWhileWaiting),
                cabin = CabinState(hvacOn = d.hub.hvacOn, recirculation = d.hub.recirculation, setTempC = d.hub.setTempC),
                route = d.candidates.withHorizon(navi.liveRoute(odo), navi, odo),
                candidates = CoreRules.withExtras(ahead, urgent.extras(), pos),
                // 엔진에 넣을 때만 꺼낸다 (기다리는 틱에 꺼내면 그 말이 사라진다)
                intent = if (waiting) null else if (urgent.takeEngineIntent()) VoiceIntent.UrgentRestroom else pendingIntents.removeFirstOrNull(),
                cabinAutoAllowed = d.settings.autoFreshAir,
                asleepOccupantIds = asleep.ids,
            ),
        )
        d.loopInputs.request = CandidateRequest(CoreRules.searchFix(pos, real, heading), navi, a.world.candidatePolicy)
        val out = if (waiting) null else try {
            a.engine.update(input)
        } catch (e: Exception) {
            log.w("engine.update failed: ${e.message ?: e.javaClass.simpleName}")
            null
        }
        // 엔진이 본 입력만 남긴다: 기다리는 동안에는 급해·계획도 엔진을 부르지 않는다 (엔진 시계가 되돌린 정차보다 앞서면 안 된다)
        if (!waiting) {
            lastInput = input
            doorWhileWaiting = false
        }
        if (out != null) {
            logNext(out)
            lastOutput = out
            toMachine(PromptEvent.EngineTick(out.prompt, out.next, out.parked))
            autoFreshAir(out, el)
            urgent.track(pos, out.next?.stop?.id, el)
        }
        if (freshAir.takeRevert(el, d.hub.recirculation)) revertFreshAir()
        noticeUntilMs?.takeIf { el >= it }?.let { clearNotice() }
        if (!waiting) advanceUrgent(el, a, pos, input)
        refreshPlan(force = false)
        publish(out, input, navi, a.world, ahead)
        memorySaver.due(el) { worlds.memoryToSave() }?.let(::saveMemory)
    }

    /** 다음 추천이 바뀌거나 도착 예상이 1분 넘게 바뀌면 남긴다 (기기에서 정체가 도착 시간에 주는 영향을 확인하는 근거) */
    private fun logNext(out: CoachOutput) {
        val n = out.next?.stop
        val prev = lastOutput?.next?.stop
        val eta = n?.etaS
        if (n?.id == prev?.id && (eta == null || prev?.etaS?.let { kotlin.math.abs(it - eta) < 60.0 } == true)) return
        if (n == null) {
            if (prev != null) log.i("next: none")
            return
        }
        val dist = (n.routeDistanceM ?: n.straightDistanceM)?.let { "${it.toLong()}m" } ?: "?"
        log.i("next: ${n.name} $dist eta=${eta?.let { "${it.toLong()}s" } ?: "?"} (${out.next?.reasons?.joinToString(",") { it.code }})")
    }

    private fun publish(out: CoachOutput?, input: CoachInput, navi: NaviSnapshot, world: CoachWorld, ahead: List<StopCandidate>) {
        val r = input.route
        val promptStop = machine.current?.prompt?.stop?.let { liveStop(it.id) }
        val candidatesState = d.candidates.snapshot.value.state
        val cabin = d.cabin.freshAirSupported
        val tts = d.voice.ttsReady
        val stt = d.voice.sttReady
        val llm = d.interpreter?.label
        ui.update {
            copy(
                restroom = out?.restroom ?: restroom,
                fatigue = out?.fatigue ?: fatigue,
                // style(운전 스타일 편차)은 화면이 쓰지 않고 매초 바뀌어 화면 전체를 다시 그리게 해서 올리지 않는다
                next = if (out != null) out.next else next,
                parked = out?.parked ?: parked,
                // 차속은 시연 패널이 정수로만 보여 준다. 소수점 흔들림으로 매초 상태가 바뀌지 않게 정수로 올린다
                speedKph = input.speedKph?.let { truncate(it) },
                guiding = r?.guiding == true,
                destinationName = world.destinationName(navi.destination?.name),
                remainDistanceM = r?.remainDistanceM,
                remainTimeS = r?.remainTimeS,
                congestion = r?.congestion.orEmpty(),
                promptStop = promptStop,
                ahead = ahead,
                candidatesState = candidatesState,
                cabinSupported = cabin,
                ttsAvailable = tts,
                sttAvailable = stt,
                demo = world.name,
                llm = llm,
            )
        }
    }

    /** 그 정차의 지금 위치 기준 후보 (지난 틱의 엔진 입력에서). 거리가 지금 값이다 */
    private fun liveStop(id: String): StopCandidate? = lastInput?.candidates?.firstOrNull { it.id == id }

    // ------------------------------------------------------------ 질문 기계와 효과

    private fun promptContext(): PromptContext = PromptContext.of(
        coachMs = worlds.active.clock.now(),
        elapsedMs = d.elapsed.elapsedMs(),
        voiceEnabled = d.settings.voiceEnabled,
        ttsReady = d.voice.ttsReady,
        sttReady = d.voice.sttReady,
    )

    private fun onPromptEvent(e: PromptEvent) {
        if (e is PromptEvent.Expired) expiryJobs.remove(e.promptId)
        toMachine(e)
    }

    /** 질문 기계에 사건을 넣고 효과를 실행한다. 들르기 결과가 나왔으면 그 뒷일은 효과(엔진 보고 포함)가 모두 끝난 뒤에 한다 */
    private fun toMachine(e: PromptEvent) {
        runEffects(machine.on(e, promptContext()))
        goToDone?.let { (result, start) ->
            goToDone = null
            onGoToResult(result, start)
        }
    }

    private fun runEffects(list: List<PromptEffect>) {
        for (e in list) {
            when (e) {
                is PromptEffect.Show -> {
                    val stop = e.prompt?.stop?.let { liveStop(it.id) }
                    ui.update { copy(prompt = e.prompt, promptReasons = e.reasons, promptStop = stop) }
                    val p = e.prompt
                    if (p == null) log.i("prompt hidden") else {
                        val at = (stop?.routeDistanceM ?: stop?.straightDistanceM)?.let { " at ${it.toLong()}m" } ?: ""
                        log.i("prompt shown: ${p.kind} ${p.stop?.name ?: "-"}$at (${p.id})")
                    }
                }
                is PromptEffect.Ask -> desk.ask(e.promptId, e.speech, e.listen) { text -> send(Msg.AskHeard(e.promptId, text)) }
                PromptEffect.CancelVoice -> desk.cancelCard()
                is PromptEffect.Speak -> speak(e.text)
                is PromptEffect.Notice -> notice(e.text)
                is PromptEffect.ScheduleExpiry -> scheduleExpiry(e.promptId, e.atElapsedMs)
                is PromptEffect.Report -> try {
                    worlds.active.engine.onPromptResult(e.promptId, e.accept, worlds.active.clock.now())
                } catch (x: Exception) {
                    log.w("onPromptResult failed: ${x.message}")
                }
                is PromptEffect.StartGoTo -> startGoTo(e)
                is PromptEffect.FreshAir -> startFreshAir(e.announce)
                is PromptEffect.Forward -> dispatchIntent(e.intent)
                is PromptEffect.PublishGoToResult -> {
                    val r = e.result
                    if (r == null) ui.update { copy(goToResult = null) } else {
                        // 결과가 나왔다: 넣는 중 표시를 내린다. 같은 효과 묶음에서 대기하던 들르기가 시작되면 그것이 다시 올린다
                        ui.update { copy(goToResult = r, goToPending = null) }
                        goToDone = r to goToStart
                        goToStart = null
                    }
                }
            }
        }
    }

    private fun scheduleExpiry(key: String, atMs: Long) {
        expiryJobs.remove(key)?.cancel()
        expiryJobs[key] = effect("expiry") {
            delay((atMs - d.elapsed.elapsedMs()).coerceAtLeast(0L))
            send(Msg.MachineEvent(PromptEvent.Expired(key)))
        }
    }

    private fun startGoTo(e: PromptEffect.StartGoTo) {
        // 지금 위치 기준 후보(같은 id)로 실행한다: 결과 안내의 거리가 지금 거리다 (prompt.md 6.4)
        val live = liveStop(e.stop.id) ?: e.stop
        log.i("goTo ${live.name} (request ${e.requestId}, parked=${e.parked})")
        goToStart = GoToStart(worlds.active.generation, urgentPick = urgent.onGoToStarted(live))
        ui.update { copy(goToPending = live) }
        effect("goTo") {
            val outcome = try {
                goTo.run(live, e.parked)
            } catch (c: CancellationException) {
                throw c
            } catch (x: Throwable) {
                // Error 등: 결과를 못 보내면 들르기가 감시 시간(140초)까지 줄을 막는다
                log.w("goTo ${live.name} crashed: ${x.message}")
                GoToOutcome.FAILED
            }
            send(Msg.MachineEvent(PromptEvent.GoToFinished(e.requestId, outcome, live)))
        }
    }

    /**
     * 들르기 하나가 끝났다 (기계가 결과를 올렸다: 내비의 답, 또는 감시가 끝낸 실패). 기계의 엔진 보고(질문 수락)가 끝난 뒤에 불린다.
     * [start]: 그 들르기를 시작할 때의 세계와 급해 목록 선택 여부
     */
    private fun onGoToResult(r: GoToResult, start: GoToStart?) {
        val ok = r.outcome != GoToOutcome.FAILED
        log.i("goTo result: ${r.outcome} ${r.stop.name}")
        // 경로를 바꿨으면 내비를 곧바로 다시 읽는다 (경로 위 안내·검색 열기는 경로를 바꾸지 않았다)
        if (r.outcome == GoToOutcome.WAYPOINT_ADDED || r.outcome == GoToOutcome.ROUTE_STARTED) d.tracker.requestRefresh(routeChanged = true)
        if (urgent.onGoToFinished(r.stop, ok, d.elapsed.elapsedMs())) publishUrgentClosed()
        // 성공한 들르기는 모두 엔진의 '들르기로 한 곳'이다. 세계가 바뀌기 전에 보낸 것은 지금 엔진의 일이 아니다
        val a = worlds.active
        if (!ok || start == null || start.generation != a.generation || lastInput == null) return
        try {
            d.accepted.note(a.engine, r.stop, CoreRules.acceptedPurposes(r.stop, start.urgentPick, lastOutput?.next, lastPlan), a.clock.now())
        } catch (x: Exception) {
            log.w("noteAccepted failed: ${x.message}")
        }
    }

    // ------------------------------------------------------------ 음성

    /** 대답을 받지 않는 말. 설정에서 음성을 껐으면 아무것도 말하지 않는다 (UI 감사 #12) */
    private fun speak(text: String) {
        if (!d.settings.voiceEnabled || !d.voice.ttsReady) return
        desk.say(text)
    }

    /** 사용자가 말로 물은 것의 대답: 말할 수 있으면 말하고, 아니면 화면 알림으로 낸다 (리뷰 R-F4) */
    private fun answer(text: String) {
        if (promptContext().speak) desk.say(text) else notice(text)
    }

    /** 화면에 잠깐 보일 알림. [NOTICE_MS] 뒤 틱이 거둔다. 새 알림이 앞의 것을 바꾼다 */
    private fun notice(text: String) {
        noticeUntilMs = d.elapsed.elapsedMs() + NOTICE_MS
        ui.update { copy(notice = text) }
    }

    private fun clearNotice() {
        noticeUntilMs = null
        ui.update { copy(notice = null) }
    }

    /** 음성 결과가 지금 세계의 것인지 (그 말을 들은 뒤 세계·엔진이 바뀌지 않았는지) */
    private fun current(gen: Int): Boolean = gen == worlds.active.generation

    private fun clearThinking() = ui.update { if (voice == VoiceState.THINKING) copy(voice = VoiceState.IDLE) else this }

    /** 사용자가 멈추기를 눌렀다. 카드가 묻던 중이면 버튼으로 대답할 시간을 준다 (Heard UNKNOWN) */
    private fun stopVoice() {
        val key = desk.stopAll()
        ui.update { if (voice == VoiceState.UNAVAILABLE) this else copy(voice = VoiceState.IDLE) }
        key?.let { send(Msg.AskHeard(it, null)) }
    }

    private fun startListen() {
        if (!d.voice.sttReady) return
        val gen = worlds.active.generation
        desk.listen { text -> send(Msg.Listened(gen, text)) }
    }

    private fun onListened(text: String?) {
        if (text.isNullOrBlank()) return
        // 떠 있는 카드에 대한 대답이면 Accept/Decline(key)로 나온다 (prompt.md 6.2)
        when (val spoken = parser.parseSpoken(text, occupants(), activePromptId = machine.current?.key)) {
            is Spoken.Intent -> return dispatchIntent(spoken.intent)
            is Spoken.Asleep -> return answer(asleepReply(setAsleep(spoken.occupantId, spoken.asleep)))
            null -> Unit
        }
        val interpreter = d.interpreter ?: return notUnderstood()
        val people = occupants()
        val key = machine.current?.key
        val gen = worlds.active.generation
        ui.update { copy(voice = VoiceState.THINKING) }
        desk.interpret("interpret", failed = null, done = { send(Msg.Interpreted(gen, it)) }) { interpreter.interpret(text, people, key) }
    }

    private fun notUnderstood() = answer("잘 못 알아들었어요. 화장실 급해, 졸려, 얼마나 남았어처럼 말해 주세요")

    /** 말로 한 요청. 질문 대답은 기계로, 급해는 급해 흐름으로, 나머지는 다음 틱 엔진 입력으로 (엔진이 안내·질문으로 답한다) */
    private fun dispatchIntent(intent: VoiceIntent) {
        when (intent) {
            is VoiceIntent.Accept -> onPromptEvent(PromptEvent.Tapped(intent.promptId, true))
            is VoiceIntent.Decline -> onPromptEvent(PromptEvent.Tapped(intent.promptId, false))
            VoiceIntent.UrgentRestroom ->
                if (urgent.active) {
                    urgent.sayAgain()
                    advanceUrgentNow()
                } else {
                    urgentOpened(urgent.openByVoice(d.elapsed.elapsedMs()))
                }
            else -> {
                if (pendingIntents.size >= MAX_PENDING_INTENTS) pendingIntents.removeFirst()
                pendingIntents.addLast(intent)
            }
        }
    }

    // ------------------------------------------------------------ 환기

    private fun autoFreshAir(out: CoachOutput, el: Long) {
        if (out.cabinAction != CabinAction.FRESH_AIR || !d.settings.autoFreshAir) return
        if (!freshAir.autoAllowed(el, d.hub.recirculation)) return
        startFreshAir(announce = promptContext().speak)
    }

    private fun startFreshAir(announce: Boolean) {
        if (!d.cabin.freshAirSupported || !freshAir.begin(d.elapsed.elapsedMs())) return
        effect("freshAir") {
            val ok = try {
                d.cabin.freshAir()
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                log.w("freshAir failed: ${x.message}")
                false
            }
            send(Msg.FreshAirDone(ok, announce))
        }
    }

    private fun revertFreshAir() {
        log.i("fresh air: back to recirculation")
        effect("recirculate") {
            val ok = try {
                d.cabin.recirculate()
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                false
            }
            if (!ok) log.w("recirculate not confirmed")
        }
    }

    // ------------------------------------------------------------ 탑승자

    private fun occupants(): List<Occupant> = worlds.active.world.occupants(d.settings)

    private fun setOccupants(list: List<Occupant>) {
        val a = worlds.setOccupants(HealthConsent.allowed(list, d.settings.healthConsent))
        val now = a.world.occupants(d.settings)
        asleep.onOccupants(now)
        ui.update { copy(occupants = now, asleepOccupantIds = asleep.ids) }
    }

    /** 탑승자를 자는 중으로 표시하거나 푼다 (화면의 표시, 말 "아이 잠들었어"·"깼어"). 아이만 표시할 수 있다 (AsleepMarks) */
    private fun setAsleep(occupantId: String?, on: Boolean): AsleepMarks.Result {
        val result = asleep.set(occupantId, on, occupants(), worlds.active.clock.now())
        asleepChanged(result == AsleepMarks.Result.MARKED || result == AsleepMarks.Result.CLEARED)
        return result
    }

    private fun asleepChanged(changed: Boolean) {
        if (changed) ui.update { copy(asleepOccupantIds = asleep.ids) }
    }

    private fun asleepReply(result: AsleepMarks.Result): String = when (result) {
        AsleepMarks.Result.MARKED -> "자는 동안은 화장실 여유를 조금 더 둘게요"
        AsleepMarks.Result.CLEARED -> "깬 것으로 바꿨어요"
        AsleepMarks.Result.NOT_A_CHILD -> "자는 중 표시는 아이 탑승자에게만 쓸 수 있어요"
        AsleepMarks.Result.UNKNOWN_WHO -> "누가 자는지 탑승자 화면에서 표시해 주세요"
        AsleepMarks.Result.NO_CHANGE -> "네, 알겠어요"
    }

    /**
     * 건강 정보 동의가 꺼져 있으면(화면이 설정을 바꾼다: 명령은 오지 않는다) 건강 유형 탑승자를 모든 세계에서 뺀다 (UI 닫힘 검증 N3).
     * 동의 값이 바뀐 때와 시작할 때만 탑승자를 본다 (동의 읽기는 싸고, 탑승자 읽기는 설정을 다시 푼다)
     */
    private fun enforceHealthConsent() {
        val consent = d.settings.healthConsent
        if (consent == healthConsentSeen) return
        healthConsentSeen = consent
        if (!consent && worlds.dropHealthOccupants()) {
            log.i("health consent off: health-type occupants removed")
            val now = occupants()
            asleep.onOccupants(now)
            ui.update { copy(occupants = now, asleepOccupantIds = asleep.ids) }
        }
    }

    /**
     * [말로 설정]: 누가 타는지 묻고 듣는다. 음성 안내를 껐으면 묻는 말은 화면 알림으로 내고 듣기만 한다. 음성 인식을 쓸 수 없으면
     * 왜 안 되는지 알린다 (UI 닫힘 검증 #12: 예전에는 눌러도 조용히 아무 일도 없었다)
     */
    private fun describeOccupants() {
        if (desk.describing) return
        if (!d.voice.sttReady) return notice("음성 인식을 쓸 수 없어요. 화면에서 골라 주세요")
        val question = "누가 함께 타나요? 예를 들어 일곱 살 아이 하나랑 강아지처럼 말해 주세요"
        val speak = promptContext().speak
        if (!speak) notice(question)
        val gen = worlds.active.generation
        desk.describe(question.takeIf { speak }) { text -> send(Msg.OccupantsHeard(gen, text)) }
    }

    private fun onOccupantsHeard(text: String?) {
        if (text.isNullOrBlank()) return
        val list = OccupantPhrase.parse(text)
        val interpreter = d.interpreter
        if (list.isNotEmpty() || interpreter == null) return applySpokenOccupants(list)
        val gen = worlds.active.generation
        desk.interpret("interpretOccupants", failed = null, done = { send(Msg.OccupantsInterpreted(gen, it)) }) { interpreter.occupants(text) }
    }

    private fun applySpokenOccupants(list: List<Occupant>?) {
        if (list.isNullOrEmpty()) return answer("잘 못 알아들었어요. 화면에서 골라 주세요")
        val heard = HealthConsent.spoken(list, d.settings.healthConsent)
        heard.apply?.let(::setOccupants)
        // 한 번에 말한다 (화면 알림은 한 자리라 두 문장을 따로 내면 앞의 것이 지워진다)
        answer(
            listOfNotNull(
                "건강 관련 정보는 설정에서 동의한 뒤에 넣을 수 있어요".takeIf { heard.consentNeeded },
                heard.apply?.let { "${Hangul.euRo(OccupantPhrase.describe(it))} 맞췄어요" },
            ).joinToString(" "),
        )
    }

    // ------------------------------------------------------------ 세계 (시연·실제)

    private fun switchDemo(name: String?) {
        val wasReal = worlds.active === worlds.real
        val change = worlds.enterDemo(name)
        val nowReal = worlds.active === worlds.real
        var lastSeen: Long? = null
        if (wasReal && !nowReal) demoSinceWall = d.wall.now()
        if (!wasReal && nowReal) {
            lastSeen = demoSinceWall
            demoSinceWall = null
        }
        applyChange(change, lastSeen)
        log.i("demo started: ${worlds.active.world.name ?: "off"}")
    }

    private fun clearRecords() {
        recorder.clear()
        // 세대를 바로 올려야 이미 꺼낸 저장이 지운 학습을 되살리지 않는다 (AtomicFile). 파일 하나 지우기라 짧다
        runCatching { d.memory.clear() }.onFailure { log.w("memory clear failed: ${it.message}") }
        memorySaver.cleared()
        applyChange(worlds.clearLearning())
        val now = worlds.active.clock.now()
        val shown = shownHistory(now)
        ui.update { copy(history = shown, lastStop = shown.lastOrNull()) }
    }

    /**
     * 세계·엔진이 바뀌었다 (WorldChange). 틱 사이에 한 번에 바꾼다 (구조 리뷰 D6·D11, world.md 6).
     * [lastSeenMs]: 시연을 끌 때 시연을 켠 실제 시각 (실제 정차를 그때 끝낼지 이어 갈지 감지기가 정한다)
     */
    private fun applyChange(change: WorldChange, lastSeenMs: Long? = null) {
        change.saveMemory?.let(::saveMemory)
        when (change.kind) {
            WorldChange.Kind.NONE -> return
            WorldChange.Kind.ENGINE -> {
                resetPrompts()
                urgent.onEngineReset()
                plans.engineChanged()
            }
            WorldChange.Kind.WORLD -> {
                resetPrompts()
                val a = change.active
                val now = a.clock.now()
                recorder.onWorldChanged(now, change.restorePark, lastSeenMs ?: now)
                urgent.reset()
                plans.worldChanged()
                asleep.clear()
                lastInput = null
                lastOutput = null
                lastPlan = null
                enterWorld(a)
                d.candidates.reset()
                val shown = shownHistory(now)
                ui.update {
                    copy(
                        demo = a.world.name, occupants = a.world.occupants(d.settings), history = shown, lastStop = shown.lastOrNull(),
                        urgent = null, urgentState = UrgentState.IDLE, plan = null, ahead = emptyList(), promptStop = null,
                        asleepOccupantIds = emptySet(),
                    )
                }
            }
        }
    }

    /** 새 세계의 정책을 다른 루프에 알린다 (내비 위치 보충, 후보 입력, 경로 보정 스위치) */
    private fun enterWorld(a: ActiveWorld) {
        d.oracle.enabled = a.world.oracleEnabled
        d.loopInputs.naviFallback = a.world.usesNaviLocationFallback
        val real = d.positions.current.value?.fix
        // 시작하자마자 달리고 있으면 첫 후보 갱신부터 진행 방향을 안다 (첫 틱을 기다리면 방위 없이 한 번 만든 목록이 20초 간다)
        val heading = travel.update(real, d.hub.speedKph, d.hub.gear)
        d.loopInputs.request = CandidateRequest(CoreRules.searchFix(a.world.position(real), real, heading), d.tracker.snapshot.value, a.world.candidatePolicy)
    }

    /**
     * 질문 상태를 비우고 지난 세계에서 시작한 음성 작업을 모두 멈춘다 (그 결과는 세대가 달라 버려진다).
     * 실행 중인 들르기는 취소하지 않는다 (결과는 보고 없이 알린다, prompt.md 6.6)
     */
    private fun resetPrompts() {
        pendingIntents.clear()
        expiryJobs.values.forEach { it.cancel() }
        expiryJobs.clear()
        toMachine(PromptEvent.Reset)
        desk.worldChanged()
        clearThinking()
    }

    private fun shownHistory(now: Long) = StopHistory.sanitize(recorder.shown, now)

    // ------------------------------------------------------------ 급해

    /** 급해 요청이 열렸다: 화면에 바로 알리고, 위치를 알면 이 자리에서 찾는다 */
    private fun urgentOpened(requestId: Long) {
        log.i("urgent $requestId")
        ui.update { copy(urgentRequestId = requestId, urgentState = UrgentState.LOCATING, urgent = null) }
        advanceUrgentNow()
    }

    /** 틱을 기다리지 않고 급해를 한 걸음 진행한다 (명령·말에 바로 답한다). 엔진 입력이 아직 없으면 다음 틱이 한다 */
    private fun advanceUrgentNow() {
        val input = lastInput ?: return
        val a = worlds.active
        advanceUrgent(d.elapsed.elapsedMs(), a, a.world.position(d.positions.current.value?.fix), input)
    }

    private fun advanceUrgent(el: Long, a: ActiveWorld, pos: Fix?, input: CoachInput) {
        val before = urgent.view ?: return
        when (urgent.step(el, pos != null, d.stops.loaded)) {
            UrgentFlow.Step.NONE -> {}
            UrgentFlow.Step.NO_LOCATION -> speak("현재 위치를 아직 모르겠어요")
            UrgentFlow.Step.SEARCH -> {
                val real = d.positions.current.value?.fix
                val f = CoreRules.searchFix(pos, real, travel.heading)!!
                val near = d.stops.near(f.lat, f.lon, f.heading, UrgentFlow.RADIUS_M, CoreRules.PARKABLE_KINDS, now = input.now, limit = UrgentFlow.MAX)
                // 화면 목록과 엔진의 급해 질문이 같은 곳을 1등으로 고르도록 같은 후보(경로 후보 + 가까운 화장실)로 순위를 매긴다
                val ranked = try {
                    a.engine.urgent(input.copy(candidates = CoreRules.withExtras(input.candidates, near, pos), intent = null))
                } catch (e: Exception) {
                    log.w("urgent rank failed: ${e.message}")
                    emptyList()
                }
                // 처음 찾았으면 다음 틱에 엔진에 급해를 알린다 (UrgentFlow.takeEngineIntent, 서비스 감사 D7)
                if (urgent.found(el, near, ranked)) log.i("urgent ${before.requestId}: ${ranked.size} results")
            }
        }
        val after = urgent.view ?: return
        if (after != before) ui.update { copy(urgentRequestId = after.requestId, urgentState = after.state, urgent = after.results) }
    }

    /** 급해 요청이 끝났다 (닫기, 들르기 성공). 화면은 같은 번호의 IDLE을 보고 닫는다 */
    private fun publishUrgentClosed() = ui.update { copy(urgentState = UrgentState.IDLE, urgent = null) }

    // ------------------------------------------------------------ 계획

    private fun refreshPlan(force: Boolean) {
        val input = lastInput ?: return
        val key = Triple(d.candidates.snapshot.value, d.tracker.snapshot.value.epoch, worlds.active.generation)
        if (!plans.due(d.elapsed.elapsedMs(), key, force)) return
        val plan: PitStopPlan? = try {
            worlds.active.engine.plan(input.copy(intent = null))
        } catch (e: Exception) {
            log.w("plan failed: ${e.message}")
            return
        }
        val gen = plans.built()
        lastPlan = plan
        ui.update { copy(plan = plan, planGeneration = gen) }
    }

    // ------------------------------------------------------------ 저장

    /** 학습 상태를 IO에서 쓴다 (언제 쓸지는 MemorySaver) */
    private fun saveMemory(m: MemorySave) {
        memorySaver.started(d.elapsed.elapsedMs())
        effect("saveMemory") {
            val ok = try {
                withContext(d.io) { d.memory.save(m.memory, m.generation) }
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                log.w("memory save failed: ${x.message}")
                false
            }
            send(Msg.MemorySaved(m.memory, ok))
        }
    }

    // ------------------------------------------------------------ 효과 띄우기

    /** 효과 하나. 실패는 로그만 남긴다 (supervisorScope라 액터는 영향받지 않는다) */
    private fun effect(name: String, block: suspend CoroutineScope.() -> Unit): Job {
        val scope = checkNotNull(effects) { "CoachCore.run 전" }
        return scope.launch {
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                log.w("$name failed: ${x.message ?: x.javaClass.simpleName}")
            }
        }
    }

    companion object {
        const val TICK_MS = 1_000L
        const val MAX_PENDING_INTENTS = 4
        /** 화면 알림을 보여 두는 시간 (틱이 거두므로 6~7초) */
        const val NOTICE_MS = 6_000L
    }
}
