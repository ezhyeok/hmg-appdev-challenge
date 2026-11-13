package dev.shimpyo.app.ui.history

import dev.shimpyo.coach.StopLog
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 기록 화면의 '지금' (historyNow). 기록 시각은 코치 시각이다. 2026-11-13 시연 영상 녹화: 실제 14:31에 12:40 시연으로 13:04에 끝낸
 * 정차가 "1시간 27분 전"으로 보였다 (화면이 실제 시계로 셌다)
 */
class HistoryNowTest {
    private fun kst(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 13, h, m, 0, 0, ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
    private val lunch = StopLog(start = kst(12, 40), end = kst(13, 4), lat = null, lon = null, restroom = true, name = "안성휴게소")

    @Test
    fun aDemoClockBehindTheRealClockStillSaysJustNow() {
        val now = historyNow(coachMinuteMs = kst(13, 5), wallMs = kst(14, 31), history = listOf(lunch))
        assertEquals("코치 시각으로 센다: 1분 전", 1L, (now - lunch.end) / 60_000L)
    }

    @Test
    fun aDemoClockAheadOfTheRealClockToo() {
        // 새벽 1시에 오후 시연: 코치 시각이 실제보다 앞선다
        val now = historyNow(coachMinuteMs = kst(13, 10), wallMs = kst(1, 30), history = listOf(lunch))
        assertEquals(6L, (now - lunch.end) / 60_000L)
    }

    @Test
    fun beforeTheFirstTickTheWallClockIsUsedButNeverBeforeTheLatestRecord() {
        assertEquals(kst(14, 31), historyNow(coachMinuteMs = null, wallMs = kst(14, 31), history = listOf(lunch)))
        // 분 단위로 내린 코치 시각이 방금 끝난 기록보다 이르면 기록 끝을 쓴다 ('방금')
        assertEquals(lunch.end + 30_000L, historyNow(coachMinuteMs = kst(13, 4), wallMs = kst(9, 0), history = listOf(lunch.copy(end = lunch.end + 30_000L))))
        assertEquals(kst(9, 0), historyNow(coachMinuteMs = null, wallMs = kst(9, 0), history = emptyList()))
    }
}
