package dev.shimpyo.app.ui.occupants

import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 탑승자 편집 (UI 감사 #21·#23), 평소 화장실 간격(FIXLIST U3), 자는 중 표시(U4) */
class OccupantEditTest {

    private val driver = Occupant("driver", OccupantType.ADULT, label = "운전자")
    private val kid = Occupant("k", OccupantType.CHILD, ageYears = 7)

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

    // ------------------------------------------------------------ 평소 간격 (U3)

    @Test
    fun usualIntervalChoicesSetTheOverrideAndAutoClearsIt() {
        assertEquals(listOf("자동", "1시간", "1시간 30분", "2시간", "2시간 30분", "3시간"), INTERVAL_CHOICES.map(::intervalChoiceLabel))
        assertEquals(120.0, withInterval(driver, 120).intervalOverrideMin!!, 0.0)
        // 자동 = 유형 기본값으로 돌아간다 (엔진은 null이면 유형별 사전값을 쓴다)
        assertNull(withInterval(driver.copy(intervalOverrideMin = 120.0), null).intervalOverrideMin)
        // 이름·나이는 그대로
        val named = kid.copy(label = "7살 아이")
        assertEquals(named.copy(intervalOverrideMin = 90.0), withInterval(named, 90))
    }

    @Test
    fun infantsAndDogsHaveNoUsualIntervalChoice() {
        // 영아(기저귀)와 반려견은 '화장실 간격'으로 물을 대상이 아니다: 엔진의 정차 간 상한·유형 기본값만 쓴다
        assertFalse(canSetInterval(OccupantType.INFANT))
        assertFalse(canSetInterval(OccupantType.DOG))
        OccupantType.entries.filter { it != OccupantType.INFANT && it != OccupantType.DOG }.forEach { assertTrue(it.name, canSetInterval(it)) }
        val baby = Occupant("b", OccupantType.INFANT)
        assertEquals(baby, withInterval(baby, 60))
    }

    @Test
    fun theSelectorShowsWhatIsSetAndHighlightsIt() {
        assertEquals("평소 간격 · 자동", intervalButtonLabel(driver))
        assertTrue(intervalSelected(driver, null))
        assertFalse(intervalSelected(driver, 150))
        val set = driver.copy(intervalOverrideMin = 120.0)
        assertEquals("평소 간격 · 2시간", intervalButtonLabel(set))
        assertTrue(intervalSelected(set, 120))
        assertFalse(intervalSelected(set, null))
        // 선택지에 없는 값(다른 경로로 정해진 간격)은 그대로 보이고 어느 선택지도 켜지지 않는다
        val odd = driver.copy(intervalOverrideMin = 75.0)
        assertEquals("평소 간격 · 1시간 15분", intervalButtonLabel(odd))
        assertTrue(INTERVAL_CHOICES.none { intervalSelected(odd, it) })
        // 자동이 무엇인지는 정해 둔 값과 관계없이 유형 기본값으로 말한다
        assertEquals("자동은 어른 기본값(2시간 30분)이에요", autoIntervalHint(set))
        assertEquals("자동은 아이 기본값(1시간 45분)이에요", autoIntervalHint(kid))
    }

    @Test
    fun theRowAndTheSummarySayTheIntervalInWords() {
        // 2026-11-12 화면: "운전자 · 어른 · 150분마다", "지금 기준: 150분마다 화장실"
        assertEquals("어른 · 평소 2시간 30분마다", occupantSubtitle(driver, isDriver = true))
        assertEquals("어른 · 평소 2시간마다", occupantSubtitle(driver.copy(intervalOverrideMin = 120.0), isDriver = true))
        assertEquals("평소 1시간 45분마다", occupantSubtitle(kid, isDriver = false))
        assertEquals("평소 1시간 45분마다 · 자는 중", occupantSubtitle(kid, isDriver = false, asleep = true))
        // 영아·반려견은 간격을 정하지 않으므로 '평소'를 붙이지 않는다
        assertEquals("3시간마다", occupantSubtitle(Occupant("b", OccupantType.INFANT), isDriver = false))

        val alone = occupantsSummary(listOf(driver), drivenBy = "driver")!!
        assertEquals("지금 기준: 2시간 30분마다 화장실", alone.headline)
        assertEquals("같이 탄 사람을 더하면 기준이 바뀌어요", alone.detail)
        val set = driver.copy(intervalOverrideMin = 120.0)
        assertEquals("지금 기준: 2시간마다 화장실", occupantsSummary(listOf(set), drivenBy = "driver")!!.headline)
        assertEquals("직접 정한 평소 간격이에요", occupantsSummary(listOf(set), drivenBy = "driver")!!.detail)

        val family = occupantsSummary(listOf(set, kid), drivenBy = "k")!!
        assertEquals("지금 기준: 1시간 45분마다 화장실", family.headline)
        assertEquals("가장 엄격한 탑승자(아이 7세)를 따라요", family.detail)
        // 게이지를 정한 사람을 아직 모르면 간격이 가장 짧은 사람
        assertEquals("지금 기준: 1시간 45분마다 화장실", occupantsSummary(listOf(set, kid), drivenBy = null)!!.headline)
        // 아이의 간격을 1시간으로 정하면 기준도 따라간다
        assertEquals("지금 기준: 1시간마다 화장실", occupantsSummary(listOf(set, withInterval(kid, 60)), drivenBy = "k")!!.headline)
        assertNull(occupantsSummary(emptyList(), drivenBy = null))
    }

    // ------------------------------------------------------------ 자는 중 (U4)

    @Test
    fun onlyChildrenCanBeMarkedAsleep() {
        assertTrue(canSleep(kid))
        OccupantType.entries.filter { it != OccupantType.CHILD }.forEach { assertFalse(it.name, canSleep(Occupant("x", it))) }
    }
}
