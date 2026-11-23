package com.mytv.mymusic.data.subsonic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

class SubsonicCoverCache(context: Context) {
    private val root = File(context.cacheDir, "subsonic-covers")
    private val gate = Semaphore(2)

    fun file(accountId: String, coverArt: String, size: Int = 300): File {
        val safeAccount = accountId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(File(root, safeAccount), "${md5(coverArt)}@$size.image")
    }

    fun uri(accountId: String, coverArt: String, size: Int = 300): String =
        SubsonicCoverUris.create(SUBSONIC_PROVIDER_PREFIX + accountId, coverArt, size)

    suspend fun ensure(client: SubsonicClient, coverArt: String, size: Int = 300) {
        if (coverArt.isBlank()) return
        val target = file(client.account.id, coverArt, size)
        if (target.length() > 0) return
        gate.withPermit {
            if (target.length() > 0) return
            val bytes = client.bytes("getCoverArt", listOf("id" to coverArt, "size" to size.toString()))
            if (bytes.isEmpty() || looksLikeJson(bytes)) throw IllegalStateException("服务器没有返回封面图片")
            withContext(Dispatchers.IO) {
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, "${target.name}.${System.nanoTime()}.part")
                try {
                    temporary.writeBytes(bytes)
                    if (target.exists()) target.delete()
                    if (!temporary.renameTo(target)) {
                        temporary.copyTo(target, overwrite = true)
                        temporary.delete()
                    }
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    fun removeAccount(accountId: String) {
        val safeAccount = accountId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        File(root, safeAccount).deleteRecursively()
    }

    private fun looksLikeJson(bytes: ByteArray): Boolean {
        val prefix = bytes.take(32).toByteArray().toString(Charsets.UTF_8).trimStart()
        return prefix.startsWith("{") && prefix.contains("subsonic-response")
    }
}
