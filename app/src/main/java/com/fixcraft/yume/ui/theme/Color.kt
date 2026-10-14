package com.fixcraft.yume.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// The YUME palette, shared with the desktop GUI (src/gui/qml/Theme.qml) and
// the website (website/assets/tokens.css): every neutral and the accent sit on
// one plum axis, OKLCH hue 337, and each value below is that axis at the
// lightness and chroma in its comment. Change a value there and here together.

// Light: page and card steps, from the website's paper and cloud.
val YumePaper = Color(0xFFFFFBFE) // L99.2 C0.006
val YumePage = Color(0xFFFCF4FA) // L97.6 C0.011
val YumeCardLow = Color(0xFFFBF2F8) // L97 C0.013
val YumeCard = Color(0xFFF9EDF5) // L95.8 C0.017
val YumeCardHigh = Color(0xFFF7E8F2) // L94.5 C0.021
val YumeCardHighest = Color(0xFFF4E2EE) // L93 C0.025
val YumeDim = Color(0xFFE7D9E3) // L90 C0.02
val YumeInk = Color(0xFF392C35) // L31 C0.025, the website's ink
val YumeMuted = Color(0xFF6C5A67) // L49 C0.03
val YumeOutline = Color(0xFF93808D) // L62 C0.03, 3:1 on the page
val YumeRule = Color(0xFFE3D2DD) // L88 C0.024
val YumeAccentStrong = Color(0xFF9C4088) // L52 C0.15, links, focus and controls
val YumeAccentSoft = Color(0xFFFAD4EF) // L91 C0.055, selection
val YumeButton = Color(0xFFF8BBE7) // L86 C0.09, the main action, as on the website
val YumeTonal = Color(0xFFFCE0F3) // L93.5 C0.04
val YumeSecondary = Color(0xFF714966) // L46 C0.07

// Dark: the same axis at low lightness, so paper stays plum rather than black.
val YumeNight = Color(0xFF10080E) // L14.9 C0.019
val YumeNightSurface = Color(0xFF11090F) // L15.5 C0.019
val YumeNightLowest = Color(0xFF090407) // L12 C0.015
val YumeNightLow = Color(0xFF160D14) // L17.5 C0.02
val YumeNightCard = Color(0xFF1B1118) // L19.5 C0.021
val YumeNightCardHigh = Color(0xFF21171E) // L22 C0.022
val YumeNightCardHighest = Color(0xFF2A1D26) // L25 C0.025
val YumeNightBright = Color(0xFF31242D) // L28 C0.025
val YumeMist = Color(0xFFF0E4EC) // L93 C0.018, dark ink
val YumeNightMuted = Color(0xFFB4A6AF) // L74 C0.02
val YumeNightOutline = Color(0xFF7D7079) // L56 C0.02
val YumeNightRule = Color(0xFF3A2F37) // L32 C0.022
val YumeNightAccent = Color(0xFFE596D0) // L77 C0.12
val YumeNightButton = Color(0xFFE8A5D5) // L80 C0.10
val YumeNightButtonInk = Color(0xFF1F111B) // L20 C0.03
val YumeNightAccentSoft = Color(0xFF3E1935) // L28 C0.07
val YumeNightOnAccentSoft = Color(0xFFF7DBEE) // L92 C0.04
val YumeNightTonal = Color(0xFF3E2437) // L30 C0.05

// Connection states use the figure roles at matched lightness, as the desktop
// state pill does: mint while protected, amber while working, coral after a
// failure. Light shows the soft tint with the strong ink, dark the reverse.
internal data class StatusColors(val container: Color, val content: Color)

internal data class StatusPalette(val protected: StatusColors, val working: StatusColors, val failed: StatusColors)

private val LightStatus = StatusPalette(
    protected = StatusColors(Color(0xFFDBF4E8), Color(0xFF25694F)),
    working = StatusColors(Color(0xFFFFE8D2), Color(0xFF985521)),
    failed = StatusColors(Color(0xFFFFE3DF), Color(0xFFA73E3B)),
)

private val DarkStatus = StatusPalette(
    protected = StatusColors(Color(0xFF112D21), Color(0xFFAADFC6)),
    working = StatusColors(Color(0xFF3C220F), Color(0xFFF8C696)),
    failed = StatusColors(Color(0xFF401D1B), Color(0xFFFEBAB4)),
)

@Composable
internal fun statusPalette(): StatusPalette =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) DarkStatus else LightStatus

// Traffic direction colors stay stable even when the application uses dynamic colors.
internal data class TrafficColors(val upload: Color, val download: Color)

@Composable
internal fun trafficColors(): TrafficColors = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
    TrafficColors(YumeNightAccent, Color(0xFF91C9F4))
} else {
    TrafficColors(YumeAccentStrong, Color(0xFF1E628E))
}
