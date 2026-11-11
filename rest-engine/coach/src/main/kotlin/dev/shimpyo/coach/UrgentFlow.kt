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
 * 요청이 끝나는 때: 화면이 닫음([dismiss]), 이 요청의 목록에서 고른 곳으로 들르기가 성공함([onGoToFinished]), 세계 전환([reset]).
 * 목록과 상관없는 들르기(먼저 보낸 것, 계획 화면)가 성공해도 끝나지 않는다 (리뷰 R-F7a).
 *
 * 엔진에 알리기([takeEngineIntent]): 결과가 나오면 다음 틱 엔진 입력에 UrgentRestroom을 넣을 차례가 된다. 엔진은 필요를 '말한 것'으로
 * 기록하고 1등 장소로 급해 질문을 낸다. 요청이 살아 있을 때만 알린다(닫은 뒤에는 말하지 않는다, R-F7b). 목록에서 이미 고른 곳으로
 * 들르기가 가는 중이면 미룬다(방금 고른 곳을 "바로 안내할까요?"로 다시 묻지 않는다, UI 닫힘 검증 9b). "화장실 급해"를 또 말하면
 * 다시 찾은 결과로 다시 알린다([sayAgain], R-F7c). 엔진의 주행 상태가 새로 시작되면 다시 알린다([onEngineReset], R-F7d).
 *
 * 고정: 목록에서 고른 곳은 대개 경로 후보가 아니라서, 엔진 후보([extras])에 계속 넣어 줘야 엔진이 '들르기로 한 곳'을 잊지 않는다.
 * 들르기를 시작할 때부터 넣고(화면을 닫아도 남는다, 9c), 실패하면 뺀다. 성공한 뒤에는 지나쳤거나(직선거리가 가장 가까웠던 때보다
 * [PASSED_M] 넘게 멀어짐, 또는 [ARRIVED_M] 안까지 갔다가 벗어남) 엔진이 [PIN_GRACE_MS] 넘게 추천하지 않으면(도착해 섰다, 엔진이
 * 내렸다) 푼다. 예전에는 엔진이 추천을 멈출 때만 풀었는데, 고정이 후보를 대 주므로 엔진은 지나친 곳을 90분 동안 추천했다 (R-F1).
 *
 * @param seedMs 음성으로 연 요청 번호의 시작점 (코어를 만든 때의 경과 시간). 화면은 닫은 번호를 기억하므로 다시 뜬 코어가 앞 코어의
 *   번호를 또 쓰면 안 된다 (UI 닫힘 검증 N4)
 */
class UrgentFlow(seedMs: Long = 0L) {
    /** 이번 틱에 할 일 */
    enum class Step { NONE, SEARCH, NO_LOCATION }

    /** 화면에 올릴 것. [results]는 READY·NO_RESULT일 때만 있다 */
    data class View(val requestId: Long, val state: UrgentState, val results: List<RankedStop>?)

    private class Session(val requestId: Long, val startedAtMs: Long) {
        var state = UrgentState.LOCATING
        var results: List<RankedStop> = emptyList()
        /** 찾은 가까운 화장실 (엔진 후보에 더한다: 엔진이 같은 목록으로 급해 질문을 만든다) */
        var near: List<StopCandidate> = emptyList()
        /** 이 요청이 한 번이라도 목록에 올린 곳. 화면이 한 박자 늦은 목록에서 골라도 이 요청의 선택으로 본다 */
        val listed = HashSet<String>()
        var searchedAtMs: Long? = null
        /** 지금 결과로 엔진에 급해를 알렸는지 (알릴 차례를 만들었는지) */
        var engineTold = false
        /** 목록에서 골라 들르기가 가는 중인 곳 */
        var pickedId: String? = null
    }

    private class Pin(val stop: StopCandidate) {
        /** 엔진이 마지막으로 이곳을 추천한 때 */
        var seenAtMs = 0L
        /** 차가 가장 가까웠던 직선거리 (m) */
        var minDistM = Double.POSITIVE_INFINITY
    }

    private var session: Session? = null
    /** 코어가 연 요청 번호 (음성 "화장실 급해"). 화면 번호(양수, 0 아님)와 겹치지 않게 음수 */
    private var voiceId = -seedMs.coerceAtLeast(0L)
    /** 고른 곳: 내비에 넣는 중([picking])과 넣은 뒤([pin]). 새로 고른 곳이 실패하면 앞서 들르기로 한 곳은 그대로다 */
    private var picking: Pin? = null
    private var pin: Pin? = null
    private var intentDue = false

    /** 요청이 살아 있는지 */
    val active: Boolean get() = session != null

    val view: View?
        get() = session?.let { s ->
            View(s.requestId, s.state, if (s.state == UrgentState.READY || s.state == UrgentState.NO_RESULT) s.results else null)
        }

    /** 들르기로 한 곳 (내비에 넣은 뒤, 테스트·로그용) */
    val pinned: StopCandidate? get() = pin?.stop

    /** 지금 요청이 목록에 올린 곳인지 (화면의 들르기가 급해 목록에서 고른 것인지 가린다) */
    fun lists(stopId: String): Boolean = session?.listed?.contains(stopId) == true

    // ------------------------------------------------------------ 요청

    /** 화면이 연 요청 (UiCommand.Urgent). 앞 요청이 있으면 바꾼다. 들르기로 한 곳은 새로 고를 때까지 그대로 둔다 */
    fun open(requestId: Long, nowMs: Long) {
        session = Session(requestId, nowMs)
        intentDue = false
    }

    /** 음성으로 연 요청. 번호를 돌려준다 */
    fun openByVoice(nowMs: Long): Long {
        val id = --voiceId
        open(id, nowMs)
        return id
    }

    /** 화면이 닫았다. 그 요청이었으면 true. 아직 엔진에 알리지 않았으면 알리지 않는다 */
    fun dismiss(requestId: Long): Boolean {
        if (session?.requestId != requestId) return false
        session = null
        intentDue = false
        return true
    }

    /** 요청이 열려 있는데 "화장실 급해"를 또 말했다: 바로 다시 찾고, 그 결과로 엔진에 다시 알린다 (엔진이 1등 장소를 다시 묻는다) */
    fun sayAgain() {
        val s = session ?: return
        s.searchedAtMs = null
        s.engineTold = false
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

    /** 찾은 결과 ([Step.SEARCH]의 답). 엔진에 알릴 차례가 됐으면 true (처음, 또는 다시 말한 뒤) */
    fun found(nowMs: Long, near: List<StopCandidate>, ranked: List<RankedStop>): Boolean {
        val s = session ?: return false
        s.near = near
        near.forEach { s.listed += it.id }
        ranked.forEach { s.listed += it.stop.id }
        s.results = ranked
        s.searchedAtMs = nowMs
        s.state = if (ranked.isEmpty()) UrgentState.NO_RESULT else UrgentState.READY
        if (s.engineTold) return false
        s.engineTold = true
        intentDue = true
        return true
    }

    // ------------------------------------------------------------ 엔진

    /** 이번 틱 엔진 입력에 UrgentRestroom을 넣을 차례면 true (한 번만). 고른 곳으로 들르기가 가는 중이면 그 결과를 기다린다 */
    fun takeEngineIntent(): Boolean {
        val s = session
        if (!intentDue || s == null || s.pickedId != null) return false
        intentDue = false
        return true
    }

    /** 엔진의 주행 상태가 새로 시작됐다 (새 여정, 기록 지우기): 새 엔진은 급해를 모른다. 요청이 살아 있으면 다시 알린다 */
    fun onEngineReset() {
        intentDue = session?.engineTold == true
    }

    /** 엔진 후보에 더할 것: 찾은 가까운 화장실과 들르기로 한 곳 (넣는 중인 곳 포함). 같은 곳이 겹쳐도 된다 (CoreRules.withExtras) */
    fun extras(): List<StopCandidate> {
        val near = session?.near.orEmpty()
        val a = pin?.stop
        val b = picking?.stop
        if (a == null && b == null) return near
        return near + listOfNotNull(a, b)
    }

    // ------------------------------------------------------------ 들르기

    /** 들르기를 시작했다 (PromptEffect.StartGoTo). 이 요청의 목록에서 고른 곳이면 true: 그곳을 고정하고 결과를 기다린다 */
    fun onGoToStarted(stop: StopCandidate): Boolean {
        val s = session ?: return false
        if (!lists(stop.id)) return false
        s.pickedId = stop.id
        picking = Pin(stop)
        return true
    }

    /** 들르기가 끝났다 ([ok]: 내비 명령 성공). 이 요청에서 고른 곳이 성공해 요청이 끝났으면 true (화면은 같은 번호의 IDLE을 보고 닫는다) */
    fun onGoToFinished(stop: StopCandidate, ok: Boolean, nowMs: Long): Boolean {
        picking?.takeIf { it.stop.id == stop.id }?.let { p ->
            picking = null
            if (ok) {
                p.seenAtMs = nowMs
                pin = p
            }
        }
        val s = session ?: return false
        if (s.pickedId != stop.id) return false
        s.pickedId = null
        if (!ok) return false // 요청은 그대로다: 다시 고를 수 있고, 미뤄 둔 엔진 알림도 풀린다
        session = null
        intentDue = false
        return true
    }

    /**
     * 틱마다 차 위치([pos], 모르면 null)와 엔진의 추천을 본다. 들르기로 한 곳을 지나쳤거나 엔진이 더는 추천하지 않으면 고정을 푼다:
     * 그러면 후보에서 빠지고 엔진이 그곳을 잊어 보통 추천으로 돌아간다
     */
    fun track(pos: Fix?, nextStopId: String?, nowMs: Long) {
        // 넣는 중에도 가장 가까웠던 거리는 잰다 (내비 확인이 20초 걸리는 동안 500m를 간다)
        picking?.let { p -> if (pos != null) p.minDistM = minOf(p.minDistM, distanceM(pos, p.stop)) }
        val p = pin ?: return
        if (pos != null) {
            val dist = distanceM(pos, p.stop)
            if (dist < p.minDistM) p.minDistM = dist
            if (dist > p.minDistM + PASSED_M || (p.minDistM <= ARRIVED_M && dist > ARRIVED_M)) {
                pin = null
                return
            }
        }
        if (nextStopId == p.stop.id) p.seenAtMs = nowMs else if (nowMs - p.seenAtMs > PIN_GRACE_MS) pin = null
    }

    private fun distanceM(pos: Fix, stop: StopCandidate) = Geo.distanceM(pos.lat, pos.lon, stop.lat, stop.lon)

    /** 세계(시연·실제)가 바뀌었다: 요청도 고정도 지난 세계의 것이다 */
    fun reset() {
        session = null
        picking = null
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
        /** 가장 가까웠던 때보다 이만큼 넘게 멀어지면 지나친 것이다 (m) */
        const val PASSED_M = 300.0
        /** 이 안까지 갔으면 도착했던 것이다. 다시 벗어나면 떠난 것이다 (m) */
        const val ARRIVED_M = 150.0
    }
}
