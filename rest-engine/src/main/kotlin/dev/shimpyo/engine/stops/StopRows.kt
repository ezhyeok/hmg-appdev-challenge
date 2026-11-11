package dev.shimpyo.engine.stops

import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import java.time.LocalTime

/** 앱에 넣는 정차 후보 데이터 파일 (tools/data/README.md). 이 순서대로 테이블에 들어간다 */
enum class StopFile(val fileName: String) {
    REST_AREAS("rest_areas.json"),
    SHELTERS("drowsy_shelters.json"),
    RESTROOMS("restrooms.json"),
    PARKABLES("parkables.json"),
}

/** 영업 시간 (급해 모드에서 '지금 열려 있는지' 판단). 분 단위, 모르면 null */
data class StopHours(val h24: Boolean?, val open: Int?, val close: Int?)

/** 데이터 한 행을 변환한 결과. 차로 방위와 영업 시간은 후보 밖에 따로 둔다 */
data class StopRecord(val candidate: StopCandidate, val laneHeading: Int?, val hours: StopHours?)

/**
 * 데이터 행(평평한 키-값, 키가 없으면 값이 없거나 모르는 것, 불리언은 1/0) → 정차 후보.
 * 예전에 앱(StopAssets)이 JSON을 읽으며 하던 변환을 그대로 옮겼다. stops.bin을 만드는 도구와
 * 테스트(StopTableTest: JSON 행 = stops.bin 행)가 이 규칙 하나를 함께 쓴다.
 */
object StopRows {

    fun map(file: StopFile, o: Map<String, Any?>): StopRecord? = when (file) {
        StopFile.REST_AREAS -> restArea(o)
        StopFile.SHELTERS -> shelter(o)
        StopFile.RESTROOMS -> restroom(o)
        StopFile.PARKABLES -> parkable(o)
    }

    private fun restArea(o: Map<String, Any?>): StopRecord? {
        val c = base(o) ?: return null
        val candidate = StopCandidate(
            id = c.first, name = c.second, kind = StopKind.HIGHWAY_REST_AREA, lat = c.third.first, lon = c.third.second,
            facilities = Facilities(
                restroom = true,
                nursingRoom = o.bool("nr") == true,
                evCharger = o.bool("ev") == true,
                fuel = o.bool("gas") == true || o.bool("lpg") == true,
                food = o.bool("food") == true || o.bool("shop") == true,
                open24h = o.bool("h24") ?: true,
                // 휴게소는 CCTV·비상 대응 인력이 있는 공공 시설로 본다
                cctv = true,
            ),
            parkable = true,
            openNow = true,
        )
        return StopRecord(candidate, o.num("hdg")?.toInt(), StopHours(o.bool("h24") ?: true, minutes(o.str("open")), minutes(o.str("close"))))
    }

    private fun shelter(o: Map<String, Any?>): StopRecord? {
        val c = base(o) ?: return null
        // 졸음쉼터는 규정상 화장실·비상벨·CCTV를 갖춘다 (docs/research/ai-science.md 3-C). OSM에서 온 것은 모를 수 있다
        val wc = o.bool("wc") ?: true
        val candidate = StopCandidate(
            id = c.first, name = c.second, kind = StopKind.DROWSY_SHELTER, lat = c.third.first, lon = c.third.second,
            facilities = Facilities(restroom = wc, emergencyBell = true, cctv = true, open24h = true),
            parkable = true,
            openNow = true,
        )
        return StopRecord(candidate, o.num("hdg")?.toInt(), hours = null)
    }

    private fun restroom(o: Map<String, Any?>): StopRecord? {
        val c = base(o) ?: return null
        val oh = o.str("oh")
        val hours = StopHours(o.bool("h24"), oh?.substringBefore('-')?.let(::minutes), oh?.substringAfter('-', "")?.let(::minutes))
        val candidate = StopCandidate(
            id = c.first, name = c.second, kind = StopKind.PUBLIC_RESTROOM, lat = c.third.first, lon = c.third.second,
            facilities = Facilities(
                restroom = true,
                diaperTable = o.bool("dp") == true,
                emergencyBell = o.bool("bell") == true,
                cctv = o.bool("cctv") == true,
                accessible = o.bool("dis") == true,
                open24h = o.bool("h24") == true,
            ),
            parkable = o.bool("pk") == true,
            walkDistanceM = o.num("pkd"),
        )
        return StopRecord(candidate, laneHeading = null, hours = hours)
    }

    private fun parkable(o: Map<String, Any?>): StopRecord? {
        val c = base(o) ?: return null
        val k = o.str("k")
        val fuel = k == "fuel" || k == "lpg"
        if (!fuel) {
            // 주차장은 300m 안에 화장실이 있을 때만 '세우고 걸어가는' 후보가 된다
            val wcd = o.num("wcd") ?: return null
            val hours = StopHours(o.bool("h24"), o.str("oh")?.substringBefore('-')?.let(::minutes), o.str("oh")?.substringAfter('-', "")?.let(::minutes))
            val candidate = StopCandidate(
                id = c.first, name = c.second, kind = StopKind.PUBLIC_PARKING, lat = c.third.first, lon = c.third.second,
                facilities = Facilities(restroom = true, open24h = o.bool("h24") == true),
                parkable = o.bool("pk") != false,
                walkDistanceM = wcd,
            )
            return StopRecord(candidate, laneHeading = null, hours = hours)
        }
        // 주유소: 화장실 근거(wc)가 있는 곳만 화장실 후보로 쓴다
        if (o.bool("wc") != true) return null
        val candidate = StopCandidate(
            id = c.first, name = c.second, kind = StopKind.GAS_STATION, lat = c.third.first, lon = c.third.second,
            facilities = Facilities(restroom = true, fuel = true, open24h = o.bool("h24") == true),
            parkable = true,
        )
        return StopRecord(candidate, laneHeading = null, hours = StopHours(o.bool("h24"), null, null))
    }

    // ------------------------------------------------------------ 도우미

    /** (id, 이름, (lat, lon)). 좌표가 한국 밖이면 버린다 */
    private fun base(o: Map<String, Any?>): Triple<String, String, Pair<Double, Double>>? {
        val id = o.str("id") ?: return null
        val lat = o.num("lat") ?: return null
        val lon = o.num("lon") ?: return null
        if (lat !in 33.0..39.0 || lon !in 124.0..132.0) return null
        return Triple(id, o.str("n") ?: "이름 없는 화장실", lat to lon)
    }

    internal fun minutes(s: String?): Int? {
        if (s.isNullOrBlank() || !s.contains(':')) return null
        return runCatching { LocalTime.parse(s.trim().padStart(5, '0')).let { it.hour * 60 + it.minute } }
            .getOrElse { if (s.trim() == "24:00") 24 * 60 else null }
    }

    private fun Map<String, Any?>.str(k: String) = this[k] as? String
    private fun Map<String, Any?>.num(k: String) = (this[k] as? Number)?.toDouble()
    private fun Map<String, Any?>.bool(k: String): Boolean? = when (val v = this[k]) {
        is Number -> v.toInt() != 0
        is Boolean -> v
        else -> null
    }
}
