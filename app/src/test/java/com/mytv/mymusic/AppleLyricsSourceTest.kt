package com.mytv.mymusic

import com.mytv.mymusic.data.native.AppleLyricsConfig
import com.mytv.mymusic.data.native.APPLE_LYRIC_SOURCES
import com.mytv.mymusic.data.native.appleTtmlFromAmll
import com.mytv.mymusic.data.native.appleTtmlFromPaxsenix
import com.mytv.mymusic.data.native.appleTtmlFromZarz
import com.mytv.mymusic.data.native.convertTtmlToLrc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple Music 匿名歌词：Apple 官方接口已不再返回歌词，改为按顺序试三个第三方来源。
 * 这些用例锁住每个来源的响应形态——它们返回的都是 Apple 原生逐词 TTML，
 * 必须能被 [convertTtmlToLrc] 吃成增强 LRC。
 */
class AppleLyricsSourceTest {
    @After
    fun resetConfig() {
        AppleLyricsConfig.configure(true)
    }

    // zarz 真实响应（2026-11-04 陈奕迅《十年》，节选）
    private val zarzBody = """
        {"type":"Syllable","lyricVariant":"syllable-lyrics","storefront":"id",
         "ttmlContent":"<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" itunes:timing=\"Word\"><body><div><p begin=\"15.244\" end=\"18.996\"><span begin=\"15.244\" end=\"15.702\">如果</span><span begin=\"15.702\" end=\"16.164\">那</span></p></div></body></tt>",
         "lrc":"[00:15.24]如果那兩個字沒有顫抖"}
    """.trimIndent()

    // AMLL 真实响应形态
    private val amllBody = """
        {"status":200,"data":{"id":2305904522870254,"format":"ttml",
         "lyrics":"<tt xmlns=\"http://www.w3.org/ns/ttml\" itunes:timing=\"Word\"><body><div><p begin=\"0.0\" end=\"2.0\"><span begin=\"0.0\" end=\"1.0\">告白</span><span begin=\"1.0\" end=\"2.0\">气球</span></p></div></body></tt>"}}
    """.trimIndent()

    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word"><body><div>
        <p begin="15.244" end="18.996"><span begin="15.244" end="15.702">如果</span><span begin="15.702" end="16.164">那</span></p>
        </div></body></tt>
    """.trimIndent()

    @Test
    fun extractsTtmlFromZarz() {
        val result = appleTtmlFromZarz(zarzBody)
        assertTrue(result.startsWith("<tt "))
        assertTrue(result.contains("itunes:timing=\"Word\""))
    }

    @Test
    fun zarzFeedsExistingTtmlConverter() {
        val lrc = convertTtmlToLrc(appleTtmlFromZarz(zarzBody))
        // 逐字：每个 span 一个 <起> 标签，行首一个 [mm:ss.SSS]
        assertEquals("[00:15.244]<00:15.244>如果</00:15.702><00:15.702>那</00:16.164>", lrc)
    }

    @Test
    fun extractsTtmlFromAmll() {
        val result = appleTtmlFromAmll(amllBody)
        assertTrue(result.startsWith("<tt "))
        assertEquals("[00:00.000]<00:00.000>告白</00:01.000><00:01.000>气球</00:02.000>", convertTtmlToLrc(result))
    }

    @Test
    fun amllErrorPayloadYieldsBlank() {
        // 词库没有这首歌时 AMLL 回错误对象，必须按空丢掉，不能把 JSON 当歌词
        val body = """{"status":404,"error":"Not Found","message":"No lyrics found for the provided query."}"""
        assertEquals("", appleTtmlFromAmll(body))
    }

    @Test
    fun paxsenixAcceptsRawTtml() {
        assertEquals(ttml, appleTtmlFromPaxsenix(ttml))
        assertTrue(appleTtmlFromPaxsenix("\n  $ttml").startsWith("<tt "))
    }

    @Test
    fun paxsenixRejectsOverloadAndJsonPayloads() {
        // 过载时回 HTML 或 JSON，都不是 TTML，按空丢掉好让下一个来源接手
        assertEquals("", appleTtmlFromPaxsenix("<html><body>503 Service Unavailable</body></html>"))
        // 错误页也以 < 开头（<html>、<?xml 之外的 <tt> 才算 TTML）
        assertEquals("", appleTtmlFromPaxsenix("<title>Service Unavailable</title>"))
        assertEquals("", appleTtmlFromPaxsenix("""{"message":"busy handling 500 requests","error":true}"""))
        assertEquals("", appleTtmlFromPaxsenix(""))
    }

    @Test
    fun sourcesAreOrderedMostReliableFirst() {
        assertEquals(listOf("zarz", "paxsenix", "amll"), APPLE_LYRIC_SOURCES.map { it.name })
        // zarz 实测最稳，必须是第一个
        assertEquals("zarz", APPLE_LYRIC_SOURCES.first().name)
    }

    @Test
    fun everySourceBuildsItsOwnQuery() {
        // 三个源的参数名不一样：zarz/paxsenix 用 id，AMLL 用 appleMusicId
        assertEquals("id=651456011&v=1", APPLE_LYRIC_SOURCES[0].query("651456011"))
        assertEquals("id=651456011&ttml=true", APPLE_LYRIC_SOURCES[1].query("651456011"))
        assertEquals("appleMusicId=651456011", APPLE_LYRIC_SOURCES[2].query("651456011"))
    }

    @Test
    fun configToggleFlips() {
        assertTrue(AppleLyricsConfig.thirdPartyLyrics)
        AppleLyricsConfig.configure(false)
        assertTrue(!AppleLyricsConfig.thirdPartyLyrics)
        AppleLyricsConfig.configure(true)
        assertTrue(AppleLyricsConfig.thirdPartyLyrics)
    }
}
