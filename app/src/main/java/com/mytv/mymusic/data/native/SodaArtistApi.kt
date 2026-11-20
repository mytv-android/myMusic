package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.ArtistAlbumPage
import com.mytv.mymusic.data.ArtistDetail
import com.mytv.mymusic.data.ArtistProfile
import com.mytv.mymusic.data.AlbumSummary
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.normalizePublishTime
import kotlinx.serialization.json.JsonObject

/**
 * 汽水艺人页的三个接口，逐条对齐 libresoda `artist.rs`（AGPL-3.0，见 THIRD_PARTY_NOTICES.md）：
 *
 * | 能力 | 接口 |
 * | --- | --- |
 * | 艺人详情 | `GET /luna/pc/artists/{artist_id}` |
 * | 艺人单曲 | `GET /luna/pc/artists/{artist_id}/tracks` |
 * | 艺人专辑 | `GET /luna/pc/artists/{artist_id}/albums` |
 *
 * 上游只把回包当原始 JSON 交出去（字段名随版本变动），这里按官方客户端用过的字段名
 * 做了多层兜底解析：哪个键命中就用哪个，全都没命中时对应块留空，由界面决定隐藏。
 */
internal object SodaArtistApi {
    const val ARTIST_PATH = "/luna/pc/artists"

    const val MAX_COUNT = 100
    const val DEFAULT_COUNT = 20

    fun clampCount(count: Int): Int = if (count <= 0) DEFAULT_COUNT else count.coerceAtMost(MAX_COUNT)

    // ---------------- 详情 ----------------

    /** 详情节点：`artist_info` / `artist` / `data.artist_info` 都可能是它。 */
    fun detailNode(root: JsonObject?): JsonObject? {
        if (root == null) return null
        return jsonObj(root, "artist_info")
            ?: jsonObj(root, "artist")
            ?: jsonObj(jsonObj(root, "data"), "artist_info")
            ?: jsonObj(jsonObj(root, "data"), "artist")
    }

    fun profile(root: JsonObject?, artistId: String, source: String): ArtistProfile {
        val node = detailNode(root) ?: JsonObject(emptyMap())
        val id = listOf(jsonText(node, "id"), jsonText(node, "artist_id"), artistId)
            .firstOrNull { it.isNotBlank() }.orEmpty()
        val name = listOf(jsonText(node, "name"), jsonText(node, "public_name"))
            .firstOrNull { it.isNotBlank() }.orEmpty()
        val alias = listOf(
            jsonText(node, "alias"),
            jsonText(node, "english_name"),
        ).firstOrNull { it.isNotBlank() && it != name }.orEmpty()
        val description = listOf(
            jsonText(node, "description"),
            jsonText(node, "brief"),
            jsonText(node, "desc"),
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val avatar = listOf(
            jsonObj(node, "url_avatar"),
            jsonObj(node, "url_cover"),
            jsonObj(node, "avatar"),
        ).firstOrNull { it != null }?.let { imageUrl(it) }.orEmpty()
        return ArtistProfile(
            id = id,
            name = name,
            source = source,
            avatar = avatar,
            alias = alias,
            description = description,
            songCount = firstInt(node, "count_tracks", "track_count", "track_cnt", "tracks_count"),
            albumCount = firstInt(node, "count_albums", "album_count", "album_cnt", "albums_count"),
            mvCount = firstInt(node, "count_mvs", "mv_count", "count_video"),
            fans = firstLong(node, "count_fans", "fans_count", "count_collected", "fans"),
            facts = facts(node),
            link = "https://www.qishui.com/artist/$id",
        )
    }

    /** 详情页头部那几行「键：值」。服务端给什么就展示什么，不造。 */
    private fun facts(node: JsonObject): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val region = jsonText(node, "region").trim()
        if (region.isNotEmpty()) out += "地区" to region
        val birthday = jsonText(node, "birthday").trim()
        if (birthday.isNotEmpty()) out += "生日" to birthday
        val constellations = jsonText(node, "constellations").trim()
        if (constellations.isNotEmpty()) out += "星座" to constellations
        return out
    }

    // ---------------- 单曲 ----------------

    /**
     * 单曲列表：`tracks` / `hot_tracks` / `items` 都可能是它（可能在 `data` 下面）。
     * 每条用搜索侧同一个 [SodaTrack] DTO 解（未知字段忽略），解析不了的条目丢掉。
     */
    fun tracks(root: JsonObject?): List<SodaTrack> =
        firstArray(root, "tracks", "hot_tracks", "items").mapNotNull { entry -> decodeTrack(entry) }

    private fun decodeTrack(entry: JsonObject): SodaTrack? =
        runCatching { SodaJsonHolder.json.decodeFromString<SodaTrack>(entry.toString()) }.getOrNull()
            ?.takeIf { it.id.isNotEmpty() }

    // ---------------- 专辑 ----------------

    /** 专辑列表：`albums` / `items`；每项按官方客户端的字段名兜底。 */
    fun albums(root: JsonObject?): List<AlbumSummary> {
        val source = SodaProvider.SODA_PROVIDER_ID
        return firstArray(root, "albums", "items").mapNotNull { entry ->
            val id = jsonText(entry, "id")
            if (id.isEmpty()) return@mapNotNull null
            val name = jsonText(entry, "name")
            if (name.isEmpty()) return@mapNotNull null
            val cover = listOf(
                jsonObj(entry, "url_cover"),
                jsonObj(entry, "url_avatar"),
                jsonObj(entry, "cover"),
            ).firstOrNull { it != null }?.let { imageUrl(it, "~c5_300x300.jpg") }.orEmpty()
            val artists = jsonObjList(entry, "artists")
            AlbumSummary(
                id = id,
                name = name,
                source = source,
                cover = cover,
                artist = artists.firstOrNull()?.let { jsonText(it, "name") }.orEmpty(),
                artistId = artists.firstOrNull()?.let { jsonText(it, "id") }.orEmpty(),
                publishTime = normalizePublishTime(
                    jsonText(entry, "release_date").ifEmpty { jsonText(entry, "publish_time") },
                ),
                type = jsonText(entry, "album_type").ifEmpty { jsonText(entry, "type") },
                trackCount = firstInt(entry, "count_tracks", "track_count", "track_cnt"),
                company = jsonText(entry, "company"),
                description = jsonText(entry, "description").ifEmpty { jsonText(entry, "desc") },
                link = "https://www.qishui.com/album/$id",
            )
        }
    }

    /**
     * 按候选键找第一个非空数组。回包可能在顶层，也可能裹在 `data` 里，
     * 所以每个键都先试顶层再试 `data`。
     */
    fun firstArray(root: JsonObject?, vararg keys: String): List<JsonObject> {
        if (root == null) return emptyList()
        val data = jsonObj(root, "data")
        for (key in keys) {
            val direct = jsonObjList(root, key)
            if (direct.isNotEmpty()) return direct
            if (data != null) {
                val nested = jsonObjList(data, key)
                if (nested.isNotEmpty()) return nested
            }
        }
        return emptyList()
    }

    // ---------------- 分页 ----------------

    fun hasMore(root: JsonObject?): Boolean = jsonBool(root, "has_more") == true

    fun nextCursor(root: JsonObject?): String =
        jsonText(root, "next_cursor").ifEmpty { jsonText(root, "cursor") }

    fun total(root: JsonObject?): Int = firstInt(root, "total", "total_num", "total_count")

    // ---------------- 小工具 ----------------

    private fun firstInt(node: JsonObject?, vararg keys: String): Int =
        keys.firstOrNull { jsonInt(node, it) != null }?.let { jsonInt(node, it) } ?: 0

    private fun firstLong(node: JsonObject?, vararg keys: String): Long =
        keys.firstOrNull { jsonLong(node, it) != null }?.let { jsonLong(node, it) } ?: 0L

    /** 图片对象 → 地址。汽水的图都是 `template_prefix` + `uri` + `urls`。 */
    fun imageUrl(image: JsonObject, suffix: String = ""): String {
        val urls = jsonStringList(image, "urls")
        val base = urls.firstOrNull { it.isNotBlank() }.orEmpty()
        val uri = jsonText(image, "uri").trim()
        if (uri.isEmpty()) return base
        val prefix = jsonText(image, "template_prefix").trim()
        val host = base.ifEmpty { SodaProvider.DOUYIN_IMAGE_BASE_URL }
        val tail = when {
            suffix.isNotEmpty() -> suffix
            prefix.isNotEmpty() -> "~$prefix-resize:960:960.png"
            else -> ""
        }
        return host.trimEnd('/') + "/" + uri + tail
    }
}

/** 供本模块复用的 JSON 实例（和 SodaProvider 用的是同一份配置）。 */
internal object SodaJsonHolder {
    val json = NativeHttp.json
}

/** 组装歌手页要的结果，省得 Provider 里散着写。 */
internal fun sodaArtistDetail(
    root: JsonObject?,
    artistId: String,
    source: String,
    hotSongs: List<Song> = emptyList(),
): ArtistDetail = ArtistDetail(
    profile = SodaArtistApi.profile(root, artistId, source),
    hotSongs = hotSongs,
)

internal fun sodaArtistAlbumPage(root: JsonObject?, albums: List<AlbumSummary>): ArtistAlbumPage =
    ArtistAlbumPage(
        albums = albums,
        total = SodaArtistApi.total(root),
        hasMore = SodaArtistApi.hasMore(root),
    )
