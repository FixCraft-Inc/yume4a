package com.fixcraft.yume.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.fixcraft.yume.data.ThemeColorMode

// Generated from one seed, see Color.kt. Every role is set, so nothing falls
// back to Material's stock colours.
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFF5B2E1),
    onPrimary = Color(0xFF4E1E45),
    primaryContainer = Color(0xFF68355C),
    onPrimaryContainer = Color(0xFFFFD7F0),
    secondary = Color(0xFFDCBED0),
    onSecondary = Color(0xFF3E2A38),
    secondaryContainer = Color(0xFF56404F),
    onSecondaryContainer = Color(0xFFF9DAED),
    tertiary = Color(0xFFF5B9A3),
    onTertiary = Color(0xFF4B2617),
    tertiaryContainer = Color(0xFFBA8470),
    onTertiaryContainer = Color(0xFF000000),
    background = Color(0xFF181216),
    onBackground = Color(0xFFECDFE5),
    surface = Color(0xFF181216),
    onSurface = Color(0xFFECDFE5),
    surfaceVariant = Color(0xFF4F444A),
    onSurfaceVariant = Color(0xFFD2C2CA),
    outline = Color(0xFF9B8D94),
    outlineVariant = Color(0xFF4F444A),
    inverseSurface = Color(0xFFECDFE5),
    inverseOnSurface = Color(0xFF362E33),
    inversePrimary = Color(0xFF834C75),
    surfaceTint = Color(0xFFF5B2E1),
    surfaceDim = Color(0xFF181216),
    surfaceBright = Color(0xFF3F373B),
    surfaceContainerLowest = Color(0xFF120C10),
    surfaceContainerLow = Color(0xFF201A1E),
    surfaceContainer = Color(0xFF241E22),
    surfaceContainerHigh = Color(0xFF2F282C),
    surfaceContainerHighest = Color(0xFF3A3337),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF834C75),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD7F0),
    onPrimaryContainer = Color(0xFF68355C),
    secondary = Color(0xFF6F5767),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF9DAED),
    onSecondaryContainer = Color(0xFF56404F),
    tertiary = Color(0xFF9A6956),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9A6956),
    onTertiaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFFFFF7F9),
    onBackground = Color(0xFF201A1E),
    surface = Color(0xFFFFF7F9),
    onSurface = Color(0xFF201A1E),
    surfaceVariant = Color(0xFFEFDEE6),
    onSurfaceVariant = Color(0xFF4F444A),
    outline = Color(0xFF7E7178),
    outlineVariant = Color(0xFFD2C2CA),
    inverseSurface = Color(0xFF362E33),
    inverseOnSurface = Color(0xFFFBEDF3),
    inversePrimary = Color(0xFFF5B2E1),
    surfaceTint = Color(0xFF834C75),
    surfaceDim = Color(0xFFE4D7DC),
    surfaceBright = Color(0xFFFFF7F9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFEF0F6),
    surfaceContainer = Color(0xFFF8EAF0),
    surfaceContainerHigh = Color(0xFFF2E5EA),
    surfaceContainerHighest = Color(0xFFECDFE5),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
)

// AMOLED keeps the dark scheme over pure black, with the containers stepping
// up from black on the scheme's own neutral palette.
private val AmoledDarkColorScheme = DarkColorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF241E22),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0609),
    surfaceContainer = Color(0xFF120C10),
    surfaceContainerHigh = Color(0xFF181216),
    surfaceContainerHighest = Color(0xFF201A1E),
    surfaceVariant = Color(0xFF181216),
)

private fun ColorScheme.withAmoledSurfaces(): ColorScheme {
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceBright = Color(0xFF141419),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF09090D),
        surfaceContainer = Color(0xFF0F0F14),
        surfaceContainerHigh = Color(0xFF15151B),
        surfaceContainerHighest = Color(0xFF1D1C24),
        surfaceVariant = Color(0xFF17161D),
    )
}

@Composable
fun YumeVPNTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeColorMode: ThemeColorMode = ThemeColorMode.Yume,
    amoledDarkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val dynamic = themeColorMode == ThemeColorMode.System && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val baseScheme = when {
        dynamic -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val colorScheme = when {
        darkTheme && amoledDarkTheme && !dynamic -> AmoledDarkColorScheme
        darkTheme && amoledDarkTheme -> baseScheme.withAmoledSurfaces()
        else -> baseScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
