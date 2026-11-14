package com.mytv.mymusic

import androidx.compose.ui.input.key.Key
import com.mytv.mymusic.ui.player.TvSeekStepMs
import com.mytv.mymusic.ui.player.TvTransport
import com.mytv.mymusic.ui.player.seekStepTarget
import com.mytv.mymusic.ui.player.tvTransportOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 电视全屏歌词页（环境模式）用遥控器方向键直接控制播放：上下切歌、左右快退快进。
 * 控制条亮着时不接管，方向键照旧在按钮间走，所以这里只测按一步该出什么动作、快退快进落到哪里。
 */
class TvTransportTest {
    @Test
    fun upDownSwitchSongs() {
        assertEquals(TvTransport.Previous, tvTransportOf(Key.DirectionUp, 0))
        assertEquals(TvTransport.Next, tvTransportOf(Key.DirectionDown, 0))
    }

    @Test
    fun leftRightSeek() {
        assertEquals(TvTransport.SeekBack, tvTransportOf(Key.DirectionLeft, 0))
        assertEquals(TvTransport.SeekForward, tvTransportOf(Key.DirectionRight, 0))
    }

    @Test
    fun holdingDoesNotRunThroughTheQueue() {
        // 切歌只在第一下生效，按住不会连着跳过一整列；快退快进接自动重复
        assertNull(tvTransportOf(Key.DirectionUp, 1))
        assertNull(tvTransportOf(Key.DirectionDown, 3))
        assertEquals(TvTransport.SeekBack, tvTransportOf(Key.DirectionLeft, 1))
        assertEquals(TvTransport.SeekForward, tvTransportOf(Key.DirectionRight, 5))
    }

    @Test
    fun otherKeysAreLeftAlone() {
        assertNull(tvTransportOf(Key.Enter, 0))
        assertNull(tvTransportOf(Key.NumPadEnter, 0))
        assertNull(tvTransportOf(Key.Back, 0))
        assertNull(tvTransportOf(Key.MediaPlayPause, 0))
    }

    @Test
    fun seekStepsByTenSeconds() {
        assertEquals(TvSeekStepMs, 10_000L)
        assertEquals(70_000L, seekStepTarget(60_000L, TvSeekStepMs, 200_000L))
        assertEquals(50_000L, seekStepTarget(60_000L, -TvSeekStepMs, 200_000L))
    }

    @Test
    fun seekIsClampedToTheTrack() {
        // 快到开头再按左：停在 0，不绕到上一首
        assertEquals(0L, seekStepTarget(4_000L, -TvSeekStepMs, 200_000L))
        // 快到结尾再按右：停在总时长
        assertEquals(200_000L, seekStepTarget(196_000L, TvSeekStepMs, 200_000L))
    }

    @Test
    fun unknownDurationOnlyClampsTheLowerBound() {
        assertEquals(10_000L, seekStepTarget(0L, TvSeekStepMs, 0L))
        assertEquals(0L, seekStepTarget(2_000L, -TvSeekStepMs, 0L))
    }
}
