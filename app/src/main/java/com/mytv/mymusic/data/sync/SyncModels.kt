package com.mytv.mymusic.data.sync

import com.mytv.mymusic.data.PlaylistEntryEntity
import com.mytv.mymusic.data.PlaylistEntity
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.SavedSongTombstone
import com.mytv.mymusic.data.Song
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

const val SCHEMA = "mymusic.library"
/**
 * 3：本地歌多了 local / remote / match 三组线索（设计稿第 08 节）。旧版本读到 3 会停在“请先更新应用”，
 * 这是有意的：旧客户端不认识这几个字段，写回时会把别的设备记下的云端位置和匹配结果丢掉。
 */
const val SCHEMA_VERSION = 3

@Serializable
data class SyncSnapshot(
    val schema: String = SCHEMA,
    val version: Int = SCHEMA_VERSION,
    val deviceId: String = "",
    val updatedAt: String = SyncTime.nowIso(),
    val playlists: List<SyncPlaylist> = emptyList(),
    val favorites: List<SyncFavorite> = emptyList(),
    val cookies: List<SyncCookie> = emptyList(),
)

@Serializable
data class SyncPlaylist(
    val id: String,
    val name: String,
    val createdAt: String = SyncTime.nowIso(),
    val updatedAt: String = SyncTime.nowIso(),
    val deletedAt: String? = null,
    val tracks: List<SyncTrack> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SyncTrack(
    val key: String,
    val provider: String = "",
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSec: Int = 0,
    val cover: String = "",
    // v3：只有本地歌（key 是 local:sha256:…）才有；平台歌是 null，不写进文件，免得每首多十几个字节
    @EncodeDefault(EncodeDefault.Mode.NEVER) val local: SyncLocal? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val remote: SyncRemote? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val match: SyncMatch? = null,
) {
    val hasRefs: Boolean get() = local != null || remote != null || match != null

    fun toSong(): Song = Song(
        id = id,
        name = title,
        artist = artist,
        album = album,
        duration = durationSec,
        source = provider,
        cover = cover,
    )
}

/**
 * 本地文件的线索。[hash] 就是本地身份（首尾各 64KB + 大小的指纹），和 key 去掉 `local:` 一样；
 * [device] 是文件所在设备的名字（例如“Pixel 8”），哪里都放不了时界面写“只在 Pixel 8 上”。
 * 不放文件路径：路径只在那台设备上有意义，还会暴露目录结构。
 */
@Serializable
data class SyncLocal(
    val hash: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val device: String = "",
) {
    val isEmpty: Boolean get() = hash.isBlank() && fileName.isBlank() && fileSize <= 0 && device.isBlank()
}

/**
 * 云端副本的位置（规格 3.2）：provider 是 netease_cloud / qq_cloud / webdav。
 * 只记位置，不记播放地址、账号和密码；另一台设备用自己登录的账号去取。
 * status 只同步 ready 和 deleted：上传中、失败是这台设备自己的事，404 也只记在本机（换了账号也会 404）。
 */
@Serializable
data class SyncRemote(
    val provider: String,
    val id: String,
    val playableId: String = "",
    val status: String = "ready",
    val uploadedAt: String = "",
)

/** 匹配结果（规格 3.3）：只记平台和 ID，另一台设备按它现取封面和歌词，不同步歌词正文。 */
@Serializable
data class SyncMatch(
    val provider: String,
    val id: String,
    val confidence: Float = 0f,
    /** tag / audio / manual；用户在「换一个匹配」里选的置信度记 1，合并时总能盖过自动匹配。 */
    val method: String = "",
    val matchedAt: String = "",
)

/** 建快照时一首本地歌要带上的线索；[cover] 是能公开的封面网址（匹配到的平台封面），本机封面缓存不算。 */
data class SyncTrackRefs(
    val local: SyncLocal? = null,
    val remote: SyncRemote? = null,
    val match: SyncMatch? = null,
    val cover: String = "",
)

/** 收藏歌曲；deletedAt 非空表示取消收藏的墓碑。 */
@Serializable
data class SyncFavorite(
    val key: String,
    val addedAt: String = SyncTime.nowIso(),
    val deletedAt: String? = null,
    val track: SyncTrack? = null,
)

/** 平台账号 Cookie；deletedAt 非空表示退出登录的墓碑。 */
@Serializable
data class SyncCookie(
    val provider: String,
    val value: String = "",
    val updatedAt: String = SyncTime.nowIso(),
    val deletedAt: String? = null,
)

fun SyncFavorite.changeTimeMillis(): Long = SyncTime.toMillis(deletedAt ?: addedAt)

fun SyncCookie.changeTimeMillis(): Long =
    maxOf(SyncTime.toMillis(updatedAt), SyncTime.toMillis(deletedAt))

object SyncJson {
    val format = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(snapshot: SyncSnapshot): String = format.encodeToString(snapshot)

    fun decode(raw: String): SyncSnapshot {
        val snapshot = runCatching { format.decodeFromString<SyncSnapshot>(raw) }
            .getOrElse { throw SyncException(SyncFailureKind.Corrupt, "同步文件无法读取，本机数据没有改动。") }
        if (snapshot.schema != SCHEMA) {
            throw SyncException(SyncFailureKind.Corrupt, "同步文件格式不受支持：${snapshot.schema}")
        }
        if (snapshot.version > SCHEMA_VERSION) {
            throw SyncException(SyncFailureKind.Corrupt, "同步文件版本高于当前应用，请先更新应用。")
        }
        return snapshot
    }

    fun encodePlaylist(playlist: SyncPlaylist?): String =
        if (playlist == null) "null" else format.encodeToString(playlist)

    fun decodePlaylist(raw: String): SyncPlaylist? =
        if (raw == "null") null else runCatching { format.decodeFromString<SyncPlaylist>(raw) }.getOrNull()
}

object SyncTime {
    fun nowIso(): String = Instant.ofEpochMilli(System.currentTimeMillis()).toString()

    fun toIso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    fun toMillis(value: String?): Long = runCatching { Instant.parse(value.orEmpty()).toEpochMilli() }.getOrDefault(0L)
}

fun buildSnapshot(
    playlists: List<PlaylistEntity>,
    entries: List<PlaylistEntryEntity>,
    deviceId: String,
    includeDeleted: Boolean = true,
    favorites: List<SavedSong> = emptyList(),
    favoriteTombstones: List<SavedSongTombstone> = emptyList(),
    cookies: Map<String, String> = emptyMap(),
    cookieUpdatedAt: Map<String, Long> = emptyMap(),
    /** 本地歌的线索，按 Song.key（local:sha256:…）查；平台歌不在里面。 */
    localRefs: Map<String, SyncTrackRefs> = emptyMap(),
): SyncSnapshot {
    val entriesByPlaylist = entries.groupBy { it.playlistId }
    return SyncSnapshot(
        deviceId = deviceId,
        playlists = playlists
            .filter { includeDeleted || it.deletedAt == null }
            .map { playlist ->
                val tracks = if (playlist.deletedAt != null) emptyList() else {
                    entriesByPlaylist[playlist.id].orEmpty()
                        .sortedBy { it.position }
                        .mapNotNull { entry -> entry.toSyncTrack(localRefs) }
                }
                SyncPlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    createdAt = SyncTime.toIso(playlist.createdAt),
                    updatedAt = SyncTime.toIso(playlist.updatedAt),
                    deletedAt = playlist.deletedAt?.let(SyncTime::toIso),
                    tracks = tracks,
                )
            },
        favorites = favorites.mapNotNull { it.toSyncFavorite(localRefs) } +
            favoriteTombstones
                .filter { tombstone -> favorites.none { it.key == tombstone.key } }
                .filter { includeDeleted }
                .map { tombstone ->
                    SyncFavorite(
                        key = tombstone.key,
                        addedAt = SyncTime.toIso(tombstone.deletedAt),
                        deletedAt = SyncTime.toIso(tombstone.deletedAt),
                    )
                },
        cookies = cookies.entries
            .map { (provider, value) ->
                SyncCookie(
                    provider = provider,
                    value = value,
                    updatedAt = SyncTime.toIso(cookieUpdatedAt[provider] ?: 0L),
                )
            } + cookieUpdatedAt
            .filterKeys { it !in cookies }
            .map { (provider, time) ->
                SyncCookie(
                    provider = provider,
                    value = "",
                    updatedAt = SyncTime.toIso(time),
                    deletedAt = SyncTime.toIso(time),
                )
            }
            .filter { includeDeleted },
    )
}

private fun SavedSong.toSyncFavorite(localRefs: Map<String, SyncTrackRefs>): SyncFavorite? {
    val song = runCatching { entryJson.decodeFromString<Song>(payload) }.getOrNull() ?: return null
    return SyncFavorite(
        key = key,
        addedAt = SyncTime.toIso(updatedAt),
        track = song.toSyncTrack(localRefs),
    )
}

private val entryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun PlaylistEntryEntity.toSyncTrack(localRefs: Map<String, SyncTrackRefs>): SyncTrack? {
    val song = runCatching { entryJson.decodeFromString<Song>(payload) }.getOrNull() ?: return null
    return song.toSyncTrack(localRefs)
}

/**
 * 歌单条目 / 收藏 → 同步文件里的一首。本地歌另外挂上线索；封面只留网址，
 * 本机封面缓存（file://）这类路径一律不出设备——旧数据里万一还留着也在这里拦下。
 */
internal fun Song.toSyncTrack(localRefs: Map<String, SyncTrackRefs> = emptyMap()): SyncTrack {
    val local = source == LOCAL_TRACK_PROVIDER
    val refs = if (local) localRefs[key] else null
    // 本地歌用匹配到的平台封面：换了匹配，别的设备上的封面跟着换
    val shownCover = if (local) refs?.cover?.takeIf(::isWebUrl) ?: cover.takeIf(::isWebUrl).orEmpty() else cover
    return SyncTrack(
        key = key,
        provider = source,
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationSec = duration,
        cover = shownCover,
        local = refs?.local?.takeUnless { it.isEmpty },
        remote = refs?.remote,
        match = refs?.match,
    )
}

/** 本地歌的 Song.source；和 data.local.LOCAL_SOURCE 同值，同步层不反过来依赖本地音乐库。 */
const val LOCAL_TRACK_PROVIDER = "local"

internal fun isWebUrl(value: String): Boolean =
    value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

fun SyncTrack.toSongStub(): Song = toSong()

fun playlistSignature(playlist: SyncPlaylist?): String {
    if (playlist == null) return "null"
    return buildString {
        append(playlist.name).append('|')
        append(playlist.deletedAt.orEmpty()).append('|')
        playlist.tracks.forEach { append(it.key).append(',').append(it.title).append(',').append(it.artist).append(';') }
    }
}

fun favoriteSignature(favorite: SyncFavorite?): String {
    if (favorite == null) return "null"
    return buildString {
        append(favorite.key).append('|')
        append(favorite.addedAt).append('|')
        append(favorite.deletedAt.orEmpty()).append('|')
        favorite.track?.let { append(it.title).append(',').append(it.artist) }
    }
}

fun cookieSignature(cookie: SyncCookie?): String {
    if (cookie == null) return "null"
    if (cookie.deletedAt != null || cookie.value.isBlank()) return "${cookie.provider}|deleted"
    return "${cookie.provider}|${sha256(cookie.value.toByteArray(Charsets.UTF_8))}"
}

/**
 * 整份快照的指纹：两边一样就不用上传。本地歌的线索（云端位置、匹配）单独算一段——
 * 只改了线索也要上传，但不能算进 [playlistSignature]：那是判断歌单冲突用的，
 * 两台设备各自给同一首歌换了匹配不该弹冲突，按 [SyncRefMerge] 的规则合并就行。
 */
fun snapshotSignature(snapshot: SyncSnapshot): String = buildString {
    append(snapshot.playlists.sortedBy { it.id }.joinToString("\n") { "${it.id}:${playlistSignature(it)}" })
    append("\n--favorites--\n")
    append(snapshot.favorites.sortedBy { it.key }.joinToString("\n") { "${it.key}:${favoriteSignature(it)}" })
    append("\n--cookies--\n")
    append(snapshot.cookies.sortedBy { it.provider }.joinToString("\n") { "${it.provider}:${cookieSignature(it)}" })
    append("\n--refs--\n")
    append(refsSignature(snapshot))
}

/** 所有带线索的本地歌：key + 三组线索，排好序；同一首在几个歌单里出现只算一次。 */
fun refsSignature(snapshot: SyncSnapshot): String =
    snapshot.activeTracks()
        .filter { it.hasRefs }
        .map { track -> "${track.key}|${track.local}|${track.remote}|${track.match}" }
        .distinct()
        .sorted()
        .joinToString("\n")

/** 没删的歌单里的歌 + 还在的收藏里的歌。 */
fun SyncSnapshot.activeTracks(): Sequence<SyncTrack> =
    playlists.asSequence().filter { it.deletedAt == null }.flatMap { it.tracks.asSequence() } +
        favorites.asSequence().filter { it.deletedAt == null }.mapNotNull { it.track }

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
