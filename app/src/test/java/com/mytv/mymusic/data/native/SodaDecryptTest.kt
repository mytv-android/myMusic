package com.mytv.mymusic.data.native

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SodaDecryptTest {
    // 由 spade 算法反推出来的 play_auth，解出的密钥为下面的 key
    private val playAuth = "nL8T+Vu+EvdZvhL1X7kR90G6DvVfpSbcXaUm3luiJdyloA=="
    private val key = "00112233445566778899aabbccddeeff".chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
    private val ivs = listOf(ByteArray(8) { it.toByte() }, ByteArray(8) { (it + 8).toByte() })
    private val plain = listOf(ByteArray(32) { it.toByte() }, ByteArray(24) { (it * 3).toByte() })

    @Test
    fun decryptsSencSamples() {
        val encrypted = plain.mapIndexed { index, sample -> ctr(ivs[index]) { it.update(sample) } }
        val decrypted = SodaDecrypt.decryptAudio(mp4(encrypted), playAuth)
        val payload = mdatPayload(decrypted)
        val expected = plain.fold(ByteArray(0)) { acc, item -> acc + item }
        assertArrayEquals(expected, payload)
    }

    private fun mp4(samples: List<ByteArray>): ByteArray {
        val sizes = samples.map { it.size }
        val stsz = ByteArray(4) + u32(0) + u32(sizes.size) + sizes.map(::u32).fold(ByteArray(0)) { a, b -> a + b }
        val tenc = ByteArray(8).also { it[7] = 8 }
        val senc = ByteArray(4) + u32(ivs.size) + ivs.fold(ByteArray(0)) { acc, iv -> acc + iv }
        val stbl = box("stbl", box("stsz", stsz) + box("tenc", tenc) + box("senc", senc))
        val moov = box("moov", box("trak", box("mdia", box("minf", stbl))))
        val mdat = box("mdat", samples.fold(ByteArray(0)) { acc, item -> acc + item })
        return moov + mdat
    }

    private fun ctr(iv: ByteArray, block: (Cipher) -> ByteArray): ByteArray {
        val padded = ByteArray(16).also { iv.copyInto(it) }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(padded))
        return block(cipher)
    }

    private fun mdatPayload(data: ByteArray): ByteArray {
        var position = 0
        while (position + 8 <= data.size) {
            val size = readU32(data, position)
            val type = String(data, position + 4, 4, Charsets.ISO_8859_1)
            if (type == "mdat") return data.copyOfRange(position + 8, position + size)
            position += size
        }
        error("mdat not found")
    }

    private fun readU32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun box(type: String, payload: ByteArray): ByteArray =
        u32(payload.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + payload
}
