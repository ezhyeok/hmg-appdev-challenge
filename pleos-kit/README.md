# pleos-kit

Pleos Connect SDK(Vehicle, NaviHelper, TTS, STT, FusedLocation)와 AAOS Car API를 코루틴/Flow로 감싼 공통 모듈이다. 에뮬레이터에서 확인한 동작 규칙을 코드로 옮겨, 앱에서는 규칙을 신경 쓰지 않고 쓸 수 있게 했다.

## kit이 대신 지켜 주는 규칙

| 규칙 (에뮬레이터에서 확인) | kit에서 처리하는 곳 |
|---|---|
| Vehicle·NaviHelper 호출은 호출 스레드를 수 초간 막는다. 메인 스레드에서 부르면 ANR | 모든 호출을 SDK 전용 스레드에서 실행 (`SdkThreads`). 앱은 어느 스레드에서든 suspend 함수로 호출 |
| 내비 앱이 화면 앞으로 오면 Activity만 있는 앱은 얼려져서 이벤트를 받지 못한다 | `PleosForegroundService`를 상속한 서비스에 로직을 둔다 |
| 제어 API는 성공 콜백이 와도 값이 안 바뀔 수 있다 | `ReadWriteSignal.setAndConfirm()`이 값을 다시 읽어 확인 |
| 내비 조회는 호출해야 응답 이벤트가 오고, 요청·응답을 짝짓는 ID가 없다 | `NaviKit`이 조회를 한 번에 하나씩 보내고 응답 이벤트를 기다려 돌려준다 |
| 내비 경로 요청을 연달아 보내면 경로가 취소되는 일이 있었다 | `NaviKit`이 명령 사이에 3초 간격을 둔다 |
| `onRouteStarted`가 앱에 오지 않는다 | `NaviKit.startRoute()`가 `naviMode`를 조회해 길안내 시작을 확인 |
| 차속·위치 권한은 실행 중에 요청해야 한다 | `PleosPermissions.requestIfNeeded()` |
| 영역(area)과 값 범위는 차종마다 다르다 | `hvacAreas()`, `windowAreas()` 등 capability 조회 함수로 얻는다 |

## 구성

| 파일 | 내용 |
|---|---|
| `VehicleKit` | Vehicle SDK. 자주 쓰는 신호를 `ReadSignal`/`ReadWriteSignal`로 제공. 그 밖의 API는 `call`, `call2`, `observe`로 감싼다 |
| `CarState` | AAOS Car API. 기어, 차속, 주행 중 UX 제한을 `StateFlow`로 제공 |
| `NaviKit` | NaviHelper. 조회(`routeState`, `tbt`, `waypoints`, `tripEstimate` 등)와 경로 변경(`startRoute`, `addWaypoint`, `cancelRoute`) |
| `TtsKit`, `SttKit` | 음성 합성·인식(ON_DEVICE 기본). `speak()`는 재생이 끝날 때까지 기다린다 |
| `LocationKit` | FusedLocation. 마지막 위치, 위치 갱신 Flow, 근접 알림 Flow |
| `PleosForegroundService` | 주행 중 계속 살아 있어야 하는 로직을 두는 서비스의 바탕 |
| `PleosPermissions` | 실행 중 요청이 필요한 권한 처리 |

## 사용 예

```kotlin
class DriveService : PleosForegroundService() {
    override val notificationTitle = "My App"
    private val vehicle by lazy { VehicleKit(this) }
    private val car by lazy { CarState(this) }
    private val navi by lazy { NaviKit(this) }
    private val tts by lazy { TtsKit(this) }

    override fun onServiceStarted() {
        serviceScope.launch {
            car.connect()
            vehicle.connect()

            // 조회와 구독
            val area = vehicle.hvacAreas().first()
            val recirculation = vehicle.recirculation(area)
            val now: Boolean? = recirculation.get()
            launch { recirculation.changes().collect { on -> /* 값이 바뀔 때마다 */ } }

            // 제어: 실제로 반영됐는지까지 확인
            val applied: Boolean = recirculation.setAndConfirm(false)

            // 주행 상태
            launch { car.drivingRestricted.collect { restricted -> /* 주행 중이면 화면 조작을 줄인다 */ } }

            // 내비: 길안내 없이 거리·시간·도착 시 배터리만 조회
            navi.connect()
            val trip = navi.tripEstimate(TripRequest(37.5547, 126.9707, "", "서울역", ""))

            // 음성: 재생이 끝날 때까지 기다린다
            tts.connect()
            tts.speak("환기를 위해 외기 모드로 바꿨어요")
        }
    }
}
```

kit에 전용 래퍼가 없는 Vehicle API는 범용 어댑터로 감싼다. 콜백 인자가 하나면 `call`, 둘이면 `call2`, 리스너 등록·해제 쌍은 `observe`를 쓴다.

```kotlin
val light = vehicle.raw.getLight()
val switch = vehicle.call<LightSwitch?>("getCabinLightSwitch") { ok, fail -> light.getCabinLightSwitch(ok, fail) }
vehicle.call2<SeatArea, MoveControl?>("moveSeatBackrestAngle") { ok, fail ->
    vehicle.raw.getSeat().moveSeatBackrestAngle(area, MoveControl.BACKWARD, ok, fail)
}
```

새 API를 감쌀 때는 먼저 `docs/contest/03_SDK_API_레퍼런스/`에서 시그니처와 권한을 확인한다.

## 신호와 권한

| kit 함수 | 필요한 Manifest 권한 |
|---|---|
| `recirculation`, `hvacPower`, `hvacTemperatureC`, `hvacFanSpeed` 조회 | `pleos.car.permission.CAR_CLIMATE` |
| 위 신호의 제어(`set`, `setAndConfirm`) | `pleos.car.permission.CONTROL_CAR_CLIMATE` |
| `doorPosition` | `pleos.car.permission.CAR_DOORS` |
| `windowPosition` 조회 / 제어 | `pleos.car.permission.CAR_WINDOWS` / `CONTROL_CAR_WINDOWS` |
| `seatOccupancy` | `pleos.car.permission.CAR_SEATS` |
| `seatBackrestPosition` / `moveSeatBackrest` | `pleos.car.permission.CAR_SEATS` / `CONTROL_CAR_SEATS` |
| `cruiseControl`, `tripDrivingTimeMin`, `brakePedal` | `pleos.car.permission.CAR_DRIVE` |
| `evStateOfCharge` | `pleos.car.permission.CAR_ENERGY` |
| `CarState.gear` | `android.car.permission.CAR_POWERTRAIN` |
| `CarState.speedMps` | `android.car.permission.CAR_SPEED` (실행 중 요청) |
| `NaviKit` 조회 / 경로 변경 | `pleos.car.permission.NAVI_ROUTE` / `NAVI_ROUTE_SEARCH` |
| `TtsKit` / `SttKit` | `pleos.car.permission.TTS_SERVICE` / `STT_SERVICE` |
| `LocationKit` | `pleos.car.permission.FUSED_LOCATION` (실행 중 요청) |

포그라운드 서비스 권한(`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`)은 kit의 Manifest가 선언한다. SDK 권한은 앱이 쓰는 것만 앱 Manifest에 선언한다.

## 에뮬레이터 확인 결과

`app`의 `KitSelfTest`가 kit의 각 기능을 실제로 호출해 본다. 실행 방법과 최신 결과는 `docs/kit-selftest.md`에 있다.
