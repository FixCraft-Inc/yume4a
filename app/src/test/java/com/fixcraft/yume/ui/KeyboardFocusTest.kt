package com.fixcraft.yume.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardFocusTest {
    @Test
    fun tabPressMovesFocusOn() {
        assertTrue(isPlainTabPress(Key.Tab, KeyEventType.KeyDown, modified = false))
    }

    @Test
    fun otherKeysReleasesAndModifiedTabPassThrough() {
        // Enter starts a new line, which separates addresses.
        assertFalse(isPlainTabPress(Key.Enter, KeyEventType.KeyDown, modified = false))
        assertFalse(isPlainTabPress(Key.Spacebar, KeyEventType.KeyDown, modified = false))
        assertFalse(isPlainTabPress(Key.Tab, KeyEventType.KeyUp, modified = false))
        // Shift+Tab already moves focus back, and other combinations are not ours.
        assertFalse(isPlainTabPress(Key.Tab, KeyEventType.KeyDown, modified = true))
    }
}
