package com.jbokser.rideology_companion.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.EmojiSupportMatch
import androidx.compose.ui.unit.sp

private val defaults = Typography()
val RideTitleStyle = defaults.titleLarge.copy(
    fontFamily = FontFamily.Default,
    letterSpacing = 0.sp,
    lineHeight = 32.sp,
    platformStyle = PlatformTextStyle(
        spanStyle = null,
        paragraphStyle = PlatformParagraphStyle(EmojiSupportMatch.All, includeFontPadding = true)
    )
)
val Typography = Typography(
    headlineMedium = defaults.headlineMedium.copy(fontFamily = FontFamily.Monospace),
    titleLarge = defaults.titleLarge.copy(fontFamily = FontFamily.Monospace),
    titleMedium = defaults.titleMedium.copy(fontFamily = FontFamily.Monospace),
    bodyLarge = defaults.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    bodyMedium = defaults.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    bodySmall = defaults.bodySmall.copy(fontFamily = FontFamily.Monospace),
    labelLarge = defaults.labelLarge.copy(fontFamily = FontFamily.Monospace)
)
