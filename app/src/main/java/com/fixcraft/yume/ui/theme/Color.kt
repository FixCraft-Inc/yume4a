package com.fixcraft.yume.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Colours Material has no role for, all from the shared palette. Status and
// traffic colours stay YUME's under dynamic colours too, so a state reads the
// same whichever theme the phone uses.

internal data class StatusColors(val container: Color, val content: Color)

internal data class StatusPalette(val protected: StatusColors, val working: StatusColors, val failed: StatusColors)

@Composable
internal fun statusPalette(): StatusPalette {
    val palette = LocalYumeColors.current.palette
    return StatusPalette(
        protected = StatusColors(palette.outside.soft, palette.outside.onSoft),
        working = StatusColors(palette.disguise.soft, palette.disguise.onSoft),
        failed = StatusColors(palette.refused.soft, palette.refused.onSoft),
    )
}

internal data class TrafficColors(val upload: Color, val download: Color)

@Composable
internal fun trafficColors(): TrafficColors {
    val palette = LocalYumeColors.current.palette
    return TrafficColors(palette.scheme.primary, palette.data.strong)
}

/**
 * A screen's main action in the palette's button colours: blossom with plum
 * text in light and the soft pink in dark. Dynamic colours keep Material's
 * own filled button.
 */
@Composable
internal fun mainActionColors(): ButtonColors {
    val colors = LocalYumeColors.current
    if (colors.dynamic) return ButtonDefaults.buttonColors()
    return ButtonDefaults.buttonColors(
        containerColor = colors.palette.button,
        contentColor = colors.palette.buttonInk,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
}

/**
 * A slider whose filled track is the palette's soft track colour, so a full
 * light slider is not a heavy bar. The thumb keeps the accent.
 */
@Composable
internal fun yumeSliderColors(): SliderColors {
    val colors = LocalYumeColors.current
    if (colors.dynamic) return SliderDefaults.colors()
    return SliderDefaults.colors(
        activeTrackColor = colors.palette.track,
        activeTickColor = MaterialTheme.colorScheme.onPrimary,
    )
}
