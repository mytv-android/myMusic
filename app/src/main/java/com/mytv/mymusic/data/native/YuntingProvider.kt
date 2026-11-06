package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate

private const val YUNTING_ID = "yunting"
private const val API_BASE = "https://ytmsout.radio.cn"
private const val STATION_PAGE = "https://www.radio.cn/pc-portal/erji/radioStation.html"
private const val LIVE_PREFIX = "live:"
private const val REPLAY_PREFIX = "rp:"

/** 所有电台的直播 HLS 都在这个域下，播放层据此让直播流绕开磁盘缓存。 */
internal const val YUNTING_LIVE_HOST = "ytcast2.radio.cn"

/** 网页版自己用的签名常量（pc-portal/js/api.js），不是账号密钥。 */
private const val SIGN_KEY = "f0fc4c668392f9f9a447e48584c214ee"

private val YUNTING_INFO = ProviderInfo(
    id = YUNTING_ID,
    name = "云听",
    recommendedPlaylists = true,
    playlistCategories = true,
    playlistSongs = true,
    parsePlaylist = true,
    note = "直播电台 + 央广当天回听，无搜索",
)

/**
 * 云听（radio.cn）：一个电台就是一个歌单。
 * 第一条曲目是直播（HLS，没有时长），后面是央广台当天的回听节目（m4a，可拖动）。
 */
class YuntingProvider : BaseNativeProvider(YUNTING_INFO) {
    private val json = NativeHttp.json

    override suspend fun recommendedPlaylists(cookie: String): List<Playlist> =
        stations(categoryId = "0", provinceCode = "0").map { it.toPlaylist("0") }

    override suspend fun categories(cookie: String): List<PlaylistCategory> {
        val types = runCatching { categoriesRaw() }.getOrDefault(emptyList())
        val provinces = runCatching { provincesRaw() }.getOrDefault(emptyList())
        return types.filter { it.id != "0" }.map {
            PlaylistCategory(id = "c${it.id}", name = it.categoryName, group = "类型", source = YUNTING_ID)
        } + provinces.map {
            PlaylistCategory(
                id = "p${it.provinceCode}",
                // 省级编码 0 是央广系列，名字直接标「央广」比「国家」清楚
                name = if (it.provinceCode == 0L) "央广" else it.provinceName,
                group = "地区",
                source = YUNTING_ID,
            )
        }
    }

    override suspend fun categoryPlaylists(cookie: String, id: String, page: Int, limit: Int): List<Playlist> {
        if (page > 1) return emptyList()
        val (categoryId, provinceCode) = splitCategoryId(id)
        return stations(categoryId, provinceCode).map { it.toPlaylist(provinceCode) }
    }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> {
        val (contentId, provinceCode) = splitStationId(id)
        val station = stations("0", provinceCode).firstOrNull { it.contentId == contentId }
            ?: throw IllegalStateException("这个电台不在云听的列表里：$contentId")
        return buildList {
            add(station.liveSong(provinceCode))
            addAll(station.replaySongs(provinceCode))
        }
    }

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        val contentId = STATION_ID.find(link)?.groupValues?.get(1)
            ?: link.substringBefore('@').takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
            ?: throw IllegalArgumentException("invalid yunting station link")
        val provinceCode = STATION_PROVINCE.find(link)?.groupValues?.get(1)
            ?: link.substringAfter('@', "").takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
            ?: "0"
        val station = stations("0", provinceCode).firstOrNull { it.contentId == contentId }
            ?: throw IllegalArgumentException("找不到这个电台：$contentId")
        val playlist = station.toPlaylist(provinceCode)
        return CollectionResult(playlist, playlistSongs(cookie, playlist.id))
    }

    /**
     * 直播地址里的 token 是签发时刻生成的，队列里放久了会失效：
     * 播放前按省重新拉一次列表换新地址，拉不到就退回原地址再试。
     */
    override suspend fun playableStream(cookie: String, song: Song, workDir: File): PlayableStream {
        if (song.extra["live"] != "1") {
            return PlayableStream(url = song.url, ext = song.ext.ifBlank { "m4a" })
        }
        val contentId = song.extra["content_id"] ?: song.id.removePrefix(LIVE_PREFIX)
        val provinceCode = song.extra["province"] ?: "0"
        val fresh = runCatching { stations("0", provinceCode) }
            .getOrDefault(emptyList())
            .firstOrNull { it.contentId == contentId }
            ?.streamUrl()
            .orEmpty()
        val url = fresh.ifBlank { song.url }
        if (url.isBlank()) throw IllegalStateException("直播地址为空：${song.name}")
        return PlayableStream(url = url, ext = "m3u8")
    }

    // ---------------- 接口 ----------------

    private suspend fun categoriesRaw(): List<YtCategory> =
        call<YtCategory>("/web/appCategory/list/all", mapOf("t" to "1"))

    private suspend fun provincesRaw(): List<YtProvince> =
        call<YtProvince>("/web/appProvince/list/all", mapOf("t" to "1"))

    private suspend fun stations(categoryId: String, provinceCode: String): List<YtBroadcast> =
        call<YtBroadcast>(
            "/web/appBroadcast/list",
            mapOf("categoryId" to categoryId, "provinceCode" to provinceCode),
        )

    private suspend fun listenBackIds(): Set<String> =
        call<YtListenBack>("/web/appBroadcast/listenbackList", mapOf("provinceCode" to ""))
            .mapNotNull { it.id.takeIf { id -> id.isNotBlank() } }
            .toSet()

    private suspend fun programs(contentId: String): List<YtProgram> =
        call<YtProgram>(
            "/web/appProgram/listByDate",
            mapOf("date" to LocalDate.now().toString(), "broadcastId" to contentId),
        )

    private suspend inline fun <reified T> call(path: String, params: Map<String, String>): List<T> {
        val timestamp = System.currentTimeMillis()
        val query = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val sign = md5("${sortedParams(params)}&timestamp=$timestamp&key=$SIGN_KEY").uppercase()
        val url = buildString {
            append(API_BASE).append(path)
            if (query.isNotEmpty()) append('?').append(query)
        }
        val body = NativeHttp.get(
            url,
            headers = mapOf(
                "Content-Type" to "application/json",
                "equipmentId" to "0000",
                "platformCode" to "WEB",
                "timestamp" to timestamp.toString(),
                "sign" to sign,
            ),
        )
        val envelope = json.decodeFromString<YtEnvelope<T>>(body)
        if (envelope.code != 0) {
            throw IllegalStateException(envelope.message.ifBlank { "云听接口返回 ${envelope.code}" })
        }
        return envelope.data.orEmpty()
    }

    private fun sortedParams(params: Map<String, String>): String =
        params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---------------- 映射 ----------------

    private fun splitCategoryId(id: String): Pair<String, String> = when {
        id.startsWith("c") -> id.drop(1) to "0"
        id.startsWith("p") -> "0" to id.drop(1)
        else -> "0" to "0"
    }

    private fun splitStationId(id: String): Pair<String, String> =
        id.substringBefore('@') to id.substringAfter('@', "0")

    private fun YtBroadcast.toPlaylist(provinceCode: String): Playlist = Playlist(
        id = "$contentId@$provinceCode",
        name = title,
        cover = image,
        // 一个电台只有一条直播曲目，卡片副标题留给「正在直播：…」
        trackCount = 0,
        creator = liveCaption(),
        description = subtitle,
        source = YUNTING_ID,
        link = stationLink(contentId, provinceCode),
        extra = mapOf("content_id" to contentId, "province" to provinceCode),
    )

    private fun YtBroadcast.liveCaption(): String {
        val now = subtitle.removePrefix("正在直播：").trim()
        return if (now.isBlank() || now == "暂无节目单") "直播中" else "正在直播：$now"
    }

    private fun YtBroadcast.liveSong(provinceCode: String): Song {
        val now = subtitle.removePrefix("正在直播：").trim().takeIf { it.isNotBlank() && it != "暂无节目单" }
        val url = streamUrl()
        return Song(
            id = LIVE_PREFIX + contentId,
            name = if (now != null) "直播 · $now" else "直播 · $title",
            artist = title,
            duration = 0,
            source = YUNTING_ID,
            url = url,
            ext = "m3u8",
            cover = image,
            link = stationLink(contentId, provinceCode),
            extra = buildMap {
                put("live", "1")
                put("content_id", contentId)
                put("province", provinceCode)
                put("station", title)
                now?.let { put("program", it) }
            },
        )
    }

    private suspend fun YtBroadcast.replaySongs(provinceCode: String): List<Song> {
        // 只有央广系列有回听；省级列表返回空，这里多一次请求就能省掉全部省级电台的无效调用
        val canReplay = runCatching { contentId in listenBackIds() }.getOrDefault(false)
        if (!canReplay) return emptyList()
        val list = runCatching { programs(contentId) }.getOrDefault(emptyList())
        return list.mapNotNull { program ->
            val url = program.playUrlHigh?.takeIf { it.isNotBlank() }
                ?: program.playUrlLow?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val seconds = ((program.endTime - program.startTime) / 1000).toInt().coerceAtLeast(0)
            Song(
                id = REPLAY_PREFIX + program.id,
                name = program.programName,
                artist = title,
                album = "回听",
                duration = seconds,
                source = YUNTING_ID,
                url = url,
                ext = "m4a",
                cover = program.image?.takeIf { it.isNotBlank() } ?: image,
                link = stationLink(contentId, provinceCode),
                extra = buildMap {
                    put("replay", "1")
                    put("content_id", contentId)
                    put("program_id", program.id)
                    put("start", program.startTime.toString())
                    put("end", program.endTime.toString())
                    put("province", provinceCode)
                },
            )
        }
    }

    private fun YtBroadcast.streamUrl(): String =
        playUrlMulti?.takeIf { it.isNotBlank() }
            ?: mp3PlayUrlHigh?.takeIf { it.isNotBlank() }
            ?: mp3PlayUrlLow?.takeIf { it.isNotBlank() }
            ?: playUrlLow.orEmpty()

    private fun stationLink(contentId: String, provinceCode: String): String =
        "$STATION_PAGE?id=$contentId&province=$provinceCode"

    private companion object {
        val STATION_ID = Regex("""[?&]id=(\d+)""")
        val STATION_PROVINCE = Regex("""[?&]province=(\d+)""")
    }
}

@Serializable
private data class YtEnvelope<T>(
    val code: Int = -1,
    val message: String = "",
    val data: List<T>? = null,
)

@Serializable
private data class YtCategory(
    val id: String = "",
    val categoryName: String = "",
)

@Serializable
private data class YtProvince(
    val provinceCode: Long = 0,
    val provinceName: String = "",
)

@Serializable
private data class YtBroadcast(
    val contentId: String = "",
    val title: String = "",
    val subtitle: String = "",
    val image: String = "",
    val playUrlLow: String? = null,
    val mp3PlayUrlLow: String? = null,
    @SerialName("mp3PlayUrlHigh") val mp3PlayUrlHigh: String? = null,
    val playUrlMulti: String? = null,
)

@Serializable
private data class YtListenBack(
    val id: String = "",
    val broadcastName: String = "",
)

@Serializable
private data class YtProgram(
    val id: String = "",
    val columnId: String = "",
    val broadcastId: String = "",
    val programName: String = "",
    val startTime: Long = 0,
    val endTime: Long = 0,
    val playUrlLow: String? = null,
    val playUrlHigh: String? = null,
    val image: String? = null,
    val enableStatus: Int = 0,
    val columnEnableStatus: Int = 0,
)
