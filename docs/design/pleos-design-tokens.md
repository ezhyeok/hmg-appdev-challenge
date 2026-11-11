# Pleos Connect 디자인 토큰 (쉼표 앱용 정리)

작성 2026-11-11. Pleos Connect UI Design Guide와 주행 안전 관련 문서에서 앱 디자인에 필요한 값을 뽑아 정리했다. 코드는 `app/src/main/java/dev/shimpyo/app/ui/theme/`(`Color.kt`, `Type.kt`, `Dimens.kt`, `Theme.kt`)에 있고, 화면 설계는 `docs/design/screens.md`에 있다.

## 읽는 법

경로 약어 (모두 repo 루트 기준)

- `DG/` = `docs/contest/02_Pleos_개발가이드/connect-guide/docs-design/`
- `PC/` = `docs/contest/02_Pleos_개발가이드/pleos-connect/`

값의 출처 표시

| 표시 | 뜻 |
|---|---|
| [문서] | md 본문 글자로 적힌 값 |
| [표] | md가 링크한 표 이미지에 적힌 값. 이미지는 저장소에 없어서 md에 적힌 원본 URL(document.pleos.ai)에서 2026-11-11에 내려받아 읽었다. 괄호 안은 이미지 파일 이름 |
| [실측] | md가 링크하거나 내장한 SVG 도식에서 도형 좌표를 잰 값. 도식은 1:1 dp로 그려져 있다(예: 앱 윈도 도식 폭이 표의 1700dp, 840dp와 같다) |
| [제안] | 가이드에 값이 없어서 이 문서가 정한 값 |

단위는 dp다. 가이드 기본 화면(Connect-S 17", 160dpi)에서는 1dp = 1px이다. 글자 크기는 Compose에서 같은 숫자의 sp로 쓴다.

---

## 0. 한눈에

| 항목 | 값 | 출처 |
|---|---|---|
| 테마 | 라이트·다크 둘 다. 시스템 Screen Mode에 Auto(주간/야간 자동)가 있다. 다크 우선 아님 | `DG/Introduction/theme.md` [표 Theme2], `DG/Introduction/intro.md` [문서] |
| 배경 | 라이트 `#F7F8FA` / 다크 `#1D1E21` (background_primary) | `DG/foundations/colors.md` [표] |
| 기본 글자 | 라이트 `#131417` / 다크 `#FFFFFF` (text_primary) | 같은 문서 [표] |
| 버튼 | 무채색. Filled 라이트 `#44464E` / 다크 `#E0E1E6`, Basic은 `#131417`·흰색 5% | 같은 문서 [표] |
| 의미색 | active `#02C265`/`#00E074`, positive `#0064FF`/`#0082FF`, negative `#FE3D16`/`#FF4C28` | 같은 문서 [표] |
| 서체 | Asta Sans (한글·영문). 저장소에 없음 → 기본 서체로 대체, TODO | `DG/foundations/typography.md` [문서] |
| 글자 스케일 | Headline 60/56/48, Title 40/36/32, Body 30/28/26, Label 24/20 | 같은 문서 [표] |
| 앱 윈도 | 17": 가로 1700×1184, 세로 840×1184. 모서리 40 | `DG/getting-started/layout.md` [표], 모서리 [실측] |
| 버튼 높이 | Large 100 / Medium 80 / Small 64 (반경 32/24/16) | `DG/components/actions/basic-button.md` [실측] |
| 터치 최소 | 64 (가이드 최소 컴포넌트), 주행 중 100 [제안] | 4장, 7.4 |
| 주행 중 핵심 | 1~2초에 파악, 동영상·애니메이션·자동 스크롤 금지, 대비 4.5:1, 문단 3줄, 중요 글자 5mm·보조 3mm | `DG/Introduction/intro.md` [문서] |

---

## 1. 컬러

출처: `DG/foundations/colors.md`. 이 문서의 본문에는 수치가 없고, 모든 값은 링크된 표 이미지에 있다.

### 1.1 Grayscale (basic_00 ~ basic_900) [표 grayscale]

라이트와 다크가 같은 11단계를 거꾸로 쓴다. basic_400만 같다.

| 토큰 | 라이트 | 다크 |
|---|---|---|
| basic_00 | `#FFFFFF` | `#131417` |
| basic_50 | `#F7F8FA` | `#1D1E21` |
| basic_100 | `#EDEEF2` | `#313236` |
| basic_200 | `#E0E1E6` | `#44464E` |
| basic_300 | `#C7C9CE` | `#686A72` |
| basic_400 | `#A4A8AE` | `#A4A8AE` |
| basic_500 | `#686A72` | `#C7C9CE` |
| basic_600 | `#44464E` | `#E0E1E6` |
| basic_700 | `#313236` | `#EDEEF2` |
| basic_800 | `#1D1E21` | `#F7F8FA` |
| basic_900 | `#131417` | `#FFFFFF` |

### 1.2 Alpha color [표 white-alpha, gray-alpha]

| 토큰 | 라이트 | 다크 | 이름 |
|---|---|---|---|
| basic_alpha_dark_50/100/200/300/400/500 | `#131417` 5/10/20/30/40/50% | `#FFFFFF` 같은 % | Gray Alpha. 배경을 누르는 알파 |
| basic_alpha_light_50/100/200/300/400/500 | `#FFFFFF` 5/10/20/30/40/50% | `#131417` 같은 % | White Alpha. 배경을 띄우는 알파 |

주의: 두 표의 Light/Dark 칸 글자 라벨은 위와 반대로 적혀 있다(예: White Alpha의 Light 칸에 "#131417, 5%"). 다음 세 가지가 모두 위 표와 맞아서 라벨이 뒤바뀐 것으로 판단했다.

- 두 표의 견본 픽셀(White Alpha의 Light 견본은 어두운 칸 위의 흰색 반투명)
- Button 표의 라이트 alpha_dark_50 견본이 `#F3F3F3` = 흰 바탕 위 `#131417` 5%
- Dropdown 표의 다크 basic_alpha_dark100 견본이 어두운 바탕 위 흰색 10%

라벨대로라면 라이트 테마의 Basic 버튼(alpha_dark_50)이 흰 바탕 위 흰색 5%가 되어 보이지 않는다.

### 1.3 Static color [표 light, dark]

테마와 무관하게 항상 같은 값. Overlay 위 요소에 쓴다 [문서].

| 토큰 | 값 |
|---|---|
| basic_static_light_100/200/300/400 | `#FFFFFF` 100 / 84 / 64 / 32% |
| basic_static_dark_100/200/300/400 | `#131417` 100 / 84 / 64 / 32% |

### 1.4 System color (시맨틱 토큰)

표 이미지 이름은 `system-color-<구역>`. 표의 견본 그림 일부는 라벨과 다르다(Background 표는 견본이 한 줄씩 밀려 있고, Button·Controller 표의 다크 견본은 라이트 값으로 그려져 있다). 라벨끼리는 서로 일관돼서 라벨을 따랐다.

**Background** [표 system-color-background]

| 토큰 | 라이트 | 다크 |
|---|---|---|
| background_primary | basic_50 `#F7F8FA` | basic_50 `#1D1E21` |
| background_secondary | basic_00 `#FFFFFF` | basic_00 `#131417` |
| background_popup | basic_00 `#FFFFFF` | basic_100 `#313236` |

**Surface** [표 system-color-surface]

| 토큰 | 라이트 | 다크 |
|---|---|---|
| surface_basic | basic_00 `#FFFFFF` | basic_50 `#1D1E21` |
| surface_low | basic_50 `#F7F8FA` | basic_00 `#131417` |
| surface_high | basic_100 `#EDEEF2` | basic_100 `#313236` |
| surface_accent | basic_200 `#E0E1E6` | basic_200 `#44464E` |

**Text / Icon** [표 system-color-text, system-color-icon]

| 토큰 | 라이트 | 다크 |
|---|---|---|
| text_dark_primary / icon_dark_primary | static_dark_100 (`#131417`) | static_light_100 (`#FFFFFF`) |
| …_secondary | static_dark_200 (84%) | static_light_200 (84%) |
| …_tertiary | static_dark_300 (64%) | static_light_300 (64%) |
| …_quaternary | static_dark_400 (32%) | static_light_400 (32%) |
| (5~8행, 반전) | static_light_100~400 | static_dark_100~400 |

5~8행은 표에서 1~4행과 같은 이름(text_dark_*)으로 적혀 있지만 값이 반대다. Filled 버튼처럼 반전된 바탕 위 글자로 보고 코드에서는 `textOnFilled*`로 이름 붙였다.

**Button** [표 system-color-button]

| 토큰 | 라이트 | 다크 | 예전 이름 |
|---|---|---|---|
| button_basic_enabled | alpha_dark_50 | alpha_dark_50 | |
| button_basic_pressed | alpha_dark_100 | alpha_dark_100 | |
| button_basic_disabled | alpha_light_200 | alpha_light_200 | |
| button_switch_enabled | basic_00 | alpha_dark_200 | button-basic-active-enabled |
| button_switch_pressed | alpha_light_500 | alpha_dark_200 | button-basic-active-pressed |
| button_switch_disabled | alpha_light_300 | alpha_dark_300 | button-basic-active-disabled |
| button_filled_enabled | basic_600 `#44464E` | basic_600 `#E0E1E6` | |
| button_filled_pressed | basic_700 `#313236` | basic_500 `#C7C9CE` | |
| button_filled_disabled | alpha_dark_50 | alpha_dark_50 | |

**Switch** [표 system-color-switch]

| 토큰 | 라이트 | 다크 |
|---|---|---|
| switch_on | informative_active | informative_active |
| switch_off | alpha_dark_300 | alpha_dark_100 |
| switch_disabled | alpha_dark_100 | alpha_dark_100 |
| switch_knob_on | basic_00 | basic_900 |
| switch_knob_off | basic_00 | basic_400 |
| switch_knob_disabled | basic_00 | alpha_dark_200 |

**Controller** [표 system-color-controller]

| 토큰 | 라이트 | 다크 |
|---|---|---|
| controller_slider_normal | alpha_dark_200 | alpha_dark_100 |
| controller_slider_pressed | alpha_dark_300 | alpha_dark_200 |
| controller_slider_knob | basic_00 | alpha_dark_300 |
| controller_tab_background | alpha_dark_50 | alpha_dark_50 |
| controller_stepper_background | basic_00 | alpha_light_100 |

**Fields / Dropdown / Dim / Overlay** [표 system-color-fields, -dropdown, -dim, -overlay]

| 토큰 | 라이트 | 다크 |
|---|---|---|
| field_background | basic_00 | basic_00 |
| field_focused | basic_700 | basic_700 |
| dropdown_normal | basic_00 | basic_alpha_dark100 |
| dropdown_pressed | basic_100 | basic_alpha_dark300 |
| dim_dark_primary / secondary / tertiary | alpha_dark_50 / 100 / 200 | 같음 |
| dim_light_primary / secondary / tertiary | alpha_light_500 / 400 / 300 | 같음 |
| overlay_default | alpha_dark_200 | alpha_light_500 |

**Informative** [표 system-color-informative]

| 토큰 | 라이트 | 다크 |
|---|---|---|
| informative_active | `#02C265` | `#00E074` |
| informative_positive | `#0064FF` | `#0082FF` |
| informative_negative | `#FE3D16` | `#FF4C28` |
| informative_autonomous_driving | `#5A46FA` | `#6A57FF` |

**Regulation** [표 system-color-regulation] (텔테일 계열 규제 색)

| 토큰 | 라이트 | 다크 |
|---|---|---|
| regulation_blue | `#0064FF` | `#0082FF` |
| regulation_green | `#00BA13` | `#55F165` |
| regulation_yellow | `#FFC224` | `#FFC224` |
| regulation_orange | `#FF8A00` | `#FF8A00` |
| regulation_red | `#F62E24` | `#FF4339` |
| regulation_bluetooth | `#0082FC` | `#0082FC` |

**Driving** [표 system-color-driving]: gearshitft_background(표 표기 그대로) 라이트 basic_900 / 다크 basic_100, charging_normal `#00DB25`, charging_deep 라이트 `#00B975` / 다크 `#0082FC`(견본은 둘 다 청록이라 다크 라벨이 의심스럽다), gradient_start `#FFFFFF` 0% / `#131417` 0%, gradient_end basic_00. 앱에서는 쓰지 않는다.

**Gleo AI** [표 system-color-gleo-ai] (참고만): gleo_surface_primary 라이트 basic_00 / 다크 basic_200, gleo_surface_secondary alpha_dark_50 / alpha_light_200, gleo_bubble_primary `#9BA8E2`/`#545F93`, _secondary `#8896D3`/`#3E4976`, _tertiary basic_00/basic_100, _speaker `#3F4B81`/`#B6C1F3`. 앱 화면이 Gleo처럼 보이면 사용자가 시스템 비서와 헷갈리므로 이 색은 쓰지 않는다 [제안].

Climate, Keyboard, Media, Phone 표는 앱과 관계없어 옮기지 않았다(원본: 같은 문서).

### 1.5 테마 정책

- 테마는 아이콘·폰트·컬러로 브랜드 정체성을 유지한다 [문서 `DG/Introduction/theme.md`]. 시스템에는 42dot / Kia / Genesis 브랜드 테마가 있다 [표 Theme1].
- 시스템 설정 Display에 Screen Mode: Auto("Brightness will change automatically")와 라이트·다크 미리보기가 있다 [표 Theme2].
- "야간/주간에 따른 조명 환경에 따라 UI 밝기와 대비를 자동 조정" [문서 `DG/Introduction/intro.md`].
- 결정: 앱은 시스템 야간 모드를 따른다(`isSystemInDarkTheme()`, AAOS에서는 차량이 주간/야간을 정한다). 브랜드 테마 색을 흉내 내지 않고 가이드 공통 토큰만 쓴다 [제안].

### 1.6 앱 시맨틱 색 [제안, 가이드 토큰 조합]

| 코드 이름 (`ShimpyoColors`) | 라이트 | 다크 | 쓰는 곳, 근거 |
|---|---|---|---|
| cardContainer | surface_basic `#FFFFFF` | surface_basic `#1D1E21` | 카드 바탕. 가이드 설정 화면 도식의 컨테이너 방식(흰 바탕 + 2dp 테두리) [실측 Theme2] |
| cardStroke, divider | alpha_dark_100 | alpha_dark_100 | 2dp. 도식의 테두리·구분선이 `#131417` 10%, 2px [실측 Theme2] |
| toastContainer / toastContent | static_dark_200 / static_light_100 | 같음 | Toast 도식 바탕 `#131417` 84% [실측 Toast1] |
| toastStroke | 투명 | alpha_dark_100 | 다크에서 토스트와 배경 대비가 1.1:1이라 테두리를 더한다 |
| gaugeOk, gaugeWatch | informative_positive | informative_positive | 게이지 OK·WATCH. 색은 행동이 필요할 때만 바꾼다 |
| gaugeSoon | regulation_orange `#FF8A00` | `#FF8A00` | SOON. 시스템 알림 센터도 경고 아이콘에 주황을 쓴다 [실측 menu_list_panel] |
| gaugeNow | informative_negative | informative_negative | NOW |
| gaugeTrack | alpha_dark_100 | alpha_dark_100 | 게이지 빈 부분 |
| onGaugeColor | static_dark_100 | static_dark_100 | 게이지 색 위 글자·아이콘 (아래 대비표) |
| urgentContainer / onUrgentContainer | informative_negative / static_dark_100 | 같음 | 급해 버튼. 흰 글자는 4.5:1 미달이라 어두운 글자 |
| timelineOpportunity / Gap / Congestion / Tunnel / PlannedStop | positive / orange / negative / basic_400 / button_filled | 같음 | 계획 화면 타임라인 |
| voiceActive, focusRing | informative_positive | informative_positive | 음성 듣는 중, CCP 포커스 |

게이지 단계는 엔진의 `Level { OK, WATCH, SOON, NOW }`(`rest-engine/.../Api.kt`)와 1:1이다.

### 1.7 Material3 역할 매핑 [제안]

가이드에 M3 대응표는 없다. M3 컴포넌트를 그대로 쓸 때 가이드와 비슷하게 보이도록 다음처럼 맞췄다(`Theme.kt`).

| M3 역할 | 가이드 토큰 |
|---|---|
| primary / onPrimary | button_filled_enabled / 반전 글자 |
| primaryContainer / on | button_basic_enabled / text_primary |
| secondary / onSecondary | informative_positive / 흰색(라이트)·static_dark_100(다크) |
| tertiary / onTertiary | informative_active / static_dark_100 |
| error / onError | informative_negative / static_dark_100 |
| background / surface | background_primary / surface_basic |
| surfaceVariant / onSurfaceVariant | surface_high / text_tertiary |
| inverseSurface / inverseOnSurface | 토스트 색 |
| outline / outlineVariant | basic_300 / alpha_dark_100 |
| scrim | overlay_default |
| surfaceContainerHigh | background_popup (M3 다이얼로그) |
| surfaceTint | 투명 (M3 보라 틴트 끔) |

M3 버튼은 기본이 알약 모양이라 가이드 반경(16/24/32)과 다르다. 화면은 가이드 모양의 자체 컴포넌트로 만든다(`screens.md` 0장).

### 1.8 대비 확인

가이드 기준: "아이콘, 텍스트 및 기타 이미지의 대비율이 4.5:1 이상" [문서 `DG/Introduction/intro.md`]. WCAG 상대 휘도 공식으로 계산했다.

| 조합 | 라이트 | 다크 | 판정 |
|---|---|---|---|
| text_primary / background_primary | 17.3 | 16.7 | 통과 |
| text_secondary(84%) / 배경 | 11.0 | 12.0 | 통과 |
| text_tertiary(64%) / 배경 | 5.4 | 7.5 | 통과 |
| text_quaternary(32%) / 배경 | 2.1 | 2.9 | 미달 → 비활성 표시에만 |
| 흰 글자 / informative_positive | 4.9 | 3.7 | 다크 미달 → 다크는 어두운 글자(4.9) |
| 흰 글자 / informative_negative | 3.6 | 3.3 | 미달 |
| static_dark_100 / informative_negative | 5.2 | 5.5 | 통과 → 급해 버튼 글자 |
| static_dark_100 / regulation_orange | 7.8 | 7.8 | 통과 |
| static_dark_100 / informative_active | 7.8 | 10.4 | 통과 |
| 흰 글자 / button_filled (라이트) · 어두운 글자 / button_filled (다크) | 9.4 | 14.1 | 통과 |
| informative_positive (그래픽) / 카드 | 4.9 | 4.47 | 다크는 4.5 바로 아래 → 숫자·배지로 보완 |
| regulation_orange (그래픽) / 카드 | 2.4 | 7.1 | 라이트 미달 |
| informative_negative (그래픽) / 카드 | 3.6 | 5.0 | 라이트 미달 |
| regulation_yellow (그래픽) / 흰색 | 1.6 | - | 라이트에서 거의 안 보임 → 쓰지 않음 |

라이트 테마에서 주황·빨강 게이지 막대는 4.5:1이 안 된다. 그래서 게이지 상태는 색만으로 전하지 않는다. 숫자(text_primary)와 단계 배지(색 바탕 + 어두운 글자, 5.2:1 이상)를 함께 둔다(`screens.md` 1장). 다크에서 카드에 surface_high를 쓰면 positive 3.4, negative 3.9로 떨어져서 카드는 surface_basic + 테두리로 정했다.

---

## 2. 타이포그래피

출처: `DG/foundations/typography.md`.

### 2.1 서체

- System UI 서체: **Asta Sans**, 한국어·영어 지원, 출처 Google Fonts [문서].
- 중립적인 형태와 균일한 시인성. 캐릭터가 강한 서체는 지양 [문서].
- 저장소에 번들되어 있지 않아 `FontFamily.Default`로 대체하고 `Type.kt`에 TODO를 남겼다. 번들하려면 TTF를 `res/font/`에 넣고 라이선스를 확인해 출처를 표기한다.

### 2.2 스케일 [표 headline, title, body, label]

| 단계 | 크기 | 행간 | 허용 굵기 | 코드 (`PleosType`) |
|---|---|---|---|---|
| Headline Large | 60 | 80 | Extra Bold, Bold | HeadlineLarge |
| Headline Medium | 56 | 72 | Extra Bold, Bold | HeadlineMedium |
| Headline Small | 48 | 64 | Extra Bold, Bold | HeadlineSmall |
| Title Large | 40 | 52 | Extra Bold, Bold, Regular | TitleLarge(Bold), TitleLargeRegular |
| Title Medium | 36 | 44 | Extra Bold, Bold, Regular | TitleMedium |
| Title Small | 32 | 40 | Extra Bold, Bold, Regular | TitleSmall |
| Body Large | 30 | 38 | Strong, Normal | BodyLarge, BodyLargeStrong |
| Body Medium | 28 | 36 | Strong, Normal | BodyMedium, BodyMediumStrong |
| Body Small | 26 | 34 | Strong, Normal | BodySmall, BodySmallStrong |
| Label Medium | 24 | 28 | Normal | LabelMedium |
| Label Small | 20 | 24 | Normal | LabelSmall |

- 굵기 수치는 가이드에 없다. Extra Bold 800, Bold 700, Regular·Normal 400으로 두고, Body의 **Strong은 600(SemiBold)** 으로 정했다 [제안]. 버튼 도식의 라벨 획 굵기(글자 크기의 약 12.6%)가 SemiBold에 가깝다 [실측].
- 단위는 표에 없다. 표의 값이 dp(17" 기준 px) 스케일이라고 보고 같은 숫자를 sp로 쓴다.

### 2.3 운영 규칙 [문서]

- 정보 위계에 따라 컴포넌트 간 일관된 크기, 서브 텍스트는 위계를 구분해 사용.
- 최소 글자 12sp 이상(Android 접근성), 행간은 보통 1.5배 이상, 실제 픽셀 높이 18px 이상.
- 이 앱의 가장 작은 토큰은 Label Small 20(주차 화면 전용)이고, 주행 화면 최소는 28이다(7.3).

### 2.4 버튼 라벨 [실측 basic_size]

라벨 'L' 대문자 높이가 Large 22.6 / Medium 21.2 / Small 18.4dp다. Asta Sans 대문자 높이(약 0.71em)로 나누면 **32 / 30 / 26**이고, 굵기는 Strong이다. 코드: `PleosButtonType`.

### 2.5 Material3 Typography 매핑 [제안]

display = Headline, headline = Title(Bold), title = Body Strong, body = Body, labelLarge = Body Large Strong(Medium 버튼 라벨 30), labelMedium/Small = Label.

---

## 3. 레이아웃

### 3.1 화면 구성 [문서 `DG/getting-started/layout.md`]

- Status bar(주행 상태, Gleo, 검색, 시계, 네트워크, 마이크, 알림 센터, 프로필), Driving view(텔테일, 속도, 차량 상태), GNB(App library, 즐겨찾기 앱, 공조), Application window(앱 1개 또는 2개).
- 상태 표시줄 패널, App library, 공조 패널, 키보드, Gleo 패널은 앱보다 위 레이어라 앱 윈도를 잠시 가릴 수 있다. Driving view는 앱이 가리거나 종료할 수 없다 [문서 `PC/vehicle-app-planning-guide/app-structure-screen-model.md`].
- 가로 전용 게임을 빼면 모든 앱은 Landscape, Portrait 두 레이아웃에 맞게 설계한다 [문서].

### 3.2 디스플레이와 앱 윈도 크기 [표 pleos_connect_deisgnguide_displaylayout_02]

| 유형 | 비율 | 크기 | DPI | 해상도(px) | 가로 앱 윈도(dp) | 세로 앱 윈도(dp) |
|---|---|---|---|---|---|---|
| Connect-S-17 (기본) | 16:9 | 17" | 160 | 2560×1440 | 1700×1184 | 840×1184 |
| Connect-S-14.6 | 16:9 | 14.6" | 200 | 2048×1152 | 1360×948 | 672×948 |
| Connect-L-12.9 | 16:9 | 12.9" | 160 | 1920×1080 | 1274×855 | 628×855 |
| Connect-W-Standard | 9:2 | 24.6" | 160 | 3840×850 | 1640×810 | 800×810 |
| Connect-W-Extended | 24:9 | 24.6" | 160 | 3840×1440 | 1640×1400 | 800×1400 |
| Connect-WF | 32:9 | 18.5" | 200 | 3072×816 | 1256×800 | 648×800 |

- 표의 "Resolution (dp)" 열은 실제로는 px다. 폼 팩터 테스트 화면에서 14.6" 가로가 "1360 × 948 dp", "200 DPI · 1700 × 1185 px"로 나온다 [표 `PC/app-test-guide/form-factor-test-guide.md`의 form_factor_14.6_land]. 폼 팩터 콘솔은 WF를 18.7"로 적는다.
- 에뮬레이터 기본 화면은 Connect-S 17" 배치다(왼쪽 Driving view + 오른쪽 앱 영역) [표 form_factor_4].
- 도식의 앱 윈도 높이는 1212로 그려져 있어 표의 1184와 28 다르다 [실측 Toast2, Theme2]. 레이아웃은 런타임에 실제 크기를 재서 맞춘다.

### 3.3 윈도 정책 [문서 `DG/getting-started/window.md`, `PC/vehicle-app-planning-guide/app-structure-screen-model.md`]

- Connect-S: 앱 2개면 1:1, 1개면 2:0(기본 1:1). Connect-W: 2:1 또는 1:2(기본 2:1), 새 앱은 항상 primary window에 landscape.
- 처음 켰을 때 primary는 내비 앱. 그래서 쉼표는 보통 **내비 옆 세로 윈도(17"에서 840×1184)** 에 뜬다.
- `screenOrientation`을 지정하지 않으면 portrait로 실행되고 윈도 1개를 쓴다. landscape로 지정하면 윈도 2개를 쓴다.
- Portrait에는 핵심 기능과 주요 정보, landscape에는 부가 정보와 확장 기능을 둔다. 단, 화면 상태가 바뀌어도 같은 기능과 작업을 모두 노출해야 한다(기능 패리티) [문서 `PC/distribute/app-review-guide/review-functionality-performance-stability.md`]. → 가로 화면은 정보만 더하고 버튼은 같게 둔다.
- 앱은 레터박스 없이 화면을 채워야 하고(한 방향 소량은 허용), 해상도가 바뀌어도 상태를 잃지 않아야 한다 [같은 문서].

### 3.4 여백과 모서리 [실측]

| 값 | 크기 | 근거 |
|---|---|---|
| 앱 윈도 모서리 | 40 | 앱 패널 rx=40 (`DG/components/toast.md` Toast2, `DG/Introduction/theme.md` Theme2) |
| Page Navigation 여백 | 위 40, 왼쪽 40 | 루트 모서리 40에서 앱 아이콘까지 40 (`DG/components/page-navigation.md` nav_anatomy) |
| 앱 아이콘 | 80, 반경 20 | 같은 도식 |
| 설정 화면 콘텐츠 여백 | 48 | Theme2: 오른쪽 콘텐츠 영역 좌우 48, 왼쪽 목록 폭 약 430 |
| 구분선, 컨테이너 테두리 | 2, `#131417` 10% | Theme2 |
| 토스트 여백 | 좌우 24, 아래 24 | `DG/components/toast.md` [문서] |
| 팝업 안쪽 여백, 버튼 간격 | 48, 24 | `DG/components/popups/system-popup.md` pop_anatomy |

코드: `Dimens.Layout` (세로 여백 40, 가로 여백 48은 위 두 도식에서 고른 값).

### 3.5 그리드 [제안]

가이드에 컬럼 그리드는 없다. 4dp 배수 간격(4, 8, 12, 16, 24, 32, 40, 48, 64)을 쓰고, 폭 1000dp 이상이면 2단(모든 landscape 윈도), 높이 1000dp 미만이면 compact 배치로 바꾼다(`Dimens.Window`). 주행 화면 기본 배치의 세로 합이 988dp라서, 17"(1184)·W-Extended(1400)만 기본이고 14.6"(948)·12.9"(855)·W-Standard(810)·WF(800)는 compact다.

### 3.6 Safe area

- 앱은 자기 윈도 안만 그린다. 시스템 UI는 윈도 밖이거나 위 레이어다 [문서 3.1].
- 모서리 반경 40 안쪽에 콘텐츠를 둔다(여백 40 이상이면 자동으로 만족).
- Status bar의 SP0·SP1 사이 40dp safe bounds는 시스템 UI 내부 규칙이다 [문서 `DG/components/status-bar.md`].
- IME: 텍스트 필드가 없는 윈도는 IME가 떠도 밀려 올라가면 안 된다. 다이얼로그는 `setFitInsetsTypes(statusBars or navigationBars)`로 IME insets를 뺀다 [문서 `PC/vehicle-app-developer-guide/ime-window-resize-handling-guide.md`].

### 3.7 dp와 실제 크기

화면 대각선 해상도와 크기로 계산한 1dp의 실제 길이. 17"가 가장 작아서(촘촘해서) 기준으로 삼는다.

| 화면 | ppi | 1dp | 5mm = | 3mm = | 64dp | 100dp |
|---|---|---|---|---|---|---|
| Connect-S 17" | 173 | 0.147mm | 34.0dp | 20.4dp | 9.4mm | 14.7mm |
| Connect-S 14.6" | 161 | 0.197mm | 25.3dp | 15.2dp | 12.6mm | 19.7mm |
| Connect-L 12.9" | 171 | 0.149mm | 33.6dp | 20.2dp | 9.5mm | 14.9mm |
| Connect-W-Standard | 160 | 0.159mm | 31.5dp | 18.9dp | 10.2mm | 15.9mm |
| Connect-W-Extended | 167 | 0.152mm | 32.8dp | 19.7dp | 9.8mm | 15.2mm |
| Connect-WF | 172 | 0.185mm | 27.1dp | 16.2dp | 11.8mm | 18.5mm |

---

## 4. 컴포넌트

출처 폴더 `DG/components/`. 상태 이름은 문서 표, 크기는 도식 [실측].

### 4.1 버튼

| 컴포넌트 | 사양 | 출처 |
|---|---|---|
| Basic button | 구조: Root, Label, Icon(prefix), Icon(suffix). 상태: Pressed, Disabled, Loading(로더 표시 + 조작 불가). 크기 Large **100**(반경 32) / Medium **80**(24) / Small **64**(16). 라벨 32/30/26 Strong, 아이콘 48/40/32, 좌우 여백 약 40/32/24 | `actions/basic-button.md` [문서], basic_size [실측] |
| 위계 | Primary = Basic(`#131417` 5% 바탕, 어두운 글자), Secondary = Filled(`#44464E` 바탕, 흰 글자). 도식 표기 그대로 | basic_hierarchy [실측] |
| Text button | 부가 정보 링크용, 앞뒤 아이콘. 크기 Medium, Small. 배경 없음, 아이콘+라벨 묶음 155×38 / 129×34(높이가 Body Large/Small 행간과 같다), 라벨·아이콘 색 `#0064FF`(informative_positive). 터치 영역은 최소 64로 키운다 [제안] | `actions/text-button.md` [문서], text_property [실측] |
| Toggle button | On/Off, 다중 옵션. 상태 Toggled(기본 False), Pressed, Disabled. 속성 Floating(그림자 또는 색), 크기 Large/Medium/Small(수치 미확인) | `actions/toggle-button.md` [문서] |
| Icon button | 누구나 알아보는 아이콘일 때. 스타일 Basic/Plain/Circle, 크기 L/M/S. 도식 **80×80, 반경 20**(Circle은 원), Basic 바탕 5% → 눌림 10% → 비활성 흰색 20% | `actions/icon-button.md` [문서], icon_type1~3 [실측] |
| Keypad | Primary/Secondary/Special, Text/Voice. 이 앱은 쓰지 않음(주행 중 키보드 제한) | `actions/keypad.md` |

### 4.2 선택 컨트롤

| 컴포넌트 | 사양 | 출처 |
|---|---|---|
| Switch | Root, Handle, Track. 상태 Checked, Pressed, Disabled, Invalid. 트랙 **80×48 반경 16**, 손잡이 **32 반경 8**. On `#02C265`, Off `#131417` 30%, 비활성 10% | `selections/switches.md` [문서], Switch2·3 [실측] |
| Checkbox | 다중 선택. 상태 Checked, Pressed, Disabled. **48×48 반경 12**, 체크 시 `#02C265`, 해제 시 44 반경 10 테두리, 눌림 `#131417` 10% | `selections/checkbox.md`, Checkbox2·4 [실측] |
| Radio | 독립 선택. 상태 Value(Num/Null), Pressed, Disabled, Invalid. 선택 점 24 | `selections/radio.md`, radio3 [실측] |
| Tabs | 2개 이상 콘텐츠·기능. 상태 Enable, Pressed, Selected. 타입 Box/Line, Fitted(균등 폭) 또는 왼쪽 정렬 | `segmented-menu.md` [문서] |
| Dropdown | 상태 Opened, Pressed, Disabled. Prefix(앱 아이콘 60 반경 16, 아이콘 48), Suffix(스위치 64×40, 아이콘). 필드 **100 / 80, 반경 24, 테두리 2**, 열린 목록 반경 32, 항목 100/80 반경 24, 눌림 5% | `dropdown.md` [문서], Dropdown1~6 [실측] |

### 4.3 입력·조절

| 컴포넌트 | 사양 | 출처 |
|---|---|---|
| Text field | Root, Field, Cursor, Placeholder, Suffix. 상태 Value, Focused, Disabled, Read Only, Invalid. 크기 Medium **100** / Small **80**, 반경 24 | `fields/text-field.md` [문서], text_status·text_type [실측] |
| Number field | 크기 Large/Medium. 칸 140×180 / 128×128, 반경 32 | `fields/number-field.md`, num_* [실측] |
| Slider | Continuous(Basic, Floating), Centered. 상태 Pressed/Dragging, 옵션 Disabled(최소·최대 도달), Min, Max, Step. Floating 컨테이너 512×100 반경 32. 볼륨 단계 Max 100%, High 76~100, Mid 26~75, Low 1~25, Min 0 | `controllers.md` [문서], Slider_State [실측] |
| Stepper | Current value, i++, i--. **400×100 반경 24** | `controllers.md`, Stepper_* [실측] |
| Spinner | 드래그로 하나 선택, Date/Time. 600(또는 500)×384 반경 32, 선택 줄 88 반경 24, 위아래 60 페이드 | `spinner.md` [문서], Picker1·4·7 [실측] |

### 4.4 알림·팝업

| 컴포넌트 | 사양 | 출처 |
|---|---|---|
| System popup | Root, Title, Description(선택), Confirm, Cancel(선택). Header(Icon, SubTitle, Description), Body(콘텐츠), Size Large/Medium, Button Align Single/Vertical/Horizontal. 폭 Medium **808** / Large **1200**, 반경 **48**, 안쪽 여백 48, 버튼 높이 100 반경 32, 버튼 간격 24 | `popups/system-popup.md` [문서], pop_* [실측] |
| Toast | 일시 알림. 높이 80(한 줄), 반경 16, 바탕 `#131417` 84%, 좌우 여백 40 위아래 24. 최대 폭 **792**, 글자 최대 폭 712. 윈도 가장자리에서 좌우·아래 24, 가운데 정렬 | `toast.md` [문서], Toast1~3 [실측] |
| Toast popup | 시스템 메시지를 24dp 공간을 두고 최대 폭으로 | `popups/toast-popup.md` [문서] |
| System notification | Root, Imagery, Header, Action. 타입 App icon, Profile, Image, Action. Button Align | `notification/system-notification.md` [문서] |
| Vehicle notification | 주행 알림: Telltale, Title, Description, Guide image. 카드 426×160 반경 24, 경고형 바탕 `#F62E24` | `notification/vehicle-notification.md`, noti_driving* [실측] |

### 4.5 구조

| 컴포넌트 | 사양 | 출처 |
|---|---|---|
| Page navigation | Root, App icon, Primary menu(드롭다운), Secondary menu(선택), Suffix(아이콘 메뉴). 타입 Main / Depth(Back 버튼). 루트 위 모서리 40, 여백 40, 앱 아이콘 80 | `page-navigation.md` [문서], nav_* [실측] |
| Scroll bar | 손잡이 6×150 반경 3, 라이트 `#131417` 20%, 다크 흰색 20% | `bar.md` [문서], ScrollBar_* [실측] |
| Window bar | 시스템 UI(앱 윈도 크기 조절). 손잡이 4×160, 눌림 4×80 50% | `bar.md` [실측] |
| Indicator | Present value, Next/Prev. 크기 Wide/Narrow | `indicator.md` [문서] |
| Widget | Handle, Pagination, Title/Action, Information. 792×176 반경 32, 안 버튼 224×64 반경 16 | `widgets.md` [문서], Widget1 [실측] |
| GNB, App library, Status bar | 시스템 UI. App library는 Connect-S에서 한 줄 최대 7개, OSD는 조작 없으면 2초 뒤 사라짐 | `gnb.md`, `app-library.md`, `status-bar.md` [문서] |
| 목록 | 드롭다운 목록·설정 메뉴 항목 높이 100 | Dropdown1·4, Theme2 [실측] |

### 4.6 가이드에 없는 컴포넌트 [제안]

- **카드**: 별도 컴포넌트 없음. 컨테이너 패턴(surface_basic + 2dp `cardStroke`, 반경 32)을 카드로 쓴다.
- **칩**: 범용 칩 없음. Gleo 패널의 선택 칩이 높이 64 반경 18이다 [실측 Panel_Template_1]. 이 앱의 이유 칩은 누를 수 없는 정보 표시라 버튼과 구분되게 높이 60, 반경 16, 바탕 surface_high, 글자 30 Strong으로 정했다.
- **진행 표시**: Loading 애니메이션(Linear: 정적 로딩, Tension: 오래 걸리는 페이지 로딩)만 있다 [문서 `DG/foundations/animation.md`]. 막대형 진행 표시는 없어서 게이지 막대를 직접 그린다(높이 24, 반경 12).
- **다이얼로그 크기**: 앱 세로 윈도(840)에는 Medium 팝업(808)이 거의 꽉 찬다. 세로에서는 좌우 16 여백으로 줄여 쓴다.

---

## 5. 아이콘

| 규칙 | 값 | 출처 |
|---|---|---|
| 그리드 | 기본 48×48dp 그리드로 제작 | `DG/foundations/contents-creation/iconography.md` [문서] |
| 스타일 | 시인성을 위해 상황에 따라 Filled / Outlined | 같은 문서 |
| 변형 | 한 벌을 만들고 필요한 크기로 조정 | 같은 문서 |
| 라벨 | 국제적으로 통하는 직관적 아이콘 + 텍스트 라벨 | `DG/Introduction/intro.md` [문서] |
| 피드백 | 터치 시 햅틱 또는 음성 피드백 | 같은 문서 |
| 색 | icon_primary~quaternary (text와 같은 값) | 1.4 |
| 앱 아이콘 | Android Adaptive Icon(Foreground/Background 분리), 브랜드별 마스킹, Figma 템플릿 제공 | `DG/foundations/contents-creation/app-icon.md` [문서] |
| 버튼 안 크기 | Large 48 / Medium 40 / Small 32 | basic_size [실측] |

이 앱의 카테고리 아이콘(화장실, 휴식, 주유, 수유, 반려견 등)은 `screens.md` 0.3에 있다.

---

## 6. 모션, 인터랙션, 그림자

| 항목 | 값 | 출처 |
|---|---|---|
| Easing | cubic-bezier(0.16, 1, 0.3, 1) | `DG/foundations/interaction.md` 내장 SVG Motion1 [실측] |
| Duration | short1 100, short2 150, medium1 250, medium2 300, long1 400, long2 450 ms (`ivi_motion_duration_*`) | 같은 문서 [표 Motion2] |
| 용도 | short: 작은 전환, medium: 화면 중간 영역 전환, long: 큰 전환(Emphasized easing과 함께) | 같은 문서 [문서] |
| 버튼 | 터치 가능한 모든 요소에 shrink: Pressed 때 95% 축소 + 모드별 바탕색 변경. 손가락이 영역 밖으로 나가면 취소, 영역 안에서 떼면 실행 | 같은 문서 [문서] |
| 제스처 | Pressed, Long pressed(2~3초, 시스템 편집), Swipe, Drag & Drop | 같은 문서 [문서] |
| 스크롤 끝 | Bounce, 최대 overscroll은 스크롤 영역의 약 2/5 | 같은 문서 [문서] |
| 응답 시간 | 입력 후 0.25초 안에 눌림 효과. 늦어지면 응답 중임을 표시 | `DG/Introduction/intro.md`, `PC/distribute/app-review-guide/review-functionality-performance-stability.md` [문서] |
| 반응 | 앱 실행·콘텐츠 로드 10초 이내, 버튼 반응 2초 이내(못 하면 동작 중 표시), 복귀 시 마지막 상태 복원 | 같은 심사 문서 [문서] |
| 그림자 Level 1 | X0 Y6 Blur24 Spread0, 검정 8% | `DG/foundations/shadow.md` [문서] |
| 그림자 Level 2 | Y8 Blur32 16% + Y2 Blur2 8% | 같은 문서 |
| 그림자 Level 3 | Y20 Blur40 24% + Y2 Blur4 20% | 같은 문서 |

코드: `ShimpyoMotion`(Theme.kt), `Dimens.Elevation`(Compose `shadow`는 Y·blur를 따로 줄 수 없어 Y 오프셋을 elevation 근사로 쓴다).

---

## 7. 주행 중 제약 (운전자 주의 분산)

### 7.1 가이드 규칙

| 규칙 | 출처 |
|---|---|
| 운전자는 **1~2초** 안에 정보를 파악해야 한다. 핵심 정보만, 불필요한 요소 배제 | `DG/Introduction/intro.md` [문서] |
| 주행 중 **동영상, 애니메이션 이미지, 텍스트 자동 스크롤** 금지 | 같은 문서 |
| **한 손**으로 조작 가능, 양손 금지, 단순한 조작만. 손이나 발을 움직이게 유도하지 않는다 | 같은 문서 |
| 중요 정보는 강한 대비, 과도한 색 사용 피함. 대비 **4.5:1** 이상 | 같은 문서 |
| 장문 최소화, 단순한 아이콘과 짧은 문장. 단일 메시지 영문 **120자 또는 24단어** 이하, 일본어 30자, 한 줄 **80자**(영문) 이하, 문장·단락 **3줄** 이하 | 같은 문서 |
| 중요 정보(속도, 내비 안내, 경고 등) 글자 높이 **5mm** 이상, 보조 **3mm** 이상 | 같은 문서 |
| 아이콘에 텍스트 라벨, 터치 시 햅틱 또는 음성 피드백 | 같은 문서 |
| 알림은 운전자에게 필요할 때만, 간결하게. 알림으로 광고 금지 | 같은 문서 |
| 음성: 핸즈프리로 말하고 들을 수 있어야 한다. 대화 시작·종료·중단은 수동으로도 가능 | 같은 문서 |
| 정보에 우선순위, 최소 조작으로 도달. 자주 쓰는 기능은 첫 화면·상단, 드롭다운·다단 메뉴 남용 금지. 자동 애니메이션·반복 화면 전환 최소화 | `PC/vehicle-app-planning-guide/driver-distraction-guideline.md` [문서] |
| 큰 터치 영역, 높은 대비, 텍스트 최소화. 터치 최소화, 음성·물리 버튼 연계 | 같은 문서 |
| 키보드 입력보다 프리셋, 최근 항목, 음성 입력 우선 | 같은 문서 |
| 주행 중 조작 금지(내비 앱 기준): **3 depth 초과 메뉴**, **긴 목록 스크롤**, 영상·애니메이션·광고. 허용: 음성 입력, 단일 터치 기반 주요 기능, 즐겨찾기 선택. **주행 중 전체 화면 팝업 금지**, 터치 영역 최소 크기 충족 | `PC/vehicle-app-developer-guide/navigation-app-development-guide.md` [문서] |
| `CarUxRestrictions`를 기준으로 제한 기능의 UI·동작을 축소하거나 끈다. DO 선언 앱도 직접 적용 | `PC/vehicle-app-developer-guide/driving-restriction-development-guide.md` [문서] |
| DO 미선언 앱은 주행 중 사용 불가 | `PC/vehicle-app-planning-guide/driving-restiction-policy.md` [문서] |
| 주행 중 차량 설정·로그인·QR·동영상·매뉴얼(대용량 텍스트) 금지(전 지역). 북미는 키보드·다이얼패드도 금지, 검색은 음성만 | 같은 문서 [표] |
| Mobility Services 카테고리는 주행과 밀접한 정보, 현재 위치 기반 즉각 정보, 주차장·주유소 탐색이 제한적으로 허용될 수 있다 | `PC/vehicle-app-planning-guide/app-category.md` [문서] |

### 7.2 CarUxRestrictions 플래그와 이 앱의 대응

| 플래그 (값) | 이 앱에서 막는 것 |
|---|---|
| NO_SETUP (64) | 탑승자 설정, 기준 시간 변경, 설정 화면 → "정차 후 바꿀 수 있어요" |
| NO_KEYBOARD (8) | 텍스트 입력 전부(이 앱은 원래 키보드를 쓰지 않는다) |
| NO_FILTERING (2) | 목록 필터·정렬 조작 |
| LIMIT_CONTENT (32) | 계획 타임라인, 휴식 기록, 급해 결과 3번째 이후 → 숨김. `getMaxCumulativeContentItems()`, `getMaxContentDepth()`를 읽어 상한으로 쓴다 |
| LIMIT_STRING_LENGTH (4) | 문구를 `getMaxRestrictedStringLength()`로 자른다(값이 없으면 120) |
| NO_VIDEO (16) | 해당 없음(애니메이션·영상 없음) |

`AndroidManifest.xml`은 이미 `distractionOptimized=true`를 선언했다. 그러므로 위 제한은 앱이 직접 적용해야 한다.

### 7.3 글자 크기 계산

"높이 5mm/3mm"가 글자 크기(em)인지 실제 글자 높이인지 가이드에 없다. 엄격한 쪽(실제 글자 높이)으로 계산했다. 가장 촘촘한 17"(1dp = 0.147mm)에서 한글 글자 높이 ≈ 0.9em, 숫자 ≈ 0.72em으로 잡았다 [제안].

| 크기 | 한글 높이 | 숫자 높이 | 판정 |
|---|---|---|---|
| 60 (Headline L) | 7.9mm | 6.4mm | 중요 정보 통과 → 게이지 숫자 |
| 48 (Headline S) | 6.4mm | 5.1mm | 중요 정보 통과 → 급해 버튼 |
| 40 (Title L) | 5.3mm | 4.2mm | 한글 중요 정보 통과 → 장소 이름, 행동 버튼 라벨 |
| 36 (Title M) | 4.8mm | 3.8mm | 한글 중요 정보 미달(em 기준으로는 5.3mm) |
| 32 (Title S) | 4.2mm | 3.4mm | 보조 통과 → "분" 단위 |
| 30 (Body L) | 4.0mm | 3.2mm | 보조 통과(숫자 포함) → 거리·시간, 이유 칩 |
| 28 (Body M) | 3.7mm | 3.0mm | 보조 통과 → 주행 화면 최소 |
| 26 (Body S) | 3.4mm | 2.8mm | 숫자가 있으면 미달 → 주행 화면에서 안 씀 |
| 24, 20 (Label) | 3.2, 2.6mm | 2.5, 2.1mm | 미달 → 주차 화면만 |

코드: `DriveType`(Type.kt). 14.6"·WF는 1dp가 더 커서(0.19~0.20mm) 같은 dp면 더 크게 보인다.

### 7.4 터치 영역

- 가이드에 최소 터치 크기 수치는 없다("터치 영역 최소 크기 충족", "버튼 크기와 간격은 UI Design Guide 기준"만 있다 [문서 `PC/vehicle-app-developer-guide/navigation-app-development-guide.md`, `PC/vehicle-app-planning-guide/driver-distraction-guideline.md`]). 가이드 컴포넌트 중 가장 작은 조작 요소가 Small 버튼 64라서 **64를 최소**로 한다.
- 주행 화면은 Large 버튼 높이 **100**(17"에서 14.7mm) 이상, 급해 버튼 **120**(17.6mm) [제안].
- 터치 요소 간격은 팝업 버튼 간격 **24** 이상(17"에서 3.5mm) [실측 근거, 적용은 제안].
- 손가락이 영역을 벗어나면 취소되는 가이드 동작 덕분에 흔들림에 의한 오입력이 줄어든다(6장).

### 7.5 주행 화면 양 제한 [제안]

가이드 숫자가 없는 부분은 "1~2초", "핵심 정보만", "3줄"에서 아래처럼 정했다(`DriveLimits`).

| 항목 | 상한 |
|---|---|
| 게이지 | 2 (화장실, 피로) |
| 추천 장소 | 1 (결정은 한 번에 하나) |
| 이유 칩 | 2 (칩당 한글 12자 이하) |
| 급해 결과 | 2 |
| 터치 요소 | 4 (들르기, 괜찮아요, 급해, 마이크) |
| 문단 | 3줄, 120자 |
| 메뉴 깊이 | 1 (주행 화면에서 다른 화면으로 가지 않는다. 급해 결과만 예외로 한 단계) |
| 움직임 | 단계가 바뀔 때 색·막대 길이 전환 1회(medium1 250ms). 깜빡임·반복 애니메이션 없음 |

### 7.6 음성

- Gleo 대화 상태: Sleep, Listening_Passive(2초 무입력 시 "운전석에서 듣는 중..."), Listening_Active, Thinking, Asking, Speaking, Standby(응답 화면이 있으면 5초 유지) [문서 `DG/Experiences/gleo-ai.md`]. 앱의 음성 상태 표시도 "듣는 중 / 말하는 중"을 분명히 나눈다.
- Gleo 패널은 GNB 바로 아래 레이어에 떠서 앱을 가릴 수 있다 [같은 문서]. 앱 화면이 Gleo 크롬처럼 보이지 않게 한다.
- TTS 재생 전 선행 묵음 **150~200ms** [문서 `PC/vehicle-app-developer-guide/voice-guidance-preroll-silence-guide.md`].
- 오디오 포커스: 재생 전 `AudioFocusRequest`, 음악과 함께 들리게 하려면 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. Gleo가 호출되어 `AUDIOFOCUS_LOSS_TRANSIENT`를 받으면 즉시 멈춘다 [문서 `PC/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development.md`]. 질문은 내비 안내처럼 짧게 끼어드는 소리라서 NAVIGATION 계열(`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`)이 가장 가깝다 [제안, 에뮬레이터 확인 필요].

### 7.7 CCP (로터리 컨트롤러)

- 사용자 설치 앱은 CCP 지원이 선택이다. 지원하지 않으면 모든 필수 조작을 터치로 끝낼 수 있어야 하고, `com.hmg.rotary.ROTARY_SUPPORTED=false` 메타데이터로 선언한다 [문서 `PC/vehicle-app-developer-guide/ccp-rotary-controller-development.md`].
- 최상위 화면에서 CCP Back은 앱을 종료하지 않고 `moveTaskToBack(true)`로 숨긴다(필수, 위반 시 반려 대상). 하위 화면·팝업에서는 이전 화면·팝업 닫기 [같은 문서].
- CCP 포커스는 회전·틸트·푸시가 적용될 대상을 표시한다. 팝업 상황에서는 틸트가 제한된다 [문서 `DG/Experiences/gleo-ai.md`]. 포커스 표시의 시각 사양은 Figma(Pleos Connect CCP UX Guideline)에만 있다.
- 이 앱은 CCP를 지원한다 [제안]: 모든 조작 요소를 포커스 가능하게, 포커스 링 4dp `focusRing`, 회전 순서는 화면에서 읽는 순서(위→아래, 왼쪽→오른쪽). 순서는 `screens.md`에 화면별로 적었다.

---

## 8. 가이드에 없는 것

| 항목 | 처리 |
|---|---|
| 색 값이 본문에 없음(전부 이미지) | 원본 이미지에서 읽음. 알파 표 라벨 반전, Background 표 견본 밀림, text 표 5~8행 이름 중복, charging_deep 다크 값 의심 |
| Material3 역할 대응 | 1.7 [제안] |
| 굵기 수치, Body Strong | 600 [제안] |
| 글자 크기 단위, 5mm/3mm 기준(em인지 글자 높이인지) | 엄격한 쪽으로 계산(7.3) |
| 컬럼 그리드, 간격 스케일, 브레이크포인트 | 3.5 [제안] |
| 최소 터치 크기, 터치 간격 | 64 / 24 (가이드 컴포넌트에서 추론) |
| 카드, 칩, 진행 막대 | 4.6 [제안] |
| Toggle·Text button·Tabs·Radio 외곽의 정확한 크기 | 도식에서 일부만 확인 |
| 주행 중 항목 수·메뉴 깊이 수치 | AAOS `CarUxRestrictions` 값을 런타임에 읽고, 없으면 7.5 [제안] |
| CCP 포커스 표시, UX 규제 상세 사양 | Figma 전용 또는 비공개("UX 규제 피그마 사양서는 Pleos Playground에 공개하지 않습니다") |
| 다크 테마 토스트 | 라이트 도식뿐. 같은 static 색 + 다크 테두리 [제안] |
| 햅틱 패턴 | 없음 |
| Asta Sans 파일 | 저장소에 없음 → 기본 서체 + TODO |

Figma 원본(`Pleos_Connect_display_guide_215.fig`, `Pleos_Connect_design_assets_215.fig`, CCP UX Guideline `.fig`)은 `DG/Introduction/intro.md`와 CCP 문서에 링크만 있고 이번 정리에는 쓰지 않았다. 컴포넌트 크기를 더 정확히 맞추려면 이 파일을 확인한다.
