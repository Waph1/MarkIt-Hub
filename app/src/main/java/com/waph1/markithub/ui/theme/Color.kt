package com.waph1.markithub.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val GoogleBlue = Color(0xFF4285F4)
val GoogleRed = Color(0xFFDB4437)
val GoogleYellow = Color(0xFFF4B400)
val GoogleGreen = Color(0xFF0F9D58)

// Light Theme Colors
val PrimaryBlue = Color(0xFF1967D2)
val BackgroundBlue = Color(0xFFE8F0FE)
val OnPrimary = Color.White
val SurfaceLight = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF202124)
val OnSurfaceVariantLight = Color(0xFF5F6368)
val OutlineLight = Color(0xFFDADCE0)

// Dark Theme Colors
val PrimaryBlueDark = Color(0xFF8AB4F8)
val SurfaceDark = Color(0xFF202124)
val OnSurfaceDark = Color(0xFFE8EAED)
val OnSurfaceVariantDark = Color(0xFF9AA0A6)
val PrimaryContainerDark = Color(0xFF28354A)
val OutlineDark = Color(0xFF3C4043)


val LightColors = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = OnPrimary,
    primaryContainer = BackgroundBlue,
    onPrimaryContainer = PrimaryBlue,
    secondary = GoogleBlue,
    onSecondary = Color.White,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight
)

val DarkColors = darkColorScheme(
    primary = PrimaryBlueDark,
    onPrimary = SurfaceDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = PrimaryBlueDark,
    secondary = GoogleBlue,
    onSecondary = Color.White,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark
)
