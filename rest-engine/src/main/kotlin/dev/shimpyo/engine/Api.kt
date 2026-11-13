package dev.shimpyo.engine

// 엔진의 공개 계약. 앱(dev.shimpyo.app)이 이 타입들로 엔진과 주고받는다.
// 바꿀 때는 기본값이 있는 필드 추가만 한다 (앱 코드가 깨지지 않게).
// 단위: 시각은 epoch millis, 거리는 m, 소요 시간은 초, 게이지는 분.

// ---------- 탑승자와 여정 ----------

enum class OccupantType { ADULT, CHILD, INFANT, PREGNANT, ELDERLY, OAB, IBS, DOG }

data class Occupant(
    val id: String,
    val type: OccupantType,
    /** CHILD일 때 나이. 다른 유형은 무시 */
    val ageYears: Int? = null,
    /** 화면·음성에 쓰는 이름 (예: "첫째"). 비우면 유형 이름을 쓴다 */
    val label: String = "",
    /**
     * (v0.2 추가) 이 사람의 평소 화장실 간격(분). 정하면 유형별 사전값 대신 이 사람의 사전 중앙값으로 쓴다.
     * 정차 기록으로 계속 갱신된다. 모르면 null
     */
    val intervalOverrideMin: Double? = null,
)

data class TripProfile(
    val occupants: List<Occupant>,
    val tripStart: Long,
    /** 운전자가 입력한 지난밤 수면 시간. 모르면 null */
    val driverSleepHours: Float? = null,
    /** 탑승자 id → 출발 전 마지막 화장실 시각. 없으면 출발 시각으로 본다 */
    val lastRestroomAt: Map<String, Long> = emptyMap(),
    /**
     * (v0.2 추가) 운전자 기상 시각 (졸림 사전 곡선용). 모르면 null →
     * 그날 07:00으로 본다 (04~07시 출발이면 출발 1시간 전, 00~04시 출발이면 전날 07:00)
     */
    val driverWakeAt: Long? = null,
    /**
     * 엔진이 시작되기 전에 운전자가 이미 쉬지 않고 달린 시간(분). 휴식 리듬을 이만큼 채운 채 시작한다(시연, 주행 중 앱 재시작).
     * 0이면 엔진이 시작될 때부터 센다. 예전에는 시연의 '연속 95분'이 출발 시각에만 들어가 피로 게이지가 0분부터 셌다(2026-11-11)
     */
    val continuousDrivingMin: Double = 0.0,
)

// ---------- 정차 후보 ----------

enum class StopKind { HIGHWAY_REST_AREA, DROWSY_SHELTER, PUBLIC_RESTROOM, GAS_STATION, PUBLIC_PARKING, OTHER }

data class Facilities(
    val restroom: Boolean = true,
    val diaperTable: Boolean = false,
    val nursingRoom: Boolean = false,
    val emergencyBell: Boolean = false,
    val cctv: Boolean = false,
    val accessible: Boolean = false,
    val food: Boolean = false,
    val fuel: Boolean = false,
    val evCharger: Boolean = false,
    val petArea: Boolean = false,
    val open24h: Boolean = false,
)

data class StopCandidate(
    val id: String,
    val name: String,
    val kind: StopKind,
    val lat: Double,
    val lon: Double,
    val facilities: Facilities,
    /** 차를 세울 수 있는 곳인지 (주차장 없는 공중화장실은 false) */
    val parkable: Boolean,
    /** 지금 영업 중인지. 모르면 null */
    val openNow: Boolean? = null,
    /** 차에서 그곳까지 경로 거리. 모르면 null → 엔진이 직선거리로 추정 */
    val routeDistanceM: Double? = null,
    /**
     * 차에서 그곳까지 주행 시간. 내비가 그 후보까지 교통을 반영해 계산한 값(requestTrip의 ETA)만 넣고, 모르면 null.
     * 값이 있으면 엔진이 그대로 믿으므로 평균 속도로 만든 값을 넣으면 앞 정체를 무시하게 된다(2026-11-11 시연에서
     * 정체 뒤 죽암휴게소가 63분이 아닌 16분으로 계산돼 마지막 기회가 사라졌다). null이면 엔진이 정체 구간으로 계산하고,
     * 추천(Recommendation.stop)에는 엔진이 계산한 값을 채워 준다.
     */
    val etaS: Double? = null,
    /** 진행 방향에서 유턴 없이 갈 수 있는지. 모르면 null */
    val directionOk: Boolean? = null,
    /** 경로를 벗어나 들렀다 오는 추가 시간 (휴게소처럼 경로 위면 0) */
    val detourS: Double = 0.0,
    /** 현재 위치에서의 직선거리. 앱이 계산해 넣는다 */
    val straightDistanceM: Double? = null,
    /** (v0.2 추가) 차를 세운 곳에서 화장실까지 걷는 거리. 모르면 null → 급해 모드가 기본 도보 시간을 쓴다 */
    val walkDistanceM: Double? = null,
)

// ---------- 차량·경로 신호 ----------

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class TurnSignal { NONE, LEFT, RIGHT, HAZARD }

/** 1초 안팎 간격의 차량 신호 표본. 값이 없으면 null */
data class DrivingSample(
    val t: Long,
    val speedKph: Float? = null,
    val steeringDeg: Float? = null,
    val brake: Boolean? = null,
    val turnSignal: TurnSignal? = null,
    val cruiseActive: Boolean? = null,
)

data class CabinState(
    val hvacOn: Boolean? = null,
    val recirculation: Boolean? = null,
    val setTempC: Float? = null,
)

data class CongestionSegment(
    /** 차 위치에서 경로를 따라 정체 시작까지 거리 */
    val startDistanceM: Double,
    val lengthM: Double,
    /** 정체 구간을 지나는 데 걸리는 예상 시간 */
    val durationS: Double,
    val type: String = "",
    val roadName: String = "",
    val startName: String = "",
    val endName: String = "",
)

data class RouteState(
    val guiding: Boolean,
    val remainDistanceM: Double? = null,
    val remainTimeS: Double? = null,
    val congestion: List<CongestionSegment> = emptyList(),
    /** 다음 회전까지 거리 (묻는 타이밍 판단용) */
    val nextManeuverDistanceM: Double? = null,
    /**
     * 정차 후보를 이 경로 거리까지만 넣었다(목록을 잘랐을 때). 그 사이에는 넣은 것 말고 기회가 없고, 그 뒤는 모른다.
     * null이면 목적지까지 다 넣은 것. 예전에는 자른 목록의 마지막 기회 뒤를 목적지까지의 공백으로 봐서
     * '이후 2시간 34분 공백' 같은 틀린 마지막 기회가 나왔다(2026-11-11 에뮬레이터, CandidateHorizonTest가 지킨다)
     */
    val candidatesUntilM: Double? = null,
)

// ---------- 음성 의도 ----------

enum class Purpose { RESTROOM, REST, MEAL, FUEL, FEEDING, PET }

sealed interface VoiceIntent {
    /** "화장실 급해" */
    data object UrgentRestroom : VoiceIntent
    /** "좀 쉬고 싶어" */
    data object WantRest : VoiceIntent
    /** "졸려" */
    data object Sleepy : VoiceIntent
    /** "배고파" */
    data object Hungry : VoiceIntent
    /** "화장실까지 얼마나 남았어?" */
    data object HowLong : VoiceIntent
    data class Accept(val promptId: String) : VoiceIntent
    data class Decline(val promptId: String) : VoiceIntent
    /** "아이가 화장실 가고 싶대" 같은 탑승자 요청. 누구인지 모르면 occupantId = null */
    data class OccupantNeed(val occupantId: String?, val purpose: Purpose) : VoiceIntent
}

// ---------- 엔진 입력 ----------

data class CoachInput(
    val now: Long,
    val gear: Gear,
    val speedKph: Float? = null,
    /** 지난 update 이후 쌓인 표본 */
    val samples: List<DrivingSample> = emptyList(),
    /** 지난 update 이후 문이 열린 적이 있는지 */
    val doorOpened: Boolean = false,
    val cabin: CabinState = CabinState(),
    val route: RouteState? = null,
    /** 앱이 진행 방향으로 추린 후보 (경로 거리·시간을 아는 만큼 채움) */
    val candidates: List<StopCandidate> = emptyList(),
    val intent: VoiceIntent? = null,
    /** (v0.2 추가) 차의 남은 주행가능거리(km). 모르면 null → 피트스톱 계획에서 주유를 고려하지 않는다 */
    val rangeKm: Double? = null,
    /** (v0.2 추가) 사용자가 공조 자동 조작을 허용했는지. true면 FRESH_AIR를 묻지 않고 cabinAction만 낸다 */
    val cabinAutoAllowed: Boolean = false,
    /**
     * (문맥 보정 추가) 지금 자고 있다고 알려진 탑승자 id ("아이가 자요"). 매 update마다 지금 상태를 넣는다(깼거나 모르면 뺀다).
     * 엔진은 CHILD 탑승자만, 밤 시간대(CoachConfig.asleepNightStartHour~asleepNightEndHour)에만 화면 게이지를 늘려 잡는다.
     * 모르는 id와 아이가 아닌 탑승자는 무시한다. 판단 마감(마지막 기회·계획)은 바뀌지 않는다
     */
    val asleepOccupantIds: Set<String> = emptySet(),
    /**
     * (일상 확장) 차 가까이의 일상 정차 후보: 공중화장실, 화장실 있는 주유소, 화장실 가까운 공영주차장 (지금 연 곳, 진행 방향, 가까운 순).
     * [candidates]와 달리 고속도로의 화장실 공백 판단에는 쓰지 않는다(빠짐없는 목록이 아니고, 고속도로에서는 길가 화장실에 못 간다).
     * 경로 위 화장실 기회가 [CoachConfig.nearbyPreferRouteM] 안에 없을 때 다음 화장실, NOW 질문, "화장실 가고 싶대"의 답에 쓴다.
     * 앱은 고속도로 경로(길안내 TBT에 휴게소)나 빠른 주행에서는 비워 넣는다 (CityNearbyTest)
     */
    val nearby: List<StopCandidate> = emptyList(),
)

// ---------- 엔진 출력 ----------

enum class Level { OK, WATCH, SOON, NOW }

/** 화면·음성에 쓰는 짧은 이유. code는 테스트·로그용 고정 문자열 */
data class Reason(val code: String, val text: String)

data class Gauge(
    /** 남은 분. 계산할 수 없으면 null */
    val minutes: Int?,
    val level: Level,
    val reasons: List<Reason> = emptyList(),
    /** 이 게이지를 정한 탑승자 id (화장실 게이지) */
    val drivenBy: String? = null,
)

data class DrivingStyle(
    /** 0~1. 이번 주행 기준선 대비 편차. 기준선이 아직 없으면 null */
    val deviation: Double?,
    val baselineReady: Boolean,
    val reasons: List<Reason> = emptyList(),
    /** (v0.2 추가) 최근 5개 창 중 3개 이상이 크게 벗어났는지 ('편차 지속'). 피로 단계를 앞당기는 조건 */
    val persistent: Boolean = false,
)

data class Recommendation(
    val stop: StopCandidate,
    val purposes: Set<Purpose>,
    val reasons: List<Reason>,
)

/**
 * 질문 종류. DEPARTURE: 서 있고 목적지가 있을 때 "출발 전에 화장실 다녀오세요" (일상 주행, 정차지를 묻지 않는다.
 * 서 있는 동안에만 뜻이 있어 달리기 시작하면 거둔다)
 */
enum class PromptKind { LAST_CHANCE, REST_SUGGESTION, URGENT_RESULT, CHECK_IN, FRESH_AIR, INFO, DEPARTURE }

data class Prompt(
    val id: String,
    val kind: PromptKind,
    /** TTS로 읽을 문장 (한국어, 한 문장 + 질문) */
    val speech: String,
    val stop: StopCandidate? = null,
    val expiresAt: Long,
    /**
     * (추가) 사용자의 말에 대한 답인지 ("화장실 급해", "아이가 화장실 가고 싶대", "쉬고 싶어", "배고파", 주유, "얼마나 남았어").
     * 엔진이 먼저 건 질문이면 false. 엔진은 서 있는 동안(기어 P) 정차를 먼저 묻지 않지만 사용자의 말에는 서 있어도 답하므로,
     * 앱이 '달리는 동안에만 뜻이 있는 질문'을 거둘 때 이 값이 true인 것은 남겨야 그 답이 보인다
     */
    val userRequested: Boolean = false,
)

enum class CabinAction { FRESH_AIR, COOL_DOWN }

data class RankedStop(val stop: StopCandidate, val totalTimeS: Double, val why: List<Reason>)

data class PlannedStop(val stop: StopCandidate, val purposes: Set<Purpose>, val dwellMin: Int, val arriveInS: Double)

data class PitStopPlan(val stops: List<PlannedStop>, val totalStopMin: Int, val notes: List<Reason> = emptyList())

data class CoachOutput(
    val restroom: Gauge,
    val fatigue: Gauge,
    val style: DrivingStyle,
    val next: Recommendation? = null,
    /** 지금 말하거나 물어볼 것. 없으면 null */
    val prompt: Prompt? = null,
    /** 사용자가 허용했을 때 앱이 실행할 공조 동작 */
    val cabinAction: CabinAction? = null,
    /** 급해 모드 결과 (요청이 있었을 때만) */
    val urgent: List<RankedStop>? = null,
    /** 정차(기어 P) 중인지 */
    val parked: Boolean = false,
    /** (v0.2 추가) cabinAction의 이유 (예: 내기순환 30분째). 피로와 별개인 편의 기능이다 */
    val cabinReason: Reason? = null,
)

// ---------- 개인 학습 상태 ----------

data class PromptStat(val accepted: Int = 0, val declined: Int = 0)

/** 기기에만 저장하는 학습 상태. 앱이 JSON으로 저장·복원한다 */
data class PersonalMemory(
    /**
     * 탑승자 id → 관측된 화장실 간격(분), 최근 것부터 최대 N개.
     * 세 간격 기록 모두 문맥 보정(식사 뒤, 잠든 아이)이 걸린 구간은 기준 조건으로 환산한 값이다: 식사 뒤 96분은 120분으로 남는다.
     * 그래야 문맥이 개인 기준 간격을 한쪽으로 끌지 않는다 (docs/research/need-covariates.md 6장)
     */
    val restroomIntervalsMin: Map<String, List<Double>> = emptyMap(),
    /** "질문종류:거리구간" → 수락·거절 횟수 */
    val promptStats: Map<String, PromptStat> = emptyMap(),
    /**
     * (v0.2 추가) 탑승자 id → 중도절단 간격(분), 최근 것부터 최대 N개.
     * 그 사람 때문이 아닌 화장실 정차까지 '적어도 이만큼은 버텼다'는 기록이다.
     * 베이즈 갱신에서 노출 시간에만 더해져 게이지를 늘리는 쪽으로만 작용한다.
     */
    val restroomCensoredMin: Map<String, List<Double>> = emptyMap(),
    /**
     * (v0.2 추가) 탑승자 id → 앱이 권해서 들른 화장실 정차까지의 간격(분), 최근 것부터 최대 N개.
     * '필요해서'가 아니라 '권해서' 들렀을 수 있어 사건 0.5회로 센다 (ai-science.md 8-1)
     */
    val restroomSuggestedMin: Map<String, List<Double>> = emptyMap(),
)
