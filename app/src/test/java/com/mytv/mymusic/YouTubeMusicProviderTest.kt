package com.mytv.mymusic

import com.mytv.mymusic.data.native.YouTubeMusicProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeMusicProviderTest {
    private val provider = YouTubeMusicProvider()

    @Test
    fun extractsVideoIdFromCommonLinks() {
        assertEquals("DxpndDEAqGk", provider.extractVideoId("https://music.youtube.com/watch?v=DxpndDEAqGk&list=x"))
        assertEquals("DxpndDEAqGk", provider.extractVideoId("https://youtu.be/DxpndDEAqGk"))
        assertEquals("DxpndDEAqGk", provider.extractVideoId("DxpndDEAqGk"))
        assertNull(provider.extractVideoId("https://example.com/nope"))
    }

    @Test
    fun extractsPlaylistIdFromLinks() {
        val link = "https://music.youtube.com/playlist?list=PL4fGSI1pDJn5ZuHumtS6meFhiCcxLqrCb"
        assertEquals("PL4fGSI1pDJn5ZuHumtS6meFhiCcxLqrCb", provider.extractPlaylistId(link))
        assertEquals("PL4fGSI1pDJn5ZuHumtS6meFhiCcxLqrCb", provider.extractPlaylistId("PL4fGSI1pDJn5ZuHumtS6meFhiCcxLqrCb"))
    }

    /** SAPISIDHASH = sha1("时间戳 空格 SAPISID 空格 origin")，照 ytmusicapi 的算法。 */
    @Test
    fun computesSapisidHash() {
        val headers = provider.authHeaders("SID=x; SAPISID=abc123; HSID=y", timestamp = 1_700_000_000)
        assertEquals(
            "SAPISIDHASH 1700000000_597a8411c1c01f9c14cea84b6ec377b76d93be6e",
            headers["Authorization"],
        )
        assertEquals("0", headers["X-Goog-AuthUser"])
        assertEquals(emptyMap<String, String>(), provider.authHeaders("SID=x; HSID=y"))
    }

    /** InnerTube 的 renderer 结构照真实响应裁剪；解析错一层就会整页空歌单。 */
    @Test
    fun parsesPlaylistTracks() {
        val body = """
            {"contents":{"musicResponsiveListItemRenderer":{
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"NEON"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"A2O LITE"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Album X"}]}}}
              ],
              "playlistItemData":{"videoId":"D968qfUZMUE"},
              "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"3:55"}]}}}],
              "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                {"url":"https://img.example/cover.jpg","width":400,"height":400}
              ]}}}
            }}}
        """.trimIndent()
        val songs = provider.parseTracks(Json.parseToJsonElement(body))
        assertEquals(1, songs.size)
        val song = songs.single()
        assertEquals("D968qfUZMUE", song.id)
        assertEquals("NEON", song.name)
        assertEquals("A2O LITE", song.artist)
        assertEquals("Album X", song.album)
        assertEquals(235, song.duration)
        assertEquals("https://img.example/cover.jpg", song.cover)
        assertEquals("https://music.youtube.com/watch?v=D968qfUZMUE", song.link)
    }
}
