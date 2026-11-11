package dev.shimpyo.coach

import dev.shimpyo.coach.voice.IntentParser
import dev.shimpyo.engine.CabinAction
import dev.shimpyo.engine.CabinState
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.CoachOutput
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.PitStopPlan
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.VoiceIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.truncate

/**
 * 코어가 쓰는 바깥(포트)과 부품 한 묶음. 앱(CoachService)과 테스트(CoachCoreTest)가 같은 방법으로 만든다.
 * [tracker]·[candidates]의 루프는 코어 밖에서 같은 코치 디스패처로 돌린다(서비스가 launch). 코어는 그 스냅샷만 읽는다.
 */
class CoachDeps(
    /** 실제 시각 (epoch ms). 실제 세계의 코치 시각이고 맥박·정차 기록에 쓴다 */
    val wall: CoachClock,
    val elapsed: ElapsedClock,
    val hub: SignalHub,
    val odometer: Odometer,
    val positions: PositionTracker,
    val navi: NaviPort,
    val tracker: NaviTracker,
    val candidates: CandidateProvider,
    val oracle: TripEstimator,
    val stops: StopIndex,
    val voice: VoicePort,
    /** 규칙으로 못 알아들은 말을 맡는다. 없으면 null */
    val interpreter: IntentInterpreter?,
    val cabin: CabinPort,
    val history: HistoryPort,
    val memory: MemoryPort,
    val settings: SettingsPort,
    val trips: TripStorePort,
    val engines: EngineFactory,
    val ui: UiSink,
    val gates: CoachGates,
    /** 파일 읽기·쓰기 (앱: Dispatchers.IO, 테스트: 테스트 디스패처) */
    val io: CoroutineDispatcher,
    val log: CoachLog = CoachLog.NONE,
)

/**
 * 코어가 쓰고 다른 루프가 읽는 값 두 개. 내비 추적과 후보 루프는 코어보다 먼저 만들어지므로(코어의 부품이다) 코어를 직접 참조하지 않고
 * 이것을 읽는다. 모두 같은 코치 디스패처에서 돌지만 값을 통째로 바꾸므로 @Volatile로 둔다.
 */
class CoachGates {
    /** 위치 SDK가 끊겼을 때 내비 위치로 보충할지 (CoachWorld.usesNaviLocationFallback) */
    @Volatile var naviFallback: Boolean = true
    /** 후보 갱신 루프의 입력 (CandidateProvider.run) */
    @Volatile var request: CandidateRequest = CandidateRequest(null, NaviSnapshot(), CandidatePolicy.REAL)
}

/**
 * 쉼표의 코치 액터 (docs/design/coach-layer/core.md). 우편함 하나, 코루틴 하나. 메시지를 하나씩 처리하고, 처리 중에는
 * 절대 일시 중단하지 않는다. 오래 걸리는 일(음성, 내비 명령, LLM 해석, 파일 저장)은 효과로 launch하고 결과를 다시 메시지로 받는다.
 * 그래서 코어의 상태(세계·엔진·질문·정차·여정·급해·계획)는 잠금 없이 한 스레드만 바꾼다. 예전 CoachService는 필드 30개를
 * 코루틴 12종이 함께 바꿨다(구조 리뷰 §0·D6·D7).
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
        data class Prompt(val event: PromptEvent) : Msg
        /** 카드의 묻기 음성 작업이 끝났다. [text]는 들은 말 (못 들었으면 null) */
        data class AskHeard(val key: String, val text: String?) : Msg
        /** 마이크 버튼으로 들은 말 */
        data class Listened(val text: String?) : Msg
        data class Interpreted(val text: String, val intent: VoiceIntent?) : Msg
        data class OccupantsHeard(val text: String?) : Msg
        data class OccupantsInterpreted(val list: List<Occupant>?) : Msg
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
    private val freshAir = FreshAirGate()

    private var started = false
    private var lastInput: CoachInput? = null
    private var lastOutput: CoachOutput? = null
    /** 엔진은 update 한 번에 의도 하나를 받는다. 몰리면 다음 틱으로 넘긴다 */
    private val pendingIntents = ArrayDeque<VoiceIntent>()

    // 음성 작업. 모두 코어 스레드에서만 바꾼다
    private var askJob: Job? = null
    private var askKey: String? = null
    private var listenJob: Job? = null
    private var describeJob: Job? = null
    private var interpretJob: Job? = null
    private val speakJobs = ArrayList<Job>(4)
    private val expiryJobs = HashMap<String, Job>()

    private var urgent: UrgentSession? = null
    /** 코어가 연 급해 요청 번호 (음성 "화장실 급해"). 화면 번호(양수, 0 아님)와 겹치지 않게 음수 */
    private var coreUrgentId = 0L
    /** 급해에서 들르기로 한 곳. 엔진 후보에 계속 넣어 엔진이 '들르기로 한 곳'을 추적하게 한다 (UI #9) */
    private var pinned: Pinned? = null
    private class Pinned(val stop: StopCandidate, var seenAtMs: Long)

    /** 시연을 처음 켠 실제 시각. 끌 때 실제 정차를 그 시각에 끝내는 데 쓴다 (StopDetector.restore의 lastSeenMs) */
    private var demoSinceWall: Long? = null

    private var planWanted = false
    private var planKey: Any? = null
    private var planAtMs = 0L
    /** 요청(MakePlan)에 답해 새로 세운 계획의 번호 (CoachUiState.planGeneration). 떠 있는 동안 고쳐 내는 계획은 올리지 않는다 */
    private var planGeneration = 0L
    /** MakePlan을 받았고 아직 그 계획을 내지 않았다 (첫 틱 전에 와도 다음 계산에서 번호를 올린다) */
    private var planRequested = false

    /** 마지막으로 올린 들르기 결과의 at과 올린 실제 시각 (결과마다 다른 at, 오래된 결과 거두기) */
    private var lastGoToAt = Long.MIN_VALUE
    private var goToShownAtMs: Long? = null

    private var lastSavedMemory: PersonalMemory? = null
    private var memorySaving = false
    private var lastMemorySaveMs: Long? = null
    private var freshAirBusy = false

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
        runCatching { worlds.flush() }.onFailure { log.w("trip flush failed: ${it.message}") }
        worlds.memoryToSave()?.takeIf { it.memory != lastSavedMemory }?.let { m ->
            runCatching { d.memory.save(m.memory, m.generation) }.onFailure { log.w("memory save failed: ${it.message}") }
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
        lastSavedMemory = loaded.memory
        enterWorld(a)
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
            is Msg.Prompt -> onPromptEvent(m.event)
            is Msg.AskHeard -> {
                if (askKey == m.key) {
                    askJob = null
                    askKey = null
                }
                onPromptEvent(PromptEvent.Heard(m.key, parser.yesNo(m.text), parser.parse(m.text, occupants(), activePromptId = null)))
            }
            is Msg.Listened -> onListened(m.text)
            is Msg.Interpreted -> {
                ui.update { if (voice == VoiceState.THINKING) copy(voice = VoiceState.IDLE) else this }
                m.intent?.let(::dispatchIntent) ?: notUnderstood()
            }
            is Msg.OccupantsHeard -> onOccupantsHeard(m.text)
            is Msg.OccupantsInterpreted -> applySpokenOccupants(m.list)
            is Msg.FreshAirDone -> {
                freshAirBusy = false
                if (m.ok) {
                    freshAir.switched(d.elapsed.elapsedMs())
                    if (m.announce) speak("환기를 위해 외기로 바꿨어요")
                }
            }
            is Msg.MemorySaved -> {
                memorySaving = false
                if (m.ok) lastSavedMemory = m.memory
            }
        }
    }

    private fun onCommand(c: UiCommand) {
        when (c) {
            is UiCommand.Urgent -> startUrgent(c.requestId)
            is UiCommand.DismissUrgent -> if (urgent?.requestId == c.requestId) endUrgent()
            UiCommand.Listen -> if (voiceBusy()) stopVoice() else startListen()
            UiCommand.StopVoice -> stopVoice()
            UiCommand.MakePlan -> {
                planWanted = true
                planRequested = true
                planKey = null
                refreshPlan(force = true)
            }
            is UiCommand.Answer -> onPromptEvent(PromptEvent.Tapped(c.promptId, c.accept))
            is UiCommand.GoTo -> onPromptEvent(PromptEvent.GoTo(c.stop))
            UiCommand.DismissGoToResult -> onPromptEvent(PromptEvent.DismissGoToResult)
            is UiCommand.SetOccupants -> setOccupants(c.occupants)
            UiCommand.DescribeOccupants -> describeOccupants()
            is UiCommand.Demo -> switchDemo(c.scenario)
            UiCommand.ClearRecords -> clearRecords()
            UiCommand.ProfileChanged -> worlds.profileChanged()
            // 신호 수집을 다시 잇는 일은 서비스가 한다 (코어는 신호의 출처를 모른다)
            UiCommand.PermissionsChanged -> Unit
        }
    }

    // ------------------------------------------------------------ 틱

    private fun tick() {
        val el = d.elapsed.elapsedMs()
        d.odometer.advance(d.hub.speedKph)
        var a = worlds.active
        val t = a.clock.now()
        val real = d.positions.current.value?.fix
        val pos = a.world.position(real)
        val gear = d.hub.gear
        val speed = d.hub.speedKph
        val (samples, door) = d.hub.drain(t)

        recorder.onTick(t, gear, speed, door, pos, a.world.persistsHistory)?.let { r ->
            worlds.onStop(r.log, t)
            val shown = StopHistory.sanitize(r.history, t)
            ui.update { copy(history = shown, lastStop = r.log) }
            log.i("stop recorded: ${(r.log.end - r.log.start) / 60_000} min restroom=${r.log.restroom} persisted=${r.persisted}")
        }
        worlds.onTick(t, recorder.parkState)?.let { change ->
            log.i("new trip")
            apply(change)
            a = worlds.active
        }

        val navi = d.tracker.snapshot.value
        val odo = d.odometer.meters
        val ahead = a.world.candidates(d.candidates.live(navi, odo))
        val engineCandidates = CoreRules.withExtras(ahead, extras(), pos)
        val input = a.world.apply(
            CoachInput(
                now = t,
                gear = gear,
                speedKph = speed,
                samples = samples,
                doorOpened = door,
                cabin = CabinState(hvacOn = d.hub.hvacOn, recirculation = d.hub.recirculation, setTempC = d.hub.setTempC),
                route = d.candidates.withHorizon(navi.liveRoute(odo), navi, odo),
                candidates = engineCandidates,
                intent = pendingIntents.removeFirstOrNull(),
                cabinAutoAllowed = d.settings.autoFreshAir,
            ),
        )
        d.gates.request = CandidateRequest(CoreRules.searchFix(pos, real, speed, gear), navi, a.world.candidatePolicy)
        val out = try {
            a.engine.update(input)
        } catch (e: Exception) {
            log.w("engine.update failed: ${e.message ?: e.javaClass.simpleName}")
            null
        }
        lastInput = input
        if (out != null) {
            lastOutput = out
            runEffects(machine.on(PromptEvent.EngineTick(out.prompt, out.next, out.parked), ctx()))
            autoFreshAir(out, el)
            trackPinned(out, el)
        }
        if (freshAir.takeRevert(el, d.hub.recirculation)) revertFreshAir()
        goToShownAtMs?.let { if (el - it >= GO_TO_RESULT_TTL_MS) onPromptEvent(PromptEvent.DismissGoToResult) }
        advanceUrgent(el, a, pos, input)
        refreshPlan(force = false)
        publish(out, input, navi, a.world, ahead)
        autosaveMemory(el)
    }

    private fun publish(out: CoachOutput?, input: CoachInput, navi: NaviSnapshot, world: CoachWorld, ahead: List<StopCandidate>) {
        val r = input.route
        val cardStop = machine.current?.prompt?.stop?.id
        val promptStop = cardStop?.let { id -> input.candidates.firstOrNull { it.id == id } }
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

    // ------------------------------------------------------------ 질문 기계와 효과

    private fun ctx(): PromptContext = PromptContext.of(
        coachMs = worlds.active.clock.now(),
        elapsedMs = d.elapsed.elapsedMs(),
        voiceEnabled = d.settings.voiceEnabled,
        ttsReady = d.voice.ttsReady,
        sttReady = d.voice.sttReady,
    )

    private fun onPromptEvent(e: PromptEvent) {
        if (e is PromptEvent.Expired) expiryJobs.remove(e.promptId)
        runEffects(machine.on(e, ctx()))
        if (e is PromptEvent.GoToFinished && e.outcome != GoToOutcome.FAILED) onGoToSucceeded(e)
    }

    private fun runEffects(list: List<PromptEffect>) {
        for (e in list) {
            when (e) {
                is PromptEffect.Show -> {
                    val stop = e.prompt?.stop?.id?.let { id -> lastInput?.candidates?.firstOrNull { it.id == id } }
                    ui.update { copy(prompt = e.prompt, promptReasons = e.reasons, promptStop = stop) }
                    val p = e.prompt
                    if (p == null) log.i("prompt hidden") else {
                        val at = (stop?.routeDistanceM ?: stop?.straightDistanceM)?.let { " at ${it.toLong()}m" } ?: ""
                        log.i("prompt shown: ${p.kind} ${p.stop?.name ?: "-"}$at (${p.id})")
                    }
                }
                is PromptEffect.Ask -> startAsk(e)
                PromptEffect.CancelVoice -> cancelCardVoice()
                is PromptEffect.Speak -> speak(e.text)
                is PromptEffect.ScheduleExpiry -> scheduleExpiry(e.promptId, e.atElapsedMs)
                is PromptEffect.Report -> try {
                    worlds.active.engine.onPromptResult(e.promptId, e.accept, worlds.active.clock.now())
                } catch (x: Exception) {
                    log.w("onPromptResult failed: ${x.message}")
                }
                is PromptEffect.StartGoTo -> startGoTo(e)
                is PromptEffect.FreshAir -> startFreshAir(e.announce)
                is PromptEffect.Forward -> dispatchIntent(e.intent)
                is PromptEffect.PublishGoToResult -> publishGoToResult(e.result)
            }
        }
    }

    /**
     * 들르기 결과. 화면은 at으로 새 결과를 가린다(ui.md): 같은 순간(코치 시각 ms)에 두 결과가 나도 at이 겹치지 않게 늘 키운다.
     * 결과는 [GO_TO_RESULT_TTL_MS] 뒤 거둔다 (지나간 실패 카드·다시 시도가 남지 않게)
     */
    private fun publishGoToResult(r: GoToResult?) {
        val out = r?.let { if (it.at <= lastGoToAt) it.copy(at = lastGoToAt + 1) else it }
        if (out != null) lastGoToAt = out.at
        goToShownAtMs = out?.let { d.elapsed.elapsedMs() }
        ui.update { copy(goToResult = out) }
    }

    private fun startAsk(e: PromptEffect.Ask) {
        askJob?.cancel()
        askKey = e.promptId
        askJob = effect("ask") {
            val text = try {
                if (e.listen) d.voice.ask(e.speech) else {
                    d.voice.say(e.speech)
                    null
                }
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                log.w("ask failed: ${x.message}")
                null
            }
            // 끝나면 반드시 알린다 (못 들었으면 null = UNKNOWN). 취소됐으면 알리지 않는다 (prompt.md 6.1)
            ensureActive()
            send(Msg.AskHeard(e.promptId, text))
        }
    }

    /** 질문의 음성을 멈춘다 (기계의 CancelVoice: 카드를 내리거나 바꿀 때) */
    private fun cancelCardVoice() {
        askJob?.cancel()
        askJob = null
        askKey = null
        listenJob?.cancel()
        listenJob = null
        d.voice.stop()
    }

    /** 사용자가 마이크를 눌러 멈췄다. 카드가 묻던 중이면 버튼으로 대답할 시간을 준다 (Heard UNKNOWN) */
    private fun stopVoice() {
        val key = askKey?.takeIf { askJob?.isActive == true }
        askJob?.cancel()
        askJob = null
        askKey = null
        listenJob?.cancel()
        describeJob?.cancel()
        interpretJob?.cancel()
        speakJobs.forEach { it.cancel() }
        speakJobs.clear()
        d.voice.stop()
        ui.update { if (voice == VoiceState.UNAVAILABLE) this else copy(voice = VoiceState.IDLE) }
        key?.let { send(Msg.AskHeard(it, null)) }
    }

    private fun voiceBusy(): Boolean {
        speakJobs.removeAll { !it.isActive }
        return askJob?.isActive == true || listenJob?.isActive == true || describeJob?.isActive == true ||
            interpretJob?.isActive == true || speakJobs.isNotEmpty()
    }

    /** 대답을 받지 않는 말. 설정에서 음성을 껐으면 아무것도 말하지 않는다 (UI 감사 #12) */
    private fun speak(text: String) {
        if (!d.settings.voiceEnabled || !d.voice.ttsReady) return
        speakJobs.removeAll { !it.isActive }
        speakJobs += effect("say") { d.voice.say(text) }
    }

    private fun scheduleExpiry(key: String, atMs: Long) {
        expiryJobs.remove(key)?.cancel()
        expiryJobs[key] = effect("expiry") {
            delay((atMs - d.elapsed.elapsedMs()).coerceAtLeast(0L))
            send(Msg.Prompt(PromptEvent.Expired(key)))
        }
    }

    private fun startGoTo(e: PromptEffect.StartGoTo) {
        // 지금 위치 기준 후보(같은 id)로 실행한다: 결과 안내의 거리가 지금 거리다 (prompt.md 6.4)
        val live = lastInput?.candidates?.firstOrNull { it.id == e.stop.id } ?: e.stop
        log.i("goTo ${live.name} (request ${e.requestId}, parked=${e.parked})")
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
            send(Msg.Prompt(PromptEvent.GoToFinished(e.requestId, outcome, live)))
        }
    }

    private fun onGoToSucceeded(e: PromptEvent.GoToFinished) {
        log.i("goTo result: ${e.outcome} ${e.stop?.name}")
        // 경로를 바꿨으면 내비를 곧바로 다시 읽는다 (경로 위 안내·검색 열기는 경로를 바꾸지 않았다)
        if (e.outcome == GoToOutcome.WAYPOINT_ADDED || e.outcome == GoToOutcome.ROUTE_STARTED) d.tracker.requestRefresh(routeChanged = true)
        val u = urgent ?: return
        val stop = e.stop
        // 급해에서 고른 곳은 경로 후보에 없을 수 있다: 엔진이 들르기로 한 곳을 계속 보게 후보에 남긴다
        if (stop != null && u.near.any { it.id == stop.id }) pinned = Pinned(stop, d.elapsed.elapsedMs())
        endUrgent()
    }

    // ------------------------------------------------------------ 환기

    private fun autoFreshAir(out: CoachOutput, el: Long) {
        if (out.cabinAction != CabinAction.FRESH_AIR || !d.settings.autoFreshAir) return
        if (!freshAir.autoAllowed(el, d.hub.recirculation)) return
        startFreshAir(announce = ctx().speak)
    }

    private fun startFreshAir(announce: Boolean) {
        if (!d.cabin.freshAirSupported || freshAirBusy) return
        freshAirBusy = true
        freshAir.attempted(d.elapsed.elapsedMs())
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

    // ------------------------------------------------------------ 음성 명령 (마이크 버튼)

    private fun startListen() {
        if (!d.voice.sttReady) return
        listenJob = effect("listen") { send(Msg.Listened(d.voice.listen())) }
    }

    private fun onListened(text: String?) {
        if (text.isNullOrBlank()) return
        // 떠 있는 카드에 대한 대답이면 Accept/Decline(key)로 나온다 (prompt.md 6.2)
        val intent = parser.parse(text, occupants(), activePromptId = machine.current?.key)
        if (intent != null) {
            dispatchIntent(intent)
            return
        }
        val interpreter = d.interpreter ?: return notUnderstood()
        val people = occupants()
        val key = machine.current?.key
        ui.update { copy(voice = VoiceState.THINKING) }
        interpretJob = effect("interpret") { send(Msg.Interpreted(text, interpreter.interpret(text, people, key))) }
    }

    private fun notUnderstood() = speak("잘 못 알아들었어요. 화장실 급해, 졸려, 얼마나 남았어처럼 말해 주세요")

    /** 말로 한 요청. 질문 대답은 기계로, 급해는 급해 처리로, 나머지는 다음 틱 엔진 입력으로 (엔진이 안내·질문으로 답한다) */
    private fun dispatchIntent(intent: VoiceIntent) {
        when (intent) {
            is VoiceIntent.Accept -> onPromptEvent(PromptEvent.Tapped(intent.promptId, true))
            is VoiceIntent.Decline -> onPromptEvent(PromptEvent.Tapped(intent.promptId, false))
            VoiceIntent.UrgentRestroom -> {
                val u = urgent
                if (u == null) startUrgent(--coreUrgentId) else u.searchAgain()
            }
            else -> {
                if (pendingIntents.size >= MAX_PENDING_INTENTS) pendingIntents.removeFirst()
                pendingIntents.addLast(intent)
            }
        }
    }

    // ------------------------------------------------------------ 탑승자

    private fun occupants(): List<Occupant> = worlds.active.world.occupants(d.settings)

    private fun setOccupants(list: List<Occupant>) {
        val a = worlds.setOccupants(allowedOccupants(list))
        ui.update { copy(occupants = a.world.occupants(d.settings)) }
    }

    /** 건강 관련 유형(임신, 과민성 방광·장)은 따로 동의한 경우에만 둔다 (개인정보 보호법 제23조) */
    private fun allowedOccupants(list: List<Occupant>): List<Occupant> =
        if (d.settings.healthConsent) list else list.filter { it.type !in HEALTH_TYPES }

    private fun describeOccupants() {
        if (!ctx().listen || describeJob?.isActive == true) return
        describeJob = effect("describe") {
            send(Msg.OccupantsHeard(d.voice.ask("누가 함께 타나요? 예를 들어 일곱 살 아이 하나랑 강아지처럼 말해 주세요", listenMs = 8_000)))
        }
    }

    private fun onOccupantsHeard(text: String?) {
        if (text.isNullOrBlank()) return
        val list = OccupantPhrase.parse(text)
        val interpreter = d.interpreter
        if (list.isNotEmpty() || interpreter == null) return applySpokenOccupants(list)
        interpretJob = effect("interpretOccupants") { send(Msg.OccupantsInterpreted(interpreter.occupants(text))) }
    }

    private fun applySpokenOccupants(list: List<Occupant>?) {
        if (list.isNullOrEmpty()) return speak("잘 못 알아들었어요. 화면에서 골라 주세요")
        val allowed = allowedOccupants(list)
        if (allowed.size < list.size) speak("건강 관련 정보는 설정에서 동의한 뒤에 넣을 수 있어요")
        setOccupants(allowed)
        speak("${OccupantPhrase.describe(allowed)}로 맞췄어요")
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
        apply(change, lastSeen)
        log.i("demo started: ${worlds.active.world.name ?: "off"}")
    }

    private fun clearRecords() {
        recorder.clear()
        // 세대를 바로 올려야 이미 꺼낸 저장이 지운 학습을 되살리지 않는다 (AtomicFile). 파일 하나 지우기라 짧다
        runCatching { d.memory.clear() }.onFailure { log.w("memory clear failed: ${it.message}") }
        lastSavedMemory = null
        apply(worlds.clearLearning())
        val now = worlds.active.clock.now()
        val shown = shownHistory(now)
        ui.update { copy(history = shown, lastStop = shown.lastOrNull()) }
    }

    /**
     * 세계·엔진이 바뀌었다 (WorldChange). 틱 사이에 한 번에 바꾼다 (구조 리뷰 D6·D11, world.md 6).
     * [lastSeenMs]: 시연을 끌 때 시연을 켠 실제 시각 (실제 정차를 그때 끝낼지 이어 갈지 감지기가 정한다)
     */
    private fun apply(change: WorldChange, lastSeenMs: Long? = null) {
        change.saveMemory?.let(::saveMemory)
        when (change.kind) {
            WorldChange.Kind.NONE -> return
            WorldChange.Kind.ENGINE -> {
                resetPrompts()
                planKey = null
            }
            WorldChange.Kind.WORLD -> {
                resetPrompts()
                val a = change.active
                val now = a.clock.now()
                recorder.onWorldChanged(now, change.restorePark, lastSeenMs ?: now)
                endUrgent(publish = false)
                pinned = null
                // 지난 세계의 계획은 버린다. 아직 답하지 않은 요청만 새 세계에서 답한다
                planWanted = planRequested
                planKey = null
                lastInput = null
                lastOutput = null
                enterWorld(a)
                d.candidates.reset()
                val shown = shownHistory(now)
                ui.update {
                    copy(
                        demo = a.world.name, occupants = a.world.occupants(d.settings), history = shown, lastStop = shown.lastOrNull(),
                        urgent = null, urgentState = UrgentState.IDLE, plan = null, ahead = emptyList(), promptStop = null,
                    )
                }
            }
        }
    }

    /** 새 세계의 정책을 다른 루프에 알린다 (내비 위치 보충, 후보 입력, 경로 보정 스위치) */
    private fun enterWorld(a: ActiveWorld) {
        d.oracle.enabled = a.world.oracleEnabled
        d.gates.naviFallback = a.world.usesNaviLocationFallback
        val real = d.positions.current.value?.fix
        d.gates.request = CandidateRequest(CoreRules.searchFix(a.world.position(real), real, d.hub.speedKph, d.hub.gear), d.tracker.snapshot.value, a.world.candidatePolicy)
    }

    /** 질문 상태를 비운다. 실행 중인 들르기는 취소하지 않는다 (결과는 보고 없이 알린다, prompt.md 6.6) */
    private fun resetPrompts() {
        pendingIntents.clear()
        expiryJobs.values.forEach { it.cancel() }
        expiryJobs.clear()
        runEffects(machine.on(PromptEvent.Reset, ctx()))
    }

    private fun shownHistory(now: Long) = StopHistory.sanitize(recorder.shown, now)

    // ------------------------------------------------------------ 급해

    private fun startUrgent(requestId: Long) {
        urgent = UrgentSession(requestId, d.elapsed.elapsedMs())
        pinned = null
        log.i("urgent $requestId")
        ui.update { copy(urgentRequestId = requestId, urgentState = UrgentState.LOCATING, urgent = null) }
        val input = lastInput ?: return
        val a = worlds.active
        advanceUrgent(d.elapsed.elapsedMs(), a, a.world.position(d.positions.current.value?.fix), input)
    }

    private fun advanceUrgent(el: Long, a: ActiveWorld, pos: Fix?, input: CoachInput) {
        val u = urgent ?: return
        val before = u.state to u.results
        when (u.next(el, pos != null, d.stops.loaded)) {
            UrgentSession.Step.NONE -> {}
            UrgentSession.Step.NO_LOCATION -> speak("현재 위치를 아직 모르겠어요")
            UrgentSession.Step.SEARCH -> {
                val real = d.positions.current.value?.fix
                val f = CoreRules.searchFix(pos, real, d.hub.speedKph, d.hub.gear)!!
                val near = d.stops.near(f.lat, f.lon, f.heading, UrgentSession.RADIUS_M, UrgentSession.KINDS, now = input.now, limit = UrgentSession.MAX)
                // 화면 목록과 엔진의 급해 질문이 같은 곳을 1등으로 고르도록 같은 후보(경로 후보 + 가까운 화장실)로 순위를 매긴다
                val ranked = try {
                    a.engine.urgent(input.copy(candidates = CoreRules.withExtras(input.candidates, near, pos), intent = null))
                } catch (e: Exception) {
                    log.w("urgent rank failed: ${e.message}")
                    emptyList()
                }
                if (u.found(el, near, ranked)) {
                    // 엔진에 급해를 알린다 (서비스 감사 D7): 필요를 말한 것으로 기록하고(게이지·학습), 30초 조용히 하고,
                    // 1등 장소로 급해 질문을 낸다. 그 질문을 수락하면 엔진이 들르기로 한 곳을 안다
                    pendingIntents.addFirst(VoiceIntent.UrgentRestroom)
                    log.i("urgent ${u.requestId}: ${ranked.size} results")
                }
            }
        }
        if (urgent === u && (u.state to u.results) != before) {
            val list = if (u.state == UrgentState.READY || u.state == UrgentState.NO_RESULT) u.results else null
            ui.update { copy(urgentRequestId = u.requestId, urgentState = u.state, urgent = list) }
        }
    }

    /** 급해 요청을 끝낸다 (닫기, 들르기 성공, 세계 전환). 화면은 같은 번호의 IDLE을 보고 닫는다 */
    private fun endUrgent(publish: Boolean = true) {
        if (urgent == null) return
        urgent = null
        if (publish) ui.update { copy(urgentState = UrgentState.IDLE, urgent = null) }
    }

    /** 엔진 후보에 더할 것: 급해 결과와 급해에서 들르기로 한 곳 */
    private fun extras(): List<StopCandidate> {
        val u = urgent?.near.orEmpty()
        val p = pinned?.stop ?: return u
        return if (u.isEmpty()) listOf(p) else u + p
    }

    /** 엔진이 고정한 곳을 더는 추천하지 않으면(지나쳤거나 도착) 후보에서 뺀다 */
    private fun trackPinned(out: CoachOutput, el: Long) {
        val p = pinned ?: return
        if (out.next?.stop?.id == p.stop.id) p.seenAtMs = el else if (el - p.seenAtMs > PIN_GRACE_MS) pinned = null
    }

    // ------------------------------------------------------------ 계획

    /** 계획 화면이 떠 있는 동안 후보·경로가 바뀌면 다시 만든다 (UI 감사 #4) */
    private fun refreshPlan(force: Boolean) {
        if (!planWanted) return
        val input = lastInput ?: return
        val el = d.elapsed.elapsedMs()
        val key = Triple(d.candidates.snapshot.value, d.tracker.snapshot.value.epoch, worlds.active.generation)
        if (!force && key == planKey && el - planAtMs < PLAN_REFRESH_MS) return
        planKey = key
        planAtMs = el
        val plan: PitStopPlan? = try {
            worlds.active.engine.plan(input.copy(intent = null))
        } catch (e: Exception) {
            log.w("plan failed: ${e.message}")
            return
        }
        if (planRequested) {
            planRequested = false
            planGeneration++
        }
        val gen = planGeneration
        ui.update { copy(plan = plan, planGeneration = gen) }
    }

    // ------------------------------------------------------------ 저장

    /** 학습 상태: 1분에 한 번까지, 바뀌었고 학습을 저장하는 세계일 때만 (시연은 memoryToSave가 null) */
    private fun autosaveMemory(el: Long) {
        val last = lastMemorySaveMs
        if (memorySaving || (last != null && el - last < MEMORY_EVERY_MS)) return
        val m = worlds.memoryToSave() ?: return
        if (m.memory == lastSavedMemory) return
        saveMemory(m)
    }

    private fun saveMemory(m: MemorySave) {
        memorySaving = true
        lastMemorySaveMs = d.elapsed.elapsedMs()
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
        /** 고정한 급해 정차를 엔진이 추천하지 않은 채로 두는 시간 */
        const val PIN_GRACE_MS = 15_000L
        const val PLAN_REFRESH_MS = 30_000L
        /** 들르기 결과를 화면에 두는 시간 */
        const val GO_TO_RESULT_TTL_MS = 10 * 60_000L
        const val MEMORY_EVERY_MS = 60_000L
        val HEALTH_TYPES = setOf(OccupantType.PREGNANT, OccupantType.OAB, OccupantType.IBS)
    }
}
