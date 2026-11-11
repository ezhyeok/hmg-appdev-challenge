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
 * @property urgentDismissed 마지막으로 닫은 급해 요청 번호 (늦은 결과를 다시 띄우지 않는다)
 * @property pickingId 급해 결과에서 고르고 결과를 기다리는 곳
 * @property goToPendingId 계획 화면·다시 시도에서 내비에 넣고 결과를 기다리는 곳
 * @property seenGoToAt 이미 알린 들르기 결과의 시각 (다시 만들어질 때 같은 토스트를 또 띄우지 않게)
 */
@Stable
class ContentState(
    screen: Screen,
    demoReturn: Screen = Screen.SETTINGS,
    planRequested: Boolean = false,
    urgentRequested: Long? = null,
    urgentDismissed: Long? = null,
    pickingId: String? = null,
    goToPendingId: String? = null,
    seenGoToAt: Long = 0L,
) {
    var screen by mutableStateOf(screen)
    var demoReturn by mutableStateOf(demoReturn)
    var planRequested by mutableStateOf(planRequested)
    var urgentRequested by mutableStateOf(urgentRequested)
    var urgentDismissed by mutableStateOf(urgentDismissed)
    var pickingId by mutableStateOf(pickingId)
    var goToPendingId by mutableStateOf(goToPendingId)
    var seenGoToAt by mutableStateOf(seenGoToAt)

    companion object {
        /** Bundle에 넣을 수 있게 null은 빈 값(-1, "")으로 바꿔 저장한다. 요청 번호는 늘 양수다 */
        val Saver: Saver<ContentState, Any> = listSaver(
            save = {
                listOf(
                    it.screen.name, it.demoReturn.name, it.planRequested,
                    it.urgentRequested ?: -1L, it.urgentDismissed ?: -1L,
                    it.pickingId.orEmpty(), it.goToPendingId.orEmpty(), it.seenGoToAt,
                )
            },
            restore = { v ->
                ContentState(
                    screen = Screen.valueOf(v[0] as String),
                    demoReturn = Screen.valueOf(v[1] as String),
                    planRequested = v[2] as Boolean,
                    urgentRequested = (v[3] as Long).takeIf { it >= 0 },
                    urgentDismissed = (v[4] as Long).takeIf { it >= 0 },
                    pickingId = (v[5] as String).ifEmpty { null },
                    goToPendingId = (v[6] as String).ifEmpty { null },
                    seenGoToAt = v[7] as Long,
                )
            },
        )
    }
}

/**
 * 들르기·안내 결과를 토스트로 알릴지. 주행 화면 카드가 이미 보여 주는 결과(실패 카드, 지금 추천과 같은 곳의 성공)는
 * 토스트를 띄우지 않는다. 그 밖(계획 화면, 급해 화면, 추천과 다른 곳)은 한 줄로 알린다
 */
fun goToToast(r: GoToResult, onDriveCard: Boolean, nextStopId: String?): String? {
    if (onDriveCard && (r.outcome == GoToOutcome.FAILED || r.stop.id == nextStopId)) return null
    val name = shortStopName(r.stop.name)
    return when (r.outcome) {
        GoToOutcome.WAYPOINT_ADDED -> "${Hangul.eulReul(name)} 경유지로 넣었어요"
        GoToOutcome.ROUTE_STARTED -> "${Hangul.euRo(name)} 길안내를 시작했어요"
        GoToOutcome.ON_ROUTE -> "${Hangul.iGa(name)} 경로 위에 있어요. 진입로로 들어가세요"
        GoToOutcome.SEARCH_OPENED -> "내비에서 ${Hangul.eulReul(name)} 찾아 두었어요"
        GoToOutcome.FAILED -> "${Hangul.eulReul(name)} 넣지 못했어요. 다시 시도해 주세요"
    }
}
