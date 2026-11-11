package dev.shimpyo.coach

import dev.shimpyo.engine.StopCandidate

/**
 * 엔진에 넣을 정차 후보 한 묶음 (CandidateProvider가 통째로 바꿔 끼운다).
 * 거리는 odometerM 시점의 값이다. untilM: 후보를 이 경로 거리까지만 넣었다(목록을 잘랐거나 TBT가 거기까지만 닿는다,
 * RouteState.candidatesUntilM). null이면 목적지까지 다 넣었다.
 */
data class CandidateSnap(
    val list: List<StopCandidate> = emptyList(),
    val odometerM: Double = 0.0,
    val untilM: Double? = null,
    /** 만들 때의 내비 epoch. 경로가 바뀌면 다시 만든다 */
    val naviEpoch: Int = 0,
    val state: CandidatesState = CandidatesState.LOADING_DATA,
)

/** 후보를 만드는 방식. 실제/시연이 다르다 (CoachWorld가 고른다) */
data class CandidatePolicy(
    /** 길안내 중 TBT의 경로 위 시설을 쓸지 */
    val useRouteTbt: Boolean,
    /** 내비 requestTrip으로 보정할 최대 후보 수. null이면 보정하지 않고 다 넘긴다 */
    val oracleMax: Int?,
    /** 다시 만드는 주기 (실제 시간) */
    val periodMs: Long,
) {
    companion object {
        val REAL = CandidatePolicy(useRouteTbt = true, oracleMax = 6, periodMs = 20_000)
        val DEMO = CandidatePolicy(useRouteTbt = false, oracleMax = null, periodMs = 5_000)
    }
}
