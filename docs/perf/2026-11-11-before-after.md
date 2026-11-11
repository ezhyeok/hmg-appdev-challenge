# 앱 성능 전후 비교 (2026-11-11)

Pleos Connect Emulator 2.1.0, release APK, portrait 창(내비 옆), Virtual Console 기어 D·100km/h. 같은 에뮬레이터 세션에서 이전(46981d8)과 이후를 차례로 설치해 `tools/laptop/measure-app.sh`로 쟀다.

| 조건 | 항목 | 이전 (46981d8) | 이후 |
|---|---|---|---|
| 귀성길 시연 | 정차 후보 데이터 로딩 (앱 시작부터) | 8.2초 | 1.4초 (표 읽기 56ms) |
| | 메모리 PSS (Java / Native) | 159MB (45 / 84) | 69MB (17 / 16) |
| | 내비 요청 (약 70초) | 24회 (TBT 14, 경로 상태 5, 목적지 5) | 5회 (경로 상태) |
| | 앱 CPU (코어 1개 기준) | 4.5% | 3.9% |
| | 화면 프레임 (30초) | 50, 지연 74% | 46, 지연 74% |
| 시연 없이 주행 | 정차 후보 데이터 로딩 | 12.0초 | 0.6초 |
| | 메모리 PSS | 88MB | 63MB |
| | 내비 요청 (약 70초) | 24회 | 5회 |
| | 앱 CPU | 1.2% | 1.0% |
| | 화면 프레임 (30초) | 4 | 2 |

- 로딩·메모리: JSON 13MB를 행마다 객체로 읽던 것을 열 단위 이진 표(stops.bin, 압축 없이 메모리 매핑)로 바꿨다. JSON과 같은 후보가 나오는지는 StopTableTest가 6만여 행 전부를 비교한다.
- 내비 요청: 경로 없는 안전운전 모드(SAFE_GUIDING)를 길안내로 보지 않고, 길안내 중에는 TBT를 적응형(5~20초)으로 받는다. 에뮬레이터 내비는 경로가 없어 경로 상태만 15초마다 묻는다.
- 화면 프레임: 시연은 시각이 7.2배로 흘러 거리 글자가 0.5초마다 바뀌므로 매초 다시 그리는 것이 맞다. 프레임당 시간(p50 32ms)은 에뮬레이터 그리기 경로의 비용이다.
- 엔진 계산(CoachEngine.update)은 서버 JVM에서 1회 약 25µs로 측정 대상에서 뺐다.

측정 원문:

```
Success
Starting: Intent { cmp=dev.shimpyo.app/.MainActivity (has extras) }
== before (46981d8) (pid 6212)
load: 8.2 s after process start | stop assets loaded (local): 59552
cpu: 4.5% of one core (avg of 4 x 5 s)
frames (30 s): Total frames rendered: 50;Janky frames: 37 (74.00%);50th percentile: 32ms;90th percentile: 48ms;95th percentile: 61ms;
memory: Java 45 MB Native 84 MBPSS 159 MB 
navi requests (60 s): GetDestinationInfo 5, GetRouteStateInfo 5, GetTBTInfo 14, 
== after v2, holiday demo (pid 31915)
load: 1.4 s after process start | stop table loaded: 60084 in 56 ms
cpu: 3.9% of one core (avg of 4 x 5 s)
frames (30 s): Total frames rendered: 46;Janky frames: 34 (73.91%);50th percentile: 32ms;90th percentile: 48ms;95th percentile: 69ms;
memory: Java 17 MB Native 16 MBPSS 69 MB 
navi requests (60 s): GetRouteStateInfo 5, 

Success
Starting: Intent { cmp=dev.shimpyo.app/.MainActivity (has extras) }
== before, driving no demo (pid 16595)
load: 12.0 s after process start | stop assets loaded (local): 59552
cpu: 1.2% of one core (avg of 4 x 5 s)
frames (30 s): Total frames rendered: 4;Janky frames: 3 (75.00%);50th percentile: 48ms;90th percentile: 57ms;95th percentile: 57ms;
memory: Java 30 MB Native 22 MBPSS 88 MB 
navi requests (60 s): GetDestinationInfo 5, GetRouteStateInfo 5, GetTBTInfo 14, 
== after v2, driving no demo (pid 18814)
load: 0.6 s after process start | stop table loaded: 60084 in 56 ms
cpu: 1.0% of one core (avg of 4 x 5 s)
frames (30 s): Total frames rendered: 2;Janky frames: 1 (50.00%);50th percentile: 77ms;90th percentile: 77ms;95th percentile: 77ms;
memory: Java 15 MB Native 15 MBPSS 63 MB 
navi requests (60 s): GetRouteStateInfo 5, 
```
