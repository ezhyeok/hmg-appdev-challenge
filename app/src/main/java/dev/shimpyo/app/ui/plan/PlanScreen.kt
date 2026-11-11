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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import dev.shimpyo.app.ui.congestionDetail
import dev.shimpyo.app.ui.congestionLabel
import dev.shimpyo.app.ui.drive.StripLegend
import dev.shimpyo.app.ui.livePlan
import dev.shimpyo.app.ui.opportunities
import dev.shimpyo.app.ui.remainingCongestion
import dev.shimpyo.app.ui.restroomGaps
import dev.shimpyo.app.ui.routeSpeedMps
import dev.shimpyo.app.ui.stripData
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.PleosType
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.app.ui.travelSeconds
import dev.shimpyo.coach.CandidatesState
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.PlannedStop
import dev.shimpyo.engine.StopCandidate

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

    /**
     * @property estimated 거리를 어림했다 (지금 후보 목록에 없는 계획 정차) → "약"을 붙인다
     * @property etaS 지금 기준 도착까지 (초): 후보의 ETA, 없으면 평균 속도 + 정체 지연
     */
    data class Stop(
        val stop: StopCandidate,
        override val distanceM: Double,
        val etaS: Double?,
        val planned: PlannedStop?,
        val number: Int?,
        val recommended: Boolean,
        val estimated: Boolean = false,
    ) : TimelineRow

    /** [segment]는 남은 부분이다 (차가 정체 안이면 지금 위치부터, 시간도 남은 길이만큼) */
    data class Congestion(val segment: CongestionSegment) : TimelineRow {
        override val distanceM: Double get() = segment.startDistanceM
    }

    data class Gap(val gap: RestroomGap) : TimelineRow {
        override val distanceM: Double get() = gap.startM
    }

    data class Destination(val name: String?, override val distanceM: Double, val etaS: Double?) : TimelineRow
}

/**
 * 상태 → 타임라인. 같은 거리에서는 지금 → 정차 → 공백 → 정체 → 목적지 순.
 * 계획 정차는 지금 후보 값으로 그리고(livePlan), 지나친 정차와 목적지 뒤 후보는 뺀다 (UI 감사 #4).
 */
fun timeline(state: CoachUiState): List<TimelineRow> {
    val plan = livePlan(state)
    val planById = plan.associateBy { it.planned.stop.id }
    val recommendedId = (state.prompt?.stop ?: state.next?.stop)?.id
    val speed = routeSpeedMps(state)
    val rows = ArrayList<TimelineRow>()
    rows += TimelineRow.Now
    val opps = opportunities(state)
    opps.forEach { s ->
        val p = planById[s.id]
        val d = s.routeDistanceM!!
        rows += TimelineRow.Stop(s, d, s.etaS ?: travelSeconds(0.0, d, state.congestion, speed), p?.planned, p?.number, s.id == recommendedId)
    }
    plan.forEach { p ->
        if (opps.none { it.id == p.planned.stop.id }) {
            rows += TimelineRow.Stop(p.stop, p.distanceM, p.etaS, p.planned, p.number, p.planned.stop.id == recommendedId, p.estimated)
        }
    }
    restroomGaps(state).forEach { rows += TimelineRow.Gap(it) }
    state.congestion.forEach { c ->
        val r = remainingCongestion(c)
        if (r.lengthM > 0.0) rows += TimelineRow.Congestion(r)
    }
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

/** 계획 요약 카드의 글자 */
@Immutable
data class PlanSummary(val headline: String, val detail: String?, val notes: List<PlanNote>)

@Immutable
data class PlanNote(val text: String, val warning: Boolean)

/**
 * 요약 첫 줄은 엔진의 계획 메모(PitStopPlan.notes 코드)를 따른다. 정차가 없다고 "쉬지 않고 가도 돼요"라고 하지 않는다:
 * 후보가 없을 수도(PLAN_NO_CANDIDATES), 앞 일부만 계획했을 수도(PLAN_PARTIAL, PLAN_NO_DESTINATION) 있다 (UI 감사 #5).
 * 첫 줄로 쓴 메모는 아래 목록에서 뺀다. PLAN_NOW·PLAN_GAP·PLAN_FUEL_SHORT는 경고로 보인다.
 */
fun planSummary(state: CoachUiState): PlanSummary {
    val plan = state.plan
        ?: return PlanSummary("아직 계획이 없어요", candidatesWaiting(state.candidatesState), emptyList())
    val codes = plan.notes.map { it.code }.toSet()
    val live = livePlan(state)
    val count = if (live.isNotEmpty()) "정차 ${live.size}번 · 쉬는 시간 ${Labels.duration(live.sumOf { it.planned.dwellMin })}" else null
    val partial = "PLAN_PARTIAL" in codes || "PLAN_NO_DESTINATION" in codes
    var used: String? = null
    val (headline, detail) = when {
        "PLAN_NOW" in codes -> "지금 쉬어야 해요" to count
        live.isNotEmpty() -> count!! to null
        plan.stops.isNotEmpty() -> "지금 경로에 남은 계획 정차가 없어요" to "다시 계산해 주세요"
        "PLAN_NO_CANDIDATES" in codes -> {
            used = "PLAN_NO_CANDIDATES"
            "정차 후보를 찾지 못했어요" to candidatesWaiting(state.candidatesState)
        }
        "PLAN_NONE_NEEDED" in codes -> {
            used = "PLAN_NONE_NEEDED"
            (if (partial) "앞 후보까지는 쉬지 않아도 돼요" else "목적지까지 쉬지 않아도 돼요") to null
        }
        else -> "쉴 곳을 정하지 못했어요" to null
    }
    val notes = plan.notes.filter { it.code != used }.map { PlanNote(it.text, it.code in WARNING_NOTES) }
    return PlanSummary(headline, detail, notes)
}

private val WARNING_NOTES = setOf("PLAN_NOW", "PLAN_GAP", "PLAN_FUEL_SHORT")

/** 후보 목록이 아직 준비되지 않은 이유. 준비됐으면 null */
fun candidatesWaiting(s: CandidatesState): String? = when (s) {
    CandidatesState.LOADING_DATA -> "정차 데이터를 읽는 중이에요"
    CandidatesState.NO_LOCATION -> "위치를 확인하는 중이에요"
    CandidatesState.READY -> null
}

/** 경로 앞에 보일 정차 후보가 없을 때의 문구: 준비 전이면 그 이유, 준비됐으면 "정보가 없어요" */
fun noOpportunityText(s: CandidatesState): String = candidatesWaiting(s) ?: "이 경로에는 화장실 기회 정보가 없어요"

/**
 * @param goToPendingId 내비에 넣는 중인 곳. 결과가 올 때까지 같은 곳의 버튼을 막는다 (UI 감사 #24: 두 번 누르면 경유지 요청이 1초 안에 두 번 갔다)
 */
@Composable
fun PlanScreen(
    state: CoachUiState,
    planRequested: Boolean,
    goToPendingId: String?,
    onMakePlan: () -> Unit,
    onGoTo: (StopCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = LocalLayout.current
    val rows = timeline(state)
    val summary = planSummary(state)
    val first = livePlan(state).firstOrNull()?.stop
    if (layout.landscape) {
        Row(
            modifier.fillMaxSize().padding(start = layout.margin, end = layout.margin),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space.xxl),
        ) {
            Column(Modifier.width(640.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = layout.bottom)) {
                Spacer(Modifier.height(layout.gap))
                SummaryCard(state, summary, first, planRequested, goToPendingId, onMakePlan, onGoTo)
                Spacer(Modifier.height(layout.gap))
                StripCard(state)
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = layout.bottom)) {
                Spacer(Modifier.height(layout.gap))
                Timeline(rows, state.candidatesState, goToPendingId, onGoTo)
            }
        }
    } else {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = layout.margin, end = layout.margin, bottom = layout.bottom),
        ) {
            Spacer(Modifier.height(layout.gap))
            SummaryCard(state, summary, first, planRequested, goToPendingId, onMakePlan, onGoTo)
            Spacer(Modifier.height(layout.gap))
            StripCard(state)
            Spacer(Modifier.height(layout.gap))
            Timeline(rows, state.candidatesState, goToPendingId, onGoTo)
        }
    }
}

@Composable
private fun SummaryCard(
    state: CoachUiState,
    summary: PlanSummary,
    first: StopCandidate?,
    requested: Boolean,
    goToPendingId: String?,
    onMakePlan: () -> Unit,
    onGoTo: (StopCandidate) -> Unit,
) {
    val c = ShimpyoTheme.colors
    val layout = LocalLayout.current
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
        Text(summary.headline, style = PleosType.TitleMedium, color = c.textPrimary)
        summary.detail?.let {
            Spacer(Modifier.height(Dimens.Space.xxs))
            Text(it, style = PleosType.BodyLarge, color = c.textSecondary)
        }
        summary.notes.forEach { note ->
            Spacer(Modifier.height(Dimens.Space.xs))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    ShimpyoIcons.Info,
                    contentDescription = if (note.warning) "주의" else null,
                    modifier = Modifier.padding(top = 2.dp).size(Dimens.Icon.small),
                    tint = if (note.warning) c.iconPrimary else c.iconTertiary,
                )
                Spacer(Modifier.width(Dimens.Space.xs))
                Text(note.text, style = PleosType.BodyMedium, color = if (note.warning) c.textPrimary else c.textSecondary)
            }
        }
        Spacer(Modifier.height(Dimens.Space.l))
        // 카드 안 전체 폭 Filled 하나 (다시 계산은 헤더의 [새로])
        when {
            requested -> PleosButton("계획을 세우는 중", {}, Modifier.fillMaxWidth(), filled = true, icon = ShimpyoIcons.Route, enabled = false)
            first != null && goToPendingId == first.id ->
                PleosButton("내비에 넣는 중", {}, Modifier.fillMaxWidth(), filled = true, icon = ShimpyoIcons.Navigation, enabled = false)
            first != null -> PleosButton("첫 정차 경유지로 넣기", { onGoTo(first) }, Modifier.fillMaxWidth(), filled = true, icon = ShimpyoIcons.Navigation, enabled = goToPendingId == null)
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
            Text(noOpportunityText(state.candidatesState), style = PleosType.BodyMedium, color = c.textSecondary)
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
private fun Timeline(rows: List<TimelineRow>, candidates: CandidatesState, goToPendingId: String?, onGoTo: (StopCandidate) -> Unit) {
    val c = ShimpyoTheme.colors
    Column(Modifier.fillMaxWidth()) {
        if (rows.none { it is TimelineRow.Stop }) {
            TimelineLine(RailKind.ROUTE, top = true, bottom = true, marker = {}) {
                Text(noOpportunityText(candidates), style = PleosType.BodyMedium, color = c.textSecondary, modifier = Modifier.padding(vertical = Dimens.Space.l))
            }
        }
        rows.forEachIndexed { i, row ->
            val first = i == 0
            val last = i == rows.lastIndex
            when (row) {
                TimelineRow.Now -> TimelineLine(RailKind.ROUTE, top = false, bottom = !last, marker = { NowMarker() }) {
                    RowText("지금", "0km", null, strong = true)
                }
                is TimelineRow.Stop -> StopLine(row, first, last, goToPendingId, onGoTo)
                is TimelineRow.Gap -> TimelineLine(RailKind.GAP, top = !first, bottom = !last, marker = {}) {
                    EventText(
                        "화장실 공백 ${Labels.duration(row.gap.minutes)}",
                        when {
                            row.gap.congested -> "정체를 지나는 동안 화장실이 없어요"
                            row.gap.startM <= 0.0 -> "첫 화장실까지 ${Labels.distance(row.gap.endM)}"
                            else -> "다음 화장실까지 ${Labels.distance(row.gap.endM - row.gap.startM)}"
                        },
                    )
                }
                is TimelineRow.Congestion -> TimelineLine(RailKind.CONGESTION, top = !first, bottom = !last, marker = { EventMarker(c.timelineCongestion) }) {
                    // segment는 이미 남은 부분이다 (정체 안이면 시작 0 → "지금부터 12km")
                    EventText(congestionLabel(row.segment), congestionDetail(row.segment))
                }
                is TimelineRow.Destination -> TimelineLine(RailKind.ROUTE, top = !first, bottom = false, marker = { DestinationMarker() }) {
                    RowText(row.name ?: "목적지", Labels.distance(row.distanceM), row.etaS?.let { Labels.minutesOf(it) }, strong = true)
                }
            }
        }
    }
}

@Composable
private fun StopLine(row: TimelineRow.Stop, first: Boolean, last: Boolean, goToPendingId: String?, onGoTo: (StopCandidate) -> Unit) {
    val c = ShimpyoTheme.colors
    val p = row.planned
    val pending = goToPendingId == row.stop.id
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
            val distance = Labels.distance(row.distanceM).let { if (row.estimated) "약 $it" else it }
            RowText(row.stop.name, distance, row.etaS?.let { Labels.minutesOf(it) }, strong = p != null)
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
                    PleosButton(if (pending) "넣는 중" else "경유지로", { onGoTo(row.stop) }, size = ButtonSize.Small, enabled = goToPendingId == null)
                }
            } else if (row.recommended) {
                Spacer(Modifier.height(Dimens.Space.s))
                PleosButton(if (pending) "넣는 중" else "경유지로", { onGoTo(row.stop) }, size = ButtonSize.Small, enabled = goToPendingId == null)
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
