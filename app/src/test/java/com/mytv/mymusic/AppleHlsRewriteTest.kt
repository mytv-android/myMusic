package com.mytv.mymusic

import com.mytv.mymusic.data.native.AppleHlsManifest
import com.mytv.mymusic.data.native.AppleHlsRewrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整曲媒体清单重写。样例是 2026-11-05 实抓的 NEON（adam 6820428730）stereo-256 清单。
 *
 * 重写只做两件事：段/init 地址绝对化、init 段 tenc kid 按样本条目改写（见
 * [AppleInitSegmentKidTest]）。两把 key 都必须原样留着——默认 key 保开头 30s 左右，
 * 轮换 key 保正片，删掉任何一把都会丢一段真实的歌（开头那段曾被误当静音删过）。
 */
class AppleHlsRewriteTest {

    private val playlistUrl =
        "https://aod.itunes.apple.com/itunes-assets/HLSMusic221/v4/7c/b3/62/7cb36273-983b-d8ac-9646-054b48108e5c/P1516389482_A6820428730_audio_en_gr256_mp4a-40-2.m3u8"
    private val baseDir = playlistUrl.substringBeforeLast('/') + "/"

    private val media: String = checkNotNull(javaClass.getResourceAsStream("/apple_neon_media.m3u8"))
        .bufferedReader()
        .use { it.readText() }

    private val widevineKeyLine =
        Regex("""#EXT-X-KEY:[^\r\n]*edef8ba9-79d6-4ace-a3c8-27dcd51d21ed[^\r\n]*""")

    @Test
    fun `both widevine keys survive the rewrite`() {
        val out = AppleHlsRewrite.rewrite(media, playlistUrl)
        // 默认 key（预取段）和轮换 key（正片）都得在，且保持 data: URI 原样
        assertTrue("默认 key 行丢了:\n$out", out.contains(AppleHlsManifest.defaultWidevineKeyUri()))
        val lines = widevineKeyLine.findAll(out).toList()
        assertEquals("Widevine key 行数量不对:\n$out", 2, lines.size)
        lines.forEach { assertTrue("URI 不是原样的 data: URI: ${it.value}", it.value.contains("URI=\"data:text/plain;base64,")) }
        // 两把 key 的先后顺序不能变：默认在前，轮换在后（样本条目 1=预取、2=正片就按这个排）
        val defaultAt = out.indexOf(AppleHlsManifest.defaultWidevineKeyUri())
        val rotatedAt = out.indexOf(lines.last().value)
        assertTrue("默认 key 应排在轮换 key 前面", defaultAt in 0 until rotatedAt)
    }

    @Test
    fun `rotated pssh is picked from the playlist`() {
        val all = AppleHlsManifest.widevineKeyUris(media)
        assertEquals("应识别出两把 key", 2, all.size)
        assertEquals("第一个应是默认 key", AppleHlsManifest.defaultWidevineKeyUri(), all[0])
        assertEquals("widevinePssh 必须给轮换 key", all[1], AppleHlsManifest.widevinePssh(media))
    }

    @Test
    fun `split puts the default key block and its segments in the intro part`() {
        val out = AppleHlsRewrite.rewrite(media, playlistUrl)
        val split = AppleHlsRewrite.splitAtSecondKeyBlock(out)
        val intro = split.intro
        assertNotNull("两把 key 的清单应该切出开头段", intro)
        // 每份清单只留一把 Widevine key，否则 Media3 整轨只会用其中一把
        assertEquals("intro 里的 Widevine key 数不对:\n$intro", 1, widevineKeyLine.findAll(intro!!).count())
        assertTrue("intro 没拿到默认 key:\n$intro", intro.contains(AppleHlsManifest.defaultWidevineKeyUri()))
        assertEquals("main 里的 Widevine key 数不对", 1, widevineKeyLine.findAll(split.main).count())
        assertFalse("main 不该再有默认 key", split.main.contains(AppleHlsManifest.defaultWidevineKeyUri()))
        // 两份都要有 init 段和 ENDLIST
        listOf(intro, split.main).forEach {
            assertTrue("缺 EXT-X-MAP:\n$it", it.contains("#EXT-X-MAP"))
            assertTrue("缺 ENDLIST:\n$it", it.trimEnd().endsWith("#EXT-X-ENDLIST"))
        }
        // 段一个都不能丢
        val total = out.split("\n").count { it.startsWith("https://") }
        val kept = intro.split("\n").count { it.startsWith("https://") } +
            split.main.split("\n").count { it.startsWith("https://") }
        assertEquals("切完段数变了", total, kept)
    }

    @Test
    fun `split leaves a single-key playlist alone`() {
        val one = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="${AppleHlsManifest.defaultWidevineKeyUri()}",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
            #EXTINF:15.0,
            seg1.mp4
            #EXT-X-ENDLIST
        """.trimIndent()
        val split = AppleHlsRewrite.splitAtSecondKeyBlock(one)
        assertNull("只有一把 key 不该切", split.intro)
        assertEquals(one, split.main)
    }

    @Test
    fun `relative segment and map uris are absolutized`() {
        val out = AppleHlsRewrite.rewrite(media, playlistUrl)
        val outSegments = out.split("\n").filter { it.startsWith("https://") && it.endsWith(".mp4") }
        val originalSegments = media.split("\n").filter { it.startsWith("P1516389482") }
        assertEquals("段数量变了:\n$out", originalSegments.size, outSegments.size)
        outSegments.forEach { assertTrue("不是同目录的绝对地址: $it", it.startsWith(baseDir)) }
        // byterange 行原样保留
        assertTrue(out.contains("#EXT-X-BYTERANGE:"))
    }

    @Test
    fun `line count is unchanged`() {
        val out = AppleHlsRewrite.rewrite(media, playlistUrl)
        assertEquals(media.split("\n").size, out.split("\n").size)
    }

    @Test
    fun `crlf playlists keep crlf`() {
        val crlf = media.replace("\n", "\r\n")
        val out = AppleHlsRewrite.rewrite(crlf, playlistUrl)
        assertTrue(out.contains("\r\n"))
        assertFalse(out.substringAfterLast("\r\n").contains('\n'))
    }

    @Test
    fun `junk playlist passes through untouched`() {
        assertEquals("", AppleHlsRewrite.rewrite("", playlistUrl))
        val junk = "#EXTM3U\n#EXT-X-ENDLIST\n"
        assertEquals(junk, AppleHlsRewrite.rewrite(junk, playlistUrl))
        // base 不是合法 URI 时原样返回，不做任何改动
        assertEquals(junk, AppleHlsRewrite.rewrite(junk, "::not a url::"))
    }

    @Test
    fun `playlist with only the default key still parses to something playable`() {
        val onlyDefault = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:15
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="${AppleHlsManifest.defaultWidevineKeyUri()}",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",KEYFORMATVERSIONS="1"
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:15.0,
            seg1.mp4
            #EXT-X-ENDLIST
        """.trimIndent()
        val out = AppleHlsRewrite.rewrite(onlyDefault, "https://example.com/a/master.m3u8")
        assertTrue("默认 key 行被删了:\n$out", out.contains("edef8ba9"))
        assertTrue("MAP 没绝对化:\n$out", out.contains("https://example.com/a/init.mp4"))
        assertTrue("段没绝对化:\n$out", out.contains("https://example.com/a/seg1.mp4"))
    }
}
