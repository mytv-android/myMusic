package com.mytv.mymusic

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.mytv.mymusic.playback.RHYTHM_FFT_SIZE
import com.mytv.mymusic.playback.RhythmAnalyzer
import com.mytv.mymusic.playback.RhythmFrame
import com.mytv.mymusic.playback.rhythmHopSamples
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@androidx.annotation.OptIn(UnstableApi::class)
class RhythmAnalyzerTest {
    @Test
    fun hopNeverLeavesAnUnanalysedPcmGap() {
        listOf(8_000, 44_100, 48_000, 96_000, 192_000).forEach { rate ->
            val hop = rhythmHopSamples(rate)
            assertTrue("hop must be positive at $rate Hz", hop > 0)
            assertTrue("hop $hop exceeds FFT window at $rate Hz", hop <= RHYTHM_FFT_SIZE)
        }
        assertEquals(1_470, rhythmHopSamples(44_100))
        assertEquals(1_600, rhythmHopSamples(48_000))
        assertEquals(RHYTHM_FFT_SIZE, rhythmHopSamples(192_000))
    }

    @Test
    fun pcmTapDetectsBeatAndStopsWhenInactive() = runBlocking {
        val analyzer = RhythmAnalyzer()
        val sampleRate = 44_100
        val samplesPerFrame = sampleRate / 30
        analyzer.audioBufferSink.flush(sampleRate, 1, C.ENCODING_PCM_16BIT)
        analyzer.setActive(true)

        repeat(14) {
            analyzer.audioBufferSink.handleBuffer(pcm16(samplesPerFrame, sampleRate, tone = false))
            delay(60)
        }
        assertEquals(0f, analyzer.frames.value.pulse, 0f)

        var detected: RhythmFrame? = null
        for (attempt in 0 until 4) {
            analyzer.audioBufferSink.handleBuffer(pcm16(samplesPerFrame, sampleRate, tone = true))
            detected = withTimeoutOrNull(500) { analyzer.frames.first { it.pulse > 0.9f } }
            if (detected != null) break
            analyzer.audioBufferSink.handleBuffer(pcm16(samplesPerFrame, sampleRate, tone = false))
            delay(60)
        }
        assertNotNull("PCM fed through TeeAudioProcessor's sink must produce an onset", detected)
        assertTrue("a 110 Hz tone onset is also a bass kick", detected!!.kick)
        assertTrue("a published kick advances the count", detected.kickCount >= 1)
        assertTrue(detected.loudness > 0f)

        analyzer.setActive(false)
        val stopped = analyzer.frames.value
        // 停止只清掉信号，鼓点序号保持不变：界面不会把重置当成一次新鼓点
        assertEquals(RhythmFrame(kickCount = stopped.kickCount), stopped)
        assertTrue(stopped.kickCount >= detected.kickCount)
        analyzer.audioBufferSink.handleBuffer(pcm16(samplesPerFrame, sampleRate, tone = true))
        delay(80)
        assertEquals(stopped, analyzer.frames.value)
    }

    @Test
    fun kickCountOnlyMovesForwardAcrossResetsAndGenerations() = runBlocking {
        val analyzer = RhythmAnalyzer()
        val sampleRate = 44_100
        analyzer.audioBufferSink.flush(sampleRate, 1, C.ENCODING_PCM_16BIT)
        analyzer.setActive(true)
        assertEquals(0, analyzer.frames.value.kickCount)

        val first = kickAfterSilence(analyzer, sampleRate, after = 0)
        assertTrue(first.kick)

        // 切歌：新的一代重新学增益，序号接着上一首往后数
        analyzer.reset()
        val reset = analyzer.frames.value
        assertEquals(RhythmFrame(kickCount = reset.kickCount), reset)
        assertTrue(reset.kickCount >= first.kickCount)

        val second = kickAfterSilence(analyzer, sampleRate, after = reset.kickCount)
        assertTrue(second.kickCount > reset.kickCount)

        // 格式变化（flush）同样只换代，不清零
        analyzer.audioBufferSink.flush(sampleRate, 1, C.ENCODING_PCM_16BIT)
        assertTrue(analyzer.frames.value.kickCount >= second.kickCount)
        analyzer.setActive(false)
    }

    /** 先喂一段静音让起拍检测攒够历史，再喂 110 Hz 的音，等到序号超过 [after]。 */
    private suspend fun kickAfterSilence(analyzer: RhythmAnalyzer, sampleRate: Int, after: Int): RhythmFrame {
        val samplesPerFrame = sampleRate / 30
        repeat(14) {
            analyzer.audioBufferSink.handleBuffer(pcm16(samplesPerFrame, sampleRate, tone = false))
            delay(60)
        }
        assertEquals("silence never advances the count", after, analyzer.frames.value.kickCount)
        var kicked: RhythmFrame? = null
        for (attempt in 0 until 4) {
            analyzer.audioBufferSink.handleBuffer(pcm16(samplesPerFrame, sampleRate, tone = true))
            kicked = withTimeoutOrNull(500) { analyzer.frames.first { it.kickCount > after } }
            if (kicked != null) break
            analyzer.audioBufferSink.handleBuffer(pcm16(samplesPerFrame, sampleRate, tone = false))
            delay(60)
        }
        assertNotNull("a bass onset must advance the kick count", kicked)
        return kicked!!
    }

    private fun pcm16(frameCount: Int, sampleRate: Int, tone: Boolean): ByteBuffer {
        val buffer = ByteBuffer.allocate(frameCount * 2).order(ByteOrder.nativeOrder())
        repeat(frameCount) { index ->
            val sample = if (tone) sin(2 * PI * 110 * index / sampleRate) * 0.8 else 0.0
            buffer.putShort((sample * 32767).toInt().toShort())
        }
        buffer.flip()
        return buffer
    }
}
