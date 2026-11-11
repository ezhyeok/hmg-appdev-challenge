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
        val times = listOf(3, 9, 14, 23).map { h -> Instant.parse("2026-11-11T00:00:00Z").atZone(seoul).withHour(h).withMinute(10).toInstant().toEpochMilli() }
        for (i in records.indices) {
            val r = records[i]
            assertEquals(r.candidate, table.candidate(i), "행 $i (${r.candidate.id})")
            assertEquals(r.laneHeading, table.laneHeading(i), "차로 방위 $i")
            assertEquals(r.hours, table.hours(i), "영업 시간 $i")
            assertEquals(r.candidate.kind, table.kind(i))
            assertEquals(r.candidate.lat, table.lat(i))
            assertEquals(r.candidate.lon, table.lon(i))
            for (t in times) assertEquals(referenceOpenNow(r.hours, t, seoul), table.openNow(i, t), "영업 여부 $i @ $t")
        }
        // 예전 앱이 읽던 수 (기획서·README의 60,084곳)
        assertEquals(60_084, table.size)
        assertEquals(532, (0 until table.size).count { table.kind(it) == StopKind.HIGHWAY_REST_AREA || table.kind(it) == StopKind.DROWSY_SHELTER })
    }

    @Test
    fun codecKeepsNullsAndEdgeValues() {
        val a = StopRecord(
            StopCandidate("a", "가", StopKind.PUBLIC_RESTROOM, 37.123456789, 127.5, Facilities(restroom = true, diaperTable = true, petArea = true), parkable = false, walkDistanceM = 120.0),
            laneHeading = null, hours = StopHours(h24 = false, open = 0, close = 24 * 60),
        )
        val b = StopRecord(
            StopCandidate("b", "Shelter (북)", StopKind.DROWSY_SHELTER, 36.0, 128.0, Facilities(restroom = false, cctv = true), parkable = true, openNow = true),
            laneHeading = 359, hours = null,
        )
        val c = StopRecord(
            StopCandidate("c", "", StopKind.GAS_STATION, 35.0, 129.0, Facilities(fuel = true), parkable = true, openNow = false),
            laneHeading = 0, hours = StopHours(h24 = null, open = null, close = null),
        )
        val t = StopTable.decode(StopTable.encode(listOf(a, b, c)))
        assertEquals(listOf(a.candidate, b.candidate, c.candidate), (0 until t.size).map(t::candidate))
        assertEquals(listOf(null, 359, 0), (0 until t.size).map(t::laneHeading))
        assertEquals(listOf(a.hours, null, c.hours), (0 until t.size).map(t::hours))
        assertNull(t.openNow(1, 0L), "영업 시간 정보가 없으면 모름")
        assertNull(t.openNow(2, 0L), "24시간 여부도 시간도 모르면 모름")
    }

    @Test
    fun flatJsonReadsValuesLikeTheOldAndroidReader() {
        val rows = ArrayList<Map<String, Any?>>()
        FlatJson.forEachObject(StringReader("""[{"id":"x","n":"옥산\"휴게소A","lat":36.657743,"h24":1,"t":true,"z":null,"arr":[1,{"a":2}],"e":-1.5e2}, {}]""")) { rows += HashMap(it) }
        assertEquals(2, rows.size)
        val r = rows[0]
        assertEquals("옥산\"휴게소A", r["n"])
        assertEquals(36.657743, r["lat"])
        assertEquals(1.0, r["h24"], "숫자는 Double")
        assertEquals(true, r["t"])
        assertTrue(r.containsKey("z") && r["z"] == null)
        assertNull(r["arr"], "배열·객체는 null")
        assertEquals(-150.0, r["e"])
        assertTrue(rows[1].isEmpty())
    }

    /** 예전 StopAssets.openNow와 같은 식 */
    private fun referenceOpenNow(h: StopHours?, nowMs: Long, zone: ZoneId): Boolean? {
        h ?: return null
        if (h.h24 == true) return true
        val open = h.open ?: return null
        val close = h.close ?: return null
        val lt = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime()
        val m = lt.hour * 60 + lt.minute
        return if (open <= close) m in open until close else m >= open || m < close
    }
}
