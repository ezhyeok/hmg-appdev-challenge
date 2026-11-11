package dev.shimpyo.engine.stops

import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId

/** stops.bin이 깨졌거나 이 코드와 형식이 다르다. 앱(StopAssets.loadTable)은 이것을 잡고 후보 없이 동작한다 */
class StopTableFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 고속도로 차로 하나의 단순화한 선형 (tools/data motorways.json, OSM). [points] = [lat0, lon0, lat1, lon1, ...].
 * 일방통행 차로는 점 순서가 진행 방향이다. 내비 없이 '지금 어느 고속도로를 달리는지' 맞추는 데 쓴다 ([MotorwayIndex])
 */
class MotorwayLine(val road: String, val twoWay: Boolean, val points: DoubleArray) {
    init {
        require(points.size >= 4 && points.size % 2 == 0) { "line $road: ${points.size} coordinates" }
    }
}

/**
 * 정차 후보 6만여 곳을 열(column) 단위 배열로 들고 있는 표. 앱은 assets/data/stops.bin을 [decode]로 한 번에 읽고,
 * 질의 결과로 나간 행만 [candidate]로 StopCandidate를 만든다.
 *
 * 예전에는 JSON 13MB를 행마다 Map → StopCandidate로 만들어 에뮬레이터에서 18초 동안 GC가 돌았다(2026-11-11).
 * 같은 변환 규칙([StopRows])으로 만든 이진 표라서 결과는 같다(StopTableTest가 JSON 전 행과 비교한다).
 *
 * [decode]는 모든 값을 검사한다(개수, 종류, 좌표, 방위, 오프셋, 색인). 그래서 읽힌 표의 어느 행을 꺼내도 예외가 나지 않는다
 * (예전에는 종류 바이트 하나가 깨지면 질의 중에 예외가 나서 후보 갱신 루프가 끝났다. 데이터 감사 #9).
 *
 * stops.bin 형식 (리틀엔디언, 버전 2):
 * ```
 * "SHPT", version:int = 2, n:int, roadCount:int, scheduleCount:int, lineCount:int, pointCount:int
 * kind:byte[n], lat:double[n], lon:double[n], flags:int[n], walk:double[n] (NaN = 없음)
 * lane:short[n] (MIN = 없음), road:short[n] (-1 = 고속도로 시설 아님), schedule:short[n] (-1 = 영업 시간 모름)
 * idOffset:int[n+1], nameOffset:int[n+1], roadOffset:int[roadCount+1], blobLength:int, blob:UTF-8 (id들, 이름들, 도로명들)
 * scheduleFlags:byte[scheduleCount] (24시간 여부: 0 모름, 1 아님, 2 24시간)
 * scheduleTimes:short[scheduleCount * 6] (평일·토요일·공휴일의 여는·닫는 분, -1 모름, -2 휴무)
 * lineRoad:short[lineCount], lineFlags:byte[lineCount] (1 = 양방향 통행), lineStart:int[lineCount+1] (점 색인)
 * pointLat:float[pointCount], pointLon:float[pointCount] (고속도로 차로 선형, float은 약 1m 정밀도)
 * ```
 * 버전 1(도로·요일별 시간 없음)은 읽지 않는다: 앱은 자기 APK의 stops.bin만 읽으므로 옛 파일이 올 일이 없고,
 * 오면 [StopTableFormatException]으로 분명히 실패한다.
 */
class StopTable private constructor(
    val size: Int,
    private val kinds: ByteArray,
    private val lats: DoubleArray,
    private val lons: DoubleArray,
    private val flags: IntArray,
    private val walks: DoubleArray,
    private val lanes: ShortArray,
    private val roads: ShortArray,
    private val schedules: ShortArray,
    private val idOffsets: IntArray,
    private val nameOffsets: IntArray,
    private val blob: ByteArray,
    private val roadNames: Array<String>,
    private val scheduleTable: Array<StopHours>,
    private val lineRoads: ShortArray,
    private val lineFlags: ByteArray,
    private val lineStarts: IntArray,
    private val pointLats: FloatArray,
    private val pointLons: FloatArray,
) {
    fun kind(i: Int): StopKind = KINDS[kinds[i].toInt()]
    fun lat(i: Int): Double = lats[i]
    fun lon(i: Int): Double = lons[i]
    fun id(i: Int): String = String(blob, idOffsets[i], idOffsets[i + 1] - idOffsets[i], Charsets.UTF_8)
    fun name(i: Int): String = String(blob, nameOffsets[i], nameOffsets[i + 1] - nameOffsets[i], Charsets.UTF_8)

    /** 그 휴게소·졸음쉼터로 들어가는 차로의 진행 방위(도). 양방향 시설이나 모르면 null */
    fun laneHeading(i: Int): Int? = lanes[i].takeIf { it != NO_LANE }?.toInt()

    /** 고속도로 시설이 있는 고속도로의 번호(표 안의 색인, 0..roadCount-1). 고속도로 시설이 아니면 -1 */
    fun roadId(i: Int): Int = roads[i].toInt()

    /** 고속도로 이름 (OSM 고속도로명, 예: 경부고속도로). 고속도로 시설이 아니면 null */
    fun road(i: Int): String? = roadId(i).takeIf { it >= 0 }?.let { roadNames[it] }

    val roadCount: Int get() = roadNames.size
    fun roadName(roadId: Int): String = roadNames[roadId]

    /** 고속도로 차로 선형 수와 접근자. 선 k의 점은 lineStart(k) until lineEnd(k) */
    val lineCount: Int get() = lineRoads.size
    fun lineRoadId(k: Int): Int = lineRoads[k].toInt()
    fun lineTwoWay(k: Int): Boolean = lineFlags[k].toInt() == 1
    fun lineStart(k: Int): Int = lineStarts[k]
    fun lineEnd(k: Int): Int = lineStarts[k + 1]
    fun pointLat(p: Int): Double = pointLats[p].toDouble()
    fun pointLon(p: Int): Double = pointLons[p].toDouble()

    fun hours(i: Int): StopHours? = schedules[i].toInt().takeIf { it >= 0 }?.let { scheduleTable[it] }

    /** 지금 열려 있는지. 24시간이면 true, 그날(평일·토요일·일요일/공휴일) 시간 범위가 있으면 계산, 모르면 null */
    fun openNow(i: Int, nowMs: Long, zone: ZoneId = SEOUL): Boolean? {
        val h = hours(i) ?: return null
        val t = Instant.ofEpochMilli(nowMs).atZone(zone)
        return h.openAt(KoreanCalendar.dayType(t.toLocalDate()), t.hour * 60 + t.minute)
    }

    /** i번째 행의 정차 후보 (질의 결과로 나갈 때만 만든다) */
    fun candidate(i: Int): StopCandidate {
        val f = flags[i]
        return StopCandidate(
            id = id(i),
            name = name(i),
            kind = kind(i),
            lat = lats[i],
            lon = lons[i],
            facilities = Facilities(
                restroom = f has F_RESTROOM,
                diaperTable = f has F_DIAPER,
                nursingRoom = f has F_NURSING,
                emergencyBell = f has F_BELL,
                cctv = f has F_CCTV,
                accessible = f has F_ACCESSIBLE,
                food = f has F_FOOD,
                fuel = f has F_FUEL,
                evCharger = f has F_EV,
                petArea = f has F_PET,
                open24h = f has F_OPEN24H,
            ),
            parkable = f has F_PARKABLE,
            openNow = if (f has F_OPEN_KNOWN) f has F_OPEN_NOW else null,
            walkDistanceM = walks[i].takeIf { !it.isNaN() },
        )
    }

    companion object {
        private val KINDS = StopKind.entries.toTypedArray()
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        private const val MAGIC = 0x54504853 // "SHPT" (리틀엔디언)
        const val VERSION = 2
        private const val NO_LANE: Short = Short.MIN_VALUE

        // 머리말 상한: 깨진 개수로 몇 GB를 잡다가 OutOfMemoryError가 나지 않게 (파일 크기와도 맞춰 본다)
        private const val MAX_ROWS = 2_000_000
        private const val MAX_INDEX = Short.MAX_VALUE.toInt()
        private const val HEADER_BYTES = 28
        private const val ROW_BYTES = 1 + 8 + 8 + 4 + 8 + 2 + 2 + 2 + 4 + 4
        private const val SCHEDULE_BYTES = 1 + 6 * 2
        private const val LINE_BYTES = 2 + 1 + 4
        private const val POINT_BYTES = 4 + 4
        private const val MAX_POINTS = 10_000_000
        private const val T_UNKNOWN: Short = -1
        private const val T_CLOSED: Short = -2

        private const val F_RESTROOM = 1 shl 0
        private const val F_DIAPER = 1 shl 1
        private const val F_NURSING = 1 shl 2
        private const val F_BELL = 1 shl 3
        private const val F_CCTV = 1 shl 4
        private const val F_ACCESSIBLE = 1 shl 5
        private const val F_FOOD = 1 shl 6
        private const val F_FUEL = 1 shl 7
        private const val F_EV = 1 shl 8
        private const val F_PET = 1 shl 9
        private const val F_OPEN24H = 1 shl 10
        private const val F_PARKABLE = 1 shl 11
        private const val F_OPEN_KNOWN = 1 shl 12
        private const val F_OPEN_NOW = 1 shl 13

        private infix fun Int.has(bit: Int) = this and bit != 0

        /** 변환된 행들과 고속도로 선형을 stops.bin 바이트로 */
        fun encode(records: List<StopRecord>, lines: List<MotorwayLine> = emptyList()): ByteArray {
            val n = records.size
            val roadIndex = LinkedHashMap<String, Int>()
            val scheduleIndex = LinkedHashMap<StopHours, Int>()
            for (r in records) {
                r.road?.let { roadIndex.getOrPut(it) { roadIndex.size } }
                r.hours?.let { scheduleIndex.getOrPut(it) { scheduleIndex.size } }
            }
            for (l in lines) roadIndex.getOrPut(l.road) { roadIndex.size }
            val pointCount = lines.sumOf { it.points.size / 2 }
            require(roadIndex.size <= MAX_INDEX && scheduleIndex.size <= MAX_INDEX) { "too many roads/schedules" }

            val strings = ByteArrayOutputStream(n * 32)
            val idOff = IntArray(n + 1)
            val nameOff = IntArray(n + 1)
            val roadOff = IntArray(roadIndex.size + 1)
            var pos = 0
            fun put(s: String): Int { val b = s.toByteArray(Charsets.UTF_8); strings.write(b); pos += b.size; return pos }
            for (i in 0 until n) { idOff[i] = pos; put(records[i].candidate.id) }
            idOff[n] = pos
            for (i in 0 until n) { nameOff[i] = pos; put(records[i].candidate.name) }
            nameOff[n] = pos
            roadIndex.keys.forEachIndexed { k, name -> roadOff[k] = pos; put(name) }
            roadOff[roadIndex.size] = pos
            val blob = strings.toByteArray()

            val size = HEADER_BYTES + n * ROW_BYTES + 8 + 4 * (roadIndex.size + 1) + 4 + blob.size + scheduleIndex.size * SCHEDULE_BYTES +
                lines.size * LINE_BYTES + 4 + pointCount * POINT_BYTES
            val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(MAGIC).putInt(VERSION).putInt(n).putInt(roadIndex.size).putInt(scheduleIndex.size).putInt(lines.size).putInt(pointCount)
            for (r in records) b.put(r.candidate.kind.ordinal.toByte())
            for (r in records) b.putDouble(r.candidate.lat)
            for (r in records) b.putDouble(r.candidate.lon)
            for (r in records) b.putInt(flagsOf(r.candidate))
            for (r in records) b.putDouble(r.candidate.walkDistanceM ?: Double.NaN)
            for (r in records) b.putShort(r.laneHeading?.also { require(it in 0..360) { "lane heading $it" } }?.toShort() ?: NO_LANE)
            for (r in records) b.putShort((r.road?.let(roadIndex::getValue) ?: -1).toShort())
            for (r in records) b.putShort((r.hours?.let(scheduleIndex::getValue) ?: -1).toShort())
            for (v in idOff) b.putInt(v)
            for (v in nameOff) b.putInt(v)
            for (v in roadOff) b.putInt(v)
            b.putInt(blob.size).put(blob)
            for (h in scheduleIndex.keys) b.put(when (h.h24) { null -> 0; false -> 1; true -> 2 }.toByte())
            for (h in scheduleIndex.keys) for (d in listOf(h.weekday, h.saturday, h.holiday)) {
                when (d) {
                    null -> b.putShort(T_UNKNOWN).putShort(T_UNKNOWN)
                    DayHours.Closed -> b.putShort(T_CLOSED).putShort(T_CLOSED)
                    is DayHours.Open -> b.putShort(d.from.toShort()).putShort(d.until.toShort())
                }
            }
            for (l in lines) b.putShort(roadIndex.getValue(l.road).toShort())
            for (l in lines) b.put(if (l.twoWay) 1 else 0)
            var start = 0
            for (l in lines) { b.putInt(start); start += l.points.size / 2 }
            b.putInt(start)
            for (l in lines) for (k in l.points.indices step 2) b.putFloat(l.points[k].toFloat())
            for (l in lines) for (k in l.points.indices step 2) b.putFloat(l.points[k + 1].toFloat())
            check(!b.hasRemaining()) { "size mismatch" }
            return b.array()
        }

        /** stops.bin 바이트 → 표. 배열을 통째로 읽으므로 행 수에 비례하는 짧은 시간만 든다 */
        fun decode(bytes: ByteArray): StopTable = decode(ByteBuffer.wrap(bytes))

        /**
         * 메모리 매핑한 파일(앱은 압축하지 않은 asset을 매핑한다)에서 바로 읽는다. 배열로 복사하므로 다 읽은 뒤 매핑은 놓아도 된다.
         * 형식이 다르거나 값이 하나라도 범위를 벗어나면 [StopTableFormatException].
         */
        fun decode(buffer: ByteBuffer): StopTable = try {
            decodeChecked(buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
        } catch (e: BufferUnderflowException) {
            throw StopTableFormatException("stops.bin is truncated", e)
        }

        private fun decodeChecked(b: ByteBuffer): StopTable {
            fun bad(msg: String): Nothing = throw StopTableFormatException("stops.bin: $msg")
            if (b.remaining() < 8 || b.int != MAGIC) bad("not a stops.bin")
            val version = b.int
            if (version != VERSION) bad("version $version, expected $VERSION (rest-engine buildStopTable로 다시 만들 것)")
            if (b.remaining() < HEADER_BYTES - 8) bad("header is truncated")
            val n = b.int
            val roadCount = b.int
            val scheduleCount = b.int
            val lineCount = b.int
            val pointCount = b.int
            if (n !in 0..MAX_ROWS) bad("row count $n")
            if (roadCount !in 0..MAX_INDEX || scheduleCount !in 0..MAX_INDEX) bad("road/schedule count $roadCount/$scheduleCount")
            if (lineCount !in 0..MAX_POINTS / 2 || pointCount !in 0..MAX_POINTS) bad("line/point count $lineCount/$pointCount")
            val tail = scheduleCount.toLong() * SCHEDULE_BYTES + lineCount.toLong() * LINE_BYTES + 4 + pointCount.toLong() * POINT_BYTES
            val fixed = n.toLong() * ROW_BYTES + 8 + 4L * (roadCount + 1) + 4 + tail
            if (fixed > b.remaining()) bad("$n rows do not fit in ${b.remaining()} bytes")

            val kinds = ByteArray(n).also { b.get(it) }
            for (i in 0 until n) if (kinds[i] !in 0 until KINDS.size) bad("row $i: kind ${kinds[i]}")
            val lats = doubles(b, n)
            val lons = doubles(b, n)
            for (i in 0 until n) {
                if (!(lats[i] in -90.0..90.0 && lons[i] in -180.0..180.0)) bad("row $i: position ${lats[i]},${lons[i]}")
            }
            val flags = IntArray(n).also { b.asIntBuffer().get(it); b.position(b.position() + 4 * n) }
            val walks = doubles(b, n)
            for (i in 0 until n) if (!(walks[i].isNaN() || walks[i] in 0.0..1e6)) bad("row $i: walk ${walks[i]}")
            val lanes = shorts(b, n)
            for (i in 0 until n) if (lanes[i] != NO_LANE && lanes[i] !in 0..360) bad("row $i: lane ${lanes[i]}")
            val roads = shorts(b, n)
            for (i in 0 until n) if (roads[i] !in -1 until roadCount) bad("row $i: road ${roads[i]}")
            val schedules = shorts(b, n)
            for (i in 0 until n) if (schedules[i] !in -1 until scheduleCount) bad("row $i: schedule ${schedules[i]}")
            val idOff = ints(b, n + 1)
            val nameOff = ints(b, n + 1)
            val roadOff = ints(b, roadCount + 1)
            val blobLength = b.int
            if (blobLength < 0 || blobLength.toLong() + tail != b.remaining().toLong()) {
                bad("blob length $blobLength does not match the file")
            }
            // id들, 이름들, 도로명들이 blob 안에서 끊김 없이 이어져야 한다
            if (idOff[0] != 0 || idOff[n] != nameOff[0] || nameOff[n] != roadOff[0] || roadOff[roadCount] != blobLength) bad("string offsets")
            for (off in arrayOf(idOff, nameOff, roadOff)) for (k in 1 until off.size) if (off[k] < off[k - 1]) bad("string offsets go backwards")
            val blob = ByteArray(blobLength).also { b.get(it) }
            val roadNames = Array(roadCount) { String(blob, roadOff[it], roadOff[it + 1] - roadOff[it], Charsets.UTF_8) }

            val scheduleFlags = ByteArray(scheduleCount).also { b.get(it) }
            val times = shorts(b, scheduleCount * 6)
            val scheduleTable = Array(scheduleCount) { s ->
                val h24 = when (scheduleFlags[s].toInt()) { 0 -> null; 1 -> false; 2 -> true; else -> bad("schedule $s: flags ${scheduleFlags[s]}") }
                fun day(k: Int): DayHours? {
                    val from = times[s * 6 + k * 2]
                    val until = times[s * 6 + k * 2 + 1]
                    return when {
                        from == T_UNKNOWN && until == T_UNKNOWN -> null
                        from == T_CLOSED && until == T_CLOSED -> DayHours.Closed
                        from in 0 until DayHours.DAY && until in 0 until DayHours.DAY -> DayHours.Open(from.toInt(), until.toInt())
                        else -> bad("schedule $s: times $from-$until")
                    }
                }
                StopHours(h24, day(0), day(1), day(2))
            }
            val lineRoads = shorts(b, lineCount)
            for (k in 0 until lineCount) if (lineRoads[k] !in 0 until roadCount) bad("line $k: road ${lineRoads[k]}")
            val lineFlags = ByteArray(lineCount).also { b.get(it) }
            for (k in 0 until lineCount) if (lineFlags[k] !in 0..1) bad("line $k: flags ${lineFlags[k]}")
            val lineStarts = ints(b, lineCount + 1)
            if (lineStarts[0] != 0 || lineStarts[lineCount] != pointCount) bad("line offsets")
            for (k in 0 until lineCount) if (lineStarts[k + 1] - lineStarts[k] < 2) bad("line $k: fewer than 2 points")
            val pointLats = FloatArray(pointCount).also { b.asFloatBuffer().get(it); b.position(b.position() + 4 * pointCount) }
            val pointLons = FloatArray(pointCount).also { b.asFloatBuffer().get(it); b.position(b.position() + 4 * pointCount) }
            for (p in 0 until pointCount) {
                if (!(pointLats[p] in -90f..90f && pointLons[p] in -180f..180f)) bad("point $p: ${pointLats[p]},${pointLons[p]}")
            }
            if (b.hasRemaining()) bad("trailing bytes")
            return StopTable(n, kinds, lats, lons, flags, walks, lanes, roads, schedules, idOff, nameOff, blob, roadNames, scheduleTable,
                lineRoads, lineFlags, lineStarts, pointLats, pointLons)
        }

        private fun doubles(b: ByteBuffer, n: Int) = DoubleArray(n).also { b.asDoubleBuffer().get(it); b.position(b.position() + 8 * n) }
        private fun shorts(b: ByteBuffer, n: Int) = ShortArray(n).also { b.asShortBuffer().get(it); b.position(b.position() + 2 * n) }
        private fun ints(b: ByteBuffer, n: Int) = IntArray(n).also { b.asIntBuffer().get(it); b.position(b.position() + 4 * n) }

        private fun flagsOf(c: StopCandidate): Int {
            val f = c.facilities
            var v = 0
            if (f.restroom) v = v or F_RESTROOM
            if (f.diaperTable) v = v or F_DIAPER
            if (f.nursingRoom) v = v or F_NURSING
            if (f.emergencyBell) v = v or F_BELL
            if (f.cctv) v = v or F_CCTV
            if (f.accessible) v = v or F_ACCESSIBLE
            if (f.food) v = v or F_FOOD
            if (f.fuel) v = v or F_FUEL
            if (f.evCharger) v = v or F_EV
            if (f.petArea) v = v or F_PET
            if (f.open24h) v = v or F_OPEN24H
            if (c.parkable) v = v or F_PARKABLE
            c.openNow?.let { v = v or F_OPEN_KNOWN; if (it) v = v or F_OPEN_NOW }
            return v
        }
    }
}
