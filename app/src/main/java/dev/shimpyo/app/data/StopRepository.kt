package dev.shimpyo.app.data

import android.content.Context
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 앱에 들어 있는 정차 후보 데이터(assets/data)를 읽어 격자 색인으로 찾는다.
 * 데이터 형식은 tools/data/README.md. 로딩은 한 번만 하고 메모리에 둔다.
 */
class StopRepository(private val context: Context) {
    private val loadLock = Mutex()
    @Volatile private var index: GridIndex? = null

    val count: Int get() = index?.size ?: 0

    suspend fun ensureLoaded(): Int = loadLock.withLock {
        index?.let { return it.size }
        val all = withContext(Dispatchers.IO) { StopAssets.loadAll(context) }
        index = GridIndex(all)
        all.size
    }

    /**
     * 진행 방향 앞쪽 후보. [heading]이 없으면 방향 필터 없이 반경만 본다.
     * 결과에는 현재 위치 기준 직선거리를 채워 둔다.
     */
    fun ahead(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>, maxAngle: Double = 75.0): List<StopCandidate> {
        val idx = index ?: return emptyList()
        return idx.query(lat, lon, radiusM)
            .filter { it.kind in kinds }
            .mapNotNull { c ->
                val d = Geo.distanceM(lat, lon, c.lat, c.lon)
                if (d > radiusM) return@mapNotNull null
                if (heading != null && d > 300.0) {
                    val b = Geo.bearingDeg(lat, lon, c.lat, c.lon)
                    if (Geo.angleDiff(b, heading.toDouble()) > maxAngle) return@mapNotNull null
                }
                c.copy(straightDistanceM = d)
            }
            .sortedBy { it.straightDistanceM }
    }

    /** 반경 안 모든 후보 (급해 모드). 진행 방향 뒤쪽이면 directionOk = false로 표시한다 */
    fun near(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>, now: Long = System.currentTimeMillis()): List<StopCandidate> {
        val idx = index ?: return emptyList()
        return idx.query(lat, lon, radiusM)
            .filter { it.kind in kinds }
            .mapNotNull { c ->
                val d = Geo.distanceM(lat, lon, c.lat, c.lon)
                if (d > radiusM) return@mapNotNull null
                val behind = heading != null && d > 150.0 &&
                    Geo.angleDiff(Geo.bearingDeg(lat, lon, c.lat, c.lon), heading.toDouble()) > 100.0
                c.copy(straightDistanceM = d, directionOk = if (heading == null) null else !behind, openNow = c.openNow ?: StopAssets.openNow(c.id, now))
            }
            .sortedBy { it.straightDistanceM }
    }

    /**
     * TBT에서 읽은 이름과 맞는 후보. 이름은 정규화해서 비교하고(방향 표기·접미어 제거),
     * 현재 위치에서 [maxStraightM] 안에 있는 것만 돌려준다 (양방향이 다 나올 수 있다).
     */
    fun matchByName(name: String, kind: StopKind, lat: Double, lon: Double, maxStraightM: Double): List<StopCandidate> {
        val idx = index ?: return emptyList()
        val key = dev.shimpyo.app.coach.RouteOpportunities.normalizeName(name)
        if (key.isEmpty()) return emptyList()
        return idx.query(lat, lon, maxStraightM)
            .filter { it.kind == kind && dev.shimpyo.app.coach.RouteOpportunities.normalizeName(it.name) == key }
            .mapNotNull { c ->
                val d = Geo.distanceM(lat, lon, c.lat, c.lon)
                if (d > maxStraightM) null else c.copy(straightDistanceM = d)
            }
    }

    fun byId(id: String): StopCandidate? = index?.byId(id)
}

/** 위경도 격자(약 0.05도 ≈ 5km) 색인 */
private class GridIndex(all: List<StopCandidate>) {
    private val cells = HashMap<Long, MutableList<StopCandidate>>()
    private val ids = HashMap<String, StopCandidate>(all.size)
    val size = all.size

    init {
        all.forEach { c ->
            cells.getOrPut(key(cell(c.lat), cell(c.lon))) { ArrayList() }.add(c)
            ids[c.id] = c
        }
    }

    fun byId(id: String) = ids[id]

    fun query(lat: Double, lon: Double, radiusM: Double): List<StopCandidate> {
        val dLat = radiusM / 111_000.0
        val dLon = radiusM / (111_000.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        val out = ArrayList<StopCandidate>()
        for (y in cell(lat - dLat)..cell(lat + dLat)) {
            for (x in cell(lon - dLon)..cell(lon + dLon)) {
                cells[key(y, x)]?.let(out::addAll)
            }
        }
        return out
    }

    private fun cell(v: Double) = Math.floor(v / CELL).toInt()
    private fun key(y: Int, x: Int) = (y.toLong() shl 32) or (x.toLong() and 0xffffffffL)

    private companion object {
        const val CELL = 0.05
    }
}
