package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.QRCheckResult
import com.mytv.mymusic.data.QRCreateResult
import com.mytv.mymusic.data.QRSession
import com.mytv.mymusic.data.Song
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

private val BILIBILI_INFO = ProviderInfo(
    id = "bilibili",
    name = "Bilibili",
    search = true,
    download = true,
    parseSong = true,
    searchPlaylist = true,
    playlistSongs = true,
    parsePlaylist = true,
    qrLogin = true,
)

private const val BILIBILI_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0"
private const val BILIBILI_REFERER = "https://www.bilibili.com/"
private const val BILIBILI_DEFAULT_COOKIE =
    "buvid3=2E109C72-251F-3827-FA8E-921FA0D7EC5291319infoc; SESSDATA=your_sessdata;"

private const val BILIBILI_SEARCH_API = "https://api.bilibili.com/x/web-interface/search/type"
private const val BILIBILI_VIEW_API = "https://api.bilibili.com/x/web-interface/view"
private const val BILIBILI_PAGE_LIST_API = "https://api.bilibili.com/x/player/pagelist"
private const val BILIBILI_PLAY_URL_API = "https://api.bilibili.com/x/player/playurl"
private const val BILIBILI_SEASON_API = "https://api.bilibili.com/x/space/ugc/season"
private const val BILIBILI_NAV_API = "https://api.bilibili.com/x/web-interface/nav"
private const val BILIBILI_QR_GENERATE_API =
    "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
private const val BILIBILI_QR_POLL_API =
    "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"

private const val BILIBILI_SEASON_PAGE_SIZE = 30
private const val QR_SESSION_TTL_MS = 3 * 60 * 1000L
private const val LOGIN_SESSION_TTL_MS = 10 * 60 * 1000L

class BilibiliProvider : BaseNativeProvider(BILIBILI_INFO) {
    private val json = NativeHttp.json
    private val vipCache = ConcurrentHashMap<String, Boolean>()
    private val loginSessions = ConcurrentHashMap<String, BilibiliLoginSession>()

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        val body = NativeHttp.get(
            searchUrl(keyword),
            headers = mapOf("Referer" to BILIBILI_REFERER),
            cookie = effectiveCookie(cookie),
            userAgent = BILIBILI_USER_AGENT,
        )
        val response = json.decodeFromString<BilibiliSearchResponse>(body)
        val songs = mutableListOf<Song>()
        for (item in response.data?.result.orEmpty()) {
            val rootTitle = cleanTitle(item.title)
            val view = runCatching { fetchView(cookie, item.bvid) }.getOrNull() ?: continue
            val pages = view.pages.orEmpty()
            if (pages.isEmpty()) continue

            val cover = normalizeCover(item.pic)
            val page = pages.first()
            var displayTitle = page.part
            if (displayTitle.isEmpty()) {
                displayTitle = rootTitle
            } else if (displayTitle != rootTitle) {
                displayTitle = "$rootTitle - $displayTitle"
            }

            songs += Song(
                id = "${item.bvid}|${page.cid}",
                name = displayTitle,
                artist = item.author,
                album = item.bvid,
                duration = page.duration,
                source = "bilibili",
                cover = cover,
                link = "https://www.bilibili.com/video/${item.bvid}?p=1",
                extra = mapOf(
                    "bvid" to item.bvid,
                    "cid" to page.cid.toString(),
                ),
            )
        }
        return songs
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

    override suspend fun downloadUrl(cookie: String, song: Song): String {
        if (song.source != "bilibili") throw IllegalArgumentException("source mismatch")
        if (song.url.isNotEmpty()) return song.url

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

        // Go stores the resolved URL on the Song; Kotlin Song is immutable, so the
        // URL is returned to the caller instead of mutating Song.Ext/url in place.
        val isVip = isVipAccount(cookie)
        return fetchAudioUrl(cookie, bvid, cid, isVip)
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.source != "bilibili") throw IllegalArgumentException("source mismatch")
        // The Go provider has no lyric endpoint and returns an empty string
        // (bridge.go wires Bilibili to emptyLyrics); BILIBILI_INFO.lyrics is false.
        return ""
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val body = NativeHttp.get(
            searchUrl(keyword),
            headers = mapOf("Referer" to BILIBILI_REFERER),
            cookie = effectiveCookie(cookie),
            userAgent = BILIBILI_USER_AGENT,
        )
        val response = json.decodeFromString<BilibiliSearchResponse>(body)
        val seen = mutableSetOf<String>()
        val playlists = mutableListOf<Playlist>()

        for (item in response.data?.result.orEmpty()) {
            val view = runCatching { fetchView(cookie, item.bvid) }.getOrNull() ?: continue
            val cover = normalizeCover(item.pic)
            val season = view.ugcSeason
            if (season != null) {
                val seasonId = season.id
                val mid = view.owner.mid
                val key = "season:$seasonId:$mid:${item.bvid}"
                if (!seen.add(key)) continue

                var seasonName = season.title
                if (seasonName.isEmpty()) seasonName = cleanTitle(item.title)
                var seasonCover = season.cover
                if (seasonCover.isEmpty()) seasonCover = cover

                val sections = season.sections.orEmpty()
                var trackCount = countSeasonEpisodes(sections)
                if (trackCount == 0 && seasonId != 0L && mid != 0L) {
                    val seasonSongs = runCatching { fetchSeasonSongs(cookie, mid, seasonId) }.getOrNull()
                    if (seasonSongs != null) trackCount = seasonSongs.size
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
                continue
            }

            val pages = view.pages.orEmpty()
            if (pages.size > 1) {
                val playlistId = "bvid:${item.bvid}"
                if (!seen.add(playlistId)) continue
                playlists += Playlist(
                    id = playlistId,
                    name = cleanTitle(item.title),
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
        return buildSongsFromPages(bvid, rootTitle, view.owner.name, view.pic, pages)
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
                val songs = buildSongsFromPages(bvid, view.title, view.owner.name, view.pic, pages)
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
                val songs = buildSongsFromPages(bvid, view.title, view.owner.name, view.pic, pages)
                return CollectionResult(playlist, songs)
            }
        }
        throw IllegalArgumentException("invalid bilibili playlist link")
    }

    override suspend fun createLogin(type: String): QRCreateResult {
        val body = NativeHttp.get(
            BILIBILI_QR_GENERATE_API,
            headers = mapOf("Referer" to BILIBILI_REFERER),
            randomIp = true,
            userAgent = BILIBILI_USER_AGENT,
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
            headers = mapOf("Referer" to BILIBILI_REFERER),
            userAgent = BILIBILI_USER_AGENT,
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
            userAgent = BILIBILI_USER_AGENT,
        )
        return json.decodeFromString<BilibiliViewResponse>(body).data ?: BilibiliViewData()
    }

    private suspend fun fetchPageList(cookie: String, bvid: String): List<BilibiliPage> {
        val body = NativeHttp.get(
            "$BILIBILI_PAGE_LIST_API?bvid=$bvid",
            headers = mapOf("Referer" to BILIBILI_REFERER),
            cookie = effectiveCookie(cookie),
            userAgent = BILIBILI_USER_AGENT,
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

        while (true) {
            val data = fetchSeasonPage(cookie, mid, seasonId, pageNum)
            if (seasonTitle.isEmpty()) seasonTitle = data.season.title
            if (seasonCover.isEmpty()) seasonCover = data.season.cover

            val archives = data.archives.orEmpty()
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

        val songs = mutableListOf<Song>()
        var processedArchives = 0
        var seasonTitle = ""
        var seasonCover = ""
        var pageNum = 1

        while (true) {
            val data = fetchSeasonPage(cookie, mid, seasonId, pageNum)
            if (seasonTitle.isEmpty()) seasonTitle = data.season.title
            if (seasonCover.isEmpty()) seasonCover = data.season.cover

            val archives = data.archives.orEmpty()
            if (archives.isEmpty()) break

            for (archive in archives) {
                if (archive.cid == 0L) continue
                var cover = archive.cover
                if (cover.isEmpty()) cover = seasonCover
                songs += Song(
                    id = "${archive.bvid}|${archive.cid}",
                    name = archive.title,
                    artist = "",
                    album = seasonTitle,
                    duration = archive.duration,
                    source = "bilibili",
                    cover = normalizeCover(cover),
                    link = "https://www.bilibili.com/video/${archive.bvid}",
                    extra = mapOf(
                        "bvid" to archive.bvid,
                        "cid" to archive.cid.toString(),
                    ),
                )
            }
            processedArchives += archives.size

            val total = data.page.total
            if (total == 0 || processedArchives >= total) break
            pageNum++
        }
        return songs
    }

    private suspend fun fetchSeasonPage(
        cookie: String,
        mid: Long,
        seasonId: Long,
        pageNum: Int,
    ): BilibiliSeasonData {
        val url = "$BILIBILI_SEASON_API?mid=$mid&season_id=$seasonId" +
            "&page_num=$pageNum&page_size=$BILIBILI_SEASON_PAGE_SIZE"
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to BILIBILI_REFERER),
            cookie = effectiveCookie(cookie),
            userAgent = BILIBILI_USER_AGENT,
        )
        val response = json.decodeFromString<BilibiliSeasonResponse>(body)
        if (response.code != 0) {
            throw IllegalStateException("bilibili season api error: ${response.code}")
        }
        return response.data ?: BilibiliSeasonData()
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
            headers = mapOf("Referer" to BILIBILI_REFERER),
            cookie = effectiveCookie(cookie),
            userAgent = BILIBILI_USER_AGENT,
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

    private suspend fun isVipAccount(cookie: String): Boolean {
        val effective = effectiveCookie(cookie)
        val cacheKey = NativeCrypto.md5Hex(effective)
        vipCache[cacheKey]?.let { return it }

        val body = runCatching {
            NativeHttp.get(
                BILIBILI_NAV_API,
                headers = mapOf("Referer" to BILIBILI_REFERER),
                cookie = effective,
                userAgent = BILIBILI_USER_AGENT,
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
                headers = mapOf("Referer" to BILIBILI_REFERER),
                cookie = effective,
                userAgent = BILIBILI_USER_AGENT,
            )
        }.getOrNull() ?: return null
        val response = runCatching { json.decodeFromString<BilibiliNavResponse>(body) }.getOrNull() ?: return null
        val data = response.data ?: return null
        if (response.code != 0 || !data.isLogin || data.uname.isBlank()) return null
        val detail = listOfNotNull(
            data.levelInfo.currentLevel.takeIf { it > 0 }?.let { "Lv.$it" },
            if (data.vipStatus == 1 && data.vipType > 0) "大会员" else null,
        ).joinToString(" · ")
        return AccountInfo(nickname = data.uname, avatar = data.face, detail = detail)
    }

    private fun buildSongsFromPages(
        bvid: String,
        rootTitle: String,
        author: String,
        cover: String,
        pages: List<BilibiliPage>,
    ): List<Song> {
        val normalizedCover = normalizeCover(cover)
        val songs = mutableListOf<Song>()
        pages.forEachIndexed { index, page ->
            var displayTitle = page.part
            if (pages.size == 1 && displayTitle.isEmpty()) {
                displayTitle = rootTitle
            } else if (displayTitle != rootTitle) {
                displayTitle = "$rootTitle - $displayTitle"
            }
            songs += Song(
                id = "$bvid|${page.cid}",
                name = displayTitle,
                artist = author,
                album = bvid,
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
)

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
    val season: BilibiliSeasonInfo = BilibiliSeasonInfo(),
    val page: BilibiliSeasonPage = BilibiliSeasonPage(),
    val archives: List<BilibiliSeasonArchive>? = null,
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
    val vipStatus: Int = 0,
    val vipType: Int = 0,
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
