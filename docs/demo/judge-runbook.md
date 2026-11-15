# 쉼표 심사 시연 안내

2026-11-15 문서 점검. 제출 APK로 재현하는 절차이며 이번 문서 작업에서 APK 설치·실차·마이크 시연을 새로 수행하지는 않았다. 설치 명령·권한·장면별 예상 카드는 [README 빠른 시작](../../README.md#심사자-빠른-시작--제출-apk-설치)을 따른다.

## 파일과 소스 식별

제출 파일: `apk/차곡_쉼표_release.apk`. 바이너리 Manifest 확인값은 `dev.shimpyo.app`, 버전 `0.1`, versionCode `1`이다.

- SHA-256: `4bd85506a17ea422129fdeb17ebe66f56923c0468a9c6db2ef5924a528df99fd`
- 이 저장소의 최종 소스로 2026-11-15 노트북에서 만든 release 빌드다. APK를 다시 만들면 해시를 갱신한다.
- 서명 유효성은 SDK의 `apksigner verify --verbose`로 별도 확인할 수 있다.

## VC를 로컬에서 설정하기

에뮬레이터와 함께 제공되는 Virtual Console을 열고 차량 시뮬레이터를 켜 둔다. 차량 제어의 속도와 기어를 바꾼다. **차속 전용 카드가 없는 버전**에서는 아래 HTTP 제어를 사용할 수 있다. 개발자 노트북 호스트 이름이나 SSH 키는 필요 없다.

```sh
adb forward tcp:8088 tcp:8088
curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Drive"
curl -X PUT "http://127.0.0.1:8088/vehicle/controls?speed=100&accelerator=20&brake=0"
```

Windows PowerShell에서는 `curl` 대신 `curl.exe`를 쓴다. VC 창을 닫아 세션이 끊기면 제어가 반영되지 않을 수 있다. 계기판에서 기어·차속이 실제 바뀌었는지 먼저 확인한다. stop/departure 또는 종료 시:

```sh
curl -X PUT "http://127.0.0.1:8088/vehicle/controls?speed=0&accelerator=0&brake=0"
curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Park"
```

## 5분 핵심 확인 순서

1. README대로 APK 설치·현재 사용자 권한 허용. VC D·100km/h·도어 닫힘·제동/방향지시등 꺼짐.
2. `adb shell am start -S --user current -n dev.shimpyo.app/.MainActivity --es demo holiday` 실행. 시연 배지와 접근 중인 옥산휴게소 확인. 약 1분 안팎 기다려 질문이 나타나면 ‘들르기’를 터치한다. 내비의 목적지/경유지 목록을 확인한다. 이는 터치→실제 내비 검증이다.
3. 같은 VC 설정에서 `adb shell am start -S --user current -n dev.shimpyo.app/.MainActivity --es demo charge` 실행. 화장실·충전 질문이 하나인지, 잔여 km의 현재 표시와 다음 충전소 도착 때의 이유 칩을 구분하는지 본다.
4. VC P·0km/h 뒤 `adb shell am start -S --user current -n dev.shimpyo.app/.MainActivity --es demo departure` 실행. 출발 전 질문을 확인한다. 질문 기준은 지금 다녀오면 모델상 여유가 **20분 이상** 늘어나는 경우다. 개인의 실제 필요가 그만큼 늦어진다는 뜻은 아니다.
5. 종료는 `adb shell am start -S --user current -n dev.shimpyo.app/.MainActivity --es demo off`. VC P·0km/h 복원.

| 추가 장면 | VC | 실행 extra / 화면 조작 | 예상 |
|---|---|---|---|
| 오후 졸음 | D·90km/h | `--es demo fatigue` | 휴식 권유·앞 쉼터 카드 |
| 도심 급해 | D·40km/h | `--es demo urgent`, ‘급해요’ | 후보 2곳, 경로 미확인 시간은 추정 |
| 휴게소 정차 | P·0km/h | `--es demo stop`, 계획 탭 | ‘여기서 쉬고 출발하세요’, 가상 시간 10배 |

모든 extra는 README와 동일한 `am start -S --user current -n dev.shimpyo.app/.MainActivity` 뒤에 붙인다. 연속 재시작은 에뮬레이터 부하를 높이므로 장면마다 응답이 안정된 뒤 다음으로 넘어간다.

## 입력 출처와 아직 입증하지 않은 것

| 항목 | 입력·검증 범위 |
|---|---|
| 위치·시각·정체·경로·탑승자 | 앱 시연 주입. 실제 운전·실시간 정체 위치 검증 아님 |
| 기어·차속·도어 | VC → 차량 SDK. 설정 뒤 계기판과 앱 변화 확인 필요 |
| charge 잔여거리 | 앱 주입. 실제 AAOS 잔량 수신은 `demo off`에서 VC Remaining Range로 별도 점검 |
| 경유지/길안내 | 실제 NaviHelper 명령·네트워크. 시연 위치와 실제 내비 출발 위치는 다를 수 있음 |
| 음성 | 기존 기록은 녹음 PCM의 `sendAudio` 인식. 실제 마이크 전체 흐름과 다름. 실패하면 터치로 대체하고 실패를 기록 |
| LLM | 선택적 해석 보조. 실제 응답 검증 없음; 핵심 결정은 규칙·확률모델 |
| 정차·학습 | 시연 결과는 실제 기록에 저장되지 않음. 문 열림만으로 화장실 이용자·필요 시각을 알 수 없음 |
| 영상 | 기존 무음 MP4. 자막 명세 수정만으로 기존 MP4가 바뀌지는 않음 |

## 막혔을 때

- **화면이 이전 장면이다:** `-S`와 `--user current` 확인. 운전자 프로필이 아닌 user 0에서 실행하지 않았는지 `adb shell am get-current-user`로 확인한다.
- **주행 질문이 안 뜬다:** VC 시뮬레이터·D·차속, 권한, 제동·회전 신호를 확인한다. holiday/charge는 접근 시간이 필요하다. departure는 P에서만 나온다.
- **업데이트 설치 실패:** 기존 설치와 서명이 다를 수 있다. 데이터 삭제·삭제 설치 전에 별도 에뮬레이터 사용을 권한다.
- **내비 오류/다시 시도:** 네트워크·내비 앱 응답·실제 목적지/경유지 반영 여부를 확인한다. 앱 질문이 떴다는 이유만으로 내비 성공으로 세지 않는다.
- **문을 열었는데 게이지가 예상과 다르다:** 제출 APK와 개선 소스의 정차 처리 버전 차이를 확인한다. 개인별 이용 확인 없이 전원 이용 완료를 증명할 수 없다.

제출 전 남은 증빙: 실제 빌드 커밋과 APK 해시 연결, 마이크 종단 확인, 수정 자막의 영상 렌더링·재생 확인, 제출 URL 공개 접근 확인. 이 안내는 해당 검증을 완료했다는 보고서가 아니다.
