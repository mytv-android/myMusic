package com.mytv.mymusic

import com.mytv.mymusic.data.native.APPLE_BUNDLE_REGEXES
import com.mytv.mymusic.data.native.appleBundlePaths
import com.mytv.mymusic.data.native.appleTokens
import com.mytv.mymusic.data.native.AppleTokenCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple Music 的 developer token 抓取：入口包名和 token 前缀都随 music.apple.com 前端发版变过，
 * 这里用真实首页 / 真实前端包的形态做回归，避免再出现整个源失效。
 */
class AppleTokenScrapeTest {
    // 现行首页（2026-11）：入口包是 index~<hash>.js，不再是 index-legacy~*.js
    private val currentHome = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <script src="/assets/index~cc245af156.js"></script>
        <script src="/includes/js-cdn/musickit/v3/components/musickit-components/musickit-components.esm.js"></script>
        </head><body></body></html>
    """.trimIndent()

    // 历史首页形态
    private val legacyHome = """
        <html><head><script src="/assets/index-legacy~4f2a1b9c3.js"></script></head></html>
    """.trimIndent()

    // 真实前端包里三个 JWT 的片段（值取自 index~cc245af156.js）
    private val currentBundle = """
        ...const DX="eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6Ijk3RFFVOVFVRDYifQ.eyJpc3MiOiJNNjJZRDg1RlRRIiwiaWF0IjoxNzkyNzgwNTM0LCJleHAiOjE3OTg4Mjg1MzQsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdLCJvcmlnaW4iOiIqLmFwcGxlLmNvbSJ9.B22AwiQK1P0iQNDaTqYn_S202-P4GfAlQiI1ll0j-uD_bRhJmcUxVzMD53JLkbqDUiwVi8nWdMKyg9GKDa1Fdw";
        const qa="eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ.eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzkyNzgwNTM0LCJleHAiOjE3OTg4Mjg1MzQsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ.wcf_7U-zcgUjY2L8d4HWFkdxP1YSJCkkqEpEtVPKpRARrlaag0DHyIpRcoUEWZU8kJlvx5Q7ZNp04OabfuiuAw",
        u_e="eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IkxUMlpEWlNYTlEifQ.eyJpc3MiOiI1SUtQUDJJRUNRIiwiaWF0IjoxNzkyNzgwNTM0LCJleHAiOjE3OTg4Mjg1MzQsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ.p95fR8Bo64P4CU24a9SZiTMGFO3vefVVu3cRii4QjL0K9MBwNgWzo0o23lTR8MfJW0B7j-qY59jmZ74am3Fqng";
    """.trimIndent()

    @Test
    fun findsCurrentEntryBundle() {
        assertEquals(listOf("assets/index~cc245af156.js"), appleBundlePaths(currentHome))
    }

    @Test
    fun findsLegacyEntryBundle() {
        assertEquals(listOf("assets/index-legacy~4f2a1b9c3.js"), appleBundlePaths(legacyHome))
    }

    @Test
    fun fallsBackToAnyAssetsBundleWhenNoIndexBundle() {
        val home = """<html><script src="/assets/main-abc123.js"></script></html>"""
        assertEquals(listOf("assets/main-abc123.js"), appleBundlePaths(home))
    }

    @Test
    fun ignoresNonAssetsScripts() {
        val home = """<html><script src="/includes/js-cdn/musickit/v3/musickit.js"></script></html>"""
        assertEquals(emptyList<String>(), appleBundlePaths(home))
    }

    @Test
    fun capsBundleCandidates() {
        val home = (1..5).joinToString("") { """<script src="/assets/chunk-$it.js"></script>""" }
        assertEquals(2, appleBundlePaths(home).size)
    }

    @Test
    fun extractsEveryJwtNotJustEyJhOnes() {
        val tokens = appleTokens(currentBundle)
        assertEquals(3, tokens.size)
        // 老正则 (?:=["'])(eyJh[^"']+) 在这里一个都匹配不到
        assertTrue(tokens.all { it.startsWith("eyJ0eXAi") })
        assertTrue(tokens.any { it.contains("AMPWebPlay") || it.length > 250 })
    }

    @Test
    fun ignoresShortOrTruncatedJwtShapedStrings() {
        val js = """const a="eyJhbGciOiJFUzI1NiJ9"; const b="eyJaaa.eyJbbb";"""
        assertEquals(emptyList<String>(), appleTokens(js))
    }

    @Test
    fun bundleRegexesPreferIndexEntry() {
        assertEquals(2, APPLE_BUNDLE_REGEXES.size)
        val first = APPLE_BUNDLE_REGEXES.first().find(currentHome)?.groupValues?.get(1)
        assertEquals("assets/index~cc245af156.js", first)
    }

    // 真实的 developer token（2026-11 实测）：iat 1792780534 / exp 1798828534，约 70 天有效
    private val realToken =
        "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ." +
            "eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzkyNzgwNTM0LCJleHAiOjE3OTg4Mjg1MzQs" +
            "InJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ.sig"

    @Test
    fun realTokenIsNotExpired() {
        // 1798828534 = 2027-01 前后，只要没过那天就算有效
        assertTrue(!AppleTokenCache.isExpired(realToken))
    }

    @Test
    fun expiredTokenIsDetected() {
        val stale = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9." +
            "eyJpc3MiOiJYIiwiZXhwIjoxMDAwMDAwMDAwfQ.sig"
        assertTrue(AppleTokenCache.isExpired(stale))
    }

    @Test
    fun unparseableTokenCountsAsExpired() {
        // 读不出 exp 宁可多抓一次，也不要拿过期 token 撞 401
        for (bad in listOf("", "not-a-jwt", "a.b", "a.b.c", "eyJhbGciOiJFUzI1NiJ9..sig")) {
            assertTrue("should be expired: $bad", AppleTokenCache.isExpired(bad))
        }
    }
}
