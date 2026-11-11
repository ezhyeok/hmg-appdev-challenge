package dev.shimpyo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.shimpyo.app.coach.CoachUiState
import dev.shimpyo.app.coach.UiCommand
import dev.shimpyo.app.coach.VoiceState
import dev.shimpyo.app.ui.components.HeaderMenuItem
import dev.shimpyo.app.ui.components.LocalOverlays
import dev.shimpyo.app.ui.components.OverlayState
import dev.shimpyo.app.ui.components.PageHeader
import dev.shimpyo.app.ui.components.PleosIconButton
import dev.shimpyo.app.ui.components.PopupLayer
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.components.ToastLayer
import dev.shimpyo.app.ui.components.VoiceButton
import dev.shimpyo.app.ui.demo.DemoPanel
import dev.shimpyo.app.ui.demo.demoTitle
import dev.shimpyo.app.ui.drive.DriveScreen
import dev.shimpyo.app.ui.history.HistoryScreen
import dev.shimpyo.app.ui.occupants.OccupantsScreen
import dev.shimpyo.app.ui.plan.PlanScreen
import dev.shimpyo.app.ui.settings.SettingsScreen
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.app.ui.urgent.UrgentScreen
import dev.shimpyo.app.ui.urgent.UrgentStatus
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.RankedStop
import kotlinx.coroutines.delay

/** 앱 화면. 주요 메뉴(1 depth)에 있는 다섯 개 + 숨은 시연 패널 */
enum class Screen(val title: String) {
    DRIVE("주행"),
    PLAN("계획"),
    OCCUPANTS("탑승자"),
    HISTORY("기록"),
    SETTINGS("설정"),
    DEMO("시연 패널"),
    ;

    val icon: ImageVector
        get() = when (this) {
            DRIVE -> ShimpyoIcons.Car
            PLAN -> ShimpyoIcons.Route
            OCCUPANTS -> ShimpyoIcons.Group
            HISTORY -> ShimpyoIcons.History
            SETTINGS -> ShimpyoIcons.Settings
            DEMO -> ShimpyoIcons.PlayArrow
        }
}

private val MenuScreens = listOf(Screen.DRIVE, Screen.PLAN, Screen.OCCUPANTS, Screen.HISTORY, Screen.SETTINGS)

/**
 * 쉼표의 화면 전체. 서비스 상태([CoachUiState])를 그리고 명령은 [AppHost]로 보낸다.
 *
 * 주행 제한(`state.drivingRestricted`, CarUxRestrictions의 requiresDistractionOptimization)이 켜지면
 * 주행 화면과 급해 결과만 그린다. 다른 화면에 있었다면 주행 화면으로 돌아오고 토스트를 띄운다.
 * 각 화면의 입력 상태는 SaveableStateHolder에 남아 있어 정차 후 그 화면으로 돌아가면 복원된다.
 *
 * 탐색 라이브러리 없이 화면 하나를 상태로 들고 있다. 급해 결과는 다른 화면 위에 덮는 한 단계 화면이다.
 */
@Composable
fun ShimpyoContent(
    state: CoachUiState,
    host: AppHost,
    modifier: Modifier = Modifier,
    initialScreen: Screen = Screen.DRIVE,
    initialUrgentPending: Boolean = false,
) {
    val c = ShimpyoTheme.colors
    val overlays = remember { OverlayState() }
    var screen by rememberSaveable { mutableStateOf(initialScreen) }
    var demoReturn by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
    val driving = state.drivingRestricted
    // 한 프레임도 제한 화면을 그리지 않도록 그릴 화면은 여기서 바로 정한다
    val shown = if (driving) Screen.DRIVE else screen
    val holder = rememberSaveableStateHolder()

    LaunchedEffect(driving) {
        if (driving) {
            overlays.dismissPopup()
            if (screen != Screen.DRIVE) {
                screen = Screen.DRIVE
                overlays.toast("주행 중에는 주행 화면만 보여요")
            }
        }
    }

    // 계획 요청: 결과가 오면 진행 표시를 끈다. 다시 계산한 계획이 이전과 같으면 상태가 바뀌지 않으므로
    // 시간이 지나면 조용히 끄고, 계획이 아예 없을 때만 실패를 알린다
    var planRequested by remember { mutableStateOf(false) }
    val latestPlan by rememberUpdatedState(state.plan)
    LaunchedEffect(state.plan) { planRequested = false }
    LaunchedEffect(planRequested) {
        if (planRequested) {
            delay(PLAN_TIMEOUT_MS)
            if (planRequested) {
                planRequested = false
                if (latestPlan == null) overlays.toast("계획을 세우지 못했어요. 다시 시도해 주세요")
            }
        }
    }

    // 급해 결과: 누르는 즉시 결과 화면을 열고, 결과가 늦으면 실패로 바꾼다
    var urgentPending by remember { mutableStateOf(initialUrgentPending) }
    var urgentFailed by remember { mutableStateOf(false) }
    var pickingId by remember { mutableStateOf<String?>(null) }
    val urgentVisible = state.urgent != null || urgentPending || urgentFailed
    LaunchedEffect(state.urgent) {
        if (state.urgent != null) {
            urgentPending = false
            urgentFailed = false
        } else {
            pickingId = null
        }
    }
    LaunchedEffect(urgentPending) {
        if (urgentPending) {
            delay(URGENT_TIMEOUT_MS)
            if (urgentPending) {
                urgentPending = false
                urgentFailed = true
            }
        }
    }
    LaunchedEffect(pickingId) {
        if (pickingId != null) {
            delay(GOTO_TIMEOUT_MS)
            if (pickingId != null) {
                pickingId = null
                overlays.toast("경유지를 넣지 못했어요. 내비에서 확인해 주세요")
            }
        }
    }

    val current by rememberUpdatedState(state)
    fun send(command: UiCommand) = host.send(command)
    fun requestUrgent() {
        urgentFailed = false
        urgentPending = true
        send(UiCommand.Urgent)
    }
    fun closeUrgent() {
        val p = current.prompt
        if (p?.kind == PromptKind.URGENT_RESULT) send(UiCommand.Answer(p.id, false))
        if (current.urgent != null) send(UiCommand.DismissUrgent)
        urgentPending = false
        urgentFailed = false
        pickingId = null
    }
    fun pick(r: RankedStop) {
        pickingId = r.stop.id
        val p = current.prompt
        if (p?.kind == PromptKind.URGENT_RESULT && p.stop?.id == r.stop.id) {
            // 음성 질문("안내할까요?")과 같은 곳이면 그 질문에 '응'으로 답한다
            send(UiCommand.Answer(p.id, true))
        } else {
            send(UiCommand.GoTo(r.stop))
            if (p?.kind == PromptKind.URGENT_RESULT) send(UiCommand.Answer(p.id, false))
        }
    }
    fun listen() {
        if (current.voice == VoiceState.UNAVAILABLE) overlays.toast("지금은 음성을 쓸 수 없어요") else send(UiCommand.Listen)
    }
    fun makePlan() {
        planRequested = true
        send(UiCommand.MakePlan)
    }
    fun open(target: Screen) {
        if (target == Screen.DEMO) demoReturn = screen
        screen = target
    }

    PlatformBackHandler {
        when {
            overlays.popup != null -> overlays.dismissPopup()
            urgentVisible -> closeUrgent()
            shown == Screen.DEMO -> screen = demoReturn
            else -> host.moveToBack()
        }
    }

    CompositionLocalProvider(LocalOverlays provides overlays) {
        BoxWithConstraints(modifier.fillMaxSize().background(c.backgroundPrimary)) {
            val layout = LayoutSpec(maxWidth, maxHeight)
            CompositionLocalProvider(LocalLayout provides layout) {
                // 시연 중에는 모든 화면 머리에 "시연 모드"를 띄운다
                val badge = demoTitle(state.demo)?.let { "시연 모드 · $it" }
                val badgeShort = if (badge != null) "시연 모드" else null
                // 급해 결과는 다른 화면 위에 겹쳐 그리지 않고 대신 그린다: 아래 화면의 버튼이 눌리거나 CCP 포커스를 받지 않게
                if (urgentVisible) {
                    UrgentScreen(
                        results = state.urgent,
                        status = when {
                            state.urgent != null -> UrgentStatus.READY
                            urgentFailed -> UrgentStatus.FAILED
                            else -> UrgentStatus.LOADING
                        },
                        driving = driving,
                        pickingId = pickingId,
                        voice = state.voice,
                        voiceEnabled = host.prefs.voiceEnabled,
                        onPick = ::pick,
                        onClose = ::closeUrgent,
                        onRetry = ::requestUrgent,
                        onListen = ::listen,
                        badge = badge,
                        badgeShort = badgeShort,
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        if (shown == Screen.DEMO) {
                            PageHeader(
                                title = Screen.DEMO.title,
                                height = layout.headerHeight(driving),
                                margin = layout.margin,
                                onBack = { screen = demoReturn },
                                badge = badge,
                                badgeShort = badgeShort,
                            )
                        } else {
                            PageHeader(
                                title = shown.title,
                                height = layout.headerHeight(driving),
                                margin = layout.margin,
                                // 주행 중에는 드롭다운과 숨은 메뉴를 끈다 (제목만, 화살표 없음)
                                menu = if (driving) null else MenuScreens.map { s -> HeaderMenuItem(s.title, s.icon, s == shown) { open(s) } },
                                onTitleLongPress = if (driving) null else ({ open(Screen.DEMO) }),
                                badge = badge,
                                badgeShort = badgeShort,
                                trailing = {
                                    when (shown) {
                                        Screen.DRIVE -> VoiceButton(state.voice, host.prefs.voiceEnabled, ::listen)
                                        // 계획 다시 계산 (screens.md 2.2의 [새로])
                                        Screen.PLAN -> PleosIconButton(ShimpyoIcons.Refresh, "다시 계산", ::makePlan, enabled = !planRequested)
                                        else -> Unit
                                    }
                                },
                            )
                        }
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            holder.SaveableStateProvider(shown.name) {
                                when (shown) {
                                    Screen.DRIVE -> DriveScreen(
                                        state = state,
                                        driving = driving,
                                        onAnswer = { p, accept -> send(UiCommand.Answer(p.id, accept)) },
                                        onUrgent = ::requestUrgent,
                                        onOpenPlan = { open(Screen.PLAN) },
                                        onOpenOccupants = { open(Screen.OCCUPANTS) },
                                    )
                                    Screen.PLAN -> PlanScreen(
                                        state = state,
                                        planRequested = planRequested,
                                        onMakePlan = ::makePlan,
                                        onGoTo = { stop ->
                                            send(UiCommand.GoTo(stop))
                                            overlays.toast("${Labels.withObject(shortStopName(stop.name))} 내비에 넣는 중이에요")
                                        },
                                    )
                                    Screen.OCCUPANTS -> OccupantsScreen(
                                        occupants = state.occupants,
                                        drivenBy = state.restroom?.drivenBy,
                                        voice = state.voice,
                                        prefs = host.prefs,
                                        onSetOccupants = { send(UiCommand.SetOccupants(it)) },
                                        onDescribe = { send(UiCommand.DescribeOccupants) },
                                    )
                                    Screen.HISTORY -> HistoryScreen(state.history, lastStop = state.lastStop)
                                    Screen.SETTINGS -> SettingsScreen(
                                        state = state,
                                        prefs = host.prefs,
                                        versionName = host.versionName,
                                        onSetOccupants = { send(UiCommand.SetOccupants(it)) },
                                        onClearRecords = {
                                            overlays.toast(if (host.clearRecords()) "기록을 지웠어요" else "일부 기록을 지우지 못했어요")
                                        },
                                    )
                                    Screen.DEMO -> DemoPanel(
                                        state = state,
                                        onScenario = { key ->
                                            send(UiCommand.Demo(key))
                                            overlays.toast(if (key == null) "시연을 끝냈어요" else "시연 시작: ${demoTitle(key)}")
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                PopupLayer(overlays)
                ToastLayer(overlays, topInset = if (urgentVisible || shown == Screen.DRIVE) layout.headerHeight(driving) else null)
            }
        }
    }
}

private const val URGENT_TIMEOUT_MS = 8_000L
private const val GOTO_TIMEOUT_MS = 12_000L
private const val PLAN_TIMEOUT_MS = 6_000L
