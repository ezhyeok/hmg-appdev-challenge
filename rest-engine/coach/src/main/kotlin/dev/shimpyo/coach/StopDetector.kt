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
 */
class StopDetector(private val minStopMs: Long = CoachConfig().restroomStopMinParkedMin * 60_000L) {
    private var since: Long? = null
    private var doorAt: Long? = null

    val parked: Boolean get() = since != null

    /** 정차 중이면 그 상태, 아니면 null */
    val parkState: ParkState? get() = since?.let { ParkState(it, doorAt) }

    /** [doorOpened]: 지난 틱 이후 문이 열린 적이 있는지 */
    fun onTick(t: Long, gear: Gear, speedKph: Float?, doorOpened: Boolean): ParkedStop? {
        if (gear == Gear.PARK) {
            if (since == null) {
                since = t
                doorAt = null
            }
            if (doorOpened && doorAt == null) doorAt = t
            return null
        }
        val start = since ?: return null
        since = null
        if (gear == Gear.UNKNOWN) return null
        if (t - start < minStopMs) return null
        return ParkedStop(start, t, doorAt != null)
    }
}
