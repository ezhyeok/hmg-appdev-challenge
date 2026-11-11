package dev.shimpyo.app.coach

/**
 * NaviHelper 정체 정보(TrafficCongestionInfo)를 '차 기준' 값으로 바꾼다.
 *
 * 확인 결과(2026-11-11): trafficCongestionTime 단위는 초. offset의 기준(출발지 / 현재 위치)은
 * 에뮬레이터에서 차가 움직이지 않아 판정하지 못했다. 그래서 주행 중에 판정한다:
 * 같은 구간(도로명 + 시작 교차로)의 offset을 처음 본 뒤 남은 거리가 1km 이상 줄었을 때
 * offset도 그만큼 줄었으면 '현재 위치 기준', 그대로면 '출발지 기준'이다. 판정 전에는 현재 위치 기준으로 본다.
 */
object CongestionMath {
    enum class Basis { UNKNOWN, FROM_CAR, FROM_ORIGIN }

    @Volatile var basis = Basis.UNKNOWN
        private set
    /** 출발지 기준일 때 쓰는 전체 경로 거리 (처음 본 남은 거리) */
    @Volatile private var totalRouteM: Double? = null
    private val firstSeen = HashMap<String, Pair<Double, Double>>() // key → (offset, remain)

    /** 새 경로가 시작되면 부른다 */
    @Synchronized
    fun reset() {
        basis = Basis.UNKNOWN
        totalRouteM = null
        firstSeen.clear()
    }

    /** 정체 목록을 볼 때마다 부른다. 판정이 끝나면 더 계산하지 않는다 */
    @Synchronized
    fun observe(key: String, offset: Double, remainM: Double) {
        if (totalRouteM == null) totalRouteM = remainM
        if (basis != Basis.UNKNOWN) return
        val first = firstSeen.getOrPut(key) { offset to remainM }
        val moved = first.second - remainM
        if (moved < 1_000.0) return
        val offsetDrop = first.first - offset
        basis = when {
            kotlin.math.abs(offsetDrop - moved) < moved * 0.3 -> Basis.FROM_CAR
            kotlin.math.abs(offsetDrop) < 200.0 -> Basis.FROM_ORIGIN
            else -> Basis.UNKNOWN
        }
    }

    fun startFromCar(offset: Double, remainM: Double?): Double {
        if (basis != Basis.FROM_ORIGIN) return offset
        val total = totalRouteM ?: return offset
        val travelled = remainM?.let { total - it } ?: 0.0
        return offset - travelled
    }

    /** trafficCongestionTime은 초 단위 */
    fun durationS(time: Long): Double = time.toDouble()
}
