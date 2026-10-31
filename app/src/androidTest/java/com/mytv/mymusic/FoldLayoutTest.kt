package com.mytv.mymusic

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.testing.layout.FoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import com.mytv.mymusic.ui.adaptive.FoldColumn
import com.mytv.mymusic.ui.adaptive.FoldMargin
import com.mytv.mymusic.ui.adaptive.FoldRow
import com.mytv.mymusic.ui.components.AppWindow
import com.mytv.mymusic.ui.components.DeviceKind
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.toFold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

/**
 * 照 codelab：WindowLayoutInfoPublisherRule 往 Activity 里塞一条假的 FoldingFeature，
 * 看折痕换算（toFold、posture）和 FoldRow / FoldColumn 的切分。宿主和 MainActivity 一样收集 WindowLayoutInfo。
 */
class FoldLayoutTest {
    private val publisher = WindowLayoutInfoPublisherRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(publisher).around(compose)

    @Composable
    private fun FoldHost(content: @Composable () -> Unit) {
        val activity = compose.activity
        val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
        val info by flow.collectAsState(null)
        val config = LocalConfiguration.current
        val window = AppWindow(DeviceKind.Tablet, config.screenWidthDp, config.screenHeightDp, info?.toFold())
        CompositionLocalProvider(LocalAppWindow provides window) {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    content()
                    Text(window.posture.name, Modifier.testTag("posture"))
                }
            }
        }
    }

    /** 塞一条折痕，返回它在窗口里的位置（dp）。 */
    private fun publish(state: FoldingFeature.State, orientation: FoldingFeature.Orientation, size: Int = 0): FoldDp {
        var fold: FoldDp? = null
        compose.activityRule.scenario.onActivity { activity ->
            val feature = FoldingFeature(activity = activity, size = size, state = state, orientation = orientation)
            publisher.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(feature)))
            val density = activity.resources.displayMetrics.density
            val b = feature.bounds
            fold = FoldDp(
                start = (if (orientation == FoldingFeature.Orientation.VERTICAL) b.left else b.top) / density,
                // 宽度为 0 的折痕按 1px 算
                end = (if (orientation == FoldingFeature.Orientation.VERTICAL) maxOf(b.right, b.left + 1) else maxOf(b.bottom, b.top + 1)) / density,
            )
        }
        compose.waitForIdle()
        // FoldRow 第一次摆放后才知道自己在窗口里的位置，再等一帧
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        return fold!!
    }

    private data class FoldDp(val start: Float, val end: Float)

    /** 节点在窗口里的矩形（dp）：折痕 bounds 是窗口坐标，测试 Activity 的内容区不一定从窗口左上角开始。 */
    private fun window(tag: String): Rect {
        val density = compose.activity.resources.displayMetrics.density
        val r = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        return Rect(r.left / density, r.top / density, r.right / density, r.bottom / density)
    }

    private fun Dp.f() = value
    private fun Float.f() = this

    @Composable
    private fun Row() {
        FoldRow(
            startWidth = 320.dp,
            modifier = Modifier.fillMaxSize(),
            spacing = 24.dp,
            minPane = 120.dp,
            start = { Box(Modifier.fillMaxSize().testTag("start")) },
            end = { Box(Modifier.fillMaxSize().testTag("end")) },
        )
    }

    @Composable
    private fun Column() {
        FoldColumn(
            modifier = Modifier.fillMaxSize(),
            top = { Box(Modifier.fillMaxSize().testTag("top")) },
            bottom = { Box(Modifier.fillMaxSize().testTag("bottom")) },
        )
    }

    @Test
    fun bookPostureSplitsRowAtTheCrease() {
        compose.setContent { FoldHost { Row() } }
        val fold = publish(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.VERTICAL)
        compose.onNodeWithTag("posture").assertTextEquals("Book")
        val start = window("start")
        val end = window("end")
        // 左栏收到折痕左缘再让 16dp，右栏从折痕右缘再让 16dp 开始（取整误差 1dp 以内）
        assertEquals(fold.start - FoldMargin.f(), start.right.f(), 1f)
        assertEquals(fold.end + FoldMargin.f(), end.left.f(), 1f)
    }

    @Test
    fun flatFoldKeepsTheNormalRow() {
        compose.setContent { FoldHost { Row() } }
        publish(FoldingFeature.State.FLAT, FoldingFeature.Orientation.VERTICAL)
        compose.onNodeWithTag("posture").assertTextEquals("Flat")
        val start = window("start")
        val end = window("end")
        assertEquals(320f, (start.right - start.left).f(), 1f)
        assertEquals(344f, end.left.f(), 1f)
    }

    @Test
    fun tabletopKeepsTheRow() {
        compose.setContent { FoldHost { Row() } }
        publish(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.HORIZONTAL)
        compose.onNodeWithTag("posture").assertTextEquals("Tabletop")
        // 半开的横折痕不挡像素：列表-详情照常左右两栏
        val start = window("start")
        assertEquals(320f, (start.right - start.left).f(), 1f)
    }

    @Test
    fun tabletopSplitsTheColumnAtTheCrease() {
        compose.setContent { FoldHost { Column() } }
        val fold = publish(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.HORIZONTAL)
        val top = window("top")
        val bottom = window("bottom")
        assertEquals(fold.start - FoldMargin.f(), top.bottom.f(), 1f)
        assertEquals(fold.end + FoldMargin.f(), bottom.top.f(), 1f)
    }

    @Test
    fun occludingHorizontalHingeStacksListOverDetail() {
        compose.setContent { FoldHost { Row() } }
        // 有宽度的铰链：挡像素，展平也分页，不另加安全距离
        val fold = publish(FoldingFeature.State.FLAT, FoldingFeature.Orientation.HORIZONTAL, size = 60)
        compose.onNodeWithTag("posture").assertTextEquals("Tabletop")
        val start = window("start")
        val end = window("end")
        assertEquals(fold.start, start.bottom.f(), 1f)
        assertEquals(fold.end, end.top.f(), 1f)
        assertTrue("上下两栏都撑满宽度", (start.right - start.left) == (end.right - end.left))
    }
}
