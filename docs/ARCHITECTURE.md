# 쉼표 구조 (2026-11-15 문서 점검)

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

- **rest-engine**: "언제 무엇을 권할지"를 정하는 엔진. 입력(신호·경로·후보·의도) → 출력(게이지·추천·질문). 엔진·코치 JVM 테스트는 서버에서, 앱·pleos-kit 테스트는 Android SDK가 있는 노트북에서 실행한다.
- **rest-engine/coach**: 엔진을 차에서 돌리는 실행 계층. 안드로이드에 의존하지 않고 바깥은 모두 포트(`Ports.kt`) 뒤에 있어서, 서비스 전체 흐름
  (귀성길 시연 → 질문 → '응' → 내비 경유지)을 가짜 포트와 가상 시간으로 JVM에서 끝까지 돌린다(`CoachCoreTest`). 부품별 설계는
  `docs/design/coach-layer/`(navi, candidates, prompt, world, data, core, ui).
- **app**: 포트를 SDK·파일·화면으로 구현하고 조립한다. 판단하지 않는다.
- **pleos-kit**: SDK의 함정(호출 스레드 블로킹, 앱이 얼려지는 문제, 제어 결과 미반영, 내비 요청 간격)을 감춘다. `pleos-kit/README.md`.
- composite build(`includeBuild`)라 앱은 `dev.shimpyo:rest-engine`, `dev.shimpyo:coach` 좌표로 쓴다.

## 2. 실행 흐름

1. `MainActivity`가 실행 중 권한(차속, 위치, 차량 에너지)을 받고 `CoachService`(포그라운드 서비스)를 시작한다. 포그라운드여야 내비가 앞에 와도 얼려지지 않는다.
   권한을 나중에 받으면 `PermissionsChanged`로 신호 수집을 다시 잇는다. 주행 중 UX 제한은 화면이 직접 구독한다(모르면 제한).
2. 기어·차속은 AAOS `CarPropertyManager`, 도어는 Pleos Vehicle `registerDoorPosition`에서 받는다. `CarSignals`가 신호를 `SignalHub`(기어·차속·잔여 거리·에너지 종류·조향·제동·방향지시등·크루즈·문·공조)와 `PositionTracker`(위치 SDK, 끊기면 내비 위치)에 모은다.
   연결 셋(Car API, Vehicle SDK, 위치 SDK)을 신호 목록으로 선언하고, 받는 방법은 coach 모듈의 `SignalCollector` 하나가 맡는다: 연결·기능 확인·
   신호마다 따로 다시 시도(`Retry`/`Backoff`, 2초부터 최대 1분), 리스너 값이 없을 때만 한 번 읽기, 30초마다 다시 읽어 조용한 신호가 멎은 것인지
   확인(`SignalHealth`), 읽기가 모두 실패하고 이벤트도 없으면 연결을 버리고 새로 맺기(`LinkHealth`, Vehicle 서비스가 강제 종료된 경우).
   에너지는 `VehicleEnergy` 값으로 포트 경계를 넘고, 성공한 동일 값 재조회도 `reportReads`로 전달한다. `SignalHub`는 90초 넘게 갱신되지 않으면 잔량·종류를 각각 null로 돌린다. `CoachWorld`만 시연 잔량을 덧씌운다.
   끊긴 동안 그 신호는 '모름'(null)이다. 조향 표본은 받은 순간의 단조 시계로 찍고 틱에서 엔진 시계로 옮긴다(시연 배속과 무관).
3. `NaviTracker`가 내비 조회를 혼자 한다: 경로 상태·목적지 15초, 길안내 중 TBT 5~20초(다음 화장실·회전 거리와 주행 거리 보정에 따라), 내비 이벤트와
   경유지 추가 뒤에는 곧바로. 경로가 바뀔 때마다 epoch를 올리고 옛 TBT를 새 경로와 섞지 않는다. 결과는 불변 `NaviSnapshot` 하나. `congestionBasisKnown`으로 정체 좌표 신뢰를 전파하며 UNKNOWN·출발점 불명·미수신이면 정체 위치 판단을 보류한다. 빈 목록만으로 정체 없음이라 확정하지 않는다(SPEC 4.3).
4. `CandidateProvider`가 엔진에 넣을 정차 후보를 만든다: 길안내 중이면 TBT의 경로 위 휴게소·졸음쉼터를 이름으로 공공데이터(`stops.bin`: 화장실 후보 60,084곳 / 전체 87,497곳)와
   맞추고(같은 이름은 차로 방위로), 아니면 진행 방향 60km 반경 검색 + 가까운 6곳 `requestTrip` 보정. 어디까지 빠짐없이 넣었는지(`candidatesUntilM`)도 준다.
   `NearbyStops`가 차 가까이 5km 안의 일상 정차 후보(지금 열고 차를 세울 수 있는 공중화장실·주유소·공영주차장 12곳, 250m 움직이거나 20초마다
   다시 찾음)를 따로 넣는다(`CoachInput.nearby`). 직선거리만 아는 목록이라 길가 화장실에 바로 갈 수 없는 길에서는 비운다: 고속도로 차로 위(지도 맞춤
   `MotorwayIndex`), 내비가 매초 알려 주는 제한속도 70 이상(접근 제한의 보조 규칙이며 일반 국도 오제외 가능, `NaviTracker.roadLimit`, 실제 세계만), 시속 80 이상. 엔진은 이 목록을
   고속도로의 공백 판단에 쓰지 않는다.
5. `CoachCore`가 1초마다 틱: 정차 판정 → 여정 → 내비·후보를 달린 만큼 투영 → 세계(실제/시연)가 입력을 바꿈 → `CoachEngine.update` →
   `PromptMachine`(질문 상태 기계)이 효과를 냄 → 효과 실행(음성으로 묻기, 내비 경유지, 외기 전환…) → 화면 상태 한 번 갱신.
6. 질문을 수락하면 `GoToRunner`가 명령 직전에 내비 모드를 다시 읽어 경유지 추가(길안내 중) 또는 새 길안내를 하고, 성공했을 때만 엔진에 수락을 알린다.
7. 기어 P와 문 열림으로 정차를 추정·기록한다(`StopRecorder`). 문 열림만으로 이용자·화장실 완료를 관측한 것은 아니다. 실제 세계만 기록·학습·여정 맥박을 기기 안(`filesDir`)에 저장한다.

## 3. 판단 엔진 (요약)

| 구성 | 입력 | 방법 | 출력 |
|---|---|---|---|
| 화장실 필요 모델 | 마지막 화장실 이후 시간, 탑승자 유형, 개인 정차 기록, 식사 정차 여부, 잠든 아이(앱이 알려 줌) | 탑승자별 와이블 분포 + 개인 기록으로 베이즈 갱신. 게이지 = 가장 급한 사람의 필요 확률 50%, 마지막 기회의 판단 마감 = 탑승자 중 누군가 필요해질 확률 30%(여럿이 타면 더 이르고, 그 때문에 앞당겨 물으면 이유를 붙이며 그렇게 선 정차는 누구의 사건으로도 배우지 않는다). 계획의 구간 상한은 한 사람 기준 30%. 문맥 보정은 시계를 유효 시간으로 돌려 넣는다: 식사 정차 뒤 구간 ×0.8, 밤에 잠든 아이 ×1.5(게이지에만). 근거와 넣지 않는 것은 `docs/research/need-covariates.md` | 화장실 게이지(분), 기준 탑승자, 판단 마감 |
| 운전 스타일 분석 | 조향각, 차속, 제동 | 이번 주행 초반을 기준선으로 한 편차 점수(0~1) | 피로 단계 조정, 이유 문구 |
| 피로 모델 | 연속 주행 시간, 시간대, 수면, 운전 스타일, 내기순환 | 규칙 기반 (기준 시간 × 가중치) | 피로 게이지(분), 환기 권유 |
| 마지막 기회 | 경로 앞 화장실 기회·쉴 수 있는 곳, 정체 구간 | 정체·공백으로 늘어난 도착 시간과 판단 마감 비교. 같은 계산을 피로 게이지(남은 분)로 한 번 더 해 졸음의 마지막 기회를 찾는다(같은 곳이면 질문 하나, SPEC 4.3a). 마감이 지났으면 아직 들를 수 있는 가장 가까운 기회(다음 기회·목적지까지 공백을 알 때). 게이지가 NOW면 가장 가까운 화장실이 먼 마지막 기회보다 먼저다 | 마지막 기회 질문, 여유 없을 때의 가까운 화장실 질문 |
| 차량 마지막 기회·함께 들르기 | 주행가능 km·EnergyKind, 목적지·후보 한계, 시설 | `GapAnalyzer.analyzeRange`: 예비 거리(연료 50km, EV 20km)를 뺀 거리 안 마지막 기회. `selectLastChance` 순수 함수가 화장실·피로·차량을 선택·병합해 질문과 카드에 함께 쓴다. 사람 정차 뒤 가용 거리 <100km이고 목적지 전 에너지 정차가 필요하면 함께 권한다(SPEC 4.3b). EV/연료/모름별 시설 필터를 계획·음성 응답도 공유 | RANGE_LAST_CHANCE·RANGE_TIGHT·ENERGY_BUNDLE, FUEL 목적 |
| 급해 모드 | 경로 후보 + 반경 5km 후보, 경로의 정체 | 주행 시간(내비 ETA → 길안내 경로 위면 엔진 도착 시간 → 거리 ÷ 평균 속도. 순위·음성·카드가 같은 값) + 주차·도보 + 유턴 + 영업 여부 + 야간 안전 | 순위 |
| 피트스톱 플래너 | 경로 앞 후보, 제약(한 사람 기준 화장실 마감, 2시간 휴식 리듬, 수유·주유) | 동적 계획법 (정차 수·시간 최소화). 기어 P이고 300m 안에 화장실이 있는 곳에 서 있으면 그곳에서 화장실(필요하면 휴식·식사)을 마치고 출발한다고 보고 그 뒤를 계획한다(`PLAN_HERE_NOW`/`PLAN_HERE`, `PlanFromHereTest`) | 정차 계획 |
| 출발 전 한마디 (일상) | 기어 P, 내비 남은 시간(정체 포함)·목적지(`RouteState.destination`), 이번 여정에서 실제로 움직였는지(`tripMoved`, 재시작은 `TripProfile.tripMoved`), 이 정차의 문 열림 | 도착까지 ≥ 20분, 게이지보다 길고, 지금 다녀오면 게이지가 20분 이상 늘 때. 아직 움직이지 않았거나, 여정 중간 정차에서 문이 열렸고 목적지가 바뀌었을 때(재탐색은 같은 목적지). 서 있으면 회전·제동 조용한 때 규칙을 쓰지 않고, 시간당 상한에 세지 않는다. 출발하면 거둔다. `DepartureCheckTest`, `DepartureEdgeTest` | 출발 전 질문(`DEPARTURE`, 경유지 없음, 이유 `DEPARTURE_TRIP`) |
| 타는 중 (일상) | 이번 여정에서 움직였는지, 출발 전 질문의 '응' | 아직 움직이지 않았을 때의 정차에서 연 문은 화장실 정차가 아니다(엔진 `StopTracker`, 코치 `StopDetector`·`TripSession.moved`가 같은 규칙). '응' 뒤의 문만 화장실이고 배우지 않는다 | 화장실 정차 판정 |
| 도심 다음 화장실 (일상) | `CoachInput.nearby` (+ 후보에 섞인 일상 종류) | 쓸 수 있는 곳(화장실·주차·영업·진행 방향, 목적지보다 가까운 곳)을 급해 모드와 같은 계산(도심 속도·주차·도보, 경로 색인을 쓰지 않음)으로 고른다. NOW·말한 요청에서는 경로의 화장실보다 2분 넘게 빨리 닿을 때 그곳. 진입로 2km 규칙 없이 묻고, 그 질문은 장소가 목록에 남아 있는 동안 산다(`PromptPolicy` 거리 없는 질문). 서 있는 곳 300m 안이면 "가까이에 화장실이 있어요". `CityNearbyTest` | 다음 화장실(`NEXT_NEARBY_RESTROOM`), 질문 |
| 묻는 타이밍 | 다음 회전 거리, 제동·조향, 거절 이력 | 규칙 + 톰프슨 샘플링(묻는 거리 학습) | 질문 여부와 시점 |

엔진 안의 공통 규칙 (감사 2026-11-11 뒤, `rest-engine/README.md` 3장에 자세히)
- 입력 경계: `InputSanitizer`가 update·urgent·plan 입구에서 NaN·무한대를 '모름'으로 바꾸고 그런 정체 항목을 뺀다. 차속은 그 update에 들어온 값만 쓴다(앞 값을 들고 있지 않는다).
- 경로 모델(`RouteContext`): 정체 목록에서 같은 항목이 두 번 온 것만 한 번으로 세고, 범위가 겹치는 서로 다른 항목은 이어 놓은 뒤(내비가 시작 거리를 반올림해 보낸다) [0, 목적지]로 자른다. 후보 도착 시간은 거리순으로 엔진이 '뒤 후보에 먼저 닿는다'고 지어내지 않게 맞춘다. 목록을 자른 곳(`candidatesUntilM`)·목적지 너머 후보는 공백의 증거로 쓰지 않는다.
- 출력: 추천·질문의 정차 후보는 한 함수(`withEta`)로 엔진 도착 시간을 채운다. 질문 id는 엔진 인스턴스마다 다른 접두어 + 번호라 엔진을 다시 만들어도 겹치지 않는다.
- 질문: 사용자가 말한 것에는 바로 답하고(둘이면 차례로, 서 있어도), 그 말에 끊긴 엔진 질문은 '무시'로 치지 않지만 이미 발화한 같은 장소·이유는 다시 말하지 않는다. 일반 선제 질문은 우선순위와 무관하게 최근 60분 총 3회다. 일반 마지막 기회용 1회를 예약하며 NOW·긴급, 사용자 요청, 정차 중 출발 질문은 별도 집계한다. 같은 장소·이유의 반복을 막고 이미 발화한 질문은 중단돼도 횟수에서 빼지 않는다(SPEC 4.6).
- 들르기로 한 곳: 수락한 질문의 장소, 또는 운전자가 화면에서 직접 고른 곳(`CoachEngine.noteAccepted`: 급해 목록, 계획, 다시 시도). 그동안 엔진은 정차를 먼저 묻지 않고 그곳을 다시 묻지 않는다. 다른 곳을 묻던 화장실 질문은 그 선택으로 끝난다(무시로 세지 않는다).
- 화장실 질문이 떠 있는 동안 추천(`next`)은 그 질문의 장소와 이유다. 화면이 질문 카드의 이유를 추천에서 가져오므로 둘이 어긋나지 않는다(`LAST_CHANCE_*`, `NEXT_NEAREST_RESTROOM`, 요청의 답은 `NEXT_REQUESTED_RESTROOM`·`NEXT_REQUESTED_CARE`).
- 정차 판정: P에서 나와 3분 안에 30km/h를 넘지 않고 다시 P면 같은 정차지만, 그 정차를 시작한 뒤 움직인 거리가 300m를 넘으면 새 정차다(정체 속 가다 서다를 휴식으로 세지 않는다).

주장하지 않는 것: 의료 진단, 개인의 안전 운전 한계·요의 발생 시각 예측. 게이지는 기본 간격과 인구 평균으로 계산한 권유 시점의 추정이다. 개인 표시 문턱 50%와 집단 판단 문턱 30%는 다르며 실사용 보정 정확도는 미검증이다.

## 4. 스레드와 동시성
- 코치 디스패처 하나(`Dispatchers.Default.limitedParallelism(1)`)에서 `CoachCore`(액터)·`NaviTracker`·`CandidateProvider` 루프가 돈다. 그래서 코치 상태에
  잠금이 없다. 코어는 우편함 하나를 직렬 처리하고 처리 중에는 일시 중단하지 않는다. 동시 쓰기를 막지만 메시지 도착 순서가 결과에 영향을 줄 수 있다. 오래 걸리는 일(음성, 내비 명령, LLM, 파일 저장)은 효과로
  띄우고 결과를 메시지로 받는다. 시연 전환도 명령 메시지라 틱 중간에 끼지 않는다.
- 부품 사이에는 불변 스냅샷(`NaviSnapshot`, `CandidateSnap`)만 오간다. 다른 루프가 코어를 부르지 않고 `CoachGates`만 읽는다.
- 시계 둘: `CoachClock`(엔진과 세계 상태, 시연이면 가상·배속), `ElapsedClock`(질문 수명, 조회 간격, 환기 문, 위치 신선도처럼 시연 전환을 넘는 것).
- SDK 호출은 kit의 전용 스레드에서 실행된다(`SdkThreads`). 내비 요청은 kit이 한 번에 하나씩, 경로 변경 명령은 3초 간격.
  SDK 객체도 그 스레드에서 처음 쓸 때 만든다(`LazySdk`): 서비스 시작이 메인 스레드를 잡는 시간이 줄고, 연결을 버리고 새로 만들 수 있다.
- 말하기·듣기는 요청 id가 없는 SDK라 kit의 `TurnRunner`가 한 번에 하나씩 돌린다: 새 요청은 앞 요청의 끝(완료·멈춤·오류) 이벤트를 기다린 뒤에
  나가므로 앞 요청의 늦은 이벤트가 새 요청의 결과로 읽히지 않는다. 질문을 끝까지 말하지 못했으면(끊김·오류) 마이크를 열지 않는다(`VoiceAgent`).
- 서비스는 시스템이 다시 만들 때(`onStartCommand(null)` 또는 `onTaskRemoved`만 오는 경우)에도 같은 시작 경로를 탄다(`PleosForegroundService`).
- 개발용 점검(kit 점검, SDK 실험)은 debug 소스셋(`app/src/debug/.../devtools`)에만 있다. 제출용 빌드에는 코드와 권한이 없고, 인텐트로는
  시연 전환만 된다(`LaunchExtras`, `DevHooks`, `tools/laptop/devhooks-check.sh`).
- 파일: 시작 때 IO에서 읽고, 학습은 IO 효과로, 정차 기록·여정 맥박은 앱의 쓰기 스레드(한 번에 하나, 맡긴 순서)로 AtomicFile에 쓴다
  (임시 파일 → fsync → 이름 바꾸기). 읽지 못하는 파일은 지우지 않고 `<이름>.corrupt`로 옮긴 뒤 빈 상태로 시작한다.

## 5. 데이터
- 휴게소·졸음쉼터·공중화장실·주유소·공영주차장: `app/src/main/assets/data/`, 출처와 라이선스는 `tools/data/README.md`.
- 개인 데이터(탑승자 설정, 정차 기록, 학습 상태)는 기기 밖으로 보내지 않는다.

## 6. 시연
- 차량 신호: Virtual Console HTTP API를 `tools/vc/vc.sh`로 조작 (`shimpyo-*.vc` 시나리오).
- 위치·시각·정체·경로: 시연 세계(`CoachWorld.Demo` + 불변 `DemoRun`)가 덧씌운다. 실제 위치는 계속 받으므로 시연을 끄면 바로 실제 위치다.
- 시연 경로는 앱에 든 실제 휴게소·졸음쉼터 좌표를 잇는다. 그래서 후보와 공백은 실제 데이터에서 나온다(귀성길: 옥산휴게소 → 죽암휴게소 21km 공백에 20km·60분 정체).
  시연은 여섯 장면이다: 차도 사람도(charge: EV 50km, 아이 화장실과 차량 충전을 옥산에서 한 번에), 귀성길(holiday), 오후 졸음(fatigue), 밤 급해(urgent), 정차(stop), 출발 전(departure: 08:10 분당 아파트 앞, 1시간 전에 화장실에
  다녀온 7살 아이, 정체 포함 1시간 3분 거리 → "출발 전에 다녀오세요". Virtual Console 기어를 P로 둔다).
- 질문 카드가 떠 있는 동안 시연의 시각과 위치가 함께 0.25배로 흐른다(가상 속도는 그대로라 판단은 같다). `CoachWorld.questionShown`이 빠르기만 바꾼
  `DemoRun`을 만들고 `WorldSwitch`가 같은 엔진·여정·세대로 세계만 바꿔 끼운다(세계 전환이 아니다). PT에서 카드를 설명하고 누를 시간이다.
- 시연은 빈 학습 상태의 새 엔진(질문 수명은 배속만큼 늘림)으로 시작하고 기록·학습·여정을 저장하지 않는다. 시연을 켜면 실제 엔진과 여정을 옆에 두고,
  끄면 그대로 되돌린다(피로·화장실 시계가 이어진다). 다른 코드는 '시연 중인가'를 묻지 않고 세계의 정책(`persistsHistory` 등)을 묻는다.

## 7. 알려진 제약 (에뮬레이터)
- 주행 시간 API(Trip 계열)는 에뮬레이터가 지원하지 않아 앱이 기어·차속으로 직접 잰다.
- 좌석 점유 값은 에뮬레이터에서 믿을 수 없어서 탑승자는 사용자가 정한다(말로 설정 가능).
- 에뮬레이터를 오디오 없이 띄우므로 음성 인식은 녹음 입력(`sendAudio`)으로 확인한다(`tools/laptop/stt-pcm-check.sh`).
- 그 밖의 확인 결과는 `docs/kit-selftest.md`와 SPEC 8장.

AAOS 에너지 계약(API 34): [Android 14 VehiclePropertyIds](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android14-release/car-lib/src/android/car/VehiclePropertyIds.java), [권한 선언](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android14-release/service/AndroidManifest.xml). `RANGE_REMAINING`은 Float 미터, `INFO_FUEL_TYPE`은 Integer[]이며 kit은 int[]도 정규화한다. `CarPropertyValue.STATUS_AVAILABLE`일 때만 값을 전달하고 등록 실패(false)·오류·미지원은 기존 재연결 경로로 처리한다.

## 8. 심사에서 구분할 증빙

- 정체 `offset` 기준은 SDK 자료만으로 확정하지 못했다. 이번 소스는 기준 미확정 시 정체 위치 판단을 보류한다. 기준을 얻은 뒤의 현재 위치/출발지 해석과 통과 시간(거리÷평균속도)도 관찰 기반 추정이다. TBT 누적 거리도 에뮬레이터 사례에서 맞춘 것이며 모든 경로에 대한 SDK 보장이 아니다.
- 도심 후보의 경로 미확인 주행 시간은 `(직선거리 × 1.3) ÷ 가정 속도`로 추정하고 주차·도보·유턴 시간을 더한다. 속도 제한만으로 나들목 전용 도로를 확정하거나 실제 최단 도착을 보장하지 않는다.
- 현재도 P 3분+문 열림을 이용으로 추정해 전원 화장실 게이지를 초기화한다. 이용자 확인은 미구현이다. 출발 전 승차의 문 열림은 제외하며, 출발 전 제안 수락 뒤 문 열림은 이용으로 추정한다.
- 제안 정차의 베이즈 갱신은 사건 수 a에 0.5를 더하고 b에는 t^k를 더하는 설계다. 전체 우도를 0.5로 가중한 갱신과 다르다. 정차 간격은 실제 필요 시각의 정답이 아니며 이용자·목적을 확인하지 않은 자동 기록은 오인할 수 있다.
- 차량 예비거리(연료 50km·EV 20km)는 설계값이다. 정적 에너지 시설 목록은 커넥터 적합성·가동·빈자리·도착 SOC를 보장하지 않는다.
- LLM은 선택적 문장 해석이며 응답이 없으면 비활성화한다. 핵심 결정은 베이즈 추정·규칙이다. 작성 텍스트 코퍼스의 분류 성능과 실제 마이크→STT→내비 성공률은 다르다. 후자와 LLM 실제 응답은 미검증이다.
- `docs/plan/data/test_counts.json`의 `commit`은 집계 시 HEAD이며 각 JUnit 실행 소스나 제출 APK의 빌드 커밋이 아니다. 현재 소스로 만든 2026-11-15 APK 식별·모듈별 실행 기록은 [README 상태표](../README.md#최종-제출물-상태-2026-11-15), 설치는 [심사 시연 안내](demo/judge-runbook.md)에 있다.
