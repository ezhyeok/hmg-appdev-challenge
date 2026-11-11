# 쉼표 — 사람용 주행가능시간

**차에는 연료 경고등이 있는데, 사람에게는 없다.** 쉼표는 차에 탄 사람의 '주행가능시간'(다음 화장실·휴식이 필요해질 때까지 남은 시간)을 계산하고, 그 시간이 바닥나기 전에 갈 수 있는 곳을 먼저 알려 주는 Pleos Connect 앱입니다.

- 화장실 게이지와 피로 게이지
- 정체·화장실 공백 구간 앞에서 '마지막 화장실' 알림
- 급해 모드: 진행 방향, 주차 가능, 지금 영업 중인 화장실
- 탑승자 설정(아이, 영아, 임신부, 어르신, 과민성 방광, 과민성 장, 반려견)
- 운전 스타일 변화와 연속 주행 시간으로 휴식 권유, 원하면 외기 전환
- 기어 P와 문 열림으로 정차 자동 기록, 개인 학습은 기기 안에서만

## APK

`apk/` 폴더의 `팀명_쉼표_release.apk` (release 빌드). 제출 전에 팀명을 넣어 만든다.

## 실행 방법

1. Pleos Connect Emulator v2.1.0(API 34, Automotive)을 Cold boot로 실행합니다.
2. APK를 설치합니다: `adb install -r apk/<파일명>.apk`
3. 앱을 실행하고 차속·위치 권한을 허용합니다. 앱은 포그라운드 서비스로 계속 동작합니다.
4. Virtual Console에서 기어·차속·도어·조향을 바꾸거나, 내비에서 목적지를 설정하면 게이지와 안내가 바뀝니다.

### 시연 모드

에뮬레이터는 실제로 달리지 않아서 위치 이동과 정체 구간은 앱의 시연 모드로 넣습니다.

```
adb shell am start -n dev.shimpyo.app/.MainActivity --es demo holiday   # 귀성길 마지막 화장실 (아이 동승, 40분 정체)
adb shell am start -n dev.shimpyo.app/.MainActivity --es demo fatigue   # 오후 졸음 (연속 주행 95분)
adb shell am start -n dev.shimpyo.app/.MainActivity --es demo urgent    # 밤 11시 급해 모드 (영아 동승)
```

차량 신호는 Virtual Console 시나리오로 함께 바꿉니다: `tools/vc/vc.sh up` 다음 `tools/vc/vc.sh play tools/vc/shimpyo-fatigue.vc` (Virtual Console HTTP API, 에뮬레이터 8088 포트).

## 테스트 환경과 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0, API 34, RAM 4GB, GPU 가속 권장 |
| SDK | Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1, SpeechToText 3.3.1, FusedLocation 1.1.3, LLM 2.1.3.2 |
| 권한 | Manifest의 `pleos.car.permission.*` 선언. 차속(`android.car.permission.CAR_SPEED`)과 위치(`pleos.car.permission.FUSED_LOCATION`)는 실행 중 허용 |
| 네트워크 | 내비 경로 계산에 필요 |
| Virtual Console | 차량 시뮬레이터가 켜져 있어야 제어 결과가 반영됩니다 (페이지를 열어 두거나 `tools/vc/vc.sh up`) |

## 빌드

```
./gradlew :app:assembleRelease      # 서명 설정은 저장소에 없음 (keystore.properties)
./gradlew -p rest-engine test       # 판단 엔진 단위 테스트 (JVM)
```

## 구조

- `rest-engine/` 판단 엔진 (순수 Kotlin, 단위 테스트) — 설명: `rest-engine/README.md`
- `pleos-kit/` Pleos SDK를 코루틴으로 감싼 공통 모듈 — 설명: `pleos-kit/README.md`
- `app/` 앱 (`dev.shimpyo.app`) — 구조: `docs/ARCHITECTURE.md`, 사양: `docs/product/SPEC.md`
- 데이터 출처와 라이선스: `tools/data/README.md`
