package com.mytv.mymusic.data.transfer

import com.mytv.mymusic.data.sync.LOCAL_TRACK_PROVIDER
import com.mytv.mymusic.data.sync.SyncTime
import com.mytv.mymusic.data.sync.SyncTrackRefs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/** 读出来的 myMusic 歌单文件；[dropped] 和 playlists 一一对应，是每个歌单里格式不对、丢掉的条目数。 */
data class DecodedPlaylists(
    val file: MyMusicPlaylistFile,
    val newerVersion: Boolean,
    val dropped: List<Int>,
)

/** myMusic 歌单文件编解码（明文 JSON、UTF-8 无 BOM）。 */
object MyMusicPlaylistCodec {
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    fun encode(file: MyMusicPlaylistFile): String = json.encodeToString(file)

    /**
     * [root] 的 type 不是 [PLAYLIST_FILE_TYPE] 时返回 null，交给下一个识别器。更新版本的文件照样读，只读认识的字段；
     * 单首坏了（缺 provider / id、类型不对）只丢那一首，整份结构不对（playlists 不是数组）抛 [IllegalArgumentException]。
     */
    fun decode(root: JsonObject): DecodedPlaylists? {
        if (root.string("type") != PLAYLIST_FILE_TYPE) return null
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: PLAYLIST_FILE_VERSION
        val rawLists = root["playlists"] as? JsonArray ?: throw IllegalArgumentException("歌单文件结构不对")
        val playlists = mutableListOf<PortablePlaylist>()
        val dropped = mutableListOf<Int>()
        for (element in rawLists) {
            val obj = element as? JsonObject ?: continue
            var bad = 0
            val tracks = (obj["tracks"] as? JsonArray).orEmpty().mapNotNull { raw ->
                val track = runCatching { json.decodeFromJsonElement<PortableTrack>(raw) }.getOrNull()
                if (track == null || track.provider.isBlank() || track.id.isBlank()) {
                    bad++
                    null
                } else {
                    track
                }
            }
            val source = (obj["source"] as? JsonObject)?.let { runCatching { json.decodeFromJsonElement<PortableSource>(it) }.getOrNull() }
            playlists += PortablePlaylist(
                name = obj.string("name").orEmpty().trim().ifBlank { "未命名歌单" }.take(MAX_NAME_LENGTH),
                kind = obj.string("kind")?.takeIf { it == KIND_FAVORITES } ?: KIND_PLAYLIST,
                createdAt = obj.string("createdAt"),
                source = source?.takeIf { it.provider.isNotBlank() && it.id.isNotBlank() },
                tracks = tracks,
            )
            dropped += bad
        }
        return DecodedPlaylists(
            file = MyMusicPlaylistFile(
                version = version,
                exportedAt = root.string("exportedAt").orEmpty(),
                app = root.string("app").orEmpty(),
                playlists = playlists,
            ),
            newerVersion = version > PLAYLIST_FILE_VERSION,
            dropped = dropped,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    /** 歌单名上限，和洛雪的 clipNameLength 一样。 */
    const val MAX_NAME_LENGTH = 80
}

/** 把选好的歌单写成一个 myMusic 歌单文件。 */
object PlaylistExport {
    /** 单个歌单 `<名字>.mymusic.json`，多个 `myMusic 歌单 <N> 个.mymusic.json`。 */
    fun fileName(lists: List<ExportList>): String =
        if (lists.size == 1) safeFileName(lists.first().name) + PLAYLIST_FILE_SUFFIX
        else "myMusic 歌单 ${lists.size} 个$PLAYLIST_FILE_SUFFIX"

    fun title(lists: List<ExportList>): String =
        if (lists.size == 1) lists.first().name else "${lists.size} 个歌单"

    /** 同一个歌单里按平台 + id 去重（导出、计数都按它）。 */
    fun distinctSongs(list: ExportList) = list.songs.distinctBy { "${it.source}\u0000${it.id}" }

    fun counts(lists: List<ExportList>): ExportCounts {
        val kept = lists.flatMap(::distinctSongs)
        return ExportCounts(
            total = lists.sumOf { it.songs.size + it.unreadable },
            kept = kept.size,
            byProvider = kept.groupingBy { it.source }.eachCount(),
            local = kept.count { it.source == LOCAL_TRACK_PROVIDER },
        )
    }

    fun preview(lists: List<ExportList>): ExportPreview =
        ExportPreview(title = title(lists), lists = lists, counts = counts(lists), fileName = fileName(lists))

    /** [refs] 是本地歌的线索，按 Song.key 查；[app] 写进文件只作排查。 */
    fun build(
        lists: List<ExportList>,
        now: Long,
        app: String = "",
        refs: Map<String, SyncTrackRefs> = emptyMap(),
    ): ExportFile {
        val file = MyMusicPlaylistFile(
            exportedAt = SyncTime.toIso(now),
            app = app,
            playlists = lists.map { list ->
                PortablePlaylist(
                    name = list.name,
                    kind = if (list.favorites) KIND_FAVORITES else KIND_PLAYLIST,
                    createdAt = list.createdAt.takeIf { it > 0 }?.let(SyncTime::toIso),
                    source = list.source,
                    tracks = distinctSongs(list).map { song ->
                        PortableTrack.of(song, if (song.source == LOCAL_TRACK_PROVIDER) refs[song.key] else null)
                    },
                )
            },
        )
        return ExportFile(
            fileName = fileName(lists),
            mime = PLAYLIST_FILE_MIME,
            bytes = MyMusicPlaylistCodec.encode(file).toByteArray(Charsets.UTF_8),
            counts = counts(lists),
        )
    }
}

private val forbiddenFileChars = Regex("""[\\/:*?#"<>|\p{Cntrl}]""")

/** 文件名里去掉 `\ / : * ? # " < > |` 和控制字符，首尾空白去掉，最长 150 字符，空了用「歌单」。 */
internal fun safeFileName(name: String): String {
    val cleaned = name.replace(forbiddenFileChars, "").trim().trimEnd('.').trim()
    return cleaned.take(MAX_FILE_NAME_LENGTH).trim().ifBlank { "歌单" }
}

/** 和洛雪 filterFileName 一样的上限。 */
internal const val MAX_FILE_NAME_LENGTH = 150
