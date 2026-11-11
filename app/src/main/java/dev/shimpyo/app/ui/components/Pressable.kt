package dev.shimpyo.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.ShimpyoMotion
import dev.shimpyo.app.ui.theme.ShimpyoTheme

/**
 * 가이드의 눌림 동작: 누르면 95%로 줄고(0.1초) 바탕색이 바뀐다. 손가락이 영역을 벗어나면 취소, 안에서 떼면 실행.
 * 물결(ripple) 대신 축소를 쓰고, 누를 때 햅틱을 준다. CCP 포커스를 받으면 4dp 링을 요소 바깥에 그린다.
 * (pleos-design-tokens.md 6장, 7.7)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    interaction: MutableInteractionSource,
    enabled: Boolean = true,
    cornerRadius: Dp = Dimens.Radius.l,
    role: Role? = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) ShimpyoMotion.PRESSED_SCALE else 1f,
        animationSpec = tween(ShimpyoMotion.DURATION_SHORT_1, easing = ShimpyoMotion.Easing),
        label = "pressScale",
    )
    val haptic = LocalHapticFeedback.current
    val ring = ShimpyoTheme.colors.focusRing
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .focusRing(focused && enabled, ring, cornerRadius)
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onLongClick = onLongClick?.let { long ->
                {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    long()
                }
            },
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        )
}

/** CCP 포커스 링: 4dp, 요소 바깥 4dp, 반경 = 요소 반경 + 4 (screens.md 0.2) */
fun Modifier.focusRing(visible: Boolean, color: Color, cornerRadius: Dp): Modifier =
    if (!visible) {
        this
    } else {
        drawWithContent {
            drawContent()
            val w = Dimens.Component.focusRingWidth.toPx()
            val off = w * 1.5f
            drawRoundRect(
                color = color,
                topLeft = Offset(-off, -off),
                size = Size(size.width + off * 2, size.height + off * 2),
                cornerRadius = CornerRadius(cornerRadius.toPx() + off),
                style = Stroke(width = w),
            )
        }
    }
