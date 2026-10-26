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

        analyzer.setActive(false)
        assertEquals(RhythmFrame(), analyzer.frames.value)
        analyzer.audioBufferSink.handleBuffer(pcm16(samplesPerFrame, sampleRate, tone = true))
        delay(80)
        assertEquals(RhythmFrame(), analyzer.frames.value)
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
