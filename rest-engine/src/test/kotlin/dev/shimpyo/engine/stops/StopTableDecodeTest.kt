package dev.shimpyo.engine.stops

import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 깨진 stops.bin (데이터 감사 #9). 예전 decode는 검사하지 않아서 행 수가 깨지면 2GB까지 잡다가 OutOfMemoryError
 * (앱의 catch(Exception)이 못 잡음)가 났고, 종류 바이트가 깨지면 질의 중에 예외가 나서 후보 루프가 끝났다.
 * 이제 decode가 모든 값을 검사해 [StopTableFormatException] 하나로 실패하고, 읽힌 표는 어느 행을 꺼내도 안전하다.
 */
class StopTableDecodeTest {
    private val rows = listOf(
        StopRecord(StopCandidate("k1", "안성휴게소", StopKind.HIGHWAY_REST_AREA, 37.0, 127.1, Facilities(restroom = true), parkable = true, openNow = true),
            laneHeading = 157, hours = StopHours(true, DayHours.Open(0, 0)), road = "경부고속도로"),
        StopRecord(StopCandidate("t1", "화장실", StopKind.PUBLIC_RESTROOM, 37.5, 127.0, Facilities(restroom = true), parkable = false, walkDistanceM = 40.0),
            laneHeading = null, hours = StopHours(false, DayHours.Open(300, 20), DayHours.Closed, null)),
        StopRecord(StopCandidate("p1", "주차장", StopKind.PUBLIC_PARKING, 35.1, 129.0, Facilities(restroom = true), parkable = true),
            laneHeading = null, hours = null),
    )
    private val lines = listOf(
        MotorwayLine("경부고속도로", false, doubleArrayOf(37.0, 127.1, 36.99, 127.11, 36.98, 127.12)),
        MotorwayLine("중부고속도로", true, doubleArrayOf(37.2, 127.3, 37.21, 127.31)),
    )
    private val good = StopTable.encode(rows, lines)

    private fun ByteArray.le() = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun goodTableRoundTrips() {
        val t = StopTable.decode(good)
        assertEquals(3, t.size)
        assertEquals(rows.map { it.candidate }, (0 until 3).map(t::candidate))
        assertEquals(2, t.lineCount)
        assertEquals(listOf("경부고속도로", "중부고속도로"), (0 until 2).map { t.roadName(t.lineRoadId(it)) })
        assertEquals(listOf(false, true), (0 until 2).map(t::lineTwoWay))
        assertEquals(listOf(0, 3, 5), listOf(t.lineStart(0), t.lineStart(1), t.lineEnd(1)))
        assertEquals(36.99, t.pointLat(1), 1e-5)
        assertEquals(127.31, t.pointLon(4), 1e-5)
        assertEquals("경부고속도로", t.road(0), "시설 행과 선이 같은 도로 색인을 쓴다")
    }

    @Test
    fun wrongMagicOrOldVersionFailsClearly() {
        assertFailsWith<StopTableFormatException> { StopTable.decode(good.copyOf().also { it[0] = 'X'.code.toByte() }) }
        val v1 = good.copyOf().also { it.le().putInt(4, 1) }
        val e = assertFailsWith<StopTableFormatException> { StopTable.decode(v1) }
        assertTrue("buildStopTable" in e.message!!, e.message)
        assertFailsWith<StopTableFormatException> { StopTable.decode(ByteArray(0)) }
        assertFailsWith<StopTableFormatException> { StopTable.decode(good.copyOf(10)) }
    }

    @Test
    fun hugeRowCountFailsWithoutAllocating() {
        // 예전에는 n만큼 배열을 잡다가 OutOfMemoryError가 났다
        for (n in listOf(Int.MAX_VALUE, 1_000_000_000, 1_999_999, -1)) {
            assertFailsWith<StopTableFormatException>("n=$n") { StopTable.decode(good.copyOf().also { it.le().putInt(8, n) }) }
        }
        assertFailsWith<StopTableFormatException> { StopTable.decode(good.copyOf().also { it.le().putInt(12, 70_000) }) }
        assertFailsWith<StopTableFormatException> { StopTable.decode(good.copyOf().also { it.le().putInt(16, -3) }) }
        assertFailsWith<StopTableFormatException> { StopTable.decode(good.copyOf().also { it.le().putInt(20, Int.MAX_VALUE) }) }
        assertFailsWith<StopTableFormatException> { StopTable.decode(good.copyOf().also { it.le().putInt(24, 9_000_000) }) }
    }

    @Test
    fun lineSectionIsValidatedToo() {
        // 마지막 점들(float)의 끝: 경도 배열의 마지막 값을 범위 밖으로
        val bytes = good.copyOf().also { it.le().putFloat(it.size - 4, 999f) }
        assertFailsWith<StopTableFormatException> { StopTable.decode(bytes) }
        val nan = good.copyOf().also { it.le().putFloat(it.size - 4, Float.NaN) }
        assertFailsWith<StopTableFormatException> { StopTable.decode(nan) }
    }

    @Test
    fun badKindByteFailsAtDecodeNotDuringQueries() {
        val bytes = good.copyOf().also { it[28] = 99 }   // 첫 행의 종류 (머리말 28바이트 뒤)
        assertFailsWith<StopTableFormatException> { StopTable.decode(bytes) }
    }

    @Test
    fun truncatedOrPaddedFileFails() {
        assertFailsWith<StopTableFormatException> { StopTable.decode(good.copyOf(good.size - 1)) }
        assertFailsWith<StopTableFormatException> { StopTable.decode(good.copyOf(good.size + 1)) }
    }

    /** 바이트 하나를 바꾼 모든 경우: 분명히 실패하거나, 읽혔다면 모든 행의 모든 접근이 예외 없이 돈다 */
    @Test
    fun anySingleByteCorruptionEitherFailsClearlyOrYieldsASafeTable() {
        val rnd = Random(7)
        var rejected = 0
        for (pos in good.indices) {
            for (v in listOf(0x00, 0xFF, 0x80, rnd.nextInt(256))) {
                val bytes = good.copyOf().also { it[pos] = v.toByte() }
                val t = try {
                    StopTable.decode(bytes)
                } catch (e: StopTableFormatException) {
                    rejected++
                    continue
                }
                for (i in 0 until t.size) {
                    t.candidate(i); t.kind(i); t.road(i); t.laneHeading(i); t.hours(i)
                    t.openNow(i, 1_762_000_000_000L)
                    assertTrue(t.lat(i).isFinite() && t.lon(i).isFinite())
                }
                for (k in 0 until t.lineCount) {
                    t.roadName(t.lineRoadId(k)); t.lineTwoWay(k)
                    for (p in t.lineStart(k) until t.lineEnd(k)) assertTrue(t.pointLat(p).isFinite() && t.pointLon(p).isFinite())
                }
                MotorwayIndex(t).roadAt(37.0, 127.1, 150.0)
            }
        }
        assertTrue(rejected > 0)
    }
}
