package dev.shimpyo.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/*
 * Pleos Connect 컬러 토큰.
 *
 * 출처: docs/contest/02_Pleos_개발가이드/connect-guide/docs-design/foundations/colors.md
 * 값은 이 문서가 링크한 팔레트 표 이미지(grayscale, white/gray alpha, static, system color 표)에서 읽었다.
 * 토큰별 근거와 판단(표 오류 처리 포함)은 docs/design/pleos-design-tokens.md 1장에 정리했다.
 *
 * 이름 규칙: 가이드 토큰 이름(접두어 ivi_ 생략)을 camelCase로 옮겼다. 예) background_primary -> backgroundPrimary.
 * 앱 전용 토큰(게이지, 급해 버튼, 타임라인 등)은 "앱 시맨틱" 구역에 따로 두고 가이드 토큰으로만 조합한다.
 */

// ---------------------------------------------------------------------------------------------
// 1. Basic color (Scale token)
// ---------------------------------------------------------------------------------------------

/** basic_00 ~ basic_900. 라이트와 다크가 같은 11단계 원값을 서로 뒤집어 쓴다 (basic_400만 같다). */
@Immutable
class PleosBasicScale(
    val basic00: Color,
    val basic50: Color,
    val basic100: Color,
    val basic200: Color,
    val basic300: Color,
    val basic400: Color,
    val basic500: Color,
    val basic600: Color,
    val basic700: Color,
    val basic800: Color,
    val basic900: Color,
)

val LightBasic = PleosBasicScale(
    basic00 = Color(0xFFFFFFFF),
    basic50 = Color(0xFFF7F8FA),
    basic100 = Color(0xFFEDEEF2),
    basic200 = Color(0xFFE0E1E6),
    basic300 = Color(0xFFC7C9CE),
    basic400 = Color(0xFFA4A8AE),
    basic500 = Color(0xFF686A72),
    basic600 = Color(0xFF44464E),
    basic700 = Color(0xFF313236),
    basic800 = Color(0xFF1D1E21),
    basic900 = Color(0xFF131417),
)

val DarkBasic = PleosBasicScale(
    basic00 = Color(0xFF131417),
    basic50 = Color(0xFF1D1E21),
    basic100 = Color(0xFF313236),
    basic200 = Color(0xFF44464E),
    basic300 = Color(0xFF686A72),
    basic400 = Color(0xFFA4A8AE),
    basic500 = Color(0xFFC7C9CE),
    basic600 = Color(0xFFE0E1E6),
    basic700 = Color(0xFFEDEEF2),
    basic800 = Color(0xFFF7F8FA),
    basic900 = Color(0xFFFFFFFF),
)

/** Static color. 테마와 무관하게 항상 같은 값이다 (Overlay 위 요소, 토스트 등). */
object PleosStatic {
    val Light100 = Color(0xFFFFFFFF)
    val Light200 = Light100.copy(alpha = 0.84f)
    val Light300 = Light100.copy(alpha = 0.64f)
    val Light400 = Light100.copy(alpha = 0.32f)

    val Dark100 = Color(0xFF131417)
    val Dark200 = Dark100.copy(alpha = 0.84f)
    val Dark300 = Dark100.copy(alpha = 0.64f)
    val Dark400 = Dark100.copy(alpha = 0.32f)
}

/** basic_alpha_*_50 ~ 500 (= 5% ~ 50%). */
@Immutable
class PleosAlphaScale(
    val alpha50: Color,
    val alpha100: Color,
    val alpha200: Color,
    val alpha300: Color,
    val alpha400: Color,
    val alpha500: Color,
)

private fun alphaScale(base: Color) = PleosAlphaScale(
    alpha50 = base.copy(alpha = 0.05f),
    alpha100 = base.copy(alpha = 0.10f),
    alpha200 = base.copy(alpha = 0.20f),
    alpha300 = base.copy(alpha = 0.30f),
    alpha400 = base.copy(alpha = 0.40f),
    alpha500 = base.copy(alpha = 0.50f),
)

/*
 * Gray alpha(basic_alpha_dark_*)는 배경을 어둡게 누르는 알파, White alpha(basic_alpha_light_*)는 밝게 띄우는 알파다.
 * 가이드의 두 알파 표는 Light/Dark 칸의 글자 라벨이 견본과 서로 뒤바뀌어 있다.
 * 견본 픽셀, button 표(라이트 alpha_dark_50 견본 = #F3F3F3 = #131417 5%), dropdown 표(다크 견본 = 흰색 10%)가
 * 모두 아래 해석과 맞아서 이 값을 쓴다.
 */
val LightAlphaDark = alphaScale(PleosStatic.Dark100)
val DarkAlphaDark = alphaScale(PleosStatic.Light100)
val LightAlphaLight = alphaScale(PleosStatic.Light100)
val DarkAlphaLight = alphaScale(PleosStatic.Dark100)

// ---------------------------------------------------------------------------------------------
// 2. Informative / Regulation (라이트·다크 값이 다른 원색)
// ---------------------------------------------------------------------------------------------

object PleosInformative {
    val ActiveLight = Color(0xFF02C265)
    val ActiveDark = Color(0xFF00E074)
    val PositiveLight = Color(0xFF0064FF)
    val PositiveDark = Color(0xFF0082FF)
    val NegativeLight = Color(0xFFFE3D16)
    val NegativeDark = Color(0xFFFF4C28)
    val AutonomousDrivingLight = Color(0xFF5A46FA)
    val AutonomousDrivingDark = Color(0xFF6A57FF)
}

/** 규제(텔테일 계열) 색. 앱에서는 주의 단계 표시에 regulation_orange만 쓴다. */
object PleosRegulation {
    val BlueLight = Color(0xFF0064FF)
    val BlueDark = Color(0xFF0082FF)
    val GreenLight = Color(0xFF00BA13)
    val GreenDark = Color(0xFF55F165)
    val Yellow = Color(0xFFFFC224)
    val Orange = Color(0xFFFF8A00)
    val RedLight = Color(0xFFF62E24)
    val RedDark = Color(0xFFFF4339)
    val Bluetooth = Color(0xFF0082FC)
}

// ---------------------------------------------------------------------------------------------
// 3. System color (시맨틱 토큰) + 앱 시맨틱
// ---------------------------------------------------------------------------------------------

/**
 * 화면에서 쓰는 색은 모두 여기서 꺼낸다. `ShimpyoTheme.colors.textPrimary`처럼 쓴다.
 * 가이드 토큰은 같은 이름, 앱 토큰은 아래쪽 "앱 시맨틱" 구역에 있다.
 */
@Immutable
class ShimpyoColors(
    val isDark: Boolean,
    val basic: PleosBasicScale,
    val alphaDark: PleosAlphaScale,
    val alphaLight: PleosAlphaScale,

    // Background (Z축 가장 아래)
    val backgroundPrimary: Color,
    val backgroundSecondary: Color,
    val backgroundPopup: Color,

    // Surface
    val surfaceBasic: Color,
    val surfaceLow: Color,
    val surfaceHigh: Color,
    val surfaceAccent: Color,

    // Text: 테마 배경 위 글자
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textQuaternary: Color,

    // Text: Filled 버튼처럼 반전된 바탕 위 글자 (표의 5~8행)
    val textOnFilledPrimary: Color,
    val textOnFilledSecondary: Color,
    val textOnFilledTertiary: Color,
    val textOnFilledQuaternary: Color,

    // Icon (값은 text와 같다)
    val iconPrimary: Color,
    val iconSecondary: Color,
    val iconTertiary: Color,
    val iconQuaternary: Color,

    // Button
    val buttonBasicEnabled: Color,
    val buttonBasicPressed: Color,
    val buttonBasicDisabled: Color,
    val buttonFilledEnabled: Color,
    val buttonFilledPressed: Color,
    val buttonFilledDisabled: Color,
    val buttonSwitchEnabled: Color,
    val buttonSwitchPressed: Color,
    val buttonSwitchDisabled: Color,

    // Switch
    val switchOn: Color,
    val switchOff: Color,
    val switchDisabled: Color,
    val switchKnobOn: Color,
    val switchKnobOff: Color,
    val switchKnobDisabled: Color,

    // Controller
    val controllerSliderNormal: Color,
    val controllerSliderPressed: Color,
    val controllerSliderKnob: Color,
    val controllerTabBackground: Color,
    val controllerStepperBackground: Color,

    // Fields / Dropdown
    val fieldBackground: Color,
    val fieldFocused: Color,
    val dropdownNormal: Color,
    val dropdownPressed: Color,

    // Dim / Overlay
    val dimDarkPrimary: Color,
    val dimDarkSecondary: Color,
    val dimDarkTertiary: Color,
    val dimLightPrimary: Color,
    val dimLightSecondary: Color,
    val dimLightTertiary: Color,
    val overlayDefault: Color,

    // Informative
    val informativeActive: Color,
    val informativePositive: Color,
    val informativeNegative: Color,
    val informativeAutonomousDriving: Color,

    // Regulation
    val regulationBlue: Color,
    val regulationGreen: Color,
    val regulationYellow: Color,
    val regulationOrange: Color,
    val regulationRed: Color,

    // ---- 앱 시맨틱 (가이드 토큰 조합. 근거: pleos-design-tokens.md 1.6, screens.md 0장) ----

    /**
     * 카드 바탕 = surface_basic + cardStroke 2dp (가이드 설정 화면 도식의 컨테이너 방식).
     * 다크에서 surface_high를 쓰면 게이지·경고 색 대비가 3.4~3.9:1로 떨어져서 surface_basic을 쓴다.
     */
    val cardContainer: Color,
    /** 카드 테두리, 2dp. alpha_dark_100 (가이드 설정 화면 도식의 컨테이너 테두리 #131417 10%) */
    val cardStroke: Color,
    val divider: Color,
    /** 토스트: static_dark_200 바탕 + static_light_100 글자 (Toast 도식) */
    val toastContainer: Color,
    val toastContent: Color,
    /** 다크에서는 토스트 바탕과 배경의 대비가 1.1:1이라 테두리를 더한다. 라이트는 투명 */
    val toastStroke: Color,

    /** 게이지 단계 OK(남은 시간 > 45분) */
    val gaugeOk: Color,
    /** 게이지 단계 WATCH. 색은 OK와 같고 단계 문구로 구분한다 (색은 행동이 필요할 때만 바꾼다) */
    val gaugeWatch: Color,
    /** 게이지 단계 SOON (regulation_orange) */
    val gaugeSoon: Color,
    /** 게이지 단계 NOW (informative_negative) */
    val gaugeNow: Color,
    /** 게이지 빈 부분 */
    val gaugeTrack: Color,
    /** 게이지 색 위에 글자나 아이콘을 올릴 때. 네 단계 색 모두 대비 4.5:1 이상 */
    val onGaugeColor: Color,

    /** 급해 버튼 바탕(informative_negative)과 글자(static_dark_100, 대비 5.2:1 라이트 / 5.5:1 다크) */
    val urgentContainer: Color,
    val onUrgentContainer: Color,

    /** 계획 화면 타임라인 */
    val timelineRoute: Color,
    val timelineOpportunity: Color,
    val timelineGap: Color,
    val timelineCongestion: Color,
    val timelineTunnel: Color,
    val timelinePlannedStop: Color,

    /** 음성 듣는 중 표시, CCP 포커스 링 */
    val voiceActive: Color,
    val focusRing: Color,
) {
    /** 눌림 때도 급해 버튼 글자가 4.5:1 이상이다(라이트 4.62, 다크 4.94). */
    val urgentPressedContainer: Color = Color.Black.copy(alpha = 0.06f).compositeOver(urgentContainer)
}

private fun buildColors(dark: Boolean): ShimpyoColors {
    val b = if (dark) DarkBasic else LightBasic
    val aDark = if (dark) DarkAlphaDark else LightAlphaDark
    val aLight = if (dark) DarkAlphaLight else LightAlphaLight

    // text_*: 라이트 static_dark_100~400, 다크 static_light_100~400
    val t1 = if (dark) PleosStatic.Light100 else PleosStatic.Dark100
    val t2 = if (dark) PleosStatic.Light200 else PleosStatic.Dark200
    val t3 = if (dark) PleosStatic.Light300 else PleosStatic.Dark300
    val t4 = if (dark) PleosStatic.Light400 else PleosStatic.Dark400
    // 반전 글자: 라이트 static_light_*, 다크 static_dark_*
    val f1 = if (dark) PleosStatic.Dark100 else PleosStatic.Light100
    val f2 = if (dark) PleosStatic.Dark200 else PleosStatic.Light200
    val f3 = if (dark) PleosStatic.Dark300 else PleosStatic.Light300
    val f4 = if (dark) PleosStatic.Dark400 else PleosStatic.Light400

    val active = if (dark) PleosInformative.ActiveDark else PleosInformative.ActiveLight
    val positive = if (dark) PleosInformative.PositiveDark else PleosInformative.PositiveLight
    val negative = if (dark) PleosInformative.NegativeDark else PleosInformative.NegativeLight
    val autonomous = if (dark) PleosInformative.AutonomousDrivingDark else PleosInformative.AutonomousDrivingLight

    val surfaceBasic = if (dark) b.basic50 else b.basic00
    val surfaceHigh = b.basic100
    val filledEnabled = b.basic600

    return ShimpyoColors(
        isDark = dark,
        basic = b,
        alphaDark = aDark,
        alphaLight = aLight,

        backgroundPrimary = b.basic50,
        backgroundSecondary = b.basic00,
        backgroundPopup = if (dark) b.basic100 else b.basic00,

        surfaceBasic = surfaceBasic,
        surfaceLow = if (dark) b.basic00 else b.basic50,
        surfaceHigh = surfaceHigh,
        surfaceAccent = b.basic200,

        textPrimary = t1,
        textSecondary = t2,
        textTertiary = t3,
        textQuaternary = t4,

        textOnFilledPrimary = f1,
        textOnFilledSecondary = f2,
        textOnFilledTertiary = f3,
        textOnFilledQuaternary = f4,

        iconPrimary = t1,
        iconSecondary = t2,
        iconTertiary = t3,
        iconQuaternary = t4,

        buttonBasicEnabled = aDark.alpha50,
        buttonBasicPressed = aDark.alpha100,
        buttonBasicDisabled = aLight.alpha200,
        buttonFilledEnabled = filledEnabled,
        buttonFilledPressed = if (dark) b.basic500 else b.basic700,
        buttonFilledDisabled = aDark.alpha50,
        buttonSwitchEnabled = if (dark) aDark.alpha200 else b.basic00,
        buttonSwitchPressed = if (dark) aDark.alpha200 else aLight.alpha500,
        buttonSwitchDisabled = if (dark) aDark.alpha300 else aLight.alpha300,

        switchOn = active,
        switchOff = if (dark) aDark.alpha100 else aDark.alpha300,
        switchDisabled = aDark.alpha100,
        switchKnobOn = if (dark) b.basic900 else b.basic00,
        switchKnobOff = if (dark) b.basic400 else b.basic00,
        switchKnobDisabled = if (dark) aDark.alpha200 else b.basic00,

        controllerSliderNormal = if (dark) aDark.alpha100 else aDark.alpha200,
        controllerSliderPressed = if (dark) aDark.alpha200 else aDark.alpha300,
        controllerSliderKnob = if (dark) aDark.alpha300 else b.basic00,
        controllerTabBackground = aDark.alpha50,
        controllerStepperBackground = if (dark) aLight.alpha100 else b.basic00,

        fieldBackground = b.basic00,
        fieldFocused = b.basic700,
        dropdownNormal = if (dark) aDark.alpha100 else b.basic00,
        dropdownPressed = if (dark) aDark.alpha300 else b.basic100,

        dimDarkPrimary = aDark.alpha50,
        dimDarkSecondary = aDark.alpha100,
        dimDarkTertiary = aDark.alpha200,
        dimLightPrimary = aLight.alpha500,
        dimLightSecondary = aLight.alpha400,
        dimLightTertiary = aLight.alpha300,
        overlayDefault = if (dark) aLight.alpha500 else aDark.alpha200,

        informativeActive = active,
        informativePositive = positive,
        informativeNegative = negative,
        informativeAutonomousDriving = autonomous,

        regulationBlue = if (dark) PleosRegulation.BlueDark else PleosRegulation.BlueLight,
        regulationGreen = if (dark) PleosRegulation.GreenDark else PleosRegulation.GreenLight,
        regulationYellow = PleosRegulation.Yellow,
        regulationOrange = PleosRegulation.Orange,
        regulationRed = if (dark) PleosRegulation.RedDark else PleosRegulation.RedLight,

        cardContainer = surfaceBasic,
        cardStroke = aDark.alpha100,
        divider = aDark.alpha100,
        toastContainer = PleosStatic.Dark200,
        toastContent = PleosStatic.Light100,
        toastStroke = if (dark) aDark.alpha100 else Color.Transparent,

        gaugeOk = positive,
        gaugeWatch = positive,
        gaugeSoon = PleosRegulation.Orange,
        gaugeNow = negative,
        gaugeTrack = aDark.alpha100,
        onGaugeColor = PleosStatic.Dark100,

        urgentContainer = negative,
        onUrgentContainer = PleosStatic.Dark100,

        timelineRoute = aDark.alpha200,
        timelineOpportunity = positive,
        timelineGap = PleosRegulation.Orange,
        timelineCongestion = negative,
        timelineTunnel = b.basic400,
        timelinePlannedStop = filledEnabled,

        // 파랑은 Basic 버튼의 눌림 바탕에서 3.3~3.8:1뿐이다. 음성 상태·CCP 링은 두 테마 모두 높은 대비로.
        // 듣는 중 문구와 채워진 마이크 아이콘이 상태를 구분하므로 색에만 의존하지 않는다.
        voiceActive = t1,
        focusRing = t1,
    )
}

val LightShimpyoColors: ShimpyoColors = buildColors(dark = false)
val DarkShimpyoColors: ShimpyoColors = buildColors(dark = true)
