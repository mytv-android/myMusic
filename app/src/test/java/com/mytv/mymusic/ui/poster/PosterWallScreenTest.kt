package com.mytv.mymusic.ui.poster

import androidx.compose.ui.unit.dp
import com.mytv.mymusic.ui.ListeningFeedUi
import org.junit.Assert.assertEquals
import org.junit.Test

class PosterWallScreenTest {
    private val feed = ListeningFeedUi(modeId = "fm", name = "私人漫游", providerId = "netease", providerName = "网易云音乐", appending = false)

    @Test
    fun queueSubtitleCountsFromOne() {
        assertEquals("30 首 · 正在播放第 3 首", wallSubtitle(null, 30, 2))
        assertEquals("30 首", wallSubtitle(null, 30, -1))
    }

    @Test
    fun feedSubtitleLeavesOutTheTotal() {
        assertEquals("听歌模式 · 网易云音乐 · 已听 12 首 · 后面还有 5 首", wallSubtitle(feed, 18, 12))
        assertEquals(
            "听歌模式 · 网易云音乐 · 已听 0 首 · 后面还有 14 首 · 正在续取…",
            wallSubtitle(feed.copy(appending = true), 15, 0),
        )
    }

    @Test
    fun insetsFollowThePrototype() {
        // 手机：状态栏 28 + 8 + 48 = 84，导航栏 0 + 92
        assertEquals(84.dp to 92.dp, wallInsets(WallMetrics.Phone, 28.dp, 0.dp, 800))
        assertEquals(0.dp to 0.dp, wallInsets(WallMetrics.Tablet, 24.dp, 48.dp, 800))
        assertEquals(80.dp to 0.dp, wallInsets(WallMetrics.Tv, 0.dp, 0.dp, 540))
        // 横屏手机让完放不下大卡：都不让
        assertEquals(0.dp to 0.dp, wallInsets(WallMetrics.Phone, 24.dp, 0.dp, 360))
    }
}
