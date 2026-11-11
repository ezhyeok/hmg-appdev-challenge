package dev.shimpyo.app.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.GoToOutcome
import dev.shimpyo.coach.GoToResult
import dev.shimpyo.coach.voice.Hangul

/**
 * 화면이 들고 있는 진행 중 상태. 창 분할·크기 변경·야간 모드로 액티비티가 다시 만들어져도 남는다([Saver]).
 * 예전에는 remember로만 들고 있어서 다시 만들어지면 '넣는 중'이 풀리고 1초 안에 경유지 요청이 또 갔다 (UI 감사 #14).
 *
 * @property urgentRequested 급해 요청 번호 (닫으면 null). 화면이 정한 번호는 양수, 코치가 음성으로 연 요청을 이어받으면 음수다
 * @property dismissedUrgent 닫은 급해 요청 번호들 (늦은 결과를 다시 띄우지 않는다, UrgentModel.rememberDismissed)
 * @property urgentResultsFor 옛 코치에서 결과 목록을 이미 보인 요청 (그 뒤 목록이 지워지면 끝난 것)
 * @property ackExpiredFor 코치 답을 기다리다 시간을 넘긴 요청 (다시 만들어져도 '찾는 중'으로 돌아가지 않게)
 * @property pickingId 급해 결과에서 고른 곳 (그 결과가 성공으로 오면 급해 화면을 닫는다)
 * @property goToSentId 화면이 방금 보낸 들르기(급해 고르기, 계획 화면, 다시 시도)의 장소. 코치가 받았다고 알리기(goToPending) 전의
 *   짧은 틈에 같은 요청을 또 보내지 않게 한다. 그 뒤의 진행은 코치 상태가 정한다 ([goToBusy])
 * @property seenGoToAt 이미 알린 들르기 결과의 시각 (다시 만들어질 때 같은 토스트를 또 띄우지 않게)
 * @property planRequestedGen 계획을 요청한 때의 CoachUiState.planGeneration (이보다 커지면 새 계획이 왔다)
 * @property planWanted 코치에 계획을 요청해 두었다 (MakePlan을 보냈고 아직 ClosePlan을 보내지 않았다). 계획 화면을 떠나면 닫는다
 */
@Stable
class ContentState(
    screen: Screen,
    demoReturn: Screen = Screen.SETTINGS,
    planRequested: Boolean = false,
    urgentRequested: Long? = null,
    dismissedUrgent: Set<Long> = emptySet(),
    urgentResultsFor: Long? = null,
    ackExpiredFor: Long? = null,
    pickingId: String? = null,
    goToSentId: String? = null,
    seenGoToAt: Long = 0L,
    planRequestedGen: Long = 0L,
    planWanted: Boolean = false,
) {
    var screen by mutableStateOf(screen)
    var demoReturn by mutableStateOf(demoReturn)
    var planRequested by mutableStateOf(planRequested)
    var urgentRequested by mutableStateOf(urgentRequested)
    var dismissedUrgent by mutableStateOf(dismissedUrgent)
    var urgentResultsFor by mutableStateOf(urgentResultsFor)
    var ackExpiredFor by mutableStateOf(ackExpiredFor)
    var pickingId by mutableStateOf(pickingId)
    var goToSentId by mutableStateOf(goToSentId)
    var seenGoToAt by mutableStateOf(seenGoToAt)
    var planRequestedGen by mutableStateOf(planRequestedGen)
    var planWanted by mutableStateOf(planWanted)

    companion object {
        /**
         * Bundle에 넣을 수 있는 값으로 저장한다. 없는 번호(null)는 빈 LongArray, 있는 번호는 한 칸짜리 LongArray다.
         * 예전에는 '번호는 0 이상'이라 보고 null을 -1로 적었다: 코치가 음성으로 연 요청은 음수 번호라서 다시 만들어지면
         * -1도 -2도 null로 돌아왔다 (닫힘 검증 UI-N4). 어떤 번호도 '없음'과 겹치지 않게 한다
         */
        val Saver: Saver<ContentState, Any> = listSaver(
            save = {
                listOf(
                    it.screen.name, it.demoReturn.name, it.planRequested,
                    box(it.urgentRequested), it.dismissedUrgent.toLongArray(), box(it.urgentResultsFor), box(it.ackExpiredFor),
                    it.pickingId.orEmpty(), it.goToSentId.orEmpty(), it.seenGoToAt, it.planRequestedGen, it.planWanted,
                )
            },
            restore = { v ->
                ContentState(
                    screen = Screen.valueOf(v[0] as String),
                    demoReturn = Screen.valueOf(v[1] as String),
                    planRequested = v[2] as Boolean,
                    urgentRequested = unbox(v[3]),
                    dismissedUrgent = (v[4] as LongArray).toSet(),
                    urgentResultsFor = unbox(v[5]),
                    ackExpiredFor = unbox(v[6]),
                    pickingId = (v[7] as String).ifEmpty { null },
                    goToSentId = (v[8] as String).ifEmpty { null },
                    seenGoToAt = v[9] as Long,
                    planRequestedGen = v[10] as Long,
                    planWanted = v[11] as Boolean,
                )
            },
        )

        private fun box(id: Long?): LongArray = if (id == null) LongArray(0) else longArrayOf(id)

        private fun unbox(saved: Any?): Long? = (saved as LongArray).firstOrNull()
    }
}

/**
 * 진행 중인 들르기(내비에 넣는 중). 화면은 이 값이 있는 동안 넣기 버튼을 막고 진행을 보인다.
 * @property name 코치가 알린 곳의 이름. 화면이 방금 보내 코치가 아직 알리기 전이면 null
 */
@Immutable
data class GoToBusy(val stopId: String, val name: String?)

/**
 * 들르기가 진행 중인지는 코치가 정한다 (CoachUiState.goToPending: 시작하면 생기고 결과를 내면 없어진다. 끝은 코치의 감시 타이머가
 * 보장한다). 화면은 따로 시간을 재지 않는다: 예전의 12초 제한은 에뮬레이터에서 19.5초 걸린 성공보다 먼저 '결과를 받지 못했어요'를
 * 띄우고 버튼을 다시 켜서, 그 사이 누른 다른 곳이 두 번째 경유지로 들어갔다 (닫힘 검증 UI-N2).
 * [sentId]는 화면이 방금 보낸 곳이다: 코치가 받았다고 알리기 전의 짧은 틈에도 바쁜 것으로 본다. 멈춘 코치에 남은 값은 믿지 않는다.
 */
fun goToBusy(state: CoachUiState, sentId: String?): GoToBusy? =
    state.goToPending?.takeIf { state.serviceRunning }?.let { GoToBusy(it.id, it.name) } ?: sentId?.let { GoToBusy(it, null) }

/** 진행 표시 문구: "안성휴게소를 내비에 넣는 중…" */
fun goToProgressText(busy: GoToBusy): String =
    busy.name?.let { "${Labels.withObject(shortStopName(it))} 내비에 넣는 중…" } ?: "내비에 넣는 중…"

/**
 * 들르기·안내 결과 토스트. 주행 화면 카드가 지금 그 결과를 보이고 있으면([shownOnCard], DriveModels.cardShowsGoTo) 띄우지 않는다.
 * 그 밖(계획 화면, 급해 화면, 질문에 가려진 실패, 추천과 다른 곳)은 한 줄로 알린다 (재검토 #10)
 */
fun goToToast(r: GoToResult, shownOnCard: Boolean): String? {
    if (shownOnCard) return null
    val name = shortStopName(r.stop.name)
    return when (r.outcome) {
        GoToOutcome.WAYPOINT_ADDED -> "${Hangul.eulReul(name)} 경유지로 넣었어요"
        GoToOutcome.ROUTE_STARTED -> "${Hangul.euRo(name)} 길안내를 시작했어요"
        GoToOutcome.ON_ROUTE -> "${Hangul.iGa(name)} 경로 위에 있어요. 진입로로 들어가세요"
        GoToOutcome.SEARCH_OPENED -> "내비에서 ${Hangul.eulReul(name)} 찾아 두었어요"
        GoToOutcome.FAILED -> "${Hangul.eulReul(name)} 넣지 못했어요. 다시 시도해 주세요"
    }
}
