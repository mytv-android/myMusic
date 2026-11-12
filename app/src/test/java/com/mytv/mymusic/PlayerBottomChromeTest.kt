package com.mytv.mymusic

import com.mytv.mymusic.ui.player.PlayerBottomChrome
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerBottomChromeTest {
    @Test
    fun noReportsIsZero() {
        assertEquals(0, PlayerBottomChrome().bottomPx)
    }

    @Test
    fun takesTallestReport() {
        val chrome = PlayerBottomChrome()
        chrome.report(PlayerBottomChrome.Panel, 112)
        chrome.report(PlayerBottomChrome.PanelLyrics, 68)
        assertEquals(112, chrome.bottomPx)
    }

    @Test
    fun clearingLayoutThatLeftDropsItsHeight() {
        val chrome = PlayerBottomChrome()
        chrome.report(PlayerBottomChrome.Panel, 112)
        chrome.report(PlayerBottomChrome.Tabletop, 480)
        assertEquals(480, chrome.bottomPx)
        chrome.clear(PlayerBottomChrome.Tabletop)
        assertEquals(112, chrome.bottomPx)
    }

    @Test
    fun clearAllIsZero() {
        val chrome = PlayerBottomChrome()
        chrome.report(PlayerBottomChrome.Tv, 132)
        chrome.clearAll()
        assertEquals(0, chrome.bottomPx)
    }

    @Test
    fun negativeReportIsClampedToZero() {
        val chrome = PlayerBottomChrome()
        chrome.report(PlayerBottomChrome.Tabletop, -40)
        assertEquals(0, chrome.bottomPx)
    }
}
