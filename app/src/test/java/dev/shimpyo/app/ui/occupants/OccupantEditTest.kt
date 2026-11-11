package dev.shimpyo.app.ui.occupants

import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import org.junit.Assert.assertEquals
import org.junit.Test

/** 탑승자 편집 (UI 감사 #21, #23) */
class OccupantEditTest {

    @Test
    fun ageInLabelFollowsStepper() {
        // 음성 설정이 붙인 "7살 아이"가 나이를 바꿔도 남았다
        val kid = Occupant("p1", OccupantType.CHILD, ageYears = 7, label = "7살 아이")
        assertEquals(Occupant("p1", OccupantType.CHILD, ageYears = 8, label = "8살 아이"), withAge(kid, 8))
        // 나이를 담지 않은 이름은 그대로, 다른 숫자는 건드리지 않는다
        assertEquals("첫째", withAge(kid.copy(label = "첫째"), 8).label)
        assertEquals("17번 좌석 9세", withAge(kid.copy(label = "17번 좌석 7세"), 9).label)
        assertEquals("", withAge(kid.copy(label = "", ageYears = null), 5).label)
        assertEquals(5, withAge(kid.copy(label = "", ageYears = null), 5).ageYears)
    }

    @Test
    fun withdrawingConsentKeepsNonHealthOccupants() {
        val list = listOf(
            Occupant("driver", OccupantType.ADULT),
            Occupant("p", OccupantType.PREGNANT),
            Occupant("k", OccupantType.CHILD, ageYears = 5),
            Occupant("o", OccupantType.OAB),
        )
        assertEquals(listOf("driver", "k"), withoutHealth(list).map { it.id })
    }
}
