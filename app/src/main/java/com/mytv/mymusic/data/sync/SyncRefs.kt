package com.mytv.mymusic.data.sync

import java.util.Locale

/**
 * 本地歌三组线索（local / remote / match）的合并规则（设计稿第 08 节，规格 9.3）。
 *
 * 歌单层面照旧按修改时间后写的为准；选定的那一版里，同一首歌在两边都出现时线索取并集：
 * - remote：上传时间新的胜出；同一次上传（同位置、同时间）里 deleted 胜出——删除是单向的，
 *   重新上传会带新的上传时间，自然盖过删除；
 * - match：用户的决定（手选一首、或点了「不匹配」）总能盖过自动匹配；两边都是用户的决定时取新的——
 *   后来又换回一首，换回的那次胜出；两边都是自动匹配时分数高的胜出，分数一样取匹配时间新的；
 * - local：逐个字段非空的胜出。
 * 两边完全打平时取 [b]（云端那一份）：云端是各设备已经看到的版本，取它就不会每次同步来回翻。
 *
 * “哪里都放不了”不在这里体现，也永远不是删歌单条目的理由：这些规则只动线索，不增删歌。
 */
object SyncRefMerge {
    const val STATUS_READY = "ready"
    const val STATUS_DELETED = "deleted"

    /** 只有这两种状态进同步文件；上传中、失败、404 都是这台设备自己的事。 */
    val SYNCED_STATUSES = setOf(STATUS_READY, STATUS_DELETED)

    fun local(a: SyncLocal?, b: SyncLocal?): SyncLocal? {
        if (a == null || a.isEmpty) return b?.takeUnless { it.isEmpty } ?: a?.takeUnless { it.isEmpty }
        if (b == null || b.isEmpty) return a
        return SyncLocal(
            hash = b.hash.ifBlank { a.hash },
            fileName = b.fileName.ifBlank { a.fileName },
            fileSize = if (b.fileSize > 0) b.fileSize else a.fileSize,
            device = b.device.ifBlank { a.device },
        )
    }

    fun remote(a: SyncRemote?, b: SyncRemote?): SyncRemote? {
        if (a == null) return b
        if (b == null) return a
        val ta = SyncTime.toMillis(a.uploadedAt)
        val tb = SyncTime.toMillis(b.uploadedAt)
        if (ta != tb) return if (ta > tb) a else b
        if (a.provider == b.provider && a.id == b.id) {
            val aDeleted = a.status == STATUS_DELETED
            val bDeleted = b.status == STATUS_DELETED
            if (aDeleted != bDeleted) return if (aDeleted) a else b
        }
        return b
    }

    fun match(a: SyncMatch?, b: SyncMatch?): SyncMatch? {
        if (a == null) return b
        if (b == null) return a
        // 用户的决定和自动匹配不比分数：自动匹配的分数可能也到 1（标签完全一致），不能因此盖过用户点的“不匹配”
        val ua = isUserDecision(a)
        val ub = isUserDecision(b)
        if (ua != ub) return if (ua) a else b
        val ta = SyncTime.toMillis(a.matchedAt)
        val tb = SyncTime.toMillis(b.matchedAt)
        // 两边都是用户的决定：最后一次说了算（拒绝之后又手选一首，或手选之后又拒绝）。
        // 同一毫秒的两个不同决定按内容定一个，两台设备谁先谁后合并都得出同一个结果
        if (ua) {
            if (ta != tb) return if (ta > tb) a else b
            return if (decisionKey(a) >= decisionKey(b)) a else b
        }
        // 浮点经过 JSON 往返可能差最后一位：按三位小数比，免得同一个匹配被当成两个分数
        val ca = Math.round(a.confidence * 1000)
        val cb = Math.round(b.confidence * 1000)
        if (ca != cb) return if (ca > cb) a else b
        if (ta != tb) return if (ta > tb) a else b
        return b
    }

    private fun decisionKey(match: SyncMatch): String = "${match.method}|${match.provider}|${match.id}"

    /**
     * 用户自己定的：「不匹配」、手选（manual），以及在「换一个匹配」里选的识曲候选（记满分）。
     * 自动按标签搜到的即使满分也不算——那是机器的判断。
     */
    fun isUserDecision(match: SyncMatch): Boolean = when (match.method) {
        SyncMatch.METHOD_REJECTED, SyncMatch.METHOD_MANUAL -> true
        SyncMatch.METHOD_TAG -> false
        else -> Math.round(match.confidence * 1000) >= 1000
    }

    /** [a] 是选定的那一版里的歌，[b] 是另一边同一首的线索；歌本身（标题、位置）以 [a] 为准。 */
    fun track(a: SyncTrack, b: SyncTrack?): SyncTrack {
        if (b == null || !b.hasRefs && b.cover.isBlank()) return a
        return a.copy(
            local = local(a.local, b.local)?.takeUnless { it.isEmpty },
            remote = remote(a.remote, b.remote),
            match = match(a.match, b.match),
            cover = a.cover.ifBlank { b.cover },
        )
    }

    /** 一份快照里每首本地歌的线索；同一首出现在几个歌单 / 收藏里时先在自己这一边合一次。 */
    fun index(playlists: List<SyncPlaylist>, favorites: List<SyncFavorite>): Map<String, SyncTrack> {
        val out = LinkedHashMap<String, SyncTrack>()
        val tracks = playlists.asSequence().filter { it.deletedAt == null }.flatMap { it.tracks.asSequence() } +
            favorites.asSequence().filter { it.deletedAt == null }.mapNotNull { it.track }
        tracks.filter { it.hasRefs }.forEach { track ->
            out[track.key] = out[track.key]?.let { track(it, track) } ?: track
        }
        return out
    }

    fun index(snapshot: SyncSnapshot): Map<String, SyncTrack> = index(snapshot.playlists, snapshot.favorites)

    /**
     * 把两边的线索并到合并结果上：[playlists] / [favorites] 是已经按歌单规则选好的，
     * 这里只给其中的本地歌换上并集后的线索，不增删任何一首。
     */
    fun unite(
        playlists: List<SyncPlaylist>,
        favorites: List<SyncFavorite>,
        local: Map<String, SyncTrack>,
        remote: Map<String, SyncTrack>,
    ): Pair<List<SyncPlaylist>, List<SyncFavorite>> {
        if (local.isEmpty() && remote.isEmpty()) return playlists to favorites
        val keys = local.keys + remote.keys
        val merged = keys.associateWith { key ->
            val l = local[key]
            val r = remote[key]
            when {
                l == null -> r!!
                r == null -> l
                else -> track(l, r)
            }
        }
        fun apply(track: SyncTrack): SyncTrack {
            val refs = merged[track.key] ?: return track
            return track.copy(
                local = refs.local,
                remote = refs.remote,
                match = refs.match,
                cover = refs.cover.ifBlank { track.cover },
            )
        }
        val outPlaylists = playlists.map { playlist ->
            if (playlist.deletedAt != null || playlist.tracks.none { it.key in merged }) playlist
            else playlist.copy(tracks = playlist.tracks.map(::apply))
        }
        val outFavorites = favorites.map { favorite ->
            val track = favorite.track
            if (favorite.deletedAt != null || track == null || track.key !in merged) favorite
            else favorite.copy(track = apply(track))
        }
        return outPlaylists to outFavorites
    }
}

/**
 * 同步文件的体积（设计稿第 08 节）：只同步加进歌单和收藏的本地歌，每首多 150–250 字节；
 * 超过 1 MB 时在同步页提示一句，不悄悄截断，也不因此停止同步。
 *
 * [hardLimitFor] 是另一回事：Gist / Gitee 的接口对 1 MB 左右的文件只回截断的内容，
 * 写上去就再也读不全，所以那两家仍在 900 KB 处停下；WebDAV 读写都不截断，放到读取上限 4 MB。
 */
object SyncSizeBudget {
    const val WARN_BYTES = 1024 * 1024
    const val SNIPPET_LIMIT_BYTES = 900 * 1024
    const val WEBDAV_LIMIT_BYTES = 4 * 1024 * 1024

    fun hardLimitFor(providerId: String): Int =
        if (providerId == WebDavSyncProvider.ID) WEBDAV_LIMIT_BYTES else SNIPPET_LIMIT_BYTES

    /** 超过 1 MB 时给同步页的提示；没超返回空串。 */
    fun warning(bytes: Int): String {
        if (bytes <= WARN_BYTES) return ""
        val mb = String.format(Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0)
        return "同步文件有 $mb MB，已经超过 1 MB。同步照常进行，内容没有删减；整理掉不用的歌单可以让它小一些。"
    }
}

/**
 * 本地音乐给同步层的接口（MyMusicApp 接上 LocalLibrary）：建快照时按 key 取本地歌的线索，
 * 合并完把线索写回本机——这台设备没有文件的歌，解析、徽标、换匹配全靠写回的这一份，重启也在。
 */
interface LocalSyncRefs {
    /** [keys] 是歌单和收藏里所有 `local:` 开头的 key。 */
    suspend fun snapshotRefs(keys: Set<String>): Map<String, SyncTrackRefs>

    /** 合并后（或从云端恢复时）选定的本地歌，带着并好的线索。 */
    suspend fun applyRefs(tracks: Collection<SyncTrack>)
}
