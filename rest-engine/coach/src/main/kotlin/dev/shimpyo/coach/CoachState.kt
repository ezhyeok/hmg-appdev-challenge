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
    /**
     * 내비에 넣는 중인 곳 (들르기를 시작해 결과를 기다리는 동안). 화면은 이 값이 있는 동안 진행 표시를 하고 버튼을 막는다.
     * 화면이 따로 시간을 재지 않는다: 에뮬레이터에서 내비 확인이 19.5초 걸렸는데 화면의 12초 제한이 먼저 '결과 없음'을 띄웠다
     * (2026-11-12 UI 검증 N2). 끝은 코치의 감시 타이머가 보장한다
     */
    val goToPending: StopCandidate? = null,
    /**
     * 말 대신 화면에 잠깐 보일 알림 (음성 안내를 껐을 때의 대답, 못 알아들었을 때 등). 코치가 몇 초 뒤 지운다.
     * (2026-11-12 리뷰 R-F4: 음성 안내를 끄면 '얼마나 남았어'의 대답이 말로도 화면으로도 나오지 않았다)
     */
    val notice: String? = null,
    val urgent: List<RankedStop>? = null,
    /** 급해 화면의 진행 상태. requestId는 화면이 보낸 Urgent 명령의 번호 (닫은 뒤 늦게 온 결과를 버린다) */
    val urgentState: UrgentState = UrgentState.IDLE,
    val urgentRequestId: Long = 0,
    val plan: PitStopPlan? = null,
    /**
     * 계획을 새로 세울 때마다 1씩 오르는 번호 (MakePlan에 답해 새로 세웠는지 화면이 안다). 지나친 정차를 빼거나 거리를 고쳐
     * 같은 계획을 다시 낼 때는 올리지 않는다(그때마다 '계획을 세우는 중'이 풀리면 안 된다). 0이면 이 번호를 쓰지 않는 코치다.
     * 1부터가 아니라 코치를 만든 때(경과 시간)에서 시작한다: 서비스가 다시 떠도 앞 코치가 남긴 번호와 겹치지 않는다. 화면은
     * 요청할 때의 번호와 달라졌는지만 본다. (2026-11-12 UI 계약 추가)
     */
    val planGeneration: Long = 0,
    val parked: Boolean = true,
    /**
     * 주행 중 UX 제한. 모르면 제한으로 본다(서비스 시작 전·권한 창·Car 서비스 오류에도 주차 중 화면이 열리지 않게).
     * 2026-11-11 UI 감사: 기본값이 false여서 시작 직후 주행 중에도 메뉴·설정이 보였다
     */
    val drivingRestricted: Boolean = true,
    val speedKph: Float? = null,
    /** 차에서 읽은 주행가능거리. 예비 거리를 빼기 전이며 모르면 화면에서 숨긴다. */
    val rangeKm: Double? = null,
    val energy: dev.shimpyo.engine.EnergyKind? = null,
    val guiding: Boolean = false,
    val destinationName: String? = null,
    val remainDistanceM: Double? = null,
    val remainTimeS: Double? = null,
    val congestion: List<CongestionSegment> = emptyList(),
    val congestionBasisKnown: Boolean = false,
    /** 경로 앞쪽 화장실 기회 (계획 화면 타임라인). 매초 달린 만큼 거리를 줄여 올린다 */
    val ahead: List<StopCandidate> = emptyList(),
    /** 앞쪽 후보를 아직 못 구한 이유 (빈 목록을 '화장실 없음'으로 보이지 않게) */
    val candidatesState: CandidatesState = CandidatesState.LOADING_DATA,
    /** 차량이 내기·외기 전환을 지원하는지 (자동 환기 설정과 버튼을 켤지) */
    val cabinSupported: Boolean = false,
    val occupants: List<Occupant> = emptyList(),
    val lastStop: StopLog? = null,
    val history: List<StopLog> = emptyList(),
    /**
     * 코치 시각(시연이면 가상 시각)을 분 단위로 내린 값. 기록 화면의 '몇 분 전'은 이것으로 센다: 정차 기록의 시각이 코치 시각이기
     * 때문이다. 예전에는 실제 시계를 써서 오후 2시 반에 12:40 시연으로 방금 끝낸 정차가 "1시간 27분 전"으로 보였다 (2026-11-13 영상 녹화).
     * 분이 바뀔 때만 바뀌어 화면을 매초 다시 그리게 하지 않는다. 첫 틱 전에는 null
     */
    val clockMinuteMs: Long? = null,
    val voice: VoiceState = VoiceState.IDLE,
    /** 음성 출력·인식을 쓸 수 있는지 (연결 실패면 false). 설정의 음성 끄기와는 별개다 */
    val ttsAvailable: Boolean = false,
    val sttAvailable: Boolean = false,
    val lastHeard: String? = null,
    /** 화면 아래 한 줄 상태 (예: "휴게소 데이터 1,203곳 불러옴") */
    val status: String? = null,
    /** 시연 모드 이름. 꺼져 있으면 null */
    val demo: String? = null,
    /**
     * 운전자가 '자는 중'이라고 알려 준 탑승자 id (말 또는 탑승자 화면). 밤에 잠든 아이는 화장실 게이지에 여유를 더 둔다
     * (엔진 CoachInput.asleepOccupantIds, docs/research/need-covariates.md). 아이(CHILD)만 들어간다. 깼다고 하거나, 화장실 정차가
     * 있거나, 아침 7시가 지나거나, 그 탑승자가 빠지거나, 시연을 켜고 끄면 코치가 뺀다 (AsleepMarks)
     */
    val asleepOccupantIds: Set<String> = emptySet(),
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

/**
 * 들르기가 실패한 까닭 중 운전자가 할 수 있는 일이 다른 것. 그 밖의 실패(내비가 답하지 않음 등)는 null이다.
 * (2026-11-12 에뮬레이터: Pleos 내비는 경유지를 둘까지만 받고 셋째는 말없이 버린다. '다시 눌러 주세요'로는 해결되지 않는다)
 */
enum class GoToFailure {
    /** 내비 경유지가 이미 둘이다 */
    WAYPOINTS_FULL,
    /** 밖에서 넣은 경유지(좌표를 모른다)보다 가까워 순서를 맞출 수 없다 (뒤에 붙이면 지나쳐 갔다 돌아온다) */
    AHEAD_OF_OTHER_WAYPOINT,
}

/** at: 코치 시각. failure: outcome이 FAILED일 때 그 까닭 (모르면 null) */
data class GoToResult(val stop: StopCandidate, val outcome: GoToOutcome, val at: Long, val failure: GoToFailure? = null)

/** 화면 → 서비스 명령 */
sealed interface UiCommand {
    /** 급해: requestId는 화면이 고른 번호 (닫기와 늦은 결과를 짝짓는다) */
    data class Urgent(val requestId: Long) : UiCommand
    /** 듣기 시작. 말하거나 듣는 중이면 멈춘다 */
    data object Listen : UiCommand
    data object StopVoice : UiCommand
    data object MakePlan : UiCommand
    /** 계획 화면을 떠났다 (또는 주행을 시작했다): 계획을 더 고쳐 세우지 않는다. 마지막 계획은 상태에 남는다 */
    data object ClosePlan : UiCommand
    data class Answer(val promptId: String, val accept: Boolean) : UiCommand
    data class GoTo(val stop: StopCandidate) : UiCommand
    data class SetOccupants(val occupants: List<Occupant>) : UiCommand
    /** 탑승자를 '자는 중'으로 표시하거나 푼다. 아이(CHILD)가 아니면 코치가 무시한다 */
    data class SetAsleep(val occupantId: String, val asleep: Boolean) : UiCommand
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
