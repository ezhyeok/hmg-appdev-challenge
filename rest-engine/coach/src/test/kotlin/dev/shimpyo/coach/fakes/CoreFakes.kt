package dev.shimpyo.coach.fakes

import dev.shimpyo.coach.CabinPort
import dev.shimpyo.coach.CandidateProvider
import dev.shimpyo.coach.CoachClock
import dev.shimpyo.coach.CoachCore
import dev.shimpyo.coach.CoachDeps
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.ElapsedClock
import dev.shimpyo.coach.EngineAccepted
import dev.shimpyo.coach.Fix
import dev.shimpyo.coach.IntentInterpreter
import dev.shimpyo.coach.LoopInputs
import dev.shimpyo.coach.MemoryPort
import dev.shimpyo.coach.NaviPort
import dev.shimpyo.coach.NaviTracker
import dev.shimpyo.coach.Odometer
import dev.shimpyo.coach.PositionTracker
import dev.shimpyo.coach.RouteOracle
import dev.shimpyo.coach.SignalHub
import dev.shimpyo.coach.StopIndex
import dev.shimpyo.coach.StopLog
import dev.shimpyo.coach.TripHeartbeat
import dev.shimpyo.coach.UiCommand
import dev.shimpyo.coach.UiSink
import dev.shimpyo.coach.VoicePort
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.VoiceIntent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/*
 * CoachCore 테스트의 가짜와 조립 (core 담당). 서비스(CoachService)가 하는 조립을 그대로 따라 한다: 같은 부품, 같은 루프,
 * 가상 시간 하나. 시계는 TestCoroutineScheduler에 묶어서 delay와 시각이 어긋나지 않는다.
 */

/** 실제 시각(벽시계)과 경과 시각을 가상 시간에 묶는다. 2026-11-12 10:00 KST에서 시작 */
class RigClock(private val scheduler: TestCoroutineScheduler, var wallBase: Long = 1_762_909_200_000L) : CoachClock, ElapsedClock {
    override fun now(): Long = wallBase + scheduler.currentTime
    override fun elapsedMs(): Long = ELAPSED_BASE + scheduler.currentTime

    companion object {
        const val ELAPSED_BASE = 5_000_000L
    }
}

/**
 * 음성. 물으면 [answer]의 답을, 마이크 버튼은 [listens] 차례로 답한다. 말하기는 [speakMs]만큼 걸린다.
 * [hang]이면 말하기·듣기가 끝나지 않는다 (TTS 서비스가 멈춤).
 */
class FakeVoice(
    var answer: (String) -> String? = { null },
    var speakMs: Long = 1_500,
) : VoicePort {
    override var ttsReady = true
    override var sttReady = true
    var hang = false
    val said = mutableListOf<String>()
    val asked = mutableListOf<String>()
    val listens = ArrayDeque<String?>()
    var stops = 0

    override suspend fun connect() = Unit

    override suspend fun say(text: String): Boolean {
        said += text
        if (hang) awaitCancellation()
        delay(speakMs)
        return true
    }

    override suspend fun ask(text: String, listenMs: Long): String? {
        asked += text
        if (hang) awaitCancellation()
        delay(speakMs)
        return answer(text)
    }

    override suspend fun listen(listenMs: Long): String? {
        if (hang) awaitCancellation()
        delay(1_000)
        return listens.removeFirstOrNull()
    }

    override fun stop() {
        stops++
    }
}

/** 공조. 바꾸면 차 신호(hub.recirculation)도 바뀐다 */
class FakeCabin(private val hub: SignalHub, override var freshAirSupported: Boolean = true) : CabinPort {
    val freshAirAt = mutableListOf<Long>()
    val recirculateAt = mutableListOf<Long>()
    var clock: () -> Long = { 0L }

    override suspend fun freshAir(): Boolean {
        freshAirAt += clock()
        hub.recirculation = false
        return true
    }

    override suspend fun recirculate(): Boolean {
        recirculateAt += clock()
        hub.recirculation = true
        return true
    }
}

/** 저장한 실제 시각을 남기는 학습 저장소 */
class TimedMemory(private val inner: InMemoryMemory, private val clock: () -> Long) : MemoryPort by inner {
    val saveTimes = mutableListOf<Long>()
    override fun save(memory: PersonalMemory, generationAtSnapshot: Long): Boolean =
        inner.save(memory, generationAtSnapshot).also { if (it) saveTimes += clock() }
}

/** 화면 상태 (CoachStore 대신) */
class RecordingUi : UiSink {
    @Volatile var state = CoachUiState()
        private set
    var updates = 0
        private set

    override fun update(transform: CoachUiState.() -> CoachUiState) {
        synchronized(this) {
            state = state.transform()
            updates++
        }
    }
}

/** 내비 명령의 좌표를 남기는 감싸개 */
class CommandNavi(private val inner: FakeNaviPort) : NaviPort by inner {
    data class Command(val name: String, val stopName: String, val lat: Double, val lon: Double)

    val commands = mutableListOf<Command>()

    override suspend fun addWaypoint(lat: Double, lon: Double, name: String): Boolean {
        commands += Command("addWaypoint", name, lat, lon)
        return inner.addWaypoint(lat, lon, name)
    }

    override suspend fun startRoute(lat: Double, lon: Double, name: String): Boolean {
        commands += Command("startRoute", name, lat, lon)
        return inner.startRoute(lat, lon, name)
    }
}

/** 해석기 (Pleos LLM 대신). 정해 둔 답만 준다 */
class FakeInterpreter(var intent: VoiceIntent? = null, var people: List<Occupant>? = null) : IntentInterpreter {
    override val label: String = "가짜 LLM"
    var calls = 0
    override suspend fun interpret(text: String, occupants: List<Occupant>, activePromptId: String?): VoiceIntent? {
        calls++
        return intent
    }

    override suspend fun occupants(text: String): List<Occupant>? = people
}

/** 엔진에 알린 '들르기로 한 곳'을 적어 두고 엔진에 그대로 넘긴다 (앱과 같은 길: EngineAccepted.ENGINE) */
class RecordingAccepted : EngineAccepted {
    data class Note(val stopId: String, val purposes: Set<Purpose>, val now: Long, val engine: CoachEngine)

    val notes = mutableListOf<Note>()

    override fun note(engine: CoachEngine, stop: StopCandidate, purposes: Set<Purpose>, now: Long) {
        notes += Note(stop.id, purposes, now, engine)
        EngineAccepted.ENGINE.note(engine, stop, purposes, now)
    }
}

/** 경부선 남행 부산방향 시설 (앱 데이터와 같은 좌표, DemoRun의 시연 경로가 이 점들을 잇는다) */
object Gyeongbu {
    private fun s(id: String, name: String, lat: Double, lon: Double, kind: StopKind = StopKind.HIGHWAY_REST_AREA) =
        PromptFakes.stop(id, name, lat, lon, kind)

    val manghyang = s("manghyang", "망향휴게소(부산방향)", 36.855650, 127.180929)
    val oksan = s("oksan", "옥산휴게소(부산방향)", 36.657743, 127.369817)
    val jukam = s("jukam", "죽암휴게소(부산방향)", 36.486810, 127.429261)
    val sintanjin = s("sintanjin", "신탄진졸음쉼터", 36.425354, 127.416697, StopKind.DROWSY_SHELTER)
    val ipjang = s("ipjang", "입장졸음쉼터", 36.914432, 127.188465, StopKind.DROWSY_SHELTER)
    val cheonan = s("cheonan", "천안호두휴게소(부산방향)", 36.730226, 127.263879)
    /** 강남역 근처 공중화장실 (급해 시연) */
    val gangnamToilet = s("gangnam-wc", "강남역 공중화장실", 37.4985, 127.0290, StopKind.PUBLIC_RESTROOM)
    val gangnamGas = s("gangnam-gas", "역삼 주유소", 37.5000, 127.0350, StopKind.GAS_STATION)

    val all = listOf(manghyang, oksan, jukam, sintanjin, ipjang, cheonan, gangnamToilet, gangnamGas)
}

/**
 * 서비스처럼 조립한 코어 한 벌. [start]가 코어·내비 추적·후보 루프를 테스트의 backgroundScope에 띄운다.
 * 시간은 [run]·[runUntil]로 흘린다 (1초씩).
 */
class CoreRig(
    val test: TestScope,
    val voice: FakeVoice = FakeVoice(),
    val settings: FakeSettings = FakeSettings(listOf(DRIVER, KID)),
    val history: PromptFakes.History = PromptFakes.History(),
    val memory: InMemoryMemory = InMemoryMemory(),
    val trips: InMemoryTripStore = InMemoryTripStore(),
    stopRows: List<StopCandidate> = Gyeongbu.all,
    val interpreter: IntentInterpreter? = null,
    wallBase: Long = 1_762_909_200_000L,
) {
    val accepted = RecordingAccepted()
    val clock = RigClock(test.testScheduler, wallBase)
    val log = RecordingLog()
    val hub = SignalHub()
    val odometer = Odometer(clock)
    val positions = PositionTracker(clock) { PositionTracker.isMoving(hub.speedKph, hub.gear == Gear.PARK) }
    val loopInputs = LoopInputs()
    val fakeNavi = FakeNaviPort(clock)
    val navi = CommandNavi(fakeNavi)
    val tracker = NaviTracker(navi, odometer, { hub.speedKph != null }, clock, log, positions.naviFallback { loopInputs.naviFallback })
    val oracle = RouteOracle(navi, clock, log)
    val stops: StopIndex = PromptFakes.Stops(stopRows)
    val candidates = CandidateProvider(stops, oracle, odometer, clock, log)
    val cabin = FakeCabin(hub).also { c -> c.clock = { clock.elapsedMs() } }
    val ui = RecordingUi()
    val timedMemory = TimedMemory(memory) { clock.elapsedMs() }
    val core = CoachCore(
        CoachDeps(
            wall = clock, elapsed = clock, hub = hub, odometer = odometer, positions = positions, navi = navi, tracker = tracker,
            candidates = candidates, oracle = oracle, stops = stops, voice = voice, interpreter = interpreter, cabin = cabin,
            history = history, memory = timedMemory, settings = settings, trips = trips, engines = seededEngines(), ui = ui, loopInputs = loopInputs,
            io = StandardTestDispatcher(test.testScheduler), log = log, accepted = accepted,
        ),
    )

    val state: CoachUiState get() = ui.state

    fun start() {
        test.backgroundScope.launch { core.run() }
        test.backgroundScope.launch { tracker.run() }
        test.backgroundScope.launch { candidates.run { loopInputs.request } }
        test.runCurrent()
    }

    fun drive(kph: Float, gear: Gear = Gear.DRIVE) {
        hub.gear = gear
        hub.speedKph = kph
    }

    fun park() = drive(0f, Gear.PARK)

    fun command(c: UiCommand) {
        core.command(c)
        test.runCurrent()
    }

    /** [ms]만큼 1초씩 흘린다 */
    fun run(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(1_000L, left)
            test.advanceTimeBy(step)
            test.runCurrent()
            left -= step
        }
    }

    /** 조건이 맞을 때까지 1초씩 (최대 [maxMs]). 맞았으면 그때까지 흐른 시간, 아니면 null */
    fun runUntil(maxMs: Long, condition: () -> Boolean): Long? {
        var t = 0L
        while (t <= maxMs) {
            if (condition()) return t
            run(1_000)
            t += 1_000
        }
        return null
    }

    /** 맥박 저장소를 미리 채운다 (재시작 테스트) */
    fun savedTrip(h: TripHeartbeat) {
        trips.stored = h
    }

    fun memoryOf(): PersonalMemory = memory.stored
    fun appended(): List<StopLog> = history.appended
    /**
     * 차의 위치. 위치 SDK와 내비가 같은 곳을 본다: 테스트는 위치를 한 번만 넣고 달리므로(차속만 있다) 15초 뒤 코치가 내비 위치로
     * 보충하는데(PositionTracker), 가짜 내비의 기본 위치(판교)가 다르면 차가 12km 옮겨 간 것처럼 된다
     */
    fun fix(lat: Double, lon: Double, heading: Float? = 180f) {
        fakeNavi.location = { Fix(lat, lon, heading) }
        positions.onFused(Fix(lat, lon, heading))
    }
}
