package com.mytv.mymusic.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 歌手主页的资料。各平台给的字段不一样，拿不到的留空 / 0，界面按有没有决定显示哪一块。
 * [avatar] 尽量给大图（≥ 500px），手机歌手页把它铺满整个头部。
 */
data class ArtistProfile(
    val id: String,
    val name: String,
    val source: String,
    val avatar: String = "",
    /** 外文名 / 别名，显示在名字下面一行。 */
    val alias: String = "",
    /** 简介纯文本，段落之间用换行分隔；HTML 实体和标签要在平台层去掉。 */
    val description: String = "",
    val songCount: Int = 0,
    val albumCount: Int = 0,
    val mvCount: Int = 0,
    val fans: Long = 0,
    /** 生日、地区、出道时间这类“键：值”，按平台给的顺序。 */
    val facts: List<Pair<String, String>> = emptyList(),
    val link: String = "",
)

/** 歌手详情接口的结果。[hotSongs] 是详情接口顺带给的热门歌曲（网易云 50 首），没有就空。 */
data class ArtistDetail(
    val profile: ArtistProfile,
    val hotSongs: List<Song> = emptyList(),
)

/**
 * 专辑条目：歌手页的专辑网格、专辑页头部共用。
 * [publishTime] 统一成 `yyyy-MM-dd`（只知道年份时是 `yyyy`），见 [normalizePublishTime]。
 */
data class AlbumSummary(
    val id: String,
    val name: String,
    val source: String,
    val cover: String = "",
    val artist: String = "",
    /** 主歌手的平台 id（QQ 是 singer mid）；专辑页“更多专辑”和歌手入口靠它，拿不到就按名字搜。 */
    val artistId: String = "",
    val publishTime: String = "",
    /** 平台给的类型：专辑 / EP / Single / 录音室专辑 / 现场…… */
    val type: String = "",
    val trackCount: Int = 0,
    val company: String = "",
    val description: String = "",
    val link: String = "",
) {
    val year: String get() = publishTime.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()

    fun toPlaylist(): Playlist = Playlist(
        id = id,
        name = name,
        cover = cover,
        trackCount = trackCount,
        creator = artist,
        description = description,
        source = source,
        link = link,
        extra = buildMap {
            put("type", "album")
            if (company.isNotBlank()) put("company", company)
            if (publishTime.isNotBlank()) put("publish_time", publishTime)
            if (artistId.isNotBlank()) put("artist_id", artistId)
            if (type.isNotBlank()) put("album_type", type)
        },
    )
}

/** 歌手专辑的一页。[total] 平台不给时为 0。 */
data class ArtistAlbumPage(
    val albums: List<AlbumSummary>,
    val total: Int = 0,
    val hasMore: Boolean = false,
)

/** 专辑详情：头部资料 + 曲目。 */
data class AlbumDetail(
    val album: AlbumSummary,
    val songs: List<Song>,
)

/**
 * 平台专辑详情里的 Playlist（extra 带 company / publish_time / artist_id / album_type）转成 [AlbumSummary]。
 * 各平台 fetchAlbumDetail 已经返回这种 Playlist，albumDetail 直接拿它转。
 */
fun Playlist.toAlbumSummary(): AlbumSummary = AlbumSummary(
    id = id,
    name = name,
    source = source,
    cover = cover,
    artist = creator,
    artistId = extra["artist_id"].orEmpty(),
    publishTime = normalizePublishTime(extra["publish_time"].orEmpty()),
    type = extra["album_type"].orEmpty(),
    trackCount = trackCount,
    company = extra["company"].orEmpty(),
    description = description,
    link = link,
)

private val Ymd = Regex("""^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})""")
private val YearOnly = Regex("""^(\d{4})(?:\D|$)""")

/**
 * 发行时间统一成 `yyyy-MM-dd`：毫秒 / 秒时间戳、`2026-03-25 00:00:00`、`2026/3/5`、`2026年3月25日` 都认；
 * 只有年份的给 `yyyy`；认不出来、0、负数给空串。时间戳按东八区换算（国内平台的发行日是北京时间零点）。
 */
fun normalizePublishTime(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty() || text == "0") return ""
    text.toLongOrNull()?.let { number ->
        if (number <= 0) return ""
        // 4 位数字是年份，不是时间戳
        if (text.length == 4) return text
        val millis = if (number < 100_000_000_000L) number * 1000 else number
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("GMT+08:00") }
        return format.format(Date(millis))
    }
    Ymd.find(text)?.let { match ->
        val (year, month, day) = match.destructured
        return "%s-%02d-%02d".format(Locale.ROOT, year, month.toInt(), day.toInt())
    }
    YearOnly.find(text)?.let { return it.groupValues[1] }
    return ""
}
