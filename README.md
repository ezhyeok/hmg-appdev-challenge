# 쉼표 — 사람용 주행가능시간

**차에는 연료 경고등이 있는데, 사람에게는 없다.** 쉼표는 차에 탄 사람의 '주행가능시간'(운전자의 졸음 한계, 탑승자의 다음 화장실까지 남은 시간)을 계산하고, 그 시간이 바닥나기 전에 갈 수 있는 곳을 먼저 알려 주는 Pleos Connect 앱입니다. 고속도로 사망자의 71.6%가 졸음·주시태만(2026년 상반기)이라, 쉬어야 할 때 쉴 곳이 앞에 있게 하는 것이 목표입니다.

- 화장실 게이지와 피로 게이지(2시간 휴식 리듬 + 졸림 곡선, 시간대·수면 보정)
- 정체·쉼터 공백 구간 앞에서 '마지막 쉼터' 질문: 운전자의 피로 한계나 탑승자의 화장실 마감 전에 닿는 마지막 휴게소·졸음쉼터를 진입로 2~5km 전에 한 번 묻는다 ("응" 하면 내비 경유지. 둘이 같은 곳이면 "마지막 화장실이자 쉼터예요")
- **차도 사람도 한 번에**: 차량 주행가능거리를 세 번째 마감으로 합쳐 화장실·휴식·충전/주유를 한 정차에 묶습니다. EV는 충전기가 있는 곳만 권하고, 두 사람 게이지 옆에 차의 남은 km를 보입니다. 잔량을 모르면 에너지 권고는 숨깁니다.
- 급해 모드: 진행 방향, 주차 가능, 지금 영업 중인 화장실
- 매일의 길에서도: 목적지를 정하고 서 있을 때 도착까지가 화장실 게이지보다 길면 "출발 전에 다녀오세요" 한 번, 시내에서는 차를 세울 수 있는 가까운 공중화장실·주유소·공영주차장을 다음 화장실로 (고속도로·도시고속도로 위에서는 길가 화장실을 내지 않는다)
- 탑승자 설정(아이, 영아, 임신부, 어르신, 과민성 방광, 과민성 장, 반려견)과 평소 화장실 간격
- 운전 스타일 변화와 연속 주행 시간으로 휴식 권유, 원하면 외기 전환
- 기어 P와 문 열림으로 정차 자동 기록, 개인 학습은 기기 안에서만
- 근거가 있는 문맥만 보정: 식사 정차 뒤, 밤에 잠든 아이 (근거 조사: `docs/research/need-covariates.md`)

## APK

[`apk/차곡_쉼표_release.apk`](apk/차곡_쉼표_release.apk) (release 빌드, 서명됨. 개인 참가이며 파일명의 팀명 자리는 활동명 ‘차곡’).

## 실행 방법

1. Pleos Connect Emulator v2.1.0(API 34, Automotive)을 Cold boot로 실행합니다.
2. APK를 설치합니다: `adb install -r apk/차곡_쉼표_release.apk`
3. 앱을 실행하고 차속·위치·차량 에너지 권한을 허용합니다. 앱은 포그라운드 서비스로 계속 동작합니다.
4. Virtual Console에서 기어·차속·도어·조향을 바꾸거나, 내비에서 목적지를 설정하면 게이지와 안내가 바뀝니다.

### 시연 모드

시연 영상: [`docs/demo/shimpyo-demo.mp4`](docs/demo/shimpyo-demo.mp4) (3분 13초, 에뮬레이터 화면 녹화와 자막: 귀성길 마지막 화장실, 전기차의 화장실·충전 마지막 기회, 휴게소 정차, 일상의 출발 전 한마디. 다시 만들기: `tools/video/`).

에뮬레이터는 실제로 달리지 않아서 위치 이동과 정체 구간은 앱의 시연 모드로 넣습니다.

```
adb shell am start -n dev.shimpyo.app/.MainActivity --es demo holiday   # 귀성길 마지막 화장실 (7살 아이 동승, 옥산휴게소 뒤 1시간 정체)
adb shell am start -n dev.shimpyo.app/.MainActivity --es demo charge    # 차도 사람도: EV 50km, 옥산에서 화장실·충전 한 질문
adb shell am start -n dev.shimpyo.app/.MainActivity --es demo fatigue   # 오후 졸음 (연속 주행 95분)
adb shell am start -n dev.shimpyo.app/.MainActivity --es demo urgent    # 밤 11시 급해 모드 (영아 동승)
adb shell am start -n dev.shimpyo.app/.MainActivity --es demo stop      # 휴게소 정차: 자동 기록과 피트스톱 계획
adb shell am start -n dev.shimpyo.app/.MainActivity --es demo departure # 출발 전 (아침 8시 10분 분당, 7살 아이, 목적지까지 1시간 3분)
adb shell am start -n dev.shimpyo.app/.MainActivity --es demo off       # 시연 끄기 (실제 신호로 돌아간다)
```

시연은 기어 D + 차속이 있을 때 질문이 나옵니다(주차 중에는 주행 질문을 거둡니다). '출발 전' 시연만 기어 P에서 묻습니다(출발하면 거둡니다). 질문 카드가 떠 있는 동안에는 시연 시각과 위치가 함께 4배 느리게 흘러, 카드를 설명하고 누를 시간이 있습니다(가상 속도는 같아서 판단은 바뀌지 않습니다). 시연 중의 정차·학습은 실제 기록에 남지 않습니다.

실제 차량 잔량 연동은 Virtual Console의 Remaining Range 카드로 확인합니다. `charge` 장면은 잔량을 시연 값으로 주입하고, 기존 `holiday` 등은 잔량을 섞지 않습니다.

차량 신호는 Virtual Console 시나리오로 함께 바꿉니다: `tools/vc/vc.sh up` 다음 `tools/vc/vc.sh play tools/vc/shimpyo-fatigue.vc` (Virtual Console HTTP API, 에뮬레이터 8088 포트).

## 테스트 환경과 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0, API 34, RAM 4GB, GPU 가속 권장 |
| 화면 규격 | 2560×1440 가로, 160dpi (에뮬레이터 AVD `hw.lcd.width/height/density`). 앱은 가로 두 창 배치에서 동작을 확인했다 |
| SDK | Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1, SpeechToText 3.3.1, FusedLocation 1.1.3, LLM 2.1.3.2 |
| 권한 | Manifest의 `pleos.car.permission.*` 선언. 차속(`android.car.permission.CAR_SPEED`)·차량 에너지(`android.car.permission.CAR_ENERGY`)와 위치(`pleos.car.permission.FUSED_LOCATION`)는 실행 중 허용 |
| 네트워크 | 내비 경로 계산에 필요 |
| Virtual Console | 차량 시뮬레이터가 켜져 있어야 제어 결과가 반영됩니다 (페이지를 열어 두거나 `tools/vc/vc.sh up`) |

## 빌드

```
./gradlew :app:assembleRelease                              # 서명 설정은 저장소에 없음 (keystore.properties)
./gradlew -p rest-engine test                               # 판단 엔진 + 코치 계층 테스트 (JVM, 안드로이드 불필요)
./gradlew :app:testDebugUnitTest :pleos-kit:testDebugUnitTest   # 화면 모델·저장소·SDK 규칙 테스트
```

release 빌드에는 개발용 점검 도구(SDK 자가 점검 등)와 그 권한이 들어가지 않습니다(`app/src/debug`).

## 기획서 수치와 그림 재현

기획서의 분석과 시뮬레이션은 저장소 코드로 다시 만들 수 있습니다.

```
python3 -I -B tools/plan/analysis.py     # 경부고속도로 화장실 공백, 공중화장실 시설 비율 → docs/plan/data/analysis.json
./gradlew -p rest-engine runSim          # 판단 엔진으로 돌린 귀성길 시뮬레이션(조건·정책마다 5,000회, 약 20분)
                                         #   → docs/plan/data/effect_sim.json, engine_curves.json (-PsimTrips=500이면 빠르게)
python3 -I tools/plan/figures.py         # 기획서 그림 → docs/plan/assets/
```

## 구조

- `rest-engine/` 판단 엔진 (순수 Kotlin) — 설명: `rest-engine/README.md`
- `rest-engine/coach/` 코치 계층 (순수 Kotlin): 엔진을 차에서 돌리는 실행 계층. 코치 액터, 내비 추적, 정차 후보, 질문 상태기계, 시연/실제 전환 — 설명: `docs/design/coach-layer/`
- `pleos-kit/` Pleos SDK를 코루틴으로 감싼 공통 모듈 — 설명: `pleos-kit/README.md`
- `app/` 안드로이드 껍데기 (`dev.shimpyo.app`): 화면(Compose), 포그라운드 서비스, 코치 계층의 포트를 SDK로 구현한 어댑터 — 구조: `docs/ARCHITECTURE.md`, 사양: `docs/product/SPEC.md`
- 데이터 출처와 라이선스: `tools/data/README.md`

## 라이선스

모든 권리 보유. 공모전 심사와 열람을 위해 공개한다 ([LICENSE](LICENSE)). 내장 데이터와 제3자 구성요소의 라이선스는 [NOTICE.md](NOTICE.md)에 있다. 개발 중 참고한 공모전·Pleos 문서 원문(`docs/contest/`)은 저작권자의 자료라 저장소에 넣지 않았다.
