package dev.shimpyo.coach

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.DrivingSample
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.TripProfile
import kotlin.math.roundToInt

/**
 * 코치가 돌아가는 세계: 실제 또는 시연. 시연과 실제가 다른 것은 모두 여기서 정한다 (구조 리뷰 3.8, concern N).
 * 다른 코드는 '시연 중인가?'를 묻지 않고 세계에 정책을 묻는다 (저장할지, 내비 위치로 보충할지, 후보를 어떻게 만들지...).
 * 예전에는 서비스 곳곳의 `director.active` 분기 8곳과 `director.` 접점 20곳이 이 결정을 나눠 들고 있어서, 새 기능마다
 * 분기를 잊는 버그가 났다 (e95c384 #1).
 *
 * 세계는 값이다. 코치 액터(WorldSwitch)가 세계와 그 엔진·여정을 한 묶음(ActiveWorld)으로 틱 사이에 바꿔 끼운다.
 */
sealed class CoachWorld {
    /** 시연 이름. 실제면 null (CoachUiState.demo) */
    abstract val name: String?
    /** 엔진과 이 세계 상태가 쓰는 시각. 시연이면 가상 시각 */
    abstract val clock: CoachClock
    abstract val candidatePolicy: CandidatePolicy
    /** 후보 거리·ETA를 내비 requestTrip으로 보정할지 (TripEstimator.enabled) */
    abstract val oracleEnabled: Boolean
    /** 개인 학습 상태(memory.json)를 읽고 저장할지 */
    abstract val persistsLearning: Boolean
    /** 정차 기록(stops.json)에 덧붙일지. 아니면 화면에만 보인다 */
    abstract val persistsHistory: Boolean
    /** 여정 맥박(trip.json)을 저장할지 */
    abstract val persistsTrip: Boolean
    /** 위치 SDK가 멈췄을 때 내비 현재 위치로 보충할지 */
    abstract val usesNaviLocationFallback: Boolean
    /** 내비가 알려 주는 지금 도로의 제한속도를 쓸지 (NearbyStops). 시연 위치는 내비가 보는 실제 도로와 다르므로 쓰지 않는다 */
    abstract val usesNaviRoadLimit: Boolean

    /** 이 세계의 탑승자. 질문·음성 해석·화면이 모두 이것을 쓴다 (구조 리뷰 D9: 경로마다 출처가 달랐다) */
    abstract fun occupants(settings: SettingsPort): List<Occupant>

    /** 엔진과 후보가 쓸 위치. 실제 위치는 PositionTracker가 계속 받고, 시연은 그 위에 덧씌울 뿐 쓰지 않는다 (구조 리뷰 D3) */
    open fun position(real: Fix?): Fix? = real

    /** 엔진 입력을 이 세계에 맞게 바꾼다 */
    open fun apply(input: CoachInput): CoachInput = input

    /** 후보 거리를 이 세계 기준으로 바꾼다 */
    open fun candidates(live: List<StopCandidate>): List<StopCandidate> = live

    /** 새 엔진의 학습 상태 */
    protected abstract fun engineMemory(loaded: PersonalMemory): PersonalMemory

    /**
     * 이 세계의 엔진 설정. 엔진의 시간 값은 코치 시각으로 흐른다. 질문 수명(promptTtlS)만은 사람이 듣고 누르는 실제 시간이라
     * 시연은 배속만큼 늘린다: 그러지 않으면 7.2배속 귀성길에서 45초 수명이 실제 6초가 되어 엔진이 카드를 먼저 거뒀다
     * (prompt.md 2.5, PromptEngineScenarioTest). 쉼·조용한 때 같은 다른 값은 시연이 빨리 흐르도록 그대로 둔다.
     */
    open val engineConfig: CoachConfig get() = BASE_CONFIG

    /** 화면에 보일 목적지 이름. [navi]는 실제 내비의 목적지 */
    open fun destinationName(navi: String?): String? = navi

    /**
     * 질문 카드가 떴거나([shown]) 내려갔다. 시연은 카드가 떠 있는 동안 시각과 위치를 함께 [QUESTION_PACE]로 늦춘다(가상 속도는
     * 그대로라 엔진의 판단은 같다): 7.2배속 귀성길에서 옥산휴게소 카드가 실제 13~25초 만에 지나가, PT에서 카드를 설명하고 누를
     * 시간이 빠듯했다 (2026-11-13). 실제 세계는 바꾸지 않는다. 바뀐 것이 없으면 이 값 그대로
     */
    open fun questionShown(shown: Boolean, elapsedNow: Long): CoachWorld = this

    /**
     * 엔진 프로필을 만드는 단 한 곳. 탑승자는 이 세계의 것, 수면 시간 등은 설정, 여정 값은 [anchor]에서 온다.
     * 탑승자·설정이 바뀌어도 같은 기준점이면 tripStart가 같아 엔진이 여정을 이어 간다 (서비스 감사 D4, 구조 리뷰 D5).
     */
    fun profile(settings: SettingsPort, anchor: TripAnchor): TripProfile {
        val occupants = occupants(settings)
        return settings.tripProfile(anchor.tripStart, anchor.lastRestroomFor(occupants))
            .copy(occupants = occupants, continuousDrivingMin = anchor.continuousDrivingMin)
    }

    /**
     * 이 세계의 새 엔진. [park]가 있으면 그 정차 시작 시각에 P 입력을 한 번 넣어, 엔진이 정차를 처음부터 센다
     * (재시작 전 문 열림과 정차 시간이 이어져 화장실 정차·15분 휴식이 제때 잡힌다, 서비스 감사 D12).
     * P에서는 엔진이 질문을 내지 않으므로 이 입력의 출력은 버려도 된다.
     */
    fun newEngine(factory: EngineFactory, settings: SettingsPort, memory: PersonalMemory, anchor: TripAnchor, park: ParkState? = null): CoachEngine {
        val engine = factory.create(profile(settings, anchor), engineMemory(memory), engineConfig)
        if (park != null) {
            val at = maxOf(park.since, anchor.tripStart)
            if (at <= clock.now()) runCatching { engine.update(CoachInput(now = at, gear = Gear.PARK, doorOpened = park.doorSeen)) }
        }
        return engine
    }

    /**
     * 엔진이 서 있다고 아는 정차([newEngine]의 park, 또는 시연 동안 옆에 둔 실제 엔진의 정차)가 [endedAt]에 끝났다고 알린다.
     * 정차 감지기가 "되돌린 정차는 마지막으로 본 때 끝났다"고 판정했을 때, 그 판정 뒤 첫 엔진 update 전에 부른다: 그러지 않으면 엔진은
     * 다음 update까지 서 있던 것으로 세어 보지 못한 시간을 화장실 정차·휴식으로 잡는다 (서비스 닫힘 검증 N3: 게이지와 기록이 어긋났다).
     * 공개 엔진 API(update)만 쓴다: [endedAt] 직전의 P 입력으로 정차를 거기까지 세고, [endedAt]의 D 입력으로 끝낸다.
     * D 입력에는 제동 표본을 실어 엔진이 이 입력으로 질문을 내지 않게 한다(조용한 때 규칙: 운전자가 보지 못할 질문이다).
     */
    fun endPark(engine: CoachEngine, endedAt: Long) {
        runCatching {
            engine.update(CoachInput(now = endedAt - 1, gear = Gear.PARK))
            engine.update(CoachInput(now = endedAt, gear = Gear.DRIVE, samples = listOf(DrivingSample(t = endedAt, brake = true))))
        }
    }

    /** 실제 세계 */
    class Real(override val clock: CoachClock) : CoachWorld() {
        override val name: String? get() = null
        override val candidatePolicy: CandidatePolicy get() = CandidatePolicy.REAL
        override val oracleEnabled: Boolean get() = true
        override val persistsLearning: Boolean get() = true
        override val persistsHistory: Boolean get() = true
        override val persistsTrip: Boolean get() = true
        override val usesNaviLocationFallback: Boolean get() = true
        override val usesNaviRoadLimit: Boolean get() = true
        override fun occupants(settings: SettingsPort): List<Occupant> = settings.occupants
        override fun engineMemory(loaded: PersonalMemory): PersonalMemory = loaded
    }

    /**
     * 시연 세계. 위치·시각·경로·정체·후보 거리는 [run]이 만든다. 학습·기록·여정을 읽지도 저장하지도 않는다
     * (시연은 매번 같은 조건에서 시작하고, 사용자 기록을 가상 시각으로 더럽히지 않는다: 서비스 감사 D3·D15, 구조 리뷰 B4, UI #15).
     * 시연 중 탑승자를 바꾸면 이 시연에만 반영한다 ([withOccupants]): 설정에 저장하지 않는다.
     */
    class Demo(val run: DemoRun, private val occupantsOverride: List<Occupant>? = null) : CoachWorld() {
        override val name: String get() = run.name
        override val clock: CoachClock get() = run
        override val candidatePolicy: CandidatePolicy get() = CandidatePolicy.DEMO
        override val oracleEnabled: Boolean get() = false
        override val persistsLearning: Boolean get() = false
        override val persistsHistory: Boolean get() = false
        override val persistsTrip: Boolean get() = false
        override val usesNaviLocationFallback: Boolean get() = false
        override val usesNaviRoadLimit: Boolean get() = false
        override fun occupants(settings: SettingsPort): List<Occupant> = occupantsOverride ?: run.scenario.occupants
        override fun position(real: Fix?): Fix = run.position()
        override fun apply(input: CoachInput): CoachInput = run.apply(input)
        override fun candidates(live: List<StopCandidate>): List<StopCandidate> = run.candidates(live)
        override fun engineMemory(loaded: PersonalMemory): PersonalMemory = PersonalMemory()
        override val engineConfig: CoachConfig
            get() = BASE_CONFIG.copy(promptTtlS = (BASE_CONFIG.promptTtlS * run.scenario.timeScale).roundToInt())
        /** 시연 경로는 가상이라 실제 내비의 목적지 대신 시나리오의 목적지를 보인다 */
        override fun destinationName(navi: String?): String? = run.scenario.destinationName

        override fun questionShown(shown: Boolean, elapsedNow: Long): Demo {
            val next = run.paced(if (shown) QUESTION_PACE else 1.0, elapsedNow)
            return if (next === run) this else Demo(next, occupantsOverride)
        }

        /** 사용자가 이 시연의 탑승자를 고쳤는지 (아니면 시나리오가 정한 탑승자다) */
        val occupantsEdited: Boolean get() = occupantsOverride != null

        /** 이 시연의 탑승자만 바꾼 세계 (같은 시연, 같은 시각) */
        fun withOccupants(list: List<Occupant>): Demo = Demo(run, list)
    }

    companion object {
        private val BASE_CONFIG = CoachConfig()

        /**
         * 질문 카드가 떠 있는 동안의 시연 빠르기. 귀성길(실제 1초에 200m)이 1초에 50m가 되어, 진입로 2km 전에 물어도 엔진이 카드를
         * 거두는 500m 앞까지 30초가 남는다. 그보다 멀면 카드의 실제 수명(PromptMachine.ENGINE_CARD_MS 45초)이 먼저 끝난다
         */
        const val QUESTION_PACE = 0.25
    }
}
