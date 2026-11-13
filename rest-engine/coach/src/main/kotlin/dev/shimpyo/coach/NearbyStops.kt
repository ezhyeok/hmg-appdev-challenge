package dev.shimpyo.coach

import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind

/**
 * 일상 길의 가까운 정차 후보 (엔진 CoachInput.nearby). 공중화장실, 화장실 있는 주유소, 화장실 가까운 공영주차장 가운데 [RADIUS_M] 안의,
 * 지금 열었고 차를 세울 수 있고 진행 방향인 곳을 가까운 순 [MAX]곳 넣는다. 엔진은 고속도로의 공백 판단에 쓰지 않고, 다음 화장실(경로 위
 * 기회가 없을 때), 지금 필요할 때(경로의 화장실보다 빨리 닿을 때), 말로 한 요청의 답에 쓴다 (CityNearbyTest).
 *
 * 넣지 않는 때 ([NearbyMode.SIGNALS], 실제 세계): 이 목록은 직선거리만 알아서 길가 화장실에 바로 갈 수 없는 길에서는 틀린 답이 된다
 * (직선 600m인 한강공원 화장실도 강변북로에서는 나들목을 돌아 몇 km다).
 * - 고속도로 차로 위 (지도 맞춤, StopIndex.onMotorway). 방향을 모르면(출발 전) 길안내 경로 위에 휴게소가 보이는지로 대신한다
 * - 내비가 알려 준 지금 도로의 제한속도가 [ACCESS_CONTROLLED_LIMIT_KPH] 이상 (도시고속도로·자동차전용도로. 지도 맞춤 자료에 없다)
 * - 시속 [HIGHWAY_KPH] 이상
 * - 고속도로 휴게소·졸음쉼터 [AT_HIGHWAY_FACILITY_M] 안 (그 시설에 서 있다: 울타리 밖 마을 화장실에 갈 수 없다)
 * 시연은 장면이 정한다 ([NearbyMode.ALWAYS]·[NearbyMode.NEVER], CoachWorld.nearbyMode).
 *
 * 검색은 [REFRESH_M]를 움직였거나 [REFRESH_MS]가 지났을 때만 한다(격자 색인이라 가볍지만 매초 할 일은 아니다). 쓸 수 없는 곳(화장실
 * 없음·주차 불가·닫힘·뒤쪽)은 자르기 전에 거른다(StopIndex.nearUsable): 도심에서는 가장 가까운 화장실 여럿이 주차 불가라, 자른 뒤 거르면
 * 쓸 곳이 남지 않았다. 매 틱에는 지금 위치로 직선거리를 다시 재고, 달리는 중이면 이미 지나친(뒤로 간) 곳을 뺀다: 검색 사이에 지나친
 * 곳이 '진행 방향'으로 남아 있었다. 서 있으면 빼지 않는다(걸어서 간다). 코치 디스패처에서만 쓴다.
 */
class NearbyStops(private val stops: StopIndex) {
    private var cache: List<StopCandidate> = emptyList()
    private var at: Fix? = null
    private var atMs = Long.MIN_VALUE
    /** 마지막 검색이 달리는 중이었는지 (서 있을 때는 방향 없이 찾는다: 출발하면 다시 찾는다) */
    private var atMoving = false

    /**
     * [pos]: 이 세계의 위치(시연이면 시연 위치), [heading]: 진행 방향(서 있어도 마지막으로 달리던 방향), [highwayRoute]: 길안내 경로 위
     * 휴게소 기회가 보인다, [roadLimitKph]: 내비가 알려 준 지금 도로의 제한속도(모르면 null), [now]: 코치 시각(영업 시간 판단),
     * [elapsedMs]: 갱신 주기를 재는 실제 시간, [mode]: 이 세계가 정한 방식
     */
    fun current(
        pos: Fix?, heading: Float?, highwayRoute: Boolean, speedKph: Float?, roadLimitKph: Int?, now: Long, elapsedMs: Long,
        mode: NearbyMode = NearbyMode.SIGNALS,
    ): List<StopCandidate> {
        if (pos == null || !stops.loaded || mode == NearbyMode.NEVER) return emptyList()
        if (mode == NearbyMode.SIGNALS && accessControlled(pos, heading, highwayRoute, speedKph, roadLimitKph, now)) return emptyList()
        val moving = (speedKph ?: 0f) >= MOVING_KPH
        val last = at
        if (last == null || moving != atMoving || Geo.distanceM(last.lat, last.lon, pos.lat, pos.lon) > REFRESH_M || elapsedMs - atMs >= REFRESH_MS) {
            // 서 있으면 방향 없이 찾는다: 걸어서 가니 지나쳐 세운 뒤쪽 화장실도 '가까이에 화장실이 있어요'다 (2026-11-13 검증)
            cache = stops.nearUsable(pos.lat, pos.lon, if (moving) heading else null, RADIUS_M, KINDS, now = now, limit = MAX)
            at = pos
            atMs = elapsedMs
            atMoving = moving
        }
        val out = ArrayList<StopCandidate>(cache.size)
        for (c in cache) {
            val d = Geo.distanceM(pos.lat, pos.lon, c.lat, c.lon)
            // 지나친 곳은 바로 뒤([PASSED_MAX_M] 안)뿐이다: 길을 꺾는 동안 멀리 있는 곳이 잠깐 뒤로 들어가는 것은 지나친 것이 아니다
            if (moving && heading != null && d > PASSED_FREE_M && d <= PASSED_MAX_M &&
                Geo.angleDiff(Geo.bearingDeg(pos.lat, pos.lon, c.lat, c.lon), heading.toDouble()) > PASSED_DEG
            ) continue
            out += c.copy(straightDistanceM = d, routeDistanceM = null, etaS = null)
        }
        out.sortBy { it.straightDistanceM }
        return out
    }

    /** 길가 화장실에 바로 갈 수 없는 길인가 (클래스 설명) */
    private fun accessControlled(pos: Fix, heading: Float?, highwayRoute: Boolean, speedKph: Float?, roadLimitKph: Int?, now: Long): Boolean {
        if ((speedKph ?: 0f) >= HIGHWAY_KPH) return true
        if (roadLimitKph != null && roadLimitKph >= ACCESS_CONTROLLED_LIMIT_KPH) return true
        if (if (heading != null) stops.onMotorway(pos.lat, pos.lon, heading) else highwayRoute) return true
        return stops.near(pos.lat, pos.lon, null, AT_HIGHWAY_FACILITY_M, HIGHWAY_KINDS, now, 1).isNotEmpty()
    }

    /** 세계가 바뀌었다 (시연 켜고 끄기): 다음 틱에 새로 찾는다 */
    fun reset() {
        cache = emptyList()
        at = null
        atMs = Long.MIN_VALUE
        atMoving = false
    }

    companion object {
        /** 급해 모드와 같은 반경 (시골에서 3km 안에 쓸 곳이 없던 곳이 있었다) */
        const val RADIUS_M = 5_000.0
        const val MAX = 12
        const val REFRESH_M = 250.0
        const val REFRESH_MS = 20_000L
        /** 이 속도 이상이면 고속도로·도시고속도로로 본다 (길가 화장실에 바로 갈 수 없다) */
        const val HIGHWAY_KPH = 80f
        /**
         * 제한속도가 이 이상인 길은 나들목으로만 드나드는 길로 본다. 도시고속도로(강변북로·올림픽대로·내부순환로 등) 70~80,
         * 고속도로 100~110, 시내 간선도로는 50(안전속도 5030)·일부 60이다. 평면 교차로가 있는 70~80 국도도 여기 걸린다(알려진 한계)
         */
        const val ACCESS_CONTROLLED_LIMIT_KPH = 70
        /** 고속도로 휴게소·졸음쉼터가 이 안에 있으면 그 시설에 서 있거나 지나는 중이다 (StopRepository.AT_FACILITY_M보다 조금 넓게) */
        const val AT_HIGHWAY_FACILITY_M = 300.0
        /** 이 속도 이상이면 달리는 중: 매 틱 지나친(뒤로 간) 곳을 뺀다 */
        const val MOVING_KPH = 5f
        /** 바로 옆(이 안)은 방위가 흔들려 지나쳤다고 보지 않는다 */
        const val PASSED_FREE_M = 50.0
        /** 검색 사이(250m)에 지나친 곳은 이 안에 있다. 더 먼 곳이 뒤로 보이면 길을 꺾는 중이다 */
        const val PASSED_MAX_M = 300.0
        const val PASSED_DEG = 100.0
        val KINDS: Set<StopKind> = setOf(StopKind.PUBLIC_RESTROOM, StopKind.GAS_STATION, StopKind.PUBLIC_PARKING)
        private val HIGHWAY_KINDS: Set<StopKind> = setOf(StopKind.HIGHWAY_REST_AREA, StopKind.DROWSY_SHELTER)
    }
}
