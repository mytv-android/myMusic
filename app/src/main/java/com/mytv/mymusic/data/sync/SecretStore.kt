package com.mytv.mymusic.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("mymusic_secrets", Context.MODE_PRIVATE)
    private val alias = "mymusic_sync_token"

    fun hasToken(provider: String): Boolean = preferences.contains(key(provider))

    fun saveToken(provider: String, token: String) = put(key(provider), token)

    fun token(provider: String): String? = get(key(provider))

    fun removeToken(provider: String) {
        preferences.edit().remove(key(provider)).apply()
    }

    /**
     * 不属于某个同步后端的机密（目前是 WebDAV 密码）。和令牌分开命名，断开同步时只删令牌，
     * WebDAV 账号还被上传用着时就能留下来。
     */
    fun hasSecret(name: String): Boolean = preferences.contains(secretName(name))

    fun saveSecret(name: String, value: String) = put(secretName(name), value)

    fun secret(name: String): String? = get(secretName(name))

    fun removeSecret(name: String) {
        preferences.edit().remove(secretName(name)).apply()
    }

    private fun put(key: String, plain: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        preferences.edit().putString(key, value).apply()
    }

    private fun get(key: String): String? {
        val value = preferences.getString(key, null) ?: return null
        return runCatching {
            val (ivPart, dataPart) = value.split(':', limit = 2).also {
                if (it.size != 2) return null
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(ivPart, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(dataPart, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun key(provider: String) = "token_$provider"

    private fun secretName(name: String) = "secret_$name"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
