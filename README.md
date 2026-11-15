# 쉼표 — 사람용 주행가능시간

쉼표는 **휴식·화장실 권유 시점을 추정해, 쉴 곳이 끊기기 전 마지막 쉼터를 묻는 Pleos Connect 앱**입니다. ‘사람용 주행가능시간’은 개인의 안전 운전 한계나 요의 발생 시각이 아닙니다. 시간·탑승자 설정·경로·차량 신호를 휴식 규칙과 확률모델에 넣은 권유 기준이며, 개인 정확도와 실제 예방 효과는 미검증입니다.

- **마지막 쉼터**: 추정 권유 시점과 알려진 경로의 정체·쉼터 공백을 비교합니다. 휴식과 화장실이 같은 곳이면 질문 하나로 합칩니다.
- **차도 사람도 한 번에**: 주유를 화장실·휴식과 묶습니다. 전기차는 충전 시설을 찾습니다. AAOS 잔여거리에서 예비거리(연료 50km·EV 20km, 설계값)를 뺀 범위로 판단합니다. 정적 시설 정보는 가동·빈자리·커넥터 적합성을 보장하지 않습니다.
- **일상 길**: 출발 전 한마디와 가까운 화장실, 급해 모드를 제공합니다. 경로를 확인하지 못한 후보의 시간은 거리 기반 추정입니다.
- **차량 문맥**: 기어·도어·차속·주행 제한을 이용합니다. 문 열림만으로 누가 화장실에 갔는지, 언제 필요했는지는 알 수 없습니다.
- **기기 안 판단**: 베이즈 추정·휴식 규칙이 정차를 결정합니다. 선택적 LLM은 음성 해석 보조이며 응답이 없으면 비활성화됩니다. 마이크 전체 흐름과 LLM 응답은 별도 검증 대상입니다.

## 심사자 빠른 시작 — 제출 APK 설치

소스 빌드 없이 [`apk/차곡_쉼표_release.apk`](apk/차곡_쉼표_release.apk)를 사용합니다. 파일명의 ‘차곡’은 개인 참가 활동명입니다. 자세한 실패 대처와 확인 범위는 [심사 시연 안내](docs/demo/judge-runbook.md)에 있습니다.

APK 식별: **0.1 / versionCode 1** · SHA-256 `4bd85506a17ea422129fdeb17ebe66f56923c0468a9c6db2ef5924a528df99fd`. 이 저장소의 최종 소스로 2026-11-15 노트북에서 만든 release 빌드다(앱·kit 단위 테스트 통과, 에뮬레이터에서 시연 장면 확인).

1. Pleos Connect Emulator **2.1.0 / API 34**를 Cold boot로 실행합니다. 운전자 프로필의 홈과 내비가 응답할 때까지 기다립니다. ADB에 여러 기기가 있으면 아래 모든 `adb`에 `-s <serial>`을 붙입니다.
2. 에뮬레이터가 실행되는 컴퓨터에서 설치하고 **현재 운전자 사용자**에게 권한을 줍니다.

```sh
adb devices
adb shell am get-current-user
adb install --user current -r "apk/차곡_쉼표_release.apk"
adb shell pm grant --user current dev.shimpyo.app android.car.permission.CAR_SPEED
adb shell pm grant --user current dev.shimpyo.app android.car.permission.CAR_ENERGY
adb shell pm grant --user current dev.shimpyo.app pleos.car.permission.FUSED_LOCATION
```

3. Virtual Console(VC) 창과 차량 시뮬레이터를 켜 둡니다. 도어 닫힘, 제동 0, 방향지시등 꺼짐으로 시작합니다. **holiday/charge는 기어 D·100km/h**, **fatigue는 D·90km/h**, **urgent는 D·40km/h**, **stop/departure는 P·0km/h**로 둡니다. 차속 전용 카드 대신 차량 제어를 사용합니다. 로컬 HTTP 제어 예시는 시연 안내에 있습니다.
4. 아래 명령 중 하나를 실행합니다. **`-S`는 필수**입니다. 이미 열린 앱에는 extra가 전달되지 않을 수 있으므로 장면을 바꿀 때만 한 번씩 재시작합니다.

```sh
adb shell am start -S --user current -n dev.shimpyo.app/.MainActivity --es demo holiday
adb shell am start -S --user current -n dev.shimpyo.app/.MainActivity --es demo charge
adb shell am start -S --user current -n dev.shimpyo.app/.MainActivity --es demo fatigue
adb shell am start -S --user current -n dev.shimpyo.app/.MainActivity --es demo urgent
adb shell am start -S --user current -n dev.shimpyo.app/.MainActivity --es demo stop
adb shell am start -S --user current -n dev.shimpyo.app/.MainActivity --es demo departure
```

| 장면 | 예상 카드·조작 | 확인할 것 |
|---|---|---|
| `holiday` | 옥산휴게소, 정체 전 마지막 화장실·쉼터 질문. 진입로 2~5km에 접근할 때까지 기다림 | ‘들르기’ 터치 → 내비 새 길안내 또는 경유지. 마이크 확인과 별개 |
| `charge` | 옥산휴게소 화장실·충전 질문 하나, ‘충전 주행가능거리’와 다음 충전 때 잔여 km | 잔량은 시연 주입값이며 VC 실제 잔량 검증이 아님 |
| `fatigue` | 휴식 권유와 앞 쉼터 카드 | ‘졸음 발생 시각’이 아닌 권유 단계 |
| `urgent` | ‘급해요’를 눌러 가까운 화장실 후보 | 주행 중 후보 2곳. 거리 기반 예상과 실제 경로는 다를 수 있음 |
| `stop` | 계획 탭 ‘여기서 쉬고 출발하세요’ | P 유지, 시연 시간이 10배로 흐름. 정차 기록과 실제 화장실 이용은 별개 |
| `departure` | 주행 탭 ‘출발 전에 다녀오세요’ | P에서만 질문. 지금 다녀오면 모델상 여유가 20분 이상 늘 때(설계값) |

5. 주행 장면은 P이면 질문이 내려갑니다. 수락 후 내비 명령은 네트워크와 실제 SDK를 사용합니다. 첫 심사는 터치 ‘들르기’로 확인하고, 음성 ‘응’은 별도로 시험합니다. 마이크→STT→의도→내비 전체 성공을 이미 확인했다고 주장하지 않습니다.

```sh
adb shell am start -S --user current -n dev.shimpyo.app/.MainActivity --es demo off
```

시연을 끈 뒤 VC를 P·0km/h로 되돌립니다. 시연 중 위치·시각·경로·정체는 앱이 주입하며, 실제 기어·차속·도어는 VC에서 받습니다. 시연의 정차·학습은 실제 기록에 저장하지 않습니다. 질문이 떠 있는 동안 시연 진행은 느려집니다. ‘시연 모드’ 배지와 실제 길안내를 구분해 확인합니다.

## 영상과 검증 범위

[시연 영상](docs/demo/shimpyo-demo.mp4)은 약 3분 13초의 무음 에뮬레이터 녹화입니다. 위치·정체 주입, 실제 VC, 실제 내비가 섞인 데모이며 실차 효능 검증이 아닙니다. `tools/video/spec.json`은 수정된 자막 원본입니다. MP4를 다시 렌더링하지 않았다면 자막 수정은 기존 영상에 반영되지 않습니다. 제출 전 파일과 편집 명세를 함께 확인합니다.

## 테스트 환경과 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator 2.1.0, API 34, RAM 4GB, GPU 가속 권장 |
| 화면 | 2560×1440, 160dpi. 가로 두 창 배치에서 점검 |
| SDK | Vehicle 2.3.2, NaviHelper 3.0.5, TTS·STT 3.3.1, FusedLocation 1.1.3, LLM 2.1.3.2 |
| 권한 | 위 3개 런타임 권한, 나머지 `pleos.car.permission.*`는 Manifest 선언. `CAR_INFO`는 일반 권한 |
| 네트워크 | 내비 경로 계산에 필요 |
| VC | 창·시뮬레이터를 켜 두어야 차량 제어가 반영됨 |

## 소스에서 빌드 — 심사 APK 설치와 별개

Android SDK·Pleos 의존성을 준비한 개발 환경에서 실행합니다. 저장소의 `settings.gradle.kts`가 요구하는 Gradle 홈 설정도 확인합니다. 직접 시험할 때는 debug 빌드를 먼저 사용합니다.

```sh
./gradlew :app:assembleDebug
./gradlew -p rest-engine test
./gradlew :app:testDebugUnitTest :pleos-kit:testDebugUnitTest
```

release 재빌드는 **본인의 서명키**가 필요합니다. 루트의 gitignored `keystore.properties`에 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`를 설정한 뒤 `./gradlew :app:assembleRelease`를 실행합니다. 키 설정이 없으면 미서명 release가 만들어집니다. 제출 APK의 서명키는 저장소에 없으며, 다른 키로 만든 APK는 기존 설치에 덮어쓸 수 없습니다. 별도 에뮬레이터로 시험하는 편이 안전합니다. release에는 debug 전용 점검 코드와 권한이 포함되지 않습니다.

## 기획서 수치와 그림 재현

```sh
python3 -I -B tools/plan/analysis.py
./gradlew -p rest-engine runSim
python3 -I tools/plan/count_tests.py
python3 -I tools/plan/build_docx.py
```

`count_tests.py`는 테스트를 실행하지 않고 기존 JUnit XML을 집계합니다. JSON의 `commit`은 **집계 시 HEAD**이며 실행 커밋·APK 빌드 커밋 증명이 아닙니다. 앱·kit의 보관 XML은 새 실행 결과와 구분합니다. 시뮬레이션은 모형 내 비교로 실사용 효과가 아니며 가정과 불리한 결과도 기획서에 적었습니다.

## 구조와 라이선스

- 순수 Kotlin 판단 엔진: `rest-engine/`, 실행 계층: `rest-engine/coach/`
- SDK 래퍼: `pleos-kit/`, 화면·SDK 어댑터: `app/`
- [구조](docs/ARCHITECTURE.md), [제품 사양](docs/product/SPEC.md), [데이터 출처](tools/data/README.md)

모든 권리 보유. 공모전 심사·열람을 위해 공개합니다([LICENSE](LICENSE)). 내장 데이터와 제3자 라이선스는 [NOTICE.md](NOTICE.md)에 있습니다. 공모전·SDK 문서 원문은 저작권자의 자료라 저장소에 포함하지 않습니다.
