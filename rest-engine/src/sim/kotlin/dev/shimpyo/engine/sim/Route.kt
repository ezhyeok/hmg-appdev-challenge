package dev.shimpyo.engine.sim

import dev.shimpyo.engine.StopKind
import java.io.File

/** 경로 위 정차 후보 (경부고속도로 서울→부산, 서울 기준 km) */
internal class StopDef(val idx: Int, val name: String, val kind: StopKind, val km: Double, val lat: Double, val lon: Double)

/** 정체 구간: [startKm, endKm)에서 kph로 달린다 */
internal class Jam(val startKm: Double, val endKm: Double, val kph: Double)

/**
 * 구간별 속도 모델. 정체 밖은 freeKph, 정체 안은 그 구간 속도. 시간·위치 계산은 구간 경계까지 정확히 적분한다.
 */
internal class Traffic(val jams: List<Jam>, val freeKph: Double, val endKm: Double) {

    fun speedAt(km: Double): Double {
        for (j in jams) if (km >= j.startKm && km < j.endKm) return j.kph
        return freeKph
    }

    fun inJam(km: Double): Boolean = jams.any { km >= it.startKm && km < it.endKm }

    /** fromKm → toKm 주행 시간 (시간) */
    fun hours(fromKm: Double, toKm: Double): Double {
        if (toKm <= fromKm) return 0.0
        var t = 0.0
        var x = fromKm
        for (j in jams) {
            if (j.endKm <= x) continue
            if (j.startKm >= toKm) break
            val freeEnd = minOf(j.startKm, toKm)
            if (freeEnd > x) { t += (freeEnd - x) / freeKph; x = freeEnd }
            val jamEnd = minOf(j.endKm, toKm)
            if (jamEnd > x) { t += (jamEnd - x) / j.kph; x = jamEnd }
        }
        if (toKm > x) t += (toKm - x) / freeKph
        return t
    }

    /** fromKm에서 hours시간 달린 뒤 위치 */
    fun advance(fromKm: Double, hours: Double): Double {
        var x = fromKm
        var rem = hours
        var guard = 0
        while (rem > 1e-15 && x < endKm && guard++ < 100) {
            val v = speedAt(x)
            var boundary = endKm
            for (j in jams) {
                if (j.startKm > x + 1e-12 && j.startKm < boundary) boundary = j.startKm
                if (x >= j.startKm && x < j.endKm && j.endKm < boundary) boundary = j.endKm
            }
            val toB = (boundary - x) / v
            if (toB >= rem) { x += rem * v; rem = 0.0 } else { x = boundary; rem -= toB }
        }
        return minOf(x, endKm)
    }
}

internal object RouteData {
    /** analysis.json → gyeongbu_south.stops[] (fromOriginKm, kind rest_area/shelter, 모두 화장실 있음) */
    fun load(analysisJson: File): Pair<List<StopDef>, Double> {
        @Suppress("UNCHECKED_CAST")
        val root = Json.parse(analysisJson.readText(Charsets.UTF_8)) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val g = root["gyeongbu_south"] as Map<String, Any?>
        val length = (g["lengthKm"] as Double)
        @Suppress("UNCHECKED_CAST")
        val raw = (g["stops"] as List<Map<String, Any?>>).sortedBy { it["fromOriginKm"] as Double }
        val stops = raw.mapIndexed { i, s ->
            StopDef(
                idx = i,
                name = s["name"] as String,
                kind = if (s["kind"] == "shelter") StopKind.DROWSY_SHELTER else StopKind.HIGHWAY_REST_AREA,
                km = s["fromOriginKm"] as Double,
                lat = s["lat"] as Double,
                lon = s["lon"] as Double,
            )
        }
        return stops to length
    }
}
