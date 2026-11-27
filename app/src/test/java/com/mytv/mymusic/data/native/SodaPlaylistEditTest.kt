package com.mytv.mymusic.data.native

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 汽水歌单写操作的请求体与回包解析，逐条对齐 libresoda `playlist_edit.rs` 的离线用例：
 * 请求体形状（`media` 而不是 `track_ids`）、30 字符硬线、成功回包缺 `status_code`
 * 必须当成功、敏感词以 `is_passed` 取反。
 */
class SodaPlaylistEditTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    @Test
    fun mediaArrayKeepsOrderAndDropsBlank() {
        val array = SodaPlaylistEdit.mediaArray(listOf(" 7501 ", "", "7502"))
        assertEquals(2, array.size)
        assertEquals("7501", array[0].let { (it as JsonObject)["id"] }.toString().trim('"'))
    }

    @Test
    fun createBodyUsesMediaNotTrackIds() {
        val body = parse(SodaPlaylistEdit.createBody("夜跑", isPrivate = true, trackIds = listOf("7501")))
        // 官方 CreatePlaylistDialog.vue 传的是 media:[{id,type}]，不是 track_ids
        assertTrue(body.containsKey("media"))
        assertFalse(body.containsKey("track_ids"))
        assertEquals("夜跑", body["name"].toString().trim('"'))
        assertEquals("true", body["is_private"].toString())
        val media = body["media"] as kotlinx.serialization.json.JsonArray
        val first = media[0] as JsonObject
        assertEquals("7501", first["id"].toString().trim('"'))
        assertEquals("track", first["type"].toString().trim('"'))
    }

    @Test
    fun createBodyOmitsMediaWhenEmpty() {
        val body = parse(SodaPlaylistEdit.createBody("空的", isPrivate = false, trackIds = emptyList()))
        assertFalse(body.containsKey("media"))
    }

    @Test
    fun updateBodyOnlyCarriesGivenFields() {
        val body = parse(SodaPlaylistEdit.updateBody("pl-1", name = "新名字"))
        assertEquals("pl-1", body["playlist_id"].toString().trim('"'))
        assertEquals("新名字", body["name"].toString().trim('"'))
        assertFalse(body.containsKey("description"))
        assertFalse(body.containsKey("is_private"))
    }

    @Test
    fun mediaBodyCarriesPlaylistIdAndOrder() {
        val body = parse(SodaPlaylistEdit.mediaBody("pl-1", listOf("a", "b")))
        val media = body["media"] as kotlinx.serialization.json.JsonArray
        assertEquals("a", (media[0] as JsonObject)["id"].toString().trim('"'))
        assertEquals("b", (media[1] as JsonObject)["id"].toString().trim('"'))
    }

    @Test
    fun nameLimitIsThirtyChars() {
        assertNull(SodaPlaylistEdit.checkName("a".repeat(30)))
        assertTrue(SodaPlaylistEdit.checkName("a".repeat(31))!!.contains("30"))
        assertTrue(SodaPlaylistEdit.checkName("   ") != null)
    }

    @Test
    fun missingStatusCodeCountsAsSuccess() {
        // CreatePlaylist / UpdatePlaylistInfo 的成功回包只有 playlist + status_info
        assertTrue(SodaPlaylistEdit.responseIsOk(parse("""{"playlist":{"id":"7501"},"status_info":{"now":1}}""")))
        assertTrue(SodaPlaylistEdit.responseIsOk(parse("""{"status_code":0}""")))
        assertFalse(SodaPlaylistEdit.responseIsOk(parse("""{"status_code":1000004}""")))
        // 收藏接口的错误码挂在下层 status_info，不能因为顶层缺码就当成功
        assertFalse(
            SodaPlaylistEdit.responseIsOk(
                parse("""{"status_info":{"status_code":1000003,"status_msg":"未登录"}}"""),
            ),
        )
        assertEquals(
            1000003,
            SodaPlaylistEdit.responseCode(parse("""{"status_info":{"status_code":1000003}}""")),
        )
        // 解析不出 JSON 时不当成功：宁可报错也不能把失败吞了
        assertFalse(SodaPlaylistEdit.responseIsOk(null))
    }

    @Test
    fun responseErrorMessageReadsStatusInfo() {
        assertEquals(
            "invalid param",
            SodaPlaylistEdit.responseErrorMessage(parse("""{"status_code":1000004,"status_info":{"status_msg":"invalid param"}}""")),
        )
        assertEquals("", SodaPlaylistEdit.responseErrorMessage(parse("""{"status_code":0}""")))
    }

    @Test
    fun extractPlaylistIdCoversEveryServerShape() {
        assertEquals("7501", SodaPlaylistEdit.extractPlaylistId(parse("""{"data":{"playlist_id":"7501"}}""")))
        assertEquals("7502", SodaPlaylistEdit.extractPlaylistId(parse("""{"data":{"playlist":{"id":"7502"}}}""")))
        assertEquals("7503", SodaPlaylistEdit.extractPlaylistId(parse("""{"data":{"id":"7503"}}""")))
        assertEquals("7504", SodaPlaylistEdit.extractPlaylistId(parse("""{"playlist":{"id":"7504"}}""")))
        assertEquals("7505", SodaPlaylistEdit.extractPlaylistId(parse("""{"playlist_id":"7505"}""")))
        assertEquals("", SodaPlaylistEdit.extractPlaylistId(parse("""{"playlist":{}}""")))
    }

    @Test
    fun sensitiveWordHitInvertsIsPassed() {
        assertTrue(SodaPlaylistEdit.sensitiveWordHit(parse("""{"status_code":0,"is_passed":false}""")) == true)
        assertFalse(SodaPlaylistEdit.sensitiveWordHit(parse("""{"status_code":0,"is_passed":true}""")) == true)
        // 字段裹在 data 里也是官方用过的形状
        assertTrue(SodaPlaylistEdit.sensitiveWordHit(parse("""{"data":{"is_passed":false}}""")) == true)
    }

    @Test
    fun sensitiveWordErrorCodeIsNotAHit() {
        // 非 0 status_code 是接口错误，不能报成"命中敏感词"
        assertNull(SodaPlaylistEdit.sensitiveWordHit(parse("""{"status_code":1000004}""")))
        assertNull(SodaPlaylistEdit.sensitiveWordHit(null))
    }

    @Test
    fun cleanIdsTrimsAndDedupes() {
        assertEquals(listOf("a", "b"), SodaCollection.cleanIds(listOf(" a ", "a", "b", "  ")))
    }
}
