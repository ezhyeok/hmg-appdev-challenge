package dev.shimpyo.coach

import dev.shimpyo.engine.CongestionSegment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * NaviHelper 정체 정보(TrafficCongestionInfo)의 offset을 '차에서 정체 시작까지 거리'로 바꾼다.
 * NaviTracker가 하나를 갖고 내비 조회 코루틴에서만 부른다 (예전 전역 CongestionMath 객체를 대신한다, 구조 리뷰 S3).
 *
 * offset의 기준(출발지 / 현재 위치)은 에뮬레이터 내비가 움직이지 않아 확인하지 못했다(2026-11-11). 그래서 주행 중에 판정한다.
 * 같은 구간(도로명 + 시작 교차로)을 처음 본 뒤 내비 남은 거리가 1km 이상 줄었을 때
 * - offset도 그만큼(±30%) 줄었으면 '현재 위치 기준'(FROM_CAR) 한 표,
 * - 거의 그대로(이동 거리의 20% 안)면 '출발지 기준'(FROM_ORIGIN) 한 표.
 * 한쪽이 두 표 이상, 다른 쪽보다 두 표 넘게 앞서면 정한다. 정하기 전에는 위치 기반 정체 판단을 보류한다.
 *
 * 예전 판정의 구멍(데이터 감사 #4)과 막는 방법:
 * - 전체 경로 거리를 첫 정체를 본 때 잡았다 → 길안내 시작·재탐색 때([start]) 잡는다.
 * - 차가 이미 정체 안에 있으면 offset이 0 근처에 머물러 1km 뒤 '출발지 기준'으로 굳었다 → offset 300m 미만은 판정에 쓰지 않는다.
 * - 남은 거리 차이만 봤다 → 주행 거리(odometer)가 같은 만큼 늘었을 때만 센다. 남은 거리만으로는 주행과 경로 재계산을 못 가르고,
 *   주행 거리만으로는 내비가 서 있는 경우(에뮬레이터)를 못 가른다. 둘이 맞을 때만 내비가 실제로 그만큼 나아간 것이다.
 *   offset과 비교하는 이동 거리는 내비 남은 거리 차이다(offset과 같은 내비 기준).
 * - 한 번 보고 굳혔다 → 두 표가 맞아야 정한다. 표를 던진 구간은 그 자리에서 다시 재기 시작해 같은 관측을 두 번 세지 않는다.
 *
 * 판정 결과(어느 기준인지)는 경로가 아니라 내비 API의 성질이라 경로가 바뀌어도 유지한다. 경로마다 바뀌는 것
 * (구간별 첫 관측, 전체 경로 거리)만 [start]·[clear]에서 지운다.
 */
class CongestionBasis {
    enum class Basis { UNKNOWN, FROM_CAR, FROM_ORIGIN }

    /** 빈 segments만으로 '정체 없음'을 단정하지 않는다. 수신·기준·출발점이 모두 유효해야 basisKnown이다. */
    data class Result(val segments: List<CongestionSegment>, val basisKnown: Boolean)

    var basis = Basis.UNKNOWN
        private set
    private var votesCar = 0
    private var votesOrigin = 0

    // ---- 이번 경로
    /** 이번 경로의 시작을 봤는지. 길안내 중에 앱이 켜졌으면 출발지를 몰라 출발지 기준 offset을 바꿀 수 없다 */
    private var originKnown = false
    /** 출발지 기준일 때 쓰는 전체 경로 거리 (경로 시작 때의 남은 거리) */
    private var totalRouteM: Double? = null
    private val sightings = HashMap<String, Sighting>()

    private data class Sighting(val offsetM: Double, val remainM: Double, val odometerM: Double)

    /**
     * 새 경로(길안내 시작·재탐색·목적지 변경, NaviTracker의 epoch 증가). [originKnown]: 경로가 시작되는 것을 봤는지
     * (내비는 재탐색하면 지금 위치에서 경로를 다시 짠다고 본다). [remainM]이 null이면 이번 경로에서 처음 받는 남은 거리를 쓴다.
     */
    fun start(remainM: Double?, originKnown: Boolean) {
        this.originKnown = originKnown
        totalRouteM = remainM?.takeIf { it.isFinite() && it >= 0.0 }
        sightings.clear()
    }

    /** 길안내가 끝났다 */
    fun clear() {
        originKnown = false
        totalRouteM = null
        sightings.clear()
    }

    /**
     * 경로 상태를 받을 때마다 부른다: 판정에 쓰고, 차 기준 정체 구간으로 바꾼다. 깨진 항목(NaN)은 버리고,
     * 이미 지나간 구간과 위치를 정할 수 없는 구간(출발지 기준인데 출발지를 모름)도 뺀다.
     */
    fun update(items: List<NaviCongestion>, remainM: Double?, odometerM: Double, available: Boolean = true): Result {
        if (!available) return Result(emptyList(), false)
        val remain = remainM?.takeIf { it.isFinite() && it >= 0.0 }
        if (originKnown && totalRouteM == null && remain != null) totalRouteM = remain
        val valid = items.filter { it.offsetM.isFinite() && it.lengthM.isFinite() && it.lengthM >= 0.0 }
        if (basis == Basis.UNKNOWN && remain != null) valid.forEach { observe(keyOf(it), it.offsetM, remain, odometerM) }
        val located = valid.mapNotNull { c ->
            val start = startFromCar(c.offsetM, remain) ?: return@mapNotNull null
            CongestionSegment(
                startDistanceM = start,
                lengthM = c.lengthM,
                durationS = c.durationS.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                type = c.type,
                roadName = c.roadName,
                startName = c.startName,
                endName = c.endName,
            )
        }.filter { it.startDistanceM + it.lengthM > 0.0 }
        val known = startFromCar(0.0, remain) != null && valid.size == items.size
        return Result(located, known)
    }

    /**
     * 차에서 정체 시작까지 거리. 출발지 기준이면 그동안 내비가 나아간 거리(전체 - 남은 거리)를 뺀다.
     * 출발지 기준인데 출발지나 남은 거리를 모르면 null (위치를 정할 수 없다).
     */
    fun startFromCar(offsetM: Double, remainM: Double?): Double? {
        if (basis == Basis.UNKNOWN) return null
        if (basis == Basis.FROM_CAR) return offsetM
        val total = totalRouteM ?: return null
        if (!originKnown || remainM == null) return null
        return offsetM - (total - remainM)
    }

    private fun observe(key: String, offset: Double, remain: Double, odometer: Double) {
        val first = sightings[key]
        if (first == null) {
            // 차가 정체 안이거나 바로 앞이면 현재 위치 기준 offset도 0 근처에 머물러 '출발지 기준'처럼 보인다
            if (offset >= MIN_OFFSET_M) sightings[key] = Sighting(offset, remain, odometer)
            return
        }
        val naviMoved = first.remainM - remain
        if (naviMoved < DECIDE_M) return
        val odoMoved = odometer - first.odometerM
        // 다음 표는 여기서부터 다시 잰다 (같은 관측을 두 번 세지 않게). 정체 근처로 왔으면 더 쓰지 않는다
        if (offset >= MIN_OFFSET_M) sightings[key] = Sighting(offset, remain, odometer) else sightings.remove(key)
        if (abs(odoMoved - naviMoved) > AGREE * naviMoved) return
        val drop = first.offsetM - offset
        when {
            abs(drop - naviMoved) <= AGREE * naviMoved -> votesCar++
            abs(drop) <= STILL * naviMoved -> votesOrigin++
            else -> return // 정체가 자라거나 줄었다: 어느 쪽 증거도 아니다
        }
        val lead = max(votesCar, votesOrigin)
        if (lead >= VOTES_NEEDED && lead - min(votesCar, votesOrigin) >= VOTES_NEEDED) {
            basis = if (votesCar > votesOrigin) Basis.FROM_CAR else Basis.FROM_ORIGIN
        }
    }

    private fun keyOf(c: NaviCongestion) = "${c.roadName}|${c.startName}"

    /** 테스트용: (현재 위치 기준 표, 출발지 기준 표) */
    internal val votes: Pair<Int, Int> get() = votesCar to votesOrigin
    /** 테스트용: 이번 경로의 전체 거리 */
    internal val routeTotalM: Double? get() = totalRouteM
    /** 테스트용: 이번 경로의 시작을 봤는지 */
    internal val routeOriginKnown: Boolean get() = originKnown

    companion object {
        /** 판정에 쓰는 최소 offset (정체 안·바로 앞은 기준을 가를 수 없다) */
        const val MIN_OFFSET_M = 300.0
        /** 한 표를 던지는 데 필요한 내비 이동 거리 */
        const val DECIDE_M = 1_000.0
        /** offset 감소가 이동 거리와 '같다'고 보는 범위, 주행 거리와 내비 이동이 '맞다'고 보는 범위 */
        const val AGREE = 0.3
        /** offset이 '그대로'라고 보는 범위 (이동 거리 대비) */
        const val STILL = 0.2
        const val VOTES_NEEDED = 2
    }
}
