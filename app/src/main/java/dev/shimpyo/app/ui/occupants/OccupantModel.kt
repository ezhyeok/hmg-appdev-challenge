package dev.shimpyo.app.ui.occupants

import androidx.compose.runtime.Immutable
import dev.shimpyo.app.ui.Labels
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import kotlin.math.roundToInt

/*
 * 탑승자 화면의 순수 모델: 평소 화장실 간격 선택(FIXLIST U3), 자는 중 표시(U4), 행과 요약의 글자.
 * '평소 간격'은 Occupant.intervalOverrideMin이다: 정하면 엔진이 유형별 사전값 대신 그 사람의 사전 중앙값으로 쓰고(RestroomNeedModel),
 * 탑승자 설정과 함께 저장된다. 본인이 아는 평소 간격은 나이·성별 같은 어떤 공변량보다 예측에 도움이 된다
 * (docs/research/need-covariates.md).
 */

/** 평소 간격 선택지 (분). null = 자동(유형 기본값: 엔진 CoachConfig.prior*Min) */
val INTERVAL_CHOICES: List<Int?> = listOf(null, 60, 90, 120, 150, 180)

/** "자동", "1시간", "1시간 30분" */
fun intervalChoiceLabel(min: Int?): String = if (min == null) "자동" else Labels.duration(min)

/** 영아(기저귀)와 반려견은 화장실 간격을 물을 대상이 아니다: 엔진의 정차 간 상한과 유형 기본값만 쓴다 */
fun canSetInterval(type: OccupantType): Boolean = type != OccupantType.INFANT && type != OccupantType.DOG

/** 평소 간격을 정한다. [min]이 null이면 자동으로 되돌린다. 정할 수 없는 유형은 그대로 */
fun withInterval(o: Occupant, min: Int?): Occupant = if (canSetInterval(o.type)) o.copy(intervalOverrideMin = min?.toDouble()) else o

/** 정해 둔 평소 간격 (분). 자동이면 null */
private fun overrideMin(o: Occupant): Int? = o.intervalOverrideMin?.takeIf { it > 0.0 }?.roundToInt()

/** 선택지 [choice]가 지금 값인지. 선택지에 없는 값이 정해져 있으면 어느 것도 켜지지 않는다 */
fun intervalSelected(o: Occupant, choice: Int?): Boolean = overrideMin(o) == choice

/** 선택 버튼 글자: "평소 간격 · 자동", "평소 간격 · 2시간" */
fun intervalButtonLabel(o: Occupant): String = "평소 간격 · ${intervalChoiceLabel(overrideMin(o))}"

/** 선택지 아래 안내: "자동은 어른 기본값(2시간 30분)이에요". 정해 둔 값과 관계없이 유형 기본값을 말한다 */
fun autoIntervalHint(o: Occupant): String =
    "자동은 ${Labels.typeName(o.type)} 기본값(${Labels.duration(Labels.baselineMin(o.copy(intervalOverrideMin = null)))})이에요"

/** 밤에 잠든 아이는 화장실 게이지에 여유를 더 둔다 (엔진 CoachInput.asleepOccupantIds). 아이만 표시할 수 있다 */
fun canSleep(o: Occupant): Boolean = o.type == OccupantType.CHILD

/** '자는 중'을 켠 아이 행의 안내. 엔진은 밤(20~07시)에만 여유를 더 두고, 깼다고 하거나 화장실에 들르면 코치가 표시를 푼다 */
const val ASLEEP_HINT = "밤에 자는 동안은 화장실 여유를 조금 더 둬요"

/**
 * 탑승자 행의 둘째 줄: "어른 · 평소 2시간 30분마다", "평소 1시간 45분마다 · 자는 중".
 * 예전에는 분으로만 썼다("150분마다"). 간격을 정할 수 없는 유형(영아·반려견)은 '평소'를 붙이지 않는다
 */
fun occupantSubtitle(o: Occupant, isDriver: Boolean, asleep: Boolean = false): String {
    val every = "${Labels.duration(Labels.baselineMin(o))}마다"
    return listOfNotNull(
        Labels.typeName(o.type).takeIf { isDriver },
        if (canSetInterval(o.type)) "평소 $every" else every,
        "자는 중".takeIf { asleep && canSleep(o) },
    ).joinToString(" · ")
}

/** 목록 아래 요약 두 줄 */
@Immutable
data class OccupantsSummary(val headline: String, val detail: String)

/**
 * "지금 기준: 1시간 45분마다 화장실". 기준은 지금 화장실 게이지를 정하는 탑승자([drivenBy], Gauge.drivenBy)이고,
 * 아직 모르면 간격이 가장 짧은 사람이다. 탑승자가 없으면 null
 */
fun occupantsSummary(list: List<Occupant>, drivenBy: String?): OccupantsSummary? {
    val strictest = list.firstOrNull { it.id == drivenBy } ?: list.minByOrNull { Labels.baselineMin(it) } ?: return null
    val detail = when {
        list.size > 1 -> "가장 엄격한 탑승자(${Labels.occupantName(strictest)})를 따라요"
        overrideMin(strictest) != null -> "직접 정한 평소 간격이에요"
        else -> "같이 탄 사람을 더하면 기준이 바뀌어요"
    }
    return OccupantsSummary("지금 기준: ${Labels.duration(Labels.baselineMin(strictest))}마다 화장실", detail)
}
