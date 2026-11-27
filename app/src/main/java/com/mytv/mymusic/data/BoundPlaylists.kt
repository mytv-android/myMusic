package com.mytv.mymusic.data

/** 平台歌单刷新后的内容差异，用于提示“新增几首、移除几首”。 */
data class BoundDiff(val added: Int, val removed: Int) {
    val changed get() = added > 0 || removed > 0
}

fun boundDiff(oldKeys: Collection<String>, newKeys: Collection<String>): BoundDiff {
    val old = oldKeys.toSet()
    val new = newKeys.toSet()
    return BoundDiff(added = new.count { it !in old }, removed = old.count { it !in new })
}

/**
 * 加歌进歌单的默认落点：新歌插到最上面（像网易云那样），原来的歌整体往后挪。
 * position 按 0..n-1 重排后返回，调用方直接 replaceEntries。
 */
fun prependPlaylistEntries(
    playlistId: String,
    existing: List<PlaylistEntryEntity>,
    added: List<Song>,
    entryOf: (String, Int, Song) -> PlaylistEntryEntity,
): List<PlaylistEntryEntity> =
    (added.map { entryOf(playlistId, 0, it) } + existing)
        .withIndex()
        .map { (index, entry) -> entry.copy(position = index) }

/**
 * 绑定歌单能不能调整顺序。没绑定、或者平台没有写接口时仍然可以在本机调（调完解绑）；
 * 有写接口的平台则要看它是否支持把整张歌单的新顺序写回去。
 */
fun canReorderBoundPlaylist(bound: Boolean, writeSupported: Boolean, reorderSupported: Boolean): Boolean =
    !bound || !writeSupported || reorderSupported

/**
 * 登录后「导入平台歌单」面板要标出来的两类行：已经绑过的（灰掉、不可勾），
 * 和会合并进同名普通歌单的（[bindingTarget] 的既有行为，面板上写一句提示）。
 */
data class PlatformImportMarks(val boundIds: Set<String> = emptySet(), val mergeableIds: Set<String> = emptySet())

fun platformImportMarks(
    provider: String,
    remote: List<Playlist>,
    local: List<PlaylistEntity>,
): PlatformImportMarks {
    val live = local.filter { it.deletedAt == null }
    val boundIds = live.filter { it.bound && it.linkProvider == provider }.map { it.linkId }.toSet()
    val plainNames = live.filter { it.linkId.isBlank() }.map { it.name }.toSet()
    val ids = remote.map { it.id }.toSet()
    return PlatformImportMarks(
        boundIds = ids.filter { it in boundIds }.toSet(),
        mergeableIds = remote.filter { it.id !in boundIds && it.name in plainNames }.map { it.id }.toSet(),
    )
}

/**
 * 绑定时落到哪个本地歌单：优先找已绑定同一平台歌单的；没有就升级同名的普通歌单
 * （旧版“收藏 = 存成同名本地歌单”留下的副本，点绑定直接变活，不产生重复行）；
 * 都没有返回 null，由调用方新建。
 */
fun bindingTarget(
    playlists: List<PlaylistEntity>,
    provider: String,
    remoteId: String,
    name: String,
): PlaylistEntity? {
    val live = playlists.filter { it.deletedAt == null }
    live.firstOrNull { it.linkProvider == provider && it.linkId == remoteId }?.let { return it }
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null
    return live.firstOrNull { it.linkId.isBlank() && it.name == trimmed }
}
