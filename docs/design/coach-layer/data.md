# 정차 후보 데이터와 조회 (DATA)

공공데이터·OSM으로 만든 정차 후보 표(`stops.bin`)와 그 위의 조회(`StopRepository`)다. 코치는 이 조회로
"진행 방향 앞쪽 휴게소·졸음쉼터"(20초마다), "지금 가까운 화장실"(급해 모드), "TBT 이름이 가리키는 공공데이터 행"을 얻는다.
2026-11-11 데이터 감사(#1 #2 #3 #5 #8 #9 #10 #11)를 이 문서의 규칙으로 닫았다.

## 1. 구성과 책임

| 위치 | 하는 일 |
|---|---|
| `tools/data/` (Python) | 원천 내려받기·정제·지오코딩·차로 방위·검증 → `app/src/main/assets/data/*.json` |
| `tools/data/motorway.py` | OSM 고속도로 차로 색인, **차로 사슬 따라가기**(`trace`, `progress_toward`), 선형 단순화·내보내기 |
| `tools/data/roadside.py` | 휴게소·졸음쉼터 방향 라벨 → 차로 방위(`hdg`) |
| `tools/data/validate.py` | 형식 + **같은 길 이웃과 어긋난 차로 방위** + 영업 시간 형식 + 고속도로 선형 |
| `rest-engine/.../stops/StopRows.kt` | JSON 행 → `StopRecord`(후보, 차로 방위, 고속도로, 영업 시간). 변환 규칙은 여기 하나 |
| `rest-engine/.../stops/StopTable.kt` | `stops.bin` 형식 2(열 단위 이진 표) 쓰기·읽기·**검사** |
| `rest-engine/.../stops/StopHours.kt` | 요일별 영업 시간, 한국 공휴일 |
| `rest-engine/.../stops/MotorwayIndex.kt` | **지도 맞춤**: 위치+방위 → 지금 달리는 고속도로 |
| `coach/StopRepository.kt` | `StopIndex` 포트 구현: `ahead`, `near`, `matchByName`, `currentRoad` |
| `coach/StopNames.kt` | TBT 이름 ↔ 데이터 이름 키, 이름의 시설 종류 |
| `app/.../data/StopAssets.kt` | APK의 `stops.bin`을 메모리 매핑으로 읽기. 깨진 표면 후보 없이 동작 |

데이터를 바꾸는 순서: `build.py` → `validate.py` → `./gradlew -p rest-engine buildStopTable` → `StopTableTest`
(JSON 전 행 = `stops.bin` 전 행). 자세한 실행법과 출처·라이선스는 `tools/data/README.md`.

## 2. 상태와 스레드

- `StopRepository`의 상태는 읽은 표와 색인 묶음 하나(`Loaded`: 표, 전체 격자, 고속도로 격자, `MotorwayIndex`, 이름 키)다.
  `ensureLoaded()`가 IO 스레드에서 한 번 만들고 `@Volatile` 참조 하나로 통째로 끼운다. 그 뒤로는 바뀌지 않으므로
  조회(`ahead`·`near`·`matchByName`)는 어느 스레드에서 동시에 불러도 된다(잠금 없음). 예전에는 이름 키 배열을 조회 중에
  채웠다(경합).
- 비용(JVM, 85곳): `ahead`(60km) 0.055~0.058ms, `near`(5km, 전 종류) 0.067~0.080ms로 예전(0.063~0.065 / 0.075~0.094ms)
  이하. 읽기: decode+검사 6ms, 색인 약 40ms. `StopRepositoryTest.queriesCostNoMoreThanBefore`가 크게 나빠지면 잡는다.

## 3. 규칙

### 3.1 차로 방위 (`hdg`, 감사 #1)

휴게소·졸음쉼터마다 "그 시설로 들어갈 수 있는 차의 진행 방위"를 둔다. 방향 라벨('부산방향')로 차로를 고르는 방법을 바꿨다.

- **예전**: 시설 옆 차로의 *그 자리 방위*와 목적지까지 직선 방위가 60° 안이면 라벨이 결정. 길이 산·도시를 돌아가면 틀린다:
  경부선은 대신졸음쉼터에서 2km 동안 북북동(31°)으로 달리고(부산은 남동), 호남선은 동광주에서 서남서(242°)로 달린다(천안은 북).
- **지금**: 시설 옆 두 차로(서로 120° 넘게 다른 가장 가까운 두 일방통행 차로)를 **OSM 차로 사슬을 따라 30km** 가 보고,
  목적지에 더 가까워지는 쪽(`progress = 직선거리 감소 / 주행거리`)이 라벨의 차로다. 사슬은 공유 노드로 잇는다(진입로가
  본선 중간 노드로 합류하는 것까지). 반대 라벨(쌍의 다른 방향 라벨, 졸음쉼터는 'X기점')이 있으면 `p(목적지) − p(기점)`으로
  판단해서, 목적지가 너무 가까워 못 쓰는 라벨(순천졸음쉼터 '순천방향')도 기점(부산)에서 멀어지는 차로로 정한다.
  결정 조건: 두 차로의 점수 차 ≥ 0.3(라벨 하나) / 0.6(라벨 둘), 긴 사슬 ≥ 3km, 짧은 사슬 ≥ 0.5km(고속도로 끝 구간).
  결정 못 하면 예전처럼 "시설이 오른쪽에 있는 차로"(우측통행).
- 방위 값은 그 차로의 ±150m 구간 방위(`heading_along`). 휴게소 방위 변화는 최대 5°(구간 평활)였다.
- 같은 이름의 두 방향은 같은 방위일 수 없다. 남으면 비운다(예전 규칙 유지).
- 설치현황 목록의 노선명을 바뀐 이름·구간 표기와 맞춘다(`서울외곽선` = `수도권제1순환선`, `남해선` = `남해선(순천부산)`).
  덕분에 구리남양주졸음쉼터 방향을 설치현황·좌표·OSM이 모두 말하는 `판교방향`으로 고쳤고 이정이 40곳 더 채워졌다.

결과(행 단위): 대신 부산방향 209→30°·서울방향 31→211°(위치도 OSM 노드와 맞게), 동광주 천안방향 61→242°, 순천졸음쉼터
149→329°(라벨, OSM 위치), 칠곡 242/63°·유성 21°(비어 있던 것), 구리남양주 일산→판교방향.
`validate.py` 이웃 검사(같은 길·같은 방향 1~25km 이웃의 1/d 가중 원형 평균과 90° 넘게 다르면 / 같은 이름 쌍이 500m 안
120°, 3km 안 90° 미만이면): 예전 데이터 8건 → 0건.

재현(감사 스크립트 `hdg_effect.py`): 봉산→대신 남행 4곳 모두 예전에는 서울방향 행만 보였고 지금은 부산방향 행만,
천안방향(대덕·동광주 1km 앞)은 예전 0곳 → 지금 동광주, 순천방향은 예전에 동광주(반대 차로)가 보였고 지금은 안 보인다.
OSM 차로 위 위치로 다시 확인: `StopRepositoryTest.approachesToTheFixedSheltersSeeTheirOwnSide`.

### 3.2 지금 달리는 고속도로 (`MotorwayIndex`, 감사 #3)

목적지가 없을 때 `ahead()`는 직선거리 순이었고 부르는 쪽이 앞 6곳만 내비로 보정해서, 경부선 기흥에서 남행할 때 6곳 중
4곳이 다른 고속도로였다. 지금은 **지금 달리는 고속도로의 시설을 먼저** 둔다(그 안에서 거리 순, 그다음 다른 길).

- 지금 길은 지도 맞춤으로 정한다: `motorways.json`(OSM 이름 있는 고속도로 20,329개 way를 10m로 단순화, 53k점)을
  `stops.bin`에 float로 싣고, 차 위치 45m 안·진행 방위 35° 안 차로 중 가장 가까운 것의 도로. 아니면 모름(-1, 거리 순 유지).
- 시설 행으로 추정하는 방법(근처 시설들의 차로 방위·방위각이 맞는 도로)을 먼저 재 봤다: 시설이 방향별 15~20km에 하나라
  OSM 차로 위 3,000곳에서 맞음 77%·틀림 8%(엄격하게 63%·2.3%)가 한계. 지도 맞춤은 흔들린 표본 1,500곳(위치 8m, 방위 8°)에서
  맞음 99.9%·틀림 0.1%, 고속도로 옆 150m~2km 300곳은 한 곳도 고속도로로 보지 않았다(`MotorwayIndexTest`).
- 재현: 경부선 부산방향 실제 차로 위 39곳(기흥→옥천, 감사와 같은 구간), 화장실 있는 앞 6곳 중
  같은 길 109 / 다른 길 125(예전 순서) → **같은 길 205 / 다른 길 29**. 감사 원래의 39곳은 13점 꺾은선 위라 39곳 중 5곳만
  차로 위에 있어서 대부분 '모름'(123/111 → 131/103). (감사가 적은 250/508은 앞 6곳이 아니라 전체 개수다.)

### 3.3 방향 거르기 (`ahead`, `near`, `matchByName`)

| 상황 | 규칙 | 이유 / 테스트 |
|---|---|---|
| 방위 모름(`heading == null`) | 방향 거르기 없음 | 서 있는 차의 방위는 믿을 수 없다. **부르는 쪽은 기어 P·저속이면 null을 넘긴다** |
| 300m보다 먼 시설 | 진행 방향 ±75° 원뿔 | 예전과 같다 |
| 300m 안 시설 | 진행 축으로 50m보다 뒤면 뺀다(`ahead`) | 지나친 휴게소는 진출로도 지났다. 예전에는 300m 안이면 뒤라도 남김(#11). `facilityJustPassedIsDroppedButOneAbeamIsKept` |
| 반대 차로 | 차로 방위가 진행 방향과 90° 넘게 다르면 뺀다(2km보다 멀면 '나→시설' 방위와 비교) | 예전과 같다 |
| 시설 안 | 차가 **고속도로 차로 위가 아니고** 휴게소·졸음쉼터 250m 안이면 그 시설은 반대 차로·뒤쪽 검사를 안 한다 | 주차장에 반대로 세운 차가 자기 휴게소를 잃지 않게(#10). 반경만으로는 안 된다: 방향별 시설 93%는 반대 차로가 시설 점 150m 안(중앙값 51m)을 지나가서 지나가는 차에게 건너편 시설이 다시 나온다. `carParkedFacingTheOtherWayKeepsTheRestAreaItIsIn`, `passingTheOppositeTwinDoesNotOfferIt` |
| `near`의 directionOk | 150m보다 멀고 진행 방향과 100° 넘게 다르면 false | 예전과 같다(시설 안이면 true) |

남은 한계: 차로 위 45m 안에 세운 졸음쉼터 차가 반대 방향을 보고 서 있으면 반대 차로와 맞춰질 수 있다. 확실한 신호는 속도라서
정차 중에는 방위를 넘기지 않는 것이 부르는 쪽 계약이다(코치 담당 A/E에 알림).

### 3.4 이름 맞추기 (`StopNames`, `matchByName`, 감사 #2 #5)

- 키 = 방향 표기와 시설어를 지운 고유 부분. 방향은 괄호·띄어 쓴 낱말·시설어 뒤에 붙인 것·앞에 쓴 것 모두 지운다
  ("안성휴게소 부산방향", "서울방향 안성휴게소", "안성휴게소부산방향", "오산졸음쉼터 상행" → 안성/오산).
- **시설어만 있는 이름("졸음쉼터")은 키가 null**이고 `matchByName`은 빈 목록을 준다(결정). 빈 문자열 키로 "모두와 맞음"이나
  "조용히 아무것도 안 맞음"이 되지 않게 한 것이다. 데이터의 이름 없는 OSM 쉼터 4곳도 이름으로는 찾지 않는다(위치·방향 조회로는 나온다).
- 찾는 종류는 이름의 시설어가 정한다(`kindFor`): 쉼터를 SERVICEAREA로 보내도 같은 이름 휴게소(옥산졸음쉼터(서울방향) ↔
  옥산휴게소(부산방향) 0.3km)와 맞추지 않는다.
- 키가 같은 행이 없으면 데이터 키가 TBT 키의 앞부분인 가장 긴 행(2글자 이상): "서울만남의광장휴게소" → 서울만남,
  "덕평자연휴게소" → 덕평. 거꾸로는 안 한다("안성" ↛ "안성맞춤").
- 방위를 주면 반대 차로 행을 뺀다. 같은 이름이 3km 안에 둘 이상인 행 350개 모두 각자 차로로 3km 앞에서 다가가면 자기
  행만 나온다(`sameNamePairsResolveByLaneHeadingWithoutANaviRequest`) → 같은 이름마다 requestTrip을 보낼 필요가 없다.
  남는 행이 없으면 빈 목록(반대 차로 행을 대신 주지 않는다).

### 3.5 영업 시간 (`StopHours`, 감사 #8)

| 원천 | 해석 | 근거 |
|---|---|---|
| `09:00-09:00` (open == close) | 24시간 | 그런 화장실은 경찰서·지구대·소방서·주유소(교대 시각을 적음) |
| `05:00-24:20` | 다음 날 00:20까지 | 역 화장실 18곳. 예전에는 '모름' |
| `HH:MM-23:59` / `24:00` | 그날 끝까지 | 원천이 하루 끝을 23:59로 적는다 |
| 주차장 `days`(w/s/h) | 운영 요일이 아니면 휴무, 그날은 `oh`/`ohs`/`ohh` | 평일만 611곳은 주말 휴무, 1,774곳은 토·공휴일 시간이 다름. 예전에는 매일 평일 시간 |
| 주차장 `00:00-00:00` | 운영 요일 밖에만 나온다 → 휴무 | 요일 규칙이 먼저 |
| 날 종류 | 토요일 / 일요일·공휴일(공휴일 시간) / 평일, 시각대 Asia/Seoul | 양력 고정 공휴일+대체공휴일은 해마다 계산, 음력 명절·선거일은 2026년만 목록 |

자정을 넘는 구간은 그날 시간표로 본다(전날 밤 구간이 넘어오는 것은 보지 않는다). 2027년 이후 음력 공휴일은 `KoreanCalendar.LISTED`에 더할 것.

### 3.6 표 검사 (`StopTable.decode`, 감사 #9)

- 머리말의 개수(행·도로·시간표·선·점)를 상한과 **파일 크기**에 먼저 맞춰 본 뒤에만 배열을 잡는다 → 깨진 개수로 GB를 잡다가
  OutOfMemoryError(앱의 catch가 못 잡던 것)가 나지 않는다.
- 종류 바이트, 좌표(유한·범위), 도보 거리, 차로 방위(0~360), 도로·시간표 색인, 문자열 오프셋(연속·단조), 시간표 값, 선 오프셋·점
  좌표를 모두 검사한다. 하나라도 벗어나면 `StopTableFormatException`(IOException). 잘린 파일도 같은 예외.
- 그래서 **읽힌 표는 어느 행을 꺼내도 예외가 없다**. 바이트 하나를 바꾼 모든 경우(위치마다 4값)에 "분명히 실패 또는 모든 행
  안전"을 확인한다(`StopTableDecodeTest.anySingleByteCorruptionEitherFailsClearlyOrYieldsASafeTable`). 예전에는 종류 바이트
  하나가 깨지면 질의 중에 예외가 나서 후보 루프가 끝났다.
- 형식 1은 읽지 않고 분명히 실패한다(앱은 자기 APK의 표만 읽는다).

## 4. `stops.bin` 형식 2

```
"SHPT", version=2, n, roadCount, scheduleCount, lineCount, pointCount
kind:byte[n] lat,lon:double[n] flags:int[n] walk:double[n] lane:short[n] road:short[n] schedule:short[n]
idOffset,nameOffset:int[n+1] roadOffset:int[roadCount+1] blobLength:int blob(UTF-8: id, 이름, 도로명)
scheduleFlags:byte[scheduleCount] scheduleTimes:short[scheduleCount*6]   (평일·토·공휴일 여는/닫는 분, -1 모름, -2 휴무)
lineRoad:short[lineCount] lineFlags:byte[lineCount] lineStart:int[lineCount+1] pointLat,pointLon:float[pointCount]
```

60,084행 + 고속도로 63개 + 선 20,329개(53,297점) = 5.05MB (형식 1은 4.53MB). 영업 시간은 서로 다른 시간표만 한 번씩 담는다.

## 5. 시나리오 표 → 테스트

| 시나리오 | 테스트 |
|---|---|
| 대신·동광주 접근(실제 차로 위) | `StopRepositoryTest.approachesToTheFixedSheltersSeeTheirOwnSide` |
| 같은 길 먼저 / 국도에서는 거리 순 / 방위 없음 | `aheadRanksTheExpresswayUnderTheCarFirst`, `offTheExpresswayTheOrderIsDistance` |
| 경부 39곳 앞 6곳 (실제 차로 + 감사 꺾은선) | `gyeongbuReplayKeepsTheTopSixOnTheRoad` |
| 건너편 쌍둥이 지나가기 / 반대로 세운 차 | `passingTheOppositeTwinDoesNotOfferIt`, `carParkedFacingTheOtherWayKeepsTheRestAreaItIsIn` |
| 지나친 시설 / 옆의 시설 | `facilityJustPassedIsDroppedButOneAbeamIsKept` |
| 같은 이름 쌍, 방향 표기 | `matchByNameUsesTheLaneHeadingForSameNamePairs`, `sameNamePairsResolveByLaneHeadingWithoutANaviRequest` |
| 쉼터를 SERVICEAREA로 / 시설어만 / 앞부분 맞춤 | `shelterLabelledAsServiceAreaMatchesTheShelterNotTheRestArea`, `namesWithoutAnIdentityMatchNothingAndPrefixesOnlyOneWay` |
| 이름 정규화 표 | `StopNamesTest` |
| 지도 맞춤 정확도·국도 | `MotorwayIndexTest` |
| 영업 시간(24시간·자정 넘김·요일·공휴일·시각대) | `StopHoursTest` |
| 깨진 표 | `StopTableDecodeTest` |
| JSON = stops.bin (도로·시간표·선 포함) | `StopTableTest` |
| 데이터 차로 방위 | `validate.py` (이웃·쌍 검사) |
| 비용 | `queriesCostNoMoreThanBefore`, `loadCost` |

## 6. 다른 담당에게 (포트·계약)

- `StopIndex` 포트는 바꾸지 않았다. `StopRepository.currentRoad(lat, lon, heading)`을 더했다(지금 고속도로 이름, 디버그·화면용).
- `ahead()` 결과 순서가 바뀌었다(같은 길 먼저). 앞 N곳만 보정하는 쪽(CandidateProvider)은 그대로 `take(N)`하면 된다.
- 방위 계약: 정차 중(기어 P·저속)이거나 방위 출처가 대체값(내비 0.0 등)이면 `heading = null`을 넘긴다(PositionTracker, 감사 #10).
- `matchByName`에 방위를 넘기면 같은 이름 쌍마다 requestTrip을 보낼 필요가 없다(현재 CoachService는 방위를 안 넘긴다).
- TBT 기회의 종류도 이름의 시설어를 따르는 것이 맞다: `StopNames.kindFor(name, tbtKind)` (RouteOpportunities.extract 담당 A).
