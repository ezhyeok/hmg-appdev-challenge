# 코치 계층: 내비 추적 (NaviTracker · CongestionBasis · PositionTracker)

PT 기술 질의응답용. 코드는 `rest-engine/coach/src/main/kotlin/dev/shimpyo/coach/`, 테스트는 같은 경로의 `src/test`.
안드로이드에 의존하지 않아 개발 서버에서 `./gradlew -p rest-engine :coach:test`로 돈다.

## 1. 맡은 일

| 파일 | 맡은 일 |
|---|---|
| `NaviTracker.kt` | Pleos 내비 조회(경로 상태·목적지·TBT·위치 대체)를 혼자 한다. 결과를 `StateFlow<NaviSnapshot>` 하나로 내놓는다 |
| `NaviSnapshot.kt` | 내비 값 한 묶음(불변)과 `Odometer`(차속 적분 주행 거리). 받은 뒤 달린 만큼 거리를 줄이는 `liveRoute`·`liveOpportunities`·`liveTbtCoverageM` |
| `NaviPoll.kt` | 조회 간격과 주행 거리 보정 신뢰 판정 (순수 함수, NaviPollTest) |
| `CongestionBasis.kt` | 정체 offset의 기준(출발지/현재 위치)을 주행 중에 판정하고 차 기준 거리로 바꾼다. 예전 전역 `CongestionMath`를 대신한다 |
| `TbtDigest.kt` | TBT 응답 정리: 경로 위 휴게소, 다음 회전, 목록이 닿는 거리, 깨진 거리 처리 |
| `PositionTracker.kt` | 실제 위치 하나(위치 SDK + 내비 대체), 실제 시간 기준 신선도, 방위 0.0 처리 |
| `RouteOpportunities.extract` | TBT에서 휴게소·졸음쉼터 뽑기. 종류는 이름이 분명하면 이름을 따른다(SERVICEAREA로 온 '옥산졸음쉼터'는 졸음쉼터, 데이터 감사 #5). 이름 정규화 함수는 데이터 담당의 `StopNames.kt`로 옮겨 간다 |

예전에는 이 일이 `CoachService`의 `collectNavi`·`refreshRoute`와 필드 14개(`routeSnap`, `tbtSnap`, `destinationName`, `odometerTrusted`, `forceRoute` …)에 흩어져 있었고, 정체 판정은 프로세스 전역 객체였다.

## 2. 공개 API

```kotlin
class NaviTracker(navi: NaviPort, odometer: Odometer, speedKnown: () -> Boolean, clock: ElapsedClock,
                  log: CoachLog = CoachLog.NONE, locationFallback: LocationFallback? = null) {
    val snapshot: StateFlow<NaviSnapshot>
    fun requestRefresh(routeChanged: Boolean = true)   // 어느 스레드에서나, 기다리지 않음
    suspend fun pollOnce()                             // 루프 한 번 (테스트 입구)
    suspend fun run()                                  // 연결(재시도) + 이벤트 구독 + 조회 루프. 취소될 때까지
}

class PositionTracker(clock: ElapsedClock, moving: () -> Boolean) {
    val current: StateFlow<Located?>
    fun onFused(fix: Fix)
    fun onNavi(fix: Fix, requestedAtMs: Long)
    fun needsNaviFallback(): Boolean
    fun naviFallback(allowed: () -> Boolean): LocationFallback   // NaviTracker에 넘긴다
    companion object { fun isMoving(speedKph: Float?, parked: Boolean): Boolean }
}

class CongestionBasis {
    val basis: Basis                     // UNKNOWN / FROM_CAR / FROM_ORIGIN
    fun start(remainM: Double?, originKnown: Boolean)   // 새 경로
    fun clear()                                          // 길안내 끝
    fun update(items: List<NaviCongestion>, remainM: Double?, odometerM: Double): List<CongestionSegment>
    fun startFromCar(offsetM: Double, remainM: Double?): Double?
}
```

브리프의 시그니처에서 바꾼 것과 이유:
- `requestRefresh(routeChanged = true)`: 수락 경로(경유지 추가·길안내 시작)는 경로를 바꾼 것을 안다. 이벤트(ROUTE_STARTED는 에뮬레이터에서 오지 않았다)에 기대지 않고 epoch를 늘리게 했다. 값만 새로 받을 때는 `false`.
- `run()`은 `Unit`을 돌려주지만 취소될 때까지 끝나지 않는다.
- `PositionTracker`는 `moving`을 생성자 람다로 받는다. 신선도뿐 아니라 위치 SDK 방위 0.0 판단에도 필요해서다.
- 위치 대체는 `LocationFallback` 인터페이스로 NaviTracker에 꽂는다. 내비 호출은 NaviTracker만 한다(kit의 내비 뮤텍스를 한 루프가 순서대로 쓴다).

## 3. 스냅샷 (`NaviSnapshot`)

| 필드 | 뜻 |
|---|---|
| `epoch` | 경로가 바뀔 때마다 1씩 는다 (4장) |
| `mode`, `route` | 마지막 경로 조회의 내비 모드와 엔진 입력(`RouteState`). 첫 조회 전에는 `route = null`(모름) |
| `routeOdometerM` | 경로를 받은 때의 주행 거리 |
| `destination` | 목적지 (같은 조회에서 받은 것) |
| `opportunities`, `nextManeuverM`, `tbtCoverageM`, `tbtReceived`, `tbtOdometerM`, `tbtEpoch` | 이번 epoch의 TBT |
| `odometerTrusted` | 내비가 확인한 진행과 주행 거리가 맞는지 (맞을 때만 주행 거리로 거리를 줄인다) |

불변식 (`NaviTrackerTest.everyPublishedSnapshotIsInternallyConsistent`가 섞인 대본 전체에서 확인한다):
- 길안내가 아니면 목적지·TBT·보정 신뢰가 모두 비어 있다.
- `tbtReceived`면 `tbtEpoch == epoch`. 받기 전에는 TBT 값이 모두 비어 있다.
- `route.guiding == mode.hasRoute`. epoch는 줄지 않는다.
- 옛 경로의 TBT가 새 경로의 목적지와 짝이 되지 않는다.

후보 담당과의 약속:
- `tbtReceived`는 이번 epoch에 TBT 응답을 한 번이라도 받으면 epoch가 끝날 때까지 true다. 뒤의 실패·무응답은 지난 TBT를 지우지 않는다. epoch가 늘 때만 false로 돌아간다.
- `tbtCoverageM`은 받았으면 항상 값이 있고(못 받았으면 `null`), 목록 전체 거리의 합이다. 빈 목록이면 0.
- 한 가지 다르게 한 것: 거리가 깨진 항목(NaN·음수·무한)이 있으면 거기서 읽기를 멈추고 그 앞까지의 합을 커버리지로 준다(그 뒤 휴게소도 넣지 않는다). 깨진 거리를 0으로 바꿔 끝까지 더하면 뒤의 휴게소가 실제보다 가깝게 놓이고 커버리지도 '목적지까지 다 안다'처럼 보인다. 모른다고 하는 쪽이 안전하다. 그래서 첫 거리부터 깨졌으면 빈 목록이 아니어도 0이다(빈 목록과 똑같이 '아무것도 모른다'로 다루면 된다). 정상 목록에서는 '전체 합'과 같다.

`tbtReceived`와 `tbtCoverageM`으로 세 경우를 가른다 (서비스 감사 D5·P5):
- 받기 전(`tbtReceived=false`, 커버리지 `null`): 경로 위 시설을 모른다.
- 빈 TBT(커버리지 0): 아무 구간도 모른다. 길안내 중 경로에는 목적지라도 있어야 하므로 '경로 위에 휴게소가 없다'는 뜻이 아니다.
- 목록이 남은 거리보다 짧음(`liveTbtCoverageM < 남은 거리`): 그 뒤는 모른다. 후보 담당이 `candidatesUntilM`에 쓴다.

## 4. 조회 규칙

| 무엇 | 언제 |
|---|---|
| 경로 상태 + 목적지 | 15초. 실패하거나 남은 거리를 못 읽으면 5초 뒤 |
| TBT | 경로가 있는 길안내(ROUTE_GUIDING, SIMULATION_GUIDING) 중에만. 기본 5초, 다음 화장실·회전이 8km 넘게 멀고 보정을 믿으면 20초. 새 epoch·`requestRefresh` 뒤 30초는 5초. 실패하면 5초 뒤 |
| 이벤트·`requestRefresh` | 곧바로 경로 + TBT. 단 내비 요청은 1초에 한 번까지 (몰려도 합친다) |
| 위치 대체 | `LocationFallback.wanted()`가 참일 때 (루프마다 확인, 5초 이하) |
| 연결 | 실패하면 2초, 4초, 8초 … 최대 1분 간격으로 계속 (서비스 감사 D13) |
| 호출 시간 초과 | 20초 (kit의 15초보다 길게. 포트가 영영 답하지 않아도 루프가 멈추지 않는다) |

루프는 다음 할 일까지 자되 5초를 넘기지 않는다(가까워지면 TBT 간격이 짧아지므로). 요청·이벤트는 CONFLATED 채널로 루프를 깨운다.

## 5. epoch 규칙

| 상황 | 알아채는 방법 | epoch | 그 밖에 |
|---|---|---|---|
| 길안내 시작 | 경로 조회: 경로 없음 → 있음 | +1 | TBT 비우고 곧바로 받음, 30초 빠른 조회, 정체 판정 새 경로 |
| 길안내 끝 (취소·도착) | 경로 조회: 있음 → 없음 | +1 | 경로·목적지·TBT·보정 신뢰 지움, TBT 조회 멈춤 |
| 재탐색 | 남은 거리가 1km 넘게 늘거나 주행 거리로 설명 못 할 만큼(3km) 줄음 (`checkProgress`) | +1 | 보정 신뢰 다시 얻기 |
| 목적지 변경 | 목적지 좌표가 100m 넘게 바뀜 (좌표를 모르면 이름) | +1 | |
| 내비 이벤트 ROUTING_COMPLETED, REROUTED, ROUTE_CANCELLED, DESTINATION_CHANGED, WAYPOINT_CHANGED, DESTINATION_ARRIVED | 곧바로 경로 조회 | 경로가 있으면 +1 | 이벤트가 TBT 조회 도중에 오면 그 응답은 버림 |
| ROUTE_STARTED(탐색 시작), WAYPOINT_ARRIVED(경로는 이어짐) | 곧바로 다시 읽기만 | 상태가 바뀌었으면 위 규칙대로 | |
| `requestRefresh()` (경유지 추가·길안내 시작 뒤) | 곧바로 경로 조회 | +1 | `routeChanged=false`면 epoch 그대로 |
| 앱이 길안내 도중에 켜짐 | 첫 조회부터 길안내 | +1 | 출발지를 몰라 출발지 기준 정체는 넣지 않음 |

새 epoch마다 보정 신뢰를 끄고 다시 얻는다(300m 이상 달려 내비 진행과 맞으면). 옛 경로의 남은 거리와 새 경로의 남은 거리를 섞어 비교하지 않기 위해서다.

옛 TBT가 새 경로와 짝이 되지 않게 하는 장치 세 겹 (구조 리뷰 D13):
1. 새 epoch 스냅샷은 TBT를 비운 채 나간다.
2. TBT를 묻는 사이 경로 변경 이벤트가 오면 응답을 버린다. 경로가 바뀐 줄 아는데 경로를 아직 다시 못 읽었으면 TBT를 묻지 않는다.
3. `NaviSnapshot`의 `live*` 함수는 `tbtEpoch != epoch`면 TBT 값을 쓰지 않는다.

남는 창: 내비가 이벤트 없이 재탐색하면 다음 경로 조회(최대 15초)까지는 알 수 없다. 그 사이 받은 TBT는 새 경로의 것이고 옛 epoch에 붙는다. 반대(옛 TBT가 새 경로에 붙음)는 일어나지 않는다. 새 경로의 TBT가 실제로 갈 길이라 이쪽이 덜 해롭다.

## 6. 실패 처리

| 실패 | 동작 |
|---|---|
| 경로 상태 조회 실패 | 스냅샷 그대로, 5초 뒤 다시. TBT는 지난 스냅샷 기준으로 계속 |
| 목적지 조회 실패·남은 거리 NaN | 길안내 시작이면 남은 거리 `null`로 길안내를 알린다(TBT는 받는다). 같은 경로면 지난 남은 거리를 그대로 둔다(엔진 입력이 null로 깜박이지 않게). 5초 뒤 다시 |
| TBT 실패 | 지난 TBT 그대로(같은 epoch일 때), 5초 뒤 다시. 경로 조회는 영향 없음 |
| 무응답 | 20초에 끊고 실패로 처리. `requestRefresh`는 그동안에도 막히지 않는다 |
| 같은 실패 반복 | 로그는 처음과 12번째마다, 회복하면 한 줄 |
| 이벤트 흐름이 끝나거나 실패 | 2초 뒤 다시 구독 |

## 7. 정체 offset 기준 판정 (`CongestionBasis`)

먼저 시간: NaviHelper의 `trafficCongestionTime`은 구간을 지나는 시간이 아니라 분 단위로 반올림한 늘어난 시간이다(최소 1분, 2026-11-12 실측,
CLAUDE.md). 엔진은 `durationS`를 지나는 시간으로 쓰므로 포트 경계(`KitNaviPort` → `NaviCongestion.fromNavi`)에서 거리 ÷ 평균 속도로 바꾼다
(`NaviCongestion.crossingS`, 평균 속도가 없으면 자유 흐름 80km/h + 늘어난 시간, 상한 6시간, 깨진 길이는 버림). 테스트 `NaviCongestionTest`.

NaviHelper의 정체 `offset`이 출발지 기준인지 현재 위치 기준인지 문서에 없고, 에뮬레이터 내비는 움직이지 않아 확인하지 못했다. 그래서 주행 중에 판정한다.

- 같은 구간(도로명 + 시작 교차로)을 처음 본 뒤 내비 남은 거리가 1km 이상 줄면 한 표: offset도 그만큼(±30%) 줄었으면 FROM_CAR, 거의 그대로(이동의 20% 안)면 FROM_ORIGIN, 아니면 표 없음(정체가 자라거나 줄었다).
- 주행 거리도 같은 만큼(±30%) 늘었을 때만 센다. 남은 거리 차이만으로는 주행과 경로 재계산을 못 가르고, 주행 거리만으로는 내비가 서 있는 경우(에뮬레이터)를 못 가른다. offset과 비교하는 이동 거리는 offset과 같은 기준인 내비 남은 거리 차이다.
- offset 300m 미만은 쓰지 않는다. 차가 정체 안에 있으면 현재 위치 기준 offset도 0 근처에 머물러 출발지 기준처럼 보인다.
- 두 표 이상, 다른 쪽보다 두 표 넘게 앞서면 정한다. 표를 던진 구간은 그 자리부터 다시 잰다(같은 관측을 두 번 세지 않게).
- 정하기 전에는 현재 위치 기준으로 본다(예전과 같다).
- 출발지 기준이면 `offset − (경로 시작 때 남은 거리 − 지금 남은 거리)`. 경로 시작 때 남은 거리는 길안내 시작·재탐색 때 잡는다(예전에는 첫 정체를 본 때). 경로 시작을 못 봤으면(앱이 도중에 켜짐) 정체 위치를 정할 수 없어 넣지 않는다.

결정: 판정 결과는 경로가 바뀌어도 유지한다. 어느 기준인지는 경로가 아니라 내비 API의 성질이라서다. 경로마다 바뀌는 것(구간별 첫 관측, 경로 시작 때 남은 거리)만 epoch마다 지운다. 브리프의 'epoch마다 초기화'를 이렇게 나눠 적용했다.

옛 `CongestionMath` 객체는 옛 `CoachService`가 아직 써서 `@Deprecated`로 남겼다. 코어 담당이 CoachService를 NaviTracker로 옮길 때 지운다.

## 8. 실제 위치 (`PositionTracker`)

- 실제 위치만 담는다. 시연 위치는 CoachWorld가 덧씌운다. 그래서 시연 중에도 실제 위치가 계속 들어오고, 시연을 끄면 바로 실제 위치다(구조 리뷰 D3, B3).
- 신선도는 실제 시간(`ElapsedClock`)으로만 잰다. 생성자에 코치 시계가 없으니 시연 가상 시각과 섞일 수 없다.
- 내비 대체가 필요한 때: 위치가 아직 없거나, 움직이는 동안 15초 넘게 새 위치가 없을 때. 서 있으면 오래된 위치도 맞다(서비스 감사 P6). 오래 서 있다 출발하면 출발한 때부터 15초를 준다.
- 내비에 묻고 답을 받는 사이 위치 SDK가 새 값을 주면 내비 답을 버린다.
- 방위: 내비 heading 0.0은 SDK 기본값이라 모름으로 본다. 위치 SDK의 0.0도 서 있을 때는 모름으로 본다. 모르면 같은 자리(30m 안)면 직전 방위, 1분 안에 50m 넘게 움직였으면 두 위치의 방위, 아니면 `null`(데이터 감사 #10). `null`이면 후보 검색이 방향으로 거르지 않는다. 틀린 방위로 앞뒤가 뒤집히는 것보다 낫다.
- `isMoving`: 차속을 알면 1km/h 이상, 모르면 P가 아닌지.

## 9. 스레드

- `NaviTracker.run()`과 `pollOnce()`는 한 코루틴(코치 디스패처)에서만 돈다. 조회 시각, epoch, 보정 기준 같은 상태는 그 코루틴만 쓴다. 내비 호출은 순서대로 한다(kit도 뮤텍스로 한 번에 하나만 보낸다).
- 다른 코루틴이 루프에 알리는 길은 두 개: `requestRefresh`와 내비 이벤트. 둘 다 원자 플래그(`AtomicInteger`)와 CONFLATED 채널만 쓴다. 플래그는 `pollOnce` 시작에서 한꺼번에 가져가므로, 조회 도중 온 요청은 다음 조회에서 처리된다(예전 `forceRoute`의 잃어버린 갱신, 서비스 감사 P10).
- 스냅샷은 `MutableStateFlow`에 통째로 바꿔 넣는다. 읽는 쪽은 언제든 일관된 한 묶음을 본다.
- `Odometer.meters`는 코치 틱 하나가 쓰고 `@Volatile`이다. 트래커는 요청 시점의 값을 읽어 응답과 함께 저장한다.
- `PositionTracker`의 메서드는 `@Synchronized`라 어느 스레드에서 불러도 된다.

## 10. 시나리오 표 → 테스트

| 시나리오 | 테스트 |
|---|---|
| 길안내 아님 → 경로 15초, TBT·목적지 조회 없음 | `NaviTrackerTest.notGuidingPollsTheRouteEvery15sAndNeverAsksTbtOrDestination` |
| SAFE_GUIDING = 경로 없음 | `safeGuidingHasNoRouteSoItIsIdle`, `NaviPollTest.safeGuidingWithoutARouteIsNotGuiding` |
| 길안내 + 보정 못 믿음(내비가 서 있음) → TBT 5초 | `guidingWithAnUntrustedOdometerPollsTbtEvery5s` |
| 믿음 + 멂 → 30초 뒤부터 20초 | `trustedAndFarPollsTbtEvery20sAfterTheFastWindow` |
| 화장실 8km 안 → 5초 | `aNearOpportunityKeepsTbtAt5sEvenWhenTrusted` |
| `requestRefresh(false)` → 즉시, 30초 동안 5초, epoch 그대로 | `requestRefreshPollsAtOnceThenTbtEvery5sFor30s` |
| `requestRefresh()` → epoch+1, 옛 TBT 안 씀, 신뢰 초기화 | `requestRefreshAfterChangingTheRouteStartsANewEpoch` |
| 길안내 시작 → epoch+1, TBT 즉시, 정체 전체 거리 이때 | `guidanceStartBumpsTheEpochAndFetchesTbtAtOnce` |
| 길안내 도중 앱 시작 → 출발지 모름 | `startingMidRouteDoesNotKnowTheOrigin` |
| 재탐색(남은 거리 증가) → epoch+1, 정체 초기화, 옛 TBT 안 씀 | `aRerouteBumpsTheEpochResetsCongestionAndNeverPairsTheOldTbt` |
| 목적지 변경 → epoch+1 | `aDestinationChangeBumpsTheEpoch` |
| 내비 이벤트 → 주기 전에 다시 읽음 | `aNaviEventRefreshesBeforeTheNextPeriod` |
| 취소 이벤트 → 즉시 지움 | `aCancelEventClearsTheRouteAtOnce` |
| 길안내 아닐 때 이벤트 → epoch 그대로 | `anEventWhileIdleOnlyRefreshes` |
| 경로 변경과 경쟁한 TBT 응답 → 버림 | `aTbtAnswerThatRacedARouteChangeIsDropped` |
| 경로 변경을 알았는데 경로 조회 실패 → TBT 안 물음 | `aKnownRouteChangeBlocksTbtUntilTheRouteIsReadAgain` |
| 이벤트 폭주 → 1초에 한 번 | `aBurstOfEventsIsCoalescedToOneRequestPerSecond` |
| 길안내 끝 → 경로·TBT·신뢰 지움, TBT 멈춤 | `leavingGuidanceClearsRouteTbtAndTrust` |
| 빈 TBT vs 아직 못 받음 | `anEmptyTbtIsReceivedButCoversNothing`, `NaviSnapshotTest.coverageIsUnknownUntilTheTbtArrives` |
| TBT가 목적지보다 짧음 | `aTbtListShorterThanTheRouteReportsItsCoverage` |
| TBT 거리 NaN·음수·무한 | `aBrokenTbtDistanceStopsReadingThere`, `TbtDigestTest.readingStopsAtABrokenDistance`, `aBrokenFirstDistanceLeavesNoManeuver` |
| TBT 실패 → 경로 조회 계속, 로그 제한, 회복 | `aFailingTbtDoesNotStopRoutePolling` |
| 받은 TBT는 epoch 끝까지 유지 (실패·무응답에도) | `onceReceivedTheTbtStaysForTheRestOfTheEpoch` |
| TBT 종류는 이름이 분명하면 이름 (SERVICEAREA로 온 졸음쉼터) | `TbtDigestTest.theNameDecidesTheKindWhenItIsClear` |
| 경로 조회 실패 → 5초 뒤, 스냅샷 유지 | `aFailingRouteCallRetriesIn5sAndKeepsTheLastSnapshot` |
| 목적지 조회 실패 / 남은 거리 NaN | `aFailingDestinationCallStillStartsGuidanceAndKeepsTheLastRemainLater`, `aBrokenRemainingDistanceIsUnknownNotZero` |
| 내비 무응답 → 시간 초과, 요청은 안 막힘 | `aHangingNaviCallTimesOutAndNeverBlocksRequests` |
| 연결 실패 → 백오프 재시도 | `aFailingConnectRetriesWithBackoff` |
| 위치 대체: 원할 때만 내비에 물음 / PositionTracker와 함께 약 20초마다 | `theLocationFallbackAsksTheNaviOnlyWhileWanted`, `withThePositionTrackerAStaleFusedFeedIsReplacedAboutEvery20s` |
| 시작·재탐색·이벤트·실패·취소·재시작을 섞은 대본의 불변식 | `everyPublishedSnapshotIsInternallyConsistent` |
| 실시간 보정 (믿음/못 믿음/다른 epoch) | `NaviSnapshotTest` 4개 |
| 정체: 현재 위치 기준 / 출발지 기준 / 판정 불가 | `CongestionBasisTest.offsetsThatShrinkWithTheCarAreFromTheCar`, `offsetsThatStayPutAreFromTheOriginAndAreConvertedWithTheTotalFromTheStart`, `inconsistentChangesDecideNothingAndOffsetsStayAsGiven` |
| 정체: 전체 거리는 경로 시작 때 | `theRouteTotalIsTakenAtTheStartNotAtTheFirstJam`, `aMissingStartRemainIsTakenFromTheFirstKnownRemainOfTheRoute` |
| 정체: 정체 안에서 출발 | `startingInsideAJamDoesNotLookLikeFromOrigin` |
| 정체: 주행 거리와 내비 진행이 맞을 때만 | `movementCountsOnlyWhenTheOdometerAndTheNaviAgree` |
| 정체: 엇갈린 표 | `contradictingVotesNeedALeadOfTwo` |
| 정체: 새 경로는 관측을 지우고 판정은 유지 | `aNewRouteForgetsOldSightingsButKeepsTheDecidedBasis` |
| 정체: 출발지를 모르면 넣지 않음 / 깨진 항목 | `fromOriginWithoutKnowingTheOriginCannotPlaceAJam`, `brokenItemsAreDroppedAndPassedJamsFiltered` |
| 위치: 서 있는 차의 오래된 위치는 신선 (P6) | `PositionTrackerTest.aStaticFusedFixWhileParkedIsNotStale` |
| 위치: 움직이며 15초 무소식 → 대체 / 오래 서 있다 출발 | `movingWithoutUpdatesFor15sIsStale`, `startingToMoveAfterALongParkGivesTheFusedFeed15s` |
| 위치: 내비 답보다 새 위치 SDK 값 | `aNaviAnswerIsDroppedWhenAFresherFusedFixArrivedMeanwhile` |
| 위치: 방위 0.0 | `aNaviHeadingOfZeroIsUnknownNotNorth`, `aFusedHeadingOfZeroCountsOnlyWhileMoving`, `anOldFixIsNotUsedToEstimateAHeading`, `aRealNaviHeadingIsKept` |
| 위치: 잘못된 좌표 / 시연에서 대체 금지 / 움직임 판정 | `invalidCoordinatesAreIgnored`, `theWorldCanForbidTheNaviFallback`, `movingMeansSpeedAboveWalkingPaceOrNotParkedWhenSpeedIsUnknown` |

테스트가 실제로 막는지 변이 검사로 확인했다: 22가지 변이(TBT 버리기 제거, 새 epoch에 옛 TBT 유지, 빠른 조회 창 제거, 요청 간격 제거, 호출 시간 초과 제거, 정체 최소 offset 제거, 주행 거리 일치 조건 제거, 출발 후 15초 유예 제거, 내비 방위 0.0 신뢰 등)를 하나씩 넣었을 때 모두 테스트가 실패했다.

## 11. 코어 담당이 연결할 것

- `Odometer` 하나를 코치 틱이 `advance(hub.speedKph)`하고, 같은 인스턴스를 `NaviTracker`와 후보 담당에 넘긴다.
- `PositionTracker(elapsed) { PositionTracker.isMoving(hub.speedKph, hub.gear == Gear.PARK) }`. 위치 SDK 수집은 `onFused(Fix(lat, lon, heading))`만 한다(시연 중에도 계속).
- `NaviTracker(navi, odometer, { hub.speedKph != null }, elapsed, log, positions.naviFallback { world.usesNaviLocationFallback })`, `launch { tracker.run() }`.
- 수락 처리: 경유지/길안내 결정 직전에는 `navi.routeStatus()`를 새로 읽는다(스냅샷은 최대 15초 묵었다, 구조 리뷰 D12). 성공하면 `tracker.requestRefresh()`.
- `CoachService`의 `collectNavi`, `refreshRoute`, `collectLocation`, `liveRoute`, `moved`, `markNaviReset`과 관련 필드, `CongestionMath.kt`를 지운다.
- 후보 담당은 `snapshot.epoch`로 후보를 다시 만들지 정하고, `liveOpportunities`, `tbtReceived`, `liveTbtCoverageM`으로 TBT 범위를 판단한다.

## 12. 남은 것

- 정체 offset 기준은 여전히 실차에서 확인하지 못했다. 판정 로직은 둘 다 처리하지만, 실제 주행 로그가 생기면 `CongestionBasis` 상수(1km, ±30%, 300m)를 다시 볼 것.
- 이벤트 없는 재탐색은 다음 경로 조회(최대 15초)까지 모른다. TBT 커버리지와 남은 거리의 차이로 더 일찍 알아챌 수 있지만 내비가 TBT를 어디까지 주는지 확인되지 않아 넣지 않았다.
- 내비 모드가 짧게 깜박이면(길안내 → 경로 없음 → 길안내) epoch가 두 번 는다. 실제 취소 후 재시작과 구별할 방법이 없어 그대로 둔다.
