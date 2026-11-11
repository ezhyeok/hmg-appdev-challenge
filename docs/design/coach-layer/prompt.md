# 질문·들르기·정차 기록 (coach 계층, prompt 담당)

코드: `rest-engine/coach/src/main/kotlin/dev/shimpyo/coach/`
`PromptMachine.kt`, `PromptRules.kt`, `GoTo.kt`(GoToPlanner, GoToRunner), `StopDetector.kt`(StopDetector, ParkState), `StopRecorder.kt`.
테스트: `rest-engine/coach/src/test/kotlin/dev/shimpyo/coach/` 의 `PromptMachineTest`, `PromptRulesTest`, `GoToPlannerTest`,
`StopDetectorTest`, `StopRecorderTest`, 가짜는 `fakes/PromptFakes.kt`.
실행: `./gradlew -q -p rest-engine :coach:test --tests 'dev.shimpyo.coach.PromptMachineTest'` (서버에서 돈다, 안드로이드 불필요).

## 1. 맡는 것과 바꾼 것

| 부품 | 하는 일 | 예전 위치 (CoachService) |
|---|---|---|
| `PromptMachine` | 화면 질문 카드와 음성 질문의 상태 기계. 대답·만료·철회·들르기 결과를 받아 효과를 낸다 | `react`, `ask`, `answer`, `onIntent`의 대답 부분, `urgent`의 질문 부분, `goTo`의 결과 처리 |
| `PromptRules` | 질문 종류별 규칙 (정차 중 거두기, 말로만 알리기) | 같은 파일 |
| `GoToPlanner` | 들르기를 경유지 추가 / 새 길안내 / 경로 위 안내 / 장소 검색 중 무엇으로 할지, 다시 시도할지 (순수) | `goTo` |
| `GoToRunner` | 위 판단대로 내비를 부르는 효과 실행기 (가짜 내비로 테스트) | `goTo` |
| `StopDetector` | 기어·문·시간으로 정차 판정 (순수) | `trackStops` |
| `StopRecorder` | 정차 기록 저장(세계가 허락할 때만)과 화면용 목록 | `trackStops`, `nearestKnownPlace` |

예전 코드의 문제는 상태가 네 곳(`activePrompt`, `lastPromptId`, `CoachStore.prompt`, 질문 코루틴)에 흩어져 있고, 앱이 질문을
'자기 타이머'로 관리해서 엔진이 질문을 바꾸거나 거둔 것을 따르지 못한 것이다. 이제 질문 상태는 `PromptMachine` 하나가 들고,
`CoachCore`(액터, 한 스레드)만 부른다. 기계는 입출력을 하지 않고 효과 목록을 돌려준다. 코어가 효과를 실행하고 결과를 다시 사건으로 넣는다.

## 2. PromptMachine

### 2.1 상태

```
PromptState = Idle
            | Asking(card)        카드가 떠 있고 음성 작업(묻기·듣기)이 돌고 있다
            | AwaitingTap(card)   카드가 떠 있고 음성은 끝났다 (못 알아들음, 음성 끔). 버튼·마이크로 대답을 받는다

PromptCard(prompt, key, origin = ENGINE | APP, reasons, shownAtMs, deadlineMs)   ← 시각은 ElapsedClock(실제 시간)
```

상태 밖에 기계가 들고 있는 값:

| 값 | 뜻 |
|---|---|
| `generation` | Reset마다 1씩 오른다. 카드 key = `엔진id#세대` |
| `closed` | 이 세대에 닫은 질문 id (대답·만료·정차로 거둠·밀려난 앱 질문). 엔진이 계속 내도 다시 띄우지 않는다 |
| `lastParked` | 마지막 틱의 정차 여부. 모르면 주행 (주행 중 화면을 여는 쪽으로 틀리지 않게) |
| `inFlight`, `queued` | 실행 중인 들르기 하나, 대기 하나 (새 요청이 대기를 바꾼다) |
| `retryLink` | 실패한 질문 수락 (장소 → 엔진 질문). 화면의 다시 시도가 같은 곳이면 그 질문의 수락으로 알린다 |

### 2.2 사건과 효과

| 사건 | 누가 보내나 |
|---|---|
| `EngineTick(offered, next, parked)` | 코어, 1초 틱마다 (`CoachOutput.prompt`, `.next`, `.parked`) |
| `AppPrompt(prompt, reasons)` | 코어의 급해 처리 (URGENT_RESULT). reasons = `RankedStop.why` |
| `Heard(promptId, answer, other)` | Ask 음성 작업이 끝날 때 반드시 (못 들었으면 UNKNOWN). 마이크 버튼으로 들은 말이 지금 질문의 대답일 때도 |
| `Tapped(promptId, accept)` | `UiCommand.Answer` |
| `Expired(promptId)` | ScheduleExpiry 타이머 |
| `GoTo(stop)` | `UiCommand.GoTo` (급해 목록, 계획 화면, 실패 뒤 다시 시도) |
| `GoToFinished(requestId, outcome, stop?)` | StartGoTo를 실행한 GoToRunner의 결과 |
| `Reset` | 세계(시연·실제) 교체, 엔진 교체, 기록 지우기 |

| 효과 | 코어가 하는 일 |
|---|---|
| `Show(prompt?, reasons)` | `CoachUiState.prompt`, `.promptReasons` |
| `Ask(key, speech, listen)` | 음성 작업 launch: 말하고 listen이면 듣는다 → 끝나면 `Heard(key, …)` |
| `CancelVoice` | 그 음성 작업 취소 + `VoicePort.stop()` |
| `Speak(text)` | 대답을 받지 않는 말 (안내, 들르기 결과) |
| `ScheduleExpiry(key, atElapsedMs)` | 그 실제 시각에 `Expired(key)` (같은 key면 앞의 타이머를 바꾼다) |
| `Report(engineId, accept)` | `engine.onPromptResult(engineId, accept, now)` |
| `StartGoTo(requestId, stop, parked)` | 지금 위치 기준 후보로 바꿔(`candidates`에서 같은 id) `GoToRunner.run` launch → `GoToFinished` |
| `FreshAir(announce)` | 외기 전환 (`CabinPort.freshAir`), 성공하면 알림. 마지막 환기 시각(실제 시간)은 코어가 든다 |
| `Forward(intent)` | 마이크로 들은 요청처럼 처리 (급해 → 급해 처리, 그 밖은 다음 틱 엔진 입력) |
| `PublishGoToResult(result?)` | `CoachUiState.goToResult` |

### 2.3 전이표 (상태 다이어그램)

E = 엔진 카드, A = 앱 카드. "닫음" = closed에 넣어 다시 띄우지 않음. "내림" = 카드를 내리지만 닫지 않음(엔진이 다시 내면 띄운다).

| 지금 상태 | 사건 | 조건 | 다음 상태 | 효과 |
|---|---|---|---|---|
| 아무 상태 | EngineTick | 카드 수명이 지났다 | (아래 계속) | 닫음: [CancelVoice] Show(null) |
| 아무 상태 | EngineTick | 카드가 주행 질문이고 P | (아래 계속) | 닫음: [CancelVoice] Show(null) |
| 아무 상태 | EngineTick(INFO) | 처음 보는 id | 그대로 | Speak, Report(id, true) |
| Idle | EngineTick(p) | p가 닫히지 않음, P에서 주행 질문이 아님 | Asking (음성 끔: AwaitingTap) | Show(p), ScheduleExpiry(+45초), Ask |
| Idle | EngineTick(null / 닫힌 p / P의 주행 질문) | | Idle | 없음 |
| E 카드 | EngineTick(같은 id) | | 그대로 | 없음 |
| E 카드 | EngineTick(다른 p) | | Asking(p) | 내림 [CancelVoice] → Show(p), ScheduleExpiry, Ask (같은 틱) |
| E 카드 | EngineTick(null) | | Idle | 내림 [CancelVoice] Show(null) |
| A 카드 | EngineTick(아무것) | INFO 아님 | 그대로 | 없음 (엔진 질문은 A 카드가 끝난 뒤 틱에) |
| 아무 상태 | AppPrompt(INFO) | | 그대로 | Speak |
| 아무 상태 | AppPrompt(u) | 같은 앱 카드 아님, 닫히지 않음 | Asking(A u) | 지금 카드 내림(앱 카드면 닫음) → Show(u), ScheduleExpiry(+90초), Ask |
| 카드 | Heard(key, YES) / Tapped(key, true) | | Idle | 닫음 → 수락 처리 (2.4) |
| 카드 | Heard(key, NO) / Tapped(key, false) | | Idle | 닫음, E면 Report(id, false) |
| 카드 | Heard(key, UNKNOWN) | | AwaitingTap | 수명을 max(수명, 지금+10초)로, 상한 처음 수명+10초. 바뀌면 ScheduleExpiry |
| 아무 상태 | Heard(다른 key, …) / Tapped(다른 key) | | 그대로 | 없음 |
| 아무 상태 | Heard(…, other) | other가 Accept/Decline이 아님 | (위대로) | 끝에 Forward(other) |
| 카드 | Expired(key) | 지금 ≥ 수명 | Idle | 닫음 [CancelVoice] Show(null) |
| 아무 상태 | Expired(그 밖) | | 그대로 | 없음 |
| 아무 상태 | GoTo(stop) | | 그대로 | 들르기 요청 (2.4) |
| 아무 상태 | GoToFinished(실행 중인 id) | | 그대로 또는 Idle | 2.4 |
| 아무 상태 | Reset | | Idle | CancelVoice, Show(null), PublishGoToResult(null); 세대+1, closed·대기·retryLink 비움 |

[CancelVoice]는 Asking일 때만 낸다(AwaitingTap이면 멈출 음성이 없다). Heard가 오면 음성 작업이 끝난 것이라 먼저 AwaitingTap으로 본다.
한 사건에서 카드를 내리고 새 카드를 띄우면 Show(null) 없이 Show(새 질문)만 낸다.

### 2.4 수락과 들르기

| 질문 | 수락하면 | 엔진 보고 |
|---|---|---|
| 장소가 있는 질문 (마지막 기회, 휴식 권유, 급해 결과) | 들르기 요청 | 내비 명령이 성공했을 때 (WAYPOINT_ADDED, ROUTE_STARTED, ON_ROUTE, SEARCH_OPENED) |
| 환기 | FreshAir(announce = 말할 수 있으면) | 바로 |
| 장소 없는 질문 (상태 확인, 장소 없는 휴식 권유) | 없음 (엔진이 다음 틱에 반응) | 바로 |
| 앱 질문 | 들르기 요청 | 없음 (엔진이 모르는 질문) |

들르기 요청:
- 실행 중이 없으면 `PublishGoToResult(null)`(지난 실패 카드를 내림)와 `StartGoTo`. 실행 중이면 줄을 선다. 대기는 하나, 새 요청이 바꾼다.
- 같은 장소가 실행 중이거나 대기 중이면 다시 보내지 않는다(두 번 누름). 새 요청이 질문 수락이면 그 질문을 이어 붙인다.
- 결과: `PublishGoToResult(GoToResult(stop, outcome, 코치 시각))`, 결과 문장 Speak, 성공이고 같은 세대의 질문이면 Report(true).
  실패면 retryLink를 남긴다: 화면의 다시 시도 버튼(같은 곳 GoTo)이 성공하면 그 질문의 수락으로 알린다.
- 성공했는데 같은 장소를 묻는 카드가 떠 있으면(계획 화면에서 같은 곳을 눌렀다) 그 카드는 답한 것으로 닫고, 엔진 카드면 수락을 알린다.
- 다음 대기 요청이 있으면 시작한다.

왜 보고를 미루나: 예전에는 수락하자마자 엔진에 알려서, 내비가 경유지를 못 넣어도 엔진은 '들르기로 했다'고 보고 그곳을 지나칠 때까지
다른 화장실을 권하지 않았고 화면은 "경유지로 넣었어요"를 보였다(UI 감사 #3). 엔진은 `recent` 8개 안의 id면 늦은 보고도 받는다
(엔진 감사 P4).

### 2.5 수명: 왜 45초(엔진)·90초(앱)·10초(버튼 창)인가

- 카드 수명은 **실제 시간(ElapsedClock)**으로만 잰다. 예전에는 엔진의 만료 시각(코치 시각)에서 지금(코치 시각)을 빼 실제 ms로
  `delay`했다. 시연은 코치 시각이 7.2~10배로 흐르므로 45초가 4.5~6초가 됐다(구조 리뷰 §2.4, B5).
- 엔진 카드 45초 = 엔진의 질문 수명(`CoachConfig.promptTtlS`)과 같다. 실제 주행에서는 두 수명이 함께 끝나 어긋나지 않는다.
  TTS(6~8초) + 듣기(6초) 뒤에도 30초 넘게 누를 수 있고, 시속 100km로 1.25km라 마지막 기회(진입로 2~5km 전)는 진입로 전에 끝난다.
- 앱 카드 90초 = 예전 급해 결과와 같다. 급해 화면에서 목록을 보고 고를 시간을 준다.
- 음성이 대답 없이 늦게 끝나도 적어도 10초는 누를 수 있게 늘리되, 처음 수명 + 10초를 넘지 않는다. 카드가 언제까지나 남지 않는다.
- 엔진 카드는 엔진이 먼저 거둘 수 있다(대상 500m 안, 지나침, 엔진 수명). 그때는 바로 내린다(2.3). 기계의 수명은 엔진이 멈췄거나
  틱 출력이 없을 때의 상한이다. 만료는 Expired 타이머와 틱 둘 다 본다(타이머를 잃어도 1초 안에 거둔다).
- **시연 주의**: 엔진 수명은 코치 시각으로 흐르므로, 시연 엔진은 `promptTtlS`를 배속만큼 늘려 만들어야 한다(예: holiday 7.2배면
  45 × 7.2 = 324초 코치 시각 = 실제 45초). 그러지 않으면 엔진이 6초 만에 질문을 거두고 기계는 엔진을 따라 카드를 내린다. CoachWorld 담당.

### 2.6 왜 key에 세대를 붙이나

엔진을 새로 만들면(시연 켜기·끄기, 기록 지우기) 엔진 질문 id가 p1부터 다시 시작했다(엔진 감사 P3, 엔진 쪽에서도 고친다). 옛 카드의
"들르기"가 늦게 오면 새 엔진의 p1에 수락이 들어갔다(서비스 감사 D1). 기계는 화면에 `p1#0`처럼 세대를 붙인 key를 올리고, 대답은
key가 지금 카드와 같을 때만 받는다. Reset 뒤에는 세대가 달라 옛 대답이 절대 새 질문에 닿지 않는다(엔진 id 규칙과 무관). 엔진에 알릴
때는 원래 id를 쓴다. 코어는 IntentParser의 `activePromptId`로 `current.key`를 넘긴다.

### 2.7 시나리오 표 (상황 → 동작 → 테스트)

| 상황 | 동작 | 테스트 (PromptMachineTest) |
|---|---|---|
| 휴식 권유를 묻는 중 엔진이 마지막 기회로 밀어냄 | 같은 틱에 음성 멈추고 새 카드 (D1) | transitionTable "휴식 권유 × 엔진이 마지막 기회로 밀어냄", "묻는 중 × 엔진이 더 급한 질문으로 바꿈" |
| 대상이 500m 안·지나침 (엔진이 null) | 카드·음성 내림, 늦은 수락은 무시, 내비 명령 없음 (D2) | `targetPassedMeansTheCardIsGoneAndAcceptingDoesNothing` |
| 대답한 질문을 엔진이 한 틱 더 냄 | 다시 띄우지 않음 | `anAnsweredPromptIsNotAskedAgainWhileTheEngineCatchesUp` |
| 수명이 지난 질문을 엔진이 계속 냄 | 다시 띄우지 않음 | `anExpiredEngineCardIsNotShownAgainEvenIfStillOffered` |
| P로 세움 | 주행 질문 거둠(다시 안 띄움), 정차 중 처음 온 질문은 출발 뒤 띄움 | `aParkedWithdrawalIsFinalButAnUnshownOfferWaitsForDriving`, 표 "P로 세움" 행 |
| "얼마나 남았어?"의 대답(INFO) | 말만, 카드 그대로, 한 번만 (D16) | `anInfoTickDoesNotHideTheCardAndTheCardIsNotReasked`, `anInfoIsSpokenOnce` |
| 엔진이 잠깐 다른 질문을 냈다가 원래 질문을 다시 냄 | 원래 질문을 다시 띄움 | `aDisplacedEngineCardReturnsIfTheEngineStillOffersIt` |
| 엔진 질문 위에 급해 결과, 끝난 뒤 | 급해가 앞서고, 끝나면 엔진 질문 다시 | `urgentResultOverAnEnginePromptAndBack`, `theUrgentCardIsNotDisplacedByEnginePrompts` |
| 시연 전환 뒤 옛 카드 탭 (새 엔진이 p1 재사용) | 무시, 새 p1은 띄움 | `aStaleTapAfterResetDoesNothingEvenIfTheNewEngineReusesTheId`, `resetForgetsAnsweredIdsSoARebuiltEngineIsAsked` |
| 세계 전환 중 들르기 실행 중 | 결과는 알리되 새 엔진에 보고 안 함, 대기는 버림 | `resetUnlinksTheGoToInFlightAndDropsTheQueuedOne` |
| 음성 '응' + 버튼 동시 | 내비 명령 한 번 (P7, D7) | `aDoubleAcceptRunsOneNaviCommand` |
| 수락 → 내비 성공 | 그때 보고 | `acceptIsReportedOnlyAfterTheNaviSucceeded` |
| 수락 → 내비 실패 → 다시 시도 성공 | FAILED 결과 + 다시 시도가 그 질문의 수락 | `aFailedGoToShowsRetryAndTheRetryStillReportsTheAccept` |
| 좌표 없는 경로 위 휴게소 수락 | "경로 위에 있어요, 2.6km 앞 진입로로…" (지금 거리) | `liveStopFromTheCoreIsUsedForTheResultAndSpeech` |
| 다른 요청의 늦은 결과 | 무시 | `aResultForAnotherRequestIsIgnored` |
| 들르기 여러 번 | 한 번에 하나, 마지막 대기만, 같은 곳 무시 | `oneNaviCommandAtATimeAndTheLatestQueuedWins` |
| 실행 중인 곳을 묻는 질문 수락 | 명령 하나, 그 질문으로 보고 | `acceptingAPlaceAlreadyOnItsWayReportsThatPrompt` |
| 카드가 묻는 곳을 계획 화면에서 눌러 성공 | 카드 닫고 수락 보고, 실패면 카드 유지 | `aCardForThePlaceJustAddedIsAnswered` |
| 정차 중 들르기 | 마지막 틱의 정차 여부로 실행 | `goToUsesTheLastKnownParkedState` |
| 시연 배속으로 코치 시각이 뜀 | 수명은 실제 시간 | `theCardLifetimeIgnoresTheCoachClock` |
| 음성이 늦게 끝남 | 10초 버튼 창, 상한 55초 | `aSlowVoiceLeavesATapWindowButTheLifetimeStaysBounded` |
| "아니 화장실 급해" | 거절 + 급해 전달 | 표 "'아니 화장실 급해'" 행 |
| "배고파"(대답 아님) | 버튼 대기 + 전달 | 표 "'배고파'" 행 |
| 질문이 내려간 뒤 온 "화장실 급해" | 대답은 버리고 요청은 전달 | 표 "Idle × 늦은 음성 대답" 행 |
| 음성 끔 | Ask·Speak 없음, 환기 알림 없음 (UI #12) | `voiceOffNeverSpeaksOrListens`, 표 "음성 끔" 행 |
| STT 없음 | 말만 하고 마이크는 열지 않음 | 표 "STT 없음" 행 |
| 질문 도중 추천이 다음 곳으로 | 카드 이유는 띄울 때 것 (UI #6) | `currentCardExposesWhatTheCoreNeeds` |

## 3. GoToPlanner, GoToRunner

| 장소 | 정차 | 명령 직전에 읽은 내비 모드 | 할 일 | 결과 |
|---|---|---|---|---|
| 좌표 있음 | 상관없음 | ROUTE_GUIDING, SIMULATION_GUIDING | 경유지 추가 | WAYPOINT_ADDED |
| 좌표 있음 | 상관없음 | SAFE_GUIDING, NOT_WORKING | 새 길안내 | ROUTE_STARTED |
| 좌표 있음 | 상관없음 | 읽기 실패, UNKNOWN | 보내지 않음 (MODE_UNKNOWN) | 다시 시도 → FAILED |
| `tbt:` (좌표 없음) | 주행 | 경로 있음, 읽기 실패 | 명령 없음 | ON_ROUTE |
| `tbt:` | 주행 | 경로 없음 | 없음 (NO_ROUTE) | FAILED |
| `tbt:` | 정차 | (읽지 않음) | 장소 검색 열기 | 열었을 때만 SEARCH_OPENED |

- **모드는 명령 직전에 다시 읽는다.** 코어의 내비 스냅숏은 최대 15초 묵어서, 그 사이 사용자가 길안내를 시작했으면 새 길안내가
  사용자의 목적지를 덮어썼다(구조 리뷰 D12, 서비스 감사 D9). GoToRunner는 스냅숏을 받지도 않는다.
- **모의 주행도 경로가 있다** (`NaviMode.hasRoute`, B8).
- **다시 시도는 한 번, 보내지 못한 것만**: 예외(명령을 못 보냄)와 모드 조회 실패만 3초(NaviKit 명령 간격) 뒤 다시 한다. 보냈는데
  확인 시간 안에 반영되지 않은 명령(false)은 늦게 들어갔을 수 있어 다시 보내지 않는다(경유지가 두 번 들어간다). 화면의 다시 시도에 맡긴다.
- **장소 검색은 정차 중에만** 연다(주행 중 화면 조작 유도 금지). 예전에는 검색을 못 열어도 "찾아 두었어요"라고 했다.
- 실행 전체 상한 130초(조회 15초 + 길안내 확인 45초를 두 번). 넘으면 FAILED. 취소(세계 전환으로 코어가 취소)는 결과로 바꾸지 않는다.
- 남은 위험: 보냈지만 확인이 안 된 경유지가 실제로는 늦게 들어갔으면, 사용자가 다시 시도를 누를 때 두 번 들어갈 수 있다.
  NaviPort에 경유지 조회가 없어 확인하지 못한다.

테스트: `GoToPlannerTest` (순수 판단 4개 + 가짜 내비로 실행 13개).

## 4. StopDetector, StopRecorder, ParkState

### 4.1 판정 규칙 (엔진 StopTracker와 같게)

| 상황 | 예전 (trackStops) | 지금 | 테스트 (StopDetectorTest) |
|---|---|---|---|
| P 3분 이상 + 문 → 출발 | 화장실 정차 | 같음 | `parkedThreeMinutesWithDoorIsARestroomStop` |
| 정확히 3분 / 2분 59초 | 기록 / 안 함 | 같음 | `exactlyThreeMinutesCountsAndJustUnderDoesNot` |
| P 중 기어 UNKNOWN (Car 서비스 재연결) | 정차가 사라지고 다시 센 정차에 문 열림이 빠짐 | 차속 3km/h 초과가 아니면 정차 유지 | `unknownGearWhileParkedKeepsTheStop`, `unknownGearWithSpeedMeansMoving`, `unknownGearSlowOrSpeedUnknownKeepsThePreviousState` |
| P→D 틱에 함께 온 문 열림 | 버림 (엔진은 셈) | 셈 | `doorOnTheDepartureTickCounts` |
| 정차 중 시계가 거꾸로 감 | — | 기록 안 함 | `clockGoingBackwardsWhileParkedRecordsNothing` |
| 실제 10:00에 세운 채 시연(14:00) 켜고 출발 | 10:00→14:00 정차 기록 (서비스 감사 D3) | `rebase`: 새 시계로 다시 셈 | `parkedAcrossADemoStartDoesNotInventAFourHourStop` |
| 정차 중 앱 재시작 | 정차를 잃음 (D12) | `resume(ParkState)` | `restartWhileParkedResumesTheStop`, `resumeKeepsTheEarlierStartIfATickCameFirst`, `resumeFromTheFutureIsIgnored` |

예전 동작은 먼저 그대로 고정한 테스트(`currentBehavior_*`)로 커밋한 뒤 다음 커밋에서 뒤집었다 (git log로 바뀐 점이 보인다).
기록의 '화장실' 표시와 엔진이 배운 화장실 간격이 같은 규칙에서 나오게 하려는 것이다(구조 리뷰 §2.3e). 나중에 엔진이 정차 사건을
출력하게 되면(B9) StopDetector를 지우고 그 사건으로 기록한다.

### 4.2 ParkState (world 담당과의 약속)

```kotlin
data class ParkState(val sinceMs: Long, val doorAtMs: Long?)   // 코치 시각. 정차 중일 때만 있다
```

- `StopRecorder.parkState`(= `StopDetector.parkState`)를 TripSession이 trip heartbeat에 함께 저장한다. **실제 세계일 때만** 저장한다
  (시연 값은 가상 시각이다).
- 재시작하면 TripSession이 이어 가기로 정했을 때(heartbeat가 충분히 최근일 때) `StopRecorder.resume(state, now)`를 부른다.
  미래 시각은 버린다. 다음 틱이 P가 아니면 그 틱에 정차가 끝난다(앱이 꺼진 사이 출발했으면 정차 끝이 늦게 잡힌다).
- `doorAtMs`가 있고 `now - sinceMs ≥ 3분`이면 정차 중 이미 화장실 정차다. 엔진과 같은 시각은 `max(doorAtMs, sinceMs + 3분)`.
  TripSession이 재시작 때 `lastRestroomAt`을 정하는 데 쓸 수 있다.

### 4.3 기록 규칙 (StopRecorder)

- 좌표는 남기지 않는다(`lat = lon = null`). 서 있던 곳 300m 안의 정차 가능한 시설(휴게소·졸음쉼터·공중화장실·주유소·공영주차장)
  이름만 남긴다. 출발 틱 위치보다 정차 중 마지막 위치를 쓴다.
- `persist`(= `CoachWorld.persistsHistory`)가 true일 때만 HistoryPort에 쓴다. 아니면 화면 목록에만 넣고, 세계가 바뀌면
  (`onWorldChanged`) 버린다. 시연 정차가 실제 기록에 미래 시각으로 들어가던 것(D3, UI #15)을 막는다.
- 저장이 실패해도 화면에는 보인다(`persisted = false`).
- 기록 지우기는 HistoryPort.clear로 한다(파일 이름을 서비스가 알지 않게, D14). 지금 서 있는 정차는 차의 상태라 지우지 않는다.
- 미래 시각 기록 정리(로드 때 `end > now` 버리기)와 `lastRestroomAt` 갱신은 TripSession(world 담당)이 한다. `Recorded.log`의
  `restroom`, `end`를 쓰면 된다.

테스트: `StopRecorderTest` 10개.

## 5. 스레드

모두 `CoachCore`의 한 디스패처(액터)에서만 부른다. `PromptMachine`, `GoToPlanner`, `StopDetector`는 순수하고 잠금이 없다.
두 사건이 동시에 들어올 수 없으므로 "확인한 뒤 행동" 경쟁(예전 `answer()`의 이중 수락)이 구조적으로 없다.
`GoToRunner.run`과 음성 작업은 코어가 launch하는 효과이고, 결과는 사건으로 우편함에 돌아온다(핸들러 안에서 기다리지 않는다).
`StopRecorder`의 HistoryPort.append는 정차 한 건에 한 번 작은 파일을 원자적으로 쓴다.

## 6. 코어(CoachCore)가 지켜야 할 것

1. Ask 음성 작업은 끝나면 **반드시** `Heard(key, …)`를 보낸다 (못 들음·STT 없음·실패 = UNKNOWN). 취소됐으면 보내지 않아도 된다.
   대답 판정: `answer = parser.yesNo(text)`, `other = parser.parse(text, occupants, activePromptId = null)`.
2. 마이크 버튼으로 들은 말이 `VoiceIntent.Accept/Decline(id)`이면 `Tapped(id, accept)`(또는 `Heard`)로 넣는다. 엔진 update의
   intent로 넣지 않는다. IntentParser의 `activePromptId`에는 `machine.current?.key`를 넘긴다.
3. `promptStop`은 `machine.current?.prompt?.stop`의 id로 지금 후보에서 찾는다. `promptReasons`는 Show 효과의 reasons.
4. `StartGoTo`를 실행할 때 지금 위치 기준 후보(같은 id)로 바꿔 `GoToRunner.run`에 넘기고, `GoToFinished`에 그 후보를 함께 넣는다.
   성공(FAILED 외)이면 내비 즉시 재조회(`NaviTracker.requestRefresh`)와 급해 목록 닫기도 한다.
5. `Expired` 타이머는 취소하지 않아도 된다(다른 key는 무시한다). `ScheduleExpiry`가 같은 key로 다시 오면 시각을 바꾼다.
6. 세계 전환·엔진 교체·기록 지우기 때 `Reset`을 넣는다. 그 뒤 실행 중인 GoToRunner는 둬도 된다(결과는 보고 없이 알린다).
7. 급해 결과는 `AppPrompt`로, id는 요청마다 달라야 한다(예: `urgent-<requestId>`).
8. `Speak`는 지금 음성 뒤에 이어 말해도 된다(VoiceAgent의 Mutex). `CancelVoice`는 지금 말·듣기를 멈춘다.

## 7. 열린 문제와 다른 담당에게

- **CoachWorld (world)**: 시연 엔진은 `CoachConfig.promptTtlS`를 배속만큼 늘려 만든다(2.5). 그러지 않으면 시연 질문이 6초 만에 사라진다.
  `ParkState`는 실제 세계에서만 heartbeat에 저장한다(4.2).
- **엔진 (engine)**: INFO를 Report(true)로 알리면 `PromptPolicy.onResult`가 `noteInteraction`을 다시 불러 30초 조용한 때가 그 시각부터
  다시 시작된다(INFO를 낼 때 이미 한 번 시작했으므로 실제로는 몇 초 늘어날 뿐이다). 사용자와 대화했으니 맞는 동작이다.
- 앱 질문(급해 결과) 수락은 엔진에 알리지 않는다(엔진이 모르는 질문). 엔진이 급해를 알게 하는 것은 코어의 급해 처리(서비스 감사 D7) 몫이다.
- GoToOutcome에 '실행 중'이 없다. 실행 중에는 카드가 내려가고 결과만 나중에 뜬다. 필요하면 UI 계약에 PENDING을 더하는 것을 UI 담당과 정한다.
