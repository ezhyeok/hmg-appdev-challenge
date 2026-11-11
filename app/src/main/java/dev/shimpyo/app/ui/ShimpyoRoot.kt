package dev.shimpyo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shimpyo.app.coach.CoachStore
import dev.shimpyo.app.ui.theme.ShimpyoTheme

/**
 * 화면 진입점 (MainActivity.setContent). 서비스가 1초마다 갱신하는 [CoachStore.state]와 화면이 스스로 구독한 주행 제한을
 * 화면 생명주기에 맞춰 모으고, 명령은 [CoachStore.send]로 보낸다. 판단은 모두 서비스(엔진)가 하고 화면은 그리기만 한다.
 * 화면 구성: docs/design/screens.md, 상태별 표시: docs/design/coach-layer/ui.md, 토큰: ui/theme/.
 */
@Composable
fun ShimpyoRoot() {
    val state by CoachStore.state.collectAsStateWithLifecycle()
    val ux = rememberUxRestrictions()
    val host = rememberAndroidHost()
    ShimpyoTheme {
        ShimpyoContent(state = state, host = host, restriction = driveRestriction(ux))
    }
}
