package com.mytv.mymusic

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.mytv.mymusic.playback.VolumeGainAudioProcessor
import com.mytv.mymusic.playback.VolumeNormalizationState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VolumeGainAudioProcessorTest {
    @After
    fun resetState() {
        VolumeNormalizationState.update(false, 0f, null)
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

    private fun pcm16(value: Short, count: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) { buffer.putShort(value) }
        buffer.flip()
        return buffer
    }
}
