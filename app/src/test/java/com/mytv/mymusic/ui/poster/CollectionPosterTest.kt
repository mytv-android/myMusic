package com.mytv.mymusic.ui.poster

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.CollectionKind
import com.mytv.mymusic.ui.components.DeviceKind
import com.mytv.mymusic.ui.queueInsertNextAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPosterTest {
    private fun song(id: String, source: String = "netease") = Song(id = id, name = id, source = source)

    private val songs = listOf(song("a"), song("b"), song("c"), song("d"))

    // ------------------------------------------------------------------ 正在放的是第几首

    @Test
    fun currentIndexMatchesSongKey() {
        assertEquals(2, posterCurrentIndex(songs, "netease:c", emptyList()))
        assertEquals(0, posterCurrentIndex(songs, "netease:a", listOf("qq:x")))
    }

    @Test
    fun currentIndexFallsBackToSourceKeysAfterAutoSwitch() {
        // 自动换源后正在放的是 QQ 的版本，歌单里是网易云的：按当前曲目各平台音源的 key 认出来
        assertEquals(1, posterCurrentIndex(songs, "qq:999", listOf("qq:999", "netease:b")))
    }

    @Test
    fun currentIndexIsMinusOneWhenNotInThisCollection() {
        assertEquals(-1, posterCurrentIndex(songs, "qq:999", emptyList()))
        assertEquals(-1, posterCurrentIndex(songs, "qq:999", listOf("qq:999", "kugou:1")))
        assertEquals(-1, posterCurrentIndex(songs, null, listOf("netease:a")))
        assertEquals(-1, posterCurrentIndex(emptyList(), "netease:a", emptyList()))
    }

    @Test
    fun currentIndexTakesFirstCopyOfDuplicateSong() {
        val dup = listOf(song("a"), song("b"), song("a"))
        assertEquals(0, posterCurrentIndex(dup, "netease:a", emptyList()))
    }

    // ------------------------------------------------------------------ 胶囊副标题

    @Test
    fun subtitleListsKindSourceAndCount() {
        assertEquals("歌单 · 网易云音乐 · 30 首", posterSubtitle("歌单", "网易云音乐", 30, loading = false, playingIndex = -1))
    }

    @Test
    fun subtitleAddsPlayingPositionWhenThisCollectionIsPlaying() {
        assertEquals(
            "专辑 · QQ 音乐 · 12 首 · 正在播放第 3 首",
            posterSubtitle("专辑", "QQ 音乐", 12, loading = false, playingIndex = 2),
        )
    }

    @Test
    fun subtitleShowsLoadingBeforeSongsArrive() {
        assertEquals("榜单 · 酷狗 · 正在读取…", posterSubtitle("榜单", "酷狗", 0, loading = true, playingIndex = -1))
        // 读完还是空的：不写“0 首”
        assertEquals("榜单 · 酷狗", posterSubtitle("榜单", "酷狗", 0, loading = false, playingIndex = -1))
    }

    @Test
    fun subtitleSkipsBlankSourceAndOutOfRangeIndex() {
        // 自建歌单没有来源
        assertEquals("自建歌单 · 8 首", posterSubtitle(LOCAL_PLAYLIST_LABEL, "", 8, loading = false, playingIndex = 8))
        assertEquals("自建歌单 · 8 首 · 正在播放第 8 首", posterSubtitle(LOCAL_PLAYLIST_LABEL, " ", 8, loading = false, playingIndex = 7))
    }

    @Test
    fun kindLabels() {
        assertEquals("榜单", collectionKindLabel(CollectionKind.Chart))
        assertEquals("歌单", collectionKindLabel(CollectionKind.Playlist))
        assertEquals("专辑", collectionKindLabel(CollectionKind.Album))
        assertEquals("歌手", collectionKindLabel(CollectionKind.Artist))
    }

    // ------------------------------------------------------------------ 浮层按设备

    @Test
    fun phoneToolbarAtBottomWithNarrowButtons() {
        val c = posterChromeFor(DeviceKind.Phone)
        assertFalse(c.topRow)
        assertTrue(c.showBack)
        assertEquals(40f, c.itemWidth)
        assertEquals(48f, c.itemHeight)
        assertEquals(60f, c.toolbarHeight)
        // 状态栏 24 + 8 + 胶囊 48 + 4
        assertEquals(84f, c.insetTop(24f))
        // 迷你播放器和导航栏 120 + 12 + 工具栏 60 + 4
        assertEquals(196f, c.insetBottom(120f))
    }

    @Test
    fun phoneToolbarFitsNarrowestPhone() {
        // 主按钮约 114dp（图标 20 + 间隔 6 + 四个字 + 左右 14/18）+ 2 + 5 × 40 + 左右各 6，两边各留 12 的边距
        val c = posterChromeFor(DeviceKind.Phone)
        val width = 114f + 2f + 5 * c.itemWidth + 2 * c.toolbarPadding
        assertTrue("toolbar $width dp", width + 2 * c.side <= 360f)
    }

    @Test
    fun tabletToolbarAtBottomWithFullSizeButtons() {
        val c = posterChromeFor(DeviceKind.Tablet)
        assertFalse(c.topRow)
        assertTrue(c.showBack)
        assertEquals(48f, c.itemWidth)
        assertEquals(64f, c.toolbarHeight)
        assertEquals(24f + 12f + 48f + 4f, c.insetTop(24f))
        assertEquals(24f + 64f + 4f, c.insetBottom(0f))
    }

    @Test
    fun tvTopRowHasNoToolbarButKeepsTheCardWhereItWas() {
        val c = posterChromeFor(DeviceKind.Tv)
        assertTrue(c.topRow)
        assertFalse(c.showBack)
        // 工具栏去掉了，顶排还按原来的 56dp 留：墙让出的 80dp 和大卡位置都不变
        assertEquals(56f, c.topRowHeight)
        assertEquals(80f, c.insetTop(0f))
        assertEquals(0f, c.insetBottom(48f))
        // 电视 540dp 高，让出顶排后放得下 451dp 的大卡
        assertTrue(540f - c.insetTop(0f) - c.insetBottom(0f) >= WallMetrics.Tv.grownSize)
    }

    // ------------------------------------------------------------------ 电视：整页操作接在单曲面板下面

    @Test
    fun tvPageMenuHasEveryToolbarActionTitledByKindAndName() {
        val calls = mutableListOf<String>()
        val extra = PosterExtraAction(Icons.Filled.Link, "绑定到我的歌单", { calls += "extra" }, on = true, stateDescription = "已绑定，平台更新自动同步")
        val menu = collectionWallMenu(
            kindLabel = "榜单",
            title = "热歌榜",
            playLabel = "播放全部",
            hasSongs = true,
            extra = extra,
            playingHere = true,
            sourceSettings = { calls += "source" },
            onPlayAll = { calls += "play" },
            onShuffle = { calls += "shuffle" },
            onLocate = { calls += "locate" },
            onList = { calls += "list" },
            onSettings = { calls += "settings" },
        )
        assertEquals("榜单 · 热歌榜", menu.title)
        assertEquals(
            listOf("播放全部", "随机播放", "绑定到我的歌单", "回到正在播放", "换源设置", "切回列表视图", "海报墙设置"),
            menu.items.map { it.label },
        )
        // 已绑定 / 已收藏写在第二行，别的行没有第二行
        assertEquals("已绑定，平台更新自动同步", menu.items[2].summary)
        assertTrue(menu.items.filterIndexed { i, _ -> i != 2 }.all { it.summary.isEmpty() })
        menu.items.forEach { it.onClick() }
        assertEquals(listOf("play", "shuffle", "extra", "locate", "source", "list", "settings"), calls)
    }

    @Test
    fun tvPageMenuDropsWhatCannotApply() {
        val menu = collectionWallMenu(
            kindLabel = LOCAL_PLAYLIST_LABEL,
            title = "",
            playLabel = "播放全部",
            hasSongs = false,
            extra = PosterExtraAction(Icons.Filled.Edit, "编辑歌单", {}),
            playingHere = false,
            sourceSettings = null,
            onPlayAll = {}, onShuffle = {}, onLocate = {}, onList = {}, onSettings = {},
        )
        assertEquals("自建歌单 · 详情", menu.title)
        // 没有歌不给随机，这张没在放不给回到正在播放，不是榜单没有换源设置；没收藏的状态不写第二行
        assertEquals(listOf("播放全部", "编辑歌单", "切回列表视图", "海报墙设置"), menu.items.map { it.label })
        assertEquals("", menu.items[1].summary)
    }

    @Test
    fun queuePageMenuOffersLocateOnlyWhenCurrentIsInTheQueue() {
        assertEquals(listOf("回到正在播放", "海报墙设置"), queueWallMenu("播放队列", true, {}, {}).items.map { it.label })
        assertEquals(listOf("海报墙设置"), queueWallMenu("听歌模式", false, {}, {}).items.map { it.label })
        assertEquals("播放队列", queueWallMenu("播放队列", true, {}, {}).title)
    }

    // ------------------------------------------------------------------ 下一首播放：不在队列里的歌插到哪

    @Test
    fun insertNextGoesRightAfterCurrent() {
        assertEquals(3, queueInsertNextAt(current = 2, size = 6))
        assertEquals(6, queueInsertNextAt(current = 5, size = 6))
    }

    @Test
    fun insertNextAppendsWhenNothingIsCurrent() {
        assertEquals(6, queueInsertNextAt(current = -1, size = 6))
        assertEquals(6, queueInsertNextAt(current = 9, size = 6))
        assertEquals(0, queueInsertNextAt(current = 0, size = 0))
    }
}
