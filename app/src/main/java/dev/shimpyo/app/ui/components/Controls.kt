package dev.shimpyo.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.PleosType
import dev.shimpyo.app.ui.theme.ShimpyoMotion
import dev.shimpyo.app.ui.theme.ShimpyoTheme

/** Switch: 트랙 80×48 반경 16, 손잡이 32 반경 8 (가이드 색). 누르기는 행 전체가 받는다 */
@Composable
fun PleosSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = ShimpyoTheme.colors
    val travel = Dimens.Component.switchTrackWidth - Dimens.Component.switchKnob - 16.dp
    val x by animateDpAsState(if (checked) travel else 0.dp, tween(ShimpyoMotion.DURATION_SHORT_2, easing = ShimpyoMotion.Easing), label = "switch")
    Box(
        modifier
            .size(Dimens.Component.switchTrackWidth, Dimens.Component.switchTrackHeight)
            .clip(RoundedCornerShape(Dimens.Radius.s))
            .background(
                when {
                    !enabled -> c.switchDisabled
                    checked -> c.switchOn
                    else -> c.switchOff
                },
            )
            .padding(8.dp),
    ) {
        Box(
            Modifier
                .offset(x = x)
                .size(Dimens.Component.switchKnob)
                .clip(RoundedCornerShape(Dimens.Radius.switchKnob))
                .background(
                    when {
                        !enabled -> c.switchKnobDisabled
                        checked -> c.switchKnobOn
                        else -> c.switchKnobOff
                    },
                ),
        )
    }
}

/** Stepper: 400×100 반경 24. [−] 값 [+]. 끝값에서 해당 버튼은 꺼진다 */
@Composable
fun PleosStepper(
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    minusEnabled: Boolean = true,
    plusEnabled: Boolean = true,
    label: String = "",
    width: Dp = Dimens.Component.stepperWidth,
) {
    val c = ShimpyoTheme.colors
    val shape = RoundedCornerShape(Dimens.Radius.m)
    Row(
        modifier
            .width(width)
            .height(Dimens.Component.stepperHeight)
            .clip(shape)
            .background(c.controllerStepperBackground)
            .border(Dimens.Component.strokeWidth, c.cardStroke, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperKey(ShimpyoIcons.Remove, "$label 줄이기", minusEnabled, onMinus)
        Text(
            value,
            style = PleosType.TitleSmall.copy(fontFeatureSettings = "tnum"),
            color = c.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        StepperKey(ShimpyoIcons.Add, "$label 늘리기", plusEnabled, onPlus)
    }
}

@Composable
private fun StepperKey(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = ShimpyoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(Dimens.Component.stepperHeight)
            .semantics { contentDescription = description }
            .pressable(interaction, enabled = enabled, cornerRadius = Dimens.Radius.m, onClick = onClick)
            .clip(RoundedCornerShape(Dimens.Radius.m))
            .background(if (pressed) c.buttonBasicPressed else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.Icon.base), tint = if (enabled) c.iconPrimary else c.iconQuaternary)
    }
}

/**
 * Toggle button (On/Off). 켜지면 Filled 색 + 체크. 여러 개 가운데 하나를 고르는 묶음(평소 간격 선택지)에서는
 * [role]에 Role.RadioButton을 넘긴다
 */
@Composable
fun ToggleButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    size: ButtonSize = ButtonSize.Small,
    enabled: Boolean = true,
    role: Role = Role.Checkbox,
) {
    val c = ShimpyoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        !enabled -> c.buttonBasicDisabled
        selected && pressed -> c.buttonFilledPressed
        selected -> c.buttonFilledEnabled
        pressed -> c.buttonBasicPressed
        else -> c.buttonBasicEnabled
    }
    val fg = when {
        !enabled -> c.textQuaternary
        selected -> c.textOnFilledPrimary
        else -> c.textPrimary
    }
    Row(
        modifier
            .heightIn(min = size.height)
            .pressable(interaction, enabled = enabled, cornerRadius = size.radius, role = role, onClick = onClick)
            .clip(RoundedCornerShape(size.radius))
            .background(bg)
            .padding(horizontal = size.paddingH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val leading = if (selected) ShimpyoIcons.Check else icon
        if (leading != null) {
            Icon(leading, contentDescription = null, modifier = Modifier.size(size.icon), tint = fg)
            Spacer(Modifier.width(Dimens.Space.xs))
        }
        Text(text, style = size.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 선택 버튼: 지금 값을 글자로 보이고 누르면 선택지를 그 아래에 펼친다 (가이드 Dropdown의 Label + Suffix 아이콘 모양).
 * 선택지는 부르는 쪽이 같은 화면 안에 그린다: 목록 안에서 팝업을 띄우지 않아 CCP 포커스 순서가 화면 순서 그대로다.
 * 펼쳐지면 화살표가 위를 본다 (움직이지 않는다).
 */
@Composable
fun PleosSelectButton(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.Small,
) {
    val c = ShimpyoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .heightIn(min = size.height)
            .semantics { stateDescription = if (expanded) "펼침" else "접힘" }
            .pressable(interaction, cornerRadius = size.radius, role = Role.DropdownList, onClick = onClick)
            .clip(RoundedCornerShape(size.radius))
            .background(if (pressed) c.buttonBasicPressed else c.buttonBasicEnabled)
            .padding(start = size.paddingH, end = size.paddingH - Dimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, style = size.label, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(Dimens.Space.xxs))
        Icon(ShimpyoIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(size.icon).rotate(if (expanded) 180f else 0f), tint = c.iconPrimary)
    }
}

/**
 * 설정 목록 항목: 높이 100 이상, 제목 TitleSmall, 보조 BodyMedium text_secondary.
 * [onClick]이 있으면 행 전체가 터치 영역이다(스위치도 행으로 켠다).
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val c = ShimpyoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.Component.listItemHeight)
            .then(
                if (onClick != null) {
                    Modifier.pressable(interaction, enabled = enabled, cornerRadius = Dimens.Radius.m, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(Dimens.Radius.m))
            .background(if (pressed) c.buttonBasicPressed else Color.Transparent)
            .padding(horizontal = Dimens.Space.l, vertical = Dimens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.Icon.base), tint = if (enabled) c.iconSecondary else c.iconQuaternary)
            Spacer(Modifier.width(Dimens.Space.l))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = PleosType.TitleSmall, color = if (enabled) c.textPrimary else c.textQuaternary)
            if (subtitle != null) {
                Spacer(Modifier.height(Dimens.Space.xxs))
                Text(subtitle, style = PleosType.BodyMedium, color = if (enabled) c.textSecondary else c.textQuaternary)
            }
        }
        Spacer(Modifier.width(Dimens.Space.l))
        trailing()
    }
}

/** 구분선 2dp */
@Composable
fun ListDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Space.l)
            .height(Dimens.Component.dividerThickness)
            .background(ShimpyoTheme.colors.divider),
    )
}

/** 목록 묶음 컨테이너 (반경 32, 테두리 2) */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = ShimpyoTheme.colors
    val shape = RoundedCornerShape(Dimens.Radius.l)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.cardContainer)
            .border(Dimens.Component.strokeWidth, c.cardStroke, shape)
            .padding(Dimens.Space.xs),
    ) { content() }
}

/** 오른쪽 화살표 (다음 화면·동작) */
@Composable
fun ChevronTrailing(text: String? = null) {
    val c = ShimpyoTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (text != null) {
            Text(text, style = PleosType.BodyMedium, color = c.textSecondary)
            Spacer(Modifier.width(Dimens.Space.xs))
        }
        Icon(ShimpyoIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(Dimens.Icon.base), tint = c.iconTertiary)
    }
}

/** 가는 세로 막대 (레일·구분) */
@Composable
fun VerticalRule(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.width(Dimens.Component.dividerThickness).fillMaxHeight().background(color))
}
