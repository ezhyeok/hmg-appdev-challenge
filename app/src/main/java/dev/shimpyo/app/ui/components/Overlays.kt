package dev.shimpyo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shimpyo.app.ui.RequestFocusOnce
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.DriveLimits
import dev.shimpyo.app.ui.theme.PleosType
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import kotlinx.coroutines.delay

/** 확인 팝업 내용. 파괴적 동작(기록 지우기 등)과 동의 확인에만 쓴다 */
@Immutable
data class PopupSpec(
    val title: String,
    val description: String? = null,
    val confirmText: String,
    val cancelText: String = "취소",
    val onConfirm: () -> Unit,
)

@Immutable
data class ToastSpec(val message: String, val id: Long)

/** 팝업과 토스트를 화면 맨 위 층에서 띄운다. 주행 제한이 켜지면 팝업은 닫힌다 */
@Stable
class OverlayState {
    var popup by mutableStateOf<PopupSpec?>(null)
        private set
    var toast by mutableStateOf<ToastSpec?>(null)
        private set
    private var nextId = 0L

    fun confirm(spec: PopupSpec) {
        popup = spec
    }

    fun dismissPopup() {
        popup = null
    }

    fun toast(message: String) {
        toast = ToastSpec(message, nextId++)
    }

    fun clearToast(id: Long) {
        if (toast?.id == id) toast = null
    }
}

val LocalOverlays = staticCompositionLocalOf { OverlayState() }

/**
 * System popup: 폭 808(세로 윈도에서는 윈도 폭 - 32), 반경 48, 안쪽 여백 48, 버튼 100 간격 24,
 * 바탕 background_popup, 뒤 overlay_default. 뒤를 누르면 닫힌다.
 *
 * CCP·키 포커스는 팝업 안에 가둔다: 열리면 [취소]에 포커스를 주고(파괴적 동작의 안전한 기본값), 회전해도 팝업 밖
 * (아래 화면의 버튼)으로 나가지 않는다. 뒤 덮개는 탭만 받고 포커스는 받지 않는다 (UI 감사 #13).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PopupLayer(state: OverlayState) {
    val spec = state.popup ?: return
    val c = ShimpyoTheme.colors
    val cancelFocus = remember { FocusRequester() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(c.overlayDefault)
            .pointerInput(state) { detectTapGestures { state.dismissPopup() } },
        contentAlignment = Alignment.Center,
    ) {
        val width = minOf(Dimens.Component.popupWidthMedium, maxWidth - 32.dp)
        val shape = RoundedCornerShape(Dimens.Radius.xl)
        Column(
            Modifier
                .widthIn(max = width)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .semantics { dialog() }
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .shadow(Dimens.Elevation.level3, shape)
                .clip(shape)
                .background(c.backgroundPopup)
                // 팝업 안을 누르면 닫히지 않게 탭을 여기서 먹는다 (포커스는 받지 않는다)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(Dimens.Component.popupPadding),
        ) {
            Text(spec.title, style = PleosType.TitleMedium, color = c.textPrimary)
            if (spec.description != null) {
                Spacer(Modifier.height(Dimens.Space.m))
                Text(spec.description, style = PleosType.BodyLarge, color = c.textSecondary)
            }
            Spacer(Modifier.height(Dimens.Space.xxxl))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Component.popupButtonGap)) {
                PleosButton(
                    spec.confirmText,
                    onClick = {
                        state.dismissPopup()
                        spec.onConfirm()
                    },
                    filled = true,
                    modifier = Modifier.weight(1f),
                )
                PleosButton(spec.cancelText, onClick = { state.dismissPopup() }, modifier = Modifier.weight(1f).focusRequester(cancelFocus))
            }
        }
    }
    RequestFocusOnce(cancelFocus, spec)
}

/**
 * 화면 위 알림 층. 터치는 받지 않는다(아래 버튼이 그대로 눌린다).
 * - Toast: 화면이 띄우는 한 줄 결과 알림. 2.5초 뒤 사라진다.
 * - 코치 알림([notice], CoachUiState.notice): 코치가 말 대신 화면에 보이라고 올린 문장(음성 안내를 껐을 때의 대답 등).
 *   코치가 몇 초 뒤 지우므로 화면은 시간을 재지 않고 있는 동안 보인다. 문구는 Restriction.noticeText가 주행 제한 글자 수로 자른 것.
 * 둘 다 가이드의 Toast 모양(바탕 static_dark_200, 반경 16, 최대 폭 792, 윈도 가장자리에서 24)이고 겹치지 않게 위아래로 쌓는다.
 * 기본은 아래 가운데(가이드). [topInset]을 주면 그만큼 아래(머리 밑)에 띄운다: 주행 화면에서 급해 버튼을 가리지 않게.
 */
@Composable
fun ToastLayer(state: OverlayState, topInset: Dp? = null, notice: String? = null) {
    val toast = state.toast
    if (toast == null && notice == null) return
    if (toast != null) {
        LaunchedEffect(toast.id) {
            delay(TOAST_MS)
            state.clearToast(toast.id)
        }
    }
    val placement = if (topInset != null) {
        Modifier.fillMaxSize().padding(start = Dimens.Layout.toastInset, end = Dimens.Layout.toastInset, top = topInset)
    } else {
        Modifier.fillMaxSize().padding(Dimens.Layout.toastInset)
    }
    Box(placement, contentAlignment = if (topInset != null) Alignment.TopCenter else Alignment.BottomCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.Space.xs)) {
            // 코치의 대답은 화면 낭독기에도 알린다 (말 대신 온 것이다)
            if (notice != null) Banner(notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (toast != null) Banner(toast.message)
        }
    }
}

/** Toast 모양의 알림 한 장. 길면 3줄까지(가이드: 문단 최대 3줄) */
@Composable
private fun Banner(text: String, modifier: Modifier = Modifier) {
    val c = ShimpyoTheme.colors
    val shape = RoundedCornerShape(Dimens.Radius.s)
    Box(
        modifier
            .widthIn(max = Dimens.Component.toastMaxWidth)
            .heightIn(min = Dimens.Component.toastMinHeight)
            .clip(shape)
            .background(c.toastContainer)
            .border(Dimens.Component.strokeWidth, c.toastStroke, shape)
            .padding(horizontal = Dimens.Component.toastPaddingHorizontal, vertical = Dimens.Component.toastPaddingVertical),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PleosType.BodyLarge, color = c.toastContent, textAlign = TextAlign.Center, maxLines = DriveLimits.MAX_LINES, overflow = TextOverflow.Ellipsis)
    }
}

private const val TOAST_MS = 2_500L
