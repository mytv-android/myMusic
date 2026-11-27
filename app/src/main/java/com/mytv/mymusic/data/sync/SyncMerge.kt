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
    val lxSources: List<SyncLxSource> = emptyList(),
    val sodaIdentity: SyncSodaIdentity? = null,
    val neteaseIdentity: SyncNeteaseIdentity? = null,
)

data class MergeResult(
    val playlists: List<SyncPlaylist>,
    val favorites: List<SyncFavorite>,
    val cookies: List<SyncCookie>,
    val lxSources: List<SyncLxSource>,
    val conflicts: List<MergeConflict>,
    val sodaIdentity: SyncSodaIdentity? = null,
    val neteaseIdentity: SyncNeteaseIdentity? = null,
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
        // 歌单和收藏按原规则选好之后，再把两边本地歌的线索（云端位置、匹配）并上去：
        // 线索只会越并越全，不参与冲突判断，也不会让任何一首从歌单里消失
        val (playlists, favorites) = SyncRefMerge.unite(
            merged,
            mergeFavorites(base?.favorites.orEmpty(), local.favorites, remote.favorites),
            SyncRefMerge.index(local),
            SyncRefMerge.index(remote),
        )
        return MergeResult(
            playlists = withKnownLinks(playlists, local.playlists),
            favorites = favorites,
            cookies = mergeCookies(base?.cookies.orEmpty(), local.cookies, remote.cookies),
            lxSources = mergeLxSources(base?.lxSources.orEmpty(), local.lxSources, remote.lxSources),
            conflicts = conflicts,
            sodaIdentity = mergeSodaIdentity(local.sodaIdentity, remote.sodaIdentity),
            neteaseIdentity = mergeNeteaseIdentity(local.neteaseIdentity, remote.neteaseIdentity),
        )
    }

    fun resolve(local: SyncPlaylist?, remote: SyncPlaylist?, strategy: SyncStrategy): SyncPlaylist? {
        val chosen = when (strategy) {
            SyncStrategy.Local -> local?.let(::normalize)
            SyncStrategy.Remote -> remote?.let(::normalize)
            SyncStrategy.Merge -> mergePlaylists(local, remote)?.let(::normalize)
        } ?: return null
        // 选“保留本机”也不丢另一边记下的云端位置和匹配：那是同一首歌的事实，不是歌单的版本
        return SyncRefMerge.unite(
            listOf(chosen),
            emptyList(),
            SyncRefMerge.index(listOfNotNull(local), emptyList()),
            SyncRefMerge.index(listOfNotNull(remote), emptyList()),
        ).first.let { withKnownLinks(it, listOfNotNull(local)) }.single()
    }

    fun applyStrategy(
        local: SyncSnapshot,
        remote: SyncSnapshot,
        strategy: SyncStrategy,
    ): SyncContent = uniteRefs(chooseContent(local, remote, strategy), local, remote)

    private fun uniteRefs(content: SyncContent, local: SyncSnapshot, remote: SyncSnapshot): SyncContent {
        val (playlists, favorites) = SyncRefMerge.unite(
            content.playlists,
            content.favorites,
            SyncRefMerge.index(local),
            SyncRefMerge.index(remote),
        )
        return content.copy(playlists = withKnownLinks(playlists, local.playlists), favorites = favorites)
    }

    /** 从同步文件回到本机音源：删掉的墓碑不传，脚本正文原样带回去。 */
    fun activeLxSources(sources: List<SyncLxSource>): List<SyncLxSource> =
        sources.filter { it.deletedAt == null && it.id.isNotBlank() }

    /**
     * 选中的是 v2 文件里的那一版时，它不知道绑定状态（[SyncPlaylist.linkKnown] = false）：
     * 用本机同一个歌单的绑定补上再推，不然推上去的 v3 文件会写成“没绑定”，让其他设备误解绑。
     */
    fun withKnownLinks(playlists: List<SyncPlaylist>, local: List<SyncPlaylist>): List<SyncPlaylist> {
        if (playlists.all { it.linkKnown }) return playlists
        val localLinks = local.associate { it.id to it.link.takeIf { _ -> it.linkKnown } }
        return playlists.map { playlist ->
            if (playlist.linkKnown) playlist
            else playlist.copy(link = if (playlist.deletedAt == null) localLinks[playlist.id] else null, linkKnown = true)
        }
    }

    private fun chooseContent(
        local: SyncSnapshot,
        remote: SyncSnapshot,
        strategy: SyncStrategy,
    ): SyncContent = when (strategy) {
        SyncStrategy.Local -> SyncContent(local.playlists, local.favorites, local.cookies, local.lxSources, local.sodaIdentity, local.neteaseIdentity)
        SyncStrategy.Remote -> SyncContent(remote.playlists, remote.favorites, remote.cookies, remote.lxSources, remote.sodaIdentity, remote.neteaseIdentity)
        SyncStrategy.Merge -> {
            val localMap = local.playlists.associateBy { it.id }
            val remoteMap = remote.playlists.associateBy { it.id }
            SyncContent(
                playlists = (localMap.keys + remoteMap.keys).toSortedSet().mapNotNull { id ->
                    mergePlaylists(localMap[id], remoteMap[id])?.let(::normalize)
                },
                favorites = unionFavorites(local.favorites, remote.favorites),
                cookies = unionCookies(local.cookies, remote.cookies),
                lxSources = mergeLxSources(emptyList(), local.lxSources, remote.lxSources),
                sodaIdentity = mergeSodaIdentity(local.sodaIdentity, remote.sodaIdentity),
                neteaseIdentity = mergeNeteaseIdentity(local.neteaseIdentity, remote.neteaseIdentity),
            )
        }
    }

    /**
     * 网易云播放上报的设备号按时间取新的（和 Cookie 一样不弹冲突界面）：它是单值，
     * 也没有「删除」这个状态——轮换就是写一份带新时间戳的设备号，旧快照盖不掉新的。
     * 两边都没带（老同步文件 / 两台都没生成过）时返回 null，本机保持原样。
     */
    fun mergeNeteaseIdentity(local: SyncNeteaseIdentity?, remote: SyncNeteaseIdentity?): SyncNeteaseIdentity? {
        val l = local?.takeIf { it.isUsable() }
        val r = remote?.takeIf { it.isUsable() }
        if (l == null) return r
        if (r == null) return l
        if (l.deviceId == r.deviceId) return l
        return if (r.changeTimeMillis() > l.changeTimeMillis()) r else l
    }

    /**
     * 汽水设备身份按时间取新的（和 Cookie 一样不弹冲突界面）：它是单值不是列表，
     * 也没有「删除」这个状态——轮换就是写一份带新时间戳的身份，旧快照盖不掉新的。
     * 两边都没带（老同步文件 / 两台都没生成过）时返回 null，本机保持原样。
     *
     * 上报指纹额外讲个规矩：它在一台设备上可能根本没生成过（没开播放上报），
     * 所以赢了那一份如果是空的，要把另一份非空的带回来。不然本机的指纹被抹掉，
     * 业务请求又会退化成「每次都是一个新设备号」。
     */
    fun mergeSodaIdentity(local: SyncSodaIdentity?, remote: SyncSodaIdentity?): SyncSodaIdentity? {
        val l = local?.takeIf { it.isUsable() }
        val r = remote?.takeIf { it.isUsable() }
        if (l == null) return r
        if (r == null) return l
        if (l.deviceId == r.deviceId && l.installId == r.installId && l.reportDeviceId == r.reportDeviceId) {
            return l
        }
        val remoteNewer = r.changeTimeMillis() > l.changeTimeMillis()
        val winner = if (remoteNewer) r else l
        val loser = if (remoteNewer) l else r
        if (winner.reportDeviceId.isBlank() && loser.reportDeviceId.isNotBlank()) {
            return winner.copy(reportDeviceId = loser.reportDeviceId)
        }
        return winner
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

    /**
     * 洛雪自定义音源按修改时间取新的；删除的墓碑挡住另一台设备没删的旧版本。
     * 和 Cookie 一样不做冲突界面：音源是可再导入的配置，没必要为它打断同步。
     */
    fun mergeLxSources(
        base: List<SyncLxSource>,
        local: List<SyncLxSource>,
        remote: List<SyncLxSource>,
    ): List<SyncLxSource> {
        val baseMap = base.associateBy { it.id }
        val localMap = local.associateBy { it.id }
        val remoteMap = remote.associateBy { it.id }
        val ids = (baseMap.keys + localMap.keys + remoteMap.keys).toSortedSet()
        return ids.mapNotNull { id ->
            val b = baseMap[id]
            val l = localMap[id]
            val r = remoteMap[id]
            val localChanged = !sameLx(l, b)
            val remoteChanged = !sameLx(r, b)
            when {
                sameLx(l, r) -> l ?: r
                localChanged && !remoteChanged -> l ?: lxTombstone(b)
                !localChanged && remoteChanged -> r ?: lxTombstone(b)
                else -> newerLx(l, r)
            }
        }.sortedBy { it.position }
    }

    private fun sameLx(a: SyncLxSource?, b: SyncLxSource?): Boolean {
        if (a == null || b == null) return a == b
        return a.scriptHash == b.scriptHash && a.enabled == b.enabled && a.position == b.position &&
            a.deletedAt == b.deletedAt && a.name == b.name
    }

    private fun newerLx(local: SyncLxSource?, remote: SyncLxSource?): SyncLxSource? {
        if (local == null) return remote
        if (remote == null) return local
        return if (remote.changeTimeMillis() > local.changeTimeMillis()) remote else local
    }

    private fun lxTombstone(base: SyncLxSource?): SyncLxSource? {
        if (base == null) return null
        if (base.deletedAt != null) return base
        return base.copy(deletedAt = SyncTime.nowIso(), script = "", scriptHash = "")
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
