package com.jbokser.rideology_companion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RideColors = darkColorScheme(
    primary = RideGreen,
    secondary = RideGreen,
    tertiary = RideGreen,
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color.Black,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color.White,
    outline = RideGreen,
    error = Color.White,
    onError = Color.Black
)

@Composable
fun RideologyCompanionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RideColors, typography = Typography, content = content)
}
