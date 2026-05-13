package com.fixcraft.yume.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

internal val appDialogProperties = DialogProperties(usePlatformDefaultWidth = false)

internal fun Modifier.appDialogWidth(): Modifier {
    return fillMaxWidth(0.96f).widthIn(max = 680.dp)
}
