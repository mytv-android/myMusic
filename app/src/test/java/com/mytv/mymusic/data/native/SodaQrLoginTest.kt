package com.mytv.mymusic.data.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** 汽水扫码登录状态机：分支与参数对齐 libresoda qr_login.rs，用假签名页网关覆盖。 */
class SodaQrLoginTest {

    private class FakeGateway : SodaQrGateway {
        val requests = mutableListOf<Triple<String, String, String?>>()
        val headersList = mutableListOf<Map<String, String>>()
        val responses = ArrayDeque<SodaPageResponse>()
        val harvested = mutableListOf<List<String>>()
        var harvestValue = ""
        var closed = false
        val beginTokens = mutableListOf<String>()
        var verifyResult = false
        var verifyShown = 0
        var verifyDecision = ""
        var verifyGeneral = ""

        override suspend fun beginSession(msToken: String) {
            beginTokens += msToken
        }

        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): SodaPageResponse {
            requests += Triple(method, url, body)
            headersList += headers
            return responses.removeFirstOrNull() ?: SodaPageResponse(200, "{}", "")
        }

        override suspend fun harvestCookies(urls: List<String>): String {
            harvested += urls
            return harvestValue
        }

        override suspend fun showSecondVerify(decisionJson: String, generalParamsJson: String): Boolean {
            verifyShown += 1
            verifyDecision = decisionJson
            verifyGeneral = generalParamsJson
            return verifyResult
        }

        override fun close() {
            closed = true
        }
    }

    private fun qrBody(data: String) = SodaPageResponse(200, """{"message":"success","data":$data}""", "")

    private fun newSession(): SodaQrSession = SodaQrSession().apply { token = "tok123" }

    @Test
    fun createReturnsOfficialScanUrlVerbatim() = runBlocking {
        val gateway = FakeGateway()
        val indexUrl = "https://bff-pc.qishui.com/ucenter_web/app/sdk-next?aid=386088&token=abc&uc_sdk=scan-auth"
        gateway.responses += qrBody(
            """{"token":"tok123","error_code":0,"qrcode_index_url":"$indexUrl","qrcode":"data:image/png;base64,xx"}""",
        )
        val session = SodaQrSession()
        val created = SodaQrLogin.create(gateway, session)

        assertEquals(indexUrl, created.scanUrl)
        assertEquals("tok123", session.token)
        assertEquals(session.msToken, gateway.beginTokens.single())
        val url = gateway.requests.single().second
        assertTrue(url.startsWith("https://api.qishui.com/passport/web/get_qrcode/?"))
        assertTrue(url.contains("aid=386088"))
        // msToken 末尾的 "==" 会被 query 编码成 %3D%3D
        assertTrue(url.contains("msToken=${session.msToken.dropLast(2)}%3D%3D"))
        assertTrue(url.contains("device_id=${session.deviceId}"))
        assertTrue(url.contains("install_id=${session.installId}"))
        assertTrue(url.contains("did=${session.deviceId}"))
        assertTrue(url.contains("iid=${session.installId}"))
        assertTrue(url.contains("passport_jssdk_version=2.4.13"))
        // request_host 预转义后再走一遍 query 编码（与 libresoda Params.encode 语义一致）
        assertTrue(url.contains("request_host=app%253A%252F%252Fresources"))
        assertTrue(url.contains("next=https%3A%2F%2Fapi.qishui.com"))
    }

    @Test
    fun createSurfacesServerErrorDescription() = runBlocking {
        val gateway = FakeGateway()
        gateway.responses += qrBody("""{"token":"","error_code":5,"description":"访问太频繁"}""")
        val error = runCatching { SodaQrLogin.create(gateway, SodaQrSession()) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("访问太频繁", error?.message)
    }

    @Test
    fun createRejectsScanUrlWithoutToken() = runBlocking {
        val gateway = FakeGateway()
        gateway.responses += qrBody(
            """{"token":"tok123","error_code":0,"qrcode_index_url":"https://bff-pc.qishui.com/ucenter_web/app/sdk-next?aid=386088"}""",
        )
        val error = runCatching { SodaQrLogin.create(gateway, SodaQrSession()) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("汽水二维码缺少登录 token", error?.message)
    }

    @Test
    fun checkMapsWaitingAndScanned() = runBlocking {
        val gateway = FakeGateway()
        val session = newSession()
        gateway.responses += qrBody("""{"error_code":0,"status":1}""")
        assertEquals("waiting", SodaQrLogin.check(gateway, session).status)
        assertEquals(1, gateway.requests.size)

        session.lastCheckMs = 0
        gateway.responses += qrBody("""{"error_code":0,"status":2}""")
        val scanned = SodaQrLogin.check(gateway, session)
        assertEquals("scanned", scanned.status)
        assertEquals("已扫码，请在手机上确认", scanned.message)
        assertTrue(session.everScanned)
    }

    @Test
    fun rateLimitOnlyCoolsDownAndNeverFails() = runBlocking {
        val gateway = FakeGateway()
        val session = newSession()
        gateway.responses += qrBody("""{"error_code":7,"description":"访问太频繁，请稍后再试","status":1}""")
        val first = SodaQrLogin.check(gateway, session)
        assertEquals("waiting", first.status)
        assertTrue(session.cooldownUntilMs > System.currentTimeMillis())
        // 冷却期内再轮询：直接回缓存结果，不再打服务端
        val second = SodaQrLogin.check(gateway, session)
        assertEquals("waiting", second.status)
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun rateLimitAfterScanReportsScanned() = runBlocking {
        val gateway = FakeGateway()
        val session = newSession().apply { everScanned = true }
        gateway.responses += qrBody("""{"error_code":7,"description":"访问太频繁","status":1}""")
        assertEquals("scanned", SodaQrLogin.check(gateway, session).status)
    }

    @Test
    fun expiredStopsPolling() = runBlocking {
        val gateway = FakeGateway()
        gateway.responses += qrBody("""{"error_code":2,"description":"二维码已失效","status":0}""")
        val result = SodaQrLogin.check(gateway, newSession())
        assertEquals("expired", result.status)
    }

    @Test
    fun secondVerifyCancelledStaysWaiting() = runBlocking {
        val gateway = FakeGateway()
        gateway.verifyResult = false
        gateway.responses += qrBody("""{"error_code":2046,"description":"需要二次验证","status":2}""")
        val result = SodaQrLogin.check(gateway, newSession())
        assertEquals("waiting", result.status)
        assertTrue(result.message.contains("取消"))
        assertEquals(1, gateway.verifyShown)
    }

    @Test
    fun secondVerifyDoneResendsWithBizParams() = runBlocking {
        val gateway = FakeGateway()
        gateway.verifyResult = true
        gateway.verifyDecision = """{"error_code":2046,"status":2,"biz_params":"{\"ticket\":\"t1\"}"}"""
        gateway.responses += qrBody(
            """{"error_code":2046,"status":2,"biz_params":"{\"ticket\":\"t1\"}"}""",
        )
        gateway.responses += qrBody("""{"error_code":0,"status":3,"sessionid":"sid9"}""")
        val result = SodaQrLogin.check(gateway, newSession())
        assertEquals("success", result.status)
        assertTrue(result.cookie.contains("sessionid=sid9"))
        assertEquals(1, gateway.verifyShown)
        val resend = gateway.requests.last()
        assertTrue(resend.second.contains("isResend=true"))
        assertTrue((resend.third ?: "").contains("ticket=t1"))
    }

    @Test
    fun secondVerifyStillRequiredKeepsWaiting() = runBlocking {
        val gateway = FakeGateway()
        gateway.verifyResult = true
        gateway.responses += qrBody("""{"error_code":2046,"status":2,"biz_params":"a=1"}""")
        gateway.responses += qrBody("""{"error_code":2046,"status":2}""")
        val result = SodaQrLogin.check(gateway, newSession())
        assertEquals("waiting", result.status)
        assertTrue(result.message.contains("仍要求"))
    }

    @Test
    fun otherErrorCodeFailsWithDescription() = runBlocking {
        val gateway = FakeGateway()
        gateway.responses += qrBody("""{"error_code":18,"description":"当前设备不安全","status":1}""")
        val result = SodaQrLogin.check(gateway, newSession())
        assertEquals("failed", result.status)
        assertEquals("当前设备不安全", result.message)
    }

    @Test
    fun confirmedWithSessionIdInBodySucceeds() = runBlocking {
        val gateway = FakeGateway()
        val session = newSession()
        gateway.responses += qrBody("""{"error_code":0,"status":3,"sessionid":"sid1"}""")
        val result = SodaQrLogin.check(gateway, session)
        assertEquals("success", result.status)
        assertTrue(result.cookie.contains("sessionid=sid1"))
    }

    @Test
    fun confirmedFollowsRedirectToHarvestLoginState() = runBlocking {
        val gateway = FakeGateway()
        val session = newSession()
        gateway.responses += qrBody(
            """{"error_code":0,"status":3,"redirect_url":"https://api.qishui.com/passport/web/confirm/?ticket=t"}""",
        )
        gateway.harvestValue = "sessionid_ss=ss1; sid_guard=g1"
        val result = SodaQrLogin.check(gateway, session)
        assertEquals("success", result.status)
        assertTrue(result.cookie.contains("sessionid_ss=ss1"))
        assertTrue(result.cookie.contains("sid_guard=g1"))
        assertEquals(2, gateway.requests.size) // check + redirect 跳一跳
        assertTrue(gateway.requests[1].second.contains("/passport/web/confirm/"))
        assertTrue(gateway.harvested.first().any { it.contains("confirm") })
    }

    @Test
    fun confirmedWithoutLoginStateFails() = runBlocking {
        val gateway = FakeGateway()
        gateway.responses += qrBody("""{"error_code":0,"status":3}""")
        val result = SodaQrLogin.check(gateway, newSession())
        assertEquals("failed", result.status)
        assertTrue(result.message.contains("没拿到登录态"))
    }

    @Test
    fun checkSendsSignedFormBodyWithPassportHeaders() = runBlocking {
        val gateway = FakeGateway()
        val session = newSession()
        gateway.responses += qrBody("""{"error_code":0,"status":1}""")
        SodaQrLogin.check(gateway, session)
        val (method, url, body) = gateway.requests.single()
        assertEquals("POST", method)
        assertTrue(url.startsWith("https://api.qishui.com/passport/web/check_qrconnect/"))
        // 表单按 key 排序编码
        val expected = listOf(
            "is_frontier=true",
            "is_new_login=1",
            "need_logo=false",
            "need_short_url=false",
            "next=https%3A%2F%2Fapi.qishui.com",
            "token=tok123",
        ).joinToString("&")
        assertEquals(expected, body)
        val headers = gateway.headersList.single()
        assertEquals(session.verifyPortraitId, headers["x-tt-passport-verify-portrait"])
        assertTrue(headers["x-tt-passport-trace-id"]!!.length == 8)
        assertEquals("application/x-www-form-urlencoded", headers["Content-Type"])
        assertEquals(NativeCrypto.md5Hex(expected).uppercase(), headers["x-ss-stub"])
    }

    @Test
    fun mergeCookiesOverridesAndSkipsAttributes() = runBlocking {
        val merged = SodaQrLogin.mergeCookies(
            "a=1; sessionid=old",
            listOf("sessionid=new; Path=/; Domain=.qishui.com, sid_tt=t1"),
        )
        assertTrue(merged.contains("sessionid=new"))
        assertFalse(merged.contains("old"))
        assertTrue(merged.contains("sid_tt=t1"))
        assertFalse(merged.contains("Path"))
        assertFalse(merged.contains("Domain"))
    }

    @Test
    fun hasLoginStateNeedsOneOfFourNames() = runBlocking {
        assertTrue(SodaQrLogin.hasLoginState("x=1; sessionid_ss=abc"))
        assertTrue(SodaQrLogin.hasLoginState("sid_guard=abc"))
        assertFalse(SodaQrLogin.hasLoginState("msToken=abc; passport_csrf_token=x"))
        assertFalse(SodaQrLogin.hasLoginState("sessionid="))
    }

    @Test
    fun msTokenMatchesUpstreamShape() = runBlocking {
        val token = SodaQrLogin.randomMsToken()
        assertEquals(120, token.length)
        assertTrue(token.endsWith("=="))
        assertTrue(token.dropLast(2).all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_" })
    }

    @Test
    fun formEncodeSortsAndEscapes() = runBlocking {
        assertEquals("a=x&b=1+2", SodaQrLogin.formEncode(listOf("b" to "1 2", "a" to "x")))
    }

    @Test
    fun sessionIdsHaveUpstreamShapes() = runBlocking {
        assertEquals(16, SodaQrLogin.randomDigits(16).length)
        assertTrue(SodaQrLogin.randomDigits(16).all { it.isDigit() })
        assertEquals(8, SodaQrLogin.randomHex(8).length)
    }
}
