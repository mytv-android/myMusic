package com.mytv.mymusic.data.webdav

import com.mytv.mymusic.data.sync.SyncException
import com.mytv.mymusic.data.sync.SyncFailureKind
import com.mytv.mymusic.data.sync.WebDavSyncProvider
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * “信任自签名证书”的指纹钉住：证书都在测试里现生成。[base] 只认 [root]，在这些测试里它就是
 * “系统证书库”——[root] 签发的证书算系统信任，自签名的不算。
 */
class WebDavCertificatePinningTest {
    private val root = HeldCertificate.Builder()
        .certificateAuthority(0)
        .commonName("myMusic Test Root")
        .build()
    private val systemTrust = HandshakeCertificates.Builder()
        .addTrustedCertificate(root.certificate)
        .build()
    private val base = OkHttpClient.Builder()
        .sslSocketFactory(systemTrust.sslSocketFactory(), systemTrust.trustManager)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val dav = FakeWebDavServer()
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.close()
        base.connectionPool.evictAll()
    }

    @Test
    fun pinnedSelfSignedCertificateIsAcceptedEvenWhenItsNameDoesNotMatch() = runBlocking {
        // 自签名证书常常只写了随便起的名字：指纹对上就以指纹为准
        val certificate = selfSigned("nas.local")
        serve(certificate)

        assertNotNull(client(account(pinned = certificate)).stat(""))
        assertTrue(dav.requests.isNotEmpty())
    }

    @Test
    fun changedCertificateFailsWithTypedErrorBeforeAnyRequest() {
        val trusted = selfSigned("nas.local")
        val swapped = selfSigned("nas.local")
        serve(swapped)

        val error = failure { client(account(pinned = trusted)).stat("") }

        assertEquals(WebDavErrorKind.CertificateChanged, error.kind)
        assertEquals(WebDavCertificate.sha256(swapped.certificate), error.certificate?.sha256)
        assertFalse(error.certificate!!.systemTrusted)
        // 握手阶段就停了：服务器一个请求都没收到，带密码的 Authorization 头也就没发出去
        assertEquals(0, server.requestCount)
        assertTrue(WebDavMessages.describe(error, account()).startsWith("服务器证书变了，和上次信任的不一样"))
    }

    @Test
    fun switchOnWithoutPinStillRejectsUnknownCertificate() {
        // 开关开着也不再“什么证书都收”：没在测试 / 连接里确认过的自签名证书一律拒绝
        serve(selfSigned("nas.local"))

        val error = failure { client(account(pinned = null)).stat("") }

        assertEquals(WebDavErrorKind.UntrustedCertificate, error.kind)
        assertEquals(0, server.requestCount)
        assertTrue(WebDavMessages.describe(error, account()).contains("也不是本机确认过的那张"))
    }

    @Test
    fun systemTrustedCertificatePassesWithoutPinWithStalePinAndWithSwitchOff() = runBlocking {
        serve(issuedByRoot("localhost"), root.certificate)

        assertNotNull(client(account(pinned = null)).stat(""))
        // 记下的是另一张（比如以前的自签名证书），换成系统认的证书后照样放行
        assertNotNull(client(account(pinned = selfSigned("nas.local"))).stat(""))
        assertNotNull(client(account(trust = false)).stat(""))
    }

    @Test
    fun systemTrustedChainIssuedForAnotherHostIsRejectedUnlessPinned() {
        val other = issuedByRoot("other.example")
        serve(other, root.certificate)

        // 证书链系统认，但名字不是这台服务器：开关开着也照样做主机名校验
        val error = failure { client(account(pinned = null)).stat("") }
        assertEquals(WebDavErrorKind.UntrustedCertificate, error.kind)
        assertEquals(0, server.requestCount)

        // 用户在测试连接里看过、确认过的就是这一张
        runBlocking { assertNotNull(client(account(pinned = other)).stat("")) }
    }

    @Test
    fun switchOffKeepsSystemValidation() {
        serve(selfSigned("nas.local"))

        val error = failure { client(account(trust = false)).stat("") }

        assertEquals(WebDavErrorKind.UntrustedCertificate, error.kind)
        assertTrue(WebDavMessages.describe(error, account(trust = false)).contains("可以打开“信任自签名证书”"))
    }

    @Test
    fun probeReadsLeafCertificateWithoutSendingRequest() = runBlocking {
        val certificate = selfSigned("nas.local")
        serve(certificate)

        val observed = client(account(pinned = null)).probeCertificate()

        assertEquals(WebDavCertificate.sha256(certificate.certificate), observed.sha256)
        assertEquals("nas.local", observed.subject)
        assertTrue(observed.selfSigned)
        assertFalse(observed.systemTrusted)
        assertEquals(certificate.certificate.notAfter.time, observed.notAfter)
        assertEquals(64, observed.sha256.length)
        assertEquals(95, observed.fingerprint.length)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun probeMarksSystemTrustedCertificate() = runBlocking {
        serve(issuedByRoot("localhost"), root.certificate)

        val observed = client(account()).probeCertificate()

        assertTrue(observed.systemTrusted)
        assertFalse(observed.selfSigned)
        assertEquals("localhost", observed.subject)
        assertEquals("myMusic Test Root", observed.issuer)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun probeDoesNotCountTrustedChainForAnotherHostAsSystemTrusted() = runBlocking {
        serve(issuedByRoot("other.example"), root.certificate)

        val observed = client(account()).probeCertificate()

        assertFalse(observed.systemTrusted)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun probeThenPinThenSwapIsTheWholeTrustOnFirstUseLoop() = runBlocking {
        val first = selfSigned("nas.local")
        serve(first)
        // 第一次：读到证书，没有记下的 → 信任它
        val observed = client(account(pinned = null)).probeCertificate()
        val decision = WebDavTrust.decide(null, observed)
        assertTrue(decision is WebDavTrust.Decision.Trust)
        val pinned = account().copy(pinnedCertificate = (decision as WebDavTrust.Decision.Trust).certificate)
        assertNotNull(client(pinned).stat(""))
        server.close()

        // 服务器换了证书：后台用记下的指纹连，失败；再读一次证书，判断为“变了”，不会自己换过去
        val swapped = selfSigned("nas.local")
        val next = MockWebServer()
        try {
            next.useHttps(HandshakeCertificates.Builder().heldCertificate(swapped).build().sslSocketFactory())
            next.dispatcher = dav
            next.start()
            val moved = pinned.copy(serverUrl = "https://localhost:${next.port}/dav/")
            val error = failure { client(moved).stat("") }
            assertEquals(WebDavErrorKind.CertificateChanged, error.kind)
            val again = client(moved).probeCertificate()
            val changed = WebDavTrust.decide(moved.pinnedCertificate, again)
            assertTrue(changed is WebDavTrust.Decision.Changed)
            assertEquals(WebDavCertificate.sha256(swapped.certificate), (changed as WebDavTrust.Decision.Changed).observed.sha256)
            assertEquals(0, next.requestCount)
            // 用户确认后改钉新证书，就能连上
            assertNotNull(client(moved.copy(pinnedCertificate = changed.observed)).stat(""))
        } finally {
            next.close()
        }
    }

    @Test
    fun providerTurnsChangedCertificateIntoRetryableFailureWithClearMessage() = runBlocking {
        serve(selfSigned("nas.local"))
        val provider = WebDavSyncProvider { WebDavClient(it, base) }

        val error = try {
            provider.validate(account(pinned = selfSigned("nas.local")).encode())
            null
        } catch (error: SyncException) {
            error
        }

        assertNotNull(error)
        assertEquals(SyncFailureKind.Network, error!!.kind)
        assertTrue(error.message.startsWith("服务器证书变了，和上次信任的不一样"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun decideTrustsFirstUseSamePinAndSystemTrustButStopsOnChange() {
        val pinned = fakeCertificate("AA")
        val same = pinned.copy(subject = "renamed")
        val other = fakeCertificate("BB")

        assertEquals(WebDavTrust.Decision.Trust(other), WebDavTrust.decide(null, other))
        assertEquals(WebDavTrust.Decision.Trust(same), WebDavTrust.decide(pinned, same))
        assertEquals(WebDavTrust.Decision.Changed(other, pinned), WebDavTrust.decide(pinned, other))
        val publicCert = other.copy(systemTrusted = true)
        assertEquals(WebDavTrust.Decision.Trust(publicCert), WebDavTrust.decide(pinned, publicCert))
    }

    @Test
    fun accountKeepsPinLocallyAndDropsItWhenSwitchIsOffOrCleartext() {
        val pin = fakeCertificate("CD")
        val account = WebDavAccount("https://nas.local:5006", "alice", "secret", "myMusic", true, pin)

        val decoded = WebDavAccount.decode(account.encode())
        assertEquals(pin, decoded?.pinnedCertificate)
        assertEquals(pin, account.normalized().pinnedCertificate)
        assertNull(account.copy(trustSelfSigned = false).normalized().pinnedCertificate)
        assertNull(account.copy(serverUrl = "http://nas.local:5005").normalized().pinnedCertificate)
        // 换了证书还是同一个同步位置
        assertEquals(account.locationKey(), account.copy(pinnedCertificate = fakeCertificate("EF")).locationKey())
        assertFalse(account.toString().contains("secret"))
        assertTrue(account.sameEndpoint(account.copy(serverUrl = "https://nas.local:5006/other/")))
        assertFalse(account.sameEndpoint(account.copy(serverUrl = "https://nas.local:5007/")))
    }

    @Test
    fun certificateLineShowsFingerprintSubjectIssuerAndExpiry() {
        val notAfter = Calendar.getInstance(TimeZone.getDefault()).apply { clear(); set(2027, Calendar.MARCH, 15) }.timeInMillis
        val now = Calendar.getInstance(TimeZone.getDefault()).apply { clear(); set(2026, Calendar.NOVEMBER, 9) }.timeInMillis
        val certificate = fakeCertificate("AB").copy(notAfter = notAfter)

        assertEquals(
            "证书指纹 ${"AB:".repeat(31)}AB（颁发给 nas.local，自签名，2027-03 到期）",
            WebDavMessages.certificate(certificate, now),
        )
        assertEquals(
            "证书指纹 ${"AB:".repeat(31)}AB（颁发给 nas.local，由 Home CA 签发，已于 2027-03 过期）",
            WebDavMessages.certificate(certificate.copy(selfSigned = false, issuer = "Home CA"), notAfter + 1),
        )
    }

    private fun serve(certificate: HeldCertificate, vararg chain: X509Certificate) {
        val handshake = HandshakeCertificates.Builder().heldCertificate(certificate, *chain).build()
        server.useHttps(handshake.sslSocketFactory())
        server.dispatcher = dav
        server.start()
    }

    private fun selfSigned(name: String): HeldCertificate =
        HeldCertificate.Builder().commonName(name).addSubjectAlternativeName(name).build()

    private fun issuedByRoot(name: String): HeldCertificate =
        HeldCertificate.Builder().commonName(name).addSubjectAlternativeName(name).signedBy(root).build()

    // 地址写 localhost：MockWebServer 自己给的主机名是反查出来的，Windows 上可能是别的名字
    private fun account(trust: Boolean = true, pinned: HeldCertificate? = null) = WebDavAccount(
        serverUrl = "https://localhost:${server.port}/dav/",
        username = "alice",
        password = "secret",
        directory = "myMusic",
        trustSelfSigned = trust,
        pinnedCertificate = pinned?.let { WebDavCertificate.of(it.certificate, systemTrusted = false) },
    )

    private fun client(account: WebDavAccount) = WebDavClient(account, base)

    private fun failure(block: suspend () -> Unit): WebDavException = runBlocking {
        try {
            block()
            null
        } catch (error: WebDavException) {
            error
        }
    } ?: throw AssertionError("应该失败")

    private fun fakeCertificate(byte: String) = WebDavCertificate(
        sha256 = byte.repeat(32),
        subject = "nas.local",
        issuer = "nas.local",
        notAfter = 1_900_000_000_000L,
        selfSigned = true,
    )
}
