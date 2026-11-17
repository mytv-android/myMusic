package com.mytv.mymusic.data.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音质档位归一化与阶梯判断。阶梯判断是「不静默降档」的地基：
 * 用户锁了无损，接口回 128k 时必须判成不达标，否则界面显示无损、实际播 128k。
 */
class AudioQualityTest {

    @Test
    fun `normalize accepts canonical ids`() {
        assertEquals(AudioQuality.Auto, AudioQuality.normalize("auto"))
        assertEquals(AudioQuality.Preview, AudioQuality.normalize("preview"))
        assertEquals(AudioQuality.Low, AudioQuality.normalize("low"))
        assertEquals(AudioQuality.Standard, AudioQuality.normalize("standard"))
        assertEquals(AudioQuality.Lossless, AudioQuality.normalize("lossless"))
        assertEquals(AudioQuality.HiRes, AudioQuality.normalize("hires"))
        assertEquals(AudioQuality.Master, AudioQuality.normalize("master"))
    }

    @Test
    fun `normalize accepts bitrate aliases`() {
        assertEquals(AudioQuality.High, AudioQuality.normalize("320"))
        assertEquals(AudioQuality.High, AudioQuality.normalize("320k"))
        assertEquals(AudioQuality.High, AudioQuality.normalize("exhigh"))
        assertEquals(AudioQuality.Higher, AudioQuality.normalize("192k"))
        assertEquals(AudioQuality.Standard, AudioQuality.normalize("128k"))
        assertEquals(AudioQuality.Low, AudioQuality.normalize("64k"))
        // 平台的私有档名也要认：网易云的 sq、QQ 的 f000、咪咕的 zq32
        assertEquals(AudioQuality.Lossless, AudioQuality.normalize("f000"))
        assertEquals(AudioQuality.HiRes, AudioQuality.normalize("zq24"))
        // zq32 是 32bit 母带，上游归到 Master 而不是 HiRes
        assertEquals(AudioQuality.Master, AudioQuality.normalize("zq32"))
        assertEquals(AudioQuality.Master, AudioQuality.normalize("jymaster"))
        assertEquals(AudioQuality.Atmos, AudioQuality.normalize("q000"))
        assertEquals(AudioQuality.Atmos51, AudioQuality.normalize("q001"))
        assertEquals(AudioQuality.Spatial, AudioQuality.normalize("sky"))
    }

    @Test
    fun `normalize treats blank as auto`() {
        assertEquals(AudioQuality.Auto, AudioQuality.normalize(null))
        assertEquals(AudioQuality.Auto, AudioQuality.normalize(""))
        assertEquals(AudioQuality.Auto, AudioQuality.normalize("   "))
    }

    @Test
    fun `normalize rejects unknown values`() {
        assertNull(AudioQuality.normalize("flac24bit-ultra-hd"))
        assertNull(AudioQuality.normalize("无损"))
    }

    @Test
    fun `ladder orders tiers from highest to lowest`() {
        val ladder = AudioQualityLadder.of(
            listOf(
                AudioQualityOption(AudioQuality.HiRes, "Hi-Res 无损", "flac"),
                AudioQualityOption(AudioQuality.Lossless, "无损音质", "flac"),
                AudioQualityOption(AudioQuality.High, "高品质 320k", "mp3", 320),
                AudioQualityOption(AudioQuality.Standard, "标准音质 128k", "mp3", 128),
            ),
        )
        assertEquals(0, ladder.indexOf(AudioQuality.HiRes))
        assertEquals(3, ladder.indexOf(AudioQuality.Standard))
        assertTrue(ladder.supports(AudioQuality.High))
        assertFalse(ladder.supports(AudioQuality.Master))
        assertNull(ladder.optionFor(AudioQuality.Master))
    }

    @Test
    fun `atLeast compares within the platform ladder`() {
        val ladder = AudioQualityLadder.of(
            listOf(
                AudioQualityOption(AudioQuality.Lossless, "无损音质", "flac"),
                AudioQualityOption(AudioQuality.High, "高品质 320k", "mp3", 320),
                AudioQualityOption(AudioQuality.Standard, "标准音质 128k", "mp3", 128),
            ),
        )
        assertTrue(ladder.atLeast(AudioQuality.Lossless, AudioQuality.High))
        assertTrue(ladder.atLeast(AudioQuality.High, AudioQuality.High))
        // 接口回了一档低的：不达标，不能当成满足了无损请求
        assertFalse(ladder.atLeast(AudioQuality.High, AudioQuality.Lossless))
        assertFalse(ladder.atLeast(AudioQuality.Standard, AudioQuality.Lossless))
        // 平台没有请求的档位时不比较，交给调用方按 auto 处理
        assertTrue(ladder.atLeast(AudioQuality.Standard, AudioQuality.Master))
    }

    @Test
    fun `ladderFrom returns the requested tier and everything below`() {
        val ladder = AudioQualityLadder.of(
            listOf(
                AudioQualityOption(AudioQuality.Lossless, "无损音质", "flac"),
                AudioQualityOption(AudioQuality.High, "高品质 320k", "mp3", 320),
                AudioQualityOption(AudioQuality.Standard, "标准音质 128k", "mp3", 128),
            ),
        )
        assertEquals(
            listOf(AudioQuality.Lossless, AudioQuality.High, AudioQuality.Standard),
            ladder.ladderFrom(AudioQuality.Lossless).map { it.value },
        )
        assertEquals(
            listOf(AudioQuality.Standard),
            ladder.ladderFrom(AudioQuality.Standard).map { it.value },
        )
        assertTrue(ladder.ladderFrom(AudioQuality.Master).isEmpty())
    }

    @Test
    fun `ladder rejects duplicate tiers`() {
        val error = runCatching {
            AudioQualityLadder.of(
                listOf(
                    AudioQualityOption(AudioQuality.High, "高品质", "mp3"),
                    AudioQualityOption(AudioQuality.High, "高品质（重复）", "mp3"),
                ),
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `option summary carries label format and bitrate`() {
        assertEquals(
            "高品质 320k · MP3 · 320 kbps",
            AudioQualityOption(AudioQuality.High, "高品质 320k", "mp3", 320).summary,
        )
        // 无损档没有标称码率，摘要里不出现 0 kbps
        assertEquals("无损音质 · FLAC", AudioQualityOption(AudioQuality.Lossless, "无损音质", "flac").summary)
    }

    @Test
    fun `inferAudioQuality prefers the platform level name`() {
        val byLevel = mapOf("lossless" to AudioQuality.Lossless, "exhigh" to AudioQuality.High)
        assertEquals(AudioQuality.Lossless, inferAudioQuality("lossless", "flac", 900, byLevel))
        assertEquals(AudioQuality.High, inferAudioQuality("exhigh", "mp3", 320, byLevel))
        // 大小写与空白不该影响匹配
        assertEquals(AudioQuality.Lossless, inferAudioQuality("  Lossless ", "flac", 900, byLevel))
    }

    @Test
    fun `inferAudioQuality falls back to format and bitrate`() {
        val byLevel = emptyMap<String, AudioQuality>()
        assertEquals(AudioQuality.Master, inferAudioQuality("", "wav", 2500, byLevel))
        assertEquals(AudioQuality.HiRes, inferAudioQuality("", "flac", 1200, byLevel))
        assertEquals(AudioQuality.Lossless, inferAudioQuality("", "flac", 900, byLevel))
        assertEquals(AudioQuality.High, inferAudioQuality("", "mp3", 320, byLevel))
        assertEquals(AudioQuality.Higher, inferAudioQuality("", "mp3", 192, byLevel))
        assertEquals(AudioQuality.Standard, inferAudioQuality("", "mp3", 128, byLevel))
        assertEquals(AudioQuality.Low, inferAudioQuality("", "mp3", 64, byLevel))
    }
}
