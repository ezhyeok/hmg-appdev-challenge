# pleos-kit 에뮬레이터 점검 결과

`app`의 `KitSelfTest`가 pleos-kit의 각 기능을 Pleos Connect Emulator 2.1.0에서 실제로 호출한 결과다. 마지막 실행은 2026-11-11, 노트북(AC 전원, `-gpu host`), Virtual Console 시뮬레이터를 켠 상태였다.

## 실행 방법

개발 서버에서 실행한다.

```bash
bash tools/laptop/sync.sh                      # 코드를 노트북으로 복사
ssh hmg-laptop 'powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\User\hmg-appdev-challenge\repo\tools\laptop\run-app.ps1 -BuildOnly'
ssh hmg-laptop 'powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\User\hmg-appdev-challenge\repo\tools\laptop\start-emulator.ps1'
bash tools/laptop/wait-emulator.sh             # 부팅 후 안정될 때까지 대기 (가동 4분 이상)
tools/vc/vc.sh up                              # 차량 시뮬레이터 켜기 (없으면 제어가 반영되지 않는다)
ssh hmg-laptop 'powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\User\hmg-appdev-challenge\repo\tools\laptop\run-app.ps1 -SkipBuild -SelfTest -Route -WaitSeconds 170'
```

결과는 logcat 태그 `PleosApp`에 `TEST|항목|OK/FAIL|상세`로 남고, 노트북의 `logs\app-result.log`에도 저장된다.

점검은 **debug 빌드에만 있다** (`app/src/debug/java/dev/shimpyo/app/devtools`, `run-app.ps1`은 debug를 설치한다). 점검은 운전석 등받이·창문·조명·설정 온도를 실제로 움직이고 `-Route`는 길안내를 새로 시작하므로, 제출용(release) APK에는 코드가 없고 `--ez selftest true`·`--ez verify true`를 받지 않는다 (`DevHooks`, `DevHooksTest`). release가 받는 인텐트 옵션은 시연 전환 `--es demo <이름|off>` 하나다. 제출용 APK를 만들면 `tools/laptop/devhooks-check.sh`로 확인한다 (2026-11-12: 예전 release는 `--ez selftest true`로 점검 27항목을 돌리고 공조 전원을 켠 채로 두었다).

## 결과

| 항목 | 결과 | 비고 |
|---|---|---|
| 내기순환 조회·구독·제어 | 통과 | 값 변경과 복원을 다시 읽어 확인 |
| 공조 전원 조회 | 통과 | |
| 설정 온도 제어 | 통과 | 22 → 25 → 22°C. 범위 15~30. **공조 전원이 꺼져 있으면 조회부터 실패한다** |
| 풍량 조회 | 통과 | 범위 1~8 |
| 창문 위치 제어 | 통과 | 0 → 2 → 0. 에뮬레이터의 범위는 0~10 |
| 시트 등받이 이동 | 부분 통과 | `FORWARD`/`BACKWARD`/`STOP` 명령대로 움직인다(Virtual Console에서 40 → 0 → 100 확인). **위치 조회와 구독은 계속 0만 준다** |
| 실내등 제어 | 통과 | OFF → ON |
| 앰비언트 라이트 밝기 제어 | 통과 | 100 → 0. 범위 0~100 |
| 조향각 조회 | 통과 | |
| 도어 영역·위치 | 통과 | 영역 6개(1·2열 좌우, 후방, 후드) |
| 좌석 점유 조회 | 값은 오지만 신뢰 불가 | SDK는 대부분 true, Virtual Console은 모두 비어 있음 |
| 크루즈 상태 조회 | 통과 | `ENABLED` |
| 주행 시간 조회 | **미지원** | `TripCurrent`/`TripCumulative`/`TripSinceRefill` 모두 `ERROR_NOT_SUPPORTED_PROPERTY`. 주행 시간은 앱이 기어·차속으로 직접 잰다 |
| 브레이크 페달, 배터리 잔량 | 통과 | |
| 기어, 차속, 주행 제한 (AAOS) | 통과 | Virtual Console 변경이 그대로 들어온다 |
| 위치 (FusedLocation) | 통과 | |
| TTS `speak` | 통과 | 재생 완료까지 대기 |
| STT `listen` | 호출만 확인 | 에뮬레이터에 마이크 입력이 없어 결과는 null. 실제 인식은 미확인 |
| 내비 현재 위치·경로 상태 조회 | 통과 | |
| 내비 `tripEstimate` | 통과 | 27.5km, 약 31분, 도착 시 SOC 95% |
| 내비 `startRoute` | 통과 | 요청 후 약 8초에 `ROUTE_GUIDING` 확인 |
| 내비 `addWaypoint`, `waypoints` | 통과 | |
| 내비 `tbt` | 통과 | 길안내 시작 직후에는 빈 목록, 몇 초 뒤부터 채워진다 |
| 내비 `cancelRoute` | 통과 | |
