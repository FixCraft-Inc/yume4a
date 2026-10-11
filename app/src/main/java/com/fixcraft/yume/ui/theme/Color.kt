package com.fixcraft.yume.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance


val YumePlum = Color(0xFF70536B)
val YumePlumDeep = Color(0xFF563E53)
val YumeIris = Color(0xFF5F607B)
val YumeBerry = Color(0xFF7D5867)
val YumeInk = Color(0xFF201A21)
val YumePaper = Color(0xFFFFFBFE)
val YumeFog = Color(0xFFF5F1F6)
val YumeLilac = Color(0xFFEDE3EF)
val YumeCloud = Color(0xFFE6E0EB)
val YumeSlate = Color(0xFF6D6570)
val YumeNight = Color(0xFF101014)
val YumeNightSurface = Color(0xFF17171C)
val YumeNightRaised = Color(0xFF222129)
val YumeMist = Color(0xFFE8E1E8)
val YumeProtectedContainer = Color(0xFF173626)
val YumeProtectedContent = Color(0xFFCEF3DB)
val YumeVerifyContainer = Color(0xFF4E3B14)
val YumeVerifyContent = Color(0xFFFFE7A6)
val YumeErrorContainer = Color(0xFF4B1F27)
val YumeErrorContent = Color(0xFFFFD8DE)

// Traffic direction colors stay stable even when the application uses dynamic colors.
internal data class TrafficColors(val upload: Color, val download: Color)

@Composable
internal fun trafficColors(): TrafficColors = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
    TrafficColors(Color(0xFFD9BFD4), Color(0xFF91C9F4))
} else {
    TrafficColors(YumePlum, Color(0xFF1E628E))
}
