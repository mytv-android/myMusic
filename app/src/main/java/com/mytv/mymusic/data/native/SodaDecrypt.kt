package com.mytv.mymusic.data.native

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 汽水整曲流的本地解密：play_auth（spade）还原 AES 密钥，再按 MP4 CENC 的
 * senc/tenc 逐样本做 AES-CTR。逻辑移植自 music-lib 的 soda/crypto.go 与
 * libresoda 的 soda/crypto.rs（sodam 的取流实现），只处理整文件。
 */
object SodaDecrypt {
    // 损坏文件可能声明几十亿个样本，按声明值分配会直接 OOM
    private const val MAX_SAMPLE_ENTRIES = 4 * 1024 * 1024
    private const val AES_BLOCK_SIZE = 16

    fun decryptAudio(data: ByteArray, playAuth: String): ByteArray {
        val keyHex = extractKey(playAuth)
        val key = NativeCrypto.hexToBytes(keyHex)
        require(key.size == AES_BLOCK_SIZE) { "invalid soda aes key" }

        val moov = findBox(data, "moov", 0, data.size)
            ?: throw IllegalStateException("soda 音频缺少 moov box")
        val stbl = findBox(data, "stbl", moov.offset, moov.offset + moov.size)
            ?: findStblDeep(data, moov)
            ?: throw IllegalStateException("soda 音频缺少 stbl box")
        val stsz = findBox(data, "stsz", stbl.offset + 8, stbl.offset + stbl.size)
            ?: throw IllegalStateException("soda 音频缺少 stsz box")
        val sampleSizes = parseStsz(data, stsz)
        val senc = findBox(data, "senc", moov.offset + 8, moov.offset + moov.size)
            ?: findBox(data, "senc", stbl.offset + 8, stbl.offset + stbl.size)
            ?: throw IllegalStateException("soda 音频缺少 senc box")
        val ivSize = defaultPerSampleIvSize(data, stbl.offset, stbl.offset + stbl.size)
        val sencSamples = parseSenc(data, senc, ivSize)
        val mdat = findBox(data, "mdat", 0, data.size)
            ?: throw IllegalStateException("soda 音频缺少 mdat box")

        val result = data.copyOf()
        val payloadSize = (mdat.size - 8).coerceAtLeast(0)
        // box 头声明的大小不可信，容量按真实文件长度封顶
        val decrypted = ByteArrayOutputStream(minOf(payloadSize, data.size))
        var readPtr = mdat.offset + 8
        for (index in sampleSizes.indices) {
            val size = sampleSizes[index]
            if (size <= 0 || readPtr + size > result.size) break
            val chunk = result.copyOfRange(readPtr, readPtr + size)
            val plain = if (index < sencSamples.size) decryptSample(key, chunk, sencSamples[index]) else chunk
            decrypted.write(plain)
            readPtr += size
        }
        val plainBytes = decrypted.toByteArray()
        if (plainBytes.size != payloadSize) throw IllegalStateException("soda 音频解密长度不一致")
        plainBytes.copyInto(result, mdat.offset + 8)

        // 把 stsd 里的 enca 改回 frma 指向的原始编码，播放器才认识
        val stsd = findBox(data, "stsd", stbl.offset + 8, stbl.offset + stbl.size)
        if (stsd != null) {
            val end = minOf(stsd.offset + stsd.size, result.size)
            val enca = indexOf(result, "enca", stsd.offset, end)
            if (enca >= 0) {
                encryptedSampleOriginalFormat(result, stsd.offset, end)
                    .copyInto(result, enca)
            }
        }
        return result
    }

    private fun findStblDeep(data: ByteArray, moov: Box): Box? {
        val trak = findBox(data, "trak", moov.offset + 8, moov.offset + moov.size) ?: return null
        val mdia = findBox(data, "mdia", trak.offset + 8, trak.offset + trak.size) ?: return null
        val minf = findBox(data, "minf", mdia.offset + 8, mdia.offset + mdia.size) ?: return null
        return findBox(data, "stbl", minf.offset + 8, minf.offset + minf.size)
    }

    private fun encryptedSampleOriginalFormat(data: ByteArray, start: Int, end: Int): ByteArray {
        val fallback = byteArrayOf('m'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), 'a'.code.toByte())
        val frma = indexOf(data, "frma", start, end)
        if (frma < 4 || frma + 8 > end) return fallback
        val size = readU32(data, frma - 4)
        if (size < 12 || frma - 4L + size > end) return fallback
        return data.copyOfRange(frma + 4, frma + 8)
    }

    private fun defaultPerSampleIvSize(data: ByteArray, start: Int, end: Int): Int {
        val tenc = findBoxDeep(data, "tenc", start, end) ?: return 8
        if (tenc.dataStart + 7 >= tenc.dataEnd) return 8
        val ivSize = data[tenc.dataStart + 7].toInt() and 0xff
        return if (ivSize == 8 || ivSize == 16) ivSize else 8
    }

    private fun parseStsz(data: ByteArray, box: Box): IntArray {
        val start = box.dataStart
        if (start + 12 > box.dataEnd) return IntArray(0)
        val fixed = readU32(data, start + 4).toInt()
        val declared = readU32(data, start + 8)
        val count = if (fixed != 0) {
            declared.coerceAtMost(MAX_SAMPLE_ENTRIES.toLong()).toInt()
        } else {
            val available = (box.dataEnd - start - 12) / 4
            minOf(declared, available.toLong(), MAX_SAMPLE_ENTRIES.toLong()).toInt()
        }
        val sizes = IntArray(count)
        if (fixed != 0) {
            sizes.fill(fixed)
        } else {
            for (index in 0 until count) {
                sizes[index] = readU32(data, start + 12 + index * 4).toInt()
            }
        }
        return sizes
    }

    private fun parseSenc(data: ByteArray, box: Box, ivSize: Int): List<SencSample> {
        val start = box.dataStart
        if (start + 8 > box.dataEnd) return emptyList()
        val safeIvSize = if (ivSize == 8 || ivSize == 16) ivSize else 8
        val flags = readU32(data, start).toInt() and 0x00FFFFFF
        val hasSubsamples = (flags and 0x02) != 0
        val declared = readU32(data, start + 4)
        val perSample = if (hasSubsamples) safeIvSize + 2 else safeIvSize
        val maxByData = (box.dataEnd - start - 8) / perSample.coerceAtLeast(1)
        val count = minOf(declared, maxByData.toLong(), MAX_SAMPLE_ENTRIES.toLong()).toInt()
        val samples = ArrayList<SencSample>(count)
        var ptr = start + 8
        for (index in 0 until count) {
            if (ptr + safeIvSize > box.dataEnd) break
            val iv = data.copyOfRange(ptr, ptr + safeIvSize)
            ptr += safeIvSize
            val subSamples = mutableListOf<SencSubsample>()
            if (hasSubsamples) {
                if (ptr + 2 > box.dataEnd) break
                val subCount = readU16(data, ptr)
                ptr += 2
                if (ptr + subCount * 6 > box.dataEnd) break
                for (sub in 0 until subCount) {
                    subSamples += SencSubsample(
                        clear = readU16(data, ptr),
                        encrypted = readU32(data, ptr + 2).toInt(),
                    )
                    ptr += 6
                }
            }
            samples += SencSample(iv, subSamples)
        }
        return samples
    }

    private fun decryptSample(key: ByteArray, chunk: ByteArray, sample: SencSample): ByteArray {
        val iv = ByteArray(AES_BLOCK_SIZE)
        sample.iv.copyInto(iv, endIndex = minOf(sample.iv.size, AES_BLOCK_SIZE))
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

        if (sample.subsamples.isEmpty()) return cipher.update(chunk)

        val output = ByteArray(chunk.size)
        var pos = 0
        for (sub in sample.subsamples) {
            val clearBytes = minOf(sub.clear, chunk.size - pos)
            chunk.copyInto(output, pos, pos, pos + clearBytes)
            pos += clearBytes
            if (pos >= chunk.size) break
            val encryptedBytes = minOf(sub.encrypted, chunk.size - pos)
            val plain = cipher.update(chunk, pos, encryptedBytes)
            plain.copyInto(output, pos)
            pos += encryptedBytes
            if (pos >= chunk.size) break
        }
        if (pos < chunk.size) chunk.copyInto(output, pos, pos, chunk.size)
        return output
    }

    private fun extractKey(playAuth: String): String {
        val bytes = decodeBase64(playAuth)
        if (bytes.size < 3) throw IllegalStateException("soda play_auth 太短")
        val paddingLen = ((bytes[0].toInt() and 0xff) xor
            (bytes[1].toInt() and 0xff) xor
            (bytes[2].toInt() and 0xff)) - 48
        if (paddingLen < 0 || bytes.size < paddingLen + 2) {
            throw IllegalStateException("soda play_auth 长度无效")
        }
        val inner = bytes.copyOfRange(1, bytes.size - paddingLen)
        val tmp = decryptSpadeInner(inner)
        if (tmp.isEmpty()) throw IllegalStateException("soda play_auth 解密失败")
        val skip = decodeBase36(tmp[0])
        val end = 1 + (bytes.size - paddingLen - 2) - skip
        if (end < 1 || end > tmp.size) throw IllegalStateException("soda play_auth 索引越界")
        return String(tmp, 1, end - 1, Charsets.UTF_8)
    }

    private fun decryptSpadeInner(keyBytes: ByteArray): ByteArray {
        val buff = ByteArray(keyBytes.size + 2)
        buff[0] = 0xFA.toByte()
        buff[1] = 0x55
        keyBytes.copyInto(buff, 2)
        val result = ByteArray(keyBytes.size)
        for (index in keyBytes.indices) {
            var value = ((keyBytes[index].toInt() and 0xff) xor
                (buff[index].toInt() and 0xff)) - Integer.bitCount(index) - 21
            while (value < 0) value += 255
            result[index] = value.toByte()
        }
        return result
    }

    private fun decodeBase36(byte: Byte): Int = when (byte.toInt() and 0xff) {
        in '0'.code..'9'.code -> (byte.toInt() and 0xff) - '0'.code
        in 'a'.code..'z'.code -> (byte.toInt() and 0xff) - 'a'.code + 10
        else -> 0xFF
    }

    private fun decodeBase64(value: String): ByteArray {
        val clean = value.filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' }
        val output = ByteArray(clean.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        var index = 0
        for (ch in clean) {
            if (ch == '=') break
            val digit = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> throw IllegalStateException("invalid soda play_auth base64")
            }
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output[index++] = (buffer ushr bits).toByte()
            }
        }
        return output.copyOf(index)
    }

    private fun findBox(data: ByteArray, type: String, start: Int, end: Int): Box? {
        val limit = minOf(end, data.size)
        var pos = start
        while (pos >= 0 && pos + 8 <= limit) {
            val size = readU32(data, pos)
            if (size < 8) break
            if (matches(data, pos + 4, type)) {
                val boxEnd = minOf(pos + size, data.size.toLong()).toInt()
                return Box(pos, size.toInt(), pos + 8, boxEnd)
            }
            if (pos + size > limit) break
            pos = (pos + size).toInt()
        }
        return null
    }

    private fun findBoxDeep(data: ByteArray, type: String, start: Int, end: Int): Box? {
        val limit = minOf(end, data.size)
        var pos = start
        while (pos >= 0 && pos + 8 <= limit) {
            var size = readU32(data, pos)
            var headerSize = 8L
            if (size == 1L) {
                if (pos + 16 > limit) break
                size = readU64(data, pos + 8)
                headerSize = 16
            }
            if (size < headerSize || pos + size > limit) break
            if (matches(data, pos + 4, type)) {
                return Box(pos, size.toInt(), (pos + headerSize).toInt(), (pos + size).toInt())
            }
            val boxType = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            val childStart = when (boxType) {
                "moov", "trak", "mdia", "minf", "stbl", "sinf", "schi" -> pos + headerSize
                "stsd" -> pos + headerSize + 8
                "enca", "mp4a", "alac", "fLaC" -> pos + headerSize + 28
                else -> -1L
            }
            if (childStart in 0 until pos + size) {
                findBoxDeep(data, type, childStart.toInt(), (pos + size).toInt())?.let { return it }
            }
            pos = (pos + size).toInt()
        }
        return null
    }

    private fun matches(data: ByteArray, offset: Int, type: String): Boolean {
        if (offset + 4 > data.size) return false
        for (index in 0 until 4) {
            if ((data[offset + index].toInt() and 0xff) != type[index].code) return false
        }
        return true
    }

    private fun indexOf(data: ByteArray, needle: String, start: Int, end: Int): Int {
        val limit = minOf(end, data.size) - needle.length
        for (index in start..limit) {
            if (matches(data, index, needle)) return index
        }
        return -1
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun readU32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)

    private fun readU64(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (data[offset + index].toLong() and 0xff)
        }
        return value
    }

    private data class Box(val offset: Int, val size: Int, val dataStart: Int, val dataEnd: Int)

    private data class SencSubsample(val clear: Int, val encrypted: Int)

    private data class SencSample(val iv: ByteArray, val subsamples: List<SencSubsample>)
}
