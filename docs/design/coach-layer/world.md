# 코치 계층: 세계(world)와 여정(trip)

담당: `DemoRun`(시연 한 번), `CoachWorld`(실제/시연 세계), `TripSession`(여정 기준점과 수명), `WorldSwitch`(세계 전환).
코드: `rest-engine/coach/src/main/kotlin/dev/shimpyo/coach/` 의 `DemoRun.kt`, `DemoDirector.kt`(호환용), `CoachWorld.kt`, `TripSession.kt`, `WorldSwitch.kt`.
모두 안드로이드 없는 순수 Kotlin이고 JVM 테스트로 검증한다.

2026-11-12 닫힘 검증 뒤 바뀐 것: 저장된 정차는 마지막 맥박이 본 만큼만 센다(4.1, 4.3), 되돌린 정차가 끝났다는 판정을 엔진에도
알린다(`restoredParkEnded`, 4.3), 들르기로 한 곳을 맥박에 적는다(4.4), 건강 정보 동의를 거두면 모든 세계에서 건강 유형 탑승자를 뺀다
(`dropHealthOccupants`, 6장). 코어 쪽 이음매는 `core.md` 8·10장.

## 1. 왜 필요한가

예전 `CoachService`는 시연과 실제를 이렇게 다뤘다 (구조 리뷰 2.3c·2.3d·2.4, 서비스 감사 D3·D4·D8·D10·D12·D15, UI 감사 #15).

- 시연 여부가 `DemoDirector` 안의 nullable 필드였고, 서비스 곳곳의 `director.active` 분기 8곳이 저장·위치·후보 정책을 나눠 정했다. 새 기능마다 분기를 잊는 버그가 났다.
- `DemoDirector`는 시나리오·시작 시각·시계 차이를 세 필드로 따로 바꿨다. 다른 스레드가 새 시나리오와 옛 시작 시각을 섞어 읽을 수 있었다. 시연 날짜는 주입한 시계가 아니라 시스템 시계로 정했다.
- 여정(출발 시각, 마지막 화장실)은 서비스 시작 때 한 번 정하고 바꾸지 않았다. 주행 중 재시작하면 피로가 0부터 다시 셌다. 프로세스가 하루를 넘겨 살아 있으면 어제 기상 시각을 계속 썼다. 나중에 탄 사람은 화장실 게이지가 'NOW'로 시작했다.
- 엔진을 새로 만드는 것으로 모드를 바꿨다. 시연을 켜면 실제 학습을 저장하지 않고 엔진을 버렸다. 시연을 끄면 파일에서 연속 주행 0으로 엔진을 다시 만들었다.
- 시연 중 탑승자나 수면 시간을 바꾸면 실제 여정의 출발 시각으로 프로필을 만들었다. 그래서 시연 여정이 리셋됐고, 시연 탑승자가 설정에 저장됐다.
- 시연 정차가 가상 시각으로 `stops.json`에 저장됐다. 다음 실제 시작 때 미래 시각이 '마지막 화장실'이 될 수 있었다.

## 2. 구성 요소와 공개 API

```
WorldSwitch ──owns──► ActiveWorld(world: CoachWorld, engine: CoachEngine, session: TripSession, generation)
     │                         ▲ 바꿀 때는 통째로 (틱 사이)
     ├─ keptReal: ActiveWorld? (시연 중 옆에 둔 실제 세계)
     │
CoachWorld = Real(wall) | Demo(run: DemoRun, occupantsOverride?)
DemoRun     = 불변 (scenario, startedAtWall, startedAtElapsed, clockOffsetMs) : CoachClock
TripSession = 여정 기준점(TripAnchor) + 연속 주행 + 정차 상태 + 맥박 저장(TripStorePort)
```

| 타입 | 역할 | 주요 API |
|---|---|---|
| `DemoScenario` | 시나리오 정의 (불변) | `NAMES`, `byName(name)` |
| `DemoRun` | 시작된 시연 한 번 (불변, `CoachClock`) | `start(name, wallNow, elapsed, elapsedNow?, zone?)`, `now()`, `travelledM()`, `position(): Fix`, `apply(input)`, `candidates(list)`, `alongM(pt)`, `anchor` |
| `DemoDirector` | 옛 서비스용 호환 겉면. 불변 `DemoRun` 참조 하나만 바꾼다 | 예전 API 그대로 (`start`, `now`, `position`, `apply`, `candidates`, `active`, `name`, `oracleEnabled`) |
| `CoachWorld` | 세계별 정책과 변환 | `name`, `clock`, `candidatePolicy`, `oracleEnabled`, `persistsLearning`, `persistsHistory`, `persistsTrip`, `usesNaviLocationFallback`, `engineConfig`, `occupants(settings)`, `position(real)`, `apply(input)`, `candidates(live)`, `profile(settings, anchor)`, `newEngine(factory, settings, memory, anchor, park?)`, `endPark(engine, endedAt)`; `Demo.withOccupants(list)`, `Demo.occupantsEdited` |
| `TripAnchor` | 여정 기준점 | `tripStart`, `lastRestroomAt`(탑승자별), `restroomFallbackAt`(나중에 탄 사람), `continuousDrivingMin`, `lastRestroomFor(occupants)`, `withRestroomAt(at)` |
| `ParkState` | 지금 정차 (`since`, `doorSeen`) | prompt 트랙 정차 감지기와 합칠 최소 타입 |
| `TripHeartbeat` / `TripStorePort` | 여정 맥박과 그 저장소 (새 포트). 맥박에는 정차(`park`)와 들르기로 한 곳(`accepted: AcceptedStop(stopId, at)?`)도 담긴다 | `load()`(시작 때 IO에서), `save(h)`(막지 않음) |
| `TripRules` | 임계값 | 4절 |
| `StopHistory` | 저장된 정차 기록 읽기 규칙 | `sanitize(history, now)`, `restroomAt(log)`, `lastRestroomAt(history)` |
| `TripSession` | 한 세계의 여정 | `open(saved, history, now, elapsed, store)`, `started(anchor, now, elapsed)`, `onTick(now, park): TripEvent?`, `onStop(log, now)`, `onAccepted(stopId, now)`, `flush(now)`, `anchor`, `park`, `restoredPark`, `accepted`, `restoredAccepted`, `origin` |
| `WorldSwitch` | 세계 전환 | `open(heartbeat, memory, history)`, `enterDemo(name)`, `exitDemo()`, `setOccupants(list)`, `dropHealthOccupants()`, `profileChanged()`, `clearLearning()`, `onTick(now, park)`, `restoredParkEnded(endedAt)`, `onAccepted(stopId)`, `onStop(log, now)`, `memoryToSave()`, `flush()`, `active`, `real` |
| `WorldChange` | 전환 결과 | `active`, `kind`(NONE/ENGINE/WORLD), `saveMemory: MemorySave?`, `restorePark: ParkState?` |

## 3. 세계별 정책

다른 코드는 "시연 중인가?"를 묻지 않는다. 세계에 정책을 묻는다.

| 정책 | Real | Demo | 왜 |
|---|---|---|---|
| `clock` | 실제 시각(wall) | `DemoRun` (가상, 배속) | 엔진은 입력 시각으로만 판단한다. 시연은 시각을 당겨 몇 분 안에 보여 준다 |
| `candidatePolicy` | `REAL` (TBT, 보정 6곳, 20초) | `DEMO` (TBT 안 씀, 보정 없음, 5초) | 시연 경로는 가상이라 내비 TBT·requestTrip이 맞지 않다 |
| `oracleEnabled` | true | false | 같은 이유 |
| `persistsLearning` | true | false | 시연은 매번 빈 학습으로 같은 조건에서 시작한다. 사용자 학습을 더럽히지 않는다 (D15) |
| `persistsHistory` | true | false | 가상 시각 정차를 `stops.json`에 쓰지 않는다 (B4, D3, UI #15). 화면에는 보인다 |
| `persistsTrip` | true | false | 시연 여정은 저장하지 않는다 |
| `usesNaviLocationFallback` | true | false | 시연 위치가 덧씌워져 있다 (D3) |
| `occupants(settings)` | 설정 | 시나리오 (편집하면 그 시연에만) | 질문·음성 해석·화면이 같은 탑승자를 쓴다 (D9) |
| `position(real)` | real | 시연 경로 위 위치 | 실제 위치는 PositionTracker가 계속 받는다. 시연은 덧씌울 뿐 쓰지 않는다 (B3) |
| `apply` / `candidates` | 그대로 | 시나리오 경로·정체·후보 거리 | 실제 내비 경로를 섞지 않는다 (2026-11-11 회귀) |
| 엔진 학습 상태 | 읽은 것 | 빈 것 | |

`profile(settings, anchor)`가 프로필을 만드는 단 한 곳이다. 탑승자는 세계에서, 수면 시간은 설정에서, 여정 값은 기준점에서 가져온다. `ActiveWorld.profile(settings)`는 그 세계의 여정(`session.anchor`)을 넘긴다. 그래서 시연 중 설정·탑승자를 바꿔도 시연 기준점이 쓰이고 엔진 여정이 리셋되지 않는다 (D4·D5).

## 4. 여정 규칙과 임계값

| 값 | 기본 | 근거·선택 이유 |
|---|---|---|
| 휴식 `restMs` | 15분 (엔진 `restMinParkedMin`) | AI §9-2: 도로공사 휴식포인트 15분. 재개 창도 같은 값이다. 헤드유닛이 15분 넘게 꺼져 있었으면 차가 그만큼 서 있었던 것이고, 그것은 엔진 기준으로 휴식이다 |
| 화장실 정차 `restroomStopMs` | 3분 (엔진 `restroomStopMinParkedMin`) | 엔진 StopTracker와 같다 (P + 문 열림 + 3분) |
| 틱 간격 상한 `maxTickGapMs` | 2분 (엔진 `maxUpdateGapS`) | 엔진 FatigueModel과 같다. 앱이 멈췄거나 시연 중 실제 엔진이 멈춰 있던 시간은 2분까지만 주행으로 센다 |
| 새 여정 `newTripParkMs` | 4시간 | 설계값. 연구에 "새 여정" 임계값은 없다. 이 값은 두 조건을 함께 맞춘다. (1) 보통 휴식은 여정을 끊지 않는다: 15분 휴식, 35분 식사(AI §9-3 플래너 체류시간), 1~2시간 점심. (2) 밤샘 주차(6시간 이상)와 "도착해서 하루 보내고 귀가"는 끊는다. 그래야 다음 날 아침 운전이 "25시간 깨어 있음"으로 계산되지 않는다(기상 추정이 출발 시각 기준). 4시간 서 있었으면 성인 화장실 사전 중앙값(2.5시간, AI §9-1)도 이미 지나서 이전 여정의 화장실 정보는 쓸모가 없다. 3~6시간 사이 어떤 값이든 위 사례에서 결과는 같다 |
| 화장실 모름 `unknownRestroomMs` | 60분 | AI §9-1: 출발 전 화장실 "아니오/모름"이면 경과시간 60분에서 시작 |
| 맥박 간격 `heartbeatEveryMs` | 1분 (경과 시간) | 재시작은 수 초~1분이다. 정차 사건은 기다리지 않고 바로 저장한다 |
| 미래 허용 `futureToleranceMs` | 1분 | NTP 보정으로 저장 시각이 조금 앞설 수 있다 |
| 정차 기록 상한 `maxStopMs` | 24시간 | 그보다 긴 기록은 시계가 섞인 것으로 본다 |
| 들르기로 한 곳 유지 `acceptedHoldMs` | 90분 | 엔진이 들르기로 한 곳을 두는 시간과 같다. 그보다 오래된 수락은 맥박에 싣지도 이어받지도 않는다 |

### 4.1 시작 (`TripSession.open`)

| 상황 | 결과 | 테스트 |
|---|---|---|
| 맥박 없음 (첫 실행) | 새 여정. 마지막 화장실 = max(최근 정차 기록, 지금−60분) | `firstStartIsANewTripWithTheRestroomUnknownSixtyMinutesBack` |
| 맥박 < 15분, 달리던 중 (sticky 재시작) | 이어 감(RESUMED). 출발 시각·화장실 유지. 연속 주행 = 저장값 + 공백. 재시작 동안에도 차는 달렸다 | `stickyRestartMidDriveResumesTheTripAndItsFatigue`, `aStickyRestartMidDriveKeepsTheFatigueGauge` |
| 맥박 < 15분, 정차 중 | 이어 감. 정차(`restoredPark`)를 감지기와 엔진에 되돌린다. 화장실·휴식은 **마지막 맥박이 본 만큼만** 센다 (아래) | `restartWhileParkedRestoresTheParkAndItsDoor`, `aSavedParkIsCreditedOnlyAsFarAsTheLastHeartbeatSawIt`, `aRestartWhileParkedResumesTheStopInTheEngine` |
| 맥박 ≥ 15분 (헤드유닛 꺼짐), 정차 < 4시간 | 같은 여정(RESUMED_AFTER_BREAK). 저장된 정차가 없으면 마지막 맥박부터 정차로 본다. 15분 넘게 꺼져 있었으므로 휴식(연속 0). 화장실은 모름 규칙(지금−60분과 저장값 중 늦은 쪽). 들르기로 한 곳은 잊는다 | `headUnitOffForABreakKeepsTheTripCountsARestAndForgetsTheRestroom` |
| 정차가 4시간 이상 (꺼져 있던 시간 포함) | 새 여정 | `aLongParkStartsANewTrip` |
| 자정을 넘겨 재시작(< 15분) | 같은 여정. 어제 출발 시각이 남아 기상 추정이 맞다 | `dayRollover` (a) |
| 어젯밤 맥박으로 아침 시작 | 새 여정 | `dayRollover` (c) |
| 맥박을 믿을 수 없음 (저장 시각이 미래, 출발·화장실·정차 시각이 저장 뒤, NaN, 다른 판) | 새 여정 | `anUntrustworthyHeartbeatStartsANewTrip` |
| 정차 기록에 미래 시각·시계가 섞인 기록·하루 넘는 기록 | 버린다. 마지막 화장실이 되지 않는다 | `futureDatedOrBrokenHistoryNeverBecomesTheLastRestroom` |

저장된 정차를 얼마나 선 것으로 세나 (서비스 닫힘 검증 N3): 맥박의 정차는 `since`부터 `savedAt`까지만 본 것이다. 예전에는 `open`이
'지금까지 서 있었다'로 세서, 12분 전에 세우고 맥박 2분 뒤 떠난 차가 다시 켜지면 화장실 정차(3분 + 문)로 게이지가 찼는데 정차
기록에는 그 정차가 없었다(감지기는 2분 정차로 봤다). 이제 `open`은 `savedAt − since`만 센다: 그때 이미 3분 + 문이었으면 화장실,
이미 15분이었으면 휴식이다. 차가 지금도 P인지는 첫 틱이 안다. P면 `onTick`이 그 사이를 이어 세고(정차 시작이 그대로라 3분·15분이
그 틱에 잡힌다), 떠나 있으면 정차는 마지막 맥박 때 끝난 것이다. 정차 감지기(prompt.md 4.2)·엔진(4.3)과 같은 규칙이다.
15분 넘게 꺼져 있던 경우의 추정(휴식, 화장실 모름, 4시간이면 새 여정)은 그대로다: 그때는 본 것이 없어서 헤드유닛이 시동과 함께
꺼진다는 사실로 추정한다.

서비스 감사 스케치(TripResumeTest)와 다른 점이 하나 있다. 스케치는 15분 넘게 지난 맥박을 모두 새 여정으로 봤다. 여기서는 30분 점심처럼 시동을 끈 휴식도 같은 여정으로 이어 간다. 그래야 새벽에 출발한 운전자의 기상 추정(출발 시각 기준)이 점심 뒤에도 남는다. 대신 휴식과 화장실 모름을 반영한다.

### 4.2 살아 있는 동안 (`onTick`, `onStop`)

| 상황 | 결과 | 테스트 |
|---|---|---|
| 달림 | 연속 주행 += 틱 간격 (≥ 0, ≤ 2분) | `continuousDrivingFollowsTheEngineRules` |
| P < 15분 | 늘지 않음. 출발하면 이어서 센다 | 같음, `aParkShorterThanFourHoursIsARestNotANewTrip` |
| P ≥ 15분 | 연속 0, 바로 저장 | 같음 |
| P + 문 열림 ≥ 3분 | 모두의 마지막 화장실 = 정차 시작 + 3분, 바로 저장 (출발 전에 재시작해도 남는다) | `aRestroomStopWhileStillParkedIsAnchoredAndSavedAtOnce` |
| 정차 기록(화장실) | 마지막 화장실 = min(끝, 시작 + 3분). 뒤로 가지 않음. 미래 기록 무시 | `restroomStopsMoveLastRestroomForwardForEveryoneIncludingLaterOccupants` |
| P ≥ 4시간 뒤 출발 | `TripEvent.NewTrip`. 출발 시각이 새 여정 시작이다(서 있는 동안 바꾸면 밤 2시가 출발 시각이 된다) | `aLongParkStartsANewTripAtDeparture`, `dayRollover` (b), `aLongParkThenDepartureStartsANewTripInTheEngine` |
| 아무 일 없음 | 맥박 1분에 한 번 | `heartbeatIsSavedAtMostOnceAMinuteWhileNothingHappens` |
| 시계가 뒤로 감 | 빼지 않는다 | `continuousDrivingFollowsTheEngineRules` |

마지막 화장실을 "정차 시작 + 3분"으로 잡는 이유: 엔진은 화장실 정차를 max(문 열림, 정차 시작 + 3분)으로 잡는다. 그 하한을 쓰면 이 값을 프로필로 넘겨도 돌고 있는 엔진의 화장실 시계는 앞당겨지지 않는다(엔진은 앞으로만 옮긴다). 그래서 게이지가 튀지 않는다. 새 엔진이나 새 탑승자에게는 보수적인 값이다(더 일찍 다녀온 것으로 본다).

연속 주행을 엔진 밖에서 따로 세는 이유: 엔진이 이 값을 내놓지 않는다. 엔진을 새로 만들 때(재시작, 기록 지우기) 휴식 리듬을 채울 값(`TripProfile.continuousDrivingMin`)이 필요하다. 그래서 규칙을 엔진 FatigueModel과 똑같이 맞췄다.

### 4.3 재시작 때 정차 이어 가기 (D12, 닫힘 검증 N2·N3)

맥박에 정차(`ParkState`)를 저장한다. 다시 시작할 때 세 곳이 같은 정차를 같은 규칙으로 본다.
1. 정차 감지기(StopDetector): `WorldChange.restorePark`로 받는다. 차가 아직 P인지 떠났는지는 **기어를 아는 첫 틱**에 정한다
   (prompt.md 4.2). P면 원래 시작 시각부터 이어 세고, 떠나 있으면 마지막 맥박 때 끝난 정차로 확정한다.
2. 엔진: `CoachWorld.newEngine(..., park)`가 정차 시작 시각에 P 입력을 한 번 넣는다. 그러면 엔진 StopTracker가 원래 시작부터 정차를
   센다. P에서는 엔진이 질문을 내지 않으므로 이 입력의 출력은 버린다. 공개 엔진 API(update)만 쓴다.
3. 여정(TripSession): 4.1대로 마지막 맥박이 본 만큼만 세고, 그 뒤는 `onTick`이 감지기의 지금 정차로 이어 센다.

감지기가 판정하는 동안(최대 10초) 코어는 여정 틱과 엔진 update를 하지 않는다. 판정이 '떠났다'면 코어가 `WorldSwitch.restoredParkEnded
(endedAt)`을 그 뒤 첫 엔진 update 전에 부르고, `CoachWorld.endPark(engine, endedAt)`가 엔진에도 그 시각에 떠난 것으로 알린다:
`endedAt − 1`의 P 입력으로 정차를 거기까지 세고, `endedAt`의 D 입력으로 끝낸다. D 입력에는 제동 표본을 실어 엔진이 이 입력으로
질문을 내지 않게 한다(조용한 때 규칙: 아무도 보지 못할 질문에 시간당 횟수를 쓰지 않는다). 이것이 없으면 엔진은 다음 update까지 서
있던 것으로 세어, 보지 못한 30분을 화장실 정차·휴식으로 잡았다(게이지는 찼는데 기록은 10분 정차). 시연을 끌 때 옆에 둔 실제
엔진도 같은 길이다: 시연 동안 떠났으면 시연을 켠 실제 시각에 끝난 것으로 알린다.

정차 중 이미 화장실 문턱을 넘었으면 기준점이 정차 시작 + 3분이다. 그래서 엔진이 다시 잡아도 간격이 0이라 학습에 중복으로 들어가지 않는다.

테스트: `CoachWorldTest.anEnginePrimedWithAParkCountsTheStopFromItsStart`, `anEngineToldTheParkEndedCountsItOnlyUntilThen`,
`tellingTheEngineTheParkEndedDoesNotSpendAQuestionNobodySaw`, 감지기·엔진·여정을 함께 보는 `StopRulesCrossCheckTest.aRestoredStopAfterARestart`,
코어까지 조립한 `CoachCoreTripTest`의 재시작 다섯 가지.

### 4.4 들르기로 한 곳 (닫힘 검증 N7)

운전자가 고른 정차를 내비에 넣는 데 성공하면 코어가 `WorldSwitch.onAccepted(stopId)`를 부르고, 여정이 `AcceptedStop(stopId, at)`을
바로 맥박에 저장한다(실제 세계만). 주행 중 재시작(15분 안)으로 이어받으면 `TripSession.restoredAccepted`가 그것이고, 코어는 그 정차가
후보에 보이는 틱에 새 엔진에 `noteAccepted`로 다시 알린다(core.md 6장). 예전에는 새 엔진이 방금 수락한 곳을 몰라 같은 곳을 다시 물었다.

| 끝나는 때 | 왜 |
|---|---|
| 화장실 정차나 15분 넘는 정차가 기록됨 (`onStop`), 정차 중 그 문턱을 넘음 (`onTick`) | 그곳에 섰거나 다른 곳에서 해결했다. 엔진도 같은 때 내린다 |
| 새 여정 | 지난 여정의 약속이다 |
| 90분(`acceptedHoldMs`) | 엔진이 들르기로 한 곳을 두는 시간 |
| 15분 넘게 꺼져 있다가 재시작 | 그사이 섰던 것이다 |
| 새로 고른 곳 | 한 번에 한 곳이다 |

저장하는 것은 정차 id와 시각뿐이다(좌표는 저장하지 않는다). 거절한 질문은 저장하지 않는다: 재시작 뒤 같은 곳의 질문을 대신 거절해
버리면 운전자가 다시 물은 것까지 삼킨다. 맥박의 `accepted.at`이 저장 시각보다 뒤면 그 맥박은 믿지 않는다.

테스트: `TripSessionTest.theAcceptedStopIsSavedAtOnceAndResumedOnlyWithoutABreak`, `theAcceptedStopEndsWithARestroomStopARestANewTripOrNinetyMinutes`,
`aHeartbeatWhoseAcceptedStopIsFromTheFutureIsNotTrusted`, `CoachCoreGoToTest.aConfirmedStopIsWrittenToTheTripHeartbeatAndAFailedOneIsNot`,
`CoachCoreTripTest.aStopAcceptedBeforeAMidDriveRestartIsNotedToTheNewEngineOnce`, `nothingIsNotedAfterABreakOrWhenTheStopNeverShowsUpAgain`.

## 5. 시계 (어떤 상태가 어떤 시계를 쓰나)

규칙: 세계가 가진 상태는 그 세계의 코치 시각을 쓴다. 세계 전환을 넘어 이어지거나 delay·TTS와 맞아야 하는 것은 경과 시각(`ElapsedClock`)을 쓴다. 재부팅을 넘어 저장하는 것은 실제 시각(epoch)을 쓴다.

| 상태 | 시계 | 이유 |
|---|---|---|
| `CoachInput.now`, 엔진 | 세계의 코치 시각 | 엔진은 입력 시각으로만 판단한다. 시연은 가상 |
| `DemoRun.now()` | 시작 실제 시각 + 시계 차이 + 시작 뒤 경과 × 배속 | 벽시계(NTP)가 바뀌어도 시연 시각이 튀지 않는다 |
| 시연 진행 거리 | 경과 | 예전에는 벽시계라 NTP 보정에 시연 차가 순간 이동했다 |
| 시연 날짜 | 주입한 실제 시각의 서울 날짜 | 예전에는 시스템 시계라 테스트가 날짜에 따라 달랐다 |
| `TripAnchor`, `ParkState.since` | 그 여정을 가진 세계의 코치 시각 | 세계마다 여정이 따로다. 실제 여정의 시계는 벽시계라 시연 옆에 있다가 돌아와도 비교할 수 있다 |
| 연속 주행 누적 | 그 세계 코치 시각의 증분 (0~2분) | 엔진 FatigueModel과 같게 (시연 배속에서도 시연 엔진과 맞는다) |
| 맥박 저장 간격 | 경과 | 일정 |
| `TripHeartbeat` 시각 | 실제 시각 | 재부팅을 넘어 비교한다 (경과 시각은 재부팅하면 0) |
| `StopLog` (stops.json) | 실제 세계 시각만 | 시연 정차는 저장하지 않는다 |
| 질문의 실제 수명, 환기 간격(`lastFreshAirAt`), 위치 신선도, 내비 조회 간격 | 경과 (core·prompt·navi 트랙) | 시연 전환을 넘어 이어진다. 예전에는 코치 시각이라 시연 뒤 몇 시간씩 막혔다 (D3, D8) |

## 6. 스레드와 전환 순서

모든 API는 코치 액터(한 디스패처, 한 우편함) 안에서만 부른다. 내부 잠금은 없다. `WorldSwitch`는 파일을 읽거나 쓰지 않는다. 읽기는 코어가 IO에서 해서 `open`에 넘긴다. 학습 저장은 `MemorySave`로 돌려준다(코어가 IO에서 `MemoryPort.save`). 맥박은 `TripStorePort.save`로 넘긴다. 이 포트는 막지 않아야 한다(구현이 IO로 넘기고 마지막 값만 쓴다). `DemoDirector`만 옛 멀티스레드 서비스용이라 `@Volatile` 참조 하나를 쓴다.

`enterDemo(name)`
1. 모르는 이름이나 null이면 `exitDemo()`
2. 처음 들어가면: 실제 학습을 `MemorySave`로 담고, 실제 여정 맥박을 바로 저장하고, 실제 `ActiveWorld`를 옆에 둔다 (다른 시연으로 바꿀 때는 건너뛴다)
3. 시연 세계·시연 여정(저장 안 함)·빈 학습 엔진을 만들고 세대를 올린다 → `WORLD`

`exitDemo()`
1. 옆에 둔 실제 세계가 없으면 `NONE`
2. 같은 엔진을 되돌리고, 시연 중 바뀐 설정을 넘긴다 (같은 tripStart라 이어진다)
3. 실제 여정의 마지막 정차를 `restorePark`로 → `WORLD`

코어가 `WorldChange.kind`에 따라 할 일 (부품마다 무엇을 지우는지는 core.md 8장의 표)
- `WORLD`: 질문 상태 초기화(`PromptMachine` Reset, 음성 작업 모두 취소), 급해 요청·고정, 계획, 자는 중 표시, 이어받던 수락을 버림, 후보 초기화 후 `world.position(real)`로 바로 다시 만들기(시연 위치는 시작 즉시 정해진다), 정차 감지기를 `restorePark`로 되돌리기(끌 때는 시연을 켠 실제 시각을 '마지막으로 본 때'로), `TripEstimator.enabled = world.oracleEnabled` + `clear()`, 화면 `demo`·`occupants`·`history` 갱신, `saveMemory`가 있으면 IO에서 저장
- `ENGINE` (새 여정, 기록 지우기): 질문 상태 초기화, 음성 작업 취소, 계획을 새 엔진으로 다시, 급해 요청이 살아 있으면 새 엔진에 다시 알림
- 세대(`generation`)가 다른 질문·음성 결과·들르기 결과의 엔진 알림은 버린다

`dropHealthOccupants()` (UI 닫힘 검증 N3): 건강 정보 동의는 세계와 무관한 설정 하나인데 탑승자는 세계마다 따로다. 시연 중에 동의를
끄면 옆에 둔 실제 세계의 임신부·과민성 방광·과민성 장 탑승자가 시연을 끈 뒤 동의 없이 돌아와 게이지의 기준이 됐다. 동의가 꺼진 것을
코어가 보면(시작할 때와 매 틱, `Crew.enforceConsent`) 설정에 저장된 실제 탑승자와, 시연에서 사용자가 고친 탑승자(`Demo.occupantsEdited`)
에서 건강 유형을 빼고 두 엔진에 프로필을 다시 넘긴다. 시나리오가 정한 시연 탑승자는 사용자의 정보가 아니라 그대로 둔다.
테스트: `WorldSwitchTest.withdrawingHealthConsentRemovesHealthOccupantsFromEveryWorld`, `scenarioOccupantsOfADemoAreNotTheUsersHealthData`.

## 7. 코어가 CoachService/CoachCore에서 바꿀 것 (병합 체크리스트)

- `DemoDirector`, `tripStart`, `lastRestroomAt`, `profileFor`, `startDemo`, `engine`/`engineLock`를 없애고 `WorldSwitch` 하나로
- 시작: IO에서 `TripStore.load()`, `memory.load()`, `history.load()` → `worlds.open(...)` → `restorePark`를 정차 감지기에. 화면 기록은 `StopHistory.sanitize(history, now)`
- 틱: `t = active.clock.now()`, `pos = active.world.position(positionTracker.real)`, `worlds.onTick(t, stopDetector.park)`(ENGINE이면 질문 초기화), `input = active.world.apply(CoachInput(..., candidates = active.world.candidates(live)))`, `active.engine.update(input)`
- 정차 기록: `worlds.onStop(log, t)`. `active.world.persistsHistory`일 때만 `history.append`. 아니면 화면에만
- 학습 자동 저장: 1분마다 `worlds.memoryToSave()`가 null이 아니고 마지막 저장과 다르면 IO에서
- 명령: `Demo(name)` → `enterDemo(name)`, `SetOccupants` → `setOccupants`, `ProfileChanged` → `profileChanged()`, `ClearRecords` → `memory.clear()` + `history.clear()` + `worlds.clearLearning()`
- 위치 보충: `active.world.usesNaviLocationFallback`. 후보: `active.world.candidatePolicy`
- 종료: `worlds.flush()`, `memoryToSave()` 저장
- 앱 어댑터 `TripStore : TripStorePort`: `filesDir/trip.json`, AtomicFile + org.json. 필드: `savedAt, tripStart, lastRestroomAt{id:ms}, restroomFallbackAt, continuousDrivingMin, park{since, doorSeen}|null, accepted{stopId, at}|null, version`. `accepted`는 2026-11-12에 더했다(없는 파일은 null로 읽는다. 앱의 `Stores.kt`가 읽고 써야 재시작을 넘는다). `save`는 IO 코루틴으로 넘기고(conflate) 막지 않는다
- prompt 트랙 병합: `ParkState`를 정차 감지기의 정차 타입과 합친다. 감지기에 `restore(ParkState?)`와 지금 정차 조회가 필요하다
- (2026-11-12) 감지기가 되돌린 정차를 판정하는 동안(`recorder.restorePending`) `worlds.onTick`과 엔진 update를 건너뛰고, `recorder.takeRestoredEnd()`가 시각을 주면 `worlds.restoredParkEnded(그 시각)`. 들르기 성공에 `worlds.onAccepted(stopId)`. 틱마다 동의가 꺼졌는지 보고 `worlds.dropHealthOccupants()`

## 8. 닫은 결함

| ID | 내용 | 어떻게 | 테스트 |
|---|---|---|---|
| 구조 Q2 / S2, 서비스 P1 | DemoDirector 세 필드 따로 바꿈(찢어진 읽기), 날짜가 시스템 시계 | 불변 `DemoRun`, 날짜는 주입 시계. 호환 겉면도 참조 하나 | `DemoRunTest` 전체 |
| 구조 2.4 | 시연 진행이 벽시계 (NTP에 순간 이동) | 경과 시각 | `wallClockJumpDoesNotMoveTheDemoCarOrItsClock` |
| 구조 3.8 / S11 | 시연·실제 분기 20곳 | `CoachWorld` | `CoachWorldTest` |
| 구조 D9 | 경로마다 탑승자 출처가 다름 | `world.occupants(settings)` | `occupantsComeFromTheWorld` |
| 구조 B4, 서비스 D3, 구조 D4, UI #15 | 시연 정차가 가상 시각으로 저장, 미래 시각이 마지막 화장실 | `persistsHistory=false`, `StopHistory.sanitize`, 세계별 여정 | `demoWorldPoliciesKeepTheDemoOutOfRealData`, `futureDatedOrBrokenHistoryNeverBecomesTheLastRestroom`, `aDemoStartsFromEmptyLearningAndWritesNothingReal` |
| 서비스 D4, 구조 D5 | 시연 중 탑승자·설정 변경이 실제 tripStart로 시연 리셋 | 프로필은 세계의 기준점으로 | `occupantChangesDuringADemoStayInTheDemoAndKeepItsTrip`, `aSettingsChangeDuringADemoKeepsTheDemoTrip`, `profileIsBuiltFromTheAnchorInBothWorlds` |
| UI #15 | 시연 탑승자가 설정에 저장 | `Demo.withOccupants` | `occupantChangesDuringADemoStayInTheDemoAndKeepItsTrip` |
| 서비스 D8 | 재시작하면 피로 0, 하루 넘겨도 같은 여정, 마지막 화장실 고정 | `TripSession` 맥박·재개·새 여정·화장실 갱신 | `TripSessionTest`, `aStickyRestartMidDriveKeepsTheFatigueGauge` |
| 서비스 D12 | 정차 중 재시작하면 정차·화장실 시계 사라짐 | 맥박에 정차, 감지기 복원 + 엔진 프라이밍, 정차 중 화장실 기준점 바로 저장 | `restartWhileParkedRestoresTheParkAndItsDoor`, `anEnginePrimedWithAParkCountsTheStopFromItsStart`, `aRestartWhileParkedResumesTheStopInTheEngine` |
| 서비스 D10, 구조 B7 | 시연을 끄면 실제 엔진을 파일에서 연속 0으로 재생성 | 실제 엔진을 옆에 두고 되돌림 | `aDemoKeepsTheRealEngineAsideAndExitRestoresIt` |
| 서비스 D15, 구조 Q9 | 시연 전 실제 학습 미저장 | `MemorySave` 효과 | `enteringADemoSavesTheRealLearningAndTripFirst` |
| (새로 찾음) | 시연 중 기록 지우기 뒤 시연을 끄면 옆에 있던 실제 엔진이 지운 학습을 자동 저장으로 되살림 | 옆의 실제 엔진도 교체 | `clearingLearningDuringADemoAlsoClearsTheKeptRealEngine` |
| 닫힘 검증 SV-N2·N3 | 재시작 뒤 정차가 신호 순서에 따라 다르고, 여정·엔진은 보지 못한 시간을 선 것으로 세어 게이지와 기록이 어긋남 | 마지막 맥박이 본 만큼만(4.1), 판정을 기어를 알 때까지 미루고 엔진에도 알림(4.3) | `aSavedParkIsCreditedOnlyAsFarAsTheLastHeartbeatSawIt`, `anEngineToldTheParkEndedCountsItOnlyUntilThen`, `StopRulesCrossCheckTest.aRestoredStopAfterARestart` |
| 닫힘 검증 SV-N7 | 주행 중 재시작 뒤 방금 수락한 곳을 다시 물음 | 맥박의 `accepted` (4.4) | `theAcceptedStopIsSavedAtOnceAndResumedOnlyWithoutABreak` 외 |
| 닫힘 검증 UI-N3 | 시연 중 동의를 거둬도 실제 세계의 건강 유형 탑승자가 남음 | `dropHealthOccupants` (6장) | `withdrawingHealthConsentRemovesHealthOccupantsFromEveryWorld` |
| 구조 D11·D6 일부 | 전환이 엔진·시계·후보를 다른 순간에 바꿈 | `ActiveWorld` 통째 교체 + `WorldChange.kind` (코어가 틱 사이에 적용) | `rapidDemoTogglesEndOnTheOriginalRealWorld` |

## 9. 동작이 같다는 확인

- `DemoRun`의 시나리오·경로 코드는 `DemoDirector`에서 그대로 옮겼다(Triple → `DemoScenario.Jam`만 바꿈). 옛 `DemoDirector`(c0c82c2)를 테스트에 임시로 넣어 네 시나리오와 끄기·모르는 이름을 400초씩 비교했다. `now`, `position`, `apply`, `candidates`, 진행 거리가 모두 같았다(시연 날짜를 시스템 날짜에 맞춘 경우. 다른 날짜는 의도한 차이다). 이 비교는 커밋하지 않았다. 계속 지키는 테스트는 `directorMatchesTheRunItWraps`와 `DemoScenarioTest`다.
- `DemoScenarioTest`는 에뮬레이터에서 확인한 결과를 담는다: 귀성길 옥산 2~5km 전 마지막 기회, 죽암이 정체 끝 뒤, 지어낸 etaS 없음, 오후 졸음 휴식 제안, 틀린 공백 경고 없음. 이제 엔진을 `CoachWorld.Demo.newEngine`(제품 경로)으로 만든다. 고정 Random(42)를 쓰고, 정차 시연(휴식·화장실 회복) 검사를 더했다.
- 주요 결함 테스트는 결함을 되살려 실패하는지 확인했다(뮤테이션 6개 모두 잡힘): 시연 프로필을 실제 기준점으로, 시연 중 기록 지우기가 옆 엔진을 무시, 시연 끄기 때 파일에서 재생성, 휴식 뒤 화장실 모름 규칙 제거, 재개 때 연속 주행 0, 기록 정리 제거. 엔진 프라이밍을 끄면 D12 테스트가 실패한다.

## 10. 남은 것

- 같은 날 4시간 넘게 세운 뒤의 두 번째 운행은 새 여정이라, 이른 새벽 출발자의 기상 추정이 기본값(07:00)으로 돌아간다. 설정에 기상 시각이 생기면(`driverWakeAt`) 해결된다.
- 시연 세계도 같은 규칙이라, 정차 시연을 가상 4시간(10배속에서 실제 24분) 넘게 세워 두면 새 여정이 된다. 무해하다.
- 시연 중에는 실제 여정이 멈춘다(맥박 저장도 멈춘다). 시연이 15분 넘게 이어지다 앱이 재시작하면 실제 여정은 "휴식 뒤 재개"가 된다. 실제 차에서는 서 있을 때만 시연한다.
- 예전 버그로 과거 시각에 저장된 시연 정차(미래가 아닌 것)는 실제 기록과 구별할 수 없다. 기록 지우기로 정리한다.
- 지켜보지 못한 정차(헤드유닛 꺼짐)는 감지기에 "마지막 맥박부터 정차"로 되돌린다. 다시 켜졌을 때 P면 그 시각부터의 정차로 기록되고(정확도는 맥박 간격 1분 안), 이미 달리고 있으면 길이 0인 정차라 기록되지 않는다(여정은 15분 넘게 꺼져 있었다는 것으로 휴식을 센다).
- 급해 목록에서 고른 곳은 대개 경로 후보가 아니라서, 주행 중 재시작 뒤에는 후보에 다시 보이지 않아 이어받지 못한다(2분 기다리고 잊는다).

## 11. 테스트

`./gradlew -q -p rest-engine :coach:test --tests 'dev.shimpyo.coach.*'` (서버)
- `DemoRunTest` 11: 서울 날짜 주입, 배속, 정차 시연 연속 시각, NTP 점프, 이름, 재시작, 기준점, 시작 위치, 호환 겉면 2
- `CoachWorldTest` 10: Real 항등, 정책 2, 시연 출력·위치 덧씌우기, 탑승자 출처, 기준점 프로필, 학습 상태, 정차 프라이밍, 정차가 끝났다고 알리기 2
- `TripSessionTest` 19: 4.1·4.2 표, 마지막 맥박이 본 만큼, 들르기로 한 곳 3 (4.4)
- `WorldSwitchTest` 20: 동의 철회 2, 열기, 시연 들고 나기, 학습·맥박 저장, 시연 간 전환, 끄기·모르는 이름, 빠른 전환, 탑승자·설정(시연/실제), 기록 지우기(시연/실제), 새 여정, 학습 저장 정책, 정차 복원, 재시작(주행/정차)
- `DemoScenarioTest` 3: 귀성길, 오후 졸음, 정차 시연
- 가짜: `fakes/WorldFakes.kt` (`WorldClock`, `FakeSettings`, `InMemoryMemory`, `InMemoryTripStore`, `seededEngines`)
