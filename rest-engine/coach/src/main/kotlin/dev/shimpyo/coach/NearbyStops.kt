package dev.shimpyo.coach

import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind

/**
 * 일상 길의 가까운 정차 후보 (엔진 CoachInput.nearby). 공중화장실, 화장실 있는 주유소, 화장실 가까운 공영주차장 가운데 진행 방향
 * [RADIUS_M] 안의, 지금 열었고 차를 세울 수 있는 곳을 가까운 순 [MAX]곳 넣는다. 엔진은 경로 위 화장실 기회가 가까이 없을 때만
 * 이 목록을 쓴다 (CityNearbyTest).
 *
 * 길가 화장실에 바로 갈 수 없는 길에서는 비운다 ([accessControlled]): 직선거리로 600m인 한강공원 화장실도 강변북로에서는 나들목을
 * 돌아 몇 km다. 이 목록은 직선거리만 알아서(내비 경로 거리를 재지 않는다) 그런 곳을 가까운 곳으로 착각한다.
 * - 고속도로 차로 위 (지도 맞춤, StopIndex.onMotorway). 방향을 모르면(출발 전) 길안내 경로 위에 휴게소가 보이는지로 대신한다
 * - 내비가 알려 준 지금 도로의 제한속도가 [ACCESS_CONTROLLED_LIMIT_KPH] 이상 (도시고속도로·자동차전용도로. 지도 맞춤 자료에 없다)
 * - 시속 [HIGHWAY_KPH] 이상
 *
 * 검색은 [REFRESH_M]를 움직였거나 [REFRESH_MS]가 지났을 때만 하고(격자 색인이라 가볍지만 매초 할 일은 아니다), 매 틱에는 지금
 * 위치로 직선거리만 다시 잰다. 가까운 [POOL]곳을 받아 쓸 수 없는 곳(화장실 없음·주차 불가·닫힘·진행 방향 뒤)을 먼저 거른 뒤
 * [MAX]곳을 남긴다: 도심에서는 가장 가까운 화장실 여럿이 주차 불가라서, 8곳을 먼저 자르면 쓸 곳이 하나도 남지 않을 수 있다.
 * 코치 디스패처에서만 쓴다.
 */
class NearbyStops(private val stops: StopIndex) {
    private var cache: List<StopCandidate> = emptyList()
    private var at: Fix? = null
    private var atMs = Long.MIN_VALUE

    /**
     * [pos]: 이 세계의 위치(시연이면 시연 위치), [heading]: 진행 방향(서 있어도 마지막으로 달리던 방향), [highwayRoute]: 길안내 경로 위
     * 휴게소 기회가 보인다, [roadLimitKph]: 내비가 알려 준 지금 도로의 제한속도(모르거나 시연이면 null), [now]: 코치 시각(영업 시간
     * 판단), [elapsedMs]: 갱신 주기를 재는 실제 시간
     */
    fun current(
        pos: Fix?, heading: Float?, highwayRoute: Boolean, speedKph: Float?, roadLimitKph: Int?, now: Long, elapsedMs: Long,
    ): List<StopCandidate> {
        if (pos == null || !stops.loaded || accessControlled(pos, heading, highwayRoute, speedKph, roadLimitKph)) return emptyList()
        val last = at
        if (last == null || Geo.distanceM(last.lat, last.lon, pos.lat, pos.lon) > REFRESH_M || elapsedMs - atMs >= REFRESH_MS) {
            cache = stops.near(pos.lat, pos.lon, heading, RADIUS_M, KINDS, now = now, limit = POOL).filter(::usable).take(MAX)
            at = pos
            atMs = elapsedMs
        }
        return cache.map { it.copy(straightDistanceM = Geo.distanceM(pos.lat, pos.lon, it.lat, it.lon), routeDistanceM = null, etaS = null) }
            .sortedBy { it.straightDistanceM }
    }

    /** 길가 화장실에 바로 갈 수 없는 길인가 (클래스 설명) */
    private fun accessControlled(pos: Fix, heading: Float?, highwayRoute: Boolean, speedKph: Float?, roadLimitKph: Int?): Boolean {
        if ((speedKph ?: 0f) >= HIGHWAY_KPH) return true
        if (roadLimitKph != null && roadLimitKph >= ACCESS_CONTROLLED_LIMIT_KPH) return true
        return if (heading != null) stops.onMotorway(pos.lat, pos.lon, heading) else highwayRoute
    }

    /** 세계가 바뀌었다 (시연 켜고 끄기): 다음 틱에 새로 찾는다 */
    fun reset() {
        cache = emptyList()
        at = null
        atMs = Long.MIN_VALUE
    }

    companion object {
        const val RADIUS_M = 3_000.0
        const val MAX = 8
        /** 거르기 전에 받는 수. 쓸 수 있는 곳은 화장실의 약 3분의 1이다(차를 세울 수 있는 화장실 13,922 / 41,667) */
        const val POOL = 48
        const val REFRESH_M = 250.0
        const val REFRESH_MS = 20_000L
        /** 이 속도 이상이면 고속도로·도시고속도로로 본다 (길가 화장실에 바로 갈 수 없다) */
        const val HIGHWAY_KPH = 80f
        /**
         * 제한속도가 이 이상인 길은 나들목으로만 드나드는 길로 본다. 도시고속도로(강변북로·올림픽대로·내부순환로 등) 70~80,
         * 고속도로 100~110, 시내 간선도로는 50(안전속도 5030)·일부 60이다
         */
        const val ACCESS_CONTROLLED_LIMIT_KPH = 70
        val KINDS: Set<StopKind> = setOf(StopKind.PUBLIC_RESTROOM, StopKind.GAS_STATION, StopKind.PUBLIC_PARKING)

        /** 엔진이 쓸 수 있는 일상 정차 후보 (엔진 CoachEngine.usableNearby와 같은 조건의 앞부분) */
        fun usable(c: StopCandidate): Boolean = c.facilities.restroom && c.parkable && c.openNow != false && c.directionOk != false
    }
}
