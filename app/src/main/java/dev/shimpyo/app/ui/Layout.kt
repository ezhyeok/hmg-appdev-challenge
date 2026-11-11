package dev.shimpyo.app.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shimpyo.app.ui.theme.Dimens

/**
 * 앱 윈도 크기에 따른 배치 (screens.md 0.1, pleos-design-tokens.md 3.5).
 * 폭 1000dp 이상이면 가로(2단), 높이 1000dp 미만이면 compact. 가로는 정보만 더하고 버튼 구성은 세로와 같다.
 */
@Immutable
data class LayoutSpec(val width: Dp, val height: Dp) {
    val landscape: Boolean get() = width >= Dimens.Window.landscapeBreakpoint
    val compact: Boolean get() = height < Dimens.Window.compactHeightBreakpoint

    /** 좌우 여백. 12.9"·WF처럼 좁은 세로 윈도(700 미만)는 24로 줄인다 */
    val margin: Dp
        get() = when {
            landscape -> Dimens.Layout.marginLandscape
            width < 700.dp -> Dimens.Space.l
            else -> Dimens.Layout.marginPortrait
        }

    /** 구역 사이 간격 */
    val gap: Dp get() = if (compact) Dimens.Drive.sectionGapCompact else Dimens.Drive.sectionGap

    /** 아래 여백 */
    val bottom: Dp get() = if (compact) Dimens.Drive.sectionGapCompact else Dimens.Layout.marginBottom

    /** Page Navigation 높이. 정차 160, 주행 중과 compact는 위아래 여백을 16으로 줄인 112 */
    fun headerHeight(driving: Boolean): Dp =
        if (driving || compact) Dimens.Drive.headerHeight else Dimens.Layout.pageNavigationHeight

    /** 카드 안쪽 여백 */
    val cardPadding: Dp get() = if (compact) Dimens.Gauge.cardPaddingCompact else Dimens.Gauge.cardPadding

    companion object {
        val Portrait17 = LayoutSpec(Dimens.Window.s17Portrait.width, Dimens.Window.s17Portrait.height)
    }
}

val LocalLayout = staticCompositionLocalOf { LayoutSpec.Portrait17 }
