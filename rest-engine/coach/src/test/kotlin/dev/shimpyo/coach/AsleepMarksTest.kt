package dev.shimpyo.coach

import dev.shimpyo.coach.AsleepMarks.Result
import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.KID
import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** '자는 중' 표시의 규칙 (AsleepMarks): 누구를 표시할 수 있고 언제 풀리는지. 엔진은 표시된 아이의 밤 시간 게이지에만 여유를 둔다 */
class AsleepMarksTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val min = 60_000L
    private fun kst(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, seoul).toInstant().toEpochMilli()

    private val second = Occupant("kid2", OccupantType.CHILD, ageYears = 4, label = "둘째")
    private val baby = Occupant("baby", OccupantType.INFANT, label = "아기")
    private val family = listOf(DRIVER, KID, baby)
    private val night = kst(12, 22)

    @Test
    fun onlyAChildOnBoardCanBeMarked() {
        val a = AsleepMarks()
        assertEquals(Result.NOT_A_CHILD, a.set("driver", true, family, night))
        assertEquals("영아는 화장실 게이지가 아니라 수유·기저귀 리듬이다", Result.NOT_A_CHILD, a.set("baby", true, family, night))
        assertEquals("타고 있지 않은 사람", Result.NOT_A_CHILD, a.set("nobody", true, family, night))
        assertTrue(a.ids.isEmpty())
        assertEquals(Result.MARKED, a.set("kid", true, family, night))
        assertEquals(setOf("kid"), a.ids)
        assertEquals("이미 표시했다", Result.NO_CHANGE, a.set("kid", true, family, night + min))
    }

    @Test
    fun wordsThatDoNotSayWhoMeanTheOnlyChild() {
        val one = AsleepMarks()
        assertEquals("아이가 한 명이면 그 아이다", Result.MARKED, one.set(null, true, family, night))
        assertEquals(setOf("kid"), one.ids)

        val two = AsleepMarks()
        assertEquals("아이가 둘이면 누구인지 모른다 (깨어 있는 아이의 게이지를 늘리지 않는다)", Result.UNKNOWN_WHO, two.set(null, true, family + second, night))
        assertTrue(two.ids.isEmpty())
        assertEquals("이름을 말했으면 그 아이만", Result.MARKED, two.set("kid2", true, family + second, night))
        assertEquals(setOf("kid2"), two.ids)

        assertEquals("아이가 없다", Result.NOT_A_CHILD, AsleepMarks().set(null, true, listOf(DRIVER), night))
    }

    @Test
    fun wakingClearsTheOneNamedOrEveryoneWhenNobodyIsNamed() {
        val a = AsleepMarks()
        a.set("kid", true, family + second, night)
        a.set("kid2", true, family + second, night)
        assertEquals(Result.CLEARED, a.set("kid", false, family + second, night + min))
        assertEquals(setOf("kid2"), a.ids)
        assertEquals("표시되지 않은 사람을 깨워도 바뀌는 것이 없다", Result.NO_CHANGE, a.set("kid", false, family + second, night + min))
        a.set("kid", true, family + second, night + min)
        assertEquals("\"깼어\": 누구인지 몰라도 깨우는 쪽은 안전하다", Result.CLEARED, a.set(null, false, family + second, night + 2 * min))
        assertTrue(a.ids.isEmpty())
        assertEquals(Result.NO_CHANGE, a.set(null, false, family + second, night + 3 * min))
    }

    @Test
    fun aRemovedOccupantOrOneWhoIsNoLongerAChildIsCleared() {
        val a = AsleepMarks()
        a.set("kid", true, family + second, night)
        a.set("kid2", true, family + second, night)
        assertFalse("그대로면 바뀌지 않는다", a.onOccupants(family + second))
        assertTrue(a.onOccupants(family))
        assertEquals(setOf("kid"), a.ids)
        assertTrue("같은 id가 어른으로 바뀌었다", a.onOccupants(listOf(DRIVER, KID.copy(type = OccupantType.ADULT))))
        assertTrue(a.ids.isEmpty())
        assertFalse(a.onOccupants(listOf(DRIVER)))
    }

    @Test
    fun aRestroomStopWakesEveryone() {
        // 엔진은 P + 문 열림 + 3분에 모두의 화장실 시계를 돌린다. 표시가 남으면 새 간격에 여유가 또 붙는다
        val a = AsleepMarks()
        a.set("kid", true, family, night)
        val since = night + 10 * min
        assertFalse("문이 열리지 않은 정차", a.onTick(since + 10 * min, ParkState(since, doorSeen = false)))
        assertFalse("3분 전", a.onTick(since + 3 * min - 1_000, ParkState(since, doorSeen = true)))
        assertEquals(setOf("kid"), a.ids)
        assertTrue(a.onTick(since + 3 * min, ParkState(since, doorSeen = true)))
        assertTrue(a.ids.isEmpty())

        a.set("kid", true, family, night + 30 * min)
        assertTrue("떠나는 틱에야 화장실 정차로 기록된 경우", a.onRestroomStop())
        assertTrue(a.ids.isEmpty())
        assertFalse(a.onRestroomStop())
    }

    @Test
    fun theMarkEndsWhenTheLocalClockPassesSevenInTheMorning() {
        assertEquals("엔진이 배수를 거는 밤의 끝과 같은 시각이다", 7, CoachConfig().asleepNightEndHour)
        val a = AsleepMarks()
        a.set("kid", true, family, kst(12, 22))
        assertFalse(a.onTick(kst(13, 6, 59), null))
        assertEquals(setOf("kid"), a.ids)
        assertTrue(a.onTick(kst(13, 7, 0), null))
        assertTrue(a.ids.isEmpty())

        // 새벽에 표시한 것은 그날 아침에, 아침 7시 뒤에 표시한 것(낮잠)은 다음 날 아침에 풀린다
        a.set("kid", true, family, kst(13, 6, 59))
        assertTrue(a.onTick(kst(13, 7, 0), null))
        a.set("kid", true, family, kst(13, 7, 0))
        assertFalse(a.onTick(kst(13, 23, 0), null))
        assertTrue(a.onTick(kst(14, 7, 0), null))

        // 코치가 멈춰 있다가(틱이 건너뛰었다) 한참 뒤에 와도 풀린다
        a.set("kid", true, family, kst(12, 22))
        assertTrue(a.onTick(kst(13, 15, 0), null))
    }

    @Test
    fun differentChildrenMarkedOnDifferentDaysEndAtTheirOwnMorning() {
        val a = AsleepMarks()
        a.set("kid", true, family + second, kst(12, 23))
        a.set("kid2", true, family + second, kst(13, 8)) // 아침 뒤의 낮잠
        assertTrue(a.onTick(kst(13, 8, 1), null))
        assertEquals("밤에 표시한 아이만 풀린다", setOf("kid2"), a.ids)
    }

    @Test
    fun aWorldSwitchClearsEverything() {
        val a = AsleepMarks()
        assertFalse(a.clear())
        a.set("kid", true, family, night)
        val before = a.ids
        assertTrue(a.clear())
        assertTrue(a.ids.isEmpty())
        assertEquals("내보낸 집합은 바뀌지 않는 값이다", setOf("kid"), before)
    }
}
