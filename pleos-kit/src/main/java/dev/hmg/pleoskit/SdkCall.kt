package dev.hmg.pleoskit

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** SDK 호출이 실패했거나 제한 시간 안에 응답이 없을 때. [call]은 실패한 호출 이름. */
class PleosSdkException(val call: String, message: String, cause: Throwable? = null) :
    Exception("$call: $message", cause)

internal object KitLog {
    private const val TAG = "PleosKit"
    fun d(msg: String) {
        Log.d(TAG, msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t)
    }
}

private class Box<T>(val value: T)

/**
 * `onComplete`/`onFailed` 콜백으로 끝나는 SDK 호출 하나를 suspend 함수로 바꾼다.
 * [block]은 [dispatcher](SDK 전용 스레드)에서 실행되고, 결과 콜백이 올 때까지 호출한 코루틴만 멈춘다.
 * [timeoutMs] 안에 콜백이 오지 않으면 [PleosSdkException]을 던진다.
 */
internal suspend fun <T> sdkCall(
    dispatcher: CoroutineDispatcher,
    name: String,
    timeoutMs: Long,
    block: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit,
): T {
    val started = SystemClock.elapsedRealtime()
    val box = withTimeoutOrNull(timeoutMs) {
        withContext(dispatcher) {
            suspendCancellableCoroutine { cont ->
                // SDK가 콜백을 두 번 부르거나 동기 예외와 콜백이 겹쳐도 한 번만 재개한다
                val done = AtomicBoolean(false)
                try {
                    block(
                        { value -> if (done.compareAndSet(false, true)) cont.resume(Box(value)) },
                        { e -> if (done.compareAndSet(false, true)) cont.resumeWithException(PleosSdkException(name, e.message ?: e.javaClass.simpleName, e)) },
                    )
                } catch (t: Throwable) {
                    if (done.compareAndSet(false, true)) cont.resumeWithException(PleosSdkException(name, t.message ?: t.javaClass.simpleName, t))
                }
            }
        }
    }
    val took = SystemClock.elapsedRealtime() - started
    if (box == null) throw PleosSdkException(name, "no response in ${timeoutMs}ms")
    if (took > 1000) KitLog.d("$name took ${took}ms")
    return box.value
}
