package dev.shimpyo.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * 쉼표 테마. Pleos Connect UI Design Guide의 라이트·다크 토큰을 둘 다 쓴다.
 *
 * 가이드는 다크 우선이 아니다. 시스템 설정 Screen Mode에 Auto(주간/야간 자동)·라이트·다크가 있고
 * (connect-guide/docs-design/Introduction/theme.md 도식), 앱은 "야간/주간 모드 지원"이 원칙이다
 * (Introduction/intro.md). 그래서 기본값은 시스템의 야간 모드(AAOS는 차량이 주간/야간을 정한다)를 따른다.
 *
 * 사용:
 *   ShimpyoTheme { ... }
 *   ShimpyoTheme.colors.textPrimary, PleosType.TitleLarge, DriveType.GaugeValue, Dimens.Touch.driving
 */

/** 화면에서 가이드 토큰을 꺼내는 곳 (MaterialTheme와 같은 방식) */
object ShimpyoTheme {
    val colors: ShimpyoColors
        @Composable
        @ReadOnlyComposable
        get() = LocalShimpyoColors.current
}

val LocalShimpyoColors = staticCompositionLocalOf { LightShimpyoColors }

@Composable
fun ShimpyoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkShimpyoColors else LightShimpyoColors
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    CompositionLocalProvider(LocalShimpyoColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ShimpyoTypography,
            shapes = ShimpyoShapes,
            content = content,
        )
    }
}

/**
 * Material3 역할 매핑. 가이드에 M3 역할 대응표는 없으므로 아래는 제안이다(pleos-design-tokens.md 1.7).
 * 원칙: 버튼은 가이드처럼 무채색(primary = button_filled), 색은 의미가 있을 때만
 * (secondary = informative_positive, tertiary = informative_active, error = informative_negative).
 * M3 컴포넌트가 쓰는 surfaceContainer 계열은 가이드 basic 스케일로 맞췄다(material3 1.2 이상 필요).
 */
private fun ShimpyoColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = buttonFilledEnabled,
        onPrimary = textOnFilledPrimary,
        primaryContainer = buttonBasicEnabled,
        onPrimaryContainer = textPrimary,
        inversePrimary = if (isDark) LightBasic.basic600 else DarkBasic.basic600,
        secondary = informativePositive,
        // #0082FF(다크) 위 흰 글자는 3.7:1이라 다크에서는 static_dark_100을 쓴다
        onSecondary = if (isDark) PleosStatic.Dark100 else PleosStatic.Light100,
        secondaryContainer = surfaceAccent,
        onSecondaryContainer = textPrimary,
        tertiary = informativeActive,
        onTertiary = PleosStatic.Dark100,
        tertiaryContainer = surfaceHigh,
        onTertiaryContainer = textPrimary,
        background = backgroundPrimary,
        onBackground = textPrimary,
        surface = surfaceBasic,
        onSurface = textPrimary,
        surfaceVariant = surfaceHigh,
        onSurfaceVariant = textTertiary,
        // M3 톤 엘리베이션(보라 틴트)을 끈다
        surfaceTint = Color.Transparent,
        inverseSurface = toastContainer,
        inverseOnSurface = toastContent,
        error = informativeNegative,
        // informative_negative 위 흰 글자는 3.6:1(라이트)·3.3:1(다크)이라 static_dark_100(5.2:1·5.5:1)을 쓴다
        onError = PleosStatic.Dark100,
        errorContainer = surfaceHigh,
        onErrorContainer = textPrimary,
        outline = basic.basic300,
        outlineVariant = cardStroke,
        scrim = overlayDefault,
        surfaceBright = if (isDark) basic.basic100 else basic.basic00,
        surfaceDim = if (isDark) basic.basic00 else basic.basic100,
        surfaceContainerLowest = basic.basic00,
        surfaceContainerLow = if (isDark) basic.basic50 else basic.basic00,
        surfaceContainer = if (isDark) basic.basic100 else basic.basic00,
        surfaceContainerHigh = backgroundPopup,
        surfaceContainerHighest = if (isDark) basic.basic200 else basic.basic100,
    )
}

private val LightColorScheme: ColorScheme = LightShimpyoColors.toColorScheme()
private val DarkColorScheme: ColorScheme = DarkShimpyoColors.toColorScheme()

/** M3 Shapes. 반경은 가이드 컴포넌트 값(16/24/32/48)이다. */
val ShimpyoShapes = Shapes(
    extraSmall = RoundedCornerShape(Dimens.Radius.xs),
    small = RoundedCornerShape(Dimens.Radius.s),
    medium = RoundedCornerShape(Dimens.Radius.m),
    large = RoundedCornerShape(Dimens.Radius.l),
    extraLarge = RoundedCornerShape(Dimens.Radius.xl),
)

/**
 * 모션 토큰 [가이드: foundations/interaction.md — Easing cubic-bezier(0.16, 1, 0.3, 1), ivi_motion_duration_*].
 * 주행 중에는 상태 변화를 알리는 짧은 전환만 쓰고, 반복 애니메이션·자동 스크롤은 쓰지 않는다.
 */
object ShimpyoMotion {
    const val DURATION_SHORT_1 = 100
    const val DURATION_SHORT_2 = 150
    const val DURATION_MEDIUM_1 = 250
    const val DURATION_MEDIUM_2 = 300
    const val DURATION_LONG_1 = 400
    const val DURATION_LONG_2 = 450

    val Easing: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** 눌렀을 때 축소 비율 [가이드: Button interaction, scale down 95%] */
    const val PRESSED_SCALE = 0.95f

    /** 입력 후 눌림 효과가 시작돼야 하는 시간 [가이드: 0.25초] */
    const val MAX_TOUCH_RESPONSE_MS = 250

    /** 이 시간 안에 결과를 못 보여 주면 진행 표시를 띄운다 [가이드: 앱 심사 — 2초] */
    const val MAX_ACTION_RESPONSE_MS = 2000
}
