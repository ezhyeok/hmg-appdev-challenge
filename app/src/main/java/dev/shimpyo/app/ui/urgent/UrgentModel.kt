package dev.shimpyo.app.ui.urgent

import androidx.compose.runtime.Immutable
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.UrgentState
import dev.shimpyo.engine.RankedStop
import java.util.concurrent.atomic.AtomicLong

/*
 * 급해 화면의 상태. 화면이 요청 번호를 정해 UiCommand.Urgent(requestId)로 보내고, 코치는 그 번호와 진행 상태
 * (CoachUiState.urgentRequestId, urgentState)를 돌려준다. 닫을 때는 DismissUrgent(requestId)를 보내고, 닫은 번호의
 * 늦은 결과는 다시 띄우지 않는다 (UI 감사 #7: 찾는 중에 닫아도 결과가 다시 나타나고 TTS가 물었다).
 * 위치가 없으면 코치가 NO_LOCATION을 준다: 화면이 8초 뒤 '위치를 확인할 수 없어요'를 지어내지 않는다 (UI 감사 #8).
 */

/** 급해 화면이 보여 줄 단계 */
enum class UrgentStatus {
    /** 위치를 기다린다 (코치가 받았거나, 아직 받았다는 답이 없다) */
    LOCATING,
    SEARCHING,
    READY,
    NO_LOCATION,
    NO_RESULT,
    /**
     * 요청 뒤 [URGENT_ACK_TIMEOUT_MS] 동안 아무 진행이 없다. 코치가 응답하지 않았거나, 옛 코치가 위치를 몰라 결과 없이 끝냈다
     * (요청을 받기 전의 코치 상태(번호 0, IDLE)는 둘이 같아서 구분할 수 없다): 어느 쪽이든 맞는 말로 알린다
     */
    NO_RESPONSE,
    /** 코치 서비스가 돌지 않는다 */
    SERVICE_OFF,
}

@Immutable
data class UrgentView(val requestId: Long, val status: UrgentStatus, val results: List<RankedStop>)

/** 요청을 보낸 뒤 코치의 답(같은 번호)을 기다리는 시간. 코치는 받자마자(다음 틱) 답한다 */
const val URGENT_ACK_TIMEOUT_MS = 8_000L

/** 화면 요청 번호. 프로세스 안에서 겹치지 않고 0이 아니다(0은 번호를 돌려주지 않는 옛 코치의 값) */
object UrgentRequestIds {
    private val last = AtomicLong(System.currentTimeMillis())
    fun next(): Long = last.incrementAndGet()
}

/**
 * 옛 CoachService는 요청 번호를 돌려주지 않는다(urgentRequestId = 0, urgentState = IDLE). 그때는 urgent 목록이
 * 지금 요청의 결과다. 코치 계층(CoachCore)이 들어오면 늘 번호를 돌려주므로 이 갈래는 쓰이지 않는다.
 */
private fun CoachUiState.legacy(): Boolean = urgentRequestId == 0L

/** 코치가 [id] 요청을 아직 진행 중인지 (화면을 닫아도 코치가 늦게 결과를 낼 수 있는 동안) */
fun coachUrgentActive(id: Long, state: CoachUiState): Boolean =
    if (state.legacy()) id == 0L && state.urgent != null else state.urgentRequestId == id && state.urgentState != UrgentState.IDLE

/**
 * 닫은 번호를 언제 잊을지. 번호는 다시 쓰이지 않으므로 계속 들고 있는다: 일찍 잊으면 코치가 Urgent를 늦게 받아
 * 그 번호로 진행을 알릴 때 닫은 화면이 다시 열린다(닫기 → 코치는 아직 앞 번호 → 잊음 → 코치가 우리 번호로 LOCATING).
 * 옛 코치만 번호 0을 늘 다시 쓰므로 목록이 지워지면(그 요청이 끝나면) 잊는다.
 */
fun settleDismissed(dismissed: Long?, state: CoachUiState): Long? = when (dismissed) {
    null -> null
    0L -> dismissed.takeIf { state.legacy() && state.urgent != null }
    else -> dismissed
}

/**
 * @param requested 화면이 보내고 아직 닫지 않은 요청 번호
 * @param dismissed 마지막으로 닫은 요청 번호
 * @param ackExpired [requested]를 보낸 뒤 답 없이 [URGENT_ACK_TIMEOUT_MS]가 지났다
 * @param picking 결과에서 고른 곳 (옛 코치는 경유지를 넣으면 목록을 지운다: 고른 뒤 목록이 사라지면 닫는다)
 * @return null이면 급해 화면을 닫는다. 코치가 먼저 연 요청(음성 "화장실 급해")도 여기서 열린다
 */
fun urgentView(requested: Long?, dismissed: Long?, state: CoachUiState, ackExpired: Boolean, picking: String?): UrgentView? {
    val legacy = state.legacy()
    val coachId = state.urgentRequestId
    val coachOpen = coachUrgentActive(coachId, state) && coachId != dismissed
    val id = when {
        requested != null && !legacy && coachId == requested -> requested
        // 코치가 다른 번호로 진행 중이면(음성 요청, 또는 아직 처리하지 않은 앞 요청) 그것을 보인다: 같은 목적의 최신 결과다
        coachOpen -> coachId
        requested != null -> requested
        else -> return null
    }
    if (!state.serviceRunning) return UrgentView(id, UrgentStatus.SERVICE_OFF, emptyList())
    if (legacy) {
        val r = state.urgent
        val status = when {
            r != null -> if (r.isEmpty()) UrgentStatus.NO_RESULT else UrgentStatus.READY
            picking != null -> return null
            ackExpired -> UrgentStatus.NO_RESPONSE
            else -> UrgentStatus.LOCATING
        }
        return UrgentView(id, status, r.orEmpty())
    }
    if (coachId != id) return UrgentView(id, if (ackExpired) UrgentStatus.NO_RESPONSE else UrgentStatus.LOCATING, emptyList())
    val results = state.urgent.orEmpty()
    val status = when (state.urgentState) {
        // 코치가 우리 요청을 끝냈다 (경유지를 넣었거나 닫았다)
        UrgentState.IDLE -> return null
        UrgentState.LOCATING -> UrgentStatus.LOCATING
        UrgentState.SEARCHING -> UrgentStatus.SEARCHING
        UrgentState.READY -> if (results.isEmpty()) UrgentStatus.NO_RESULT else UrgentStatus.READY
        UrgentState.NO_LOCATION -> UrgentStatus.NO_LOCATION
        UrgentState.NO_RESULT -> UrgentStatus.NO_RESULT
    }
    return UrgentView(id, status, if (status == UrgentStatus.READY) results else emptyList())
}
