package dev.shimpyo.app.coach

import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dev.hmg.pleoskit.CarState
import dev.hmg.pleoskit.LocationKit
import dev.hmg.pleoskit.NaviKit
import dev.hmg.pleoskit.PleosForegroundService
import dev.hmg.pleoskit.ReadSignal
import dev.hmg.pleoskit.SttKit
import dev.hmg.pleoskit.TtsKit
import dev.hmg.pleoskit.VehicleKit
import dev.shimpyo.app.ShimpyoApp
import dev.shimpyo.app.data.RouteOracle
import dev.shimpyo.app.devtools.KitSelfTest
import dev.shimpyo.app.devtools.SdkVerify
import dev.shimpyo.app.voice.Hangul
import dev.shimpyo.app.voice.IntentParser
import dev.shimpyo.app.voice.PleosLlmInterpreter
import dev.shimpyo.app.voice.VoiceAgent
import dev.shimpyo.app.voice.YesNo
import dev.shimpyo.engine.CabinAction
import dev.shimpyo.engine.CabinState
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.CoachOutput
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.DrivingSample
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.TurnSignal
import dev.shimpyo.engine.VoiceIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 쉼표의 본체. 주행 중 계속 살아 있는 포그라운드 서비스.
 *
 * 흐름: 차량·내비·위치 신호 수집 → 1초마다 [CoachEngine.update] → 화면 상태 갱신([CoachStore])
 * → 엔진이 질문을 내면 음성으로 묻고 → 수락하면 내비 경유지 추가 또는 공조 변경.
 * 판단은 모두 엔진(rest-engine)이 하고, 이 클래스는 입출력만 맡는다.
 */
class CoachService : PleosForegroundService() {

    override val notificationTitle = "쉼표"

    private val app get() = application as ShimpyoApp
    private val car by lazy { CarState(this) }
    private val vehicle by lazy { VehicleKit(this) }
    private val navi by lazy { NaviKit(this) }
    private val location by lazy { LocationKit(this) }
    private val tts by lazy { TtsKit(this) }
    private val stt by lazy { SttKit(this) }
    private val voice by lazy { VoiceAgent(tts, stt) }
    private val parser = IntentParser()
    /** 규칙으로 못 알아들은 말만 맡는다. 응답이 없으면 자동으로 꺼진다 */
    private val interpreter by lazy { PleosLlmInterpreter(this) }
    private val oracle by lazy { RouteOracle(navi) }
    private val director = DemoDirector()

    private val hub = SignalHub()
    private val engineLock = Mutex()
    private lateinit var engine: CoachEngine
    private val intents = Channel<VoiceIntent>(Channel.UNLIMITED)

    // 내비에서 받은 값과 그때의 주행 거리는 한 묶음으로 바꾼다 (읽는 쪽이 새 목록과 옛 기준을 섞어 읽지 않게)
    private data class RouteSnap(val route: RouteState?, val odometerM: Double)
    /** TBT에서 읽은 경로 위 휴게소·졸음쉼터와 다음 회전 거리 */
    private data class TbtSnap(val opportunities: List<RouteOpportunity>, val nextManeuverM: Double?, val odometerM: Double)
    /** untilM: 후보를 이 경로 거리까지만 넣었다(목록을 잘랐을 때, RouteState.candidatesUntilM). null이면 다 넣었다 */
    private data class CandidateSnap(val list: List<StopCandidate>, val odometerM: Double, val untilM: Double? = null)

    @Volatile private var routeSnap = RouteSnap(null, 0.0)
    private val route: RouteState? get() = routeSnap.route
    @Volatile private var destinationName: String? = null
    @Volatile private var tbtSnap = TbtSnap(emptyList(), null, 0.0)
    @Volatile private var candidateSnap = CandidateSnap(emptyList(), 0.0)
    private val candidates: List<StopCandidate> get() = candidateSnap.list
    private val candidatesLock = Mutex()

    // 차속을 적분한 주행 거리(m, 실제 시각). 내비를 덜 자주 조회해도 그 사이 경로 거리를 매초 줄여 쓴다 (NaviPoll).
    // 내비가 그만큼 나아갔다고 확인할 때만 쓴다(odometerTrusted). 확인 전·경로 없음·어긋남이면 예전처럼 거리를 그대로 둔다
    @Volatile private var odometerM = 0.0
    private var odometerAtMs = 0L
    @Volatile private var odometerTrusted = false
    private var prevRemainM: Double? = null
    private var prevRemainOdometerM = 0.0
    private var lastNaviMode: NaviMode? = null
    /** 길안내 시작·경유지 추가·재탐색 시각. 그 뒤 30초는 TBT를 5초마다 받는다 */
    @Volatile private var naviResetAt = 0L
    /** 다른 스레드(수락 처리)가 다음 조회를 앞당길 때 쓴다. 조회 시각 변수는 내비 루프만 바꾼다 */
    @Volatile private var forceRoute = false
    @Volatile private var forceTbt = false
    private var lastTbtAt = 0L
    private var lastRouteAt = 0L
    @Volatile private var activePrompt: Prompt? = null
    private var lastPromptId: String? = null
    private var hvacArea: SeatArea? = null
    private var lastFreshAirAt = 0L
    private var parkedSince: Long? = null
    private var doorWhileParked = false
    private var lastMemorySave = 0L
    @Volatile private var lastSavedMemory: dev.shimpyo.engine.PersonalMemory? = null
    private var devJob: Job? = null
    /** 이번 여정의 시작 시각과 출발 전 마지막 화장실. 탑승자·설정을 바꿔도 여정은 이어지게 같은 값을 쓴다 */
    private var tripStart = 0L
    private var lastRestroomAt = 0L

    private fun now(): Long = director.now()

    override fun onServiceStarted() {
        // 기록·학습·설정 파일 읽기는 메인 스레드 밖(IO)에서 한다. 엔진을 만든 뒤에 루프와 명령 처리를 시작한다
        // (그 전에 온 명령은 CoachStore의 Channel에 남는다)
        serviceScope.launch {
            val start = now()
            val (history, memory, occupants) = withContext(Dispatchers.IO) {
                Triple(app.stopHistory.load(), app.memory.load(), app.settings.occupants)
            }
            // 마지막 화장실을 모르면 출발 60분 전으로 본다 (docs/research/ai-science.md 9-1)
            val lastRestroom = history.lastOrNull { it.restroom && start - it.end < 6 * 3_600_000L }?.end ?: (start - 60 * 60_000L)
            tripStart = start
            lastRestroomAt = lastRestroom
            engineLock.withLock { engine = CoachEngine(profile = profileFor(occupants), memory = memory) }
            lastSavedMemory = memory
            CoachStore.update { copy(serviceRunning = true, occupants = occupants, history = history, lastStop = history.lastOrNull()) }

            launch { guard("car") { collectCar() } }
            launch { guard("vehicle") { collectVehicle() } }
            launch { guard("navi") { collectNavi() } }
            launch { guard("location") { collectLocation() } }
            launch { guard("voice") { voice.connect() } }
            launch { guard("candidates") { candidateLoop() } }
            launch { guard("coach") { coachLoop() } }
            CoachStore.commands.collect { handle(it) }
        }
    }

    /** 개발용: `--ez verify true`(설계 확인), `--ez selftest true [--ez route true]`(kit 점검) */
    override fun onCommand(intent: Intent?) {
        if (intent == null || devJob?.isActive == true) return
        if (intent.getBooleanExtra("verify", false)) {
            devJob = serviceScope.launch { SdkVerify(this@CoachService, vehicle, navi, location).run() }
        } else if (intent.getBooleanExtra("selftest", false)) {
            devJob = serviceScope.launch { KitSelfTest(vehicle, navi, tts, stt, location).run(intent.getBooleanExtra("route", false)) }
        }
        intent.getStringExtra("demo")?.let {
            Log.i(TAG, "demo requested: $it")
            CoachStore.send(UiCommand.Demo(it))
        }
    }

    // ------------------------------------------------------------ 코치 루프

    private suspend fun coachLoop() {
        while (true) {
            val t = now()
            director.position()?.let { (la, lo, h) ->
                hub.lat = la
                hub.lon = lo
                hub.heading = h
                hub.locationAt = t
            }
            val real = SystemClock.elapsedRealtime()
            if (odometerAtMs > 0) odometerM += NaviPoll.distanceM(hub.speedKph, real - odometerAtMs)
            odometerAtMs = real
            val (samples, door) = hub.drain(t)
            trackStops(t, door)
            val intent = intents.tryReceive().getOrNull()
            val input = director.apply(
                CoachInput(
                    now = t,
                    gear = hub.gear,
                    speedKph = hub.speedKph,
                    samples = samples,
                    doorOpened = door,
                    cabin = CabinState(hvacOn = hub.hvacOn, recirculation = hub.recirculation, setTempC = hub.setTempC),
                    route = liveRouteWithHorizon(),
                    candidates = director.candidates(liveCandidates()),
                    intent = intent,
                    cabinAutoAllowed = app.settings.autoFreshAir,
                ),
            )
            val out = engineLock.withLock {
                try {
                    engine.update(input)
                } catch (e: Exception) {
                    Log.w(TAG, "engine.update failed: ${e.message}")
                    null
                }
            }
            if (out != null) {
                publish(out, input)
                react(out)
            }
            if (!director.active && t - lastMemorySave > 60_000) {
                lastMemorySave = t
                // 1분마다 확인하되 학습 상태가 바뀌었을 때만, 엔진 잠금 밖의 IO 스레드에서 쓴다 (쓰기는 MemoryStore가 하나씩 한다)
                val (mem, gen) = engineLock.withLock { engine.memory() to app.memory.generation }
                if (mem != lastSavedMemory) saveMemory(mem, gen)
            }
            delay(1_000)
        }
    }

    private fun publish(out: CoachOutput, input: CoachInput) {
        val r = input.route
        CoachStore.update {
            copy(
                restroom = out.restroom,
                fatigue = out.fatigue,
                // style(운전 스타일 편차)은 화면이 쓰지 않고 매초 바뀌어 화면 전체를 다시 그리게 해서 올리지 않는다
                next = out.next,
                parked = out.parked,
                // 차속은 시연 패널이 정수로만 보여 준다. 소수점 흔들림으로 매초 상태가 바뀌지 않게 정수로 올린다
                speedKph = hub.speedKph?.let { kotlin.math.truncate(it) },
                guiding = r?.guiding == true,
                destinationName = destinationName,
                remainDistanceM = r?.remainDistanceM,
                remainTimeS = r?.remainTimeS,
                congestion = r?.congestion.orEmpty(),
                promptStop = activePrompt?.stop?.let { s -> input.candidates.firstOrNull { it.id == s.id } },
                demo = director.name,
            )
        }
    }

    private fun react(out: CoachOutput) {
        activePrompt?.let { a ->
            if (PromptRules.withdrawWhenParked(a.kind, out.parked)) {
                activePrompt = null
                CoachStore.update { copy(prompt = null) }
            }
        }
        val p = out.prompt
        if (p != null && p.id != lastPromptId && activePrompt == null) {
            lastPromptId = p.id
            ask(p)
        }
        if (out.cabinAction == CabinAction.FRESH_AIR && app.settings.autoFreshAir && hub.recirculation != false && now() - lastFreshAirAt > FRESH_AIR_GAP_MS) {
            serviceScope.launch { applyFreshAir(announce = true) }
        }
    }

    // ------------------------------------------------------------ 질문과 대답

    private fun ask(p: Prompt) {
        activePrompt = p
        CoachStore.update { copy(prompt = p) }
        serviceScope.launch {
            val heard = if (app.settings.voiceEnabled) voice.ask(p.speech) else null
            when (parser.yesNo(heard)) {
                YesNo.YES -> answer(p.id, true)
                YesNo.NO -> answer(p.id, false)
                YesNo.UNKNOWN -> {
                    // 대답 대신 다른 요청을 했을 수 있다 ("아니 화장실 급해"는 위에서 NO로 걸린다)
                    parser.parse(heard, app.settings.occupants, null)?.let { onIntent(it) }
                    // 화면 버튼을 기다리다 만료되면 지운다
                    delay((p.expiresAt - now()).coerceIn(5_000L, 120_000L))
                    if (activePrompt?.id == p.id) {
                        activePrompt = null
                        CoachStore.update { copy(prompt = null) }
                    }
                }
            }
        }
    }

    private suspend fun answer(promptId: String, accept: Boolean) {
        val p = activePrompt?.takeIf { it.id == promptId } ?: return
        activePrompt = null
        CoachStore.update { copy(prompt = null) }
        engineLock.withLock { engine.onPromptResult(promptId, accept, now()) }
        if (!accept) return
        when (p.kind) {
            PromptKind.FRESH_AIR -> applyFreshAir(announce = true)
            else -> p.stop?.let { goTo(it) }
        }
    }

    private suspend fun onIntent(intent: VoiceIntent) {
        when (intent) {
            is VoiceIntent.Accept -> answer(intent.promptId, true)
            is VoiceIntent.Decline -> answer(intent.promptId, false)
            VoiceIntent.UrgentRestroom -> urgent()
            VoiceIntent.HowLong -> sayGauges()
            else -> intents.trySend(intent)
        }
    }

    private suspend fun listenAndHandle() {
        val heard = voice.listen() ?: return
        val occupants = CoachStore.state.value.occupants
        val intent = parser.parse(heard, occupants, activePrompt?.id)
            ?: interpreter.interpret(heard, occupants, activePrompt?.id).also { CoachStore.update { copy(llm = interpreter.label) } }
        if (intent == null) {
            voice.say("잘 못 알아들었어요. 화장실 급해, 졸려, 얼마나 남았어처럼 말해 주세요")
            return
        }
        onIntent(intent)
    }

    private suspend fun sayGauges() {
        val s = CoachStore.state.value
        val parts = buildList {
            s.restroom?.minutes?.let { add("화장실까지 약 ${it}분") }
            s.fatigue?.minutes?.let { add("쉬어야 할 때까지 약 ${it}분") }
        }
        val next = s.next?.stop?.let { stop ->
            val km = (stop.routeDistanceM ?: stop.straightDistanceM)?.let { (it / 1000).roundToInt() }
            " 다음 추천은 ${stop.name}${km?.let { ", ${it}km 앞" } ?: ""}이에요."
        } ?: ""
        voice.say(if (parts.isEmpty()) "아직 계산할 정보가 부족해요." else parts.joinToString(", ") + " 남았어요." + next)
    }

    // ------------------------------------------------------------ 급해 모드

    private suspend fun urgent() {
        val lat = hub.lat
        val lon = hub.lon
        if (lat == null || lon == null) {
            voice.say("현재 위치를 아직 모르겠어요")
            return
        }
        val t = now()
        val local = app.stops.near(lat, lon, hub.heading, URGENT_RADIUS_M, URGENT_KINDS, now = t, limit = URGENT_MAX)
        val input = director.apply(CoachInput(now = t, gear = hub.gear, speedKph = hub.speedKph, route = liveRoute(), candidates = local, intent = VoiceIntent.UrgentRestroom))
        val ranked = engineLock.withLock { engine.urgent(input) }
        CoachStore.update { copy(urgent = ranked) }
        val top = ranked.firstOrNull()
        if (top == null) {
            voice.say("근처에서 차를 세울 수 있는 화장실을 찾지 못했어요")
            return
        }
        val minutes = (top.totalTimeS / 60).roundToInt().coerceAtLeast(1)
        ask(Prompt(id = "urgent-$t", kind = PromptKind.URGENT_RESULT, speech = "가장 빠른 곳은 ${top.stop.name}, 약 ${minutes}분이에요. 안내할까요?", stop = top.stop, expiresAt = t + 90_000))
    }

    // ------------------------------------------------------------ 행동

    private suspend fun goTo(stop: StopCandidate) {
        if (stop.id.startsWith("tbt:")) {
            // 좌표를 모르는 곳: 내비 장소 검색을 열어 둔다
            runCatching { navi.showPlaceSearch(stop.name) }
            voice.say("내비에서 ${Hangul.eulReul(stop.name)} 찾아 두었어요. 정차 중에 선택해 주세요")
            return
        }
        val guiding = route?.guiding == true
        val ok = try {
            if (guiding) {
                navi.addWaypoint(RequestWaypointInfo(latitude = stop.lat, longitude = stop.lon, poiName = stop.name, waypointIndex = WaypointIndex.FIRST, poiId = "", address = "", poiSubId = ""))
            } else {
                navi.startRoute(RouteInfo(latitude = stop.lat, longitude = stop.lon, poiName = stop.name, poiId = "", address = "", poiSubId = ""))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "goTo failed: ${e.message}")
            false
        }
        voice.say(
            when {
                !ok -> "내비에 넣지 못했어요. 화면에서 다시 눌러 주세요"
                guiding -> "${Hangul.eulReul(stop.name)} 경유지로 넣었어요"
                else -> "${Hangul.euRo(stop.name)} 안내할게요"
            },
        )
        if (ok) {
            CoachStore.update { copy(urgent = null) }
            // 경로가 바뀌었으니 다음 조회 때 경로 상태와 TBT를 바로 다시 받고, 30초 동안은 5초마다 받는다
            forceRoute = true
            markNaviReset()
        }
    }

    private suspend fun applyFreshAir(announce: Boolean) {
        val area = hvacArea ?: return
        lastFreshAirAt = now()
        val ok = runCatching { vehicle.recirculation(area).setAndConfirm(false) }.getOrDefault(false)
        if (announce && ok) voice.say("환기를 위해 외기로 바꿨어요")
    }

    private suspend fun makePlan() {
        val input = director.apply(CoachInput(now = now(), gear = hub.gear, speedKph = hub.speedKph, route = liveRouteWithHorizon(), candidates = liveCandidates()))
        val plan = engineLock.withLock { engine.plan(input) }
        CoachStore.update { copy(plan = plan) }
    }

    /** 지금 여정의 프로필. tripStart가 같으면 엔진은 주행 상태를 이어 간다 */
    private fun profileFor(occupants: List<Occupant>) =
        app.settings.tripProfile(tripStart, occupants.associate { it.id to lastRestroomAt }).copy(occupants = occupants)

    private suspend fun setOccupants(list: List<Occupant>) {
        app.settings.occupants = list
        engineLock.withLock { engine.setProfile(profileFor(list)) }
        CoachStore.update { copy(occupants = list) }
    }

    /** 정차 기록·학습 상태 지우기: 파일을 지우고 엔진을 빈 학습 상태로 다시 만든다 */
    private fun saveMemory(mem: dev.shimpyo.engine.PersonalMemory, gen: Long) {
        serviceScope.launch(Dispatchers.IO) { if (app.memory.save(mem, gen)) lastSavedMemory = mem }
    }

    private suspend fun clearRecords() {
        engineLock.withLock {
            java.io.File(filesDir, "stops.json").delete()
            app.memory.clear()
            lastSavedMemory = null
            engine = CoachEngine(profile = profileFor(CoachStore.state.value.occupants), memory = dev.shimpyo.engine.PersonalMemory())
        }
        CoachStore.update { copy(history = emptyList(), lastStop = null) }
    }

    private fun handle(command: UiCommand) {
        serviceScope.launch {
            guard("command") {
                when (command) {
                    UiCommand.Urgent -> urgent()
                    UiCommand.Listen -> listenAndHandle()
                    UiCommand.MakePlan -> makePlan()
                    is UiCommand.Answer -> answer(command.promptId, command.accept)
                    is UiCommand.GoTo -> goTo(command.stop)
                    is UiCommand.SetOccupants -> setOccupants(command.occupants)
                    UiCommand.DescribeOccupants -> describeOccupants()
                    is UiCommand.Demo -> startDemo(command.scenario)
                    UiCommand.DismissUrgent -> CoachStore.update { copy(urgent = null) }
                    UiCommand.ClearRecords -> clearRecords()
                    UiCommand.ProfileChanged -> engineLock.withLock { engine.setProfile(profileFor(CoachStore.state.value.occupants)) }
                }
            }
        }
    }

    /** 시연 시나리오 시작/종료. 시연 탑승자는 설정에 저장하지 않는다 */
    private suspend fun startDemo(name: String?) {
        val r = director.start(name)
        val t = now()
        oracle.enabled = director.oracleEnabled
        oracle.clear()
        val occupants = r?.occupants ?: app.settings.occupants
        engineLock.withLock {
            engine = if (r != null) {
                // 시연은 매번 같은 조건에서 시작하도록 빈 학습 상태의 새 엔진을 쓰고, 개인 학습 파일에는 쓰지 않는다
                val restroomAt = t - r.lastRestroomMin * 60_000L
                CoachEngine(
                    // 시나리오의 '이미 달린 시간'은 휴식 리듬에도 채운다 (예전에는 출발 시각에만 들어가 피로 게이지가 0분부터 셌다)
                    profile = app.settings.tripProfile(t - r.drivenMin * 60_000L, occupants.associate { it.id to restroomAt })
                        .copy(occupants = occupants, continuousDrivingMin = r.drivenMin.toDouble()),
                    memory = dev.shimpyo.engine.PersonalMemory(),
                )
            } else {
                // 시연을 끄면 실제 시각과 저장된 학습 상태로 돌아간다
                CoachEngine(profile = profileFor(occupants), memory = app.memory.load())
            }
        }
        CoachStore.update { copy(demo = director.name, occupants = occupants, urgent = null, plan = null) }
        Log.i(TAG, "demo started: ${director.name ?: "off"}")
        if (r != null) refreshCandidates()
    }

    /** 말로 탑승자 설정 ("일곱 살 아이랑 강아지 타") */
    private suspend fun describeOccupants() {
        val heard = voice.ask("누가 함께 타나요? 예를 들어 일곱 살 아이 하나랑 강아지처럼 말해 주세요", listenMs = 8_000) ?: return
        val list = OccupantPhrase.parse(heard).ifEmpty {
            interpreter.occupants(heard).also { CoachStore.update { copy(llm = interpreter.label) } } ?: emptyList()
        }
        if (list.isEmpty()) {
            voice.say("잘 못 알아들었어요. 화면에서 골라 주세요")
            return
        }
        // 건강 관련 유형(임신, 과민성 방광·장)은 따로 동의한 경우에만 저장한다 (개인정보 보호법 제23조)
        val consent = getSharedPreferences("shimpyo", MODE_PRIVATE).getBoolean("healthConsent", false)
        val allowed = if (consent) list else list.filter { it.type !in HEALTH_TYPES }
        if (allowed.size < list.size) voice.say("건강 관련 정보는 설정에서 동의한 뒤에 넣을 수 있어요")
        setOccupants(allowed)
        voice.say("${OccupantPhrase.describe(allowed)}로 맞췄어요")
    }

    // ------------------------------------------------------------ 정차 기록

    private fun trackStops(t: Long, door: Boolean) {
        if (hub.gear == Gear.PARK) {
            if (parkedSince == null) {
                parkedSince = t
                doorWhileParked = false
            }
            if (door) doorWhileParked = true
            return
        }
        val since = parkedSince ?: return
        parkedSince = null
        if (hub.gear == Gear.UNKNOWN) return
        val minutes = (t - since) / 60_000.0
        if (minutes < 3.0) return
        // 개인정보: 좌표는 남기지 않고, 알려진 정차지(휴게소 등)면 이름만 남긴다 (docs/research/ai-science.md 8-7)
        val place = nearestKnownPlace()
        val log = StopLog(start = since, end = t, lat = null, lon = null, restroom = doorWhileParked, name = place)
        val history = app.stopHistory.append(log)
        CoachStore.update { copy(history = history, lastStop = log) }
        if (!director.active) serviceScope.launch { val (mem, gen) = engineLock.withLock { engine.memory() to app.memory.generation }; saveMemory(mem, gen) }
    }

    /** 지금 위치에서 300m 안의 알려진 정차지 이름 */
    private fun nearestKnownPlace(): String? {
        val lat = hub.lat ?: return null
        val lon = hub.lon ?: return null
        return app.stops.near(lat, lon, null, 300.0, URGENT_KINDS, limit = 1).firstOrNull()?.name
    }

    // ------------------------------------------------------------ 신호 수집

    private suspend fun collectCar() {
        car.connect()
        serviceScope.launch { car.gear.collect { hub.gear = mapGear(it) } }
        serviceScope.launch { car.speedMps.collect { hub.speedKph = it?.times(3.6f) } }
        serviceScope.launch {
            car.drivingRestricted.collect { r ->
                hub.drivingRestricted = r
                CoachStore.update { copy(drivingRestricted = r) }
            }
        }
    }

    private suspend fun collectVehicle() {
        vehicle.connect()
        hvacArea = runCatching { vehicle.hvacAreas().firstOrNull() }.getOrNull()
        hvacArea?.let { a ->
            watch(vehicle.recirculation(a)) { hub.recirculation = it }
            watch(vehicle.hvacPower(a)) { hub.hvacOn = it }
        }
        runCatching { vehicle.hvacTemperatureAreas().keys.firstOrNull() }.getOrNull()?.let { a ->
            watch(vehicle.hvacTemperatureC(a)) { hub.setTempC = it }
        }
        watch(vehicle.steeringAngle()) { v ->
            hub.steeringDeg = v
            if (v != null) hub.addSample(DrivingSample(t = now(), speedKph = hub.speedKph, steeringDeg = v))
        }
        watch(vehicle.brakePedal()) { hub.brake = it }
        watch(vehicle.turnSignal()) { v ->
            hub.turnSignal = when (v) {
                TurnSignalValue.LEFT -> TurnSignal.LEFT
                TurnSignalValue.RIGHT -> TurnSignal.RIGHT
                TurnSignalValue.OFF -> TurnSignal.NONE
                null -> null
            }
        }
        watch(vehicle.cruiseControl()) { c -> hub.cruiseActive = c?.let { it == CruiseControl.ACTIVATED } }
        runCatching { vehicle.doorAreas().keys }.getOrNull()?.forEach { area ->
            watch(vehicle.doorPosition(area)) { pos -> if ((pos ?: 0) > 0) hub.markDoorOpened() }
        }
    }

    private suspend fun collectLocation() {
        location.connect()
        location.updates().collect { l ->
            if (director.active) return@collect
            hub.lat = l.latitude
            hub.lon = l.longitude
            hub.heading = l.heading.takeIf { it.isFinite() }
            hub.locationAt = now()
        }
    }

    private suspend fun collectNavi() {
        navi.connect()
        while (true) {
            try {
                val real = SystemClock.elapsedRealtime()
                if (forceRoute || lastRouteAt == 0L || real - lastRouteAt >= NaviPoll.ROUTE_MS) {
                    forceRoute = false
                    refreshRoute()
                    lastRouteAt = real
                }
                if (route?.guiding == true) {
                    // 다음 화장실·회전이 가까우면 5초, 멀고 주행 거리 보정을 믿을 수 있으면 20초마다 받는다 (NaviPoll)
                    val ts = tbtSnap
                    val m = moved(ts.odometerM)
                    val interval = NaviPoll.tbtIntervalMs(
                        nextOpportunityM = ts.opportunities.minOfOrNull { it.routeDistanceM - m },
                        nextManeuverM = ts.nextManeuverM?.minus(m),
                        speedKnown = hub.speedKph != null,
                        trusted = odometerTrusted,
                        afterReset = real - naviResetAt < NaviPoll.FAST_AFTER_RESET_MS,
                    )
                    if (forceTbt || lastTbtAt == 0L || real - lastTbtAt >= interval) {
                        forceTbt = false
                        val at = odometerM // 내비는 요청 시점의 위치에서 잰다
                        val tbt = navi.tbt()
                        tbtSnap = TbtSnap(RouteOpportunities.extract(tbt), RouteOpportunities.nextManeuverDistance(tbt), at)
                        lastTbtAt = real
                    }
                } else if (tbtSnap.opportunities.isNotEmpty() || tbtSnap.nextManeuverM != null) {
                    tbtSnap = TbtSnap(emptyList(), null, odometerM)
                }
                // 위치 SDK 갱신이 끊기면 내비의 현재 위치로 대신한다. P로 서 있으면 위치가 그대로라 다시 묻지 않는다
                val standing = hub.gear == Gear.PARK && (hub.speedKph ?: 0f) < 1f
                if (!director.active && now() - hub.locationAt > 15_000 && (hub.lat == null || !standing)) {
                    val c = navi.currentLocation()
                    hub.lat = c.latitude
                    hub.lon = c.longitude
                    hub.heading = c.heading.toFloat()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "navi poll failed: ${e.message}")
            }
            delay(NaviPoll.NEAR_MS)
        }
    }

    /** [base] 이후 달린 거리. 주행 거리 보정을 믿을 수 없으면 0 (예전처럼 받은 거리를 그대로 쓴다) */
    private fun moved(base: Double): Double = if (odometerTrusted) (odometerM - base).coerceAtLeast(0.0) else 0.0

    private fun markNaviReset() {
        naviResetAt = SystemClock.elapsedRealtime()
        forceTbt = true
    }

    /** 엔진에 넣을 후보: 후보를 만든 뒤 달린 만큼 경로 거리를 줄인다 */
    private fun liveCandidates(): List<StopCandidate> = candidateSnap.let { NaviPoll.advanceCandidates(it.list, moved(it.odometerM)) }

    /** [liveRoute]에 후보를 넣은 범위를 더한다 (자른 목록의 마지막 후보 뒤를 목적지까지의 공백으로 보지 않게) */
    private fun liveRouteWithHorizon(): RouteState? {
        val r = liveRoute() ?: return null
        val cs = candidateSnap
        val until = cs.untilM ?: return r
        return if (r.guiding) r.copy(candidatesUntilM = (until - moved(cs.odometerM)).coerceAtLeast(0.0)) else r
    }

    /** 엔진에 넣을 경로 상태: 마지막 조회 뒤 달린 만큼 남은 거리·정체 위치·다음 회전 거리를 줄인다 */
    private fun liveRoute(): RouteState? {
        val rs = routeSnap
        val r = rs.route ?: return null
        if (!r.guiding) return r
        val sinceRoute = moved(rs.odometerM)
        val ts = tbtSnap
        val sinceTbt = moved(ts.odometerM)
        return r.copy(
            remainDistanceM = r.remainDistanceM?.let { (it - sinceRoute).coerceAtLeast(0.0) },
            congestion = if (sinceRoute <= 0.0) r.congestion
            else r.congestion.map { it.copy(startDistanceM = it.startDistanceM - sinceRoute) }.filter { it.startDistanceM + it.lengthM > 0 },
            nextManeuverDistanceM = ts.nextManeuverM?.let { (it - sinceTbt).coerceAtLeast(0.0) },
        )
    }

    private suspend fun refreshRoute() {
        val s = navi.routeState()
        if (s.naviMode != lastNaviMode) {
            Log.i(TAG, "navi mode: ${s.naviMode}")
            lastNaviMode = s.naviMode
        }
        val guiding = NaviPoll.isGuiding(s.naviMode)
        if (guiding && route?.guiding != true) {
            CongestionMath.reset()
            markNaviReset()
        }
        if (!guiding) {
            routeSnap = RouteSnap(RouteState(guiding = false), odometerM)
            destinationName = null
            prevRemainM = null
            odometerTrusted = false
            return
        }
        val at = odometerM
        val d = navi.destination()
        val remain = d.remainDistance.toDouble()
        // 내비가 확인한 진행으로 주행 거리 보정을 믿을지 정하고, 경로가 바뀌었으면 TBT를 바로 다시 받는다
        val p = NaviPoll.checkProgress(prevRemainM, prevRemainOdometerM, remain, at, odometerTrusted)
        odometerTrusted = p.trusted
        if (p.rerouted) {
            CongestionMath.reset()
            markNaviReset()
        }
        prevRemainM = remain
        prevRemainOdometerM = at
        val items = s.trafficStatus?.congestionItemList.orEmpty()
        items.forEach { c -> CongestionMath.observe("${c.roadName}|${c.startCrossName}", c.offset, remain) }
        val congestion = items.map { c ->
            CongestionSegment(
                startDistanceM = CongestionMath.startFromCar(c.offset, remain),
                lengthM = c.distance,
                durationS = CongestionMath.durationS(c.trafficCongestionTime),
                type = c.trafficCongestionType.name,
                roadName = c.roadName,
                startName = c.startCrossName,
                endName = c.endCrossName,
            )
        }.filter { it.startDistanceM + it.lengthM > 0 }
        routeSnap = RouteSnap(
            RouteState(guiding = true, remainDistanceM = remain, remainTimeS = d.remainTime.toDouble(), congestion = congestion),
            at,
        )
        destinationName = d.name
    }

    private suspend fun candidateLoop() {
        // 정차 후보 표(stops.bin)는 1초 안팎에 읽힌다 (StopAssets 주석)
        val n = app.stops.ensureLoaded()
        CoachStore.update { copy(status = "정차 후보 데이터 ${n}곳") }
        while (true) {
            refreshCandidates()
            delay(if (director.active) 5_000 else 20_000)
        }
    }

    /** 진행 방향 앞쪽 화장실 기회를 추리고 상위 몇 곳은 내비 경로 거리로 보정한다 */
    private suspend fun refreshCandidates() = candidatesLock.withLock { refreshCandidatesLocked() }

    /** 후보 갱신은 한 번에 하나만 (시연 시작과 20초 주기가 겹치면 RouteOracle 캐시를 동시에 고쳤다) */
    private suspend fun refreshCandidatesLocked() {
        val lat = hub.lat ?: return
        val lon = hub.lon ?: return
        val t = now()
        // 길안내 중이면 TBT의 경로 위 휴게소·졸음쉼터를 쓴다 (경로 거리와 방향이 정확하다)
        val ts = tbtSnap
        val at = odometerM
        val opps = NaviPoll.advance(ts.opportunities, moved(ts.odometerM))
        if (opps.isNotEmpty() && !director.active) {
            val list = candidatesFromRoute(opps, lat, lon, t)
            // TBT 기회를 ROUTE_OPP_MAX곳까지만 넣었으면 첫 번째로 뺀 기회 위치까지가 '아는 범위'다
            val until = opps.getOrNull(ROUTE_OPP_MAX)?.routeDistanceM
            candidateSnap = CandidateSnap(list, at, until)
            CoachStore.update { copy(ahead = list) }
            return
        }
        // requestTrip으로 보정하는 상위 몇 곳만 쓴다. 시연은 requestTrip을 안 쓰고 경로 위 시설만 고르므로 다 넘긴다
        val raw = app.stops.ahead(lat, lon, hub.heading, AHEAD_RADIUS_M, PLANNED_KINDS)
            .filter { it.facilities.restroom }
            .let { if (director.active) it else it.take(ORACLE_MAX) }
        // requestTrip은 후보마다 요청 시점의 위치에서 잰다: 요청 때 주행 거리를 같이 적어 두고 마지막에 같은 기준으로 맞춘다
        val refined = raw.map { c -> odometerM to oracle.refine(c, lat, lon, t) }
        val base = odometerM
        val list = refined.mapNotNull { (requestedAt, c) ->
            if (c.directionOk == false) null else NaviPoll.advanceCandidates(listOf(c), moved(requestedAt) - moved(base)).firstOrNull()
        }
        candidateSnap = CandidateSnap(list, base)
        CoachStore.update { copy(ahead = director.candidates(list)) }
    }

    /**
     * TBT의 경로 위 기회를 정차 후보로 바꾼다. 좌표와 편의시설은 이름으로 공공데이터와 맞추고,
     * 같은 이름이 양방향에 있으면 requestTrip 거리가 TBT 누적 거리와 가장 가까운 쪽을 고른다.
     * 데이터에 없는 곳은 좌표 없이 넣고, 수락하면 내비 장소 검색을 연다.
     */
    private suspend fun candidatesFromRoute(opps: List<RouteOpportunity>, lat: Double, lon: Double, t: Long): List<StopCandidate> {
        return opps.take(ROUTE_OPP_MAX).map { o ->
            val matches = app.stops.matchByName(o.name, o.kind, lat, lon, o.routeDistanceM + 5_000.0)
            val chosen = when (matches.size) {
                0 -> null
                1 -> matches.first()
                else -> matches
                    .map { m -> m to oracle.refine(m, lat, lon, t) }
                    .minByOrNull { (_, refined) -> kotlin.math.abs((refined.routeDistanceM ?: Double.MAX_VALUE) - o.routeDistanceM) }
                    ?.first
            }
            val base = chosen ?: StopCandidate(
                id = "tbt:${o.name}",
                name = o.name,
                kind = o.kind,
                lat = lat,
                lon = lon,
                facilities = Facilities(restroom = true, food = o.kind == StopKind.HIGHWAY_REST_AREA),
                parkable = true,
            )
            // etaS는 비워 둔다: 경로 평균 속도로 만든 값은 앞 정체를 반영하지 못하고, 엔진은 etaS를 내비 값으로 믿는다
            // (StopCandidate.etaS 주석). 비워 두면 엔진이 정체 구간으로 도착 시간을 계산해 추천에 채운다
            base.copy(routeDistanceM = o.routeDistanceM, directionOk = true)
        }
    }

    // ------------------------------------------------------------ 도우미

    private fun <T : Any> watch(signal: ReadSignal<T>, onValue: (T?) -> Unit) {
        serviceScope.launch {
            runCatching { onValue(signal.get()) }
            try {
                signal.changes().collect { onValue(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "watch failed: ${e.message}")
            }
        }
    }

    private suspend fun guard(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$name failed: ${e.message}")
        }
    }

    private fun mapGear(v: Int?): Gear = when (v) {
        null -> Gear.UNKNOWN
        GEAR_PARK -> Gear.PARK
        GEAR_DRIVE -> Gear.DRIVE
        GEAR_REVERSE -> Gear.REVERSE
        GEAR_NEUTRAL -> Gear.NEUTRAL
        else -> if (v >= GEAR_1) Gear.DRIVE else Gear.UNKNOWN
    }

    override fun onDestroy() {
        if (!director.active && ::engine.isInitialized) runCatching { app.memory.save(engine.memory()) }
        voice.stop()
        interpreter.release()
        car.release()
        vehicle.release()
        navi.release()
        location.release()
        tts.release()
        stt.release()
        CoachStore.update { copy(serviceRunning = false) }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "Shimpyo"
        const val FRESH_AIR_GAP_MS = 10 * 60_000L
        const val AHEAD_RADIUS_M = 60_000.0
        const val ORACLE_MAX = 6
        const val ROUTE_OPP_MAX = 10
        const val URGENT_RADIUS_M = 5_000.0
        const val URGENT_MAX = 15
        // android.car.VehicleGear 값
        const val GEAR_NEUTRAL = 1
        const val GEAR_REVERSE = 2
        const val GEAR_PARK = 4
        const val GEAR_DRIVE = 8
        const val GEAR_1 = 16
        val HEALTH_TYPES = setOf(dev.shimpyo.engine.OccupantType.PREGNANT, dev.shimpyo.engine.OccupantType.OAB, dev.shimpyo.engine.OccupantType.IBS)
        val PLANNED_KINDS = setOf(StopKind.HIGHWAY_REST_AREA, StopKind.DROWSY_SHELTER)
        val URGENT_KINDS = setOf(StopKind.HIGHWAY_REST_AREA, StopKind.DROWSY_SHELTER, StopKind.PUBLIC_RESTROOM, StopKind.GAS_STATION, StopKind.PUBLIC_PARKING)
    }
}
