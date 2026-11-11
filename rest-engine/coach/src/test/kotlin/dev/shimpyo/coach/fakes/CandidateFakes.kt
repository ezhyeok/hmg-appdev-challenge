package dev.shimpyo.coach.fakes

import dev.shimpyo.coach.ElapsedClock
import dev.shimpyo.coach.Fix
import dev.shimpyo.coach.Geo
import dev.shimpyo.coach.NaviChange
import dev.shimpyo.coach.NaviDestination
import dev.shimpyo.coach.NaviPort
import dev.shimpyo.coach.NaviRouteStatus
import dev.shimpyo.coach.StopIndex
import dev.shimpyo.coach.TbtItem
import dev.shimpyo.coach.TripEstimate
import dev.shimpyo.coach.TripEstimator
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/*
 * CandidateProvider·RouteOracle 테스트용 가짜. 위치는 남북으로 뻗은 가상 고속도로(경도 127.1) 위에 둔다:
 * 남쪽으로 k km = 위도 37.0 - k/111. 차는 37.0에서 남쪽(방위 180)으로 달린다.
 */
object Road {
    const val LON = 127.1
    const val START_LAT = 37.0
    fun latAtKm(km: Double) = START_LAT - km / 111.0
    /** 남쪽 k km 지점에서 남행(방위 180)하는 차 */
    fun car(km: Double = 0.0, heading: Float? = 180f) = Fix(latAtKm(km), LON, heading)
}

/** 정차 후보 표 한 행. laneHeading: 그 시설로 들어가는 차로의 방위 (StopTable.laneHeading) */
data class StopRow(val c: StopCandidate, val laneHeading: Float? = null)

fun stop(
    id: String,
    name: String,
    km: Double,
    kind: StopKind = StopKind.HIGHWAY_REST_AREA,
    laneHeading: Float? = 180f,
    lonOffset: Double = 0.0,
    restroom: Boolean = true,
) = StopRow(
    StopCandidate(
        id = id, name = name, kind = kind, lat = Road.latAtKm(km), lon = Road.LON + lonOffset,
        facilities = Facilities(restroom = restroom, food = kind == StopKind.HIGHWAY_REST_AREA), parkable = true,
    ),
    laneHeading,
)

/**
 * 목록으로 된 [StopIndex]. 거르기 규칙은 StopRepository와 같다: 반경, 진행 방향 ±75도(300m 넘을 때),
 * 차로 방위가 있는 시설은 내 방향(2km 넘으면 '나 → 시설' 방위)과 90도 넘게 다르면 반대 차로로 뺀다.
 * 이름은 괄호·공백·시설 접미어를 지워 비교한다 (실제 정규화는 데이터 담당 StopNames가 한다).
 */
class FakeStopIndex(private val rows: List<StopRow>, override var loaded: Boolean = true) : StopIndex {
    var aheadCalls = 0
    val matchCalls = mutableListOf<String>()

    override fun ahead(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>): List<StopCandidate> {
        aheadCalls++
        if (!loaded) return emptyList()
        return rows.mapNotNull { r ->
            val c = r.c
            if (c.kind !in kinds) return@mapNotNull null
            val d = Geo.distanceM(lat, lon, c.lat, c.lon)
            if (d > radiusM) return@mapNotNull null
            if (heading != null && d > 300.0 && Geo.angleDiff(Geo.bearingDeg(lat, lon, c.lat, c.lon), heading.toDouble()) > 75.0) return@mapNotNull null
            if (opposite(r, lat, lon, d, heading)) return@mapNotNull null
            c.copy(straightDistanceM = d)
        }.sortedBy { it.straightDistanceM }
    }

    override fun near(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>, now: Long, limit: Int): List<StopCandidate> =
        rows.map { it.c.copy(straightDistanceM = Geo.distanceM(lat, lon, it.c.lat, it.c.lon)) }
            .filter { it.kind in kinds && it.straightDistanceM!! <= radiusM }
            .sortedBy { it.straightDistanceM }
            .take(limit)

    override fun matchByName(name: String, kind: StopKind, lat: Double, lon: Double, maxStraightM: Double, heading: Float?): List<StopCandidate> {
        matchCalls += name
        if (!loaded) return emptyList()
        val key = key(name)
        if (key.isEmpty()) return emptyList()
        return rows.mapNotNull { r ->
            val c = r.c
            if (c.kind != kind || key(c.name) != key) return@mapNotNull null
            val d = Geo.distanceM(lat, lon, c.lat, c.lon)
            if (d > maxStraightM || opposite(r, lat, lon, d, heading)) null else c.copy(straightDistanceM = d)
        }
    }

    private fun opposite(r: StopRow, lat: Double, lon: Double, d: Double, heading: Float?): Boolean {
        if (heading == null) return false
        val lane = r.laneHeading ?: return false
        val ref = if (d > 2_000.0) Geo.bearingDeg(lat, lon, r.c.lat, r.c.lon) else heading.toDouble()
        return Geo.angleDiff(lane.toDouble(), ref) > 90.0
    }

    private fun key(name: String) = name.replace(Regex("\\(.*?\\)"), "").replace(" ", "")
        .replace(Regex("(졸음쉼터|휴게소|쉼터)$"), "")
}

/** [FakeTripEstimator]가 후보마다 돌려줄 답 */
sealed interface TripAnswer {
    data class Route(val distanceM: Double, val etaS: Double = distanceM / 25.0, val directionOk: Boolean = true) : TripAnswer
    /** 내비가 답하지 않았거나 실패했다 (refine → null) */
    data object Unknown : TripAnswer
    /** 답이 오지 않는다 (취소될 때까지 멈춘다) */
    data object Hang : TripAnswer
}

/** 후보 id → 답. [onCall]로 요청 중에 차를 움직이거나 시간을 흘린다. 캐시가 없어서 부를 때마다 내비 요청 하나다 */
class FakeTripEstimator(var default: TripAnswer = TripAnswer.Unknown) : TripEstimator {
    val answers = mutableMapOf<String, TripAnswer>()
    val calls = mutableListOf<String>()
    var cleared = 0
    var onCall: (suspend (StopCandidate) -> Unit)? = null
    override var enabled = true

    override suspend fun refine(c: StopCandidate, from: Fix, permit: () -> Boolean): StopCandidate? {
        if (!permit()) return null
        calls += c.id
        onCall?.invoke(c)
        return when (val a = answers[c.id] ?: default) {
            is TripAnswer.Route -> c.copy(
                straightDistanceM = Geo.distanceM(from.lat, from.lon, c.lat, c.lon),
                routeDistanceM = a.distanceM, etaS = a.etaS, directionOk = a.directionOk,
            )
            TripAnswer.Unknown -> null
            TripAnswer.Hang -> awaitCancellation()
        }
    }

    override fun clear() {
        cleared++
    }
}

/**
 * 내비가 요청을 받고도 답하지 않을 때 포트가 하는 일: 자기 시간 초과([timeoutMs], 내비 잠금을 얻은 뒤부터) 뒤에 실패를 낸다 (NaviKit.awaitEvent).
 * 끝나지 않는 `awaitCancellation()`은 그와 다르다: 요청을 시작하지도 못하고 내비 잠금을 기다리는 것이다.
 */
suspend fun naviDoesNotAnswer(timeoutMs: Long): Nothing {
    delay(timeoutMs)
    throw IllegalStateException("requestTrip: no response in ${timeoutMs}ms")
}

/** 손으로 돌리는 실제 시간 (RouteOracle 캐시 나이·백오프) */
class ManualElapsed(var ms: Long = 1_000_000L) : ElapsedClock {
    override fun elapsedMs(): Long = ms
}

/**
 * requestTrip만 쓰는 [NaviPort]. RouteOracle 테스트용 (다른 조회는 쓰지 않는다).
 * 이름이 다른 에이전트의 FakeNaviPort와 겹치지 않게 TripNaviPort로 둔다.
 */
class TripNaviPort(var answer: suspend (lat: Double, lon: Double, name: String, timeoutMs: Long) -> TripEstimate) : NaviPort {
    var tripCalls = 0
    override suspend fun tripEstimate(lat: Double, lon: Double, name: String, timeoutMs: Long): TripEstimate {
        tripCalls++
        return answer(lat, lon, name, timeoutMs)
    }

    override suspend fun connect() = Unit
    override suspend fun routeStatus(): NaviRouteStatus = error("not used")
    override suspend fun destination(): NaviDestination = error("not used")
    override suspend fun tbt(): List<TbtItem> = error("not used")
    override suspend fun currentLocation(): Fix = error("not used")
    override suspend fun addWaypoint(lat: Double, lon: Double, name: String): Boolean = error("not used")
    override suspend fun startRoute(lat: Double, lon: Double, name: String): Boolean = error("not used")
    override suspend fun showPlaceSearch(keyword: String): Boolean = error("not used")
    override val changes: Flow<NaviChange> = emptyFlow()
}
