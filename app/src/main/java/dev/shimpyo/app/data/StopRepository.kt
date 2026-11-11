package dev.shimpyo.app.data

import android.content.Context
import dev.shimpyo.app.coach.RouteOpportunities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.stops.StopTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 앱에 들어 있는 정차 후보 표(assets/data/stops.bin, [StopAssets])를 격자 색인으로 찾는다. 로딩은 한 번만 하고 메모리에 둔다.
 *
 * 색인은 행 번호(IntArray)만 들고, 거리·방향 거르기는 표의 원시 배열로 한 뒤 통과한 행만 StopCandidate로 만든다.
 * 고속도로 휴게소·졸음쉼터(532곳) 전용 색인을 따로 둬서, 60km 반경을 보는 [ahead]가 공중화장실 6만 곳을
 * 훑지 않는다. 각 색인은 예전 색인과 같은 칸 순서·행 순서로 돌기 때문에 결과(거리가 같은 후보의 순서까지)가 같다.
 */
class StopRepository(private val context: Context) {
    private val loadLock = Mutex()
    @Volatile private var table: StopTable? = null
    @Volatile private var all: GridIndex? = null
    @Volatile private var highway: GridIndex? = null
    /** 고속도로 시설의 정규화한 이름 (TBT 이름 맞추기에서 매번 정규식을 돌리지 않게) */
    private var normalizedNames: Array<String?> = emptyArray()
    @Volatile private var ids: Map<String, Int>? = null

    val count: Int get() = table?.size ?: 0

    /** 표를 읽고 색인을 만든다 (에뮬레이터에서 1초 안팎). 행 수를 돌려준다 */
    suspend fun ensureLoaded(): Int = loadLock.withLock {
        table?.let { return it.size }
        withContext(Dispatchers.IO) {
            val t = StopAssets.loadTable(context) ?: return@withContext 0
            all = GridIndex(t) { true }
            highway = GridIndex(t) { t.kind(it) in HIGHWAY_KINDS }
            normalizedNames = arrayOfNulls(t.size)
            table = t
            t.size
        }
    }

    /**
     * 진행 방향 앞쪽 후보. [heading]이 없으면 방향 필터 없이 반경만 본다.
     * 결과에는 현재 위치 기준 직선거리를 채워 둔다.
     */
    fun ahead(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>, maxAngle: Double = 75.0): List<StopCandidate> {
        val t = table ?: return emptyList()
        val out = ArrayList<StopCandidate>()
        indexFor(kinds).forEachIn(lat, lon, radiusM) { i ->
            if (t.kind(i) !in kinds) return@forEachIn
            val cLat = t.lat(i)
            val cLon = t.lon(i)
            val d = Geo.distanceM(lat, lon, cLat, cLon)
            if (d > radiusM) return@forEachIn
            if (heading != null && d > 300.0) {
                val b = Geo.bearingDeg(lat, lon, cLat, cLon)
                if (Geo.angleDiff(b, heading.toDouble()) > maxAngle) return@forEachIn
            }
            if (oppositeLane(t, i, lat, lon, d, heading)) return@forEachIn
            out += t.candidate(i).copy(straightDistanceM = d)
        }
        out.sortBy { it.straightDistanceM }
        return out
    }

    /**
     * 반경 안 후보를 가까운 순으로 [limit]곳까지 (급해 모드). 진행 방향 뒤쪽이면 directionOk = false로 표시한다.
     * 거르기·정렬은 행 번호와 거리로만 하고 마지막에 [limit]곳만 StopCandidate로 만든다(강남역 부근 반경 5km에서
     * 예전에는 841곳을 만들어 15곳을 썼다). 정렬이 안정적이라 결과는 예전과 같다.
     */
    fun near(
        lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>,
        now: Long = System.currentTimeMillis(), limit: Int = Int.MAX_VALUE,
    ): List<StopCandidate> {
        val t = table ?: return emptyList()
        val hits = ArrayList<Hit>()
        indexFor(kinds).forEachIn(lat, lon, radiusM) { i ->
            if (t.kind(i) !in kinds) return@forEachIn
            val cLat = t.lat(i)
            val cLon = t.lon(i)
            val d = Geo.distanceM(lat, lon, cLat, cLon)
            if (d > radiusM) return@forEachIn
            val behind = heading != null && d > 150.0 &&
                Geo.angleDiff(Geo.bearingDeg(lat, lon, cLat, cLon), heading.toDouble()) > 100.0
            // 고속도로 반대 차로의 휴게소·졸음쉼터는 유턴 없이는 못 간다
            if (oppositeLane(t, i, lat, lon, d, heading)) return@forEachIn
            hits += Hit(i, d, behind)
        }
        hits.sortBy { it.distanceM }
        val n = minOf(limit, hits.size)
        val out = ArrayList<StopCandidate>(n)
        for (k in 0 until n) {
            val h = hits[k]
            val c = t.candidate(h.row)
            out += c.copy(straightDistanceM = h.distanceM, directionOk = if (heading == null) null else !h.behind, openNow = c.openNow ?: t.openNow(h.row, now))
        }
        return out
    }

    private class Hit(val row: Int, val distanceM: Double, val behind: Boolean)

    /**
     * TBT에서 읽은 이름과 맞는 후보. 이름은 정규화해서 비교하고(방향 표기·접미어 제거),
     * 현재 위치에서 [maxStraightM] 안에 있는 것만 돌려준다 (양방향이 다 나올 수 있다).
     */
    fun matchByName(name: String, kind: StopKind, lat: Double, lon: Double, maxStraightM: Double): List<StopCandidate> {
        val t = table ?: return emptyList()
        val key = RouteOpportunities.normalizeName(name)
        if (key.isEmpty()) return emptyList()
        val out = ArrayList<StopCandidate>()
        indexFor(setOf(kind)).forEachIn(lat, lon, maxStraightM) { i ->
            if (t.kind(i) != kind || normalizedName(t, i) != key) return@forEachIn
            val d = Geo.distanceM(lat, lon, t.lat(i), t.lon(i))
            if (d <= maxStraightM) out += t.candidate(i).copy(straightDistanceM = d)
        }
        return out
    }

    fun byId(id: String): StopCandidate? {
        val t = table ?: return null
        val map = ids ?: HashMap<String, Int>(t.size * 2).also { m -> for (i in 0 until t.size) m[t.id(i)] = i; ids = m }
        return map[id]?.let(t::candidate)
    }

    private fun indexFor(kinds: Set<StopKind>): GridIndex =
        (if (kinds.all { it in HIGHWAY_KINDS }) highway else all) ?: error("not loaded")

    private fun normalizedName(t: StopTable, i: Int): String {
        if (t.kind(i) !in HIGHWAY_KINDS) return RouteOpportunities.normalizeName(t.name(i))
        return normalizedNames[i] ?: RouteOpportunities.normalizeName(t.name(i)).also { normalizedNames[i] = it }
    }

    /**
     * 차로 방위가 있는 시설(휴게소·졸음쉼터)이 내 진행 방향과 90도 넘게 다르면 반대 차로다.
     * 2km보다 멀면 내 순간 방향 대신 '나 → 시설' 방위와 비교한다 (길이 굽어도 앞쪽 시설을 잘못 빼지 않게)
     */
    private fun oppositeLane(t: StopTable, i: Int, lat: Double, lon: Double, d: Double, heading: Float?): Boolean {
        if (heading == null) return false
        val lane = t.laneHeading(i) ?: return false
        val ref = if (d > 2_000.0) Geo.bearingDeg(lat, lon, t.lat(i), t.lon(i)) else heading.toDouble()
        return Geo.angleDiff(lane.toDouble(), ref) > 90.0
    }

    private companion object {
        val HIGHWAY_KINDS = setOf(StopKind.HIGHWAY_REST_AREA, StopKind.DROWSY_SHELTER)
    }
}

/**
 * 위경도 격자(0.05도 ≈ 5km) 색인. 칸마다 행 번호를 표 순서대로 든다(예전 색인과 같은 방문 순서).
 * [include]가 참인 행만 넣는다.
 */
private class GridIndex(table: StopTable, include: (Int) -> Boolean) {
    private val cells: HashMap<Long, IntArray>

    init {
        val build = HashMap<Long, IntList>()
        for (i in 0 until table.size) {
            if (!include(i)) continue
            build.getOrPut(key(cell(table.lat(i)), cell(table.lon(i)))) { IntList() }.add(i)
        }
        cells = HashMap<Long, IntArray>(build.size * 2).also { m -> build.forEach { (k, v) -> m[k] = v.toArray() } }
    }

    /** 반경을 덮는 칸들의 행 번호를 칸 순서(위도 → 경도)대로 넘긴다. 거리 거르기는 부르는 쪽이 한다 */
    inline fun forEachIn(lat: Double, lon: Double, radiusM: Double, action: (Int) -> Unit) {
        val dLat = radiusM / 111_000.0
        val dLon = radiusM / (111_000.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        for (y in cell(lat - dLat)..cell(lat + dLat)) {
            for (x in cell(lon - dLon)..cell(lon + dLon)) {
                val rows = cells[key(y, x)] ?: continue
                for (i in rows) action(i)
            }
        }
    }

    fun cell(v: Double) = Math.floor(v / CELL).toInt()
    fun key(y: Int, x: Int) = (y.toLong() shl 32) or (x.toLong() and 0xffffffffL)

    private class IntList {
        private var a = IntArray(8)
        private var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray(): IntArray = a.copyOf(n)
    }

    companion object {
        const val CELL = 0.05
    }
}
