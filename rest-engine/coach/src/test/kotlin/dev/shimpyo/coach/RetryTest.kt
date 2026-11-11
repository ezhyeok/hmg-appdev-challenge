package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.RecordingLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 다시 시도 간격과 루프 (Retry.kt). 가상 시간으로 돈다 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryTest {

    @Test
    fun theWaitDoublesFromTwoSecondsToAMinute() {
        val b = Backoff()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), List(7) { b.next() })
    }

    @Test
    fun aRunThatLastedGoesBackToTheShortWait() {
        val b = Backoff()
        repeat(5) { b.next() }
        assertEquals("30초 못 채운 시도는 늘어난 간격 그대로", 60_000L, b.next(ranMs = 29_999))
        assertEquals("30초 넘게 잘 돌았으면 처음 간격", 2_000L, b.next(ranMs = 30_000))
        assertEquals(4_000L, b.next(ranMs = 100))
        b.reset()
        assertEquals(2_000L, b.next())
    }

    @Test
    fun untilSuccessRetriesAtTheBackoffPaceAndReturnsTheValue() = runTest {
        val attempts = mutableListOf<Long>()
        val log = RecordingLog()
        val value = Retry.untilSuccess("voice", log) {
            attempts += testScheduler.currentTime
            if (attempts.size < 4) error("not yet")
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L), attempts)
        assertEquals(3, log.lines.count { it.startsWith("W voice failed: not yet") })
    }

    @Test
    fun aTimeoutInsideTheBlockIsAFailureNotACancellation() = runTest {
        // 블록 안의 withTimeout이 던지는 것도 CancellationException이다. 그대로 던지면 루프가 조용히 끝난다
        var attempts = 0
        val value = Retry.untilSuccess("navi") {
            attempts++
            if (attempts < 3) withTimeout(1_000) { awaitCancellation() }
            attempts
        }
        assertEquals(3, value)
    }

    @Test
    fun cancellingTheCallerStopsTheLoopAtOnce() = runTest {
        var attempts = 0
        val job = launch {
            Retry.untilSuccess<Unit>("car") {
                attempts++
                error("down")
            }
        }
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(2, attempts)
        job.cancelAndJoin()
        advanceTimeBy(600_000)
        assertEquals("취소한 뒤에는 다시 하지 않는다", 2, attempts)
        assertTrue(job.isCancelled)
    }

    @Test
    fun foreverRerunsABlockThatEndsOrFailsAndKeepsTheShortWaitAfterAHealthyRun() = runTest {
        val clock = ElapsedClock { testScheduler.currentTime }
        val starts = mutableListOf<Long>()
        val job = launch {
            Retry.forever("vehicle", clock) {
                starts += testScheduler.currentTime
                when (starts.size) {
                    1 -> error("connect failed")          // 곧바로 실패: 2초 뒤
                    2 -> return@forever                   // 끝남도 다시 한다: 4초 뒤
                    3 -> { delay(40_000); error("lost") } // 40초 잘 돌았다: 처음 간격(2초)으로
                    else -> awaitCancellation()
                }
            }
        }
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(listOf(0L, 2_000L, 6_000L, 48_000L), starts)
        job.cancelAndJoin()
    }

    @Test
    fun foreverDoesNotSwallowTheCallersCancellation() = runTest {
        val clock = ElapsedClock { testScheduler.currentTime }
        var seen: Throwable? = null
        val job = launch {
            try {
                Retry.forever("location", clock) { awaitCancellation() }
            } catch (e: CancellationException) {
                seen = e
                throw e
            }
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(seen is CancellationException)
    }
}
