package com.mytv.mymusic.ui.poster

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.LyricsBackgroundMode
import com.mytv.mymusic.data.RhythmIntensity
import com.mytv.mymusic.ui.player.BackdropPulse
import com.mytv.mymusic.ui.player.FlowFieldMotion
import com.mytv.mymusic.ui.player.RhythmFeed
import com.mytv.mymusic.ui.player.RhythmFeedReader
import com.mytv.mymusic.ui.player.TvFrameGate
import com.mytv.mymusic.ui.player.rememberBackdropPulse
import com.mytv.mymusic.ui.player.rememberSystemAnimationsOff
import com.mytv.mymusic.ui.theme.LocalAppearance

/**
 * 海报墙的跟拍：不画流光，只按播放页同一套（[RhythmFeedReader] 按听到的位置取帧 + [FlowFieldMotion] 的拍子时钟）
 * 推出一份 [BackdropPulse]，给正在播放的大卡弹跳（[BackdropPulse.coverScale]）。
 * 跟播放页一样只在歌词背景选“律动”时跟拍，强度和跟拍校准也沿用播放页的设置；减少动画时不跟。
 * [visible] 为 false（播放页盖在墙上面、墙已经退出）时停下帧循环、不占分析器。
 */
@Composable
internal fun rememberPosterPulse(
    playing: Boolean,
    mode: LyricsBackgroundMode,
    intensity: RhythmIntensity,
    rhythmOffsetMs: Int,
    visible: Boolean,
): BackdropPulse {
    val pulse = rememberBackdropPulse()
    val reduceMotion = LocalAppearance.current.reduceMotion || rememberSystemAnimationsOff()
    val context = LocalContext.current
    val analyzer = remember(context) { (context.applicationContext as? MyMusicApp)?.rhythmAnalyzer }
    val reactive = visible && mode == LyricsBackgroundMode.Reactive && !reduceMotion && analyzer != null
    val motion = remember { FlowFieldMotion(moving = false) }
    SideEffect {
        pulse.reactive = reactive
        pulse.intensity = intensity
        motion.playing = playing && reactive
        motion.reactive = reactive
        motion.intensity = intensity
        if (!reactive) {
            motion.settle()
            pulse.clear()
        }
    }
    if (!reactive || analyzer == null) return pulse
    RhythmFeed(analyzer, playing)
    val feed = remember(analyzer) { RhythmFeedReader(analyzer) }
    val offsetUs = rhythmOffsetMs * 1_000L
    LaunchedEffect(motion, feed, playing, offsetUs) {
        // 限 30 帧，和大卡上的花瓣旋转、跳动的条落在同一批垂直同步上
        while (true) {
            val settled = withFrameNanos { now ->
                if (!wallFrameDue(now)) return@withFrameNanos false
                feed.feedUntil(now, offsetUs, motion)
                motion.advanceTo(now)
                pulse.publish(motion)
                !playing && motion.isSettled
            }
            if (settled) break
        }
        // 暂停后包络落完就停，播放时按 key 重新启动
        motion.stopClock()
    }
    return pulse
}

/**
 * 海报墙上一直在动的小东西（大卡的花瓣旋转、跳动的条、跟拍弹跳）在所有设备上都限 30 帧：和电视用同一个节拍，
 * 几个循环落在同一批垂直同步上，墙不跟着屏幕刷新率一直满帧重画。运动都按真实帧间隔积分，速度不变。
 */
internal fun wallFrameDue(frameNanos: Long): Boolean = TvFrameGate.due(frameNanos)
