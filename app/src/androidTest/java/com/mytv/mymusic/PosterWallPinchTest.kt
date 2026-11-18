package com.mytv.mymusic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.PosterPrefs
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.ZoomScope
import com.mytv.mymusic.ui.components.AppWindow
import com.mytv.mymusic.ui.components.DeviceKind
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.poster.PosterWall
import com.mytv.mymusic.ui.poster.PosterWallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * 海报墙双指捏合（adb input 做不出多指，只能在这里验）：一面 48 首的手机墙，两指往里捏到一半，
 * 松手前墙只改图层缩放、不重排；松手吸附到 0.5 并写回设置，墙原地换格距、不重放入场。
 *
 * 跑法：ANDROID_SERIAL=<设备> ./gradlew :app:connectedDebugAndroidTest
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.mytv.mymusic.PosterWallPinchTest
 */
class PosterWallPinchTest {
    @get:Rule
    val compose = createComposeRule()

    private val songs = List(48) { Song(id = "s$it", name = "第 $it 首", artist = "歌手", source = "test") }

    @Test
    fun pinchingInHalfwaySnapsToFarAndKeepsTheWall() {
        val state = PosterWallState()
        var prefs by mutableStateOf(PosterPrefs())
        var committed: Float? = null
        compose.setContent {
            CompositionLocalProvider(LocalAppWindow provides AppWindow(DeviceKind.Phone, 400, 800)) {
                MaterialTheme {
                    Box(Modifier.size(400.dp, 800.dp)) {
                        PosterWall(
                            state = state,
                            songs = songs,
                            currentIndex = 5,
                            feed = null,
                            prefs = prefs,
                            insetTop = 0.dp,
                            insetBottom = 0.dp,
                            onLongPress = { _, _ -> },
                            grownContent = { _, _, _ -> },
                            onZoomChange = { z ->
                                committed = z
                                prefs = prefs.copy(zoom = prefs.zoom.with(ZoomScope.Phone, z))
                            },
                            modifier = Modifier.testTag("wall"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val generation = state.generation
        assertEquals(1f, state.metrics.zoom, 0f)

        val wall = compose.onNodeWithTag("wall")
        // 两指水平各在中心两侧 160px，一点点往里捏到 80px（间距减半）
        wall.performTouchInput {
            val c = center
            down(0, c - Offset(160f, 0f))
            down(1, c + Offset(160f, 0f))
            for (k in 1..8) {
                val half = 160f - 10f * k
                updatePointerTo(0, c - Offset(half, 0f))
                updatePointerTo(1, c + Offset(half, 0f))
                move()
            }
        }
        compose.waitForIdle()
        // 松手前：只是图层在缩，格距和场景都没变
        assertEquals(1f, state.metrics.zoom, 0f)
        assertEquals(0.5f, state.layerScale, 0.02f)
        assertEquals(generation, state.generation)
        assertNull(committed)

        wall.performTouchInput {
            up(0)
            up(1)
        }
        compose.waitForIdle()
        assertEquals(0.5f, committed!!, 0f)
        assertEquals(0.5f, prefs.zoom.phone, 0f)
        assertEquals(0.5f, state.metrics.zoom, 0f)
        assertEquals(28f, state.metrics.pitch, 0f)
        // 原地换格距：不重排、不重放入场；过渡缩放走完回到 1，设置回来后不再用临时倍数
        assertEquals(generation, state.generation)
        assertEquals(1f, state.layerScale, 0f)
        assertNull(state.zoomOverride)
    }
}
