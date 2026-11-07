package com.mytv.mymusic.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * 包住 DefaultAudioSink，把律动分析需要的时间信息交给 [RhythmAnalyzer]，声音本身一点不动：
 * - handleBuffer：报上这个缓冲的 pts，分析器据此给分析帧定时刻；
 * - getCurrentPositionUs：记下此刻听到的位置（已经扣掉 AudioTrack 缓冲和设备延迟），界面按它取帧；
 * - play / pause / flush / setPlaybackParameters：让推算冻结、继续或换倍速；
 * - setOutputStreamOffsetUs：输出换到了播放列表的下一项（无缝衔接时不会清空处理链），分析器在下一个缓冲处记分界。
 * 全部在音频渲染线程上调用。
 */
@UnstableApi
internal class RhythmAudioSink(
    sink: AudioSink,
    private val analyzer: RhythmAnalyzer,
    private val source: Int,
) : ForwardingAudioSink(sink) {
    private val clock = analyzer.clock

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (analyzer.isActiveSource(source)) analyzer.onSinkInput(presentationTimeUs, source)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        // 预载的那一路也在报位置；不是主播放器就只出声，不碰界面的进度时钟
        if (analyzer.isActiveSource(source)) clock.onPosition(position, System.nanoTime())
        return position
    }

    override fun play() {
        super.play()
        if (analyzer.isActiveSource(source)) clock.setRunning(true, System.nanoTime())
    }

    override fun pause() {
        super.pause()
        if (analyzer.isActiveSource(source)) clock.setRunning(false, System.nanoTime())
    }

    override fun flush() {
        super.flush()
        if (analyzer.isActiveSource(source)) clock.clear()
    }

    override fun reset() {
        super.reset()
        if (analyzer.isActiveSource(source)) clock.clear()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        super.setPlaybackParameters(playbackParameters)
        if (analyzer.isActiveSource(source)) clock.setSpeed(playbackParameters.speed, System.nanoTime())
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
        if (analyzer.isActiveSource(source)) analyzer.onOutputStreamOffset(outputStreamOffsetUs, source)
    }
}
