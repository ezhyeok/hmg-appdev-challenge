package dev.shimpyo.coach.fakes

import dev.shimpyo.coach.Fix
import dev.shimpyo.coach.Geo
import dev.shimpyo.coach.HistoryPort
import dev.shimpyo.coach.NaviChange
import dev.shimpyo.coach.NaviDestination
import dev.shimpyo.coach.NaviMode
import dev.shimpyo.coach.NaviPort
import dev.shimpyo.coach.NaviRouteStatus
import dev.shimpyo.coach.StopIndex
import dev.shimpyo.coach.StopLog
import dev.shimpyo.coach.TbtItem
import dev.shimpyo.coach.TripEstimate
import dev.shimpyo.coach.WaypointResult
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeout

/**
 * 질문·들르기·정차 기록 테스트의 가짜들 (prompt 담당). 다른 담당의 가짜와 이름이 겹치지 않게 PromptFakes 안에 둔다.
 */
object PromptFakes {

    fun stop(
        id: String,
        name: String = id,
        lat: Double = 36.7,
        lon: Double = 127.4,
        kind: StopKind = StopKind.HIGHWAY_REST_AREA,
        routeDistanceM: Double? = null,
        straightDistanceM: Double? = null,
    ) = StopCandidate(
        id = id, name = name, kind = kind, lat = lat, lon = lon, facilities = Facilities(), parkable = true,
        routeDistanceM = routeDistanceM, straightDistanceM = straightDistanceM,
    )

    /** stops.json 대신. 덧붙인 기록과 지우기 횟수를 남긴다 */
    class History(initial: List<StopLog> = emptyList()) : HistoryPort {
        val saved = initial.toMutableList()
        val appended = mutableListOf<StopLog>()
        var clears = 0
        /** true면 append가 실패한다 (저장소 오류) */
        var failAppend = false

        override fun load(): List<StopLog> = saved.toList()

        override fun append(log: StopLog): List<StopLog> {
            if (failAppend) throw java.io.IOException("disk full")
            appended += log
            saved += log
            return saved.toList()
        }

        override fun clear() {
            clears++
            saved.clear()
        }
    }

    /** 목록 기반 정차 후보 표. near는 직선거리로 반경 안을 가까운 순으로 준다 */
    class Stops(private val all: List<StopCandidate>) : StopIndex {
        val nearRadii = mutableListOf<Double>()
        override val loaded = true

        override fun ahead(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>): List<StopCandidate> =
            near(lat, lon, heading, radiusM, kinds, 0L, Int.MAX_VALUE)

        override fun near(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>, now: Long, limit: Int): List<StopCandidate> {
            nearRadii += radiusM
            return all.filter { it.kind in kinds }
                .map { it.copy(straightDistanceM = Geo.distanceM(lat, lon, it.lat, it.lon)) }
                .filter { it.straightDistanceM!! <= radiusM }
                .sortedBy { it.straightDistanceM }
                .take(limit)
        }

        override fun matchByName(name: String, kind: StopKind, lat: Double, lon: Double, maxStraightM: Double, heading: Float?): List<StopCandidate> =
            emptyList()
    }

    /**
     * 들르기 실행(GoToRunner)용 내비. 경로 상태 조회 결과와 명령 결과를 차례로 정해 둔다.
     * 각 목록의 값: NaviMode/Boolean = 그 값을 돌려준다, Throwable = 던진다, HANG = 끝나지 않는다, PORT_TIMEOUT = 포트 자체 시간 초과.
     * 목록이 비면 마지막 값을 반복한다.
     */
    class Navi(
        modes: List<Any> = listOf(NaviMode.SAFE_GUIDING),
        commands: List<Any> = listOf(true),
    ) : NaviPort {
        private val modes = ArrayDeque(modes)
        private val commands = ArrayDeque(commands)
        /** 받은 호출 (순서대로): "routeStatus", "addWaypoint:이름", "startRoute:이름", "search:이름" */
        val calls = mutableListOf<String>()

        private fun next(q: ArrayDeque<Any>): Any = if (q.size > 1) q.removeFirst() else q.first()

        private suspend fun raw(label: String): Any {
            calls += label
            return when (val v = next(commands)) {
                HANG -> awaitCancellation()
                PORT_TIMEOUT -> withTimeout(1) { awaitCancellation() }
                is Throwable -> throw v
                else -> v
            }
        }

        /** 참·거짓 명령. 목록에 WaypointResult를 넣었으면 ADDED만 참이다 */
        private suspend fun command(label: String): Boolean = when (val v = raw(label)) {
            is WaypointResult -> v == WaypointResult.ADDED
            else -> v as Boolean
        }

        override suspend fun connect() {}

        override suspend fun routeStatus(): NaviRouteStatus {
            calls += "routeStatus"
            return when (val v = next(modes)) {
                HANG -> awaitCancellation()
                is Throwable -> throw v
                else -> NaviRouteStatus(v as NaviMode)
            }
        }

        /** 목록에 WaypointResult를 넣으면 그대로, 참·거짓이면 ADDED·NOT_CONFIRMED */
        override suspend fun addWaypoint(lat: Double, lon: Double, name: String): WaypointResult =
            when (val v = raw("addWaypoint:$name")) {
                is WaypointResult -> v
                true -> WaypointResult.ADDED
                else -> WaypointResult.NOT_CONFIRMED
            }
        override suspend fun startRoute(lat: Double, lon: Double, name: String) = command("startRoute:$name")
        override suspend fun showPlaceSearch(keyword: String) = command("search:$keyword")

        override suspend fun destination(): NaviDestination = error("not used")
        override suspend fun tbt(): List<TbtItem> = error("not used")
        override suspend fun currentLocation(): Fix = error("not used")
        override suspend fun tripEstimate(lat: Double, lon: Double, name: String, timeoutMs: Long): TripEstimate = error("not used")
        override val changes: Flow<NaviChange> = emptyFlow()

        companion object {
            val HANG = Any()
            /** 포트 안에서 자기 withTimeout이 끝난다 (TimeoutCancellationException) */
            val PORT_TIMEOUT = Any()
        }
    }
}
