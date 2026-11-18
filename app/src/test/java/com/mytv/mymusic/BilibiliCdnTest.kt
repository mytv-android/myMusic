package com.mytv.mymusic

import com.mytv.mymusic.data.native.BilibiliCdn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B 站音频 CDN 的域名判定和防盗链请求头。实测结论（2026-11-18，匿名账号）：
 * `*.bilivideo.com` 上的音频字节不带 `Referer: https://www.bilibili.com/` 就是
 * openresty 的 403，UA 也必须是浏览器档（ExoPlayer / Dalvik 默认 UA 都被挡）。
 */
class BilibiliCdnTest {

    @Test
    fun `covers the cdn hosts playurl actually returns`() {
        assertTrue(BilibiliCdn.covers("cn-sccd-fx-01-03.bilivideo.com"))
        assertTrue(BilibiliCdn.covers("cn-sccd-fx-01-01.bilivideo.com"))
        assertTrue(BilibiliCdn.covers("upos-sz-mirrorcoso1.bilivideo.com"))
    }

    @Test
    fun `covers the bare domain and the cn mirror`() {
        assertTrue(BilibiliCdn.covers("bilivideo.com"))
        assertTrue(BilibiliCdn.covers("bilivideo.cn"))
        assertTrue(BilibiliCdn.covers("upos-sz-mirrorali.bilivideo.cn"))
    }

    @Test
    fun `does not cover the api or the site itself`() {
        // 打接口的请求各自带 Referer，不能被误加一遍；站点域名更不是 CDN
        assertFalse(BilibiliCdn.covers("api.bilibili.com"))
        assertFalse(BilibiliCdn.covers("www.bilibili.com"))
        assertFalse(BilibiliCdn.covers("passport.bilibili.com"))
    }

    @Test
    fun `does not cover other sources cdn hosts`() {
        // music.op.wiki 自己那套 TLS 绕过不能顺带吃到 B 站的 Referer
        assertFalse(BilibiliCdn.covers("music.zr.wiki"))
        assertFalse(BilibiliCdn.covers("music.op.wiki"))
        // akamaized.net 是公用 CDN，不是 B 站专属
        assertFalse(BilibiliCdn.covers("foo.akamaized.net"))
        assertFalse(BilibiliCdn.covers("bilivideo.com.evil.example"))
    }

    @Test
    fun `host matching is case insensitive and tolerates null`() {
        assertTrue(BilibiliCdn.covers("CN-SCCD-FX-01-03.BILIVIDEO.COM"))
        assertFalse(BilibiliCdn.covers(null))
        assertFalse(BilibiliCdn.covers(""))
    }

    @Test
    fun `headers carry the referer and a desktop browser ua`() {
        val headers = BilibiliCdn.headers(
            "https://cn-sccd-fx-01-03.bilivideo.com/upgcxcode/05/67/43575806705/43575806705-1-30077.m4s" +
                "?e=ig8euxZM2rNcNbdlhoNvNC8BqJIzNbfqXBvEqxTE0bdrouxVDEnA6JRV3TGlMhxXPo&" +
                "mid=0&deadline=1&uipk=0&trid=1&platform=pc&gen=playurl&os=bcache&oi=1"
        )
        assertEquals("https://www.bilibili.com/", headers["Referer"])
        assertTrue(headers["User-Agent"]!!.startsWith("Mozilla/5.0"))
        assertFalse(headers["User-Agent"]!!.contains("ExoPlayerLib"))
        assertEquals(2, headers.size)
    }

    @Test
    fun `headers are empty for anything that is not the bilibili cdn`() {
        assertTrue(BilibiliCdn.headers("https://music.zr.wiki/track/1.m4a").isEmpty())
        assertTrue(BilibiliCdn.headers("https://api.bilibili.com/x/player/playurl?bvid=BV1").isEmpty())
        assertTrue(BilibiliCdn.headers("file:///storage/emulated/0/Music/a.mp3").isEmpty())
        assertTrue(BilibiliCdn.headers("").isEmpty())
    }

    @Test
    fun `headers parse the host out of urls with ports and userinfo`() {
        assertTrue(BilibiliCdn.coversUrl("https://cn-sccd-fx-01-03.bilivideo.com:443/a.m4s?e=1"))
        assertTrue(BilibiliCdn.coversUrl("https://user:pw@cn-sccd-fx-01-03.bilivideo.com/a.m4s"))
        assertFalse(BilibiliCdn.coversUrl("not-a-url"))
        assertFalse(BilibiliCdn.coversUrl(""))
    }
}
