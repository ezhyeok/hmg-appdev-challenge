package dev.shimpyo.engine.stops

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

/**
 * 하루의 영업 구간. 분 단위(0..1439)이고 시각대는 Asia/Seoul이다.
 *
 * 원천 표기를 이렇게 읽는다 (tools/data/README.md "개방시간", 2026-11-11 데이터 감사 #8):
 * - `05:00-24:20`: 자정을 넘겨 다음 날 00:20에 닫는다 → [Open] (300, 20). until < from이면 자정을 넘는 구간이다.
 * - `09:00-09:00`(open == close): 24시간. 이런 화장실은 경찰서·지구대·소방서·주유소라 교대 시각을 적은 것이다.
 *   주차장의 `00:00-00:00`은 운영 요일이 아닌 날에만 나와서 그날은 [Closed]다(요일 규칙이 먼저 적용된다).
 * - `23:59`로 닫는 것은 그날 끝(24:00)으로 본다 (원천이 하루 끝을 23:59로 적는다).
 */
sealed interface DayHours {
    /** 그날 운영하지 않는다 (주차장의 운영 요일 밖) */
    data object Closed : DayHours

    /** [from]부터 [until] 전까지 연다. from == until은 24시간 */
    data class Open(val from: Int, val until: Int) : DayHours {
        init {
            require(from in 0 until DAY && until in 0 until DAY) { "minutes out of range: $from-$until" }
        }

        fun contains(minute: Int): Boolean = when {
            from == until -> true
            from < until -> minute in from until until
            else -> minute >= from || minute < until
        }
    }

    companion object {
        const val DAY = 24 * 60

        /** 여는 시각·닫는 시각(분, 닫는 시각은 다음 날로 넘어가면 1440 이상)을 하루 구간으로. 하나라도 모르면 null */
        fun of(open: Int?, close: Int?): Open? {
            if (open == null || close == null || open < 0 || close < 0 || open > 2 * DAY || close > 2 * DAY) return null
            val until = if (close == DAY - 1) 0 else close % DAY
            return Open(open % DAY, until)
        }
    }
}

/** 영업 시간을 고르는 날의 종류. 공휴일 시간은 일요일과 공휴일에 쓴다 (주차장 표준데이터의 '공휴일' 열) */
enum class DayType { WEEKDAY, SATURDAY, HOLIDAY }

/**
 * 영업 시간 (급해 모드에서 '지금 열려 있는지' 판단). 날의 종류별 구간이고, 모르면 null이다.
 * 공중화장실은 원천에 시간대가 하나라 세 칸이 같다. 주차장은 평일·토요일·공휴일 시간과 운영 요일이 따로 있다.
 */
data class StopHours(
    /** 24시간(true) / 시간 제한(false) / 모름(null). true면 구간과 상관없이 연다 */
    val h24: Boolean?,
    val weekday: DayHours?,
    val saturday: DayHours? = weekday,
    val holiday: DayHours? = weekday,
) {
    /** [type]인 날 [minute](0..1439)에 열려 있는지. 모르면 null. 전날 밤에 시작해 자정을 넘는 구간은 그날 시간표로 본다 */
    fun openAt(type: DayType, minute: Int): Boolean? {
        if (h24 == true) return true
        return when (val d = forDay(type)) {
            null -> null
            DayHours.Closed -> false
            is DayHours.Open -> d.contains(minute)
        }
    }

    fun forDay(type: DayType): DayHours? = when (type) {
        DayType.WEEKDAY -> weekday
        DayType.SATURDAY -> saturday
        DayType.HOLIDAY -> holiday
    }
}

/**
 * 한국의 날 종류 (평일·토요일·일요일/공휴일). 양력 고정 공휴일과 그 대체공휴일은 해마다 계산하고,
 * 음력 명절(설날·추석·부처님오신날)과 선거일은 해마다 날짜가 달라 [LISTED]에 적은 해만 안다.
 * 적지 않은 해의 음력 공휴일은 평일로 본다 (그날 주차장은 평일 시간표를 쓴다). 2028년 이후는 목록을 늘릴 것.
 */
object KoreanCalendar {
    /**
     * 해마다 날짜가 바뀌는 공휴일 (대체공휴일 포함). 출처: 관공서의 공휴일에 관한 규정, 월력요항
     * (2026년: 한국천문연구원, 2027년: 우주항공청 2026-06-29 발표). 목록에 없는 해의 음력 명절은 평일 시간으로 읽힌다
     * (StopHoursTest가 목록의 해를 지킨다. 해가 바뀌면 월력요항을 보고 더한다)
     */
    private val LISTED: Set<LocalDate> = listOf(
        // 2026: 설날 2/16~18, 부처님오신날 5/24(일) → 대체 5/25, 제9회 전국동시지방선거 6/3, 추석 9/24~26
        "2026-02-16", "2026-02-17", "2026-02-18", "2026-05-24", "2026-05-25", "2026-06-03",
        "2026-09-24", "2026-09-25", "2026-09-26",
        // 2027: 설날 2/6~8(설날 2/7이 일요일 → 대체 2/9), 부처님오신날 5/13, 추석 9/14~16
        "2027-02-06", "2027-02-07", "2027-02-08", "2027-02-09", "2027-05-13",
        "2027-09-14", "2027-09-15", "2027-09-16",
    ).map(LocalDate::parse).toSet()

    /** 양력 고정 공휴일: 월, 일, 토·일과 겹치면 대체공휴일이 있는지, 공휴일이 된 해 */
    private class Fixed(val month: Month, val day: Int, val substitute: Boolean, val fromYear: Int = 0)

    private val FIXED = listOf(
        Fixed(Month.JANUARY, 1, false), Fixed(Month.MARCH, 1, true), Fixed(Month.MAY, 5, true),
        Fixed(Month.JUNE, 6, false), Fixed(Month.AUGUST, 15, true), Fixed(Month.OCTOBER, 3, true),
        Fixed(Month.OCTOBER, 9, true), Fixed(Month.DECEMBER, 25, true),
        // 2027년부터 공휴일 (2027년 월력요항: 노동절 5/1(토) → 대체 5/3, 제헌절 7/17(토) → 대체 7/19)
        Fixed(Month.MAY, 1, true, fromYear = 2027), Fixed(Month.JULY, 17, true, fromYear = 2027),
    )

    fun dayType(date: LocalDate): DayType = when {
        isHoliday(date) || date.dayOfWeek == DayOfWeek.SUNDAY -> DayType.HOLIDAY
        date.dayOfWeek == DayOfWeek.SATURDAY -> DayType.SATURDAY
        else -> DayType.WEEKDAY
    }

    fun isHoliday(date: LocalDate): Boolean {
        if (date in LISTED || fixed(date)) return true
        // 대체공휴일: 토·일과 겹친 고정 공휴일 다음의 첫 평일(다른 공휴일이 아닌 날)
        if (date.isWeekend()) return false
        for (f in FIXED) {
            if (!f.substitute || date.year < f.fromYear) continue
            var h = LocalDate.of(date.year, f.month, f.day)
            if (!h.isWeekend()) continue
            do h = h.plusDays(1) while (h.isWeekend() || h in LISTED || fixed(h))
            if (h == date) return true
        }
        return false
    }

    private fun fixed(date: LocalDate) = FIXED.any { date.year >= it.fromYear && date.month == it.month && date.dayOfMonth == it.day }

    private fun LocalDate.isWeekend() = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
}
