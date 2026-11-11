package dev.shimpyo.coach

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.Gear

/** 끝난 정차 한 건. start·end는 코치 시각, end는 P에서 나온 때 */
data class ParkedStop(val start: Long, val end: Long, val doorOpened: Boolean)

/**
 * 기어·문·시간으로 정차를 판정하는 순수 상태 기계. 코치 틱마다 한 번 [onTick]을 부른다 (코치 디스패처 한 곳에서만).
 * P가 [minStopMs] 이상 이어진 정차가 끝나면 한 건을 낸다. 그 사이 문이 열렸으면 화장실 정차다.
 * 지금 정차는 [parkState] (TripSession.kt의 [ParkState], world 담당과 같은 타입)로 내보내 맥박에 저장하고, 재시작·세계 전환 때 [restore]한다.
 *
 * 규칙은 엔진 StopTracker와 같다. 다르면 기록의 '화장실' 표시와 엔진이 배운 화장실 간격이 어긋난다(구조 리뷰 §2.3e).
 * - 기어 P는 차속보다 믿는다 (P에서는 바퀴가 잠긴다. P + 차속은 잡음이다)
 * - 기어 UNKNOWN: 차속이 [MOVING_KPH]를 넘으면 주행, 아니면 직전 상태를 유지한다 (Car 서비스가 잠깐 끊겨도 정차를 잃지 않는다)
 * - P에서 벗어나는 틱에 함께 온 문 열림도 그 정차의 문 열림이다 (1초 틱 사이에 문을 닫고 출발할 수 있다)
 * - 다시 대기: P에서 나와 [REPARK_MAX_GAP_MS] 안에 [REPARK_MAX_KPH]를 넘지 않고 다시 P면 같은 정차다 (휴게소 안에서 옮겨 대기).
 *   단 그 정차를 시작한 뒤 P 밖에서 움직인 거리가 모두 합쳐 [REPARK_MAX_MOVE_M] 이하일 때만이다: 정체 속에서 P로 섰다가 기어가기를
 *   되풀이하는 것은 한 정차가 아니다 (엔진 닫힘 검증 E4). 거리는 차속을 틱마다 더해 잰다(지난 틱의 차속으로 이번 틱까지 갔다고 본다).
 *   차속을 모르는 동안은 [REPARK_MAX_KPH]로 갔다고 본다(같은 정차라고 확인할 수 있을 때만 이어 센다). 다시 P가 된 틱의 차속은 잡음이다.
 *   그래서 정차는 P에서 나온 순간이 아니라 그 창이 끝날 때(시간이 지나거나, 빨리 달리거나, 300m를 넘을 때) 확정해서 낸다. 끝 시각은
 *   P에서 나온 때다
 * - 선 시간은 틱 사이 시간을 더해 잰다(다시 대기의 빈틈 포함, 엔진과 같다). 시계가 거꾸로 간 걸음은 0으로 센다(엔진 EngineClock과 같다).
 *   그래서 정차 중 시각이 뒤로 맞춰져도(NTP) 정차를 잃지 않는다. 기록의 끝은 그때 시계, 시작은 끝 - 선 시간이다
 * - 세계가 바뀌면(시연 켜기·끄기) [restore]로 그 세계의 정차를 끼운다. 두 시계의 시각을 빼서 4시간 정차를 만들지 않는다
 * - 되돌린 정차는 "마지막으로 본 때 끝났다, 차가 지금도 P가 아니라면"이다. 그 판정은 기어를 알 때까지 미룬다([restorePending]):
 *   서비스가 켜진 직후 첫 틱은 Car 연결보다 먼저 올 수 있어서, 첫 틱의 UNKNOWN을 '아직 서 있다'로 읽으면 같은 상황이 신호 도착
 *   순서에 따라 10분 정차 / 40분 정차로 갈렸다 (서비스 닫힘 검증 N2). 판정 결과는 여정·엔진도 함께 쓴다 ([takeRestoredEnd], N3).
 *   떠난 것으로 판정해도 다시 대기 창은 마지막으로 본 때부터 열려 있다: 보지 못한 사이는 차속을 모르는 것으로 센다
 *
 * 엔진과 같은 순서에 같은 판정을 내는지는 StopRulesCrossCheckTest가 지킨다.
 */
class StopDetector(private val minStopMs: Long = CoachConfig().restroomStopMinParkedMin * 60_000L) {
    /** 지금 P인 정차 */
    private var since: Long? = null
    private var doorAt: Long? = null
    /** 이번 정차에서 선 시간 (틱 사이 시간의 합) */
    private var parkedMs = 0L

    /** 이번 정차를 시작한 뒤 P 밖에서 움직인 거리 (m). 다시 대기를 거쳐도 이어 더한다 */
    private var movedM = 0.0

    /** P에서 나와 다시 대기를 기다리는 정차. [movedM]: 그 정차를 시작한 뒤 움직인 거리 (앞선 다시 대기의 것 포함) */
    private class Pending(val since: Long, val doorAt: Long?, val parkedMs: Long, val leftAt: Long, var movedM: Double) {
        var gapMs = 0L
        var maxKph = 0f

        /** 아직 다시 대기로 이어질 수 있는지 */
        val open: Boolean get() = gapMs <= REPARK_MAX_GAP_MS && maxKph <= REPARK_MAX_KPH && movedM <= REPARK_MAX_MOVE_M

        /** P 밖에서 [dtMs] 동안 [kph]로 갔다 (모르면 다시 대기로 볼 수 있는 가장 빠른 속도: 거리의 위 한계) */
        fun moved(dtMs: Long, kph: Float?) {
            gapMs += dtMs
            movedM += (kph ?: REPARK_MAX_KPH) / 3.6 * dtMs / 1000.0
        }
    }
    private var pending: Pending? = null
    /** 지난 틱의 차속 (움직인 거리를 더하는 데 쓴다) */
    private var lastKph: Float? = null

    /** 마지막 틱 시각 */
    private var lastT = 0L
    private var ticked = false
    /** 되돌린 정차를 마지막으로 본 때. 판정 전에만 있다: 차가 떠나 있으면 이때가 정차 끝이다 */
    private var restoredLastSeen: Long? = null
    /** 되돌린 때 (판정을 기다리는 시간을 여기서부터 잰다) */
    private var restoredAt = 0L
    /** 되돌린 정차가 끝난 것으로 판정된 때 ([takeRestoredEnd]가 한 번 가져간다) */
    private var restoredEnd: Long? = null

    /** 되돌린 정차가 아직 서 있는지 떠났는지 정하지 못했다 (기어 신호를 기다린다). 그동안 여정과 엔진은 이 정차를 건드리지 않는다 */
    val restorePending: Boolean get() = restoredLastSeen != null

    /** 되돌린 정차가 "떠났다"로 판정됐으면 그 끝 시각을 한 번 돌려준다 (엔진을 같은 시각에 떠난 것으로 맞추는 데 쓴다) */
    fun takeRestoredEnd(): Long? = restoredEnd.also { restoredEnd = null }

    /** 지금 P인지 */
    val parked: Boolean get() = since != null

    /** 지금 P면 그 정차, 아니면 null (다시 대기를 기다리는 동안에도 null: 차는 P가 아니다) */
    val parkState: ParkState? get() = since?.let { ParkState(it, doorAt != null) }

    /** [doorOpened]: 지난 틱 이후 문이 열린 적이 있는지 */
    fun onTick(t: Long, gear: Gear, speedKph: Float?, doorOpened: Boolean): ParkedStop? {
        restoredLastSeen?.let { lastSeen ->
            val moving = speedKph != null && speedKph > MOVING_KPH
            if (gear == Gear.UNKNOWN && !moving && t - restoredAt < RESTORE_WAIT_MS) {
                // 기어를 아직 모른다: 서 있는지 떠났는지 정하지 않는다 (선 시간도 더하지 않는다). 문 열림은 이 정차의 것으로 적어 둔다
                if (doorOpened && doorAt == null) doorAt = t
                return null
            }
            restoredLastSeen = null
            if (gear != Gear.PARK) {
                // 이미 떠나 있다 (기어 D·R·N, 기어는 모르지만 움직인다, 끝내 기어를 모른다): 앱이 꺼진 사이(또는 시연 중) 떠났다.
                // 그 사이는 정차로 세지 않고 마지막으로 본 때에 끝낸다
                if (doorOpened && doorAt == null) doorAt = lastSeen
                // 다시 대기 창은 마지막으로 본 때부터다. 보지 못한 사이의 차속은 모른다 (엔진도 그렇게 센다: CoachWorld.endPark)
                val p = Pending(since!!, doorAt, parkedMs, lastSeen, movedM)
                p.moved(maxOf(0L, t - lastSeen), null)
                speedKph?.let { p.maxKph = it }
                since = null
                doorAt = null
                parkedMs = 0L
                movedM = 0.0
                lastT = t
                ticked = true
                lastKph = speedKph
                restoredEnd = lastSeen
                if (p.open) {
                    pending = p
                    return null
                }
                return finished(p)
            }
            // 아직 P다: 못 본 동안에도 서 있었다. 아래에서 마지막으로 본 때(lastT)부터 지금까지를 선 시간에 더한다
        }
        val dt = if (ticked) maxOf(0L, t - lastT) else 0L
        val wasParked = since != null
        val nowParked = when (gear) {
            Gear.PARK -> true
            Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL -> false
            Gear.UNKNOWN -> if (speedKph != null && speedKph > MOVING_KPH) false else wasParked
        }
        lastT = t
        ticked = true
        val prevKph = lastKph
        lastKph = speedKph
        var done: ParkedStop? = null

        if (wasParked) {
            parkedMs += dt
            if (doorOpened && doorAt == null) doorAt = t
            if (!nowParked) {
                pending = Pending(since!!, doorAt, parkedMs, t, movedM)
                since = null
                doorAt = null
            }
        } else if (nowParked) {
            val p = pending
            pending = null
            // 지난 틱부터 이 틱까지도 P 밖에서 움직였다. 이 틱의 차속은 보지 않는다 (P + 차속은 잡음이다)
            p?.moved(dt, prevKph)
            if (p != null && p.open) {
                // 다시 대기: 같은 정차로 이어 센다 (빈틈도 선 시간에 넣는다)
                since = p.since
                doorAt = p.doorAt
                parkedMs = p.parkedMs + p.gapMs
                movedM = p.movedM
            } else {
                if (p != null) done = finished(p)
                since = t
                doorAt = null
                parkedMs = 0L
                movedM = 0.0
            }
            if (doorOpened && doorAt == null) doorAt = t
            return done
        } else {
            pending?.moved(dt, prevKph)
        }
        // P가 아니다: 다시 대기 창을 지나면(시간·차속·거리) 정차를 확정한다
        val p = pending ?: return null
        speedKph?.let { if (it > p.maxKph) p.maxKph = it }
        if (!p.open) {
            pending = null
            return finished(p)
        }
        return null
    }

    private fun finished(p: Pending): ParkedStop? {
        if (p.parkedMs < minStopMs) return null
        return ParkedStop(p.leftAt - p.parkedMs, p.leftAt, p.doorAt != null)
    }

    /**
     * 정차를 바꿔 끼운다 (합치지 않는다). [state]가 null이면 정차 없이 시작한다. 다시 대기를 기다리던 정차는 옛 세계의 것이라 버린다.
     * - 앱 시작: TripSession.restoredPark를 첫 틱 전에. [lastSeenMs] = 마지막 맥박을 쓴 때
     * - 세계 전환(시연 켜기·끄기): WorldChange.restorePark를 바로. 시연을 켜면 null(시연 시계로 새로 센다: 두 시계를 빼서
     *   4시간 정차를 만들지 않는다), 끄면 실제 여정의 정차. 끌 때 [lastSeenMs] = 시연을 켠 실제 시각
     *
     * 기어를 아는 다음 틱이 P면 그 사이도 서 있던 것으로 센다. P가 아니면 그 사이 떠난 것이라 [lastSeenMs]에 정차가 끝난다(모르면 [now]).
     * 기어를 모르는 틱은 판정을 미루고, [RESTORE_WAIT_MS]가 지나도 모르면 떠난 것으로 본다(보지 못한 시간을 선 것으로 세지 않는다).
     * 그래서 시연 중 Virtual Console로 D를 넣고 시연을 꺼도 시연 시간만큼의 가짜 실제 정차가 생기지 않는다.
     * 미래 시각(시연 시각이 저장된 경우 등)은 정차 없음으로 본다. doorSeen이면 문 열림을 세운 때로 본다(문 시각은 저장하지 않는다).
     */
    fun restore(state: ParkState?, now: Long, lastSeenMs: Long = now) {
        pending = null
        lastT = now
        ticked = true
        lastKph = null
        movedM = 0.0
        restoredLastSeen = null
        restoredEnd = null
        restoredAt = now
        if (state == null || state.since > now) {
            since = null
            doorAt = null
            parkedMs = 0L
            return
        }
        val lastSeen = lastSeenMs.coerceIn(state.since, now)
        since = state.since
        doorAt = if (state.doorSeen) state.since else null
        parkedMs = lastSeen - state.since
        lastT = lastSeen
        restoredLastSeen = lastSeen
    }

    companion object {
        /** 기어를 모를 때 이 속도를 넘으면 주행 중으로 본다 (km/h, 엔진 StopTracker.MOVING_KPH와 같다) */
        const val MOVING_KPH = 3f
        /** 다시 대기로 보는 최대 공백 (엔진 StopTracker.REPARK_MAX_GAP_MS와 같다) */
        const val REPARK_MAX_GAP_MS = 3 * 60_000L
        /** 다시 대기로 보는 최고 차속 (km/h, 엔진 StopTracker.REPARK_MAX_KPH와 같다) */
        const val REPARK_MAX_KPH = 30f
        /** 다시 대기로 보는 최대 이동 거리 (m): 한 정차를 시작한 뒤 P 밖에서 움직인 거리의 합 (엔진 StopTracker.REPARK_MAX_MOVE_M과 같다) */
        const val REPARK_MAX_MOVE_M = 300.0
        /**
         * 되돌린 정차의 판정을 기어 신호 없이 기다리는 시간. Car 연결은 보통 1~2초 안에 온다. 그 동안 코치는 엔진을 돌리지 않으므로
         * 길게 잡지 않는다: 넘으면 떠난 것으로 본다
         */
        const val RESTORE_WAIT_MS = 10_000L
    }
}
