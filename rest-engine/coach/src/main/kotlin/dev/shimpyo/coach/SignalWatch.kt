package dev.shimpyo.coach

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/*
 * 차량 신호를 끊기지 않게 받는 방법 (docs/design/coach-layer/core.md 8장 '신호 수집').
 * 앱(CarSignals)은 무엇을 받을지만 적고(연결 SignalLink, 묶음 SignalGroup, 신호 SignalWatch), 어떻게 받을지는 여기서 정한다.
 * 안드로이드·SDK를 모르므로 가짜 신호로 서버에서 시험한다 (SignalCollectorTest).
 *
 * 예전 방식의 문제 (서비스 감사 D13·P8·P12, 리뷰 R-F5·R-F10)
 * - 연결 실패를 삼켜서 다시 시도하는 루프가 돌지 않았다: Car 연결이 한 번 실패하면 재시작할 때까지 기어·차속이 없었다.
 * - 영역 조회 하나가 실패하면 그 뒤에 거는 신호(문, 조향, 브레이크)를 모두 잃었다.
 * - 서비스가 죽어도 프로세스가 살아 있으면 마지막 값이 그대로 남았다.
 *
 * 규칙
 * 1. 연결: 붙지 못하면 간격을 늘려 가며 다시 붙는다 (Retry.forever).
 * 2. 묶음: 영역을 조회해야 하는 신호는 묶음마다 따로 풀고 따로 다시 한다. 다른 묶음은 영향받지 않는다.
 * 3. 신호: 구독한다. 리스너가 곧 지금 값을 주지 않으면 한 번 읽는다. 구독이 실패하거나 끝나면 그 신호만 다시 건다.
 * 4. 다시 읽기: [SignalCollector.refreshMs]마다 값을 다시 읽는다. 그동안 이벤트가 없었는데 값이 달라져 있으면 구독이 죽은 것이다:
 *    읽은 값을 넘기고 다시 건다 (SignalHealth.stale).
 * 5. 연결 점검: [SignalCollector.checkMs]마다, 그동안 읽기를 둘 이상 했는데 모두 실패했고 이벤트도 하나도 없었으면 연결이 죽은 것이다:
 *    모두 걷고 다시 붙는다 (LinkHealth.lost). 연결이 끊긴 것을 스스로 아는 연결(Car 서비스)은 곧바로 알린다 (SignalLink.awaitLost).
 * 6. 연결을 떠나면 그 연결의 신호 값은 모르는 것이다: null을 넘긴다 (멈춘 값을 계속 쓰지 않는다).
 */

/** 신호 하나의 출처. 앱이 SDK로 채우고 테스트는 가짜로 채운다 */
class SignalSource<T : Any>(
    val name: String,
    /** 지금 값을 한 번 읽는다. 차가 값을 주지 않으면 null, 읽지 못하면 예외 */
    val read: suspend () -> T?,
    /** 바뀔 때마다 내는 흐름. 수집을 시작하면 리스너를 걸고 취소하면 푼다. 걸지 못하면 예외로 끝난다 */
    val changes: () -> Flow<T?>,
    /** 수신 시각으로 신선도를 재는 신호: 값이 같아도 성공한 재조회는 전달한다. 실패한 조회는 갱신이 아니다. */
    val reportReads: Boolean = false,
)

/** 지켜볼 신호 하나와 값을 받을 곳. 값을 모르게 되면(연결이 끊기면) null을 받는다 */
class SignalWatch<T : Any>(val source: SignalSource<T>, val onValue: (T?) -> Unit)

/**
 * 함께 풀리는 신호 묶음. [resolve]가 지켜볼 신호들을 준다: 지원 영역(capability)을 먼저 물어야 하는 신호는 여기서 묻는다.
 * 실패하면(예외) 이 묶음만 간격을 두고 다시 푼다. 빈 목록이면 이 차는 그 신호를 지원하지 않는 것이다 (다시 묻지 않는다).
 */
class SignalGroup(val name: String, val resolve: suspend () -> List<SignalWatch<*>>) {
    companion object {
        /** 물어볼 것 없이 바로 걸 수 있는 신호 하나 */
        fun <T : Any> of(source: SignalSource<T>, onValue: (T?) -> Unit): SignalGroup {
            val watch = SignalWatch(source, onValue)
            return SignalGroup(source.name) { listOf(watch) }
        }
    }
}

/** 신호를 주는 연결 하나 (AAOS Car 서비스, Pleos 차량 서비스, 위치 서비스) */
interface SignalLink {
    val name: String

    /** 연결한다. [again]이면 앞 연결은 죽은 것으로 보고 버린 뒤 새로 붙는다. 붙지 못하면 예외 */
    suspend fun connect(again: Boolean)

    /** 끊긴 것을 스스로 아는 연결은 끊길 때 돌아온다. 모르는 연결은 돌아오지 않는다 (신호가 죽었는지로 판단한다) */
    suspend fun awaitLost() {
        awaitCancellation()
    }

    val groups: List<SignalGroup>
}

/** 연결이 죽었다 (모두 걷고 다시 붙는다) */
class SignalLinkLost(message: String) : Exception(message)

/** 신호 하나의 구독이 죽었다 (그 신호만 다시 건다) */
class SignalStale(message: String) : Exception(message)

/** 신호 하나의 구독이 살아 있는지 (순수 규칙) */
object SignalHealth {
    /**
     * [quiet]: 지난 점검 뒤 리스너가 값을 하나도 주지 않았다. [last]: 마지막에 받은 값, [fresh]: 방금 읽은 값.
     * 조용했는데 값이 달라져 있으면 리스너가 변화를 놓친 것이다. 값이 그대로면 조용한 것이 정상이고,
     * 이벤트가 오고 있으면 읽은 값과 달라도 정상이다 (조향각처럼 계속 바뀌는 신호).
     */
    fun <T> stale(quiet: Boolean, last: T?, fresh: T?): Boolean = quiet && fresh != last
}

/** 연결이 살아 있는지 (순수 규칙) */
object LinkHealth {
    const val MIN_READS = 2

    /**
     * 점검 주기 동안 한 읽기(영역 조회 포함) [reads]번 가운데 [failed]번이 실패했고 이벤트가 [events]개 왔다.
     * 둘 이상 읽었는데 하나도 읽지 못했고 이벤트도 없으면 죽은 것으로 본다. 읽기 하나의 실패나 조용한 차를 죽었다고 하지 않는다
     * (공조 전원이 꺼져 있으면 설정 온도는 읽히지 않는다. 서 있는 차는 이벤트가 없다).
     */
    fun lost(reads: Int, failed: Int, events: Int): Boolean = reads >= MIN_READS && failed == reads && events == 0
}

/**
 * 연결 하나의 신호를 끊기지 않게 받는다. 규칙은 파일 머리말.
 * [run]은 취소될 때까지 돌아오지 않는다. 어느 디스패처에서 불러도 된다 (값 콜백은 부른 디스패처에서 온다).
 */
class SignalCollector(
    private val clock: ElapsedClock,
    private val log: CoachLog = CoachLog.NONE,
    val refreshMs: Long = REFRESH_MS,
    val checkMs: Long = CHECK_MS,
    val firstReadAfterMs: Long = FIRST_READ_AFTER_MS,
) {
    suspend fun run(link: SignalLink): Nothing {
        var again = false
        // 첫 점검도 못 넘긴 연결은 건강하지 않다: 간격을 늘려 다시 붙는다 (붙자마자 죽는 연결을 2초마다 다시 붙이지 않는다)
        Retry.forever(link.name, clock, log, Backoff(healthyMs = 3 * checkMs)) {
            link.connect(again)
            again = true
            log.i("${link.name}: connected")
            val live = CopyOnWriteArrayList<SignalWatch<*>>()
            try {
                collect(link, live)
            } finally {
                // 자식(구독)이 모두 끝난 뒤다: 늦게 온 값이 '모름'을 덮지 않는다
                live.forEach { clear(it) }
            }
        }
    }

    private suspend fun collect(link: SignalLink, live: MutableList<SignalWatch<*>>): Unit = coroutineScope {
        val health = Health()
        for (g in link.groups) launch { group(link, g, health, live) }
        launch {
            link.awaitLost()
            throw SignalLinkLost("connection lost")
        }
        while (true) {
            delay(checkMs)
            val w = health.take()
            if (LinkHealth.lost(w.reads, w.failed, w.events)) {
                throw SignalLinkLost("all ${w.reads} reads failed and no event in ${checkMs / 1000}s")
            }
        }
    }

    private suspend fun group(link: SignalLink, g: SignalGroup, health: Health, live: MutableList<SignalWatch<*>>): Unit = coroutineScope {
        val name = "${link.name}.${g.name}"
        val watches = Retry.untilSuccess(name, log) {
            try {
                g.resolve().also { health.read(ok = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                health.read(ok = false)
                throw e
            }
        }
        if (watches.isEmpty()) {
            log.i("$name: not supported on this car")
            return@coroutineScope
        }
        live.addAll(watches)
        for (w in watches) launch { watch("${link.name}.${w.source.name}", w, health) }
    }

    private suspend fun <T : Any> watch(name: String, w: SignalWatch<T>, health: Health): Nothing {
        var attempt = 0
        // 처음 다시 읽기(refreshMs)도 못 넘기고 죽는 구독은 간격을 늘려 다시 건다. 그동안에도 값은 다시 읽기로 따라간다
        Retry.forever(name, clock, log, Backoff(healthyMs = 2 * refreshMs + 1)) {
            val again = attempt++ > 0
            subscribe(w, health) { if (again) log.i("$name: receiving again") }
        }
    }

    /** 구독 한 번. 구독이 죽었다고 판단하면 예외로 끝난다. [onFirstEvent]: 이번 구독의 리스너가 처음 값을 줬다 */
    private suspend fun <T : Any> subscribe(w: SignalWatch<T>, health: Health, onFirstEvent: () -> Unit): Unit = coroutineScope {
        val seen = Seen<T>()
        launch {
            w.source.changes().collect { v ->
                if (seen.event(v)) onFirstEvent()
                health.event()
                w.onValue(v)
            }
            throw SignalStale("stream ended")
        }
        // 리스너는 걸자마자 지금 값을 준다(에뮬레이터의 Vehicle·Car 리스너). 잠깐 기다려도 주지 않는 신호만 한 번 읽는다
        // (예전에는 걸기 전에 늘 읽어서 시작할 때 SDK 호출이 신호 수만큼 더 나갔고 같은 값이 두 번 왔다)
        delay(firstReadAfterMs)
        if (seen.events == 0) read(w, health)?.let { first -> if (seen.first(first.value)) w.onValue(first.value) }
        var checked = seen.events
        while (true) {
            delay(refreshMs)
            val fresh = read(w, health) ?: continue
            if (seen.first(fresh.value)) {
                // 아직 아무 값도 받지 못했다 (처음 읽기는 실패했고 리스너는 조용했다): 읽은 값이 첫 값이다
                w.onValue(fresh.value)
                continue
            }
            val events = seen.events
            if (SignalHealth.stale(quiet = events == checked, last = seen.last, fresh = fresh.value)) {
                seen.set(fresh.value)
                w.onValue(fresh.value)
                throw SignalStale("value changed without an event")
            }
            if (w.source.reportReads) w.onValue(fresh.value)
            checked = events
        }
    }

    private class Read<T>(val value: T?)

    /** 한 번 읽는다. 읽지 못하면 null (연결 점검에 실패로 센다) */
    private suspend fun <T : Any> read(w: SignalWatch<T>, health: Health): Read<T>? = try {
        Read(w.source.read()).also { health.read(ok = true) }
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        health.read(ok = false)
        null
    } catch (e: Exception) {
        health.read(ok = false)
        null
    }

    private fun clear(w: SignalWatch<*>) {
        try {
            w.onValue(null)
        } catch (e: Exception) {
            log.w("${w.source.name}: clear failed: ${e.message}")
        }
    }

    /** 신호 하나가 이번 구독에서 받은 것. 구독 코루틴과 다시 읽기 루프가 함께 쓴다 */
    private class Seen<T> {
        var events = 0
            @Synchronized get
            private set
        private var known = false
        var last: T? = null
            @Synchronized get
            private set

        /** 리스너가 값을 줬다. 이번 구독의 첫 이벤트면 true */
        @Synchronized
        fun event(v: T?): Boolean {
            events++
            known = true
            last = v
            return events == 1
        }

        /** 읽은 값을 첫 값으로 삼는다. 아직 아무 값도 받지 못했을 때만이다 (늦게 읽은 옛 값이 리스너 값을 덮지 않게) */
        @Synchronized
        fun first(v: T?): Boolean {
            if (known) return false
            known = true
            last = v
            return true
        }

        @Synchronized
        fun set(v: T?) {
            known = true
            last = v
        }
    }

    /** 연결 점검 주기 하나의 집계 */
    private class Health {
        private val reads = AtomicInteger()
        private val failed = AtomicInteger()
        private val events = AtomicInteger()

        fun read(ok: Boolean) {
            reads.incrementAndGet()
            if (!ok) failed.incrementAndGet()
        }

        fun event() {
            events.incrementAndGet()
        }

        fun take() = Window(reads.getAndSet(0), failed.getAndSet(0), events.getAndSet(0))
    }

    private class Window(val reads: Int, val failed: Int, val events: Int)

    companion object {
        const val REFRESH_MS = 30_000L
        const val CHECK_MS = 30_000L
        const val FIRST_READ_AFTER_MS = 2_000L
    }
}
