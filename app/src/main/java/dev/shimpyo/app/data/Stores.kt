package dev.shimpyo.app.data

import android.content.Context
import android.util.Log
import dev.shimpyo.coach.HistoryPort
import dev.shimpyo.coach.MemoryPort
import dev.shimpyo.coach.ParkState
import dev.shimpyo.coach.SettingsPort
import dev.shimpyo.coach.StopLog
import dev.shimpyo.coach.TripHeartbeat
import dev.shimpyo.coach.TripStorePort
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.PromptStat
import dev.shimpyo.engine.TripProfile
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 사용자 설정. 탑승자, 자동 환기 허용, 음성 사용, 시연 모드.
 * 개인 정보는 기기 안(SharedPreferences, filesDir)에만 둔다. 서버로 보내지 않는다.
 */
class Settings(context: Context) : SettingsPort {
    private val prefs = context.getSharedPreferences("shimpyo", Context.MODE_PRIVATE)

    override var occupants: List<Occupant>
        get() = prefs.getString(KEY_OCCUPANTS, null)?.let(::parseOccupants) ?: listOf(Occupant("driver", OccupantType.ADULT, label = "운전자"))
        set(value) = prefs.edit().putString(KEY_OCCUPANTS, occupantsJson(value)).apply()

    /** 피로가 오르면 묻지 않고 외기로 바꿀지 (기본: 묻고 바꾼다) */
    override var autoFreshAir: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FRESH_AIR, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FRESH_AIR, value).apply()

    override var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    /** 운전자가 입력한 지난밤 수면 시간. 0이면 입력 안 함 */
    var driverSleepHours: Float
        get() = prefs.getFloat(KEY_SLEEP, 0f)
        set(value) = prefs.edit().putFloat(KEY_SLEEP, value).apply()

    /** 건강 관련 탑승자 유형 저장 동의. 화면(HealthConsent)이 같은 키에 쓴다 (개인정보 보호법 제23조) */
    override val healthConsent: Boolean
        get() = prefs.getBoolean(KEY_HEALTH_CONSENT, false)

    override fun tripProfile(start: Long, lastRestroomAt: Map<String, Long>) = TripProfile(
        occupants = occupants,
        tripStart = start,
        driverSleepHours = driverSleepHours.takeIf { it > 0f },
        lastRestroomAt = lastRestroomAt,
    )

    private companion object {
        const val KEY_OCCUPANTS = "occupants"
        const val KEY_AUTO_FRESH_AIR = "autoFreshAir"
        const val KEY_VOICE = "voice"
        const val KEY_SLEEP = "sleepHours"
        const val KEY_HEALTH_CONSENT = "healthConsent"
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

/**
 * 엔진의 개인 학습 상태(PersonalMemory)를 filesDir에 JSON으로 저장한다.
 * 읽을 수 없는 파일은 `memory.json.corrupt`로 옮겨 두고 빈 학습으로 시작한다: 그대로 두면 다음 저장이 덮어쓴다 (리뷰 R-F14).
 */
class MemoryStore(file: File) : MemoryPort {
    constructor(context: Context) : this(File(context.filesDir, "memory.json"))

    private val store = AtomicFile(file)

    /** 기록 지우기([clear])마다 오른다. 저장은 학습 상태를 꺼낼 때의 값을 들고 와서, 그 사이 지웠으면 쓰지 않는다 */
    override val generation: Long get() = store.generation

    override fun load(): PersonalMemory {
        val text = runCatching { store.readText() }.getOrElse { "" } ?: return PersonalMemory()
        return MemoryJson.decode(text) ?: PersonalMemory().also {
            val kept = store.quarantine()
            Log.w(TAG, "memory.json unreadable: kept as ${kept?.name ?: "nothing (could not move)"}, starting with empty learning")
        }
    }

    // 쓰기는 AtomicFile이 한 번에 하나씩 한다. 예전에는 정차 기록 저장과 1분 저장이 같은 임시 파일을 동시에 써서
    // memory.json이 깨질 수 있었다(2026-11-11 리뷰). 성공하면 true
    override fun save(memory: PersonalMemory, generationAtSnapshot: Long): Boolean =
        store.write(MemoryJson.encode(memory), generationAtSnapshot)

    /** 학습 상태를 지운다. 이미 꺼내 둔 저장은 더 쓰지 않는다 */
    override fun clear() = store.clear()

    private companion object {
        const val TAG = "Shimpyo"
    }
}

/** 개인 학습 상태 JSON. 읽을 수 없으면 null */
object MemoryJson {
    fun encode(memory: PersonalMemory): String {
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
        return o.toString()
    }

    fun decode(text: String): PersonalMemory? = runCatching {
        val o = JSONObject(text)
        fun doubles(key: String): Map<String, List<Double>> = o.optJSONObject(key)?.let { m ->
            m.keys().asSequence().associateWith { k ->
                val a = m.getJSONArray(k)
                (0 until a.length()).map { a.getDouble(it) }
            }
        } ?: emptyMap()
        val stats = o.optJSONObject("promptStats")?.let { m ->
            m.keys().asSequence().associateWith { k ->
                val s = m.getJSONObject(k)
                PromptStat(accepted = s.optInt("a"), declined = s.optInt("d"))
            }
        } ?: emptyMap()
        PersonalMemory(
            restroomIntervalsMin = doubles("restroomIntervalsMin"),
            promptStats = stats,
            restroomCensoredMin = doubles("restroomCensoredMin"),
            restroomSuggestedMin = doubles("restroomSuggestedMin"),
        )
    }.getOrNull()
}

/**
 * 정차 기록 (stops.json, 최근 200건). 좌표는 남기지 않는다(StopRecorder가 null로 넘긴다).
 * 코치 스레드에서 부르므로 막지 않는다: 목록은 메모리에 두고, 쓰기·지우기는 앱의 쓰기 스레드([writer], 한 번에 하나)에 차례로 넘긴다.
 * 차례가 지켜지므로 지운 뒤에 덧붙인 기록이 지우기에 휩쓸리거나, 지우기 전 쓰기가 지운 뒤 파일을 되살리지 않는다.
 * 쓰기는 AtomicFile로 통째로 바꾼다 (서비스 감사 D14: 예전에는 바로 덮어쓰고, 지우기는 서비스가 파일 이름으로 지웠다).
 * 읽을 수 없는 파일은 `stops.json.corrupt`로 옮겨 두고 빈 기록으로 시작한다: 예전에는 '기록 없음'으로 읽고 다음 정차가
 * 그 파일을 한 건짜리로 덮어썼다 (리뷰 R-F14). StopHistoryStoreTest가 지킨다.
 */
class StopHistoryStore(file: File, private val writer: CoroutineScope) : HistoryPort {
    constructor(context: Context, writer: CoroutineScope) : this(File(context.filesDir, "stops.json"), writer)

    private val store = AtomicFile(file)
    private val lock = Any()
    private var cache: List<StopLog>? = null

    override fun load(): List<StopLog> = synchronized(lock) { cache ?: read().also { cache = it } }

    override fun append(log: StopLog): List<StopLog> {
        val list = synchronized(lock) { (load() + log).takeLast(MAX).also { cache = it } }
        writer.launch { if (!store.write(StopHistoryJson.encode(list))) Log.w(TAG, "stops.json write failed") }
        return list
    }

    override fun clear() {
        synchronized(lock) { cache = emptyList() }
        writer.launch { store.clear() }
    }

    private fun read(): List<StopLog> {
        val text = runCatching { store.readText() }.getOrElse { "" } ?: return emptyList()
        return StopHistoryJson.decode(text) ?: emptyList<StopLog>().also {
            val kept = store.quarantine()
            Log.w(TAG, "stops.json unreadable: kept as ${kept?.name ?: "nothing (could not move)"}, starting with an empty history")
        }
    }

    companion object {
        const val MAX = 200
        private const val TAG = "Shimpyo"
    }
}

/** 정차 기록 JSON. 읽을 수 없으면 null (빈 목록 `[]`과 다르다) */
object StopHistoryJson {
    fun encode(list: List<StopLog>): String = JSONArray().apply {
        list.forEach { s ->
            put(JSONObject().put("s", s.start).put("e", s.end).put("lat", s.lat ?: JSONObject.NULL).put("lon", s.lon ?: JSONObject.NULL).put("r", s.restroom).put("n", s.name ?: ""))
        }
    }.toString()

    fun decode(text: String): List<StopLog>? = runCatching {
        val a = JSONArray(text)
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
    }.getOrNull()
}

/**
 * 여정 맥박 (trip.json, world.md 7장). 재시작 뒤 여정을 이어 가는 데 쓴다. 좌표는 담지 않는다.
 * [save]는 코치 스레드에서 1분에 한 번쯤 부른다: 막지 않고 쓰기 스레드에 넘긴다. 맥박은 전체 스냅숏이라 밀린 값은 마지막 것만 쓴다.
 */
class TripStore(file: File, private val writer: CoroutineScope) : TripStorePort {
    constructor(context: Context, writer: CoroutineScope) : this(File(context.filesDir, "trip.json"), writer)

    private val store = AtomicFile(file)
    private val latest = AtomicReference<TripHeartbeat?>(null)

    override fun load(): TripHeartbeat? = store.readText()?.let(TripJson::decode)

    override fun save(heartbeat: TripHeartbeat) {
        // 쓰기를 기다리는 값이 없을 때만 새 쓰기를 띄운다 (있으면 그 쓰기가 이 값을 가져간다)
        if (latest.getAndSet(heartbeat) == null) {
            writer.launch { latest.getAndSet(null)?.let { if (!store.write(TripJson.encode(it))) Log.w("Shimpyo", "trip.json write failed") } }
        }
    }
}

/** 여정 맥박 JSON. 읽을 수 없거나 판이 다르면 null (새 여정으로 시작한다, TripSession.open) */
object TripJson {
    fun encode(h: TripHeartbeat): String = JSONObject()
        .put("version", h.version)
        .put("savedAt", h.savedAt)
        .put("tripStart", h.tripStart)
        .put("lastRestroomAt", JSONObject().apply { h.lastRestroomAt.forEach { (k, v) -> put(k, v) } })
        .put("restroomFallbackAt", h.restroomFallbackAt)
        .put("continuousDrivingMin", h.continuousDrivingMin)
        .put("park", h.park?.let { JSONObject().put("since", it.since).put("doorSeen", it.doorSeen) } ?: JSONObject.NULL)
        .toString()

    fun decode(text: String): TripHeartbeat? = runCatching {
        val o = JSONObject(text)
        val restroom = o.getJSONObject("lastRestroomAt")
        TripHeartbeat(
            savedAt = o.getLong("savedAt"),
            tripStart = o.getLong("tripStart"),
            lastRestroomAt = restroom.keys().asSequence().associateWith { restroom.getLong(it) },
            restroomFallbackAt = o.getLong("restroomFallbackAt"),
            continuousDrivingMin = o.getDouble("continuousDrivingMin"),
            park = if (o.isNull("park")) null else o.getJSONObject("park").let { ParkState(it.getLong("since"), it.getBoolean("doorSeen")) },
            version = o.getInt("version"),
        )
    }.getOrNull()
}
