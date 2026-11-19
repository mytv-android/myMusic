package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.QRCheckResult
import com.mytv.mymusic.data.QRCreateResult
import com.mytv.mymusic.data.QRSession
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

private val BILIBILI_INFO = ProviderInfo(
    id = "bilibili",
    name = "Bilibili",
    search = true,
    download = true,
    parseSong = true,
    searchPlaylist = true,
    // 音乐分区排行里的合集 / 分P稿就是歌单；没有分类接口，所以只开推荐
    recommendedPlaylists = true,
    playlistSongs = true,
    parsePlaylist = true,
    qrLogin = true,
)

/**
 * 未登录时的匿名 Cookie。只带 buvid3，**不能**带 `SESSDATA=your_sessdata` 这种占位符——
 * 上游 music-lib 就是这么写的，照抄会让取流接口直接回 412 Precondition Failed：
 * B 站认得出这是个无效登录态。不带 SESSDATA 时 playurl 正常返回匿名档。
 */
private const val BILIBILI_DEFAULT_COOKIE = "buvid3=2E109C72-251F-3827-FA8E-921FA0D7EC5291319infoc"

private const val BILIBILI_SEARCH_API = "https://api.bilibili.com/x/web-interface/search/type"
private const val BILIBILI_VIEW_API = "https://api.bilibili.com/x/web-interface/view"
private const val BILIBILI_PAGE_LIST_API = "https://api.bilibili.com/x/player/pagelist"
private const val BILIBILI_PLAY_URL_API = "https://api.bilibili.com/x/player/playurl"
private const val BILIBILI_NAV_API = "https://api.bilibili.com/x/web-interface/nav"
/** 音乐分区排行（rid=3）：合集就是这张榜单里的条目，旧的 `x/space/ugc/season` 已经 404。 */
private const val BILIBILI_MUSIC_RANKING_API = "https://api.bilibili.com/x/web-interface/ranking/v2?rid=3&type=all"
private const val BILIBILI_SEASON_API =
    "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list"
private const val BILIBILI_SEASON_META_API = BILIBILI_SEASON_API
private const val BILIBILI_QR_GENERATE_API =
    "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
private const val BILIBILI_QR_POLL_API =
    "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"

/** polymer 的 archives 接口每页最多给这么多条，page_size 传再大也只回 12 条。 */
private const val BILIBILI_SEASON_PAGE_SIZE = 12
/** 一次歌单最多取这么多集：合集动辄上千集，每集还要单独问一次 cid，全拉完要几百个请求。 */
private const val SEASON_SONG_LIMIT = 120
/** 搜索时顺带展开的分P总数上限：一个多P合集不能把整页搜索结果吃光。 */
private const val SEARCH_EPISODE_BUDGET = 40
private const val RECOMMENDED_PLAYLIST_LIMIT = 24
/** 推荐歌单里只有头部几条补曲目数，后面的省掉一次请求。 */
private const val RECOMMENDED_PLAYLIST_META_LIMIT = 12
private const val QR_SESSION_TTL_MS = 3 * 60 * 1000L
private const val LOGIN_SESSION_TTL_MS = 10 * 60 * 1000L

class BilibiliProvider : BaseNativeProvider(BILIBILI_INFO) {
    private val json = NativeHttp.json
    private val vipCache = ConcurrentHashMap<String, Boolean>()
    private val loginSessions = ConcurrentHashMap<String, BilibiliLoginSession>()
    /** 音质探测结果按 cookie 缓存：探测要打一次 playurl，不能每次进设置页都重跑。 */
    private val availableQualityCache = ConcurrentHashMap<String, List<AudioQualityOption>>()

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        val body = NativeHttp.get(
            searchUrl(keyword),
            headers = mapOf("Referer" to BilibiliCdn.REFERER),
            cookie = effectiveCookie(cookie),
            userAgent = BilibiliCdn.USER_AGENT,
        )
        val response = json.decodeFromString<BilibiliSearchResponse>(body)
        val items = response.data?.result.orEmpty().filter { it.bvid.isNotBlank() }
        // 每条流水线的视频详情都要单独问，串行 20 条必然撞上聚合搜索的 6 秒超时
        val views = coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) { runCatching { fetchView(cookie, item.bvid) }.getOrNull() }
            }.map { it.await() }
        }

        val songs = mutableListOf<Song>()
        // 搜索词里的歌手是匹配时唯一可靠的「这歌是谁唱的」，标题拆不出来时用它兜底
        val keywordArtist = keywordArtist(keyword)
        items.forEachIndexed { index, item ->
            val view = views.getOrNull(index) ?: return@forEachIndexed
            val pages = view.pages.orEmpty()
            if (pages.isEmpty()) return@forEachIndexed

            val rootTitle = cleanTitle(item.title).ifBlank { view.title }
            val page = pages.first()
            val track = chooseTrack(singlePageTitle(rootTitle, page.part), keyword)
            val duration = page.duration.takeIf { it > 0 } ?: item.durationSec
            songs += Song(
                id = "${item.bvid}|${page.cid}",
                name = track.title.ifBlank { singlePageTitle(rootTitle, page.part) },
                artist = track.artist.ifBlank { keywordArtist.orEmpty() },
                album = view.title.takeIf { pages.size > 1 }.orEmpty(),
                duration = duration,
                source = "bilibili",
                cover = normalizeCover(item.pic),
                link = "https://www.bilibili.com/video/${item.bvid}?p=1",
                extra = mapOf(
                    "bvid" to item.bvid,
                    "cid" to page.cid.toString(),
                ),
            )
        }
        return songs
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val body = NativeHttp.get(
            searchUrl(keyword),
            headers = mapOf("Referer" to BilibiliCdn.REFERER),
            cookie = effectiveCookie(cookie),
            userAgent = BilibiliCdn.USER_AGENT,
        )
        val response = json.decodeFromString<BilibiliSearchResponse>(body)
        val items = response.data?.result.orEmpty().filter { it.bvid.isNotBlank() }
        val views = coroutineScope {
            items.map { item -> async(Dispatchers.IO) { runCatching { fetchView(cookie, item.bvid) }.getOrNull() } }
                .map { it.await() }
        }
        val seen = mutableSetOf<String>()
        val playlists = mutableListOf<Playlist>()

        items.forEachIndexed { index, item ->
            val view = views.getOrNull(index) ?: return@forEachIndexed
            val cover = normalizeCover(item.pic)
            val season = view.ugcSeason
            if (season != null) {
                val seasonId = season.id
                val mid = view.owner.mid
                val key = "season:$seasonId:$mid:${item.bvid}"
                if (!seen.add(key)) return@forEachIndexed

                var seasonName = season.title
                if (seasonName.isEmpty()) seasonName = cleanTitle(item.title)
                var seasonCover = season.cover
                if (seasonCover.isEmpty()) seasonCover = cover

                val sections = season.sections.orEmpty()
                // view 里的 sections 只装得下前 100 集，合集多大得以 polymer 的 total 为准
                var trackCount = countSeasonEpisodes(sections)
                if (seasonId != 0L && mid != 0L) {
                    val meta = runCatching { fetchSeasonMeta(cookie, mid, seasonId) }.getOrNull()
                    if (meta != null) {
                        if (seasonName.isEmpty() || seasonName == cleanTitle(item.title)) {
                            seasonName = meta.title.ifBlank { seasonName }
                        }
                        if (seasonCover.isEmpty()) seasonCover = meta.cover
                        if (meta.total > 0) trackCount = meta.total
                    }
                }

                playlists += Playlist(
                    id = key,
                    name = seasonName,
                    cover = normalizeCover(seasonCover),
                    trackCount = trackCount,
                    playCount = season.stat.view,
                    creator = view.owner.name,
                    description = season.intro,
                    source = "bilibili",
                    link = "https://www.bilibili.com/video/${item.bvid}",
                    extra = mapOf(
                        "season_id" to seasonId.toString(),
                        "mid" to mid.toString(),
                        "bvid" to item.bvid,
                        "type" to "season",
                    ),
                )
                return@forEachIndexed
            }

            // 分P稿件一律算歌单：单视频条目本身并不是歌单
            val pages = view.pages.orEmpty()
            if (pages.size > 1) {
                val playlistId = "bvid:${item.bvid}"
                if (!seen.add(playlistId)) return@forEachIndexed
                playlists += Playlist(
                    id = playlistId,
                    name = cleanTitle(item.title).ifBlank { view.title },
                    cover = cover,
                    trackCount = pages.size,
                    creator = view.owner.name,
                    source = "bilibili",
                    link = "https://www.bilibili.com/video/${item.bvid}",
                    extra = mapOf(
                        "bvid" to item.bvid,
                        "type" to "multipart",
                    ),
                )
            }
        }
        return playlists
    }

    /**
     * 推荐歌单：音乐分区的排行稿件里，合集 / 分P稿就是歌单。
     * 合集的曲目数要单独问一次 archives 接口，只给推荐的头部几条补，控制请求数。
     */
    override suspend fun recommendedPlaylists(cookie: String): List<Playlist> {
        val body = NativeHttp.get(
            BILIBILI_MUSIC_RANKING_API,
            headers = mapOf("Referer" to BilibiliCdn.REFERER),
            cookie = effectiveCookie(cookie),
            userAgent = BilibiliCdn.USER_AGENT,
        )
        val entries = json.decodeFromString<BilibiliRankingResponse>(body).data?.list.orEmpty()
        val playlists = mutableListOf<Playlist>()

        for (entry in entries) {
            if (playlists.size >= RECOMMENDED_PLAYLIST_LIMIT) break
            val seasonId = entry.seasonId
            val mid = entry.owner.mid
            if (seasonId <= 0L || mid <= 0L) continue
            val extra = mapOf(
                "season_id" to seasonId.toString(),
                "mid" to mid.toString(),
                "bvid" to entry.bvid,
                "type" to "season",
            )
            val meta = if (playlists.size < RECOMMENDED_PLAYLIST_META_LIMIT) {
                runCatching { fetchSeasonMeta(cookie, mid, seasonId) }.getOrNull()
            } else {
                null
            }
            val name = meta?.title?.takeIf { it.isNotBlank() }
                ?: cleanTitle(entry.title).ifBlank { entry.bvid }
            playlists += Playlist(
                id = "season:$seasonId:$mid:${entry.bvid}",
                name = name,
                cover = normalizeCover(meta?.cover?.takeIf { it.isNotBlank() } ?: entry.pic),
                trackCount = meta?.total ?: 0,
                playCount = entry.stat.view,
                creator = entry.owner.name,
                description = meta?.description.orEmpty(),
                source = "bilibili",
                link = "https://www.bilibili.com/video/${entry.bvid}",
                extra = extra,
            )
        }
        return playlists
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val bvid = Regex("(BV\\w+)").find(link)?.groupValues?.getOrNull(1)
            ?: throw IllegalArgumentException("invalid bilibili link: bvid not found")

        var page = 1
        Regex("[?&]p=(\\d+)").find(link)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { page = it }

        val view = fetchView(cookie, bvid)
        val pages = view.pages.orEmpty()
        if (view.ugcSeason != null || pages.size > 1) {
            throw IllegalStateException("playlist link detected")
        }
        if (pages.size <= 1) {
            val pageList = runCatching { fetchPageList(cookie, bvid) }.getOrNull()
            if (pageList != null && pageList.size > 1) {
                throw IllegalStateException("playlist link detected")
            }
        }
        if (pages.isEmpty()) throw IllegalStateException("no video pages found")
        if (page > pages.size) page = 1

        val targetPage = pages[page - 1]
        var displayTitle = targetPage.part
        if (pages.size == 1 && displayTitle.isEmpty()) {
            displayTitle = view.title
        } else if (displayTitle != view.title) {
            displayTitle = "${view.title} - $displayTitle"
        }

        val cover = normalizeCover(view.pic)
        val cid = targetPage.cid.toString()
        val isVip = isVipAccount(cookie)
        val audioUrl = runCatching { fetchAudioUrl(cookie, bvid, cid, isVip) }.getOrDefault("")

        return Song(
            id = "${view.bvid}|${targetPage.cid}",
            name = displayTitle,
            artist = view.owner.name,
            album = view.bvid,
            duration = targetPage.duration,
            source = "bilibili",
            url = audioUrl,
            cover = cover,
            link = "https://www.bilibili.com/video/${view.bvid}?p=$page",
            extra = mapOf(
                "bvid" to view.bvid,
                "cid" to cid,
            ),
        )
    }

    override suspend fun downloadUrl(cookie: String, song: Song, quality: AudioQuality): String {
        if (song.source != "bilibili") throw IllegalArgumentException("source mismatch")
        if (quality == AudioQuality.Auto && song.url.isNotEmpty()) return song.url

        val (bvid, cid) = bilibiliSongIdentity(song)
        val isVip = isVipAccount(cookie)
        if (quality == AudioQuality.Auto) return fetchAudioUrl(cookie, bvid, cid, isVip)

        // 指定档位：只认这一档的 DASH 音频 id，拿不到就报错，不退到低档
        if (!BILIBILI_QUALITY_DEFINITIONS.containsKey(quality)) {
            throw IllegalArgumentException("unsupported bilibili audio quality: $quality")
        }
        val candidates = fetchAudioCandidates(cookie, bvid, cid, isVip)
        return candidates.firstOrNull { it.option.value == quality }?.url
            ?: throw IllegalStateException("bilibili audio quality $quality is unavailable")
    }

    /** 探测账号能拿到的档：拿公开音乐视频的 playurl 回包里实际出现的档位去重。 */
    override suspend fun availableQualityOptions(cookie: String): List<AudioQualityOption> {
        availableQualityCache[cookie]?.let { return it }
        val isVip = isVipAccount(cookie)
        val candidates = runCatching {
            fetchAudioCandidates(cookie, BILIBILI_QUALITY_PROBE_BVID, BILIBILI_QUALITY_PROBE_CID, isVip)
        }.getOrNull() ?: return emptyList()
        val seen = mutableSetOf<AudioQuality>()
        val options = candidates.mapNotNull { candidate ->
            if (!seen.add(candidate.option.value)) null else candidate.option
        }
        availableQualityCache[cookie] = options
        return options
    }

    private fun bilibiliSongIdentity(song: Song): Pair<String, String> {
        var bvid = song.extra["bvid"].orEmpty()
        var cid = song.extra["cid"].orEmpty()
        if (bvid.isEmpty() || cid.isEmpty()) {
            val parts = song.id.split("|")
            if (parts.size == 2) {
                bvid = parts[0]
                cid = parts[1]
            } else {
                throw IllegalArgumentException("invalid id structure")
            }
        }
        return bvid to cid
    }

    /** 指定档位时不能走基类的 song.url 短路（那是自动选的最高档），必须重新按档取流。 */
    override suspend fun playableStream(cookie: String, song: Song, workDir: File, quality: AudioQuality): PlayableStream {
        val url = if (quality == AudioQuality.Auto) {
            song.url.ifBlank { downloadUrl(cookie, song, AudioQuality.Auto) }
        } else {
            downloadUrl(cookie, song, quality)
        }
        val option = BILIBILI_QUALITY_DEFINITIONS[quality]?.option
        return PlayableStream(
            url = url,
            // 自动档时 fetchAudioUrl 挑的是「Hi-Res > 全景声 > 最高 DASH 档 > durl」，
            // 挑中了哪一档没随地址带下来，而 DASH 音频在 B 站一律是 fMP4（地址后缀 .m4s，
            // extensionFromUrl 认不出来），后缀为空就按 m4a 兜，别让下载文件命名成 .m4s
            ext = option?.format ?: song.ext.ifBlank { "m4a" },
            bitrate = option?.bitrate?.takeIf { it > 0 } ?: song.bitrate,
        )
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.source != "bilibili") throw IllegalArgumentException("source mismatch")
        // The Go provider has no lyric endpoint and returns an empty string
        // (bridge.go wires Bilibili to emptyLyrics); BILIBILI_INFO.lyrics is false.
        return ""
    }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> {
        if (id.startsWith("season:")) {
            val parts = id.split(":")
            if (parts.size < 3) throw IllegalArgumentException("invalid season id")
            val seasonId = parts[1].toLongOrNull() ?: 0L
            val mid = parts[2].toLongOrNull() ?: 0L
            val bvid = if (parts.size >= 4) parts[3] else ""

            if (bvid.isNotEmpty()) {
                val view = runCatching { fetchView(cookie, bvid) }.getOrNull()
                if (view != null) {
                    val season = view.ugcSeason
                    if (season != null) {
                        var seasonTitle = season.title
                        var seasonCover = season.cover
                        var archiveIndex: Map<String, BilibiliSeasonArchiveMeta> = emptyMap()
                        val seasonArchive = runCatching {
                            fetchSeasonArchiveIndex(cookie, mid, seasonId)
                        }.getOrNull()
                        if (seasonArchive != null) {
                            archiveIndex = seasonArchive.index
                            if (seasonTitle.isEmpty()) seasonTitle = seasonArchive.seasonTitle
                            if (seasonCover.isEmpty()) seasonCover = seasonArchive.seasonCover
                        }
                        val songs = buildSongsFromSeasonSections(
                            season.sections.orEmpty(),
                            seasonTitle,
                            seasonCover,
                            view.owner.name,
                            archiveIndex,
                        )
                        if (songs.isNotEmpty()) return songs
                    }
                }
            }
            return fetchSeasonSongs(cookie, mid, seasonId)
        }

        val bvid = id.removePrefix("bvid:")
        if (bvid.isEmpty()) throw IllegalArgumentException("invalid playlist id")
        val view = fetchView(cookie, bvid)
        val rootTitle = view.title
        var pages = view.pages.orEmpty()
        if (pages.size <= 1) {
            val pageList = runCatching { fetchPageList(cookie, bvid) }.getOrNull()
            if (pageList != null && pageList.isNotEmpty()) pages = pageList
        }
        if (pages.isEmpty()) throw IllegalStateException("no video pages found")
        return buildSongsFromPages(bvid, rootTitle, view.pic, pages)
    }

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        val bvid = Regex("(BV\\w+)").find(link)?.groupValues?.getOrNull(1)
        if (bvid != null) {
            val view = fetchView(cookie, bvid)
            val season = view.ugcSeason
            if (season != null) {
                val seasonId = season.id
                val mid = view.owner.mid
                val sections = season.sections.orEmpty()
                var trackCount = countSeasonEpisodes(sections)
                var playlist = Playlist(
                    id = "season:$seasonId:$mid:$bvid",
                    name = season.title,
                    cover = normalizeCover(season.cover),
                    trackCount = trackCount,
                    playCount = season.stat.view,
                    creator = view.owner.name,
                    description = season.intro,
                    source = "bilibili",
                    link = "https://www.bilibili.com/video/$bvid",
                    extra = mapOf(
                        "season_id" to seasonId.toString(),
                        "mid" to mid.toString(),
                        "bvid" to bvid,
                        "type" to "season",
                    ),
                )

                var seasonTitle = season.title
                var seasonCover = season.cover
                var archiveIndex: Map<String, BilibiliSeasonArchiveMeta> = emptyMap()
                val seasonArchive = runCatching {
                    fetchSeasonArchiveIndex(cookie, mid, seasonId)
                }.getOrNull()
                if (seasonArchive != null) {
                    archiveIndex = seasonArchive.index
                    if (seasonTitle.isEmpty()) seasonTitle = seasonArchive.seasonTitle
                    if (seasonCover.isEmpty()) seasonCover = seasonArchive.seasonCover
                    if (trackCount == 0) {
                        // Matches Go: len(index) counts both the bvid and cid keys.
                        trackCount = archiveIndex.size
                        playlist = playlist.copy(trackCount = trackCount)
                    }
                }

                val songs = buildSongsFromSeasonSections(
                    sections,
                    seasonTitle,
                    seasonCover,
                    view.owner.name,
                    archiveIndex,
                )
                if (songs.isNotEmpty()) return CollectionResult(playlist, songs)
                return CollectionResult(playlist, fetchSeasonSongs(cookie, mid, seasonId))
            }

            val pages = view.pages.orEmpty()
            if (pages.size > 1) {
                val playlist = Playlist(
                    id = "bvid:$bvid",
                    name = view.title,
                    cover = normalizeCover(view.pic),
                    trackCount = pages.size,
                    creator = view.owner.name,
                    source = "bilibili",
                    link = "https://www.bilibili.com/video/$bvid",
                    extra = mapOf(
                        "bvid" to bvid,
                        "type" to "multipart",
                    ),
                )
                val songs = buildSongsFromPages(bvid, view.title, view.pic, pages)
                return CollectionResult(playlist, songs)
            }

            if (pages.size == 1) {
                val playlist = Playlist(
                    id = "bvid:$bvid",
                    name = view.title,
                    cover = normalizeCover(view.pic),
                    trackCount = 1,
                    creator = view.owner.name,
                    source = "bilibili",
                    link = "https://www.bilibili.com/video/$bvid",
                    extra = mapOf(
                        "bvid" to bvid,
                        "type" to "single",
                    ),
                )
                val songs = buildSongsFromPages(bvid, view.title, view.pic, pages)
                return CollectionResult(playlist, songs)
            }
        }
        throw IllegalArgumentException("invalid bilibili playlist link")
    }

    override suspend fun createLogin(type: String): QRCreateResult {
        val body = NativeHttp.get(
            BILIBILI_QR_GENERATE_API,
            headers = mapOf("Referer" to BilibiliCdn.REFERER),
            randomIp = true,
            userAgent = BilibiliCdn.USER_AGENT,
        )
        val response = json.decodeFromString<BilibiliQrGenerateResponse>(body)
        val data = response.data ?: BilibiliQrGenerateData()
        if (response.code != 0 || data.qrCodeKey.trim().isEmpty()) {
            throw IllegalStateException(
                "bilibili qr generate api error: code=${response.code} message=${response.message}"
            )
        }

        val sessionId = NativeProviders.newSessionId(info.id)
        val now = System.currentTimeMillis()
        pruneLoginSessions(now)
        loginSessions[sessionId] = BilibiliLoginSession(
            key = data.qrCodeKey,
            createdAt = now,
            expiresAt = now + QR_SESSION_TTL_MS,
        )
        return QRCreateResult(
            sessionId = sessionId,
            session = QRSession(
                key = data.qrCodeKey,
                url = data.url,
                imageUrl = "",
            ),
        )
    }

    override suspend fun checkLogin(sessionId: String, key: String): QRCheckResult {
        if (!loginSessions.containsKey(sessionId)) {
            throw IllegalStateException("login session not found")
        }
        val qrKey = key.trim()
        if (qrKey.isEmpty()) throw IllegalArgumentException("bilibili qr login key is empty")

        val response = NativeHttp.getResponse(
            "$BILIBILI_QR_POLL_API?qrcode_key=${encode(qrKey)}",
            headers = mapOf("Referer" to BilibiliCdn.REFERER),
            userAgent = BilibiliCdn.USER_AGENT,
        )
        val payload = json.decodeFromString<BilibiliQrPollResponse>(response.body)
        val data = payload.data ?: BilibiliQrPollData()
        val status = mapBilibiliQrStatus(data.code)
        val message = firstNonEmpty(data.message, payload.message)

        if (status == "success") {
            val cookie = joinCookies(response.cookies)
            loginSessions.remove(sessionId)
            // Go resets its cached VIP state after a successful login.
            vipCache.clear()
            return QRCheckResult(status = status, message = message, cookie = cookie)
        }
        if (status == "expired" || status == "failed") loginSessions.remove(sessionId)
        return QRCheckResult(status = status, message = message, cookie = "")
    }

    // ---- Bilibili API helpers ----

    private suspend fun fetchView(cookie: String, bvid: String): BilibiliViewData {
        // The Go implementation sends User-Agent and Cookie but no Referer here.
        val body = NativeHttp.get(
            "$BILIBILI_VIEW_API?bvid=$bvid",
            cookie = effectiveCookie(cookie),
            userAgent = BilibiliCdn.USER_AGENT,
        )
        return json.decodeFromString<BilibiliViewResponse>(body).data ?: BilibiliViewData()
    }

    private suspend fun fetchPageList(cookie: String, bvid: String): List<BilibiliPage> {
        val body = NativeHttp.get(
            "$BILIBILI_PAGE_LIST_API?bvid=$bvid",
            headers = mapOf("Referer" to BilibiliCdn.REFERER),
            cookie = effectiveCookie(cookie),
            userAgent = BilibiliCdn.USER_AGENT,
        )
        val response = json.decodeFromString<BilibiliPageListResponse>(body)
        if (response.code != 0) {
            throw IllegalStateException("bilibili pagelist api error: ${response.code}")
        }
        return response.data.orEmpty()
    }

    private suspend fun fetchSeasonArchiveIndex(
        cookie: String,
        mid: Long,
        seasonId: Long,
    ): BilibiliSeasonArchiveIndex {
        if (seasonId == 0L || mid == 0L) throw IllegalArgumentException("invalid season info")

        val index = mutableMapOf<String, BilibiliSeasonArchiveMeta>()
        var processedArchives = 0
        var seasonTitle = ""
        var seasonCover = ""
        var pageNum = 1

        while (pageNum <= seasonPageLimit(seasonId, mid)) {
            val data = fetchSeasonPage(cookie, mid, seasonId, pageNum)
            if (seasonTitle.isEmpty()) seasonTitle = data.meta?.title.orEmpty()
            if (seasonCover.isEmpty()) seasonCover = data.meta?.cover.orEmpty()

            val archives = data.episodes
            if (archives.isEmpty()) break

            for (archive in archives) {
                val meta = BilibiliSeasonArchiveMeta(
                    title = archive.title,
                    cover = normalizeCover(archive.cover),
                    duration = archive.duration,
                )
                if (archive.bvid.isNotEmpty()) index["bvid:${archive.bvid}"] = meta
                if (archive.cid != 0L) index["cid:${archive.cid}"] = meta
            }
            processedArchives += archives.size

            val total = data.page.total
            if (total == 0 || processedArchives >= total) break
            pageNum++
        }
        return BilibiliSeasonArchiveIndex(index, seasonTitle, seasonCover)
    }

    private suspend fun fetchSeasonSongs(cookie: String, mid: Long, seasonId: Long): List<Song> {
        if (seasonId == 0L || mid == 0L) throw IllegalArgumentException("invalid season info")

        val season: BilibiliSeasonArchivePage = fetchSeasonArchives(cookie, mid, seasonId)
        if (season.episodes.isEmpty()) return emptyList()
        val cids = resolveEpisodeCids(cookie, season.episodes)

        return season.episodes.mapIndexedNotNull { index, episode ->
            val cid = cids.getOrNull(index) ?: return@mapIndexedNotNull null
            val cover = episode.cover.ifEmpty { season.cover }
            val title = BilibiliTitleParser.parse(episode.title, "")
            Song(
                id = "${episode.bvid}|$cid",
                name = title.firstOrNull()?.title?.ifBlank { episode.title } ?: episode.title,
                artist = title.firstNotNullOfOrNull { it.artist.takeIf(String::isNotBlank) }.orEmpty(),
                album = season.title,
                duration = episode.duration,
                source = "bilibili",
                cover = normalizeCover(cover),
                link = "https://www.bilibili.com/video/${episode.bvid}",
                extra = mapOf(
                    "bvid" to episode.bvid,
                    "cid" to cid.toString(),
                ),
            )
        }
    }

    /** 合集的全部分P连同标题、封面；合集动辄上千集，只翻前面几页。 */
    private suspend fun fetchSeasonArchives(
        cookie: String,
        mid: Long,
        seasonId: Long,
    ): BilibiliSeasonArchivePage {
        val episodes = mutableListOf<BilibiliSeasonArchive>()
        var title = ""
        var cover = ""
        var processedArchives = 0
        var pageNum = 1
        while (pageNum <= seasonPageLimit(seasonId, mid)) {
            val data = fetchSeasonPage(cookie, mid, seasonId, pageNum)
            if (title.isEmpty()) title = data.meta?.title.orEmpty()
            if (cover.isEmpty()) cover = data.meta?.cover.orEmpty()
            val archives = data.episodes
            if (archives.isEmpty()) break
            archives.filterTo(episodes) { it.bvid.isNotEmpty() }
            processedArchives += archives.size
            val total = data.page.total
            if (total == 0 || processedArchives >= total) break
            pageNum++
        }
        return BilibiliSeasonArchivePage(title = title, cover = cover, episodes = episodes)
    }

    /** 每集的分P cid：polymer 接口不给，只能逐条问视频详情；并发拉，否则一整个歌单要等几十秒。 */
    private suspend fun resolveEpisodeCids(
        cookie: String,
        episodes: List<BilibiliSeasonArchive>,
    ): List<Long?> = coroutineScope {
        val semaphore = Semaphore(6)
        episodes.map { episode ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    episode.cid.takeIf { it != 0L } ?: runCatching {
                        fetchEpisodeCid(cookie, episode.bvid, episode.page, episode.title)
                    }.getOrNull()?.takeIf { it != 0L }
                }
            }
        }.map { it.await() }
    }

    /** 分页上限：合集动辄几百集，全翻完一次歌单要几十个请求，取前 [SEASON_SONG_LIMIT] 首就够。 */
    private fun seasonPageLimit(seasonId: Long, mid: Long): Int {
        val size = BILIBILI_SEASON_PAGE_SIZE
        return maxOf(1, (SEASON_SONG_LIMIT + size - 1) / size)
    }

    private suspend fun fetchSeasonPage(
        cookie: String,
        mid: Long,
        seasonId: Long,
        pageNum: Int,
    ): BilibiliSeasonData {
        val url = "$BILIBILI_SEASON_API?mid=$mid&season_id=$seasonId" +
            "&sort_reverse=false&page_num=$pageNum&page_size=$BILIBILI_SEASON_PAGE_SIZE"
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to BilibiliCdn.REFERER),
            cookie = effectiveCookie(cookie),
            userAgent = BilibiliCdn.USER_AGENT,
        )
        val response = json.decodeFromString<BilibiliSeasonResponse>(body)
        if (response.code != 0) {
            throw IllegalStateException("bilibili season api error: ${response.code}")
        }
        val data = response.data ?: BilibiliSeasonData()
        // polymer 接口不返回每集的分P信息，也没有 cid；标题/封面/时长都在 archives 上
        return data.copy(
            episodes = data.archives.orEmpty().mapIndexed { index, archive ->
                BilibiliSeasonArchive(
                    bvid = archive.bvid,
                    title = archive.title,
                    cover = archive.pic,
                    duration = archive.duration,
                    page = (pageNum - 1) * BILIBILI_SEASON_PAGE_SIZE + index + 1,
                )
            },
        )
    }

    /** 合集头部资料（标题、封面、曲目数）：排行里只给了合集 id，曲目数得单独问一次。 */
    private suspend fun fetchSeasonMeta(cookie: String, mid: Long, seasonId: Long): BilibiliSeasonMeta? {
        val url = "$BILIBILI_SEASON_META_API?mid=$mid&season_id=$seasonId" +
            "&sort_reverse=false&page_num=1&page_size=1"
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to BilibiliCdn.REFERER),
            cookie = effectiveCookie(cookie),
            userAgent = BilibiliCdn.USER_AGENT,
        )
        val response = json.decodeFromString<BilibiliSeasonResponse>(body)
        if (response.code != 0) return null
        return response.data?.meta
    }

    /**
     * 合集分P的 cid：polymer 的 archives 里没有，得靠视频详情。
     * 优先按标题在分P里找（合集收录的就是某一个分P），找不到才按合集里的顺序取第 N 个分P。
     * 都分不出就不放进来——宁可少一首，也不把某一集播成同一稿件的别的分P。
     */
    private suspend fun fetchEpisodeCid(
        cookie: String,
        bvid: String,
        page: Int,
        title: String,
    ): Long {
        val pages = fetchPageList(cookie, bvid)
        if (pages.isEmpty()) return 0L
        val byTitle = pages.firstOrNull { it.part.isNotEmpty() && sameTitle(it.part, title) }
        if (byTitle != null) return byTitle.cid
        return pages.getOrNull(page - 1)?.cid ?: 0L
    }

    private fun sameTitle(a: String, b: String): Boolean {
        val left = BilibiliTitleParser.keyOf(a)
        return left.isNotEmpty() && left == BilibiliTitleParser.keyOf(b)
    }

    /** 分P的 part 是上传文件名时不能用，退回稿件标题。 */
    private fun singlePageTitle(rootTitle: String, part: String): String {
        if (BilibiliTitleParser.isUploadFileName(part)) return rootTitle
        return part.ifEmpty { rootTitle }
    }

    /**
     * 单 P 稿件的曲目信息：稿件标题是 UP 主的宣传文案（「【4K修复】周杰伦 - 晴天MV」），
     * 直接当歌名列表里很难看，聚合也认不出来，所以按标题拆回歌名 + 歌手。
     */
    private fun displayTitle(rootTitle: String, part: String, keyword: String = ""): String {
        val raw = singlePageTitle(rootTitle, part)
        return chooseTrack(raw, keyword).title.ifBlank { raw }
    }

    /**
     * `晴天-周杰伦` 和 `周杰伦-晴天` 都能拆通，得挑一个：检索词是「歌名 歌手」或只有歌名，
     * 所以标题和检索词第一个词对得上的那种解读更可能是歌名。
     */
    private fun chooseTrack(rawTitle: String, keyword: String): BilibiliTrackTitle {
        val parsed = BilibiliTitleParser.parse(rawTitle, "")
        if (parsed.size <= 1) return parsed.firstOrNull() ?: BilibiliTrackTitle(rawTitle, "")
        val tokens = keyword.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return parsed.first()
        val song = tokens.first()
        return parsed.firstOrNull { it.title == song }
            ?: parsed.firstOrNull { it.title.isNotEmpty() && (it.title.contains(song) || song.contains(it.title)) }
            ?: parsed.first()
    }

    /**
     * 匹配换源时检索词就是「歌名 歌手」；这种词搜出来的东西全部由这个歌手相关，
     * 标题又拆不出歌手时用它兜底。聚合搜索的词只有歌名，这里返回 null。
     */
    private fun keywordArtist(keyword: String): String? {
        val tokens = keyword.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.size < 2) return null
        val candidate = tokens.last()
        if (candidate.length > 16 || candidate.any { it.isDigit() }) return null
        return candidate
    }

    private suspend fun fetchAudioUrl(
        cookie: String,
        bvid: String,
        cid: String,
        isVip: Boolean,
    ): String {
        val fnval = if (isVip) 4048 else 80
        val url = "$BILIBILI_PLAY_URL_API?fnval=$fnval&qn=127&bvid=$bvid&cid=$cid"
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to BilibiliCdn.REFERER),
            cookie = effectiveCookie(cookie),
            userAgent = BilibiliCdn.USER_AGENT,
        )
        // Go ignores JSON unmarshal errors here, treating a bad body as an empty response.
        val response = runCatching {
            json.decodeFromString<BilibiliPlayUrlResponse>(body)
        }.getOrNull() ?: BilibiliPlayUrlResponse()
        val data = response.data ?: BilibiliPlayUrlData()

        val dash = data.dash

        // 1. Hi-Res FLAC is specifically marked by id 30251.
        val flacAudio = dash?.flac?.audio
        if (flacAudio != null && flacAudio.id == 30251 && flacAudio.baseUrl.isNotEmpty()) {
            return flacAudio.baseUrl
        }

        // 2. Dolby Atmos is specifically marked by id 30250.
        dash?.dolby?.audio.orEmpty().firstOrNull {
            it.id == 30250 && it.baseUrl.isNotEmpty()
        }?.let { return it.baseUrl }

        // 3. Highest-id standard DASH audio stream.
        var bestUrl = ""
        var highestId = -1
        for (audio in dash?.audio.orEmpty()) {
            if (audio.id > highestId && audio.baseUrl.isNotEmpty()) {
                highestId = audio.id
                bestUrl = audio.baseUrl
            }
        }
        if (bestUrl.isNotEmpty()) return bestUrl

        // 4. Fallback to the unsegmented DURL format.
        val durl = data.durl.orEmpty()
        if (durl.isNotEmpty()) return durl.first().url

        throw IllegalStateException("no audio found")
    }

    /** 拉一次 playurl，把 flac / dolby / dash 三处的音频流都摊平成候选，供按档位选择。 */
    private suspend fun fetchAudioCandidates(
        cookie: String,
        bvid: String,
        cid: String,
        isVip: Boolean,
    ): List<BilibiliAudioCandidate> {
        if (bvid.isBlank() || cid.isBlank()) throw IllegalArgumentException("bilibili bvid or cid is empty")
        val fnval = if (isVip) 4048 else 80
        val url = "$BILIBILI_PLAY_URL_API?fnval=$fnval&qn=127&bvid=$bvid&cid=$cid"
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to BilibiliCdn.REFERER),
            cookie = effectiveCookie(cookie),
            userAgent = BilibiliCdn.USER_AGENT,
        )
        val response = json.decodeFromString<BilibiliPlayUrlResponse>(body)
        if (response.code != 0) {
            throw IllegalStateException("bilibili playurl api error: ${response.code}")
        }
        val data = response.data ?: BilibiliPlayUrlData()
        val dash = data.dash
        val candidates = mutableListOf<BilibiliAudioCandidate>()
        dash?.flac?.audio?.let { audio -> bilibiliCandidateOrNull(audio)?.let { candidates += it } }
        for (audio in dash?.dolby?.audio.orEmpty()) bilibiliCandidateOrNull(audio)?.let { candidates += it }
        for (audio in dash?.audio.orEmpty()) bilibiliCandidateOrNull(audio)?.let { candidates += it }
        if (candidates.isEmpty()) {
            // durl 无独立分档，按标准音质处理
            val option = BILIBILI_QUALITY_DEFINITIONS.getValue(AudioQuality.Standard).option
            for (durl in data.durl.orEmpty()) {
                if (durl.url.isBlank()) continue
                candidates += BilibiliAudioCandidate(option, durl.url, 0)
            }
        }
        return candidates.sortedWith(
            compareByDescending<BilibiliAudioCandidate> { BILIBILI_QUALITY_DEFINITIONS[it.option.value]?.rank ?: 0 }
                .thenByDescending { it.id },
        )
    }

    private fun bilibiliCandidateOrNull(audio: BilibiliAudioStream): BilibiliAudioCandidate? {
        val baseUrl = audio.baseUrl.trim()
        if (baseUrl.isEmpty()) return null
        val quality = bilibiliQualityForId(audio.id) ?: return null
        val option = BILIBILI_QUALITY_DEFINITIONS[quality]?.option ?: return null
        return BilibiliAudioCandidate(option, baseUrl, audio.id)
    }

    private suspend fun isVipAccount(cookie: String): Boolean {
        val effective = effectiveCookie(cookie)
        val cacheKey = NativeCrypto.md5Hex(effective)
        vipCache[cacheKey]?.let { return it }

        val body = runCatching {
            NativeHttp.get(
                BILIBILI_NAV_API,
                headers = mapOf("Referer" to BilibiliCdn.REFERER),
                cookie = effective,
                userAgent = BilibiliCdn.USER_AGENT,
            )
        }.getOrNull() ?: return false

        val response = runCatching {
            json.decodeFromString<BilibiliNavResponse>(body)
        }.getOrNull() ?: return false

        val data = response.data ?: BilibiliNavData()
        val isVip = response.code == 0 &&
            data.isLogin &&
            data.vipStatus == 1 &&
            data.vipType > 0
        vipCache[cacheKey] = isVip
        return isVip
    }

    override suspend fun accountInfo(cookie: String): AccountInfo? {
        val effective = effectiveCookie(cookie)
        if (effective.isBlank()) return null
        val body = runCatching {
            NativeHttp.get(
                BILIBILI_NAV_API,
                headers = mapOf("Referer" to BilibiliCdn.REFERER),
                cookie = effective,
                userAgent = BilibiliCdn.USER_AGENT,
            )
        }.getOrNull() ?: return null
        val response = runCatching { json.decodeFromString<BilibiliNavResponse>(body) }.getOrNull() ?: return null
        val data = response.data ?: return null
        if (response.code != 0 || !data.isLogin || data.uname.isBlank()) return null
        val membership = data.vipLabel.ifBlank {
            if (data.vipStatus == 1 && data.vipType > 0) "大会员" else ""
        }
        val detail = listOfNotNull(
            membership.takeIf { it.isNotBlank() },
            data.levelInfo.currentLevel.takeIf { it > 0 }?.let { "Lv.$it" },
        ).joinToString(" · ")
        return AccountInfo(nickname = data.uname, avatar = data.face, detail = detail)
    }

    private fun buildSongsFromPages(
        bvid: String,
        rootTitle: String,
        cover: String,
        pages: List<BilibiliPage>,
    ): List<Song> {
        val normalizedCover = normalizeCover(cover)
        val songs = mutableListOf<Song>()
        pages.forEachIndexed { index, page ->
            // 单 P 时 page.part 往往和稿件标题一样，拼起来就成了「晴天 - 晴天」
            val name = if (pages.size == 1) displayTitle(rootTitle, page.part) else page.part.ifEmpty { rootTitle }
            songs += Song(
                id = "$bvid|${page.cid}",
                name = name,
                artist = chooseTrack(singlePageTitle(rootTitle, page.part), "").artist,
                album = rootTitle.takeIf { pages.size > 1 }.orEmpty(),
                duration = page.duration,
                source = "bilibili",
                cover = normalizedCover,
                link = "https://www.bilibili.com/video/$bvid?p=${index + 1}",
                extra = mapOf(
                    "bvid" to bvid,
                    "cid" to page.cid.toString(),
                ),
            )
        }
        return songs
    }

    private fun buildSongsFromSeasonSections(
        sections: List<BilibiliSeasonSection>,
        seasonTitle: String,
        seasonCover: String,
        artistName: String,
        archiveIndex: Map<String, BilibiliSeasonArchiveMeta>,
    ): List<Song> {
        val artist = artistName.ifEmpty { seasonTitle }
        val songs = mutableListOf<Song>()
        for (section in sections) {
            for (episode in section.episodes.orEmpty()) {
                var cover = episode.cover
                val meta = archiveIndex["bvid:${episode.bvid}"]
                    ?: if (episode.cid != 0L) archiveIndex["cid:${episode.cid}"] else null

                if (cover.isEmpty()) cover = episode.arc.pic
                if (cover.isEmpty()) {
                    cover = seasonCover
                    if (cover.isEmpty() && meta != null) cover = meta.cover
                }
                cover = normalizeCover(cover)

                var duration = episode.duration
                if (duration == 0) duration = episode.page.duration
                if (duration == 0) duration = episode.arc.duration
                if (duration == 0 && meta != null) duration = meta.duration

                var name = episode.title
                if (name.isEmpty()) name = episode.arc.title
                if (name.isEmpty()) name = episode.page.part
                if (name.isEmpty() && meta != null) name = meta.title

                songs += Song(
                    id = "${episode.bvid}|${episode.cid}",
                    name = name,
                    artist = artist,
                    album = episode.bvid,
                    duration = duration,
                    source = "bilibili",
                    cover = cover,
                    link = "https://www.bilibili.com/video/${episode.bvid}",
                    extra = mapOf(
                        "bvid" to episode.bvid,
                        "cid" to episode.cid.toString(),
                    ),
                )
            }
        }
        return songs
    }

    private fun countSeasonEpisodes(sections: List<BilibiliSeasonSection>): Int {
        var count = 0
        for (section in sections) count += section.episodes.orEmpty().size
        return count
    }

    private fun pruneLoginSessions(now: Long) {
        val iterator = loginSessions.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.createdAt > LOGIN_SESSION_TTL_MS) {
                iterator.remove()
            }
        }
    }

    private fun mapBilibiliQrStatus(code: Int): String = when (code) {
        0 -> "success"
        86038 -> "expired"
        86090 -> "scanned"
        86101 -> "waiting"
        else -> "failed"
    }

    private fun firstNonEmpty(vararg values: String): String {
        for (value in values) {
            if (value.trim().isNotEmpty()) return value.trim()
        }
        return ""
    }

    private fun joinCookies(cookies: Map<String, String>): String =
        cookies.entries
            .map { "${it.key}=${it.value}" }
            .sorted()
            .joinToString("; ")

    private fun cleanTitle(title: String): String {
        if (title.isEmpty()) return ""
        return title
            .replace("<em class=\"keyword\">", "")
            .replace("</em>", "")
    }

    private fun normalizeCover(cover: String): String =
        if (cover.startsWith("//")) "https:$cover" else cover

    private fun effectiveCookie(cookie: String): String =
        if (cookie.isEmpty()) BILIBILI_DEFAULT_COOKIE else cookie

    private fun searchUrl(keyword: String): String =
        // Go url.Values.Encode() sorts keys: keyword, page, page_size, search_type.
        "$BILIBILI_SEARCH_API?keyword=${encode(keyword)}&page=1&page_size=20&search_type=video"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private data class BilibiliLoginSession(
        val key: String,
        val createdAt: Long,
        val expiresAt: Long,
    )

    private data class BilibiliSeasonArchiveMeta(
        val title: String,
        val cover: String,
        val duration: Int,
    )

    private data class BilibiliSeasonArchiveIndex(
        val index: Map<String, BilibiliSeasonArchiveMeta>,
        val seasonTitle: String,
        val seasonCover: String,
    )
}

private const val BILIBILI_QUALITY_PROBE_BVID = "BV1zYWB6qED9"
private const val BILIBILI_QUALITY_PROBE_CID = "43575806705"

/** 一个 DASH 音频候选：档位、地址、流的音频 id（同档时 id 越大越好）。 */
private data class BilibiliAudioCandidate(val option: AudioQualityOption, val url: String, val id: Int)

private data class BilibiliQualityDefinition(val option: AudioQualityOption, val rank: Int)

/**
 * Bilibili 的音频档，从高到低。rank 只用于候选排序，和 ID 映射表一起照搬
 * music-lib 的 bilibili/quality.go；标签/格式/码率一字不差。
 */
private val BILIBILI_QUALITY_DEFINITIONS: Map<AudioQuality, BilibiliQualityDefinition> = mapOf(
    AudioQuality.HiRes to BilibiliQualityDefinition(
        AudioQualityOption(AudioQuality.HiRes, "Hi-Res 无损", "flac", vip = true),
        5,
    ),
    AudioQuality.Atmos to BilibiliQualityDefinition(
        AudioQualityOption(AudioQuality.Atmos, "全景声", "m4a", vip = true),
        4,
    ),
    AudioQuality.High to BilibiliQualityDefinition(
        AudioQualityOption(AudioQuality.High, "高品质", "m4a", 192),
        3,
    ),
    AudioQuality.Standard to BilibiliQualityDefinition(
        AudioQualityOption(AudioQuality.Standard, "标准音质", "m4a", 132),
        2,
    ),
    AudioQuality.Low to BilibiliQualityDefinition(
        AudioQualityOption(AudioQuality.Low, "低品质", "m4a", 64),
        1,
    ),
)

internal val BILIBILI_QUALITY_LADDER: AudioQualityLadder = AudioQualityLadder.of(
    listOf(
        AudioQuality.HiRes,
        AudioQuality.Atmos,
        AudioQuality.High,
        AudioQuality.Standard,
        AudioQuality.Low,
    ).map { BILIBILI_QUALITY_DEFINITIONS.getValue(it).option },
)

/** DASH 音频 id → 档位，映射照搬 music-lib 的 bilibiliQualityForID。 */
private fun bilibiliQualityForId(id: Int): AudioQuality? = when (id) {
    30251 -> AudioQuality.HiRes
    30250 -> AudioQuality.Atmos
    30280 -> AudioQuality.High
    30232 -> AudioQuality.Standard
    30216 -> AudioQuality.Low
    else -> null
}

@Serializable
private data class BilibiliSearchResponse(
    val code: Int = 0,
    val message: String = "",
    val data: BilibiliSearchData? = null,
)

@Serializable
private data class BilibiliSearchData(
    val result: List<BilibiliSearchItem>? = null,
)

@Serializable
private data class BilibiliSearchItem(
    val bvid: String = "",
    val title: String = "",
    val author: String = "",
    val pic: String = "",
    /** 搜索结果自带总时长，形如 `4:30` / `1:02:03` 的字符串；视频详情拿不到时用它。 */
    val duration: String = "",
) {
    val durationSec: Int get() = parseDurationText(duration)
}

@Serializable
private data class BilibiliViewResponse(
    val code: Int = 0,
    val message: String = "",
    val data: BilibiliViewData? = null,
)

@Serializable
private data class BilibiliViewData(
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    val owner: BilibiliOwner = BilibiliOwner(),
    val pages: List<BilibiliPage>? = null,
    @SerialName("ugc_season") val ugcSeason: BilibiliUgcSeason? = null,
)

@Serializable
private data class BilibiliOwner(
    val name: String = "",
    val mid: Long = 0,
)

@Serializable
private data class BilibiliPage(
    val cid: Long = 0,
    val page: Int = 0,
    val part: String = "",
    val duration: Int = 0,
)

@Serializable
private data class BilibiliUgcSeason(
    val id: Long = 0,
    val title: String = "",
    val cover: String = "",
    val intro: String = "",
    val stat: BilibiliUgcStat = BilibiliUgcStat(),
    val sections: List<BilibiliSeasonSection>? = null,
)

@Serializable
private data class BilibiliUgcStat(
    val view: Long = 0,
)

@Serializable
private data class BilibiliSeasonSection(
    val episodes: List<BilibiliSeasonEpisode>? = null,
)

@Serializable
private data class BilibiliSeasonEpisode(
    val bvid: String = "",
    val cid: Long = 0,
    val title: String = "",
    val cover: String = "",
    val duration: Int = 0,
    val arc: BilibiliEpisodeArc = BilibiliEpisodeArc(),
    val page: BilibiliEpisodePage = BilibiliEpisodePage(),
)

@Serializable
private data class BilibiliEpisodeArc(
    val pic: String = "",
    val title: String = "",
    val duration: Int = 0,
)

@Serializable
private data class BilibiliEpisodePage(
    val part: String = "",
    val duration: Int = 0,
)

@Serializable
private data class BilibiliPageListResponse(
    val code: Int = 0,
    val data: List<BilibiliPage>? = null,
)

@Serializable
private data class BilibiliSeasonResponse(
    val code: Int = 0,
    val message: String = "",
    val data: BilibiliSeasonData? = null,
)

@Serializable
private data class BilibiliSeasonData(
    val meta: BilibiliSeasonMeta? = null,
    val page: BilibiliSeasonPage = BilibiliSeasonPage(),
    /** polymer 原始回包：`pic` 字段，没有 cid。 */
    val archives: List<BilibiliPolymerArchive>? = null,
    /** [fetchSeasonPage] 归一化之后的分P列表，调用方只认这个。 */
    val episodes: List<BilibiliSeasonArchive> = emptyList(),
)

@Serializable
private data class BilibiliSeasonMeta(
    @SerialName("season_id") val seasonId: Long = 0,
    val title: String = "",
    val cover: String = "",
    val description: String = "",
    val total: Int = 0,
    val mid: Long = 0,
)

@Serializable
private data class BilibiliPolymerArchive(
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    val duration: Int = 0,
    val ctime: Long = 0,
)

@Serializable
private data class BilibiliSeasonInfo(
    val id: Long = 0,
    val title: String = "",
    val cover: String = "",
    val intro: String = "",
)

@Serializable
private data class BilibiliSeasonPage(
    val total: Int = 0,
)

@Serializable
private data class BilibiliSeasonArchive(
    val bvid: String = "",
    val title: String = "",
    val cover: String = "",
    val duration: Int = 0,
    val cid: Long = 0,
    /** 在这个合集里的第几 P；polymer 接口不返回 cid，只按它去找分P。 */
    val page: Int = 0,
)

private data class BilibiliSeasonArchivePage(
    val title: String = "",
    val cover: String = "",
    val episodes: List<BilibiliSeasonArchive> = emptyList(),
)

@Serializable
private data class BilibiliRankingResponse(
    val code: Int = 0,
    val data: BilibiliRankingData? = null,
)

@Serializable
private data class BilibiliRankingData(
    val list: List<BilibiliRankingEntry>? = null,
)

@Serializable
private data class BilibiliRankingEntry(
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    @SerialName("season_id") val seasonId: Long = 0,
    val owner: BilibiliOwner = BilibiliOwner(),
    val stat: BilibiliUgcStat = BilibiliUgcStat(),
)

@Serializable
private data class BilibiliPlayUrlResponse(
    val code: Int = 0,
    val data: BilibiliPlayUrlData? = null,
)

@Serializable
private data class BilibiliPlayUrlData(
    val durl: List<BilibiliDurl>? = null,
    val dash: BilibiliDash? = null,
)

@Serializable
private data class BilibiliDurl(
    val url: String = "",
)

@Serializable
private data class BilibiliDash(
    val audio: List<BilibiliAudioStream>? = null,
    val flac: BilibiliFlac? = null,
    val dolby: BilibiliDolby? = null,
)

@Serializable
private data class BilibiliFlac(
    val audio: BilibiliAudioStream? = null,
)

@Serializable
private data class BilibiliDolby(
    val audio: List<BilibiliAudioStream>? = null,
)

@Serializable
private data class BilibiliAudioStream(
    val id: Int = 0,
    val baseUrl: String = "",
)

@Serializable
private data class BilibiliNavResponse(
    val code: Int = 0,
    val data: BilibiliNavData? = null,
)

@Serializable
private data class BilibiliNavData(
    val isLogin: Boolean = false,
    @SerialName("vipStatus") val vipStatus: Int = 0,
    @SerialName("vipType") val vipType: Int = 0,
    @SerialName("vip_label") val vipLabel: String = "",
    val uname: String = "",
    val face: String = "",
    @SerialName("level_info") val levelInfo: BilibiliNavLevel = BilibiliNavLevel(),
)

@Serializable
private data class BilibiliNavLevel(@SerialName("current_level") val currentLevel: Int = 0)

@Serializable
private data class BilibiliQrGenerateResponse(
    val code: Int = 0,
    val message: String = "",
    val data: BilibiliQrGenerateData? = null,
)

@Serializable
private data class BilibiliQrGenerateData(
    val url: String = "",
    @SerialName("qrcode_key") val qrCodeKey: String = "",
)

@Serializable
private data class BilibiliQrPollResponse(
    val code: Int = 0,
    val message: String = "",
    val data: BilibiliQrPollData? = null,
)

@Serializable
private data class BilibiliQrPollData(
    val url: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    val timestamp: Long = 0,
    val code: Int = 0,
    val message: String = "",
)
