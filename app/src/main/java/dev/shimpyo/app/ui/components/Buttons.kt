package dev.shimpyo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.DriveType
import dev.shimpyo.app.ui.theme.PleosButtonType
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.coach.VoiceState

/** Basic button 크기 [가이드: basic_size 도식] — 높이 100/80/64, 반경 32/24/16, 아이콘 48/40/32 */
enum class ButtonSize(val height: Dp, val radius: Dp, val paddingH: Dp, val icon: Dp) {
    Large(Dimens.Button.heightLarge, Dimens.Radius.l, Dimens.Button.paddingHorizontalLarge, Dimens.Button.iconLarge),
    Medium(Dimens.Button.heightMedium, Dimens.Radius.m, Dimens.Button.paddingHorizontalMedium, Dimens.Button.iconMedium),
    Small(Dimens.Button.heightSmall, Dimens.Radius.s, Dimens.Button.paddingHorizontalSmall, Dimens.Button.iconSmall),
    ;

    val label: TextStyle
        get() = when (this) {
            Large -> PleosButtonType.Large
            Medium -> PleosButtonType.Medium
            Small -> PleosButtonType.Small
        }
}

/**
 * Pleos Basic/Filled 버튼. Filled는 화면의 주 행동 하나에만 쓴다.
 * 주행 화면에서는 [labelStyle]에 DriveType.ActionLabel(40)을 넘긴다.
 */
@Composable
fun PleosButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    size: ButtonSize = ButtonSize.Large,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    labelStyle: TextStyle = size.label,
) {
    val c = ShimpyoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        !enabled -> if (filled) c.buttonFilledDisabled else c.buttonBasicDisabled
        pressed -> if (filled) c.buttonFilledPressed else c.buttonBasicPressed
        else -> if (filled) c.buttonFilledEnabled else c.buttonBasicEnabled
    }
    val fg = when {
        !enabled -> c.textQuaternary
        filled -> c.textOnFilledPrimary
        else -> c.textPrimary
    }
    Row(
        modifier = modifier
            .heightIn(min = size.height)
            .pressable(interaction, enabled = enabled, cornerRadius = size.radius, onClick = onClick)
            .clip(RoundedCornerShape(size.radius))
            .background(bg)
            .padding(horizontal = size.paddingH),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(size.icon), tint = fg)
            Spacer(Modifier.width(Dimens.Button.iconLabelGap))
        }
        Text(text, style = labelStyle, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Icon button (Basic) 80×80, 반경 20, 아이콘 48. [active]이면 고정 링(움직이지 않음)을 두른다 */
@Composable
fun PleosIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color = ShimpyoTheme.colors.iconPrimary,
) {
    val c = ShimpyoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(Dimens.Radius.iconButton)
    Box(
        modifier = modifier
            .size(Dimens.Button.iconButton)
            .semantics { this.contentDescription = contentDescription }
            .pressable(interaction, enabled = enabled, cornerRadius = Dimens.Radius.iconButton, onClick = onClick)
            .clip(shape)
            .background(
                when {
                    !enabled -> c.buttonBasicDisabled
                    pressed -> c.buttonBasicPressed
                    else -> c.buttonBasicEnabled
                },
            )
            .then(if (active) Modifier.border(Dimens.Component.focusRingWidth, c.voiceActive, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.Icon.base), tint = if (enabled) tint else c.iconQuaternary)
    }
}

/**
 * 급해 버튼. 화면 맨 아래 전체 폭, 높이 120(compact 100), informative_negative 바탕에 어두운 글자(대비 5.2:1 이상).
 * 아이콘은 화장실 Filled 64.
 */
@Composable
fun UrgentButton(onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, text: String = "급해요") {
    val c = ShimpyoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = if (pressed) Color.Black.copy(alpha = 0.12f).compositeOver(c.urgentContainer) else c.urgentContainer
    Row(
        modifier = modifier
            .heightIn(min = if (compact) Dimens.Drive.urgentHeightCompact else Dimens.Drive.urgentHeight)
            .pressable(interaction, cornerRadius = Dimens.Radius.l, onClick = onClick)
            .clip(RoundedCornerShape(Dimens.Radius.l))
            .background(bg)
            .padding(horizontal = Dimens.Button.paddingHorizontalLarge),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShimpyoIcons.WcFilled, contentDescription = null, modifier = Modifier.size(Dimens.Icon.large), tint = c.onUrgentContainer)
        Spacer(Modifier.width(Dimens.Space.m))
        Text(text, style = DriveType.UrgentLabel, color = c.onUrgentContainer, maxLines = 1)
    }
}

/**
 * 마이크 버튼과 음성 상태. "듣는 중"과 "말하는 중"을 글자로 나눠 보여 준다(screens.md 0.4).
 * 듣는 중에는 Filled 마이크 + 고정 링. 음성을 쓸 수 없으면 사선 마이크.
 */
@Composable
fun VoiceButton(voice: VoiceState, voiceEnabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = ShimpyoTheme.colors
    val available = voiceEnabled && voice != VoiceState.UNAVAILABLE
    val status = when {
        !available -> "음성 꺼짐"
        voice == VoiceState.LISTENING -> "듣는 중"
        voice == VoiceState.SPEAKING -> "말하는 중"
        voice == VoiceState.THINKING -> "생각하는 중"
        else -> null
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (status != null) {
            Text(
                status,
                style = DriveType.GaugeLabel,
                color = if (voice == VoiceState.LISTENING && available) c.voiceActive else c.textSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.width(Dimens.Space.m))
        }
        PleosIconButton(
            icon = when {
                !available -> ShimpyoIcons.MicOff
                voice == VoiceState.LISTENING -> ShimpyoIcons.MicFilled
                voice == VoiceState.SPEAKING -> ShimpyoIcons.VolumeUp
                else -> ShimpyoIcons.Mic
            },
            contentDescription = if (available) "말하기" else "음성 꺼짐",
            onClick = onClick,
            active = available && voice == VoiceState.LISTENING,
            tint = if (available && voice == VoiceState.LISTENING) c.voiceActive else c.iconPrimary,
        )
    }
}

/** 버튼 사이 간격 [가이드: 팝업 버튼 간격 24] */
val ButtonGap: Dp = 24.dp
