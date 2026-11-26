package com.mytv.mymusic.ui.artist

import com.mytv.mymusic.aggregate.TrackNormalizer
import com.mytv.mymusic.data.AlbumSummary
import com.mytv.mymusic.data.ArtistProfile
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一个歌手页的状态。资料、歌曲、专辑三路各自加载，谁先回来先显示谁。
 * [name] / [avatar] / [seedSongCount] 是打开时带进来的（搜索结果、歌曲上的歌手名），
 * 资料接口回来之前头部先用它们画出来。
 */
data class ArtistUiState(
    val key: String,
    val source: String,
    val sourceName: String = "",
    val id: String = "",
    val name: String = "",
    val avatar: String = "",
    val seedSongCount: Int = 0,
    val seedAlbumCount: Int = 0,
    val profile: ArtistProfile? = null,
    /** 只有歌手名、还在平台上找这个歌手（从歌曲进来时）。 */
    val resolving: Boolean = false,
    val resolveError: String = "",
    val songs: List<Song> = emptyList(),
    /** 资料接口顺带给的热门歌曲；歌曲接口失败时顶上。 */
    val hotSongs: List<Song> = emptyList(),
    val songsLoading: Boolean = false,
    val songsError: String = "",
    val albums: List<AlbumSummary> = emptyList(),
    val albumsTotal: Int = 0,
    val albumsPage: Int = 0,
    val albumsHasMore: Boolean = false,
    val albumsLoading: Boolean = false,
    val albumsError: String = "",
    /** 平台没有歌手专辑接口：专辑是从歌手的歌里按专辑归并出来的，数量只代表这些歌覆盖到的专辑。 */
    val albumsDerived: Boolean = false,
) {
    val displayName: String get() = profile?.name?.takeIf { it.isNotBlank() } ?: name
    val displayAvatar: String get() = profile?.avatar?.takeIf { it.isNotBlank() } ?: avatar
    val visibleSongs: List<Song> get() = songs.ifEmpty { hotSongs }
    val songCount: Int get() = profile?.songCount?.takeIf { it > 0 } ?: seedSongCount.takeIf { it > 0 } ?: visibleSongs.size
    val albumCount: Int get() = when {
        albumsDerived -> albums.size
        else -> profile?.albumCount?.takeIf { it > 0 } ?: albumsTotal.takeIf { it > 0 } ?: seedAlbumCount.takeIf { it > 0 } ?: albums.size
    }
}

/**
 * 歌手页的数据：按 key 分开存，歌手 → 专辑 → 另一个歌手这样一路点下去，返回时每一层还是自己的内容。
 * 只在主线程（viewModelScope）里调用。
 */
internal class ArtistPages(
    private val scope: CoroutineScope,
    private val repository: MusicRepository,
    private val cookieFor: (String) -> String,
    private val providerName: (String) -> String,
    /** 浏览来源在前、按启用顺序排的平台，用来给“没有歌手接口的平台上的歌”找一个能查歌手的平台。 */
    private val artistProviders: () -> List<ProviderInfo>,
) {
    private val _pages = MutableStateFlow<Map<String, ArtistUiState>>(emptyMap())
    val pages: StateFlow<Map<String, ArtistUiState>> = _pages.asStateFlow()
    private val jobs = HashMap<String, Job>()

    /** 搜索结果里的歌手条目（Playlist 表示，id 即歌手 id）。返回页面 key。 */
    fun open(artist: Playlist, fallbackSource: String): String {
        val source = artist.source.ifBlank { fallbackSource }
        val key = artistKey(source, artist.id)
        val cached = _pages.value[key]
        // 已经完整加载过的直接复用；失败过的重新拉
        if (cached != null && cached.songsError.isBlank() && cached.resolveError.isBlank() && !cached.songsLoading) return key
        put(
            ArtistUiState(
                key = key,
                source = source,
                sourceName = providerName(source),
                id = artist.id,
                name = artist.name,
                avatar = artist.cover,
                seedSongCount = artist.trackCount,
                seedAlbumCount = artist.extra["album_count"]?.toIntOrNull() ?: 0,
            ),
        )
        load(key)
        return key
    }

    /**
     * 只有歌手名（歌曲上的歌手）：先在歌曲所在平台按名字搜这个歌手，平台不能查歌手就换浏览来源 / 第一个能查的平台。
     * 页面先只显示名字和封面，找到后再加载。
     */
    fun openByName(name: String, preferSource: String, cover: String = ""): String {
        val providers = artistProviders()
        val source = providers.firstOrNull { it.id == preferSource }?.id ?: providers.firstOrNull()?.id ?: preferSource
        val key = artistNameKey(source, name)
        val cached = _pages.value[key]
        if (cached != null && cached.resolveError.isBlank() && cached.songsError.isBlank() && !cached.songsLoading && !cached.resolving) return key
        put(
            ArtistUiState(
                key = key,
                source = source,
                sourceName = providerName(source),
                name = name,
                avatar = "",
                resolving = true,
            ),
        )
        resolve(key, name, cover)
        return key
    }

    /** 进程被回收后从返回栈恢复：页面状态没了，按 key 重新加载。 */
    fun ensure(key: String) {
        if (key in _pages.value) return
        val parsed = parseArtistKey(key) ?: return
        val (source, id, name) = parsed
        if (id.isNotBlank()) {
            put(ArtistUiState(key = key, source = source, sourceName = providerName(source), id = id, name = name))
            load(key)
        } else {
            put(ArtistUiState(key = key, source = source, sourceName = providerName(source), name = name, resolving = true))
            resolve(key, name, "")
        }
    }

    fun retry(key: String) {
        val state = _pages.value[key] ?: return
        if (state.id.isBlank()) {
            update(key) { it.copy(resolving = true, resolveError = "") }
            resolve(key, state.name, state.avatar)
        } else {
            load(key)
        }
    }

    fun loadMoreAlbums(key: String) {
        val state = _pages.value[key] ?: return
        if (state.albumsLoading || !state.albumsHasMore || state.albumsDerived || state.id.isBlank()) return
        update(key) { it.copy(albumsLoading = true, albumsError = "") }
        scope.launch { loadAlbumPage(key, state.source, state.id, state.albumsPage + 1) }
    }

    /**
     * 按名字在平台上找歌手：先歌曲所在平台，再其余能查歌手的平台。
     * 某个平台的歌手搜索整个挂了（接口 500/502 那种）就换下一个——否则整页只有一句“没有找到”，
     * 而从洛雪、本地这类没有歌手接口的歌进来时，第一个平台恰好就是挂掉的那个。
     */
    private fun resolve(key: String, name: String, cover: String) {
        val state = _pages.value[key] ?: return
        jobs[key]?.cancel()
        jobs[key] = scope.launch {
            val order = resolveOrder(state.source)
            var found: Playlist? = null
            var source = state.source
            for (candidate in order) {
                found = runCatching {
                    pickArtist(name, repository.searchArtists(candidate, cookieFor(candidate), name))
                }.getOrElse {
                    if (it is CancellationException) throw it
                    null
                }
                if (found != null) {
                    source = candidate
                    break
                }
            }
            if (found == null) {
                val tried = order.map { providerName(it).ifBlank { it } }.distinct()
                update(key) {
                    it.copy(
                        resolving = false,
                        resolveError = if (tried.isEmpty()) {
                            "没有可以查歌手的平台"
                        } else {
                            "没有在${tried.joinToString("、")}找到“$name”"
                        },
                    )
                }
                return@launch
            }
            update(key) {
                it.copy(
                    source = source,
                    sourceName = if (source == state.source) it.sourceName else providerName(source),
                    id = found.id,
                    name = found.name.ifBlank { name },
                    avatar = found.cover.ifBlank { cover },
                    seedSongCount = found.trackCount,
                    seedAlbumCount = found.extra["album_count"]?.toIntOrNull() ?: 0,
                    resolving = false,
                    resolveError = "",
                )
            }
            load(key)
        }
    }

    /** 歌曲所在平台在前，其余能查歌手的平台按启用顺序跟上；最多试 [MAX_RESOLVE_ATTEMPTS] 个。 */
    private fun resolveOrder(preferred: String): List<String> {
        val providers = artistProviders().map { it.id }
        if (preferred.isNotBlank() && preferred in providers) {
            return (listOf(preferred) + providers.filter { it != preferred }).take(MAX_RESOLVE_ATTEMPTS)
        }
        return providers.take(MAX_RESOLVE_ATTEMPTS)
    }

    private fun load(key: String) {
        val state = _pages.value[key] ?: return
        if (state.id.isBlank()) return
        jobs[key]?.cancel()
        update(key) {
            it.copy(
                songsLoading = true,
                songsError = "",
                albumsLoading = true,
                albumsError = "",
                albums = emptyList(),
                albumsPage = 0,
                albumsHasMore = false,
                albumsDerived = false,
            )
        }
        val source = state.source
        val id = state.id
        val cookie = cookieFor(source)
        jobs[key] = scope.launch {
            coroutineScope {
                launch {
                    val detail = runCatching { repository.artistDetail(source, cookie, id) }.getOrElse {
                        if (it is CancellationException) throw it
                        null
                    }
                    if (detail != null) update(key) { it.copy(profile = detail.profile, hotSongs = detail.hotSongs) }
                }
                launch {
                    val result = runCatching { loadSongs(source, cookie, id, state.displayName) }
                    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    update(key) { current ->
                        val songs = result.getOrDefault(emptyList())
                        val next = current.copy(
                            songs = songs,
                            songsLoading = false,
                            songsError = if (songs.isEmpty() && current.hotSongs.isEmpty()) {
                                result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "没有读到歌曲"
                            } else {
                                ""
                            },
                        )
                        if (next.albumsDerived) next.copy(albums = deriveAlbums(next.visibleSongs, source, id)) else next
                    }
                }
                launch { loadAlbumPage(key, source, id, 1) }
            }
        }
    }

    /** 歌手歌曲；平台没有歌手歌曲接口（汽水等）就按名字搜歌，只留歌手对得上的。 */
    private suspend fun loadSongs(source: String, cookie: String, id: String, name: String): List<Song> =
        try {
            repository.artistSongs(source, cookie, id)
        } catch (unsupported: UnsupportedOperationException) {
            if (name.isBlank()) throw unsupported
            val target = TrackNormalizer.keyOf(name)
            repository.searchSongs(source, cookie, name)
                .filter { song -> splitArtistNames(song.artist).any { TrackNormalizer.keyOf(it) == target } }
                .ifEmpty { throw unsupported }
        }

    private suspend fun loadAlbumPage(key: String, source: String, id: String, page: Int) {
        val result = runCatching { repository.artistAlbums(source, cookieFor(source), id, page, ALBUM_PAGE_SIZE) }
        val error = result.exceptionOrNull()
        if (error is CancellationException) throw error
        if (error is UnsupportedOperationException) {
            // 没有专辑接口：从已经读到的歌里归并；歌曲还没回来的话，歌曲回来时再归并一次
            update(key) {
                it.copy(
                    albumsDerived = true,
                    albumsLoading = false,
                    albumsHasMore = false,
                    albums = deriveAlbums(it.visibleSongs, source, id),
                )
            }
            return
        }
        update(key) { current ->
            val pageResult = result.getOrNull()
            if (pageResult == null) {
                current.copy(albumsLoading = false, albumsError = error?.message?.takeIf { it.isNotBlank() } ?: "专辑没有加载出来")
            } else {
                val merged = (current.albums + pageResult.albums).distinctBy { it.id.ifBlank { it.name } }
                current.copy(
                    albums = merged,
                    albumsTotal = pageResult.total,
                    albumsPage = page,
                    // 平台不给 hasMore 时按“这一页满了”和总数推断
                    albumsHasMore = pageResult.albums.isNotEmpty() &&
                        (pageResult.hasMore || (pageResult.total > merged.size)),
                    albumsLoading = false,
                    albumsError = "",
                )
            }
        }
    }

    private fun put(state: ArtistUiState) {
        _pages.update { pages ->
            val next = LinkedHashMap(pages)
            next.remove(state.key)
            next[state.key] = state
            // 只留最近的一批，够返回栈用
            while (next.size > MAX_PAGES) next.remove(next.keys.first())
            next
        }
    }

    private fun update(key: String, transform: (ArtistUiState) -> ArtistUiState) {
        _pages.update { pages ->
            val current = pages[key] ?: return@update pages
            pages + (key to transform(current))
        }
    }

    companion object {
        const val ALBUM_PAGE_SIZE = 30
        private const val MAX_PAGES = 16
        private const val MAX_RESOLVE_ATTEMPTS = 3
    }
}

/** 歌手页 key：`平台|id`；只有名字时是 `平台|name:名字`。进路由前要 Uri.encode。 */
internal fun artistKey(source: String, id: String) = "$source|$id"

internal fun artistNameKey(source: String, name: String) = "$source|name:$name"

/** 拆 key：(平台, id, 名字)，id 和名字有一个是空的。 */
internal fun parseArtistKey(key: String): Triple<String, String, String>? {
    val source = key.substringBefore('|', "")
    val rest = key.substringAfter('|', "")
    if (source.isBlank() || rest.isBlank()) return null
    return if (rest.startsWith("name:")) Triple(source, "", rest.removePrefix("name:")) else Triple(source, rest, "")
}

/**
 * 从平台的歌手搜索结果里挑这个名字对应的那位：名字规整后完全相同的优先，其次是互相包含的
 * （“G.E.M.邓紫棋”和“邓紫棋”）。都对不上返回 null——认错人比找不到更糟（B 站 UP 主名、电台名
 * 搜出来的第一个歌手往往毫不相干）。
 */
internal fun pickArtist(name: String, candidates: List<Playlist>): Playlist? {
    val target = TrackNormalizer.keyOf(name)
    if (target.isEmpty() || candidates.isEmpty()) return null
    return candidates.firstOrNull { TrackNormalizer.keyOf(it.name) == target }
        ?: candidates.firstOrNull {
            val key = TrackNormalizer.keyOf(it.name)
            key.length >= 2 && target.length >= 2 && (key.contains(target) || target.contains(key))
        }
}

/**
 * 歌曲上的歌手名拆成单个歌手：只按 / 、 , ; 和 feat. 拆，不拆 &（“Simon & Garfunkel”是一个组合）。
 */
internal fun splitArtistNames(value: String): List<String> =
    value.split(ArtistSeparator)
        .map { it.trim() }
        .filter { it.isNotBlank() && it != "未知歌手" && !it.equals("various artists", ignoreCase = true) }
        .distinct()

private val ArtistSeparator = Regex("""\s*(?:/|、|,|，|;|；|\bfeat\.?\s|\bft\.?\s)\s*""", RegexOption.IGNORE_CASE)

/** 歌曲所属专辑在平台上的 id：QQ 专辑接口认 album mid，其余平台用 albumId / extra 里的 album_id。 */
internal fun albumIdOf(song: Song): String =
    song.extra["album_mid"]?.takeIf { it.isNotBlank() }
        ?: song.albumId.takeIf { it.isNotBlank() && it != "0" }
        ?: song.extra["album_id"]?.takeIf { it.isNotBlank() && it != "0" }
        ?: ""

/**
 * 平台没有歌手专辑接口时，从歌手的歌里按专辑归并：保持歌曲出现的顺序（热门在前），
 * 曲目数是这些歌里属于该专辑的首数。
 */
internal fun deriveAlbums(songs: List<Song>, source: String, artistId: String): List<AlbumSummary> {
    val groups = LinkedHashMap<String, MutableList<Song>>()
    songs.forEach { song ->
        if (song.album.isBlank()) return@forEach
        val key = albumIdOf(song).ifBlank { "name:" + TrackNormalizer.keyOf(song.album) }
        groups.getOrPut(key) { mutableListOf() }.add(song)
    }
    return groups.values.map { group ->
        val first = group.first()
        AlbumSummary(
            id = albumIdOf(first),
            name = first.album,
            source = source,
            cover = group.firstOrNull { it.cover.isNotBlank() }?.cover.orEmpty(),
            artist = first.artist,
            artistId = artistId,
            trackCount = group.size,
        )
    }
}
