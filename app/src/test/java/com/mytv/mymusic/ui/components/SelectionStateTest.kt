package com.mytv.mymusic.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionStateTest {
    private val order = listOf("a", "b", "c", "d", "e")

    @Test
    fun singleTapTogglesOneSongAndLastOneExitsSelection() {
        val state = SelectionState()
        state.start()
        state.tap("b", order)
        assertEquals(setOf("b"), state.selected)
        state.tap("d", order)
        assertEquals(setOf("b", "d"), state.selected)
        state.tap("b", order)
        state.tap("d", order)
        assertFalse(state.active)
        assertTrue(state.selected.isEmpty())
    }

    @Test
    fun rangeModeSelectsFromAnchorToTappedRow() {
        val state = SelectionState()
        state.start()
        state.toggleRangeMode()
        state.tap("b", order)
        state.tap("d", order)
        assertEquals(setOf("b", "c", "d"), state.selected)
        // 反向点选也取两端之间
        state.tap("a", order)
        assertEquals(setOf("a", "b", "c", "d"), state.selected)
        // 再点已经选中的延伸点：区间保留，不会把整段取消
        state.tap("c", order)
        assertEquals(setOf("a", "b", "c", "d"), state.selected)
    }

    @Test
    fun togglingRangeModeKeepsExistingSelection() {
        val state = SelectionState()
        state.start()
        state.tap("a", order)
        state.toggleRangeMode()
        state.tap("c", order)
        assertEquals(setOf("a", "c"), state.selected)
        state.tap("e", order)
        assertEquals(setOf("a", "c", "d", "e"), state.selected)
    }

    @Test
    fun toggleAllAndExitResetMode() {
        val state = SelectionState()
        state.start()
        state.toggleRangeMode()
        state.toggleAll(order)
        assertEquals(order.toSet(), state.selected)
        state.toggleAll(order)
        assertTrue(state.selected.isEmpty())
        state.toggleAll(order)
        state.exit()
        assertFalse(state.active)
        assertFalse(state.rangeMode)
        assertTrue(state.selected.isEmpty())
    }

    @Test
    fun anchorDisappearingFromListStartsFreshFromTappedRow() {
        val state = SelectionState()
        state.start()
        state.toggleRangeMode()
        state.tap("b", order)
        state.tap("e", listOf("a", "c", "e"))
        assertEquals(setOf("b", "e"), state.selected)
    }
}
