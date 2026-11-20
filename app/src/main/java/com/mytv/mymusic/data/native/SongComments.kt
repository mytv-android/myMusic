package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.SongComment
import com.mytv.mymusic.data.SongCommentPage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal suspend fun neteaseSongComments(cookie: String, song: Song, page: Int, limit: Int): SongCommentPage {
    val songId = song.extra["song_id"]?.takeIf { it.isNotBlank() } ?: song.id
    if (songId.isBlank()) throw IllegalArgumentException("netease song id is empty")
    val safePage = page.coerceAtLeast(1)
    val safeLimit = limit.coerceIn(1, 50)
    // 网页公开读接口；这里不需要 weapi 加密，匿名与登录 Cookie 都可读。
    val response = NativeHttp.postForm(
        "https://music.163.com/api/v1/resource/comments/R_SO_4_$songId",
        form = mapOf(
            "rid" to songId,
            "limit" to safeLimit.toString(),
            "offset" to ((safePage - 1) * safeLimit).toString(),
            "beforeTime" to "0",
        ),
        headers = mapOf("Referer" to "https://music.163.com/song?id=$songId"),
        cookie = cookie,
    )
    return parseNeteaseComments(response.body, safePage, safeLimit)
}

internal fun parseNeteaseComments(body: String, page: Int, limit: Int): SongCommentPage {
    val root = NativeHttp.json.parseToJsonElement(body).jsonObject
    val code = root["code"].primitiveLong()
    if (code != 200L) throw IllegalStateException("netease comment api error: $code")
    fun comments(key: String): List<SongComment> =
        root[key].arrayObjects().mapNotNull { item ->
            val content = item["content"].primitiveText().trim()
            if (content.isEmpty()) return@mapNotNull null
            val user = item["user"] as? JsonObject
            SongComment(
                id = item["commentId"].primitiveText(),
                userName = user?.get("nickname").primitiveText().ifBlank { "网易云用户" },
                avatar = user?.get("avatarUrl").primitiveText().https(),
                content = content,
                likedCount = item["likedCount"].primitiveLong(),
                liked = item["liked"].primitiveBoolean(),
                time = item["time"].primitiveLong(),
                timeText = item["timeStr"].primitiveText(),
                location = (item["ipLocation"] as? JsonObject)?.get("location").primitiveText(),
            ).takeIf { it.id.isNotBlank() }
        }
    val comments = comments("comments")
    val total = root["total"].primitiveLong()
    return SongCommentPage(
        page = page,
        comments = comments,
        hotComments = if (page == 1) comments("hotComments") else emptyList(),
        total = total,
        hasMore = root["more"].primitiveBoolean() || (page * limit < total && comments.size >= limit),
    )
}

internal fun parseQqComments(body: String, page: Int, limit: Int): SongCommentPage {
    val root = NativeHttp.json.parseToJsonElement(body).jsonObject
    val code = root["code"].primitiveLong()
    val subcode = root["subcode"].primitiveLong()
    if (code != 0L || subcode != 0L) throw IllegalStateException("qq comment api error: $code/$subcode")
    fun comments(key: String): List<SongComment> {
        val group = root[key] as? JsonObject ?: return emptyList()
        return group["commentlist"].arrayObjects().mapNotNull { item ->
            val content = item["rootcommentcontent"].primitiveText().trim()
            if (content.isEmpty()) return@mapNotNull null
            SongComment(
                id = item["commentid"].primitiveText(),
                userName = item["nick"].primitiveText().ifBlank { "QQ 音乐用户" },
                avatar = item["avatarurl"].primitiveText().https(),
                content = content,
                likedCount = item["praisenum"].primitiveLong(),
                liked = item["ispraise"].primitiveLong() == 1L,
                // QQ 这一接口给的是 Unix 秒；UI 统一按毫秒处理。
                time = item["time"].primitiveLong() * 1000,
            )
        }
    }
    val comments = comments("comment")
    val commentGroup = root["comment"] as? JsonObject
    val total = commentGroup?.get("commenttotal").primitiveLong()
    val more = root["morecomment"].primitiveLong() == 1L
    return SongCommentPage(
        page = page,
        comments = comments,
        hotComments = if (page == 1) comments("hot_comment") else emptyList(),
        total = total,
        hasMore = more || (comments.size >= limit && (total <= 0L || page * limit < total)),
    )
}

private fun kotlinx.serialization.json.JsonElement?.arrayObjects(): List<JsonObject> =
    (this as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun kotlinx.serialization.json.JsonElement?.primitiveText(): String =
    (this as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun kotlinx.serialization.json.JsonElement?.primitiveLong(): Long =
    (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L

private fun kotlinx.serialization.json.JsonElement?.primitiveBoolean(): Boolean =
    (this as? JsonPrimitive)?.booleanOrNull == true

private fun String.https(): String = if (startsWith("http://")) "https://" + removePrefix("http://") else this
