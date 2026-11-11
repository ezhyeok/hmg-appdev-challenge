package dev.shimpyo.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shimpyo.app.ui.theme.DriveType
import dev.shimpyo.app.ui.theme.PleosType
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import kotlin.math.max
import kotlin.math.min

enum class StripMark { OPPORTUNITY, PLANNED, RECOMMENDED }

enum class StripSpanKind { CONGESTION, GAP }

/** 경로 위 한 지점. [distanceM]은 차에서 경로를 따라 잰 거리 */
@Immutable
data class StripStop(val distanceM: Double, val mark: StripMark, val label: String? = null, val number: Int? = null)

/** 경로 위 구간 (정체, 화장실 공백) */
@Immutable
data class StripSpan(val startM: Double, val endM: Double, val kind: StripSpanKind, val label: String? = null)

/**
 * 경로 띠: 거리에 비례해 지금 위치 → 화장실 기회(o) · 계획 정차(번호) · 추천(O) · 정체(빨강) · 화장실 공백(주황) → 목적지를 그린다.
 * 보기 전용이라 포커스를 받지 않는다. 계획 화면과 가로 주행 화면이 같은 축 계산(RouteModel.stripData)을 쓴다.
 *
 *   안성휴게소          옥산휴게소        ← 계획·추천 정차 이름 (위)
 *   ●──O━━━━━━──o──o──②──o────▶         ← 레일
 *        정체 40분                        ← 구간 이름 (아래)
 *   0        50       100km              ← 거리 눈금 (정차 화면만)
 *
 * @param axisM 띠 오른쪽 끝이 나타내는 거리
 * @param driving 주행 중이면 글자를 28(주행 화면 최소)로 키우고 거리 눈금은 숨긴다
 */
@Composable
fun RouteStrip(
    axisM: Double,
    stops: List<StripStop>,
    spans: List<StripSpan>,
    destinationM: Double?,
    modifier: Modifier = Modifier,
    driving: Boolean = false,
    description: String = "경로 띠",
) {
    val c = ShimpyoTheme.colors
    // 라벨이 8개(기본 캐시)를 넘으면 그릴 때마다 글자를 다시 쟀다. 넉넉하게 둔다
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val label = if (driving) DriveType.Minimum else PleosType.BodySmallStrong
    // 글자 줄 높이는 sp라 글꼴 배율을 따른다
    val labelH = with(LocalDensity.current) { label.lineHeight.toDp() } + 4.dp
    val metrics = StripMetrics(labelH = labelH, railH = 52.dp, scaleH = if (driving) 0.dp else 30.dp)
    val palette = StripPalette(
        rail = c.timelineRoute,
        opportunity = c.timelineOpportunity,
        planned = c.timelinePlannedStop,
        onPlanned = c.textOnFilledPrimary,
        congestion = c.timelineCongestion,
        gap = c.timelineGap,
        ink = c.iconPrimary,
        card = c.cardContainer,
        label = c.textPrimary,
        labelSecondary = c.textSecondary,
        scale = c.textTertiary,
    )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(metrics.labelH * 2 + metrics.railH + metrics.scaleH)
            .semantics { contentDescription = description },
    ) {
        drawStrip(axisM, stops, spans, destinationM, palette, measurer, label, metrics)
    }
}

private class StripMetrics(val labelH: Dp, val railH: Dp, val scaleH: Dp)

private class StripPalette(
    val rail: Color,
    val opportunity: Color,
    val planned: Color,
    val onPlanned: Color,
    val congestion: Color,
    val gap: Color,
    val ink: Color,
    val card: Color,
    val label: Color,
    val labelSecondary: Color,
    val scale: Color,
)

/** 한 줄에 겹치지 않게 이름을 놓는다. 겹치면 그 이름은 뺀다 */
private class LabelRow {
    private val taken = ArrayList<ClosedFloatingPointRange<Float>>()

    fun place(start: Float, width: Float): Boolean {
        val range = (start - 12f)..(start + width + 12f)
        if (taken.any { it.start < range.endInclusive && range.start < it.endInclusive }) return false
        taken += range
        return true
    }
}

private fun DrawScope.drawStrip(
    axisM: Double,
    stops: List<StripStop>,
    spans: List<StripSpan>,
    destinationM: Double?,
    p: StripPalette,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    m: StripMetrics,
) {
    val axis = max(axisM, 1.0)
    val inset = 24.dp.toPx()
    val left = inset
    val right = size.width - inset
    fun x(d: Double): Float = left + (min(max(d, 0.0), axis) / axis).toFloat() * (right - left)
    val labelH = m.labelH.toPx()
    val cy = labelH + m.railH.toPx() / 2f
    val railW = 8.dp.toPx()
    val spanW = 16.dp.toPx()

    fun label(row: LabelRow, text: String, centerX: Float, top: Float, color: Color) {
        val layout = measurer.measure(text, labelStyle)
        val w = layout.size.width.toFloat()
        val start = (centerX - w / 2f).coerceIn(0f, size.width - w)
        if (row.place(start, w)) drawText(layout, color = color, topLeft = Offset(start, top))
    }

    // 레일. 목적지가 축 밖이면 끝에 화살표로 이어짐을 표시
    val destInside = destinationM != null && destinationM <= axis
    val railEnd = if (destInside) x(destinationM!!) else right
    drawRoundRect(p.rail, Offset(left, cy - railW / 2), Size(railEnd - left, railW), CornerRadius(railW / 2))
    if (!destInside) {
        val a = 12.dp.toPx()
        val arrow = Path().apply {
            moveTo(right + a * 0.7f, cy)
            lineTo(right - a * 0.5f, cy - a)
            lineTo(right - a * 0.5f, cy + a)
            close()
        }
        drawPath(arrow, p.rail)
    }

    // 구간 (정체가 공백 위에 오도록 공백 먼저)
    val below = LabelRow()
    val belowTop = cy + m.railH.toPx() / 2f
    spans.sortedBy { if (it.kind == StripSpanKind.GAP) 0 else 1 }.forEach { s ->
        if (s.endM <= 0.0 || s.startM >= axis) return@forEach
        val x0 = x(s.startM)
        val x1 = max(x(s.endM), x0 + spanW)
        val color = if (s.kind == StripSpanKind.CONGESTION) p.congestion else p.gap
        drawRoundRect(color, Offset(x0, cy - spanW / 2), Size(x1 - x0, spanW), CornerRadius(spanW / 2))
    }
    spans.sortedBy { it.startM }.forEach { s ->
        if (s.endM <= 0.0 || s.startM >= axis || s.label == null) return@forEach
        label(below, s.label, (x(s.startM) + x(s.endM)) / 2f, belowTop, p.labelSecondary)
    }

    // 목적지
    if (destInside) {
        val s = 24.dp.toPx()
        drawRoundRect(p.ink, Offset(railEnd - s / 2, cy - s / 2), Size(s, s), CornerRadius(6.dp.toPx()))
    }

    // 지점: 기회 → 추천 → 계획 순으로 그려 중요한 것이 위에 온다
    val visible = stops.filter { it.distanceM in 0.0..axis }
    visible.sortedBy { it.mark.ordinal }.forEach { s ->
        val center = Offset(x(s.distanceM), cy)
        when (s.mark) {
            StripMark.OPPORTUNITY -> {
                drawCircle(p.card, radius = 12.dp.toPx(), center = center)
                drawCircle(p.opportunity, radius = 10.dp.toPx(), center = center, style = Stroke(4.dp.toPx()))
            }
            StripMark.RECOMMENDED -> {
                drawCircle(p.card, radius = 21.dp.toPx(), center = center)
                drawCircle(p.opportunity, radius = 18.dp.toPx(), center = center)
                drawCircle(p.card, radius = 7.dp.toPx(), center = center)
            }
            StripMark.PLANNED -> {
                drawCircle(p.card, radius = 23.dp.toPx(), center = center)
                drawCircle(p.planned, radius = 20.dp.toPx(), center = center)
                s.number?.let { n ->
                    val t = measurer.measure(n.toString(), PleosType.BodySmallStrong)
                    drawText(t, color = p.onPlanned, topLeft = Offset(center.x - t.size.width / 2f, center.y - t.size.height / 2f))
                }
            }
        }
    }

    // 지금 위치는 늘 보이게 마지막에
    drawCircle(p.card, radius = 17.dp.toPx(), center = Offset(left, cy))
    drawCircle(p.ink, radius = 14.dp.toPx(), center = Offset(left, cy))
    drawCircle(p.card, radius = 6.dp.toPx(), center = Offset(left, cy))

    // 이름: 계획 정차와 추천만 위에 (계획 정차 먼저)
    val above = LabelRow()
    visible.filter { it.label != null && it.mark != StripMark.OPPORTUNITY }
        .sortedWith(compareBy<StripStop> { if (it.mark == StripMark.PLANNED) 0 else 1 }.thenBy { it.distanceM })
        .forEach { s -> label(above, s.label!!, x(s.distanceM), 0f, p.label) }

    // 거리 눈금 (정차 화면)
    if (m.scaleH > 0.dp) {
        val stepKm = niceStep(axis / 1000.0)
        val y = belowTop + labelH
        val scaleRow = LabelRow()
        var km = 0.0
        while (km * 1000.0 <= axis + 1.0) {
            val text = if (km == 0.0) "0" else "${km.toInt()}km"
            val t = measurer.measure(text, ScaleStyle)
            val cx = x(km * 1000.0)
            val start = (cx - t.size.width / 2f).coerceIn(0f, size.width - t.size.width)
            if (scaleRow.place(start, t.size.width.toFloat())) drawText(t, color = p.scale, topLeft = Offset(start, y))
            km += stepKm
        }
    }
}

private val ScaleStyle = PleosType.LabelMedium.copy(fontSize = 24.sp)

/** 눈금 간격: 축 길이(km)에 맞춰 10·20·50·100km 중 하나 (눈금 3~6개) */
private fun niceStep(axisKm: Double): Double = when {
    axisKm <= 40 -> 10.0
    axisKm <= 90 -> 20.0
    axisKm <= 220 -> 50.0
    else -> 100.0
}
