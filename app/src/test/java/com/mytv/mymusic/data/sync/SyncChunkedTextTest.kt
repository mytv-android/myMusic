package com.mytv.mymusic.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [readChunkedText] 的 offset 必须按 Unicode 码位推进。
 *
 * SQLite 的 length() 和 substr() 数的是码位，Kotlin 的 String.length 数的是 UTF-16 码元：
 * 一个非 BMP 字符（emoji、CJK 扩展区等）在 SQLite 里算 1 个、在 Kotlin 里算 2 个。
 * 用 chunk.length 推进 offset 的话，每跨一次块边界就多跳 1 个字符，
 * 拼回来的同步基线会缺字、解析失败，然后一路掉进「首次同步」分支——
 * 用户每次同步都被问怎么合并，选「云端」还会删掉仅本机有的歌单。
 */
class SyncChunkedTextTest {
    /** 按 SQLite substr 的语义切片：下标从 1 开始、按码位计数，末块不足时给剩下的。 */
    private fun substrByCodePoints(text: String, start: Int, size: Int): String {
        val from = text.offsetByCodePoints(0, start - 1)
        val take = minOf(size, text.codePointCount(from, text.length))
        return text.substring(from, text.offsetByCodePoints(from, take))
    }

    private fun readAll(text: String, chunkChars: Int): String? = runBlocking {
        readChunkedText(text.codePointCount(0, text.length), chunkChars) { offset, size ->
            substrByCodePoints(text, offset, size)
        }
    }

    @Test
    fun `multi chunk text round trips`() {
        val text = buildString {
            append("{\"schema\":3,\"playlists\":[")
            repeat(300_000) { append('歌') }
            append("]}")
        }
        assertEquals(text, readAll(text, CHUNK))
    }

    @Test
    fun `supplementary characters do not shift the offset`() {
        val text = buildString {
            append("{\"schema\":3,\"playlists\":[")
            repeat(100_000) { append('歌') }
            append("🎵")
            repeat(300_000) { append('歌') }
            append("🎶我的歌单")
            repeat(300_000) { append('歌') }
            append("]}")
        }
        assertEquals(text, readAll(text, CHUNK))
    }

    @Test
    fun `text shorter than one chunk round trips`() {
        val text = "{\"schema\":3,\"playlists\":[]}"
        assertEquals(text, readAll(text, CHUNK))
    }

    @Test
    fun `empty text returns empty string without reading`() {
        val result = runBlocking { readChunkedText(0, CHUNK) { _, _ -> error("空文本不该发起读取") } }
        assertEquals("", result)
    }

    @Test
    fun `unreadable chunk returns null`() {
        val result = runBlocking { readChunkedText(10, 4) { _, _ -> null } }
        assertNull(result)
    }

    private companion object {
        private const val CHUNK = 256 * 1024
    }
}
