package dev.shimpyo.app.ui.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shimpyo.app.coach.CoachUiState
import dev.shimpyo.app.ui.Labels
import dev.shimpyo.app.ui.LocalLayout
import dev.shimpyo.app.ui.RestroomGap
import dev.shimpyo.app.ui.components.ButtonSize
import dev.shimpyo.app.ui.components.ChipFlow
import dev.shimpyo.app.ui.components.PleosButton
import dev.shimpyo.app.ui.components.RouteStrip
import dev.shimpyo.app.ui.components.ShimpyoCard
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.components.SmallChip
import dev.shimpyo.app.ui.congestionPlace
import dev.shimpyo.app.ui.drive.StripLegend
import dev.shimpyo.app.ui.opportunities
import dev.shimpyo.app.ui.plannedDistance
import dev.shimpyo.app.ui.restroomGaps
import dev.shimpyo.app.ui.routeSpeedMps
import dev.shimpyo.app.ui.stripData
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.PleosType
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.PlannedStop
import dev.shimpyo.engine.StopCandidate
import kotlin.math.roundToInt

/*
 * 2. 계획 화면 (screens.md 2장, 정차 중에만). 경로 앞의 화장실 기회, 화장실 공백, 정체, 계획된 정차를
 * 같은 거리 축에 놓는다: 위의 경로 띠는 거리 비례, 아래 타임라인은 같은 순서의 상세.
 * 시각 대신 '남은 시간'을 쓴다: 시연 모드에서는 서비스 시계가 실제 시계보다 빨리 갈 수 있어서다.
 */

/** 타임라인 한 줄 */
sealed interface TimelineRow {
    val distanceM: Double

    data object Now : TimelineRow {
        override val distanceM = 0.0
    }

    data class Stop(
        val stop: StopCandidate,
        override val distanceM: Double,
        val etaS: Double?,
        val planned: PlannedStop?,
        val number: Int?,
        val recommended: Boolean,
    ) : TimelineRow

    data class Congestion(val segment: CongestionSegment) : TimelineRow {
        override val distanceM: Double get() = segment.startDistanceM.coerceAtLeast(0.0)
    }

    data class Gap(val gap: RestroomGap) : TimelineRow {
        override val distanceM: Double get() = gap.startM
    }

    data class Destination(val name: String?, override val distanceM: Double, val etaS: Double?) : TimelineRow
}

/** 상태 → 타임라인. 같은 거리에서는 지금 → 정차 → 공백 → 정체 → 목적지 순 */
fun timeline(state: CoachUiState): List<TimelineRow> {
    val plan = state.plan?.stops.orEmpty()
    val planIndex = plan.withIndex().associate { (i, p) -> p.stop.id to i }
    val recommendedId = (state.prompt?.stop ?: state.next?.stop)?.id
    val speed = routeSpeedMps(state)
    val rows = ArrayList<TimelineRow>()
    rows += TimelineRow.Now
    val opps = opportunities(state)
    opps.forEach { s ->
        val i = planIndex[s.id]
        val p = i?.let { plan[it] }
        rows += TimelineRow.Stop(s, s.routeDistanceM!!, s.etaS ?: p?.arriveInS, p, i?.plus(1), s.id == recommendedId)
    }
    plan.forEachIndexed { i, p ->
        if (opps.none { it.id == p.stop.id }) {
            val d = plannedDistance(p, state) ?: return@forEachIndexed
            rows += TimelineRow.Stop(p.stop, d, p.arriveInS, p, i + 1, p.stop.id == recommendedId)
        }
    }
    restroomGaps(state).forEach { rows += TimelineRow.Gap(it) }
    state.congestion.forEach { rows += TimelineRow.Congestion(it) }
    state.remainDistanceM?.let { d -> rows += TimelineRow.Destination(state.destinationName, d, state.remainTimeS ?: d / speed) }
    return rows.sortedWith(compareBy<TimelineRow> { it.distanceM }.thenBy { order(it) })
}

private fun order(r: TimelineRow): Int = when (r) {
    TimelineRow.Now -> 0
    is TimelineRow.Stop -> 1
    is TimelineRow.Gap -> 2
    is TimelineRow.Congestion -> 3
    is TimelineRow.Destination -> 4
}

@Composable
fun PlanScreen(
    state: CoachUiState,
    planRequested: Boolean,
    onMakePlan: () -> Unit,
    onGoTo: (StopCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = LocalLayout.current
    val rows = timeline(state)
    if (layout.landscape) {
        Row(
            modifier.fillMaxSize().padding(start = layout.margin, end = layout.margin),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space.xxl),
        ) {
            Column(Modifier.width(640.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = layout.bottom)) {
                Spacer(Modifier.height(layout.gap))
                SummaryCard(state, planRequested, onMakePlan, onGoTo)
                Spacer(Modifier.height(layout.gap))
                StripCard(state)
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = layout.bottom)) {
                Spacer(Modifier.height(layout.gap))
                Timeline(rows, onGoTo)
            }
        }
    } else {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = layout.margin, end = layout.margin, bottom = layout.bottom),
        ) {
            Spacer(Modifier.height(layout.gap))
            SummaryCard(state, planRequested, onMakePlan, onGoTo)
            Spacer(Modifier.height(layout.gap))
            StripCard(state)
            Spacer(Modifier.height(layout.gap))
            Timeline(rows, onGoTo)
        }
    }
}

@Composable
private fun SummaryCard(state: CoachUiState, requested: Boolean, onMakePlan: () -> Unit, onGoTo: (StopCandidate) -> Unit) {
    val c = ShimpyoTheme.colors
    val layout = LocalLayout.current
    val plan = state.plan
    ShimpyoCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(layout.cardPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ShimpyoIcons.Flag, contentDescription = null, modifier = Modifier.size(40.dp), tint = c.iconSecondary)
            Spacer(Modifier.width(Dimens.Space.s))
            Text(
                state.destinationName?.let { "${it}까지" } ?: "목적지 없음",
                style = PleosType.TitleSmall,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Dimens.Space.xs))
        val remain = state.remainDistanceM
        Text(
            if (remain != null) {
                listOfNotNull(
                    "남은 ${Labels.distance(remain)}",
                    state.remainTimeS?.let { "도착까지 ${Labels.minutesOf(it)}" },
                ).joinToString(" · ")
            } else {
                "내비에서 목적지를 정하면 도착까지 계획해요"
            },
            style = PleosType.BodyLarge,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(Dimens.Space.l))
        Text(
            when {
                plan == null -> "아직 계획이 없어요"
                plan.stops.isEmpty() -> "쉬지 않고 가도 돼요"
                else -> "정차 ${plan.stops.size}번 · 쉬는 시간 ${Labels.duration(plan.totalStopMin)}"
            },
            style = PleosType.TitleMedium,
            color = c.textPrimary,
        )
        plan?.notes?.forEach { note ->
            Spacer(Modifier.height(Dimens.Space.xs))
            Row(verticalAlignment = Alignment.Top) {
                Icon(ShimpyoIcons.Info, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(Dimens.Icon.small), tint = c.iconTertiary)
                Spacer(Modifier.width(Dimens.Space.xs))
                Text(note.text, style = PleosType.BodyMedium, color = c.textSecondary)
            }
        }
        Spacer(Modifier.height(Dimens.Space.l))
        // 카드 안 전체 폭 Filled 하나 (다시 계산은 헤더의 [새로])
        val first = plan?.stops?.firstOrNull()
        when {
            requested -> PleosButton("계획을 세우는 중", {}, Modifier.fillMaxWidth(), filled = true, icon = ShimpyoIcons.Route, enabled = false)
            first != null -> PleosButton("첫 정차 경유지로 넣기", { onGoTo(first.stop) }, Modifier.fillMaxWidth(), filled = true, icon = ShimpyoIcons.Navigation)
            else -> PleosButton("계획 세우기", onMakePlan, Modifier.fillMaxWidth(), filled = true, icon = ShimpyoIcons.Route)
        }
    }
}

@Composable
private fun StripCard(state: CoachUiState) {
    val c = ShimpyoTheme.colors
    val layout = LocalLayout.current
    val data = stripData(state)
    ShimpyoCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(layout.cardPadding)) {
        Text("경로 앞 (거리 비례)", style = PleosType.BodyMediumStrong, color = c.textSecondary)
        Spacer(Modifier.height(Dimens.Space.s))
        if (data.isEmpty) {
            Text("이 경로에는 화장실 기회 정보가 없어요", style = PleosType.BodyMedium, color = c.textSecondary)
        } else {
            RouteStrip(data.axisM, data.stops, data.spans, data.destinationM, description = "경로 앞 화장실 기회, 정체, 계획 정차")
            Spacer(Modifier.height(Dimens.Space.xs))
            StripLegend()
        }
    }
}

// ------------------------------------------------------------ 타임라인

private val RailColumn = 72.dp

@Composable
private fun Timeline(rows: List<TimelineRow>, onGoTo: (StopCandidate) -> Unit) {
    val c = ShimpyoTheme.colors
    Column(Modifier.fillMaxWidth()) {
        if (rows.none { it is TimelineRow.Stop }) {
            TimelineLine(RailKind.ROUTE, top = true, bottom = true, marker = {}) {
                Text("이 경로에는 화장실 기회 정보가 없어요", style = PleosType.BodyMedium, color = c.textSecondary, modifier = Modifier.padding(vertical = Dimens.Space.l))
            }
        }
        rows.forEachIndexed { i, row ->
            val first = i == 0
            val last = i == rows.lastIndex
            when (row) {
                TimelineRow.Now -> TimelineLine(RailKind.ROUTE, top = false, bottom = !last, marker = { NowMarker() }) {
                    RowText("지금", "0km", null, strong = true)
                }
                is TimelineRow.Stop -> StopLine(row, first, last, onGoTo)
                is TimelineRow.Gap -> TimelineLine(RailKind.GAP, top = !first, bottom = !last, marker = {}) {
                    EventText(
                        "화장실 공백 ${Labels.duration(row.gap.minutes)}",
                        if (row.gap.congested) "정체를 지나는 동안 화장실이 없어요" else "다음 화장실까지 ${Labels.distance(row.gap.endM - row.gap.startM)}",
                    )
                }
                is TimelineRow.Congestion -> TimelineLine(RailKind.CONGESTION, top = !first, bottom = !last, marker = { EventMarker(c.timelineCongestion) }) {
                    val seg = row.segment
                    EventText(
                        "정체 ${Labels.duration((seg.durationS / 60.0).roundToInt())}",
                        listOfNotNull(congestionPlace(seg), "${Labels.distance(seg.startDistanceM.coerceAtLeast(0.0))} 앞부터 ${Labels.distance(seg.lengthM)}").joinToString(" · "),
                    )
                }
                is TimelineRow.Destination -> TimelineLine(RailKind.ROUTE, top = !first, bottom = false, marker = { DestinationMarker() }) {
                    RowText(row.name ?: "목적지", Labels.distance(row.distanceM), row.etaS?.let { Labels.minutesOf(it) }, strong = true)
                }
            }
        }
    }
}

@Composable
private fun StopLine(row: TimelineRow.Stop, first: Boolean, last: Boolean, onGoTo: (StopCandidate) -> Unit) {
    val c = ShimpyoTheme.colors
    val p = row.planned
    TimelineLine(
        RailKind.ROUTE,
        top = !first,
        bottom = !last,
        marker = {
            when {
                row.number != null -> PlannedMarker(row.number)
                row.recommended -> RecommendedMarker()
                else -> OpportunityMarker()
            }
        },
    ) {
        Column(Modifier.padding(vertical = Dimens.Space.m)) {
            RowText(row.stop.name, Labels.distance(row.distanceM), row.etaS?.let { Labels.minutesOf(it) }, strong = p != null)
            Spacer(Modifier.height(Dimens.Space.xxs))
            Text(
                facilitiesLine(row.stop),
                style = PleosType.BodySmall,
                color = c.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (p != null) {
                Spacer(Modifier.height(Dimens.Space.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChipFlow(Modifier.weight(1f), spacing = Dimens.Space.xs, maxLines = 2) {
                        Labels.sortedPurposes(p.purposes).forEach { SmallChip(Labels.purposeName(it), icon = Labels.purposeIcon(it)) }
                        SmallChip("${p.dwellMin}분", icon = ShimpyoIcons.Schedule, container = Color.Transparent, content = c.textSecondary)
                    }
                    Spacer(Modifier.width(Dimens.Space.s))
                    PleosButton("경유지로", { onGoTo(row.stop) }, size = ButtonSize.Small)
                }
            } else if (row.recommended) {
                Spacer(Modifier.height(Dimens.Space.s))
                PleosButton("경유지로", { onGoTo(row.stop) }, size = ButtonSize.Small)
            }
        }
    }
}

private fun facilitiesLine(s: StopCandidate): String {
    val f = s.facilities
    val items = buildList {
        add(Labels.kindName(s.kind))
        if (f.food) add("식당")
        if (f.fuel) add("주유")
        if (f.evCharger) add("충전")
        if (f.diaperTable) add("교환대")
        if (f.nursingRoom) add("수유실")
        if (f.petArea) add("반려동물")
        if (f.open24h) add("24시간")
    }
    return items.joinToString(" · ")
}

@Composable
private fun RowText(title: String, distance: String, time: String?, strong: Boolean) {
    val c = ShimpyoTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = if (strong) PleosType.TitleSmall else PleosType.TitleSmallRegular,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Dimens.Space.m))
        Text(distance, style = PleosType.BodyMedium.copy(fontFeatureSettings = "tnum"), color = c.textSecondary, maxLines = 1)
        if (time != null) {
            Spacer(Modifier.width(Dimens.Space.l))
            Text(time, style = PleosType.BodyMediumStrong.copy(fontFeatureSettings = "tnum"), color = c.textPrimary, maxLines = 1)
        }
    }
}

@Composable
private fun EventText(title: String, detail: String) {
    val c = ShimpyoTheme.colors
    Column(Modifier.padding(vertical = Dimens.Space.s)) {
        Text(title, style = PleosType.BodyMediumStrong, color = c.textPrimary)
        Text(detail, style = PleosType.BodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private enum class RailKind { ROUTE, GAP, CONGESTION }

/** 레일(폭 8) + 표식 + 내용 한 줄. 레일은 줄 높이만큼 이어진다 */
@Composable
private fun TimelineLine(kind: RailKind, top: Boolean, bottom: Boolean, marker: @Composable () -> Unit, content: @Composable () -> Unit) {
    val c = ShimpyoTheme.colors
    val railColor = when (kind) {
        RailKind.ROUTE -> c.timelineRoute
        RailKind.GAP -> c.timelineGap
        RailKind.CONGESTION -> c.timelineCongestion
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 64.dp)) {
        Box(Modifier.width(RailColumn).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 8.dp.toPx()
                val x = size.width / 2f
                val startY = if (top) 0f else size.height / 2f
                val endY = if (bottom) size.height else size.height / 2f
                if (endY > startY) {
                    drawLine(railColor, Offset(x, startY), Offset(x, endY), strokeWidth = w)
                }
            }
            marker()
        }
        Spacer(Modifier.width(Dimens.Space.m))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { content() }
    }
}

@Composable
private fun NowMarker() {
    val c = ShimpyoTheme.colors
    Canvas(Modifier.size(32.dp)) {
        drawCircle(c.iconPrimary, radius = size.minDimension / 2f)
        drawCircle(c.cardContainer, radius = size.minDimension / 5f)
    }
}

@Composable
private fun OpportunityMarker() {
    val c = ShimpyoTheme.colors
    Canvas(Modifier.size(28.dp)) {
        drawCircle(c.backgroundPrimary, radius = size.minDimension / 2f)
        drawCircle(c.timelineOpportunity, radius = size.minDimension / 2f - 2.dp.toPx(), style = Stroke(4.dp.toPx()))
    }
}

@Composable
private fun RecommendedMarker() {
    val c = ShimpyoTheme.colors
    Canvas(Modifier.size(36.dp)) {
        drawCircle(c.timelineOpportunity, radius = size.minDimension / 2f)
        drawCircle(c.cardContainer, radius = size.minDimension / 6f)
    }
}

@Composable
private fun PlannedMarker(number: Int) {
    val c = ShimpyoTheme.colors
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawCircle(c.timelinePlannedStop) }
        Text(number.toString(), style = PleosType.BodyMediumStrong, color = c.textOnFilledPrimary)
    }
}

@Composable
private fun EventMarker(color: Color, size: Dp = 24.dp) {
    val c = ShimpyoTheme.colors
    Canvas(Modifier.size(size)) {
        drawCircle(c.backgroundPrimary, radius = this.size.minDimension / 2f)
        drawCircle(color, radius = this.size.minDimension / 2f - 3.dp.toPx())
    }
}

@Composable
private fun DestinationMarker() {
    val c = ShimpyoTheme.colors
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawCircle(c.iconPrimary) }
        Icon(ShimpyoIcons.Flag, contentDescription = "목적지", modifier = Modifier.size(28.dp), tint = c.cardContainer)
    }
}
