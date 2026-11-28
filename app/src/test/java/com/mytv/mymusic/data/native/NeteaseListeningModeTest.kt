package com.mytv.mymusic.data.native

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网易云听歌模式：心动模式的入口与响应解析。
 *
 * 这里只覆盖不联网的部分（模式列表、未登录提示、样本 JSON 解析）；
 * 真实生成并播放队列要授权的登录环境，单测不碰网络。
 */
class NeteaseListeningModeTest {
    @Test
    fun includesHeartbeatAndRoamModes() = runBlocking {
        val modes = NetEaseProvider().listeningModes("")
        assertTrue(modes.any { it.id == "heartbeat" && it.name == "心动模式" })
        assertTrue(modes.any { it.id == "fm:DEFAULT" })
    }

    @Test
    fun heartbeatWithoutLoginAsksForLogin() = runBlocking {
        // 未登录时先给出可读提示，不去打接口
        val provider = NetEaseProvider()
        val mode = provider.listeningModes("").first { it.id == "heartbeat" }
        val error = runCatching { provider.listeningModeSongs("", mode) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("登录"))
    }

    @Test
    fun heartbeatSongAcceptsNewFieldShape() {
        // 新版接口字段：ar / al / dt
        val song = songOf(
            """{"id":123,"name":"样本","ar":[{"name":"歌手甲"},{"name":"歌手乙"}],
               "al":{"id":9,"name":"专辑","picUrl":"https://p1/cover.jpg"},"dt":215000}""",
        )!!
        assertEquals("123", song.id)
        assertEquals("样本", song.name)
        assertEquals("歌手甲、歌手乙", song.artist)
        assertEquals("专辑", song.album)
        assertEquals("9", song.albumId)
        assertEquals(215, song.duration)
        assertEquals("netease", song.source)
        assertEquals("https://p1/cover.jpg", song.cover)
        assertEquals(mapOf("song_id" to "123"), song.extra)
    }

    @Test
    fun heartbeatSongAcceptsLegacyFieldShape() {
        // 旧版接口字段：artists / album / duration，没有高清封面时退回模糊封面
        val song = songOf(
            """{"id":456,"name":"旧样本","artists":[{"name":"歌手丙"}],
               "album":{"name":"旧专辑","blurPicUrl":"https://p2/blur.jpg"},"duration":180500}""",
        )!!
        assertEquals("歌手丙", song.artist)
        assertEquals("旧专辑", song.album)
        assertEquals("https://p2/blur.jpg", song.cover)
        assertEquals(180, song.duration)
    }

    @Test
    fun heartbeatSongAcceptsSongInfoWrapper() {
        // 真实接口把歌曲放在 songInfo 里，外层只是候选条目；字段仍沿用新版 ar/al/dt
        val song = songOf(
            """{"songInfo":{"id":789,"name":"包裹样本","ar":[{"name":"歌手丁"}],
               "al":{"id":10,"name":"包裹专辑","picUrl":"https://p3/wrapped.jpg"},"dt":200500}}""",
        )!!
        assertEquals("789", song.id)
        assertEquals("包裹样本", song.name)
        assertEquals("歌手丁", song.artist)
        assertEquals("包裹专辑", song.album)
        assertEquals("10", song.albumId)
        assertEquals(200, song.duration)
        assertEquals("https://p3/wrapped.jpg", song.cover)
    }

    @Test
    fun heartbeatSongSkipsBrokenWrappedEntries() {
        // 包裹项里没有 songInfo、songInfo 为空或缺少 id/name 时跳过，不让整批失败
        assertNull(songOf("""{"songInfo":null}"""))
        assertNull(songOf("""{"songInfo":{}}"""))
        assertNull(songOf("""{"songInfo":{"name":"没有 id"}}"""))
        assertNull(songOf("""{"songInfo":{"id":2}}"""))
    }

    @Test
    fun heartbeatSongSkipsEntriesWithoutIdOrName() {
        // 队列里混进残缺项时跳过这一首，不让整批失败
        assertNull(songOf("""{"name":"没有 id"}"""))
        assertNull(songOf("""{"id":1}"""))
    }

    private fun songOf(raw: String) =
        NetEaseProvider().heartbeatSong(Json.parseToJsonElement(raw).jsonObject)
}
