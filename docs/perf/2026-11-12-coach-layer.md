# 코치 계층(CoachCore) 성능 비교 (2026-11-12)

Pleos Connect Emulator 2.1.0, release APK, portrait 창(내비 옆, `demo-smoke.sh … --split`), Virtual Console 기어 D·100km/h.
같은 에뮬레이터 세션에서 이전과 이후를 번갈아 설치해 `tools/laptop/measure-app.sh`로 쟀다.

- **이전** = main `e295d5f` (UI 작업까지 들어간 main, 옛 `CoachService` 871줄)
- **이후** = `wt-core` (코치 액터 + 얇은 서비스). 1·2차는 내비 조회 간격을 바꾸기 전, 3차부터는 최종(서 있는 내비는 TBT를 경로 조회만큼)

11-11 측정(`2026-11-11-before-after.md`)과 다른 조건: 이번에는 **Pleos 내비가 길안내 중**이었다(판교 → 안성휴게소, 경유지 2곳). 그래서 TBT를
조회하고(11-11에는 경로가 없어 경로 상태만 물었다), 내비의 주행 화면(`ai.umos.drivingview`, 약 97%)과 내비 앱(약 86%)이 에뮬레이터 CPU를 많이 써서
프레임 시간이 크게 흔들린다. 그래서 같은 조건의 이전/이후를 번갈아 여러 번 쟀다.

## 귀성길 시연

| 항목 | 이전 (4회) | 이후, 조회 간격 바꾸기 전 (3회) | 이후 최종 (2회) |
|---|---|---|---|
| 앱 CPU (코어 1개 기준) | 3.5 / 4.2 / 5.1 / 5.8% (평균 4.7) | 7.4 / 3.9 / 5.2% | 3.5 / 4.7% (평균 4.1) |
| 메모리 PSS | 75 / 75 / 74 / 76MB | 75 / 74 / 73MB | 75 / 72MB |
| 화면 프레임 (30초, p50) | 45·34ms / 45·57ms / 43·69ms / 54·77ms | 48·129ms / 51·34ms / 47·57ms | 48·36ms / 43·48ms |
| 내비 요청 (약 70초 창) | 20~21 (TBT 12~13, 경로 상태 4, 목적지 4) | 24 (14 / 5 / 5) | **15 (5 / 5 / 5)** |

## 시연 없이 주행 (실제 길안내)

| 항목 | 이전 (2회) | 이후 최종 (2회) |
|---|---|---|
| 앱 CPU | 1.1 / 1.2% | 1.4 / 1.4% |
| 메모리 PSS | 71 / 70MB | 72 / 72MB |
| 화면 프레임 (30초) | 2 / 2 | 4 / 2 |
| 내비 요청 (약 70초 창) | 21 (13 / 4 / 4) | **14 (4 / 5 / 5)** |
| 정차 후보 표 로딩 (앱 시작부터) | 0.6초 | 0.6~1.1초 |

## 읽는 법

- **내비 요청**: 이전 서비스의 루프는 일한 뒤 5초를 쉬어서 실제로는 TBT 약 5.6초, 경로 약 16.7초 간격으로 밀렸다(분당 약 18번). 새 `NaviTracker`는
  규칙 그대로 TBT 5초·경로 15초(분당 20번)라 처음 측정에서는 오히려 많았다. 에뮬레이터 내비는 판교에 서 있어(남은 거리가 그대로) TBT도 바뀌지 않는데
  5초마다 묻고 있었으므로, '내비가 서 있으면 TBT를 경로 조회만큼만'(`NaviPoll.naviFrozen`, 테스트 먼저)으로 바꿨다. 최종은 분당 12번(TBT 4, 경로 4,
  목적지 4)으로 이전보다 약 35% 적다. 실제 차에서 내비가 움직이면 예전과 같이 5초(가까움·보정 못 믿음)·20초(멀고 보정 믿음)다.
- **CPU**: 시연은 이전 평균 4.7%, 최종 4.1%로 같은 수준(회차 사이 차이 3.5~7.4%가 더 크다). 실제 주행은 최종이 0.25%p(코어 1개의 1초에 약 2.5ms)
  높게 나왔다. 틱마다 후보를 투영해 올리고(UI 감사 #19), 1분마다 여정 맥박을 저장하고, 후보 루프가 1초마다 입력을 확인하는 몫이다. 같은 시간에
  내비 앱에 보내는 요청은 3분의 1 줄었다(내비 프로세스의 CPU는 이 표에 없다).
- **메모리**: 같다(±2MB).
- **프레임**: 시연은 시각이 7.2배로 흘러 매초 다시 그리는 것이 맞다. 프레임 수는 같고, 프레임 시간(p50 34~129ms)은 내비 주행 화면이 에뮬레이터를
  다 쓰는 정도에 따라 흔들린다.

## 같은 세션에서 찾은 것

- `trafficCongestionTime`은 지나는 시간이 아니라 분 단위로 반올림한 늘어난 시간이었다(항목 35개 모두 60초). 이 경로의 실제 정체 목록을 그대로 넣으면
  20km 앞 기흥휴게소의 엔진 도착 예상이 1,203초 → 858초(90km/h, `CongestionArrivalTest`)로 바뀐다. 기기에서는 고친 뒤 `next: 기흥휴게소 20490m eta=862s`(100km/h).

## 측정 원문

```
== base e295d5f, holiday demo split, D 100, navi guiding (pid 4920)
cpu: 3.5% | frames 45, janky 88.89%, p50 34ms p90 73ms | Java 21 MB Native 18 MB PSS 75 MB | navi: GetDestinationInfo 4, GetRouteStateInfo 4, GetTBTInfo 13
== new wt-core (before frozen pacing), run 1 (pid 21723)
cpu: 7.4% | frames 48, janky 97.92%, p50 129ms p90 450ms | Java 21 MB Native 20 MB PSS 75 MB | navi: 5, 5, 14
== base run 2 (pid 5403)
cpu: 4.2% | frames 45, janky 93.33%, p50 57ms p90 105ms | Java 21 MB Native 20 MB PSS 75 MB | navi: 4, 4, 13
== new run 2 (pid 18869)
cpu: 3.9% | frames 51, janky 90.20%, p50 34ms p90 65ms | Java 20 MB Native 19 MB PSS 74 MB | navi: 5, 5, 14
== new run 3 (pid 5511)
cpu: 5.2% | frames 47, janky 95.74%, p50 57ms p90 133ms | Java 21 MB Native 18 MB PSS 73 MB | navi: 5, 5, 14
== base run 3 (pid 21482)
cpu: 5.1% | frames 43, janky 97.67%, p50 69ms p90 133ms | Java 20 MB Native 19 MB PSS 74 MB | navi: 4, 4, 13
== final F1, holiday (pid 4496)
cpu: 3.5% | frames 48, janky 85.42%, p50 36ms p90 65ms | Java 20 MB Native 20 MB PSS 75 MB | navi: 5, 5, 5
== base B4, holiday (pid 19742)
cpu: 5.8% | frames 54, janky 96.30%, p50 77ms p90 150ms | Java 21 MB Native 20 MB PSS 76 MB | navi: 4, 4, 12
== final F2, holiday (pid 31972)
cpu: 4.7% | frames 43, janky 93.02%, p50 48ms p90 97ms | Java 20 MB Native 18 MB PSS 72 MB | navi: 5, 5, 5
== final F3, real driving (pid 16031)
cpu: 1.4% | frames 4, p50 101ms | Java 20 MB Native 20 MB PSS 72 MB | navi: 5, 5, 4
== base B5, real driving (pid 32750)
cpu: 1.1% | frames 2, p50 85ms | Java 17 MB Native 20 MB PSS 71 MB | navi: 4, 4, 13
== final F4, real driving (pid 18053)
cpu: 1.4% | frames 2, p50 53ms | Java 18 MB Native 20 MB PSS 72 MB | navi: 5, 5, 4
== base B6, real driving (pid 799)
cpu: 1.2% | frames 2, p50 53ms | Java 17 MB Native 18 MB PSS 70 MB | navi: 4, 4, 13
```

(내비 요청 순서: GetDestinationInfo, GetRouteStateInfo, GetTBTInfo. 로딩은 모두 0.4~1.8초, 표 읽기 49~265ms.)
