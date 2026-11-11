# 화면 계층 (UI): 코치 상태 → 화면

화면은 판단하지 않는다. 코치가 1초마다 내는 `CoachUiState`(rest-engine/coach `CoachState.kt`)와 화면이 스스로 구독한 주행 제한을 그리고, 사용자의 조작을 `UiCommand`로 보낸다. 이 문서는 "어떤 상태에서 화면이 무엇을 보이는가"의 기준이다. 시각 사양(크기·색·배치)은 `docs/design/screens.md`, 토큰은 `docs/design/pleos-design-tokens.md`.

2026-11-11 UI 감사(결함 #1~#27, Compose S1~S4)를 반영해 다시 짰다. 아래 "감사 #n"은 그 번호다.
2026-11-12 닫힘 검증과 에뮬레이터 확인(실제 경로 판교→부산역)에서 나온 것을 더 고쳤다. "닫힘 UI-n"·"리뷰 R-Fn"·"U n"은 그때의 수정 목록 번호다.

## 1. 구조: 순수 모델과 그리기

상태 → 표시 내용은 모두 순수 함수다. Composable은 그 결과를 그리기만 하므로, 상태별 표시 규칙을 안드로이드 없이 JVM 시험으로 지킨다.

| 파일 (app/src/main/java/dev/shimpyo/app/ui/) | 순수 함수·타입 | 그리는 곳 |
|---|---|---|
| `Restriction.kt` | `driveRestriction(ux, state) → DriveRestriction`, `limitChars` | 모든 화면 |
| `drive/DriveModels.kt` | `stopCard`, `driveActions`, `driveFocus`, `restroomGauge`, `fatigueGauge`, `shortQuestion`, `acceptLabel`, `goToContext`, `restroomQuestionContext` | `DriveScreen` |
| `RouteModel.kt` | `opportunities`, `restroomGaps`, `livePlan`, `displayJams`, `remainingCongestion`, `congestionLabel`, `stripData` | 계획 화면, 가로 주행 화면 띠 |
| `plan/PlanScreen.kt` | `timeline`, `planSummary`, `noOpportunityText` | `PlanScreen` |
| `urgent/UrgentModel.kt` | `urgentView`, `settleDismissed`, `UrgentRequestIds`, `urgentQuestion` | `UrgentScreen` |
| `components/MicModel.kt` | `micModel`, `describeModel` | `VoiceButton`, 탑승자 화면 [말로 설정] |
| `occupants/OccupantModel.kt` | `INTERVAL_CHOICES`, `withInterval`, `occupantSubtitle`, `occupantsSummary`, `canSleep` | `OccupantsScreen` |
| `occupants/HealthConsent.kt` | `onHealthConsentTap`, `withoutHealth` | 동의 스위치 (탑승자·설정) |
| `Labels.kt` | `shortReason`(이유 코드 → 12자 칩), `baselineMin` | 칩, 게이지 한 줄 |
| `ContentState.kt` | `ContentState`(진행 중 요청, 저장·복원), `goToBusy`, `goToProgressText`, `goToToast` | `ShimpyoContent` |
| `Restriction.kt` | `driveRestriction`, `limitChars`, `noticeText` | 모든 화면, 코치 알림 |
| `LaunchExtras.kt` | 실행 인텐트에서 서비스로 넘길 값 | `MainActivity` |
| `PermissionPolicy.kt` | 권한 창을 띄울 때 | `MainActivity` |
| `components/FitColumn.kt` | 높이가 모자라면 덜 중요한 줄부터 빼는 배치 | 추천 카드, 급해 결과 카드 |

화면 → 서비스 명령은 `AppHost.send` → `CoachStore.send`(Channel)로 간다. `ShimpyoContent`의 `ContentActions`가 명령을 보내는 유일한 곳이고, 무엇을 어떤 순서로 보내는지는 `ContentActionsTest`가 지킨다(예전에는 '무엇을 보이는가'만 시험했다).

## 2. 주행 제한 (fail closed, 감사 #2·#26)

플랫폼의 `CarUxRestrictions`를 화면이 서비스 없이 직접 구독한다(`UiRestrictions`, pleos-kit `UxRestrictionWatcher`). 프로세스에 하나만 두어 액티비티가 다시 만들어져도 값을 이어받는다(새로 연결하면 잠깐 '모름'이 되어 정차 중 설정 화면에서 주행 화면으로 튕긴다). 연결이나 구독에 실패하면(Car가 null, 서비스 오류) 1초부터 두 배씩(최대 30초) 다시 연결한다.

| 화면 구독(`UxRestrictions`) | 결과 `driving` |
|---|---|
| Known, requiresDO = true | true |
| Known, requiresDO = false | false |
| Unknown(연결 전·구독 실패·해제) | **true** |

서비스가 읽은 값(`CoachUiState.drivingRestricted`)은 쓰지 않는다(`driveRestriction(ux)`는 상태를 받지 않는다). 서비스가 다시 뜰 때 새 인스턴스가 자기 구독을 내기 전까지는 죽은 인스턴스의 옛 false가 CoachStore에 남아 그 틈에 정차 화면이 열릴 수 있기 때문이다(독립 재검토).

- `driving`이면 주행 화면과 급해 결과만 그린다. 제한을 실제로 읽었을 때(`known`)만 고른 화면을 주행 화면으로 되돌리고 토스트 "주행 중에는 주행 화면만 보여요"를 띄운다. 모를 때(차가운 시작, 프로세스 복원)는 그리기만 주행 화면으로 하고 저장된 화면은 남겨 두어, 정차로 읽히면 그 화면으로 돌아간다.
- 기능별 비트(주행 중일 때만): `LIMIT_STRING_LENGTH` → 주행 화면 카드, 급해 결과(이름·시간·질문 줄·진행 줄), 코치 알림(3.9)을 `maxRestrictedStringLength`로 자른다(게이지·칩 문구는 12자 이하라 걸리지 않는다). `LIMIT_CONTENT` → 급해 결과 수를 `maxCumulativeContentItems`로 줄인다(앱 상한 2보다 작을 때만). `NO_SETUP`·`NO_KEYBOARD`·`NO_VIDEO`는 정차 화면을 모두 닫는 것으로 이미 지킨다. 음성 인식 문장(`lastHeard`)은 정차 중 시연 패널에만 있어 `NO_VOICE_TRANSCRIPTION`에 걸리지 않는다.
- pleos-kit `CarState.drivingRestricted`도 모르면 true다(예전 기본값 false).

## 3. 상태 → 화면

### 3.1 추천 카드 (`stopCard`, 위에서부터 먼저 맞는 것)

| 상태 | 맥락 줄 | 제목 | 둘째 줄 |
|---|---|---|---|
| `serviceRunning = false`, 게이지를 받은 적 없음 | 다음 화장실 | 준비하고 있어요 | 잠시 후 게이지와 추천이 나타나요 |
| `serviceRunning = false`, 받은 적 있음 (감사 #18) | 다음 화장실 | 코치가 멈췄어요 | 앱을 다시 열면 이어서 계산해요 (게이지는 "--"·"코치 멈춤", 버튼 없음) |
| `prompt != null` | 화장실 질문(LAST_CHANCE)은 아래 '화장실 질문의 맥락 줄' 규칙으로. 그 밖은 종류로 (쉬어 갈 곳, 식사할 곳, 가장 빠른 화장실, 졸음 확인, 환기 권유, 안내) | 장소 이름, 장소 없으면 `shortQuestion`(질문 문장만, 30자 이하, 감사 #11) | 지금 거리만: 같은 곳의 `next` → `promptStop`. 둘 다 없으면 위치("경로 위")만 (물은 때의 거리를 보이지 않는다, 감사 #1) |
| `goToPending != null` (닫힘 UI-N2) | 내비에 넣는 중… | 넣는 곳 이름 | 지금 거리만: 같은 곳의 `next` → 앞쪽 후보. 없으면 위치만. 버튼 없음(다시 시도도 두지 않는다) |
| `goToResult.outcome = FAILED` (감사 #3), 그곳이 아직 앞에 있음 | 경유지를 넣지 못했어요 | 장소 이름 | 다시 시도하거나 내비에서 넣어 주세요. 후보 목록이 준비됐는데 그곳이 앞쪽 후보·추천·질문·급해 결과·계획 어디에도 없으면(지나침) 카드와 다시 시도를 두지 않는다 (`failedGoTo`) |
| 정차 중 + 남은 계획 정차 있음 (감사 #4) | 다음 계획 | 첫 정차(지나친 것 뺌) | `plannedDistanceText`(지금 거리, 또는 "계획 때 52km") · 지금 후보가 있을 때만 "N분 뒤 도착" · 정차 분 |
| `next != null` | `goToResult`가 같은 곳이면 결과(넣었어요/길안내 시작/경로 위·진입로/내비에서 찾아 둠), `NEXT_ACCEPTED`만이면 "들르기로 한 곳"(엔진은 '응'을 받은 순간 기록하므로 넣었다고 하지 않는다), `NEXT_REQUESTED_*`면 "요청하신 화장실"·"요청하신 곳", 이유에 `LAST_CHANCE_*`가 있으면 마지막 기회 | 장소 | 거리·시간·위치 |
| 경로 앞 첫 화장실 (목적지까지, 유턴 필요한 곳 제외) | 다음 화장실 | 장소 | 거리. 가는 길에 화면에 보이는 정체(3.5 `displayJams`)가 있으면 칩: 그 앞에서 끝나면 "정체 뒤", 그곳을 지나서도 이어지면 "정체 구간 안"(차가 이미 정체 안이어도 센다) |
| 후보 없음 (감사 #18) | — | `candidatesState`: LOADING_DATA "정차 데이터를 읽는 중이에요", NO_LOCATION "위치를 확인하는 중이에요", READY "앞쪽에 화장실 정보가 없어요" | 급할 때는 아래 버튼을 누르세요 |

칩: 주행 중 2개, 정차 중 3개. 모든 칩은 12자 이하이고(`Labels.shortReason`이 넘치면 짧은 대체 문구), 모르는 코드의 원문은 내보내지 않는다(질환 이름이 샐 수 있다). 엔진 소스의 모든 이유 코드는 문구가 있거나 `NOT_CHIP_CODES`에 있어야 한다(`ReasonCoverageTest`). 맥락 줄이 뜻을 이미 말한 이유(`NEXT_ACCEPTED`, `NEXT_REQUESTED_*`)는 칩으로 되풀이하지 않는다.

**화장실 질문의 맥락 줄** (`restroomQuestionContext`, 감사 #6, 닫힘 UI-6·UI-N5). 엔진은 LAST_CHANCE 종류로 다섯 가지 문장을 낸다(CoachTexts): 정체 전 마지막, 공백 전 마지막, 여유가 거의 없을 때 가까운 곳, "화장실 가고 싶대"의 답, 수유·반려견 요청의 답. 종류만 보고 '마지막 기회'라고 하면 틀린 말이 된다. 위에서부터 먼저 맞는 것:

| 순서 | 조건 | 맥락 줄 |
|---|---|---|
| 1 | `promptReasons`에 `LAST_CHANCE_CONGESTION` | 정체 전 마지막 화장실 |
| 2 | 그 밖의 `LAST_CHANCE_*` (공백, 기준, 여럿이 타서) | 화장실 공백 전 마지막 기회 |
| 3 | 엔진의 마지막 기회 문장 ("정체 전 마지막"·"정체가 있어요" / "동안 화장실이 없어요") | 위 둘과 같다 |
| 4 | `NEXT_REQUESTED_RESTROOM` / `NEXT_REQUESTED_CARE` | 요청하신 화장실 / 요청하신 곳 |
| 5 | `NEXT_NEAREST_RESTROOM`, 또는 문장 "여유가 거의 없어요" | 가까운 화장실 |
| 6 | 문장 "다음 화장실은 …" / "… 쉬어 갈 수 있어요" (이유가 비었을 때) | 요청하신 화장실 / 요청하신 곳 |
| 7 | 그 밖 | 다음 화장실 (마지막 기회라고 하지 않는다) |

3이 4·5보다 먼저인 이유: 게이지가 NOW일 때 엔진은 "정체 전 마지막 화장실이에요"라고 물으면서 이유는 `NEXT_NEAREST_RESTROOM` 하나만 줬다. 말한 문장과 맥락 줄이 어긋나면 안 된다. 문장은 장소 이름에 들어갈 수 있는 낱말("신정체육공원")이 아니라 구절로 가린다.
이유가 비었으면(띄울 때 추천이 다른 곳) 지금 추천(`next`)의 이유로 채우지 않는다: 1초 뒤 추천이 넘어오면 질문 도중에 맥락 줄과 칩이 바뀌었다. 급해 질문만 결과 목록의 이유(영업·방향)를 칩으로 쓴다.

### 3.2 행동 줄 (`driveActions`, 터치 요소 4개 이하)

| 상태 | 왼쪽(Filled) | 오른쪽 |
|---|---|---|
| 서비스 돌고 질문 있음 | `acceptLabel` (들르기/쉬어 갈게요/외기로 전환) | 괜찮아요. `acceptLabel`이 null이면 [확인] 하나 (INFO, 외기 전환 미지원 차의 환기 권유: 감사 #16) |
| 서비스 돌고 경유지 실패, 넣는 중 아님 | 다시 시도 (`GoTo` 하나, 닫힘 UI-3) | 닫기 (`DismissGoToResult`) |
| 정차 중 | 계획 보기 | 탑승자 |
| 주행 중, 그 밖 | 빈 자리 (높이 유지) | |

행동 줄의 내용이 바뀐 뒤 0.6초 동안은 누름을 받지 않는다: 정차 중 [계획 보기]를 누르는 순간 질문이 떠서 같은 자리의 [들르기]가 눌리는 일을 막는다.

좌표 없는 경로 위 시설(TBT)의 질문도 "들르기"다: 코치가 `ON_ROUTE`(진입로로 들어가면 된다)로 답하고 카드가 그것을 보인다. 주행 중에 내비 장소 검색을 여는 것은 코치가 하지 않는다(감사 #3).

[다시 시도]는 `GoTo` 하나만 보낸다. 지난 실패 결과는 코치가 새 들르기를 시작하며 내린다. 예전에는 `DismissGoToResult`를 먼저 보내서 코치의 '실패한 수락 → 다시 시도' 연결이 끊겼고, 경유지는 들어가도 엔진은 수락을 몰랐다.

**들르기 진행** (`goToBusy`, 닫힘 UI-N2). 내비에 넣는 중인지는 코치가 정한다: `CoachUiState.goToPending`이 있는 동안 주행 카드가 "내비에 넣는 중…"을 보이고, 계획 화면의 경유지 버튼과 급해 결과 카드를 모두 막는다(고른 것에는 "넣는 중…", 보이지 않는 곳을 넣는 중이면 "○○를 내비에 넣는 중…" 한 줄). 화면은 결과를 기다리는 시간을 재지 않는다. 예전의 12초 제한은 에뮬레이터에서 19.5초 걸린 성공보다 먼저 '결과 없음'을 띄우고 버튼을 다시 켜서, 그 사이 누른 다른 곳이 두 번째 경유지로 들어갔다. 끝은 코치의 감시 타이머가 보장한다.
화면이 들고 있는 것은 방금 보낸 곳(`ContentState.goToSentId`)뿐이다: 코치가 받았다고 알리기 전의 짧은 틈에 같은 요청을 또 보내지 않게 한다. 코치가 5초 안에 받았다고 알리지 않으면(지난 질문에 한 대답처럼 들르기가 시작되지 않았다) 버튼을 다시 연다. 이 기다림은 코치가 넣는 중일 때는 돌지 않는다. 질문의 [들르기]·[괜찮아요]는 넣는 중에도 받는다(시간이 급한 결정이고, 코치가 차례로 처리한다).

### 3.3 게이지

- 막대 분모 = 게이지를 정한 탑승자의 기준 간격: 개인 간격(`intervalOverrideMin`)이 있으면 그것, 없으면 유형별 사전값.
- 이유 한 줄 우선순위. 화장실: 직접 요청 > 정차 간 상한(카시트·걷기) > 자는 중("7살 아이 자는 중") > 식사 뒤 > 누구 기준(늘 붙는 기본 이유) > 개인 기록. 자는 중·식사 뒤는 숫자가 평소와 다른 이유라 '누구 기준'보다 먼저다(U4·U5). 피로: 직접 말함 > 강한 권고 > 운전 패턴 > 시간대·깨어 있은 시간 > 수면 > 단조로움 > 연속 운전(늘 붙는 기본 이유라 맨 뒤, 감사 #17).

### 3.4 급해 화면 (`urgentView`, 감사 #7·#8)

화면이 요청 번호를 정해(`UrgentRequestIds`, 0이 아닌 고유 값) `Urgent(id)`를 보내고, 코치가 `urgentRequestId`·`urgentState`로 진행을 돌려준다.

| 상황 | 표시 |
|---|---|
| 서비스가 아직 안 뜸 (한 번도 계산을 내지 않음: 차가운 시작, 프로세스 복원) / 돌다가 멈춤 | 준비하고 있어요 / 코치가 멈췄어요 |
| 코치가 우리 번호로 LOCATING / SEARCHING | 위치를 확인하는 중이에요 / 가까운 화장실을 찾는 중이에요 |
| READY (목록 있음) | 결과 (주행 중 2곳 카드, 정차 중 필터 목록) |
| READY 빈 목록, NO_RESULT | 가까운 곳에 열린 화장실이 없어요 + [다시 시도] |
| NO_LOCATION | 위치를 확인할 수 없어요 + [다시 시도] (코치가 정한다. 화면이 8초 뒤 지어내지 않는다) |
| 아직 답이 없음 | 위치를 확인하는 중이에요 → 8초 뒤 "가까운 화장실을 찾지 못했어요 · 위치를 확인하지 못했을 수 있어요" + [다시 시도]. 요청을 받기 전의 코치 상태(번호 0, IDLE)는 응답 없는 코치와 위치가 없어 조용히 끝난 옛 코치가 같아서, 둘 다에 맞는 말로 알린다 |
| 코치가 우리 번호를 IDLE로 끝냄 | 닫는다 (경유지를 넣었다) |
| 닫은 번호의 늦은 결과 | 다시 띄우지 않는다 (`dismissedUrgent`, 최근 8개). 번호는 다시 쓰이지 않으므로 계속 들고 있는다: 일찍 잊으면 코치가 Urgent를 늦게 받아 그 번호로 LOCATING을 알릴 때 닫은 화면이 다시 열린다. 하나만 들고 있으면 '7을 답 전에 닫고 8을 보내고 닫기'에서 7의 늦은 답이 다시 연다. 번호 0을 다시 쓰는 옛 코치만 목록이 지워지면 잊는다 |
| [다시 시도] | 끝난 앞 번호를 닫은 번호에 넣고 새 번호로 보낸다(코치가 아직 앞 번호를 보이는 동안 화면이 그것을 따르지 않는다) |
| 화면이 요청하지 않았는데 코치가 진행 중 (음성 "화장실 급해") | 연다. 우리 요청이 떠 있는데 코치가 다른 번호로 시작했으면 그 번호를 우리 요청으로 삼는다(`adoptUrgentRequest`): 그 요청이 끝나면(IDLE) 닫힌다 |
| 옛 코치: 결과를 보인 뒤 목록이 지워짐 | 닫는다 (음성 '응'으로 경유지를 넣었다. 예전에는 8초가 지나 '찾지 못했어요'로 바뀌었다) |

급해 버튼은 진행 중인 요청이 있으면 새 요청을 보내지 않는다(한 프레임 안의 두 번 누르기). [다시 시도]만 끝난 앞 요청을 정리하고(`DismissUrgent`) 새 번호로 보낸다.

결과 고르기(`pick`, 닫힘 UI-9): 들르기가 진행 중이면 받지 않는다. 코치가 묻고 있는 곳("…안내할까요?")을 고르면 그 질문에 '응'(`Answer(id, true)`)으로 답하고, 다른 곳을 고르거나 질문이 뜨기 전에 고르면 `GoTo` 하나만 보낸다. 고른 것이 그 질문의 답이고 코치가 질문을 거둔다. 예전에는 2순위를 고르면 거절(`Answer(id, false)`)도 함께 보내서 엔진이 거절만 알고 30초 뒤 다시 물었다. 고른 뒤에는 코치가 끝낼 때까지 카드를 막는다(3.2 '들르기 진행'). `goToResult`가 실패면 토스트를 띄우고 다시 고를 수 있고, 성공이면 닫는다.
닫기: 고르지 않고 닫았을 때만 급해 질문에 '아니'로 답한다. 고른 곳을 넣는 중에 닫은 것은 거절이 아니다(들르기는 계속 진행되고 다른 화면의 넣기 버튼도 끝날 때까지 막힌다).
코치가 음성으로 연 요청의 번호는 음수이고 코어마다 겹치지 않는다(닫힘 UI-N4). 화면은 그 번호를 그대로 따르고 저장한다(4장).

급해 화면이 떠 있을 때 다른 질문이 오면(감사 #27): 주행 중에는 터치 요소를 늘리지 않고 한 줄로 질문과 "말로 응·아니 하거나, 닫고 답해 주세요"를 보인다. 정차 중에는 버튼도 같이 둔다.

compact 세로 윈도에서 결과 카드는 `FitColumn`(줄을 빼는 판단은 순수 함수 `fitRows`, `FitColumnTest`)으로 소요 구성 줄 → 배지 줄 순으로 빼고, 이름과 시간은 늘 남긴다. 줄 순서(이름, 시간, 소요 구성, 배지)는 그대로다. 위 설명 줄은 가장 좁은 윈도에서도 한 줄에 들어가게 줄였다(감사 #10).

### 3.5 계획 화면 (감사 #4·#5)

- `livePlan`: 계획 정차를 지금 후보(ahead)에 맞춘다. 같은 id가 후보에 있으면 그 거리·ETA(없으면 평균 속도 + 정체 지연). 없으면 계획 때 거리를 "계획 때 52km"로 밝히고 ETA는 보이지 않는다(계획 때 도착 시간을 지금 값처럼 보이지 않는다). 거리 출처는 `PlannedSource`.
- 지나침: 뒤의 계획 정차가 후보에 있는데 앞의 것이 없으면 지나친 것이다. 또 길안내 중이고 목록이 준비됐는데 경로 위 목록(TBT, 경로 순서대로 빠짐없이 온다)이 그 정차의 계획 때 위치보다 멀리까지 오면서 그 정차가 없으면 지나친 것이다(앞에 있다면 지금 거리는 계획 때 거리보다 짧으므로 목록 안에 있어야 한다). 마지막 하나 남은 정차도 이렇게 빠진다. 반경 후보(길안내 아님)는 빠짐없다고 볼 수 없어 이 추론을 하지 않는다.
- 목적지보다 먼 정차는 뺀다(지금 후보의 거리로만 견준다: 계획 때 거리는 그 뒤 달린 만큼 낡았다). 거리를 전혀 모르는 정차(PLAN_NOW, 도착 0초)는 지금 위치에 "지금"으로 그린다(예전에는 조용히 빠졌다). 번호는 남은 정차 안에서 1부터.
- 타임라인: 지금 → 정차 → 공백 → 정체 → 목적지 (같은 거리일 때). 목적지 뒤 후보는 뺀다. 정체 안이면 남은 부분과 남은 시간만("지금부터 12km", "정체 40분").
- 화면에 보이는 정체(`displayJams`, U1): 내비는 한 정체를 도로 구간마다 쪼개 보내고 100m 안팎의 느린 구간도 정체 항목으로 준다(2026-11-12 판교→부산역: 35항목, 대부분 30~200m·지나는 데 10~40초). 그대로 그리자 타임라인에 "정체 0분 · 대왕판교로 · 400m 앞부터 100m"가 여덟 줄 이어지고 띠에 "정체 0분"이 붙었다. 그래서
  - 남은 부분만 보고, 똑같은 항목이 두 번 오면 한 번만 센다.
  - 가까운 순으로 놓고, 앞 묶음의 끝에서 1km 안에 시작하는 항목은 같은 정체로 묶는다(엔진 `GapAnalyzer.JAM_JOIN_M`과 같은 값, 시험이 지킨다). 범위가 겹치는 항목(내비 offset은 10km 밖에서 1km 단위)은 앞 항목 끝에 이어 놓는다. 길이와 지나는 시간은 항목의 합, 이름은 가장 긴 항목의 것.
  - 지나는 데 5분 이상인 묶음만 보인다. 그날의 35항목은 다섯 덩어리로 묶이고 가장 긴 것도 3분 40초라 하나도 그리지 않는다.
  - 경로 띠(구간과 이름표), 타임라인, 추천 카드의 "정체 뒤"·"정체 구간 안" 칩, 공백의 '정체를 지나는 동안' 표시가 같은 함수를 쓴다. 도착 시간 계산(`travelSeconds`, 공백의 분)은 원래 항목을 쓴다: 짧은 느린 구간도 도착 시간에는 들어간다.
  - 이름표는 1분 미만도 1분으로 쓴다: "정체 0분"은 없다.
- 화장실 공백: 차 → 첫 기회도 본다(정체 속에서 가장 중요한 공백, 감사 #20). 경로 거리를 모르는 화장실 후보가 있으면 순서를 알 수 없어 공백을 말하지 않는다. 마지막 기회 뒤는 모른다(후보 목록이 일부라서).
- 요약 첫 줄은 엔진 메모 코드를 따른다:

| 메모·상태 | 첫 줄 |
|---|---|
| 계획 없음 | 아직 계획이 없어요 (+ 후보 준비 전이면 그 이유) |
| PLAN_NOW | 지금 쉬어야 해요 (+ "정차 N번 · 쉬는 시간") |
| 남은 정차 있음 | 정차 N번 · 쉬는 시간 M분 |
| 정차가 있었지만 남은 것 없음 | 지금 경로에 남은 계획 정차가 없어요 · 다시 계산해 주세요 |
| PLAN_NO_CANDIDATES | 정차 후보를 찾지 못했어요 |
| PLAN_NONE_NEEDED + (PLAN_PARTIAL 또는 PLAN_NO_DESTINATION) | 앞 후보까지는 쉬지 않아도 돼요 |
| PLAN_NONE_NEEDED | 목적지까지 쉬지 않아도 돼요 |
| 그 밖 | 쉴 곳을 정하지 못했어요 |

지나친(또는 목적지 뒤로 빠진) 정차를 이름으로 가리키는 메모(PLAN_NOW·PLAN_MEAL·PLAN_FUEL)는 뺀다: "안성휴게소에 바로 들르세요"가 지나친 뒤에도 남았다. 첫 줄로 쓴 메모는 아래 목록에서 빼고, 나머지 메모는 문장 그대로 보인다. PLAN_NOW·PLAN_GAP·PLAN_FUEL_SHORT는 강조(경고)로 보인다. 경유지 버튼은 들르기가 진행 중인 동안 모두 막는다(감사 #24, 3.2 '들르기 진행'): 넣는 곳의 버튼은 "넣는 중…", 다른 곳(질문에 '응'한 곳)을 넣는 중이면 요약 카드에 "○○를 내비에 넣는 중…" 한 줄. 화장실 기회가 없을 때 문구: 준비 전이면 그 이유, 후보는 있는데 경로 거리를 아직 모르면 "경로 거리를 확인하는 중이에요", 정말 없으면 "이 경로에는 화장실 기회 정보가 없어요". 남은 거리가 100m 아래면 "목적지에 거의 다 왔어요".

'계획을 세우는 중'은 코치가 `planGeneration`(새로 세울 때마다 오르는 번호, 2026-11-12 계약 추가)을 쓰면 번호가 오를 때 풀고, 쓰지 않으면(옛 코치) 새 계획 객체가 올 때 푼다. 번호를 쓰는 코치는 지나친 정차를 빼며 같은 계획을 매초 다시 낼 수 있어서 객체로 보면 바로 풀린다.

계획 갱신은 계획 화면이 보이는 동안만 (`ContentActions.planVisibility`, 리뷰 R-F12). 한 번 요청한 계획을 화면을 떠난 뒤에도 코치가 30초마다 다시 세웠다(10분에 30번). 화면이 `MakePlan`을 보냈으면(`ContentState.planWanted`) 계획 화면이 안 보이게 될 때 `ClosePlan`을 한 번 보낸다: 다른 화면으로 가거나, 주행을 시작해 주행 화면만 그릴 때. 계획 화면을 다시 열었는데 계획이 이미 있으면 `MakePlan`을 다시 보내 이어서 고쳐 세우게 한다(지나친 정차를 빼는 것은 코치의 일이다, 감사 #4). 계획이 없으면 [계획 세우기]를 눌러야 세운다.

### 3.6 마이크 (`micModel`, 감사 #12·#25)

| 음성 상태 | 표시 | 누르면 |
|---|---|---|
| LISTENING | "듣는 중" + 고정 링 | `StopVoice` |
| SPEAKING / THINKING | "말하는 중" / "생각하는 중" | `StopVoice` |
| IDLE·UNAVAILABLE, 인식 불가(`sttAvailable = false`) | 사선 마이크, "듣기 불가"(말하기는 됨) / "음성 불가" | 토스트로 이유 |
| IDLE·UNAVAILABLE, 인식 됨, 말하기 꺼짐(설정 또는 TTS 연결 실패) | "음성 안내 꺼짐" | `Listen` (듣기는 된다: 설정의 '음성 안내'는 말하기만 끈다) |
| IDLE·UNAVAILABLE, 둘 다 됨 | (글자 없음) | `Listen` |

음성 상태 UNAVAILABLE만으로는 막지 않는다(닫힘 UI-12): VoiceAgent는 TTS만 안 붙어도 UNAVAILABLE을 올리는데 코어는 STT만 되면 듣는다. 예전에는 그 조합에서 사선 마이크를 보이고 `Listen`을 보내지 않았다. 막는 기준은 `sttAvailable` 하나다.

주행 중 마이크는 100dp(앱의 주행 중 터치 최소).

탑승자 화면의 [말로 설정] (`describeModel`): 코치가 "누가 함께 타나요?"라고 묻고(TTS) 듣는다(STT). 음성 안내가 켜져 있고 둘 다 될 때만 `DescribeOccupants`를 보낸다. 안 되면 버튼이 사선 마이크로 바뀌고 아래에 이유를 보이며, 눌러도 토스트로 이유를 알린다(예전에는 조용히 아무 일도 없었다). 이유: 인식 불가 "지금은 음성 인식을 쓸 수 없어요" > 설정 꺼짐 "설정에서 음성 안내를 켜면 말로 설정할 수 있어요" > TTS 연결 실패 "지금은 음성 안내를 쓸 수 없어요". 묻거나 듣거나 해석하는 중에 누르면 멈춘다(`StopVoice`, 마이크와 같은 규칙).

### 3.7 설정

외기 전환 스위치는 `cabinSupported`일 때만 켤 수 있고, 아니면 "이 차에서는 쓸 수 없어요"(감사 #16). 이미 켜져 있으면(예전 설정) 끌 수는 있다(`freshAirToggle`): 꺼진 채 잠기면 옛 서비스가 계속 외기로 바꾼다. 설정 화면은 상태 전체가 아니라 `SettingsStatus`와 탑승자만 받는다(매초 다시 그리지 않게, S3).

### 3.8 탑승자 화면 (U3·U4)

- 행 둘째 줄은 간격을 말로 쓴다(`occupantSubtitle`): "어른 · 평소 2시간 30분마다", "평소 1시간 45분마다 · 자는 중". 예전에는 "150분마다"였다. 요약도 같다(`occupantsSummary`): "지금 기준: 1시간 45분마다 화장실". 기준은 지금 화장실 게이지를 정하는 탑승자(`Gauge.drivenBy`), 모르면 간격이 가장 짧은 사람.
- **평소 간격**: 행마다 [평소 간격 · 자동 v] 버튼. 누르면 그 행 아래에 선택지 여섯 개(자동 / 1시간 / 1시간 30분 / 2시간 / 2시간 30분 / 3시간)가 펼쳐지고, 고르면 `Occupant.intervalOverrideMin`을 바꿔 `SetOccupants`로 보낸 뒤 접는다(한 번에 한 사람만 펼친다). 자동은 유형 기본값(null)이고, 선택지 아래에 "자동은 어른 기본값(2시간 30분)이에요"라고 알린다. 본인이 아는 평소 간격은 엔진이 유형별 사전값 대신 그 사람의 사전 중앙값으로 쓰고 설정과 함께 저장된다. 영아(기저귀)와 반려견에는 두지 않는다. 건강 관련 유형은 동의 규칙 그대로다(동의가 있어야 목록에 있을 수 있다). 선택지에 없는 값이 정해져 있으면 버튼에 그 값을 보이고 어느 선택지도 켜지지 않는다.
  선택지를 팝업이 아니라 행 아래에 펼치는 이유: 스크롤되는 목록 안에서 팝업 위치를 맞출 필요가 없고 CCP 포커스 순서가 화면 순서 그대로다.
- **자는 중**: 아이(CHILD) 행의 토글. 누르면 `SetAsleep(id, 켬/끔)`을 보내고, 켜짐 표시는 코치 상태(`asleepOccupantIds`)를 따른다(화면이 먼저 켜 보이지 않는다). 켜져 있으면 "밤에 자는 동안은 화장실 여유를 조금 더 둬요"를 보인다. 엔진은 밤(20~07시)에만 여유를 더 두고, 깼다고 하거나 화장실에 들르면 코치가 표시를 푼다. 게이지 이유 한 줄은 "7살 아이 자는 중"(3.3).
- 동의 철회(`onHealthConsentTap`, 감사 #23): 철회 확인은 [확인]을 누르는 순간의 목록에서 건강 유형만 뺀다. 팝업을 연 때의 목록으로 덮어쓰면 그 사이 음성으로 더한 탑승자가 사라진다. Composable 밖의 함수라 `HealthConsentTest`가 지킨다.

### 3.9 코치 알림 (리뷰 R-F4)

코치가 말 대신 화면에 보이라고 올린 문장(`CoachUiState.notice`: 음성 안내를 껐을 때 "얼마나 남았어"의 대답, 못 알아들었을 때 등)을 토스트와 같은 모양으로 띄운다. 띄우고 내리는 때는 코치가 정한다(몇 초 뒤 지운다): 화면은 시간을 재지 않고 값이 있는 동안 보인다. 주행 화면과 급해 화면에서는 머리 아래, 정차 화면에서는 아래 가운데이고 토스트와 겹치지 않게 위아래로 쌓는다. 터치는 받지 않는다. 주행 중(모를 때 포함)에는 `noticeText`가 주행 제한의 글자 수로 자르고, 길어도 3줄까지다.

## 4. 진행 중 상태와 액티비티 재생성 (감사 #14)

`ContentState`(화면, 계획 계산 중과 그때의 계획 번호, 계획 요청 여부, 급해 요청·닫은 번호들·결과를 본 요청·답 기다림 초과, 고른 곳, 방금 보낸 들르기, 이미 알린 결과 시각)는 `rememberSaveable`의 Saver로 저장한다. 들르기가 진행 중인지는 코치 상태(`goToPending`, CoachStore에 있어 액티비티와 무관하다)가 정하므로, 창 분할·크기 변경·야간 모드로 액티비티가 다시 만들어져도 넣기 버튼이 풀리지 않는다.

없는 번호(null)는 빈 LongArray, 있는 번호는 한 칸짜리 LongArray로 저장한다(닫힘 UI-N4). 예전에는 '번호는 0 이상'이라 보고 null을 -1로 적어서, 코치가 음성으로 연 요청(음수 번호)을 따르던 화면이 다시 만들어지면 번호를 잃었다.

`MainActivity`:
- 런처라 exported다. 실행 인텐트에서는 `LaunchExtras.pick`이 고른 값만 서비스로 넘긴다(닫힘 UI-N1): 시연 전환 `demo`(시연 이름이 아니면 "off"로 바꾼다. 화면 시연 패널과 같은 일만 한다)는 어느 빌드에서나, 개발용 점검 옵션(`selftest`·`route`·`verify`)은 debug 빌드에서만. 받은 extras 묶음을 통째로 넘기지 않는다. 예전에는 제출용 빌드에서도 `am start … --ez selftest true`가 운전석 등받이·창문·실내등을 움직였다.
- 서비스는 권한과 관계없이 화면이 보일 때 시작한다(이미 돌면 다시 부르지 않음). 서비스가 멈췄다가 화면으로 돌아오면 다시 시작한다.
- 실행 중 권한(차속·위치)은 정차 중임을 실제로 읽었을 때 한 번만 묻는다(`PermissionPolicy`). 주행 중(모를 때 포함)에는 시스템 권한 창을 띄우지 않고 정차할 때까지 기다린다. 다시 만들어져도 다시 묻지 않는다.
- 빠진 권한이 새로 허용되면(권한 창 결과, 또는 설정 앱에서 허용하고 돌아옴) `PermissionsChanged`를 보낸다.

### 4.1 권한 (닫힘 UI-N1)

제출용(release) Manifest에는 제품이 쓰는 권한만 둔다(앱 보안 가이드: 필요한 권한만 요청). 개발용 점검(KitSelfTest)만 쓰는 권한은 `app/src/debug/AndroidManifest.xml`에 있어 debug 빌드에만 들어간다. `ReleaseSurfaceTest`가 두 목록을 지킨다(권한을 더하려면 그 시험의 표에 쓰는 곳을 적는다).

| 권한 | 쓰는 곳 | 어디 |
|---|---|---|
| `pleos.car.permission.CAR_DOORS` | 문 열림 → 화장실 정차 판정 (`CarSignals` → `VehicleKit.doorPosition`) | main |
| `pleos.car.permission.CAR_LIGHTS` | 방향지시등 → 운전 패턴, 묻는 때 (`VehicleKit.turnSignal`) | main |
| `pleos.car.permission.CAR_CLIMATE` | 내기순환·공조 전원·설정 온도 읽기 → 환기 권유 (`KitCabin`, `CarSignals`) | main |
| `pleos.car.permission.CONTROL_CAR_CLIMATE` | 외기 전환 (`KitCabin.freshAir`/`recirculate`): 제품이 차를 움직이는 유일한 곳 | main |
| `pleos.car.permission.CAR_DRIVE` | 조향각·브레이크·크루즈 → 운전 패턴, 단조로운 주행 (`CarSignals`) | main |
| `pleos.car.permission.NAVI_ROUTE` | 길안내 상태·TBT·정체·목적지 읽기 (`NaviTracker` → `NaviKit`) | main |
| `pleos.car.permission.NAVI_ROUTE_SEARCH` | 경유지 추가·길안내 시작·requestTrip (`KitNaviPort`) | main |
| `pleos.car.permission.TTS_SERVICE` / `STT_SERVICE` | 질문·안내 음성, 응·아니와 말로 한 요청 (`VoiceAgent`) | main |
| `pleos.car.permission.FUSED_LOCATION` | 지금 위치 → 앞쪽 후보, 급해 (`LocationKit`). 실행 중 요청 | main |
| `pleos.car.permission.LLM_SERVICE` | 규칙으로 못 알아들은 말 해석, ON_DEVICE (`PleosLlmInterpreter`) | main |
| `android.car.permission.CAR_POWERTRAIN` | 기어 → 정차·주행 판정 (`CarState`) | main |
| `android.car.permission.CAR_SPEED` | 차속 → 주행 거리, 운전 패턴 (`CarState`). 실행 중 요청 | main |
| `android.permission.FOREGROUND_SERVICE`, `…_SPECIAL_USE` | 코치 포그라운드 서비스 (pleos-kit Manifest에서 합쳐진다) | pleos-kit |
| `pleos.car.permission.CAR_SEATS`, `CONTROL_CAR_SEATS` | 좌석 점유 조회, 등받이 이동 (KitSelfTest만) | debug |
| `pleos.car.permission.CONTROL_CAR_LIGHTS` | 실내등·앰비언트 라이트 제어 (KitSelfTest만) | debug |
| `pleos.car.permission.CAR_WINDOWS`, `CONTROL_CAR_WINDOWS` | 창문 위치 조회·이동 (KitSelfTest만) | debug |
| `pleos.car.permission.CAR_ENERGY` | 배터리 잔량 조회 (KitSelfTest만. 엔진의 `rangeKm`은 아직 채우지 않는다) | debug |

SDK와 라이브러리의 Manifest에서 합쳐져 APK에 들어가는 것(앱 Manifest에는 없다. 2026-11-12 release 빌드의 합쳐진 Manifest로 확인):

| 권한·컴포넌트 | 어디서 오나 |
|---|---|
| `android.permission.INTERNET` | Pleos TextToSpeech SDK 3.3.1의 Manifest |
| `ai.pleos.playground.service.vehicle.VEHICLE_BINDING` | Pleos Vehicle SDK 2.3.2 (차량 서비스 바인딩) |
| `ai.pleos.playground.service.location.fused.FUSED_LOCATION_BINDING` | Pleos FusedLocation SDK 1.1.3 |
| `dev.shimpyo.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx.core (앱 안에서만 쓰는 서명 권한) |
| 내보낸 리시버 `androidx.profileinstaller.ProfileInstallReceiver` (`android.permission.DUMP` 필요) | androidx.profileinstaller (Compose가 끌어온다) |
| debug만: 내보낸 `androidx.compose.ui.tooling.PreviewActivity` | compose ui-tooling (`debugImplementation`) |

`android.permission.INTERNET`은 앱 Manifest에서 뺐다: 앱·pleos-kit·엔진 어디에도 소켓을 여는 코드가 없다(경로 계산은 내비 앱이, 음성·LLM은 ON_DEVICE로 시스템 서비스가 한다). 그래도 TextToSpeech SDK가 자기 Manifest에 선언해 두어서 APK의 권한 목록에는 그대로 남는다. 그래서 이 변경으로 기기 동작은 달라지지 않는다. 앱 Manifest는 앱이 쓰는 것만 적고, SDK가 쓰는 것은 SDK Manifest가 가져온다. 앱이 외부 API를 쓰게 되면 그때 앱 Manifest에도 적는다.

## 5. CCP(로터리) (감사 #13)

- 기본 포커스(`driveFocus`): 주행 화면은 질문(또는 경유지 실패) 중이면 왼쪽 버튼, 아니면 급해. 질문이 끝나 버튼이 사라지면 급해로 옮긴다(`RequestFocusOnce`의 키가 바뀜). 급해 결과는 1순위, 안내 화면은 [다시 시도]. 팝업은 [취소]에 포커스를 주고 포커스를 팝업 안에 가둔다(`focusProperties { exit = Cancel }`). 뒤 덮개는 포커스를 받지 않는다.
- 터치 모드에서는 누를 수 있는 요소가 포커스를 받지 않으므로 터치 사용자에게 링이 보이지 않는다.
- 탑승자 화면의 평소 간격 선택지는 팝업이 아니라 행 아래에 펼친다: 포커스 순서가 화면 순서 그대로다(버튼 → 선택지 → 다음 행).
- Manifest에 `com.hmg.rotary.ROTARY_SUPPORTED=false`를 선언한다(`ReleaseSurfaceTest`가 지킨다). 사용자 설치 앱은 CCP가 선택이고, 미지원이면 필수 조작이 터치로 끝나야 한다(쉼표는 모두 터치로 된다). CCP UX 가이드 심사 항목 전체를 실제 CCP로 확인하지 못했으므로 지원한다고 선언하지 않는다. 최상위 화면에서 Back은 `moveTaskToBack(true)`(필수 정책).

## 6. Compose 안정성 (S1~S4)

- `compose-stability.conf`에는 화면 매개변수로 들어오는 불변 타입만 하나씩 적는다(엔진 Api.kt 출력 타입과 코치 상태 타입). 예전 `dev.shimpyo.engine.*`는 가변 `CoachEngine`·`RouteContext`까지 안정으로 보였다. `StabilityConfigTest`가 지킨다: 적힌 쉼표 타입은 실제로 있고(이름을 바꾸면 조용히 효과가 사라진다) 필드가 모두 final이어야 하며, 묶음(`*`)으로 적지 않는다. 화면 쪽 모델(`GoToBusy`, `DescribeModel`, `OccupantsSummary` 등)은 코드에서 `@Immutable`로 표시한다.
- 화면에 넘기는 람다는 `ContentActions`(한 화면 동안 같은 객체)만 잡는다. 함수 참조(`::pick`)는 쓰지 않는다. 헤더 `trailing` 람다는 `MicModel`·계획 계산 중 여부만 잡는다(매초 바뀌는 상태 전체를 잡으면 머리가 매초 다시 그려진다).
- 확인: Compose 컴파일러 보고서(빌드 파일을 바꾸지 않고 `--init-script`로 `composeCompiler.reportsDestination`을 켬). 2026-11-12 기준 재시작 가능한 Composable 111개 모두 건너뛰기 가능, 런타임 안정성 매개변수는 `DriveActions`·`List<TimelineRow>` 두 개였고 `@Immutable`로 고쳤다. 남은 불안정 매개변수는 `RequestFocusOnce(key: Any?)` 하나(키라서 의도한 것).

## 7. 계약 필드가 비어 있을 때 (기본값)

화면은 계약(`CoachState.kt`)만 보고 짰다. 코치 계층(CoachCore)이 모든 필드를 채운다. 아래는 필드가 기본값일 때(서비스가 뜨기 전, 코치가 아직 그 기능을 채우지 않을 때) 화면이 보이는 것이다.

| 필드 | 기본값일 때 보이는 것 |
|---|---|
| `urgentRequestId`·`urgentState` | 번호 0이면 옛 방식(urgent 목록이 결과)으로 동작한다. 이 갈래만 화면에 남겨 두었다(`UrgentModel.legacy`) |
| `promptReasons` | 비어 있으면 문장으로 맥락을 고른다(3.1). 지금 추천(`next`)의 이유로 채우지 않는다: 코어는 띄울 때 추천이 다른 곳이면 이유를 비워 둔다 |
| `goToPending` (기본 null) | 넣기 버튼을 막지 않는다. 화면이 방금 보낸 것만 5초 동안 막는다(3.2) |
| `notice` (기본 null) | 아무것도 띄우지 않는다 |
| `asleepOccupantIds` (기본 빈 집합) | '자는 중'이 켜지지 않는다(눌러도 코치가 받아야 켜진다) |
| `goToResult` | 오지 않으므로 '들르기'를 받은 곳은 "들르기로 한 곳"으로, 결과는 TTS로만 알 수 있다 |
| `candidatesState` (기본 LOADING_DATA) | 후보가 정말 없을 때도 "정차 데이터를 읽는 중이에요"로 보인다(틀려도 "없다"고 하지는 않는다) |
| `cabinSupported` (기본 false) | 외기 전환 스위치가 꺼져 있고, 환기 권유에는 [확인]만 보인다 |
| `ttsAvailable`·`sttAvailable` (기본 false) | 마이크가 "음성 불가"로 보이고 누르면 이유만 알린다 |
| `PermissionsChanged` | 옛 서비스는 무시한다: 권한을 나중에 허용하면 서비스를 다시 시작해야 차속·위치가 들어온다 |
| `planGeneration` | 0이면 계획 객체가 바뀔 때 '세우는 중'을 푼다 |

## 8. 시험 (app/src/test/java/dev/shimpyo/app/ui/, 노트북 `gradle.ps1 :app:testDebugUnitTest`)

| 상황 | 시험 |
|---|---|
| 모르면 제한, 화면 구독만 믿음, 기능별 비트·최대 글자 수 | `RestrictionTest.unknownIsRestricted`, `screenSubscriptionIsTheOnlySource`, `perFeatureLimitsApplyOnlyWhenFlagged` |
| 권한은 정차 중 한 번, 새로 허용되면 알림 | `RestrictionTest.permissionsAskedOnceOnlyWhenKnownParked` |
| 질문 카드의 지금 거리, 지나친 곳의 물은 때 거리 금지 | `DriveModelsTest.questionCardShowsTheLiveDistanceNotTheOneAtAskTime`, `passedPromptStopDoesNotShowAskTimeDistance` |
| 질문 이유 유지, 이유 없을 때의 맥락 | `gapPromptKeepsContextWhenRecommendationMovesOn`, `lastChanceWithoutReasonsIsNeverLabelledNearestRestroom` |
| 짧은 질문, 설명 줄, 문구 길이 제한 | `stoplessPromptTitleIsCondensed`, `shortQuestionAndLead`, `cardTextRespectsRestrictedStringLength` |
| 들르기 결과·실패 카드·TBT·외기 미지원 | `acceptedStopIsNotClaimedAddedUntilTheNaviConfirms`, `failedWaypointShowsRetryCardNotAdded`, `tbtOnlyStopAcceptAnswersOnRouteNotWaypoint`, `freshAirCannotBeAcceptedWhenCabinUnsupported` |
| 빈 상태·서비스 멈춤 | `emptyCardFollowsCandidatesStateAndNeverClaimsNoRestroomBeforeReady`, `stoppedServiceDoesNotLeaveALiveLookingCardOrButtons`, `nearestAheadIgnoresStopsBeyondTheDestination` |
| 정차 중 다음 계획 | `parkedPlanCardUsesLiveDistanceAndSkipsPassedStops` |
| 게이지·이유 | `bodyClockReasonOutranksDrivingMinutes`, `strongAndMonotonyReasonsAreNotSilentlyDropped`, `restroomCapExplainsTheNumberWhenItBinds`, `gaugeBarUsesPersonalIntervalWhenSet` |
| 칩 길이·질환 이름·모든 엔진 코드 | `LabelsTest.*`, `ReasonCoverageTest` |
| 급해 상태·늦은 결과(답 전에 닫기 포함)·옛 코치 | `UrgentModelTest.*` (`closingBeforeTheCoachAcknowledgesDoesNotReopenOnTheLateAck` 등) |
| 카드가 잘리지 않음 (덜 중요한 줄부터 뺌) | `FitColumnTest.*` |
| 독립 재검토: 지나친 실패 카드, 유턴 곳·정체 칩, 계획 때 값 표시, 카드가 실제로 보이는 결과 | `DriveModelsTest.failedWaypointForAStopBehindTheCarIsNotOfferedAgain`, `nearestAheadSkipsUTurnStopsAndJamChipCoversTheJamWeAreIn`, `parkedPlanCardForStopWithoutLiveAnchorShowsNoCurrentEta`, `cardShowsGoToMatchesWhatIsActuallyOnTheCard` |
| 독립 재검토: 급해 음성 수락·다시 시도·이어받기 | `UrgentModelTest.legacyVoiceAcceptClosesInsteadOfClaimingFailure`, `retryHidesTheReplacedRequestAndClosedIdsAllStayHidden`, `coachInitiatedRequestTakingOverOursIsAdopted` |
| 독립 재검토: 마지막 계획 정차 지나침, 거리 출처, 지나친 정차 메모, 경로 거리 확인 중 | `RouteModelTest.lastPlannedStopPassedWhenTheRouteListReachesBeyondIt`, `plannedDistanceTextNamesItsSource`, `PlanTimelineTest.notesNamingPassedStopsAreDropped`, `candidatesWithoutRouteDistanceAreNotCalledNoInformation` |
| 외기 전환 설정 | `FreshAirToggleTest` |
| 계획 타임라인·요약 | `PlanTimelineTest.*`, `RouteModelTest.*` |
| 마이크 | `MicModelTest.*` |
| 재생성 뒤 진행 중 상태, 결과 토스트 | `ContentStateTest.*` |
| 탑승자 나이 이름, 동의 철회 | `OccupantEditTest.*` |
| 화면에 보이는 정체 (U1): 기기에서 받은 35항목, 묶기·5분 기준·겹친 항목·정체 안, 띠·타임라인·칩·공백, 엔진과 같은 묶는 거리 | `CongestionDisplayTest.*` (`theDeviceLoggedSlowPatchesAreNotDrawnAsJams`, `noLabelEverSaysZeroMinutes`, `itemsWithinOneKilometreBecomeOneJamNamedAfterTheLongest`, `theJoinDistanceIsTheEnginesOwn` 등 10개) |
| 화장실 질문의 맥락 줄 (닫힘 UI-6·UI-N5) | `PromptContextTest.*` (`aJamLastChanceAskedWhileTheGaugeIsNowKeepsItsJamLabel`, `emptyReasonsDoNotFollowTheLiveRecommendationInTheMiddleOfAQuestion`, `anAnswerToARestroomRequestIsNotCalledALastChanceBeforeAGap`, `aCareStopAnswerIsNotCalledALastChance` 등 8개) |
| 내비에 넣는 중 (닫힘 UI-N2): 카드·버튼, 코치가 정하는 진행, 화면에 시간 제한 없음 | `DriveModelsTest.aGoToInFlightShowsProgressAndNoRetryButtons`, `aPendingStopOtherThanTheRecommendationShowsNoStaleDistance`, `ContentStateTest.theCoachDecidesHowLongAGoToIsBusy`, `theScreenHasNoGoToResultTimeoutOfItsOwn`, `UrgentModelTest.aGoToForAStopThatIsNotOnTheListIsExplainedAboveIt` |
| 화면이 보내는 명령 (닫힘 UI-3·UI-9, 리뷰 R-F12, 감사 #24): 다시 시도는 GoTo만, 두 번 누르기, 급해 고르기·닫기, 계획 닫기·잇기, 말로 설정, 자는 중 | `ContentActionsTest.*` (`retryFromTheFailedCardSendsOnlyTheGoTo`, `aSecondTapWhileAGoToIsBusySendsNothing`, `pickingAnotherResultNeverDeclinesTheCoachsQuestion`, `closingTheUrgentScreenDeclinesTheQuestionOnlyWhenNothingWasPicked`, `leavingThePlanScreenClosesThePlanOnceAndComingBackResumesIt` 등 9개) |
| 음수 요청 번호 (닫힘 UI-N4) | `ContentStateTest.aCoreOpenedUrgentRequestSurvivesRecreation`, `UrgentModelTest.coreOpenedNegativeIdsAreFollowedAndStayClosedOnceDismissed` |
| 마이크: TTS만 안 될 때 듣기, [말로 설정]의 이유 (닫힘 UI-12) | `MicModelTest.ttsDownButSttUpStillListens`, `describeByVoiceSaysWhyItCannotBeUsed` |
| 평소 간격·자는 중·간격을 말로 (U3·U4) | `OccupantEditTest.usualIntervalChoicesSetTheOverrideAndAutoClearsIt`, `infantsAndDogsHaveNoUsualIntervalChoice`, `theSelectorShowsWhatIsSetAndHighlightsIt`, `theRowAndTheSummarySayTheIntervalInWords`, `onlyChildrenCanBeMarkedAsleep` |
| 새 이유 코드의 문구와 게이지 이유 순서 (U5) | `LabelsTest.contextFactorsAndRequestedStopsHaveLabels`, `DriveModelsTest.aSleepingChildAndAMealExplainTheRestroomGauge` |
| 동의 철회는 확인 순간의 목록으로 (감사 #23) | `HealthConsentTest.*` |
| 코치 알림의 글자 수 (리뷰 R-F4) | `RestrictionTest.aCoachNoticeIsCutToTheRestrictedStringLengthOnlyWhileDriving` |
| 제출용 빌드가 내놓는 것 (닫힘 UI-N1): 권한 목록, debug 전용 권한, 내보낸 컴포넌트, CCP 선언, 실행 인텐트에서 넘기는 값 | `ReleaseSurfaceTest.*` |
| 기본 포커스, 급해 화면 위의 다른 질문, 안정성 설정 (감사 #13·#27·S4: 고친 코드에 시험이 없던 것) | `DriveModelsTest.defaultFocusGoesToTheAnswerButtonAndBackToUrgentWhenTheQuestionEnds`, `UrgentModelTest.anotherQuestionIsShownOnTheUrgentScreenButNotItsOwn`, `StabilityConfigTest` |

## 9. 기기에서 확인할 것 (통합 실행)

에뮬레이터 + Virtual Console에서 확인한다(화면 작업은 JVM 시험과 빌드만 했다). 12번부터는 2026-11-12 수정분이다.

1. 앱을 켜자마자(서비스가 뜨기 전) 기어 D + 40km/h: 메뉴 드롭다운·제목 길게 누르기가 처음부터 없어야 한다. P로 돌리면 바로 돌아온다. 설정 화면에서 D로 바꾸면 주행 화면 + 토스트.
2. 정차 중 설정 화면에서 창 분할·크기 변경: 설정 화면에 남아야 한다(주행 화면으로 튕기지 않음). 권한 창이 다시 뜨지 않아야 한다.
3. 처음 설치(권한 없음)로 D 상태에서 실행: 권한 창이 뜨지 않고, P로 바꾸면 그때 뜬다.
4. 급해 → 찾는 중에 [닫기] → 결과가 다시 나타나지 않고 TTS가 묻지 않아야 한다(코치 계층 이후. 옛 서비스는 번호를 몰라 늦은 목록이 다시 뜰 수 있다).
5. 급해 결과 고르기 연타: 경유지 요청이 한 번만 가야 한다(logcat 내비 요청 수).
6. 급해 결과 카드: 12.9" 세로 628×855, W 800×810, WF 648×800 창에서 잘린 줄이 없어야 한다(소요 구성·배지 줄이 빠지는 것은 정상).
7. 탑승자 화면 12.9" 세로: 아이 행의 이름이 보이고 나이 스테퍼가 아랫줄에 있어야 한다. 나이를 바꾸면 "7살 아이" → "8살 아이".
8. 마이크: 말하는 중에 누르면 멈춘다. 설정에서 음성 안내를 끄면 "음성 안내 꺼짐"이 보이고 눌러서 말할 수 있다.
9. 기록 지우기 팝업: 다른 버튼을 눌러도 팝업이 닫히지 않고(뒤 덮개 탭만 닫음), 키보드 화살표(에뮬레이터)로 포커스가 팝업 밖으로 나가지 않는다.
10. 주행 화면 가로 1700: 계획 정차 번호가 띠에서 읽히는지(28).
11. 계획 화면: 계획 뒤 50km 달린 다음 첫 정차 거리가 줄어 있는지, 지나친 정차가 빠지는지.
12. 실제 경로(판교→부산역) 계획 화면과 가로 주행 화면: "정체 0분"·100m짜리 정체 줄이 없어야 한다. 시연 holiday에서는 "정체 1시간" 한 줄과 띠의 한 구간이 그대로 보여야 한다. 시연 패널의 '경로 앞' 줄에서 그린 구간 수와 내비 항목 수를 견준다.
13. 들르기: 질문에 [들르기] → 카드가 "내비에 넣는 중…"으로 바뀌고 내비가 확인할 때(에뮬레이터 약 20초)까지 그대로였다가 "경유지로 넣었어요". 그동안 '결과 없음' 토스트가 뜨지 않아야 한다. 계획 화면 [경유지로]와 급해 결과 카드도 그동안 꺼져 있고(고른 것은 "넣는 중…"), 급해 화면 [닫기]는 눌린다.
14. 경유지 실패([다시 시도]): 강제로 실패시킨 뒤 다시 시도 → logcat에 `DismissGoToResult` 없이 `GoTo` 한 번, 성공 뒤 추천 이유가 `NEXT_ACCEPTED`.
15. 급해 결과: 1순위를 질문이 뜨기 전(1초 안)에 누르기, 2순위 고르기, 고른 뒤 넣는 중에 [닫기] → 어느 경우에도 같은 곳을 다시 묻거나 30초 뒤 다른 곳을 묻지 않아야 한다(코어 수정과 함께).
16. "아이가 화장실 가고 싶대": 카드 맥락 줄이 "요청하신 화장실"(엔진 수정 뒤 코드로, 그 전에는 문장으로). 정체 전 마지막 휴게소 앞에서 말하면 "정체 전 마지막 화장실".
17. 탑승자 화면: [평소 간격 · 자동] → 선택지 여섯 개가 행 아래에 펼쳐지고, 2시간을 고르면 행이 "평소 2시간마다", 요약이 "지금 기준: …"로 바뀌며 앱을 다시 켜도 남는다. 영아·반려견 행에는 버튼이 없다. 628×855 창에서 선택지가 잘리지 않고 줄바꿈된다. 아이 행 [자는 중]을 켜면 토글이 켜지고(코어 수정 뒤) 밤 시연에서 게이지 이유가 "… 자는 중".
18. 음성 안내를 끄고 마이크로 "얼마나 남았어": 대답이 머리 아래에 몇 초 떴다 사라진다(코어 수정 뒤). 주행 중 급해 버튼을 가리지 않는다. 탑승자 화면 [말로 설정]은 사선 마이크 + 이유 한 줄, 누르면 토스트.
19. 계획 화면을 열어 둔 채 D로 바꾸기, 다른 화면으로 가기 → logcat에 계획을 다시 세우는 줄이 멈춘다. 계획 화면으로 돌아오면 다시 세운다.
20. 제출용(release) APK: `adb shell am start -n dev.shimpyo.app/.MainActivity --ez selftest true`와 `--ez verify true`는 아무 일도 하지 않고, `--es demo holiday`는 시연을 켠다. `--es demo 아무말`은 시연을 끈다. `adb shell dumpsys package dev.shimpyo.app | grep permission`에 좌석·창문·조명 제어·에너지 권한이 없다(INTERNET은 TextToSpeech SDK가 선언해 그대로 있다).
21. release APK에서 음성 질문과 대답(TTS·STT), 길안내 중 경유지 추가, requestTrip 보정, 외기 전환이 예전처럼 되는지(권한을 줄인 뒤의 회귀 확인).
22. debug APK: `run-app.ps1 -SelfTest`가 예전처럼 kit 점검을 돌린다(앱 담당의 debug 전용 점검 코드와 함께).
23. 음성 "화장실 급해"로 열린 급해 화면에서 창 분할·크기 변경 → 화면이 그대로 이어지고, 닫은 뒤 다시 열리지 않는다.
