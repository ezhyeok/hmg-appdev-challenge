package dev.shimpyo.coach

import dev.shimpyo.engine.DrivingSample
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.TurnSignal

/**
 * 차량 신호의 최신값과, 엔진에 넘길 표본 버퍼.
 * 수집 코루틴들이 값을 쓰고, 코치 루프가 1초마다 [drain]으로 가져간다.
 *
 * 표본의 시각은 여기서만 정한다 (리뷰 R-F6). 수집 쪽은 값만 넣고([addSteering]), 허브가 받은 때를 경과 시계로 적어 두었다가
 * [drain]이 엔진 시각(그 틱의 세계 시각)으로 바꾼다: 표본 시각 = 틱 시각 − (지금 경과 − 받은 때 경과).
 * 예전에는 수집 쪽이 실제 시각으로 찍어서, 시연(가상 시각)에서는 한 틱의 표본에 두 시계가 섞였다. 엔진의 운전 패턴 분석이
 * 표본마다 시계 역행·긴 공백으로 보고 창을 버려 기준선이 서지 않았다 (SignalHubTest, ReviewSampleClockTest).
 *
 * [elapsed]는 코어와 같은 경과 시계를 넘긴다. 기본값(JVM 단조 시계)은 조향 표본을 넣지 않는 조립용이다.
 */
class SignalHub(private val elapsed: ElapsedClock = ElapsedClock { System.nanoTime() / 1_000_000 }) {
    @Volatile var gear: Gear = Gear.UNKNOWN
    @Volatile var speedKph: Float? = null
    @Volatile var steeringDeg: Float? = null
        private set
    @Volatile var brake: Boolean? = null
    @Volatile var turnSignal: TurnSignal? = null
    @Volatile var cruiseActive: Boolean? = null
    @Volatile var hvacOn: Boolean? = null
    @Volatile var recirculation: Boolean? = null
    @Volatile var setTempC: Float? = null

    /** 받은 조향 이벤트 하나. 시각은 경과 시계로만 들고 있는다 */
    private class Steer(val atElapsedMs: Long, val speedKph: Float?, val deg: Float)

    private val steers = ArrayList<Steer>(256)
    private var doorOpened = false
    /** 지난 [drain]의 틱 시각. 표본이 지난 틱보다 앞서지 않게 하는 데 쓴다 */
    private var lastDrainNow: Long? = null

    /**
     * 조향각 이벤트. 1초보다 촘촘하게 올 수 있어 표본으로 쌓는다 (값을 못 읽었으면 null: 최신값만 지운다).
     * 시각을 받지 않는다: 허브가 받은 때를 적는다.
     */
    @Synchronized
    fun addSteering(deg: Float?) {
        steeringDeg = deg
        if (deg != null && steers.size < MAX_BUFFER) steers.add(Steer(elapsed.elapsedMs(), speedKph, deg))
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

    /**
     * 쌓인 표본과 문 열림 여부를 가져가고 비운다. [now]는 이 틱의 엔진 시각(세계 시각)이고, 돌려주는 표본은 모두 그 시계다:
     * 받은 순서대로, 지난 틱보다 뒤, [now] 이하. 마지막은 [now]의 최신값 표본이다.
     * 지난 틱보다 뒤로 맞추는 이유: 세계 시각이 경과 시계보다 느리게 간 틱(시계 보정)에서 표본이 지난 틱 앞으로 가면 엔진이 버린다.
     * 세계 시각 자체가 뒤로 갔으면(시연 전환) 맞출 기준이 없으므로 그대로 둔다.
     */
    @Synchronized
    fun drain(now: Long): Pair<List<DrivingSample>, Boolean> {
        val el = elapsed.elapsedMs()
        val floor = lastDrainNow?.takeIf { it < now }?.plus(1)
        val out = ArrayList<DrivingSample>(steers.size + 1)
        for (s in steers) {
            val age = (el - s.atElapsedMs).coerceAtLeast(0L)
            val t = (now - age).let { if (floor != null) it.coerceAtLeast(floor) else it }
            out.add(DrivingSample(t = t, speedKph = s.speedKph, steeringDeg = s.deg))
        }
        out.add(snapshot(now))
        steers.clear()
        lastDrainNow = now
        val door = doorOpened
        doorOpened = false
        return out to door
    }

    private companion object {
        const val MAX_BUFFER = 2_000
    }
}
