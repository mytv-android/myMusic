package com.mytv.mymusic

import com.mytv.mymusic.recognize.AudioResampler
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioResamplerTest {
    @Test
    fun `resampling one second to 8k yields 8000 samples`() {
        val output = AudioResampler.resample(ShortArray(44100), 44100, 8000)
        assertEquals(8000, output.size)
    }

    @Test
    fun `constant signal keeps its amplitude`() {
        val output = AudioResampler.resample(ShortArray(44100) { 16384 }, 44100, 8000)
        output.forEach { assertEquals(0.5f, it, 0.001f) }
    }

    @Test
    fun `pcm16 output is little endian`() {
        val bytes = AudioResampler.toPcm16(floatArrayOf(0f, 0.5f, -1f, 1f))
        assertEquals(8, bytes.size)
        assertEquals(0x00, bytes[0].toInt() and 0xff)
        assertEquals(0x00, bytes[1].toInt() and 0xff)
        assertEquals(0x00, bytes[2].toInt() and 0xff)
        assertEquals(0x40, bytes[3].toInt() and 0xff)
        assertEquals(0x00, bytes[4].toInt() and 0xff)
        assertEquals(0x80, bytes[5].toInt() and 0xff)
        assertEquals(0xff, bytes[6].toInt() and 0xff)
        assertEquals(0x7f, bytes[7].toInt() and 0xff)
    }

    @Test
    fun `clamps out of range floats`() {
        val bytes = AudioResampler.toPcm16(floatArrayOf(-4f, 4f))
        assertEquals(0x00, bytes[0].toInt() and 0xff)
        assertEquals(0x80, bytes[1].toInt() and 0xff)
        assertEquals(0xff, bytes[2].toInt() and 0xff)
        assertEquals(0x7f, bytes[3].toInt() and 0xff)
    }
}
