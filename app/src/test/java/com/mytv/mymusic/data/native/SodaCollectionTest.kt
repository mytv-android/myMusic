package com.mytv.mymusic.data.native

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 汽水收藏（「我喜欢的音乐」）与「我收藏的」列表的请求体 / 回包解析，
 * 逐条对齐 libresoda `collection.rs` 的离线用例。
 */
class SodaCollectionTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    @Test
    fun collectMediaBodyCarriesEmptyScene() {
        val body = parse(SodaCollection.collectMediaBody(listOf("7501674235158431760")))
        // 客户端 collect.ts 固定传空串 scene
        assertEquals("", body["scene"].toString().trim('"'))
        val media = body["media"] as kotlinx.serialization.json.JsonArray
        val first = media[0] as JsonObject
        assertEquals("7501674235158431760", first["id"].toString().trim('"'))
        assertEquals("track", first["type"].toString().trim('"'))
        // 没有 collect_action：官方回包没有这个字段
        assertFalse(body.containsKey("collect_action"))
    }

    @Test
    fun uncollectMediaBodyHasNoScene() {
        val body = parse(SodaCollection.uncollectMediaBody(listOf("7501")))
        assertFalse(body.containsKey("scene"))
        assertTrue(body.containsKey("media"))
    }

    @Test
    fun idsBodyFieldNamesMatchServer() {
        assertEquals("playlist_ids", parse(SodaCollection.idsBody("playlist_ids", listOf("a"))).keys.first())
        assertEquals("album_ids", parse(SodaCollection.idsBody("album_ids", listOf("a"))).keys.first())
        assertEquals("artist_ids", parse(SodaCollection.idsBody("artist_ids", listOf("a"))).keys.first())
    }

    @Test
    fun countClampMatchesUpstream() {
        assertEquals(20, SodaCollection.clampCount(0))
        assertEquals(20, SodaCollection.clampCount(-5))
        assertEquals(50, SodaCollection.clampCount(50))
        assertEquals(100, SodaCollection.clampCount(500))
    }

    @Test
    fun emptyFavouriteListIsEmptyNotFailure() {
        // 服务端在没有收藏时只回 status_info，连 mixed_collections 字段都不给
        val items = SodaCollectedParser.parse(parse("""{"status_info":{"now":1789445945}}"""))
        assertTrue(items.isEmpty())
    }

    @Test
    fun playlistItemIsParsedWithPublicTitleFirst() {
        val items = SodaCollectedParser.parse(
            parse(
                """
                {"total_num":1,"mixed_collections":[{"playlist":{
                   "id":"7306662862905147427","title":"华语","public_title":"华语热歌",
                   "url_cover":{"template_prefix":"tplv-b829550vbb","uri":"ies-music/cover",
                                "urls":["https://p3-luna.douyinpic.com/img/"]}}}]}
                """.trimIndent(),
            ),
        )
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("playlist", item.kind)
        assertEquals("7306662862905147427", item.id)
        // public_title 优先于 title
        assertEquals("华语热歌", item.title)
        assertTrue(item.cover.startsWith("https://p3-luna.douyinpic.com/img/"))
    }

    @Test
    fun unknownKindKeepsRawPayload() {
        val items = SodaCollectedParser.parse(
            parse("""{"mixed_collections":[{"album":{"id":"999","name":"专辑"}}]}"""),
        )
        assertEquals(1, items.size)
        assertEquals("album", items[0].kind)
        assertEquals("999", items[0].id)
        assertEquals("专辑", items[0].title)
    }

    @Test
    fun collectedArtistsComeFromTheirOwnEndpoint() {
        val artists = SodaCollectedParser.parseArtists(
            parse("""{"artists":[{"id":"7123","name":"周杰伦","is_collected":true}]}"""),
        )
        assertEquals(1, artists.size)
        assertEquals("artist", artists[0].kind)
        assertEquals("7123", artists[0].id)
        assertEquals("周杰伦", artists[0].title)
    }

    @Test
    fun itemWithoutAnyKnownTypeIsDropped() {
        val items = SodaCollectedParser.parse(parse("""{"mixed_collections":[{"unknown":{"id":"1"}}]}"""))
        assertTrue(items.isEmpty())
    }

    @Test
    fun parseReturnsNullForNonJson() {
        assertNull(SodaJson.parse(""))
        assertNull(SodaJson.parse("not json"))
        assertNotNull(SodaJson.parse("{}"))
    }

    @Test
    fun stringReadsSkipNonStringPrimitives() {
        val root = parse("""{"a":{"b":"文本","c":123}}""")
        assertEquals("文本", jsonText(root, "a", "b"))
        // 数字按它的文本读：服务端有的字段给 "123"、有的给 123，两种都要能拿到
        assertEquals("123", jsonText(root, "a", "c"))
        assertEquals(123, jsonInt(root, "a", "c"))
        // 缺字段 / 不是对象时给空，不抛
        assertEquals("", jsonText(root, "a", "missing"))
        assertEquals("", jsonText(null, "a"))
    }

    @Test
    fun boolAcceptsNumbersAndWords() {
        val root = parse("""{"a":true,"b":false,"c":1,"d":0,"e":"yes","f":""}""")
        assertEquals(true, jsonBool(root, "a"))
        assertEquals(false, jsonBool(root, "b"))
        assertEquals(true, jsonBool(root, "c"))
        assertEquals(false, jsonBool(root, "d"))
        assertEquals(true, jsonBool(root, "e"))
        assertNull(jsonBool(root, "f"))
    }
}
