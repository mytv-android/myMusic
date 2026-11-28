package com.mytv.mymusic.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionStateTest {
    private val order = listOf("a", "b", "c", "d", "e")

    @Test
    fun singleTapKeepsSelectionActiveWhenLastSongIsCleared() {
        val state = SelectionState()
        state.start()
        state.tap("b", order)
        assertEquals(setOf("b"), state.selected)
        state.tap("d", order)
        assertEquals(setOf("b", "d"), state.selected)
        state.tap("b", order)
        state.tap("d", order)
        assertTrue(state.active)
        assertTrue(state.selected.isEmpty())
    }

    @Test
    fun rangeModeTracksEndpointsAndSupportsMultipleRanges() {
        val state = SelectionState()
        state.start()
        state.toggleRangeMode()
        state.tap("b", order)
        assertEquals("b", state.anchor)
        assertNull(state.rangeEnd)
        state.tap("d", order)
        assertEquals(setOf("b", "c", "d"), state.selected)
        assertEquals("b", state.anchor)
        assertEquals("d", state.rangeEnd)

        // 完成一段后，再点一行开始下一段；之前的选择不清空。
        state.tap("a", order)
        assertEquals("a", state.anchor)
        assertNull(state.rangeEnd)
        assertEquals(setOf("a", "b", "c", "d"), state.selected)
        state.tap("b", order)
        assertEquals(setOf("a", "b", "c", "d"), state.selected)
        assertEquals("a", state.anchor)
        assertEquals("b", state.rangeEnd)
    }

    @Test
    fun reverseRangeAndTappingTheAnchorAgainWork() {
        val state = SelectionState()
        state.start()
        state.toggleRangeMode()
        state.tap("d", order)
        state.tap("b", order)
        assertEquals(setOf("b", "c", "d"), state.selected)
        assertEquals("d", state.anchor)
        assertEquals("b", state.rangeEnd)

        val cancelled = SelectionState()
        cancelled.start()
        cancelled.toggleRangeMode()
        cancelled.tap("b", order)
        cancelled.tap("b", order)
        assertTrue(cancelled.active)
        assertTrue(cancelled.selected.isEmpty())
        assertNull(cancelled.anchor)
        assertNull(cancelled.rangeEnd)
    }

    @Test
    fun togglingRangeModeKeepsSelectionAndClearsEndpoints() {
        val state = SelectionState()
        state.start()
        state.tap("a", order)
        state.toggleRangeMode()
        state.tap("c", order)
        state.tap("e", order)
        assertEquals(setOf("a", "c", "d", "e"), state.selected)
        assertEquals("c", state.anchor)
        assertEquals("e", state.rangeEnd)
        state.toggleRangeMode()
        assertEquals(setOf("a", "c", "d", "e"), state.selected)
        assertNull(state.anchor)
        assertNull(state.rangeEnd)
        assertFalse(state.rangeMode)
    }

    @Test
    fun toggleAllAndExitResetMode() {
        val state = SelectionState()
        state.start()
        state.toggleRangeMode()
        state.toggleAll(order)
        assertEquals(order.toSet(), state.selected)
        state.toggleAll(order)
        assertTrue(state.active)
        assertTrue(state.selected.isEmpty())
        assertNull(state.anchor)
        assertNull(state.rangeEnd)
        state.toggleAll(order)
        state.chromeContent = content(state)
        state.exit()
        assertFalse(state.active)
        assertFalse(state.rangeMode)
        assertTrue(state.selected.isEmpty())
        assertNull(state.chromeContent)
    }

    @Test
    fun anchorDisappearingFromListStartsFreshFromTappedRow() {
        val state = SelectionState()
        state.start()
        state.toggleRangeMode()
        state.tap("b", order)
        state.tap("e", listOf("a", "c", "e"))
        assertEquals(setOf("b", "e"), state.selected)
        assertEquals("e", state.anchor)
        assertNull(state.rangeEnd)
    }

    @Test
    fun selectionChromeKeepsNewestOwnerWhenOldPageDisposes() {
        val chrome = SelectionChrome()
        val oldOwner = Any()
        val newOwner = Any()
        val oldContent = content(SelectionState())
        val newContent = content(SelectionState())
        chrome.publish(oldOwner, oldContent)
        chrome.publish(newOwner, newContent)
        chrome.clear(oldOwner)
        assertSame(newContent, chrome.current)
        chrome.clear(newOwner)
        assertNull(chrome.current)
    }

    @Test
    fun selectionContentDerivesCountAndAllSelectedFromVisibleKeys() {
        val state = SelectionState()
        state.start()
        val content = content(state)
        assertEquals(0, content.count)
        assertFalse(content.allSelected)
        state.tap("b", order)
        assertEquals(1, content.count)
        assertFalse(content.allSelected)
        state.toggleAll(order)
        assertEquals(5, content.count)
        assertTrue(content.allSelected)
    }

    private fun content(state: SelectionState) = SelectionContent(
        state = state,
        keys = order,
        showDock = true,
        onPlay = {},
        onPlayNext = {},
        onAddTo = {},
        onExit = {},
    )
}
