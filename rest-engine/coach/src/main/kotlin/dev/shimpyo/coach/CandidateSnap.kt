package dev.shimpyo.coach

import dev.shimpyo.engine.StopCandidate

/**
 * 엔진에 넣을 정차 후보 한 묶음 (CandidateProvider가 통째로 바꿔 끼운다).
 * 거리는 odometerM 시점의 값이다. untilM: 후보를 이 경로 거리까지만 넣었다(목록을 잘랐거나 TBT가 거기까지만 닿는다,
 * RouteState.candidatesUntilM). 그 안에는 넣은 것 말고 기회가 없고, 그 뒤는 모른다. null이면 목적지까지 다 넣었다.
 * 0이면 아무것도 주장하지 않는다(아직 못 만들었거나 다른 경로에서 만든 목록).
 */
data class CandidateSnap(
    val list: List<StopCandidate> = emptyList(),
    val odometerM: Double = 0.0,
    val untilM: Double? = 0.0,
    /** 만들 때의 내비 epoch. 지금 epoch와 다르면 다른 경로에서 만든 목록이라 범위를 0으로 본다 (CandidateProvider.withHorizon) */
    val naviEpoch: Int = 0,
    /** 만들 때 길안내 중이었는지. epoch와 함께 '같은 경로에서 만든 목록인지'를 가린다 */
    val naviGuiding: Boolean = false,
    val state: CandidatesState = CandidatesState.LOADING_DATA,
    val source: CandidateSource = CandidateSource.NONE,
)

/** 후보를 어디서 만들었는지 (기록·화면 설명·테스트용) */
enum class CandidateSource {
    /** 아직 만들지 않았다 (데이터·위치·TBT를 기다리거나 막 초기화했다) */
    NONE,
    /** 길안내 경로의 TBT에 나온 휴게소·졸음쉼터. 경로 위라 방향이 맞고 거리는 경로 거리다 */
    ROUTE,
    /** 진행 방향 앞쪽 반경 검색 (길안내가 없거나 TBT를 끝내 못 받았을 때, 시연) */
    NEARBY,
}

/** 후보를 만드는 방식. 실제/시연이 다르다 (CoachWorld가 고른다) */
data class CandidatePolicy(
    /**
     * 내비 경로를 따를지: 길안내 중 TBT의 경로 위 시설을 쓰고 목적지 뒤 시설을 뺀다. false면 내비 경로를 아예 보지 않는다
     * (시연: 경로는 시나리오가 정하고, 실제 내비에 다른 경로가 켜져 있어도 섞지 않는다)
     */
    val useRouteTbt: Boolean,
    /** 내비 requestTrip으로 보정할 최대 후보 수(갱신 하나의 요청 수 한도). null이면 내비에 묻지 않고 다 넘긴다 */
    val oracleMax: Int?,
    /** 다시 만드는 주기 (실제 시간). 이와 별도로 epoch·TBT·위치·데이터·정책이 바뀌면 바로 다시 만든다 */
    val periodMs: Long,
) {
    companion object {
        val REAL = CandidatePolicy(useRouteTbt = true, oracleMax = CandidateTuning.ORACLE_MAX, periodMs = 20_000)
        val DEMO = CandidatePolicy(useRouteTbt = false, oracleMax = null, periodMs = 5_000)
    }
}

/** 갱신 하나의 입력. 갱신 루프(CandidateProvider.run)가 매초 코치 상태에서 읽는다 */
data class CandidateRequest(
    /** 엔진이 보는 위치 (실제 위치, 시연이면 시연 위치). 처음 위치를 받기 전에는 null */
    val pos: Fix?,
    val navi: NaviSnapshot,
    val policy: CandidatePolicy,
)
