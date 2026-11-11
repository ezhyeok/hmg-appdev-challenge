# 화면 계층 (UI): 코치 상태 → 화면

화면은 판단하지 않는다. 코치가 1초마다 내는 `CoachUiState`(rest-engine/coach `CoachState.kt`)와 화면이 스스로 구독한 주행 제한을 그리고, 사용자의 조작을 `UiCommand`로 보낸다. 이 문서는 "어떤 상태에서 화면이 무엇을 보이는가"의 기준이다. 시각 사양(크기·색·배치)은 `docs/design/screens.md`, 토큰은 `docs/design/pleos-design-tokens.md`.

2026-11-11 UI 감사(결함 #1~#27, Compose S1~S4)를 반영해 다시 짰다. 아래 "감사 #n"은 그 번호다.

## 1. 구조: 순수 모델과 그리기

상태 → 표시 내용은 모두 순수 함수다. Composable은 그 결과를 그리기만 하므로, 상태별 표시 규칙을 안드로이드 없이 JVM 시험으로 지킨다.

| 파일 (app/src/main/java/dev/shimpyo/app/ui/) | 순수 함수·타입 | 그리는 곳 |
|---|---|---|
| `Restriction.kt` | `driveRestriction(ux, state) → DriveRestriction`, `limitChars` | 모든 화면 |
| `drive/DriveModels.kt` | `stopCard`, `driveActions`, `restroomGauge`, `fatigueGauge`, `shortQuestion`, `acceptLabel`, `goToContext` | `DriveScreen` |
| `RouteModel.kt` | `opportunities`, `restroomGaps`, `livePlan`, `remainingCongestion`, `congestionLabel`, `stripData` | 계획 화면, 가로 주행 화면 띠 |
| `plan/PlanScreen.kt` | `timeline`, `planSummary`, `noOpportunityText` | `PlanScreen` |
| `urgent/UrgentModel.kt` | `urgentView`, `settleDismissed`, `UrgentRequestIds` | `UrgentScreen` |
| `components/MicModel.kt` | `micModel` | `VoiceButton` |
| `Labels.kt` | `shortReason`(이유 코드 → 12자 칩), `baselineMin` | 칩, 게이지 한 줄 |
| `ContentState.kt` | `ContentState`(진행 중 요청, 저장·복원), `goToToast` | `ShimpyoContent` |
| `PermissionPolicy.kt` | 권한 창을 띄울 때 | `MainActivity` |
| `components/FitColumn.kt` | 높이가 모자라면 덜 중요한 줄부터 빼는 배치 | 추천 카드, 급해 결과 카드 |

화면 → 서비스 명령은 `AppHost.send` → `CoachStore.send`(Channel)로 간다. `ShimpyoContent`의 `ContentActions`가 명령을 보내는 유일한 곳이다.

## 2. 주행 제한 (fail closed, 감사 #2·#26)

플랫폼의 `CarUxRestrictions`를 화면이 서비스 없이 직접 구독한다(`UiRestrictions`, pleos-kit `UxRestrictionWatcher`). 프로세스에 하나만 두어 액티비티가 다시 만들어져도 값을 이어받는다(새로 연결하면 잠깐 '모름'이 되어 정차 중 설정 화면에서 주행 화면으로 튕긴다).

| 화면 구독(`UxRestrictions`) | 서비스 | 결과 `driving` |
|---|---|---|
| Known, requiresDO = true | 무관 | true |
| Known, requiresDO = false | 무관 | false |
| Unknown(연결 전·구독 실패·해제) | 돌고 있고 `drivingRestricted = false` | false (서비스가 플랫폼에서 읽은 값) |
| Unknown | 그 밖 (서비스 꺼짐, 기본값 true) | **true** |

- `driving`이면 주행 화면과 급해 결과만 그린다. 다른 화면에 있었으면 주행 화면으로 돌아오고, 제한을 실제로 읽었을 때만(`known`) 토스트 "주행 중에는 주행 화면만 보여요"를 띄운다.
- 기능별 비트(주행 중일 때만): `LIMIT_STRING_LENGTH` → 주행 화면 카드 문구를 `maxRestrictedStringLength`로 자른다. `LIMIT_CONTENT` → 급해 결과 수를 `maxCumulativeContentItems`로 줄인다(앱 상한 2보다 작을 때만). `NO_SETUP`·`NO_KEYBOARD`·`NO_VIDEO`는 정차 화면을 모두 닫는 것으로 이미 지킨다. 음성 인식 문장(`lastHeard`)은 정차 중 시연 패널에만 있어 `NO_VOICE_TRANSCRIPTION`에 걸리지 않는다.
- pleos-kit `CarState.drivingRestricted`도 모르면 true다(예전 기본값 false).

## 3. 상태 → 화면

### 3.1 추천 카드 (`stopCard`, 위에서부터 먼저 맞는 것)

| 상태 | 맥락 줄 | 제목 | 둘째 줄 |
|---|---|---|---|
| `serviceRunning = false`, 게이지를 받은 적 없음 | 다음 화장실 | 준비하고 있어요 | 잠시 후 게이지와 추천이 나타나요 |
| `serviceRunning = false`, 받은 적 있음 (감사 #18) | 다음 화장실 | 코치가 멈췄어요 | 앱을 다시 열면 이어서 계산해요 (게이지는 "--"·"코치 멈춤", 버튼 없음) |
| `prompt != null` | `promptReasons` 코드로 (감사 #6): 정체 → "정체 전 마지막 화장실", 공백·기준 → "화장실 공백 전 마지막 기회", 가장 가까운 곳 → "가까운 화장실". 이유가 없으면 문장으로 고르되 마지막 기회를 "가까운 화장실"로 부르지 않는다 | 장소 이름, 장소 없으면 `shortQuestion`(질문 문장만, 30자 이하, 감사 #11) | 지금 거리만: 같은 곳의 `next` → `promptStop`. 둘 다 없으면 위치("경로 위")만 (물은 때의 거리를 보이지 않는다, 감사 #1) |
| `goToResult.outcome = FAILED` (감사 #3) | 경유지를 넣지 못했어요 | 장소 이름 | 다시 시도하거나 내비에서 넣어 주세요 |
| 정차 중 + 남은 계획 정차 있음 (감사 #4) | 다음 계획 | 첫 정차(지나친 것 뺌) | 지금 거리 · 지금 기준 "N분 뒤 도착" · 정차 분 |
| `next != null` | `goToResult`가 같은 곳이면 결과(넣었어요/길안내 시작/경로 위·진입로/내비에서 찾아 둠), `NEXT_ACCEPTED`만이면 "들르기로 한 곳"(엔진은 '응'을 받은 순간 기록하므로 넣었다고 하지 않는다) | 장소 | 거리·시간·위치 |
| 경로 앞 첫 화장실 (목적지까지) | 다음 화장실 | 장소 | 거리, 정체 뒤면 칩 "정체 뒤" |
| 후보 없음 (감사 #18) | — | `candidatesState`: LOADING_DATA "정차 데이터를 읽는 중이에요", NO_LOCATION "위치를 확인하는 중이에요", READY "앞쪽에 화장실 정보가 없어요" | 급할 때는 아래 버튼을 누르세요 |

칩: 주행 중 2개, 정차 중 3개. 모든 칩은 12자 이하이고(`Labels.shortReason`이 넘치면 짧은 대체 문구), 모르는 코드의 원문은 내보내지 않는다(질환 이름이 샐 수 있다). 엔진 소스의 모든 이유 코드는 문구가 있거나 `NOT_CHIP_CODES`에 있어야 한다(`ReasonCoverageTest`).

### 3.2 행동 줄 (`driveActions`, 터치 요소 4개 이하)

| 상태 | 왼쪽(Filled) | 오른쪽 |
|---|---|---|
| 서비스 돌고 질문 있음 | `acceptLabel` (들르기/쉬어 갈게요/외기로 전환) | 괜찮아요. `acceptLabel`이 null이면 [확인] 하나 (INFO, 외기 전환 미지원 차의 환기 권유: 감사 #16) |
| 서비스 돌고 경유지 실패 | 다시 시도 (`DismissGoToResult` + `GoTo`) | 닫기 (`DismissGoToResult`) |
| 정차 중 | 계획 보기 | 탑승자 |
| 주행 중, 그 밖 | 빈 자리 (높이 유지) | |

좌표 없는 경로 위 시설(TBT)의 질문도 "들르기"다: 코치가 `ON_ROUTE`(진입로로 들어가면 된다)로 답하고 카드가 그것을 보인다. 주행 중에 내비 장소 검색을 여는 것은 코치가 하지 않는다(감사 #3).

### 3.3 게이지

- 막대 분모 = 게이지를 정한 탑승자의 기준 간격: 개인 간격(`intervalOverrideMin`)이 있으면 그것, 없으면 유형별 사전값.
- 이유 한 줄 우선순위. 화장실: 직접 요청 > 정차 간 상한(카시트·걷기) > 누구 기준 > 개인 기록. 피로: 직접 말함 > 강한 권고 > 운전 패턴 > 시간대·깨어 있은 시간 > 수면 > 단조로움 > 연속 운전(늘 붙는 기본 이유라 맨 뒤, 감사 #17).

### 3.4 급해 화면 (`urgentView`, 감사 #7·#8)

화면이 요청 번호를 정해(`UrgentRequestIds`, 0이 아닌 고유 값) `Urgent(id)`를 보내고, 코치가 `urgentRequestId`·`urgentState`로 진행을 돌려준다.

| 상황 | 표시 |
|---|---|
| 서비스 꺼짐 | 코치가 멈췄어요 |
| 코치가 우리 번호로 LOCATING / SEARCHING | 위치를 확인하는 중이에요 / 가까운 화장실을 찾는 중이에요 |
| READY (목록 있음) | 결과 (주행 중 2곳 카드, 정차 중 필터 목록) |
| READY 빈 목록, NO_RESULT | 가까운 곳에 열린 화장실이 없어요 + [다시 시도] |
| NO_LOCATION | 위치를 확인할 수 없어요 + [다시 시도] (코치가 정한다. 화면이 8초 뒤 지어내지 않는다) |
| 아직 답이 없음 | 위치를 확인하는 중이에요 → 8초 뒤 "가까운 화장실을 찾지 못했어요 · 위치를 확인하지 못했을 수 있어요" + [다시 시도]. 요청을 받기 전의 코치 상태(번호 0, IDLE)는 응답 없는 코치와 위치가 없어 조용히 끝난 옛 코치가 같아서, 둘 다에 맞는 말로 알린다 |
| 코치가 우리 번호를 IDLE로 끝냄 | 닫는다 (경유지를 넣었다) |
| 닫은 번호의 늦은 결과 | 다시 띄우지 않는다 (`urgentDismissed`). 번호는 다시 쓰이지 않으므로 계속 들고 있는다: 일찍 잊으면 코치가 Urgent를 늦게 받아 그 번호로 LOCATING을 알릴 때 닫은 화면이 다시 열린다. 번호 0을 다시 쓰는 옛 코치만 목록이 지워지면 잊는다 |
| 화면이 요청하지 않았는데 코치가 진행 중 (음성 "화장실 급해") | 연다 |

급해 버튼은 진행 중인 요청이 있으면 새 요청을 보내지 않는다(한 프레임 안의 두 번 누르기). [다시 시도]만 끝난 앞 요청을 정리하고(`DismissUrgent`) 새 번호로 보낸다. 결과를 고르면(`pick`) 결과가 올 때까지 다른 카드를 막는다. `goToResult`가 실패면 다시 고를 수 있게 풀고 토스트, 성공이면 닫는다. 12초 동안 결과가 없으면 "결과를 받지 못했어요. 내비에서 확인해 주세요"(성공했는지 모르므로 실패라고 하지 않는다).

급해 화면이 떠 있을 때 다른 질문이 오면(감사 #27): 주행 중에는 터치 요소를 늘리지 않고 한 줄로 질문과 "말로 응·아니 하거나, 닫고 답해 주세요"를 보인다. 정차 중에는 버튼도 같이 둔다.

compact 세로 윈도에서 결과 카드는 `FitColumn`(줄을 빼는 판단은 순수 함수 `fitRows`, `FitColumnTest`)으로 소요 구성 줄 → 배지 줄 순으로 빼고, 이름과 시간은 늘 남긴다. 줄 순서(이름, 시간, 소요 구성, 배지)는 그대로다. 위 설명 줄은 가장 좁은 윈도에서도 한 줄에 들어가게 줄였다(감사 #10).

### 3.5 계획 화면 (감사 #4·#5)

- `livePlan`: 계획 정차를 지금 후보(ahead)에 맞춘다. 같은 id가 후보에 있으면 그 거리·ETA(없으면 평균 속도 + 정체 지연), 없으면 계획 때 값을 "약"으로. 뒤의 계획 정차가 후보에 있는데 앞의 것이 없으면 지나친 것이라 뺀다. 목적지보다 먼 정차는 뺀다. 거리를 전혀 모르는 정차(PLAN_NOW, 도착 0초)는 지금 위치에 그린다(예전에는 조용히 빠졌다). 번호는 남은 정차 안에서 1부터.
- 타임라인: 지금 → 정차 → 공백 → 정체 → 목적지 (같은 거리일 때). 목적지 뒤 후보는 뺀다. 정체 안이면 남은 부분과 남은 시간만("지금부터 12km", "정체 40분").
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

첫 줄로 쓴 메모는 아래 목록에서 빼고, 나머지 메모는 문장 그대로 보인다. PLAN_NOW·PLAN_GAP·PLAN_FUEL_SHORT는 강조(경고)로 보인다. 경유지 버튼은 결과가 오거나 12초가 지날 때까지 막는다(감사 #24).

### 3.6 마이크 (`micModel`, 감사 #12·#25)

| 음성 상태 | 표시 | 누르면 |
|---|---|---|
| LISTENING | "듣는 중" + 고정 링 | `StopVoice` |
| SPEAKING / THINKING | "말하는 중" / "생각하는 중" | `StopVoice` |
| IDLE, 인식 불가(`sttAvailable = false` 또는 UNAVAILABLE) | 사선 마이크, "듣기 불가"(말하기는 됨) / "음성 불가" | 토스트로 이유 |
| IDLE, 인식 됨, 말하기 꺼짐(설정 또는 TTS 연결 실패) | "음성 안내 꺼짐" | `Listen` (듣기는 된다: 설정의 '음성 안내'는 말하기만 끈다) |
| IDLE, 둘 다 됨 | (글자 없음) | `Listen` |

주행 중 마이크는 100dp(앱의 주행 중 터치 최소).

### 3.7 설정

외기 전환 스위치는 `cabinSupported`일 때만 켤 수 있고, 아니면 "이 차에서는 쓸 수 없어요"(감사 #16). 설정 화면은 상태 전체가 아니라 `SettingsStatus`와 탑승자만 받는다(매초 다시 그리지 않게, S3).

## 4. 진행 중 상태와 액티비티 재생성 (감사 #14)

`ContentState`(화면, 계획 계산 중, 급해 요청·닫은 번호, 고른 곳, 넣는 중, 이미 알린 결과 시각)는 `rememberSaveable`의 Saver로 저장한다. 창 분할·크기 변경·야간 모드로 액티비티가 다시 만들어져도 '넣는 중'이 풀리지 않아 같은 요청을 1초 안에 다시 보내지 않는다.

`MainActivity`:
- 서비스는 권한과 관계없이 화면이 보일 때 시작한다(이미 돌면 다시 부르지 않음). 서비스가 멈췄다가 화면으로 돌아오면 다시 시작한다.
- 실행 중 권한(차속·위치)은 정차 중임을 실제로 읽었을 때 한 번만 묻는다(`PermissionPolicy`). 주행 중(모를 때 포함)에는 시스템 권한 창을 띄우지 않고 정차할 때까지 기다린다. 다시 만들어져도 다시 묻지 않는다.
- 빠진 권한이 새로 허용되면(권한 창 결과, 또는 설정 앱에서 허용하고 돌아옴) `PermissionsChanged`를 보낸다.

## 5. CCP(로터리) (감사 #13)

- 기본 포커스: 주행 화면은 질문(또는 경유지 실패) 중이면 왼쪽 버튼, 아니면 급해. 질문이 끝나 버튼이 사라지면 급해로 옮긴다(`RequestFocusOnce`의 키가 바뀜). 급해 결과는 1순위, 안내 화면은 [다시 시도]. 팝업은 [취소]에 포커스를 주고 포커스를 팝업 안에 가둔다(`focusProperties { exit = Cancel }`). 뒤 덮개는 포커스를 받지 않는다.
- 터치 모드에서는 누를 수 있는 요소가 포커스를 받지 않으므로 터치 사용자에게 링이 보이지 않는다.
- Manifest에 `com.hmg.rotary.ROTARY_SUPPORTED=false`를 선언한다. 사용자 설치 앱은 CCP가 선택이고, 미지원이면 필수 조작이 터치로 끝나야 한다(쉼표는 모두 터치로 된다). CCP UX 가이드 심사 항목 전체를 실제 CCP로 확인하지 못했으므로 지원한다고 선언하지 않는다. 최상위 화면에서 Back은 `moveTaskToBack(true)`(필수 정책).

## 6. Compose 안정성 (S1~S4)

- `compose-stability.conf`에는 화면 매개변수로 들어오는 불변 타입만 하나씩 적는다(엔진 Api.kt 출력 타입과 코치 상태 타입). 예전 `dev.shimpyo.engine.*`는 가변 `CoachEngine`·`RouteContext`까지 안정으로 보였다.
- 화면에 넘기는 람다는 `ContentActions`(한 화면 동안 같은 객체)만 잡는다. 함수 참조(`::pick`)는 쓰지 않는다. 헤더 `trailing` 람다는 `MicModel`·계획 계산 중 여부만 잡는다(매초 바뀌는 상태 전체를 잡으면 머리가 매초 다시 그려진다).
- 확인: Compose 컴파일러 보고서(빌드 파일을 바꾸지 않고 `--init-script`로 `composeCompiler.reportsDestination`을 켬). 2026-11-12 기준 재시작 가능한 Composable 111개 모두 건너뛰기 가능, 런타임 안정성 매개변수는 `DriveActions`·`List<TimelineRow>` 두 개였고 `@Immutable`로 고쳤다. 남은 불안정 매개변수는 `RequestFocusOnce(key: Any?)` 하나(키라서 의도한 것).

## 7. 옛 CoachService와의 관계 (코치 계층이 들어오기 전)

화면은 계약(`CoachState.kt`)만 보고 짰다. 지금의 CoachService는 아래 필드를 채우지 않아 기본값이 보인다. CoachCore(E)가 채우면 사라진다.

| 필드 | 옛 서비스에서 보이는 것 |
|---|---|
| `urgentRequestId`·`urgentState` | 번호 0이면 옛 방식(urgent 목록이 결과)으로 동작한다. 이 갈래만 화면에 남겨 두었다(`UrgentModel.legacy`) |
| `promptReasons` | 비어 있으면 같은 곳의 `next` 이유, 그것도 없으면 문장으로 맥락을 고른다 |
| `goToResult` | 오지 않으므로 '들르기'를 받은 곳은 "들르기로 한 곳"으로, 결과는 TTS로만 알 수 있다 |
| `candidatesState` (기본 LOADING_DATA) | 후보가 정말 없을 때도 "정차 데이터를 읽는 중이에요"로 보인다(틀려도 "없다"고 하지는 않는다) |
| `cabinSupported` (기본 false) | 외기 전환 스위치가 꺼져 있고, 환기 권유에는 [확인]만 보인다 |
| `ttsAvailable`·`sttAvailable` (기본 false) | 마이크가 "음성 불가"로 보이고 누르면 이유만 알린다 |
| `PermissionsChanged` | 옛 서비스는 무시한다: 권한을 나중에 허용하면 서비스를 다시 시작해야 차속·위치가 들어온다 |

## 8. 시험 (app/src/test/java/dev/shimpyo/app/ui/, 노트북 `gradle.ps1 :app:testDebugUnitTest`)

| 상황 | 시험 |
|---|---|
| 모르면 제한, 화면 구독 우선, 기능별 비트·최대 글자 수 | `RestrictionTest.unknownIsRestricted`, `screenSubscriptionWinsOverServiceValue`, `perFeatureLimitsApplyOnlyWhenFlagged` |
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
| 계획 타임라인·요약 | `PlanTimelineTest.*`, `RouteModelTest.*` |
| 마이크 | `MicModelTest.*` |
| 재생성 뒤 진행 중 상태, 결과 토스트 | `ContentStateTest.*` |
| 탑승자 나이 이름, 동의 철회 | `OccupantEditTest.*` |

## 9. 기기에서 확인할 것 (통합 실행)

에뮬레이터 + Virtual Console에서 확인한다(이 작업은 JVM 시험과 빌드만 했다).

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
