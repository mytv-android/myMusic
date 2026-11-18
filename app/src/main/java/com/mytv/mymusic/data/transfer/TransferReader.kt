package com.mytv.mymusic.data.transfer

import com.mytv.mymusic.data.sync.SCHEMA
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** 从文件读出来的东西，按内容分流（不看扩展名）。 */
sealed interface TransferContent {
    /** 本应用的歌单文件 → 导入预览。 */
    data class Playlists(val decoded: DecodedPlaylists) : TransferContent

    /** 老的整库备份（同步文件）→ 原来的整库合并，不出预览。 */
    data class Library(val text: String) : TransferContent

    /** 洛雪导出的歌单 → 导入预览。 */
    data class Lx(val file: LxListFile) : TransferContent

    data class Unsupported(val message: String) : TransferContent
}

class TransferTooLarge : IOException("文件解压后太大")

object TransferReader {
    /** 读进来的原始字节上限。 */
    const val MAX_FILE_BYTES = 32L * 1024 * 1024

    /** gzip 解出来的上限，防压缩炸弹。 */
    const val MAX_INFLATED_BYTES = 96L * 1024 * 1024

    const val UNKNOWN_FILE = "认不出这个文件，只能导入 myMusic 歌单文件、整库备份和洛雪导出的歌单"

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun read(bytes: ByteArray, inflateLimit: Long = MAX_INFLATED_BYTES): TransferContent {
        if (bytes.isEmpty()) return TransferContent.Unsupported("文件是空的")
        if (bytes.size > MAX_FILE_BYTES) return TransferContent.Unsupported("文件太大（超过 32MB）")
        val raw = if (isGzip(bytes)) {
            try {
                inflate(bytes, inflateLimit)
            } catch (_: TransferTooLarge) {
                return TransferContent.Unsupported("文件解压后太大，不像是歌单文件")
            } catch (_: IOException) {
                return TransferContent.Unsupported("文件压缩格式坏了，读不出来")
            }
        } else {
            bytes
        }
        val text = raw.toString(Charsets.UTF_8)
        val root = parseTree(text) as? JsonObject ?: return TransferContent.Unsupported(UNKNOWN_FILE)
        val type = (root["type"] as? JsonPrimitive)?.contentOrNull
        val schema = (root["schema"] as? JsonPrimitive)?.contentOrNull
        return try {
            when {
                type == PLAYLIST_FILE_TYPE -> MyMusicPlaylistCodec.decode(root)?.let { TransferContent.Playlists(it) }
                    ?: TransferContent.Unsupported(UNKNOWN_FILE)
                schema == SCHEMA || (type == null && root["playlists"] is JsonArray) -> TransferContent.Library(text.removePrefix(BOM))
                LxKind.of(type) != null -> TransferContent.Lx(LxListCodec.parse(root))
                type != null -> TransferContent.Unsupported("不认识的文件格式（type=$type）")
                else -> TransferContent.Unsupported(UNKNOWN_FILE)
            }
        } catch (error: IllegalArgumentException) {
            TransferContent.Unsupported(error.message ?: UNKNOWN_FILE)
        }
    }

    /**
     * 上传时先粗看一眼：gzip 头，或者去掉 BOM 和空白后以 `{` 开头（洛雪 1.14.0 序列化两次的文件以 `"` 开头）。
     * 只是挡掉明显不是歌单的文件，真正的判断在 [read]。
     */
    fun looksLikeTransfer(bytes: ByteArray): Boolean {
        if (isGzip(bytes)) return true
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
        while (start < bytes.size && bytes[start].toInt().toChar().isWhitespace()) start++
        if (start >= bytes.size) return false
        val first = bytes[start].toInt().toChar()
        return first == '{' || first == '"'
    }

    internal fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    /** 解压，超过 [limit] 抛 [TransferTooLarge]。 */
    internal fun inflate(bytes: ByteArray, limit: Long): ByteArray {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            return readBounded(input, limit) ?: throw TransferTooLarge()
        }
    }

    /** 最多读 [limit] 字节；还没读完就超了返回 null（调用方不用先把整个文件读进内存）。 */
    fun readBounded(input: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** 去 BOM 后解析；解出来是字符串（洛雪 1.14.0 把整份序列化了两次）再解一次，只多解一次。 */
    internal fun parseTree(text: String): JsonElement? {
        val clean = text.removePrefix(BOM).trim()
        if (clean.isEmpty()) return null
        val first = runCatching { json.parseToJsonElement(clean) }.getOrNull() ?: return null
        val primitive = first as? JsonPrimitive
        if (primitive != null && primitive.isString) {
            return runCatching { json.parseToJsonElement(primitive.content.trim()) }.getOrNull()
        }
        return first
    }

    private const val BOM = "﻿"
}
