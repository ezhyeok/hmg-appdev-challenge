package dev.shimpyo.app.ui

import androidx.compose.ui.graphics.Color
import dev.shimpyo.app.ui.theme.DarkShimpyoColors
import dev.shimpyo.app.ui.theme.LightShimpyoColors
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingContrastTest {
    @Test fun drivingTextAndActiveVoiceRemainLegibleInBothThemesIncludingPressedButtons() {
        for (c in listOf(LightShimpyoColors, DarkShimpyoColors)) {
            val surfaces = listOf(c.backgroundPrimary, c.cardContainer, c.surfaceHigh)
            for (bg in surfaces) {
                checkContrast("primary", c.textPrimary, bg)
                checkContrast("secondary", c.textSecondary, bg)
                checkContrast("tertiary", c.textTertiary, bg)
            }
            checkContrast("filled", c.textOnFilledPrimary, c.buttonFilledEnabled)
            checkContrast("urgent", c.onUrgentContainer, c.urgentContainer)
            checkContrast("urgent pressed", c.onUrgentContainer, c.urgentPressedContainer)
            checkContrast("filled pressed", c.textOnFilledPrimary, c.buttonFilledPressed)
            checkContrast("soon", c.onGaugeColor, c.gaugeSoon)
            checkContrast("now", c.onGaugeColor, c.gaugeNow)
            for (bg in listOf(c.backgroundPrimary, over(c.buttonBasicEnabled, c.backgroundPrimary), over(c.buttonBasicPressed, c.backgroundPrimary))) {
                checkContrast("voice", c.voiceActive, bg)
                checkContrast("focus", c.focusRing, bg)
            }
        }
    }

    private fun checkContrast(label: String, fg: Color, bg: Color) {
        val a = luminance(over(fg, bg))
        val b = luminance(bg)
        val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        assertTrue("$label: $ratio", ratio >= 4.5)
    }

    private fun over(fg: Color, bg: Color) = Color(
        fg.red * fg.alpha + bg.red * (1f - fg.alpha),
        fg.green * fg.alpha + bg.green * (1f - fg.alpha),
        fg.blue * fg.alpha + bg.blue * (1f - fg.alpha),
    )

    private fun luminance(c: Color): Double {
        fun linear(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)
    }
}
