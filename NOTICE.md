# NOTICE — 제3자 구성요소와 데이터

이 저장소의 코드와 문서는 [LICENSE](LICENSE)(모든 권리 보유)를 따른다. 아래 구성요소와 데이터는 각각의 라이선스를 따르며,
LICENSE가 그 권리를 제한하지 않는다.

## 앱에 내장한 공공 데이터 (`app/src/main/assets/data/`, `stops.bin`)

| 데이터 | 제공 | 이용 조건 |
|---|---|---|
| 공중화장실·안전비상벨·CCTV·석유판매업 등 인허가·생활편의 데이터 | 행정안전부 지방행정 인허가 데이터(LOCALDATA) | 이용허락범위 제한 없음 |
| 전국휴게소정보·전국졸음쉼터 표준데이터, 졸음쉼터 설치현황 | 한국도로공사 (공공데이터포털) | 이용허락범위 제한 없음 |
| 전국주차장정보표준데이터 | 각 지방자치단체 (공공데이터포털) | 이용허락범위 제한 없음 |
| 전기자동차 충전소 정보 (2026-11-15 수집) | 한국환경공단 (공공데이터포털 15076352) | 공공누리 제1유형(출처표시) |
| 고속도로 선형, 민자고속도로 휴게소·졸음쉼터, 주유소·LPG충전소, 공중화장실 일부 | © OpenStreetMap contributors | [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/) |

OpenStreetMap에서 온 내용을 포함한 데이터베이스(`app/src/main/assets/data/*.json`, `stops.bin`)는 ODbL 1.0에 따라
제공하며, 앱 설정 화면에 출처를 표시한다. 만드는 방법은 `tools/data/`에 있다.

## 소프트웨어 구성요소

| 구성요소 | 라이선스 |
|---|---|
| Pleos Playground SDK (Vehicle, NaviHelper, TextToSpeech, SpeechToText, FusedLocation, LLM) | 42dot Pleos Connect SDK 라이선스. 저장소에 포함하지 않으며 빌드 때 Pleos Maven 저장소에서 받는다 |
| AndroidX (Compose, Activity, Lifecycle), Kotlin 표준 라이브러리, kotlinx.coroutines | Apache License 2.0 |
| Gradle Wrapper (`gradle/wrapper/gradle-wrapper.jar`) | Apache License 2.0 |
| 시연 영상 자막 글꼴 (Noto Sans CJK, 영상에만 사용) | SIL Open Font License 1.1 |

## 저장소에 넣지 않은 자료

공모전 안내 페이지, Pleos 개발 가이드·SDK 레퍼런스 원문, 접수양식 원본은 각 저작권자의 자료라 공개 저장소에 넣지 않는다.
개발 중에는 `docs/contest/`(gitignore)에 내려받아 참고했고, 코드와 문서에 그 경로를 출처로 적었다. 원문은
appdevchallenge.auto-hmg.io와 document.pleos.ai에 있다.
