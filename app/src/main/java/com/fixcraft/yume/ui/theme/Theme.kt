package com.fixcraft.yume.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.fixcraft.yume.data.ThemeColorMode

/** The palette in use, and whether Material's dynamic colours replace its scheme. */
internal class YumeColors(val palette: YumePalette, val dynamic: Boolean)

internal val LocalYumeColors = staticCompositionLocalOf { YumeColors(YumeLight, dynamic = false) }

/**
 * YUME's theme. The colours are the shared palette (config/palette.json in
 * YUME, generated into GeneratedPalette.kt): separate light and dark themes
 * and a dark variant over true black. The system theme takes Android's
 * dynamic colours instead, while status colours stay YUME's.
 */
@Composable
fun YumeVPNTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeColorMode: ThemeColorMode = ThemeColorMode.Yume,
    amoledDarkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val dynamic = themeColorMode == ThemeColorMode.System && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val palette = when {
        darkTheme && amoledDarkTheme -> YumeAmoled
        darkTheme -> YumeDark
        else -> YumeLight
    }
    val colorScheme = if (dynamic) {
        val context = LocalContext.current
        val scheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        if (darkTheme && amoledDarkTheme) {
            // Dynamic accents over the palette's true-black surfaces.
            val black = YumeAmoled.scheme
            scheme.copy(
                background = black.background,
                surface = black.surface,
                surfaceDim = black.surfaceDim,
                surfaceBright = black.surfaceBright,
                surfaceContainerLowest = black.surfaceContainerLowest,
                surfaceContainerLow = black.surfaceContainerLow,
                surfaceContainer = black.surfaceContainer,
                surfaceContainerHigh = black.surfaceContainerHigh,
                surfaceContainerHighest = black.surfaceContainerHighest,
                surfaceVariant = black.surfaceVariant,
            )
        } else {
            scheme
        }
    } else {
        palette.scheme
    }

    CompositionLocalProvider(LocalYumeColors provides YumeColors(palette, dynamic)) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
