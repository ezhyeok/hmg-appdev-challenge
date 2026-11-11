package dev.shimpyo.coach

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 운전자가 '자는 중'이라고 알려 준 탑승자 (말 "아이 잠들었어" 또는 탑승자 화면). 밤에 잠든 아이는 소변을 낮의 절반만 만들어서
 * 엔진이 그 아이의 화면 게이지에 여유를 더 둔다 (CoachInput.asleepOccupantIds, docs/research/need-covariates.md 8장).
 * 잠든 것은 엔진이 알 수 없는 사실이라 코치가 들고 매 틱 엔진에 넘긴다. 순수하다 (AsleepMarksTest).
 *
 * - 표시할 수 있는 사람: 아이(CHILD) 탑승자만. 누구인지 말하지 않았으면 아이가 한 명일 때만 그 아이로 본다.
 * - 푸는 때: "깼어"·화면에서 끔, 그 탑승자가 빠짐, 화장실 정차(P + 문 열림 + 3분: 엔진이 화장실 시계를 돌리는 때. 그대로 두면
 *   새 간격에 여유가 또 붙는다), 현지 시각이 아침([wakeHour]시)을 지남, 세계 전환(시연·실제).
 *
 * 시각은 그 세계의 코치 시각이다 (시연이면 가상 시각: 엔진이 보는 밤과 같은 시계).
 */
class AsleepMarks(
    private val zone: ZoneId = ENGINE.zoneId,
    /** 엔진이 잠든 아이 배수를 거는 밤 시간대의 끝 (CoachConfig.asleepNightEndHour) */
    private val wakeHour: Int = ENGINE.asleepNightEndHour,
    private val restroomStopMs: Long = ENGINE.restroomStopMinParkedMin * 60_000L,
) {
    /** 지금 자는 중으로 표시된 탑승자 id (바뀔 때마다 새 집합) */
    var ids: Set<String> = emptySet()
        private set

    /** id → 이 시각(코치 시각)이 되면 푼다 (표시한 뒤 처음 오는 아침) */
    private val clearAt = HashMap<String, Long>()

    enum class Result {
        /** 자는 중으로 표시했다 */
        MARKED,
        /** 표시를 풀었다 */
        CLEARED,
        /** 그 사람은 아이가 아니다 (또는 타고 있지 않다, 아이가 없다) */
        NOT_A_CHILD,
        /** 아이가 여럿인데 누구인지 모른다 */
        UNKNOWN_WHO,
        /** 바뀐 것이 없다 (이미 그 상태다) */
        NO_CHANGE,
    }

    /** 표시하거나 푼다. [occupantId]가 null이면 누구인지 모르는 말이다: 재울 때는 아이가 한 명일 때만, 깨울 때는 모두 */
    fun set(occupantId: String?, asleep: Boolean, occupants: List<Occupant>, now: Long): Result {
        if (!asleep) {
            if (occupantId == null) return if (clear()) Result.CLEARED else Result.NO_CHANGE
            if (clearAt.remove(occupantId) == null) return Result.NO_CHANGE
            publish()
            return Result.CLEARED
        }
        val children = occupants.filter { it.type == OccupantType.CHILD }
        val child = if (occupantId != null) children.firstOrNull { it.id == occupantId } else children.singleOrNull()
        if (child == null) return if (occupantId == null && children.size > 1) Result.UNKNOWN_WHO else Result.NOT_A_CHILD
        val known = child.id in clearAt
        clearAt[child.id] = nextWake(now)
        if (known) return Result.NO_CHANGE
        publish()
        return Result.MARKED
    }

    /** 탑승자가 바뀌었다: 빠진 사람과 아이가 아니게 된 사람의 표시를 푼다. 바뀌었으면 true */
    fun onOccupants(occupants: List<Occupant>): Boolean {
        if (clearAt.isEmpty()) return false
        val children = occupants.filter { it.type == OccupantType.CHILD }.mapTo(HashSet()) { it.id }
        if (!clearAt.keys.retainAll(children)) return false
        publish()
        return true
    }

    /**
     * 틱마다: 화장실 정차가 됐거나(지금 정차 [park]가 문 열림 + 3분) 아침이 지났으면 푼다. 바뀌었으면 true.
     * 표시가 없으면 아무 계산도 하지 않는다
     */
    fun onTick(now: Long, park: ParkState?): Boolean {
        if (clearAt.isEmpty()) return false
        if (park != null && park.doorSeen && now - park.since >= restroomStopMs) return clear()
        if (!clearAt.values.removeAll { now >= it }) return false
        publish()
        return true
    }

    /** 화장실 정차가 기록됐다 (떠나는 틱에 문이 열린 정차처럼 [onTick]이 보지 못한 것): 모두 푼다 */
    fun onRestroomStop(): Boolean = clear()

    /** 모두 푼다 (세계 전환, 누구인지 모르는 "깼어"). 바뀌었으면 true */
    fun clear(): Boolean {
        if (clearAt.isEmpty()) return false
        clearAt.clear()
        publish()
        return true
    }

    private fun publish() {
        ids = clearAt.keys.toSet()
    }

    /** [now] 뒤에 처음 오는 현지 [wakeHour]시 (정각에 표시한 것은 다음 날 아침) */
    private fun nextWake(now: Long): Long {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val today = t.toLocalDate().atTime(wakeHour, 0).atZone(zone)
        return (if (t.isBefore(today)) today else today.plusDays(1)).toInstant().toEpochMilli()
    }

    companion object {
        private val ENGINE = CoachConfig()

        /** 말로 표시했을 때 운전자에게 할 대답 */
        fun reply(result: Result): String = when (result) {
            Result.MARKED -> "자는 동안은 화장실 여유를 조금 더 둘게요"
            Result.CLEARED -> "깬 것으로 바꿨어요"
            Result.NOT_A_CHILD -> "자는 중 표시는 아이 탑승자에게만 쓸 수 있어요"
            Result.UNKNOWN_WHO -> "누가 자는지 탑승자 화면에서 표시해 주세요"
            Result.NO_CHANGE -> "네, 알겠어요"
        }
    }
}
