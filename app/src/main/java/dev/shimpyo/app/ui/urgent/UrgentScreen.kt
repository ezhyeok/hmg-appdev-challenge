package dev.shimpyo.app.ui.urgent

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shimpyo.app.ui.Labels
import dev.shimpyo.app.ui.LocalLayout
import dev.shimpyo.app.ui.components.ButtonGap
import dev.shimpyo.app.ui.components.ChipFlow
import dev.shimpyo.app.ui.components.EmptyMessage
import dev.shimpyo.app.ui.components.ListDivider
import dev.shimpyo.app.ui.components.ListGroup
import dev.shimpyo.app.ui.components.PageHeader
import dev.shimpyo.app.ui.components.PleosButton
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.components.SmallChip
import dev.shimpyo.app.ui.components.ToggleButton
import dev.shimpyo.app.ui.components.VoiceButton
import dev.shimpyo.app.ui.components.pressable
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.DriveLimits
import dev.shimpyo.app.ui.theme.DriveType
import dev.shimpyo.app.ui.theme.PleosType
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.coach.VoiceState
import dev.shimpyo.engine.RankedStop
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind

/** 급해 결과 상태 */
enum class UrgentStatus { LOADING, FAILED, READY }

/**
 * 3. 급해 결과 (screens.md 3장). 엔진이 매긴 순위를 그대로 보여 준다.
 *
 * 주행 중: 최대 2곳([DriveLimits.MAX_URGENT_RESULTS]), 카드 전체가 하나의 터치 영역(높이 220 이상).
 * 1순위는 Filled, 2순위는 Basic. 자동으로 넘기거나 회전하지 않고, 지도는 그리지 않는다.
 * 정차 중: 필터(교환대·24시간·주차 가능)와 시설 줄이 있는 목록.
 */
@Composable
fun UrgentScreen(
    results: List<RankedStop>?,
    status: UrgentStatus,
    driving: Boolean,
    pickingId: String?,
    voice: VoiceState,
    voiceEnabled: Boolean,
    onPick: (RankedStop) -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onListen: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    badgeShort: String? = null,
) {
    val layout = LocalLayout.current
    val c = ShimpyoTheme.colors
    Column(modifier.fillMaxSize().background(c.backgroundPrimary)) {
        PageHeader(
            badge = badge,
            badgeShort = badgeShort,
            title = "급해요",
            height = layout.headerHeight(driving),
            margin = layout.margin,
            // 주행 중에는 아래 [닫기]와 겹치는 뒤로 버튼을 빼서 터치 요소를 4개(마이크, 1·2순위, 닫기)로 둔다
            onBack = if (driving) null else onClose,
            trailing = { VoiceButton(voice, voiceEnabled, onListen) },
        )
        val body = Modifier.fillMaxWidth().weight(1f).padding(start = layout.margin, end = layout.margin, bottom = layout.bottom)
        when {
            status == UrgentStatus.LOADING -> Notice(body, "가까운 화장실을 찾는 중이에요", "진행 방향, 주차, 영업 여부를 보고 있어요", null, onClose, null)
            status == UrgentStatus.FAILED -> Notice(body, "위치를 확인할 수 없어요", "잠시 후 다시 시도해 주세요", "다시 시도", onClose, onRetry)
            results.isNullOrEmpty() -> Notice(body, "가까운 곳에 열린 화장실이 없어요", "다음 휴게소까지 기다려 주세요", null, onClose, null)
            driving -> DrivingResults(results.take(DriveLimits.MAX_URGENT_RESULTS), pickingId, onPick, onClose, body)
            else -> ParkedResults(results, pickingId, onPick, body)
        }
    }
}

@Composable
private fun Notice(modifier: Modifier, title: String, detail: String, actionText: String?, onClose: () -> Unit, onAction: (() -> Unit)?) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            EmptyMessage(title, detail = detail, icon = ShimpyoIcons.Wc)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGap)) {
            if (actionText != null && onAction != null) {
                PleosButton(actionText, onAction, Modifier.weight(1f), filled = true, labelStyle = DriveType.ActionLabel)
            }
            PleosButton("닫기", onClose, Modifier.weight(1f), labelStyle = DriveType.ActionLabel)
        }
    }
}

@Composable
private fun DrivingResults(results: List<RankedStop>, pickingId: String?, onPick: (RankedStop) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val layout = LocalLayout.current
    Column(modifier) {
        Text("진행 방향 · 주차 가능 · 지금 열린 곳을 빠른 순으로", style = DriveType.Minimum, color = ShimpyoTheme.colors.textSecondary, maxLines = 1)
        Spacer(Modifier.height(Dimens.Space.m))
        // 두 카드가 남는 높이를 나눠 가진다 (주행 중 터치 영역은 클수록 좋다). 1곳이면 2순위 자리는 비워 둔다
        val slots: @Composable (Modifier) -> Unit = { cell ->
            for (i in 0 until DriveLimits.MAX_URGENT_RESULTS) {
                val r = results.getOrNull(i)
                if (r != null) {
                    UrgentResultCard(i + 1, r, primary = i == 0, picking = pickingId == r.stop.id, onClick = { onPick(r) }, modifier = cell)
                } else {
                    Spacer(cell)
                }
            }
        }
        if (layout.landscape) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(ButtonGap)) {
                slots(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(ButtonGap)) {
                slots(Modifier.weight(1f).fillMaxWidth())
            }
        }
        Spacer(Modifier.height(ButtonGap))
        PleosButton("닫기", onClose, Modifier.fillMaxWidth(), labelStyle = DriveType.ActionLabel)
    }
}

/** 주행 중 결과 카드: 순위, 종류 아이콘, 이름(40), "3분 · 1.1km · 진행 방향"(30), 배지 2개까지, "여기로 >"(40) */
@Composable
private fun UrgentResultCard(rank: Int, r: RankedStop, primary: Boolean, picking: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = ShimpyoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        primary && pressed -> c.buttonFilledPressed
        primary -> c.buttonFilledEnabled
        pressed -> c.buttonBasicPressed
        else -> c.buttonBasicEnabled
    }
    val fg = if (primary) c.textOnFilledPrimary else c.textPrimary
    val fg2 = if (primary) c.textOnFilledSecondary else c.textSecondary
    val chipBg = if (primary) c.textOnFilledPrimary.copy(alpha = 0.16f) else c.cardContainer
    Column(
        modifier
            .heightIn(min = Dimens.Drive.urgentResultCardMinHeight)
            .pressable(interaction, enabled = !picking, cornerRadius = Dimens.Radius.l, onClick = onClick)
            .clip(RoundedCornerShape(Dimens.Radius.l))
            .background(bg)
            .padding(Dimens.Space.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RankMark(rank, primary)
            Spacer(Modifier.width(Dimens.Space.m))
            Icon(Labels.kindIcon(r.stop.kind), contentDescription = Labels.kindName(r.stop.kind), modifier = Modifier.size(Dimens.Icon.base), tint = fg)
            Spacer(Modifier.width(Dimens.Space.s))
            Text(r.stop.name, style = DriveType.StopName, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(Dimens.Space.s))
        Text(urgentMeta(r), style = DriveType.StopMeta, color = fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        urgentBreakdown(r)?.let { Text(it, style = DriveType.Minimum, color = fg2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(Dimens.Space.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipFlow(Modifier.weight(1f), maxLines = 1) {
                urgentBadges(r).take(2).forEach { badge ->
                    Box(
                        Modifier.height(Dimens.Component.chipHeight).clip(RoundedCornerShape(Dimens.Radius.s)).background(chipBg)
                            .padding(horizontal = Dimens.Component.chipPaddingHorizontal),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(badge, style = DriveType.ReasonChip, color = fg, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.width(Dimens.Space.m))
            Text(if (picking) "넣는 중" else "여기로", style = DriveType.ActionLabel, color = fg, maxLines = 1)
            if (!picking) Icon(ShimpyoIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(Dimens.Icon.base), tint = fg)
        }
    }
}

@Composable
private fun RankMark(rank: Int, primary: Boolean) {
    val c = ShimpyoTheme.colors
    Box(
        Modifier.size(Dimens.Icon.base).clip(CircleShape).background(if (primary) c.textOnFilledPrimary else c.buttonFilledEnabled),
        contentAlignment = Alignment.Center,
    ) {
        Text(rank.toString(), style = PleosType.BodyLargeStrong, color = if (primary) c.buttonFilledEnabled else c.textOnFilledPrimary)
    }
}

/** "3분 · 1.1km · 진행 방향": 총 소요(주행 + 주차·도보 + 유턴) 먼저 */
private fun urgentMeta(r: RankedStop): String = listOfNotNull(
    Labels.minutesOf(r.totalTimeS),
    Labels.stopDistance(r.stop),
    when (r.stop.directionOk) {
        true -> "진행 방향"
        false -> "유턴 필요"
        null -> null
    },
).joinToString(" · ")

/** 총 소요의 구성: 엔진 이유 "약 3분 (주행 2분, 주차·도보 1분)" → "주행 2분 + 주차·도보 1분" */
private fun urgentBreakdown(r: RankedStop): String? =
    r.why.firstOrNull { it.code == "URGENT_TIME" }?.text
        ?.substringAfter('(', "")?.substringBefore(')')?.takeIf { it.isNotBlank() }
        ?.replace(", ", " + ")

/**
 * 상황에 맞는 배지. 엔진이 붙인 이유(영아 동승이면 교환대, 밤이면 비상벨·CCTV)를 앞에 두고,
 * 그 외에는 24시간·주차 가능.
 */
private fun urgentBadges(r: RankedStop): List<String> {
    val order = listOf("URGENT_DIAPER", "URGENT_SAFETY", "URGENT_OPEN_24H", "URGENT_OPEN")
    val fromWhy = r.why.filter { it.code in order }.sortedBy { order.indexOf(it.code) }.mapNotNull { Labels.shortReason(it) }
    return buildList {
        addAll(fromWhy)
        if (r.stop.parkable && r.stop.kind != StopKind.HIGHWAY_REST_AREA) add("주차 가능")
        if (r.why.any { it.code == "URGENT_OPEN_UNKNOWN" }) add("영업 여부 모름")
    }.distinct()
}

// ------------------------------------------------------------ 정차 중 목록

@Composable
private fun ParkedResults(results: List<RankedStop>, pickingId: String?, onPick: (RankedStop) -> Unit, modifier: Modifier) {
    var diaper by rememberSaveable { mutableStateOf(false) }
    var open24 by rememberSaveable { mutableStateOf(false) }
    var parking by rememberSaveable { mutableStateOf(false) }
    val shown = results.filter { r ->
        (!diaper || r.stop.facilities.diaperTable) && (!open24 || r.stop.facilities.open24h) && (!parking || r.stop.parkable)
    }
    Column(modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(Dimens.Space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space.m)) {
            ToggleButton("교환대", diaper, { diaper = !diaper }, icon = ShimpyoIcons.BabyChanging)
            ToggleButton("24시간", open24, { open24 = !open24 }, icon = ShimpyoIcons.Schedule)
            ToggleButton("주차 가능", parking, { parking = !parking }, icon = ShimpyoIcons.Parking)
        }
        Spacer(Modifier.height(Dimens.Space.l))
        if (shown.isEmpty()) {
            EmptyMessage("조건에 맞는 곳이 없어요", Modifier.fillMaxWidth().padding(vertical = Dimens.Space.huge), detail = "필터를 줄여 보세요")
        } else {
            ListGroup {
                shown.forEachIndexed { i, r ->
                    if (i > 0) ListDivider()
                    ParkedRow(results.indexOf(r) + 1, r, pickingId == r.stop.id) { onPick(r) }
                }
            }
        }
        Spacer(Modifier.height(Dimens.Space.xl))
    }
}

/** 목록 항목(세 줄): 순위·이름·총 소요 / 거리·방향·영업 / 시설(있음·없음)과 주차 형태 */
@Composable
private fun ParkedRow(rank: Int, r: RankedStop, picking: Boolean, onClick: () -> Unit) {
    val c = ShimpyoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s = r.stop
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp)
            .pressable(interaction, enabled = !picking, cornerRadius = Dimens.Radius.m, onClick = onClick)
            .clip(RoundedCornerShape(Dimens.Radius.m))
            .background(if (pressed) c.buttonBasicPressed else Color.Transparent)
            .padding(horizontal = Dimens.Space.l, vertical = Dimens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankMark(rank, primary = false)
        Spacer(Modifier.width(Dimens.Space.l))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Labels.kindIcon(s.kind), contentDescription = null, modifier = Modifier.size(Dimens.Icon.small), tint = c.iconSecondary)
                Spacer(Modifier.width(Dimens.Space.xs))
                Text(s.name, style = PleosType.TitleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(Dimens.Space.xxs))
            Text(
                listOfNotNull(Labels.stopDistance(s), Labels.stopPosition(s), openWord(s)).joinToString(" · "),
                style = PleosType.BodyMedium,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Dimens.Space.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space.s), verticalAlignment = Alignment.CenterVertically) {
                Facility("교환대", s.facilities.diaperTable)
                Facility("비상벨", s.facilities.emergencyBell)
                Facility("CCTV", s.facilities.cctv)
                Text(if (s.parkable) "주차 가능" else "주차 불가", style = PleosType.LabelMedium, color = c.textSecondary)
            }
        }
        Spacer(Modifier.width(Dimens.Space.m))
        Column(horizontalAlignment = Alignment.End) {
            Text(if (picking) "넣는 중" else Labels.minutesOf(r.totalTimeS), style = PleosType.TitleMedium, color = c.textPrimary)
            Icon(ShimpyoIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(Dimens.Icon.base), tint = c.iconTertiary)
        }
    }
}

/** 시설 있음(진한 글자)·없음(흐린 글자 + 취소선). 색만으로 구분하지 않는다 */
@Composable
private fun Facility(name: String, present: Boolean) {
    val c = ShimpyoTheme.colors
    SmallChip(
        text = name,
        icon = if (present) ShimpyoIcons.Check else ShimpyoIcons.Close,
        container = if (present) c.surfaceHigh else Color.Transparent,
        content = if (present) c.textPrimary else c.textTertiary,
        style = PleosType.LabelMedium.copy(textDecoration = if (present) null else TextDecoration.LineThrough),
    )
}

private fun openWord(s: StopCandidate): String = when {
    s.facilities.open24h -> "24시간"
    s.openNow == true -> "영업 중"
    s.openNow == false -> "영업 종료"
    else -> "영업 여부 모름"
}
