package dev.shimpyo.coach

import dev.shimpyo.engine.StopKind

/** 경로 위 화장실 기회 하나 (TBT에서 읽은 것) */
data class RouteOpportunity(
    val name: String,
    val kind: StopKind,
    /** 현재 위치에서 경로를 따라간 거리 */
    val routeDistanceM: Double,
)

/**
 * Pleos 내비의 회전 안내(TBT)에서 경로 위 휴게소·졸음쉼터를 뽑는다.
 *
 * 에뮬레이터 확인(2026-11-11): TBT에는 휴게소(TURN_ENTER_SERVICEAREA)와 졸음쉼터(TURN_RESTAREA)가
 * 이름과 함께 오고, distance는 '직전 항목부터의 거리'다. 첫 항목만 현재 위치부터의 거리다.
 * 그래서 누적합이 경로 거리다. 경로 위에 있으니 진행 방향도 자동으로 맞다.
 */
object RouteOpportunities {

    fun extract(tbt: List<TbtItem>): List<RouteOpportunity> {
        var cumulative = 0.0
        val out = ArrayList<RouteOpportunity>()
        for (item in tbt) {
            cumulative += item.distanceM.coerceAtLeast(0.0)
            val kind = when (item.type) {
                TbtType.SERVICE_AREA -> StopKind.HIGHWAY_REST_AREA
                TbtType.REST_AREA -> StopKind.DROWSY_SHELTER
                else -> null
            } ?: continue
            val name = item.description.trim()
            // 하이패스센터처럼 화장실이 없는 '서비스 시설'도 SERVICEAREA로 온다. 이름으로 거른다
            if (!isRestStop(name, kind)) continue
            out += RouteOpportunity(name = name, kind = kind, routeDistanceM = cumulative)
        }
        return out
    }

    /** 다음 회전까지 거리 (첫 항목) */
    fun nextManeuverDistance(tbt: List<TbtItem>): Double? = tbt.firstOrNull()?.distanceM

    // 이름 규칙은 StopNames.kt (DATA 담당). 부르는 쪽이 그대로 컴파일되도록 위임만 남긴다
    private fun isRestStop(name: String, kind: StopKind): Boolean = StopNames.isRestStop(name, kind)

    /** 이름 비교용 정규화 (StopNames.normalizeName). 예) "안성휴게소 부산방향" → "안성" */
    fun normalizeName(name: String): String = StopNames.normalizeName(name)
}
