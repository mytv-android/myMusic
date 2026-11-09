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
 * 绑定歌单能不能调整顺序。没绑定、或者平台没有写接口时仍然可以在本机调（调完解绑）；
 * 有写接口的平台则要看它是否支持把整张歌单的新顺序写回去。
 */
fun canReorderBoundPlaylist(bound: Boolean, writeSupported: Boolean, reorderSupported: Boolean): Boolean =
    !bound || !writeSupported || reorderSupported

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
