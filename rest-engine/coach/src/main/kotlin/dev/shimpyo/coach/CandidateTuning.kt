package dev.shimpyo.coach

import dev.shimpyo.engine.StopKind

/**
 * 정차 후보(CandidateProvider)와 경로 보정(RouteOracle)의 조정값을 한곳에 둔다. 값마다 왜 그 값인지 적는다.
 * 거리 m, 시간 ms(실제 시간, ElapsedClock).
 */
object CandidateTuning {

    // ------------------------------------------------------------ 후보 찾기

    /**
     * 진행 방향 앞쪽을 찾는 반경. 고속도로 휴게시설 간격 기준은 평균 15km·최대 25km라(docs/research/ai-science.md 7)
     * 가장 성긴 구간에서도 앞쪽 두 곳 이상이 들어온다. 시연 경로의 후보 범위(DemoDirector 55km)도 이 반경에서 나온다.
     */
    const val AHEAD_RADIUS_M = 60_000.0

    /**
     * 갱신 하나가 내비에 보내는 requestTrip 수 (CandidatePolicy.REAL). 요청 하나가 NaviKit 잠금을 잡는 동안 경로·TBT 조회와
     * 경유지 명령이 기다리므로 적게 묻는다. 반경 안 가까운 순 6곳이면 평균 간격 15km로 약 90km, 엔진이 따지는 범위를 덮는다.
     * 자른 뒤에는 7번째의 직선거리를 범위(untilM)로 넘겨 그 뒤를 공백으로 보지 않게 한다.
     * 캐시가 답한 것은 세지 않는다: 세면 방위를 모를 때 같은 이름 쌍(두 번씩 쓴다)을 앞 세 쌍만 고르고 네 번째부터는 영영 못 골랐다 (SV-N1).
     */
    const val ORACLE_MAX = 6

    /**
     * TBT의 경로 위 기회 중 후보로 넣는 수. 같은 이름 행을 고를 때만 내비에 묻고(대개 차로 방위로 고른다) 엔진·화면 목록도
     * 이 정도면 충분하다(평균 15km 간격이면 150km). 넘으면 11번째 기회 위치를 범위로 넘긴다.
     */
    const val ROUTE_OPP_MAX = 10

    /** 계획 후보의 종류. 공중화장실·주유소는 급해 모드(StopIndex.near)에서만 쓴다 */
    val PLANNED_KINDS = setOf(StopKind.HIGHWAY_REST_AREA, StopKind.DROWSY_SHELTER)

    // ------------------------------------------------------------ TBT 이름 맞추기

    /** TBT 이름으로 데이터 행을 찾는 직선거리 여유: 직선거리 ≤ 경로 거리 + 이만큼 (좌표 오차, TBT를 받은 뒤 움직인 거리) */
    const val MATCH_SLACK_M = 5_000.0

    /**
     * 같은 이름 행의 직선거리 하한: 경로 거리 × 0.5 - 2km. 고속도로의 경로/직선 비는 굽은 산길에서도 2를 넘지 않는다.
     * 이보다 가까운 같은 이름 시설은 다른 노선의 다른 곳이다 (대신졸음쉼터: 경부선과 광주원주선, 데이터 감사 #5).
     */
    const val MATCH_MIN_STRAIGHT_RATIO = 0.5
    const val MATCH_MIN_SLACK_M = 2_000.0

    /**
     * requestTrip 거리와 TBT 거리의 차 허용: 2km + 10%. 같은 방향 행은 거의 같고(안성 부산방향 47.5km / TBT 47km),
     * 반대 방향 행은 유턴만큼 길다(서울방향 57.1km). 넘으면 어느 행도 아니라고 보고 좌표 없는 tbt: 후보로 둔다 (서비스 감사 D6).
     */
    const val MATCH_TOLERANCE_M = 2_000.0
    const val MATCH_TOLERANCE_RATIO = 0.1

    // ------------------------------------------------------------ 범위 (RouteState.candidatesUntilM)

    /**
     * TBT 목록이 남은 거리보다 이만큼 넘게 짧으면 그 뒤는 모른다. 마지막 안내(목적지 도착)가 목적지 수백 m 앞에서 끝나고
     * 경로 상태와 TBT를 받은 때가 조금 달라서 여유를 둔다 (데이터 감사 #7).
     */
    const val COVERAGE_SLACK_M = 1_000.0

    /**
     * 경로가 바뀐 뒤(epoch 변경) 새 TBT를 기다리는 시간. NaviTracker가 이 동안 TBT를 5초마다 받으므로(NaviPoll.FAST_AFTER_RESET_MS)
     * 대개 몇 초 안에 온다. 그동안 반경 검색과 requestTrip을 하지 않는다: 길안내 시작 직후 내비 요청을 연달아 보내면
     * 시작된 경로가 취소된 일이 있었다(CLAUDE.md). 끝까지 안 오면 반경 검색으로 넘어간다.
     */
    const val ROUTE_TBT_WAIT_MS = NaviPoll.FAST_AFTER_RESET_MS

    // ------------------------------------------------------------ 갱신

    /**
     * 갱신 하나가 requestTrip에 쓰는 최대 시간. 넘으면 남은 후보는 '모름'(directionOk = null)으로 넣는다.
     * 정상 응답(수 초) 몇 개는 들어가고, 멈춘 내비가 갱신 루프·내비 잠금을 오래 잡지 않는다 (서비스 감사 P2, 데이터 감사 #6).
     * 요청 하나의 상한([ORACLE_LINE_WAIT_MS] + [ORACLE_REQUEST_TIMEOUT_MS])보다 길어야 멈춘 내비를 한 번의 갱신 안에서 알아채고 쉰다
     * (RouteOracleTest가 지킨다).
     */
    const val REFRESH_BUDGET_MS = 15_000L

    /** 갱신 루프가 조건(epoch, TBT, 위치, 데이터, 정책)을 다시 보는 간격. 코치 틱과 같다 */
    const val CHECK_MS = 1_000L

    // ------------------------------------------------------------ RouteOracle

    /**
     * requestTrip 하나의 시간 초과: 내비가 요청을 받은 뒤 답할 때까지. 포트(NaviKit)가 내비 잠금을 얻은 뒤부터 재고, 넘으면 포트가 실패를 낸다.
     * 예전 20초는 6곳이면 2분이었다. 경로 계산은 서버에서 하고 정상이면 수 초 안에 온다.
     */
    const val ORACLE_REQUEST_TIMEOUT_MS = 8_000L

    /**
     * 내비 차례(NaviKit 잠금)를 기다려 주는 시간. 포트는 내비에 한 번에 하나만 보내서, 경로·TBT 조회나 경유지 명령이 끝나야 차례가 온다.
     * 조회는 1~2초면 끝난다. 이보다 오래 걸리면 경유지·길안내 명령(확인까지 20~45초)이 잡고 있는 것이라 더 기다리지 않는다.
     * 예전에는 이 기다림이 요청 시간 초과(8초)에 들어 있어서, 들르기 하나가 잠금을 6초 넘게 잡으면 '답 없는 내비'로 보고 30초 넘게 쉬었다
     * (서비스 감사 SV-N6).
     */
    const val ORACLE_LINE_WAIT_MS = 4_000L

    /** 차례가 오지 않았을 때 다시 줄을 서기까지. 내비가 실패한 것이 아니라서 늘리지 않는다 (그 사이에도 캐시는 답한다) */
    const val ORACLE_BUSY_PAUSE_MS = 10_000L

    /**
     * 연달아 이만큼 차례가 오지 않으면 답 없는 내비처럼 쉰다 (12초 기다림 + 10초 쉼이 세 번이면 약 1분: 들르기 하나의 보통 길이보다 길다).
     * 잠금을 영영 놓지 않는 포트에 갱신마다 줄을 서지 않게 하는 상한이다.
     */
    const val ORACLE_LINES_FOR_BACKOFF = 3

    /** 포트가 이만큼 걸려 실패하면 '답이 없다'로 본다 (오류로 바로 답한 것과 구분). 요청 시간 초과의 3/4 */
    const val ORACLE_NO_ANSWER_MS = ORACLE_REQUEST_TIMEOUT_MS * 3 / 4

    /** 같은 후보를 다시 묻는 조건: 5분이 지났거나 3km 넘게 움직였거나 방향이 90도 넘게 바뀌었을 때 (내비 요청을 아끼려고) */
    const val ORACLE_MAX_AGE_MS = 5 * 60_000L
    const val ORACLE_MOVE_M = 3_000.0
    const val ORACLE_TURN_DEG = 90.0

    /**
     * 경로 거리가 직선거리의 1.3배 + 2km를 넘으면 유턴이 필요하다고 본다.
     * 확인(2026-11-11): 안성휴게소 부산방향 47.5/44.6km(1.07), 서울방향 57.1/37.6km(1.52).
     * 한계: 멀리 있는 반대 차로 시설은 유턴 거리가 비율에 묻힐 수 있다(데이터 감사 #11). 그래서 반대 차로는 먼저 차로 방위로 뺀다.
     */
    const val DETOUR_RATIO = 1.3
    const val DETOUR_SLACK_M = 2_000.0

    /** 실패한 후보는 이만큼 지나야 다시 묻는다 (경로를 못 찾는 곳을 갱신마다 묻지 않게) */
    const val ORACLE_RETRY_MS = 60_000L

    /**
     * 내비가 답하지 않거나 연달아 [ORACLE_FAILS_FOR_BACKOFF]번 실패하면 모든 요청을 쉰다. 30초에서 두 배씩 5분까지,
     * 한 번 답하면 처음으로. 쉬는 동안 refine은 바로 '모름'을 돌려준다.
     */
    const val ORACLE_BACKOFF_MIN_MS = 30_000L
    const val ORACLE_BACKOFF_MAX_MS = 5 * 60_000L
    const val ORACLE_FAILS_FOR_BACKOFF = 3
}
