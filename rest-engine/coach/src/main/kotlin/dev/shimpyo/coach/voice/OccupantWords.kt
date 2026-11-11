package dev.shimpyo.coach.voice

import dev.shimpyo.engine.OccupantType

/**
 * 탑승자를 가리키는 낱말. IntentParser(누가 화장실에 가고 싶은지, 누가 자는지)와 OccupantPhrase(누가 타는지)가 함께 쓴다.
 * 예전에는 두 곳에 비슷한 목록이 따로 있어서 한쪽에만 낱말이 늘었다("셋째"는 의도에서만, "멍멍이"는 탑승자 말하기에서만 알았다).
 * 띄어쓰기를 지운 말에서 찾는다.
 */
object OccupantWords {
    val INFANT = listOf("아기", "애기", "갓난", "신생아")
    val CHILD = listOf("아이", "애가", "애는", "애도", "애들", "첫째", "둘째", "셋째", "막내", "큰애", "작은애", "딸", "아들", "꼬맹이", "꼬마")
    val ELDER = listOf("할머니", "할아버지", "어르신", "부모님", "어머님", "아버님")
    val PREGNANT = listOf("임산부", "임신", "만삭")
    val DOG = listOf("강아지", "반려견", "멍멍", "댕댕", "개가")
    /** 운전자 말고 함께 탄 어른 */
    val ADULT = listOf("어른", "친구", "와이프", "아내", "남편")
    val OAB = listOf("과민성방광", "방광")
    val IBS = listOf("과민성장", "장트러블", "장이예민")

    /** 말에 나온 유형을 찾는 순서. 아기를 아이라고도 부르므로 영아를 먼저 본다 */
    private val BY_TYPE = listOf(
        OccupantType.INFANT to INFANT, OccupantType.CHILD to CHILD, OccupantType.ELDERLY to ELDER,
        OccupantType.DOG to DOG, OccupantType.PREGNANT to PREGNANT, OccupantType.ADULT to ADULT,
    )

    /** 같은 글자로 시작하지만 사람이 아닌 말. "아이고 졸려"를 아이 이야기로 들으면 운전자의 졸림을 놓친다 */
    private val NOT_A_PERSON = listOf("아이스", "아이고", "아이구", "아이쿠", "아이참", "아이씨", "딸기", "딸꾹")

    /** [words] 가운데 [flat]에 나온 첫 낱말. 없으면 null */
    fun find(flat: String, words: List<String>): String? = words.firstOrNull { indexOf(flat, it) >= 0 }

    fun has(flat: String, words: List<String>): Boolean = find(flat, words) != null

    /** [word]가 사람을 가리키는 말로 나온 자리. 없으면 -1 */
    fun indexOf(flat: String, word: String): Int {
        var i = flat.indexOf(word)
        while (i >= 0) {
            if (NOT_A_PERSON.none { flat.startsWith(it, i) }) return i
            i = flat.indexOf(word, i + 1)
        }
        return -1
    }

    /** 말에 나온 탑승자 유형. 아무도 가리키지 않았으면 null */
    fun typeIn(flat: String): OccupantType? = BY_TYPE.firstOrNull { has(flat, it.second) }?.first
}
