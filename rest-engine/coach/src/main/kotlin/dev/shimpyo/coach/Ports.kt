package dev.shimpyo.coach

import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.TripProfile
import dev.shimpyo.engine.VoiceIntent
import kotlinx.coroutines.flow.Flow

/*
 * 코치 계층이 바깥(차량·내비·음성·저장소·화면)과 만나는 경계. 앱이 Pleos SDK와 안드로이드로 구현하고,
 * 테스트는 가짜로 구현한다. 여기 있는 것만 바깥에 의존한다.
 */

// ------------------------------------------------------------ 시간

/**
 * 엔진과 모드별 상태가 쓰는 시각(epoch ms). 시연에서는 가상 시각이다(DemoDirector).
 * 정차 기록·질문 만료처럼 '엔진이 보는 시각'에 쓴다.
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

interface NaviPort {
    suspend fun connect()
    suspend fun routeStatus(): NaviRouteStatus
    suspend fun destination(): NaviDestination
    suspend fun tbt(): List<TbtItem>
    suspend fun currentLocation(): Fix
    /** requestTrip: 길안내를 시작하지 않고 거리·ETA만 받는다 */
    suspend fun tripEstimate(lat: Double, lon: Double, name: String, timeoutMs: Long): TripEstimate
    /** 경유지 추가. 내비가 경유지로 넣었다고 확인되면 true */
    suspend fun addWaypoint(lat: Double, lon: Double, name: String): Boolean
    /** 목적지로 길안내 시작. 경로가 있는 길안내 모드(NaviMode.hasRoute)가 되면 true */
    suspend fun startRoute(lat: Double, lon: Double, name: String): Boolean
    /** 좌표를 모르는 곳: 내비 장소 검색 화면을 연다. 열었으면 true */
    suspend fun showPlaceSearch(keyword: String): Boolean
    /** 내비가 먼저 보내는 경로 변화. 조회 주기를 기다리지 않고 다시 읽는 데 쓴다 */
    val changes: Flow<NaviChange>
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
    /** TBT 이름으로 공공데이터 행을 찾는다. [heading]을 주면 반대 차로 행은 뺀다 */
    fun matchByName(name: String, kind: StopKind, lat: Double, lon: Double, maxStraightM: Double, heading: Float? = null): List<StopCandidate>
}

/** 후보까지의 실제 경로 거리·ETA·방향을 내비로 보정한다 (RouteOracle) */
interface TripEstimator {
    /** false면 내비에 묻지 않는다 (시연) */
    var enabled: Boolean
    suspend fun refine(c: StopCandidate, lat: Double, lon: Double, now: Long): StopCandidate
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

/** 엔진 만들기. 테스트는 고정 Random으로 재현 가능한 엔진을 넘긴다 */
fun interface EngineFactory {
    fun create(profile: TripProfile, memory: PersonalMemory): CoachEngine
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
