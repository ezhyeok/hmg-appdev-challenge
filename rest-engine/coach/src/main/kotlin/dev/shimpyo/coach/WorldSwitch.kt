package dev.shimpyo.coach

import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.TripProfile

/**
 * 지금 세계와 그 엔진·여정 한 벌. 코치 액터는 이 참조 하나를 들고, 바꿀 때는 틱 사이에 통째로 바꿔 끼운다
 * (구조 리뷰 D6·D11: 시연 전환이 시계·엔진·후보를 서로 다른 순간에 바꿨다).
 */
class ActiveWorld internal constructor(
    val world: CoachWorld,
    val engine: CoachEngine,
    val session: TripSession,
    /** 세계나 엔진을 바꿀 때마다 늘어난다. 질문 상태와 늦게 온 결과(옛 엔진의 질문 id)를 버리는 데 쓴다 */
    val generation: Int,
) {
    val clock: CoachClock get() = world.clock

    /** 이 세계의 지금 프로필 (탑승자·설정이 바뀌면 engine.setProfile로 넘긴다) */
    fun profile(settings: SettingsPort): TripProfile = world.profile(settings, session.anchor)

    internal fun with(world: CoachWorld = this.world, engine: CoachEngine = this.engine, generation: Int = this.generation) =
        ActiveWorld(world, engine, session, generation)
}

/** 바꾸기 전에 저장할 실제 학습 상태. 코어가 IO 스레드에서 MemoryPort.save(memory, generation)으로 쓴다 */
data class MemorySave(val memory: PersonalMemory, val generation: Long)

/** 세계 전환의 결과. 코어가 [kind]에 따라 상태를 초기화하고 [saveMemory]를 IO에서 저장한다 */
data class WorldChange(
    val active: ActiveWorld,
    val kind: Kind,
    /** 전환 전에 저장할 실제 학습 상태 (서비스 감사 D15: 시연을 켜면 마지막 1분 학습이 사라졌다) */
    val saveMemory: MemorySave? = null,
    /** kind가 WORLD일 때 정차 감지기에 되돌릴 정차 (null이면 비운다) */
    val restorePark: ParkState? = null,
) {
    enum class Kind {
        /** 바뀐 것 없음 */
        NONE,
        /** 같은 세계에서 엔진이나 여정이 바뀜: 질문 상태를 초기화한다 */
        ENGINE,
        /** 세계가 바뀜: 질문·후보·정차 감지·위치 덧씌우기를 모두 새 세계 기준으로 초기화한다 */
        WORLD,
    }
}

/**
 * 세계 전환을 맡는 단 한 곳 (구조 리뷰 3.8·B7, 서비스 감사 D4·D10·D15).
 * - 시연을 켜면 실제 엔진과 여정을 그대로 옆에 둔다. 끄면 그 엔진을 되돌린다 (예전에는 파일에서 새로 만들어 피로 연속성이 0이 됐다)
 * - 시연을 켜기 전에 실제 학습 상태를 저장 효과로 돌려주고 여정 맥박을 바로 저장한다
 * - 시연 중 다른 시연으로 바꿔도 옆에 둔 실제 세계는 처음 것 그대로다
 * - 탑승자·설정 변경, 기록 지우기, 새 여정은 지금 세계의 기준점으로 프로필을 만든다
 *
 * 파일을 읽거나 쓰지 않는다: 읽기는 코어가 IO에서 해서 [open]에 넘기고, 학습 저장은 [MemorySave]로 돌려준다
 * (여정 맥박만 TripStorePort.save로 넘긴다. 그 포트는 막지 않는다). 코치 액터 안에서만 부른다.
 */
class WorldSwitch(
    private val engines: EngineFactory,
    private val settings: SettingsPort,
    private val memory: MemoryPort,
    private val tripStore: TripStorePort,
    /** 실제 시각 (epoch ms) */
    private val wall: WallClock,
    private val elapsed: ElapsedClock,
    private val rules: TripRules = TripRules(),
) {
    private var current: ActiveWorld? = null
    /** 시연 중 옆에 둔 실제 세계 */
    private var keptReal: ActiveWorld? = null
    private var generation = 0

    /** 지금 세계. [open] 전에는 쓸 수 없다 */
    val active: ActiveWorld get() = checkNotNull(current) { "WorldSwitch.open 전" }

    /** 실제 세계 (시연 중이면 옆에 둔 것) */
    val real: ActiveWorld get() = keptReal ?: active

    /**
     * 서비스 시작 때 한 번. [heartbeat]·[loadedMemory]·[history]는 코어가 IO에서 읽어 넘긴다.
     * 저장된 여정을 이어 가거나 새로 시작하고, 정차 중 재시작이었으면 그 정차를 엔진과 정차 감지기에 되돌린다.
     */
    fun open(heartbeat: TripHeartbeat?, loadedMemory: PersonalMemory, history: List<StopLog>): WorldChange {
        check(current == null) { "WorldSwitch.open은 한 번만" }
        val world = CoachWorld.Real(wall)
        val now = world.clock.now()
        val session = TripSession.open(heartbeat, history, now, elapsed, tripStore.takeIf { world.persistsTrip }, rules)
        val engine = world.newEngine(engines, settings, loadedMemory, session.anchor, session.restoredPark)
        val a = ActiveWorld(world, engine, session, ++generation)
        current = a
        return WorldChange(a, WorldChange.Kind.WORLD, restorePark = session.restoredPark)
    }

    /** 시연 시작. 모르는 이름이나 null이면 시연 끄기 (예전 DemoDirector와 같다) */
    fun enterDemo(name: String?): WorldChange {
        val run = DemoRun.start(name, wallNow = wall.now(), elapsed = elapsed) ?: return exitDemo()
        val first = keptReal == null
        val real = this.real
        var save: MemorySave? = null
        if (first) {
            // 실제 세계를 옆에 두기 전에: 학습 상태는 저장 효과로, 여정 맥박은 바로 (시연 중 재시작해도 실제 여정이 남는다)
            if (real.world.persistsLearning) save = MemorySave(real.engine.memory(), memory.generation)
            real.session.flush(real.clock.now())
            keptReal = real
        }
        val world = CoachWorld.Demo(run)
        val session = TripSession.started(run.anchor, run.now(), elapsed, tripStore.takeIf { world.persistsTrip }, rules)
        val engine = world.newEngine(engines, settings, PersonalMemory(), session.anchor)
        val a = ActiveWorld(world, engine, session, ++generation)
        current = a
        return WorldChange(a, WorldChange.Kind.WORLD, saveMemory = save, restorePark = null)
    }

    /** 시연 끄기: 옆에 둔 실제 엔진과 여정을 되돌린다. 실제 세계면 아무것도 하지 않는다 */
    fun exitDemo(): WorldChange {
        val real = keptReal ?: return WorldChange(active, WorldChange.Kind.NONE)
        keptReal = null
        // 시연 중에 바뀐 설정(수면 시간 등)을 실제 엔진에도 넘긴다. 같은 tripStart라 주행 상태는 이어진다
        real.engine.setProfile(real.profile(settings))
        val a = real.with(generation = ++generation)
        current = a
        // 멈춰 있던 실제 엔진도 시연 전 정차를 기억한다. 정차 감지기도 같은 정차로 되돌려 기록과 엔진이 어긋나지 않게 한다
        return WorldChange(a, WorldChange.Kind.WORLD, restorePark = real.session.park)
    }

    /**
     * 탑승자 변경. 실제 세계는 설정에 저장하고, 시연은 그 시연에만 반영한다 (UI #15: 시연 탑승자가 설정에 저장됐다).
     * 같은 기준점이라 엔진은 여정을 이어 가고, 새로 탄 사람의 마지막 화장실은 여정이 아는 값이다.
     */
    fun setOccupants(list: List<Occupant>): ActiveWorld {
        val a = active
        val next = when (val w = a.world) {
            is CoachWorld.Real -> a.also { settings.occupants = list }
            is CoachWorld.Demo -> a.with(world = w.withOccupants(list))
        }
        current = next
        next.engine.setProfile(next.profile(settings))
        return next
    }

    /**
     * 건강 정보 동의를 거뒀다: 사용자가 넣은 건강 유형 탑승자를 모든 세계에서 뺀다. 동의는 세계와 무관한 설정 하나인데 탑승자는
     * 세계마다 따로라서, 시연 중에 동의를 끄면 옆에 둔 실제 세계의 임신부가 시연을 끈 뒤 동의 없이 돌아와 게이지의 기준이 됐다
     * (UI 닫힘 검증 N3). 설정에 저장된 실제 탑승자(시연 중이면 옆에 둔 세계의 것)와 시연에서 사용자가 고친 탑승자가 대상이다.
     * 시나리오가 정한 시연 탑승자는 사용자의 정보가 아니라 그대로 둔다. 바뀐 것이 있으면 true
     */
    fun dropHealthOccupants(): Boolean {
        var changed = false
        val saved = settings.occupants
        if (saved.any { HealthConsent.isHealth(it.type) }) {
            settings.occupants = HealthConsent.allowed(saved, consent = false)
            val real = this.real
            real.engine.setProfile(real.profile(settings))
            changed = true
        }
        val a = active
        val w = a.world
        if (w is CoachWorld.Demo && w.occupantsEdited) {
            val edited = w.occupants(settings)
            if (edited.any { HealthConsent.isHealth(it.type) }) {
                val next = a.with(world = w.withOccupants(HealthConsent.allowed(edited, consent = false)))
                current = next
                next.engine.setProfile(next.profile(settings))
                changed = true
            }
        }
        return changed
    }

    /** 설정(수면 시간 등)이 바뀌었다. 지금 세계의 기준점으로 프로필을 다시 넘긴다 (서비스 감사 D4: 시연 중이면 시연 여정이 리셋됐다) */
    fun profileChanged() {
        val a = active
        a.engine.setProfile(a.profile(settings))
    }

    /**
     * 기록 지우기 (파일은 코어가 지운다). 실제 엔진을 빈 학습 상태로 다시 만들되 여정 기준점과 지금 정차는 이어 간다.
     * 시연 중이면 옆에 둔 실제 엔진을 바꾼다: 그대로 두면 시연을 끈 뒤 자동 저장이 지운 학습을 되살린다.
     */
    fun clearLearning(): WorldChange {
        val real = this.real
        val engine = real.world.newEngine(engines, settings, PersonalMemory(), real.session.anchor, real.session.park)
        if (keptReal != null) {
            keptReal = real.with(engine = engine)
            return WorldChange(active, WorldChange.Kind.NONE)
        }
        val a = real.with(engine = engine, generation = ++generation)
        current = a
        return WorldChange(a, WorldChange.Kind.ENGINE)
    }

    /**
     * 매 틱 (엔진 update 전). [now]는 지금 세계의 시각, [park]는 정차 감지기의 지금 정차.
     * 오래 서 있다 출발해 새 여정이 되면 엔진에 새 프로필을 넘기고 ENGINE 변경을 돌려준다.
     */
    fun onTick(now: Long, park: ParkState?): WorldChange? {
        val a = active
        a.session.onTick(now, park) ?: return null
        // tripStart가 다른 프로필을 넘기면 엔진이 주행 상태를 새로 시작한다 (CoachEngine.setProfile)
        a.engine.setProfile(a.profile(settings))
        val next = a.with(generation = ++generation)
        current = next
        return WorldChange(next, WorldChange.Kind.ENGINE)
    }

    /**
     * 되돌린 정차(재시작, 시연 끄기)가 [endedAt]에 끝난 것으로 판정됐다: 차는 지금 P가 아니다. 지금 세계의 엔진도 그때 떠난 것으로
     * 맞춘다. 여정은 이어지는 [onTick](정차 없음)이 맞춘다. 판정 뒤 첫 엔진 update 전에 부른다
     */
    fun restoredParkEnded(endedAt: Long) {
        val a = active
        a.world.endPark(a.engine, endedAt)
    }

    /** 운전자가 고른 정차를 내비에 넣었다: 지금 세계의 여정에 적는다 (실제 세계면 맥박에 저장된다) */
    fun onAccepted(stopId: String) {
        val a = active
        a.session.onAccepted(stopId, a.clock.now())
    }

    /** 정차 기록 (StopRecorder). 기록 파일에 덧붙일지는 코어가 world.persistsHistory로 정한다 */
    fun onStop(log: StopLog, now: Long) = active.session.onStop(log, now)

    /** 주기 저장할 학습 상태. 학습을 저장하지 않는 세계(시연)면 null. 바뀌었는지는 코어가 마지막 저장과 비교한다 */
    fun memoryToSave(): MemorySave? {
        val a = active
        return if (a.world.persistsLearning) MemorySave(a.engine.memory(), memory.generation) else null
    }

    /** 서비스 종료: 실제 여정 맥박을 바로 저장한다 */
    fun flush() = real.session.flush(real.clock.now())
}
