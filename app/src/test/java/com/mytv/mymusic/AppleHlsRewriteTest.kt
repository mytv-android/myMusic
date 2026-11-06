package com.mytv.mymusic

import com.mytv.mymusic.data.native.AppleHlsManifest
import com.mytv.mymusic.data.native.AppleHlsRewrite
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整曲媒体清单重写。样例是 2026-11-05 实抓的 NEON（adam 6820428730）stereo-256 清单。
 *
 * 重写的唯一动机：删掉默认 key 行后 Media3 的 DRM initData 才会落到轮换 key 上，
 * Apple 的 license（只回挑战里那个 kid）才能对上音频段 tenc 的 kid——
 * 不删的话 CDM 拿着默认 key 的密钥解不了任何音频段，解码器报 4003。
 */
class AppleHlsRewriteTest {

    private val playlistUrl =
        "https://aod.itunes.apple.com/itunes-assets/HLSMusic221/v4/7c/b3/62/7cb36273-983b-d8ac-9646-054b48108e5c/P1516389482_A6820428730_audio_en_gr256_mp4a-40-2.m3u8"
    private val baseDir = playlistUrl.substringBeforeLast('/') + "/"

    private val media: String = checkNotNull(javaClass.getResourceAsStream("/apple_neon_media.m3u8"))
        .bufferedReader()
        .use { it.readText() }

    @Test
    fun `default widevine key line is gone and rotated key stays`() {
        val defaultLine = media.lineSequence().first { it.contains("edef8ba9") && it.contains("AAAAAczEvZTEg") }
        assertTrue("isDefaultWidevineKey 不认这行:\n$defaultLine", AppleHlsRewrite.isDefaultWidevineKey(defaultLine))
        val out = AppleHlsRewrite.rewrite(media, playlistUrl)
        // 默认 key 必须没了——它就是让 initData 落错的那行
        assertFalse("默认 key 行没删掉:\n$out", out.contains(AppleHlsManifest.defaultWidevineKeyUri()))
        // 轮换 key 必须还在，而且保持 data: URI 原样（不能被 resolve 弄坏）
        val widevineKeys = Regex("""#EXT-X-KEY:[^\r\n]*edef8ba9-79d6-4ace-a3c8-27dcd51d21ed[^\r\n]*""").findAll(out).toList()
        assertEquals("轮换 key 行数量不对:\n$out", 1, widevineKeys.size)
        assertTrue(widevineKeys[0].value.contains("URI=\"data:text/plain;base64,"))
        // FairPlay / PlayReady 行不受影响
        assertTrue(out.contains("com.apple.streamingkeydelivery"))
        assertTrue(out.contains("com.microsoft.playready"))
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
    fun `byte count is preserved apart from removed lines`() {
        val out = AppleHlsRewrite.rewrite(media, playlistUrl)
        // 删掉的恰好是默认 key 那一行：行数差 1（split 的末尾空元素两边一致）
        assertEquals(media.split("\n").size - 1, out.split("\n").size)
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
        assertFalse("默认 key 行没删掉:\n$out", out.contains("edef8ba9"))
        assertTrue("MAP 没绝对化:\n$out", out.contains("https://example.com/a/init.mp4"))
        assertTrue("段没绝对化:\n$out", out.contains("https://example.com/a/seg1.mp4"))
    }

    // ---- 静音前导段识别（tfhd 探测）----

    /** 真机样例：预取段的 tfhd（flags=0x020008，不标 sample_description_index）。 */
    private val TFHD_PREFETCH = byteArrayOf(
        0, 0, 1, 8, 't'.code.toByte(), 'f'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte(),
        0x00, 0x02, 0x00, 0x08, // version=0 flags=0x020008（无 sdi 位）
        0, 0, 0, 1,             // track_id=1
        0, 0, 4, 0,             // default_duration
    )

    /** 真机样例：正片段的 tfhd（flags=0x02000A，sample_description_index=2）。 */
    private val TFHD_REAL = byteArrayOf(
        0, 0, 1, 12, 't'.code.toByte(), 'f'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte(),
        0x00, 0x02, 0x00, 0x0A, // version=0 flags=0x02000A（带 sdi）
        0, 0, 0, 1,             // track_id=1
        0, 0, 0, 2,             // sample_description_index=2 → 正片条目
    )

    @Test
    fun `tfhd probe distinguishes prefetch from real fragments`() {
        assertEquals("没标 sdi 的应是预取段", true, AppleHlsRewrite.isPrefetchFragment(TFHD_PREFETCH))
        assertEquals("sdi=2 应是正片段", false, AppleHlsRewrite.isPrefetchFragment(TFHD_REAL))
        assertNull("没有 tfhd 应返回 null（看不懂）", AppleHlsRewrite.isPrefetchFragment(ByteArray(64)))
        assertNull("太短应返回 null", AppleHlsRewrite.isPrefetchFragment(TFHD_PREFETCH.copyOf(10)))
    }

    @Test
    fun `trim drops leading prefetch segments and keeps the rest`() = runBlocking {
        val rewritten = AppleHlsRewrite.rewrite(media, playlistUrl)
        val originalSegments = rewritten.split("\n").count { it.startsWith("https://") }
        // 第 1 段是预取静音（NEON 实测），其余是正片
        var calls = 0
        val trimmed = AppleHlsRewrite.trimPrefetchSegments(rewritten) { _, _, _ ->
            calls++
            if (calls == 1) TFHD_PREFETCH else TFHD_REAL
        }
        val kept = trimmed.split("\n").count { it.startsWith("https://") }
        assertEquals("应丢 1 段", originalSegments - 1, kept)
        assertFalse("丢的应是第一个段", trimmed.contains("485745@1127"))
        assertTrue("EXT-X-MAP 必须保留", trimmed.contains("#EXT-X-MAP"))
        assertTrue("ENDLIST 必须保留", trimmed.contains("#EXT-X-ENDLIST"))
        assertEquals("探测次数应止于第一个正片段", 2, calls)
    }

    @Test
    fun `trim keeps everything when probe fails or all real`() = runBlocking {
        val rewritten = AppleHlsRewrite.rewrite(media, playlistUrl)
        // 探测失败 → 原样
        assertEquals(rewritten, AppleHlsRewrite.trimPrefetchSegments(rewritten) { _, _, _ -> null })
        // 第一个就是正片 → 原样
        assertEquals(rewritten, AppleHlsRewrite.trimPrefetchSegments(rewritten) { _, _, _ -> TFHD_REAL })
        // 没有 byterange 的清单不动
        val plain = "#EXTM3U\n#EXTINF:15.0,\nhttps://example.com/whole.mp4\n#EXT-X-ENDLIST\n"
        assertEquals(plain, AppleHlsRewrite.trimPrefetchSegments(plain) { _, _, _ -> TFHD_PREFETCH })
    }
}
