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

    // ------------------------------------------------------------ 멀리 있는 같은 이름 쌍 (닫힘 검증 C2)

    /**
     * 남북 고속도로가 30km 남쪽에서 잠깐 동서로 꺾인다(굽이). 굽이의 쌍은 차로 방위가 85/265도라, 북쪽에서 내려오는 차의
     * '나 → 시설' 방위(180도)와 거의 직각이다. 곧은 구간의 쌍(곧은휴게소 180/0도)은 분명히 갈린다.
     */
    private val bend: StopTable by lazy {
        val r = "굽이고속도로"
        StopTable.encode(
            listOf(
                rec("bendE", "굽이졸음쉼터", StopKind.DROWSY_SHELTER, 37.0300, 127.0000, 85, r),
                rec("bendW", "굽이졸음쉼터", StopKind.DROWSY_SHELTER, 37.0303, 127.0005, 265, r),
                rec("straightS", "곧은휴게소", StopKind.HIGHWAY_REST_AREA, 37.0200, 126.9990, 180, r),
                rec("straightN", "곧은휴게소", StopKind.HIGHWAY_REST_AREA, 37.0230, 127.0010, 0, r),
                rec("aloneE", "혼자졸음쉼터", StopKind.DROWSY_SHELTER, 37.0100, 127.0000, 85, r),
            ),
            listOf(MotorwayLine(r, false, doubleArrayOf(37.40, 126.9998, 37.00, 126.9998))),
        ).let(StopTable::decode)
    }

    @Test
    fun farAwayTheBearingDecidesBetweenSameNameTwinsOnlyWhenItIsClear() {
        val repo = repo(bend)
        // 30km 북쪽에서 남행. '나 → 시설' 방위 180도와 굽이 쌍의 차로 방위는 95도·85도 차이: 어느 쪽도 분명하지 않다.
        // 예전에는 90도를 넘는 쪽(265도 차로)만 빼서, 길이 반대로 꺾이는 곳(경부선 대신졸음쉼터)에서 반대 차로 행만 남았다
        val far = repo.matchByName("굽이졸음쉼터", StopKind.DROWSY_SHELTER, 37.30, 126.9998, 60_000.0, 180f).ids()
        assertEquals(setOf("bendE", "bendW"), far.toSet(), "분명하지 않으면 둘 다 준다 (부르는 쪽이 내비 거리로 고른다)")
        val ahead = repo.ahead(37.30, 126.9998, 180f, 60_000.0, highway).ids()
        assertTrue("bendE" in ahead && "bendW" in ahead, ahead.toString())

        // 분명히 갈리는 쌍은 멀어도 예전처럼 한쪽만 준다 (쌍마다 내비에 묻지 않는다)
        assertEquals(listOf("straightS"), repo.matchByName("곧은휴게소", StopKind.HIGHWAY_REST_AREA, 37.30, 126.9998, 60_000.0, 180f).ids())
        assertTrue("straightN" !in ahead && "straightS" in ahead, ahead.toString())

        // 20km 안에서는 예전 규칙 그대로다: 90도를 넘는 쪽을 뺀다 (실제 차로 위 136쌍이 20km 안에서는 모두 맞았다)
        assertEquals(listOf("bendW"), repo.matchByName("굽이졸음쉼터", StopKind.DROWSY_SHELTER, 37.10, 126.9998, 60_000.0, 180f).ids())

        // 쌍이 아닌 시설은 멀어도 예전 규칙 그대로다(95도 → 반대 차로): 고를 상대가 없으니 부르는 쪽이 내비로 가를 수도 없다
        assertEquals(emptyList(), repo.matchByName("혼자졸음쉼터", StopKind.DROWSY_SHELTER, 37.30, 126.9998, 60_000.0, 180f).ids())
        assertTrue("aloneE" !in ahead)
    }

    @Test
    fun farUpstreamTheOppositeTwinIsNeverTheOnlyOneOffered() {
        // 닫힘 검증 C2 (데이터 감사 #1·#5가 남긴 것): 반대 차로 판정은 2km보다 먼 시설에 '나 → 시설' 방위와 차로 방위를 비교한다.
        // 차로 방위를 그 자리의 실제 방위로 고친 뒤(대신졸음쉼터 부산방향 30도·서울방향 211도: 김천~구미에서 길이 북북동으로 꺾인다)
        // 대구 쪽에서 서울방향으로 올라오는 차(칠곡휴게소 26km 전 ~ 검단 46km 전)에게는 '나 → 대신'이 북서라서 서울방향 행(211도)이
        // 반대 차로로 빠지고 부산방향 행(30도)만 남았다. matchByName이 한 행만 주면 CandidateProvider는 내비에 묻지 않고 그 행을 쓴다.
        // 같은 노선·같은 방향 쉼터에서 본 쌍 154곳(직선 20~60km) 가운데 틀린 3곳이 모두 직각에서 13도 안이었다 (docs/design/coach-layer/data.md 3.3).
        fun row(name: String, road: String, towards: Double): Int = (0 until realTable.size)
            .filter { realTable.kind(it) in highway && realTable.name(it) == name && realTable.road(it) == road && realTable.laneHeading(it) != null }
            .minBy { Geo.angleDiff(realTable.laneHeading(it)!!.toDouble(), towards) }
        data class Case(val car: Int, val mine: Int, val other: Int, val note: String)
        val gyeongbu = "경부고속도로"
        val ring = "수도권제1순환고속도로"
        val daesinSeoul = row("대신졸음쉼터", gyeongbu, 211.0)
        val daesinBusan = row("대신졸음쉼터", gyeongbu, 30.0)
        assertEquals(listOf(DAESIN_SEOUL, DAESIN_BUSAN), listOf(realTable.id(daesinSeoul), realTable.id(daesinBusan)), "전제: 대신졸음쉼터 두 행")
        val cases = listOf(
            Case(row("칠곡휴게소", gyeongbu, 0.0), daesinSeoul, daesinBusan, "칠곡휴게소(서울방향) → 대신 27km"),
            Case(row("지천졸음쉼터", gyeongbu, 337.0), daesinSeoul, daesinBusan, "지천졸음쉼터(서울방향) → 대신 37km"),
            Case(row("검단졸음쉼터", gyeongbu, 258.0), daesinSeoul, daesinBusan, "검단졸음쉼터(서울방향) → 대신 46km"),
            Case(row("구리남양주졸음쉼터", ring, 185.0), row("시흥졸음쉼터", ring, 141.0), row("시흥졸음쉼터", ring, 322.0), "구리남양주(판교방향) → 시흥 35km"),
        )
        for (c in cases) {
            val lat = realTable.lat(c.car)
            val lon = realTable.lon(c.car)
            val lane = realTable.laneHeading(c.car)!!.toFloat()
            val mine = realTable.id(c.mine)
            val other = realTable.id(c.other)
            val matched = real.matchByName(realTable.name(c.mine), realTable.kind(c.mine), lat, lon, 60_000.0, lane).ids()
            assertTrue(mine in matched, "${c.note}: 이름으로 찾은 행 $matched 에 같은 방향 행 $mine 이 없다")
            val ahead = real.ahead(lat, lon, lane, 60_000.0, highway).ids()
            assertFalse(other in ahead && mine !in ahead, "${c.note}: 앞쪽 후보에 반대 차로 행만 있다")
        }
        // 가까워지면(석적졸음쉼터, 대신 22km 전 → 5km 전) 차로 방위가 한 행으로 가른다
        val near = pos("daesin-seoulbound-5km")
        assertEquals(listOf(DAESIN_SEOUL), real.matchByName("대신졸음쉼터", StopKind.DROWSY_SHELTER, near.lat, near.lon, 60_000.0, near.heading).ids())
    }

    @Test
    fun aCarParkedInARestAreaKeepsItWithTheHeadingItArrivedWith() {
        // 닫힘 검증 C1: 코어는 서 있는 동안에도 마지막으로 달리던 방위를 넘긴다(방위를 버리면 앞 6곳이 뒤·반대 차로 시설로 찬다).
        // 그 방위로도 차가 서 있는 휴게소는 남아야 한다: 휴게소 시설 점은 본선에서 떨어져 있어 '시설 안'(차로 위가 아니고 250m 안)으로 본다
        val lat = 37.0005
        val lon = 126.9991
        assertTrue("okS" in syn.ahead(lat, lon, 180f, 60_000.0, highway).ids(), "달리던 방위(남행)")
        assertTrue("okS" in syn.ahead(lat, lon, 0f, 60_000.0, highway).ids(), "주차장을 돌아 반대로 선 방위")
        // 실제 휴게소: 시설 점에서 진행 방향으로 0~150m 더 들어가 선 차 (오른쪽으로 15m). 차로 방위 = 달리던 방위
        var parked = 0
        var kept = 0
        for (i in 0 until realTable.size) {
            if (realTable.kind(i) != StopKind.HIGHWAY_REST_AREA) continue
            val lane = realTable.laneHeading(i) ?: continue
            val h = Math.toRadians(lane.toDouble())
            for (along in listOf(0.0, 60.0, 150.0)) {
                val north = along * kotlin.math.cos(h) - 15.0 * kotlin.math.sin(h)
                val east = along * kotlin.math.sin(h) + 15.0 * kotlin.math.cos(h)
                val pLat = realTable.lat(i) + north / 111_195.0
                val pLon = realTable.lon(i) + east / (111_195.0 * kotlin.math.cos(Math.toRadians(pLat)))
                parked++
                if (real.ahead(pLat, pLon, lane.toFloat(), 60_000.0, highway).any { it.id == realTable.id(i) }) kept++
            }
        }
        println("휴게소 안에 선 차 $parked 곳 중 $kept 곳에서 그 휴게소가 앞쪽 후보에 남는다")
        assertTrue(kept >= parked * 0.95, "$kept / $parked")
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
        val (oldOn, oldOff, newOn, newOff) = replayTopSix("gyeongbu-")
        assertTrue(newOn > oldOn && newOff < oldOff)
        // 같은 길 시설이 6곳 미만일 때만 다른 길 시설이 들어온다
        assertTrue(newOff <= 39 * 6 - newOn)
        // 감사 원래의 39곳(13점 꺾은선 위, 길에서 수백 m~수 km 벗어남): 지도 맞춤이 '모름'인 곳은 예전 순서 그대로다
        val (aOldOn, aOldOff, aNewOn, aNewOff) = replayTopSix("audit-", requireMatch = false)
        assertTrue(aNewOn >= aOldOn && aNewOff <= aOldOff)
    }

    private fun replayTopSix(prefix: String, requireMatch: Boolean = true): List<Int> {
        var oldOn = 0; var oldOff = 0; var newOn = 0; var newOff = 0; var matched = 0
        val positions = positions.filter { it.name.startsWith(prefix) }
        assertEquals(39, positions.size)
        for (p in positions) {
            val road = real.currentRoad(p.lat, p.lon, p.heading)
            if (requireMatch) assertEquals("경부고속도로", road, p.name)
            if (road != null) matched++
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
        println("$prefix 39곳 앞 6곳 (지도 맞춤 $matched 곳): 예전 같은 길 $oldOn / 다른 길 $oldOff → 지금 같은 길 $newOn / 다른 길 $newOff")
        return listOf(oldOn, oldOff, newOn, newOff)
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
        val files = (System.getenv("SHIMPYO_STOP_BENCHMARK") ?: "../../app/src/main/assets/data/stops.bin")
            .split(File.pathSeparator).map { File(it).readBytes() }
        fun measure(bytes: ByteArray): Triple<Double, Double, Double> {
            val t0 = System.nanoTime()
            val t = StopTable.decode(bytes)
            val t1 = System.nanoTime()
            StopRepository { t }.also { r -> runBlocking { r.ensureLoaded() } }
            val t2 = System.nanoTime()
            return Triple((t1 - t0) / 1e6, (t2 - t1) / 1e6, (t2 - t0) / 1e6)
        }
        val first = files.map { measure(it).third }
        repeat(3) { for (bytes in files) measure(bytes) }
        val samples = files.map { ArrayList<Triple<Double, Double, Double>>() }
        repeat(7) { round ->
            // 두 파일을 같은 JVM에서 교대로 재면 서버 부하/JIT 순서 효과가 줄어든다.
            val order = if (round % 2 == 0) files.indices.toList() else files.indices.reversed()
            for (i in order) samples[i] += measure(files[i])
        }
        for (i in files.indices) {
            val rows = samples[i]
            println("stops.bin ${files[i].size} bytes: first decode+index ${"%.1f".format(first[i])} ms; " +
                "warm medians (3 warmups, 7 interleaved samples): decode ${"%.1f".format(rows.map { it.first }.sorted()[3])} ms, " +
                "index ${"%.1f".format(rows.map { it.second }.sorted()[3])} ms, total ${"%.1f".format(rows.map { it.third }.sorted()[3])} ms (JVM)")
        }
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
