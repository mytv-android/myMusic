package com.mytv.mymusic.data.native

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 汽水收藏（「我喜欢的音乐」、收藏的歌单 / 专辑 / 艺人），逐条对齐 libresoda
 * `collection.rs`（AGPL-3.0，见 THIRD_PARTY_NOTICES.md）。
 *
 * | 能力 | 收藏 | 取消收藏 |
 * | --- | --- | --- |
 * | 单曲 | `POST /luna/pc/me/collection/media` | `.../media/delete` |
 * | 歌单 | `POST /luna/pc/me/collection/playlist` | `.../playlist/delete` |
 * | 专辑 | `POST /luna/pc/me/collection/album` | `.../album/delete` |
 * | 艺人 | `POST /luna/pc/me/collection/artist` | `.../artist/delete` |
 *
 * 读「我收藏的」走 `GET /luna/me/collection/mixed`，数组参数编成重复 key
 * （`item_types=album&item_types=playlist`），和官方客户端一致。
 *
 * 请求体形状（实测自官方客户端 `collect.ts`）：
 * ```json
 * { "scene": "", "media": [{ "type": "track", "id": "7501674235158431760" }] }
 * ```
 * 取消收藏时**没有** `scene` 字段。
 *
 * 这些路径在客户端里属「零信任加签」名单（bdticket 的 `session_guard`），
 * 但实测只需应用级签名（`x-helios` / `x-medusa`）就能写入；服务端哪天开始强制
 * 校验 ticket guard，只需在签名那一层补一个提供者，这里的形状不用改。
 */
internal object SodaCollection {
    const val COLLECT_MEDIA_PATH = "/luna/pc/me/collection/media"
    const val UNCOLLECT_MEDIA_PATH = "/luna/pc/me/collection/media/delete"
    const val COLLECT_PLAYLIST_PATH = "/luna/pc/me/collection/playlist"
    const val UNCOLLECT_PLAYLIST_PATH = "/luna/pc/me/collection/playlist/delete"
    const val COLLECT_ALBUM_PATH = "/luna/pc/me/collection/album"
    const val UNCOLLECT_ALBUM_PATH = "/luna/pc/me/collection/album/delete"
    const val COLLECT_ARTIST_PATH = "/luna/pc/me/collection/artist"
    const val UNCOLLECT_ARTIST_PATH = "/luna/pc/me/collection/artist/delete"

    /**
     * 「我收藏的」混合列表。客户端 IDL 同时登记了 PC / 非 PC 两条路径，实测
     * `/luna/pc/me/collection/mixed` 只回 `status_info` 空信封，所以用非 PC 这条。
     */
    const val COLLECTED_MIXED_PATH = "/luna/me/collection/mixed"

    /** 我收藏的艺人（客户端 `GetArtistCollection`）。 */
    const val ARTIST_COLLECTION_PATH = "/luna/me/collection/artist"

    /** 指定用户的收藏混合列表（传自己的 user_id 就是「我收藏的」）。 */
    const val USER_MIXED_PATH = "/luna/pc/user/collection/mixed"

    /** 已购 / 收藏的数字专辑。 */
    const val DIGITAL_ALBUMS_PATH = "/luna/pc/me/assets/albums"

    /** 列表接口的 count 上限。 */
    const val MAX_COUNT = 100
    const val DEFAULT_COUNT = 20

    /** `item_types` 的取值：`playable` 单曲 / `playlist` 歌单 / `album` 专辑 / `artist` 艺人。 */
    const val ITEM_PLAYABLE = "playable"
    const val ITEM_PLAYLIST = "playlist"
    const val ITEM_ALBUM = "album"
    const val ITEM_ARTIST = "artist"

    fun clampCount(count: Int): Int = if (count <= 0) DEFAULT_COUNT else count.coerceAtMost(MAX_COUNT)

    /** 收藏单曲：带 `scene`（客户端固定传空串）。 */
    fun collectMediaBody(ids: List<String>): String = buildJsonObject {
        put("scene", "")
        put("media", mediaArray(ids))
    }.toString()

    /** 取消收藏单曲：没有 `scene`。 */
    fun uncollectMediaBody(ids: List<String>): String = buildJsonObject {
        put("media", mediaArray(ids))
    }.toString()

    private fun mediaArray(ids: List<String>): JsonArray = buildJsonArray {
        ids.forEach { raw ->
            val id = raw.trim()
            if (id.isEmpty()) return@forEach
            add(buildJsonObject {
                put("id", id)
                put("type", "track")
            })
        }
    }

    /** 歌单 / 专辑 / 艺人的收藏与取消收藏都只是 id 数组，字段名不同。 */
    fun idsBody(field: String, ids: List<String>): String = buildJsonObject {
        put(field, buildJsonArray {
            ids.forEach { raw ->
                val id = raw.trim()
                if (id.isNotEmpty()) add(kotlinx.serialization.json.JsonPrimitive(id))
            }
        })
    }.toString()

    fun cleanIds(ids: List<String>): List<String> =
        ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 写一条播放记录（最近播放）也复用 `{media:[{id,type}]}` 这个形状。 */
    fun mediaOnlyBody(ids: List<String>): String = buildJsonObject {
        put("media", mediaArray(ids))
    }.toString()
}

/**
 * 「我收藏的」列表项。服务端每个条目只会填充其中一种类型，未建模的类型保留原始 JSON。
 * 空收藏时服务端**根本不返回** `mixed_collections` 字段，解析成空列表而不是失败。
 */
internal data class SodaCollectedItem(
    val kind: String = "",
    val id: String = "",
    val title: String = "",
    val cover: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val isTrack: Boolean get() = kind == SodaCollection.ITEM_PLAYABLE
    val isPlaylist: Boolean get() = kind == SodaCollection.ITEM_PLAYLIST
    val isAlbum: Boolean get() = kind == SodaCollection.ITEM_ALBUM
    val isArtist: Boolean get() = kind == SodaCollection.ITEM_ARTIST
}

internal object SodaCollectedParser {
    /**
     * 解析「我收藏的」。空列表（服务端只回 `status_info`）返回空列表——
     * 直接读原始 JSON 会把「没有收藏」误判成失败。
     */
    fun parse(root: JsonObject?): List<SodaCollectedItem> {
        val items = jsonObjList(root, "mixed_collections")
        return items.mapNotNull { entry -> parseItem(entry) }
    }

    private fun parseItem(entry: JsonObject): SodaCollectedItem? {
        // 官方客户端同款优先级：歌单 → 单曲 → 专辑 → 艺人
        val kind = when {
            jsonObj(entry, SodaCollection.ITEM_PLAYLIST) != null -> SodaCollection.ITEM_PLAYLIST
            jsonObj(entry, SodaCollection.ITEM_PLAYABLE) != null -> SodaCollection.ITEM_PLAYABLE
            jsonObj(entry, SodaCollection.ITEM_ALBUM) != null -> SodaCollection.ITEM_ALBUM
            jsonObj(entry, SodaCollection.ITEM_ARTIST) != null -> SodaCollection.ITEM_ARTIST
            else -> return null
        }
        val payload = jsonObj(entry, kind) ?: return null
        val title = listOf(
            jsonText(payload, "public_title"),
            jsonText(payload, "title"),
            jsonText(payload, "name"),
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        return SodaCollectedItem(
            kind = kind,
            id = jsonText(payload, "id"),
            title = title,
            cover = coverOf(payload),
            raw = payload,
        )
    }

    /** 封面：服务端给 `url_cover`（template_prefix + uri + urls），和搜索结果的图片同一个拼法。 */
    private fun coverOf(payload: JsonObject): String {
        val cover = jsonObj(payload, "url_cover") ?: return ""
        val prefix = jsonText(cover, "template_prefix").trim()
        val uri = jsonText(cover, "uri").trim()
        val urls = jsonStringList(cover, "urls")
        val base = urls.firstOrNull { it.isNotBlank() }.orEmpty()
        if (uri.isEmpty()) return base
        // 没有 urls 时退回官方图片域，至少能拿到一张图
        val host = base.ifEmpty { SodaProvider.DOUYIN_IMAGE_BASE_URL }
        return if (prefix.isEmpty()) "$host$uri" else "$host$uri~$prefix"
    }

    /** 我收藏的艺人列表：`artists` 数组，每项 `is_collected=true`。 */
    fun parseArtists(root: JsonObject?): List<SodaCollectedItem> =
        jsonObjList(root, "artists").mapNotNull { entry ->
            val id = jsonText(entry, "id")
            if (id.isEmpty()) return@mapNotNull null
            SodaCollectedItem(
                kind = SodaCollection.ITEM_ARTIST,
                id = id,
                title = listOf(jsonText(entry, "public_title"), jsonText(entry, "name"))
                    .firstOrNull { it.isNotBlank() }.orEmpty(),
                cover = coverOf(entry),
                raw = entry,
            )
        }
}
