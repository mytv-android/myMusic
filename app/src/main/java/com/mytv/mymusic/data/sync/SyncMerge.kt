package com.mytv.mymusic.data.sync

enum class SyncStrategy { Merge, Local, Remote }

data class MergeConflict(
    val playlistId: String,
    val base: SyncPlaylist?,
    val local: SyncPlaylist?,
    val remote: SyncPlaylist?,
)

data class MergeResult(
    val playlists: List<SyncPlaylist>,
    val conflicts: List<MergeConflict>,
)

object SyncMerge {
    fun merge(base: SyncSnapshot?, local: SyncSnapshot, remote: SyncSnapshot): MergeResult {
        val baseMap = base?.playlists.orEmpty().associateBy { it.id }
        val localMap = local.playlists.associateBy { it.id }
        val remoteMap = remote.playlists.associateBy { it.id }
        val ids = (localMap.keys + remoteMap.keys + baseMap.keys).toSortedSet()
        val merged = mutableListOf<SyncPlaylist>()
        val conflicts = mutableListOf<MergeConflict>()

        ids.forEach { id ->
            val b = baseMap[id]
            val l = localMap[id]
            val r = remoteMap[id]
            val localChanged = !same(l, b)
            val remoteChanged = !same(r, b)
            when {
                same(l, r) -> (l ?: r)?.let(::normalize)?.let(merged::add)
                localChanged && !remoteChanged -> {
                    if (l == null) {
                        b?.let { merged += tombstoneOf(it) }
                    } else {
                        merged += normalize(l)
                    }
                }
                !localChanged && remoteChanged -> {
                    if (r == null) {
                        b?.let { merged += tombstoneOf(it) }
                    } else {
                        merged += normalize(r)
                    }
                }
                else -> conflicts += MergeConflict(id, b, l, r)
            }
        }
        return MergeResult(merged, conflicts)
    }

    fun resolve(local: SyncPlaylist?, remote: SyncPlaylist?, strategy: SyncStrategy): SyncPlaylist? = when (strategy) {
        SyncStrategy.Local -> local?.let(::normalize)
        SyncStrategy.Remote -> remote?.let(::normalize)
        SyncStrategy.Merge -> mergePlaylists(local, remote)?.let(::normalize)
    }

    fun applyStrategy(
        local: SyncSnapshot,
        remote: SyncSnapshot,
        strategy: SyncStrategy,
    ): List<SyncPlaylist> {
        val localMap = local.playlists.associateBy { it.id }
        val remoteMap = remote.playlists.associateBy { it.id }
        return when (strategy) {
            SyncStrategy.Local -> local.playlists
            SyncStrategy.Remote -> remote.playlists
            SyncStrategy.Merge -> (localMap.keys + remoteMap.keys).toSortedSet().mapNotNull { id ->
                mergePlaylists(localMap[id], remoteMap[id])?.let(::normalize)
            }
        }
    }

    private fun mergePlaylists(local: SyncPlaylist?, remote: SyncPlaylist?): SyncPlaylist? {
        if (local == null) return remote
        if (remote == null) return local
        val localDeleted = local.deletedAt != null
        val remoteDeleted = remote.deletedAt != null
        if (localDeleted && remoteDeleted) return local
        if (localDeleted) return remote
        if (remoteDeleted) return local
        val seen = local.tracks.map { it.key }.toMutableSet()
        val extras = remote.tracks.filter { seen.add(it.key) }
        return local.copy(
            tracks = local.tracks + extras,
            deletedAt = null,
            updatedAt = SyncTime.nowIso(),
        )
    }

    private fun same(a: SyncPlaylist?, b: SyncPlaylist?): Boolean =
        playlistSignature(a) == playlistSignature(b)

    private fun normalize(playlist: SyncPlaylist): SyncPlaylist =
        if (playlist.deletedAt != null) playlist.copy(tracks = emptyList()) else playlist

    private fun tombstoneOf(playlist: SyncPlaylist): SyncPlaylist = playlist.copy(
        tracks = emptyList(),
        deletedAt = SyncTime.nowIso(),
        updatedAt = SyncTime.nowIso(),
    )
}
