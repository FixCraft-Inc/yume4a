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
import com.fixcraft.yume.data.ThemeColorMode

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD9BFD4),
    onPrimary = Color(0xFF3F2940),
    secondary = Color(0xFFC9C4E8),
    onSecondary = Color(0xFF30314B),
    tertiary = Color(0xFFE8BECC),
    onTertiary = Color(0xFF472734),
    background = YumeNight,
    onBackground = YumeMist,
    surface = YumeNightSurface,
    onSurface = YumeMist,
    surfaceVariant = YumeNightRaised,
    onSurfaceVariant = Color(0xFFCAC3CA),
    primaryContainer = YumePlumDeep,
    onPrimaryContainer = Color(0xFFF4DDEA),
    secondaryContainer = Color(0xFF41445C),
    onSecondaryContainer = Color(0xFFE4E0FF),
    tertiaryContainer = Color(0xFF5A3D49),
    onTertiaryContainer = Color(0xFFFDDCE5),
    outline = Color(0xFF8C848E),
)

private val LightColorScheme = lightColorScheme(
    primary = YumePlum,
    onPrimary = Color(0xFFFFFBFF),
    secondary = YumeIris,
    onSecondary = Color(0xFFFFFBFF),
    tertiary = YumeBerry,
    onTertiary = Color(0xFFFFFBFF),
    background = YumeFog,
    onBackground = YumeInk,
    surface = YumePaper,
    onSurface = YumeInk,
    surfaceVariant = YumeCloud,
    onSurfaceVariant = YumeSlate,
    primaryContainer = YumeLilac,
    onPrimaryContainer = YumeInk,
    secondaryContainer = Color(0xFFE2E2F4),
    onSecondaryContainer = Color(0xFF1D1E33),
    tertiaryContainer = Color(0xFFF1E0E7),
    onTertiaryContainer = Color(0xFF32161F),
    outline = Color(0xFF958D98),
)

private val AmoledDarkColorScheme = darkColorScheme(
    primary = Color(0xFFD3C0E8),
    onPrimary = Color(0xFF382B4B),
    secondary = Color(0xFFBEC6F3),
    onSecondary = Color(0xFF28304C),
    tertiary = Color(0xFFE9C0DA),
    onTertiary = Color(0xFF4A2940),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEAE3EA),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFEAE3EA),
    surfaceVariant = Color(0xFF15141A),
    onSurfaceVariant = Color(0xFFC9C3CD),
    primaryContainer = Color(0xFF2B2234),
    onPrimaryContainer = Color(0xFFF1DDFB),
    secondaryContainer = Color(0xFF222839),
    onSecondaryContainer = Color(0xFFE1E6FF),
    tertiaryContainer = Color(0xFF362330),
    onTertiaryContainer = Color(0xFFFFDDEC),
    outline = Color(0xFF817B86),
)

@Composable
fun YumeVPNTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeColorMode: ThemeColorMode = ThemeColorMode.Yume,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        themeColorMode == ThemeColorMode.System && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        themeColorMode == ThemeColorMode.Amoled && darkTheme -> AmoledDarkColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
