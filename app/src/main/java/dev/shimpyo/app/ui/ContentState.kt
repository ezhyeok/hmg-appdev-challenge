package dev.shimpyo.app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.shimpyo.coach.GoToOutcome
import dev.shimpyo.coach.GoToResult
import dev.shimpyo.coach.voice.Hangul

/**
 * 화면이 들고 있는 진행 중 상태. 창 분할·크기 변경·야간 모드로 액티비티가 다시 만들어져도 남는다([Saver]).
 * 예전에는 remember로만 들고 있어서 다시 만들어지면 '넣는 중'이 풀리고 1초 안에 경유지 요청이 또 갔다 (UI 감사 #14).
 *
 * @property urgentRequested 급해 요청 번호 (닫으면 null)
 * @property dismissedUrgent 닫은 급해 요청 번호들 (늦은 결과를 다시 띄우지 않는다, UrgentModel.rememberDismissed)
 * @property urgentResultsFor 옛 코치에서 결과 목록을 이미 보인 요청 (그 뒤 목록이 지워지면 끝난 것)
 * @property ackExpiredFor 코치 답을 기다리다 시간을 넘긴 요청 (다시 만들어져도 '찾는 중'으로 돌아가지 않게)
 * @property pickingId 급해 결과에서 고르고 결과를 기다리는 곳
 * @property goToPendingId 계획 화면·다시 시도에서 내비에 넣고 결과를 기다리는 곳
 * @property seenGoToAt 이미 알린 들르기 결과의 시각 (다시 만들어질 때 같은 토스트를 또 띄우지 않게)
 * @property planRequestedGen 계획을 요청한 때의 CoachUiState.planGeneration (이보다 커지면 새 계획이 왔다)
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
    goToPendingId: String? = null,
    seenGoToAt: Long = 0L,
    planRequestedGen: Long = 0L,
) {
    var screen by mutableStateOf(screen)
    var demoReturn by mutableStateOf(demoReturn)
    var planRequested by mutableStateOf(planRequested)
    var urgentRequested by mutableStateOf(urgentRequested)
    var dismissedUrgent by mutableStateOf(dismissedUrgent)
    var urgentResultsFor by mutableStateOf(urgentResultsFor)
    var ackExpiredFor by mutableStateOf(ackExpiredFor)
    var pickingId by mutableStateOf(pickingId)
    var goToPendingId by mutableStateOf(goToPendingId)
    var seenGoToAt by mutableStateOf(seenGoToAt)
    var planRequestedGen by mutableStateOf(planRequestedGen)

    companion object {
        /** Bundle에 넣을 수 있게 null은 빈 값(-1, "")으로, 번호 묶음은 LongArray로 바꿔 저장한다. 요청 번호는 0 이상이다 */
        val Saver: Saver<ContentState, Any> = listSaver(
            save = {
                listOf(
                    it.screen.name, it.demoReturn.name, it.planRequested,
                    it.urgentRequested ?: -1L, it.dismissedUrgent.toLongArray(), it.urgentResultsFor ?: -1L, it.ackExpiredFor ?: -1L,
                    it.pickingId.orEmpty(), it.goToPendingId.orEmpty(), it.seenGoToAt, it.planRequestedGen,
                )
            },
            restore = { v ->
                ContentState(
                    screen = Screen.valueOf(v[0] as String),
                    demoReturn = Screen.valueOf(v[1] as String),
                    planRequested = v[2] as Boolean,
                    urgentRequested = (v[3] as Long).takeIf { it >= 0 },
                    dismissedUrgent = (v[4] as LongArray).toSet(),
                    urgentResultsFor = (v[5] as Long).takeIf { it >= 0 },
                    ackExpiredFor = (v[6] as Long).takeIf { it >= 0 },
                    pickingId = (v[7] as String).ifEmpty { null },
                    goToPendingId = (v[8] as String).ifEmpty { null },
                    seenGoToAt = v[9] as Long,
                    planRequestedGen = v[10] as Long,
                )
            },
        )
    }
}

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
