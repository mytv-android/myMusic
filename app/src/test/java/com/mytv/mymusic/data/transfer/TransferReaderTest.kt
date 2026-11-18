package com.mytv.mymusic.data.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

class TransferReaderTest {
    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/lx/$name")).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun unsupported(content: TransferContent): String {
        assertTrue("应该认不出：$content", content is TransferContent.Unsupported)
        return (content as TransferContent.Unsupported).message
    }

    @Test
    fun gzipIsInflatedWhateverTheName() {
        val content = TransferReader.read(gzip(resource("part_v2.json").toByteArray()))
        assertTrue(content is TransferContent.Lx)
        assertEquals(LxKind.PartV2, (content as TransferContent.Lx).file.kind)
    }

    @Test
    fun bomIsStripped() {
        val content = TransferReader.read(("﻿" + resource("list_v2.json")).toByteArray())
        assertTrue(content is TransferContent.Lx)
    }

    @Test
    fun doubleSerializedIsParsedOnceMore() {
        val once = kotlinx.serialization.json.JsonPrimitive(resource("list_v2.json")).toString()
        val content = TransferReader.read(gzip(once.toByteArray()))
        assertTrue(content is TransferContent.Lx)
        // 三重序列化不再继续解：解两次还是字符串，就当认不出
        val twice = kotlinx.serialization.json.JsonPrimitive(once).toString()
        unsupported(TransferReader.read(twice.toByteArray()))
    }

    @Test
    fun myMusicPlaylistFile() {
        val bytes = """{"type":"mymusic.playlists","version":1,"playlists":[{"name":"跑步","tracks":[{"provider":"netease","id":"1"}]}]}"""
            .toByteArray()
        val content = TransferReader.read(bytes)
        assertTrue(content is TransferContent.Playlists)
        assertEquals("跑步", (content as TransferContent.Playlists).decoded.file.playlists.single().name)
    }

    @Test
    fun libraryBackupGoesToTheOldPath() {
        val withSchema = """{"schema":"mymusic.library","version":3,"playlists":[],"favorites":[]}"""
        assertTrue(TransferReader.read(withSchema.toByteArray()) is TransferContent.Library)
        // 更早的备份没有 schema、只有 playlists
        val old = """{"playlists":[{"id":"a","name":"x"}]}"""
        val content = TransferReader.read(old.toByteArray())
        assertTrue(content is TransferContent.Library)
        assertEquals(old, (content as TransferContent.Library).text)
    }

    @Test
    fun allSevenLxTypesAreRecognized() {
        val samples = mapOf(
            "playListPart_v2" to """{"type":"playListPart_v2","data":{"id":"a","name":"x","list":[]}}""",
            "playList_v2" to """{"type":"playList_v2","data":[]}""",
            "allData_v2" to """{"type":"allData_v2","playList":[],"setting":{}}""",
            "playListPart" to """{"type":"playListPart","data":{"id":"a","name":"x","list":[]}}""",
            "playList" to """{"type":"playList","data":[]}""",
            "allData" to """{"type":"allData","playList":[]}""",
            "defautlList" to """{"type":"defautlList","data":{"list":[]}}""",
        )
        assertEquals(LxKind.entries.size, samples.size)
        samples.forEach { (type, text) ->
            val content = TransferReader.read(text.toByteArray())
            assertTrue(type, content is TransferContent.Lx)
            assertEquals(type, (content as TransferContent.Lx).file.kind.wire)
        }
    }

    @Test
    fun unknownTypeNamesIt() {
        val message = unsupported(TransferReader.read("""{"type":"setting_v2","data":{}}""".toByteArray()))
        assertTrue(message, message.contains("setting_v2"))
    }

    @Test
    fun plainTextIsNotRecognized() {
        assertEquals(TransferReader.UNKNOWN_FILE, unsupported(TransferReader.read("晴天 - 周杰伦\n七里香 - 周杰伦\n".toByteArray())))
        // png 文件头
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + Random(1).nextBytes(64)
        assertEquals(TransferReader.UNKNOWN_FILE, unsupported(TransferReader.read(png)))
    }

    @Test
    fun tooLargeFile() {
        val big = ByteArray((TransferReader.MAX_FILE_BYTES + 1).toInt())
        assertTrue(unsupported(TransferReader.read(big)).contains("太大"))
    }

    @Test
    fun inflateBombIsCut() {
        val bomb = gzip(ByteArray(1024 * 1024) { 'a'.code.toByte() })
        assertTrue(bomb.size < 16 * 1024)
        val message = unsupported(TransferReader.read(bomb, inflateLimit = 64 * 1024))
        assertTrue(message, message.contains("太大"))
    }

    @Test
    fun brokenStructureIsUnsupported() {
        val message = unsupported(TransferReader.read("""{"type":"playList_v2","data":{"x":1}}""".toByteArray()))
        assertTrue(message, message.contains("洛雪"))
    }

    @Test
    fun emptyFile() {
        assertTrue(unsupported(TransferReader.read(ByteArray(0))).contains("空"))
        assertEquals(TransferReader.UNKNOWN_FILE, unsupported(TransferReader.read("  \n".toByteArray())))
    }

    @Test
    fun uploadSniff() {
        assertTrue(TransferReader.looksLikeTransfer(gzip("x".toByteArray())))
        assertTrue(TransferReader.looksLikeTransfer("﻿ \n{\"a\":1}".toByteArray()))
        assertTrue(TransferReader.looksLikeTransfer("\"{\\\"type\\\":1}\"".toByteArray()))
        assertFalse(TransferReader.looksLikeTransfer("hello".toByteArray()))
        assertFalse(TransferReader.looksLikeTransfer(ByteArray(0)))
    }

    @Test
    fun readBoundedStopsPastLimit() {
        val input = ByteArray(1000).inputStream()
        assertEquals(null, TransferReader.readBounded(input, 999))
        assertEquals(1000, TransferReader.readBounded(ByteArray(1000).inputStream(), 1000)!!.size)
    }
}
