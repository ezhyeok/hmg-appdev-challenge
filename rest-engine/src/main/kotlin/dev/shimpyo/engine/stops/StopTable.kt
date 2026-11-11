package dev.shimpyo.engine.stops

import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId

/**
 * 정차 후보 6만여 곳을 열(column) 단위 배열로 들고 있는 표. 앱은 assets/data/stops.bin을 [decode]로 한 번에 읽고,
 * 질의 결과로 나간 행만 [candidate]로 StopCandidate를 만든다.
 *
 * 예전에는 JSON 13MB를 행마다 Map → StopCandidate로 만들어 에뮬레이터에서 18초 동안 GC가 돌았다(2026-11-11).
 * 같은 변환 규칙([StopRows])으로 만든 이진 표라서 결과는 같다(StopTableTest가 JSON 전 행과 비교한다).
 *
 * stops.bin 형식 (리틀엔디언, 버전 1): "SHPT", version:int, n:int, kind:byte[n], lat:double[n], lon:double[n],
 * flags:int[n], walk:double[n](NaN = 없음), lane:short[n](MIN = 없음), hoursFlags:byte[n], open:short[n], close:short[n]
 * (-1 = 없음), idOffset:int[n+1], nameOffset:int[n+1], blobLength:int, blob:UTF-8
 */
class StopTable private constructor(
    val size: Int,
    private val kinds: ByteArray,
    private val lats: DoubleArray,
    private val lons: DoubleArray,
    private val flags: IntArray,
    private val walks: DoubleArray,
    private val lanes: ShortArray,
    private val hoursFlags: ByteArray,
    private val opens: ShortArray,
    private val closes: ShortArray,
    private val idOffsets: IntArray,
    private val nameOffsets: IntArray,
    private val blob: ByteArray,
) {
    fun kind(i: Int): StopKind = KINDS[kinds[i].toInt()]
    fun lat(i: Int): Double = lats[i]
    fun lon(i: Int): Double = lons[i]
    fun id(i: Int): String = String(blob, idOffsets[i], idOffsets[i + 1] - idOffsets[i], Charsets.UTF_8)
    fun name(i: Int): String = String(blob, nameOffsets[i], nameOffsets[i + 1] - nameOffsets[i], Charsets.UTF_8)

    /** 그 휴게소·졸음쉼터로 들어가는 차로의 진행 방위(도). 양방향 시설이나 모르면 null */
    fun laneHeading(i: Int): Int? = lanes[i].takeIf { it != NO_LANE }?.toInt()

    fun hours(i: Int): StopHours? {
        val h = hoursFlags[i].toInt()
        if (h and HOURS_PRESENT == 0) return null
        val h24 = when ((h shr 1) and 3) { 1 -> false; 2 -> true; else -> null }
        return StopHours(h24, opens[i].takeIf { it >= 0 }?.toInt(), closes[i].takeIf { it >= 0 }?.toInt())
    }

    /** 지금 열려 있는지. 24시간이면 true, 시간 범위가 있으면 계산, 모르면 null */
    fun openNow(i: Int, nowMs: Long, zone: ZoneId = SEOUL): Boolean? {
        val h = hours(i) ?: return null
        if (h.h24 == true) return true
        val open = h.open ?: return null
        val close = h.close ?: return null
        val t = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime()
        val m = t.hour * 60 + t.minute
        return if (open <= close) m in open until close else m >= open || m < close
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
        private const val VERSION = 1
        private const val NO_LANE: Short = Short.MIN_VALUE
        private const val HOURS_PRESENT = 1

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

        /** 변환된 행들을 stops.bin 바이트로 */
        fun encode(records: List<StopRecord>): ByteArray {
            val n = records.size
            val strings = ByteArrayOutputStream(n * 32)
            val idOff = IntArray(n + 1)
            val nameOff = IntArray(n + 1)
            val idBytes = ArrayList<ByteArray>(n)
            val nameBytes = ArrayList<ByteArray>(n)
            for (r in records) {
                idBytes += r.candidate.id.toByteArray(Charsets.UTF_8)
                nameBytes += r.candidate.name.toByteArray(Charsets.UTF_8)
            }
            var pos = 0
            for (i in 0 until n) { idOff[i] = pos; strings.write(idBytes[i]); pos += idBytes[i].size }
            idOff[n] = pos
            for (i in 0 until n) { nameOff[i] = pos; strings.write(nameBytes[i]); pos += nameBytes[i].size }
            nameOff[n] = pos
            val blob = strings.toByteArray()

            val size = 12 + n * (1 + 8 + 8 + 4 + 8 + 2 + 1 + 2 + 2) + 4 * (n + 1) * 2 + 4 + blob.size
            val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(MAGIC).putInt(VERSION).putInt(n)
            for (r in records) b.put(r.candidate.kind.ordinal.toByte())
            for (r in records) b.putDouble(r.candidate.lat)
            for (r in records) b.putDouble(r.candidate.lon)
            for (r in records) b.putInt(flagsOf(r.candidate))
            for (r in records) b.putDouble(r.candidate.walkDistanceM ?: Double.NaN)
            for (r in records) b.putShort(r.laneHeading?.also { require(it in -32767..32767) }?.toShort() ?: NO_LANE)
            for (r in records) {
                val h = r.hours
                val tri = when (h?.h24) { null -> 0; false -> 1; true -> 2 }
                b.put(if (h == null) 0 else (HOURS_PRESENT or (tri shl 1)).toByte())
            }
            for (r in records) b.putShort((r.hours?.open ?: -1).toShort())
            for (r in records) b.putShort((r.hours?.close ?: -1).toShort())
            for (v in idOff) b.putInt(v)
            for (v in nameOff) b.putInt(v)
            b.putInt(blob.size).put(blob)
            check(!b.hasRemaining()) { "size mismatch" }
            return b.array()
        }

        /** stops.bin 바이트 → 표. 배열을 통째로 읽으므로 행 수에 비례하는 짧은 시간만 든다 */
        fun decode(bytes: ByteArray): StopTable = decode(ByteBuffer.wrap(bytes))

        /** 메모리 매핑한 파일(앱은 압축하지 않은 asset을 매핑한다)에서 바로 읽는다. 배열로 복사하므로 다 읽은 뒤 매핑은 놓아도 된다 */
        fun decode(buffer: ByteBuffer): StopTable {
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            require(b.int == MAGIC) { "not a stops.bin" }
            val version = b.int
            require(version == VERSION) { "stops.bin version $version, expected $VERSION (rest-engine buildStopTable로 다시 만들 것)" }
            val n = b.int
            val kinds = ByteArray(n).also { b.get(it) }
            val lats = DoubleArray(n).also { b.asDoubleBuffer().get(it); b.position(b.position() + 8 * n) }
            val lons = DoubleArray(n).also { b.asDoubleBuffer().get(it); b.position(b.position() + 8 * n) }
            val flags = IntArray(n).also { b.asIntBuffer().get(it); b.position(b.position() + 4 * n) }
            val walks = DoubleArray(n).also { b.asDoubleBuffer().get(it); b.position(b.position() + 8 * n) }
            val lanes = ShortArray(n).also { b.asShortBuffer().get(it); b.position(b.position() + 2 * n) }
            val hoursFlags = ByteArray(n).also { b.get(it) }
            val opens = ShortArray(n).also { b.asShortBuffer().get(it); b.position(b.position() + 2 * n) }
            val closes = ShortArray(n).also { b.asShortBuffer().get(it); b.position(b.position() + 2 * n) }
            val idOff = IntArray(n + 1).also { b.asIntBuffer().get(it); b.position(b.position() + 4 * (n + 1)) }
            val nameOff = IntArray(n + 1).also { b.asIntBuffer().get(it); b.position(b.position() + 4 * (n + 1)) }
            val blob = ByteArray(b.int).also { b.get(it) }
            require(!b.hasRemaining()) { "trailing bytes in stops.bin" }
            return StopTable(n, kinds, lats, lons, flags, walks, lanes, hoursFlags, opens, closes, idOff, nameOff, blob)
        }

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
