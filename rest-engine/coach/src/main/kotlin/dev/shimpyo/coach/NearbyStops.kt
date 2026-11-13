package dev.shimpyo.coach

import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind

/**
 * 일상 길의 가까운 정차 후보 (엔진 CoachInput.nearby). 공중화장실, 화장실 있는 주유소, 화장실 가까운 공영주차장 가운데 진행 방향
 * [RADIUS_M] 안의 지금 연 곳을 가까운 순 [MAX]곳 넣는다. 엔진은 경로 위 화장실 기회가 가까이 없을 때만 이 목록을 쓴다 (CityNearbyTest).
 *
 * 고속도로에서는 비운다: 길안내 경로가 휴게소를 지나거나(TBT 기회가 있다) 시속 [HIGHWAY_KPH] 이상이면 길가 화장실에 갈 수 없다.
 * 검색은 [REFRESH_M]를 움직였거나 [REFRESH_MS]가 지났을 때만 하고(격자 색인이라 가볍지만 매초 할 일은 아니다), 매 틱에는 지금
 * 위치로 직선거리만 다시 잰다. 코치 디스패처에서만 쓴다.
 */
class NearbyStops(private val stops: StopIndex) {
    private var cache: List<StopCandidate> = emptyList()
    private var at: Fix? = null
    private var atMs = Long.MIN_VALUE

    /**
     * [pos]: 이 세계의 위치(시연이면 시연 위치), [heading]: 진행 방향, [highwayRoute]: 길안내 경로 위 휴게소 기회가 보인다,
     * [now]: 코치 시각(영업 시간 판단), [elapsedMs]: 갱신 주기를 재는 실제 시간
     */
    fun current(pos: Fix?, heading: Float?, highwayRoute: Boolean, speedKph: Float?, now: Long, elapsedMs: Long): List<StopCandidate> {
        if (pos == null || !stops.loaded || highwayRoute || (speedKph ?: 0f) >= HIGHWAY_KPH) return emptyList()
        val last = at
        if (last == null || Geo.distanceM(last.lat, last.lon, pos.lat, pos.lon) > REFRESH_M || elapsedMs - atMs >= REFRESH_MS) {
            cache = stops.near(pos.lat, pos.lon, heading, RADIUS_M, KINDS, now = now, limit = MAX).filter { it.facilities.restroom && it.parkable }
            at = pos
            atMs = elapsedMs
        }
        return cache.map { it.copy(straightDistanceM = Geo.distanceM(pos.lat, pos.lon, it.lat, it.lon), routeDistanceM = null, etaS = null) }
            .sortedBy { it.straightDistanceM }
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
        const val REFRESH_M = 250.0
        const val REFRESH_MS = 20_000L
        /** 이 속도 이상이면 고속도로·도시고속도로로 본다 (길가 화장실에 바로 갈 수 없다) */
        const val HIGHWAY_KPH = 80f
        val KINDS: Set<StopKind> = setOf(StopKind.PUBLIC_RESTROOM, StopKind.GAS_STATION, StopKind.PUBLIC_PARKING)
    }
}
