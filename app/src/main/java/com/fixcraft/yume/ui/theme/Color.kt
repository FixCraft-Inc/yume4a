package com.fixcraft.yume.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// The YUME palette. Light mode is white with a trace of rose in the cards,
// soft pink for buttons and selection, and a dusty rose accent that is only
// as deep as readable text needs. Dark mode keeps the plum axis (OKLCH hue
// 337) of the desktop GUI (src/gui/qml/Theme.qml) and the website
// (website/assets/tokens.css). Each comment gives the OKLCH source.

// Light: white page, cards one step at a time.
val YumePaper = Color(0xFFFFFFFF)
val YumePage = Color(0xFFFFFFFF)
val YumeCardLow = Color(0xFFFAF5F8) // L97.5 C0.006 H345
val YumeCard = Color(0xFFF7F1F4) // L96.4 C0.008 H345
val YumeCardHigh = Color(0xFFF5EDF1) // L95.3 C0.010 H345
val YumeCardHighest = Color(0xFFF2E8ED) // L94 C0.012 H345
val YumeDim = Color(0xFFE8E0E4) // L91.5 C0.010 H345
val YumeInk = Color(0xFF352A30) // L30 C0.02 H345
val YumeMuted = Color(0xFF6B5B64) // L49 C0.025 H345
val YumeOutline = Color(0xFF908289) // L62 C0.02 H345, 3.6:1 on white
val YumeRule = Color(0xFFE3D9DE) // L89.5 C0.012 H345
val YumeAccentStrong = Color(0xFFA06486) // L58 C0.09 H345, 4.5:1 for text on white
val YumeAccentSoft = Color(0xFFFDDEEE) // L93 C0.04 H345, selection
val YumeButton = Color(0xFFF8BBE7) // L86 C0.09 H337, the main action, as on the desktop and website
val YumeTonal = Color(0xFFFCE8F2) // L95 C0.025 H345
val YumeSecondary = Color(0xFF6D4D5E) // L46 C0.05 H345

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
