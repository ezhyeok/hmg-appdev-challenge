package dev.shimpyo.engine.stops

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 지금 달리는 고속도로 찾기 (내비 없이, 지도 맞춤). [StopTable]의 고속도로 차로 선형(OSM, 10m로 단순화)에서
 * 차 위치 [MAX_OFFSET_M] 안에 있고 방향이 진행 방위와 [MAX_ANGLE_DEG] 안으로 맞는 차로 중 가장 가까운 것을 고른다.
 *
 * 왜 선형인가: 휴게소·졸음쉼터 행만으로 추정하면(가까운 시설들의 차로 방위가 내 방위와 맞는 도로) 시설이 방향별로
 * 15~20km에 하나라서 OSM 차로 위 3,000곳 표본에서 맞음 77%·틀림 8%(엄격하게 하면 63%·2.3%)가 한계였다.
 * 선형 맞춤은 차가 고속도로 위에 없으면(국도·시내) 모른다(-1)고 답하는 것도 장점이다 (행 추정은 옆 고속도로로 착각한다).
 *
 * 칸(0.01도) 색인은 선분의 경계 상자를 [MAX_OFFSET_M]만큼 넓혀 덮는 모든 칸에 선분을 넣으므로 질의는 차가 있는 칸 하나만 본다.
 */
class MotorwayIndex(private val table: StopTable) {
    private val pointLine = IntArray(if (table.lineCount == 0) 0 else table.lineEnd(table.lineCount - 1))
    private val cells: HashMap<Long, IntArray>

    /** 맞춘 결과: 도로 색인, 선분까지 거리(m), 방향 차이(도) */
    data class Match(val roadId: Int, val distanceM: Double, val angleDeg: Double)

    init {
        val build = HashMap<Long, IntList>()
        for (k in 0 until table.lineCount) {
            for (p in table.lineStart(k) until table.lineEnd(k)) pointLine[p] = k
            for (p in table.lineStart(k) until table.lineEnd(k) - 1) {
                val la1 = table.pointLat(p); val lo1 = table.pointLon(p)
                val la2 = table.pointLat(p + 1); val lo2 = table.pointLon(p + 1)
                val mLat = MAX_OFFSET_M / M_PER_DEG
                val mLon = MAX_OFFSET_M / (M_PER_DEG * max(cos(Math.toRadians(la1)), 0.2))
                for (y in cell(min(la1, la2) - mLat)..cell(max(la1, la2) + mLat)) {
                    for (x in cell(min(lo1, lo2) - mLon)..cell(max(lo1, lo2) + mLon)) {
                        build.getOrPut(key(y, x)) { IntList() }.add(p)
                    }
                }
            }
        }
        cells = HashMap<Long, IntArray>(build.size * 2).also { m -> build.forEach { (k, v) -> m[k] = v.toArray() } }
    }

    /** 지금 달리는 고속도로의 도로 색인([StopTable.roadName]). 고속도로 위가 아니거나 모르면 -1 */
    fun roadAt(lat: Double, lon: Double, headingDeg: Double): Int = match(lat, lon, headingDeg)?.roadId ?: -1

    fun match(lat: Double, lon: Double, headingDeg: Double): Match? {
        if (!lat.isFinite() || !lon.isFinite() || !headingDeg.isFinite()) return null
        val segs = cells[key(cell(lat), cell(lon))] ?: return null
        val kx = M_PER_DEG * cos(Math.toRadians(lat))
        var best: Match? = null
        for (p in segs) {
            // 차를 원점으로 한 국지 평면(m)
            val ax = (table.pointLon(p) - lon) * kx; val ay = (table.pointLat(p) - lat) * M_PER_DEG
            val bx = (table.pointLon(p + 1) - lon) * kx; val by = (table.pointLat(p + 1) - lat) * M_PER_DEG
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            if (len2 <= 0.0) continue
            val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d > MAX_OFFSET_M) continue
            val bearing = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
            var angle = angleDiff(bearing, headingDeg)
            if (table.lineTwoWay(pointLine[p])) angle = min(angle, 180.0 - angle)
            if (angle > MAX_ANGLE_DEG) continue
            val b = best
            if (b == null || d < b.distanceM - 0.5 || (abs(d - b.distanceM) <= 0.5 && angle < b.angleDeg)) {
                best = Match(table.lineRoadId(pointLine[p]), d, angle)
            }
        }
        return best
    }

    private fun cell(v: Double) = floor(v / CELL).toInt()
    private fun key(y: Int, x: Int) = (y.toLong() shl 32) or (x.toLong() and 0xffffffffL)

    private class IntList {
        private var a = IntArray(8)
        private var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray(): IntArray = a.copyOf(n)
    }

    companion object {
        /**
         * 차와 차로 중심선 사이 허용 거리. GPS 오차(5~10m) + 차로 폭(편도 4차로 약 15m의 절반) + 단순화 오차(10m) + OSM 오차.
         * 반대 방향 차로는 방향으로 걸러지므로 이 거리는 넉넉해도 된다.
         */
        const val MAX_OFFSET_M = 45.0
        /** 진행 방위와 차로 방향의 허용 차이. 굽은 길·GPS 방위 흔들림을 받되 교차하는 고속도로(대개 60도 이상)는 거른다 */
        const val MAX_ANGLE_DEG = 35.0
        private const val CELL = 0.01
        private const val M_PER_DEG = 111_195.0

        private fun angleDiff(a: Double, b: Double): Double {
            val d = abs(a - b) % 360.0
            return if (d > 180.0) 360.0 - d else d
        }
    }
}
