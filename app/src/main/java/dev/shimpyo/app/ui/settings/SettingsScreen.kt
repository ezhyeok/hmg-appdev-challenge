package dev.shimpyo.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shimpyo.app.ui.LocalLayout
import dev.shimpyo.app.ui.PrefsState
import dev.shimpyo.app.ui.components.ChevronTrailing
import dev.shimpyo.app.ui.components.ListDivider
import dev.shimpyo.app.ui.components.ListGroup
import dev.shimpyo.app.ui.components.LocalOverlays
import dev.shimpyo.app.ui.components.PleosStepper
import dev.shimpyo.app.ui.components.PleosSwitch
import dev.shimpyo.app.ui.components.PopupSpec
import dev.shimpyo.app.ui.components.SectionTitle
import dev.shimpyo.app.ui.components.SettingRow
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.occupants.HealthConsentRow
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.PleosType
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.engine.Occupant

/**
 * 6. 설정 (screens.md 6.1, 정차 중에만).
 * 음성 안내, 피로가 오르면 외기로 전환(동의), 지난밤 수면, 건강 관련 정보 동의, 기록 지우기, 정보.
 * 맨 아래에 서비스 상태와 LLM 사용 여부를 작게 보여 준다.
 */
@Composable
fun SettingsScreen(
    state: CoachUiState,
    prefs: PrefsState,
    versionName: String,
    onSetOccupants: (List<Occupant>) -> Unit,
    onClearRecords: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = LocalLayout.current
    val left: @Composable () -> Unit = {
        SectionTitle("음성")
        ListGroup {
            SettingRow(
                "음성 안내",
                subtitle = "끄면 질문을 화면에만 보여 드려요",
                icon = ShimpyoIcons.VolumeUp,
                onClick = { prefs.updateVoiceEnabled(!prefs.voiceEnabled) },
                trailing = { PleosSwitch(prefs.voiceEnabled) },
            )
        }
        SectionTitle("차량")
        ListGroup {
            SettingRow(
                "피로가 오르면 외기로 전환",
                subtitle = "묻지 않고 내기순환을 꺼서 환기해요. 공조 제어를 지원하는 차에서만 동작해요",
                icon = ShimpyoIcons.Air,
                onClick = { prefs.updateAutoFreshAir(!prefs.autoFreshAir) },
                trailing = { PleosSwitch(prefs.autoFreshAir) },
            )
        }
        SectionTitle("운전자")
        ListGroup {
            SleepRow(prefs)
        }
    }
    val right: @Composable () -> Unit = {
        SectionTitle("개인정보")
        ListGroup {
            HealthConsentRow(prefs, state.occupants, onSetOccupants)
            ListDivider()
            ClearRecordsRow(onClearRecords)
        }
        SectionTitle("정보")
        ListGroup {
            SettingRow("버전", icon = ShimpyoIcons.Info, trailing = { Text(versionName, style = PleosType.BodyLarge, color = ShimpyoTheme.colors.textSecondary) })
            ListDivider()
            LicensesRow()
        }
        StatusFooter(state)
    }
    if (layout.landscape) {
        Row(modifier.fillMaxSize().padding(horizontal = layout.margin), horizontalArrangement = Arrangement.spacedBy(Dimens.Space.xxl)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = layout.bottom)) { left() }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = layout.bottom)) { right() }
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = layout.margin, end = layout.margin, bottom = layout.bottom)) {
            left()
            right()
        }
    }
}

/** 지난밤 수면: 모름, 4~10시간. 6시간 미만이면 엔진이 휴식 권유를 앞당긴다 */
@Composable
private fun SleepRow(prefs: PrefsState) {
    val hours = prefs.driverSleepHours.toInt()
    SettingRow(
        "지난밤 수면",
        subtitle = "6시간보다 적게 잤으면 휴식을 더 일찍 권해요",
        icon = ShimpyoIcons.Bedtime,
        trailing = {
            PleosStepper(
                value = if (hours <= 0) "모름" else "${hours}시간",
                onMinus = { prefs.updateDriverSleepHours(if (hours <= MIN_SLEEP) 0f else (hours - 1).toFloat()) },
                onPlus = { prefs.updateDriverSleepHours(if (hours <= 0) MIN_SLEEP.toFloat() else (hours + 1).coerceAtMost(MAX_SLEEP).toFloat()) },
                minusEnabled = hours > 0,
                plusEnabled = hours < MAX_SLEEP,
                label = "수면 시간",
                width = 300.dp,
            )
        },
    )
}

/** 기록 지우기: 확인 팝업 뒤 stops.json, memory.json을 지운다 (파괴적 동작이라 팝업) */
@Composable
private fun ClearRecordsRow(onClearRecords: () -> Unit) {
    val overlays = LocalOverlays.current
    SettingRow(
        "기록 지우기",
        subtitle = "정차 기록과 학습한 화장실 간격을 이 기기에서 지워요",
        icon = ShimpyoIcons.Delete,
        onClick = {
            overlays.confirm(
                PopupSpec(
                    title = "기록을 지울까요?",
                    description = "정차 기록과 학습한 화장실 간격이 지워지고 되돌릴 수 없어요.",
                    confirmText = "지우기",
                    onConfirm = onClearRecords,
                ),
            )
        },
        trailing = { ChevronTrailing() },
    )
}

@Composable
private fun LicensesRow() {
    var open by rememberSaveable { mutableStateOf(false) }
    val c = ShimpyoTheme.colors
    SettingRow("데이터 출처와 라이선스", icon = ShimpyoIcons.Info, onClick = { open = !open }, trailing = { ChevronTrailing() })
    if (open) {
        Column(Modifier.fillMaxWidth().padding(start = Dimens.Space.l, end = Dimens.Space.l, bottom = Dimens.Space.l)) {
            ATTRIBUTION.forEach { line ->
                Text(line, style = PleosType.BodySmall, color = c.textSecondary)
                Spacer(Modifier.height(Dimens.Space.xs))
            }
        }
    }
}

/** 서비스 상태, 데이터, LLM (작게) */
@Composable
private fun StatusFooter(state: CoachUiState) {
    val c = ShimpyoTheme.colors
    Spacer(Modifier.height(Dimens.Space.xl))
    val lines = listOfNotNull(
        if (state.serviceRunning) "코치 서비스 실행 중" else "코치 서비스 꺼짐",
        state.status,
        "음성 이해: " + (state.llm ?: "규칙 기반"),
    )
    lines.forEach { Text(it, style = PleosType.LabelMedium, color = c.textTertiary) }
    Spacer(Modifier.height(Dimens.Space.xl))
}

private const val MIN_SLEEP = 4
private const val MAX_SLEEP = 10

/** tools/data/build.py ATTRIBUTION과 같은 출처 + 아이콘 라이선스 */
private val ATTRIBUTION = listOf(
    "공중화장실·비상벨·CCTV·주유소 등: 행정안전부 지방행정 인허가·생활편의 데이터(LOCALDATA)",
    "휴게소·졸음쉼터: 한국도로공사, 전국휴게소정보·졸음쉼터 표준데이터 (공공데이터포털)",
    "주차장: 전국주차장정보표준데이터, 각 지방자치단체 (공공데이터포털)",
    "© OpenStreetMap contributors, ODbL 1.0 (openstreetmap.org/copyright): 고속도로 선형, 민자고속도로 휴게소·졸음쉼터, 주유소·LPG충전소, 공중화장실 일부",
    "아이콘: Google Material Symbols, Apache License 2.0",
)
