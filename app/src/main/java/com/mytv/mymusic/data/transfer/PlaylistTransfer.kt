package com.mytv.mymusic.data.transfer

import com.mytv.mymusic.data.LibraryDao
import com.mytv.mymusic.data.MusicDao
import com.mytv.mymusic.data.PlaylistEntity
import com.mytv.mymusic.data.PlaylistEntryEntity
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.local.forStorage
import com.mytv.mymusic.data.sync.LOCAL_TRACK_PROVIDER
import com.mytv.mymusic.data.sync.LocalSyncRefs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 歌单文件的导出和导入：从库里取歌单 → [ExportFile]；读出来的文件 → [ImportPlan]（本地歌查找、咪咕换取）→ 写库。
 * 只依赖 DAO 接口、同步层的本地歌线索和几个注入的函数，不碰 Android。
 *
 * @param localSongs 这台设备本地音乐里的歌（文件在的），只在文件里有本地歌时取一次。
 * @param migu 按版权号换咪咕歌（`MiguProvider.songsByCopyright`），回调是已完成批数 / 总批数。
 * @param knownProvider 本应用认得的平台；myMusic 文件里别的平台（比如以后删掉的）算「本应用没有这个平台」。
 */
class PlaylistTransfer(
    private val library: LibraryDao,
    private val music: MusicDao,
    private val localRefs: () -> LocalSyncRefs?,
    private val localSongs: suspend () -> List<LocalCandidate> = { emptyList() },
    private val migu: suspend (List<String>, (Int, Int) -> Unit) -> Map<String, Song> = { _, _ -> emptyMap() },
    private val knownProvider: (String) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val payloadJson = Json { ignoreUnknownKeys = true }

    /** 存进库里的歌和 MusicViewModel 一样的写法（不写默认值、去掉本机路径）。 */
    private val storeJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

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

    // ---------------- 导入 ----------------

    /**
     * 文件内容 → 预览。本地歌去本地音乐里找、咪咕按版权号换（这台设备唯一联网的一步），[progress] 报给面板。
     * [content] 只能是 [TransferContent.Playlists] 或 [TransferContent.Lx]。
     */
    suspend fun plan(
        content: TransferContent,
        fileName: String,
        fromPhone: Boolean = false,
        progress: (String) -> Unit = {},
    ): ImportPlan {
        val existing = library.playlistsOnce().filter { it.deletedAt == null }
        val needsLocal = when (content) {
            is TransferContent.Lx -> content.file.lists.any { list -> list.songs.any { it.source == "local" } }
            is TransferContent.Playlists -> content.decoded.file.playlists.any { list -> list.tracks.any { it.isLocal } }
            else -> false
        }
        val local = if (needsLocal) {
            progress("正在本地音乐里找同名歌曲…")
            LocalMatchIndex(localSongs())
        } else {
            null
        }
        val miguIds = (content as? TransferContent.Lx)?.file?.lists.orEmpty()
            .filter { it.fixed != LxFixed.Temp }
            .flatMap { list -> list.songs.mapNotNull { (it.mapToSong() as? LxMapped.Migu)?.copyrightId } }
            .distinct()
        val resolved = if (miguIds.isEmpty()) emptyMap() else {
            val batches = (miguIds.size + 99) / 100
            progress("正在换取咪咕歌曲 0/$batches 批")
            migu(miguIds) { done, total -> progress("正在换取咪咕歌曲 $done/$total 批") }
        }
        return buildImportPlan(content, fileName, existing, local, resolved, knownProvider, fromPhone)
    }

    /**
     * 按预览里的选择写库：合并到收藏（已有的不动）、合并到没绑定的同名歌单（同一首不重复加）、新建歌单（一律不绑定）。
     * 一个歌单写失败就记下继续下一个。调用方拿着歌单编辑锁，写完自己触发同步。
     */
    suspend fun apply(plan: ImportPlan, choices: Map<String, ImportChoice>): ImportResult {
        val now = clock()
        val names = library.playlistsOnce().filter { it.deletedAt == null }.mapTo(HashSet()) { it.name }
        var lists = 0
        var added = 0
        var alreadyIn = 0
        var favorites = 0
        var failed = 0
        var firstError = ""
        val created = mutableListOf<String>()
        for (list in plan.lists) {
            val choice = choices[list.sourceId] ?: continue
            if (!choice.include || list.importable == 0) continue
            val songs = list.songs.distinctBy(::importKey)
            try {
                when (choice.target) {
                    ImportTarget.Favorites -> {
                        val have = music.songsOnce(FAVORITE_BUCKET).mapTo(HashSet()) { saved ->
                            decode(saved.payload)?.let(::importKey) ?: saved.key
                        }
                        var index = 0
                        songs.forEach { song ->
                            if (importKey(song) in have) {
                                alreadyIn++
                            } else {
                                music.clearTombstone(FAVORITE_BUCKET, song.key)
                                // 文件里排前面的在收藏里也排前面（收藏按时间倒序）
                                music.save(SavedSong(song.key, FAVORITE_BUCKET, encode(song), now - index))
                                index++
                                added++
                                favorites++
                            }
                        }
                    }
                    ImportTarget.Merge -> {
                        val target = plan.sameName[list.sourceId]?.takeIf { !it.bound }
                            ?.let { library.playlistOnce(it.playlistId) }
                            ?.takeIf { it.deletedAt == null && !it.bound }
                            ?: throw IllegalStateException("本机的同名歌单不见了或已绑定平台歌单")
                        val entries = library.entriesOnce(target.id)
                        val have = entries.mapTo(HashSet()) { entry -> decode(entry.payload)?.let(::importKey) ?: entry.songKey }
                        val fresh = songs.filter { importKey(it) !in have }
                        alreadyIn += songs.size - fresh.size
                        if (fresh.isNotEmpty()) {
                            library.replaceEntries(
                                target.id,
                                entries + fresh.mapIndexed { index, song -> entryOf(target.id, entries.size + index, song) },
                            )
                            library.markDirty(target.id, now)
                            added += fresh.size
                        }
                    }
                    ImportTarget.New -> {
                        val name = newNameFor(plan, list, names)
                        val id = UUID.randomUUID().toString()
                        library.putPlaylist(PlaylistEntity(id = id, name = name, createdAt = now, updatedAt = now, dirty = true))
                        library.replaceEntries(id, songs.mapIndexed { index, song -> entryOf(id, index, song) })
                        names += name
                        created += name
                        added += songs.size
                    }
                }
                lists++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed++
                if (firstError.isEmpty()) firstError = "「${list.name}」${error.message.orEmpty()}"
            }
        }
        return ImportResult(lists, added, alreadyIn, favorites, failed, created, firstError)
    }

    private fun entryOf(playlistId: String, position: Int, song: Song) =
        PlaylistEntryEntity(playlistId, position, song.key, encode(song))

    private fun encode(song: Song): String = storeJson.encodeToString(Song.serializer(), song.forStorage())

    private fun decode(payload: String): Song? =
        runCatching { payloadJson.decodeFromString(Song.serializer(), payload) }.getOrNull()?.takeIf { it.source.isNotBlank() && it.id.isNotBlank() }

    companion object {
        const val FAVORITE_BUCKET = "favorite"
    }
}

/**
 * 文件内容 + 本机现状 → 预览（纯函数）。洛雪的临时列表不导入，只计数；每个歌单里按 [importKey] 去重。
 * [local] 为 null 时本地歌都算没找到；[resolvedMigu] 是按版权号换到的咪咕歌。
 */
internal fun buildImportPlan(
    content: TransferContent,
    fileName: String,
    existing: List<PlaylistEntity>,
    local: LocalMatchIndex?,
    resolvedMigu: Map<String, Song>,
    knownProvider: (String) -> Boolean,
    fromPhone: Boolean = false,
): ImportPlan {
    val alive = existing.filter { it.deletedAt == null }
    val byName = alive.groupBy { nameKey(it.name) }
    fun sameName(name: String): SameName? {
        val hits = byName[nameKey(name)].orEmpty()
        // 有没绑定的同名就合并进它；同名的全是绑定歌单才算「绑定」
        val target = hits.firstOrNull { !it.bound } ?: hits.firstOrNull() ?: return null
        return SameName(target.id, target.name, target.bound)
    }
    val lists = mutableListOf<ImportedList>()
    var skippedTemp = 0
    var legacy = false
    var newer = false
    val label: String
    when (content) {
        is TransferContent.Lx -> {
            label = ImportPlan.LX_LABEL
            legacy = content.file.kind.legacy
            content.file.lists.forEachIndexed { index, list ->
                if (list.fixed == LxFixed.Temp) {
                    skippedTemp += list.songs.size
                    return@forEachIndexed
                }
                val mapped = list.songs.map { it to it.mapToSong() }
                val tracks = mapped.map { (song, result) ->
                    when (result) {
                        is LxMapped.Ready -> ImportedTrack(result.song, null, result.song.source)
                        is LxMapped.Migu -> ImportedTrack(resolvedMigu[result.copyrightId] ?: result.fallback, null, "migu")
                        is LxMapped.Local -> {
                            val hit = local?.pick(result.title, result.artist, result.durationSec, result.fileName)
                            if (hit != null) ImportedTrack(hit, null, LOCAL_TRACK_PROVIDER)
                            else ImportedTrack(null, SkipReason.LocalNotFound, LOCAL_TRACK_PROVIDER)
                        }
                        is LxMapped.Skip -> ImportedTrack(null, result.reason, lxProvider(song.source) ?: OTHER_PLATFORM)
                    }
                }
                val miguSongs = mapped.mapNotNull { it.second as? LxMapped.Migu }
                lists += importedList(
                    sourceId = list.id.ifBlank { "lx:$index" },
                    name = list.name,
                    fixed = list.fixed,
                    tracks = tracks,
                    badFormat = list.dropped,
                    migu = miguSongs.size,
                    miguResolved = miguSongs.count { it.copyrightId in resolvedMigu },
                )
            }
        }
        is TransferContent.Playlists -> {
            label = ImportPlan.MYMUSIC_LABEL
            newer = content.decoded.newerVersion
            content.decoded.file.playlists.forEachIndexed { index, playlist ->
                val tracks = playlist.tracks.map { track ->
                    when {
                        track.isLocal -> {
                            // 同一个文件（同一个本地身份）先认，再按歌名歌手找
                            val hit = local?.byId(track.local?.hash?.ifBlank { null } ?: track.id)
                                ?: local?.pick(track.title, track.artist, track.durationSec, "")
                            if (hit != null) ImportedTrack(hit, null, LOCAL_TRACK_PROVIDER)
                            else ImportedTrack(null, SkipReason.LocalNotFound, LOCAL_TRACK_PROVIDER)
                        }
                        !knownProvider(track.provider) -> ImportedTrack(null, SkipReason.UnknownSource, track.provider)
                        else -> ImportedTrack(track.toSong(), null, track.provider)
                    }
                }
                lists += importedList(
                    sourceId = "mymusic:$index",
                    name = playlist.name,
                    fixed = if (playlist.favorites) LxFixed.Love else null,
                    tracks = tracks,
                    badFormat = content.decoded.dropped.getOrElse(index) { 0 },
                    migu = 0,
                    miguResolved = 0,
                )
            }
        }
        else -> throw IllegalArgumentException("不是歌单文件")
    }
    val sameNames = lists.filter { it.fixed != LxFixed.Love }
        .mapNotNull { list -> sameName(list.name)?.let { list.sourceId to it } }
        .toMap()
    return ImportPlan(
        fileName = fileName,
        sourceLabel = label,
        legacy = legacy,
        newerVersion = newer,
        lists = lists,
        skippedTemp = skippedTemp,
        sameName = sameNames,
        fromPhone = fromPhone,
        existingNames = alive.mapTo(HashSet()) { it.name },
    )
}

/** 歌单内去重（重复的计 [SkipReason.Duplicate]），再按平台数能导的和导不了的。 */
private fun importedList(
    sourceId: String,
    name: String,
    fixed: LxFixed?,
    tracks: List<ImportedTrack>,
    badFormat: Int,
    migu: Int,
    miguResolved: Int,
): ImportedList {
    val seen = HashSet<String>()
    val deduped = tracks.map { track ->
        val song = track.song ?: return@map track
        if (seen.add(importKey(song))) track else ImportedTrack(null, SkipReason.Duplicate, track.platform)
    }
    val skipped = deduped.mapNotNull { it.skip }.groupingBy { it }.eachCount().toMutableMap()
    if (badFormat > 0) skipped[SkipReason.BadFormat] = (skipped[SkipReason.BadFormat] ?: 0) + badFormat
    return ImportedList(
        sourceId = sourceId,
        name = name,
        fixed = fixed,
        tracks = deduped,
        platformCounts = deduped.filter { it.song != null }.groupingBy { it.platform }.eachCount(),
        skippedPlatforms = deduped.filter { it.skip != null && it.skip != SkipReason.Duplicate }.groupingBy { it.platform }.eachCount(),
        skipped = skipped,
        migu = migu,
        miguResolved = miguResolved,
    )
}

/** 洛雪自定义源这类认不出的平台。 */
const val OTHER_PLATFORM = "other"
