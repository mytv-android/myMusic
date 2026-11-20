package com.mytv.mymusic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.PosterPrefs
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.components.AppWindow
import com.mytv.mymusic.ui.components.DeviceKind
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.poster.PosterWall
import com.mytv.mymusic.ui.poster.PosterWallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

/**
 * issue #24 的两个现象在设备上验：
 * - 按住拖很久再松手，惯性不能把按住那段时间当成一帧一下子走掉（相机跟着手指走，松手后第一帧只走 1/60 秒）；
 * - 441 首的墙上把墙面拖远、切到镜头上看不见的一首，墙要跟过去把它放大（原来只在中心 ±2 块里找，找不到就不跟）。
 *
 * 跑法：ANDROID_SERIAL=<设备> ./gradlew :app:connectedDebugAndroidTest
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.mytv.mymusic.PosterWallFlingTest
 */
class PosterWallFlingTest {
    @get:Rule
    val compose = createComposeRule()

    private val songs = List(441) { Song(id = "s$it", name = "第 $it 首", artist = "歌手", source = "test") }
    private var current by mutableIntStateOf(4)
    private lateinit var state: PosterWallState

    @Test
    fun aHeldDragDoesNotJumpOnTheFirstFrameAfterRelease() {
        mount()
        // 等入场波走完、帧循环真的睡下（睡着时没有任何一帧在推进镜头，lastTick 停在睡下的那一刻）
        compose.waitUntil(10_000) { !state.needsFrames }
        // 手指按下、跟手拖一小段，然后按着不动 0.8 秒；松手带着 1200dp/s 甩出去
        compose.runOnUiThread {
            state.beginDrag()
            state.dragBy(30f, 10f)
        }
        Thread.sleep(800)
        // 基线取松手那一刻的位置：跳跃那一帧可能已经在 signal 之后走上去了（那时还没开始采样）
        var held = 0f
        compose.runOnUiThread {
            held = state.camera.x
            state.endDrag(-1200f, 0f)
        }
        val jump = sampledMaxStep(from = held)
        // 1200dp/s 一帧 (1 − e^(−4.2/60)) / 4.2 ≈ 19dp；旧代码把睡过去的 0.8 秒当成一帧，实测 192dp
        assertTrue("松手后单帧最大位移 $jump dp", jump < 40f)
    }

    /** 单帧位移（dp）：从松手位置起逐帧读镜头位置，取相邻两帧差的最大值。 */
    private fun sampledMaxStep(from: Float): Float {
        var maxStep = 0f
        var last = from
        for (k in 0 until 120) {
            compose.mainClock.advanceTimeByFrame()
            val now = state.camera.x
            maxStep = maxOf(maxStep, abs(now - last))
            last = now
            if (!state.camera.isAnimating) break
        }
        return maxStep
    }

    @Test
    fun aSongSwitchedAfterPullingTheWallFarAwayIsFollowed() {
        mount()
        val wall = compose.onNodeWithTag("wall")
        // 往右下拖十来下，墙面离家很远；镜头反向跟手指走
        repeat(12) { flingOnce(wall) }
        compose.waitForIdle()
        assertTrue("镜头没拖远：${state.camX}", abs(state.camX) > 2_000f)

        // 挑一首在“中心 ±2 块”（旧代码的搜索窗口）里一份都没有的
        val scene = state.scene!!
        val cx = state.camX + 200f
        val cy = state.camY + 400f
        val bx = floor(cx / scene.metrics.blockWidth).toInt()
        val by = floor(cy / scene.metrics.blockHeight).toInt()
        val near = (by - 2..by + 2).flatMap { y ->
            (bx - 2..bx + 2).flatMap { x -> (0..11).map { scene.songAt(x, y, it) } }
        }.toSet()
        val target = (0 until 441).first { it !in near }

        current = target
        compose.waitForIdle()
        assertEquals(target, state.grownIndex)
        assertTrue(state.currentGrown)
        // 大卡整张落在可用区里（跟着切歌走到了屏幕内）
        val r = state.scene!!.rectAt(state.scene!!.grown!!)
        assertTrue("大卡 x 出了屏：${r.x - state.camX}..${r.right - state.camX}", r.x - state.camX >= 0f && r.right - state.camX <= 400f)
        assertTrue("大卡 y 出了屏：${r.y - state.camY}..${r.bottom - state.camY}", r.y - state.camY >= 0f && r.bottom - state.camY <= 800f)
    }

    private fun mount() {
        state = PosterWallState()
        compose.setContent {
            CompositionLocalProvider(LocalAppWindow provides AppWindow(DeviceKind.Phone, 400, 800)) {
                MaterialTheme {
                    Box(Modifier.size(400.dp, 800.dp)) {
                        PosterWall(
                            state = state,
                            songs = songs,
                            currentIndex = current,
                            feed = null,
                            prefs = PosterPrefs(),
                            insetTop = 0.dp,
                            insetBottom = 0.dp,
                            onLongPress = { _, _ -> },
                            grownContent = { _, _, _ -> },
                            modifier = Modifier.testTag("wall"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** 往右下甩一把：手指每帧走 32dp，松开后墙带着惯性往左上走。 */
    private fun flingOnce(wall: SemanticsNodeInteraction) {
        wall.performTouchInput {
            down(center)
            for (k in 1..10) {
                updatePointerBy(Offset(32f, 18f))
                move()
            }
        }
        wall.performTouchInput { up() }
        compose.waitForIdle()
    }
}
