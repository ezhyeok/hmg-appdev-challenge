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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shimpyo.app.coach.VoiceState
import dev.shimpyo.app.ui.Labels
import dev.shimpyo.app.ui.Labels.isHealth
import dev.shimpyo.app.ui.LocalLayout
import dev.shimpyo.app.ui.PrefsState
import dev.shimpyo.app.ui.components.ButtonGap
import dev.shimpyo.app.ui.components.ButtonSize
import dev.shimpyo.app.ui.components.ListDivider
import dev.shimpyo.app.ui.components.ListGroup
import dev.shimpyo.app.ui.components.PleosButton
import dev.shimpyo.app.ui.components.PleosIconButton
import dev.shimpyo.app.ui.components.PleosStepper
import dev.shimpyo.app.ui.components.SectionTitle
import dev.shimpyo.app.ui.components.ShimpyoCard
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.components.SmallChip
import dev.shimpyo.app.ui.theme.Dimens
import dev.shimpyo.app.ui.theme.PleosType
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import kotlinx.coroutines.delay

/**
 * 4. 탑승자 설정 (screens.md 4장, 정차 중에만). 누가 탔는지에 따라 화장실 기준 시간과 필요한 시설이 바뀐다.
 * 키보드를 쓰지 않는다: 유형은 버튼, 아이 나이는 스테퍼, 또는 "말로 설정"(UiCommand.DescribeOccupants).
 * 운전자 행은 항상 있고 지울 수 없다. 건강 관련 유형은 동의한 뒤에만 고를 수 있다.
 */
@Composable
fun OccupantsScreen(
    occupants: List<Occupant>,
    drivenBy: String?,
    voice: VoiceState,
    prefs: PrefsState,
    onSetOccupants: (List<Occupant>) -> Unit,
    onDescribe: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyAdding: Boolean = false,
) {
    val layout = LocalLayout.current
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
            onAge = { o, age -> changeLater(list.map { if (it.id == o.id) it.copy(ageYears = age) else it }) },
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
            PleosButton(
                if (voice == VoiceState.LISTENING) "듣는 중" else "말로 설정",
                onDescribe,
                Modifier.weight(1f),
                icon = ShimpyoIcons.Mic,
            )
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

@Composable
private fun OccupantList(list: List<Occupant>, drivenBy: String?, onAge: (Occupant, Int) -> Unit, onRemove: (Occupant) -> Unit) {
    val c = ShimpyoTheme.colors
    ListGroup {
        list.forEachIndexed { i, o ->
            if (i > 0) ListDivider()
            // 운전자는 항상 첫 번째다 (Settings 기본값, OccupantPhrase 모두 운전자를 먼저 넣는다)
            val isDriver = o.id == DRIVER.id || (i == 0 && o.type == OccupantType.ADULT)
            Row(
                Modifier.fillMaxWidth().heightIn(min = Dimens.Component.listItemHeight).padding(horizontal = Dimens.Space.l, vertical = Dimens.Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                    Text(
                        if (isDriver) "${Labels.typeName(o.type)} · ${Labels.baselineMin(o)}분마다" else "${Labels.baselineMin(o)}분마다",
                        style = PleosType.BodyMedium,
                        color = c.textSecondary,
                        maxLines = 1,
                    )
                }
                if (o.type == OccupantType.CHILD) {
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
                    Spacer(Modifier.width(Dimens.Space.m))
                }
                if (!isDriver) {
                    PleosIconButton(ShimpyoIcons.Close, contentDescription = "${Labels.occupantName(o)} 빼기", onClick = { onRemove(o) })
                }
            }
        }
    }
}

@Composable
private fun Summary(list: List<Occupant>, drivenBy: String?) {
    val c = ShimpyoTheme.colors
    val strictest = list.firstOrNull { it.id == drivenBy } ?: list.minByOrNull { Labels.baselineMin(it) }
    Column {
        if (strictest != null) {
            Text("지금 기준: ${Labels.baselineMin(strictest)}분마다 화장실", style = PleosType.TitleSmall, color = c.textPrimary)
            Spacer(Modifier.height(Dimens.Space.xxs))
            Text(
                if (list.size > 1) "가장 엄격한 탑승자(${Labels.occupantName(strictest)})를 따라요" else "같이 탄 사람을 더하면 기준이 바뀌어요",
                style = PleosType.BodyMedium,
                color = c.textSecondary,
            )
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
