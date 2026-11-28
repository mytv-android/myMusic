package com.mytv.mymusic

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.mytv.mymusic.playback.PlayerVolumeState
import com.mytv.mymusic.playback.VolumeGainAudioProcessor
import com.mytv.mymusic.playback.VolumeNormalizationState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class VolumeGainAudioProcessorTest {
    @After
    fun resetState() {
        VolumeNormalizationState.update(false, 0f, null)
        PlayerVolumeState.update(PlayerVolumeState.FULL)
    }

    @Test
    fun `applies gain to 16 bit pcm`() {
        VolumeNormalizationState.update(true, 6f, "track-a")
        val processor = VolumeGainAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        processor.queueInput(pcm16(1000, 16))
        val output = processor.getOutput().order(ByteOrder.LITTLE_ENDIAN)
        while (output.hasRemaining()) {
            assertTrue("sample should be boosted", output.short.toInt() > 1500)
        }
        processor.reset()
    }

    @Test
    fun `applies gain to float pcm and clamps`() {
        VolumeNormalizationState.update(true, 6f, "track-b")
        val processor = VolumeGainAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT))
        val input = ByteBuffer.allocateDirect(4 * 8).order(ByteOrder.nativeOrder())
        repeat(8) { input.putFloat(0.9f) }
        input.flip()
        processor.queueInput(input)
        val output = processor.getOutput().order(ByteOrder.nativeOrder())
        while (output.hasRemaining()) {
            assertEquals(1f, output.float, 0.0001f)
        }
        processor.reset()
    }

    @Test
    fun `disabled state passes pcm through unchanged`() {
        VolumeNormalizationState.update(false, 0f, "track-c")
        val processor = VolumeGainAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        processor.queueInput(pcm16(1000, 8))
        val output = processor.getOutput().order(ByteOrder.LITTLE_ENDIAN)
        while (output.hasRemaining()) {
            assertEquals(1000, output.short.toInt())
        }
        processor.reset()
    }

    @Test
    fun `fallback boosts quiet audio and limits loud peaks`() {
        VolumeNormalizationState.update(true, 0f, "fallback", fallback = true)
        val processor = VolumeGainAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(8000, 1, C.ENCODING_PCM_16BIT))
        val quiet = drain(processor, pcm16(1638, 8000))
        assertTrue("quiet audio should be boosted", quiet.last() > 2500)
        val loud = drain(processor, pcm16(29491, 8000))
        assertTrue("peaks must stay below the ceiling", loud.max() <= 32113)
        processor.reset()
    }

    /** 播放器音量只缩放样本，不碰响度归一化：归一化关着时 50% 就是原来的半个。 */
    @Test
    fun `player volume scales pcm`() {
        PlayerVolumeState.update(50)
        val processor = VolumeGainAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        processor.queueInput(pcm16(1000, 8))
        val output = processor.getOutput().order(ByteOrder.LITTLE_ENDIAN)
        while (output.hasRemaining()) {
            assertEquals("half volume should halve the sample", 500, output.short.toInt())
        }
        processor.reset()
    }

    @Test
    fun `zero player volume mutes`() {
        PlayerVolumeState.update(0)
        val processor = VolumeGainAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        val output = drain(processor, pcm16(12000, 64))
        assertTrue("everything should be silent", output.all { it.toInt() == 0 })
        processor.reset()
    }

    /** 没有响度元数据的实时 AGC 那条路也要跟着音量缩，不然归一化开着时音量滑不动。 */
    @Test
    fun `fallback path scales with player volume`() {
        VolumeNormalizationState.update(true, 0f, "fallback-volume", fallback = true)
        PlayerVolumeState.update(PlayerVolumeState.FULL)
        val full = maxAmplitude(pcm16(20000, 8000))
        PlayerVolumeState.update(25)
        val quarter = maxAmplitude(pcm16(20000, 8000))
        assertTrue("$quarter should be far below $full", quarter * 3 < full)
    }

    private fun maxAmplitude(input: ByteBuffer): Int {
        val processor = VolumeGainAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(8000, 1, C.ENCODING_PCM_16BIT))
        val samples = drain(processor, input)
        processor.reset()
        return samples.maxOf { abs(it.toInt()) }
    }

    private fun drain(processor: VolumeGainAudioProcessor, input: ByteBuffer): ShortArray {
        processor.queueInput(input)
        val output = processor.getOutput().order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(output.remaining() / 2)
        var index = 0
        while (output.hasRemaining()) samples[index++] = output.short
        return samples
    }

    private fun pcm16(value: Short, count: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) { buffer.putShort(value) }
        buffer.flip()
        return buffer
    }
}
