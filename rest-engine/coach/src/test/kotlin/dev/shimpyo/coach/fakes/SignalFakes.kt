package dev.shimpyo.coach.fakes

import dev.shimpyo.coach.SignalGroup
import dev.shimpyo.coach.SignalLink
import dev.shimpyo.coach.SignalSource
import dev.shimpyo.coach.SignalWatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

/*
 * 신호 수집(SignalCollector) 테스트의 가짜 (appfix 담당). 차량 SDK의 신호 하나와 연결 하나를 흉내 낸다.
 */

/**
 * 가짜 신호. [value]가 지금 값이다. 구독하면 리스너가 걸린 것이고([subscriptions]), [announce]면 걸자마자 지금 값을 준다
 * (에뮬레이터의 Vehicle·Car 리스너가 그렇다). [set]은 값을 바꾸고 리스너에 알린다. [mute]면 리스너가 조용히 죽은 것이다.
 */
class FakeSignal<T : Any>(val name: String, var value: T? = null, var announce: Boolean = true) {
    private val events = MutableSharedFlow<T?>(extraBufferCapacity = 64)
    var subscriptions = 0
        private set
    var active = 0
        private set
    var reads = 0
        private set
    var readError: (() -> Exception)? = null
    var subscribeError: (() -> Exception)? = null
    var mute = false
    /** 다음 구독 n번은 흐름이 값을 주고 곧 끝난다 */
    var endAfterSubscribe = 0

    val source = SignalSource(
        name,
        read = {
            reads++
            readError?.let { throw it() }
            value
        },
        changes = { changes() },
    )

    private fun changes(): Flow<T?> = flow {
        subscribeError?.let { throw it() }
        subscriptions++
        active++
        try {
            if (announce && !mute) emit(value)
            if (endAfterSubscribe > 0) {
                endAfterSubscribe--
                return@flow
            }
            events.collect { if (!mute) emit(it) }
        } finally {
            active--
        }
    }

    /** 차에서 값이 바뀌었다 */
    fun set(v: T?) {
        value = v
        events.tryEmit(v)
    }

    /** 같은 값을 다시 알린다 (조향각처럼 주기적으로 오는 이벤트) */
    fun tick() {
        events.tryEmit(value)
    }
}

/** 받은 값을 차례로 남기는 수신처 */
class Received<T : Any> {
    val values = mutableListOf<T?>()
    val last: T? get() = values.lastOrNull()
    fun watch(signal: FakeSignal<T>) = SignalWatch(signal.source) { values += it }
    fun group(signal: FakeSignal<T>) = SignalGroup.of(signal.source) { values += it }
}

/** 가짜 연결. [connects]에 연결한 가상 시각과 again 값을 남긴다 */
class FakeLink(
    override val name: String,
    override val groups: List<SignalGroup>,
    private val now: () -> Long,
) : SignalLink {
    val connects = mutableListOf<Pair<Long, Boolean>>()
    /** 남은 횟수만큼 연결이 실패한다 */
    var failConnects = 0
    private var lost = CompletableDeferred<Unit>()

    override suspend fun connect(again: Boolean) {
        connects += now() to again
        if (failConnects > 0) {
            failConnects--
            throw IllegalStateException("car service not ready")
        }
        lost = CompletableDeferred()
    }

    override suspend fun awaitLost() {
        lost.await()
    }

    /** 연결이 끊겼다고 스스로 알린다 (Car 서비스가 죽었다) */
    fun lose() {
        lost.complete(Unit)
    }
}
