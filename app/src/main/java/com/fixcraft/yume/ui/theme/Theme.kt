package com.fixcraft.yume.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = YumeOrchid,
    onPrimary = YumeInk,
    secondary = YumeRose,
    onSecondary = YumeInk,
    tertiary = Color(0xFFB8A5FF),
    background = YumeNight,
    onBackground = YumeMist,
    surface = YumeNightSurface,
    onSurface = YumeMist,
    surfaceVariant = Color(0xFF2B2145),
    onSurfaceVariant = Color(0xFFD5CBF0),
    primaryContainer = YumePurpleDeep,
    onPrimaryContainer = YumeMist,
    secondaryContainer = Color(0xFF4A2A56),
    onSecondaryContainer = YumeMist,
    tertiaryContainer = Color(0xFF3A2E62),
    onTertiaryContainer = YumeMist,
    outline = Color(0xFF8B80A9),
)

private val LightColorScheme = lightColorScheme(
    primary = YumePurple,
    onPrimary = YumeMist,
    secondary = Color(0xFF9A6BFF),
    onSecondary = YumeMist,
    tertiary = Color(0xFFB98BFF),
    onTertiary = YumeMist,
    background = YumeLavender,
    onBackground = YumeInk,
    surface = YumeMist,
    onSurface = YumeInk,
    surfaceVariant = Color(0xFFE7DDF8),
    onSurfaceVariant = YumeSlate,
    primaryContainer = YumeLilac,
    onPrimaryContainer = YumeInk,
    secondaryContainer = Color(0xFFF2E7FF),
    onSecondaryContainer = YumeInk,
    tertiaryContainer = Color(0xFFFFE4F8),
    onTertiaryContainer = YumeInk,
    outline = Color(0xFFB5A7D7),
)

@Composable
fun YumeVPNTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
