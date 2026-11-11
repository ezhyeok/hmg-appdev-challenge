package dev.shimpyo.coach

import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.stops.MotorwayLine
import dev.shimpyo.engine.stops.StopRecord
import dev.shimpyo.engine.stops.StopTable
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 정차 후보 조회 규칙 (데이터 감사 #1 #3 #5 #10 #11). 규칙은 작은 가상 고속도로로, 실제 효과는 앱의 stops.bin과
 * OSM 차로 위 위치(tools/data/replay_positions.py)로 확인한다. 예전 조회는 [Legacy]로 옮겨 두고 전후를 비교한다.
 */
class StopRepositoryTest {

    // ------------------------------------------------------------ 가상 고속도로 (남북 직선, 우측통행)
    // 남행 차로 경도 126.9998, 북행 127.0002 (약 36m 간격). 남행 시설은 남행 차로 오른쪽(서쪽), 북행 시설은 동쪽.

    private fun rec(id: String, name: String, kind: StopKind, lat: Double, lon: Double, lane: Int?, road: String?) = StopRecord(
        StopCandidate(id, name, kind, lat, lon, Facilities(restroom = true), parkable = true, openNow = true),
        laneHeading = lane, hours = null, road = road,
    )

    private val synthetic: StopTable by lazy {
        val r = "테스트고속도로"
        StopTable.encode(
            listOf(
                rec("okS", "옥산휴게소", StopKind.HIGHWAY_REST_AREA, 37.0, 126.9990, 180, r),
                rec("okN", "옥산휴게소", StopKind.HIGHWAY_REST_AREA, 37.003, 127.0010, 0, r),
                rec("okShelter", "옥산졸음쉼터", StopKind.DROWSY_SHELTER, 36.95, 126.9993, 180, r),
                rec("noname", "졸음쉼터", StopKind.DROWSY_SHELTER, 36.97, 126.9993, 180, r),
                rec("mannam", "서울만남휴게소", StopKind.HIGHWAY_REST_AREA, 37.05, 126.9990, 180, r),
                rec("anseong", "안성맞춤휴게소", StopKind.HIGHWAY_REST_AREA, 36.93, 126.9990, 180, r),
                rec("other", "다른휴게소", StopKind.HIGHWAY_REST_AREA, 37.065, 127.012, 180, "다른고속도로"),
            ),
            listOf(
                MotorwayLine(r, false, doubleArrayOf(37.20, 126.9998, 37.00, 126.9998, 36.80, 126.9998)),
                MotorwayLine(r, false, doubleArrayOf(36.80, 127.0002, 37.00, 127.0002, 37.20, 127.0002)),
                MotorwayLine("다른고속도로", false, doubleArrayOf(37.20, 127.0125, 36.80, 127.0125)),
            ),
        ).let(StopTable::decode)
    }

    private fun repo(t: StopTable) = StopRepository { t }.also { runBlocking { it.ensureLoaded() } }
    private val syn by lazy { repo(synthetic) }
    private val highway = setOf(StopKind.HIGHWAY_REST_AREA, StopKind.DROWSY_SHELTER)
    private fun List<StopCandidate>.ids() = map { it.id }

    @Test
    fun aheadRanksTheExpresswayUnderTheCarFirst() {
        // 남행 차로 위. '다른휴게소'가 직선으로는 가장 가깝지만(약 1.8km) 다른 고속도로라 같은 길 시설 뒤로 간다
        assertEquals("테스트고속도로", syn.currentRoad(37.08, 126.9998, 180f))
        val ids = syn.ahead(37.08, 126.9998, 180f, 60_000.0, highway).ids()
        assertEquals(listOf("mannam", "okS", "noname", "okShelter", "anseong", "other"), ids)
    }

    @Test
    fun offTheExpresswayTheOrderIsDistance() {
        // 고속도로에서 900m 떨어진 국도: 어느 고속도로인지 정하지 않는다 → 거리 순 (예전과 같다)
        assertEquals(null, syn.currentRoad(37.08, 126.99, 180f))
        val ahead = syn.ahead(37.08, 126.99, 180f, 60_000.0, highway)
        assertEquals(ahead.sortedBy { it.straightDistanceM }.ids(), ahead.ids())
        assertEquals(null, syn.currentRoad(37.08, 126.9998, null), "방위를 모르면 정하지 않는다")
    }

    @Test
    fun passingTheOppositeTwinDoesNotOfferIt() {
        // 북행 차로에서 남행 옥산휴게소 옆(36~72m)을 지나간다: 시설 반경 안이어도 차로 위의 차이므로 반대 차로 검사를 한다
        val ahead = syn.ahead(37.0, 127.0002, 0f, 60_000.0, highway).ids()
        assertFalse("okS" in ahead, ahead.toString())
        assertTrue("okN" in ahead)
        val near = syn.near(37.0, 127.0002, 0f, 5_000.0, highway, now = 0L, limit = 15).ids()
        assertFalse("okS" in near, near.toString())
    }

    @Test
    fun carParkedFacingTheOtherWayKeepsTheRestAreaItIsIn() {
        // 남행 옥산휴게소 주차장에 북쪽을 보고 서 있다(방위를 넘겨도): 차로 위가 아니고 시설 250m 안 → 그 휴게소를 뺀다면 잘못
        val lat = 37.0005
        val lon = 126.9991
        assertEquals(null, syn.currentRoad(lat, lon, 0f))
        assertTrue("okS" in syn.ahead(lat, lon, 0f, 60_000.0, highway).ids())
        val near = syn.near(lat, lon, 0f, 5_000.0, highway, now = 0L, limit = 15)
        assertEquals(true, near.first { it.id == "okS" }.directionOk)
        // 방위 없음(부르는 쪽이 정차 중이라 넘기지 않음)도 물론 남는다
        assertTrue("okS" in syn.near(lat, lon, null, 5_000.0, highway, now = 0L, limit = 15).ids())
    }

    @Test
    fun facilityJustPassedIsDroppedButOneAbeamIsKept() {
        // 예전에는 300m 안이면 뒤에 있어도 남겼다 (데이터 감사 #11)
        assertFalse("okS" in syn.ahead(36.9985, 126.9998, 180f, 60_000.0, highway).ids(), "167m 지나침")
        assertTrue("okS" in syn.ahead(37.0002, 126.9998, 180f, 60_000.0, highway).ids(), "22m 앞")
        assertTrue("okS" in syn.ahead(36.99975, 126.9998, 180f, 60_000.0, highway).ids(), "28m 뒤(좌표 오차 안)")
    }

    @Test
    fun matchByNameUsesTheLaneHeadingForSameNamePairs() {
        assertEquals(listOf("okS"), syn.matchByName("옥산휴게소", StopKind.HIGHWAY_REST_AREA, 37.06, 126.9998, 20_000.0, 180f).ids())
        assertEquals(listOf("okN"), syn.matchByName("옥산휴게소", StopKind.HIGHWAY_REST_AREA, 36.94, 127.0002, 20_000.0, 0f).ids())
        assertEquals(setOf("okS", "okN"), syn.matchByName("옥산휴게소", StopKind.HIGHWAY_REST_AREA, 37.06, 126.9998, 20_000.0, null).ids().toSet())
        assertEquals(listOf("okS"), syn.matchByName("옥산휴게소 부산방향", StopKind.HIGHWAY_REST_AREA, 37.06, 126.9998, 20_000.0, 180f).ids())
    }

    @Test
    fun shelterLabelledAsServiceAreaMatchesTheShelterNotTheRestArea() {
        val m = syn.matchByName("옥산졸음쉼터(서울방향)", StopKind.HIGHWAY_REST_AREA, 37.06, 126.9998, 20_000.0, 180f)
        assertEquals(listOf("okShelter"), m.ids())
        assertEquals(StopKind.DROWSY_SHELTER, m.single().kind)
    }

    @Test
    fun namesWithoutAnIdentityMatchNothingAndPrefixesOnlyOneWay() {
        assertEquals(emptyList(), syn.matchByName("졸음쉼터", StopKind.DROWSY_SHELTER, 37.06, 126.9998, 20_000.0, 180f))
        assertEquals(listOf("mannam"), syn.matchByName("서울만남의광장휴게소", StopKind.HIGHWAY_REST_AREA, 37.06, 126.9998, 20_000.0, 180f).ids())
        assertEquals(emptyList(), syn.matchByName("안성휴게소", StopKind.HIGHWAY_REST_AREA, 37.06, 126.9998, 200_000.0, 180f), "안성 ≠ 안성맞춤")
    }

    // ------------------------------------------------------------ 실제 데이터

    @Test
    fun approachesToTheFixedSheltersSeeTheirOwnSide() {
        // 데이터 감사 #1: 대신졸음쉼터는 두 방향이 뒤바뀌어 있었고, 동광주졸음쉼터 천안방향은 61도(실제 242도)였다
        fun ahead(name: String) = pos(name).let { p -> real.ahead(p.lat, p.lon, p.heading, 60_000.0, highway).ids() }
        for (km in listOf(1, 5, 15)) {
            val ids = ahead("daesin-busanbound-${km}km")
            assertTrue(DAESIN_BUSAN in ids && DAESIN_SEOUL !in ids, "부산방향 ${km}km 앞: $ids")
        }
        ahead("daesin-seoulbound-5km").let { assertTrue(DAESIN_SEOUL in it && DAESIN_BUSAN !in it, it.toString()) }
        for (km in listOf(1, 9)) assertTrue(DONGGWANGJU in ahead("donggwangju-cheonanbound-${km}km"), "천안방향 ${km}km 앞")
        assertFalse(DONGGWANGJU in ahead("donggwangju-suncheonbound-5km"), "순천방향에서는 반대 차로")
    }

    /**
     * 데이터 감사 #3의 39곳 재현 (기흥 → 옥천, 경부고속도로 부산방향, 목적지 없음). 앞 6곳 = 부르는 쪽이 화장실 있는 후보 중
     * 내비로 보정하는 수(CandidatePolicy.REAL.oracleMax). 같은 길 = 경부고속도로이고 차로가 진행 방향.
     */
    @Test
    fun gyeongbuReplayKeepsTheTopSixOnTheRoad() {
        var oldOn = 0; var oldOff = 0; var newOn = 0; var newOff = 0
        val positions = positions.filter { it.name.startsWith("gyeongbu-") }
        assertEquals(39, positions.size)
        for (p in positions) {
            assertEquals("경부고속도로", real.currentRoad(p.lat, p.lon, p.heading), p.name)
            fun onRoute(c: StopCandidate): Boolean {
                val i = rowOf.getValue(c.id)
                val lane = realTable.laneHeading(i)
                return realTable.road(i) == "경부고속도로" && (lane == null || Geo.angleDiff(lane.toDouble(), p.heading.toDouble()) <= 90.0)
            }
            val old = Legacy(realTable).ahead(p.lat, p.lon, p.heading).filter { it.facilities.restroom }.take(6)
            val new = real.ahead(p.lat, p.lon, p.heading, 60_000.0, highway).filter { it.facilities.restroom }.take(6)
            oldOn += old.count(::onRoute); oldOff += old.count { !onRoute(it) }
            newOn += new.count(::onRoute); newOff += new.count { !onRoute(it) }
        }
        println("경부 39곳 앞 6곳: 예전 같은 길 $oldOn / 다른 길 $oldOff → 지금 같은 길 $newOn / 다른 길 $newOff")
        assertTrue(newOn > oldOn && newOff < oldOff)
        // 같은 길 시설이 6곳 미만일 때만 다른 길 시설이 들어온다
        assertTrue(newOff <= 39 * 6 - newOn)
    }

    @Test
    fun sameNamePairsResolveByLaneHeadingWithoutANaviRequest() {
        // 데이터 감사 #5: 같은 이름이 2곳 이상인 휴게소 89개·졸음쉼터 97개 이름은 행마다 requestTrip을 보냈다.
        // 각 행의 차로로 3km 앞에서 다가가며 이름으로 찾으면 그 행만 나와야 한다
        var pairs = 0; var resolved = 0
        val groups = (0 until realTable.size).filter { realTable.kind(it) in highway }
            .groupBy { realTable.kind(it) to StopNames.key(realTable.name(it)) }.filter { it.key.second != null && it.value.size >= 2 }
        for ((key, rows) in groups) {
            for (i in rows) {
                val lane = realTable.laneHeading(i) ?: continue
                val twins = rows.filter { it != i && Geo.distanceM(realTable.lat(i), realTable.lon(i), realTable.lat(it), realTable.lon(it)) < 3_000.0 }
                if (twins.isEmpty()) continue
                pairs++
                val back = Math.toRadians(lane + 180.0)
                val lat = realTable.lat(i) + 3_000.0 * kotlin.math.cos(back) / 111_195.0
                val lon = realTable.lon(i) + 3_000.0 * kotlin.math.sin(back) / (111_195.0 * kotlin.math.cos(Math.toRadians(lat)))
                val m = real.matchByName(realTable.name(i), key.first, lat, lon, 10_000.0, lane.toFloat()).map { rowOf.getValue(it.id) }
                if (i in m && twins.none { it in m }) resolved++
            }
        }
        println("같은 이름 쌍: $pairs 행 중 $resolved 행을 차로 방위로 골랐다")
        assertTrue(pairs >= 300 && resolved >= pairs * 0.95, "$resolved / $pairs")
    }

    @Test
    fun everyKecRestStopHasANameKey() {
        val missing = (0 until realTable.size).filter { realTable.kind(it) in highway && StopNames.key(realTable.name(it)) == null }
            .map { realTable.id(it) to realTable.name(it) }
        // OSM에서 온 이름 없는 졸음쉼터(이름이 '졸음쉼터')만 키가 없다: 이름으로는 찾지 않는다
        assertTrue(missing.all { it.first.startsWith("o") && it.second == "졸음쉼터" }, missing.toString())
    }

    /** ahead()·near()는 20초마다와 급해 모드에 돈다. 예전 비용 이하인지 (결과가 같은 경우의 비용 비교는 Legacy) */
    @Test
    fun queriesCostNoMoreThanBefore() {
        val ps = positions
        val legacy = Legacy(realTable)
        val urgent = StopKind.entries.toSet()
        fun time(block: () -> Unit): Double {
            repeat(10) { block() }
            val t0 = System.nanoTime()
            val rounds = 100
            repeat(rounds) { block() }
            return (System.nanoTime() - t0) / 1e6 / rounds / ps.size
        }
        val oldAhead = time { for (p in ps) legacy.ahead(p.lat, p.lon, p.heading) }
        val newAhead = time { for (p in ps) real.ahead(p.lat, p.lon, p.heading, 60_000.0, highway) }
        val oldNear = time { for (p in ps) legacy.near(p.lat, p.lon, p.heading, urgent) }
        val newNear = time { for (p in ps) real.near(p.lat, p.lon, p.heading, 5_000.0, urgent, now = 0L, limit = 15) }
        println("ahead(60km) 예전 ${"%.3f".format(oldAhead)} ms → 지금 ${"%.3f".format(newAhead)} ms, " +
            "near(5km, 전 종류) 예전 ${"%.3f".format(oldNear)} ms → 지금 ${"%.3f".format(newNear)} ms (호출 1회, JVM)")
        // 공유 서버라 시간 측정이 흔들린다: 크게 나빠진 것만 잡는다
        assertTrue(newAhead <= oldAhead * 2 + 0.05 && newNear <= oldNear * 2 + 0.05)
    }

    @Test
    fun loadCost() {
        val bytes = File("../../app/src/main/assets/data/stops.bin").readBytes()
        repeat(3) { StopRepository { StopTable.decode(bytes) }.also { r -> runBlocking { r.ensureLoaded() } } }
        val t0 = System.nanoTime()
        val t = StopTable.decode(bytes)
        val t1 = System.nanoTime()
        StopRepository { t }.also { r -> runBlocking { r.ensureLoaded() } }
        val t2 = System.nanoTime()
        println("stops.bin ${bytes.size / 1_000_000.0} MB: decode+검사 ${(t1 - t0) / 1_000_000} ms, 색인(격자·지도 맞춤·이름 키) ${(t2 - t1) / 1_000_000} ms (JVM)")
    }

    // ------------------------------------------------------------ 도우미

    private data class Pos(val name: String, val lat: Double, val lon: Double, val heading: Float)

    private fun pos(name: String) = positions.first { it.name == name }

    /** 예전(main @ c0c82c2) StopRepository.ahead/near를 그대로 옮긴 기준. 같은 표의 원시 접근만 쓴다 */
    private class Legacy(private val t: StopTable) {
        private val highwayRows = (0 until t.size).filter { t.kind(it) == StopKind.HIGHWAY_REST_AREA || t.kind(it) == StopKind.DROWSY_SHELTER }
        private val cells: Map<Long, IntArray> = HashMap<Long, MutableList<Int>>().also { m ->
            for (i in 0 until t.size) m.getOrPut(key(cell(t.lat(i)), cell(t.lon(i)))) { ArrayList() }.add(i)
        }.mapValues { it.value.toIntArray() }

        fun ahead(lat: Double, lon: Double, heading: Float?): List<StopCandidate> {
            val out = ArrayList<StopCandidate>()
            for (i in highwayRows) {
                val d = Geo.distanceM(lat, lon, t.lat(i), t.lon(i))
                if (d > 60_000.0) continue
                if (heading != null && d > 300.0 && Geo.angleDiff(Geo.bearingDeg(lat, lon, t.lat(i), t.lon(i)), heading.toDouble()) > 75.0) continue
                if (opposite(i, lat, lon, d, heading)) continue
                out += t.candidate(i).copy(straightDistanceM = d)
            }
            out.sortBy { it.straightDistanceM }
            return out
        }

        fun near(lat: Double, lon: Double, heading: Float?, kinds: Set<StopKind>): List<StopCandidate> {
            val hits = ArrayList<Triple<Int, Double, Boolean>>()
            val dLat = 5_000.0 / 111_000.0
            val dLon = 5_000.0 / (111_000.0 * kotlin.math.cos(Math.toRadians(lat)))
            for (y in cell(lat - dLat)..cell(lat + dLat)) for (x in cell(lon - dLon)..cell(lon + dLon)) {
                for (i in cells[key(y, x)] ?: continue) {
                    if (t.kind(i) !in kinds) continue
                    val d = Geo.distanceM(lat, lon, t.lat(i), t.lon(i))
                    if (d > 5_000.0) continue
                    val behind = heading != null && d > 150.0 &&
                        Geo.angleDiff(Geo.bearingDeg(lat, lon, t.lat(i), t.lon(i)), heading.toDouble()) > 100.0
                    if (opposite(i, lat, lon, d, heading)) continue
                    hits += Triple(i, d, behind)
                }
            }
            hits.sortBy { it.second }
            return hits.take(15).map { (i, d, behind) ->
                val c = t.candidate(i)
                c.copy(straightDistanceM = d, directionOk = if (heading == null) null else !behind, openNow = c.openNow ?: t.openNow(i, 0L))
            }
        }

        private fun opposite(i: Int, lat: Double, lon: Double, d: Double, heading: Float?): Boolean {
            if (heading == null) return false
            val lane = t.laneHeading(i) ?: return false
            val ref = if (d > 2_000.0) Geo.bearingDeg(lat, lon, t.lat(i), t.lon(i)) else heading.toDouble()
            return Geo.angleDiff(lane.toDouble(), ref) > 90.0
        }

        private fun cell(v: Double) = Math.floor(v / 0.05).toInt()
        private fun key(y: Int, x: Int) = (y.toLong() shl 32) or (x.toLong() and 0xffffffffL)
    }

    private val real get() = shared.repo
    private val realTable get() = shared.table
    private val rowOf get() = shared.rowOf
    private val positions get() = shared.positions

    private object shared {
        val table: StopTable by lazy { StopTable.decode(File("../../app/src/main/assets/data/stops.bin").readBytes()) }
        val repo: StopRepository by lazy { StopRepository { table }.also { runBlocking { it.ensureLoaded() } } }
        val rowOf: Map<String, Int> by lazy { (0 until table.size).associateBy(table::id) }
        val positions: List<Pos> by lazy {
            StopRepositoryTest::class.java.getResource("/stops/replay_positions.csv")!!.readText().lines().drop(1).filter { it.isNotBlank() }
                .map { val f = it.split(','); Pos(f[0], f[1].toDouble(), f[2].toDouble(), f[3].toFloat()) }
        }
    }

    private companion object {
        const val DAESIN_BUSAN = "d97d09b42"
        const val DAESIN_SEOUL = "dc6bcf8b0"
        const val DONGGWANGJU = "db7874cf4"
    }
}
