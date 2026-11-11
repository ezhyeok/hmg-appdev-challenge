package dev.shimpyo.app.ui.occupants

import dev.shimpyo.app.ui.Labels
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType

// RED 단계 자리 표시: 지금 화면의 문구 그대로

val INTERVAL_CHOICES: List<Int?> = listOf(null)

fun intervalChoiceLabel(min: Int?): String = ""

fun canSetInterval(type: OccupantType): Boolean = true

fun withInterval(o: Occupant, min: Int?): Occupant = o

fun intervalSelected(o: Occupant, choice: Int?): Boolean = false

fun intervalButtonLabel(o: Occupant): String = ""

fun canSleep(o: Occupant): Boolean = false

fun occupantSubtitle(o: Occupant, isDriver: Boolean, asleep: Boolean = false): String =
    if (isDriver) "${Labels.typeName(o.type)} · ${Labels.baselineMin(o)}분마다" else "${Labels.baselineMin(o)}분마다"

data class OccupantsSummary(val headline: String, val detail: String)

fun occupantsSummary(list: List<Occupant>, drivenBy: String?): OccupantsSummary? {
    val strictest = list.firstOrNull { it.id == drivenBy } ?: list.minByOrNull { Labels.baselineMin(it) } ?: return null
    return OccupantsSummary(
        "지금 기준: ${Labels.baselineMin(strictest)}분마다 화장실",
        if (list.size > 1) "가장 엄격한 탑승자(${Labels.occupantName(strictest)})를 따라요" else "같이 탄 사람을 더하면 기준이 바뀌어요",
    )
}
