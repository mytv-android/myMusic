package com.mytv.mymusic

import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelJsonTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun parsesProviderCapabilities() {
        val providers = json.decodeFromString(ListSerializer(ProviderInfo.serializer()), """[{"ID":"netease","Name":"网易云音乐","Search":true,"QRLogin":true}]""")
        assertEquals("netease", providers.single().id)
        assertEquals(true, providers.single().qrLogin)
    }

    @Test fun parsesSongFields() {
        val song = json.decodeFromString<Song>("""{"id":"1","name":"歌曲","artist":"歌手","album_id":"a","is_vip":true,"extra":{"quality":"flac"}}""")
        assertEquals("netease:1", song.copy(source = "netease").key)
        assertEquals("flac", song.extra["quality"])
    }
}
