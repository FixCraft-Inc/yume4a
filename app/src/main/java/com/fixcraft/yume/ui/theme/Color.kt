package com.fixcraft.yume.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// The YUME colours are Material's TonalSpot scheme, the one Android builds
// from a wallpaper, seeded with the mark's colour as it shows over white
// (#e59fd1). Every role, button, card and text colour comes from that one seed,
// so they keep the same relationships as the phone's own dynamic colours.
// The desktop GUI (src/gui/qml/Theme.qml) uses the same values. The status
// colours are Material custom colours harmonized toward the seed.

internal data class StatusColors(val container: Color, val content: Color)

internal data class StatusPalette(val protected: StatusColors, val working: StatusColors, val failed: StatusColors)

private val LightStatus = StatusPalette(
    protected = StatusColors(Color(0xFFA5F1E1), Color(0xFF00201B)),
    working = StatusColors(Color(0xFFFFDBCF), Color(0xFF380D00)),
    failed = StatusColors(Color(0xFFFFD9DE), Color(0xFF3A0717)),
)

private val DarkStatus = StatusPalette(
    protected = StatusColors(Color(0xFF005046), Color(0xFFA5F1E1)),
    working = StatusColors(Color(0xFF72351F), Color(0xFFFFDBCF)),
    failed = StatusColors(Color(0xFF713341), Color(0xFFFFD9DE)),
)

@Composable
internal fun statusPalette(): StatusPalette =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) DarkStatus else LightStatus

// Traffic direction colors stay stable even when the application uses dynamic colors.
internal data class TrafficColors(val upload: Color, val download: Color)

@Composable
internal fun trafficColors(): TrafficColors = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
    TrafficColors(Color(0xFFF5B2E1), Color(0xFFABC7FF))
} else {
    TrafficColors(Color(0xFF834C75), Color(0xFF425E91))
}
