package com.mytv.mymusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {

    @Test
    fun `media-user-token in cookies is redacted`() {
        val input = "Cookie 长度=512 含 media-user-token=true 值: media-user-token=0.AjWXMbQBV0FQj/abc+def==; XID=zz"
        val out = AppLog.redact(input)
        assertTrue("token 没抹掉: $out", out.contains("media-user-token=<redacted>"))
        assertFalse("原 token 还在: $out", out.contains("0.AjWXMbQBV0FQj/abc+def=="))
    }

    @Test
    fun `bearer developer token is redacted`() {
        val out = AppLog.redact("""{"Authorization": "Bearer eyJ0eXAi.eyJpc3Mi.sig123"}""")
        assertTrue("developer token 没抹掉: $out", out.contains("Bearer <redacted>"))
        assertFalse("原 token 还在: $out", out.contains("eyJ0eXAi.eyJpc3Mi.sig123"))
    }

    @Test
    fun `bare jwt is redacted`() {
        val jwt = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJpc3MiOiI1SUtQUDJJRUNRIn0.c2lnbmF0dXJlLWhlcmU"
        val out = AppLog.redact("token=$jwt")
        assertFalse("裸 JWT 还在: $out", out.contains(jwt))
        assertTrue(out.contains("<redacted-jwt>"))
    }

    @Test
    fun `ordinary messages are untouched`() {
        val plain = "整曲就绪 Shape of You 253kbps https://aod.itunes.apple.com/x.m3u8"
        assertEquals(plain, AppLog.redact(plain))
    }

    @Test
    fun `entries are capped and keep insertion order`() {
        AppLog.clear()
        repeat(1_200) { AppLog.d("T", "line-$it") }
        val snapshot = AppLog.snapshot()
        assertTrue("超过上限: ${snapshot.size}", snapshot.size <= 800)
        // 环形缓冲丢的是最早的
        assertEquals("line-400", snapshot.first().message)
        assertEquals("line-1199", snapshot.last().message)
        AppLog.clear()
    }

    @Test
    fun `export includes header and is newline terminated`() {
        AppLog.clear()
        AppLog.i("T", "第一条")
        AppLog.w("T", "第二条")
        val text = AppLog.exportText()
        assertTrue(text.contains("第一条"))
        assertTrue(text.contains("第二条"))
        assertTrue(text.endsWith("\n"))
        AppLog.clear()
    }
}
