package dev.shimpyo.coach

import dev.shimpyo.engine.RankedStop
import dev.shimpyo.engine.StopCandidate

/**
 * 급해("화장실 급해") 흐름 하나를 통째로 든다 (UI 감사 #7·#8·#9, 서비스 감사 D7): 지금 요청, 음성으로 연 요청의 번호,
 * 들르기로 한 곳(고정), 엔진에 알릴 차례. 순수 상태 기계라 코어 없이 UrgentFlowTest로 지킨다. 시각은 경과 시간이다.
 *
 * 요청의 진행: LOCATING(위치 기다림) → SEARCHING(정차 후보 표 기다림) → READY / NO_RESULT. 위치가 [LOCATE_MS] 안에 없으면
 * NO_LOCATION이고, 그 뒤라도 위치가 생기면 찾는다. 결과는 떠 있는 동안 [REFRESH_MS]마다 다시 찾는다 (예전에는 한 번 찾고 끝이라
 * 지나친 곳이 남았다, UI #9).
 *
 * 엔진과의 관계: 결과가 처음 나오면 엔진에 급해를 알릴 차례가 된다([takeEngineIntent]: 코어가 다음 틱 입력에 UrgentRestroom을 넣는다).
 * 엔진은 필요를 '말한 것'으로 기록하고 1등 장소로 급해 질문을 낸다. 요청이 살아 있는 동안 찾은 곳([extras])을 엔진 후보에 더하고,
 * 들르기로 한 곳은 요청이 끝난 뒤에도 후보에 남긴다(고정: 경로 후보가 아니라서 빼면 엔진이 그곳을 잊는다).
 */
class UrgentFlow {
    /** 이번 틱에 할 일 */
    enum class Step { NONE, SEARCH, NO_LOCATION }

    /** 화면에 올릴 것. [results]는 READY·NO_RESULT일 때만 있다 */
    data class View(val requestId: Long, val state: UrgentState, val results: List<RankedStop>?)

    private class Session(val requestId: Long, val startedAtMs: Long) {
        var state = UrgentState.LOCATING
        var results: List<RankedStop> = emptyList()
        /** 찾은 가까운 화장실 (엔진 후보에 더한다: 엔진이 같은 목록으로 급해 질문을 만든다) */
        var near: List<StopCandidate> = emptyList()
        var searchedAtMs: Long? = null
        /** 엔진에 급해를 알렸는지 (첫 결과 때 한 번) */
        var engineTold = false
    }

    private class Pin(val stop: StopCandidate, var seenAtMs: Long)

    private var session: Session? = null
    /** 코어가 연 요청 번호 (음성 "화장실 급해"). 화면 번호(양수, 0 아님)와 겹치지 않게 음수 */
    private var voiceId = 0L
    private var pin: Pin? = null
    private var intentDue = false

    /** 요청이 살아 있는지 */
    val active: Boolean get() = session != null

    val view: View?
        get() = session?.let { s ->
            View(s.requestId, s.state, if (s.state == UrgentState.READY || s.state == UrgentState.NO_RESULT) s.results else null)
        }

    /** 고정한 곳 (테스트·로그용) */
    val pinned: StopCandidate? get() = pin?.stop

    // ------------------------------------------------------------ 요청

    /** 화면이 연 요청 (UiCommand.Urgent). 앞 요청이 있으면 바꾼다 */
    fun open(requestId: Long, nowMs: Long) {
        session = Session(requestId, nowMs)
        pin = null
    }

    /** 음성으로 연 요청. 번호를 돌려준다 */
    fun openByVoice(nowMs: Long): Long {
        val id = --voiceId
        open(id, nowMs)
        return id
    }

    /** 화면이 닫았다. 그 요청이었으면 true */
    fun dismiss(requestId: Long): Boolean {
        if (session?.requestId != requestId) return false
        session = null
        return true
    }

    /** 요청이 열려 있는데 "화장실 급해"를 또 말했다: 다음 틱에 바로 다시 찾는다 */
    fun sayAgain() {
        session?.searchedAtMs = null
    }

    /** [hasPosition]: 위치를 아는지, [dataLoaded]: 정차 후보 표를 읽었는지 */
    fun step(nowMs: Long, hasPosition: Boolean, dataLoaded: Boolean): Step {
        val s = session ?: return Step.NONE
        if (!hasPosition) {
            if (s.state == UrgentState.LOCATING && nowMs - s.startedAtMs >= LOCATE_MS) {
                s.state = UrgentState.NO_LOCATION
                return Step.NO_LOCATION
            }
            return Step.NONE
        }
        if (!dataLoaded) {
            s.state = if (nowMs - s.startedAtMs >= SEARCH_MS) UrgentState.NO_RESULT else UrgentState.SEARCHING
            return Step.NONE
        }
        val last = s.searchedAtMs
        return if (last == null || nowMs - last >= REFRESH_MS) Step.SEARCH else Step.NONE
    }

    /** 찾은 결과 ([Step.SEARCH]의 답). 처음이면 엔진에 알릴 차례가 되고 true를 돌려준다 */
    fun found(nowMs: Long, near: List<StopCandidate>, ranked: List<RankedStop>): Boolean {
        val s = session ?: return false
        s.near = near
        s.results = ranked
        s.searchedAtMs = nowMs
        s.state = if (ranked.isEmpty()) UrgentState.NO_RESULT else UrgentState.READY
        if (s.engineTold) return false
        s.engineTold = true
        intentDue = true
        return true
    }

    // ------------------------------------------------------------ 엔진

    /** 이번 틱 엔진 입력에 UrgentRestroom을 넣을 차례면 true (한 번만) */
    fun takeEngineIntent(): Boolean {
        val due = intentDue
        intentDue = false
        return due
    }

    /** 엔진 후보에 더할 것: 찾은 가까운 화장실과 들르기로 한 곳 */
    fun extras(): List<StopCandidate> {
        val near = session?.near.orEmpty()
        val p = pin?.stop ?: return near
        return if (near.isEmpty()) listOf(p) else near + p
    }

    /** 들르기가 성공했다. 요청이 끝났으면 true (화면은 같은 번호의 IDLE을 보고 닫는다) */
    fun onGoToSucceeded(stop: StopCandidate?, nowMs: Long): Boolean {
        val s = session ?: return false
        // 급해에서 고른 곳은 경로 후보에 없을 수 있다: 엔진이 들르기로 한 곳을 계속 보게 후보에 남긴다
        if (stop != null && s.near.any { it.id == stop.id }) pin = Pin(stop, nowMs)
        session = null
        return true
    }

    /** 틱마다 엔진의 추천을 본다: 엔진이 고정한 곳을 더는 추천하지 않으면(도착해 섰거나 엔진이 내렸다) 후보에서 뺀다 */
    fun onEngineNext(nextStopId: String?, nowMs: Long) {
        val p = pin ?: return
        if (nextStopId == p.stop.id) p.seenAtMs = nowMs else if (nowMs - p.seenAtMs > PIN_GRACE_MS) pin = null
    }

    /** 엔진의 주행 상태가 새로 시작됐다 (새 여정, 기록 지우기): 아직 넣지 못한 급해 의도는 버려진다 */
    fun onEngineReset() {
        intentDue = false
    }

    /** 세계(시연·실제)가 바뀌었다: 요청도 고정도 지난 세계의 것이다 */
    fun reset() {
        session = null
        pin = null
        intentDue = false
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
        /** 고정한 곳을 엔진이 추천하지 않은 채로 두는 시간 */
        const val PIN_GRACE_MS = 15_000L
    }
}
