package dev.shimpyo.coach

import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.OccupantType.ADULT
import dev.shimpyo.engine.OccupantType.CHILD
import dev.shimpyo.engine.OccupantType.DOG
import dev.shimpyo.engine.OccupantType.ELDERLY
import dev.shimpyo.engine.OccupantType.IBS
import dev.shimpyo.engine.OccupantType.INFANT
import dev.shimpyo.engine.OccupantType.OAB
import dev.shimpyo.engine.OccupantType.PREGNANT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "누가 함께 타나요?"에 한 말 → 탑승자 목록 (OccupantPhrase). 탑승자를 가리키는 낱말은 IntentParser와 같은 목록
 * (voice/OccupantWords)을 쓴다: 예전에는 두 목록이 따로라서 "셋째"·"막내"는 의도에서만, "멍멍이"는 여기서만 알았다.
 */
class OccupantPhraseTest {
    private fun types(text: String): List<OccupantType> = OccupantPhrase.parse(text).map { it.type }

    @Test
    fun theDriverComesFirstAndPassengersFollow() {
        val list = OccupantPhrase.parse("일곱 살 아이 하나랑 강아지")
        assertEquals(listOf(ADULT, CHILD, DOG), list.map { it.type })
        assertEquals("driver", list.first().id)
        assertEquals(7, list[1].ageYears)
        assertEquals("7살 아이", list[1].label)
        assertEquals("7살 아이, 반려견", OccupantPhrase.describe(list))
    }

    @Test
    fun phrasesPeopleSay() {
        val rows = listOf(
            "할머니랑 아기" to listOf(ADULT, INFANT, ELDERLY),
            "할머니랑 강아지" to listOf(ADULT, ELDERLY, DOG),
            "임산부 한 명" to listOf(ADULT, PREGNANT),
            "일곱 살 아이 하나" to listOf(ADULT, CHILD),
            "아이 둘" to listOf(ADULT, CHILD, CHILD),
            "애들 셋이랑 와이프" to listOf(ADULT, CHILD, CHILD, CHILD, ADULT),
            "다섯살 딸이랑 세살 아들" to listOf(ADULT, CHILD, CHILD),
            "강아지 두 마리" to listOf(ADULT, DOG, DOG),
            "부모님 두 분" to listOf(ADULT, ELDERLY, ELDERLY),
            "과민성 방광이 있는 친구" to listOf(ADULT, OAB, ADULT),
            "장이 예민한 남편" to listOf(ADULT, IBS, ADULT),
            // 의도 분류와 같은 낱말을 안다 (목록을 하나로 합친 뒤)
            "막내랑 멍멍이" to listOf(ADULT, CHILD, DOG),
            "셋째" to listOf(ADULT, CHILD),
            "어머님 모시고 가요" to listOf(ADULT, ELDERLY),
            "댕댕이" to listOf(ADULT, DOG),
        )
        val wrong = rows.mapNotNull { (said, expected) -> types(said).takeIf { it != expected }?.let { "  \"$said\" → $it (기대: $expected)" } }
        assertTrue("${wrong.size}/${rows.size}줄이 다르다\n${wrong.joinToString("\n")}", wrong.isEmpty())
    }

    @Test
    fun wordsThatOnlyLookLikePeopleAreNotPassengers() {
        // 아무도 못 알아들었으면 빈 목록 (코어가 LLM에 맡기거나 화면에서 고르라고 한다)
        for (said in listOf("아이스크림 먹고 싶어", "아이고 힘들다", "딸기 사 가자", "혼자 가요", "음 글쎄")) {
            assertEquals(said, emptyList<Occupant>(), OccupantPhrase.parse(said))
        }
    }

    @Test
    fun describeGroupsTheSameLabels() {
        val list = OccupantPhrase.parse("아이 둘이랑 할머니")
        assertEquals("아이 2명, 어르신", OccupantPhrase.describe(list))
        assertEquals("운전자만 있으면 말할 것이 없다", "", OccupantPhrase.describe(list.take(1)))
    }
}
