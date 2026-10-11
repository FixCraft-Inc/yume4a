package com.fixcraft.yume.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Tab moves focus on from a multi-line text field whose text has no use for a tab
 * character, as it does from a single-line one. Compose would type the tab, which leaves
 * a keyboard user no way forward to a dialog's buttons. Shift+Tab already moves back,
 * and Enter still starts a new line. Focus moves within the field's own window: a dialog
 * has its own, so the screen's focus manager would move the wrong focus.
 */
internal fun Modifier.tabMovesFocusOn(): Modifier = this then TabMovesFocusOn

/** A press of Tab without Shift, Ctrl, Alt or Meta. Its release and every other key pass through. */
internal fun isPlainTabPress(key: Key, type: KeyEventType, modified: Boolean): Boolean =
    key == Key.Tab && type == KeyEventType.KeyDown && !modified

private data object TabMovesFocusOn : ModifierNodeElement<TabMovesFocusOnNode>() {
    override fun create() = TabMovesFocusOnNode()

    override fun update(node: TabMovesFocusOnNode) = Unit
}

private class TabMovesFocusOnNode : Modifier.Node(), KeyInputModifierNode, CompositionLocalConsumerModifierNode {
    // Before the field sees the key, which is when it would type the tab.
    override fun onPreKeyEvent(event: KeyEvent): Boolean {
        val modified = event.isShiftPressed || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        if (!isPlainTabPress(event.key, event.type, modified)) return false
        currentValueOf(LocalFocusManager).moveFocus(FocusDirection.Next)
        return true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = false
}
