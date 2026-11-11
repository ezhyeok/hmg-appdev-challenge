package dev.shimpyo.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.PleosType
import dev.shimpyo.app.ui.theme.ShimpyoTheme

/** 주요 메뉴 드롭다운 항목 */
data class HeaderMenuItem(val label: String, val icon: ImageVector, val selected: Boolean, val onSelect: () -> Unit)

/**
 * Pleos Page Navigation (screens.md 0.1).
 * 정차 중: [앱 아이콘 80] [주요 메뉴 v] … [suffix], 높이 160(compact 112).
 * 주행 중: 드롭다운을 끄고 제목만(화살표 없음), 높이 112. 하위 화면은 앱 아이콘 대신 [<] 뒤로.
 *
 * @param menu null이면 제목만 보인다(주행 중, 하위 화면)
 * @param onTitleLongPress 숨은 시연 패널 진입 (정차 중에만 넘긴다)
 * @param badge 제목 옆 상태 표시 (예: "시연 모드 · 귀성길 정체"), [badgeShort]는 자리가 좁을 때 쓰는 짧은 문구
 */
@Composable
fun PageHeader(
    title: String,
    height: Dp,
    margin: Dp,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    menu: List<HeaderMenuItem>? = null,
    onTitleLongPress: (() -> Unit)? = null,
    badge: String? = null,
    badgeShort: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = ShimpyoTheme.colors
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(menu == null) { if (menu == null) expanded = false }
    Row(
        modifier = modifier.fillMaxWidth().height(height).padding(horizontal = margin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            PleosIconButton(ShimpyoIcons.ArrowBack, contentDescription = "뒤로", onClick = onBack)
        } else {
            AppIconMark()
        }
        Spacer(Modifier.width(Dimens.Space.l))
        Box {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val interactive = menu != null || onTitleLongPress != null
            Row(
                modifier = Modifier
                    .heightIn(min = Dimens.Button.iconButton)
                    .then(
                        if (interactive) {
                            Modifier.pressable(
                                interaction,
                                cornerRadius = Dimens.Radius.iconButton,
                                role = if (menu != null) Role.DropdownList else null,
                                onLongClick = onTitleLongPress,
                                onClick = { if (menu != null) expanded = true },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(RoundedCornerShape(Dimens.Radius.iconButton))
                    .background(if (pressed) c.buttonBasicPressed else Color.Transparent)
                    .padding(start = Dimens.Space.xs, end = if (menu != null) 0.dp else Dimens.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = PleosType.TitleLarge, color = c.textPrimary, maxLines = 1)
                if (menu != null) {
                    Icon(ShimpyoIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(Dimens.Icon.base), tint = c.iconPrimary)
                }
            }
            if (expanded && menu != null) {
                val offsetY = with(LocalDensity.current) { (Dimens.Button.iconButton + Dimens.Space.xs).roundToPx() }
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(0, offsetY),
                    onDismissRequest = { expanded = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    MenuList(menu) { item ->
                        expanded = false
                        item.onSelect()
                    }
                }
            }
        }
        Spacer(Modifier.width(Dimens.Space.m))
        BoxWithConstraints(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (badge != null) {
                // 좁으면 짧은 문구로 (예: "시연 모드 · 귀성길 정체" → "시연 모드")
                StatusPill(if (badgeShort != null && maxWidth < BADGE_FULL_WIDTH) badgeShort else badge)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space.m), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** 열린 드롭다운 목록: 반경 32, 항목 100, 항목 반경 24, 눌림 색 dropdown_pressed */
@Composable
private fun MenuList(items: List<HeaderMenuItem>, onPick: (HeaderMenuItem) -> Unit) {
    val c = ShimpyoTheme.colors
    val shape = RoundedCornerShape(Dimens.Radius.l)
    Column(
        Modifier
            .width(420.dp)
            .shadow(Dimens.Elevation.level2, shape)
            .clip(shape)
            .background(c.backgroundPopup)
            .border(Dimens.Component.strokeWidth, c.cardStroke, shape)
            .padding(Dimens.Space.s),
    ) {
        items.forEach { item ->
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Dimens.Component.listItemHeight)
                    .pressable(interaction, cornerRadius = Dimens.Radius.m, onClick = { onPick(item) })
                    .clip(RoundedCornerShape(Dimens.Radius.m))
                    .background(
                        when {
                            pressed -> c.dropdownPressed
                            item.selected -> c.surfaceHigh
                            else -> Color.Transparent
                        },
                    )
                    .padding(horizontal = Dimens.Space.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(item.icon, contentDescription = null, modifier = Modifier.size(Dimens.Icon.base), tint = c.iconPrimary)
                Spacer(Modifier.width(Dimens.Space.m))
                Text(item.label, style = PleosType.TitleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                if (item.selected) Icon(ShimpyoIcons.Check, contentDescription = "선택됨", modifier = Modifier.size(Dimens.Icon.base), tint = c.iconPrimary)
            }
        }
    }
}

/** 상태 알약 (예: 시연 모드). 누를 수 없다. 글자 28 (주행 화면 최소) */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, icon: ImageVector? = ShimpyoIcons.PlayArrow) {
    val c = ShimpyoTheme.colors
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(Dimens.Radius.s))
            .background(c.buttonFilledEnabled)
            .padding(start = Dimens.Space.s, end = Dimens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.Icon.small), tint = c.textOnFilledPrimary)
            Spacer(Modifier.width(Dimens.Space.xxs))
        }
        Text(text, style = PleosType.BodyMediumStrong, color = c.textOnFilledPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 앱 아이콘 자리(80, 반경 20). 앱 이름 '쉼표'의 쉼표(,)를 그린다.
 * 앱 고유 강조색을 만들지 않는 원칙에 따라 Filled 버튼과 같은 무채색이다.
 */
@Composable
fun AppIconMark(modifier: Modifier = Modifier, size: Dp = Dimens.Layout.appIcon) {
    val c = ShimpyoTheme.colors
    val ink = c.textOnFilledPrimary
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(c.buttonFilledEnabled),
    ) {
        Canvas(Modifier.size(size)) {
            val u = this.size.width / 80f
            // 점
            drawCircle(ink, radius = 11f * u, center = Offset(40f * u, 33f * u))
            // 꼬리: 점의 오른쪽 아래에서 왼쪽 아래로 가늘어진다
            val tail = Path().apply {
                moveTo(51f * u, 33f * u)
                cubicTo(51.5f * u, 47f * u, 45f * u, 58f * u, 31f * u, 64f * u)
                cubicTo(36.5f * u, 57f * u, 39.5f * u, 51f * u, 39.5f * u, 44f * u)
                close()
            }
            drawPath(tail, ink)
        }
    }
}

private val BADGE_FULL_WIDTH = 340.dp
