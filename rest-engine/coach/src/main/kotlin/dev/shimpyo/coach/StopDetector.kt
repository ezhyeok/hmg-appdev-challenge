package dev.shimpyo.coach

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.Gear

/**
 * 지금 정차 중인 상태. 시각은 코치 시각(ms).
 * 앱이 정차 중에 다시 시작돼도 정차를 이어 가도록 TripSession(world)이 heartbeat에 저장하고 [StopDetector.resume]으로 되돌린다.
 * 시연 세계의 값은 저장하지 않는다(가상 시각이다).
 */
data class ParkState(
    /** P로 세운 시각 */
    val sinceMs: Long,
    /** 정차 중 처음 문이 열린 시각. 안 열렸으면 null */
    val doorAtMs: Long?,
)

/** 끝난 정차 한 건 (P를 벗어날 때 낸다) */
data class ParkedStop(val start: Long, val end: Long, val doorOpened: Boolean)

/**
 * 기어·문·시간으로 정차를 판정하는 순수 상태 기계. 코치 틱마다 한 번 [onTick]을 부른다 (코치 디스패처 한 곳에서만).
 * P가 [minStopMs] 이상 이어진 뒤 P를 벗어나면 정차 한 건을 낸다. 그 사이 문이 열렸으면 화장실 정차다.
 *
 * 규칙은 엔진 StopTracker와 같다. 다르면 기록의 '화장실' 표시와 엔진이 배운 화장실 간격이 어긋난다(구조 리뷰 §2.3e).
 * - 기어 UNKNOWN: 차속이 [MOVING_KPH]를 넘으면 주행, 아니면 직전 상태를 유지한다 (Car 서비스가 잠깐 끊겨도 정차를 잃지 않는다)
 * - P에서 벗어나는 틱에 함께 온 문 열림도 그 정차의 문 열림이다 (1초 틱 사이에 문을 닫고 출발할 수 있다)
 * - 시계가 바뀌면(시연 켜기·끄기) [rebase]로 정차 시작을 새 시계의 지금으로 옮긴다. 두 시계의 시각을 빼서 4시간 정차를 만들지 않는다
 */
class StopDetector(private val minStopMs: Long = CoachConfig().restroomStopMinParkedMin * 60_000L) {
    private var since: Long? = null
    private var doorAt: Long? = null

    val parked: Boolean get() = since != null

    /** 정차 중이면 그 상태, 아니면 null */
    val parkState: ParkState? get() = since?.let { ParkState(it, doorAt) }

    /** [doorOpened]: 지난 틱 이후 문이 열린 적이 있는지 */
    fun onTick(t: Long, gear: Gear, speedKph: Float?, doorOpened: Boolean): ParkedStop? {
        val wasParked = since != null
        val nowParked = when (gear) {
            Gear.PARK -> true
            Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL -> false
            Gear.UNKNOWN -> if (speedKph != null && speedKph > MOVING_KPH) false else wasParked
        }
        if (nowParked && !wasParked) {
            since = t
            doorAt = null
        }
        // 정차 중이었거나 막 세웠으면 이번 정차의 문 열림이다 (P에서 D로 바뀌는 틱 포함)
        if ((wasParked || nowParked) && doorOpened && doorAt == null) doorAt = t
        if (nowParked) return null
        val start = since ?: return null
        val door = doorAt != null
        since = null
        doorAt = null
        // 시계가 거꾸로 갔으면(t < start) 이 조건에 걸린다
        if (t - start < minStopMs) return null
        return ParkedStop(start, t, door)
    }

    /**
     * 정차 중 이미 화장실 정차가 됐으면 그 시각 (엔진 StopTracker와 같은 식: max(첫 문 열림, 세운 시각 + 3분)), 아니면 null.
     * 출발 전에 앱이 다시 시작돼도 TripSession이 마지막 화장실 시각을 잃지 않게 쓴다 (서비스 감사 D12)
     */
    fun restroomReachedAt(now: Long): Long? {
        val s = since ?: return null
        val door = doorAt ?: return null
        if (now - s < minStopMs) return null
        return maxOf(door, s + minStopMs)
    }

    /** 시계가 바뀌었다 (시연 켜기·끄기). 정차 중이면 새 시계의 [now]부터 다시 센다. 문 열림도 새로 본다 */
    fun rebase(now: Long) {
        if (since == null) return
        since = now
        doorAt = null
    }

    /**
     * 앱 재시작: 저장해 둔 정차를 이어 간다. 다음 틱이 P가 아니면 그 틱에 정차가 끝난다.
     * 미래 시각(시연 시각이 저장된 경우 등)은 버린다. 이미 정차 중이면 더 이른 시작과 문 열림을 남긴다.
     */
    fun resume(state: ParkState, now: Long) {
        if (state.sinceMs > now) return
        val door = state.doorAtMs?.takeIf { it in state.sinceMs..now }
        since = minOf(since ?: state.sinceMs, state.sinceMs)
        doorAt = listOfNotNull(doorAt, door).minOrNull()
    }

    companion object {
        /** 기어를 모를 때 이 속도를 넘으면 주행 중으로 본다 (km/h, 엔진 StopTracker.MOVING_KPH와 같다) */
        const val MOVING_KPH = 3f
    }
}
