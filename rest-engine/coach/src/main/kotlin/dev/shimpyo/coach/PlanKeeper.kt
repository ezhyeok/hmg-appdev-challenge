package dev.shimpyo.coach

/**
 * 피트스톱 계획을 언제 (다시) 세울지 (UI 감사 #4). 계획을 세우는 것(engine.plan)은 코어가 하고, 여기는 시점과 번호만 정한다. 순수하다.
 *
 * - 요청(MakePlan)이 오면 바로 세운다. 그 뒤 계획이 떠 있는 동안에는 재료(후보 스냅샷·내비 epoch·세계)가 바뀌었거나
 *   [REFRESH_MS]가 지났을 때 다시 세운다: 지나친 정차가 빠지고 거리가 지금 값이 된다.
 * - 번호([generation], CoachUiState.planGeneration)는 요청에 답해 세운 계획만 올린다. 고쳐 세운 계획으로 번호가 오르면 화면의
 *   '계획을 세우는 중'이 요청 없이 풀린다.
 * - 세계가 바뀌면 지난 세계의 계획은 버린다. 아직 답하지 않은 요청만 새 세계에서 답한다.
 */
class PlanKeeper {
    /** 요청에 답해 새로 세운 계획의 번호 */
    var generation = 0L
        private set

    private var wanted = false
    /** 요청을 받았고 아직 그 계획을 내지 않았다 (첫 틱 전에 와도 다음 계산에서 번호를 올린다) */
    private var requested = false
    private var key: Any? = null
    private var builtAtMs = 0L

    /** 화면이 계획을 요청했다 (UiCommand.MakePlan) */
    fun request() {
        wanted = true
        requested = true
        key = null
    }

    /**
     * 지금 세워야 하는지. true를 돌려주면 세운 것으로 적는다(실패해도 다음 주기까지 다시 하지 않는다).
     * [key]: 계획의 재료를 가리키는 값 (바뀌면 다시 세운다). [force]: 방금 요청이 왔다
     */
    fun due(nowMs: Long, key: Any, force: Boolean = false): Boolean {
        if (!wanted) return false
        if (!force && key == this.key && nowMs - builtAtMs < REFRESH_MS) return false
        this.key = key
        builtAtMs = nowMs
        return true
    }

    /** 계획을 세웠다. 화면에 올릴 번호를 돌려준다 (요청에 답한 것이면 오른다) */
    fun built(): Long {
        if (requested) {
            requested = false
            generation++
        }
        return generation
    }

    /** 엔진의 주행 상태가 새로 시작됐다 (새 여정, 기록 지우기): 다음 틱에 다시 세운다 */
    fun engineChanged() {
        key = null
    }

    /** 세계(시연·실제)가 바뀌었다 */
    fun worldChanged() {
        wanted = requested
        key = null
    }

    companion object {
        const val REFRESH_MS = 30_000L
    }
}
