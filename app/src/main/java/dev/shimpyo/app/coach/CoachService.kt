package dev.shimpyo.app.coach

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dev.hmg.pleoskit.CarState
import dev.hmg.pleoskit.LocationKit
import dev.hmg.pleoskit.NaviKit
import dev.hmg.pleoskit.PleosForegroundService
import dev.hmg.pleoskit.SttKit
import dev.hmg.pleoskit.TtsKit
import dev.hmg.pleoskit.VehicleKit
import dev.shimpyo.app.BuildConfig
import dev.shimpyo.app.ShimpyoApp
import dev.shimpyo.app.devtools.DevToolRunner
import dev.shimpyo.app.voice.PleosLlmInterpreter
import dev.shimpyo.app.voice.VoiceAgent
import dev.shimpyo.coach.CandidateProvider
import dev.shimpyo.coach.CoachClock
import dev.shimpyo.coach.CoachCore
import dev.shimpyo.coach.CoachDeps
import dev.shimpyo.coach.CoachGates
import dev.shimpyo.coach.CoachLog
import dev.shimpyo.coach.ElapsedClock
import dev.shimpyo.coach.EngineFactory
import dev.shimpyo.coach.NaviTracker
import dev.shimpyo.coach.Odometer
import dev.shimpyo.coach.PositionTracker
import dev.shimpyo.coach.Retry
import dev.shimpyo.coach.RouteOracle
import dev.shimpyo.coach.SignalHub
import dev.shimpyo.coach.UiCommand
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.Gear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 쉼표의 포그라운드 서비스. 조립만 한다 (docs/design/coach-layer/core.md 5장): kit와 저장소로 포트를 만들고, 코치 디스패처 하나에서
 * 코어(액터)·내비 추적·후보 루프를 띄우고, 신호 수집과 화면 명령을 코어로 잇는다. 판단은 엔진(rest-engine)과 코치 계층이 한다.
 * 포그라운드 서비스라야 내비가 앞에 있어도 얼려지지 않고 이벤트를 받는다 (CLAUDE.md).
 */
class CoachService : PleosForegroundService() {

    override val notificationTitle = "쉼표"

    private val app get() = application as ShimpyoApp
    private val kits by lazy { Kits(this) }
    private var interpreter: PleosLlmInterpreter? = null

    /** 코어·내비 추적·후보 루프가 함께 쓰는 한 스레드. 그래서 코치 상태에 잠금이 없다 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val coachThread = Dispatchers.Default.limitedParallelism(1)
    private var coachScope: CoroutineScope? = null
    private var core: CoachCore? = null
    private var signals: CarSignals? = null
    private var signalsJob: Job? = null
    private var devJob: Job? = null

    override fun onServiceStarted() {
        // 메인 스레드에서 쓴 시간을 남긴다 (서비스 감사 SV-N4: SDK 객체를 여기서 만들면 메인 스레드가 막힌다)
        val startedAt = SystemClock.elapsedRealtime()
        // 같은 프로세스에서 다시 시작했으면 지난 서비스의 질문·급해 목록이 화면에 남아 있다 (다시 올리지 않는다)
        CoachStore.resetTransient()
        val scope = CoroutineScope(serviceScope.coroutineContext + coachThread)
        coachScope = scope
        val wall = CoachClock { System.currentTimeMillis() }
        val elapsed = ElapsedClock { SystemClock.elapsedRealtime() }
        // 허브는 코어와 같은 경과 시계로 표본을 받은 때를 적고, 틱마다 세계 시각으로 바꿔 준다 (리뷰 R-F6)
        val hub = SignalHub(elapsed)
        val odometer = Odometer(elapsed)
        val positions = PositionTracker(elapsed) { PositionTracker.isMoving(hub.speedKph, hub.gear == Gear.PARK) }
        val gates = CoachGates()
        val navi = KitNaviPort(kits.navi)
        val tracker = NaviTracker(navi, odometer, { hub.speedKph != null }, elapsed, AppLog, positions.naviFallback { gates.naviFallback })
        val oracle = RouteOracle(navi, elapsed, AppLog)
        val candidates = CandidateProvider(app.stops, oracle, odometer, elapsed, AppLog)
        val cabin = KitCabin(kits.vehicle)
        val voice = VoiceAgent(kits.tts, kits.stt, CoachStore.sink)
        val llm = PleosLlmInterpreter(this).also { interpreter = it }
        val c = CoachCore(
            CoachDeps(
                wall = wall, elapsed = elapsed, hub = hub, odometer = odometer, positions = positions, navi = navi, tracker = tracker,
                candidates = candidates, oracle = oracle, stops = app.stops, voice = voice, interpreter = llm, cabin = cabin,
                history = app.stopHistory, memory = app.memory, settings = app.settings, trips = app.trips, engines = ENGINES,
                ui = CoachStore.sink, gates = gates, io = Dispatchers.IO, log = AppLog,
            ),
        )
        core = c
        signals = CarSignals(kits.car, kits.vehicle, kits.location, cabin, hub, positions, elapsed, AppLog) { r -> CoachStore.update { copy(drivingRestricted = r) } }

        scope.launch { c.run() }
        scope.launch { tracker.run() }
        scope.launch { candidates.run { gates.request } }
        // 정차 후보 표(stops.bin)는 1초 안팎에 읽힌다 (StopAssets). 그 전에는 후보 루프가 LOADING_DATA를 낸다
        scope.launch(Dispatchers.IO) {
            val n = app.stops.ensureLoaded()
            CoachStore.update { copy(status = "정차 후보 데이터 ${n}곳") }
        }
        scope.launch { Retry.untilSuccess("voice", AppLog) { voice.connect() } }
        startSignals()
        // 화면 명령 (CoachStore의 Channel: 서비스가 받기 전에 온 명령도 남아 있다)
        scope.launch {
            CoachStore.commands.collect { cmd ->
                if (cmd is UiCommand.PermissionsChanged) startSignals()
                c.command(cmd)
            }
        }
        Log.i(TAG, "onServiceStarted: ${SystemClock.elapsedRealtime() - startedAt} ms on the main thread")
    }

    /** 신호 수집을 (다시) 띄운다. 권한을 새로 받으면 Car 연결부터 다시 해야 차속 구독이 걸린다 */
    private fun startSignals() {
        val scope = coachScope ?: return
        val s = signals ?: return
        val old = signalsJob
        signalsJob = scope.launch {
            old?.let {
                it.cancel()
                it.join()
                kits.car.release()
            }
            s.run()
        }
    }

    /**
     * 인텐트 extras로 온 요청. 무엇을 받아들일지는 [DevHooks]가 정한다 (닫힘 검증 N1: 제출용 빌드도 점검을 돌렸다).
     * - `--es demo <이름|off>`(시연 전환): 어느 빌드에서나. 화면 시연 패널과 같은 명령 하나만 보낸다.
     * - `--ez verify true`(설계 확인), `--ez selftest true [--ez route true]`(kit 점검): debug 빌드에서만.
     *   차를 움직이는 점검 코드는 release APK에 없다 (app/src/debug의 devtools).
     */
    override fun onCommand(intent: Intent?) {
        if (intent == null) return
        val request = DevHooks.request(
            verify = intent.getBooleanExtra(DevHooks.EXTRA_VERIFY, false),
            selftest = intent.getBooleanExtra(DevHooks.EXTRA_SELFTEST, false),
            route = intent.getBooleanExtra(DevHooks.EXTRA_ROUTE, false),
            demo = intent.getStringExtra(DevHooks.EXTRA_DEMO),
            debugBuild = BuildConfig.DEBUG,
        )
        if (request.refused) Log.w(TAG, "dev tool request ignored: not a debug build")
        request.tool?.let { tool ->
            if (devJob?.isActive == true) return@let
            val devKits = DevToolRunner.Kits({ kits.vehicle }, { kits.navi }, { kits.tts }, { kits.stt }, { kits.location })
            devJob = serviceScope.launch { DevToolRunner.run(tool, request.withRoute, this@CoachService, devKits) }
        }
        request.demo?.let {
            Log.i(TAG, "demo requested: ${it.scenario ?: "off"}")
            CoachStore.send(UiCommand.Demo(it.scenario))
        }
    }

    /** 끝날 때: 루프를 먼저 멈추고(D11), 코어의 마지막 저장을 코치 스레드에서 하고, 만든 kit만 놓고, 화면의 진행 중 상태를 지운다 */
    override fun onDestroy() {
        coachScope?.cancel()
        core?.let { c -> runBlocking { withTimeoutOrNull(SHUTDOWN_MS) { withContext(coachThread) { c.shutdown() } } } }
        kits.releaseMade()
        interpreter?.release()
        CoachStore.resetTransient()
        super.onDestroy()
    }

    /**
     * kit는 처음 쓸 때 만든다. 끝날 때는 만든 것만 놓는다 (예전에는 놓으려고 SDK 객체를 새로 만들었다, D11).
     * kit 객체를 만드는 것은 가볍다: SDK 객체(NaviHelper, Vehicle, TTS, STT, FusedLocation)는 kit이 connect 할 때 SDK 전용
     * 스레드에서 만든다. 그래서 onServiceStarted(메인 스레드)에서 조립해도 SDK를 메인 스레드에서 건드리지 않는다 (서비스 감사 SV-N4)
     */
    private class Kits(context: Context) {
        private val made = ArrayList<() -> Unit>()
        private fun <T : Any> kit(make: () -> T, release: (T) -> Unit) = lazy {
            make().also { k -> synchronized(made) { made += { release(k) } } }
        }

        val car by kit({ CarState(context) }, CarState::release)
        val vehicle by kit({ VehicleKit(context) }, VehicleKit::release)
        val navi by kit({ NaviKit(context) }, NaviKit::release)
        val location by kit({ LocationKit(context) }, LocationKit::release)
        val tts by kit({ TtsKit(context) }, TtsKit::release)
        val stt by kit({ SttKit(context) }, SttKit::release)

        fun releaseMade() = synchronized(made) {
            made.asReversed().forEach { runCatching { it() } }
            made.clear()
        }
    }

    private object AppLog : CoachLog {
        override fun i(message: String) {
            Log.i(TAG, message)
        }

        override fun w(message: String) {
            Log.w(TAG, message)
        }
    }

    private companion object {
        const val TAG = "Shimpyo"
        const val SHUTDOWN_MS = 2_000L
        val ENGINES = EngineFactory { profile, memory, config -> CoachEngine(config = config, profile = profile, memory = memory) }
    }
}
