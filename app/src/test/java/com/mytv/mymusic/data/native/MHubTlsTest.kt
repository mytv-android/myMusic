package com.mytv.mymusic.data.native

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MHub 的"不验证证书"必须严格圈在两个域名里：路由漏一个域名，那个域名的请求就会撞回
 * 系统默认客户端然后握手失败；多圈一个域名，就等于把不该关的 TLS 也关了。
 */
class MHubTlsTest {

    private fun bypasses(scheme: String?, host: String?) =
        MHubTls.bypassesCertificateValidation(scheme, host)

    @Test
    fun `mhub hosts cover both the api and the audio cdn`() {
        assertTrue(MHubTls.hosts.contains("music.op.wiki"))
        assertTrue(MHubTls.hosts.contains("music.zr.wiki"))
    }

    @Test
    fun `only https mhub urls bypass certificate validation`() {
        assertTrue(bypasses("https", "music.op.wiki"))
        assertTrue(bypasses("https", "music.zr.wiki"))
        // 主机名不区分大小写
        assertTrue(bypasses("https", "MUSIC.OP.WIKI"))
    }

    @Test
    fun `everything else keeps the default client`() {
        assertFalse(bypasses("https", "music.163.com"))
        assertFalse(bypasses("https", "y.qq.com"))
        // 明文不碰：走明文说明本来就没在验证证书
        assertFalse(bypasses("http", "music.op.wiki"))
        // 长得像但不是这两个主机
        assertFalse(bypasses("https", "evil-music.op.wiki.attacker.net"))
        assertFalse(bypasses("https", null))
        assertFalse(bypasses(null, "music.op.wiki"))
        // 本地文件
        assertFalse(bypasses("file", null))
        assertFalse(bypasses("content", "media.external"))
    }
}
