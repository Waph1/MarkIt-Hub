package com.waph1.markithub.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.waph1.markithub.ui.theme.DarkColors
import com.waph1.markithub.ui.theme.LightColors
import com.waph1.markithub.ui.theme.Typography

private val LightColorScheme = LightColors
private val DarkColorScheme = DarkColors

@Composable
fun CalendarAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
