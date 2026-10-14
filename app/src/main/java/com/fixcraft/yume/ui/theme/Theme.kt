package com.fixcraft.yume.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.fixcraft.yume.data.ThemeColorMode

// Every role is set, the surface containers included: Material fills cards,
// dialogs and chips from them, and its stock values are lavender, off the
// plum axis the rest of the palette shares with the desktop and website.
private val DarkColorScheme = darkColorScheme(
    primary = YumeNightAccent,
    onPrimary = YumeNightButtonInk,
    primaryContainer = YumeNightAccentSoft,
    onPrimaryContainer = YumeNightOnAccentSoft,
    inversePrimary = YumeAccentStrong,
    secondary = Color(0xFFE5BCD9),
    onSecondary = Color(0xFF2B1826),
    secondaryContainer = YumeNightTonal,
    onSecondaryContainer = YumeNightOnAccentSoft,
    tertiary = Color(0xFFE5BCD9),
    onTertiary = Color(0xFF2B1826),
    tertiaryContainer = YumeNightTonal,
    onTertiaryContainer = YumeNightOnAccentSoft,
    background = YumeNight,
    onBackground = YumeMist,
    surface = YumeNightSurface,
    onSurface = YumeMist,
    surfaceVariant = YumeNightCardHighest,
    onSurfaceVariant = YumeNightMuted,
    surfaceTint = YumeNightAccent,
    inverseSurface = Color(0xFFECE1E8),
    inverseOnSurface = YumeNightCardHigh,
    outline = YumeNightOutline,
    outlineVariant = YumeNightRule,
    surfaceBright = YumeNightBright,
    surfaceDim = YumeNight,
    surfaceContainerLowest = YumeNightLowest,
    surfaceContainerLow = YumeNightLow,
    surfaceContainer = YumeNightCard,
    surfaceContainerHigh = YumeNightCardHigh,
    surfaceContainerHighest = YumeNightCardHighest,
)

private val LightColorScheme = lightColorScheme(
    primary = YumeAccentStrong,
    onPrimary = YumePaper,
    primaryContainer = YumeAccentSoft,
    onPrimaryContainer = YumeInk,
    inversePrimary = Color(0xFFF8BBE7),
    secondary = YumeSecondary,
    onSecondary = YumePaper,
    secondaryContainer = YumeTonal,
    onSecondaryContainer = YumeInk,
    tertiary = YumeSecondary,
    onTertiary = YumePaper,
    tertiaryContainer = YumeTonal,
    onTertiaryContainer = YumeInk,
    background = YumePage,
    onBackground = YumeInk,
    surface = YumePaper,
    onSurface = YumeInk,
    surfaceVariant = YumeCardHighest,
    onSurfaceVariant = YumeMuted,
    surfaceTint = YumeAccentStrong,
    inverseSurface = Color(0xFF352A30),
    inverseOnSurface = Color(0xFFF7F1F4),
    outline = YumeOutline,
    outlineVariant = YumeRule,
    surfaceBright = YumePaper,
    surfaceDim = YumeDim,
    surfaceContainerLowest = YumePaper,
    surfaceContainerLow = YumeCardLow,
    surfaceContainer = YumeCard,
    surfaceContainerHigh = YumeCardHigh,
    surfaceContainerHighest = YumeCardHighest,
)

// AMOLED keeps the dark scheme's plum accents over pure black, with the
// containers stepping up from black on the same axis.
private val AmoledDarkColorScheme = DarkColorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF1C1319),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF040203),
    surfaceContainer = YumeNightLowest,
    surfaceContainerHigh = YumeNight,
    surfaceContainerHighest = Color(0xFF190F16),
    surfaceVariant = Color(0xFF120A10),
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

    // The main action is the soft pink pill of the desktop and website. With
    // the phone's dynamic colors it follows them instead.
    val action = when {
        dynamic -> ActionColors(colorScheme.primary, colorScheme.onPrimary)
        darkTheme -> ActionColors(YumeNightButton, YumeNightButtonInk)
        else -> ActionColors(YumeButton, YumeInk)
    }

    CompositionLocalProvider(LocalActionColors provides action) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

/** The colors of a screen's main action button. */
@Immutable
data class ActionColors(val container: Color, val content: Color)

internal val LocalActionColors = staticCompositionLocalOf { ActionColors(YumeButton, YumeInk) }

/** Button colors for a screen's main action, such as Connect or Add a kit. */
@Composable
fun mainActionColors(): ButtonColors {
    val action = LocalActionColors.current
    return ButtonDefaults.buttonColors(containerColor = action.container, contentColor = action.content)
}
