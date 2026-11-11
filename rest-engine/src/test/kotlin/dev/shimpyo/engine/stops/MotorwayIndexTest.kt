package dev.shimpyo.engine.stops

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 지금 달리는 고속도로 맞추기 (데이터 감사 #3: 목적지 없이 다른 고속도로 시설이 앞 순위를 차지했다).
 * 표본은 tools/data/road_samples.py가 OSM 원래 해상도 차로 위에서 뽑고 GPS 같은 흔들림(위치 8m, 방위 8도)을 준 것이다.
 * 앱의 선형은 10m로 단순화한 것이라 표본과 같은 선이 아니다.
 */
class MotorwayIndexTest {
    private val dataDir = File("..", "app/src/main/assets/data")
    private val table by lazy { StopTable.decode(File(dataDir, "stops.bin").readBytes()) }
    private val index by lazy { MotorwayIndex(table) }

    private data class Sample(val lat: Double, val lon: Double, val heading: Double, val road: String)

    private val samples by lazy {
        javaClass.getResource("/stops/road_samples.csv")!!.readText().lines().drop(1).filter { it.isNotBlank() }.map {
            val f = it.split(',')
            Sample(f[0].toDouble(), f[1].toDouble(), f[2].toDouble(), f.getOrElse(3) { "" })
        }
    }

    @Test
    fun matchesTheExpresswayUnderTheCar() {
        val on = samples.filter { it.road.isNotEmpty() }
        var right = 0
        var wrong = 0
        val misses = HashMap<String, Int>()
        for (s in on) {
            val r = index.roadAt(s.lat, s.lon, s.heading)
            when {
                r < 0 -> misses.merge("(none) on ${s.road}", 1, Int::plus)
                table.roadName(r) == s.road -> right++
                else -> { wrong++; misses.merge("${table.roadName(r)} on ${s.road}", 1, Int::plus) }
            }
        }
        val n = on.size.toDouble()
        println("map matching: ${on.size} samples, right ${"%.1f".format(100 * right / n)}%, wrong ${"%.1f".format(100 * wrong / n)}%, " +
            "unknown ${"%.1f".format(100 * (on.size - right - wrong) / n)}%; ${misses.entries.sortedByDescending { it.value }.take(6)}")
        assertTrue(right / n >= 0.97, "right ${right / n}")
        assertTrue(wrong / n <= 0.01, "wrong ${wrong / n}")
    }

    @Test
    fun offTheExpresswayIsUnknown() {
        // 고속도로 옆 150m~2km (국도·시내): 옆 고속도로로 착각하면 그 길의 휴게소를 앞에 세운다
        val off = samples.filter { it.road.isEmpty() }
        val claimed = off.count { index.roadAt(it.lat, it.lon, it.heading) >= 0 }
        assertEquals(0, claimed, "고속도로가 아닌 곳 ${off.size}곳 중 $claimed 곳을 고속도로로 봤다")
    }

    @Test
    fun oppositeCarriagewayAndCrossingRoadsAreNotMatched() {
        // 경부고속도로 부산방향 위(안성휴게소 부근)에서 반대로 달리면 서울방향 차로와 맞아야 하고, 직각이면 아무것도 아니다
        val s = samples.first { it.road == "경부고속도로" }
        val fwd = index.match(s.lat, s.lon, s.heading)!!
        assertEquals("경부고속도로", table.roadName(fwd.roadId))
        assertEquals(-1, index.roadAt(s.lat, s.lon, (s.heading + 90) % 360), "직각으로 달리는 차는 이 고속도로 위가 아니다")
        assertEquals(-1, index.roadAt(Double.NaN, s.lon, s.heading))
    }

    @Test
    fun everyRoadWithRestStopsHasGeometry() {
        val lineRoads = (0 until table.lineCount).map(table::lineRoadId).toSet()
        val stopRoads = (0 until table.size).map(table::roadId).filter { it >= 0 }.toSet()
        assertTrue(lineRoads.containsAll(stopRoads), (stopRoads - lineRoads).map(table::roadName).toString())
    }
}
