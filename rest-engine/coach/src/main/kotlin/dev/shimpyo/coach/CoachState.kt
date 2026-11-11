package dev.shimpyo.coach

import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.DrivingStyle
import dev.shimpyo.engine.Gauge
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.PitStopPlan
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.RankedStop
import dev.shimpyo.engine.Reason
import dev.shimpyo.engine.Recommendation
import dev.shimpyo.engine.StopCandidate

/*
 * 코치 계층이 화면에 내는 상태와 화면이 보내는 명령. 앱의 CoachStore가 이 상태를 들고 Compose가 그린다
 * (불변 data class라 app/compose-stability.conf에서 안정 타입으로 둔다).
 */

/** 음성 상태 (화면의 마이크 표시) */
enum class VoiceState { IDLE, SPEAKING, LISTENING, THINKING, UNAVAILABLE }

/** 정차 기록 한 건 (휴식 기록 화면) */
data class StopLog(
    val start: Long,
    val end: Long,
    val lat: Double?,
    val lon: Double?,
    /** 화장실 정차로 판정됐는지 (문 열림 + 3분 이상) */
    val restroom: Boolean,
    val name: String? = null,
)

/** 화면이 그리는 상태. 서비스가 1초마다 갱신한다 */
data class CoachUiState(
    val serviceRunning: Boolean = false,
    val restroom: Gauge? = null,
    val fatigue: Gauge? = null,
    val style: DrivingStyle? = null,
    val next: Recommendation? = null,
    /** 지금 대답을 기다리는 질문. 음성 대답이 없으면 화면 버튼으로 받는다 */
    val prompt: Prompt? = null,
    /** 떠 있는 질문의 장소를 지금 후보 목록에서 찾은 것 (거리가 매초 줄어든다). 질문 카드가 이 거리를 보여 준다 */
    val promptStop: StopCandidate? = null,
    /** 질문을 띄운 때의 추천 이유 (예: 정체 전 마지막). 질문 도중 엔진 추천이 바뀌어도 카드 표시는 그대로 둔다 */
    val promptReasons: List<Reason> = emptyList(),
    /** 들르기·안내 요청의 결과. 실패하면 화면이 다시 시도 버튼을 보인다 */
    val goToResult: GoToResult? = null,
    val urgent: List<RankedStop>? = null,
    /** 급해 화면의 진행 상태. requestId는 화면이 보낸 Urgent 명령의 번호 (닫은 뒤 늦게 온 결과를 버린다) */
    val urgentState: UrgentState = UrgentState.IDLE,
    val urgentRequestId: Long = 0,
    val plan: PitStopPlan? = null,
    /**
     * 계획을 새로 세울 때마다 1씩 오르는 번호 (MakePlan에 답해 새로 세웠는지 화면이 안다). 지나친 정차를 빼거나 거리를 고쳐
     * 같은 계획을 다시 낼 때는 올리지 않는다(그때마다 '계획을 세우는 중'이 풀리면 안 된다). 0이면 이 번호를 쓰지 않는 코치다.
     * (2026-11-12 UI 계약 추가)
     */
    val planGeneration: Long = 0,
    val parked: Boolean = true,
    /**
     * 주행 중 UX 제한. 모르면 제한으로 본다(서비스 시작 전·권한 창·Car 서비스 오류에도 주차 중 화면이 열리지 않게).
     * 2026-11-11 UI 감사: 기본값이 false여서 시작 직후 주행 중에도 메뉴·설정이 보였다
     */
    val drivingRestricted: Boolean = true,
    val speedKph: Float? = null,
    val guiding: Boolean = false,
    val destinationName: String? = null,
    val remainDistanceM: Double? = null,
    val remainTimeS: Double? = null,
    val congestion: List<CongestionSegment> = emptyList(),
    /** 경로 앞쪽 화장실 기회 (계획 화면 타임라인). 매초 달린 만큼 거리를 줄여 올린다 */
    val ahead: List<StopCandidate> = emptyList(),
    /** 앞쪽 후보를 아직 못 구한 이유 (빈 목록을 '화장실 없음'으로 보이지 않게) */
    val candidatesState: CandidatesState = CandidatesState.LOADING_DATA,
    /** 차량이 내기·외기 전환을 지원하는지 (자동 환기 설정과 버튼을 켤지) */
    val cabinSupported: Boolean = false,
    val occupants: List<Occupant> = emptyList(),
    val lastStop: StopLog? = null,
    val history: List<StopLog> = emptyList(),
    val voice: VoiceState = VoiceState.IDLE,
    /** 음성 출력·인식을 쓸 수 있는지 (연결 실패면 false). 설정의 음성 끄기와는 별개다 */
    val ttsAvailable: Boolean = false,
    val sttAvailable: Boolean = false,
    val lastHeard: String? = null,
    /** 화면 아래 한 줄 상태 (예: "휴게소 데이터 1,203곳 불러옴") */
    val status: String? = null,
    /** 시연 모드 이름. 꺼져 있으면 null */
    val demo: String? = null,
    /** LLM 사용 가능 여부와 종류 (예: "Pleos LLM (온디바이스)") */
    val llm: String? = null,
)

/** 급해 화면 진행 상태 */
enum class UrgentState { IDLE, LOCATING, SEARCHING, READY, NO_LOCATION, NO_RESULT }

/** 앞쪽 후보 목록 상태 */
enum class CandidatesState {
    /** 정차 후보 표를 읽는 중 */
    LOADING_DATA,
    /** 위치를 아직 모른다 */
    NO_LOCATION,
    /** 목록이 최신이다 (비어 있으면 정말 앞쪽에 없다) */
    READY,
}

enum class GoToOutcome {
    WAYPOINT_ADDED,
    ROUTE_STARTED,
    /** 좌표 없는 경로 위 시설(TBT): 내비 경로가 이미 지나가므로 진입로로 들어가면 된다 */
    ON_ROUTE,
    /** 정차 중: 내비 장소 검색을 열었다 */
    SEARCH_OPENED,
    FAILED,
}

/** at: 코치 시각 */
data class GoToResult(val stop: StopCandidate, val outcome: GoToOutcome, val at: Long)

/** 화면 → 서비스 명령 */
sealed interface UiCommand {
    /** 급해: requestId는 화면이 고른 번호 (닫기와 늦은 결과를 짝짓는다) */
    data class Urgent(val requestId: Long) : UiCommand
    /** 듣기 시작. 말하거나 듣는 중이면 멈춘다 */
    data object Listen : UiCommand
    data object StopVoice : UiCommand
    data object MakePlan : UiCommand
    data class Answer(val promptId: String, val accept: Boolean) : UiCommand
    data class GoTo(val stop: StopCandidate) : UiCommand
    data class SetOccupants(val occupants: List<Occupant>) : UiCommand
    /** 말로 탑승자 설정 ("아이 둘이랑 강아지 타") */
    data object DescribeOccupants : UiCommand
    data class Demo(val scenario: String?) : UiCommand
    data class DismissUrgent(val requestId: Long) : UiCommand
    data object DismissGoToResult : UiCommand
    /** 권한을 새로 받았다 (위치·차속 등). 서비스가 신호를 다시 연결한다 */
    data object PermissionsChanged : UiCommand
    /** 정차 기록과 개인 학습 상태를 지운다 (설정 > 기록 지우기) */
    data object ClearRecords : UiCommand
    /** 설정(수면 시간 등)이 바뀌어 엔진 프로필을 다시 만든다. 여정은 이어진다 */
    data object ProfileChanged : UiCommand
}
