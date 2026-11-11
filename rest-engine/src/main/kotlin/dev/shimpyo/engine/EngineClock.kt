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
}
