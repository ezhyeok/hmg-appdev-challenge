# CLAUDE.md — 현대자동차그룹 차량용 앱 개발 공모전 (Code your App into Pleos App Market)

공모전 자료는 `docs/contest/`에 있다. 공모전 공식 사이트(appdevchallenge.auto-hmg.io), 온라인 설명회 영상, Pleos 개발자 문서(document.pleos.ai)를 2026-11-10(KST)에 수집해 정리한 것이다. 이 파일의 경로는 모두 repo 루트 기준이다.

## 먼저 읽을 파일
1. `docs/contest/00_핵심요약.md` — 일정, 제출물, 심사 기준, 규정 요약 (설명회 내용 반영)
2. `docs/contest/01_공모전/OT_설명회_정리.md` — 온라인 설명회 내용. 홈페이지에 없는 운영 규칙(SDK 승인 불필요, 실차 시연 없음, 생성형 AI 사용 조건, 외부 API 허용, 복수 출품, 혼합 팀 부문 등)이 여기에 있다
3. `docs/contest/01_공모전/접수양식/접수양식_원문.md` — 기획서(=접수양식) 8개 항목 구조. 실제 제출은 같은 폴더의 `contest-application-consent.docx`에 작성
4. `docs/contest/02_Pleos_개발가이드/pleos-connect/getting-started.md` — 프로젝트 생성 → 에뮬레이터 → Vehicle SDK 첫 호출. 단, 예제의 `Vehicle:2.3.1`은 Maven에 없으므로 2.3.2를 쓴다
5. `docs/contest/02_Pleos_개발가이드/INDEX.md` — 전체 가이드 목록
6. `docs/contest/03_SDK_API_레퍼런스/INDEX.md` — SDK별·패키지별 API 명세 (메서드 시그니처, 권한, 콜백). 코드 작성 전에 반드시 여기서 실제 시그니처를 grep해서 확인하고, 존재하지 않는 API를 지어내지 말 것

## 확정된 기술 조건
- 플랫폼: Pleos Connect (Android Automotive OS 기반 인포테인먼트). 앱은 일반 Android 앱 (Kotlin 권장, Java 가능)
- minSdk 28 이상 (차량 사이버보안 요건), Gradle 8.0 이상. 에뮬레이터는 Pleos Connect Emulator v2.1.0 (API 34, Automotive AVD, Cold boot, RAM 4GB)
- 에뮬레이터 이미지: Windows/Linux는 x86_64(`crp-sys-img.xml`), Mac은 arm64(`crp-arm64-sys-img.xml`). 설치 URL과 절차는 `docs/contest/02_Pleos_개발가이드/connect-guide/getting-started/application-development/setup-connect-sdk-emulator.md`
- Maven 저장소: `https://nexus-playground.pleos.ai/repository/maven-releases/` (로그인 없이 접근 가능)
- SDK 좌표 (Maven 최신 버전, 2026-11-10 확인)
  - `ai.pleos.playground:Vehicle:2.3.2`
  - `ai.pleos.playground:NaviHelper:3.0.5`
  - `ai.pleos.playground:TextToSpeech:3.3.1`
  - `ai.pleos.playground:SpeechToText:3.3.1`
  - `ai.pleos.playground:FusedLocation:1.1.3`
  - 참고: `ai.pleos.playground:LLM:2.1.3.2`도 있지만 API 레퍼런스만 공개됐고 공모전 안내에는 없는 SDK다
- Vehicle SDK 사용 패턴: `Vehicle(context).initialize()` → `vehicle.getXxx()`로 도메인 객체 획득 → `checkXxxCapabilities` / `getCurrentXxx` / `setXxx` / `registerXxx` 호출 → 결과는 onComplete/onFailed 비동기 콜백 → 사용 종료 시 `release()` (콜백이 끝나기 전에 release 금지)
- 권한: AndroidManifest에 `pleos.car.permission.*` 선언. 조회는 `CAR_*`, 제어는 `CONTROL_CAR_*`. 도메인별 권한은 `docs/contest/03_SDK_API_레퍼런스/Vehicle/_misc.md`와 각 API의 `@RequiresPermission` 참고
- AAOS 표준 Car API로 얻을 수 있는 데이터(속도, 기어, 외기온도, 연료레벨 등)는 표준 API를 우선 쓰고, Pleos SDK는 특화 기능에 쓴다 (`docs/contest/02_Pleos_개발가이드/pleos-connect/vehicle-app-planning-guide/pleos-connect-sdk-api.md`)
- 외부 API와 퍼블릭 네트워크 연결 사용 가능 (설명회)

## 개발 환경 메모 (2026-11-10 확인)
- 이 개발 서버에서는 Pleos Connect Emulator를 돌릴 수 없다. 에뮬레이터 실행과 검증은 사용자의 로컬 PC에서 한다
  - Google은 Android Emulator를 linux-x64, macOS(x64/arm64), windows-x64용으로만 배포한다. 이 서버는 aarch64 Linux라 공식 바이너리가 없다 (SDK repository XML 확인)
  - 이 서버 자체가 KVM 게스트 VM(Neoverse-N1)이고 중첩 가상화를 지원하지 않아 `/dev/kvm`이 없다. 하드웨어 가속 없이는 Android 14 Automotive를 실용적인 속도로 돌릴 수 없다
- 에뮬레이터 실행과 빌드는 사용자의 Windows 노트북에서 SSH로 한다. 노트북 작업은 `C:\Users\User\hmg-appdev-challenge` 안에서만 한다 (프로젝트 전용 SDK `sdk\`, `android-home\`, `gradle-home\`). 노트북용 스크립트는 `tools/laptop/` (`sync.sh`로 코드 복사, `start-emulator.ps1`, `run-probe.ps1`)
  - 노트북의 PowerShell 실행 정책이 Restricted라 스크립트는 `-ExecutionPolicy Bypass`로 실행한다
  - 최신 cmdline-tools의 `sdkmanager`는 "Android CLI"로 위임되며(사용 통계 수집이 기본으로 켜짐) Pleos 이미지 경로를 처리하지 못한다. 이미지는 `tools/laptop/install-image.ps1`로 설치한다
  - 노트북에서 Google·Pleos 서버로의 단일 연결 다운로드가 매우 느리다(초당 0.1~0.2MB). 큰 파일은 분할 병렬 다운로드를 쓴다
- SDK 검증용 앱: `tools/sdk-probe/` (각 SDK 호출 결과를 logcat `PleosProbe` 태그로 출력). 노트북에서 `run-probe.ps1`로 빌드·설치·실행하고 결과는 `logs\probe-result.log`(전체 logcat은 `probe-full.log`)
  - 에뮬레이터가 띄운 adb 서버가 에뮬레이터 로그 파일 핸들을 물려받아 계속 잡는다. 그래서 `start-emulator.ps1`은 실행할 때마다 새 로그 파일(`logs\emulator-날짜-시각.log`)을 쓴다
  - 노트북 RAM이 빠듯해서(여유 3~5GB) 에뮬레이터는 `-Memory 3072`로 띄우고, 부팅이 끝난 뒤에 빌드한다
  - 노트북이 4코어에 GPU 소프트웨어 렌더링이라, 내비가 길안내 화면을 그리는 동안 에뮬레이터가 과부하(load 30 이상)가 되어 SDK 호출이 수십 초씩 밀린다. 부팅 직후 빌드가 겹치면 `carwatchdogd`가 VHAL을 재시작시켜 앱 Activity가 종료되기도 한다. 이럴 때는 `run-probe.ps1 -SkipBuild`로 다시 실행한다
- GitHub private repo: https://github.com/ezhyeok/hmg-appdev-challenge (기본 브랜치 `main`)

## SDK 사용 조건 (설명회 기준, Playground 문서보다 우선)
- 공모전 개발 단계에서는 SDK 권한 신청이나 승인이 필요 없다. Vehicle, NaviHelper, STT, TTS, FusedLocation 모두 AndroidManifest에 문서의 uses-permission을 선언하기만 하면 에뮬레이터에서 호출할 수 있다
- Playground 문서(`pleos-connect/create-my-project.md`)의 "준비 중(Coming Soon)" 표기는 App Market 배포용 신청 절차 기준이다. 공모전 개발에는 해당하지 않는다
- Vehicle 제어 API: 에뮬레이터에서 `setHvacRecirculation`(`CONTROL_CAR_CLIMATE`)으로 값이 실제로 바뀌고 변경 이벤트도 온다 (2026-11-11 sdk-probe, Virtual Console 시뮬레이터가 켜진 상태). 시뮬레이터가 꺼져 있으면 성공 콜백만 오고 값은 그대로다. 다른 제어 API는 쓰기 전에 같은 방식으로 직접 확인할 것
- TTS/STT: SERVER·HYBRID 모드는 `registerApp()`에 Playground 프로젝트의 Client ID/Secret이 필요하고, ON_DEVICE 모드는 등록이 필요 없다 (각 SDK의 `app-registration.md`). 막히면 사무국에 문의
- 설명회에서 "현재 공개되지 않은 SDK도 공모전에 제공할 예정"이라고 했지만 구체적인 방식은 안내되지 않았다. 공개된 SDK 기준으로 설계할 것

## API 사용 시 주의 (레퍼런스 확인 결과)
- 차속과 기어는 Vehicle SDK에 없다 (`VehicleSpeedListener`는 2.3.2에서 삭제). AAOS `CarPropertyManager`의 `PERF_VEHICLE_SPEED`, `GEAR_SELECTION`을 쓴다. Virtual Console에 기어 카드는 있지만 차속 카드는 없다
- 텔테일 API(`checkTelltalesCapabilities`, `registerTelltales`, `getTelltalesInfo`)는 `PRIVATE_CAR_SAFETY`(signature|privileged) 권한이라 서드파티 앱에서 쓸 수 없다
- `getLowTirePressure`는 현재 공기압이 아니라 저압 경고 임계값(kPa)을 반환한다
- **SDK 호출은 백그라운드 스레드에서 한다.** Vehicle·NaviHelper 호출은 서비스 연결을 기다리며 호출 스레드를 수 초간 막는다(sdk-probe 측정, Vehicle 호출 합계 약 9초). 메인 스레드에서 부르면 ANR로 앱이 종료된다
- `android.car.permission.CAR_SPEED`, `pleos.car.permission.FUSED_LOCATION`은 위험(dangerous) 권한이다. Manifest 선언만으로는 SecurityException이 나므로 실행 중에 `requestPermissions`로 요청한다. adb로 줄 때는 AAOS 포그라운드 사용자(user 10)에게 줘야 한다: `adb shell pm grant --user current <패키지> <권한>`
- NaviHelper `requestRoute`를 보내면 Pleos 내비 앱이 포그라운드로 올라오고 실제 길안내가 시작된다. 경로 계산은 서버에서 하므로 네트워크가 필요하다
- **내비가 떠 있는 동안에도 이벤트를 받으려면 포그라운드 서비스가 필요하다.** Activity만 있는 앱은 내비가 앞으로 오면 cached 프로세스가 되어 Android 14 freezer에 얼려지고(logcat `ActivityManager: freezing <pid> <패키지>`), 그동안 Vehicle·NaviHelper 이벤트를 받지 못한다. 포그라운드 서비스에서 리스너를 유지하면 받는다 (sdk-probe `ProbeService`로 확인)
- NaviHelper 정보 조회(`getTBTInfo`, `getWaypointInfo`, `getDestinationInfo`, `getRouteStateInfo`, `getChargerOperatorInfo`)는 호출해야 응답 이벤트가 온다. 길안내 중 `onTBTInfo`는 앞으로의 회전 목록(type, distance, description)을 준다. `onRouteCancelled`, `onNavigationStatus`, `onDrivingInfo`, `onCurrentRoadSpeedLimit`는 내비가 먼저 보낸다. `onRouteStarted`는 내비 앱 로그에 `notifyRouteStarted`가 찍혀도 앱에서 받지 못했다(원인 미확인). 길안내 여부는 `RouteStateInfo.naviMode`(`ROUTE_GUIDING`/`SAFE_GUIDING`)로 판단할 것
- `requestTrip(TripRequest)`는 길안내를 시작하지 않고 `onTripInfo`로 거리(m), ETA(초), 도착 시 예상 SOC(%), 소모 에너지, 구간별 교통 정보를 준다 (성남→서울역: 27.5km, 약 31분, SOC 95%)
- 내비 요청을 1초 안에 연달아 보내면(trip, route, waypoint) 시작된 경로가 곧바로 취소되는 일이 있었다. 요청 사이에 응답을 기다릴 것
- NaviHelper에는 POI 검색 API가 없다. 목적지·경유지의 좌표와 poiId는 앱이 외부에서 구해야 한다. 요청은 Pleos 내비 앱으로 전달되고, 어떤 이벤트가 오는지는 내비 앱 구현에 따라 다르다
- 에뮬레이터에서 재현할 수 있는 차량 상태는 `docs/contest/02_Pleos_개발가이드/pleos-connect/app-test-guide/virtual-console-guide.md`의 카드 목록이 기준이다
- Virtual Console은 에뮬레이터 8088 포트의 HTTP API로도 조작할 수 있다 (`adb forward tcp:8088 tcp:8088`). 시연 시나리오를 스크립트로 재생할 수 있다
  - 차량 시뮬레이터는 브라우저 세션이 있을 때만 켜진다. 페이지를 열어 두거나 `POST /browser/session?client=<이름>`을 1초마다 보낸다(3초 동안 없으면 꺼짐). 꺼져 있으면 제어 API 결과와 VC 변경이 반영되지 않는다
  - `GET /vehicle/catalog`(조작 가능한 63개 항목과 요청 형식), `GET /vehicle/state`(현재 상태). 변경은 PUT + 쿼리스트링. 예: `/vehicle/gear?gear=Drive`, `/vehicle/controls?speed=40&accelerator=20&brake=0`, `/vehicle/body?door_fl=true`, `/vehicle/hvac?recirc=false`, `/vehicle/odometer?usage_time_min=120`
  - 문서의 카드 목록에는 차속이 없지만 `/vehicle/controls`로 차속을 바꿀 수 있다. 앱에서 확인한 것: 기어 D + 40km/h → `GEAR_SELECTION`=8, `PERF_VEHICLE_SPEED`=11.1m/s, `CarUxRestrictions` requiresDO=true(active=0x50). P로 돌리면 해제. 도어 열림·닫힘도 `registerDoorPosition` 리스너로 온다
  - 좌석 점유는 VC 상태(모두 비어 있음)와 SDK 조회값(대부분 true)이 달랐다. 쓰기 전에 다시 확인할 것
- 주행 규제 테스트: `adb shell cmd hmg_car_service --service drivingstate driving_regulation on` / `off` (`docs/contest/02_Pleos_개발가이드/pleos-connect/vehicle-app-developer-guide/adb-command-reference-guide.md`)

## 설계·구현 원칙
- 실차 시연은 없다. 모든 검증과 2차 PT 시연은 Pleos Connect Emulator + Virtual Console 기준으로 준비한다 (기어, 도어, 배터리 잔량 등 상태를 바꿔가며 앱 반응을 보여줄 수 있게)
- SDK를 많이 쓰는 것이 목적이 아니다. 차량 환경에서만 줄 수 있는 핵심 가치 하나를 정하고, 그 가치를 SDK로 실제 동작하게 만드는 것을 우선한다
- 차량 모델별로 지원 API가 다를 수 있으므로 항상 capability를 확인한 뒤 UI를 활성화한다 (`docs/contest/02_Pleos_개발가이드/pleos-connect/vehicle-app-planning-guide/api-compatibility-availability.md`)
- 주행 중 UX 제한: `CarUxRestrictions` 연동 필수 (`docs/contest/02_Pleos_개발가이드/pleos-connect/vehicle-app-developer-guide/driving-restriction-development-guide.md`, `docs/contest/02_Pleos_개발가이드/pleos-connect/vehicle-app-planning-guide/driver-distraction-guideline.md`). 심사 항목 "차량특화성"과 직결된다
- 로터리 컨트롤러(CCP), 오디오 포커스, IME 리사이즈 같은 차량 특화 동작은 `docs/contest/02_Pleos_개발가이드/pleos-connect/vehicle-app-developer-guide/` 참고
- UI는 `docs/contest/02_Pleos_개발가이드/connect-guide/docs-design/`의 Pleos Connect UI Design Guide(레이아웃, 컬러, 타이포, 컴포넌트)를 따른다
- 생성형 AI 사용은 허용되지만, 참가자가 2차 PT 기술 Q&A에서 구조와 로직을 직접 설명해야 한다. 코드를 짤 때 아키텍처와 주요 흐름을 `docs/ARCHITECTURE.md` 같은 문서로 함께 정리하고, 과하게 복잡한 구조는 피할 것

## 참가·제출 (공모전 규정 + 설명회)
- 부문: 대학생 / 일반(스타트업 포함). 일반인이 한 명이라도 있는 팀은 일반 부문, 스타트업을 운영하는 대학생은 대학생 부문 (설명회)
- 서로 다른 작품을 개수 제한 없이 여러 개 출품할 수 있다. 작품은 앱명으로 구분한다 (설명회)
- 필수: 접수양식(기획서) docx + 참가자 전원 서명 동의서. 마감 2026-11-15(일) 23:59 KST, 이메일 appdevchallenge@hyundai-partners.com, 참가자 전원 CC
- 메일 제목: 홈페이지는 "[차량용 앱 개발 공모전] 팀명(개인은 성명)_대표자명", 설명회는 앱명을 포함하라고 안내. 복수 출품이면 앱명을 반드시 넣을 것
- 선택(가산점): 시연 영상(목업, 프로토타입, 에뮬레이터 화면 녹화 인정), GitHub 저장소(소스 + APK)
- GitHub 저장소 규칙: APK는 저장소의 `apk/` 폴더에, 파일명은 `팀명_앱이름_release.apk`, 실행 가능한 release 빌드. 용량을 초과하면 GitHub Releases 링크. Private 저장소는 심사용 계정을 Collaborator로 초대
- README.md 필수 내용: 앱 이름과 한 줄 소개 / 실행 방법 / 테스트 환경과 필요한 설정 / APK 파일 위치 또는 Release 링크
- 2차 PT(2026-12-03~04, 오프라인) 대상 20팀은 실제 동작하는 앱 시연 필수. 아이디어 특별상을 포함한 모든 수상작은 개발 산출물이 있어야 한다
- 기획서 02 "앱 프로젝트 소개"와 공유 링크, 첨부자료에 개인정보, 실명, 계정정보 노출 금지
- 표절이나 다른 공모전 수상작은 금지. 오픈소스를 쓰면 라이선스를 지키고 출처를 표기

## 심사 기준 (1차, 세부 배점 비공개)
차량특화성 / 창의성 / 기술 활용성(Pleos Playground SDK·API 활용) / 사업성(타깃, 예상 MAU, Retention, 확장 계획) + 개발 산출물 가산점

## 폴더 구조 (`docs/contest/`)
- `00_핵심요약.md` 일정·제출물·심사·규정 요약
- `01_공모전/pages/` 공모전 사이트 전체 페이지 원문 (가이드 8종, FAQ, 공지 3건, 약관, 개인정보방침)
- `01_공모전/OT_설명회_정리.md` 온라인 설명회 정리
- `01_공모전/접수양식/` 원본 docx와 텍스트 변환본
- `01_공모전/images/` 포스터, 설명회 배너, 숏폼 예시 이미지
- `02_Pleos_개발가이드/` document.pleos.ai 가이드 원문 (pleos-connect = Pleos Connect 개발자 가이드, connect-guide = 에뮬레이터·UI 디자인 가이드)
- `03_SDK_API_레퍼런스/` Vehicle / NaviHelper / SpeechToText / TextToSpeech / FusedLocation / LLM SDK API 명세 (패키지별 1파일)

`pages/`, `02_Pleos_개발가이드/`, `03_SDK_API_레퍼런스/`는 수집 원문이므로 수정하지 않는다. 각 파일 상단 주석에 원문 URL이 있다. 문서가 바뀌었을 수 있으니 중요한 결정 전에는 원문과 공모전 공지사항을 다시 확인할 것.
