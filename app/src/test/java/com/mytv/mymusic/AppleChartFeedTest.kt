package com.mytv.mymusic

import com.mytv.mymusic.data.native.AppleChartFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 榜单响应解析。样例取自 2026-11-05 实抓的 cn 区 most-played 榜单
 * （app/src/test/resources/apple_charts_cn.json），不是编的——Apple 一改结构这些就得红。
 */
class AppleChartFeedTest {

    private val body: String = checkNotNull(javaClass.getResourceAsStream("/apple_charts_cn.json"))
        .bufferedReader()
        .use { it.readText() }

    @Test
    fun `real response parses into the songs bucket`() {
        val block = AppleChartFeed.parse(body, "songs")
        assertEquals("most-played", block?.chart)
        assertEquals(3, block?.data?.size)
    }

    @Test
    fun `song fields survive the round trip`() {
        val first = AppleChartFeed.parse(body, "songs")!!.data.first()
        val attr = first.attributes
        assertEquals("6829122414", first.id)
        assertEquals("The Weeknd", attr.artistName)
        assertTrue(attr.name.startsWith("Hong Kong"))
        assertTrue(attr.durationInMillis > 0)
        assertTrue(attr.artwork.url.contains("/{w}x{h}bb.jpg"))
        assertTrue(attr.previews.first().url.startsWith("https://audio-ssl.itunes.apple.com/"))
        assertTrue(attr.url.startsWith("https://music.apple.com/cn/album/"))
    }

    @Test
    fun `extendedAssetUrls comes through so full tracks do not need a second request`() {
        // 整曲地址是这次换端点的主要收益之一：以前榜单要先拿 ID 再 lookup 一次才补得上
        val hls = AppleChartFeed.parse(body, "songs")!!.data.first().attributes.extendedAssetUrls.enhancedHls
        assertTrue(hls.startsWith("https://aod.itunes.apple.com/itunes-assets/HLSMusic221/"))
        assertTrue(hls.endsWith(".m3u8"))
    }

    @Test
    fun `unknown attributes are ignored rather than fatal`() {
        // 真实响应里带一堆用不到的字段（audioTraits、genreNames、composerName…），
        // 少配一个 ignoreUnknownKeys 整个榜单就打不开
        val extra = body.replace(
            """"name":"Hong Kong (feat. """,
            """"someFutureField":{"a":1},"name":"Hong Kong (feat. """,
        )
        assertEquals(3, AppleChartFeed.parse(extra, "songs")?.data?.size)
    }

    @Test
    fun `a type that is not in the response means no bucket, not an empty chart`() {
        // type 拼错时 Apple 返回 200 且 results 里没有这个 key。必须让调用方看出来，
        // 不能兜底成空榜单——那会让榜单页看着像"这个平台没榜"
        assertNull(AppleChartFeed.parse(body, "albums"))
        assertNull(AppleChartFeed.parse(body, "playlists"))
    }

    @Test
    fun `well formed but empty payloads yield nothing`() {
        assertNull(AppleChartFeed.parse("{}", "songs"))
        assertNull(AppleChartFeed.parse("""{"results":{}}""", "songs"))
        assertNull(AppleChartFeed.parse("""{"results":{"songs":[]}}""", "songs"))
        assertNull(AppleChartFeed.parse("""{"results":{"songs":[{"data":[]}]}}""", "songs"))
    }

    @Test
    fun `malformed payloads throw instead of looking like an empty chart`() {
        // 截断的响应不能静默变成"没榜"——那会和 Apple 真的没返数据混在一起
        assertThrows(Exception::class.java) { AppleChartFeed.parse("", "songs") }
        assertThrows(Exception::class.java) { AppleChartFeed.parse("not json", "songs") }
        assertThrows(Exception::class.java) { AppleChartFeed.parse(body.take(500), "songs") }
    }

    @Test
    fun `an empty bucket falls through to the next block`() {
        val twoBlocks = """
            {"results":{"songs":[
              {"chart":"most-played","name":"空","data":[]},
              {"chart":"most-played","name":"有数据","data":[{"id":"1","attributes":{"name":"A"}}]}
            ]}}
        """.trimIndent()
        val block = AppleChartFeed.parse(twoBlocks, "songs")
        assertEquals("有数据", block?.name)
        assertEquals("1", block?.data?.firstOrNull()?.id)
    }
}
