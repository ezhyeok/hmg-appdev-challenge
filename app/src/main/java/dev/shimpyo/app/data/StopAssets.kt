package dev.shimpyo.app.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import java.io.InputStreamReader
import java.time.LocalTime
import java.time.ZoneId

/**
 * assets/data의 JSON을 StopCandidate로 읽는다. 스키마는 tools/data/README.md
 * (키가 없으면 값이 없거나 모르는 것, 불리언은 1/0).
 * 행이 10만 개 가까이 되어 스트리밍(JsonReader)으로 읽는다.
 */
object StopAssets {
    private const val TAG = "Shimpyo"
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    /** 영업 시간 (급해 모드에서 '지금 열려 있는지' 판단) */
    data class Hours(val h24: Boolean?, val open: Int?, val close: Int?)

    /** id → 영업 시간. openNow는 시각에 따라 바뀌므로 질의할 때 계산한다 */
    val hours = HashMap<String, Hours>()

    /**
     * id → 그 휴게소·졸음쉼터로 들어가는 차로의 진행 방위(도, 북 0 시계방향). 양방향 시설은 없다.
     * 길안내 없이 달릴 때 반대 방향 차로의 휴게소를 거르는 데 쓴다 (tools/data/README.md `hdg`)
     */
    val laneHeading = HashMap<String, Int>()

    fun loadAll(context: Context): List<StopCandidate> {
        val out = ArrayList<StopCandidate>(110_000)
        read(context, "data/rest_areas.json") { o -> restArea(o) }?.let(out::addAll)
        read(context, "data/drowsy_shelters.json") { o -> shelter(o) }?.let(out::addAll)
        read(context, "data/restrooms.json") { o -> restroom(o) }?.let(out::addAll)
        read(context, "data/parkables.json") { o -> parkable(o) }?.let(out::addAll)
        Log.i(TAG, "stop assets loaded: ${out.size}")
        return out
    }

    /** 지금 열려 있는지. 24시간이면 true, 시간 범위가 있으면 계산, 모르면 null */
    fun openNow(id: String, nowMs: Long): Boolean? {
        val h = hours[id] ?: return null
        if (h.h24 == true) return true
        val open = h.open ?: return if (h.h24 == false) null else null
        val close = h.close ?: return null
        val t = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime()
        val m = t.hour * 60 + t.minute
        return if (open <= close) m in open until close else m >= open || m < close
    }

    // ------------------------------------------------------------ 종류별 변환

    private fun restArea(o: Map<String, Any?>): StopCandidate? {
        val c = base(o) ?: return null
        o.num("hdg")?.let { laneHeading[c.first] = it.toInt() }
        hours[c.first] = Hours(o.bool("h24") ?: true, minutes(o.str("open")), minutes(o.str("close")))
        return StopCandidate(
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
    }

    private fun shelter(o: Map<String, Any?>): StopCandidate? {
        val c = base(o) ?: return null
        o.num("hdg")?.let { laneHeading[c.first] = it.toInt() }
        // 졸음쉼터는 규정상 화장실·비상벨·CCTV를 갖춘다 (docs/research/ai-science.md 3-C). OSM에서 온 것은 모를 수 있다
        val wc = o.bool("wc") ?: true
        return StopCandidate(
            id = c.first, name = c.second, kind = StopKind.DROWSY_SHELTER, lat = c.third.first, lon = c.third.second,
            facilities = Facilities(restroom = wc, emergencyBell = true, cctv = true, open24h = true),
            parkable = true,
            openNow = true,
        )
    }

    private fun restroom(o: Map<String, Any?>): StopCandidate? {
        val c = base(o) ?: return null
        val oh = o.str("oh")
        hours[c.first] = Hours(o.bool("h24"), oh?.substringBefore('-')?.let(::minutes), oh?.substringAfter('-', "")?.let(::minutes))
        return StopCandidate(
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
    }

    private fun parkable(o: Map<String, Any?>): StopCandidate? {
        val c = base(o) ?: return null
        val k = o.str("k")
        val fuel = k == "fuel" || k == "lpg"
        if (!fuel) {
            // 주차장은 300m 안에 화장실이 있을 때만 '세우고 걸어가는' 후보가 된다
            val wcd = o.num("wcd") ?: return null
            hours[c.first] = Hours(o.bool("h24"), o.str("oh")?.substringBefore('-')?.let(::minutes), o.str("oh")?.substringAfter('-', "")?.let(::minutes))
            return StopCandidate(
                id = c.first, name = c.second, kind = StopKind.PUBLIC_PARKING, lat = c.third.first, lon = c.third.second,
                facilities = Facilities(restroom = true, open24h = o.bool("h24") == true),
                parkable = o.bool("pk") != false,
                walkDistanceM = wcd,
            )
        }
        // 주유소: 화장실 근거(wc)가 있는 곳만 화장실 후보로 쓴다
        if (o.bool("wc") != true) return null
        hours[c.first] = Hours(o.bool("h24"), null, null)
        return StopCandidate(
            id = c.first, name = c.second, kind = StopKind.GAS_STATION, lat = c.third.first, lon = c.third.second,
            facilities = Facilities(restroom = true, fuel = true, open24h = o.bool("h24") == true),
            parkable = true,
        )
    }

    // ------------------------------------------------------------ 도우미

    /** (id, 이름, (lat, lon)) */
    private fun base(o: Map<String, Any?>): Triple<String, String, Pair<Double, Double>>? {
        val id = o.str("id") ?: return null
        val lat = o.num("lat") ?: return null
        val lon = o.num("lon") ?: return null
        if (lat !in 33.0..39.0 || lon !in 124.0..132.0) return null
        return Triple(id, o.str("n") ?: "이름 없는 화장실", lat to lon)
    }

    private fun minutes(s: String?): Int? {
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

    /** 배열의 각 객체(평평한 키-값)를 읽어 변환한다. 파일이 없으면 null */
    private fun <T : Any> read(context: Context, path: String, map: (Map<String, Any?>) -> T?): List<T>? = try {
        context.assets.open(path).use { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                val out = ArrayList<T>()
                val obj = HashMap<String, Any?>(32)
                r.beginArray()
                while (r.hasNext()) {
                    obj.clear()
                    r.beginObject()
                    while (r.hasNext()) {
                        val name = r.nextName()
                        obj[name] = when (r.peek()) {
                            JsonToken.STRING -> r.nextString()
                            JsonToken.NUMBER -> r.nextString().toDoubleOrNull()
                            JsonToken.BOOLEAN -> r.nextBoolean()
                            JsonToken.NULL -> { r.nextNull(); null }
                            else -> { r.skipValue(); null }
                        }
                    }
                    r.endObject()
                    map(obj)?.let(out::add)
                }
                r.endArray()
                out
            }
        }
    } catch (e: java.io.FileNotFoundException) {
        null
    } catch (e: Exception) {
        Log.w(TAG, "asset $path failed: ${e.message}")
        null
    }
}
