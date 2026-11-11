package dev.shimpyo.app.ui.drive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shimpyo.app.ui.Labels
import dev.shimpyo.app.ui.LocalLayout
import dev.shimpyo.app.ui.components.ButtonGap
import dev.shimpyo.app.ui.components.ButtonSize
import dev.shimpyo.app.ui.components.ChipFlow
import dev.shimpyo.app.ui.components.HumanGauge
import dev.shimpyo.app.ui.components.PleosButton
import dev.shimpyo.app.ui.components.ReasonChip
import dev.shimpyo.app.ui.components.RouteStrip
import dev.shimpyo.app.ui.components.ShimpyoCard
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.components.UrgentButton
import dev.shimpyo.app.ui.components.levelColor
import dev.shimpyo.app.ui.stripData
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.DriveType
import dev.shimpyo.app.ui.theme.PleosType
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.engine.Prompt

/**
 * 1. 주행 화면 (screens.md 1장). 운전 중 1~2초 보고 "지금 괜찮은지, 다음에 어디서 쉬는지"를 안다.
 *
 * 주행 중([driving]): 게이지 2장, 추천 카드 1장(칩 2개까지), 질문 중일 때만 [들르기][괜찮아요], 급해 버튼.
 * 터치 요소는 마이크(헤더)·들르기·괜찮아요·급해 4개이고 모두 높이 100 이상, 스크롤·반복 애니메이션 없음.
 * 정차 중: 질문이 없으면 행동 줄이 [계획 보기][탑승자]로 바뀐다.
 * 가로 윈도: 왼쪽에 게이지 + 앞으로 경로 띠(보기 전용), 오른쪽에 추천 카드·행동 줄·급해 버튼 (버튼 구성은 세로와 같다).
 */
@Composable
fun DriveScreen(
    state: CoachUiState,
    driving: Boolean,
    onAnswer: (Prompt, Boolean) -> Unit,
    onUrgent: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenOccupants: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = LocalLayout.current
    val card = stopCard(state, driving)
    val gap = layout.gap
    val gauges: @Composable (Modifier, Boolean) -> Unit = { m, tall ->
        Row(m, horizontalArrangement = Arrangement.spacedBy(Dimens.Layout.gutter)) {
            val r = restroomGauge(state)
            val f = fatigueGauge(state)
            val cell = if (tall) Modifier.weight(1f).fillMaxHeight() else Modifier.weight(1f)
            HumanGauge("화장실", ShimpyoIcons.Wc, r.minutes, r.level, r.fraction, r.tick, r.reason, layout.compact, cell, tall = tall)
            HumanGauge("피로", ShimpyoIcons.Rest, f.minutes, f.level, f.fraction, f.tick, f.reason, layout.compact, cell, tall = tall)
        }
    }
    val actions: @Composable () -> Unit = {
        ActionRow(state, driving, onAnswer, onOpenPlan, onOpenOccupants)
    }
    val stopMin = if (layout.compact) Dimens.Drive.stopCardMinHeightCompact else Dimens.Drive.stopCardMinHeight

    if (layout.landscape) {
        Row(
            modifier.fillMaxSize().padding(start = layout.margin, end = layout.margin, bottom = layout.bottom),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space.xxl),
        ) {
            Column(Modifier.weight(0.486f).fillMaxSize()) {
                Spacer(Modifier.height(gap))
                // 가로 윈도: 게이지가 왼쪽 단 높이의 절반 남짓을 가진다 (compact는 고정 높이)
                if (layout.compact) gauges(Modifier.fillMaxWidth(), false) else gauges(Modifier.fillMaxWidth().weight(1.15f), true)
                Spacer(Modifier.height(gap))
                AheadStripCard(state, driving, Modifier.fillMaxWidth().weight(1f))
            }
            Column(Modifier.weight(0.514f).fillMaxSize()) {
                Spacer(Modifier.height(gap))
                StopCard(card, layout.compact, Modifier.fillMaxWidth().weight(1f).heightIn(min = stopMin))
                Spacer(Modifier.height(gap))
                actions()
                Spacer(Modifier.height(gap))
                UrgentButton(onUrgent, Modifier.fillMaxWidth(), compact = layout.compact)
            }
        }
    } else {
        Column(modifier.fillMaxSize().padding(start = layout.margin, end = layout.margin, bottom = layout.bottom)) {
            Spacer(Modifier.height(gap))
            gauges(Modifier.fillMaxWidth(), false)
            Spacer(Modifier.height(gap))
            StopCard(card, layout.compact, Modifier.fillMaxWidth().weight(1f).heightIn(min = stopMin))
            Spacer(Modifier.height(gap))
            actions()
            Spacer(Modifier.height(gap))
            UrgentButton(onUrgent, Modifier.fillMaxWidth(), compact = layout.compact)
        }
    }
}

/** 행동 줄(높이 100). 질문이 없으면 주행 중에는 빈 자리로 둬서 레이아웃이 움직이지 않는다 */
@Composable
private fun ActionRow(
    state: CoachUiState,
    driving: Boolean,
    onAnswer: (Prompt, Boolean) -> Unit,
    onOpenPlan: () -> Unit,
    onOpenOccupants: () -> Unit,
) {
    val prompt = state.prompt
    Row(Modifier.fillMaxWidth().height(Dimens.Drive.primaryActionHeight), horizontalArrangement = Arrangement.spacedBy(ButtonGap)) {
        when {
            prompt != null -> {
                val accept = acceptLabel(prompt)
                if (accept != null) {
                    // 들르기를 왼쪽에: 왼쪽 운전석에서 가까운 쪽이 주 행동이다
                    PleosButton(accept, { onAnswer(prompt, true) }, Modifier.weight(1f).height(Dimens.Drive.primaryActionHeight), filled = true, labelStyle = DriveType.ActionLabel)
                    PleosButton("괜찮아요", { onAnswer(prompt, false) }, Modifier.weight(1f).height(Dimens.Drive.primaryActionHeight), labelStyle = DriveType.ActionLabel)
                } else {
                    Spacer(Modifier.weight(1f))
                    PleosButton("확인", { onAnswer(prompt, false) }, Modifier.weight(1f).height(Dimens.Drive.primaryActionHeight), labelStyle = DriveType.ActionLabel)
                }
            }
            !driving -> {
                PleosButton("계획 보기", onOpenPlan, Modifier.weight(1f).height(Dimens.Drive.primaryActionHeight), filled = true, icon = ShimpyoIcons.Route, size = ButtonSize.Large)
                PleosButton("탑승자", onOpenOccupants, Modifier.weight(1f).height(Dimens.Drive.primaryActionHeight), icon = ShimpyoIcons.Group, size = ButtonSize.Large)
            }
            else -> Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * 추천 카드. 질문 중에는 테두리를 그 게이지 단계 색 4dp로 바꾼다(평소 2dp card_stroke).
 * 누를 수 없다(주행 중 depth를 늘리지 않는다). 내용 묶음은 카드 세로 가운데에 두고,
 * 칩까지 들어갈 높이가 없으면(compact) 칩을 뺀다.
 */
@Composable
fun StopCard(model: StopCardModel, compact: Boolean, modifier: Modifier = Modifier) {
    val c = ShimpyoTheme.colors
    val asking = model.askingLevel != null
    ShimpyoCard(
        modifier = modifier,
        borderColor = if (asking) levelColor(model.askingLevel) else c.cardStroke,
        borderWidth = if (asking) 4.dp else Dimens.Component.strokeWidth,
        contentPadding = PaddingValues(if (compact) Dimens.Gauge.cardPaddingCompact else Dimens.Drive.stopCardPadding),
    ) {
        CenteredWithOptional(
            gap = if (compact) Dimens.Space.s else Dimens.Space.l,
            main = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(model.icon, contentDescription = null, modifier = Modifier.size(if (compact) 32.dp else 40.dp), tint = if (asking) c.iconPrimary else c.iconSecondary)
                        Spacer(Modifier.width(Dimens.Space.s))
                        Text(
                            model.context,
                            style = PleosType.BodyMediumStrong,
                            color = if (asking) c.textPrimary else c.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    Spacer(Modifier.height(if (compact) Dimens.Space.xs else Dimens.Space.s))
                    Text(
                        model.title,
                        style = DriveType.StopName,
                        color = if (model.muted) c.textSecondary else c.textPrimary,
                        maxLines = if (model.sentence) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!model.meta.isNullOrEmpty()) {
                        Spacer(Modifier.height(Dimens.Space.xs))
                        Text(model.meta, style = DriveType.StopMeta, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            optional = if (model.chips.isEmpty()) null else ({
                ChipFlow(maxLines = 2) { model.chips.forEach { ReasonChip(it) } }
            }),
        )
    }
}

/**
 * [main]과 [optional]을 위아래로 붙여 세로 가운데에 놓는다. 둘 다 들어갈 높이가 없으면 [optional]을 뺀다.
 * (주행 화면은 스크롤하지 않으므로 넘치는 내용은 잘리기 전에 덜 중요한 것부터 뺀다)
 */
@Composable
private fun CenteredWithOptional(gap: Dp, main: @Composable () -> Unit, optional: (@Composable () -> Unit)?) {
    Layout(contents = listOf(main, optional ?: {})) { (mainMeasurables, optionalMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val m = mainMeasurables.map { it.measure(loose) }
        val o = optionalMeasurables.map { it.measure(loose) }
        val mainH = m.maxOfOrNull { it.height } ?: 0
        val optH = o.maxOfOrNull { it.height } ?: 0
        val gapPx = gap.roundToPx()
        val bounded = constraints.hasBoundedHeight
        val showOptional = optH > 0 && (!bounded || mainH + gapPx + optH <= constraints.maxHeight)
        val contentH = mainH + if (showOptional) gapPx + optH else 0
        val height = if (bounded) maxOf(constraints.maxHeight, contentH).coerceAtMost(constraints.maxHeight) else contentH
        layout(constraints.maxWidth, height) {
            val top = ((height - contentH) / 2).coerceAtLeast(0)
            m.forEach { it.placeRelative(0, top) }
            if (showOptional) o.forEach { it.placeRelative(0, top + mainH + gapPx) }
        }
    }
}

/** 가로 윈도의 "앞으로 경로" 띠 (보기 전용, 포커스 없음). 다음 약 120km */
@Composable
private fun AheadStripCard(state: CoachUiState, driving: Boolean, modifier: Modifier = Modifier) {
    val c = ShimpyoTheme.colors
    val layout = LocalLayout.current
    val data = stripData(state, maxAxisM = AHEAD_AXIS_M, nearFocus = true)
    ShimpyoCard(modifier, contentPadding = PaddingValues(layout.cardPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("앞으로 경로", style = PleosType.BodyMediumStrong, color = c.textSecondary, modifier = Modifier.weight(1f))
            if (!data.isEmpty) Text("${Labels.distance(data.axisM)}까지", style = DriveType.Minimum, color = c.textTertiary)
        }
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
            if (data.isEmpty) {
                Text("길안내를 시작하면 경로 앞 화장실과 정체를 보여 드려요", style = DriveType.Minimum, color = c.textSecondary, maxLines = 2)
            } else {
                RouteStrip(data.axisM, data.stops, data.spans, data.destinationM, driving = driving, description = "앞으로 경로: 화장실 기회와 정체")
            }
        }
        if (!data.isEmpty) StripLegend()
    }
}

/** 띠 범례 (글자 28: 주행 화면 최소) */
@Composable
fun StripLegend(modifier: Modifier = Modifier) {
    val c = ShimpyoTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.Space.l), verticalAlignment = Alignment.CenterVertically) {
        LegendItem("화장실") { s -> RingSwatch(c.timelineOpportunity, c.cardContainer, s) }
        LegendItem("정체") { s -> BarSwatch(c.timelineCongestion, s) }
        LegendItem("공백") { s -> BarSwatch(c.timelineGap, s) }
        LegendItem("추천") { s -> DotSwatch(c.timelineOpportunity, c.cardContainer, s) }
    }
}

@Composable
private fun LegendItem(label: String, swatch: @Composable (Dp) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        swatch(28.dp)
        Spacer(Modifier.width(Dimens.Space.xs))
        Text(label, style = DriveType.Minimum, color = ShimpyoTheme.colors.textSecondary)
    }
}

@Composable
private fun RingSwatch(color: Color, fill: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        drawCircle(fill, radius = this.size.minDimension / 2.6f)
        drawCircle(color, radius = this.size.minDimension / 2.8f, style = Stroke(4.dp.toPx()))
    }
}

@Composable
private fun DotSwatch(color: Color, fill: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        drawCircle(color, radius = this.size.minDimension / 2f)
        drawCircle(fill, radius = this.size.minDimension / 5.5f)
    }
}

@Composable
private fun BarSwatch(color: Color, size: Dp) {
    Box(Modifier.size(size)) {
        Canvas(Modifier.size(size)) {
            val h = 14.dp.toPx()
            drawRoundRect(color, Offset(0f, (this.size.height - h) / 2f), Size(this.size.width, h), CornerRadius(h / 2f))
        }
    }
}

private const val AHEAD_AXIS_M = 120_000.0
