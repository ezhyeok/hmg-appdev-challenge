package dev.shimpyo.coach

import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.DrivingStyle
import dev.shimpyo.engine.Gauge
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.PitStopPlan
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.RankedStop
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
    val urgent: List<RankedStop>? = null,
    val plan: PitStopPlan? = null,
    val parked: Boolean = true,
    val drivingRestricted: Boolean = false,
    val speedKph: Float? = null,
    val guiding: Boolean = false,
    val destinationName: String? = null,
    val remainDistanceM: Double? = null,
    val remainTimeS: Double? = null,
    val congestion: List<CongestionSegment> = emptyList(),
    /** 경로 앞쪽 화장실 기회 (계획 화면 타임라인) */
    val ahead: List<StopCandidate> = emptyList(),
    val occupants: List<Occupant> = emptyList(),
    val lastStop: StopLog? = null,
    val history: List<StopLog> = emptyList(),
    val voice: VoiceState = VoiceState.IDLE,
    val lastHeard: String? = null,
    /** 화면 아래 한 줄 상태 (예: "휴게소 데이터 1,203곳 불러옴") */
    val status: String? = null,
    /** 시연 모드 이름. 꺼져 있으면 null */
    val demo: String? = null,
    /** LLM 사용 가능 여부와 종류 (예: "Pleos LLM (온디바이스)") */
    val llm: String? = null,
)

/** 화면 → 서비스 명령 */
sealed interface UiCommand {
    data object Urgent : UiCommand
    data object Listen : UiCommand
    data object MakePlan : UiCommand
    data class Answer(val promptId: String, val accept: Boolean) : UiCommand
    data class GoTo(val stop: StopCandidate) : UiCommand
    data class SetOccupants(val occupants: List<Occupant>) : UiCommand
    /** 말로 탑승자 설정 ("아이 둘이랑 강아지 타") */
    data object DescribeOccupants : UiCommand
    data class Demo(val scenario: String?) : UiCommand
    data object DismissUrgent : UiCommand
    /** 정차 기록과 개인 학습 상태를 지운다 (설정 > 기록 지우기) */
    data object ClearRecords : UiCommand
    /** 설정(수면 시간 등)이 바뀌어 엔진 프로필을 다시 만든다. 여정은 이어진다 */
    data object ProfileChanged : UiCommand
}
