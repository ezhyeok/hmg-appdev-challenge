# 정차 후보 (coach 계층, candidates 담당)

코드: `rest-engine/coach/src/main/kotlin/dev/shimpyo/coach/`
`CandidateProvider.kt`, `CandidateRules.kt`(순수 규칙), `CandidateSnap.kt`(CandidateSnap, CandidateSource, CandidatePolicy, CandidateRequest),
`CandidateTuning.kt`(조정값과 이유), `RouteOracle.kt`(requestTrip 보정), `Ports.kt`의 `TripEstimator`.
테스트: `rest-engine/coach/src/test/kotlin/dev/shimpyo/coach/` 의 `CandidateProviderTest`, `CandidateRulesTest`, `RouteOracleTest`,
가짜는 `fakes/CandidateFakes.kt`(FakeStopIndex, FakeTripEstimator, TripNaviPort, 가상 남북 고속도로 `Road`).
실행: `./gradlew -q -p rest-engine :coach:test --tests 'dev.shimpyo.coach.CandidateProviderTest'` (서버에서 돈다, 안드로이드 불필요).

## 1. 맡는 것

엔진(`CoachEngine`)은 "경로 앞에 어떤 정차 기회가 어디에 있는지"를 `StopCandidate` 목록과 `RouteState.candidatesUntilM`(범위)로 받는다.
이 둘이 틀리면 엔진의 '마지막 기회'·'공백' 판단이 통째로 틀린다. 이 부품은 그 둘을 만든다.

| 부품 | 하는 일 | 예전 위치 (CoachService) |
|---|---|---|
| `CandidateProvider` | 갱신 루프, 갱신 하나(TBT 경로 / 반경 검색), 매 틱 투영(`live`, `withHorizon`), 모드 전환 초기화 | `candidateLoop`, `refreshCandidates*`, `candidatesFromRoute`, `liveCandidates`, `liveRouteWithHorizon` |
| `CandidateRules` | 어느 경로로 만들지(`plan`), 범위 계산, 목적지 뒤 빼기, 같은 이름 고르기, 종류 바로잡기 (모두 순수 함수) | 위 함수들 안에 섞여 있었다 |
| `RouteOracle` | requestTrip으로 경로 거리·ETA·방향 보정. 캐시, 실패 기억, 쉬기(backoff) | 같은 이름 (하드닝) |
| `CandidateTuning` | 반경·보정 수·TBT 최대 수·시간 초과 등 조정값을 한곳에, 이유와 함께 | 서비스·오라클 상수에 흩어져 있었다 |

### 1.1 공개 API

```kotlin
class CandidateProvider(stops: StopIndex, oracle: TripEstimator, odometer: Odometer, clock: ElapsedClock, log: CoachLog = CoachLog.NONE) {
    val snapshot: StateFlow<CandidateSnap>
    suspend fun run(request: () -> CandidateRequest)            // 유일한 갱신 루프 (코치 디스패처에서 launch)
    suspend fun refresh(pos: Fix?, navi: NaviSnapshot, policy: CandidatePolicy)   // 갱신 하나 (Mutex로 하나씩)
    fun live(navi: NaviSnapshot, odometerM: Double): List<StopCandidate>          // 매 틱: 달린 만큼 줄인 목록
    fun withHorizon(route: RouteState?, navi: NaviSnapshot, odometerM: Double): RouteState?  // 매 틱: candidatesUntilM
    fun reset()                                                  // 모드(실제/시연) 전환
}
data class CandidateRequest(val pos: Fix?, val navi: NaviSnapshot, val policy: CandidatePolicy)

interface TripEstimator {                                         // Ports.kt, RouteOracle이 구현
    var enabled: Boolean
    // null = 모름. permit: 내비에 실제로 보내기 직전에 한 번 묻는다 (false면 보내지 않고 null). 캐시가 답하면 묻지 않는다
    suspend fun refine(c: StopCandidate, from: Fix, permit: () -> Boolean = { true }): StopCandidate?
    fun clear()
}
```

브리프의 제안 API와 다른 점과 이유:
- `ElapsedClock`을 받는다. 갱신 주기·TBT 기다림·갱신 예산은 실제 시간이라 코치 시각(시연에서는 가상)과 섞으면 안 된다.
- `refresh`에서 코치 시각 `now`를 뺐다. 계획 후보(휴게소·졸음쉼터)는 영업시간을 보지 않고, 오라클 캐시 나이는 실제 시간으로 바꿨다.
- `run(request)`을 더했다. 갱신 시점(주기, epoch·TBT·위치·데이터·정책 변화, TBT 기다림 끝)을 이 부품이 정하고, 갱신을 자식 코루틴으로 돌려
  `reset()`이 루프를 죽이지 않고 진행 중인 갱신만 취소할 수 있게 하려고. 코어는 `launch { candidates.run { CandidateRequest(...) } }` 한 줄로 쓴다.
- `TripEstimator.refine(c, lat, lon, now): StopCandidate` → `refine(c, from: Fix, permit): StopCandidate?`. 실패를 '모름'(null)으로 돌려줘야
  같은 이름 고르기에서 실패를 '가장 멂'으로 읽는 버그(D6)가 구조적으로 막힌다. `from.heading`은 캐시 판단에 쓴다.
  `permit`은 갱신 하나의 요청 수 한도를 '내비에 실제로 보낸 요청'으로만 세게 하는 길이다(4장, SV-N1). 보냈는지는 오라클만 안다.
- `CandidateSnap`에 `source`(NONE/ROUTE/NEARBY)와 `naviGuiding`을 더했고, 기본 `untilM`을 null(완전)에서 0.0(주장 없음)으로 바꿨다.
  아직 아무것도 못 만든 목록이 '목적지까지 완전한 빈 목록'으로 읽히면 안 된다.

## 2. 만드는 길 (`CandidateRules.plan`)

| 조건 | 계획 | 결과 |
|---|---|---|
| 정차 후보 표를 아직 못 읽음 | `NoData` | 빈 목록, `LOADING_DATA`, 범위 0 |
| 위치 모름 | `NoLocation` | 빈 목록, `NO_LOCATION`, 범위 0 |
| 길안내 아님 (또는 시연 정책) | `Nearby(remain = null)` | 반경 검색 |
| 길안내 중, 이번 epoch의 TBT를 받음 | `Route` | TBT 경로 위 기회 (비어 있으면 정말 없다) |
| 길안내 중, TBT를 아직 못 받음, 경로를 본 지 30초 안 | `AwaitRoute` | 목록을 바꾸지 않는다. 범위는 0 (`withHorizon`) |
| 길안내 중, 30초가 지나도 TBT 없음 | `Nearby(remain)` | 반경 검색 + 목적지 뒤 빼기 |

시연 정책(`useRouteTbt = false`)은 실제 내비 스냅샷 대신 빈 스냅샷을 보므로 언제나 `Nearby(null)`이다. 시연 경로·범위는 CoachWorld(DemoDirector)가 정한다.

### 2.1 TBT 경로 (`Route`)

1. 기회를 `NaviSnapshot.liveOpportunities`로 지금 위치에 맞추고, NaN·음수 거리는 뺀다.
2. 이름이 종류를 말하면 이름을 믿는다(`effectiveKind`): TBT가 졸음쉼터를 SERVICEAREA로 보내면 extract는 휴게소로 두는데,
   그러면 같은 기본 이름의 반대편 휴게소(옥산졸음쉼터(서울방향) ↔ 옥산휴게소(부산방향) 0.3km)와 맞는다.
3. 이번 epoch에 **확인한 행**이 있으면 그 행을 쓴다(2.1.1). 없으면 아래로 고른다.
   `StopIndex.matchByName(name, kind, 위치, 경로거리 + 5km, heading)`. heading을 넘기면 반대 차로 행은 데이터 쪽에서 뺀다
   (20km보다 먼 같은 이름 쌍이 분명히 갈리지 않으면 데이터 쪽이 둘 다 준다, data.md 3.3).
4. 직선거리가 TBT 경로 거리와 어울리는 행만 남긴다: `경로×0.5 − 2km ≤ 직선 ≤ 경로 + 5km` (다른 노선의 같은 이름 시설을 뺀다).
5. 남은 행이 0개면 좌표 없는 `tbt:이름` 후보, 1개면 그 행, 여럿이면 requestTrip으로 잰다(`chooseMeasured`):
   유턴 판정 행은 빼고, 못 잰 행은 '모름', TBT 거리와 가장 가까운 행을 고르되 2km + 10%를 넘으면 어느 것도 아니다 → `tbt:`.
6. 모든 후보: `routeDistanceM` = TBT 누적 거리, `directionOk = true`, `etaS = null`.
7. 같은 이름 `tbt:` 후보가 또 오면 `tbt:이름#2`. 같은 데이터 행이 두 번 오면 앞의 것만.

#### 2.1.1 한 번 확인한 행은 그 경로에서 그 행이다 (SV-N1)

엔진은 수락한 정차를 후보 id로 따라간다. id가 바뀌면 60초 뒤 '지나쳤다'고 보고 잊고 다시 묻는다. 예전에는 갱신마다 새로 골라서,
달릴 때 차로 방위로 고른 행(`k06e247e4`)이 정체에 들어 방위를 모르게 되고(P·저속) 내비도 못 재는 동안 `tbt:옥천휴게소`로 바뀌었다
(같은 이름 양방향 쌍은 185쌍 중 151쌍이 500m 안이라 방위 없이는 둘 다 맞는다). 이미 경유지로 넣은 휴게소를 다시 물었다.

| 어떻게 골랐나 | 기억하나 | 이유 |
|---|---|---|
| 방위를 알고 한 행만 남음 (차로 방위) | 기억한다 (`resolved`) | 확인한 것이다 |
| 여럿을 requestTrip 거리로 고름 | 기억한다 | 내비가 확인한 것이다 |
| 방위를 모르는데 이름이 하나뿐 | 쓰되 기억하지 않는다 (임시) | 반대 차로 행뿐인 이름일 수 있다. 방위를 알면 다시 본다 |
| 방위를 알 때 차로 방위가 그 이름의 행을 모두 뺌 | 이름을 적어 둔다 (`laneRejected`) | 방위를 다시 모르게 돼도 뺀 행으로 돌아가지 않는다 (행 ↔ `tbt:`를 오가지 않게) |
| 못 고름 | `tbt:` | 나중에 고를 수 있게 되면 데이터 행으로 올라간다 (올라가는 것은 막지 않는다) |

- 기억은 **내비 epoch 안에서만**이다. 경로가 바뀌면(재탐색, 경유지 추가, 되돌아가는 새 경로) 같은 이름이 반대편 시설일 수 있어 다시 고른다.
  `reset()`(모드 전환)도 지운다.
- 기억한 행은 직선거리가 TBT 경로 거리와 어울릴 때만 쓴다(4번의 창). 경로가 같은 이름의 다른 시설을 또 지날 수 있다.
- 기억한 행에는 `matchByName`도 requestTrip도 쓰지 않는다. 직선거리만 지금 위치로 다시 잰다.
- 코어가 느릴 때도 마지막으로 달리던 방위를 넘기게 되면(C1) 방위를 모르는 때는 출발 전뿐이다. 이 규칙은 방위가 null로 와도 성립한다
  (`ServiceClosureOpenTest.anAcceptedRestAreaIsNotAskedAgainWhenTheCarSlowsDownAndTheNaviCannotMeasure`가 코어까지 조립해 본다).
- 남은 것: `tbt:`에서 데이터 행으로 올라갈 때도 id는 바뀐다. `tbt:` 후보를 수락한 뒤(경유지 없이 "경로 위에 있어요" 안내) 행으로 올라가면
  엔진이 한 번 더 물을 수 있다. 달리는 동안은 대개 처음부터 행으로 고르므로 드물다.

### 2.2 반경 검색 (`Nearby`)

1. `StopIndex.ahead(위치, heading, 60km, 휴게소·졸음쉼터)`에서 화장실 있는 곳 (가까운 순).
2. 시연(`oracleMax = null`): 그대로 다 넘긴다. etaS는 넣지 않는다.
3. 실제: 가까운 순 6곳만 requestTrip으로 보정한다. 요청 때 주행 거리를 적어 두고 끝난 시점(base)으로 맞춘다(요청 중 움직인 거리를 뺀다).
   유턴 판정(`directionOk = false`)은 뺀다. 못 잰 곳은 '방향 모름'(null)으로 남긴다.
4. 길안내 중이면 목적지 뒤를 뺀다: 보정한 곳은 경로 거리로, 못 한 곳은 직선거리로(경로 ≥ 직선이라 직선이 넘으면 확실히 뒤).

### 2.3 범위 (`untilM` → `RouteState.candidatesUntilM`)

| 경우 | untilM | 이유 |
|---|---|---|
| TBT 기회가 10곳 넘음 | 11번째 기회 거리 | 그 앞은 넣은 것이 전부다 |
| TBT 목록이 목적지보다 1km 넘게 짧게 끝남 | TBT가 닿는 거리 | 10곳 이하라고 완전한 목록이 아니다 (데이터 #7, P5) |
| 둘 다 | 짧은 쪽 | |
| TBT가 목적지까지, 10곳 이하 | null (완전) | 빈 목록이면 "경로 위에 휴게시설 없음"이 사실이다 |
| 남은 거리를 모름 | TBT가 닿는 거리 | 완전하다고 말할 근거가 없다 |
| 반경 검색, 실제 | min(60km, 7번째 후보의 직선거리) | 경로 ≥ 직선이라 그보다 가까운 기회는 모두 넣었다 (D5a) |
| 반경 검색, 시연 | 60km | (시연 경로가 자기 범위로 덮어쓴다) |
| 데이터·위치·TBT를 기다림, 방금 초기화 | 0 | 아무것도 주장하지 않는다 |
| 다른 경로(epoch·길안내 여부)에서 만든 목록 | 0 (`withHorizon`이 판단) | 새 경로와 어디서 갈라지는지 모른다 |

원칙: 범위를 길게 잡으면 가짜 '마지막 기회'(나쁨), 짧게 잡으면 경고를 놓친다(덜 나쁨). 모르면 짧게.

## 3. 갱신 시점과 상태

`run`은 1초마다 입력을 읽고 다음 중 하나면 갱신한다: 마지막 갱신과 키가 다르다(정책, 데이터 읽음, 위치 유무, epoch, 길안내,
TBT 내용(epoch·받음·받은 때 주행 거리·닿는 거리·기회 목록)), 주기(실제 20초, 시연 5초)가 됐다, TBT 기다림이 끝났다.
TBT 경로는 새 TBT가 올 때마다 다시 만든다(가볍다: 같은 이름이 차로 방위로 안 갈릴 때만 내비에 묻고, 그것도 캐시된다).

경로가 바뀌면(epoch) `reset` 없이 루프가 바로 다시 만든다. 새 TBT를 기다리는 동안 옛 목록은 남긴다(화면 목록과 방금 수락한 정차가
깜빡이지 않게, 엔진 감사 P11). 대신 `withHorizon`이 범위를 0으로 둬서 엔진이 옛 목록으로 공백을 주장하지 못한다. 그동안 반경 검색과
requestTrip을 하지 않는다: 길안내 시작 직후 내비 요청을 연달아 보내면 시작된 경로가 취소된 일이 있었다(CLAUDE.md).
epoch가 그대로인데 길안내만 켜진 경우도 같은 규칙을 따른다(방어).

`reset()`(모드 전환): 진행 중인 갱신을 취소하고 결과를 버리며(세대 번호), 목록을 바로 비우고(`LOADING_DATA`, 범위 0), 오라클 캐시를 비우고,
루프를 깨워 새 정책으로 곧바로 다시 만든다. 옛 모드의 후보가 새 엔진에 한 틱도 들어가지 않는다(구조 리뷰 B2, D11).

`CandidatesState`: `LOADING_DATA`(표를 읽는 중, 초기화 직후, 첫 TBT를 기다리는 중), `NO_LOCATION`, `READY`(빈 목록이면 정말 앞에 없다).
상태 이름은 UI 계약(CoachState.kt)이라 바꾸지 않았다. `LOADING_DATA`가 '첫 목록을 만드는 중'까지 뜻하게 됐다.

## 4. RouteOracle

| 규칙 | 값 | 이유 |
|---|---|---|
| 캐시 재사용 | 5분 안, 3km 안, 방향 90도 안 | 내비 요청 절약. 방향이 뒤집히면(유턴) 캐시 거리가 틀린다 (데이터 #11) |
| 캐시 나이 | `ElapsedClock` | 예전에는 코치 시각이라 시연을 끄면 몇 시간씩 어긋났다 |
| 방향 판정 | 경로 ≤ 직선 × 1.3 + 2km | 안성 부산방향 47.5/44.6km 통과, 서울방향 57.1/37.6km 유턴 |
| 요청 시간 초과 | 8초, 포트(NaviKit)가 내비 잠금을 얻은 뒤부터 잰다 | 예전 20초 × 6곳 = 2분 동안 경로·TBT 조회와 경유지 명령이 기다렸다 (P2, 데이터 #6) |
| 차례(내비 잠금) 기다림 | 4초 더 기다린다. 그때까지 포트가 답도 실패도 내지 않았으면 차례가 오지 않은 것 | 포트는 내비에 한 번에 하나만 보낸다. 조회는 1~2초, 경유지·길안내 명령은 확인까지 20~45초 잠금을 잡는다 |
| 차례가 오지 않음 | 실패가 아니다: 그 후보를 실패로 적지 않고 10초 뒤 다시 줄을 선다 (늘리지 않는다, 그 사이 캐시는 답한다). 연달아 3번이면 답 없는 내비처럼 쉰다 | 예전에는 잠금 기다림이 요청 시간 8초에 들어 있어, 들르기 하나가 잠금을 6초 넘게 잡으면 '답 없는 내비'로 보고 30초 넘게 쉬었다 (SV-N6) |
| 실패한 후보 | 1분 뒤에 다시 | 경로를 못 찾는 곳을 갱신마다 묻지 않게 |
| 내비가 답하지 않음(포트가 6초 넘게 걸려 낸 실패) 또는 연달아 3번 실패 | 모든 요청을 30초→60초→…→5분 쉼, 답하면 처음으로 | 멈춘 내비에는 한 번의 시간 초과만 쓴다 |
| 부른 쪽 취소 | 그대로 던진다, 실패로 세지 않는다 | 예전 runCatching이 CancellationException을 삼켰다 |
| 포트 안쪽 시간 초과 (`TimeoutCancellationException`) | 실패 | 부른 코루틴이 살아 있으면 우리 취소가 아니다 (`ensureActive`로 구분) |
| 이상한 답 (NaN, 음수) | 실패 | |
| `enabled = false` | 바로 null | 스위치 |
| `clear()` | 캐시·실패·쉬기 모두 비움 | 모드 전환 |

갱신 하나의 requestTrip은 `CandidateProvider`가 예산(15초, 요청 수 `oracleMax`)으로 한 번 더 묶는다. 예산이 끝나면 진행 중 요청을 취소하고
나머지는 '모름'으로 넣는다(캐시가 아는 것은 예산이 끝난 뒤에도 쓴다). 요청 하나의 상한(차례 4초 + 요청 8초) < 예산(15초)이라 멈춘 내비를 한 갱신 안에서 알아채고 쉰다.

요청 수 한도는 **내비에 실제로 보낸 요청**만 센다(`refine`의 `permit`). 예전에는 캐시가 바로 답한 것, 쉬는 중이라 묻지 않은 것까지 세어서,
방위를 모를 때 같은 이름 쌍(두 번씩 쓴다)을 앞 세 쌍만 고르고 네 번째부터는 내비가 멀쩡해도 몇 번을 다시 만들어도 `tbt:`로 남았다(SV-N1).

## 5. 스레드

모두 코치 디스패처(`limitedParallelism(1)`) 하나에서 쓴다. `run`이 유일한 갱신 루프이고 갱신은 그 자식 코루틴이다. `refresh`는 Mutex로
한 번에 하나만 돈다. `live`·`withHorizon`은 스냅샷 하나만 읽는 순수 계산이라 매 틱(1초) 불러도 된다(후보 10~30개, NaviPoll.advanceCandidates;
움직이지 않았으면 같은 리스트 인스턴스를 돌려줘 화면이 다시 그리지 않는다). 그래서 화면의 `ahead`가 20초가 아니라 매초 움직인다(UI 감사 #19).
`RouteOracle`의 `clear()`는 세계 전환 때 코치 액터가 부르므로 상태를 잠금으로 지킨다(잠금 안에서 일시 중단하지 않는다).
`CandidateProvider`의 기억(`resolved`, `laneRejected`)은 갱신(`refresh`, Mutex 안)과 `reset()`만 쓰고 둘 다 코치 디스패처에서 돈다.

## 6. 시나리오 표 (→ 테스트)

| # | 상황 | 기대 | 테스트 |
|---|---|---|---|
| C1 | TBT 이름이 데이터 한 행과 맞음 | 그 행, 거리 = TBT, 방향 OK, etaS 없음, 범위 null, 내비 요청 없음 | `CandidateProviderTest.routeTbtNameMatchesTheDataRow` |
| C2 | 같은 이름 두 행, 진행 방향 앎 | 차로 방위로 고름, 내비 요청 없음 (데이터 #5) | `laneHeadingPicksTheSameNameRowWithoutANaviRequest` |
| C3 | 같은 이름 두 행, 방향 모름, requestTrip 둘 다 실패 | `tbt:` (첫 행을 고르지 않음, D6) | `withoutHeadingAFailedRequestIsUnknownNotTheFirstGridMatch`, `CandidateRulesTest.bothRequestsFailedIsUnknownNotTheFirstMatch` |
| C4 | 같은 이름 두 행, 둘 다 답함 | TBT 거리와 가까운 행 | `withoutHeadingTheNaviDistanceClosestToTheTbtWins`, `CandidateRulesTest.bothAnsweredPicksTheClosestToTheTbtDistance` |
| C5 | 반대편 행만 답함 (57.1 vs TBT 47km) | `tbt:` | `onlyTheOppositeRowAnsweringIsRejectedAsImplausible`, `CandidateRulesTest.onlyTheOppositeOneAnsweredIsRejectedAsImplausible` |
| C6 | 유턴 판정 행이 거리로는 가까움 | 뺀다 | `CandidateRulesTest.aUturnVerdictExcludesARowEvenIfItsDistanceIsClose` |
| C7 | 데이터에 없는 이름 | `tbt:이름`, 방향 OK, etaS 없음 | `unknownNameBecomesACoordinateLessTbtCandidate` |
| C8 | 같은 이름이 다른 노선, 너무 가까움 | `tbt:` | `aSameNameFacilityFarCloserThanTheTbtDistanceIsNotIt`, `CandidateRulesTest.plausibleStraightDistanceWindow` |
| C9 | 졸음쉼터가 SERVICEAREA로 옴 | 졸음쉼터 행 (같은 기본 이름 휴게소 아님) | `serviceAreaLabelledShelterMatchesTheShelterNotTheRestAreaWithTheSameBaseName`, `CandidateRulesTest.nameDecidesTheKindWhenTheTbtTypeDisagrees` |
| C10 | TBT 기회 12곳 | 10곳, 범위 = 11번째 | `moreThanTenOpportunitiesSetTheHorizonAtTheEleventh` |
| C11 | TBT가 목적지 전에 끝남 | 범위 = TBT 끝 (P5, 데이터 #7) | `aTbtListShorterThanTheRouteSetsTheHorizonAtItsCoverage`, `CandidateRulesTest.tbtCoverageShorterThanTheRouteIsAHorizon` |
| C12 | TBT 받음, 휴게시설 없음 | 빈 목록, 범위 null, 반경 검색 안 함 (D5b) | `anEmptyTbtMeansNoRestStopsOnTheRoute` |
| C13 | TBT 거리에 NaN | 그 항목만 뺀다 | `nonFiniteTbtDistancesAreIgnored` |
| C14 | 이름 없는 '졸음쉼터' 두 번 | 서로 다른 id | `repeatedTbtNamesGetDistinctIds` |
| C15 | 길안내 중 TBT 아직 | 내비 요청·반경 검색 없음, 범위 0; 30초 뒤 반경 검색, 범위 = 반경 (D5a) | `aPendingTbtClaimsNothingThenFallsBackWithABoundedHorizon`, `aTbtThatNeverArrivesFallsBackAfterTheWait` |
| C16 | 반경 검색 6곳으로 자름 | 범위 = 7번째 직선거리 (엔진 감사 '자른 목록') | `theFallbackTruncatedToSixSetsTheHorizonAtTheFirstUnrefinedStraightDistance`, `CandidateRulesTest.nearbyHorizonIsTheRadiusOrTheFirstUnrefinedStraightDistance` |
| C17 | 반경 검색, 목적지 25km | 목적지 뒤·유턴 시설 뺌, 길안내가 아니면 거리로 빼지 않음 (D5c) | `theFallbackDropsStopsPastTheDestinationAndOnesNeedingAUturn`, `CandidateRulesTest.stopsPastTheDestinationAreDropped` |
| C18 | requestTrip 중 차가 움직임 | 요청 뒤 달린 만큼 뺀다, ETA도 같은 비율 | `movementDuringRefineIsSubtracted` |
| C19 | 내비 멈춤 | 갱신 하나가 예산(15초) 안에 끝남, 나머지는 '모름' (P2, 데이터 #6) | `aHangingNaviIsBoundedByTheRefreshBudget` |
| C20 | etaS 출처 | 내비 값만, 못 재면 null, 시연·TBT는 null (46981d8) | `etaSComesOnlyFromTheNavi`, C1, C7 |
| C21 | 시연 정책 + 실제 내비 경로 | 반경 검색, 자르지 않음, 내비 요청·이름 맞추기 없음 | `demoPolicyIgnoresTheNaviRouteAndNeitherTrimsNorAsks` |
| C22 | 데이터 전 / 위치 전 / 준비 | LOADING_DATA → NO_LOCATION → READY, 범위 0 | `statesBeforeTheFirstList` |
| C23 | 매 틱 투영 | NaviPoll.advanceCandidates와 같음, 안 움직이면 같은 인스턴스, 믿을 수 없으면 그대로 (UI #19) | `liveProjectionMatchesNaviPollAndKeepsTheSameListWhenNotMoving` |
| C24 | 범위 투영 | 달린 만큼 줄고 0 아래로 안 감, 길안내 아니면/완전하면 그대로 | `horizonIsProjectedClampedAndOnlyForGuidance` |
| C25 | epoch 변경 | 옛 목록 유지 + 범위 0, 새 TBT 오면 바로 다시 (D13) | `anEpochChangeRefreshesAndClaimsNothingUntilTheNewTbt` |
| C26 | epoch 그대로 길안내만 켜짐 | 똑같이 TBT를 기다림 (방어) | `guidanceStartingWithoutAnEpochChangeStillWaitsForTheTbt` |
| C27 | 루프 주기와 계기 | 위치가 생기면 바로, 그 밖에는 주기마다만 | `theLoopRefreshesOnItsPeriodAndOnTriggersOnly` |
| C28 | 갱신이 멈춘 사이 시연 시작 | 바로 비움, 캐시 비움, 기다리지 않고 시연 목록, 취소된 갱신은 덮어쓰지 않음 (B2) | `resetCancelsAnInFlightRefreshAndClearsTheList` |
| C29 | 어느 길로 만들지 | 표 2의 모든 행 | `CandidateRulesTest.planFollowsDataLocationAndTbt` |
| O1 | 오라클 캐시 | 다시 묻지 않고 움직인 만큼 뺌 | `RouteOracleTest.freshCacheAnswersWithoutAskingAndSubtractsTheDistanceMoved` |
| O2 | 캐시 만료 | 실제 시간 5분, 3km, 유턴 | `theCacheExpiresByElapsedTimeMovementAndHeadingReversal` |
| O3 | 방향 판정 | 안성 측정값 | `theDetourRatioDecidesDirection` |
| O4 | 실패 | 모름, 1분 뒤 다시 | `aFailedRequestIsUnknownAndRetriedOnlyAfterTheRetryDelay` |
| O5 | 응답 없음 | 한 번만 기다리고 쉼, 두 배로, 답하면 처음으로 | `anUnresponsiveNaviIsPausedSoOneRefreshCostsOneTimeout` |
| O6 | 연달아 빠른 실패 | 3번 뒤 쉼 | `quickFailuresInARowPauseTheNaviToo` |
| O7 | 취소 | 전파, 실패로 안 셈 | `cancellationPropagatesAndIsNotCountedAsAFailure` |
| O8 | 포트 안쪽 시간 초과 | 실패 (취소 아님) | `aTimeoutInsideThePortIsAFailureNotACancellation` |
| O9 | 꺼짐 / clear / 이상한 답 | 묻지 않음 / 다 비움 / 실패 | `disabledPassesThroughWithoutAsking`, `clearForgetsTheCacheFailuresAndPause`, `aGarbageAnswerIsAFailure` |
| O10 | 조정값 불변식 | 차례 기다림 + 요청 시간 초과 < 갱신 예산 | `oneRequestTimeoutFitsInsideTheRefreshBudget` |
| O11 | 들르기가 내비 잠금을 잡고 있음 (SV-N6) | 9초 기다려 답하면 답. 오래 잡으면 그만두되 실패·쉼이 아니고 10초 뒤 다시, 캐시는 계속 답함 | `waitingInLineBehindANaviCommandIsNotNoAnswer` |
| O12 | 잠금이 끝내 풀리지 않음 | 세 번 뒤 답 없는 내비처럼 쉼 | `aLineThatNeverMovesIsPausedLikeNoAnswerAfterThreeWaits` |
| O13 | 차례는 왔는데 내비가 오류 | 그 후보의 실패 (1분 뒤 다시) | `aRequestThatGotItsTurnAndFailedIsStillAFailure` |
| O14 | 요청 수 한도 (SV-N1) | 캐시 답·쉬는 후보는 허락을 쓰지 않음, 허락이 없으면 보내지 않고 실패로도 적지 않음 | `thePermitIsAskedOnlyForARealNaviRequest`, `withoutAPermitNothingIsSentAndNothingIsRemembered`, `aPausedOrFailedCandidateDoesNotSpendAPermit` |
| C30 | 달릴 때 차로 방위로 고른 행, 방위를 잃고 내비도 못 잼 (SV-N1) | 같은 id, 내비에 다시 묻지 않음 | `aRowConfirmedByTheLaneKeepsItsIdWhenTheHeadingIsLostAndTheNaviCannotMeasure`, `ServiceClosureOpenTest.anAcceptedRestAreaIsNotAskedAgainWhenTheCarSlowsDownAndTheNaviCannotMeasure` |
| C31 | 내비 거리로 고른 행, 뒤 요청은 실패 | 같은 id | `aRowChosenByNaviDistanceIsKeptWhenLaterRequestsFail` |
| C32 | 새 epoch·reset | 다시 고름 (못 고르면 `tbt:`, 고르면 행으로 올라감) | `aNewRouteOrModeResolvesAgain` |
| C33 | 방위 모를 때 이름이 하나뿐이라 고른 행 | 임시: 방위를 알면 다시 보고, 차로 방위가 뺀 행으로 돌아가지 않음 | `aRowPickedOnlyBecauseItsNameIsUniqueIsCheckedAgainOnceTheHeadingIsKnown` |
| C34 | 경로가 같은 이름을 두 번 지남 | 기억한 행을 다른 시설에 쓰지 않음 | `aRememberedRowIsNotUsedForAnotherFacilityWithTheSameName` |
| C35 | 방위 모름, 같은 이름 다섯 쌍 | 첫 갱신 세 쌍(6번), 다음 갱신에서 나머지: 내비에는 행마다 한 번(10번) | `aSameNamePairBeyondTheThirdIsStillResolvedWhenTheCarIsSlow`, `cacheAnswersDoNotUseUpTheRefreshBudgetInTheNearbySearch` |

핵심 규칙마다 일부러 망가뜨려 본 결과(heading 안 넘김, 실패=가장 멂, 종류 바로잡기 없음, TBT 끝 무시, 반경 범위 없음, 목적지 뒤 안 뺌,
쉬기 없음, 취소 삼킴, epoch 바뀌면 목록 버림, 옛 epoch로 범위 주장, etaS 지어내기) 11개 모두 위 테스트가 잡았다.
2026-11-12에 더한 규칙(C30~C35, O11~O14)은 고치기 전 코드에서 9개가 실패하는 것을 먼저 확인했다.

## 7. 남은 것과 다른 부품에 기대는 것

- `StopIndex.matchByName`의 heading 거르기는 `StopRepository`에 들어 있다. 20km보다 먼 같은 이름 쌍이 분명히 갈리지 않으면 둘 다 온다
  (닫힘 검증 C2, data.md 3.3): 그때는 requestTrip으로 고르고(C4/C5 규칙), 못 고르면 `tbt:`로 두었다가 20km 안에서 차로 방위로 고른다.
- 내비 명령이 도는 동안(들르기) 오라클은 차례를 기다리다 그만둔다(4장). 캐시가 식은 후보는 그 갱신에서 '방향 모름'으로 들어갈 수 있다.
  코어가 "지금 들르기 중"을 알려 주면 그동안 갱신을 미루는 편이 더 낫다(지금은 알 길이 없다: CandidateRequest에 칸을 더하면 된다).
- `NaviSnapshot.tbtCoverageM`은 TBT를 받으면 모든 항목 거리의 합으로 채워야 한다(navi 담당). null이면 마지막 기회 거리를 닿는 거리로 본다(짧게).
  TBT가 통째로 비어 닿는 거리가 0이면 아직 못 받은 것으로 본다.
- `RouteOpportunities.extract`(navi 담당)는 이름이 졸음쉼터인데 SERVICEAREA로 온 것을 휴게소로 둔다. 여기서 `effectiveKind`로 바로잡지만
  extract에서 고치는 편이 맞다.
- 반경 검색은 진행 방향 ±75도 원뿔이라 크게 굽은 경로 위 시설을 놓칠 수 있고, 다른 고속도로 시설이 섞인다(데이터 #3, 노선 번호로 같은 노선 먼저:
  데이터 담당). 길안내 중에는 TBT 경로가 거의 늘 쓰이므로 영향이 작다.
- 멀리 있는 반대 차로 시설은 1.3배 + 2km 판정을 통과할 수 있다(데이터 #11). 반대 차로는 먼저 차로 방위로 뺀다.
- 위치 `Fix`는 받은 때를 들고 있지 않다. 오래된 위치를 거르는 일은 PositionTracker(navi 담당)가 한다.
