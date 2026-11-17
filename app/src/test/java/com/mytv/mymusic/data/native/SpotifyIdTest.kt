package com.mytv.mymusic.data.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spotify 的 ID 解析与凭据解析。分享链接的形态很杂（裸 ID、uri、open/play 域名、
 * embed 与 intl- 前缀），解析错就会把用户粘的链接判成无效。
 */
class SpotifyIdTest {

    @Test
    fun `bare id passes through`() {
        assertEquals("4cOdK2wGL76kbP7Dk5RPjL", extractSpotifyId("4cOdK2wGL76kbP7Dk5RPjL", "track"))
    }

    @Test
    fun `spotify uri is accepted`() {
        assertEquals("4cOdK2wGL76kbP7Dk5RPjL", extractSpotifyId("spotify:track:4cOdK2wGL76kbP7Dk5RPjL", "track"))
    }

    @Test
    fun `open and play hosts are accepted`() {
        assertEquals(
            "4cOdK2wGL76kbP7Dk5RPjL",
            extractSpotifyId("https://open.spotify.com/track/4cOdK2wGL76kbP7Dk5RPjL?si=abc", "track"),
        )
        assertEquals(
            "37i9dQZEVXbMDoHDwVN2tF",
            extractSpotifyId("https://play.spotify.com/playlist/37i9dQZEVXbMDoHDwVN2tF", "playlist"),
        )
    }

    @Test
    fun `embed and intl prefixes are stripped`() {
        assertEquals(
            "4cOdK2wGL76kbP7Dk5RPjL",
            extractSpotifyId("https://open.spotify.com/embed/track/4cOdK2wGL76kbP7Dk5RPjL", "track"),
        )
        assertEquals(
            "4cOdK2wGL76kbP7Dk5RPjL",
            extractSpotifyId("https://open.spotify.com/intl-zh/track/4cOdK2wGL76kbP7Dk5RPjL", "track"),
        )
    }

    @Test
    fun `wrong entity type is rejected`() {
        val error = runCatching { extractSpotifyId("spotify:album:4cOdK2wGL76kbP7Dk5RPjL", "track") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("期望 spotify track，实际是 album", error?.message)
    }

    @Test
    fun `foreign hosts are rejected`() {
        val error = runCatching {
            extractSpotifyId("https://music.163.com/song?id=1", "track")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `blank input is rejected`() {
        val error = runCatching { extractSpotifyId("   ", "track") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `config accepts key value pairs`() {
        val config = parseSpotifyConfig("client_id=abc; client_secret=def; market=cn")
        assertEquals("abc", config["client_id"])
        assertEquals("def", config["client_secret"])
        assertEquals("cn", config["market"])
    }

    @Test
    fun `config normalizes key spellings`() {
        // 键名只做小写与 -/空格转下划线，不拆驼峰：refreshToken 归一成 refreshtoken
        val config = parseSpotifyConfig("Client-Id=abc&refreshToken=xyz")
        assertEquals("abc", config["client_id"])
        assertEquals("xyz", config["refreshtoken"])
    }

    @Test
    fun `config accepts json`() {
        val config = parseSpotifyConfig("""{"client_id":"abc","client_secret":"def","market":"jp"}""")
        assertEquals("abc", config["client_id"])
        assertEquals("def", config["client_secret"])
        assertEquals("jp", config["market"])
    }

    @Test
    fun `blank config yields nothing`() {
        assertTrue(parseSpotifyConfig("").isEmpty())
        assertTrue(parseSpotifyConfig("not a config at all").isEmpty())
    }
}
