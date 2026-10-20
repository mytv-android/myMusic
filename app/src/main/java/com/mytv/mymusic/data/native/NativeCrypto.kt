package com.mytv.mymusic.data.native

import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

object NativeCrypto {
    private val secureRandom = SecureRandom()
    private const val LINUX_API_KEY = "7246674226682325323F5E6544673A51"
    private const val WEAPI_NONCE = "0CoJUm6Qyw8W8jud"
    private const val WEAPI_IV = "0102030405060708"
    private const val WEAPI_PUB_KEY = "010001"
    private const val WEAPI_MODULUS = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"

    fun md5Hex(value: String): String = bytesToHex(
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)),
        upper = false,
    )

    fun hexToBytes(value: String): ByteArray {
        val clean = value.trim()
        require(clean.length % 2 == 0) { "invalid hex string" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray, upper: Boolean = false): String {
        val alphabet = if (upper) "0123456789ABCDEF" else "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(alphabet[value ushr 4]).append(alphabet[value and 0x0f])
        }
        return out.toString()
    }

    fun randomString(size: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return buildString(size) {
            bytes.forEach { append(alphabet[(it.toInt() and 0xff) % alphabet.length]) }
        }
    }

    fun randomChinaIp(): String {
        val prefixes = arrayOf(
            intArrayOf(116, 255),
            intArrayOf(116, 228),
            intArrayOf(218, 192),
            intArrayOf(124, 0),
            intArrayOf(14, 132),
            intArrayOf(183, 14),
            intArrayOf(58, 14),
            intArrayOf(113, 116),
            intArrayOf(120, 230),
        )
        val prefix = prefixes[Random.nextInt(prefixes.size)]
        return "${prefix[0]}.${prefix[1]}.${Random.nextInt(1, 255)}.${Random.nextInt(1, 255)}"
    }

    fun aesEcbEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(pkcs7Pad(data, 16))
    }

    fun aesEcbDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    fun aesCbcEncryptBase64(text: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        val encrypted = cipher.doFinal(pkcs7Pad(text.toByteArray(Charsets.UTF_8), 16))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    fun pkcs7Pad(data: ByteArray, blockSize: Int): ByteArray {
        val padding = blockSize - data.size % blockSize
        return data + ByteArray(padding) { padding.toByte() }
    }

    fun pkcs7Unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val padding = data.last().toInt() and 0xff
        if (padding <= 0 || padding > data.size) return data
        for (index in 0 until padding) {
            if ((data[data.size - 1 - index].toInt() and 0xff) != padding) return data
        }
        return data.copyOf(data.size - padding)
    }

    fun rsaNoPadding(text: String, pubKeyHex: String, modulusHex: String): String {
        val textHex = bytesToHex(text.reversed().toByteArray(Charsets.UTF_8), upper = false)
        val value = BigInteger(textHex, 16)
        val exponent = BigInteger(pubKeyHex, 16)
        val modulus = BigInteger(modulusHex, 16)
        return value.modPow(exponent, modulus).toString(16).padStart(256, '0')
    }

    fun encryptLinux(data: String): String {
        val key = hexToBytes(LINUX_API_KEY)
        return bytesToHex(aesEcbEncrypt(data.toByteArray(Charsets.UTF_8), key), upper = true)
    }

    fun encryptWeApi(text: String): Pair<String, String> {
        val secKey = randomString(16)
        val first = aesCbcEncryptBase64(text, WEAPI_NONCE, WEAPI_IV)
        val params = aesCbcEncryptBase64(first, secKey, WEAPI_IV)
        val encSecKey = rsaNoPadding(secKey, WEAPI_PUB_KEY, WEAPI_MODULUS)
        return params to encSecKey
    }

    fun encryptEApi(url: String, payload: String): String {
        val path = runCatching { URI(url).path }.getOrNull().takeUnless { it.isNullOrBlank() } ?: url
        val normalized = path.replace("/eapi/", "/api/")
        val digest = md5Hex("nobody${normalized}use${payload}md5forencrypt")
        val data = "$normalized-36cd479b6b5-$payload-36cd479b6b5-$digest"
        val key = "e82ckenh8dichen8".toByteArray(Charsets.UTF_8)
        return bytesToHex(aesEcbEncrypt(data.toByteArray(Charsets.UTF_8), key), upper = false)
    }
}
