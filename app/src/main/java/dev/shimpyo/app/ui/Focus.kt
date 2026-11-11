package dev.shimpyo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester

/**
 * CCP(로터리)·키 입력용 기본 포커스 (screens.md 1.7, 3.4). [key]가 바뀔 때마다 [requester]에 포커스를 준다:
 * 질문 버튼이 사라지거나(질문 끝) 결과가 바뀌면 포커스가 갈 곳을 잃지 않게 다시 정한다 (UI 감사 #13).
 * 터치 모드에서는 누를 수 있는 요소가 포커스를 받지 않으므로 터치 사용자에게는 링이 보이지 않는다.
 * 아직 붙지 않은 요청자(그리지 않은 버튼)면 조용히 넘어간다.
 */
@Composable
fun RequestFocusOnce(requester: FocusRequester, key: Any?) {
    LaunchedEffect(key) { runCatching { requester.requestFocus() } }
}
