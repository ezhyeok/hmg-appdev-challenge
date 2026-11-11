package dev.shimpyo.app.ui.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shimpyo.app.coach.CoachUiState
import dev.shimpyo.app.ui.Labels
import dev.shimpyo.app.ui.LocalLayout
import dev.shimpyo.app.ui.components.ListDivider
import dev.shimpyo.app.ui.components.ListGroup
import dev.shimpyo.app.ui.components.SectionTitle
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.components.pressable
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.PleosType
import dev.shimpyo.app.ui.theme.ShimpyoTheme

/** 시연 시나리오 (DemoDirector의 이름과 같다) */
data class DemoScenario(val key: String?, val title: String, val detail: String, val icon: ImageVector)

val DemoScenarios: List<DemoScenario> = listOf(
    DemoScenario("holiday", "귀성길 정체", "옥산 뒤 1시간 정체", ShimpyoIcons.Traffic),
    DemoScenario("fatigue", "오후 휴식 권유", "오후 3시 · 95분째", ShimpyoIcons.Rest),
    DemoScenario("urgent", "도심 급해", "밤 11시 · 영아 동승", ShimpyoIcons.Wc),
    DemoScenario("stop", "휴게소 정차", "10배속 · 회복", ShimpyoIcons.Parking),
    DemoScenario(null, "시연 끝내기", "실제 신호로 돌아가기", ShimpyoIcons.Stop),
)

/** 화면 위 배지에 쓰는 시나리오 이름 */
fun demoTitle(key: String?): String? = key?.let { k -> DemoScenarios.firstOrNull { it.key == k }?.title ?: k }

/**
 * 시연 패널 (screens.md 6.2, 정차 중에만). 앱 제목을 길게 누르면 열린다(2차 PT용).
 * 시나리오를 누르면 UiCommand.Demo로 정체·위치·시각을 주입한다. 차량 상태(기어·차속·문)는 Virtual Console로 바꾼다.
 * 아래 상태 줄은 시연 중 화면이 왜 바뀌는지 설명할 때 쓴다.
 */
@Composable
fun DemoPanel(state: CoachUiState, onScenario: (String?) -> Unit, modifier: Modifier = Modifier) {
    val layout = LocalLayout.current
    val scenarios: @Composable () -> Unit = {
        SectionTitle("시나리오")
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space.m)) {
            DemoScenarios.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space.m)) {
                    pair.forEach { s ->
                        ScenarioButton(s, selected = s.key != null && state.demo == s.key, onClick = { onScenario(s.key) }, modifier = Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(Dimens.Space.m))
        Text(
            "기어·차속·문은 Virtual Console로 바꿔요 (tools/vc/vc.sh, shimpyo-*.vc)",
            style = PleosType.BodyMedium,
            color = ShimpyoTheme.colors.textTertiary,
        )
    }
    val status: @Composable () -> Unit = {
        SectionTitle("상태")
        ListGroup {
            statusLines(state).forEachIndexed { i, (k, v) ->
                if (i > 0) ListDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = Dimens.Space.l), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, style = PleosType.BodyMedium, color = ShimpyoTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                    Text(v, style = PleosType.BodyMediumStrong, color = ShimpyoTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(Dimens.Space.xl))
    }
    if (layout.landscape) {
        Row(modifier.fillMaxSize().padding(horizontal = layout.margin), horizontalArrangement = Arrangement.spacedBy(Dimens.Space.xxl)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { scenarios() }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { status() }
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = layout.margin, end = layout.margin, bottom = layout.bottom)) {
            scenarios()
            status()
        }
    }
}

@Composable
private fun ScenarioButton(s: DemoScenario, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = ShimpyoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        selected && pressed -> c.buttonFilledPressed
        selected -> c.buttonFilledEnabled
        pressed -> c.buttonBasicPressed
        else -> c.buttonBasicEnabled
    }
    val fg = if (selected) c.textOnFilledPrimary else c.textPrimary
    val fg2 = if (selected) c.textOnFilledSecondary else c.textSecondary
    Row(
        modifier
            .heightIn(min = 120.dp)
            .pressable(interaction, cornerRadius = Dimens.Radius.l, onClick = onClick)
            .clip(RoundedCornerShape(Dimens.Radius.l))
            .background(bg)
            .padding(horizontal = Dimens.Space.l, vertical = Dimens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(s.icon, contentDescription = null, modifier = Modifier.size(Dimens.Icon.base), tint = fg)
        Spacer(Modifier.width(Dimens.Space.m))
        Column {
            Text(s.title, style = PleosType.TitleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.detail, style = PleosType.BodySmall, color = fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun statusLines(s: CoachUiState): List<Pair<String, String>> = listOf(
    "시연 모드" to (demoTitle(s.demo) ?: "꺼짐"),
    "주행 제한 (CarUxRestrictions)" to if (s.drivingRestricted) "켜짐" else "꺼짐",
    "기어" to if (s.parked) "P (정차)" else "주행",
    "차속" to (s.speedKph?.let { "${it.toInt()} km/h" } ?: "--"),
    "길안내" to if (s.guiding) listOfNotNull("안내 중", s.destinationName, s.remainDistanceM?.let { Labels.distance(it) }).joinToString(" · ") else "없음",
    "경로 앞" to "화장실 ${s.ahead.size}곳 · 정체 ${s.congestion.size}구간",
    "음성" to (s.lastHeard?.let { "\"$it\"" } ?: s.voice.name),
    "코치 서비스" to if (s.serviceRunning) "실행 중" else "꺼짐",
    "상태" to (s.status ?: "--"),
    "음성 이해" to (s.llm ?: "규칙 기반"),
)
