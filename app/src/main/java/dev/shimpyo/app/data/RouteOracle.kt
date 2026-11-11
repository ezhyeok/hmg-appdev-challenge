package dev.shimpyo.app.data

import ai.pleos.playground.navi.data.TripRequest
import dev.hmg.pleoskit.NaviKit
import dev.shimpyo.engine.StopCandidate

/**
 * 후보까지의 실제 경로 거리와 시간을 Pleos 내비(requestTrip)로 구한다.
 * 진행 방향 반대편 휴게소는 유턴을 해야 하므로 경로 거리가 직선거리보다 크게 나온다. 이걸로 방향을 판정한다.
 * 같은 후보는 차가 3km 이상 움직였거나 5분이 지났을 때만 다시 묻는다 (내비 요청을 아끼려고).
 */
class RouteOracle(private val navi: NaviKit) {
    private data class Entry(val distanceM: Double, val etaS: Double, val at: Long, val fromLat: Double, val fromLon: Double)

    // 후보 갱신 루프와 시연 시작(clear)이 다른 스레드에서 만진다
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    /** false면 requestTrip을 쓰지 않는다 (길안내를 방해한다고 확인되면 끈다) */
    @Volatile var enabled = true

    suspend fun refine(c: StopCandidate, lat: Double, lon: Double, now: Long): StopCandidate {
        val straight = c.straightDistanceM ?: Geo.distanceM(lat, lon, c.lat, c.lon)
        val cached = cache[c.id]
        val fresh = cached != null && now - cached.at < MAX_AGE_MS && Geo.distanceM(lat, lon, cached.fromLat, cached.fromLon) < MOVE_M
        val entry = when {
            fresh -> cached!!
            !enabled -> return c.copy(straightDistanceM = straight)
            else -> runCatching { navi.tripEstimate(TripRequest(c.lat, c.lon, "", c.name, ""), timeoutMs = 20_000) }.getOrNull()
                ?.let { Entry(it.distance.toDouble(), it.eta.toDouble(), now, lat, lon) }
                ?.also { cache[c.id] = it }
                ?: return c.copy(straightDistanceM = straight)
        }
        // 캐시 이후 진행한 만큼 뺀다 (진행 방향으로 움직였다고 가정)
        val moved = Geo.distanceM(lat, lon, entry.fromLat, entry.fromLon)
        val dist = (entry.distanceM - moved).coerceAtLeast(0.0)
        val eta = if (entry.distanceM > 1.0) entry.etaS * dist / entry.distanceM else entry.etaS
        return c.copy(
            straightDistanceM = straight,
            routeDistanceM = dist,
            etaS = eta,
            directionOk = dist <= straight * DETOUR_RATIO + DETOUR_SLACK_M,
        )
    }

    fun clear() = cache.clear()

    private companion object {
        const val MAX_AGE_MS = 5 * 60_000L
        const val MOVE_M = 3_000.0
        /** 경로 거리가 직선거리의 1.3배 + 2km를 넘으면 유턴이 필요하다고 본다 (확인: 안성휴게소 부산방향 47.5/44.6km, 서울방향 57.1/37.6km) */
        const val DETOUR_RATIO = 1.3
        const val DETOUR_SLACK_M = 2_000.0
    }
}
