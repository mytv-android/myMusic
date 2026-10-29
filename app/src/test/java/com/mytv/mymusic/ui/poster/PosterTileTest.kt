package com.mytv.mymusic.ui.poster

import com.mytv.mymusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterTileTest {
    @Test
    fun titleSizeScalesWithTheTileAndClamps() {
        // 手机 2×2 格 108dp：108 × 0.1 不到下限
        assertEquals(12.5f, TileStyle.Phone.titleSp(108f), 1e-4f)
        assertEquals(16.4f, TileStyle.Phone.titleSp(164f), 1e-4f)
        assertEquals(21f, TileStyle.Phone.titleSp(332f), 1e-4f)
        // 平板 2×2 格 198dp
        assertEquals(18.81f, TileStyle.Tablet.titleSp(198f), 1e-3f)
        assertEquals(36f, TileStyle.Tablet.titleSp(606f), 1e-4f)
        // 电视 2×2 格 147dp
        assertEquals(14f, TileStyle.Tv.titleSp(147f), 1e-3f)
        assertTrue(TileStyle.Tv.posterSp(147f) > TileStyle.Tv.titleSp(147f))
    }

    @Test
    fun styleFollowsTheMetrics() {
        assertEquals(TileStyle.Phone, tileStyleFor(WallMetrics.Phone))
        assertEquals(TileStyle.Tablet, tileStyleFor(WallMetrics.Tablet))
        assertEquals(TileStyle.Tv, tileStyleFor(WallMetrics.Tv))
    }

    @Test
    fun badgeIsTwoDigitsAtLeast() {
        assertEquals("01", wallBadgeText(0))
        assertEquals("30", wallBadgeText(29))
        assertEquals("100", wallBadgeText(99))
    }

    @Test
    fun descriptionReadsNumberTitleArtistAndState() {
        val song = Song(name = "独上西楼", artist = "邓丽君")
        assertEquals("第 3 首，独上西楼，邓丽君，正在播放", tileDescription(2, song, current = true))
        assertEquals("第 3 首，独上西楼，邓丽君", tileDescription(2, song, current = false))
        assertEquals("第 1 首，未命名歌曲", tileDescription(0, Song(), current = false))
    }

    @Test
    fun lightsOutSparesOnlyTheGrownCurrentCard() {
        assertFalse(lightsOutDim(grown = true, current = true, lightsOut = true))
        assertTrue(lightsOutDim(grown = true, current = false, lightsOut = true))
        assertTrue(lightsOutDim(grown = false, current = true, lightsOut = true))
        assertFalse(lightsOutDim(grown = false, current = false, lightsOut = false))
    }

    @Test
    fun tintFadesAsTheTileLifts() {
        assertEquals(0.52f, tintDimAlpha(0.52f, 0f), 1e-6f)
        assertEquals(0.26f, tintDimAlpha(0.52f, 0.5f), 1e-6f)
        assertEquals(0f, tintDimAlpha(0.52f, 1f), 1e-6f)
        assertEquals(0f, tintDimAlpha(0.52f, 2f), 1e-6f)
        assertEquals(0f, tintDimAlpha(0f, 0f), 1e-6f)
    }
}
