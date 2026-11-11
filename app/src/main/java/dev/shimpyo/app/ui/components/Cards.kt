package dev.shimpyo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.DriveType
import dev.shimpyo.app.ui.theme.PleosType
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.engine.Level

/**
 * 카드 = 가이드 설정 화면의 컨테이너 방식: surface_basic 바탕 + 2dp 테두리, 반경 32.
 * [onClick]이 있으면 카드 전체가 하나의 터치 영역이다.
 */
@Composable
fun ShimpyoCard(
    modifier: Modifier = Modifier,
    borderColor: Color = ShimpyoTheme.colors.cardStroke,
    borderWidth: Dp = Dimens.Component.strokeWidth,
    containerColor: Color = ShimpyoTheme.colors.cardContainer,
    contentPadding: PaddingValues = PaddingValues(Dimens.Gauge.cardPadding),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.Radius.l)
    val interaction = remember { MutableInteractionSource() }
    val clickable = if (onClick != null) Modifier.pressable(interaction, cornerRadius = Dimens.Radius.l, onClick = onClick) else Modifier
    Column(
        modifier = modifier
            .then(clickable)
            .clip(shape)
            .background(containerColor)
            .border(borderWidth, borderColor, shape)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * 이유 칩: 누를 수 없는 정보. 높이 60, 반경 16, surface_high, 글자 30 (주행 화면 보조 정보 기준).
 * 포커스를 받지 않는다.
 */
@Composable
fun ReasonChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = ShimpyoTheme.colors
    Row(
        modifier = modifier
            .height(Dimens.Component.chipHeight)
            .clip(RoundedCornerShape(Dimens.Radius.s))
            .background(c.surfaceHigh)
            .padding(horizontal = Dimens.Component.chipPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.Icon.small), tint = c.iconSecondary)
            Spacer(Modifier.width(Dimens.Space.xs))
        }
        Text(text, style = DriveType.ReasonChip, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 정차 화면의 작은 칩 (목적, 시설). 높이 48, 글자 26 */
@Composable
fun SmallChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = ShimpyoTheme.colors.surfaceHigh,
    content: Color = ShimpyoTheme.colors.textPrimary,
    style: TextStyle = PleosType.BodySmallStrong,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(Dimens.Radius.s))
            .background(container)
            .padding(horizontal = Dimens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.Icon.small), tint = content)
            Spacer(Modifier.width(Dimens.Space.xs))
        }
        Text(text, style = style, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 단계 배지 (SOON [곧], NOW [지금]). 단계 색 바탕 + 어두운 글자 (대비 5.2:1 이상) */
@Composable
fun LevelBadge(level: Level?, modifier: Modifier = Modifier) {
    val text = when (level) {
        Level.SOON -> "곧"
        Level.NOW -> "지금"
        else -> return
    }
    val c = ShimpyoTheme.colors
    Text(
        text,
        style = PleosType.BodyLargeStrong,
        color = c.onGaugeColor,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.Radius.s))
            .background(if (level == Level.NOW) c.gaugeNow else c.gaugeSoon)
            .padding(horizontal = Dimens.Space.m, vertical = 5.dp),
    )
}

/** 게이지 단계 색 */
@Composable
fun levelColor(level: Level?): Color {
    val c = ShimpyoTheme.colors
    return when (level) {
        Level.SOON -> c.gaugeSoon
        Level.NOW -> c.gaugeNow
        Level.WATCH -> c.gaugeWatch
        Level.OK -> c.gaugeOk
        null -> c.gaugeTrack
    }
}

/** 구역 제목 (BodyMediumStrong, text_secondary) */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = PleosType.BodyMediumStrong,
        color = ShimpyoTheme.colors.textSecondary,
        modifier = modifier.padding(top = Dimens.Space.xl, bottom = Dimens.Space.s),
    )
}

/** 빈 상태·안내 문구 */
@Composable
fun EmptyMessage(title: String, modifier: Modifier = Modifier, detail: String? = null, icon: ImageVector? = null) {
    val c = ShimpyoTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.Icon.large), tint = c.iconTertiary)
            Spacer(Modifier.height(Dimens.Space.m))
        }
        Text(title, style = PleosType.TitleSmall, color = c.textPrimary)
        if (detail != null) {
            Spacer(Modifier.height(Dimens.Space.xs))
            Text(detail, style = PleosType.BodyMedium, color = c.textSecondary)
        }
    }
}

/** 눌리는 목록 항목의 눌림 바탕 */
@Composable
fun pressedBackground(interaction: MutableInteractionSource): Color {
    val pressed by interaction.collectIsPressedAsState()
    return if (pressed) ShimpyoTheme.colors.buttonBasicPressed else Color.Transparent
}

/**
 * 칩을 왼쪽부터 놓고 줄이 넘치면 다음 줄로 보낸다. [maxLines]를 넘는 칩은 그리지 않는다
 * (주행 화면에서 칩 개수와 줄 수를 고정하려고 직접 잰다).
 */
@Composable
fun ChipFlow(
    modifier: Modifier = Modifier,
    spacing: Dp = Dimens.Space.s,
    lineSpacing: Dp = Dimens.Space.s,
    maxLines: Int = 2,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val lineGap = lineSpacing.roundToPx()
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val placeables = measurables.map { it.measure(Constraints(maxWidth = maxW)) }
        val lines = ArrayList<List<Placeable>>()
        var line = ArrayList<Placeable>()
        var lineWidth = 0
        for (p in placeables) {
            val needed = if (line.isEmpty()) p.width else lineWidth + gap + p.width
            if (line.isEmpty() || needed <= maxW) {
                line.add(p)
                lineWidth = needed
            } else {
                lines.add(line)
                if (lines.size >= maxLines) {
                    line = ArrayList()
                    break
                }
                line = arrayListOf(p)
                lineWidth = p.width
            }
        }
        if (line.isNotEmpty() && lines.size < maxLines) lines.add(line)
        val widest = lines.maxOfOrNull { l -> l.sumOf { it.width } + gap * (l.size - 1) } ?: 0
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else widest
        val height = lines.sumOf { l -> l.maxOf { it.height } } + lineGap * (lines.size - 1).coerceAtLeast(0)
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            for (l in lines) {
                val h = l.maxOf { it.height }
                var x = 0
                for (p in l) {
                    p.placeRelative(x, y + (h - p.height) / 2)
                    x += p.width + gap
                }
                y += h + lineGap
            }
        }
    }
}
