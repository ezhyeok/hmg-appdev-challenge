package dev.shimpyo.coach

import dev.shimpyo.coach.voice.OccupantWords
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType

/**
 * "일곱 살 아이 하나랑 강아지", "할머니랑 아기" 같은 말을 탑승자 목록으로 바꾼다 (규칙 기반).
 * 운전자(성인)는 항상 첫 번째로 넣는다. 탑승자를 가리키는 낱말은 IntentParser와 같은 목록([OccupantWords])을 쓴다.
 */
object OccupantPhrase {

    fun parse(text: String): List<Occupant> {
        val t = text.replace(" ", "")
        val out = mutableListOf(Occupant("driver", OccupantType.ADULT, label = "운전자"))
        var n = 0
        fun add(type: OccupantType, count: Int, age: Int? = null, label: String) {
            repeat(count.coerceIn(1, 4)) { out += Occupant("p${n++}", type, ageYears = age, label = label) }
        }
        CHILD.findAll(t).forEach { m ->
            val age = parseNumber(m.groupValues[1])
            add(OccupantType.CHILD, parseNumber(m.groupValues[3]) ?: 1, age, if (age != null) "${age}살 아이" else "아이")
        }
        if (out.none { it.type == OccupantType.CHILD } && OccupantWords.has(t, OccupantWords.CHILD)) {
            add(OccupantType.CHILD, countNear(t, OccupantWords.CHILD) ?: 1, 7, "아이")
        }
        if (OccupantWords.has(t, OccupantWords.INFANT)) add(OccupantType.INFANT, 1, label = "아기")
        if (OccupantWords.has(t, OccupantWords.ELDER)) add(OccupantType.ELDERLY, countNear(t, OccupantWords.ELDER) ?: 1, label = "어르신")
        if (OccupantWords.has(t, OccupantWords.PREGNANT)) add(OccupantType.PREGNANT, 1, label = "임신부")
        if (OccupantWords.has(t, OccupantWords.DOG)) add(OccupantType.DOG, countNear(t, OccupantWords.DOG) ?: 1, label = "반려견")
        if (OccupantWords.has(t, OccupantWords.OAB)) add(OccupantType.OAB, 1, label = "과민성 방광")
        if (OccupantWords.has(t, OccupantWords.IBS)) add(OccupantType.IBS, 1, label = "과민성 장")
        if (OccupantWords.has(t, OccupantWords.ADULT)) add(OccupantType.ADULT, countNear(t, OccupantWords.ADULT) ?: 1, label = "어른")
        return if (out.size == 1) emptyList() else out
    }

    fun describe(list: List<Occupant>): String =
        list.drop(1).groupBy { it.label }.entries.joinToString(", ") { (label, items) -> if (items.size > 1) "$label ${items.size}명" else label }

    private fun countNear(t: String, words: List<String>): Int? {
        val w = OccupantWords.find(t, words) ?: return null
        val after = t.substring(OccupantWords.indexOf(t, w) + w.length).take(4)
        return COUNT.find(after)?.groupValues?.get(1)?.let { parseNumber(it) }
    }

    private fun parseNumber(s: String?): Int? {
        if (s.isNullOrEmpty()) return null
        s.toIntOrNull()?.let { return it }
        return NUM_WORDS.entries.firstOrNull { s.startsWith(it.key) }?.value
    }

    private val NUM_WORDS = linkedMapOf(
        "열한" to 11, "열두" to 12, "한" to 1, "하나" to 1, "두" to 2, "둘" to 2, "세" to 3, "셋" to 3, "네" to 4, "넷" to 4,
        "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9, "열" to 10,
    )
    private const val NUM = "([0-9]+|열한|열두|하나|한|둘|두|셋|세|넷|네|다섯|여섯|일곱|여덟|아홉|열)"
    private val CHILD = Regex("${NUM}살(짜리)?(?:아이|애|딸|아들|꼬마)(?:${NUM}(?:명|명이))?")
    private val COUNT = Regex("^$NUM(?:명|마리)?")
}
