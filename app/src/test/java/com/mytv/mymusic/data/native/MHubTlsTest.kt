package com.mytv.mymusic.data.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * music.op.wiki 的 TLS 兜底：随包的那份 ISRG 根必须是完整、自签、仍在有效期内的，
 * 否则换根的时候把 base64 弄断一行，线上就是全线握手失败、而日志只会说"证书不受信任"。
 */
class MHubTlsTest {

    @Test
    fun `bundled root is the ISRG Root X1 and self signed`() {
        val cert = MHubTls.anchor
        assertEquals("ISRG Root X1", cert.subjectX500Principal.name.substringAfter("CN=").substringBefore(","))
        assertTrue("根证书必须是自签的", cert.subjectX500Principal == cert.issuerX500Principal)
        // 自签校验：公钥能验自己的签名，说明整份 PEM 是完整的
        cert.verify(cert.publicKey)
    }

    @Test
    fun `bundled root has not expired`() {
        val remaining = MHubTls.anchor.notAfter.time - System.currentTimeMillis()
        assertTrue("根证书已过期或即将过期，剩 ${TimeUnit.DAYS.convert(remaining, TimeUnit.MILLISECONDS)} 天", remaining > 0)
    }

    @Test
    fun `mhub hosts cover both the api and the audio cdn`() {
        assertTrue(MHubTls.hosts.contains("music.op.wiki"))
        assertTrue(MHubTls.hosts.contains("music.zr.wiki"))
        assertFalse(MHubTls.hosts.contains("music.163.com"))
    }

    @Test
    fun `installing the default ssl socket factory is idempotent`() {
        MHubTls.installDefaultSslSocketFactory()
        val first = HttpsURLConnection.getDefaultSSLSocketFactory()
        MHubTls.installDefaultSslSocketFactory()
        assertSame(first, HttpsURLConnection.getDefaultSSLSocketFactory())
    }
}
