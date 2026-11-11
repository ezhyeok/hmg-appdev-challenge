package dev.shimpyo.coach

import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind

/*
 * CoachCore가 쓰는 작은 순수 규칙들. 코어(액터) 밖에서 단위 테스트로 지킨다 (CoreRulesTest).
 * 시각은 모두 실제 경과 시간(ElapsedClock)이다: 시연 전환을 넘어 이어지는 값이라 코치 시각(시연이면 가상)과 섞지 않는다.
 * 급해 요청은 UrgentFlow.kt, 음성 작업은 VoiceDesk.kt, 계획 갱신은 PlanKeeper.kt.
 */

/**
 * 외기 전환(환기)의 문과 되돌리기 (서비스 감사 D16, rest-engine README '앱 연동 메모').
 * - 전환은 한 번에 하나다 ([begin]이 false면 앞의 전환이 아직 끝나지 않았다).
 * - 자동 환기는 마지막 시도 뒤 [GAP_MS](10분) 안에는 다시 하지 않는다. 경과 시간으로 잰다: 예전에는 코치 시각이라
 *   시연을 끈 뒤 자동 환기가 몇 시간씩 막혔다(서비스 감사 D3·D8).
 * - 우리가 바꾼 외기는 [REVERT_MS](1분 30초) 뒤 내기로 되돌린다(엔진 README: 1~2분 뒤). 그 사이 사람이 이미 내기로 돌렸으면
 *   (recirculation == true) 건드리지 않는다.
 */
class FreshAirGate {
    private var lastAttemptMs: Long? = null
    /** 외기 전환 명령을 보내고 결과를 기다리는 중 */
    var busy = false
        private set
    /** 되돌릴 실제 시각. 우리가 외기로 바꾼 뒤에만 있다 */
    var revertAtMs: Long? = null
        private set

    /** 엔진이 자동 환기(cabinAction FRESH_AIR)를 냈을 때 지금 해도 되는지 */
    fun autoAllowed(nowMs: Long, recirculation: Boolean?): Boolean {
        // 이미 외기면 할 일이 없다. 모르면(신호 전) 시도해 본다
        if (recirculation == false) return false
        val last = lastAttemptMs ?: return true
        return nowMs - last >= GAP_MS
    }

    /** 외기 전환을 보낸다 (자동이든 질문 수락이든). 앞의 전환이 끝나지 않았으면 false: 보내지 않는다 */
    fun begin(nowMs: Long): Boolean {
        if (busy) return false
        busy = true
        lastAttemptMs = nowMs
        return true
    }

    /** 전환이 끝났다. [switched]: 외기로 바뀐 것을 확인했다 (되돌리기를 예약한다) */
    fun finished(nowMs: Long, switched: Boolean) {
        busy = false
        if (switched) revertAtMs = nowMs + REVERT_MS
    }

    /** 지금 내기로 되돌려야 하는지. true를 돌려주면 되돌리기 예약을 지운다 (한 번만) */
    fun takeRevert(nowMs: Long, recirculation: Boolean?): Boolean {
        val at = revertAtMs ?: return false
        if (nowMs < at) return false
        revertAtMs = null
        // 사람이 그 사이 내기로 돌렸다
        return recirculation != true
    }

    companion object {
        const val GAP_MS = 10 * 60_000L
        const val REVERT_MS = 90_000L
    }
}

/**
 * 학습 상태(memory.json)를 언제 저장할지. 쓰기 자체는 코어가 IO 효과로 한다.
 * - 바뀌었을 때만, 한 번에 하나, [EVERY_MS](1분)에 한 번까지. 시연 세계는 저장할 것이 없다(WorldSwitch.memoryToSave가 null)
 * - 시연을 켜기 직전의 저장(WorldChange.saveMemory)은 간격을 기다리지 않는다 ([started]만 부른다)
 */
class MemorySaver {
    private var saved: PersonalMemory? = null
    private var saving = false
    private var lastStartMs: Long? = null

    /** 시작할 때 파일에서 읽은 것 (파일과 같으니 다시 쓰지 않는다) */
    fun loaded(memory: PersonalMemory) {
        saved = memory
    }

    /** 기록 지우기: 파일이 비었다. 다음 변화는 다시 쓴다 */
    fun cleared() {
        saved = null
    }

    /** 지금 자동 저장할 것. [current]: 지금 세계의 학습 상태 (저장하지 않는 세계면 null). 간격 안이면 묻지 않는다 */
    fun due(nowMs: Long, current: () -> MemorySave?): MemorySave? {
        val last = lastStartMs
        if (saving || (last != null && nowMs - last < EVERY_MS)) return null
        return current()?.takeIf { it.memory != saved }
    }

    fun started(nowMs: Long) {
        saving = true
        lastStartMs = nowMs
    }

    fun finished(memory: PersonalMemory, ok: Boolean) {
        saving = false
        if (ok) saved = memory
    }

    /** 서비스가 끝날 때 마지막으로 쓸 것 (바뀌지 않았으면 null) */
    fun atShutdown(current: MemorySave?): MemorySave? = current?.takeIf { it.memory != saved }

    companion object {
        const val EVERY_MS = 60_000L
    }
}

/**
 * 건강 관련 탑승자 유형(임신, 과민성 방광·장)은 따로 동의한 경우에만 둔다 (개인정보 보호법 제23조 민감정보).
 * 화면(app Labels.isHealth)도 같은 세 유형을 쓴다.
 */
object HealthConsent {
    val TYPES: Set<OccupantType> = setOf(OccupantType.PREGNANT, OccupantType.OAB, OccupantType.IBS)

    fun isHealth(type: OccupantType): Boolean = type in TYPES

    /** 동의가 없으면 건강 유형 탑승자를 뺀 목록 */
    fun allowed(list: List<Occupant>, consent: Boolean): List<Occupant> = if (consent) list else list.filterNot { isHealth(it.type) }
}

object CoreRules {
    /** 이보다 느리면 방위를 믿지 않는다 (km/h) */
    const val SLOW_KPH = 10f

    /**
     * 차를 세우고 화장실을 쓸 수 있는 시설 종류. 급해 검색(UrgentFlow)과 정차 기록의 장소 이름(StopRecorder)이 같은 것을 쓴다
     * (기타 시설 이름으로 정차지를 부르지 않는다). 계획 후보는 휴게소·졸음쉼터만이다(CandidateTuning.PLANNED_KINDS)
     */
    val PARKABLE_KINDS: Set<StopKind> = setOf(
        StopKind.HIGHWAY_REST_AREA, StopKind.DROWSY_SHELTER, StopKind.PUBLIC_RESTROOM, StopKind.GAS_STATION, StopKind.PUBLIC_PARKING,
    )

    /**
     * 정차 후보 검색에 넘길 위치. 실제 위치이고 차가 서 있거나(P) 느리면 방위를 null로 둔다(0.0이나 오래된 방위로 앞뒤가
     * 뒤집히지 않게, 데이터 감사 #10·data.md 3.3 '부르는 쪽 계약'). 시연 위치([pos]가 [real]과 다른 것)는 시연 경로의 방위라 그대로 쓴다.
     */
    fun searchFix(pos: Fix?, real: Fix?, speedKph: Float?, gear: Gear): Fix? {
        if (pos == null) return null
        if (pos !== real) return pos
        val slow = gear == Gear.PARK || (speedKph != null && speedKph < SLOW_KPH)
        return if (slow && pos.heading != null) pos.copy(heading = null) else pos
    }

    /** 엔진 후보에 급해 결과·고정한 정차를 더한다. 같은 id는 경로 후보(거리를 더 잘 안다)를 남긴다. 직선거리는 지금 위치로 다시 잰다 */
    fun withExtras(planned: List<StopCandidate>, extras: List<StopCandidate>, pos: Fix?): List<StopCandidate> {
        if (extras.isEmpty()) return planned
        val ids = planned.mapTo(HashSet()) { it.id }
        val add = extras.filter { ids.add(it.id) }.map { c ->
            if (pos == null) c else c.copy(straightDistanceM = Geo.distanceM(pos.lat, pos.lon, c.lat, c.lon))
        }
        return if (add.isEmpty()) planned else planned + add
    }
}
