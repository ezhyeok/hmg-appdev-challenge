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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.hmg.pleoskit.UxRestrictions
import dev.shimpyo.app.ui.components.HeaderMenuItem
import dev.shimpyo.app.ui.components.LocalOverlays
import dev.shimpyo.app.ui.components.MicAction
import dev.shimpyo.app.ui.components.MicModel
import dev.shimpyo.app.ui.components.OverlayState
import dev.shimpyo.app.ui.components.PageHeader
import dev.shimpyo.app.ui.components.PleosIconButton
import dev.shimpyo.app.ui.components.PopupLayer
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.components.ToastLayer
import dev.shimpyo.app.ui.components.VoiceButton
import dev.shimpyo.app.ui.components.micModel
import dev.shimpyo.app.ui.demo.DemoPanel
import dev.shimpyo.app.ui.demo.demoTitle
import dev.shimpyo.app.ui.drive.DriveScreen
import dev.shimpyo.app.ui.history.HistoryScreen
import dev.shimpyo.app.ui.occupants.OccupantsScreen
import dev.shimpyo.app.ui.plan.PlanScreen
import dev.shimpyo.app.ui.settings.SettingsScreen
import dev.shimpyo.app.ui.settings.SettingsStatus
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.app.ui.urgent.URGENT_ACK_TIMEOUT_MS
import dev.shimpyo.app.ui.urgent.UrgentRequestIds
import dev.shimpyo.app.ui.urgent.UrgentScreen
import dev.shimpyo.app.ui.urgent.settleDismissed
import dev.shimpyo.app.ui.urgent.urgentView
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.GoToOutcome
import dev.shimpyo.coach.UiCommand
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.RankedStop
import dev.shimpyo.engine.StopCandidate
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
 * 주행 제한([restriction], CarUxRestrictions를 화면이 스스로 구독한 값. 모르면 제한)이 켜지면 주행 화면과 급해 결과만 그린다.
 * 다른 화면에 있었다면 주행 화면으로 돌아오고, 제한을 실제로 읽었을 때만 토스트를 띄운다.
 * 각 화면의 입력 상태는 SaveableStateHolder에, 진행 중 요청(급해 번호, 넣는 중, 계획 계산 중)은 [ContentState]에 남아
 * 액티비티가 다시 만들어져도 이어진다.
 *
 * 탐색 라이브러리 없이 화면 하나를 상태로 들고 있다. 급해 결과는 다른 화면 위에 덮는 한 단계 화면이다.
 */
@Composable
fun ShimpyoContent(
    state: CoachUiState,
    host: AppHost,
    modifier: Modifier = Modifier,
    restriction: DriveRestriction = driveRestriction(UxRestrictions.NOT_CONNECTED, state),
    initialScreen: Screen = Screen.DRIVE,
    initialUrgentRequest: Long? = null,
) {
    val c = ShimpyoTheme.colors
    val overlays = remember { OverlayState() }
    val ui = rememberSaveable(saver = ContentState.Saver) {
        ContentState(initialScreen, urgentRequested = initialUrgentRequest, seenGoToAt = state.goToResult?.at ?: 0L)
    }
    val current = rememberUpdatedState(state)
    val voiceGuidance = host.prefs.voiceEnabled
    val actions = remember(ui, host, overlays) { ContentActions(ui, host, overlays) { current.value } }
    val driving = restriction.driving
    // 한 프레임도 제한 화면을 그리지 않도록 그릴 화면은 여기서 바로 정한다
    val shown = if (driving) Screen.DRIVE else ui.screen
    val holder = rememberSaveableStateHolder()

    LaunchedEffect(driving) {
        if (driving) {
            overlays.dismissPopup()
            if (ui.screen != Screen.DRIVE) {
                ui.screen = Screen.DRIVE
                if (restriction.known) overlays.toast("주행 중에는 주행 화면만 보여요")
            }
        }
    }

    // 계획 요청: 새 계획이 오면(다른 객체) 진행 표시를 끈다. 다시 계산한 계획이 이전과 같아 상태가 바뀌지 않으면
    // 시간이 지나면 조용히 끄고, 계획이 아예 없을 때만 실패를 알린다. 기준 계획은 처음 그릴 때 값이다(다시 만들어져도 이어진다)
    var planBaseline by remember { mutableStateOf(state.plan) }
    LaunchedEffect(state.plan) {
        if (state.plan !== planBaseline) {
            planBaseline = state.plan
            ui.planRequested = false
        }
    }
    LaunchedEffect(ui.planRequested) {
        if (ui.planRequested) {
            delay(PLAN_TIMEOUT_MS)
            if (ui.planRequested) {
                ui.planRequested = false
                if (current.value.plan == null) overlays.toast("계획을 세우지 못했어요. 다시 시도해 주세요")
            }
        }
    }

    // 급해: 요청 번호로 코치의 진행 상태를 짝짓는다 (UrgentModel)
    var ackExpired by remember { mutableStateOf(false) }
    LaunchedEffect(ui.urgentRequested) {
        ackExpired = false
        if (ui.urgentRequested != null) {
            delay(URGENT_ACK_TIMEOUT_MS)
            ackExpired = true
        }
    }
    val urgent = urgentView(ui.urgentRequested, ui.urgentDismissed, state, ackExpired, ui.pickingId)
    val urgentVisible = urgent != null
    LaunchedEffect(state.urgentRequestId, state.urgentState, state.urgent == null) {
        ui.urgentDismissed = settleDismissed(ui.urgentDismissed, state)
    }
    // 코치가 요청을 끝냈다(경유지를 넣었다 등): 화면도 닫는다
    LaunchedEffect(urgentVisible) {
        if (!urgentVisible && ui.urgentRequested != null) {
            ui.urgentRequested = null
            ui.pickingId = null
        }
    }
    LaunchedEffect(ui.pickingId) {
        if (ui.pickingId != null) {
            delay(GOTO_TIMEOUT_MS)
            if (ui.pickingId != null) {
                ui.pickingId = null
                overlays.toast(NO_GOTO_RESULT)
            }
        }
    }
    LaunchedEffect(ui.goToPendingId) {
        if (ui.goToPendingId != null) {
            delay(GOTO_TIMEOUT_MS)
            if (ui.goToPendingId != null) {
                ui.goToPendingId = null
                overlays.toast(NO_GOTO_RESULT)
            }
        }
    }
    // 들르기·안내 결과 (코치의 goToResult): 기다리던 버튼을 풀고, 카드가 보여 주지 않는 결과는 토스트로 알린다
    val goTo = state.goToResult
    LaunchedEffect(goTo?.at, goTo?.stop?.id, goTo?.outcome) {
        val r = goTo ?: return@LaunchedEffect
        if (r.at == ui.seenGoToAt) return@LaunchedEffect
        ui.seenGoToAt = r.at
        if (ui.goToPendingId == r.stop.id) ui.goToPendingId = null
        val view = urgent
        if (ui.pickingId == r.stop.id) {
            ui.pickingId = null
            // 고른 곳이 들어갔으면 급해 화면을 닫는다 (코치도 끝내지만 한 틱 늦을 수 있다)
            if (r.outcome != GoToOutcome.FAILED && view != null) actions.closeUrgent(view.requestId, answerUrgentPrompt = false)
        }
        goToToast(r, onDriveCard = shown == Screen.DRIVE && view == null, nextStopId = current.value.next?.stop?.id)?.let(overlays::toast)
    }

    PlatformBackHandler {
        when {
            overlays.popup != null -> overlays.dismissPopup()
            urgent != null -> actions.closeUrgent(urgent.requestId)
            shown == Screen.DEMO -> ui.screen = ui.demoReturn
            else -> host.moveToBack()
        }
    }

    val mic = micModel(state, voiceGuidance)
    CompositionLocalProvider(LocalOverlays provides overlays) {
        BoxWithConstraints(modifier.fillMaxSize().background(c.backgroundPrimary)) {
            val layout = LayoutSpec(maxWidth, maxHeight)
            CompositionLocalProvider(LocalLayout provides layout) {
                // 시연 중에는 모든 화면 머리에 "시연 모드"를 띄운다
                val badge = demoTitle(state.demo)?.let { "시연 모드 · $it" }
                val badgeShort = if (badge != null) "시연 모드" else null
                // 급해 결과는 다른 화면 위에 겹쳐 그리지 않고 대신 그린다: 아래 화면의 버튼이 눌리거나 CCP 포커스를 받지 않게
                if (urgent != null) {
                    UrgentScreen(
                        view = urgent,
                        driving = driving,
                        maxItems = restriction.maxItems,
                        pickingId = ui.pickingId,
                        mic = mic,
                        onMic = { actions.mic(mic) },
                        onPick = { actions.pick(it) },
                        onClose = { actions.closeUrgent(urgent.requestId) },
                        onRetry = { actions.requestUrgent(replacing = urgent.requestId) },
                        question = state.prompt?.takeIf { state.serviceRunning && it.kind != PromptKind.URGENT_RESULT },
                        onAnswer = { p, accept -> actions.answer(p, accept) },
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
                                onBack = { ui.screen = ui.demoReturn },
                                badge = badge,
                                badgeShort = badgeShort,
                            )
                        } else {
                            val planBusy = ui.planRequested
                            PageHeader(
                                title = shown.title,
                                height = layout.headerHeight(driving),
                                margin = layout.margin,
                                // 주행 중에는 드롭다운과 숨은 메뉴를 끈다 (제목만, 화살표 없음)
                                menu = if (driving) null else remember(shown, actions) { MenuScreens.map { s -> HeaderMenuItem(s.title, s.icon, s == shown) { actions.open(s) } } },
                                onTitleLongPress = if (driving) null else ({ actions.open(Screen.DEMO) }),
                                badge = badge,
                                badgeShort = badgeShort,
                                // 이 람다는 mic·planBusy·actions만 잡는다: 매초 바뀌는 state 전체를 잡으면 머리가 매초 다시 그려진다 (UI 감사 S1)
                                trailing = {
                                    when (shown) {
                                        Screen.DRIVE -> VoiceButton(mic, { actions.mic(mic) }, driving = driving)
                                        // 계획 다시 계산 (screens.md 2.2의 [새로])
                                        Screen.PLAN -> PleosIconButton(ShimpyoIcons.Refresh, "다시 계산", { actions.makePlan() }, enabled = !planBusy)
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
                                        restriction = restriction,
                                        onAnswer = { p, accept -> actions.answer(p, accept) },
                                        onRetryGoTo = { actions.retryGoTo(it) },
                                        onDismissGoTo = { actions.dismissGoTo() },
                                        onUrgent = { actions.requestUrgent() },
                                        onOpenPlan = { actions.open(Screen.PLAN) },
                                        onOpenOccupants = { actions.open(Screen.OCCUPANTS) },
                                    )
                                    Screen.PLAN -> PlanScreen(
                                        state = state,
                                        planRequested = ui.planRequested,
                                        goToPendingId = ui.goToPendingId,
                                        onMakePlan = { actions.makePlan() },
                                        onGoTo = { actions.goTo(it) },
                                    )
                                    Screen.OCCUPANTS -> OccupantsScreen(
                                        occupants = state.occupants,
                                        drivenBy = state.restroom?.drivenBy,
                                        voice = state.voice,
                                        prefs = host.prefs,
                                        onSetOccupants = { host.send(UiCommand.SetOccupants(it)) },
                                        onDescribe = { host.send(UiCommand.DescribeOccupants) },
                                    )
                                    Screen.HISTORY -> HistoryScreen(state.history, lastStop = state.lastStop)
                                    Screen.SETTINGS -> SettingsScreen(
                                        status = SettingsStatus(state.serviceRunning, state.status, state.llm, state.cabinSupported),
                                        occupants = state.occupants,
                                        prefs = host.prefs,
                                        versionName = host.versionName,
                                        onSetOccupants = { host.send(UiCommand.SetOccupants(it)) },
                                        onClearRecords = {
                                            overlays.toast(if (host.clearRecords()) "기록을 지웠어요" else "일부 기록을 지우지 못했어요")
                                        },
                                    )
                                    Screen.DEMO -> DemoPanel(
                                        state = state,
                                        onScenario = { key ->
                                            host.send(UiCommand.Demo(key))
                                            overlays.toast(if (key == null) "시연을 끝냈어요" else "시연 시작: ${demoTitle(key)}")
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                PopupLayer(overlays)
                ToastLayer(overlays, topInset = if (urgent != null || shown == Screen.DRIVE) layout.headerHeight(driving) else null)
            }
        }
    }
}

/**
 * 화면의 동작. [ContentState]·호스트·오버레이만 들고 있어 한 화면 동안 같은 객체다:
 * 아래 화면에 넘기는 람다가 이것만 잡으므로 매초 새 람다가 생기지 않는다 (UI 감사 S2: 함수 참조 ::pick 등은
 * Kotlin 2.0.0 Compose 컴파일러에서 기억되지 않을 수 있었다).
 */
@Stable
private class ContentActions(
    private val ui: ContentState,
    private val host: AppHost,
    private val overlays: OverlayState,
    private val state: () -> CoachUiState,
) {
    fun open(target: Screen) {
        if (target == Screen.DEMO) ui.demoReturn = ui.screen
        ui.screen = target
    }

    fun answer(p: Prompt, accept: Boolean) = host.send(UiCommand.Answer(p.id, accept))

    /** 급해 요청. [replacing]은 다시 시도할 때 끝난 앞 요청 (코치 쪽 상태도 정리한다) */
    fun requestUrgent(replacing: Long? = null) {
        if (replacing != null) host.send(UiCommand.DismissUrgent(replacing))
        val id = UrgentRequestIds.next()
        ui.pickingId = null
        ui.urgentRequested = id
        host.send(UiCommand.Urgent(id))
    }

    fun closeUrgent(requestId: Long, answerUrgentPrompt: Boolean = true) {
        val p = state().prompt
        if (answerUrgentPrompt && p?.kind == PromptKind.URGENT_RESULT) host.send(UiCommand.Answer(p.id, false))
        host.send(UiCommand.DismissUrgent(requestId))
        ui.urgentDismissed = requestId
        ui.urgentRequested = null
        ui.pickingId = null
    }

    /** 급해 결과 고르기. 결과를 기다리는 동안 다른 카드는 막는다 (두 번 누르면 경유지 요청이 두 번 갔다) */
    fun pick(r: RankedStop) {
        if (ui.pickingId != null) return
        ui.pickingId = r.stop.id
        val p = state().prompt
        if (p?.kind == PromptKind.URGENT_RESULT && p.stop?.id == r.stop.id) {
            // 음성 질문("안내할까요?")과 같은 곳이면 그 질문에 '응'으로 답한다
            host.send(UiCommand.Answer(p.id, true))
        } else {
            host.send(UiCommand.GoTo(r.stop))
            if (p?.kind == PromptKind.URGENT_RESULT) host.send(UiCommand.Answer(p.id, false))
        }
    }

    /** 계획 화면의 경유지 넣기. 결과(또는 시간 초과)까지 같은 요청을 막는다 */
    fun goTo(stop: StopCandidate) {
        if (ui.goToPendingId != null) return
        ui.goToPendingId = stop.id
        host.send(UiCommand.GoTo(stop))
        overlays.toast("${Labels.withObject(shortStopName(stop.name))} 내비에 넣는 중이에요")
    }

    /** 주행 화면의 경유지 실패 카드 [다시 시도] */
    fun retryGoTo(stop: StopCandidate) {
        if (ui.goToPendingId != null) return
        ui.goToPendingId = stop.id
        host.send(UiCommand.DismissGoToResult)
        host.send(UiCommand.GoTo(stop))
    }

    fun dismissGoTo() = host.send(UiCommand.DismissGoToResult)

    fun mic(m: MicModel) {
        when (m.action) {
            MicAction.LISTEN -> host.send(UiCommand.Listen)
            MicAction.STOP -> host.send(UiCommand.StopVoice)
            MicAction.EXPLAIN -> m.explain?.let(overlays::toast)
        }
    }

    fun makePlan() {
        ui.planRequested = true
        host.send(UiCommand.MakePlan)
    }
}

private const val GOTO_TIMEOUT_MS = 12_000L
private const val PLAN_TIMEOUT_MS = 6_000L
/** 결과가 오지 않을 때. 성공했는지 모르므로 실패라고 하지 않는다 (옛 서비스는 결과를 음성으로만 말한다) */
private const val NO_GOTO_RESULT = "결과를 받지 못했어요. 내비에서 확인해 주세요"
