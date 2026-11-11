package dev.shimpyo.engine

import dev.shimpyo.engine.EngineMath.NONE

/**
 * 기어·문·시간으로 정차를 판정한다. SPEC 4.2 마지막 줄.
 *
 * - 휴식: 기어 P가 [CoachConfig.restMinParkedMin]분 이상 → 피로를 0으로
 * - 화장실 정차: 기어 P가 [CoachConfig.restroomStopMinParkedMin]분 이상 + 그 사이 문 열림 → 화장실 시계를 0으로
 *
 * 문턱을 넘는 순간(정차 중) 사건을 한 번 낸다. update가 드물게 와서 출발할 때야 문턱을 넘은 걸 알게 되어도
 * 출발 순간에 같은 사건을 낸다.
 *
 * 기어 UNKNOWN: 차속이 [MOVING_KPH]를 넘으면 주행, 아니면 직전 상태를 유지한다 (P로 단정하지 않는다).
 */
internal class StopTracker(private val config: CoachConfig) {
    /** 한 update에서 생긴 사건. 할당을 줄이려고 같은 객체를 재사용한다 */
    class Events {
        /** 화장실 정차로 볼 시각 (엔진 시각). 없으면 NONE */
        var restroomStopAt: Long = NONE
            internal set

        /** 휴식으로 볼 시각. 없으면 NONE */
        var restAt: Long = NONE
            internal set

        internal fun clear() {
            restroomStopAt = NONE
            restAt = NONE
        }
    }

    var parked: Boolean = false
        private set

    private var parkStart = NONE
    private var firstDoorAt = NONE
    private var restroomFired = false
    private var restFired = false
    private val events = Events()

    private val restroomMs get() = config.restroomStopMinParkedMin * 60_000L
    private val restMs get() = config.restMinParkedMin * 60_000L

    fun update(mono: Long, gear: Gear, speedKph: Float?, doorOpened: Boolean): Events {
        events.clear()
        val nowParked = when (gear) {
            Gear.PARK -> true
            Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL -> false
            Gear.UNKNOWN -> if (speedKph != null && speedKph > MOVING_KPH) false else parked
        }
        if (!parked && nowParked) {
            parkStart = mono
            firstDoorAt = NONE
            restroomFired = false
            restFired = false
        }
        // 정차 중이었거나 막 정차했으면 이번 구간의 문 열림으로 센다 (P에서 D로 바뀌는 update의 문 열림도 포함)
        if ((parked || nowParked) && doorOpened && firstDoorAt == NONE) firstDoorAt = mono
        if (parked || nowParked) evaluate(mono)
        parked = nowParked
        return events
    }

    /** 지금 정차한 지 몇 ms인지. 정차 중이 아니면 0 */
    fun parkedForMs(mono: Long): Long = if (parked && parkStart != NONE) maxOf(0L, mono - parkStart) else 0L

    private fun evaluate(mono: Long) {
        val parkedMs = mono - parkStart
        if (!restroomFired && firstDoorAt != NONE && parkedMs >= restroomMs) {
            restroomFired = true
            // 문이 열린 시각과 문턱 시각 중 늦은 쪽. 정차가 끝난 뒤 늦게 알아도 실제 시각에 가깝게 남긴다
            events.restroomStopAt = maxOf(firstDoorAt, parkStart + restroomMs)
        }
        if (!restFired && parkedMs >= restMs) {
            restFired = true
            events.restAt = parkStart + restMs
        }
    }

    companion object {
        /** 기어를 모를 때 이 속도를 넘으면 주행 중으로 본다 (km/h) */
        const val MOVING_KPH = 3f
    }
}
