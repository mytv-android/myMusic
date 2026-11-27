package com.mytv.mymusic.data.local

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 歌曲旁边的同名 .lrc。解析交给现有的 LyricsParser（拿到的是和平台歌词同格式的原文），
 * 这里只管找文件和猜编码：国内老歌词文件大量是 GBK，直接按 UTF-8 读会是一片乱码。
 */
object LocalLrc {
    /** 歌词文件最大读多少；正常 .lrc 几 KB，超过的多半不是歌词。 */
    const val MAX_BYTES = 512 * 1024

    fun sidecarName(audioName: String): String = LocalTags.baseName(audioName) + ".lrc"

    /** 在同一目录的文件名里找 `歌名.lrc`，扩展名和基础名都不区分大小写。 */
    fun findSidecar(audioName: String, siblings: Collection<String>): String? {
        val base = LocalTags.baseName(audioName)
        return siblings.firstOrNull { name ->
            name.endsWith(".lrc", ignoreCase = true) && LocalTags.baseName(name).equals(base, ignoreCase = true)
        }
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strict.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            // 不是合法 UTF-8：按 GB18030 读（GBK / GB2312 的超集）
            String(bytes, runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.UTF_8))
        }
    }

    private val timeTag = Regex("""\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""")

    /** 至少有一个时间标签才算 LRC；纯文本歌词放到滚动歌词里没法对时间，不用。 */
    fun looksLikeLrc(text: String): Boolean = timeTag.containsMatchIn(text)

    /** 读 [audio] 旁边的同名 .lrc（下载时一起存下来的那份）。 */
    fun readSidecar(audio: File): String? {
        val dir = audio.parentFile ?: return null
        val name = findSidecar(audio.name, dir.list()?.toList().orEmpty()) ?: return null
        val file = File(dir, name)
        if (file.length() > MAX_BYTES) return null
        val bytes = runCatching { file.inputStream().use { it.readBytes() } }.getOrNull() ?: return null
        return decode(bytes).takeIf { it.isNotBlank() }
    }

    /** 下载完成后把歌词写到音频旁边；[text] 为空时删掉旧的那份。 */
    fun writeSidecar(audio: File, text: String) {
        val dir = audio.parentFile ?: return
        val file = File(dir, sidecarName(audio.name))
        if (text.isBlank()) {
            file.delete()
            return
        }
        runCatching {
            dir.mkdirs()
            file.writeText(text, Charsets.UTF_8)
        }
    }
}
