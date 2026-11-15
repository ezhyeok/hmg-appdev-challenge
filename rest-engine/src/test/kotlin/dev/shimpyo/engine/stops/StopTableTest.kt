package dev.shimpyo.engine.stops

import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import java.io.File
import java.io.StringReader
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

/**
 * 앱이 읽는 stops.bin이 JSON 데이터와 같은 후보를 내는지 (품질 동일성). 앱은 예전에 JSON을 직접 읽었고,
 * 지금은 같은 변환 규칙(StopRows)으로 만든 이진 표를 읽는다. JSON만 다시 만들고 stops.bin을 안 만들면 여기서 실패한다
 * (해결: ./gradlew -p rest-engine buildStopTable).
 */
class StopTableTest {
    private val dataDir = File("..", "app/src/main/assets/data")

    @Test
    fun committedStopsBinMatchesEveryJsonRow() {
        val records = loadRecords(dataDir)
        val table = StopTable.decode(File(dataDir, "stops.bin").readBytes())
        assertEquals(records.size, table.size, "행 수")
        val seoul = ZoneId.of("Asia/Seoul")
        // 수요일·토요일·일요일 (주차장은 요일별 시간이 다르다)
        val times = listOf("2026-11-11", "2026-11-14", "2026-11-15").flatMap { d ->
            listOf(3, 9, 14, 23).map { h -> java.time.LocalDate.parse(d).atTime(h, 10).atZone(seoul).toInstant().toEpochMilli() }
        }
        for (i in records.indices) {
            val r = records[i]
            assertEquals(r.candidate, table.candidate(i), "행 $i (${r.candidate.id})")
            assertEquals(r.laneHeading, table.laneHeading(i), "차로 방위 $i")
            assertEquals(r.road, table.road(i), "고속도로 $i")
            assertEquals(r.hours, table.hours(i), "영업 시간 $i")
            assertEquals(r.candidate.kind, table.kind(i))
            assertEquals(r.candidate.lat, table.lat(i))
            assertEquals(r.candidate.lon, table.lon(i))
            for (t in times) assertEquals(referenceOpenNow(r.hours, t, seoul), table.openNow(i, t), "영업 여부 $i @ $t")
        }
        assertEquals(87_497, table.size, "60,084 + 주유소 7,092 + 독립 급속충전소 20,321")
        assertTrue(records.any { it.candidate.facilities.evCharger && !it.candidate.facilities.restroom })
        assertTrue(records.any { it.candidate.facilities.fuel && !it.candidate.facilities.restroom })
        // 고속도로 선형 (motorways.json)
        val lines = loadLines(dataDir)
        assertEquals(lines.size, table.lineCount)
        for ((k, l) in lines.withIndex()) {
            assertEquals(l.road, table.roadName(table.lineRoadId(k)), "선 $k")
            assertEquals(l.twoWay, table.lineTwoWay(k))
            assertEquals(l.points.size / 2, table.lineEnd(k) - table.lineStart(k))
            for (p in 0 until l.points.size / 2) {
                assertEquals(l.points[2 * p], table.pointLat(table.lineStart(k) + p), 1e-5)
                assertEquals(l.points[2 * p + 1], table.pointLon(table.lineStart(k) + p), 1e-5)
            }
        }
        val highway = (0 until table.size).filter { table.kind(it) == StopKind.HIGHWAY_REST_AREA || table.kind(it) == StopKind.DROWSY_SHELTER }
        assertEquals(532, highway.size)
        // 휴게소·졸음쉼터는 모두 고속도로 이름이 있고, 나머지는 없다 (같은 길 순위가 이것에 기댄다)
        assertTrue(highway.all { table.road(it) != null })
        assertEquals(highway.size, (0 until table.size).count { table.road(it) != null })
        assertEquals("경부고속도로", table.road((0 until table.size).first { table.name(it) == "안성휴게소" }))
    }

    @Test
    fun energyRowsDoNotInventRestroomEvidence() {
        val base = mapOf("id" to "fuel", "n" to "주유소", "lat" to 37.0, "lon" to 127.0, "k" to "fuel")
        for (wc in listOf(null, 0, 1)) {
            val c = assertNotNull(StopRows.map(StopFile.PARKABLES, base + ("wc" to wc))).candidate
            assertTrue(c.facilities.fuel)
            assertEquals(wc == 1, c.facilities.restroom)
        }
        val ev = assertNotNull(StopRows.map(StopFile.EV_CHARGERS, base + mapOf("dc" to 2, "h24" to 1)))
        assertTrue(ev.candidate.facilities.evCharger)
        assertFalse(ev.candidate.facilities.restroom)
        assertTrue(ev.candidate.facilities.open24h)
        assertNull(StopRows.map(StopFile.EV_CHARGERS, base + ("dc" to 0)))
        assertNull(StopRows.map(StopFile.EV_CHARGERS, base + mapOf("dc" to 1, "at" to "existing")))
        val decoded = StopTable.decode(StopTable.encode(listOf(ev)))
        assertEquals(ev.candidate, decoded.candidate(0))
        assertFalse(decoded.everydayUsable(0, 0L))
        assertFalse(decoded.hasRestroom(0))
        assertFalse(assertNotNull(StopRows.map(StopFile.SHELTERS, base)).candidate.facilities.restroom)
        assertFalse(assertNotNull(StopRows.map(StopFile.REST_AREAS, base + ("ev" to 1))).candidate.facilities.evCharger,
            "기존 EV 유무만으로 급속 충전을 추론하지 않는다; loadRecords가 급속 근거를 붙인다")
    }

    @Test
    fun attachedStationsEnrichTheExistingRowAndMissingTargetsFailTheBuild() {
        val dir = kotlin.io.path.createTempDirectory("ev-attachment-").toFile()
        try {
            for (file in StopFile.entries) File(dir, file.fileName).writeText("[]")
            File(dir, StopFile.REST_AREAS.fileName).writeText("""[{"id":"rest","n":"휴게소","lat":37,"lon":127,"ev":0}]""")
            val ev = File(dir, StopFile.EV_CHARGERS.fileName)
            ev.writeText("""[{"id":"evA","at":"rest","dc":2}]""")
            val row = loadRecords(dir).single().candidate
            assertEquals("rest", row.id)
            assertTrue(row.facilities.evCharger)
            assertTrue(row.facilities.restroom)
            ev.writeText("""[{"id":"evA","at":"missing","dc":2}]""")
            assertFailsWith<IllegalArgumentException> { loadRecords(dir) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun codecKeepsNullsAndEdgeValues() {
        val a = StopRecord(
            StopCandidate("a", "가", StopKind.PUBLIC_RESTROOM, 37.123456789, 127.5, Facilities(restroom = true, diaperTable = true, petArea = true), parkable = false, walkDistanceM = 120.0),
            laneHeading = null, hours = StopHours(h24 = false, weekday = DayHours.Open(0, 0), saturday = DayHours.Closed, holiday = DayHours.Open(300, 20)),
        )
        val b = StopRecord(
            StopCandidate("b", "Shelter (북)", StopKind.DROWSY_SHELTER, 36.0, 128.0, Facilities(restroom = false, cctv = true), parkable = true, openNow = true),
            laneHeading = 359, hours = null, road = "경부고속도로",
        )
        val c = StopRecord(
            StopCandidate("c", "", StopKind.GAS_STATION, 35.0, 129.0, Facilities(fuel = true), parkable = true, openNow = false),
            laneHeading = 0, hours = StopHours(h24 = null, weekday = null), road = "",
        )
        val t = StopTable.decode(StopTable.encode(listOf(a, b, c)))
        assertEquals(listOf(a.candidate, b.candidate, c.candidate), (0 until t.size).map(t::candidate))
        assertEquals(listOf(null, 359, 0), (0 until t.size).map(t::laneHeading))
        assertEquals(listOf(a.hours, null, c.hours), (0 until t.size).map(t::hours))
        assertEquals(listOf(null, "경부고속도로", ""), (0 until t.size).map(t::road))
        assertEquals(listOf(-1, 0, 1), (0 until t.size).map(t::roadId))
        assertNull(t.openNow(1, 0L), "영업 시간 정보가 없으면 모름")
        assertNull(t.openNow(2, 0L), "24시간 여부도 시간도 모르면 모름")
    }

    @Test
    fun flatJsonReadsValuesLikeTheOldAndroidReader() {
        val rows = ArrayList<Map<String, Any?>>()
        FlatJson.forEachObject(StringReader("""[{"id":"x","n":"옥산\"휴게소A","lat":36.657743,"h24":1,"t":true,"z":null,"arr":[1,{"a":2}],"e":-1.5e2,"p":[37.5, -127.25,1e1],"q":[]}, {}]""")) { rows += HashMap(it) }
        assertEquals(2, rows.size)
        val r = rows[0]
        assertEquals("옥산\"휴게소A", r["n"])
        assertEquals(36.657743, r["lat"])
        assertEquals(1.0, r["h24"], "숫자는 Double")
        assertEquals(true, r["t"])
        assertTrue(r.containsKey("z") && r["z"] == null)
        assertNull(r["arr"], "배열·객체는 null")
        assertEquals(-150.0, r["e"])
        assertEquals(listOf(37.5, -127.25, 10.0), (r["p"] as DoubleArray).toList(), "숫자 배열 (motorways.json 좌표)")
        assertEquals(0, (r["q"] as DoubleArray).size)
        assertTrue(rows[1].isEmpty())
    }

    /** 행의 영업 시간으로 직접 계산한 값 (표를 거치지 않은 기준) */
    private fun referenceOpenNow(h: StopHours?, nowMs: Long, zone: ZoneId): Boolean? {
        h ?: return null
        val t = Instant.ofEpochMilli(nowMs).atZone(zone)
        return h.openAt(KoreanCalendar.dayType(t.toLocalDate()), t.hour * 60 + t.minute)
    }
}
