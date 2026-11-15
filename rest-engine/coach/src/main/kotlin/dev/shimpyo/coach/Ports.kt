package dev.shimpyo.coach

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.EnergyKind
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.TripProfile
import dev.shimpyo.engine.VoiceIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/*
 * 코치 계층이 바깥(차량·내비·음성·저장소·화면)과 만나는 경계. 앱이 Pleos SDK와 안드로이드로 구현하고,
 * 테스트는 가짜로 구현한다. 여기 있는 것만 바깥에 의존한다.
 */

// ------------------------------------------------------------ 시간

/**
 * epoch ms 시각을 주는 시계. 세계(CoachWorld.clock)의 것이면 코치 시각이다: 엔진 입력, 정차, 여정처럼 '엔진이 보는 시각'에 쓰고,
 * 시연에서는 가상 시각이다(DemoRun). 실제 시각을 주는 것은 WallClock이라고 부른다(같은 타입, CoachDeps.kt).
 * 질문 카드의 수명·만료처럼 사람이 겪는 시간은 이것이 아니라 [ElapsedClock]으로 잰다.
 */
fun interface CoachClock {
    fun now(): Long
}

/**
 * 단조 증가하는 실제 시간(ms, SystemClock.elapsedRealtime). 조회 간격·시간 초과·신선도처럼
 * 시연 전환을 넘어 이어지거나 delay와 맞아야 하는 것에 쓴다. 두 시각을 섞으면 시연을 끈 뒤 몇 시간씩 어긋난다.
 */
fun interface ElapsedClock {
    fun elapsedMs(): Long
}

// ------------------------------------------------------------ 내비

/** 차량 신호 경계의 최신 에너지 정보. 단위는 km; 읽지 못했거나 오래됐으면 각 값은 null. */
data class VehicleEnergy(val rangeKm: Double? = null, val kind: EnergyKind? = null)

interface NaviPort {
    suspend fun connect()
    suspend fun routeStatus(): NaviRouteStatus
    suspend fun destination(): NaviDestination
    suspend fun tbt(): List<TbtItem>
    suspend fun currentLocation(): Fix
    /** requestTrip: 길안내를 시작하지 않고 거리·ETA만 받는다 */
    suspend fun tripEstimate(lat: Double, lon: Double, name: String, timeoutMs: Long): TripEstimate
    /**
     * 경유지 추가. 넣었다고 확인되면 ADDED, 경유지가 이미 둘이면 FULL(내비가 말없이 버린다, 2026-11-12), 밖에서 넣은 경유지보다
     * 앞이라 순서를 맞출 수 없으면 AHEAD_OF_OTHER, 보냈지만 확인하지 못했으면 NOT_CONFIRMED. 까닭마다 운전자에게 할 말이 다르다
     */
    suspend fun addWaypoint(lat: Double, lon: Double, name: String): WaypointResult
    /** 목적지로 길안내 시작. 경로가 있는 길안내 모드(NaviMode.hasRoute)가 되면 true */
    suspend fun startRoute(lat: Double, lon: Double, name: String): Boolean
    /** 좌표를 모르는 곳: 내비 장소 검색 화면을 연다. 열었으면 true */
    suspend fun showPlaceSearch(keyword: String): Boolean
    /** 내비가 먼저 보내는 경로 변화. 조회 주기를 기다리지 않고 다시 읽는 데 쓴다 */
    val changes: Flow<NaviChange>
    /**
     * 지금 달리는 도로의 제한속도(km/h). Pleos 내비가 리스너를 건 뒤 매초 보낸다(onCurrentRoadSpeedLimit). 0 이하는 모름.
     * 길가 화장실에 바로 갈 수 없는 길(도시고속도로·자동차전용도로)을 가리는 데 쓴다(NearbyStops). 보내지 않는 구현은 빈 흐름
     */
    val roadSpeedLimits: Flow<Int> get() = emptyFlow()
}

// ------------------------------------------------------------ 음성

interface VoicePort {
    val ttsReady: Boolean
    val sttReady: Boolean
    suspend fun connect()
    suspend fun say(text: String): Boolean
    /** 묻고 대답을 듣는다. 대답이 없거나 STT를 못 쓰면 null (화면 버튼으로 받는다) */
    suspend fun ask(text: String, listenMs: Long = 6_000): String?
    suspend fun listen(listenMs: Long = 8_000): String?
    /** 말하기·듣기를 멈춘다 (질문을 거둘 때) */
    fun stop()
}

/** 규칙으로 못 알아들은 말을 맡는 해석기 (Pleos LLM). 쓸 수 없으면 null을 돌려준다 */
interface IntentInterpreter {
    /** 화면에 보일 이름 (예: "Pleos LLM (온디바이스)"). 아직 모르면 null */
    val label: String?
    suspend fun interpret(text: String, occupants: List<Occupant>, activePromptId: String?): VoiceIntent?
    suspend fun occupants(text: String): List<Occupant>?
}

// ------------------------------------------------------------ 공조

interface CabinPort {
    /** 내기·외기 전환을 쓸 수 있는지 (차량 capability). 연결 전이나 미지원이면 false */
    val freshAirSupported: Boolean
    /** 외기로 바꾸고 바뀐 것을 확인하면 true */
    suspend fun freshAir(): Boolean
    /** 내기로 되돌리고 확인하면 true */
    suspend fun recirculate(): Boolean
}

// ------------------------------------------------------------ 정차 후보 데이터

/** 공공데이터·OSM 정차 후보 표 조회 (StopRepository) */
interface StopIndex {
    /** 표를 읽었는지. 읽기 전에는 모든 조회가 빈 목록이다 */
    val loaded: Boolean
    /** 진행 방향 앞쪽 [radiusM] 안 후보 (반대 차로 시설은 뺀다) */
    fun ahead(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>): List<StopCandidate>
    /** 급해 모드: 가까운 순 [limit]곳. 지금 문을 연 곳만 */
    fun near(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>, now: Long, limit: Int): List<StopCandidate>
    /** 급해 모드: 화장실 근거를 확인한 뒤 limit를 적용한다 (에너지 전용 후보가 목록을 채우지 않게). */
    fun nearRestrooms(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>, now: Long, limit: Int): List<StopCandidate> =
        near(lat, lon, heading, radiusM, kinds, now, Int.MAX_VALUE).filter { it.facilities.restroom }.take(limit)
    /** TBT 이름으로 공공데이터 행을 찾는다. [heading]을 주면 반대 차로 행은 뺀다 */
    fun matchByName(name: String, kind: StopKind, lat: Double, lon: Double, maxStraightM: Double, heading: Float? = null): List<StopCandidate>
    /** 차가 고속도로 차로 위인지 (지도 맞춤, MotorwayIndex: 차로 선형 가까이이고 방향이 맞다). 표를 읽기 전이나 모르면 false */
    fun onMotorway(lat: Double, lon: Double, heading: Float): Boolean = false

    /**
     * 일상 정차 후보 (NearbyStops): [near]와 같은 반경 검색이지만 화장실·주차·지금 영업·진행 방향(뒤쪽 제외)을 먼저 거른 뒤
     * 가까운 순 [limit]곳. 자르고 거르면 도심에서 쓸 곳이 하나도 남지 않았다 (2026-11-13 감사: 잠실 서행에서 3km 안 45곳 중 0곳)
     */
    fun nearUsable(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>, now: Long, limit: Int): List<StopCandidate> =
        near(lat, lon, heading, radiusM, kinds, now, Int.MAX_VALUE)
            .filter { it.facilities.restroom && it.parkable && it.openNow != false && it.directionOk != false }
            .take(limit)

    /** id로 정차 후보 하나 (재시작 뒤 '들르기로 한 곳'을 되살릴 때). 표를 읽기 전이거나 없으면 null */
    fun byId(id: String): StopCandidate? = null
}

/** 후보까지의 실제 경로 거리·ETA·방향을 내비로 보정한다 (RouteOracle) */
interface TripEstimator {
    /** false면 내비에 묻지 않는다 (내비를 방해한다고 확인되면 끄는 스위치. 시연은 CandidatePolicy가 아예 부르지 않는다) */
    var enabled: Boolean

    /**
     * [from]에서 [c]까지 잰 경로 거리·ETA(내비 값)·방향을 채워 돌려준다. 모르면 null: 꺼져 있거나, 내비가 실패·시간 초과했거나,
     * 실패 뒤 다시 묻기를 미루는 중이거나, 내비가 다른 일로 바빠 차례가 오지 않았을 때. null은 '방향이 틀렸다'가 아니라 '모름'이다.
     * 부른 코루틴이 취소되면 CancellationException을 그대로 던진다 (실패로 세지 않는다).
     *
     * [permit]: 내비에 실제로 요청을 보내기 직전에 한 번 묻는다. false면 보내지 않고 null을 돌려준다. 캐시가 답할 때는 묻지 않는다.
     * 갱신 하나의 요청 수 한도(CandidateProvider)가 캐시가 답한 것까지 세지 않게 하는 길이다 (서비스 감사 SV-N1).
     */
    suspend fun refine(c: StopCandidate, from: Fix, permit: () -> Boolean = { true }): StopCandidate?

    /** 캐시와 실패 기록을 비운다 (모드 전환) */
    fun clear()
}

// ------------------------------------------------------------ 저장소

/** 정차 기록 (stops.json). 좌표는 남기지 않는다 (개인정보, docs/research/ai-science.md 8-7) */
interface HistoryPort {
    fun load(): List<StopLog>
    /** 덧붙이고 저장한 뒤 전체 목록을 돌려준다 */
    fun append(log: StopLog): List<StopLog>
    fun clear()
}

/** 개인 학습 상태 (memory.json). generation: 지우기 전에 꺼낸 저장을 버리는 데 쓴다 (AtomicFile) */
interface MemoryPort {
    val generation: Long
    fun load(): PersonalMemory
    fun save(memory: PersonalMemory, generationAtSnapshot: Long): Boolean
    fun clear()
}

interface SettingsPort {
    var occupants: List<Occupant>
    val autoFreshAir: Boolean
    val voiceEnabled: Boolean
    /** 건강 관련 탑승자 유형(임신, 과민성 방광·장) 저장에 따로 동의했는지 (개인정보 보호법 제23조) */
    val healthConsent: Boolean
    /** 운전자 수면 시간 등 설정을 담은 여정 프로필 */
    fun tripProfile(start: Long, lastRestroomAt: Map<String, Long>): TripProfile
}

// ------------------------------------------------------------ 화면·엔진·로그

/** 화면 상태 갱신 (CoachStore.update) */
fun interface UiSink {
    fun update(transform: CoachUiState.() -> CoachUiState)
}

/**
 * 엔진 만들기. 테스트는 고정 Random으로 재현 가능한 엔진을 넘긴다.
 * [config]는 세계가 정한다(CoachWorld.engineConfig): 시연은 시각이 배속으로 흘러 질문 수명을 배속만큼 늘린다.
 */
fun interface EngineFactory {
    fun create(profile: TripProfile, memory: PersonalMemory, config: CoachConfig): CoachEngine
}

interface CoachLog {
    fun i(message: String)
    fun w(message: String)

    companion object {
        val NONE = object : CoachLog {
            override fun i(message: String) {}
            override fun w(message: String) {}
        }
    }
}
