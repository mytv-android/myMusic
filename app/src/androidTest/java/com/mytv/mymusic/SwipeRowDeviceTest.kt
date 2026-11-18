package com.mytv.mymusic

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.SwipeAction
import com.mytv.mymusic.ui.components.AppWindow
import com.mytv.mymusic.ui.components.DeviceKind
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.RowSwipe
import com.mytv.mymusic.ui.components.SongRow
import com.mytv.mymusic.ui.components.SwipeContent
import com.mytv.mymusic.ui.components.SwipeCoordinator
import com.mytv.mymusic.ui.components.SwipeItem
import com.mytv.mymusic.ui.components.SwipeTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 歌曲行左右滑：真的手势走一遍（拉到头只执行一次、不触发点按；露出按钮能点；鼠标悬停出行尾按钮；
 * 读屏操作挂上；纵向滑交给列表）。模拟器上 adb 做不出鼠标悬停，这一项只能靠这里。
 */
@OptIn(ExperimentalTestApi::class)
class SwipeRowDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var clicks = 0
    private var downloads = 0
    private var playlists = 0
    private var favorites = 0
    private var deletes = 0

    private fun content() = SwipeContent(
        start = listOf(
            SwipeItem(SwipeAction.Download, "下载", "下载「晚风信号」", Icons.Filled.Download, SwipeTone.Secondary) { downloads++ },
            SwipeItem(SwipeAction.AddToPlaylist, "加到歌单", "加到歌单", Icons.Filled.LibraryAdd, SwipeTone.Tertiary) { playlists++ },
        ),
        startFull = SwipeItem(SwipeAction.Favorite, "收藏", "收藏", Icons.Filled.Favorite, SwipeTone.Primary) { favorites++ },
        end = listOf(SwipeItem(SwipeAction.Delete, "删除", "从最近播放删除", Icons.Filled.Delete, SwipeTone.Error) { deletes++ }),
        endFull = null,
    )

    @Composable
    private fun Host(kind: DeviceKind, rows: Int = 1) {
        val swipe = remember { RowSwipe(mutableStateOf(content()), gestures = true, hover = kind != DeviceKind.Phone, coordinator = SwipeCoordinator()) }
        CompositionLocalProvider(LocalAppWindow provides AppWindow(kind, 400, 800)) {
            MaterialTheme {
                Box(Modifier.fillMaxSize().padding(horizontal = 40.dp)) {
                    LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                        items(rows) { i ->
                            SongRow(
                                title = if (i == 0) "晚风信号" else "第 $i 首",
                                subtitle = "示例歌手",
                                cover = "",
                                index = i,
                                count = rows,
                                onClick = { clicks++ },
                                modifier = Modifier.testTag("row$i"),
                                swipe = swipe,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun pullingAllTheWayRunsTheFullActionOnceAndDoesNotPlay() {
        compose.setContent { Host(DeviceKind.Phone) }
        compose.onNodeWithTag("row0").performTouchInput {
            swipe(start = centerLeft.copy(x = 30f.dp.toPx()), end = centerRight.copy(x = width - 4f), durationMillis = 400)
        }
        compose.waitForIdle()
        assertEquals(1, favorites)
        assertEquals(0, clicks)
        assertEquals(0, downloads)
    }

    @Test
    fun aShortSwipeShowsTheButtonsAndTheyRun() {
        compose.setContent { Host(DeviceKind.Phone) }
        compose.onNodeWithTag("row0").performTouchInput {
            swipe(start = centerLeft.copy(x = 30f.dp.toPx()), end = centerLeft.copy(x = 150f.dp.toPx()), durationMillis = 500)
        }
        compose.waitForIdle()
        compose.onNodeWithText("加到歌单").performClick()
        compose.waitForIdle()
        assertEquals(1, playlists)
        assertEquals(0, favorites)
        assertEquals(0, clicks)
    }

    @Test
    fun tappingAnOpenRowOnlyClosesIt() {
        compose.setContent { Host(DeviceKind.Phone) }
        compose.onNodeWithTag("row0").performTouchInput {
            swipe(start = centerRight.copy(x = width - 30f.dp.toPx()), end = centerRight.copy(x = width - 150f.dp.toPx()), durationMillis = 500)
        }
        compose.waitForIdle()
        compose.onNodeWithText("删除").assertExistsCompat()
        compose.onNodeWithText("晚风信号").performClick()
        compose.waitForIdle()
        assertEquals(0, clicks)
        assertEquals(0, compose.onAllNodes(hasText("删除")).fetchSemanticsNodes().size)
    }

    @Test
    fun aVerticalDragScrollsTheListAndLeavesTheRowShut() {
        compose.setContent { Host(DeviceKind.Phone, rows = 40) }
        compose.onNodeWithTag("row1").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasText("下载")).fetchSemanticsNodes().size)
        assertEquals(0, favorites + downloads + deletes)
    }

    @Test
    fun hoveringWithAMouseOnATabletShowsTheButtons() {
        compose.setContent { Host(DeviceKind.Tablet) }
        compose.onNodeWithTag("row0").performMouseInput { moveTo(center) }
        compose.waitForIdle()
        // 用鼠标点：手指一碰屏幕系统就发悬停退出，按钮会先收起
        compose.onNodeWithContentDescription("下载「晚风信号」").performMouseInput { click(center) }
        compose.waitForIdle()
        assertEquals(1, downloads)
        assertEquals(0, clicks)
        // 鼠标按下拖动不会把行拖开
        compose.onNodeWithTag("row0").performMouseInput {
            moveTo(centerLeft.copy(x = 60f.dp.toPx()))
            press()
            moveBy(androidx.compose.ui.geometry.Offset(200f.dp.toPx(), 0f))
            release()
        }
        compose.waitForIdle()
        assertEquals(0, favorites + playlists)
    }

    @Test
    fun screenReaderActionsAreAttached() {
        compose.setContent { Host(DeviceKind.Phone) }
        val node = compose.onNode(hasText("晚风信号") and hasClickAction()).fetchSemanticsNode()
        val labels = node.config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("下载「晚风信号」", "加到歌单", "从最近播放删除", "收藏"), labels)
        node.config[SemanticsActions.CustomActions].first { it.label == "从最近播放删除" }.action()
        assertEquals(1, deletes)
        assertTrue(clicks == 0)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() {
        fetchSemanticsNode()
    }
}
