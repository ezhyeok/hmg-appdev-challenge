# 코치 계층: 코어 (CoachCore 액터 · 얇은 CoachService)

PT 기술 질의응답용. 코드: `rest-engine/coach/src/main/kotlin/dev/shimpyo/coach/`의 `CoachCore.kt`, `CoachDeps.kt`, `UrgentFlow.kt`,
`VoiceDesk.kt`, `Crew.kt`, `AsleepMarks.kt`, `PlanKeeper.kt`, `CoreRules.kt`. 앱 쪽 `app/src/main/java/dev/shimpyo/app/coach/CoachService.kt`.
가짜와 조립은 `fakes/CoreFakes.kt`(`CoreRig`). 서버에서 `./gradlew -q -p rest-engine :coach:test --tests 'dev.shimpyo.coach.CoachCore*'`.

2026-11-12 닫힘 검증(독립 리뷰 R-F, 서비스 SV, 화면 UI) 뒤에 코어에서 상태 기계 둘(급해, 음성)과 작은 규칙들을 떼어 냈고,
찾은 틈을 그 부품의 규칙으로 닫았다. 이 문서는 그 뒤의 모습이다. 질문 기계는 `prompt.md`, 세계와 여정은 `world.md`.

## 1. 왜 액터인가

예전 `CoachService`(871줄)는 필드 30개를 코루틴 12종이 `Dispatchers.Default`(여러 스레드)에서 함께 바꿨다. 시연 전환과 틱이 섞이고
(구조 리뷰 D6), 음성 '응'과 버튼이 둘 다 경유지를 넣고(D7), 시연 정차가 실제 기록에 들어갔다(D3). 버그가 기기 실행마다 하나씩 나왔다.

지금은 코치 상태를 `CoachCore` 하나가 들고, 우편함 하나를 코루틴 하나가 처리한다.

- 메시지 처리 중에는 **일시 중단하지 않는다**. 오래 걸리는 일(음성, 내비 명령, LLM 해석, 파일 저장, 타이머)은 효과로 launch하고 결과를
  메시지로 다시 받는다. 그래서 '확인한 뒤 행동' 경쟁이 구조적으로 없다(두 번째 수락은 이미 Idle인 기계가 버린다).
- **효과는 끝나면 결과를 정확히 한 번 알린다**(실패해도 실패 값으로). 취소됐을 때만 알리지 않는다: 취소한 쪽이 뒷일을 안다.
  예전에는 LLM 해석이 예외를 던지면 결과가 오지 않아 마이크 표시가 '생각하는 중'에 남았다(R-F11).
- **늦게 온 결과는 세계 세대로 가린다**. 사용자가 한 말의 결과(듣기·해석·탑승자 말하기)는 그 말을 듣기 시작한 때의 세대
  (`ActiveWorld.generation`)를 싣고 오고, 세대가 다르면 버린다(R-F8). 카드의 대답은 카드 key로(prompt.md 2.6), 들르기 결과는
  시작할 때의 세대로(6장) 가린다.
- 판단은 부품이 한다(functional core, imperative shell). 코어에 남은 것은 우편함, 틱, 명령 가르기, 질문 기계의 효과 실행,
  세계 전환 때의 초기화 순서다.

## 2. 코어가 드는 부품

| 부품 (파일) | 맡는 것 | 상태 | 단위 테스트 |
|---|---|---|---|
| `PromptMachine` | 화면 질문 카드와 음성 질문, 들르기 한 번에 하나, 결과의 수명 (prompt.md) | 카드, 닫은 질문, 실행·대기 중 들르기, 다시 시도 연결 | `PromptMachineTest` 40 |
| `UrgentFlow` | 급해 요청 하나의 진행, 음성으로 연 요청의 번호, 들르기로 한 곳의 고정, 엔진에 알릴 차례 (5장) | 요청, 고른 곳(넣는 중·넣은 뒤), 알릴 차례 | `UrgentFlowTest` 21 |
| `VoiceDesk` | 음성 작업 모두(묻기·듣기·탑승자 말하기·해석·말), 무엇을 취소할지, 대답을 말로 낼지 화면 알림으로 낼지, '생각하는 중' (7장) | 작업들, 알림을 거둘 때 | `VoiceDeskTest` 18 |
| `Crew` | 탑승자: 세계마다의 목록, 건강 정보 동의, 자는 중 표시, 말로 설정 (7장) | `AsleepMarks`, 마지막으로 본 동의 | `CrewTest` 6, `AsleepMarksTest` 8 |
| `PlanKeeper` | 계획을 언제 다시 세우고 번호를 언제 올리는지 (순수) | 원함·요청·재료 키·세운 때·번호 | `PlanKeeperTest` 9 |
| `StopRecorder`(`StopDetector`) | 정차 판정과 기록, 되돌린 정차의 판정 (prompt.md 4장) | 지금 정차, 다시 대기, 판정 대기 | `StopDetectorTest` 34, `StopRecorderTest` 13 |
| `WorldSwitch`(`TripSession`, `CoachWorld`) | 세계(실제·시연)와 엔진·여정 한 벌 (world.md) | 지금 세계, 옆에 둔 실제 세계 | `WorldSwitchTest` 20, `TripSessionTest` 19, `CoachWorldTest` 10 |
| `CoreRules.kt` | `FreshAirGate`(환기 문·되돌리기·한 번에 하나), `MemorySaver`(학습 저장 시점), `TravelHeading`(진행 방향), `HealthConsent`(동의), `CoreRules`(검색 위치, 후보 더하기, 고른 정차의 목적) | 각자 작은 상태 | `CoreRulesTest` 10, `TravelHeadingTest` 4 |

`CoachDeps.kt`: `CoachDeps`(포트와 부품 한 묶음), `LoopInputs`(코어가 쓰고 내비 추적·후보 루프가 읽는 값 둘: 내비 위치 보충 허락, 후보 루프의
다음 입력. 예전 이름 CoachGates), `EngineAccepted`(6장), `WallClock`(실제 시각을 주는 `CoachClock`의 이름).

크기: `CoachCore.kt` 901줄 → 떼어 낸 직후 699줄(동작 그대로, 커밋 d2cb278) → 지금 794줄. 다시 늘어난 것은 닫힘 검증의 수정이 코어에
더한 이음매다: 들르기의 넣는 중·엔진 알림(6장), 되돌린 정차의 판정 대기(10장), 음성 결과의 세대, 자는 중 표시 전달. 규칙은 모두 부품에 있다.

떼어 내지 않은 것: `tick()`, `applyChange(WorldChange)`, `runEffects`. 이 셋이 "무엇을 어떤 순서로"를 정하는 코어 자신이다.
우편함은 하나이고 범용 효과 틀은 없다(결과 메시지 하나를 돌려주는 효과용 도우미 한 개뿐).

## 3. 메시지와 효과

| 메시지 | 누가 보내나 | 처리 |
|---|---|---|
| `Tick` | 코어의 1초 타이머 (밀리면 하나만 쌓인다) | 4장 |
| `Command(UiCommand)` | 서비스가 `CoachStore.commands`에서 옮긴다 | 4장 |
| `MachineEvent(Expired / GoToFinished)` | 만료 타이머, 들르기 실행 효과 | `PromptMachine.on` → 효과 실행 |
| `AskHeard(key, text)` | 카드의 묻기 음성 효과 (끝나면 반드시, 취소되면 안 보냄) | `IntentParser.replyTo`로 대답 하나로 읽어 `Heard(key, answer, other)` (R-F3) |
| `Listened(gen, text)` | 마이크 버튼 듣기 효과 | `parseSpoken` → 의도 / 자는 중 표시 / 못 읽으면 LLM 효과 |
| `Interpreted(gen, intent)` | LLM 해석 효과 | 의도 처리, 없으면 '못 알아들었어요' |
| `OccupantsHeard(gen, text)`, `OccupantsInterpreted(gen, list)` | 탑승자 말하기·그 해석 효과 | `Crew.heard` / `Crew.interpreted` |
| `FreshAirDone(ok, announce)` | 외기 전환 효과 | `FreshAirGate.finished`(되돌리기 예약), 알림 |
| `MemorySaved(memory, ok)` | 학습 저장 효과(IO) | `MemorySaver.finished` |

`gen`이 지금 세대와 다르면 그 메시지는 버린다.

`PromptMachine` 효과 → 코어가 하는 일: `Show` 화면 카드·이유·지금 거리, `Ask` 묻기 음성 효과, `CancelVoice` 묻기·듣기 취소 + `VoicePort.stop`,
`Speak` 말하기(말할 수 있을 때), `Notice` 화면 알림(말할 수 없을 때의 안내, R-F4), `ScheduleExpiry` 실제 시간 타이머(같은 key면 교체),
`Report` 엔진에 대답, `StartGoTo` 지금 위치 기준 후보로 `GoToRunner` 효과 + `goToPending` 올림 + 급해 흐름에 알림(세계가 바뀌어도 취소하지 않음),
`FreshAir` 외기 효과, `Forward` 말로 한 요청 처리, `PublishGoToResult` 화면 결과(결과가 있으면 `goToPending`을 내리고 6장의 뒷일).

## 4. 틱과 명령

```
odometer.advance(차속); crew.enforceConsent()            주행 거리, 동의가 꺼졌는지
t = 세계 시계, pos = world.position(실제 위치)            시연이면 가상 시각·위치를 덧씌운다
heading = travel.update(실제 위치, 차속, 기어)            진행 방향: 움직일 때 본 마지막 방위 (C1)
recorder.onTick(t, 기어, 차속, 문, pos, persistsHistory)  → 정차 기록이면 여정에 알림, 화면 기록, 자는 중 표시 풂
waiting = recorder.restorePending                         되돌린 정차의 판정을 기다리는 중 (10장)
!waiting: 되돌린 정차가 끝났으면 worlds.restoredParkEnded → worlds.onTick(t, 정차) → 새 여정이면 ENGINE 변경
crew.onTick(t, 정차)                                      화장실 정차·아침 7시에 자는 중 표시 풂
ahead = world.candidates(candidates.live(내비, 주행 거리))  후보를 매초 달린 만큼 줄인다 (UI #19)
input = world.apply(CoachInput(..., candidates = ahead + 급해 결과·고른 곳, intent = 급해 알림 또는 쌓인 의도 하나, asleepOccupantIds))
loopInputs.request = 후보 루프의 다음 입력 (실제 위치면 방위 = 진행 방향)
!waiting: 재시작 전에 들르기로 한 곳이 후보에 보이면 엔진에 다시 알림 → out = engine.update(input)
PromptMachine.on(EngineTick(out.prompt, out.next, out.parked)) → 효과
자동 환기(문 10분) · urgent.track(위치, 추천) · 외기 되돌리기(90초) · 알림 거두기 · 급해 진행 · 계획 갱신 · 화면 상태 한 번 · 학습 자동 저장(1분)
```

화면 상태(`CoachUiState`)는 계약의 모든 필드를 채운다. 값이 같으면 StateFlow가 내보내지 않으므로 서 있는 동안 화면이 다시 그려지지 않는다.
이번에 채우기 시작한 필드: `goToPending`(넣는 중인 곳), `notice`(말 대신 잠깐 보일 알림), `asleepOccupantIds`.

| 명령 | 처리 |
|---|---|
| `Urgent(id)` | `UrgentFlow.open`. 바로 `urgentRequestId = id, LOCATING`을 올리고, 위치를 알면 그 자리에서 찾는다 (5장) |
| `DismissUrgent(id)` | 그 요청이면 끝 (IDLE). 아직 엔진에 알리지 않았으면 알리지 않는다 |
| `Listen` | 말하거나 듣는 중이면 멈추기, 아니면 듣기 효과 (마이크 토글, UI #12) |
| `StopVoice` | `VoiceDesk.stopAll`. 카드가 묻던 중이면 `Heard(UNKNOWN)`으로 버튼 창 10초 |
| `Answer(key, accept)` | `Tapped` |
| `GoTo(stop)` | `PromptMachine.GoTo(stop, urgentPick = 급해 목록의 곳인가)` (한 번에 하나, 같은 곳 두 번 무시, 실패 뒤 다시 시도 연결) |
| `DismissGoToResult` | 기계로. 다시 시도 연결은 끊지 않는다 (화면의 [다시 시도]가 닫기를 먼저 보낸다, SV-D9) |
| `SetOccupants` | `Crew.set`: 건강 유형은 동의가 있을 때만. 시연이면 그 시연에만 |
| `DescribeOccupants` | `Crew.describe`: 묻기 → 규칙(OccupantPhrase) → 못 하면 LLM → 동의 거르기 → 설정 (7장) |
| `SetAsleep(id, asleep)` | `Crew.setAsleep`: 아이만 (7장) |
| `Demo(name)` | `WorldSwitch.enterDemo` → 8장 |
| `ClearRecords` | 기록 지우기(`HistoryPort.clear`), 학습 지우기(`MemoryPort.clear`, 세대 올림), `WorldSwitch.clearLearning` |
| `ProfileChanged` | 지금 세계의 기준점으로 프로필 다시 (시연 여정 유지) |
| `MakePlan` | 계획을 세우고, 화면이 보는 동안 재료(후보 스냅샷·내비 epoch·세계)가 바뀌면(또는 30초마다) 다시 세운다 (UI #4) |
| `ClosePlan` | 더 세우지 않는다. 마지막 계획은 상태에 남는다 (R-F12: 예전에는 MakePlan 한 번에 10분 동안 30번 다시 세웠다) |
| `PermissionsChanged` | 서비스가 신호 수집을 다시 띄운다(Car 연결부터). 코어는 할 일이 없다 |

`planGeneration`은 요청에 답한 계획마다 1씩 오르고, 코어를 만든 때(경과 시간)에서 시작한다: 같은 프로세스에서 서비스만 다시 떠도
앞 코어가 화면 상태에 남긴 번호와 겹치지 않는다(SV-N8, UI-N6). 음성으로 연 급해 번호도 같은 방식이다(음수, UI-N4).

## 5. 급해 (`UrgentFlow`)

요청의 진행: LOCATING → (위치 있음) SEARCHING(정차 후보 표를 아직 못 읽었으면) → READY / NO_RESULT. 위치가 8초 안에 없으면
NO_LOCATION(+ "현재 위치를 아직 모르겠어요"), 그 뒤라도 위치가 생기면 찾는다. 결과는 떠 있는 동안 10초마다 다시 찾는다.
코어는 `step`이 SEARCH를 내면 가까운 화장실(`StopIndex.near`, 5km)을 찾고 엔진의 `urgent()`(상태를 바꾸지 않는 순위)로 순위를 매겨
`found`에 넘긴다: 화면 목록 1등과 엔진의 급해 질문이 같은 곳이다.

| 일어난 일 | 요청 | 엔진에 알리기 (`takeEngineIntent`) | 고른 곳 |
|---|---|---|---|
| `open` (화면) / `openByVoice` (음성, 음수 번호) | 새 요청 (앞 요청을 바꾼다) | 알릴 차례 없음 | 앞서 들르기로 한 곳은 그대로 |
| `found` 첫 결과 | READY / NO_RESULT | **알릴 차례**: 다음 틱 엔진 입력에 `UrgentRestroom` | |
| `found` 10초 갱신 | 목록 갱신 | 다시 알리지 않는다 | |
| `dismiss` (화면 닫기) | 끝 | 알릴 차례를 버린다 (R-F7b: 닫은 뒤에 엔진이 말했다) | 넣는 중·넣은 곳은 남는다 (UI-9c) |
| `sayAgain` ("화장실 급해"를 또 말함) | 바로 다시 찾는다 | 다시 찾은 결과로 **다시 알린다** (R-F7c: 아무 대답이 없었다) | |
| `onGoToStarted(stop)`: 이 요청이 목록에 올렸던 곳 | 그대로 (고른 곳을 기다림) | **미룬다** (UI-9b: 방금 고른 곳을 "바로 안내할까요?"로 또 물었다) | 넣는 중으로 후보에 넣는다 |
| `onGoToStarted(stop)`: 목록에 없던 곳 | 그대로 | 그대로 | 없음 |
| `onGoToFinished(고른 곳, 성공)` | **끝** (화면은 같은 번호의 IDLE을 보고 닫는다) | 버린다 | 들르기로 한 곳(고정) |
| `onGoToFinished(고른 곳, 실패)` | 그대로 (다시 고를 수 있다) | 미뤄 둔 알림이 풀린다 | 뺀다. 앞서 들르기로 한 곳은 남는다 |
| `onGoToFinished(다른 곳)` | 그대로 (R-F7a: 먼저 보낸 들르기가 급해 화면을 닫았다) | 그대로 | |
| `onEngineReset` (새 여정, 기록 지우기) | 그대로 | 결과가 있었으면 **다시 알린다** (R-F7d) | |
| `reset` (세계 전환) | 끝 | 버린다 | 모두 버린다 |

엔진에 급해를 알리면(서비스 감사 D7) 엔진이 필요를 '말한 것'으로 기록하고(게이지·학습), 1등 장소로 `URGENT_RESULT` 질문을 낸다.
요청이 살아 있는 동안 찾은 곳을, 그 뒤에는 고른 곳을 엔진 후보에 더한다(`extras`, 대개 경로 후보가 아니라서 빼면 엔진이 잊는다).

고정을 푸는 때 (`track`, 매 틱): 지나쳤다(직선거리가 가장 가까웠던 때보다 300m 넘게 멀어짐, 또는 150m 안까지 갔다가 벗어남),
또는 엔진이 15초 넘게 그곳을 추천하지 않는다(도착해 섰다, 엔진이 내렸다). 예전에는 엔진이 추천을 멈출 때만 풀었는데, 고정이 후보를
대 주므로 엔진은 지나친 곳을 90분 동안 추천했고 그동안 다른 질문도 내지 않았다(R-F1).

## 6. 들르기와 엔진에 알리기

들르기는 `PromptMachine`이 한 번에 하나만 실행한다(prompt.md 2.4). 코어의 이음매:

- **넣는 중** (UI-N2): `StartGoTo`에 `goToPending = 그 정차`, 결과가 나오면 null. 화면은 이 값이 있는 동안 진행 표시를 하고 버튼을 막는다.
  화면에는 자기 시간 제한이 없다: 에뮬레이터에서 내비 확인이 19.5초 걸렸는데 화면의 12초 제한이 먼저 '결과 없음'을 띄웠다.
  끝은 기계의 감시(140초)가 보장한다. 대기하던 들르기가 시작되면 그것이 넣는 중이 된다.
- **급해 목록에서 고름** (UI-9a): 화면의 `GoTo`가 지금 급해 요청이 올렸던 곳이면 `urgentPick`이다. 그 선택이 떠 있는 급해 질문의
  대답이다: 같은 곳이면 수락(성공하면 그 질문으로 보고), 다른 곳이면 질문은 거절 없이 끝난다. 화면이 뒤이어 보내는 그 질문의 거절은
  닿을 카드가 없어 버려진다.
- **결과의 뒷일** (`onGoToResult`, 기계의 엔진 보고가 끝난 뒤): 경로를 바꿨으면 내비 즉시 재조회, 급해 흐름에 알림(5장), 성공이면
  엔진과 여정에 '들르기로 한 곳'을 알린다. 감시가 끝낸 실패도 같은 길로 온다.
- **엔진에 알리기** (SV-D7): 성공한 들르기는 질문 수락이든 급해 목록이든 계획 화면이든 다시 시도든 `EngineAccepted.note(engine, stop,
  purposes, now)`를 한 번 부른다. 목적은 아는 만큼: 급해 목록이면 화장실, 엔진이 지금 그곳을 추천하면 그 목적, 계획에 있으면 계획의 목적,
  모르면 화장실 있는 곳은 화장실(`CoreRules.acceptedPurposes`). 다른 세계에서 시작한 들르기는 지금 엔진의 일이 아니라 알리지 않는다.
  엔진 쪽 API는 `CoachEngine.noteAccepted(stop, purposes, now)`이고, 그것이 들어오기 전의 기본 구현(`EngineAccepted.PIN_ONLY`)은
  아무것도 하지 않는다: 엔진이 스스로 물은 곳(질문 id로 보고)과 급해 목록에서 고른 곳(고정)만 추천이 유지된다.
- **재시작을 넘어** (SV-N7): 들르기로 한 곳의 id와 시각을 여정 맥박에 적는다(`TripSession.onAccepted`, world.md 4.4). 주행 중
  재시작으로 이어받았으면, 그 정차가 후보에 보이는 틱에 엔진 update 전에 다시 알린다(한 번). 2분 안에 보이지 않으면 잊는다.

## 7. 말하기·듣기(`VoiceDesk`)와 탑승자(`Crew`)

`VoiceDesk`는 음성 작업을 모두 든다. 작업은 코어의 효과 스코프에서 돌고 결과를 정확히 한 번 돌려준다.

| 작업 | 시작 | 끝나면 | 취소되는 때 |
|---|---|---|---|
| 묻기 (카드) | `PromptEffect.Ask` | `AskHeard(key, 들은 말)` | 새 묻기, `CancelVoice`, 멈추기, 세계 전환 |
| 듣기 (마이크) | `Listen` | `Listened(gen, text)` | `CancelVoice`, 멈추기, 세계 전환 |
| 탑승자 말하기 | `DescribeOccupants` | `OccupantsHeard(gen, text)` | 멈추기, 세계 전환 |
| 해석 (LLM) | 규칙이 못 읽은 말 | `Interpreted` / `OccupantsInterpreted` (실패하면 null) | 멈추기, 세계 전환 |
| 말 | `Speak`, 대답 | 없음 | 멈추기, 세계 전환 |

- **대답이 가는 곳** (R-F4): 사용자가 물은 것의 대답(`answer`: 안내, 못 알아들음, 탑승자 확인)은 말할 수 있으면(설정의 음성 안내 + TTS)
  말하고, 아니면 `CoachUiState.notice`로 약 6초 보인다. 음성 안내를 꺼도 마이크로 물을 수 있는데 예전에는 답이 어디로도 나가지 않았다.
  대답을 받지 않는 말(`speak`: 들르기 결과, 환기 알림)은 말할 수 없으면 내지 않는다(결과 카드가 화면에 있다).
- **생각하는 중**: 의도 해석이 도는 동안 `VoiceState.THINKING`, 끝나면(결과·실패·멈추기·세계 전환) 되돌린다.
- **카드에 한 말은 대답 하나** (R-F3): "아니 안 급해"가 거절하고 급해를 열었고, "응 휴게소 들르자"가 수락한 뒤 "쉬어 갈까요?"를 또 물었다.
  `IntentParser.replyTo(text, occupants, 카드의 장소 이름)`가 대답 하나와, 그 대답과 다른 분명한 요청이 있을 때만 `other`를 준다.

`Crew`는 탑승자에 관한 일을 잇는다.

- **동의** (UI-N3): 건강 유형(임신, 과민성 방광·장)은 동의가 있을 때만 둔다. 동의는 세계와 무관한 설정 하나인데 탑승자는 세계마다
  따로라서, 동의가 꺼진 것을 보면(값이 바뀐 때와 시작할 때) 설정에 저장된 실제 탑승자와 시연에서 사용자가 고친 탑승자에서 건강 유형을 뺀다.
- **말로 설정** (UI-12): 음성 안내를 껐으면 묻는 말은 알림으로 내고 듣기만 한다. 음성 인식을 쓸 수 없으면 왜 안 되는지 알린다.
  동의가 없어 걸러 내면 운전자만 남는 말("임산부 한 명")은 탑승자를 바꾸지 않고 동의 안내만 한다(R-F9).
- **자는 중** (U4, `AsleepMarks`): 운전자가 말("아이 잠들었어")이나 화면으로 알려 준 잠든 아이. 엔진이 밤(20~07시)에 그 아이의 화면
  게이지에 여유를 둔다(`CoachInput.asleepOccupantIds`, 매 틱 넘긴다).

  | 규칙 | |
  |---|---|
  | 표시할 수 있는 사람 | 아이(CHILD) 탑승자만. 누구인지 말하지 않았으면 아이가 한 명일 때만 |
  | 푸는 때 | "깼어"·화면에서 끔 / 그 탑승자가 빠짐 / 화장실 정차(P + 문 열림 + 3분: 엔진이 화장실 시계를 돌리는 때, 그리고 기록될 때) / 현지 시각이 07:00을 지남 / 세계 전환 |

## 8. 세계 전환 (world.md 6장)

`WorldChange.kind`에 따라 코어가 초기화하는 것:

| | `ENGINE` (새 여정, 기록 지우기) | `WORLD` (시연 켜기·끄기·바꾸기) |
|---|---|---|
| 질문 (`PromptMachine` Reset) | 초기화: 카드·결과 내림, 음성 멈춤. 실행 중 들르기는 그대로(결과는 보고 없이) | 같음 |
| 쌓인 의도, 만료 타이머 | 버림 | 버림 |
| 음성 작업 (`VoiceDesk.worldChanged`) | 모두 취소 (결과는 세대가 달라 버려진다) | 같음 |
| 급해 (`UrgentFlow`) | `onEngineReset`: 요청은 그대로, 새 엔진에 다시 알림 | `reset`: 요청·고른 곳 모두 끝 |
| 계획 (`PlanKeeper`) | 다음 틱에 다시 세움 | 지난 세계의 계획 버림. 답하지 않은 요청만 새 세계에서 |
| 재시작에서 이어받은 '들르기로 한 곳' | 버림 | 버림 |
| 자는 중 표시 | 그대로 | 모두 풂 |
| 정차 감지기 | 그대로 | 그 세계의 정차로 (`onWorldChanged`, 판정은 다음 틱: 10장) |
| 지난 엔진 입력·출력·계획 | 그대로 | 버림 |
| 다른 루프 (`LoopInputs`), 경로 보정 스위치, `candidates.reset()` | 그대로 | 새 세계 정책으로 |
| 화면 | 그대로 | 시연 이름·탑승자·기록, 급해·계획·후보·자는 중 비움 |
| 그대로 두는 것 | 넣는 중인 들르기(`goToPending`), 진행 방향, 환기 문, 학습 저장 간격 | 같음 |

`saveMemory`가 있으면 IO에서 저장한다(시연을 켜기 직전의 실제 학습, 서비스 감사 D15). 전환은 명령 메시지 안에서 한 번에 일어나므로
틱 중간에 끼지 않는다(D6). `Demo(holiday)`/`Demo(null)`/`Demo(stop)`을 몰아 보내도 차례로 처리된다.

## 9. 시계

| 값 | 시계 | 이유 |
|---|---|---|
| 엔진 입력 시각, 정차, 여정, 자는 중 표시 | 세계의 코치 시각 (시연이면 가상) | 엔진은 입력 시각으로만 판단 |
| 카드 수명, 만료 타이머, 들르기 감시·결과 수명, 급해 기다림·재검색·고정 유예, 환기 문·되돌리기, 학습 저장 간격, 계획 갱신, 알림 수명, 이어받은 수락을 기다리는 시간 | 경과 시간 | 시연 전환을 넘어 이어진다. 예전에는 코치 시각이라 시연 뒤 몇 시간씩 막혔다(D8) |
| 시연을 켠 때 (`demoSinceWall`), 맥박 | 실제 시각 (`WallClock`) | 실제 세계의 시각과 비교한다 |
| 시연 엔진의 질문 수명 | 코치 시각 × 배속 (`CoachWorld.Demo.engineConfig`) | 실제 45초 (prompt.md 2.5) |
| 계획 번호·음성으로 연 급해 번호의 시작점 | 코어를 만든 때의 경과 시간 | 다시 뜬 코어가 앞 코어의 번호를 또 쓰지 않는다 |

## 10. 스레드, 시작과 끝

- 서비스: `Dispatchers.Default.limitedParallelism(1)` 하나에 코어(`run`)·`NaviTracker.run`·`CandidateProvider.run`을 띄운다. 셋이 같은 스레드라
  `Odometer`·스냅샷을 잠금 없이 나눠 본다. 다른 루프는 코어를 직접 부르지 않고 `LoopInputs`만 읽는다.
- 효과는 코어의 `supervisorScope`에서 돈다(같은 스레드). SDK 호출은 kit이 전용 스레드로 옮긴다.
- 파일: 읽기는 시작 때 `withContext(io)`. 학습 저장은 IO 효과. 정차 기록·여정 맥박은 앱 저장소가 앱의 쓰기 스레드로 넘긴다.
  학습 지우기만 코어 스레드에서 바로 한다(세대를 즉시 올려야 이미 꺼낸 저장이 지운 학습을 되살리지 않는다).

시작: IO에서 맥박·학습·기록 읽기 → `WorldSwitch.open`(여정 이어 가기/새로) → 기록 정리 → 정차 중 재시작이면 감지기에 되돌림 →
새 세계 정책 알림 → 동의 확인 → 화면(`serviceRunning = true`, 지난 코어가 남긴 넣는 중·알림·자는 중 표시 비움). 시작 전에 온 명령은
우편함에 남아 있다.

**되돌린 정차의 판정** (SV-N2·N3): 규칙은 하나다. "되돌린 정차는 마지막으로 본 때(맥박, 시연을 켠 때) 끝났다, 차가 지금도 P가 아니라면."
그 판정은 기어를 알아야 한다. 서비스가 켜진 직후 첫 틱은 Car 연결보다 먼저 올 수 있어서, 감지기는 기어를 알 때까지(길어도 10초) 판정을
미루고(`restorePending`), 그동안 코어는 여정과 엔진을 돌리지 않는다(게이지가 1~2초 늦게 나온다). 판정이 나면 셋이 같은 답을 쓴다:

| 판정 | 정차 감지기 (기록) | 여정 (`TripSession`) | 엔진 |
|---|---|---|---|
| 아직 P | 못 본 동안도 선 것으로 이어 센다 | 첫 틱이 정차를 넘겨받아 화장실(문 + 3분)·휴식(15분)을 이어 센다 | 정차 시작의 P 입력으로 미리 채워져 있어 이어 센다 |
| 떠났다 (기어 D·R·N, 기어 모름 + 움직임, 10초 동안 기어 모름) | 마지막으로 본 때 끝난 정차로 기록 | 맥박이 본 만큼만 이미 셌다 (world.md 4.1) | `CoachWorld.endPark`: 그때 떠난 것으로 맞춘다 |

예전에는 첫 틱의 기어 UNKNOWN을 '아직 서 있다'로 읽어 같은 상황이 신호 도착 순서에 따라 10분 정차 / 40분 정차로 갈렸고, 여정과 엔진은
'지금까지 서 있었다'로 세어 게이지는 다녀온 것처럼 찼는데 기록에는 그 정차가 없었다.

끝(`onDestroy`): 코치 스코프 취소(루프가 먼저 멈춘다) → 코치 스레드에서 `shutdown()`(맥박 저장, 바뀐 학습 저장, 2초 상한. 되돌린 정차를
아직 판정하지 못했으면 맥박은 쓰지 않는다) → 만든 kit만 놓기 → 화면의 진행 중 상태 지우기(`CoachStore.resetTransient`).

## 11. 시나리오 → 테스트

`CoachCoreTest` 29 (시나리오 끝까지):

| 시나리오 | 테스트 |
|---|---|
| 귀성길: 옥산 2~5km 전 마지막 기회 → 음성 '응' → 새 길안내, 결과 안내, 다시 묻지 않음 | `holidayDemoAsksOksanAsTheLastChanceAndYesStartsTheRouteThere` |
| 길안내 중이면 경유지 추가 | `withARouteGuidingNaviTheAcceptAddsOksanAsAWaypoint` |
| 7.2배속 시연에서 카드가 실제 8초 이상 | `theLastChanceCardStaysForRealSecondsInTheSevenTimesFasterDemo` |
| 음성 끔: 묻지도 말하지도 않음, 카드는 보임 | `voiceOffMeansNoSpeechAtAllButTheCardStillShows` |
| 엔진이 더 앞선 질문을 내면 같은 틱에 카드 교체 | `theEnginesUrgentAnswerReplacesTheCardWithinOneTick` |
| 급해: LOCATING → READY, 엔진 질문, 10초마다 다시 / 찾는 중 닫기 / 위치 없음 / 수락 / 음성으로 열기 | `urgentReportsLocatingThenResultsAndRefreshesWhileShown` 외 4 |
| 오후 졸음 시연 → 실제 3분 안에 휴식 제안 | `fatigueDemoSuggestsARestWithinThreeRealMinutes` |
| 시연 전환 몰아 보내기, 시연 끄기, 정차 시연, 실제 정차 기록·학습 | `rapidDemoSwitchesEndInOneConsistentWorld` 외 3 |
| 학습 저장 1분에 한 번, 기록 지우기 | `memoryIsSavedAtMostOnceAMinute`, `clearRecordsEmptiesHistoryAndLearningAndNothingResurrectsThem` |
| 시연 중 설정·탑승자 변경, 건강 유형 동의, 동의 철회(시연 중·실제·시작할 때) | `settingsAndOccupantChangesDuringADemoKeepTheDemoTrip`, `healthTypesNeedConsentWhenSettingOccupants`, `withdrawingHealthConsent…` 3 |
| 정차 중 재시작, 자동 환기, 멈춘 음성·내비 | `aRestartWhileParkedResumesTheParkAndRecordsItFromTheOriginalStart` 외 2 |
| 계획 번호, 계획 닫기, 다시 뜬 코어의 번호, 결과의 at과 수명 | `thePlanGenerationRisesOnlyWhenAPlanIsRequested`, `aClosedPlanIsNotRebuiltUntilItIsRequestedAgain`, `aRestartedCoreNeverRepeatsAPlanGeneration`, `goToResultsHaveDistinctTimestampsAndExpire` |

닫힘 검증의 실패 테스트는 닫은 뒤 지키는 부품 옆으로 옮겼다:

| 파일 | 지키는 것 |
|---|---|
| `CoachCoreUrgentTest` 10 | R-F1, R-F7a~d, UI-7, UI-9a·b·c, UI-N4 (닫기 직후, 먼저 보낸 들르기, 다시 말하기, 엔진 초기화, 질문 전에 고름, 넣는 중, 2순위 고름, 닫고 기다림, 지나침, 번호) |
| `CoachCoreGoToTest` 9 | UI-N2(넣는 중·대기·감시), SV-D9(닫기 뒤 다시 시도), SV-D7(엔진에 알리기: 성공만, 한 번, 세계가 같을 때), SV-N7(맥박에 적기) |
| `CoachCoreVoiceTest` 19 | R-F3, R-F4, R-F8, R-F9, R-F11, UI-12, 멈추기(StopVoice), 말로 설정(DescribeOccupants) |
| `CoachCoreTripTest` 13 | SV-N2, SV-N3(재시작: 늦은 기어, 아직 P, 판정 전, 기어 없음, 시연 끄기), SV-N7(이어받은 수락), 새 여정, 종료(shutdown), 새 코어의 화면 초기화 |
| `CoachCoreAsleepTest` 6 | U4 (화면·말·화장실 정차·탑승자 빠짐·세계 전환·07:00, 엔진의 `RESTROOM_ASLEEP` 이유) |
| `StopRulesCrossCheckTest` 8 | 엔진의 정차 규칙과 `StopDetector`가 같은 신호 순서를 같게 본다 (prompt.md 4.4) |
| `closure/UiSeamPinnedTest` 10 | 화면이 계약에서 기대하는 것 (그대로) |

`closure/`에 아직 실패로 남아 있는 것은 다른 담당의 일이다: 말 해석(`ReviewIntentParserTest`, `ReviewCoachCoreTest`의 '얼마나 남았어'),
표본 시각(`ReviewSampleClockTest`), 후보의 요청 한도(`ServiceClosureOpenTest`), 먼 쌍둥이 행(`ClosureOpenTest`), 엔진의 질문 이유(`UiSeamOpenTest`).

## 12. 닫은 결함

| ID | 내용 | 어떻게 |
|---|---|---|
| 구조 D6·D7, 서비스 D10 | 틱과 시연 전환·이중 수락 경쟁 | 액터 (명령이 틱 사이에), 기계가 Idle에서 두 번째 수락을 버림 |
| 서비스 D7 | 급해가 엔진을 우회 | 5장 |
| UI #4, R-F12 | 계획이 갱신·정리되지 않음 / 닫아도 계속 세움 | 보는 동안 다시 세움, `ClosePlan` |
| UI #9, R-F1, R-F7, UI-9 | 급해: 고른 곳이 사라짐, 지나친 곳에 묶임, 닫은 뒤 말함, 다시 말해도 조용함 | `UrgentFlow` (5장) |
| UI #12, R-F4, UI-12 | 음성 끔인데 말함 / 음성 끔이면 답이 없음 / 말로 설정이 조용히 안 됨 | `VoiceDesk.speak`·`answer`, `Crew.describe` |
| R-F3 | 카드에 한 말이 대답이자 새 요청 | `IntentParser.replyTo` |
| R-F8, R-F11 | 시연 중 한 말이 실제에 적용 / 실패한 해석이 '생각하는 중'에 남음 | 세대, 결과 한 번 (1장, 7장) |
| R-F9, UI-N3 | 동의 없는 건강 유형이 탑승자를 지움 / 동의 철회가 다른 세계에 안 닿음 | `HealthConsent.spoken`, `WorldSwitch.dropHealthOccupants` |
| SV-D7, UI-9a | 엔진이 묻지 않은 곳을 골라도 엔진이 모름 | `EngineAccepted` (6장) |
| SV-D9 | 닫기 다음의 다시 시도가 수락으로 이어지지 않음 | 다시 시도 연결은 닫기로 끊지 않는다 (prompt.md 2.4) |
| UI-N2 | 화면의 12초 제한이 성공보다 먼저 | `goToPending` |
| SV-N2, SV-N3 | 재시작 뒤 정차가 신호 순서에 따라 다름, 게이지와 기록이 어긋남 | 10장 |
| SV-N7 | 주행 중 재시작 뒤 방금 수락한 곳을 다시 물음 | 맥박의 `accepted` (6장) |
| SV-N8, UI-N6, UI-N4 | 다시 뜬 코어가 번호를 또 씀 | 번호의 시작점 = 코어를 만든 때 |
| C1 | 느리거나 선 차는 방위 없이 찾아 뒤쪽 시설을 받음 | `TravelHeading` |
| UI #19 | `ahead` 20초마다 | 매초 투영 |
| 서비스 D11·D13·D14·D16·P11·P13, 구조 D12·B8 | 종료 순서, 재연결, 기록 파일, 외기 되돌리기, 경유지 확인 | 앞 판에서 닫음 (CoachService·kit·`FreshAirGate`·`NaviKit`) |
| 정리 | 죽은 코드·중복 | 앱 질문 경로(AppPrompt, APP_CARD_MS) 삭제, `Msg.Interpreted.text` 삭제, 정차 가능한 시설 종류 한 곳(`CoreRules.PARKABLE_KINDS`), 후보에서 정차 찾기 한 곳(`liveStop`) |

## 13. 에뮬레이터 확인 (2026-11-12, Pleos Connect Emulator 2.1.0, debug, Virtual Console)

코어를 다시 나누기 전의 확인이다(동작은 같다). 닫힘 검증 뒤의 수정은 JVM 테스트로만 확인했고, 기기 확인 목록은 14장에 있다.

| 시나리오 | 결과 (logcat `Shimpyo`) |
|---|---|
| 귀성길 `demo-smoke.sh holiday --split` | `prompt shown: LAST_CHANCE 옥산휴게소 at 4920m` → 22.1초 뒤 `prompt hidden` (500m 앞에서 엔진이 거둠, 200m/s). 예전에는 약 6초 |
| 오후 졸음 `fatigue --split` | 시작 66초 뒤 `prompt shown: REST_SUGGESTION 천안호두휴게소 at 17583m`. 첫 CHECK_IN 카드는 실제 45초 뒤 내려감 |
| 도심 급해 `urgent --split` + 급해 버튼 | `urgent …: 5 results`, `prompt shown: URGENT_RESULT 역삼문화공원 제1호 공영주차장 at 261m`(목록 1등과 같음), TTS 묻는 중 '말하는 중', 닫기 → `prompt hidden` |
| 정차 `stop --split` + P·문 열림 → D 40 | `stop recorded: 11 min restroom=true persisted=false`, `stops.json` 없음(`run-as --user 10`), 시연 진입 때 `trip.json` 저장 |
| 끄기 `off` | `demo started: off`, 실제 경로 후보(`candidates READY ROUTE n=9`), 실제 위치(판교 geo fix), `stops.json` 없음 |
| 실제 길안내 | `navi mode: UNKNOWN -> ROUTE_GUIDING`, `navi epoch 1: guidance started`, TBT 후보 `candidates READY ROUTE n=10 until=144490`. 급해 결과 수락 → `goTo result: WAYPOINT_ADDED SK네트웍스(주)스타팅광동주유소`, 화면 '경유지로 넣었어요' + 다음 추천이 그 주유소(엔진이 수락을 앎) |
| 마이크 토글 | 급해 질문을 말하는 중 마이크를 누르면 멈춘다 ('말하는 중' → 마이크) |
| 급해 결과 세 번 연타 | `goTo` 1번, `addWaypoint` 1번 |
| 성능 (release, 이전 main과 번갈아) | 시연 CPU 4.1% (이전 4.7%), PSS 72~75MB (같음), 내비 요청 약 70초에 15번 (이전 20~21). 실제 주행 CPU 1.4% (이전 1.1~1.2%), 내비 요청 14번 (이전 21). `docs/perf/2026-11-12-coach-layer.md` |

## 14. 남은 것과 다른 담당에게

- **엔진 `noteAccepted`**: 들어오면 `CoachDeps.accepted`의 기본값을 `EngineAccepted { e, stop, purposes, now -> e.noteAccepted(stop, purposes, now) }`로
  바꾼다(한 줄). 그 전에는 계획 화면에서 고른 곳과 재시작으로 이어받은 수락을 엔진이 모른다(코어는 알리고 있다: `CoachCoreGoToTest`가 가짜로 본다).
- **말 해석 임시 파일**: `voice/SpokenTemp.kt`(`CardReply`, `Spoken`과 얇은 임시 `replyTo`·`parseSpoken`)는 IntentParser 담당의 브랜치를
  합칠 때 지운다. `CoachCore.kt`의 `import dev.shimpyo.coach.voice.parseSpoken`·`replyTo` 두 줄도 함께 지운다(멤버 함수가 된다).
- **앱(CoachService·저장소)이 바꿀 것**: `CoachGates` → `LoopInputs`, `CoachDeps(gates = …)` → `loopInputs = …`. `TripStore`(trip.json)에
  `accepted {stopId, at}`를 읽고 쓴다(없으면 null). `CoachStore.resetTransient`가 `goToPending`·`notice`도 지운다(코어도 시작할 때 지운다).
- **화면이 바꿀 것**: `goToPending`으로 진행 표시(자기 시간 제한 없음), `notice` 표시, [다시 시도]는 `GoTo`만, 계획 화면을 떠나거나 주행을
  시작하면 `ClosePlan`, 아이 탑승자에 '자는 중'(`SetAsleep`), 급해 목록에서 고를 때 급해 질문의 거절을 보내지 않아도 된다.
- **엔진의 다시 대기 규칙(E4)**: 엔진이 '정차가 시작된 뒤 움직인 거리 300m 이하'를 더하면 `StopRulesCrossCheckTest.creepingBetweenParks`가
  어긋난다. 그때 `StopDetector`에 같은 규칙을 넣는다.
- 급해 목록에서 고른 곳을 주행 중 재시작 뒤에 이어받는 것은 그 정차가 경로 후보일 때만 된다(급해 결과는 대개 경로 후보가 아니다).
- 들르기 결과가 감시(140초)보다 늦게 오면 결과는 버려지지만 내비에는 들어갔을 수 있다 (prompt.md의 남은 위험과 같다).
- LLM 해석은 에뮬레이터에서 응답하지 않는다(첫 호출 40초 뒤 스스로 꺼짐). 그동안 마이크 표시가 THINKING이다.
- 기기에서 볼 것: 급해에서 고른 뒤 닫기·지나치기, 경유지 실패 뒤 [다시 시도], 음성 안내 끔 + 마이크로 "얼마나 남았어"(알림),
  시연 중 [말로 설정] 뒤 시연 끄기, 정차 중 앱 강제 종료 뒤 재시작(기어가 늦게 오는 로그 `restored park ended …`), 밤 시각에 '자는 중'.
