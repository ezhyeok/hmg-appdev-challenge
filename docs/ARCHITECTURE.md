# 쉼표 구조 (2026-11-12)

PT 기술 질의응답용 정리. 제품 사양은 `docs/product/SPEC.md`, 판단 로직의 수식은 `rest-engine/README.md`.

## 1. 모듈

```
┌───────────────────────── app (dev.shimpyo.app): 안드로이드 껍데기 ─────────────────────────┐
│ ui/ (Compose) ◄── CoachStore.state ──┐            CoachService: 조립만 (포트 구현 + 루프 띄우기) │
│      │                               │ UiSink     CarSignals (CarState·Vehicle·Location → 허브) │
│      └── UiCommand (Channel) ────────┼──────────► KitNaviPort · KitCabin · VoiceAgent · LLM 해석 │
│                                      │            Settings · MemoryStore · StopHistoryStore · TripStore │
└──────────────────────────────────────┼──────────────────────────────────────────────────────────┘
┌─────────── rest-engine/coach (dev.shimpyo.coach): 코치 계층, 순수 Kotlin ───────────────────────┐
│ CoachCore (액터) ── WorldSwitch(실제/시연 세계 + 엔진 + 여정) ── PromptMachine ── StopRecorder    │
│     │ 읽기         NaviTracker(내비 조회 → NaviSnapshot, epoch)   CandidateProvider(후보 → CandidateSnap) │
│     │              PositionTracker · Odometer · SignalHub      GoToRunner · RouteOracle · StopRepository │
│     ▼ Ports.kt (NaviPort, VoicePort, CabinPort, StopIndex, HistoryPort, MemoryPort, SettingsPort, UiSink …) │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
┌─────────── rest-engine (dev.shimpyo.engine): 판단 엔진, 순수 Kotlin ── CoachEngine.update() ──────┐
└───────────────────────────────────────────────────────────────────────────────────────────────┘
                     pleos-kit (dev.hmg.pleoskit): SDK를 코루틴으로 감싼 공통 모듈
              Pleos Connect SDK (Vehicle, NaviHelper, TTS, STT, FusedLocation, LLM) + AAOS Car API
```

- **rest-engine**: "언제 무엇을 권할지"를 정하는 엔진. 입력(신호·경로·후보·의도) → 출력(게이지·추천·질문). JVM 테스트 252개.
- **rest-engine/coach**: 엔진을 차에서 돌리는 실행 계층. 안드로이드에 의존하지 않고 바깥은 모두 포트(`Ports.kt`) 뒤에 있어서, 서비스 전체 흐름
  (귀성길 시연 → 질문 → '응' → 내비 경유지)을 가짜 포트와 가상 시간으로 JVM에서 끝까지 돌린다(`CoachCoreTest`). 부품별 설계는
  `docs/design/coach-layer/`(navi, candidates, prompt, world, data, core, ui).
- **app**: 포트를 SDK·파일·화면으로 구현하고 조립한다. 판단하지 않는다.
- **pleos-kit**: SDK의 함정(호출 스레드 블로킹, 앱이 얼려지는 문제, 제어 결과 미반영, 내비 요청 간격)을 감춘다. `pleos-kit/README.md`.
- composite build(`includeBuild`)라 앱은 `dev.shimpyo:rest-engine`, `dev.shimpyo:coach` 좌표로 쓴다.

## 2. 실행 흐름

1. `MainActivity`가 실행 중 권한(차속, 위치)을 받고 `CoachService`(포그라운드 서비스)를 시작한다. 포그라운드여야 내비가 앞에 와도 얼려지지 않는다.
   권한을 나중에 받으면 `PermissionsChanged`로 신호 수집을 다시 잇는다. 주행 중 UX 제한은 화면이 직접 구독한다(모르면 제한).
2. `CarSignals`가 신호를 `SignalHub`(기어·차속·조향·제동·방향지시등·크루즈·문·공조)와 `PositionTracker`(위치 SDK, 끊기면 내비 위치)에 모은다.
   연결이 실패하면 2초부터 최대 1분 간격으로 다시 한다.
3. `NaviTracker`가 내비 조회를 혼자 한다: 경로 상태·목적지 15초, 길안내 중 TBT 5~20초(다음 화장실·회전 거리와 주행 거리 보정에 따라), 내비 이벤트와
   경유지 추가 뒤에는 곧바로. 경로가 바뀔 때마다 epoch를 올리고 옛 TBT를 새 경로와 섞지 않는다. 결과는 불변 `NaviSnapshot` 하나.
4. `CandidateProvider`가 엔진에 넣을 정차 후보를 만든다: 길안내 중이면 TBT의 경로 위 휴게소·졸음쉼터를 이름으로 공공데이터(`stops.bin` 6만여 곳)와
   맞추고(같은 이름은 차로 방위로), 아니면 진행 방향 60km 반경 검색 + 가까운 6곳 `requestTrip` 보정. 어디까지 빠짐없이 넣었는지(`candidatesUntilM`)도 준다.
5. `CoachCore`가 1초마다 틱: 정차 판정 → 여정 → 내비·후보를 달린 만큼 투영 → 세계(실제/시연)가 입력을 바꿈 → `CoachEngine.update` →
   `PromptMachine`(질문 상태 기계)이 효과를 냄 → 효과 실행(음성으로 묻기, 내비 경유지, 외기 전환…) → 화면 상태 한 번 갱신.
6. 질문을 수락하면 `GoToRunner`가 명령 직전에 내비 모드를 다시 읽어 경유지 추가(길안내 중) 또는 새 길안내를 하고, 성공했을 때만 엔진에 수락을 알린다.
7. 기어 P와 문 열림으로 정차를 기록한다(`StopRecorder`, 엔진과 같은 규칙). 실제 세계만 기록·학습·여정 맥박을 기기 안(`filesDir`)에 저장한다.

## 3. 판단 엔진 (요약)

| 구성 | 입력 | 방법 | 출력 |
|---|---|---|---|
| 화장실 필요 모델 | 마지막 화장실 이후 시간, 탑승자 유형, 개인 정차 기록 | 탑승자별 와이블 분포 + 개인 기록으로 베이즈 갱신. 게이지 = 가장 급한 사람의 필요 확률 50%, 판단 마감 = 같은 사람의 30% | 화장실 게이지(분), 기준 탑승자, 판단 마감 |
| 운전 스타일 분석 | 조향각, 차속, 제동 | 이번 주행 초반을 기준선으로 한 편차 점수(0~1) | 피로 단계 조정, 이유 문구 |
| 피로 모델 | 연속 주행 시간, 시간대, 수면, 운전 스타일, 내기순환 | 규칙 기반 (기준 시간 × 가중치) | 피로 게이지(분), 환기 권유 |
| 마지막 기회 | 경로 앞 화장실 기회, 정체 구간 | 정체·공백으로 늘어난 도착 시간과 판단 마감 비교. 마감이 지났으면 아직 들를 수 있는 가장 가까운 기회(다음 기회·목적지까지 공백을 알 때) | 마지막 기회 질문 |
| 급해 모드 | 반경 5km 후보 | 주행 시간 + 주차·도보 + 유턴 + 영업 여부 + 야간 안전 | 순위 |
| 피트스톱 플래너 | 경로 앞 후보, 제약 | 동적 계획법 (정차 수·시간 최소화) | 정차 계획 |
| 묻는 타이밍 | 다음 회전 거리, 제동·조향, 거절 이력 | 규칙 + 톰프슨 샘플링(묻는 거리 학습) | 질문 여부와 시점 |

엔진 안의 공통 규칙 (감사 2026-11-11 뒤, `rest-engine/README.md` 3장에 자세히)
- 입력 경계: `InputSanitizer`가 update·urgent·plan 입구에서 NaN·무한대를 '모름'으로 바꾸고 그런 정체 항목을 뺀다. 차속은 그 update에 들어온 값만 쓴다(앞 값을 들고 있지 않는다).
- 경로 모델(`RouteContext`): 정체 항목을 [0, 목적지]로 자르고 합집합으로 만든다. 후보 도착 시간은 거리순으로 엔진이 '뒤 후보에 먼저 닿는다'고 지어내지 않게 맞춘다. 목록을 자른 곳(`candidatesUntilM`)·목적지 너머 후보는 공백의 증거로 쓰지 않는다.
- 출력: 추천·질문의 정차 후보는 한 함수(`withEta`)로 엔진 도착 시간을 채운다. 질문 id는 엔진 인스턴스마다 다른 접두어 + 번호라 엔진을 다시 만들어도 겹치지 않는다.
- 질문: 사용자가 말한 것에는 바로 답하고(둘이면 차례로), 그 말에 끊긴 엔진 질문은 '무시'로 치지 않아 다시 물을 수 있다. 시간당 상한은 같거나 높은 우선순위 질문만 센다.

주장하지 않는 것: 의료 진단, 정확한 졸음 감지. 게이지는 '권유 시점'이고, 판단 근거를 화면과 음성으로 함께 보여 준다.

## 4. 스레드와 동시성
- 코치 디스패처 하나(`Dispatchers.Default.limitedParallelism(1)`)에서 `CoachCore`(액터)·`NaviTracker`·`CandidateProvider` 루프가 돈다. 그래서 코치 상태에
  잠금이 없다. 코어는 우편함 하나를 차례로 처리하고 처리 중에는 일시 중단하지 않는다. 오래 걸리는 일(음성, 내비 명령, LLM, 파일 저장)은 효과로
  띄우고 결과를 메시지로 받는다. 시연 전환도 명령 메시지라 틱 중간에 끼지 않는다.
- 부품 사이에는 불변 스냅샷(`NaviSnapshot`, `CandidateSnap`)만 오간다. 다른 루프가 코어를 부르지 않고 `CoachGates`만 읽는다.
- 시계 둘: `CoachClock`(엔진과 세계 상태, 시연이면 가상·배속), `ElapsedClock`(질문 수명, 조회 간격, 환기 문, 위치 신선도처럼 시연 전환을 넘는 것).
- SDK 호출은 kit의 전용 스레드에서 실행된다(`SdkThreads`). 내비 요청은 kit이 한 번에 하나씩, 경로 변경 명령은 3초 간격.
- 파일: 시작 때 IO에서 읽고, 학습은 IO 효과로, 정차 기록·여정 맥박은 앱의 쓰기 스레드(한 번에 하나, 맡긴 순서)로 AtomicFile에 쓴다.

## 5. 데이터
- 휴게소·졸음쉼터·공중화장실·주유소·공영주차장: `app/src/main/assets/data/`, 출처와 라이선스는 `tools/data/README.md`.
- 개인 데이터(탑승자 설정, 정차 기록, 학습 상태)는 기기 밖으로 보내지 않는다.

## 6. 시연
- 차량 신호: Virtual Console HTTP API를 `tools/vc/vc.sh`로 조작 (`shimpyo-*.vc` 시나리오).
- 위치·시각·정체·경로: 시연 세계(`CoachWorld.Demo` + 불변 `DemoRun`)가 덧씌운다. 실제 위치는 계속 받으므로 시연을 끄면 바로 실제 위치다.
- 시연 경로는 앱에 든 실제 휴게소·졸음쉼터 좌표를 잇는다. 그래서 후보와 공백은 실제 데이터에서 나온다(귀성길: 옥산휴게소 → 죽암휴게소 21km 공백에 20km·60분 정체).
- 시연은 빈 학습 상태의 새 엔진(질문 수명은 배속만큼 늘림)으로 시작하고 기록·학습·여정을 저장하지 않는다. 시연을 켜면 실제 엔진과 여정을 옆에 두고,
  끄면 그대로 되돌린다(피로·화장실 시계가 이어진다). 다른 코드는 '시연 중인가'를 묻지 않고 세계의 정책(`persistsHistory` 등)을 묻는다.

## 7. 알려진 제약 (에뮬레이터)
- 주행 시간 API(Trip 계열)는 에뮬레이터가 지원하지 않아 앱이 기어·차속으로 직접 잰다.
- 좌석 점유 값은 에뮬레이터에서 믿을 수 없어서 탑승자는 사용자가 정한다(말로 설정 가능).
- 그 밖의 확인 결과는 `docs/kit-selftest.md`와 SPEC 8장.
