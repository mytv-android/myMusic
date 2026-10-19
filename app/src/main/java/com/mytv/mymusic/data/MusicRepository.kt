package com.mytv.mymusic.data

import com.mytv.mymusic.musicbridge.Musicbridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class MusicRepository {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun providers(): List<ProviderInfo> = io { json.decodeFromString(ListSerializer(ProviderInfo.serializer()), Musicbridge.providersJSON()) }
    suspend fun searchSongs(provider: String, cookie: String, keyword: String) = decode<List<Song>>(Musicbridge.searchSongs(provider, cookie, keyword))
    suspend fun parseSong(provider: String, cookie: String, link: String) = decode<Song>(Musicbridge.parseSong(provider, cookie, link))
    suspend fun downloadUrl(song: Song, cookie: String): String = io { Musicbridge.downloadURL(song.source, cookie, json.encodeToString(song)) }
    suspend fun lyrics(song: Song, cookie: String): String = io { Musicbridge.lyrics(song.source, cookie, json.encodeToString(song)) }
    suspend fun searchPlaylists(provider: String, cookie: String, keyword: String) = decode<List<Playlist>>(Musicbridge.searchPlaylists(provider, cookie, keyword))
    suspend fun playlistSongs(provider: String, cookie: String, id: String) = decode<List<Song>>(Musicbridge.playlistSongs(provider, cookie, id))
    suspend fun parsePlaylist(provider: String, cookie: String, link: String) = decode<CollectionResult>(Musicbridge.parsePlaylist(provider, cookie, link))
    suspend fun recommended(provider: String, cookie: String) = decode<List<Playlist>>(Musicbridge.recommendedPlaylists(provider, cookie))
    suspend fun searchAlbums(provider: String, cookie: String, keyword: String) = decode<List<Playlist>>(Musicbridge.searchAlbums(provider, cookie, keyword))
    suspend fun albumSongs(provider: String, cookie: String, id: String) = decode<List<Song>>(Musicbridge.albumSongs(provider, cookie, id))
    suspend fun parseAlbum(provider: String, cookie: String, link: String) = decode<CollectionResult>(Musicbridge.parseAlbum(provider, cookie, link))
    suspend fun categories(provider: String, cookie: String) = decode<List<PlaylistCategory>>(Musicbridge.playlistCategories(provider, cookie))
    suspend fun categoryPlaylists(provider: String, cookie: String, id: String, page: Int, limit: Int) = decode<List<Playlist>>(Musicbridge.categoryPlaylists(provider, cookie, id, page.toLong(), limit.toLong()))
    suspend fun userPlaylists(provider: String, cookie: String, page: Int, limit: Int) = decode<List<Playlist>>(Musicbridge.userPlaylists(provider, cookie, page.toLong(), limit.toLong()))
    suspend fun topLists(provider: String, cookie: String) = decode<List<TopList>>(Musicbridge.topLists(provider, cookie))
    suspend fun topListSongs(provider: String, cookie: String, id: String) = decode<List<Song>>(Musicbridge.topListSongs(provider, cookie, id))
    suspend fun hotSongs(provider: String, cookie: String, limit: Int) = decode<List<Song>>(Musicbridge.hotSongs(provider, cookie, limit.toLong()))
    suspend fun createLogin(provider: String, type: String) = decode<QRCreateResult>(Musicbridge.createQRLogin(provider, type))
    suspend fun checkLogin(sessionId: String, key: String) = decode<QRCheckResult>(Musicbridge.checkQRLogin(sessionId, key))

    private suspend inline fun <reified T> decode(raw: String): T = io {
        json.decodeFromString(raw)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
