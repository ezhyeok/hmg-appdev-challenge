package dev.shimpyo.coach

import dev.shimpyo.engine.DrivingSample
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.TurnSignal

/**
 * 차량 신호의 최신값과, 엔진에 넘길 표본 버퍼.
 * 수집 코루틴들이 값을 쓰고, 코치 루프가 1초마다 [drain]으로 가져간다.
 */
class SignalHub {
    @Volatile var gear: Gear = Gear.UNKNOWN
    @Volatile var speedKph: Float? = null
    @Volatile var steeringDeg: Float? = null
    @Volatile var brake: Boolean? = null
    @Volatile var turnSignal: TurnSignal? = null
    @Volatile var cruiseActive: Boolean? = null
    @Volatile var hvacOn: Boolean? = null
    @Volatile var recirculation: Boolean? = null
    @Volatile var setTempC: Float? = null
    @Volatile var drivingRestricted: Boolean = false

    @Volatile var lat: Double? = null
    @Volatile var lon: Double? = null
    /** 진행 방향(도, 북=0). 위치 SDK 또는 내비에서 */
    @Volatile var heading: Float? = null
    @Volatile var locationAt: Long = 0

    private val samples = ArrayList<DrivingSample>(256)
    private var doorOpened = false

    /** 조향 이벤트처럼 1초보다 촘촘한 신호를 표본으로 쌓는다 */
    @Synchronized
    fun addSample(sample: DrivingSample) {
        if (samples.size < MAX_BUFFER) samples.add(sample)
    }

    @Synchronized
    fun markDoorOpened() {
        doorOpened = true
    }

    /** 지금 시각의 최신값 표본 */
    fun snapshot(now: Long) = DrivingSample(
        t = now,
        speedKph = speedKph,
        steeringDeg = steeringDeg,
        brake = brake,
        turnSignal = turnSignal,
        cruiseActive = cruiseActive,
    )

    /** 쌓인 표본과 문 열림 여부를 가져가고 비운다 */
    @Synchronized
    fun drain(now: Long): Pair<List<DrivingSample>, Boolean> {
        val out = ArrayList<DrivingSample>(samples.size + 1)
        out.addAll(samples)
        out.add(snapshot(now))
        samples.clear()
        val door = doorOpened
        doorOpened = false
        return out to door
    }

    private companion object {
        const val MAX_BUFFER = 2_000
    }
}
