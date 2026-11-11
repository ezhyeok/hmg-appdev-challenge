package dev.shimpyo.app.ui.history

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shimpyo.app.ui.Labels
import dev.shimpyo.app.ui.LocalLayout
import dev.shimpyo.app.ui.components.EmptyMessage
import dev.shimpyo.app.ui.components.ListDivider
import dev.shimpyo.app.ui.components.ListGroup
import dev.shimpyo.app.ui.components.SectionTitle
import dev.shimpyo.app.ui.components.ShimpyoCard
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.components.SmallChip
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.PleosType
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.coach.StopLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/*
 * 5. 휴식 기록 (screens.md 5장, 정차 중에만). 기어 P와 문 열림으로 자동 기록한 정차와 연속 주행 시간을 보여 준다.
 */

/** 기록에서 뽑은 요약 */
@Immutable
data class HistoryStats(
    val stops: Int,
    val restroomStops: Int,
    /** 정차 사이 연속 주행(분), 오래된 것부터. 6시간 넘게 떨어진 정차는 다른 여정으로 본다 */
    val stints: List<Int>,
) {
    val averageStint: Int? get() = stints.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
}

fun historyStats(history: List<StopLog>): HistoryStats {
    val sorted = history.sortedBy { it.start }
    val stints = sorted.zipWithNext().mapNotNull { (a, b) ->
        val min = ((b.start - a.end) / 60_000.0).roundToInt()
        min.takeIf { it in 1..MAX_STINT_MIN }
    }
    return HistoryStats(sorted.size, sorted.count { it.restroom }, stints.takeLast(CHART_BARS))
}

@Composable
fun HistoryScreen(history: List<StopLog>, modifier: Modifier = Modifier, lastStop: StopLog? = null) {
    // 기록 시각은 서비스 시계(시연 중에는 빨리 갈 수 있다)라서 '오늘'은 실제 시각과 가장 최근 기록 중 늦은 쪽으로 정한다
    // 기록이 그대로면 이 화면은 다시 그려지지 않으므로(강한 건너뛰기) 시계는 1분마다 따로 갱신한다
    val wallMs by produceState(System.currentTimeMillis()) { while (true) { delay(60_000); value = System.currentTimeMillis() } }
    val nowMs = maxOf(wallMs, history.maxOfOrNull { it.end } ?: 0L)
    val layout = LocalLayout.current
    val stats = remember(history) { historyStats(history) }
    if (history.isEmpty()) {
        Box(modifier.fillMaxSize().padding(layout.margin), contentAlignment = Alignment.Center) {
            EmptyMessage("아직 기록이 없어요", detail = "차를 세우고(P) 문을 열면 자동으로 기록돼요", icon = ShimpyoIcons.History)
        }
        return
    }
    if (layout.landscape) {
        Row(modifier.fillMaxSize().padding(horizontal = layout.margin), horizontalArrangement = Arrangement.spacedBy(Dimens.Space.xxl)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = layout.bottom)) {
                Spacer(Modifier.height(layout.gap))
                LastStopCard(lastStop ?: history.maxByOrNull { it.end }, nowMs)
                Spacer(Modifier.height(layout.gap))
                Insights(stats)
                Spacer(Modifier.height(layout.gap))
                StintChart(stats)
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = layout.bottom)) {
                StopList(history, nowMs)
            }
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = layout.margin, end = layout.margin, bottom = layout.bottom)) {
            Spacer(Modifier.height(layout.gap))
            LastStopCard(lastStop ?: history.maxByOrNull { it.end }, nowMs)
            Spacer(Modifier.height(layout.gap))
            Insights(stats)
            Spacer(Modifier.height(layout.gap))
            StintChart(stats)
            StopList(history, nowMs)
        }
    }
}

/** 마지막 정차: 언제, 어디서, 얼마나, 화장실이었는지 */
@Composable
private fun LastStopCard(log: StopLog?, nowMs: Long) {
    if (log == null) return
    val c = ShimpyoTheme.colors
    val minutes = ((log.end - log.start) / 60_000.0).roundToInt().coerceAtLeast(1)
    val ago = ((nowMs - log.end) / 60_000.0).roundToInt()
    ShimpyoCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(LocalLayout.current.cardPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("마지막 정차", style = PleosType.BodyMediumStrong, color = c.textSecondary, modifier = Modifier.weight(1f))
            if (ago in 0..(24 * 60)) Text(if (ago < 1) "방금" else "${Labels.duration(ago)} 전", style = PleosType.BodyMedium, color = c.textSecondary)
        }
        Spacer(Modifier.height(Dimens.Space.xxs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                log.name ?: "이름 없는 정차",
                style = PleosType.TitleMedium,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(Dimens.Space.m))
            SmallChip(if (log.restroom) "화장실" else if (minutes >= Labels.config.restMinParkedMin) "휴식" else "정차", icon = if (log.restroom) ShimpyoIcons.Wc else ShimpyoIcons.Rest)
        }
        Spacer(Modifier.height(Dimens.Space.xxs))
        Text("${Labels.clock(log.start)}부터 ${minutes}분 머묾", style = PleosType.BodyMedium, color = c.textSecondary)
    }
}

@Composable
private fun Insights(stats: HistoryStats) {
    val c = ShimpyoTheme.colors
    if (stats.stops < MIN_FOR_INSIGHT) {
        ShimpyoCard(Modifier.fillMaxWidth()) {
            Text("${MIN_FOR_INSIGHT - stats.stops}번 더 쉬면 습관을 알려 드릴게요", style = PleosType.TitleSmall, color = c.textPrimary)
            Spacer(Modifier.height(Dimens.Space.xxs))
            Text("지금까지 ${stats.stops}번 기록했어요", style = PleosType.BodyMedium, color = c.textSecondary)
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Layout.gutter)) {
        InsightCard("정차", "${stats.stops}번", "화장실 ${stats.restroomStops}번", Modifier.weight(1f))
        InsightCard(
            "평균 연속 주행",
            stats.averageStint?.let { Labels.duration(it) } ?: "--",
            if (stats.stints.isNotEmpty()) "최근 ${stats.stints.size}번 기준" else "연속 주행 기록 없음",
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun InsightCard(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    val c = ShimpyoTheme.colors
    ShimpyoCard(modifier, contentPadding = PaddingValues(LocalLayout.current.cardPadding)) {
        Text(title, style = PleosType.BodyMediumStrong, color = c.textSecondary)
        Spacer(Modifier.height(Dimens.Space.xxs))
        Text(value, style = PleosType.TitleMedium.copy(fontFeatureSettings = "tnum"), color = c.textPrimary, maxLines = 1)
        Spacer(Modifier.height(Dimens.Space.xxs))
        Text(detail, style = PleosType.BodyMedium, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 최근 8번의 연속 주행 막대. 120분 점선, 막대 색은 피로 단계(100분 SOON, 120분 NOW) */
@Composable
private fun StintChart(stats: HistoryStats) {
    val c = ShimpyoTheme.colors
    if (stats.stints.isEmpty()) return
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val soon = Labels.config.fatigueSoonMin
    val now = Labels.config.fatigueNowMin
    ShimpyoCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(LocalLayout.current.cardPadding)) {
        Text("연속 주행 시간 (최근 ${stats.stints.size}번)", style = PleosType.BodyMediumStrong, color = c.textSecondary)
        Spacer(Modifier.height(Dimens.Space.m))
        val values = stats.stints
        Canvas(Modifier.fillMaxWidth().height(240.dp)) {
            val labelH = 32.dp.toPx()
            val chartH = size.height - labelH
            val top = 8.dp.toPx()
            val maxV = max(150, values.max() + 10).toFloat()
            fun y(v: Int) = top + (chartH - top) * (1f - v / maxV)
            val slot = size.width / max(values.size, MIN_SLOTS)
            val barW = slot * 0.56f
            // 120분 점선과 라벨
            val ly = y(now)
            drawLine(c.textTertiary, Offset(0f, ly), Offset(size.width, ly), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            val t120 = measurer.measure("${now}분", PleosType.LabelMedium)
            drawText(t120, color = c.textTertiary, topLeft = Offset(size.width - t120.size.width, ly - t120.size.height - 4f))
            values.forEachIndexed { i, v ->
                val x = slot * i + (slot - barW) / 2f
                val color = when {
                    v >= now -> c.gaugeNow
                    v >= soon -> c.gaugeSoon
                    else -> c.gaugeOk
                }
                val by = y(v)
                drawRoundRect(color, Offset(x, by), Size(barW, chartH - by), CornerRadius(8.dp.toPx()))
                val t = measurer.measure("$v", PleosType.LabelMedium)
                drawText(t, color = c.textSecondary, topLeft = Offset(x + barW / 2f - t.size.width / 2f, chartH + 4.dp.toPx()))
            }
        }
    }
}

@Composable
private fun StopList(history: List<StopLog>, nowMs: Long) {
    val c = ShimpyoTheme.colors
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    history.sortedByDescending { it.start }
        .groupBy { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }
        .forEach { (day, logs) ->
            SectionTitle(dayLabel(day, today))
            ListGroup {
                logs.forEachIndexed { i, log ->
                    if (i > 0) ListDivider()
                    val minutes = ((log.end - log.start) / 60_000.0).roundToInt().coerceAtLeast(1)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.Component.listItemHeight).padding(horizontal = Dimens.Space.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            Labels.clock(log.start, zone),
                            style = PleosType.TitleSmall.copy(fontFeatureSettings = "tnum"),
                            color = c.textPrimary,
                            modifier = Modifier.width(120.dp),
                        )
                        Text(
                            log.name ?: "이름 없는 정차",
                            style = PleosType.BodyLarge,
                            color = if (log.name != null) c.textPrimary else c.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(Dimens.Space.m))
                        Text("${minutes}분", style = PleosType.BodyMedium.copy(fontFeatureSettings = "tnum"), color = c.textSecondary)
                        Spacer(Modifier.width(Dimens.Space.m))
                        val (kind, icon) = when {
                            log.restroom -> "화장실" to ShimpyoIcons.Wc
                            minutes >= Labels.config.restMinParkedMin -> "휴식" to ShimpyoIcons.Rest
                            else -> "정차" to ShimpyoIcons.Parking
                        }
                        SmallChip(kind, icon = icon)
                    }
                }
            }
        }
    Spacer(Modifier.height(Dimens.Space.xl))
}

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "오늘"
    today.minusDays(1) -> "어제"
    else -> "${day.monthValue}월 ${day.dayOfMonth}일"
}

private const val MAX_STINT_MIN = 6 * 60
private const val CHART_BARS = 8
private const val MIN_FOR_INSIGHT = 3
private const val MIN_SLOTS = 5
