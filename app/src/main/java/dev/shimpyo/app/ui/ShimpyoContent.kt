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
import androidx.compose.runtime.mutableIntStateOf
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
import dev.shimpyo.app.ui.components.DescribeModel
import dev.shimpyo.app.ui.components.VoiceButton
import dev.shimpyo.app.ui.components.describeModel
import dev.shimpyo.app.ui.components.micModel
import dev.shimpyo.app.ui.demo.DemoPanel
import dev.shimpyo.app.ui.demo.demoTitle
import dev.shimpyo.app.ui.drive.DriveScreen
import dev.shimpyo.app.ui.drive.cardShowsGoTo
import dev.shimpyo.app.ui.history.HistoryScreen
import dev.shimpyo.app.ui.occupants.OccupantsScreen
import dev.shimpyo.app.ui.plan.PlanScreen
import dev.shimpyo.app.ui.settings.SettingsScreen
import dev.shimpyo.app.ui.settings.SettingsStatus
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.app.ui.urgent.URGENT_ACK_TIMEOUT_MS
import dev.shimpyo.app.ui.urgent.UrgentRequestIds
import dev.shimpyo.app.ui.urgent.UrgentScreen
import dev.shimpyo.app.ui.urgent.UrgentStatus
import dev.shimpyo.app.ui.urgent.adoptUrgentRequest
import dev.shimpyo.app.ui.urgent.rememberDismissed
import dev.shimpyo.app.ui.urgent.settleDismissed
import dev.shimpyo.app.ui.urgent.urgentQuestion
import dev.shimpyo.app.ui.urgent.urgentView
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.GoToOutcome
import dev.shimpyo.coach.UiCommand
import dev.shimpyo.engine.Occupant
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
 * 각 화면의 입력 상태는 SaveableStateHolder에, 진행 중 요청(급해 번호, 방금 보낸 들르기, 계획 계산 중)은 [ContentState]에 남아
 * 액티비티가 다시 만들어져도 이어진다. 들르기가 진행 중인지(내비에 넣는 중)는 코치 상태가 정한다([goToBusy]).
 *
 * 탐색 라이브러리 없이 화면 하나를 상태로 들고 있다. 급해 결과는 다른 화면 위에 덮는 한 단계 화면이다.
 */
@Composable
fun ShimpyoContent(
    state: CoachUiState,
    host: AppHost,
    modifier: Modifier = Modifier,
    restriction: DriveRestriction = driveRestriction(UxRestrictions.NOT_CONNECTED),
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

    // 주행 중이면 팝업을 닫고, 제한을 실제로 읽었을 때만 고른 화면을 주행으로 되돌린다. 모를 때(차가운 시작, 프로세스 복원)는
    // 그리기만 주행 화면으로 하고(shown) 저장된 화면은 남겨 둔다: 정차로 읽히면 그 화면으로 돌아간다 (재검토)
    LaunchedEffect(driving, restriction.known) {
        if (driving) overlays.dismissPopup()
        if (driving && restriction.known && ui.screen != Screen.DRIVE) {
            ui.screen = Screen.DRIVE
            overlays.toast("주행 중에는 주행 화면만 보여요")
        }
    }

    // 계획 요청: 코치가 계획 번호(planGeneration)를 쓰면 번호가 오를 때, 쓰지 않으면(옛 코치) 새 계획 객체가 올 때 진행 표시를 끈다.
    // 번호를 쓰는 코치는 지나친 정차를 빼며 같은 계획을 매초 다시 낼 수 있어 객체로 보면 바로 풀린다 (재검토).
    // 다시 계산한 계획이 이전과 같아 상태가 바뀌지 않으면 시간이 지나면 조용히 끄고, 계획이 아예 없을 때만 실패를 알린다
    var planBaseline by remember { mutableStateOf(state.plan) }
    LaunchedEffect(state.plan, state.planGeneration) {
        val arrived = if (state.planGeneration > 0L) state.planGeneration != ui.planRequestedGen else state.plan !== planBaseline
        planBaseline = state.plan
        if (arrived) ui.planRequested = false
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
    // 계획 화면이 보이는 동안만 코치가 계획을 고쳐 세운다. 다른 화면으로 가거나 주행을 시작하면(주행 화면만 그린다) 닫고,
    // 다시 열면 이어서 요청한다 (리뷰 R-F12: 한 번 요청한 계획을 화면을 떠난 뒤에도 30초마다 다시 세웠다).
    // 앱이 가려질 때(경유지를 넣어 내비가 앞으로 옴, 뒤로 보냄)도 바로 닫는다: 가려진 동안에는 화면이 다시 그려지지 않아
    // 그 뒤의 주행 시작을 알 수 없다. 다시 보이면 그때의 화면으로 다시 판단한다
    var shownAgain by remember { mutableIntStateOf(0) }
    PlatformVisibilityEffect(onHidden = { actions.planVisibility(false) }, onShown = { shownAgain++ })
    val planVisible = shown == Screen.PLAN
    LaunchedEffect(planVisible, state.plan != null, shownAgain) { actions.planVisibility(planVisible) }

    // 급해: 요청 번호로 코치의 진행 상태를 짝짓는다 (UrgentModel). 답 기다림 시간 초과는 그 번호에만 붙이고 저장한다:
    // Boolean 하나로 두면 [다시 시도]로 새 번호를 보낸 첫 프레임에 앞 요청의 '초과'가 보이고, 다시 만들어지면 8초를 또 기다렸다
    LaunchedEffect(ui.urgentRequested) {
        val id = ui.urgentRequested ?: return@LaunchedEffect
        if (ui.ackExpiredFor == id) return@LaunchedEffect
        delay(URGENT_ACK_TIMEOUT_MS)
        ui.ackExpiredFor = id
    }
    val requested = ui.urgentRequested
    val urgent = urgentView(
        requested = requested,
        dismissed = ui.dismissedUrgent,
        state = state,
        ackExpired = requested != null && ui.ackExpiredFor == requested,
        picking = ui.pickingId,
        resultsSeen = requested != null && ui.urgentResultsFor == requested,
    )
    val urgentVisible = urgent != null
    LaunchedEffect(state.urgentRequestId, state.urgentState, state.urgent == null) {
        ui.dismissedUrgent = settleDismissed(ui.dismissedUrgent, state)
    }
    LaunchedEffect(urgent?.requestId, urgent?.status) {
        // 음성 "화장실 급해"로 코치가 연 다른 번호를 따르고 있으면 그 번호를 우리 요청으로 삼는다 (끝날 때 닫히게)
        ui.urgentRequested = adoptUrgentRequest(ui.urgentRequested, urgent, current.value)
        // 옛 코치에서 결과를 보였다: 그 뒤 목록이 지워지면 끝난 것이다(음성 '응'으로 경유지를 넣음)
        if (urgent != null && (urgent.status == UrgentStatus.READY || urgent.status == UrgentStatus.NO_RESULT)) {
            ui.urgentResultsFor = ui.urgentRequested
        }
    }
    // 화면이 닫혔다(코치가 끝냄, 경유지를 넣음, 음성으로 연 급해가 끝남): 기다리던 것을 모두 푼다
    LaunchedEffect(urgentVisible) {
        if (!urgentVisible) {
            ui.urgentRequested = null
            ui.pickingId = null
        }
    }
    // 들르기(내비에 넣기)가 진행 중인지는 코치가 정한다: goToPending이 있는 동안 넣기 버튼을 막고 진행을 보인다.
    // 화면은 결과를 기다리는 시간을 재지 않는다 (닫힘 검증 UI-N2: 12초 제한이 19.5초 걸린 성공보다 먼저 '결과 없음'을 띄웠다).
    val busy = goToBusy(state, ui.goToSentId)
    // 화면이 보낸 것을 코치가 받지 않았을 때만(지난 질문에 한 대답처럼 들르기가 시작되지 않았다) 잠시 뒤 버튼을 다시 연다.
    // 코치가 넣는 중이면 이 기다림은 돌지 않는다 (멈춘 코치에 남은 값은 넣는 중으로 보지 않는다)
    val coachPendingId = coachGoToPending(state)?.id
    LaunchedEffect(ui.goToSentId, coachPendingId) {
        val sent = ui.goToSentId ?: return@LaunchedEffect
        if (coachPendingId != null) return@LaunchedEffect
        delay(GOTO_ACK_MS)
        if (ui.goToSentId == sent) ui.goToSentId = null
        if (ui.pickingId == sent) ui.pickingId = null
    }
    // 들르기·안내 결과 (코치의 goToResult): 기다리던 버튼을 풀고, 카드가 보여 주지 않는 결과는 토스트로 알린다
    val goTo = state.goToResult
    LaunchedEffect(goTo?.at, goTo?.stop?.id, goTo?.outcome) {
        val r = goTo ?: return@LaunchedEffect
        if (r.at == ui.seenGoToAt) return@LaunchedEffect
        ui.seenGoToAt = r.at
        if (ui.goToSentId == r.stop.id) ui.goToSentId = null
        val view = urgent
        if (ui.pickingId == r.stop.id) {
            ui.pickingId = null
            // 고른 곳이 들어갔으면 급해 화면을 닫는다 (코치도 끝내지만 한 틱 늦을 수 있다)
            if (r.outcome != GoToOutcome.FAILED && view != null) actions.closeUrgent(view.requestId, answerUrgentPrompt = false)
        }
        val onCard = shown == Screen.DRIVE && view == null && cardShowsGoTo(current.value, driving, r)
        goToToast(r, shownOnCard = onCard)?.let(overlays::toast)
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
                        busy = busy,
                        mic = mic,
                        onMic = { actions.mic(mic) },
                        onPick = { actions.pick(it) },
                        onClose = { actions.closeUrgent(urgent.requestId) },
                        onRetry = { actions.requestUrgent(replacing = urgent.requestId) },
                        maxChars = restriction.maxChars,
                        cabinSupported = state.cabinSupported,
                        question = urgentQuestion(state),
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
                                        busy = busy,
                                        onMakePlan = { actions.makePlan() },
                                        onGoTo = { actions.goTo(it) },
                                    )
                                    Screen.OCCUPANTS -> {
                                        val describe = describeModel(state.voice, state.ttsAvailable, state.sttAvailable, voiceGuidance)
                                        OccupantsScreen(
                                            occupants = state.occupants,
                                            drivenBy = state.restroom?.drivenBy,
                                            asleepIds = state.asleepOccupantIds,
                                            describe = describe,
                                            prefs = host.prefs,
                                            onSetOccupants = { actions.setOccupants(it) },
                                            onSetAsleep = { id, asleep -> actions.setAsleep(id, asleep) },
                                            onDescribe = { actions.describe(describe) },
                                        )
                                    }
                                    Screen.HISTORY -> HistoryScreen(state.history, lastStop = state.lastStop, coachMinuteMs = state.clockMinuteMs)
                                    Screen.SETTINGS -> SettingsScreen(
                                        status = SettingsStatus(state.serviceRunning, state.status, state.llm, state.cabinSupported),
                                        occupants = state.occupants,
                                        prefs = host.prefs,
                                        versionName = host.versionName,
                                        onSetOccupants = { actions.setOccupants(it) },
                                        onClearRecords = { actions.clearRecords() },
                                    )
                                    Screen.DEMO -> DemoPanel(state = state, onScenario = { actions.demo(it) })
                                }
                            }
                        }
                    }
                }
                PopupLayer(overlays)
                ToastLayer(
                    overlays,
                    topInset = if (urgent != null || shown == Screen.DRIVE) layout.headerHeight(driving) else null,
                    notice = coachNotice(state, restriction),
                )
            }
        }
    }
}

/**
 * 화면의 동작. [ContentState]·호스트·오버레이만 들고 있어 한 화면 동안 같은 객체다:
 * 아래 화면에 넘기는 람다가 이것만 잡으므로 매초 새 람다가 생기지 않는다 (UI 감사 S2: 함수 참조 ::pick 등은
 * Kotlin 2.0.0 Compose 컴파일러에서 기억되지 않을 수 있었다).
 * 코치에 명령을 보내는 유일한 곳이다. 무엇을 어떤 순서로 보내는지는 ContentActionsTest가 지킨다.
 */
@Stable
internal class ContentActions(
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
        // 급해 버튼을 한 프레임 안에 두 번 누르면 요청이 둘 간다: 진행 중인 요청이 있으면 다시 시도할 때만 새로 보낸다
        if (replacing == null && ui.urgentRequested != null) return
        if (replacing != null) {
            // 끝난 앞 요청은 닫은 것으로 둔다: 코치가 아직 그 번호를 보이는 동안 화면이 그것을 따르지 않게 (재검토 #2)
            host.send(UiCommand.DismissUrgent(replacing))
            ui.dismissedUrgent = rememberDismissed(ui.dismissedUrgent, replacing)
        }
        val id = UrgentRequestIds.next()
        ui.pickingId = null
        ui.urgentRequested = id
        host.send(UiCommand.Urgent(id))
    }

    /**
     * 급해 화면을 닫는다. 고르지 않고 닫았을 때만 급해 질문에 '아니'로 답한다: 고른 곳을 내비에 넣는 중에 닫은 것은 거절이 아니다
     * (들르기는 계속 진행되고, 다른 화면의 넣기 버튼도 끝날 때까지 막힌다)
     */
    fun closeUrgent(requestId: Long, answerUrgentPrompt: Boolean = true) {
        val p = state().prompt
        if (answerUrgentPrompt && p?.kind == PromptKind.URGENT_RESULT && ui.pickingId == null) host.send(UiCommand.Answer(p.id, false))
        host.send(UiCommand.DismissUrgent(requestId))
        ui.dismissedUrgent = rememberDismissed(ui.dismissedUrgent, requestId)
        ui.urgentRequested = null
        ui.pickingId = null
    }

    /**
     * 급해 결과 고르기. 들르기가 진행 중이면 받지 않는다 (두 번 누르면 경유지 요청이 두 번 갔다).
     * 코치가 묻고 있는 곳("…안내할까요?")을 고르면 그 질문에 '응'으로 답하고, 다른 곳을 고르면 GoTo만 보낸다: 고른 것이 그 질문의
     * 답이고 코치가 질문을 거둔다. 예전에는 거절도 함께 보내서 엔진이 거절만 알고 30초 뒤 다시 물었다 (닫힘 검증 UI-9)
     */
    fun pick(r: RankedStop) {
        if (busy() != null) return
        ui.pickingId = r.stop.id
        ui.goToSentId = r.stop.id
        val p = state().prompt
        if (p?.kind == PromptKind.URGENT_RESULT && p.stop?.id == r.stop.id) {
            host.send(UiCommand.Answer(p.id, true))
        } else {
            host.send(UiCommand.GoTo(r.stop))
        }
    }

    /** 계획 화면의 경유지 넣기. 들르기가 진행 중이면 받지 않는다 (UI 감사 #24) */
    fun goTo(stop: StopCandidate) {
        if (busy() != null) return
        ui.goToSentId = stop.id
        host.send(UiCommand.GoTo(stop))
        overlays.toast("${Labels.withObject(shortStopName(stop.name))} 내비에 넣는 중이에요")
    }

    /**
     * 주행 화면의 경유지 실패 카드 [다시 시도]. GoTo 하나만 보낸다: 지난 실패 결과는 코치가 새 들르기를 시작하며 내린다.
     * 예전에는 DismissGoToResult를 먼저 보내서 코치의 '실패한 수락 → 다시 시도' 연결이 끊겼고, 경유지는 들어가도
     * 엔진은 수락을 몰랐다 (닫힘 검증 UI-3)
     */
    fun retryGoTo(stop: StopCandidate) {
        if (busy() != null) return
        ui.goToSentId = stop.id
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

    /** 탑승자 화면의 [말로 설정]: 쓸 수 있으면 묻고 듣기, 진행 중이면 멈추기, 쓸 수 없으면 이유를 알린다 */
    fun describe(m: DescribeModel) {
        when (m.action) {
            MicAction.LISTEN -> host.send(UiCommand.DescribeOccupants)
            MicAction.STOP -> host.send(UiCommand.StopVoice)
            MicAction.EXPLAIN -> m.reason?.let(overlays::toast)
        }
    }

    /** 탑승자 화면의 '자는 중' (아이). 표시는 코치 상태(asleepOccupantIds)가 돌아올 때 바뀐다 */
    fun setAsleep(occupantId: String, asleep: Boolean) = host.send(UiCommand.SetAsleep(occupantId, asleep))

    /** 탑승자 목록 바꾸기 (탑승자 화면, 설정의 동의 철회). 건강 유형은 코치가 동의를 다시 확인한다 */
    fun setOccupants(list: List<Occupant>) = host.send(UiCommand.SetOccupants(list))

    /** 시연 패널: [scenario]가 null이면 시연을 끝낸다 */
    fun demo(scenario: String?) {
        host.send(UiCommand.Demo(scenario))
        overlays.toast(if (scenario == null) "시연을 끝냈어요" else "시연 시작: ${demoTitle(scenario)}")
    }

    /** 설정 > 기록 지우기 (확인 팝업 뒤) */
    fun clearRecords() {
        overlays.toast(if (host.clearRecords()) "기록을 지웠어요" else "일부 기록을 지우지 못했어요")
    }

    fun makePlan() {
        ui.planRequestedGen = state().planGeneration
        ui.planRequested = true
        ui.planWanted = true
        ui.planResume = false
        host.send(UiCommand.MakePlan)
    }

    /**
     * 계획 화면이 보이는지([visible])에 맞춰 코치의 계획 갱신을 닫거나 잇는다 (리뷰 R-F12).
     * 안 보이면(다른 화면, 주행 시작, 앱이 가려짐) 요청해 둔 계획을 닫는다(ClosePlan, 한 번). 답을 기다리던 요청은 코치가 버리므로
     * '세우는 중'도 내린다: 그대로 두면 6초 뒤 다른 화면에서 "계획을 세우지 못했어요"가 뜬다.
     * 다시 보이는데 계획이 이미 있거나 답을 기다리다 닫은 것이면 이어서 요청한다: 떠 있는 동안 코치가 지나친 정차를 빼고
     * 다시 세운다(UI 감사 #4). 계획을 요청한 적이 없으면 [계획 세우기]를 눌러야 세운다.
     */
    fun planVisibility(visible: Boolean) {
        if (!visible) {
            if (ui.planWanted) {
                ui.planWanted = false
                if (ui.planRequested) {
                    ui.planRequested = false
                    ui.planResume = true
                }
                host.send(UiCommand.ClosePlan)
            }
        } else if (!ui.planWanted && (state().plan != null || ui.planResume)) {
            makePlan()
        }
    }

    private fun busy(): GoToBusy? = goToBusy(state(), ui.goToSentId)
}

private const val PLAN_TIMEOUT_MS = 6_000L

/**
 * 화면이 보낸 들르기를 코치가 받았다고 알릴 때까지 기다리는 시간. 코치는 명령을 받자마자 goToPending을 올린다.
 * 들르기 결과를 기다리는 시간이 아니다: 그것은 코치가 정한다
 */
private const val GOTO_ACK_MS = 5_000L
