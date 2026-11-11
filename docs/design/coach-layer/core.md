# 코치 계층: 코어 (CoachCore 액터 · 얇은 CoachService)

PT 기술 질의응답용. 코드: `rest-engine/coach/src/main/kotlin/dev/shimpyo/coach/CoachCore.kt`, `CoreRules.kt`,
앱 쪽 `app/src/main/java/dev/shimpyo/app/coach/CoachService.kt`, `CarSignals.kt`. 테스트: `CoachCoreTest`(22), `CoreRulesTest`(7),
가짜와 조립은 `fakes/CoreFakes.kt`(`CoreRig`). 서버에서 `./gradlew -q -p rest-engine :coach:test --tests 'dev.shimpyo.coach.CoachCoreTest'`.

## 1. 왜 액터인가

예전 `CoachService`(871줄)는 필드 30개를 코루틴 12종이 `Dispatchers.Default`(여러 스레드)에서 함께 바꿨다. 시연 전환과 틱이 섞이고
(구조 리뷰 D6), 음성 '응'과 버튼이 둘 다 경유지를 넣고(D7), 시연 정차가 실제 기록에 들어갔다(D3). 버그가 기기 실행마다 하나씩 나왔다.

지금은 코치 상태(세계·엔진·질문·정차·여정·급해·계획·저장 시각)를 `CoachCore` 하나가 들고, 우편함 하나를 코루틴 하나가 처리한다.

- 메시지 처리 중에는 **일시 중단하지 않는다**. 오래 걸리는 일(음성, 내비 명령, LLM 해석, 파일 저장, 타이머)은 효과로 launch하고 결과를
  메시지로 다시 받는다. 그래서 '확인한 뒤 행동' 경쟁이 구조적으로 없다(두 번째 수락은 이미 Idle인 기계가 버린다).
- 판단은 순수 부품이 한다: 엔진, `PromptMachine`, `StopDetector`, `WorldSwitch`/`TripSession`, `CandidateRules`, `CoreRules`.
  코어는 그 결과를 이어 붙이는 껍데기다(functional core, imperative shell).

## 2. 메시지와 효과

| 메시지 | 누가 보내나 | 처리 |
|---|---|---|
| `Tick` | 코어의 1초 타이머 (밀리면 하나만 쌓인다) | 3장 |
| `Command(UiCommand)` | 서비스가 `CoachStore.commands`에서 옮긴다 | 4장 |
| `Prompt(Expired / GoToFinished)` | 만료 타이머, 들르기 실행 효과 | `PromptMachine.on` → 효과 실행. 들르기 성공이면 내비 즉시 재조회, 급해 끝 |
| `AskHeard(key, text)` | 카드의 묻기 음성 효과 (끝나면 반드시, 취소되면 안 보냄) | `Heard(key, yesNo, 같은 말 안의 다른 요청)` |
| `Listened(text)` | 마이크 버튼 듣기 효과 | 규칙 해석(지금 카드 key로 응/아니) → 못 하면 LLM 효과 |
| `Interpreted`, `OccupantsHeard`, `OccupantsInterpreted` | LLM·탑승자 말하기 효과 | 의도 처리, 탑승자 설정 |
| `FreshAirDone(ok)` | 외기 전환 효과 | 되돌리기 예약(90초), 알림 |
| `MemorySaved(memory, ok)` | 학습 저장 효과(IO) | 마지막 저장 갱신 |

`PromptMachine` 효과 → 코어가 하는 일: `Show` 화면 카드·이유·지금 거리, `Ask` 묻기 음성 효과, `CancelVoice` 묻기·듣기 취소 + `VoicePort.stop`,
`Speak` 말하기 효과(음성 끔이면 없음), `ScheduleExpiry` 실제 시간 타이머(같은 key면 교체), `Report` 엔진에 대답, `StartGoTo` 지금 위치 기준
후보로 `GoToRunner` 효과(세계가 바뀌어도 취소하지 않음), `FreshAir` 외기 효과, `Forward` 말로 한 요청 처리, `PublishGoToResult` 화면.

## 3. 틱 한 번

```
odometer.advance(차속)                                  주행 거리 (경과 시간)
t = 세계 시계, pos = world.position(실제 위치)          시연이면 가상 시각·위치를 덧씌운다
recorder.onTick(t, 기어, 차속, 문, pos, world.persistsHistory) → 정차 기록이면 여정에 알림, 화면 기록
worlds.onTick(t, 정차) → 4시간 정차 뒤 출발이면 새 여정(ENGINE 변경: 질문 초기화)
ahead = world.candidates(candidates.live(내비, 주행 거리))   후보를 매초 달린 만큼 줄인다 (UI #19)
input = world.apply(CoachInput(..., route = withHorizon(내비.liveRoute), candidates = ahead + 급해 결과, intent = 쌓인 의도 하나))
gates.request = 후보 루프의 다음 입력 (P·저속이면 방위 null)
out = engine.update(input)
PromptMachine.on(EngineTick(out.prompt, out.next, out.parked)) → 효과
자동 환기(문 10분) · 외기 되돌리기(90초) · 급해 진행 · 계획 갱신 · 화면 상태 한 번 · 학습 자동 저장(1분)
```

화면 상태(`CoachUiState`)는 계약의 모든 필드를 채운다: 게이지·추천·정차 여부·차속(정수)·경로·정체·`ahead`(매초)·`promptStop`(카드 장소의
지금 거리)·`promptReasons`·`goToResult`·`urgentState`/`urgentRequestId`·`candidatesState`·`cabinSupported`·`ttsAvailable`/`sttAvailable`·`demo`·`llm`.
값이 같으면 StateFlow가 내보내지 않으므로 서 있는 동안 화면이 다시 그려지지 않는다(실제 세계의 `live`는 안 움직였으면 같은 리스트).

## 4. 명령

| 명령 | 처리 |
|---|---|
| `Urgent(id)` | 급해 요청 시작(5장). 바로 `urgentRequestId = id, LOCATING`을 올린다 |
| `DismissUrgent(id)` | 그 요청이면 끝 (IDLE). 늦은 결과는 생길 수 없다: 결과는 요청이 살아 있을 때 틱 안에서만 만든다 |
| `Listen` | 말하거나 듣는 중이면 멈추기, 아니면 듣기 효과 (마이크 토글, UI #12) |
| `StopVoice` | 모든 음성 효과 취소 + `stop`. 카드가 묻던 중이면 `Heard(UNKNOWN)`으로 버튼 창 10초 |
| `Answer(key, accept)` | `Tapped` |
| `GoTo(stop)` | `PromptMachine.GoTo` (한 번에 하나, 같은 곳 두 번 무시, 실패 뒤 다시 시도 연결) |
| `SetOccupants` | 건강 유형은 동의(`SettingsPort.healthConsent`)가 있을 때만. 시연이면 그 시연에만 (`WorldSwitch.setOccupants`) |
| `DescribeOccupants` | 묻기 효과 → 규칙(OccupantPhrase) → 못 하면 LLM → 동의 거르기 → 설정 |
| `Demo(name)` | `WorldSwitch.enterDemo` → 6장 |
| `ClearRecords` | 기록 지우기(`HistoryPort.clear`), 학습 지우기(`MemoryPort.clear`, 세대 올림), `WorldSwitch.clearLearning` |
| `ProfileChanged` | 지금 세계의 기준점으로 프로필 다시 (시연 여정 유지) |
| `MakePlan` | 계획을 만들고, 떠 있는 동안 후보 스냅샷·내비 epoch·세계가 바뀌면(또는 30초마다) 다시 만든다 (UI #4) |
| `DismissGoToResult` | 기계로 (다시 시도 연결 끊기) |
| `PermissionsChanged` | 서비스가 신호 수집을 다시 띄운다(Car 연결부터). 코어는 할 일이 없다 |

## 5. 급해 (UI 감사 #7·#8·#9, 서비스 감사 D7)

`UrgentSession`(순수): LOCATING → (위치 있음) SEARCHING(정차 후보 표를 아직 못 읽었으면) → READY / NO_RESULT. 위치가 8초 안에 없으면
NO_LOCATION(+ "현재 위치를 아직 모르겠어요"), 그 뒤라도 위치가 생기면 찾는다. 결과는 떠 있는 동안 10초마다 다시 찾는다.

엔진에 급해를 알리는 방법(D7): 첫 결과 때 다음 틱 엔진 입력에 `VoiceIntent.UrgentRestroom`을 넣고, 급해가 끝날 때까지 가까운 화장실을
엔진 후보에 더한다. 그러면 엔진이 필요를 '말한 것'으로 기록하고(게이지·학습), 30초 조용히 하고, 1등 장소로 `URGENT_RESULT` 질문을 낸다.
화면 목록도 같은 후보로 `engine.urgent()`(상태를 바꾸지 않는 순위)를 매겨서 목록 1등과 음성 질문이 같은 곳이다. 그 질문을 수락하면
엔진이 '들르기로 한 곳'을 안다(예전에는 앱이 질문을 따로 만들어 엔진이 몰랐다). 들르기가 성공하면 같은 번호의 IDLE을 올려 화면이 닫히고,
고른 곳이 경로 후보가 아니면 엔진이 그곳을 추천하는 동안 후보에 계속 넣는다(15초 유예, UI #9 '고른 곳이 60초 뒤 사라짐').
음성 "화장실 급해"는 코어가 음수 번호로 요청을 연다(화면 번호와 겹치지 않게). 화면은 요청하지 않은 진행 중 요청을 연다(ui.md 3.4).

결정: prompt.md는 급해 결과를 앱 질문(AppPrompt)으로 띄우도록 기계를 만들었지만, 엔진에 급해를 알리면 엔진도 같은 질문을 내므로 둘이
겹친다(앱 카드가 끝난 뒤 엔진 카드가 또 묻는다). 엔진이 질문의 진실이라는 원칙대로 엔진 질문 하나만 쓴다. AppPrompt 경로는 기계에 남아 있다.

## 6. 세계 전환 (world.md 6장의 체크리스트)

`WorldChange.kind`
- `WORLD`(시연 켜기·끄기·바꾸기): 질문 초기화(`Reset` → 음성 멈춤·카드 내림·결과 내림, 실행 중 들르기는 그대로), 쌓인 의도·만료 타이머 버림,
  정차 감지기를 그 세계의 정차로(`onWorldChanged`: 시연을 끌 때는 시연을 켠 실제 시각을 넘겨 시연 시간만큼의 가짜 실제 정차를 막는다),
  급해·고정·계획 끝, 경로 보정 스위치·내비 위치 보충·후보 입력을 새 세계 정책으로, `candidates.reset()`(옛 세계 후보가 새 엔진에 한 틱도 안 들어간다),
  화면의 시연 이름·탑승자·기록.
- `ENGINE`(새 여정, 기록 지우기): 질문 초기화.
- `saveMemory`가 있으면 IO에서 저장 (시연을 켜기 직전의 실제 학습, 서비스 감사 D15).

전환은 명령 메시지 안에서 한 번에 일어나므로 틱 중간에 끼지 않는다(D6). `Demo(holiday)`/`Demo(null)`/`Demo(stop)`을 몰아 보내도 차례로 처리된다.

## 7. 시계

| 값 | 시계 | 이유 |
|---|---|---|
| 엔진 입력 시각, 정차, 여정 | 세계의 코치 시각 (시연이면 가상) | 엔진은 입력 시각으로만 판단 |
| 카드 수명, 만료 타이머, 급해 기다림·재검색, 환기 문·되돌리기, 학습 저장 간격, 고정 유예, 계획 갱신 | 경과 시간 | 시연 전환을 넘어 이어진다. 예전에는 코치 시각이라 시연 뒤 몇 시간씩 막혔다(D8) |
| 시연을 켠 때 (`demoSinceWall`) | 실제 시각 | 실제 세계의 시각과 비교한다 |
| 시연 엔진의 질문 수명 | 코치 시각 × 배속 (`CoachWorld.Demo.engineConfig`) | 실제 45초 (prompt.md 2.5) |

## 8. 스레드

- 서비스: `Dispatchers.Default.limitedParallelism(1)` 하나에 코어(`run`)·`NaviTracker.run`·`CandidateProvider.run`을 띄운다. 셋이 같은 스레드라
  `Odometer`·스냅샷을 잠금 없이 나눠 본다. 다른 루프는 코어를 직접 부르지 않고 `CoachGates`(위치 보충 허락, 후보 입력)만 읽는다.
- 효과는 코어의 `supervisorScope`에서 돈다(같은 스레드). 실패는 로그만 남긴다. SDK 호출은 kit이 전용 스레드로 옮긴다.
- 파일: 읽기는 시작 때 `withContext(io)`. 학습 저장은 IO 효과. 정차 기록·여정 맥박은 앱 저장소가 앱의 쓰기 스레드(한 번에 하나, 맡긴 순서)로 넘긴다.
  학습 지우기만 코어 스레드에서 바로 한다(세대를 즉시 올려야 이미 꺼낸 저장이 지운 학습을 되살리지 않는다. 파일 하나 지우기).
- 신호 수집(`CarSignals`)은 출처마다 따로, 실패하면 2초부터 두 배씩 최대 1분 간격으로 다시(D13). `SignalHub`와 `PositionTracker`는 스레드 안전.

## 9. 시작과 끝

시작: IO에서 맥박·학습·기록 읽기 → `WorldSwitch.open`(여정 이어 가기/새로) → 기록 정리(`StopHistory.sanitize`) → 정차 중 재시작이면
감지기 복원(마지막 맥박 때까지 서 있었다) → 새 세계 정책 알림 → `serviceRunning = true`. 시작 전에 온 명령은 우편함에 남아 있다.

끝(`onDestroy`): 코치 스코프 취소(루프가 먼저 멈춘다) → 코치 스레드에서 `shutdown()`(맥박 저장, 바뀐 학습 저장, 2초 상한) → 만든 kit만 놓기 →
화면의 진행 중 상태 지우기(`CoachStore.resetTransient`: 질문·급해·결과·음성 표시, D11).

## 10. 시나리오 → 테스트

| 시나리오 | 테스트 (`CoachCoreTest`) |
|---|---|
| 귀성길: 옥산 2~5km 전 마지막 기회 → 음성 '응' → 길안내 없으면 옥산 좌표로 새 길안내, 결과 안내, 다시 묻지 않음 | `holidayDemoAsksOksanAsTheLastChanceAndYesStartsTheRouteThere` |
| 길안내 중이면 경유지 추가 | `withARouteGuidingNaviTheAcceptAddsOksanAsAWaypoint` |
| 7.2배속 시연에서 카드가 실제 8초 이상 (수명 배속) | `theLastChanceCardStaysForRealSecondsInTheSevenTimesFasterDemo` |
| 음성 끔: 묻지도 말하지도 않음, 카드는 보임 | `voiceOffMeansNoSpeechAtAllButTheCardStillShows` |
| 엔진이 더 앞선 질문을 내면 같은 틱에 카드 교체 | `theEnginesUrgentAnswerReplacesTheCardWithinOneTick` |
| 급해: LOCATING → READY, 엔진 질문, 10초마다 다시 | `urgentReportsLocatingThenResultsAndRefreshesWhileShown` |
| 찾는 중 닫기 → 늦은 결과·음성 없음 | `dismissingWhileLocatingDropsTheLateResultAndSaysNothing` |
| 위치 없음 → NO_LOCATION | `withoutALocationTheUrgentRequestEndsInNoLocation` |
| 급해 질문 수락 → 길안내, 화면 닫힘(IDLE), 고른 곳 추천 유지 | `acceptingTheUrgentCardRoutesThereAndClosesTheUrgentScreen` |
| 음성 "화장실 급해" → 코어가 연 요청 | `sayingImUrgentOpensAnUrgentRequestTheScreenCanFollow` |
| 오후 졸음 시연 → 실제 3분 안에 휴식 제안 | `fatigueDemoSuggestsARestWithinThreeRealMinutes` |
| 시연 전환 몰아 보내기 → 한 세계로 일관 | `rapidDemoSwitchesEndInOneConsistentWorld` |
| 시연 끄기 → 실제 엔진(피로 이어짐), 실제 위치 | `demoOffReturnsToTheRealEngineAndTheRealPosition` |
| 정차 시연 → 화면에만, 기록 파일 없음, 끄면 사라짐 | `aStopInTheStopDemoIsShownButNeverWrittenToHistory` |
| 실제 정차 → 기록·학습 저장, 시연 중 저장 없음 | `aRealStopIsRecordedAndItsLearningSavedButADemoSavesNothing` |
| 학습 저장 1분에 한 번까지 | `memoryIsSavedAtMostOnceAMinute` |
| 기록 지우기 → 되살아나지 않음 | `clearRecordsEmptiesHistoryAndLearningAndNothingResurrectsThem` |
| 시연 중 설정·탑승자 변경 → 시연 여정 유지, 설정에 안 씀 | `settingsAndOccupantChangesDuringADemoKeepTheDemoTrip` |
| 건강 유형은 동의가 있어야 | `healthTypesNeedConsentWhenSettingOccupants` |
| 정차 중 재시작 → 원래 시작부터 기록, 문 열림 유지 | `aRestartWhileParkedResumesTheParkAndRecordsItFromTheOriginalStart` |
| 자동 환기 10분 문(실제 시간), 90초 뒤 되돌리기 | `autoFreshAirIsGatedTenRealMinutesAndRevertedAfterNinetySeconds` |
| 음성·내비가 멈춰도 틱은 매초, 들르기는 감시가 끝냄 | `aHungVoiceAndAHungNaviNeverStallTheTick` |

`CoreRulesTest`: 환기 문·되돌리기, 급해 단계(위치·표 기다림, 재검색, 엔진 알림 한 번), P·저속 방위 null, 후보 더하기.
변이 검사: 수명 배속 제거, 엔진에 급해 안 알림, 저장 간격 제거, 시연 기록 저장, 닫기 무시, 음성 끔 무시, 환기 문 제거 — 모두 잡혔다.

앱 JVM 테스트(노트북): `KitRulesTest`(모의 주행도 경로, 경유지 확인 P11, 기어 변환), `TripJsonTest`(맥박 왕복·깨진 파일).

## 11. 닫은 결함

| ID | 내용 | 어떻게 |
|---|---|---|
| 구조 D6·D7, 서비스 D10 | 틱과 시연 전환·이중 수락 경쟁 | 액터 (명령이 틱 사이에), 기계가 Idle에서 두 번째 수락을 버림 |
| 서비스 D7 | 급해가 엔진을 우회 | 5장 |
| UI #4 | 계획이 갱신·정리되지 않음 | 떠 있는 동안 다시 만듦 |
| UI #9 | 급해 결과 한 번만, 고른 곳 사라짐 | 10초 재검색, 고정 |
| UI #12 | 음성 끔인데 말함, 누르면 또 듣기 | `speak`가 설정 확인, Listen 토글, THINKING 표시 |
| UI #19 | `ahead` 20초마다 | 매초 투영 |
| 서비스 D11 | 끝날 때 수집 전에 kit 해제, 만들지 않은 kit 생성, 화면 상태 남음 | 9장 |
| 서비스 D13 | 연결 실패면 재시작까지 신호 없음 | `CarSignals.retrying`, 음성 연결 재시도, 권한 받으면 다시 연결 |
| 서비스 D14 | stops.json 덮어쓰기·지우기 경쟁 | `StopHistoryStore`: AtomicFile + 쓰기 스레드 차례 + `clear()` |
| 서비스 D16 | 외기 되돌리지 않음, INFO가 마이크를 엶 | `FreshAirGate`, 기계의 speakOnly |
| 서비스 P11 | 경유지 조회 실패면 거짓 성공 | `NaviKit.waypointAdded` (요청 좌표 확인) |
| 구조 D12·B8 | 모의 주행이면 길안내 확인 실패 | `NaviKit.hasRoute` (startRoute·isGuiding·cancelRoute) |
| 서비스 P13 | TTS/STT 이벤트에 요청 번호 없음 | `VoiceAgent`가 stop 뒤 0.4초 늦게 다음 말을 시작 (완화) |
| prompt.md 2.5 | 시연 질문이 실제 6초 만에 사라짐 | `EngineFactory`가 세계의 `engineConfig`를 받음 |
| 정리 | 중복 코드 | TBT 종류 규칙은 `StopNames.kindFor` 하나. `CongestionMath`, `DemoDirector`, 옛 `RouteOracle.refine` 삭제 |

## 12. 에뮬레이터 확인 (2026-11-12, Pleos Connect Emulator 2.1.0, debug, Virtual Console)

| 시나리오 | 결과 (logcat `Shimpyo`) |
|---|---|
| 귀성길 `demo-smoke.sh holiday --split` | `prompt shown: LAST_CHANCE 옥산휴게소 at 4920m` → 22.1초 뒤 `prompt hidden` (500m 앞에서 엔진이 거둠, 200m/s). 예전에는 약 6초 |
| 오후 졸음 `fatigue --split` | 시작 66초 뒤 `prompt shown: REST_SUGGESTION 천안호두휴게소 at 17583m`. 첫 CHECK_IN 카드는 실제 45초 뒤 내려감 |
| 도심 급해 `urgent --split` + 급해 버튼 | `urgent …: 5 results`, `prompt shown: URGENT_RESULT 역삼문화공원 제1호 공영주차장 at 261m`(목록 1등과 같음), TTS 묻는 중 '말하는 중', 닫기 → `prompt hidden` |
| 정차 `stop --split` + P·문 열림 → D 40 | `stop recorded: 11 min restroom=true persisted=false`, `stops.json` 없음(`run-as --user 10`), 시연 진입 때 `trip.json` 저장 |
| 끄기 `off` | `demo started: off`, 실제 경로 후보(`candidates READY ROUTE n=9`), 실제 위치(판교 geo fix), `stops.json` 없음 |
| 실제 길안내 | `navi mode: UNKNOWN -> ROUTE_GUIDING`, `navi epoch 1: guidance started`, TBT 후보 `candidates READY ROUTE n=10 until=144490`. 급해 결과 수락 → `goTo result: WAYPOINT_ADDED SK네트웍스(주)스타팅광동주유소`, 화면 '경유지로 넣었어요' + 다음 추천이 그 주유소(엔진이 수락을 앎). 경유지 변경 이벤트 → epoch, `navi tbt dropped: route changed while asking` |
| 마이크 토글 | 급해 질문을 말하는 중 마이크를 누르면 멈춘다 ('말하는 중' → 마이크) |
| 급해 결과 세 번 연타 | `goTo` 1번, `addWaypoint` 1번 |

기기에서 찾아 고친 것 (모두 테스트 먼저):
- 길안내 중 목적지 응답 하나가 남은 거리 0을 주면 재탐색이 두 번 잡혔다 → 0은 못 읽은 것 (`NaviTrackerTest.aZeroRemainingDistanceWhileStillGuidingIsAGlitchNotTwoReroutes`)
- 이미 경유지인 곳을 다시 넣으면 목록이 늘지 않아 FAILED → 다시 보내지 않음 (`KitRulesTest.aPlaceAlreadyInTheWaypointsIsRecognisedSoItIsNotSentAgain`)
- `trafficCongestionTime`은 분 단위 늘어난 시간 → 지나는 시간은 거리 ÷ 평균 속도 (`NaviCongestionTest`, CLAUDE.md)
- 서 있는 내비에 TBT를 5초마다 물었다(1분 20번) → 경로 조회만큼 (`NaviTrackerTest.aFrozenNaviIsAskedForTbtAtTheRoutePaceAfterTheFastWindow`)

## 13. 남은 것

- 급해 목록에서 엔진 질문과 다른 곳을 고르면 엔진은 그 선택을 모른다(질문 수락으로만 알린다). 고른 곳 고정도 엔진이 추천하는 동안만 유지된다.
- 들르기 결과가 감시(140초)보다 늦게 오면 결과는 버려지지만 내비에는 들어갔을 수 있다 (prompt.md의 남은 위험과 같다).
- LLM 해석은 에뮬레이터에서 응답하지 않는다(첫 호출 40초 뒤 스스로 꺼짐). 그동안 마이크 표시가 THINKING이다.
