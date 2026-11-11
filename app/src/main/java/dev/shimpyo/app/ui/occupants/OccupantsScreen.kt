package dev.shimpyo.app.ui.occupants

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shimpyo.app.ui.Labels
import dev.shimpyo.app.ui.Labels.isHealth
import dev.shimpyo.app.ui.LocalLayout
import dev.shimpyo.app.ui.PrefsState
import dev.shimpyo.app.ui.components.ButtonGap
import dev.shimpyo.app.ui.components.ButtonSize
import dev.shimpyo.app.ui.components.ChipFlow
import dev.shimpyo.app.ui.components.DescribeModel
import dev.shimpyo.app.ui.components.ListDivider
import dev.shimpyo.app.ui.components.ListGroup
import dev.shimpyo.app.ui.components.PleosButton
import dev.shimpyo.app.ui.components.PleosIconButton
import dev.shimpyo.app.ui.components.PleosSelectButton
import dev.shimpyo.app.ui.components.PleosStepper
import dev.shimpyo.app.ui.components.SectionTitle
import dev.shimpyo.app.ui.components.ShimpyoCard
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.components.SmallChip
import dev.shimpyo.app.ui.components.ToggleButton
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.PleosType
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import kotlinx.coroutines.delay

/**
 * 4. 탑승자 설정 (screens.md 4장, 정차 중에만). 누가 탔는지에 따라 화장실 기준 시간과 필요한 시설이 바뀐다.
 * 키보드를 쓰지 않는다: 유형은 버튼, 아이 나이는 스테퍼, 평소 화장실 간격은 선택지, 또는 "말로 설정"(UiCommand.DescribeOccupants).
 * 운전자 행은 항상 있고 지울 수 없다. 건강 관련 유형은 동의한 뒤에만 고를 수 있다.
 *
 * @param asleepIds 운전자가 '자는 중'이라고 알린 탑승자 (CoachUiState.asleepOccupantIds). 표시는 코치 상태를 따른다
 * @param describe [말로 설정] 버튼의 모양과 동작 (MicModel.describeModel: 쓸 수 없으면 이유를 보인다)
 */
@Composable
fun OccupantsScreen(
    occupants: List<Occupant>,
    drivenBy: String?,
    asleepIds: Set<String>,
    describe: DescribeModel,
    prefs: PrefsState,
    onSetOccupants: (List<Occupant>) -> Unit,
    onSetAsleep: (String, Boolean) -> Unit,
    onDescribe: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyAdding: Boolean = false,
) {
    val layout = LocalLayout.current
    val c = ShimpyoTheme.colors
    // 나이 스테퍼는 연달아 누르므로 잠깐 모았다가 한 번만 보낸다(보낼 때마다 서비스가 탑승자 프로필을 다시 만든다).
    // pending은 서비스가 바뀐 목록을 돌려줄 때까지 화면에 보여 줄 목록, unsent는 아직 보내지 않은 목록이다
    var pending by remember { mutableStateOf<List<Occupant>?>(null) }
    val unsent = remember { arrayOfNulls<List<Occupant>>(1) }
    val base = occupants.ifEmpty { listOf(DRIVER) }
    val list = pending ?: base
    val send by rememberUpdatedState(onSetOccupants)
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        if (unsent[0] !== p) return@LaunchedEffect
        delay(STEPPER_DEBOUNCE_MS)
        unsent[0] = null
        send(p)
    }
    // 서비스 상태가 바뀌었고 보낼 것이 없으면 서비스 목록을 그대로 보여 준다
    LaunchedEffect(occupants) { if (unsent[0] == null) pending = null }
    // 모으는 중에 화면을 떠나면 남은 것을 보낸다
    DisposableEffect(Unit) { onDispose { unsent[0]?.let { send(it) } } }

    fun changeLater(newList: List<Occupant>) {
        unsent[0] = newList
        pending = newList
    }
    fun changeNow(newList: List<Occupant>) {
        unsent[0] = null
        pending = newList
        onSetOccupants(newList)
    }
    // 처음(운전자 혼자)에는 유형 선택을 펼쳐 둔다
    var adding by rememberSaveable { mutableStateOf(initiallyAdding || base.size <= 1) }

    val listPart: @Composable () -> Unit = {
        OccupantList(
            list = list,
            drivenBy = drivenBy,
            asleepIds = asleepIds,
            onAge = { o, age -> changeLater(list.map { if (it.id == o.id) withAge(it, age) else it }) },
            onInterval = { o, min -> changeNow(list.map { if (it.id == o.id) withInterval(it, min) else it }) },
            onAsleep = onSetAsleep,
            onRemove = { o -> changeNow(list.filterNot { it.id == o.id }) },
        )
        Spacer(Modifier.height(ButtonGap))
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGap)) {
            if (!layout.landscape) {
                PleosButton(
                    if (adding) "추가 닫기" else "탑승자 추가",
                    { adding = !adding },
                    Modifier.weight(1f),
                    icon = if (adding) ShimpyoIcons.Close else ShimpyoIcons.Add,
                )
            }
            // 쓸 수 없을 때도 누를 수 있다: 사선 마이크로 보이고, 누르면 이유를 알린다 (마이크 버튼과 같은 규칙)
            PleosButton(
                describe.label,
                onDescribe,
                Modifier.weight(1f),
                icon = if (describe.available) ShimpyoIcons.Mic else ShimpyoIcons.MicOff,
            )
        }
        describe.reason?.let { why ->
            Spacer(Modifier.height(Dimens.Space.xs))
            Text(why, style = PleosType.BodyMedium, color = c.textTertiary)
        }
        Spacer(Modifier.height(Dimens.Space.l))
        Summary(list, drivenBy)
    }
    val addPart: @Composable () -> Unit = {
        AddPanel(list, prefs, onAdd = { type -> changeNow(list + newOccupant(type)) }, onSetOccupants = { changeNow(it) })
    }

    if (layout.landscape) {
        Row(modifier.fillMaxSize().padding(horizontal = layout.margin), horizontalArrangement = Arrangement.spacedBy(Dimens.Space.xxl)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = layout.bottom)) {
                Spacer(Modifier.height(layout.gap))
                listPart()
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = layout.bottom)) {
                Spacer(Modifier.height(layout.gap))
                addPart()
            }
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = layout.margin, end = layout.margin, bottom = layout.bottom)) {
            Spacer(Modifier.height(layout.gap))
            listPart()
            if (adding) {
                Spacer(Modifier.height(layout.gap))
                addPart()
            }
        }
    }
}

/**
 * 탑승자 목록. 행마다 이름·평소 간격, 아이는 나이 스테퍼, 그 아래 조작 줄에 [평소 간격 · 자동 v](영아·반려견 제외)와
 * 아이의 [자는 중]. 평소 간격 버튼을 누르면 그 행 아래에 선택지 여섯 개가 펼쳐진다 (한 번에 한 사람).
 */
@Composable
private fun OccupantList(
    list: List<Occupant>,
    drivenBy: String?,
    asleepIds: Set<String>,
    onAge: (Occupant, Int) -> Unit,
    onInterval: (Occupant, Int?) -> Unit,
    onAsleep: (String, Boolean) -> Unit,
    onRemove: (Occupant) -> Unit,
) {
    val c = ShimpyoTheme.colors
    // 좁은 세로 윈도(12.9" 628dp, WF 648dp)에서는 나이 스테퍼를 이름 아래 줄로 내린다: 한 줄이면 이름이 약 48dp만 남았다 (UI 감사 #22)
    val narrow = LocalLayout.current.width < NARROW_ROW_WIDTH
    // 평소 간격 선택지를 펼쳐 둔 탑승자
    var intervalOpenFor by rememberSaveable { mutableStateOf<String?>(null) }
    ListGroup {
        list.forEachIndexed { i, o ->
            if (i > 0) ListDivider()
            // 운전자는 항상 첫 번째다 (Settings 기본값, OccupantPhrase 모두 운전자를 먼저 넣는다)
            val isDriver = o.id == DRIVER.id || (i == 0 && o.type == OccupantType.ADULT)
            val asleep = canSleep(o) && o.id in asleepIds
            val stepper: @Composable () -> Unit = {
                val age = o.ageYears ?: DEFAULT_CHILD_AGE
                PleosStepper(
                    value = "${age}세",
                    onMinus = { onAge(o, (age - 1).coerceAtLeast(MIN_CHILD_AGE)) },
                    onPlus = { onAge(o, (age + 1).coerceAtMost(MAX_CHILD_AGE)) },
                    minusEnabled = age > MIN_CHILD_AGE,
                    plusEnabled = age < MAX_CHILD_AGE,
                    label = "나이",
                    width = 300.dp,
                )
            }
            // 이름 아래 줄들은 아이콘 폭만큼 들여 이름과 맞춘다
            val under = Modifier.fillMaxWidth().padding(start = Dimens.Icon.base + Dimens.Space.l)
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Space.l, vertical = Dimens.Space.s)) {
                Row(Modifier.fillMaxWidth().heightIn(min = Dimens.Component.listItemHeight - Dimens.Space.m), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Labels.typeIcon(o.type), contentDescription = null, modifier = Modifier.size(Dimens.Icon.base), tint = c.iconSecondary)
                    Spacer(Modifier.width(Dimens.Space.l))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (isDriver) "운전자" else Labels.occupantName(o),
                                style = PleosType.TitleSmall,
                                color = c.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (o.id == drivenBy && list.size > 1) {
                                // 지금 화장실 게이지를 정하는 사람
                                Spacer(Modifier.width(Dimens.Space.xs))
                                // 반전 글자: 라이트 흰색(4.9:1), 다크 static_dark_100(4.9:1)
                                SmallChip("기준", container = c.gaugeOk, content = c.textOnFilledPrimary)
                            }
                        }
                        Text(occupantSubtitle(o, isDriver, asleep), style = PleosType.BodyMedium, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (o.type == OccupantType.CHILD && !narrow) {
                        stepper()
                        Spacer(Modifier.width(Dimens.Space.m))
                    }
                    if (!isDriver) {
                        PleosIconButton(ShimpyoIcons.Close, contentDescription = "${Labels.occupantName(o)} 빼기", onClick = { onRemove(o) })
                    }
                }
                if (o.type == OccupantType.CHILD && narrow) {
                    Spacer(Modifier.height(Dimens.Space.xs))
                    Row(under) { stepper() }
                }
                if (canSetInterval(o.type) || canSleep(o)) {
                    val open = intervalOpenFor == o.id
                    Spacer(Modifier.height(Dimens.Space.xs))
                    ChipFlow(under, spacing = Dimens.Space.s, maxLines = 2) {
                        if (canSetInterval(o.type)) {
                            PleosSelectButton(intervalButtonLabel(o), expanded = open, onClick = { intervalOpenFor = if (open) null else o.id })
                        }
                        if (canSleep(o)) {
                            ToggleButton("자는 중", asleep, { onAsleep(o.id, !asleep) }, icon = ShimpyoIcons.Bedtime)
                        }
                    }
                    if (open && canSetInterval(o.type)) {
                        Spacer(Modifier.height(Dimens.Space.s))
                        // 줄이 넘치면 다음 줄로 보낸다. 선택지가 빠지지 않게 줄 수를 넉넉히 둔다 (ChipFlow는 넘치는 줄을 그리지 않는다)
                        ChipFlow(under, spacing = Dimens.Space.s, maxLines = INTERVAL_CHOICES.size) {
                            INTERVAL_CHOICES.forEach { choice ->
                                ToggleButton(
                                    intervalChoiceLabel(choice),
                                    selected = intervalSelected(o, choice),
                                    onClick = {
                                        onInterval(o, choice)
                                        intervalOpenFor = null
                                    },
                                    role = Role.RadioButton,
                                )
                            }
                        }
                        Spacer(Modifier.height(Dimens.Space.xs))
                        Text(autoIntervalHint(o), style = PleosType.BodyMedium, color = c.textTertiary, modifier = under)
                    }
                    if (asleep) {
                        Spacer(Modifier.height(Dimens.Space.xs))
                        Text(ASLEEP_HINT, style = PleosType.BodyMedium, color = c.textTertiary, modifier = under)
                    }
                    Spacer(Modifier.height(Dimens.Space.xs))
                }
            }
        }
    }
}

@Composable
private fun Summary(list: List<Occupant>, drivenBy: String?) {
    val c = ShimpyoTheme.colors
    val summary = occupantsSummary(list, drivenBy)
    Column {
        if (summary != null) {
            Text(summary.headline, style = PleosType.TitleSmall, color = c.textPrimary)
            Spacer(Modifier.height(Dimens.Space.xxs))
            Text(summary.detail, style = PleosType.BodyMedium, color = c.textSecondary)
        }
        Spacer(Modifier.height(Dimens.Space.m))
        Row(verticalAlignment = Alignment.Top) {
            Icon(ShimpyoIcons.Lock, contentDescription = null, modifier = Modifier.size(Dimens.Icon.small), tint = c.iconTertiary)
            Spacer(Modifier.width(Dimens.Space.xs))
            Text("건강 관련 정보는 이 기기에만 저장되고 서버로 보내지 않아요", style = PleosType.BodyMedium, color = c.textTertiary)
        }
    }
}

/** 유형 선택: 일반 유형 + 동의 스위치 + 건강 관련 유형(동의 전에는 꺼짐). Basic Medium 버튼 2열 */
@Composable
private fun AddPanel(list: List<Occupant>, prefs: PrefsState, onAdd: (OccupantType) -> Unit, onSetOccupants: (List<Occupant>) -> Unit) {
    val c = ShimpyoTheme.colors
    val layout = LocalLayout.current
    ShimpyoCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(layout.cardPadding)) {
        Text("누구를 더할까요?", style = PleosType.TitleSmall, color = c.textPrimary)
        Spacer(Modifier.height(Dimens.Space.m))
        TypeGrid(GENERAL_TYPES, enabled = true, onAdd)
        SectionTitle("건강 관련")
        HealthConsentRow(prefs, list, onSetOccupants)
        Spacer(Modifier.height(Dimens.Space.s))
        TypeGrid(HEALTH_TYPES, enabled = prefs.healthConsent, onAdd)
        if (!prefs.healthConsent) {
            Spacer(Modifier.height(Dimens.Space.s))
            Text("동의하면 임신부·과민성 방광·과민성 장을 고를 수 있어요", style = PleosType.BodyMedium, color = c.textTertiary)
        }
    }
}

@Composable
private fun TypeGrid(types: List<OccupantType>, enabled: Boolean, onAdd: (OccupantType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space.m)) {
        types.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space.m)) {
                pair.forEach { t ->
                    PleosButton(
                        Labels.typeName(t),
                        { onAdd(t) },
                        Modifier.weight(1f),
                        size = ButtonSize.Medium,
                        icon = Labels.typeIcon(t),
                        enabled = enabled,
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 아이 나이를 바꾼다. 이름이 옛 나이를 담고 있으면("7살 아이", 음성 설정이 붙인 이름) 그 숫자도 바꾼다
 * (UI 감사 #21: 나이를 8세로 바꿔도 "7살 아이"로 남았다)
 */
fun withAge(o: Occupant, age: Int): Occupant {
    val old = o.ageYears ?: return o.copy(ageYears = age)
    val label = o.label.replace(Regex("(?<!\\d)$old(\\s*)(살|세)")) { m -> "$age${m.groupValues[1]}${m.groupValues[2]}" }
    return o.copy(ageYears = age, label = label)
}

private fun newOccupant(type: OccupantType): Occupant = Occupant(
    id = "o${System.currentTimeMillis()}",
    type = type,
    ageYears = if (type == OccupantType.CHILD) DEFAULT_CHILD_AGE else null,
)

private val DRIVER = Occupant("driver", OccupantType.ADULT, label = "운전자")
private val GENERAL_TYPES = listOf(OccupantType.ADULT, OccupantType.CHILD, OccupantType.INFANT, OccupantType.ELDERLY, OccupantType.DOG)
private val HEALTH_TYPES = OccupantType.entries.filter { it.isHealth }
private const val DEFAULT_CHILD_AGE = 5
private const val MIN_CHILD_AGE = 3
private const val MAX_CHILD_AGE = 12
private const val STEPPER_DEBOUNCE_MS = 700L
/** 이보다 좁은 윈도에서는 탑승자 행을 두 줄로 놓는다 (LayoutSpec.margin의 좁은 기준과 같다) */
private val NARROW_ROW_WIDTH = 700.dp
