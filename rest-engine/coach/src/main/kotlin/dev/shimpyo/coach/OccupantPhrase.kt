package dev.shimpyo.coach

import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType

/**
 * "일곱 살 아이 하나랑 강아지", "할머니랑 아기" 같은 말을 탑승자 목록으로 바꾼다 (규칙 기반).
 * 운전자(성인)는 항상 첫 번째로 넣는다.
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
        if (out.none { it.type == OccupantType.CHILD } && CHILD_PLAIN.any { t.contains(it) }) {
            add(OccupantType.CHILD, countNear(t, CHILD_PLAIN) ?: 1, 7, "아이")
        }
        if (INFANT.any { t.contains(it) }) add(OccupantType.INFANT, 1, label = "아기")
        if (ELDER.any { t.contains(it) }) add(OccupantType.ELDERLY, countNear(t, ELDER) ?: 1, label = "어르신")
        if (PREGNANT.any { t.contains(it) }) add(OccupantType.PREGNANT, 1, label = "임신부")
        if (DOG.any { t.contains(it) }) add(OccupantType.DOG, countNear(t, DOG) ?: 1, label = "반려견")
        if (OAB.any { t.contains(it) }) add(OccupantType.OAB, 1, label = "과민성 방광")
        if (IBS.any { t.contains(it) }) add(OccupantType.IBS, 1, label = "과민성 장")
        if (ADULT.any { t.contains(it) }) add(OccupantType.ADULT, countNear(t, ADULT) ?: 1, label = "어른")
        return if (out.size == 1) emptyList() else out
    }

    fun describe(list: List<Occupant>): String =
        list.drop(1).groupBy { it.label }.entries.joinToString(", ") { (label, items) -> if (items.size > 1) "$label ${items.size}명" else label }

    private fun countNear(t: String, words: List<String>): Int? {
        val w = words.firstOrNull { t.contains(it) } ?: return null
        val after = t.substringAfter(w).take(4)
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
    private val CHILD_PLAIN = listOf("아이", "애들", "딸", "아들", "첫째", "둘째")
    private val INFANT = listOf("아기", "애기", "갓난", "신생아")
    private val ELDER = listOf("할머니", "할아버지", "어르신", "부모님")
    private val PREGNANT = listOf("임산부", "임신", "만삭")
    private val DOG = listOf("강아지", "반려견", "멍멍이", "댕댕이")
    private val OAB = listOf("과민성방광", "방광")
    private val IBS = listOf("과민성장", "장트러블", "장이예민")
    private val ADULT = listOf("어른", "친구", "와이프", "아내", "남편")
}
