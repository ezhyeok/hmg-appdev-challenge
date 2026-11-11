package dev.shimpyo.coach

import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.RankedStop
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind

/*
 * CoachCore가 쓰는 작은 순수 규칙들. 코어(액터) 밖에서 단위 테스트로 지킨다 (CoreRulesTest).
 * 시각은 모두 실제 경과 시간(ElapsedClock)이다: 시연 전환을 넘어 이어지는 값이라 코치 시각(시연이면 가상)과 섞지 않는다.
 */

/**
 * 외기 전환(환기)의 문과 되돌리기 (서비스 감사 D16, rest-engine README '앱 연동 메모').
 * - 자동 환기는 마지막 시도 뒤 [GAP_MS](10분) 안에는 다시 하지 않는다. 경과 시간으로 잰다: 예전에는 코치 시각이라
 *   시연을 끈 뒤 자동 환기가 몇 시간씩 막혔다(서비스 감사 D3·D8).
 * - 우리가 바꾼 외기는 [REVERT_MS](1분 30초) 뒤 내기로 되돌린다(엔진 README: 1~2분 뒤). 그 사이 사람이 이미 내기로 돌렸으면
 *   (recirculation == true) 건드리지 않는다.
 */
class FreshAirGate {
    private var lastAttemptMs: Long? = null
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

    /** 외기 전환을 보냈다 (자동이든 질문 수락이든) */
    fun attempted(nowMs: Long) {
        lastAttemptMs = nowMs
    }

    /** 외기로 바뀐 것을 확인했다 */
    fun switched(nowMs: Long) {
        revertAtMs = nowMs + REVERT_MS
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
 * 급해 요청 하나의 진행 (UI 감사 #7·#8·#9, 서비스 감사 D7). 코어가 들고 명령·틱마다 [advance]한다.
 * LOCATING(위치 기다림) → SEARCHING(정차 후보 표 기다림) → READY / NO_RESULT. 위치가 [LOCATE_MS] 안에 없으면 NO_LOCATION.
 * 결과는 화면에 떠 있는 동안 [REFRESH_MS]마다 다시 찾는다 (예전에는 한 번 찾고 끝이라 지나친 곳이 남았다, UI #9).
 * NO_LOCATION에서도 위치가 생기면 이어서 찾는다 (화면을 닫지 않았으면 기다린 보람이 있다).
 */
class UrgentSession(val requestId: Long, val startedAtMs: Long) {
    var state: UrgentState = UrgentState.LOCATING
        private set
    var results: List<RankedStop> = emptyList()
        private set
    /** 찾은 가까운 화장실 (엔진 후보에 더한다: 엔진이 같은 목록으로 급해 질문을 만들고, 수락한 곳을 추적한다) */
    var near: List<StopCandidate> = emptyList()
        private set
    private var searchedAtMs: Long? = null
    /** 엔진에 급해를 알렸는지 (첫 결과 때 한 번) */
    var engineTold = false
        private set

    /** 이번 틱에 할 일 */
    enum class Step { NONE, SEARCH, NO_LOCATION }

    /** [hasPosition]: 위치를 아는지, [dataLoaded]: 정차 후보 표를 읽었는지 */
    fun next(nowMs: Long, hasPosition: Boolean, dataLoaded: Boolean): Step {
        if (!hasPosition) {
            if (state == UrgentState.LOCATING && nowMs - startedAtMs >= LOCATE_MS) {
                state = UrgentState.NO_LOCATION
                return Step.NO_LOCATION
            }
            return Step.NONE
        }
        if (!dataLoaded) {
            state = if (nowMs - startedAtMs >= SEARCH_MS) UrgentState.NO_RESULT else UrgentState.SEARCHING
            return Step.NONE
        }
        val last = searchedAtMs
        return if (last == null || nowMs - last >= REFRESH_MS) Step.SEARCH else Step.NONE
    }

    /** 찾은 결과. 처음이면 true를 돌려준다 (엔진에 알릴 때) */
    fun found(nowMs: Long, near: List<StopCandidate>, ranked: List<RankedStop>): Boolean {
        this.near = near
        results = ranked
        searchedAtMs = nowMs
        state = if (ranked.isEmpty()) UrgentState.NO_RESULT else UrgentState.READY
        val first = !engineTold
        engineTold = true
        return first
    }

    /** 다음 틱에 바로 다시 찾는다 ("화장실 급해"를 또 말했다) */
    fun searchAgain() {
        searchedAtMs = null
    }

    companion object {
        /** 위치를 기다리는 시간. 화면의 응답 기다림(8초)과 맞춘다 */
        const val LOCATE_MS = 8_000L
        /** 정차 후보 표를 기다리는 시간 (보통 1초 안에 읽힌다. 표가 깨졌으면 끝내 안 읽힌다) */
        const val SEARCH_MS = 15_000L
        /** 결과를 다시 찾는 간격 */
        const val REFRESH_MS = 10_000L
        const val RADIUS_M = 5_000.0
        const val MAX = 15
        val KINDS = setOf(StopKind.HIGHWAY_REST_AREA, StopKind.DROWSY_SHELTER, StopKind.PUBLIC_RESTROOM, StopKind.GAS_STATION, StopKind.PUBLIC_PARKING)
    }
}

object CoreRules {
    /** 이보다 느리면 방위를 믿지 않는다 (km/h) */
    const val SLOW_KPH = 10f

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
