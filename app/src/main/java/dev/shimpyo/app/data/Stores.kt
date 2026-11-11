package dev.shimpyo.app.data

import android.content.Context
import dev.shimpyo.app.coach.StopLog
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.PromptStat
import dev.shimpyo.engine.TripProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 사용자 설정. 탑승자, 자동 환기 허용, 음성 사용, 시연 모드.
 * 개인 정보는 기기 안(SharedPreferences, filesDir)에만 둔다. 서버로 보내지 않는다.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("shimpyo", Context.MODE_PRIVATE)

    var occupants: List<Occupant>
        get() = prefs.getString(KEY_OCCUPANTS, null)?.let(::parseOccupants) ?: listOf(Occupant("driver", OccupantType.ADULT, label = "운전자"))
        set(value) = prefs.edit().putString(KEY_OCCUPANTS, occupantsJson(value)).apply()

    /** 피로가 오르면 묻지 않고 외기로 바꿀지 (기본: 묻고 바꾼다) */
    var autoFreshAir: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FRESH_AIR, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FRESH_AIR, value).apply()

    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    /** 운전자가 입력한 지난밤 수면 시간. 0이면 입력 안 함 */
    var driverSleepHours: Float
        get() = prefs.getFloat(KEY_SLEEP, 0f)
        set(value) = prefs.edit().putFloat(KEY_SLEEP, value).apply()

    fun tripProfile(now: Long, lastRestroomAt: Map<String, Long> = emptyMap()) = TripProfile(
        occupants = occupants,
        tripStart = now,
        driverSleepHours = driverSleepHours.takeIf { it > 0f },
        lastRestroomAt = lastRestroomAt,
    )

    private companion object {
        const val KEY_OCCUPANTS = "occupants"
        const val KEY_AUTO_FRESH_AIR = "autoFreshAir"
        const val KEY_VOICE = "voice"
        const val KEY_SLEEP = "sleepHours"
    }
}

fun occupantsJson(list: List<Occupant>): String = JSONArray().apply {
    list.forEach { o ->
        put(JSONObject().put("id", o.id).put("type", o.type.name).put("age", o.ageYears ?: JSONObject.NULL).put("label", o.label).put("override", o.intervalOverrideMin ?: JSONObject.NULL))
    }
}.toString()

fun parseOccupants(json: String): List<Occupant>? = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Occupant(
            id = o.getString("id"),
            type = OccupantType.valueOf(o.getString("type")),
            ageYears = if (o.isNull("age")) null else o.getInt("age"),
            label = o.optString("label"),
            intervalOverrideMin = if (o.isNull("override")) null else o.optDouble("override").takeIf { !it.isNaN() },
        )
    }
}.getOrNull()

/** 엔진의 개인 학습 상태(PersonalMemory)를 filesDir에 JSON으로 저장한다 */
class MemoryStore(context: Context) {
    private val file = File(context.filesDir, "memory.json")
    private val store = AtomicFile(file)

    /** 기록 지우기([clear])마다 오른다. 저장은 학습 상태를 꺼낼 때의 값을 들고 와서, 그 사이 지웠으면 쓰지 않는다 */
    val generation: Long get() = store.generation

    fun load(): PersonalMemory = runCatching {
        if (!file.exists()) return PersonalMemory()
        val o = JSONObject(file.readText())
        fun doubles(key: String): Map<String, List<Double>> = o.optJSONObject(key)?.let { m ->
            m.keys().asSequence().associateWith { k ->
                val a = m.getJSONArray(k)
                (0 until a.length()).map { a.getDouble(it) }
            }
        } ?: emptyMap()
        val intervals = doubles("restroomIntervalsMin")
        val stats = o.optJSONObject("promptStats")?.let { m ->
            m.keys().asSequence().associateWith { k ->
                val s = m.getJSONObject(k)
                PromptStat(accepted = s.optInt("a"), declined = s.optInt("d"))
            }
        } ?: emptyMap()
        PersonalMemory(
            restroomIntervalsMin = intervals,
            promptStats = stats,
            restroomCensoredMin = doubles("restroomCensoredMin"),
            restroomSuggestedMin = doubles("restroomSuggestedMin"),
        )
    }.getOrDefault(PersonalMemory())

    fun save(memory: PersonalMemory, generationAtSnapshot: Long = generation): Boolean {
        val o = JSONObject()
        fun putDoubles(key: String, map: Map<String, List<Double>>) {
            o.put(key, JSONObject().apply { map.forEach { (k, v) -> put(k, JSONArray(v)) } })
        }
        putDoubles("restroomIntervalsMin", memory.restroomIntervalsMin)
        putDoubles("restroomCensoredMin", memory.restroomCensoredMin)
        putDoubles("restroomSuggestedMin", memory.restroomSuggestedMin)
        o.put("promptStats", JSONObject().apply {
            memory.promptStats.forEach { (k, v) -> put(k, JSONObject().put("a", v.accepted).put("d", v.declined)) }
        })
        // 쓰기는 AtomicFile이 한 번에 하나씩 한다. 예전에는 정차 기록 저장과 1분 저장이 같은 임시 파일을 동시에 써서
        // memory.json이 깨질 수 있었다(2026-11-11 리뷰). 성공하면 true
        return store.write(o.toString(), generationAtSnapshot)
    }

    /** 학습 상태를 지운다. 이미 꺼내 둔 저장은 더 쓰지 않는다 */
    fun clear() = store.clear()
}

/** 정차 기록 (최근 200건) */
class StopHistoryStore(context: Context) {
    private val file = File(context.filesDir, "stops.json")

    fun load(): List<StopLog> = runCatching {
        if (!file.exists()) return emptyList()
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            StopLog(
                start = o.getLong("s"),
                end = o.getLong("e"),
                lat = if (o.isNull("lat")) null else o.getDouble("lat"),
                lon = if (o.isNull("lon")) null else o.getDouble("lon"),
                restroom = o.optBoolean("r"),
                name = o.optString("n").ifEmpty { null },
            )
        }
    }.getOrDefault(emptyList())

    fun append(log: StopLog): List<StopLog> {
        val list = (load() + log).takeLast(MAX)
        val a = JSONArray()
        list.forEach { s ->
            a.put(JSONObject().put("s", s.start).put("e", s.end).put("lat", s.lat ?: JSONObject.NULL).put("lon", s.lon ?: JSONObject.NULL).put("r", s.restroom).put("n", s.name ?: ""))
        }
        runCatching { file.writeText(a.toString()) }
        return list
    }

    private companion object {
        const val MAX = 200
    }
}
