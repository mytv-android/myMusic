package com.mytv.mymusic

import com.mytv.mymusic.playback.AppleWidevineDrmCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权响应取字段。license 前缀取自 2026-11-05 Pixel 8 Pro 真机抓到的 925B 响应
 * （Apple 回了 200 + license，却因为响应里带着模型没写的字段被严格解析整个扔掉，
 * 症状是"授权没给 license"重试四轮、最后 6004 换源）。
 */
class AppleLicenseResponseTest {

    // 真实响应里 license 之后还有字段（日志只截了前 400 字符看不到尾部），
    // 这里按最坏情况构造：多出来的字段、字符串型的 status、嵌套对象都有
    private val realBody = """{"license":"CAIS1QIKOwogODk2MjU2MUYwRjdFMjk4MzNBMDAwMDAwMDAwMDAwMDASCC/kDiwrdeyXIAEoADiEB0AASLSPstcGEhwIARAAGAEghAcoADh4QgBIjAZQHlgAYAFwAHgBGlYSEH23dPgmweSu1bcZpe+f9G0aQHN8k1dz9wlApJLpN7A4gD2E+Z+trO8R/lmKjYVeoLRU43CqIDTiItIPgdypHk72U9jaHz3xIZSFlSZElZQY0s0g","status":"0","license-expires-at":3600,"undocumented":{"nested":[1,2]}}"""

    @Test
    fun `license is picked out of a response full of unknown fields`() {
        val license = AppleWidevineDrmCallback.licenseField(realBody, "license")
        assertTrue("license 没取到: ${license.take(40)}", license.startsWith("CAIS1QIKOwog"))
        assertTrue(license.endsWith("SZElZQY0s0g"))
        // 未知字段不该把取值带崩
        assertEquals("0", AppleWidevineDrmCallback.licenseField(realBody, "status"))
    }

    @Test
    fun `failure responses still expose failureType and customerMessage`() {
        val failure =
            """{"status":403,"failureType":"2002","customerMessage":"会话已过期，请重新登录。","license":null}"""
        assertEquals("2002", AppleWidevineDrmCallback.licenseField(failure, "failureType"))
        assertEquals("会话已过期，请重新登录。", AppleWidevineDrmCallback.licenseField(failure, "customerMessage"))
        // license 是 null：当没有处理，交给调用方走失败分支
        assertEquals("", AppleWidevineDrmCallback.licenseField(failure, "license"))
    }

    @Test
    fun `missing fields and garbage bodies yield empty instead of throwing`() {
        assertEquals("", AppleWidevineDrmCallback.licenseField("{}", "license"))
        assertEquals("", AppleWidevineDrmCallback.licenseField("""{"license":""}""", "license"))
        assertEquals("", AppleWidevineDrmCallback.licenseField("not json", "license"))
        assertEquals("", AppleWidevineDrmCallback.licenseField("", "license"))
        assertEquals("", AppleWidevineDrmCallback.licenseField("""[1,2]""", "license"))
        // 字段是数字也按字符串取（content 的行为），不会抛
        assertEquals("42", AppleWidevineDrmCallback.licenseField("""{"license":42}""", "license"))
    }
}
