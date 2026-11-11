package dev.shimpyo.coach

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 다시 시도 간격. [minMs]에서 시작해 두 배씩 [maxMs]까지 늘린다. 한 번이 [healthyMs] 이상 잘 돌았으면 처음 간격으로 돌아간다
 * (오래 잘 돌다가 끊긴 연결은 곧바로 다시 붙이고, 붙자마자 끊기는 연결은 천천히 다시 붙인다).
 * 예전에는 같은 두 배 늘리기 루프가 CoachService·CarSignals·NaviTracker에 한 벌씩 있었다 (구조 리뷰).
 */
class Backoff(
    private val minMs: Long = MIN_MS,
    private val maxMs: Long = MAX_MS,
    private val healthyMs: Long = HEALTHY_MS,
) {
    private var next = minMs

    /** 방금 시도가 [ranMs] 동안 돌다가 끝났다. 다음 시도까지 기다릴 시간 */
    fun next(ranMs: Long = 0L): Long {
        if (ranMs >= healthyMs) next = minMs
        val wait = next
        next = (next * 2).coerceAtMost(maxMs)
        return wait
    }

    fun reset() {
        next = minMs
    }

    companion object {
        const val MIN_MS = 2_000L
        const val MAX_MS = 60_000L
        const val HEALTHY_MS = 30_000L
    }
}

/**
 * 실패하면 간격을 두고 다시 하는 루프 두 가지. 취소는 그대로 던진다: 부른 코루틴이 취소됐을 때만이다.
 * 블록 안의 시간 초과(TimeoutCancellationException)나 블록이 띄운 자식의 취소는 실패로 보고 다시 한다
 * (다시 던지면 루프가 조용히 끝나 그 신호는 재시작할 때까지 오지 않는다).
 */
object Retry {
    /** [block]이 성공할 때까지 한다. 성공하면 그 값을 돌려준다 */
    suspend fun <T> untilSuccess(name: String, log: CoachLog = CoachLog.NONE, backoff: Backoff = Backoff(), block: suspend () -> T): T {
        while (true) {
            val failure = try {
                return block()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                e
            } catch (e: Exception) {
                e
            }
            val wait = backoff.next()
            log.w("$name failed: ${describe(failure)} (retry in ${wait / 1000}s)")
            delay(wait)
        }
    }

    /**
     * [block]을 계속 돌린다. 끝나거나 실패하면 간격을 두고 다시 한다. 취소될 때까지 돌아오지 않는다.
     * [clock]으로 한 번이 얼마나 돌았는지 재서 [Backoff]에 넘긴다.
     */
    suspend fun forever(name: String, clock: ElapsedClock, log: CoachLog = CoachLog.NONE, backoff: Backoff = Backoff(), block: suspend () -> Unit): Nothing {
        while (true) {
            val startedAt = clock.elapsedMs()
            val failure: Throwable? = try {
                block()
                null
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                e
            } catch (e: Exception) {
                e
            }
            val wait = backoff.next(clock.elapsedMs() - startedAt)
            log.w(if (failure == null) "$name ended (again in ${wait / 1000}s)" else "$name failed: ${describe(failure)} (retry in ${wait / 1000}s)")
            delay(wait)
        }
    }

    private fun describe(e: Throwable): String = e.message ?: e.javaClass.simpleName
}
