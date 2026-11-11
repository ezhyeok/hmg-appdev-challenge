package dev.shimpyo.app.coach

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
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

    fun extract(tbt: List<TBTInfo>): List<RouteOpportunity> {
        var cumulative = 0.0
        val out = ArrayList<RouteOpportunity>()
        for (item in tbt) {
            cumulative += item.distance.coerceAtLeast(0.0)
            val kind = when (item.type) {
                TurnType.TURN_ENTER_SERVICEAREA -> StopKind.HIGHWAY_REST_AREA
                TurnType.TURN_RESTAREA -> StopKind.DROWSY_SHELTER
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
    fun nextManeuverDistance(tbt: List<TBTInfo>): Double? = tbt.firstOrNull()?.distance

    private fun isRestStop(name: String, kind: StopKind): Boolean = when (kind) {
        StopKind.HIGHWAY_REST_AREA -> name.contains("휴게소") || name.contains("쉼터")
        StopKind.DROWSY_SHELTER -> name.contains("쉼터") || name.contains("휴게")
        else -> false
    }

    /**
     * 이름 비교용 정규화: 공백·괄호 속 방향·"휴게소/졸음쉼터" 접미어를 지운다.
     * 예) "안성휴게소(부산방향)" → "안성", "오산 졸음쉼터" → "오산"
     */
    fun normalizeName(name: String): String = name
        .replace(Regex("\\(.*?\\)|\\[.*?]"), "")
        .replace(" ", "")
        .replace(Regex("(졸음쉼터|휴게소|쉼터|휴게시설)$"), "")
        .replace(Regex("(상행|하행|[가-힣]+방향|[가-힣]+방면)$"), "")
        .trim()
}
