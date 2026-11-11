package dev.shimpyo.coach.fakes

import dev.shimpyo.coach.CoachClock
import dev.shimpyo.coach.ElapsedClock
import dev.shimpyo.coach.EngineFactory
import dev.shimpyo.coach.MemoryPort
import dev.shimpyo.coach.SettingsPort
import dev.shimpyo.coach.TripHeartbeat
import dev.shimpyo.coach.TripStorePort
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.TripProfile
import kotlin.random.Random

/*
 * world 트랙(CoachWorld, DemoRun, TripSession, WorldSwitch) 테스트용 가짜. 다른 트랙 가짜와 이름이 겹치지 않게 했다.
 */

/** 실제 시각(벽시계)과 경과 시각을 함께 움직인다. [jumpWall]은 벽시계만 바꾼다 (NTP 보정) */
class WorldClock(var wallMs: Long, var elapsed: Long = 1_000_000L) : CoachClock, ElapsedClock {
    override fun now(): Long = wallMs
    override fun elapsedMs(): Long = elapsed

    fun advance(ms: Long) {
        wallMs += ms
        elapsed += ms
    }

    fun jumpWall(ms: Long) {
        wallMs += ms
    }
}

val DRIVER = Occupant("driver", OccupantType.ADULT, label = "운전자")
val KID = Occupant("kid", OccupantType.CHILD, ageYears = 7, label = "아이")

/** 설정 저장소. 탑승자를 쓴 횟수를 센다 (시연 중 설정이 바뀌지 않았는지 보려고) */
class FakeSettings(
    occupants: List<Occupant> = listOf(DRIVER),
    var sleepHours: Float? = null,
    override var autoFreshAir: Boolean = false,
    override var voiceEnabled: Boolean = true,
    override var healthConsent: Boolean = false,
) : SettingsPort {
    var occupantWrites = 0
        private set

    override var occupants: List<Occupant> = occupants
        set(value) {
            field = value
            occupantWrites++
        }

    override fun tripProfile(start: Long, lastRestroomAt: Map<String, Long>) =
        TripProfile(occupants = occupants, tripStart = start, driverSleepHours = sleepHours, lastRestroomAt = lastRestroomAt)
}

/** memory.json 대신. 지운 뒤에는 그 전 세대의 저장을 버린다 (MemoryStore와 같은 규칙) */
class InMemoryMemory(var stored: PersonalMemory = PersonalMemory()) : MemoryPort {
    override var generation: Long = 0L
        private set
    val saves = mutableListOf<PersonalMemory>()

    override fun load(): PersonalMemory = stored

    override fun save(memory: PersonalMemory, generationAtSnapshot: Long): Boolean {
        if (generationAtSnapshot != generation) return false
        stored = memory
        saves += memory
        return true
    }

    override fun clear() {
        stored = PersonalMemory()
        generation++
    }
}

/** trip.json 대신 */
class InMemoryTripStore(var stored: TripHeartbeat? = null) : TripStorePort {
    val saves = mutableListOf<TripHeartbeat>()

    override fun load(): TripHeartbeat? = stored

    override fun save(heartbeat: TripHeartbeat) {
        stored = heartbeat
        saves += heartbeat
    }
}

/** 재현 가능한 엔진 (톰프슨 샘플링 난수 고정) */
fun seededEngines(seed: Int = 42) = EngineFactory { profile, memory, config -> CoachEngine(config = config, profile = profile, memory = memory, random = Random(seed)) }
