# 질문·들르기·정차 기록 (coach 계층, prompt 담당)

코드: `rest-engine/coach/src/main/kotlin/dev/shimpyo/coach/`
`PromptMachine.kt`, `PromptRules.kt`, `GoTo.kt`(GoToPlanner, GoToRunner), `StopDetector.kt`(StopDetector, ParkState), `StopRecorder.kt`.
테스트: `rest-engine/coach/src/test/kotlin/dev/shimpyo/coach/` 의 `PromptMachineTest`(40), `PromptRulesTest`, `GoToPlannerTest`,
`StopDetectorTest`(41), `StopRecorderTest`(13), `StopRulesCrossCheckTest`(11), 가짜는 `fakes/PromptFakes.kt`.
실행: `./gradlew -q -p rest-engine :coach:test --tests 'dev.shimpyo.coach.PromptMachineTest'` (서버에서 돈다, 안드로이드 불필요).

2026-11-12 닫힘 검증 뒤 바뀐 것: 앱이 만든 질문(AppPrompt) 경로를 지웠다(급해 결과도 엔진의 질문이다), 급해 목록에서 고른 곳이 급해
질문의 대답이 된다, 다시 시도 연결이 결과 카드 닫기를 넘는다, 안내를 말할 수 없으면 화면 알림으로 낸다, 들르기 결과의 at과 수명을
기계가 든다, 되돌린 정차의 판정을 기어를 알 때까지 미룬다. 코어 쪽 이음매는 `core.md`.

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
            | AwaitingTap(card)   카드가 떠 있고 음성은 끝났다 (못 알아들음, 음성 끔, 안내가 끼어듦). 버튼·마이크로 대답을 받는다

PromptCard(prompt, key, reasons, shownAtMs, deadlineMs)   ← 시각은 ElapsedClock(실제 시간)
```

질문은 모두 엔진의 것이다. 급해 결과(`URGENT_RESULT`)도 코어가 엔진에 급해를 알리면 엔진이 내는 질문이다(core.md 5장).
예전에는 앱이 만든 질문(AppPrompt, 90초 수명, 엔진 질문보다 앞섬)의 갈래가 기계에 있었는데 한 번도 쓰이지 않아 지웠다.

상태 밖에 기계가 들고 있는 값:

| 값 | 뜻 |
|---|---|
| `generation` | Reset마다 1씩 오른다. 들르기의 보고 대상(옛 엔진에 보고하지 않기)을 가른다 |
| `showSeq` | 카드를 띄울 때마다 1씩 오른다. 카드 key = `질문id#순번` (2.6) |
| `closed` | 이 세대에 닫은 질문 id (대답·만료·정차로 거둠·급해 목록의 선택으로 끝남). 엔진이 계속 내도 다시 띄우지 않는다 |
| `everShown` | 이 세대에 띄운 적 있는 질문 id. P에서 주행 질문을 닫을지 정한다 |
| `lastParked` | 마지막 틱의 정차 여부. 모르면 주행 (주행 중 화면을 여는 쪽으로 틀리지 않게) |
| `inFlight`, `queued` | 실행 중인 들르기 하나(시작 시각 포함), 대기 하나 (새 요청이 대기를 바꾼다). 요청마다 보고할 질문 목록과 세대 |
| `retryLink` | 실패한 질문 수락 (장소 → 질문들, 실패 시각). 2분 안에 같은 곳 GoTo가 오면 그 질문들의 수락으로 알린다. 결과 카드를 닫아도 남는다 |
| `lastResultAt`, `resultShownAtMs` | 마지막으로 올린 들르기 결과의 at(결과마다 다르게)과 올린 실제 시각(10분 뒤 거둔다) |

### 2.2 사건과 효과

| 사건 | 누가 보내나 |
|---|---|
| `EngineTick(offered, next, parked)` | 코어, 1초 틱마다 (`CoachOutput.prompt`, `.next`, `.parked`) |
| `Heard(promptId, answer, other)` | 카드의 Ask 음성 작업이 끝날 때 한 번 (못 들었으면 UNKNOWN). 음성 작업이 끝났다는 뜻이다. `answer`·`other`는 그 말을 대답 하나로 읽은 것이다 (`IntentParser.replyTo`) |
| `Tapped(promptId, accept)` | `UiCommand.Answer`, 마이크 버튼으로 들은 `VoiceIntent.Accept/Decline(id)` |
| `Expired(promptId)` | ScheduleExpiry 타이머 |
| `GoTo(stop, urgentPick)` | `UiCommand.GoTo` (급해 목록, 계획 화면, 실패 뒤 다시 시도). `urgentPick`: 지금 급해 요청의 목록에서 고른 곳이다 |
| `GoToFinished(requestId, outcome, stop?)` | StartGoTo를 실행한 GoToRunner의 결과 |
| `DismissGoToResult` | `UiCommand.DismissGoToResult` (결과 카드 닫기) |
| `Reset` | 세계(시연·실제) 교체, 엔진 교체, 기록 지우기 |

| 효과 | 코어가 하는 일 |
|---|---|
| `Show(prompt?, reasons)` | `CoachUiState.prompt`, `.promptReasons` |
| `Ask(key, speech, listen)` | 음성 작업 launch: 말하고 listen이면 듣는다 → 끝나면 `Heard(key, …)` |
| `CancelVoice` | 그 음성 작업 취소 + `VoicePort.stop()` |
| `Speak(text)` | 대답을 받지 않는 말 (안내, 들르기 결과) |
| `Notice(text)` | 말 대신 화면에 잠깐 보일 알림 (`CoachUiState.notice`): 말할 수 없을 때의 안내 |
| `ScheduleExpiry(key, atElapsedMs)` | 그 실제 시각에 `Expired(key)` (같은 key면 앞의 타이머를 바꾼다) |
| `Report(engineId, accept)` | `engine.onPromptResult(engineId, accept, now)` |
| `StartGoTo(requestId, stop, parked)` | 지금 위치 기준 후보로 바꿔(`candidates`에서 같은 id) `GoToRunner.run` launch → `GoToFinished`. `goToPending`을 올린다. 세계 전환에도 취소하지 않는다 |
| `FreshAir(announce)` | 외기 전환 (`CabinPort.freshAir`), 성공하면 알림. 마지막 환기 시각(실제 시간)은 코어가 든다 |
| `Forward(intent)` | 마이크로 들은 요청처럼 처리 (급해 → 급해 흐름, 그 밖은 다음 틱 엔진 입력) |
| `PublishGoToResult(result?)` | `CoachUiState.goToResult`. 결과가 있으면 `goToPending`을 내리고 코어가 뒷일을 한다 (core.md 6장) |

### 2.3 전이표 (상태 다이어그램)

"닫음" = closed에 넣어 다시 띄우지 않음. "내림" = 카드를 내리지만 닫지 않음(엔진이 다시 내면 띄운다).

| 지금 상태 | 사건 | 조건 | 다음 상태 | 효과 |
|---|---|---|---|---|
| 아무 상태 | EngineTick | 들르기 결과가 130+10초 동안 안 왔다 | (아래 계속) | 그 들르기를 FAILED로 끝냄 (2.4) |
| 아무 상태 | EngineTick | 들르기 결과를 올린 지 10분 | (아래 계속) | PublishGoToResult(null) |
| 아무 상태 | EngineTick | 카드 수명이 지났다 | (아래 계속) | 닫음: [CancelVoice] Show(null) |
| 아무 상태 | EngineTick | 카드가 주행 질문이고 P | (아래 계속) | 닫음: [CancelVoice] Show(null) |
| 아무 상태 | EngineTick(INFO) | 처음 보는 id | Asking이었으면 AwaitingTap, 아니면 그대로 | [CancelVoice] Speak (말할 수 없으면 Notice), Report(id, true) |
| Idle | EngineTick(p) | p가 닫히지 않음, P에서 주행 질문이 아님 | Asking (음성 끔: AwaitingTap) | Show(p), ScheduleExpiry(+45초), Ask |
| Idle | EngineTick(null / 닫힌 p) | | Idle | 없음 |
| Idle | EngineTick(P의 주행 질문 p) | p를 띄운 적 있음 | Idle | 없음 (p를 닫음) |
| Idle | EngineTick(P의 주행 질문 p) | 처음 봄 | Idle | 없음 (출발하면 띄운다) |
| 카드 | EngineTick(같은 id) | | 그대로 | 없음 |
| 카드 | EngineTick(다른 p) | | Asking(p) | 내림 [CancelVoice] → Show(p), ScheduleExpiry, Ask (같은 틱) |
| 카드 | EngineTick(null) | | Idle | 내림 [CancelVoice] Show(null) |
| 카드 | Heard(key, YES) / Tapped(key, true) | | Idle | 닫음 → 수락 처리 (2.4) |
| 카드 | Heard(key, NO) / Tapped(key, false) | | Idle | 닫음, Report(id, false) |
| 카드 | Heard(key, UNKNOWN) | | AwaitingTap | 수명을 max(수명, 지금+10초)로, 상한 처음 수명+10초. 바뀌면 ScheduleExpiry |
| 아무 상태 | Heard(다른 key, …) / Tapped(다른 key) | | 그대로 | 없음 |
| 아무 상태 | Heard(…, other) | other가 Accept/Decline이 아님 | (위대로) | 끝에 Forward(other) |
| 카드 | Expired(key) | 지금 ≥ 수명 | Idle | 닫음 [CancelVoice] Show(null) |
| 아무 상태 | Expired(그 밖) | | 그대로 | 없음 |
| 아무 상태 | GoTo(stop) | | 그대로 | 들르기 요청 (2.4) |
| 급해 결과 카드 | GoTo(stop, urgentPick) | 카드가 같은 곳을 묻는다 | Idle | 닫음 [CancelVoice] Show(null) → 들르기 요청 (성공하면 그 질문의 수락으로 보고) |
| 급해 결과 카드 | GoTo(stop, urgentPick) | 카드가 다른 곳을 묻는다 | Idle | 닫음 [CancelVoice] Show(null) (보고 없음) → 들르기 요청 |
| 다른 종류의 카드 | GoTo(stop, urgentPick) | | 그대로 | 들르기 요청 |
| 아무 상태 | GoToFinished(실행 중인 id) | | 그대로 또는 Idle | 2.4 |
| 아무 상태 | DismissGoToResult | | 그대로 | PublishGoToResult(null). retryLink는 그대로 |
| 아무 상태 | Reset | | Idle | CancelVoice, Show(null), PublishGoToResult(null); 세대+1, closed·everShown·대기·retryLink 비움 |

[CancelVoice]는 Asking일 때만 낸다(AwaitingTap이면 멈출 음성이 없다). Heard가 오면 음성 작업이 끝난 것이라 먼저 AwaitingTap으로 본다.
한 사건에서 카드를 내리고 새 카드를 띄우면 Show(null) 없이 Show(새 질문)만 낸다.

안내(INFO)가 Asking 중에 오면 묻던 음성을 멈추고 카드는 버튼 대기로 둔다. 운전자가 방금 물은 것의 대답이 카드 질문보다 지금 더 맞고,
다음 틱에 엔진이 카드 질문을 버려 카드를 내릴 때 멈출 음성이 없어 그 대답을 끊지 않는다 (엔진은 지금 사용자 요청에 답하면서
떠 있던 질문을 버린다, 엔진 감사 P1. `PromptEngineScenarioTest.aSpokenRequestDuringTheCardIsAnsweredWithoutBeingCutOff`).
말할 수 없으면(음성 안내 끔, TTS 없음) 안내는 `Notice`로 화면에 낸다: 음성 안내를 꺼도 마이크로 물을 수 있는데 예전에는 답이
소리로도 화면으로도 나가지 않았다 (리뷰 R-F4). 들르기 결과는 결과 카드가 보이므로 알림을 따로 내지 않는다.

### 2.4 수락과 들르기

| 질문 | 수락하면 | 엔진 보고 |
|---|---|---|
| 장소가 있는 질문 (마지막 기회, 휴식 권유, 급해 결과) | 들르기 요청 | 내비 명령이 성공했을 때 (WAYPOINT_ADDED, ROUTE_STARTED, ON_ROUTE, SEARCH_OPENED) |
| 환기 | FreshAir(announce = 말할 수 있으면) | 바로 |
| 장소 없는 질문 (상태 확인, 장소 없는 휴식 권유) | 없음 (엔진이 다음 틱에 반응) | 바로 |

들르기 요청:
- 실행 중이 없으면 `PublishGoToResult(null)`(지난 실패 카드를 내림)와 `StartGoTo`. 실행 중이면 줄을 선다. 대기는 하나, 새 요청이 바꾼다.
- 같은 장소가 실행 중이거나 대기 중이면 다시 보내지 않는다(두 번 누름). 새 요청이 질문 수락이면 그 질문을 보고 목록에 더한다
  (같은 곳을 두 질문에서 수락하면 둘 다 알린다).
- **급해 목록에서 고른 곳** (`GoTo.urgentPick`, UI 닫힘 검증 9a): 그 선택이 떠 있는 급해 질문의 대답이다. 같은 곳을 묻던 질문이면 수락이고
  (성공하면 그 질문으로 보고, 실패하면 다시 시도에 이어진다), 다른 곳을 묻던 질문이면 보고 없이 끝난다. 화면은 2순위를 고르면
  `GoTo(고른 곳)` 다음에 급해 질문의 거절도 보내는데, 그 거절은 닿을 카드가 없어 버려진다. 예전에는 엔진이 '거절'만 알았다.
- 결과: `PublishGoToResult(GoToResult(stop, outcome, at))` → (성공이고 같은 장소를 묻는 카드가 있으면 그 카드 닫기) → 결과 문장 Speak
  → 같은 세대 질문들의 Report(true). 카드를 말하기 전에 닫는 것은 CancelVoice가 방금 시작한 결과 안내를 끊지 않게 하려는 것이다.
  `at`은 코치 시각이되 앞 결과보다 늘 크게 한다: 화면이 at으로 새 결과를 가리므로 같은 순간에 끝난 두 들르기나, 시연을 꺼서 코치 시각이
  뒤로 간 뒤의 결과도 겹치지 않는다. 결과는 올린 지 10분(`GO_TO_RESULT_TTL_MS`) 뒤 틱이 거둔다.
- 같은 장소를 묻는 카드: 계획 화면에서 같은 곳을 눌러 성공했으면 카드는 답한 것이다. 닫고 수락을 알린다. Reset 전에 보낸
  명령이면 새 세계의 카드는 건드리지 않는다.
- 실패면 retryLink를 남긴다: 2분(`RETRY_LINK_MS`) 안에 화면의 다시 시도(같은 곳 GoTo)가 성공하면 그 질문의 수락으로 알린다.
  **결과 카드를 닫아도 끊지 않는다** (서비스 닫힘 검증 D9): 화면의 [다시 시도]는 `DismissGoToResult` 다음에 `GoTo`를 보내는데, 예전에는
  닫기가 연결을 끊어서 경유지는 들어가도 엔진은 그 질문을 무시당한 것으로 세고 들르기로 한 곳을 몰랐다. 2분 뒤 계획 화면에서 같은 곳을
  누른 것은 그 질문의 다시 시도가 아니다.
- 다음 대기 요청은 지금 결과를 지우지 않고 시작한다(실패 카드가 보여야 "다시 눌러 주세요"가 맞다).
- **감시**: 결과가 `GO_TO_WATCHDOG_MS`(140초 = GoToRunner 상한 130초 + 10초) 안에 안 오면 틱이 그 들르기를 FAILED로 끝내고 대기를
  시작한다. 실행이 취소됐거나, 내비 SDK 스레드가 막혀 시간 초과가 듣지 않거나, Error가 났을 때 뒤의 모든 들르기가 영영 줄만 서던
  것을 막는다. 늦게 온 결과는 버린다(내비에 실제로는 들어갔을 수 있다: 남은 위험). 화면의 '넣는 중'(`goToPending`)도 여기서 끝난다.

왜 보고를 미루나: 예전에는 수락하자마자 엔진에 알려서, 내비가 경유지를 못 넣어도 엔진은 '들르기로 했다'고 보고 그곳을 지나칠 때까지
다른 화장실을 권하지 않았고 화면은 "경유지로 넣었어요"를 보였다(UI 감사 #3). 엔진은 `recent` 8개 안의 id면 늦은 보고도 받는다
(엔진 감사 P4).

### 2.5 수명: 왜 45초·10초(버튼 창)인가

- 카드 수명은 **실제 시간(ElapsedClock)**으로만 잰다. 예전에는 엔진의 만료 시각(코치 시각)에서 지금(코치 시각)을 빼 실제 ms로
  `delay`했다. 시연은 코치 시각이 7.2~10배로 흐르므로 45초가 4.5~6초가 됐다(구조 리뷰 §2.4, B5).
- 카드 45초 = 엔진의 질문 수명(`CoachConfig.promptTtlS`)과 같다. 실제 주행에서는 두 수명이 함께 끝나 어긋나지 않는다.
  TTS(6~8초) + 듣기(6초) 뒤에도 30초 넘게 누를 수 있고, 시속 100km로 1.25km라 마지막 기회(진입로 2~5km 전)는 진입로 전에 끝난다.
- 음성이 대답 없이 늦게 끝나도 적어도 10초는 누를 수 있게 늘리되, 처음 수명 + 10초를 넘지 않는다. 카드가 언제까지나 남지 않는다.
  실제 주행에서는 엔진 수명(45초)이 먼저 끝나 이 연장이 쓰이지 않을 수 있다. 연장이 뜻을 갖는 것은 엔진 수명을 카드 수명보다 길게 둔
  세계다(시연 엔진을 55초 × 배속으로 두면 연장을 쓴다).
- 엔진이 먼저 거둘 수 있다(대상 500m 안, 지나침, 엔진 수명). 그때는 바로 내린다(2.3). 기계의 수명은 엔진이 멈췄거나
  틱 출력이 없을 때의 상한이다. 만료는 Expired 타이머와 틱 둘 다 본다(타이머를 잃어도 1초 안에 거둔다).
- **시연 주의**: 엔진 수명은 코치 시각으로 흐르므로, 시연 엔진은 `promptTtlS`를 배속만큼 늘려 만든다(holiday 7.2배면
  45 × 7.2 = 324초 코치 시각 = 실제 45초, `CoachWorld.Demo.engineConfig`). 그러지 않으면 엔진이 6초 만에 질문을 거두고 기계는 엔진을 따라 카드를 내린다.

### 2.6 왜 카드마다 새 key인가

엔진을 새로 만들면(시연 켜기·끄기, 기록 지우기) 엔진 질문 id가 p1부터 다시 시작했다(엔진 감사 P3, 엔진 쪽에서도 고친다). 옛 카드의
"들르기"가 늦게 오면 새 엔진의 p1에 수락이 들어갔다(서비스 감사 D1). 또 같은 질문을 다시 띄우면(급해 카드가 끝난 뒤) 취소된 첫 음성
작업이 늦게 보낸 대답이 새 카드에 닿아 상태가 어긋날 수 있다. 그래서 기계는 카드를 띄울 때마다 `p1#3`처럼 순번을 붙인 새 key를
화면과 음성 작업에 주고, 대답은 key가 지금 카드와 같을 때만 받는다. 엔진 id 규칙과 상관없이 옛 카드·옛 음성 작업의 대답은 절대 지금
카드에 닿지 않는다. 엔진에 알릴 때는 원래 id를 쓴다. 코어는 IntentParser의 `activePromptId`로 `current.key`를 넘긴다.

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
| 엔진 질문 위에 엔진의 급해 결과, 그 대답 뒤 | 급해 결과가 카드를 바꾸고, 대답하면 엔진이 다시 내는 원래 질문을 띄움 | `theEnginesUrgentResultDisplacesTheCardAndTheCardReturnsAfterTheAnswer` |
| 급해 목록에서 고름 (카드와 같은 곳 / 다른 곳) | 카드를 닫는다. 같은 곳이면 성공 뒤 그 질문의 수락으로 보고, 다른 곳이면 보고 없음. 뒤따른 거절은 버려짐 (UI-9a) | `aPickFromTheUrgentListAnswersTheUrgentCard` |
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
| 안내가 묻는 중에 끼어듦, 다음 틱 카드 철회 | 안내를 끊지 않음 | `anInfoAnswerIsNotCutOffWhenTheCardGoesAway` |
| 다른 카드에 밀린 주행 질문, 그 사이 P | 출발해도 다시 안 띄움 | `aDrivingPromptDisplacedByAnotherCardDoesNotReturnAfterParking` |
| 취소된 음성 작업의 늦은 대답, 같은 질문을 다시 띄운 뒤 | 새 카드에 안 닿음 | `aStaleHeardFromACancelledAskDoesNotTouchTheReshownCard` |
| 들르기 결과가 끝내 안 옴 (취소·막힘) | 140초 뒤 FAILED, 대기 시작 | `aGoToThatNeverFinishesIsFailedByTheWatchdog`, `theWatchdogAlsoFreesAGoToCancelledByAWorldSwitch` |
| 실패 직후 대기 들르기 시작 | 실패 카드가 보인다 | `aFailedResultStaysVisibleWhenTheQueuedGoToStarts` |
| 같은 곳을 두 질문에서 수락 | 둘 다 보고 | `twoAcceptsForTheSamePlaceAreBothReported` |
| 다시 시도 연결 | 2분. 결과 카드를 닫아도 남는다 (SV-D9) | `theRetryLinkIsShortLived`, `aRetryRightAfterClosingTheFailedCardStillReportsTheAccept` |
| 음성 끔 + 안내(INFO) | 화면 알림 한 번, 카드 그대로 (R-F4) | `withVoiceOffAnInfoIsShownOnceAndTheCardStays` |
| 들르기 결과 둘이 같은 코치 시각, 시연을 꺼서 코치 시각이 뒤로 | 결과의 at은 늘 앞 결과보다 크다 | `resultsCarryRisingTimestampsEvenWhenTheCoachClockStandsStillOrGoesBack` |
| 결과를 아무도 닫지 않음 | 10분 뒤 거둔다 (새 결과는 다시 10분) | `anOldResultIsWithdrawnAfterTenMinutesLikeADismiss`, `aNewResultRestartsTheTenMinutesAndAClearedResultHasNoneLeft` |
| Reset 전 들르기가 새 세계의 같은 곳 카드와 만남 | 새 카드 그대로 | `aGoToFromBeforeResetDoesNotAnswerTheNewEnginesCard` |

실제 엔진과 함께 (`PromptEngineScenarioTest`, 귀성길 시연 + 엔진에 보고를 돌려줌):

| 상황 | 확인하는 것 |
|---|---|
| 마지막 기회 수락 → 내비가 넣는 동안 → 성공 | 엔진이 같은 질문을 계속 내도 다시 안 띄움, 성공 뒤 보고, 그 뒤 옥산을 다시 묻지 않음 |
| 거절 | 다시 묻지 않음 |
| 대답 없이 달림 | 옥산 500m 앞에서 엔진과 함께 카드가 내려감, 늦은 수락은 내비 명령 없음 |
| 카드가 떠 있는 동안 "얼마나 남았어?" | 대답을 말하고 끊지 않음 |
| 시연 엔진 기본 수명 | 실제 10초 안에 카드가 사라진다 → 시연 엔진은 수명을 배속만큼 늘려야 한다 (늘리면 버튼을 누를 수 있다) |

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
- 실행 전체 상한 130초(조회 15초 + 길안내 확인 45초를 두 번). 넘으면 FAILED. 우리 취소(서비스 종료)는 결과로 바꾸지 않고 그대로
  던진다. 포트 안의 자체 시간 초과(TimeoutCancellationException)는 실패로 본다(다시 던지면 결과가 영영 안 나온다).
  내비 SDK 스레드가 막혀 시간 초과가 듣지 않아도 PromptMachine의 감시(140초)가 들르기를 끝낸다.
- 남은 위험: 보냈지만 확인이 안 된 경유지가 실제로는 늦게 들어갔으면, 사용자가 다시 시도를 누를 때 두 번 들어갈 수 있다.
  NaviPort에 경유지 조회가 없어 확인하지 못한다.

테스트: `GoToPlannerTest` (순수 판단 4개 + 가짜 내비로 실행 14개).

## 4. StopDetector, StopRecorder, ParkState

### 4.1 판정 규칙 (엔진 StopTracker와 같게)

출발 차속이 30km/h를 넘으면 그 틱에 정차가 확정된다. 느리게 떠나면 다시 대기 창이 끝날 때(3분이 지나거나, 그 정차를 시작한 뒤
움직인 거리가 300m를 넘을 때) 확정된다(끝 시각은 P에서 나온 때).

| 상황 | 예전 (trackStops) | 지금 | 테스트 (StopDetectorTest) |
|---|---|---|---|
| P 3분 이상 + 문 → 출발 | 화장실 정차 | 같음 | `parkedThreeMinutesWithDoorIsARestroomStop` |
| 정확히 3분 / 2분 59초 | 기록 / 안 함 | 같음 | `exactlyThreeMinutesCountsAndJustUnderDoesNot` |
| P인데 차속이 들어옴 (잡음) | — | P를 믿는다 (엔진 감사 S8) | `parkTrustsTheGearOverSpeed` |
| P 중 기어 UNKNOWN (Car 서비스 재연결) | 정차가 사라지고 다시 센 정차에 문 열림이 빠짐 | 차속 3km/h 초과가 아니면 정차 유지 | `unknownGearWhileParkedKeepsTheStop`, `unknownGearWithSpeedMeansMoving`, `unknownGearSlowOrSpeedUnknownKeepsThePreviousState` |
| P→D 틱에 함께 온 문 열림 | 버림 (엔진은 셈) | 셈 | `doorOnTheDepartureTickCounts` |
| 휴게소 안에서 옮겨 대기 (3분 안, 30km/h 이하, 움직인 거리 합쳐 300m 이하, 후진 포함) | 정차 둘 (14분 + 5분 → 휴식이 안 됨) | 정차 하나, 옮긴 시간도 선 시간 (엔진 감사 S7) | `reparkingWithinThreeMinutesIsTheSameStop`, `reversingIntoAnotherSpotIsTheSameStop`, `movingLessThanThreeHundredMetresIsStillTheSameStop`, `aDoorDuringTheGapIsNotAStopDoor` |
| 정체 속에서 P로 섰다가 기어가기를 되풀이 (P 2분 · 시속 20km 2분 × 5) | — (앞 판: 18분 정차 하나, 문이 열렸으면 화장실 정차) | 300m를 넘는 틱에 정차를 확정한다. 거리는 다시 대기를 거쳐도 이어 더하고 새 정차에서 0부터다 (엔진 닫힘 검증 E4) | `creepingMoreThanThreeHundredMetresEndsTheStopWithoutWaitingForTheWindow`, `theDistanceAddsUpOverSeveralMovesAndStartsAgainWithANewStop` |
| 다시 대기 사이에 차속을 모름 | — | 시속 30km로 갔다고 본다 (36초면 300m): 같은 정차라고 확인할 수 있을 때만 이어 센다 | `anUnknownSpeedCountsAsThirtyKilometresAnHour` |
| 다시 P가 된 틱에 차속이 함께 옴 | 그 차속이 30km/h를 넘으면 새 정차 | 잡음으로 본다 (P를 믿는다, 엔진과 같다) | `speedOnTheTickTheCarParksAgainIsNoise` |
| 천천히 떠남 | 바로 기록 | 3분 창이 끝나거나 30km/h·300m를 넘을 때 기록 | `aSlowDepartureIsRecordedWhenTheReparkWindowCloses`, `speedAboveThirtyEndsTheStopAtOnce`, `parkingAgainAfterTheWindowIsANewStop` |
| 정차 중 시계가 뒤로 맞춰짐 (NTP) | 끝 - 시작이 짧아져 정차가 사라짐 (엔진은 셈) | 선 시간 = 틱 사이 시간의 합, 거꾸로 간 걸음은 0 (엔진 EngineClock과 같다) | `aClockJumpBackWhileParkedDoesNotLoseTheStop`, `clockGoingBackwardsCountsNoParkedTimeForThatStep` |

예전 동작은 먼저 그대로 고정한 테스트(`currentBehavior_*`)로 커밋한 뒤 다음 커밋에서 뒤집었다 (git log로 바뀐 점이 보인다).
기록의 '화장실' 표시와 엔진이 배운 화장실 간격이 같은 규칙에서 나오게 하려는 것이다(구조 리뷰 §2.3e). 엔진 규칙이 바뀌면
(이번에 S7·S8이 들어왔다) 여기도 같이 바꾼다: 어긋나면 4.4의 교차 확인 테스트가 먼저 깨진다. 나중에 엔진이 정차 사건을
출력하게 되면(B9) StopDetector를 지우고 그 사건으로 기록한다.

### 4.2 ParkState와 restore (world 담당과의 약속)

```kotlin
// TripSession.kt (world 담당). 코치 시각. 정차 중일 때만 있다
data class ParkState(val since: Long, val doorSeen: Boolean)
```

world 담당이 TripSession.kt에 먼저 정의했고 감지기도 이 타입을 쓴다(같은 이름을 두 번 정의하지 않는다).

| 때 | 코어가 부르는 것 | 뜻 |
|---|---|---|
| 매 틱 | `recorder.parkState` → `WorldSwitch.onTick(now, park)` | 맥박 저장, 휴식·화장실·새 여정 판단 (TripSession) |
| 앱 시작 (첫 틱 전) | `recorder.restore(session.restoredPark, now, lastSeenMs = 맥박 savedAt)` | 정차 중 재시작하면 정차를 이어 간다 (D12) |
| 세계 전환 (`WorldChange.Kind.WORLD`) | `recorder.onWorldChanged(now, change.restorePark, lastSeenMs)` | 시연 켜기: null → 시연 시계로 새로 센다. 끄기: 실제 여정의 정차, lastSeenMs = 시연을 켠 실제 시각 |

`restore`는 합치기가 아니라 바꾸기다. 되돌린 정차는 "마지막으로 본 때(`lastSeenMs`)에 끝났다, 차가 지금도 P가 아니라면"이고,
그 판정은 **기어를 아는 첫 틱**에 한다 (`StopDetector.restorePending`이 그동안 true다):

| 되돌린 뒤의 틱 | 판정 |
|---|---|
| 기어 P | 아직 서 있다. 보지 못한 사이도 선 시간으로 센다 |
| 기어 D·R·N, 또는 기어는 모르지만 차속 3km/h 초과 | 떠났다. `lastSeenMs`에 P에서 나온 정차다. 다시 대기 창은 그때부터 세고 보지 못한 사이는 차속을 모르는 것(시속 30km)으로 센다: 대개 이 틱에 확정되고(3분 이상이면 기록), 방금 본 정차에서 옮겨 대는 중이었으면 다시 서서 이어질 수 있다 |
| 기어 UNKNOWN, 움직이지 않음 | 미룬다. 선 시간을 더하지 않고, 문 열림은 이 정차의 것으로 적는다 |
| 10초(`RESTORE_WAIT_MS`)가 지나도 UNKNOWN | 떠난 것으로 본다 (보지 못한 시간을 선 것으로 세지 않는다) |

왜 미루나 (서비스 닫힘 검증 N2): 서비스가 켜진 직후의 첫 틱은 Car 연결보다 먼저 올 수 있다. 예전에는 첫 틱의 UNKNOWN을 '아직 서
있다'로 읽어서, 같은 상황(10분 서 있다가 앱이 꺼지고 30분 뒤 달리는 중에 다시 켜짐)이 기어 신호가 첫 틱보다 먼저 왔는지에 따라
10분 정차 / 40분 정차로 갈렸다. 판정을 기다리는 동안 코어는 여정 맥박·엔진 update·급해 진행을 하지 않는다(core.md 10장).
'떠났다'로 판정되면 `takeRestoredEnd()`가 끝 시각을 한 번 주고, 코어가 여정과 엔진도 같은 시각에 떠난 것으로 맞춘다
(`WorldSwitch.restoredParkEnded`, world.md 4.2). 예전에는 기록은 10분 정차인데 엔진은 40분 정차로 보아 게이지와 기록이 어긋났다(N3).

그래서
- 정차 중 꺼졌다가 출발한 뒤 다시 켜져도 정차 끝이 재시작 시각까지 늘지 않는다(`departedWhileTheAppWasDownEndsTheStopAtTheLastSeenTime`,
  `aRestoredStopWaitsForAKnownGearAndThenEndsAtTheLastSeenTimeIfTheCarHasLeft`).
- 아직 P면 꺼져 있던 시간도 선 것이다(`aRestoredStopSeenStillInParkCountsTheUnseenTimeToo`).
- 시연 중 Virtual Console로 D를 넣고 시연을 꺼도, 시연 시간만큼의 가짜 실제 정차가 실제 기록에 들어가지 않는다
  (`demoOffWithTheCarNoLongerParkedEndsTheRealStopWhenTheDemoStarted`). 코어가 시연을 켠 실제 시각을 들고 있다가 넘긴다.
- 시연을 켜도 실제 10:00→시연 14:00 같은 두 시계 차이로 만든 정차가 없다(`parkedAcrossADemoStartDoesNotInventAFourHourStop`).
- 문 열림 시각은 저장하지 않으므로 doorSeen이면 세운 때로 본다(TripSession과 같은 식: 화장실 = 세운 때 + 3분).
- 다시 대기를 기다리던 정차는 세계를 바꾸면 버린다(옛 세계의 것). 세계 전환 직전 3분 안에 천천히 떠난 실제 정차는 잃는다(받아들인 손실).

테스트: `demoOffRestoresTheRealStopWhileStillParked`, `restoringNothingWhileDrivingChangesNothing`, `restoreDropsAStopWaitingForRepark`,
`restartWhileParkedResumesTheStop`, `aDoorOpenedWhileUndecidedBelongsToTheRestoredStop`, `anUnknownGearWithSpeedMeansTheCarHasLeft`,
`withoutAGearForTenSecondsTheRestoredStopEndsAtTheLastSeenTime`, `restoringAgainStartsANewDecision`,
`withoutALastSeenTimeTheStopEndsAtTheRestore`, `restoreReplacesTheCurrentPark`, `restoreFromTheFutureClearsThePark`.
코어까지 이어서: `CoachCoreTripTest`의 재시작 다섯 가지 (늦게 온 기어, 아직 P, 판정 전, 끝내 기어 없음, 시연 끄기).

### 4.3 기록 규칙 (StopRecorder)

- 좌표는 남기지 않는다(`lat = lon = null`). 서 있던 곳 300m 안의 정차 가능한 시설(휴게소·졸음쉼터·공중화장실·주유소·공영주차장)
  이름만 남긴다. 출발 틱 위치보다 정차 중 마지막 위치를 쓴다. 다시 대기 창이 끝나 확정될 때 차가 이미 떠났어도 서 있던 곳 이름이다.
- `persist`(= `CoachWorld.persistsHistory`)가 true일 때만 HistoryPort에 쓴다. 아니면 화면 목록에만 넣고, 세계가 바뀌면
  (`onWorldChanged`) 버린다. 시연 정차가 실제 기록에 미래 시각으로 들어가던 것(D3, UI #15)을 막는다.
- 저장이 실패해도 화면에는 보인다(`persisted = false`). 화면 목록은 시간 순이다.
- 세계가 바뀌면 정차 중 마지막 위치도 잊는다(시연 위치의 휴게소 이름이 실제 정차에 붙지 않게).
- 기록 지우기는 HistoryPort.clear로 한다(파일 이름을 서비스가 알지 않게, D14). 지금 서 있는 정차는 차의 상태라 지우지 않는다.
- 미래 시각 기록 정리(`StopHistory.sanitize`)와 `lastRestroomAt` 갱신(`TripSession.onStop(log)`)은 world 담당이 한다.
  코어는 `Recorded.log`를 `TripSession.onStop`에 넘긴다.

- 정차 가능한 시설 종류는 `CoreRules.PARKABLE_KINDS` 한 곳에 있다 (급해 검색과 같은 목록).

테스트: `StopRecorderTest` 13개.

### 4.4 엔진 규칙과의 교차 확인 (`StopRulesCrossCheckTest`)

정차 판정이 두 벌이라(엔진 `StopTracker`가 시계를 돌리고, `StopDetector`가 기록을 남긴다) 규칙이 어긋나면 게이지는 다녀온 것으로
찼는데 기록에는 그 정차가 없다(구조 리뷰 §2.3e·B9, 서비스 닫힘 검증 N3). 엔진이 정차 사건을 내보내기 전까지 이 테스트가 둘을 묶는다:
같은 신호 순서를 실제 엔진과 감지기에 넣고, 엔진이 화장실 정차(화장실 시계가 돌아감)·휴식(휴식 리듬이 돌아감)으로 본 것과 감지기가
낸 정차(3분 + 문, 15분)가 같은 말을 하는지 본다. 엔진의 정차 규칙을 바꾸면 여기서 먼저 깨지고, 그때 감지기에 같은 규칙을 넣는다.

| 경우 | 테스트 |
|---|---|
| 정차가 없으면 엔진 시계가 돌아가지 않는다 (비교의 기준) | `theBaselineWithoutAnyStopShowsNoResetSoTheThresholdsMeanSomething` |
| 3분·15분 경계 (2분 59초, 3분, 14분 59초, 15분) | `plainStopsAndTheirBoundaries` |
| 문 열림의 때 (서기 전, 정차 중, 떠나는 틱, 떠난 뒤) | `doorTiming` |
| 기어가 흔들림, UNKNOWN (느림·빠름) | `flappingAndUnknownGear` |
| 휴게소 안에서 옮겨 대기 (3분 안·밖, 시속 30km 경계, 후진) | `reparking` |
| 정체 속에서 P와 기어가기를 되풀이 (시속 20km·15km) | `creepingBetweenParks` |
| 움직인 거리 300m (278m·333m, 150m + 200m, 새 정차는 0부터, 차속 모름 30초·40초, 화장실 정차) | `reparkingOnlyWithinThreeHundredMetresOfTheStop` |
| 다시 세운 틱의 차속 잡음 | `speedNoiseOnTheTickTheCarParksAgain` |
| 정차 중 시계가 뒤로 | `aClockSetBackWhileParked` |
| 재시작으로 되돌린 정차 (본 것 2분·5분, 이미 떠남·아직 P) | `aRestoredStopAfterARestart` |
| 옮겨 대는 사이에 재시작 (20초 전에는 P, 지금은 D, 곧 다시 섬 / 멀리 감 / 떠남) | `aRestoredStopThatIsMovedToAnotherSpotAroundTheRestart` |

2026-11-12에 이 테스트가 먼저 깨졌다: 엔진이 다시 대기에 '그 정차를 시작한 뒤 움직인 거리 300m 이하'를 더하자(엔진 닫힘 검증 E4)
`creepingBetweenParks`에서 엔진은 2분 정차 다섯, 감지기는 18분 화장실 정차 하나로 갈렸다. 감지기에 같은 규칙을 넣으면서 경우를
넓혔더니 두 곳이 더 어긋나 있었다: 다시 세운 틱의 차속(엔진은 보지 않는다)과, 옮겨 대는 사이에 재시작한 정차(감지기는 판정하는
틱에 정차를 끝냈고 엔진은 다시 대기로 이었다). 둘 다 엔진 쪽 규칙에 맞췄다.

## 5. 스레드

모두 `CoachCore`의 한 디스패처(액터)에서만 부른다. `PromptMachine`, `GoToPlanner`, `StopDetector`는 순수하고 잠금이 없다.
두 사건이 동시에 들어올 수 없으므로 "확인한 뒤 행동" 경쟁(예전 `answer()`의 이중 수락)이 구조적으로 없다.
`GoToRunner.run`과 음성 작업은 코어가 launch하는 효과이고, 결과는 사건으로 우편함에 돌아온다(핸들러 안에서 기다리지 않는다).
`StopRecorder`의 HistoryPort.append는 정차 한 건에 한 번 작은 파일을 원자적으로 쓴다.

## 6. 코어(CoachCore)가 지켜야 할 것

1. Ask 음성 작업은 끝나면 **반드시** `Heard(key, …)`를 보낸다 (못 들음·STT 없음·실패 = UNKNOWN). 취소됐으면 보내지 않아도 된다.
   대답은 하나로 읽는다: `parser.replyTo(text, occupants, 카드의 장소 이름)` → `Heard(key, reply.answer, reply.other)` (R-F3, voice.md 5장).
   `VoiceDesk`의 작업은 실패해도 결과를 한 번 돌려준다 (R-F11).
2. 마이크 버튼으로 들은 말이 `VoiceIntent.Accept/Decline(id)`이면 `Tapped(id, accept)`로 넣는다(`Heard`는 카드의 Ask 작업 결과만).
   엔진 update의 intent로 넣지 않는다. `parser.parseSpoken`의 `activePromptId`에는 `machine.current?.key`를 넘긴다.
3. `promptStop`은 `machine.current?.prompt?.stop`의 id로 지금 후보에서 찾는다. `promptReasons`는 Show 효과의 reasons.
4. `StartGoTo`를 실행할 때 지금 위치 기준 후보(같은 id)로 바꿔 `GoToRunner.run`에 넘기고, `GoToFinished`에 그 후보를 함께 넣는다.
   `goToPending`을 올리고, 그 들르기를 시작한 세계 세대와 급해 목록 선택 여부를 적어 둔다. 실행이 Error로 죽어도 `GoToFinished(FAILED)`를 보낸다.
5. `Expired` 타이머는 취소하지 않아도 된다(다른 key는 무시한다). `ScheduleExpiry`가 같은 key로 다시 오면 시각을 바꾼다.
6. 세계 전환·엔진 교체·기록 지우기, 그리고 엔진에 새 tripStart로 `setProfile`할 때(엔진의 주행 상태가 새로 시작한다) `Reset`을 넣는다.
   실행 중인 GoToRunner는 취소하지 않는다(결과는 보고 없이 알린다). 서비스 종료로 취소되면 결과를 안 보내도 된다(기계의 감시가 끝낸다).
7. 급해 결과도 엔진의 질문이다. 화면의 `GoTo`가 지금 급해 요청이 목록에 올렸던 곳이면(`UrgentFlow.lists`) `GoTo(stop, urgentPick = true)`로 넣는다.
8. `Speak`는 지금 음성 뒤에 이어 말해도 된다(VoiceAgent의 Mutex). 말해도 되는지(음성 안내 켬 + TTS)는 사건마다 `PromptContext.speak`로
   넘긴다. 안 되면 안내는 `Notice`로 온다: `CoachUiState.notice`에 올리고 약 6초 뒤 거둔다. `CancelVoice`는 지금 카드의 말·듣기를 멈춘다.
9. `UiCommand.DismissGoToResult`는 `PromptEvent.DismissGoToResult`로 넣는다(결과 카드만 내린다. 다시 시도 연결은 남는다).
10. `PublishGoToResult(결과)` 뒤에: `goToPending`을 내리고, 기계의 엔진 보고가 끝난 다음 경로가 바뀌었으면 내비 즉시 재조회, 급해 흐름에
    알림, 성공이면 `EngineAccepted.note`와 `TripSession.onAccepted` (core.md 6장). 다른 세계에서 시작한 들르기면 엔진과 여정에는 알리지 않는다.
11. 정차: 매 틱 `recorder.onTick(t, gear, speed, door, world.position(real), world.persistsHistory)` → `Recorded`가 오면 화면 기록 갱신과
    `TripSession.onStop(log)`. `recorder.parkState`를 `WorldSwitch.onTick`에 넘긴다. 시작 때 첫 틱 전에 `recorder.restore(restoredPark, now,
    맥박 savedAt)`, `WorldChange.Kind.WORLD`면 `recorder.onWorldChanged(now, restorePark, 시연을 켠 실제 시각(끌 때))`와 `machine.on(Reset)`.
    `recorder.restorePending`인 동안에는 여정·엔진을 돌리지 않고, `takeRestoredEnd()`가 시각을 주면 `WorldSwitch.restoredParkEnded`에 넘긴다 (4.2).

## 7. 열린 문제와 다른 담당에게

- **시연 엔진의 질문 수명**: 시연 엔진은 `CoachConfig.promptTtlS`를 배속만큼 늘려 만든다(2.5, `CoachWorld.Demo.engineConfig`).
  기본 수명 그대로면 시연 질문이 6초 만에 사라진다 (`PromptEngineScenarioTest.aDemoEngineWithTheDefaultTtlDropsTheCardInSecondsOfRealTime`가 그 대조다).
- **엔진 (engine)**: INFO를 Report(true)로 알리면 `PromptPolicy.onResult`가 `noteInteraction`을 다시 불러 30초 조용한 때가 그 시각부터
  다시 시작된다(INFO를 낼 때 이미 한 번 시작했으므로 실제로는 몇 초 늘어날 뿐이다). 사용자와 대화했으니 맞는 동작이다.
- **급해 목록에서 다른 곳을 골랐는데 내비가 실패**: 카드는 보고 없이 내려갔고, 엔진에서는 그 급해 질문이 수명(45초)이 끝날 때 대답 없는
  질문이 된다. 성공했다면 `noteAccepted`가 벌점 없이 끝냈을 질문이다. 엔진에 '수락 없이 끝내기'만 하는 길은 없다.
- 실행 중인 들르기는 `CoachUiState.goToPending`으로 보인다(`GoToOutcome`에 '실행 중'은 없다). 카드는 수락하는 순간 내려간다.
- **엔진 (engine)**: 사용자 요청(얼마나 남았어, 배고파)에 답하는 질문(INFO 등)이 떠 있던 질문을 버리고 쉼(쿨다운)을 건다(엔진 감사 P1).
  기계는 엔진을 따라 카드를 내리고, 그 대답을 끊지 않게만 한다. 고치는 것은 엔진 담당이다.
- **pleos-kit (NaviKit)**: `startRoute` 확인이 `ROUTE_GUIDING`만 본다(모의 주행이면 확인 실패 → FAILED). `addWaypoint`는 경유지 조회가
  실패하면 '전'을 0개로 보고 거짓 성공을 낼 수 있다(서비스 감사 P11). NaviPort 계약("경로가 있는 모드가 되면 true")과 맞춰야 한다.
- 보냈지만 확인되지 않은 경유지가 늦게 들어갔으면 다시 시도로 두 번 들어갈 수 있다(3장의 남은 위험). 감시(140초)보다 늦은 결과도 같다.
