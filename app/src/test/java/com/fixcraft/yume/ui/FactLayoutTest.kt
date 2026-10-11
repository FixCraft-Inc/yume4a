package com.fixcraft.yume.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Pixel widths of a 1280-pixel screen at density 3, inside the overview's card. */
class FactLayoutTest {
    private val width = 1040
    private val gap = 48
    private val shared = width - gap
    private val labelColumn = (shared * 0.42f).toInt() - 1
    private val valueColumn = (shared * 0.58f).toInt() - 1

    private fun layout(label: Int, value: Int, wrapsCleanly: (Int) -> Boolean = { true }) =
        factLayout(width = width, gap = gap, labelWidth = label, valueWidth = value, valueWrapsCleanly = wrapsCleanly)

    private val notWrapped: (Int) -> Boolean = { fail("a fact on one line needs no wrapped layout"); false }

    @Test
    fun factsThatFitShareOneLineEvenWithALabelWiderThanItsColumn() {
        assertEquals(FactLayout.Line, layout(label = 290, value = 90, wrapsCleanly = notWrapped))
        assertEquals(FactLayout.Line, layout(label = 686, value = 45, wrapsCleanly = notWrapped))
        assertEquals(FactLayout.Line, layout(label = 600, value = shared - 600, wrapsCleanly = notWrapped))
    }

    @Test
    fun aLongValueWrapsInItsColumnBesideAOneLineLabel() {
        assertEquals(FactLayout.Columns, layout(label = 280, value = 900))
    }

    @Test
    fun aFactThatFitsNeitherWayGoesUnderItsLabel() {
        assertEquals(FactLayout.Stacked, layout(label = 640, value = 750))
        assertEquals(FactLayout.Stacked, layout(label = 600, value = shared - 599))
        // A value its column would force apart inside a word goes under the label instead.
        assertEquals(FactLayout.Stacked, layout(label = 300, value = 800, wrapsCleanly = { false }))
    }

    @Test
    fun columnEdgesAllowForTheRowsRounding() {
        assertEquals(FactLayout.Columns, layout(label = labelColumn, value = 2000))
        assertEquals(FactLayout.Stacked, layout(label = labelColumn + 1, value = 2000))
        var tried = -1
        assertEquals(FactLayout.Columns, layout(label = 200, value = 2000) { column -> tried = column; true })
        assertEquals(valueColumn, tried)
    }
}
