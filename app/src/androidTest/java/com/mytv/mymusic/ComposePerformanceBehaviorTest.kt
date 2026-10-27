package com.mytv.mymusic

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicViewModel
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.adaptive.BottomPlayerBar
import com.mytv.mymusic.ui.adaptive.NowPlayingPane
import com.mytv.mymusic.playback.SleepTimerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import com.mytv.mymusic.data.update.UpdateInfo
import com.mytv.mymusic.data.update.UpdatePhase
import com.mytv.mymusic.data.update.UpdateUiState
import com.mytv.mymusic.ui.settings.AboutScreen
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.aggregate.VersionKind
import com.mytv.mymusic.aggregate.TrackSource
import com.mytv.mymusic.aggregate.SearchStatus
import com.mytv.mymusic.data.TopList
import com.mytv.mymusic.ui.ChartsUiState
import com.mytv.mymusic.ui.SearchUiState
import com.mytv.mymusic.ui.charts.TvChartsTestHost
import com.mytv.mymusic.ui.search.SearchScreen
import com.mytv.mymusic.ui.search.SearchResultsTestHost
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.LibrarySearchUiState
import com.mytv.mymusic.ui.LocalPlaylistSummary
import com.mytv.mymusic.ui.library.AddTracksSheet
import com.mytv.mymusic.ui.LyricsParser
import com.mytv.mymusic.ui.MergeDialog
import com.mytv.mymusic.ui.TrackActionsSheet
import com.mytv.mymusic.ui.components.AppWindow
import com.mytv.mymusic.ui.components.DeviceKind
import com.mytv.mymusic.ui.components.LocalAppSnackbar
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.WavySeekBar
import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.ui.player.CoverStage
import com.mytv.mymusic.ui.player.LyricsList
import com.mytv.mymusic.ui.player.LyricsSizing
import com.mytv.mymusic.ui.settings.QrCodeImage
import com.mytv.mymusic.ui.theme.Appearance
import com.mytv.mymusic.ui.theme.LocalAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ComposePerformanceBehaviorTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val indexedList = SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)

    @Test
    fun mergeCandidatesAreLazyAndSelectionSurvivesRestore() {
        val candidates = List(200) { track(it) }
        var merged: String? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            TestTheme {
                MergeDialog(track(-1), candidates, { merged = it.id }, {})
            }
        }
        assertTrue(compose.onAllNodes(hasText("candidate-", substring = true)).fetchSemanticsNodes().size < 30)
        compose.onNode(indexedList).performScrollToIndex(150)
        compose.onNodeWithText("candidate-150").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("合并", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("track-150", merged) }
    }

    @Test
    fun playlistPickerCanSelectAnOffscreenPlaylist() {
        val playlists = List(180) { LocalPlaylistSummary("list-$it", "playlist-$it", it, 0, false) }
        var selected: String? = null
        var dismissed = false
        compose.setContent {
            TestTheme {
                TrackActionsSheet(
                    Song(id = "sample", name = "test song", source = "test"), false, playlists,
                    onToggleFavorite = {}, onDownload = {}, onAddToPlaylist = { selected = it },
                    onOpenSource = null, onRemoveFromPlaylist = null, onDismiss = { dismissed = true },
                )
            }
        }
        compose.onNodeWithText("加到歌单").performClick()
        assertTrue(compose.onAllNodes(hasText("playlist-", substring = true)).fetchSemanticsNodes().size < 30)
        compose.onNode(indexedList).performScrollToIndex(179)
        compose.onNodeWithText("playlist-179").performClick()
        compose.runOnIdle {
            assertEquals("list-179", selected)
            assertTrue(dismissed)
        }
    }

    @Test
    fun addTracksOnlyComposesVisibleResultsAndPreservesAddedState() {
        val songs = List(80) { Song(id = "$it", name = "result-$it", artist = "artist", source = "test") }
        val existing = mutableStateOf(emptySet<String>())
        var added: String? = null
        compose.setContent {
            TestTheme {
                AddTracksSheet(
                    search = LibrarySearchUiState(query = "query", results = songs),
                    existing = existing.value, onSearch = {},
                    onAdd = { added = it.key; existing.value = existing.value + it.key }, onDismiss = {},
                )
            }
        }
        assertTrue(compose.onAllNodes(hasText("result-", substring = true)).fetchSemanticsNodes().size < 30)
        compose.onNode(indexedList).performScrollToIndex(79)
        compose.onNodeWithText("result-79").performClick()
        compose.runOnIdle { assertEquals("test:79", added) }
        compose.onNodeWithText("已添加").assertIsDisplayed()
    }

    @Test
    fun progressKeepsCoarseAccessibilityAndSeekActions() {
        val position = mutableLongStateOf(20_500L)
        var seek = -1L
        compose.setContent {
            TestTheme { WavySeekBar(position, 120_000L, false, { seek = it }) }
        }
        val bar = compose.onNodeWithContentDescription("播放进度")
        bar.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已播放 20 秒，共 2 分 0 秒"))
        compose.runOnIdle { position.longValue = 29_999L }
        bar.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已播放 20 秒，共 2 分 0 秒"))
        compose.runOnIdle { position.longValue = 30_000L }
        bar.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已播放 30 秒，共 2 分 0 秒"))
        bar.performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.runOnIdle { assertEquals(60_000L, seek) }
    }

    @Test
    fun lyricsFollowActiveLineAndSeekOnClick() {
        val lyrics = LyricsParser.parse((0..50).joinToString("\n") { "[00:${it.toString().padStart(2, '0')}.00]lyric-$it" })
        val active = mutableIntStateOf(0)
        var sought = -1L
        compose.setContent {
            TestTheme {
                Box(Modifier.size(350.dp, 440.dp)) {
                    LyricsList(lyrics, active, { sought = it }, sizing = LyricsSizing.PhoneLyrics)
                }
            }
        }
        compose.runOnIdle { active.intValue = 20 }
        compose.onNodeWithText("lyric-20").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(20_000L, sought) }
    }

    @Test
    fun karaokeLineIsReadOnceAndStillSeeksOnClick() {
        // 逐字行每个字是暗字、亮字两层 Text：读屏只该看到整行一个描述，外层行的点按跳转还在
        val lyrics = LyricsParser.parse(listOf("[00:01.00]<00:01.00>晴<00:01.40>天<00:01.80>的<00:02.20>歌", "[00:05.00]下一句").joinToString("\n"))
        val active = mutableIntStateOf(0)
        val position = mutableLongStateOf(1_500L)
        var sought = -1L
        compose.setContent {
            TestTheme {
                Box(Modifier.size(350.dp, 440.dp)) {
                    LyricsList(lyrics, active, { sought = it }, position = position, sizing = LyricsSizing.PhoneLyrics)
                }
            }
        }
        // 读屏看到的是合并后的树（被清掉的子节点不报给无障碍服务）：逐字的 Text 一个都不剩，整行只有一个带点按的节点
        compose.onAllNodesWithText("晴", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("天", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("晴天的歌").assertCountEquals(1)
        compose.onNode(hasContentDescription("晴天的歌") and hasClickAction()).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1_000L, sought) }
    }

    @Test
    fun coverOutlineTurnsWhilePlayingEvenWithoutACover() {
        // 设计稿：播放时轮廓 40 秒一圈；没有封面图时转的是首字占位的轮廓
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TestTheme {
                CompositionLocalProvider(LocalAppearance provides Appearance(reduceMotion = false)) {
                    CoverStage(
                        cover = "", title = "spin", trackKey = "spin", playing = true, shapeChoice = CoverShapeChoice.Cookie12,
                        halo = false, modifier = Modifier.size(200.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        val cover = compose.onNodeWithContentDescription("spin 的封面")
        val before = cover.captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(3_000)
        val after = cover.captureToImage().toPixelMap()
        var changed = 0
        for (y in 0 until before.height step 2) for (x in 0 until before.width step 2) if (before[x, y] != after[x, y]) changed++
        assertTrue("outline should have turned, $changed px changed", changed > 50)
    }

    @Test
    fun coverGesturesUseLatestCallbackAndSettle() {
        val version = mutableStateOf("old")
        var skipped = ""
        var favorites = 0
        compose.setContent {
            TestTheme {
                val current = version.value
                CoverStage(
                    cover = "", title = "gesture", trackKey = "gesture", playing = false, shapeChoice = CoverShapeChoice.Cookie12,
                    onSkip = { skipped = "$current:$it" }, onDoubleTap = { favorites++ },
                    modifier = Modifier.size(340.dp),
                )
            }
        }
        compose.runOnIdle { version.value = "new" }
        val art = compose.onNodeWithContentDescription("gesture 的封面")
        art.performTouchInput { swipe(Offset(width * 0.95f, height * 0.5f), Offset(width * 0.05f, height * 0.5f), 500) }
        compose.runOnIdle { assertEquals("new:1", skipped) }
        art.performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(1, favorites) }
    }

    @Test
    fun coveredTabletPlayersStopConsumingPositionAndResume() {
        val visible = mutableStateOf(true)
        val position = mutableLongStateOf(0L)
        lateinit var actions: MusicActions
        compose.runOnUiThread { actions = MusicActions(ViewModelProvider(compose.activity)[MusicViewModel::class.java]) }
        val state = MusicUiState(current = Song(id = "one", name = "sample", source = "test"), duration = 120_000L)
        compose.setContent {
            TestTheme {
                Column {
                    BottomPlayerBar(state, position, actions, {}, visible = visible.value)
                    NowPlayingPane(state, position, emptyList(), actions, {}, {}, SleepTimerState(), visible = visible.value)
                }
            }
        }
        compose.onAllNodesWithContentDescription("播放进度").assertCountEquals(2)
        compose.runOnIdle { visible.value = false; position.longValue = 40_000L }
        compose.onAllNodesWithContentDescription("播放进度").assertCountEquals(0)
        compose.runOnIdle { visible.value = true }
        compose.onAllNodesWithContentDescription("播放进度").assertCountEquals(2)
    }

    @Test
    fun widePlayerDoubleTapStillFavoritesTheSong() {
        lateinit var model: MusicViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        val actions = MusicActions(model)
        val song = Song(id = "wide-test-${System.nanoTime()}", name = "wide gesture", source = "test")
        val position = mutableLongStateOf(0L)
        compose.setContent {
            TestTheme(kind = DeviceKind.Tablet) {
                val favorites = model.favorites.collectAsState()
                com.mytv.mymusic.ui.player.PlayerScreen(
                    MusicUiState(current = song, duration = 120_000L), position, favorites.value,
                    SleepTimerState(), actions, {}, {}, {},
                )
            }
        }
        compose.onNodeWithContentDescription("wide gesture 的封面").performTouchInput { doubleClick() }
        compose.waitUntil(10_000) { model.favorites.value.any { it.key == song.key } }
        compose.onNodeWithContentDescription("收藏").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收藏"),
        )
    }

    @Test
    fun nowPlayingPaneRestoresScrollAfterFullscreenCoverage() {
        val actions = testActions()
        val visible = mutableStateOf(true)
        val queue = List(10) { Song(id = "pane-$it", name = "queue-$it", source = "test") }
        val state = MusicUiState(current = queue.first(), queue = queue, duration = 120_000L)
        val position = mutableLongStateOf(0L)
        compose.setContent {
            TestTheme(kind = DeviceKind.Tablet) {
                Box(Modifier.size(380.dp, 320.dp)) {
                    NowPlayingPane(state, position, emptyList(), actions, {}, {}, SleepTimerState(), visible.value)
                }
            }
        }
        val scroll = compose.onNode(hasScrollAction())
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 600f) }
        val before = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(before > 0f)
        compose.runOnIdle { visible.value = false }
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
        compose.runOnIdle { visible.value = true }
        val after = compose.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before, after, 1f)
    }

    @Test
    fun backgroundLyricsParsingKeepsTheLatestTextWithoutFalseEmptyState() {
        val raw = mutableStateOf("[00:01.00]first lyric")
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Boolean>>()
        compose.setContent {
            TestTheme {
                val parsed = com.mytv.mymusic.ui.player.rememberParsedLyrics(raw.value)
                androidx.compose.runtime.SideEffect { seen += parsed.lyrics.raw to parsed.loading }
                androidx.compose.material3.Text(parsed.lyrics.lines.firstOrNull()?.text.orEmpty())
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("first lyric").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { raw.value = (0..10_000).joinToString("\n") { "[00:01.00]intermediate-$it" } }
        compose.runOnIdle { raw.value = "[00:02.00]latest lyric" }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("latest lyric").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("latest lyric").assertIsDisplayed()
        assertTrue("非空歌词解析期间不能发布假空态", seen.none { it.first.isEmpty() && !it.second })
        assertEquals("[00:02.00]latest lyric", seen.last().first)
    }

    @Test
    fun updateProgressProviderRefreshesLabelWithoutChangingReleaseDetails() {
        val actions = testActions()
        val progress = mutableFloatStateOf(0.125f)
        val update = UpdateUiState(
            currentVersionName = "1.0", currentVersionCode = 1,
            phase = UpdatePhase.Downloading,
            info = UpdateInfo(2, "1.1", "v1.1", "2026-10-25T00:00:00Z", 1_048_576L, "https://example.test/app.apk", "test", listOf("测试更新说明")),
        )
        compose.setContent {
            TestTheme { AboutScreen(MusicUiState(), update, actions, {}, {}, {}, progress = { progress.floatValue }) }
        }
        compose.onNodeWithText("正在下载 12%", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { progress.floatValue = 0.57f }
        compose.onNodeWithText("正在下载 57%", substring = true).assertIsDisplayed()
        compose.onNodeWithText("测试更新说明", substring = true).assertExists()
        compose.onNodeWithText("发现新版本 1.1（构建 2）").assertExists()
    }

    @Test
    fun linkParsingBusyStateIsIndependentOfSongSearch() {
        val app = mutableStateOf(MusicUiState(loading = true))
        val search = mutableStateOf(SearchUiState(query = "https://music.163.com/playlist?id=123"))
        val actions = testActions()
        compose.setContent {
            TestTheme { SearchScreen(app.value, search.value, emptyList(), emptyList(), actions, {}) }
        }
        compose.onNodeWithText("解析网易云音乐 链接").assertExists()
        compose.onNodeWithText("解析", substring = false).assertDoesNotExist()
        compose.runOnIdle {
            app.value = app.value.copy(loading = false)
            search.value = search.value.copy(loading = true)
        }
        compose.onNodeWithText("解析", substring = false).assertIsEnabled()
    }

    @Test
    fun searchAlternativesKeepExpansionAfterRefreshAndRestore() {
        val actions = testActions()
        fun groups(name: String, provider: String) = listOf(
            track(0),
            track(1).copy(alternatives = listOf(TrackSource("test", provider, Song(id = "alt", name = name, source = "test")))),
        )
        val search = mutableStateOf(SearchUiState(submittedQuery = "query", aggregateStatus = SearchStatus.Complete, aggregateGroups = groups("旧版本", "旧平台")))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            TestTheme { SearchResultsTestHost(MusicUiState(), search.value, actions, rowWidth = 360.dp) }
        }
        compose.onNodeWithText("其他版本 · 1").performScrollTo().performClick()
        compose.onNodeWithText("旧版本").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { search.value = search.value.copy(aggregateGroups = groups("新版本", "新平台")) }
        compose.onNodeWithText("新版本").assertIsDisplayed()
        compose.onNodeWithText("旧版本").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("新版本").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("其他版本 · 1").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"),
        )
    }

    @Test
    fun tvHeroRefreshKeepsFocusedKeyAndShowsLatestData() {
        val actions = testActions()
        val old = TopList(id = "chart", name = "旧榜单", updateFrequency = "每日", trackCount = 100)
        val state = mutableStateOf(ChartsUiState(providerId = "qq", providerName = "QQ音乐", charts = listOf(old)))
        lateinit var inputMode: androidx.compose.ui.input.InputModeManager
        compose.setContent {
            TestTheme(kind = DeviceKind.Tv) {
                inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
                TvChartsTestHost(state.value, actions)
            }
        }
        compose.runOnIdle { inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        val card = compose.onNode(hasText("旧榜单") and SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
        card.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        card.assertIsFocused()
        compose.runOnIdle {
            state.value = state.value.copy(charts = listOf(old.copy(name = "更新榜单", updateFrequency = "每周", trackCount = 180, description = "刷新后的说明")))
        }
        compose.onNodeWithContentDescription("播放全部：更新榜单").assertExists()
        compose.onNodeWithText("QQ音乐 · 每周 · 180 首").assertExists()
        compose.onNodeWithText("刷新后的说明").assertExists()
        compose.onNode(hasText("更新榜单") and SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus)).assertIsFocused()
        compose.onAllNodesWithText("旧榜单").assertCountEquals(0)
    }

    private fun testActions(): MusicActions {
        lateinit var actions: MusicActions
        compose.runOnUiThread { actions = MusicActions(ViewModelProvider(compose.activity)[MusicViewModel::class.java]) }
        return actions
    }

    @Test
    fun qrCodeIsDrawnAfterBackgroundEncoding() {
        compose.setContent {
            TestTheme { QrCodeImage("https://github.com/mytv-android/myMusic", Modifier.size(240.dp), "test QR") }
        }
        val image = compose.onNodeWithContentDescription("test QR")
        compose.waitUntil(10_000) {
            val pixels = image.captureToImage().toPixelMap()
            var dark = false
            for (y in 0 until pixels.height step 4) {
                for (x in 0 until pixels.width step 4) {
                    val color = pixels[x, y]
                    if (color.red < 0.1f && color.green < 0.1f && color.blue < 0.1f) dark = true
                }
            }
            dark
        }
        image.assertIsDisplayed()
    }

    private fun track(index: Int) = CanonicalTrack(
        id = "track-$index", title = "candidate-$index", artists = listOf("artist"), album = "album",
        durationSec = 120, version = VersionKind.Studio, cover = "", sources = emptyList(),
    )
}

@OptIn(ExperimentalTestApi::class)
@Composable
private fun TestTheme(kind: DeviceKind = DeviceKind.Phone, content: @Composable () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val width = if (kind == DeviceKind.Phone) 400 else 1280
    DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 800.dp))) {
        CompositionLocalProvider(
            LocalAppWindow provides AppWindow(kind, width, 800),
            LocalAppSnackbar provides snackbar,
            LocalAppearance provides Appearance(reduceMotion = true),
        ) {
            MaterialTheme { Box(Modifier.fillMaxSize()) { content() } }
        }
    }
}
