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
        // 거리가 깨진 항목(NaN·음수)부터는 누적 거리를 믿을 수 없어 읽지 않는다 (TbtDigest, 데이터 감사 #7)
        for (item in TbtDigest.readable(tbt)) {
            cumulative += item.distanceM
            if (item.type != TbtType.SERVICE_AREA && item.type != TbtType.REST_AREA) continue
            val name = item.description.trim()
            // 종류는 이름이 분명하면 이름을 따른다. SERVICEAREA로 온 '옥산졸음쉼터(서울방향)'를 휴게소로 보면 같은 이름의
            // 옥산휴게소(부산방향, 0.3km)와 짝이 되어 반대 차로 시설을 준다 (데이터 감사 #5). 둘 다거나 둘 다 아니면 TBT 종류를 따른다
            val saysRestArea = name.contains("휴게소")
            val saysShelter = name.contains("쉼터")
            val kind = when {
                saysShelter && !saysRestArea -> StopKind.DROWSY_SHELTER
                saysRestArea && !saysShelter -> StopKind.HIGHWAY_REST_AREA
                item.type == TbtType.SERVICE_AREA -> StopKind.HIGHWAY_REST_AREA
                else -> StopKind.DROWSY_SHELTER
            }
            // 하이패스센터처럼 화장실이 없는 '서비스 시설'도 SERVICEAREA로 온다. 이름으로 거른다
            if (!isRestStop(name, kind)) continue
            out += RouteOpportunity(name = name, kind = kind, routeDistanceM = cumulative)
        }
        return out
    }

    /** 다음 회전까지 거리 (첫 항목). 첫 거리가 깨졌으면 null */
    fun nextManeuverDistance(tbt: List<TbtItem>): Double? = TbtDigest.readable(tbt).firstOrNull()?.distanceM

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
        .replace(BRACKETS, "")
        .replace(" ", "")
        .replace(FACILITY_SUFFIX, "")
        .replace(DIRECTION_SUFFIX, "")
        .trim()

    // 정규식은 한 번만 컴파일한다 (예전에는 부를 때마다 3개를 새로 컴파일했다. 이름 맞추기는 후보마다 부른다)
    private val BRACKETS = Regex("\\(.*?\\)|\\[.*?]")
    private val FACILITY_SUFFIX = Regex("(졸음쉼터|휴게소|쉼터|휴게시설)$")
    private val DIRECTION_SUFFIX = Regex("(상행|하행|[가-힣]+방향|[가-힣]+방면)$")
}
