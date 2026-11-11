package dev.shimpyo.coach

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.Occupant

/*
 * 여정(trip) 기준점과 그 수명. 엔진은 TripProfile의 tripStart가 같으면 주행 상태를 이어 가고 다르면 새 여정으로 본다.
 * 그래서 '지금 여정이 언제 시작했고, 마지막 화장실은 언제였고, 몇 분째 쉬지 않고 달리는지'를 엔진 밖 한 곳(TripSession)이
 * 들고 있어야 엔진을 다시 만들거나(기록 지우기·시연 전환·앱 재시작) 탑승자가 바뀌어도 여정이 이어진다.
 * 규칙과 근거는 docs/design/coach-layer/world.md.
 */

/**
 * 여정의 기준점. 모든 시각은 그 세계의 코치 시각(epoch ms, 시연이면 가상 시각)이다.
 */
data class TripAnchor(
    /** 여정 시작. 엔진은 이 값이 같으면 주행 상태를 이어 간다 (기상 시각을 모를 때 기상 추정에도 쓴다) */
    val tripStart: Long,
    /** 탑승자 id → 마지막 화장실 */
    val lastRestroomAt: Map<String, Long>,
    /** 맵에 없는 탑승자(나중에 탄 사람)의 마지막 화장실. 'NOW'가 아니라 이 여정이 아는 마지막 화장실이다 */
    val restroomFallbackAt: Long,
    /** 엔진을 새로 만들 때 휴식 리듬에 채울 연속 주행(분, TripProfile.continuousDrivingMin) */
    val continuousDrivingMin: Double,
) {
    /** 지금 탑승자들의 마지막 화장실 (TripProfile.lastRestroomAt) */
    fun lastRestroomFor(occupants: List<Occupant>): Map<String, Long> =
        occupants.associate { it.id to (lastRestroomAt[it.id] ?: restroomFallbackAt) }

    /** 화장실에 다녀왔다: 모든 탑승자(나중에 탈 사람 포함)의 마지막 화장실을 [at]까지 당긴다. 뒤로는 가지 않는다 */
    fun withRestroomAt(at: Long): TripAnchor {
        if (at <= restroomFallbackAt && lastRestroomAt.values.all { it >= at }) return this
        return copy(lastRestroomAt = lastRestroomAt.mapValues { maxOf(it.value, at) }, restroomFallbackAt = maxOf(restroomFallbackAt, at))
    }

    /** 가장 최근 화장실 */
    val latestRestroomAt: Long get() = maxOf(restroomFallbackAt, lastRestroomAt.values.maxOrNull() ?: Long.MIN_VALUE)
}

/**
 * 지금 정차(기어 P) 상태. 정차 감지기(StopDetector.parkState)가 만들고, TripSession이 맥박에 담아
 * 재시작 뒤 감지기와 엔진에 되돌린다 (서비스 감사 D12: 정차 중 재시작하면 정차가 사라지고 화장실 시계가 안 돌아갔다).
 * 감지기와 여정이 이 타입 하나를 함께 쓴다.
 */
data class ParkState(
    /** P로 바꾼 시각 (코치 시각) */
    val since: Long,
    /** 이번 정차 중에 문이 열렸는지 */
    val doorSeen: Boolean,
)

/** 1분마다(그리고 정차 사건마다) 저장하는 여정 맥박. 실제 세계만 저장한다. 시각은 실제 시각(epoch ms) */
data class TripHeartbeat(
    /** 저장한 실제 시각. 다음 시작 때 이 값과 지금의 차이가 '헤드유닛이 꺼져 있던 시간'이다 */
    val savedAt: Long,
    val tripStart: Long,
    val lastRestroomAt: Map<String, Long>,
    val restroomFallbackAt: Long,
    /** 저장 시점의 연속 주행(분). 정차 중이면 정차 시작 때 값이다 (정차 중에는 늘지 않는다) */
    val continuousDrivingMin: Double,
    /** 저장 시점의 정차. 달리는 중이면 null */
    val park: ParkState?,
    val version: Int = VERSION,
) {
    companion object {
        const val VERSION = 1
    }
}

/**
 * 여정 맥박 저장소 (앱: filesDir/trip.json, AtomicFile + JSON). Ports.kt와 같은 경계지만 world 트랙 파일에 둔다.
 * - [load]: 서비스 시작 때 한 번, 코어가 IO 스레드에서 부른다. 없거나 읽을 수 없으면 null.
 * - [save]: 코치 스레드에서 부른다. 막으면 안 된다: 구현은 쓰기를 IO 스레드로 넘기고 가장 마지막 값만 쓰면 된다
 *   (맥박은 전체 스냅숏이라 중간 값을 버려도 된다).
 * 좌표는 담지 않는다 (개인정보, docs/research/ai-science.md 8-7).
 */
interface TripStorePort {
    fun load(): TripHeartbeat?
    fun save(heartbeat: TripHeartbeat)
}

/**
 * 여정 규칙의 값. 엔진과 같아야 하는 값은 CoachConfig에서 읽는다 (근거·선택 이유: world.md '임계값').
 */
data class TripRules(
    /** P가 이만큼 이어지면 휴식(연속 주행 0). 맥박이 이보다 오래됐으면 그동안 차가 꺼져 서 있었다고 보고 휴식으로 센다 (AI §9-2) */
    val restMs: Long = ENGINE.restMinParkedMin * 60_000L,
    /** 문 열림 + P가 이만큼이면 화장실 정차 (엔진 StopTracker와 같다) */
    val restroomStopMs: Long = ENGINE.restroomStopMinParkedMin * 60_000L,
    /** 틱 사이가 이보다 길면 그 이상은 주행으로 세지 않는다 (엔진 maxUpdateGapS와 같다: 앱 정지·시연 중 멈춘 실제 엔진) */
    val maxTickGapMs: Long = ENGINE.maxUpdateGapS * 1_000L,
    /** P가 이만큼 이어진 뒤 출발하면 새 여정 (설계값, world.md) */
    val newTripParkMs: Long = 4 * 3_600_000L,
    /** 지켜보지 못한 정차 뒤 마지막 화장실을 모를 때: 출발 60분 전으로 본다 (AI §9-1 '모름이면 경과 60분에서 시작') */
    val unknownRestroomMs: Long = 60 * 60_000L,
    /** 아무 일 없을 때 맥박 저장 간격 (경과 시간) */
    val heartbeatEveryMs: Long = 60_000L,
    /** 저장된 시각이 지금보다 이만큼까지 뒤여도 믿는다 (NTP 보정) */
    val futureToleranceMs: Long = 60_000L,
    /** 이보다 긴 정차 기록은 시계가 섞인 기록으로 보고 버린다 */
    val maxStopMs: Long = 24 * 3_600_000L,
) {
    private companion object {
        val ENGINE = CoachConfig()
    }
}

/** 저장된 정차 기록(stops.json)을 읽을 때의 규칙 */
object StopHistory {
    /**
     * 믿을 수 없는 기록을 뺀다: 끝이 지금보다 뒤(예전 시연이 가상 시각으로 저장한 기록, 화면에 '12시간 50분 전'으로 보였다),
     * 끝이 시작보다 앞(실제 시각으로 시작해 시연 시각으로 끝난 정차), 하루보다 긴 정차.
     * 시연 기록은 이제 저장하지 않지만(CoachWorld.persistsHistory) 예전에 저장된 것이 남아 있을 수 있다 (서비스 감사 D3, 구조 리뷰 D4, UI #15)
     */
    fun sanitize(history: List<StopLog>, now: Long, rules: TripRules = TripRules()): List<StopLog> =
        history.filter { it.start <= it.end && it.end <= now + rules.futureToleranceMs && it.end - it.start <= rules.maxStopMs }

    /**
     * 화장실 정차의 마지막 화장실 시각: 정차 시작 + 3분 (끝보다 늦지 않게). 엔진이 잡는 시각(문 열림과 3분 문턱 중 늦은 쪽)의
     * 하한이라, 이 값을 프로필로 넘겨도 돌고 있는 엔진의 화장실 시계를 앞당기지 않는다 (게이지가 튀지 않는다)
     */
    fun restroomAt(log: StopLog, rules: TripRules = TripRules()): Long = minOf(log.end, log.start + rules.restroomStopMs)

    fun lastRestroomAt(history: List<StopLog>, rules: TripRules = TripRules()): Long? =
        history.filter { it.restroom }.maxOfOrNull { restroomAt(it, rules) }
}

/** 시작할 때 여정을 어떻게 정했는지 */
enum class TripOrigin {
    /** 새 여정 (첫 실행, 맥박 없음·손상, 4시간 넘게 서 있었음) */
    NEW,
    /** 끊김 없이 이어 감 (헤드유닛이 15분 안에 다시 켜짐: 앱 재시작·짧은 시동 끔). 연속 주행도 이어진다 */
    RESUMED,
    /** 같은 여정이지만 그 사이 15분 넘게 꺼져 있었다: 휴식으로 세고, 마지막 화장실은 모름 규칙(60분)으로 당긴다 */
    RESUMED_AFTER_BREAK,
}

/** 틱에서 생긴 여정 사건 */
sealed interface TripEvent {
    /** 오래 서 있다가 출발했다: 엔진에 새 tripStart의 프로필을 넘긴다 (엔진이 주행 상태를 새로 시작한다) */
    data class NewTrip(val anchor: TripAnchor) : TripEvent
}

/**
 * 한 세계의 여정. 실제 세계는 맥박을 저장하고 다음 시작 때 이어 가며, 시연 세계는 시나리오 기준점으로 시작해 저장하지 않는다.
 *
 * 하는 일
 * - 연속 주행 시간: 엔진 FatigueModel과 같은 규칙으로 센다 (P가 아니면 틱 간격만큼, 한 번에 최대 2분. P 15분이면 0).
 *   엔진이 이 값을 내놓지 않아서, 엔진을 다시 만들 때 휴식 리듬을 채울 값(TripProfile.continuousDrivingMin)을 여기서 센다.
 * - 마지막 화장실: 화장실 정차(정차 중 문 열림 + 3분, 또는 정차 기록)마다 앞으로 당긴다. 나중에 탄 사람도 'NOW'로 시작하지 않는다.
 * - 새 여정: P로 4시간 넘게 서 있다가 출발하면 [TripEvent.NewTrip] (밤새 세워 둔 차가 다음 날 아침 '25시간 깨어 있음'이 되지 않게).
 * - 맥박: 아무 일 없으면 1분(경과 시간)에 한 번, 정차 시작·문 열림·화장실·휴식·출발·새 여정 때는 바로 저장한다.
 *
 * 코치 액터 안에서만 부른다 (스레드 안전하지 않다). 시각 [now]는 그 세계의 코치 시각이고, 저장 간격만 [elapsed]로 잰다.
 */
class TripSession private constructor(
    anchor: TripAnchor,
    /** 시작할 때 이어받은 정차. 코어가 정차 감지기에 되돌리고, 엔진은 이 정차부터 시작한다 (CoachWorld.newEngine) */
    val restoredPark: ParkState?,
    val origin: TripOrigin,
    openedAt: Long,
    private val elapsed: ElapsedClock,
    private val store: TripStorePort?,
    private val rules: TripRules,
) {
    private var base = anchor
    private var continuousMs = (anchor.continuousDrivingMin * 60_000.0).toLong()
    private var lastNow = openedAt
    private var lastSaveElapsed: Long? = null

    /** 마지막 틱의 정차 상태 (시연에서 돌아올 때 정차 감지기에 되돌린다) */
    var park: ParkState? = restoredPark
        private set

    /** 지금 기준점 (연속 주행은 지금까지 센 값) */
    val anchor: TripAnchor get() = base.copy(continuousDrivingMin = continuousMs / 60_000.0)

    /** 매 틱. [park]는 정차 감지기의 지금 정차 (달리는 중이면 null) */
    fun onTick(now: Long, park: ParkState?): TripEvent? {
        // 엔진과 같이: 시계가 뒤로 가면 0, 틱 사이가 길면 최대 2분만 주행으로 센다
        val dt = (now - lastNow).coerceIn(0L, rules.maxTickGapMs)
        lastNow = now
        val prev = this.park
        var urgentSave = park != prev
        var event: TripEvent? = null
        if (park == null) {
            if (prev != null && now - prev.since >= rules.newTripParkMs) {
                base = newTrip(now)
                continuousMs = 0L
                event = TripEvent.NewTrip(anchor)
            } else {
                continuousMs += dt
            }
        } else {
            val parkedMs = now - park.since
            if (parkedMs >= rules.restMs && continuousMs != 0L) {
                continuousMs = 0L
                urgentSave = true
            }
            if (park.doorSeen && parkedMs >= rules.restroomStopMs) urgentSave = moveRestroom(park.since + rules.restroomStopMs) || urgentSave
        }
        this.park = park
        save(now, force = urgentSave)
        return event
    }

    /** 정차 기록 (StopRecorder). 화장실 정차면 마지막 화장실을 당긴다. 이 세계 시각으로 믿을 수 없는 기록은 무시한다 */
    fun onStop(log: StopLog, now: Long) {
        if (!log.restroom || log.start > log.end || log.end > now + rules.futureToleranceMs) return
        if (moveRestroom(StopHistory.restroomAt(log, rules))) save(now, force = true)
    }

    /** 바로 저장 (시연으로 바꾸기 전, 서비스 종료) */
    fun flush(now: Long) = save(now, force = true)

    fun heartbeat(now: Long): TripHeartbeat = anchor.let {
        TripHeartbeat(now, it.tripStart, it.lastRestroomAt, it.restroomFallbackAt, it.continuousDrivingMin, park)
    }

    private fun moveRestroom(at: Long): Boolean {
        val moved = base.withRestroomAt(at)
        if (moved == base) return false
        base = moved
        return true
    }

    private fun newTrip(now: Long) = freshAnchor(now, base.latestRestroomAt, rules)

    private fun save(now: Long, force: Boolean) {
        val s = store ?: return
        val e = elapsed.elapsedMs()
        val last = lastSaveElapsed
        if (!force && last != null && e - last < rules.heartbeatEveryMs) return
        s.save(heartbeat(now))
        lastSaveElapsed = e
    }

    companion object {
        /**
         * 실제 세계의 시작. 저장된 맥박 [saved]와 정차 기록 [history]로 이어 갈지 새로 시작할지 정한다 (서비스 감사 D8·D12).
         * - 맥박이 없거나 믿을 수 없음 → 새 여정
         * - 맥박 뒤로 흐른 시간 < 15분 → 그대로 이어 감. 달리던 중이었으면 그 시간도 주행으로 센다 (재시작 동안 차는 달렸다)
         * - 15분 이상 → 그 사이 차가 꺼져 서 있었다(헤드유닛은 시동과 함께 꺼진다). 저장된 정차가 없으면 마지막 맥박부터 정차로 본다
         * - 그 정차가 4시간 이상 → 새 여정. 아니면 같은 여정을 정차부터 이어 간다 (휴식은 정차 길이로, 화장실은 모름 규칙으로)
         */
        fun open(
            saved: TripHeartbeat?,
            history: List<StopLog>,
            now: Long,
            elapsed: ElapsedClock,
            store: TripStorePort?,
            rules: TripRules = TripRules(),
        ): TripSession {
            val fromHistory = StopHistory.lastRestroomAt(StopHistory.sanitize(history, now, rules), rules)
            val hb = saved?.takeIf { usable(it, now, rules) }
                ?: return TripSession(freshAnchor(now, fromHistory, rules), null, TripOrigin.NEW, now, elapsed, store, rules)
            val gap = (now - hb.savedAt).coerceAtLeast(0L)
            val offline = gap >= rules.restMs
            val park = hb.park ?: if (offline) ParkState(since = hb.savedAt, doorSeen = false) else null
            if (park != null && now - park.since >= rules.newTripParkMs) {
                return TripSession(freshAnchor(now, maxOrNull(fromHistory, hb.restroomFallbackAt), rules), null, TripOrigin.NEW, now, elapsed, store, rules)
            }
            val continuous = when {
                park == null -> hb.continuousDrivingMin + gap / 60_000.0
                now - park.since >= rules.restMs -> 0.0
                else -> hb.continuousDrivingMin
            }
            var anchor = TripAnchor(hb.tripStart, hb.lastRestroomAt, hb.restroomFallbackAt, continuous)
            fromHistory?.let { anchor = anchor.withRestroomAt(it) }
            if (park != null && park.doorSeen && now - park.since >= rules.restroomStopMs) anchor = anchor.withRestroomAt(park.since + rules.restroomStopMs)
            if (offline) anchor = anchor.withRestroomAt(now - rules.unknownRestroomMs)
            return TripSession(anchor, park, if (offline) TripOrigin.RESUMED_AFTER_BREAK else TripOrigin.RESUMED, now, elapsed, store, rules)
        }

        /** 시연처럼 기준점이 정해진 여정. [store]가 없으면 저장하지 않는다 */
        fun started(anchor: TripAnchor, now: Long, elapsed: ElapsedClock, store: TripStorePort? = null, rules: TripRules = TripRules()): TripSession =
            TripSession(anchor, null, TripOrigin.NEW, now, elapsed, store, rules)

        /** 새 여정: 지금 출발. 마지막 화장실은 아는 것과 '모름이면 60분 전' 중 늦은 쪽 */
        private fun freshAnchor(now: Long, knownRestroom: Long?, rules: TripRules): TripAnchor {
            val restroom = maxOf(knownRestroom ?: Long.MIN_VALUE, now - rules.unknownRestroomMs).coerceAtMost(now)
            return TripAnchor(tripStart = now, lastRestroomAt = emptyMap(), restroomFallbackAt = restroom, continuousDrivingMin = 0.0)
        }

        private fun maxOrNull(a: Long?, b: Long): Long = if (a == null) b else maxOf(a, b)

        /** 맥박을 믿을 수 있는지: 판이 같고, 어떤 시각도 저장 시각 뒤가 아니며, 저장 시각이 지금보다 뒤가 아니다 */
        private fun usable(h: TripHeartbeat, now: Long, rules: TripRules): Boolean {
            val tol = rules.futureToleranceMs
            if (h.version != TripHeartbeat.VERSION) return false
            if (h.savedAt > now + tol) return false
            if (h.tripStart > h.savedAt + tol) return false
            if (h.restroomFallbackAt > h.savedAt + tol || h.lastRestroomAt.values.any { it > h.savedAt + tol }) return false
            if (h.park != null && h.park.since > h.savedAt + tol) return false
            val c = h.continuousDrivingMin
            return !c.isNaN() && c >= 0.0 && c <= 24 * 60.0
        }
    }
}
