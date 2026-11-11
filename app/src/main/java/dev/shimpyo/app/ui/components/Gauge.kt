package dev.shimpyo.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shimpyo.app.ui.Labels
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.DriveType
import dev.shimpyo.app.ui.theme.ShimpyoMotion
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.engine.Level
import kotlin.math.max

/**
 * 사람 게이지: 연료 게이지처럼 생긴 '사람의 주행가능시간' (screens.md 1.4).
 *
 *  [아이콘] 화장실            [곧]
 *  38 분
 *  ██████████▌░░░░|░░░░░
 *  아이 기준
 *
 * 숫자(남은 분)가 가장 크고, 막대는 남은 분 ÷ 기준 간격. SOON 경계에 눈금을 둔다.
 * 단계는 색 + 배지 + 숫자로 같이 보여 준다(라이트 테마에서 주황·빨강 막대는 대비 4.5:1이 안 되므로).
 * 누를 수 없고 포커스도 받지 않는다. 막대 색·길이는 단계가 바뀔 때만 250ms로 바뀌고, 매분 바뀌는 숫자는 그대로 바꾼다.
 */
@Composable
fun HumanGauge(
    title: String,
    icon: ImageVector,
    minutes: Int?,
    level: Level?,
    fraction: Float?,
    tick: Float?,
    reason: String?,
    compact: Boolean,
    modifier: Modifier = Modifier,
    tall: Boolean = false,
) {
    val c = ShimpyoTheme.colors
    val readout = Labels.readout(minutes)
    val levelText = level?.let { Labels.levelWord(it) } ?: "계산 중"
    val spoken = if (minutes == null) "$title 계산 중" else "$title ${readout.value}${readout.unit} 남음, $levelText"
    // compact(윈도 높이 1000 미만): 숫자 48, 아이콘 32, 위아래 여백 20 → 카드 약 196 (WF 세로 800에 들어간다)
    val padding = if (compact) PaddingValues(horizontal = Dimens.Gauge.cardPaddingCompact, vertical = 20.dp) else PaddingValues(Dimens.Gauge.cardPadding)
    ShimpyoCard(
        modifier = modifier
            .heightIn(min = if (compact) Dimens.Gauge.cardMinHeightCompact else Dimens.Gauge.cardMinHeight)
            .clearAndSetSemantics { contentDescription = spoken },
        contentPadding = padding,
    ) {
        Row(Modifier.fillMaxWidth().height(if (compact) 40.dp else Dimens.Icon.base), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (compact) Dimens.Icon.small else Dimens.Icon.base), tint = c.iconSecondary)
            Spacer(Modifier.width(if (compact) Dimens.Space.xs else Dimens.Space.s))
            Text(title, style = DriveType.GaugeLabel, color = c.textSecondary, maxLines = 1, modifier = Modifier.weight(1f))
            LevelBadge(level)
        }
        // tall: 가로 윈도에서 카드가 세로로 늘어나면 숫자를 가운데, 막대와 이유를 아래에 둔다
        if (tall) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(if (compact) 0.dp else Dimens.Space.xxs))
        Row(verticalAlignment = Alignment.Bottom) {
            val valueStyle = if (compact) DriveType.GaugeValueCompact.copy(lineHeight = 50.sp) else DriveType.GaugeValue.copy(lineHeight = 66.sp)
            Text(
                readout.value,
                style = valueStyle,
                color = if (minutes == null) c.textTertiary else c.textPrimary,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(Dimens.Space.xs))
            Text(readout.unit, style = DriveType.GaugeUnit, color = c.textSecondary, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
        // 눈금(40)은 막대(24) 위아래로 8씩 나온다
        if (tall) Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(if (compact) 10.dp else Dimens.Space.l))
        GaugeBar(fraction = if (minutes == null) null else fraction, level = level, tick = tick)
        Spacer(Modifier.height(if (compact) 10.dp else Dimens.Space.l))
        Text(
            reason ?: if (minutes == null) "계산 중" else "",
            style = DriveType.Minimum,
            color = c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 게이지 막대: 높이 24 반경 12, 빈 곳 gauge_track, 채움 단계 색. SOON 경계 눈금 4×40(막대 밖으로 위아래 8씩 그린다).
 * 단계가 바뀔 때만 길이와 색을 250ms로 바꾸고(정차 후 차오를 때), 그 밖에는 바로 바꾼다.
 */
@Composable
fun GaugeBar(fraction: Float?, level: Level?, tick: Float?, modifier: Modifier = Modifier) {
    val c = ShimpyoTheme.colors
    val target = (fraction ?: 0f).coerceIn(0f, 1f)
    val animated = remember { Animatable(target) }
    val lastLevel = remember { arrayOfNulls<Level>(1).also { it[0] = level } }
    LaunchedEffect(target, level) {
        if (level != lastLevel[0]) {
            lastLevel[0] = level
            animated.animateTo(target, tween(ShimpyoMotion.DURATION_MEDIUM_1, easing = ShimpyoMotion.Easing))
        } else {
            animated.snapTo(target)
        }
    }
    val barColor by animateColorAsState(
        targetValue = levelColor(level),
        animationSpec = tween(ShimpyoMotion.DURATION_MEDIUM_1, easing = ShimpyoMotion.Easing),
        label = "gaugeColor",
    )
    val track = c.gaugeTrack
    val tickColor = c.textTertiary
    val visible = fraction != null
    Canvas(modifier.fillMaxWidth().height(Dimens.Gauge.barHeight)) {
        val barH = size.height
        val top = 0f
        val radius = CornerRadius(barH / 2f)
        drawRoundRect(track, Offset(0f, top), Size(size.width, barH), radius)
        val f = animated.value
        if (visible && f > 0f) {
            drawRoundRect(barColor, Offset(0f, top), Size(max(barH, size.width * f), barH), radius)
        }
        if (tick != null) {
            val w = Dimens.Gauge.thresholdTickWidth.toPx()
            val x = (size.width * tick.coerceIn(0f, 1f)).coerceIn(w / 2f, size.width - w / 2f)
            val tickH = Dimens.Gauge.thresholdTickHeight.toPx()
            drawRoundRect(tickColor, Offset(x - w / 2f, (barH - tickH) / 2f), Size(w, tickH), CornerRadius(w / 2f))
        }
    }
}

