package com.mytv.mymusic.data.transfer

import com.mytv.mymusic.data.LibraryDao
import com.mytv.mymusic.data.MusicDao
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.sync.LOCAL_TRACK_PROVIDER
import com.mytv.mymusic.data.sync.LocalSyncRefs
import kotlinx.serialization.json.Json

/**
 * 歌单文件的导出（和导入）：从库里取歌单 → [ExportFile]。
 * 只依赖 DAO 接口和同步层的本地歌线索，不碰 Android，能 JVM 单测。
 */
class PlaylistTransfer(
    private val library: LibraryDao,
    private val music: MusicDao,
    private val localRefs: () -> LocalSyncRefs?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val payloadJson = Json { ignoreUnknownKeys = true }

    /** [ids] 里的 [ExportList.FAVORITES_ID] 是我的收藏；删掉的、找不到的歌单不出现。 */
    suspend fun exportLists(ids: List<String>): List<ExportList> = ids.distinct().mapNotNull { id ->
        if (id == ExportList.FAVORITES_ID) {
            val saved = music.songsOnce(FAVORITE_BUCKET)
            val songs = saved.mapNotNull { decode(it.payload) }
            ExportList(id, ExportList.FAVORITES_NAME, songs, unreadable = saved.size - songs.size)
        } else {
            val playlist = library.playlistOnce(id)?.takeIf { it.deletedAt == null } ?: return@mapNotNull null
            val entries = library.entriesOnce(id).sortedBy { it.position }
            val songs = entries.mapNotNull { decode(it.payload) }
            ExportList(
                playlistId = id,
                name = playlist.name,
                songs = songs,
                createdAt = playlist.createdAt,
                source = if (playlist.bound) PortableSource(playlist.linkProvider, playlist.linkId) else null,
                unreadable = entries.size - songs.size,
            )
        }
    }

    suspend fun exportPreview(ids: List<String>): ExportPreview = PlaylistExport.preview(exportLists(ids))

    /** 写文件那一刻再取本地歌的线索（本地身份、匹配、匹配封面）；[app] 写进文件只作排查。 */
    suspend fun exportFile(preview: ExportPreview, app: String = ""): ExportFile {
        val localKeys = preview.lists.asSequence().flatMap { it.songs.asSequence() }
            .filter { it.source == LOCAL_TRACK_PROVIDER }.map { it.key }.toSet()
        val refs = if (localKeys.isEmpty()) emptyMap() else localRefs()?.snapshotRefs(localKeys).orEmpty()
        return PlaylistExport.build(preview.lists, clock(), app, refs)
    }

    private fun decode(payload: String): Song? =
        runCatching { payloadJson.decodeFromString(Song.serializer(), payload) }.getOrNull()?.takeIf { it.source.isNotBlank() && it.id.isNotBlank() }

    companion object {
        const val FAVORITE_BUCKET = "favorite"
    }
}
