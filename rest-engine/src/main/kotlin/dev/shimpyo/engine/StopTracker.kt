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
 * 기어 P는 차속보다 믿는다: P에서는 바퀴가 잠겨 움직일 수 없으니 P + 차속은 차속 잡음이거나 기어 신호가 몇 초 늦은 것이다.
 * 늦은 기어 몇 초는 3분·15분 판정에 영향이 없고, 잡음 한 번이 15분 휴식을 처음부터 다시 세게 하면 안 된다 (감사 S8).
 * 다시 대기: P에서 나와 [REPARK_MAX_GAP_MS] 안에, [REPARK_MAX_KPH]를 넘지 않고 다시 P면 같은 정차로 이어 센다.
 * 휴게소에서 화장실 앞에 섰다가 식당·주유기 앞으로 옮겨 대는 일은 흔하다. 예전에는 14분 + 5분 정차를 휴식으로 보지 않았다(감사 S7).
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
    private var leftAt = NONE // 마지막으로 P에서 나온 시각
    private var leftMaxKph = 0f // 그 뒤 잰 가장 높은 차속
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
        if (parked && !nowParked) {
            leftAt = mono
            leftMaxKph = 0f
        }
        if (!nowParked && speedKph != null && speedKph > leftMaxKph) leftMaxKph = speedKph
        if (!parked && nowParked) {
            val repark = leftAt != NONE && mono - leftAt <= REPARK_MAX_GAP_MS && leftMaxKph <= REPARK_MAX_KPH
            if (!repark) {
                parkStart = mono
                firstDoorAt = NONE
                restroomFired = false
                restFired = false
            }
            leftAt = NONE
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
        /** 다시 대기로 보는 최대 공백 (ms): 휴게소 안에서 옮겨 대는 데 충분하고, 휴게소를 떠났다 오기에는 짧다 */
        const val REPARK_MAX_GAP_MS = 3 * 60_000L
        /** 다시 대기로 보는 최고 차속 (km/h): 휴게소 주차장 안 속도. 본선 합류로 가속하면 넘는다 */
        const val REPARK_MAX_KPH = 30f
    }
}
