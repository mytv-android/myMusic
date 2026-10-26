package com.mytv.mymusic.data.sync

enum class SyncStrategy { Merge, Local, Remote }

data class MergeConflict(
    val playlistId: String,
    val base: SyncPlaylist?,
    val local: SyncPlaylist?,
    val remote: SyncPlaylist?,
)

data class SyncContent(
    val playlists: List<SyncPlaylist> = emptyList(),
    val favorites: List<SyncFavorite> = emptyList(),
    val cookies: List<SyncCookie> = emptyList(),
)

data class MergeResult(
    val playlists: List<SyncPlaylist>,
    val favorites: List<SyncFavorite>,
    val cookies: List<SyncCookie>,
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
        return MergeResult(
            playlists = merged,
            favorites = mergeFavorites(base?.favorites.orEmpty(), local.favorites, remote.favorites),
            cookies = mergeCookies(base?.cookies.orEmpty(), local.cookies, remote.cookies),
            conflicts = conflicts,
        )
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
    ): SyncContent = when (strategy) {
        SyncStrategy.Local -> SyncContent(local.playlists, local.favorites, local.cookies)
        SyncStrategy.Remote -> SyncContent(remote.playlists, remote.favorites, remote.cookies)
        SyncStrategy.Merge -> {
            val localMap = local.playlists.associateBy { it.id }
            val remoteMap = remote.playlists.associateBy { it.id }
            SyncContent(
                playlists = (localMap.keys + remoteMap.keys).toSortedSet().mapNotNull { id ->
                    mergePlaylists(localMap[id], remoteMap[id])?.let(::normalize)
                },
                favorites = unionFavorites(local.favorites, remote.favorites),
                cookies = unionCookies(local.cookies, remote.cookies),
            )
        }
    }

    /** 收藏没有冲突界面：两边都改过时按修改时间取新的；时间相同保留本机。 */
    fun mergeFavorites(
        base: List<SyncFavorite>,
        local: List<SyncFavorite>,
        remote: List<SyncFavorite>,
    ): List<SyncFavorite> {
        val baseMap = base.associateBy { it.key }
        val localMap = local.associateBy { it.key }
        val remoteMap = remote.associateBy { it.key }
        val ids = (baseMap.keys + localMap.keys + remoteMap.keys).toSortedSet()
        return ids.mapNotNull { id ->
            val b = baseMap[id]
            val l = localMap[id]
            val r = remoteMap[id]
            val localChanged = !sameFavorite(l, b)
            val remoteChanged = !sameFavorite(r, b)
            when {
                sameFavorite(l, r) -> l ?: r
                localChanged && !remoteChanged -> l ?: favoriteTombstone(b)
                !localChanged && remoteChanged -> r ?: favoriteTombstone(b)
                else -> newerFavorite(l, r)
            }
        }
    }

    /** 平台 Cookie 同样按修改时间合并；退出登录的墓碑不会被另一台设备的旧 Cookie 覆盖。 */
    fun mergeCookies(
        base: List<SyncCookie>,
        local: List<SyncCookie>,
        remote: List<SyncCookie>,
    ): List<SyncCookie> {
        val baseMap = base.associateBy { it.provider }
        val localMap = local.associateBy { it.provider }
        val remoteMap = remote.associateBy { it.provider }
        val providers = (baseMap.keys + localMap.keys + remoteMap.keys).toSortedSet()
        return providers.mapNotNull { provider ->
            val b = baseMap[provider]
            val l = localMap[provider]
            val r = remoteMap[provider]
            val localChanged = !sameCookie(l, b)
            val remoteChanged = !sameCookie(r, b)
            when {
                sameCookie(l, r) -> l ?: r
                localChanged && !remoteChanged -> l ?: cookieTombstone(b)
                !localChanged && remoteChanged -> r ?: cookieTombstone(b)
                else -> newerCookie(l, r)
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

    private fun unionFavorites(local: List<SyncFavorite>, remote: List<SyncFavorite>): List<SyncFavorite> {
        val merged = local.associateByTo(LinkedHashMap()) { it.key }
        remote.forEach { favorite -> merged.putIfAbsent(favorite.key, favorite) }
        return merged.values.toList()
    }

    private fun unionCookies(local: List<SyncCookie>, remote: List<SyncCookie>): List<SyncCookie> {
        val merged = local.associateByTo(LinkedHashMap()) { it.provider }
        remote.forEach { cookie -> merged.putIfAbsent(cookie.provider, cookie) }
        return merged.values.toList()
    }

    private fun same(a: SyncPlaylist?, b: SyncPlaylist?): Boolean =
        playlistSignature(a) == playlistSignature(b)

    private fun sameFavorite(a: SyncFavorite?, b: SyncFavorite?): Boolean =
        favoriteSignature(a) == favoriteSignature(b)

    private fun sameCookie(a: SyncCookie?, b: SyncCookie?): Boolean =
        cookieSignature(a) == cookieSignature(b)

    private fun newerFavorite(local: SyncFavorite?, remote: SyncFavorite?): SyncFavorite? {
        if (local == null) return remote
        if (remote == null) return local
        return if (remote.changeTimeMillis() > local.changeTimeMillis()) remote else local
    }

    private fun newerCookie(local: SyncCookie?, remote: SyncCookie?): SyncCookie? {
        if (local == null) return remote
        if (remote == null) return local
        return if (remote.changeTimeMillis() > local.changeTimeMillis()) remote else local
    }

    private fun favoriteTombstone(base: SyncFavorite?): SyncFavorite? {
        if (base == null) return null
        if (base.deletedAt != null) return base
        return base.copy(deletedAt = SyncTime.nowIso(), track = null)
    }

    private fun cookieTombstone(base: SyncCookie?): SyncCookie? {
        if (base == null) return null
        if (base.deletedAt != null || base.value.isBlank()) return base
        val now = SyncTime.nowIso()
        return base.copy(value = "", updatedAt = now, deletedAt = now)
    }

    private fun normalize(playlist: SyncPlaylist): SyncPlaylist =
        if (playlist.deletedAt != null) playlist.copy(tracks = emptyList()) else playlist

    private fun tombstoneOf(playlist: SyncPlaylist): SyncPlaylist = playlist.copy(
        tracks = emptyList(),
        deletedAt = SyncTime.nowIso(),
        updatedAt = SyncTime.nowIso(),
    )
}
