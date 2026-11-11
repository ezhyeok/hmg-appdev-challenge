package dev.shimpyo.engine.stops

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 영업 시간 해석 (2026-11-11 데이터 감사 #8). 원천 표기 그대로의 행을 [StopRows]로 바꾸고, 그 행을
 * 표에 넣었다 뺀 결과([StopTable.openNow])로 확인한다. 시각은 서울 시각으로 적는다.
 */
class StopHoursTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun openAt(row: Map<String, Any?>, file: StopFile, at: String): Boolean? {
        val rec = requireNotNull(StopRows.map(file, row)) { "row dropped: $row" }
        val t = StopTable.decode(StopTable.encode(listOf(rec)))
        val ms = LocalDateTime.parse(at).atZone(seoul).toInstant().toEpochMilli()
        return t.openNow(0, ms)
    }

    private fun restroom(vararg kv: Pair<String, Any?>) = mapOf("id" to "t1", "n" to "화장실", "lat" to 37.5, "lon" to 127.0) + kv
    private fun parking(vararg kv: Pair<String, Any?>) =
        mapOf("id" to "p1", "k" to "park", "pk" to 1, "n" to "주차장", "lat" to 37.5, "lon" to 127.0, "wcd" to 50.0) + kv

    @Test
    fun sameOpenAndCloseIsAllDayNotClosed() {
        // 경찰서·지구대 화장실 "09:00-09:00" (교대 시각). 예전에는 늘 닫힌 것으로 읽었다
        val r = restroom("h24" to 0, "oh" to "09:00-09:00")
        for (at in listOf("2026-11-11T03:00", "2026-11-11T09:00", "2026-11-11T08:59", "2026-11-15T23:30")) {
            assertEquals(true, openAt(r, StopFile.RESTROOMS, at), at)
        }
    }

    @Test
    fun closingPastMidnight() {
        // 역 화장실 "05:00-24:20": 예전에는 24:20을 못 읽어 '모름'이었다
        val r = restroom("h24" to 0, "oh" to "05:00-24:20")
        assertEquals(true, openAt(r, StopFile.RESTROOMS, "2026-11-11T00:10"))
        assertEquals(false, openAt(r, StopFile.RESTROOMS, "2026-11-11T00:20"))
        assertEquals(false, openAt(r, StopFile.RESTROOMS, "2026-11-11T04:59"))
        assertEquals(true, openAt(r, StopFile.RESTROOMS, "2026-11-11T05:00"))
        assertEquals(true, openAt(r, StopFile.RESTROOMS, "2026-11-11T23:59"))
    }

    @Test
    fun closingAt2359OrMidnightRunsToTheEndOfTheDay() {
        assertEquals(true, openAt(restroom("h24" to 0, "oh" to "09:00-23:59"), StopFile.RESTROOMS, "2026-11-11T23:59"))
        assertEquals(true, openAt(restroom("h24" to 0, "oh" to "09:00-24:00"), StopFile.RESTROOMS, "2026-11-11T23:59"))
        assertEquals(false, openAt(restroom("h24" to 0, "oh" to "09:00-24:00"), StopFile.RESTROOMS, "2026-11-12T00:00"))
        assertEquals(false, openAt(restroom("h24" to 0, "oh" to "09:00-18:00"), StopFile.RESTROOMS, "2026-11-11T18:00"))
    }

    @Test
    fun unknownStaysUnknown() {
        assertNull(openAt(restroom(), StopFile.RESTROOMS, "2026-11-11T12:00"), "시간 정보 없음")
        assertNull(openAt(restroom("h24" to 0, "oh" to "09:00-"), StopFile.RESTROOMS, "2026-11-11T12:00"), "닫는 시각 없음")
        assertEquals(true, openAt(restroom("h24" to 1), StopFile.RESTROOMS, "2026-11-11T03:00"))
    }

    @Test
    fun weekdayOnlyParkingIsClosedOnWeekendsAndHolidays() {
        // 운영요일 '평일'만인 주차장 611곳: 예전에는 주말에도 평일 시간으로 열려 있다고 했다
        val p = parking("oh" to "09:00-18:00", "days" to "w")
        assertEquals(true, openAt(p, StopFile.PARKABLES, "2026-11-11T10:00"), "수요일")
        assertEquals(false, openAt(p, StopFile.PARKABLES, "2026-11-14T10:00"), "토요일")
        assertEquals(false, openAt(p, StopFile.PARKABLES, "2026-11-15T10:00"), "일요일")
        assertEquals(false, openAt(p, StopFile.PARKABLES, "2026-09-25T10:00"), "추석(금)")
    }

    @Test
    fun saturdayAndHolidayHoursAreUsedOnTheirDays() {
        val p = parking("oh" to "09:00-18:00", "ohs" to "10:00-14:00", "ohh" to "12:00-13:00", "days" to "wsh")
        assertEquals(true, openAt(p, StopFile.PARKABLES, "2026-11-13T17:00"), "금요일 평일 시간")
        assertEquals(false, openAt(p, StopFile.PARKABLES, "2026-11-14T15:00"), "토요일 14시에 닫음")
        assertEquals(true, openAt(p, StopFile.PARKABLES, "2026-11-14T11:00"))
        assertEquals(true, openAt(p, StopFile.PARKABLES, "2026-11-15T12:30"), "일요일은 공휴일 시간")
        assertEquals(false, openAt(p, StopFile.PARKABLES, "2026-11-15T11:00"))
        assertEquals(false, openAt(p, StopFile.PARKABLES, "2026-03-02T11:00"), "삼일절(일) 대체공휴일 월요일")
    }

    @Test
    fun weekendOnlyLotWithZeroWeekdayHoursIsClosedOnWeekdays() {
        // 원천 실례(곡성기차당뚝방마켓): 평일 "00:00-00:00", 토요일 "00:00-23:59", 운영요일 토요일만
        val p = parking("oh" to "00:00-00:00", "ohs" to "00:00-23:59", "days" to "s")
        assertEquals(false, openAt(p, StopFile.PARKABLES, "2026-11-11T12:00"), "평일은 운영 요일이 아님 (24시간이 아니다)")
        assertEquals(true, openAt(p, StopFile.PARKABLES, "2026-11-14T03:00"))
        assertEquals(false, openAt(p, StopFile.PARKABLES, "2026-11-15T12:00"), "일요일도 운영 요일이 아님")
    }

    @Test
    fun operatingDayWithoutHoursIsUnknown() {
        val p = parking("oh" to "09:00-18:00", "days" to "ws")
        assertNull(openAt(p, StopFile.PARKABLES, "2026-11-14T10:00"), "토요일 운영인데 시간이 없다")
        assertEquals(true, openAt(parking("h24" to 1), StopFile.PARKABLES, "2026-11-15T03:00"))
    }

    @Test
    fun timeZoneIsSeoulWhateverTheDeviceZone() {
        val rec = requireNotNull(StopRows.map(StopFile.RESTROOMS, restroom("h24" to 0, "oh" to "05:00-24:20")))
        val t = StopTable.decode(StopTable.encode(listOf(rec)))
        // 2026-11-11T15:10Z = 서울 00:10 (목), 15:30Z = 00:30
        assertEquals(true, t.openNow(0, java.time.Instant.parse("2026-11-11T15:10:00Z").toEpochMilli()))
        assertEquals(false, t.openNow(0, java.time.Instant.parse("2026-11-11T15:30:00Z").toEpochMilli()))
    }

    @Test
    fun koreanCalendar() {
        fun type(d: String) = KoreanCalendar.dayType(LocalDate.parse(d))
        assertEquals(DayType.WEEKDAY, type("2026-11-11"))
        assertEquals(DayType.SATURDAY, type("2026-11-14"))
        assertEquals(DayType.HOLIDAY, type("2026-11-15"))
        // 고정 공휴일과 대체공휴일
        for (d in listOf("2026-01-01", "2026-03-02", "2026-05-05", "2026-08-17", "2026-10-05", "2026-10-09", "2026-12-25",
            "2027-03-01", "2027-08-16", "2027-10-04", "2027-10-11", "2027-12-27")) assertEquals(DayType.HOLIDAY, type(d), d)
        // 음력 명절·선거일 (2026), 토요일과 겹친 공휴일도 공휴일 시간
        for (d in listOf("2026-02-16", "2026-02-17", "2026-02-18", "2026-05-25", "2026-06-03", "2026-09-24", "2026-09-26"))
            assertEquals(DayType.HOLIDAY, type(d), d)
        assertEquals(DayType.HOLIDAY, type("2026-06-06"), "현충일(토)은 공휴일 시간")
        assertEquals(DayType.WEEKDAY, type("2026-06-08"), "현충일은 대체공휴일 없음")
        assertEquals(DayType.WEEKDAY, type("2026-09-28"), "추석 연휴가 토요일과 겹쳐도 대체공휴일 없음")
    }

    @Test
    fun dayHoursOfNormalizesTheSourceNotation() {
        assertEquals(DayHours.Open(300, 20), DayHours.of(300, 24 * 60 + 20))
        assertEquals(DayHours.Open(540, 0), DayHours.of(540, 24 * 60 - 1), "23:59 = 하루 끝")
        assertEquals(DayHours.Open(540, 0), DayHours.of(540, 24 * 60))
        assertNull(DayHours.of(null, 600))
        assertNull(DayHours.of(-5, 600))
    }
}
