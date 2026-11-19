package com.mytv.mymusic.data.lx

import com.mytv.mymusic.data.Song
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LxProtocolTest {
    @Test
    fun keepsExtendedQualitiesLikeLxserver() {
        val capabilities = LxProtocol.parseInit(
            """
            {
              "status": true,
              "info": {"sources": {
                "kw": {"name":"酷我","type":"music","actions":["musicUrl","lyric"],"qualitys":["128k","320k","flac","hires","master"]},
                "tx": {"name":"QQ","type":"music","actions":["musicUrl"],"qualitys":["flac24bit","atmos","atmos_plus","master"]},
                "local": {"type":"music","actions":["musicUrl","lyric","pic"],"qualitys":[]},
                "unknown": {"type":"music","actions":["musicUrl"],"qualitys":["320k"]},
                "qs": {"type":"music","actions":["musicUrl"],"qualitys":["32k","flac"]}
              }}
            }
            """.trimIndent(),
        )
        assertEquals(setOf("kw", "tx", "local"), capabilities.keys)
        assertEquals(listOf("musicUrl"), capabilities.getValue("kw").actions)
        assertEquals(listOf("128k", "320k", "flac", "hires", "master"), capabilities.getValue("kw").qualities)
        assertEquals(listOf("flac24bit", "atmos", "atmos_plus", "master"), capabilities.getValue("tx").qualities)
        assertEquals(listOf("musicUrl", "lyric", "pic"), capabilities.getValue("local").actions)
    }

    @Test
    fun mapsNativeSongFieldsForEveryPlatform() {
        val qq = LxProtocol.musicInfo("tx", Song(id = "mid1", name = "晴天", artist = "周杰伦", source = "qq", extra = mapOf("cookie" to "secret")))
        assertEquals("mid1", qq["songmid"]?.jsonPrimitive?.content)
        assertFalse(qq.containsKey("cookie"))
        val kugou = LxProtocol.musicInfo(
            "kg",
            Song(id = "HASH", source = "kugou", extra = mapOf("hash" to "HASH", "album_audio_id" to "123")),
        )
        assertEquals("HASH", kugou["hash"]?.jsonPrimitive?.content)
        assertEquals("123", kugou["songmid"]?.jsonPrimitive?.content)
        val migu = LxProtocol.musicInfo(
            "mg",
            Song(id = "content|2", source = "migu", extra = mapOf("copyright_id" to "copyright")),
        )
        assertEquals("copyright", migu["songmid"]?.jsonPrimitive?.content)
        assertEquals("tx", LxPlatform.sourceKey("qq"))
        assertEquals("kg", LxPlatform.sourceKey("kugou"))
        assertEquals("kw", LxPlatform.sourceKey("kuwo"))
        assertEquals("wy", LxPlatform.sourceKey("netease"))
        assertEquals("mg", LxPlatform.sourceKey("migu"))
    }

    @Test
    fun requestContainsQualityAndMusicInfo() {
        val payload = LxProtocol.requestPayload("wy", "320k", Song(id = "42", name = "歌", source = "netease"))
        assertEquals("musicUrl", payload["action"]?.jsonPrimitive?.content)
        assertEquals("wy", payload["source"]?.jsonPrimitive?.content)
        val info = payload["info"]?.jsonObject
        assertEquals("320k", info?.get("type")?.jsonPrimitive?.content)
        assertEquals("42", info?.get("musicInfo")?.jsonObject?.get("songmid")?.jsonPrimitive?.content)
    }

    @Test
    fun prefersCommonQualityBeforeLosslessFallback() {
        val capability = LxCapability(actions = listOf("musicUrl"), qualities = listOf("flac24bit", "flac", "128k", "320k"))
        assertEquals(listOf("320k", "128k", "flac", "flac24bit"), LxProtocol.preferredQualities(capability))
        assertEquals(listOf("flac"), LxProtocol.preferredQualities(capability, "lossless"))
        assertTrue(LxProtocol.preferredQualities(capability, "higher").isEmpty())
    }

    @Test
    fun mapsExtendedPreferencesWithBaseFallback() {
        val extended = LxCapability(actions = listOf("musicUrl"), qualities = listOf("320k", "flac", "flac24bit", "hires", "atmos", "master"))
        assertEquals(listOf("hires", "flac24bit", "flac"), LxProtocol.preferredQualities(extended, "hires"))
        assertEquals(listOf("atmos", "hires", "flac24bit", "flac"), LxProtocol.preferredQualities(extended, "atmos"))
        assertEquals(listOf("master", "hires", "flac24bit", "flac"), LxProtocol.preferredQualities(extended, "master"))
        // 只声明基础四档的脚本：扩展偏好降级到无损档，行为与透传前一致
        val baseOnly = LxCapability(actions = listOf("musicUrl"), qualities = listOf("128k", "320k", "flac", "flac24bit"))
        assertEquals(listOf("flac24bit", "flac"), LxProtocol.preferredQualities(baseOnly, "hires"))
        assertEquals(listOf("flac24bit", "flac"), LxProtocol.preferredQualities(baseOnly, "master"))
    }

    @Test
    fun customStreamCarriesAttribution() {
        val record = LxSourceRecord(id = "lx_test", name = "测试源")
        val stream = LxProtocol.stream("https://cdn.example.com/a.flac?sign=1", "flac", record)
        assertEquals("flac", stream.ext)
        assertEquals("测试源", stream.extra["lx_source"])
        assertFalse(stream.preview)
        assertTrue(stream.bitrate > 320)
    }

    @Test
    fun extendedStreamBitratesAreNominal() {
        val record = LxSourceRecord(id = "lx_test", name = "测试源")
        assertEquals(4608, LxProtocol.stream("https://cdn.example.com/a.flac?sign=1", "hires", record).bitrate)
        assertEquals(9216, LxProtocol.stream("https://cdn.example.com/b.flac?sign=1", "master", record).bitrate)
        assertEquals(768, LxProtocol.stream("https://cdn.example.com/c.ec3?sign=1", "atmos", record).bitrate)
        assertEquals(192, LxProtocol.stream("https://cdn.example.com/d.mp3?sign=1", "192k", record).bitrate)
    }
}
