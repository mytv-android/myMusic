package com.mytv.mymusic.data.transfer

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.sync.LOCAL_TRACK_PROVIDER
import com.mytv.mymusic.data.sync.isWebUrl

/** 洛雪一首歌映射成本应用的结果。 */
sealed interface LxMapped {
    /** 网易云、QQ、酷狗、酷我：直接能用。 */
    data class Ready(val song: Song) : LxMapped

    /** 咪咕只有版权号：先按 copyrightId 换取，换不到用 [fallback]（id 用版权号兜底，可能放不了）。 */
    data class Migu(val copyrightId: String, val fallback: Song) : LxMapped

    /** 本地文件：按歌名 + 歌手去这台设备的本地音乐里找；路径不用，只留最后一段文件名作比对。 */
    data class Local(
        val title: String,
        val artist: String,
        val album: String,
        val durationSec: Int,
        val fileName: String,
    ) : LxMapped

    data class Skip(val reason: SkipReason) : LxMapped
}

/** 洛雪的平台代号 → 本应用的平台 id；洛雪自定义源这类认不出的返回 null。 */
fun lxProvider(source: String): String? = when (source) {
    "wy" -> "netease"
    "tx" -> "qq"
    "kg" -> "kugou"
    "kw" -> "kuwo"
    "mg" -> "migu"
    "local" -> LOCAL_TRACK_PROVIDER
    else -> null
}

/** 只留 http(s) 封面；洛雪本地歌的封面是本机路径，丢掉。 */
internal fun lxCover(picUrl: String?): String = picUrl?.takeIf(::isWebUrl).orEmpty()

fun LxSong.mapToSong(): LxMapped {
    val songId = meta["songId"].orEmpty()
    fun base(source: String, id: String, extra: Map<String, String>, link: String) = Song(
        id = id,
        name = name,
        artist = singer,
        album = meta["albumName"].orEmpty(),
        albumId = meta["albumId"].orEmpty(),
        duration = intervalSec,
        source = source,
        cover = lxCover(meta["picUrl"]),
        link = link,
        extra = extra.filterValues { it.isNotBlank() },
    )
    return when (source) {
        "wy" -> {
            if (songId.isBlank()) return LxMapped.Skip(SkipReason.MissingId)
            LxMapped.Ready(base("netease", songId, mapOf("song_id" to songId), "https://music.163.com/#/song?id=$songId"))
        }
        "tx" -> {
            if (songId.isBlank()) return LxMapped.Skip(SkipReason.MissingId)
            val media = meta["strMediaMid"].orEmpty().takeIf { it != songId }.orEmpty()
            LxMapped.Ready(
                base(
                    "qq",
                    songId,
                    mapOf(
                        "songmid" to songId,
                        "song_id" to meta["id"].orEmpty(),
                        "album_mid" to meta["albumMid"].orEmpty(),
                        "media_mid" to media,
                    ),
                    "https://y.qq.com/n/ryqq/songDetail/$songId",
                ),
            )
        }
        "kg" -> {
            // 洛雪酷狗歌的 id 是 <audio_id>_<hash>，没有 kg_ 前缀；meta 缺字段时从 id 拆
            val hash = meta["hash"].orEmpty().ifBlank { id.substringAfter('_', "") }
            if (hash.isBlank()) return LxMapped.Skip(SkipReason.MissingId)
            val audioId = songId.ifBlank { id.substringBefore('_', "").takeIf { id.contains('_') }.orEmpty() }
            LxMapped.Ready(
                base(
                    "kugou",
                    hash,
                    mapOf(
                        "hash" to hash,
                        "audio_id" to audioId,
                        "album_audio_id" to meta["albumAudioId"].orEmpty(),
                        "album_id" to meta["albumId"].orEmpty(),
                    ),
                    "https://www.kugou.com/song/#hash=$hash",
                ),
            )
        }
        "kw" -> {
            val rid = songId.removePrefix("MUSIC_")
            if (rid.isBlank()) return LxMapped.Skip(SkipReason.MissingId)
            LxMapped.Ready(base("kuwo", rid, mapOf("rid" to rid), "https://www.kuwo.cn/play_detail/$rid"))
        }
        "mg" -> {
            val copyrightId = meta["copyrightId"].orEmpty()
            if (copyrightId.isBlank()) return LxMapped.Skip(SkipReason.MissingId)
            LxMapped.Migu(
                copyrightId,
                base(
                    "migu",
                    "$copyrightId|2|PQ",
                    mapOf(
                        "content_id" to copyrightId,
                        "resource_type" to "2",
                        "format_type" to "PQ",
                        "copyright_id" to copyrightId,
                        "song_id" to songId,
                    ),
                    "https://music.migu.cn/v3/music/song/$copyrightId",
                ),
            )
        }
        "local" -> LxMapped.Local(
            title = name,
            artist = singer,
            album = meta["albumName"].orEmpty(),
            durationSec = intervalSec,
            fileName = lastSegment(meta["filePath"].orEmpty().ifBlank { songId }),
        )
        else -> LxMapped.Skip(SkipReason.UnknownSource)
    }
}

/** 路径最后一段（`/` 和 `\` 都认）。 */
internal fun lastSegment(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
