package dev.shimpyo.engine

import dev.shimpyo.engine.EngineMath.NONE

/**
 * 기어·문·시간으로 정차를 판정한다. SPEC 4.2 마지막 줄.
 *
 * - 휴식: 기어 P가 [CoachConfig.restMinParkedMin]분 이상 → 피로를 0으로
 * - 화장실 정차: 기어 P가 [CoachConfig.restroomStopMinParkedMin]분 이상 + 그 사이 문 열림 → 화장실 시계를 0으로
 * - 식사 길이: 기어 P가 [CoachConfig.mealStopMinMin]분 이상. 여기서는 길이만 알린다. 식사 정차인지는 선 곳·목적을 아는
 *   엔진이 정한다 ([MealStopRule])
 *
 * 문턱을 넘는 순간(정차 중) 사건을 한 번 낸다. update가 드물게 와서 출발할 때야 문턱을 넘은 걸 알게 되어도
 * 출발 순간에 같은 사건을 낸다.
 *
 * 기어 UNKNOWN: 차속이 [MOVING_KPH]를 넘으면 주행, 아니면 직전 상태를 유지한다 (P로 단정하지 않는다).
 * 기어 P는 차속보다 믿는다: P에서는 바퀴가 잠겨 움직일 수 없으니 P + 차속은 차속 잡음이거나 기어 신호가 몇 초 늦은 것이다.
 * 늦은 기어 몇 초는 3분·15분 판정에 영향이 없고, 잡음 한 번이 15분 휴식을 처음부터 다시 세게 하면 안 된다 (감사 S8).
 * 다시 대기: P에서 나와 [REPARK_MAX_GAP_MS] 안에, [REPARK_MAX_KPH]를 넘지 않고 다시 P면 같은 정차로 이어 센다.
 * 휴게소에서 화장실 앞에 섰다가 식당·주유기 앞으로 옮겨 대는 일은 흔하다. 예전에는 14분 + 5분 정차를 휴식으로 보지 않았다(감사 S7).
 * 단 그 정차를 시작한 뒤 P 밖에서 움직인 거리가 모두 합쳐 [REPARK_MAX_MOVE_M] 이하일 때만이다(차속을 적분한다). 휴게소는
 * 수백 미터 크기라 그 안에서 옮겨 대는 것은 이 안에 들고, 꽉 막힌 정체에서 P로 섰다가 기어가기를 되풀이하는 것은 넘는다.
 * 예전에는 P 2분·시속 15km로 2분을 다섯 번 되풀이하면 한 정차로 이어져 '15분 휴식'이 되고, 그 사이 문이 한 번 열렸으면 화장실
 * 시계까지 돌아갔다(닫힘 검증 2026-11-12). 차속을 모르는 동안은 다시 대기로 볼 수 있는 가장 빠른 속도로 움직였다고 본다(거리의 위 한계):
 * 같은 정차라고 확인할 수 있을 때만 이어 센다.
 *
 * 타는 중 (2026-11-13 감사): 여정을 시작하며 선 정차([update]의 boarding)에서 연 문은 타고 내리는 것이라 화장실 정차로 세지 않는다.
 * 예전에는 시동 뒤 아이가 타며 문을 열고 3분 서 있으면 집 앞이 '화장실 정차'가 되어 게이지가 다시 찼고, 가정한 출발 전 시각부터
 * 잰 간격을 배웠다. 출발 전 한마디에 '응' 한 뒤([intendRestroom])에 연 문은 화장실에 다녀온 것이다.
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

        /** 식사 정차로 볼 만큼 길어진 시각. 없으면 NONE */
        var mealLengthAt: Long = NONE
            internal set

        internal fun clear() {
            restroomStopAt = NONE
            restAt = NONE
            mealLengthAt = NONE
        }
    }

    var parked: Boolean = false
        private set

    private var parkStart = NONE
    private var firstDoorAt = NONE
    /** 이번 정차가 타는 중의 정차인지 (정차를 시작할 때 정한다. 다시 대기는 같은 정차라 그대로) */
    private var boarding = false
    /** 타는 중의 정차에서 화장실에 다녀오겠다고 한 때. 그 뒤에 연 문만 센다 */
    private var restroomIntentAt = NONE
    private var restroomFired = false
    private var restFired = false
    private var mealFired = false
    private var leftAt = NONE // 마지막으로 P에서 나온 시각
    private var leftMaxKph = 0f // 그 뒤 잰 가장 높은 차속
    private var movedM = 0.0 // 지금(또는 마지막) 정차를 시작한 뒤 P 밖에서 움직인 거리 (다시 대기를 거쳐도 이어 더한다)
    private var lastMono = NONE // 지난 update의 시각과 차속 (움직인 거리 적분용)
    private var lastKph: Float? = null
    private val events = Events()

    private val restroomMs get() = config.restroomStopMinParkedMin * 60_000L
    private val restMs get() = config.restMinParkedMin * 60_000L
    private val mealMs get() = config.mealStopMinMin * 60_000L

    /** [boarding]: 지금 새 정차가 시작되면 그것이 타는 중의 정차인지 (엔진: 이번 여정에서 아직 움직이지 않았다) */
    fun update(mono: Long, gear: Gear, speedKph: Float?, doorOpened: Boolean, boarding: Boolean = false): Events {
        events.clear()
        val nowParked = when (gear) {
            Gear.PARK -> true
            Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL -> false
            Gear.UNKNOWN -> if (speedKph != null && speedKph > MOVING_KPH) false else parked
        }
        // P에서 나와 있던 지난 update부터 지금까지 움직인 거리: 지난 update의 차속으로 달렸다고 본다 (모르면 위 한계)
        if (!parked && leftAt != NONE && lastMono != NONE && mono > lastMono) {
            movedM += (lastKph ?: REPARK_MAX_KPH) / 3.6 * (mono - lastMono) / 1000.0
        }
        lastMono = mono
        lastKph = speedKph
        if (parked && !nowParked) {
            leftAt = mono
            leftMaxKph = 0f
        }
        if (!nowParked && speedKph != null && speedKph > leftMaxKph) leftMaxKph = speedKph
        if (!parked && nowParked) {
            val repark = leftAt != NONE && mono - leftAt <= REPARK_MAX_GAP_MS && leftMaxKph <= REPARK_MAX_KPH &&
                movedM <= REPARK_MAX_MOVE_M
            if (!repark) {
                parkStart = mono
                firstDoorAt = NONE
                this.boarding = boarding
                restroomIntentAt = NONE
                anyDoorThisSession = false
                restroomFired = false
                restFired = false
                mealFired = false
                movedM = 0.0
            }
            leftAt = NONE
        }
        if ((parked || nowParked) && doorOpened) anyDoorThisSession = true
        // 정차 중이었거나 막 정차했으면 이번 구간의 문 열림으로 센다 (P에서 D로 바뀌는 update의 문 열림도 포함).
        // 타는 중의 정차는 화장실에 다녀오겠다고 한 뒤의 문만 센다
        if ((parked || nowParked) && doorOpened && firstDoorAt == NONE && (!this.boarding || (restroomIntentAt != NONE && mono >= restroomIntentAt))) {
            firstDoorAt = mono
        }
        if (parked || nowParked) evaluate(mono)
        parked = nowParked
        return events
    }

    /** 지금(또는 마지막) 정차가 시작된 시각. 다시 대기는 같은 정차라 바뀌지 않는다. 선 적이 없으면 NONE */
    val sessionStart: Long get() = parkStart

    /** 지금(또는 마지막) 정차가 타는 중의 정차인지. 출발하는 update에 늦게 낸 화장실 사건에도 그 정차의 값이다 */
    val lastSessionBoarding: Boolean get() = boarding

    /** 지금 정차에서 (센) 문이 열렸는지. 타는 중의 정차면 화장실에 다녀오겠다고 한 뒤의 문만 센다 */
    val doorSeen: Boolean get() = parked && firstDoorAt != NONE

    /** 지금 정차에서 문이 열린 적이 있는지 (타는 중의 문도). 서 있는 동안 내린 사람이 있었는지 본다 */
    var anyDoorThisSession: Boolean = false
        private set

    /** 출발 전 한마디에 '응': 지금 정차에서 이 뒤에 연 문은 화장실에 다녀온 것이다 */
    fun intendRestroom(mono: Long) {
        if (parked && restroomIntentAt == NONE) restroomIntentAt = mono
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
        if (!mealFired && parkedMs >= mealMs) {
            mealFired = true
            events.mealLengthAt = parkStart + mealMs
        }
    }

    companion object {
        /** 기어를 모를 때 이 속도를 넘으면 주행 중으로 본다 (km/h) */
        const val MOVING_KPH = 3f
        /** 다시 대기로 보는 최대 공백 (ms): 휴게소 안에서 옮겨 대는 데 충분하고, 휴게소를 떠났다 오기에는 짧다 */
        const val REPARK_MAX_GAP_MS = 3 * 60_000L
        /** 다시 대기로 보는 최고 차속 (km/h): 휴게소 주차장 안 속도. 본선 합류로 가속하면 넘는다 */
        const val REPARK_MAX_KPH = 30f
        /**
         * 다시 대기로 보는 최대 이동 거리 (m): 한 정차를 시작한 뒤 P 밖에서 움직인 거리의 합. 휴게소는 수백 미터 크기다.
         * 정체 속 가다 서다(시속 15km로 2분 = 500m)는 넘는다. 근거: 닫힘 검증 2026-11-12에서 정한 값
         */
        const val REPARK_MAX_MOVE_M = 300.0
    }
}
