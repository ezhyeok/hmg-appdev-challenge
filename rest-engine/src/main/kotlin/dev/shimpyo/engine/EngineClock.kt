package dev.shimpyo.engine

import java.time.Instant
import java.time.ZoneId

/**
 * 엔진 내부의 단조 시계.
 *
 * 앱이 넘기는 `now`(벽시계)는 NTP 보정이나 시간대 변경으로 뒤로 갈 수 있다. 엔진은 그 차이 중
 * 양수만 더한 단조 시각(mono)으로 정차·쿨다운·경과 시간을 잰다. 시계가 뒤로 가면 그 update는 0초로 본다.
 * 처음에는 mono = now라서 프로필의 벽시계 시각(출발, 마지막 화장실)을 그대로 쓸 수 있다.
 */
internal class EngineClock {
    var started: Boolean = false
        private set

    /** 마지막으로 받은 벽시계 시각 */
    var wall: Long = 0L
        private set

    /** 단조 엔진 시각 (ms) */
    var mono: Long = 0L
        private set

    /** now를 반영하고 늘어난 시간(ms, 0 이상)을 돌려준다 */
    fun advance(now: Long): Long {
        if (!started) {
            started = true
            wall = now
            mono = now
            return 0L
        }
        val dt = now - wall
        wall = now
        if (dt <= 0L) return 0L
        mono += dt
        return dt
    }

    /** 벽시계 시각을 엔진 시각으로 바꾼다 (시계가 뒤로 간 만큼의 차이를 보정) */
    fun monoAt(wallTime: Long): Long = wallTime + (mono - wall)

    /** 상태를 바꾸지 않고 now 시점의 엔진 시각을 계산한다 */
    fun peek(now: Long): Long = if (!started) now else mono + maxOf(0L, now - wall)
}

/**
 * 벽시계 → 현지 시각(분). 시간대 오프셋을 한 시간 단위로 캐시해서 매초 ZonedDateTime을 만들지 않는다.
 */
internal class LocalClock(private val zone: ZoneId) {
    private var cachedHourKey = Long.MIN_VALUE
    private var offsetMs = 0L

    private fun offsetFor(wall: Long): Long {
        val key = Math.floorDiv(wall, 3_600_000L)
        if (key != cachedHourKey) {
            cachedHourKey = key
            offsetMs = zone.rules.getOffset(Instant.ofEpochMilli(wall)).totalSeconds * 1000L
        }
        return offsetMs
    }

    /** 0..1439 */
    fun minuteOfDay(wall: Long): Int =
        Math.floorMod(Math.floorDiv(wall + offsetFor(wall), 60_000L), 1440L).toInt()

    fun hourOfDay(wall: Long): Int = minuteOfDay(wall) / 60

    /** 자정부터 ms (0..86,399,999). 경계를 ms까지 맞춰 적분할 때 쓴다 */
    fun msOfDay(wall: Long): Long = Math.floorMod(wall + offsetFor(wall), DailyWindow.DAY_MS)
}

/**
 * 하루 중 [startHour, endHour) 시간 창 (현지 시각). 끝이 시작보다 이르면 자정을 넘는다: [start, 24) ∪ [0, end).
 * 시작과 끝이 같으면 빈 창이다.
 *
 * 구간과 창이 겹친 길이를 경계까지 정확히 센다. update가 1초마다 오든 몇 분 만에 오든 같은 값이 나와야
 * 창의 경계(20시, 07시)를 지나는 구간의 유효 시간이 맞는다 (RestroomNeedModel).
 */
internal class DailyWindow(private val local: LocalClock, startHour: Int, endHour: Int) {
    private val start = Math.floorMod(startHour, 24) * HOUR_MS
    private val end = Math.floorMod(endHour, 24) * HOUR_MS

    val isEmpty: Boolean get() = start == end

    fun contains(wall: Long): Boolean {
        val ms = local.msOfDay(wall)
        return when {
            start == end -> false
            start < end -> ms >= start && ms < end
            else -> ms >= start || ms < end
        }
    }

    /** wall부터 창의 다음 경계(시작 또는 끝)까지 ms. 항상 0보다 크다 (경계 위에 있으면 그다음 경계) */
    fun msToEdge(wall: Long): Long {
        val ms = local.msOfDay(wall)
        val toStart = Math.floorMod(start - ms - 1, DAY_MS) + 1
        val toEnd = Math.floorMod(end - ms - 1, DAY_MS) + 1
        return minOf(toStart, toEnd)
    }

    /** [fromWall, toWall) 가운데 창 안에 든 ms */
    fun overlapMs(fromWall: Long, toWall: Long): Long {
        if (start == end || toWall <= fromWall) return 0L
        var t = fromWall
        var sum = 0L
        var guard = 0
        while (t < toWall && guard++ < MAX_EDGES) {
            val seg = minOf(msToEdge(t), toWall - t)
            if (contains(t)) sum += seg
            t += seg
        }
        return sum
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        /** 한 번에 따라가는 경계 수 (하루에 둘). 한 달이 넘는 구간은 엔진에 없다 */
        private const val MAX_EDGES = 64
    }
}
