package dev.shimpyo.coach.fakes

import dev.shimpyo.coach.CoachLog
import dev.shimpyo.coach.ElapsedClock
import dev.shimpyo.coach.Fix
import dev.shimpyo.coach.NaviChange
import dev.shimpyo.coach.NaviCongestion
import dev.shimpyo.coach.NaviDestination
import dev.shimpyo.coach.NaviMode
import dev.shimpyo.coach.NaviPort
import dev.shimpyo.coach.NaviRouteStatus
import dev.shimpyo.coach.TbtItem
import dev.shimpyo.coach.TripEstimate
import dev.shimpyo.coach.WaypointResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 대본대로 답하는 가짜 내비. 답은 람다라서 차 위치(주행 거리)·시각에 따라 바꿀 수 있다.
 * 호출마다 이름과 시각을 남기고, 메서드별로 실패(THROW)·무응답(HANG)·지연을 줄 수 있다.
 * 메서드 이름: connect, routeStatus, destination, tbt, currentLocation, tripEstimate, addWaypoint, startRoute, showPlaceSearch
 */
class FakeNaviPort(private val clock: ElapsedClock) : NaviPort {
    enum class Fault { NONE, THROW, HANG }

    data class Call(val name: String, val atMs: Long)

    val calls = mutableListOf<Call>()
    fun count(name: String): Int = calls.count { it.name == name }
    fun times(name: String): List<Long> = calls.filter { it.name == name }.map { it.atMs }

    var mode: () -> NaviMode = { NaviMode.SAFE_GUIDING }
    var congestion: () -> List<NaviCongestion> = { emptyList() }
    var destination: () -> NaviDestination = { dest(100_000.0) }
    var tbt: () -> List<TbtItem> = { emptyList() }
    var location: () -> Fix = { Fix(37.40, 127.10, 90f) }
    var trip: () -> TripEstimate = { TripEstimate(10_000.0, 600.0) }

    /** 메서드별 실패. 없으면 NONE */
    val faults = mutableMapOf<String, Fault>()
    /** 메서드별 응답 지연 (가상 시간) */
    val latencyMs = mutableMapOf<String, Long>()
    /** 처음 이만큼의 connect는 실패한다 */
    var connectFailures = 0

    /** 내비가 먼저 보내는 변화. 구독자가 없을 때 보낸 것은 버려진다(실제 SharedFlow와 같다) */
    override val changes = MutableSharedFlow<NaviChange>(extraBufferCapacity = 16)

    /** 지금 도로의 제한속도 (실제 내비는 매초 보낸다). 구독자가 없을 때 보낸 것은 버려진다 */
    override val roadSpeedLimits = MutableSharedFlow<Int>(extraBufferCapacity = 16)

    /** 답은 물은 순간의 상태로 만든다 (지연 중에 대본이 바뀌어도 옛 답이 온다: 실제 내비와 같다) */
    private suspend fun <T> answer(name: String, value: () -> T): T {
        calls += Call(name, clock.elapsedMs())
        val fault = faults[name] ?: Fault.NONE
        val v = if (fault == Fault.NONE) value() else null
        latencyMs[name]?.let { delay(it) }
        when (fault) {
            Fault.THROW -> throw IllegalStateException("$name failed (fake)")
            Fault.HANG -> awaitCancellation()
            Fault.NONE -> Unit
        }
        @Suppress("UNCHECKED_CAST")
        return v as T
    }

    override suspend fun connect() = answer("connect") {
        if (connectFailures > 0) {
            connectFailures--
            throw IllegalStateException("connect failed (fake)")
        }
    }

    override suspend fun routeStatus(): NaviRouteStatus = answer("routeStatus") { NaviRouteStatus(mode(), congestion()) }
    override suspend fun destination(): NaviDestination = answer("destination") { destination.invoke() }
    override suspend fun tbt(): List<TbtItem> = answer("tbt") { tbt.invoke() }
    override suspend fun currentLocation(): Fix = answer("currentLocation") { location() }
    override suspend fun tripEstimate(lat: Double, lon: Double, name: String, timeoutMs: Long): TripEstimate = answer("tripEstimate") { trip() }
    override suspend fun addWaypoint(lat: Double, lon: Double, name: String): WaypointResult = answer("addWaypoint") { WaypointResult.ADDED }

    /** 실제 내비처럼 길안내를 시작하면 경로가 있는 모드가 된다 */
    override suspend fun startRoute(lat: Double, lon: Double, name: String): Boolean = answer("startRoute") {
        mode = { NaviMode.ROUTE_GUIDING }
        true
    }

    override suspend fun showPlaceSearch(keyword: String): Boolean = answer("showPlaceSearch") { true }

    companion object {
        /** 서울역 근처 목적지. 남은 거리만 바꿔 쓴다 */
        fun dest(remainM: Double, lat: Double = 37.5547, lon: Double = 126.9706, name: String = "서울역", timeS: Double = remainM / 25.0) =
            NaviDestination(lat, lon, name, remainM, timeS)
    }
}

/** 남긴 로그를 테스트가 읽는다 */
class RecordingLog : CoachLog {
    val lines = mutableListOf<String>()
    override fun i(message: String) {
        lines += "I $message"
    }

    override fun w(message: String) {
        lines += "W $message"
    }
}

/** 테스트가 직접 움직이는 실제 시간 */
class ManualClock(var nowMs: Long = 0L) : ElapsedClock {
    override fun elapsedMs(): Long = nowMs
    fun advance(ms: Long) {
        nowMs += ms
    }
}
