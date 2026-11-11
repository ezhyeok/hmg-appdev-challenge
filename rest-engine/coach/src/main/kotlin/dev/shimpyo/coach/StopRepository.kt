package dev.shimpyo.coach

import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.stops.MotorwayIndex
import dev.shimpyo.engine.stops.StopTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.cos

/**
 * 앱에 들어 있는 정차 후보 표(assets/data/stops.bin, 앱의 StopAssets가 읽는다)를 격자 색인으로 찾는다. 로딩은 한 번만 하고 메모리에 둔다.
 *
 * 색인은 행 번호(IntArray)만 들고, 거리·방향 거르기는 표의 원시 배열로 한 뒤 통과한 행만 StopCandidate로 만든다.
 * 고속도로 휴게소·졸음쉼터(532곳) 전용 색인을 따로 둬서, 60km 반경을 보는 [ahead]가 공중화장실 6만 곳을 훑지 않는다.
 *
 * 방향 규칙 (docs/design/coach-layer/data.md):
 * - [heading]은 '움직이는 차의 진행 방위'다. 서 있거나 모르면 null을 넘긴다(부르는 쪽 책임: 기어 P·저속이면 null).
 *   null이면 방향 필터를 하나도 쓰지 않는다.
 * - 반대 차로: 차로 방위가 있는 시설이 진행 방향과 90도 넘게 다르면 뺀다(2km보다 멀면 '나 → 시설' 방위와 비교).
 * - 시설 안: 차가 고속도로 차로 위가 아니고(지도 맞춤 [MotorwayIndex]) 휴게소·졸음쉼터 [AT_FACILITY_M] 안이면 그 시설의
 *   반대 차로·뒤쪽 검사를 하지 않는다. 휴게소 주차장에 반대로 세운 차가 자기가 있는 휴게소를 잃지 않게. 반경만으로 풀면 안 된다: 방향별 시설의
 *   93%는 반대 차로가 시설 점에서 150m 안(중앙값 51m)을 지나서, 지나가는 차에게 건너편 시설이 다시 나온다.
 * - 뒤쪽: 움직이는 차 기준으로 진행 방향 축을 따라 [BEHIND_TOLERANCE_M]보다 뒤에 있는 시설은 [ahead]에서 뺀다
 *   (예전에는 300m 안이면 뒤에 있어도 남겼다. 지나친 휴게소는 진출로도 지났다. 데이터 감사 #11).
 */
class StopRepository(
    /** 표 읽기 (앱: assets의 stops.bin을 메모리 매핑). 실패하면 null */
    private val loadTable: () -> StopTable?,
) : StopIndex {
    private val loadLock = Mutex()

    /** 읽은 표와 그 위의 색인. 통째로 한 번에 바꿔 끼우므로 읽는 쪽은 늘 일관된 묶음을 본다 */
    private class Loaded(
        val table: StopTable,
        val all: GridIndex,
        val highway: GridIndex,
        val motorways: MotorwayIndex,
        /** 고속도로 시설의 이름 키 (StopNames.key, 없으면 null). 이름 맞추기에서 매번 정규식을 돌리지 않게 */
        val keys: Array<String?>,
    )

    @Volatile private var state: Loaded? = null
    @Volatile private var ids: Map<String, Int>? = null

    val count: Int get() = state?.table?.size ?: 0
    override val loaded: Boolean get() = state != null

    /** 표를 읽고 색인을 만든다 (에뮬레이터에서 1초 안팎). 행 수를 돌려준다 */
    suspend fun ensureLoaded(): Int = loadLock.withLock {
        state?.let { return it.table.size }
        withContext(Dispatchers.IO) {
            val t = loadTable() ?: return@withContext 0
            val keys = arrayOfNulls<String>(t.size)
            for (i in 0 until t.size) if (t.kind(i) in HIGHWAY_KINDS) keys[i] = StopNames.key(t.name(i))
            state = Loaded(t, GridIndex(t) { true }, GridIndex(t) { t.kind(it) in HIGHWAY_KINDS }, MotorwayIndex(t), keys)
            t.size
        }
    }

    /** 지금 달리는 고속도로 이름 (지도 맞춤). 고속도로 위가 아니거나 방위를 모르면 null */
    fun currentRoad(lat: Double, lon: Double, heading: Float?): String? {
        val l = state ?: return null
        val r = roadAt(l, lat, lon, heading)
        return if (r >= 0) l.table.roadName(r) else null
    }

    /**
     * 진행 방향 앞쪽 후보. [heading]이 없으면 방향 필터 없이 반경만 본다. 결과에는 현재 위치 기준 직선거리를 채워 둔다.
     *
     * 순서: 지금 달리는 고속도로(지도 맞춤)의 시설이 먼저, 그다음 다른 길의 시설, 각각 가까운 순. 부르는 쪽은 앞에서 몇 곳만
     * 내비로 보정하므로(CandidatePolicy.oracleMax) 순서가 곧 선택이다. 예전에는 직선거리 순이라 경부선 기흥에서 남쪽으로 갈 때
     * 앞 6곳 중 4곳이 다른 고속도로였고 24km 앞 안성휴게소가 잘렸다(데이터 감사 #3). 고속도로 위가 아니면 거리 순 그대로다.
     */
    override fun ahead(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>): List<StopCandidate> =
        ahead(lat, lon, heading, radiusM, kinds, maxAngle = 75.0)

    fun ahead(lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>, maxAngle: Double): List<StopCandidate> {
        val l = state ?: return emptyList()
        val t = l.table
        val road = roadAt(l, lat, lon, heading)
        val h = heading?.toDouble()
        val kx = cos(Math.toRadians(lat))
        val hits = ArrayList<Hit>()
        indexFor(l, kinds).forEachIn(lat, lon, radiusM) { i ->
            if (t.kind(i) !in kinds) return@forEachIn
            val cLat = t.lat(i)
            val cLon = t.lon(i)
            val d = Geo.distanceM(lat, lon, cLat, cLon)
            if (d > radiusM) return@forEachIn
            if (h != null && !(road < 0 && d <= AT_FACILITY_M && t.kind(i) in HIGHWAY_KINDS)) {
                val b = Geo.bearingDeg(lat, lon, cLat, cLon)
                if (d > CONE_FREE_M) {
                    if (Geo.angleDiff(b, h) > maxAngle) return@forEachIn
                } else if (alongTrackM(lat, lon, cLat, cLon, kx, h) < -BEHIND_TOLERANCE_M) {
                    return@forEachIn
                }
                if (oppositeLane(t, i, d, b, h)) return@forEachIn
            }
            hits += Hit(i, d, behind = false, onRoad = road >= 0 && t.roadId(i) == road)
        }
        // 같은 길 먼저, 그 안에서 가까운 순 (정렬은 안정적이라 거리가 같으면 색인 순서 그대로)
        hits.sortWith(compareBy<Hit>({ !it.onRoad }, { it.distanceM }))
        return hits.map { t.candidate(it.row).copy(straightDistanceM = it.distanceM) }
    }

    /**
     * 반경 안 후보를 가까운 순으로 [limit]곳까지 (급해 모드). 진행 방향 뒤쪽이면 directionOk = false로 표시한다.
     * 거르기·정렬은 행 번호와 거리로만 하고 마지막에 [limit]곳만 StopCandidate로 만든다(강남역 부근 반경 5km에서
     * 예전에는 841곳을 만들어 15곳을 썼다). 정렬이 안정적이라 거리가 같은 후보의 순서도 늘 같다.
     */
    override fun near(
        lat: Double, lon: Double, heading: Float?, radiusM: Double, kinds: Set<StopKind>,
        now: Long, limit: Int,
    ): List<StopCandidate> {
        val l = state ?: return emptyList()
        val t = l.table
        val h = heading?.toDouble()
        // 시설 안 판정에만 필요하다: 휴게소·졸음쉼터가 250m 안에 있을 때만 지도 맞춤을 한다
        val road by lazy(LazyThreadSafetyMode.NONE) { roadAt(l, lat, lon, heading) }
        val hits = ArrayList<Hit>()
        indexFor(l, kinds).forEachIn(lat, lon, radiusM) { i ->
            if (t.kind(i) !in kinds) return@forEachIn
            val cLat = t.lat(i)
            val cLon = t.lon(i)
            val d = Geo.distanceM(lat, lon, cLat, cLon)
            if (d > radiusM) return@forEachIn
            var behind = false
            if (h != null && !(d <= AT_FACILITY_M && t.kind(i) in HIGHWAY_KINDS && road < 0)) {
                val b = Geo.bearingDeg(lat, lon, cLat, cLon)
                behind = d > 150.0 && Geo.angleDiff(b, h) > 100.0
                // 고속도로 반대 차로의 휴게소·졸음쉼터는 유턴 없이는 못 간다
                if (oppositeLane(t, i, d, b, h)) return@forEachIn
            }
            hits += Hit(i, d, behind, onRoad = false)
        }
        hits.sortBy { it.distanceM }
        val n = minOf(limit, hits.size)
        val out = ArrayList<StopCandidate>(n)
        for (k in 0 until n) {
            val hit = hits[k]
            val c = t.candidate(hit.row)
            out += c.copy(straightDistanceM = hit.distanceM, directionOk = if (heading == null) null else !hit.behind, openNow = c.openNow ?: t.openNow(hit.row, now))
        }
        return out
    }

    private class Hit(val row: Int, val distanceM: Double, val behind: Boolean, val onRoad: Boolean)

    /**
     * TBT에서 읽은 이름과 맞는 후보. 현재 위치에서 [maxStraightM] 안에 있는 것만, 가까운 순으로.
     *
     * - 이름은 [StopNames.key]로 비교한다. 구별되는 부분이 없는 이름("졸음쉼터")은 아무것도 가리키지 않는다(빈 목록).
     * - 찾는 종류는 이름의 시설어가 정한다([StopNames.kindFor]): 쉼터가 SERVICEAREA로 와도 같은 이름의 휴게소와 맞추지 않는다.
     * - 키가 똑같은 행이 없으면, 데이터 키가 TBT 키의 앞부분인 행(2글자 이상, 가장 긴 것)을 쓴다: "서울만남의광장휴게소" → 서울만남,
     *   "덕평자연휴게소" → 덕평. 반대(TBT가 더 짧은 것)는 쓰지 않는다: "안성"이 "안성맞춤"과 맞으면 안 된다.
     * - [heading]을 주면 반대 차로 행을 뺀다. 같은 이름이 양방향에 있는 곳(휴게소 89곳, 졸음쉼터 97곳)이 내비 요청 없이 풀린다
     *   (데이터 감사 #5). 남는 행이 없으면 빈 목록이다(반대 차로 행을 대신 주지 않는다).
     */
    override fun matchByName(name: String, kind: StopKind, lat: Double, lon: Double, maxStraightM: Double, heading: Float?): List<StopCandidate> {
        val l = state ?: return emptyList()
        val t = l.table
        val key = StopNames.key(name) ?: return emptyList()
        val k = StopNames.kindFor(name, kind)
        val h = heading?.toDouble()
        val road by lazy(LazyThreadSafetyMode.NONE) { roadAt(l, lat, lon, heading) }
        val exact = ArrayList<Hit>()
        val prefix = ArrayList<Pair<Hit, Int>>()
        indexFor(l, setOf(k)).forEachIn(lat, lon, maxStraightM) { i ->
            if (t.kind(i) != k) return@forEachIn
            val ki = (if (t.kind(i) in HIGHWAY_KINDS) l.keys[i] else StopNames.key(t.name(i))) ?: return@forEachIn
            val isExact = ki == key
            if (!isExact && !(ki.length >= 2 && key.startsWith(ki))) return@forEachIn
            val d = Geo.distanceM(lat, lon, t.lat(i), t.lon(i))
            if (d > maxStraightM) return@forEachIn
            if (h != null && !(d <= AT_FACILITY_M && road < 0) && oppositeLane(t, i, d, Geo.bearingDeg(lat, lon, t.lat(i), t.lon(i)), h)) return@forEachIn
            val hit = Hit(i, d, behind = false, onRoad = false)
            if (isExact) exact += hit else prefix += hit to ki.length
        }
        val chosen = exact.ifEmpty {
            val longest = prefix.maxOfOrNull { it.second } ?: return emptyList()
            prefix.filter { it.second == longest }.map { it.first }
        }
        return chosen.sortedBy { it.distanceM }.map { t.candidate(it.row).copy(straightDistanceM = it.distanceM) }
    }

    fun byId(id: String): StopCandidate? {
        val t = state?.table ?: return null
        val map = ids ?: HashMap<String, Int>(t.size * 2).also { m -> for (i in 0 until t.size) m[t.id(i)] = i; ids = m }
        return map[id]?.let(t::candidate)
    }

    private fun roadAt(l: Loaded, lat: Double, lon: Double, heading: Float?): Int =
        if (heading == null) -1 else l.motorways.roadAt(lat, lon, heading.toDouble())

    private fun indexFor(l: Loaded, kinds: Set<StopKind>): GridIndex =
        if (kinds.all { it in HIGHWAY_KINDS }) l.highway else l.all

    /**
     * 차로 방위가 있는 시설(휴게소·졸음쉼터)이 내 진행 방향과 90도 넘게 다르면 반대 차로다.
     * 2km보다 멀면 내 순간 방향 대신 '나 → 시설' 방위([bearing])와 비교한다 (길이 굽어도 앞쪽 시설을 잘못 빼지 않게)
     */
    private fun oppositeLane(t: StopTable, i: Int, d: Double, bearing: Double, heading: Double): Boolean {
        val lane = t.laneHeading(i) ?: return false
        val ref = if (d > 2_000.0) bearing else heading
        return Geo.angleDiff(lane.toDouble(), ref) > 90.0
    }

    /** 진행 방향 축을 따라 시설이 앞(+)/뒤(-)로 얼마나 떨어졌는지 (m, 국지 평면) */
    private fun alongTrackM(lat: Double, lon: Double, cLat: Double, cLon: Double, kx: Double, headingDeg: Double): Double {
        val dx = (cLon - lon) * kx * M_PER_DEG
        val dy = (cLat - lat) * M_PER_DEG
        val r = Math.toRadians(headingDeg)
        return dx * kotlin.math.sin(r) + dy * kotlin.math.cos(r)
    }

    companion object {
        private val HIGHWAY_KINDS = setOf(StopKind.HIGHWAY_REST_AREA, StopKind.DROWSY_SHELTER)
        private const val M_PER_DEG = 111_195.0
        /** 이 거리 안은 방위가 흔들리므로 진행 방향 원뿔 대신 앞뒤만 본다 */
        const val CONE_FREE_M = 300.0
        /** 시설 점 좌표 오차(한국도로공사↔OSM 중앙값 40m대, p90 100~170m)를 넘겨 뒤에 있으면 지나친 것으로 본다 */
        const val BEHIND_TOLERANCE_M = 50.0
        /** 고속도로 차로 위가 아닌 차가 이 안에 있으면 그 시설 안(주차장·진입로)으로 본다 */
        const val AT_FACILITY_M = 250.0
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
