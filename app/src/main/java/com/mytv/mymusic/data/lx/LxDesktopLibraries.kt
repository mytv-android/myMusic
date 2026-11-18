package com.mytv.mymusic.data.lx

import android.content.Context
import com.mytv.mymusic.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 桌面版（Electron）插件的运行依赖：axios 与 he。
 *
 * 脚本里写的是 `require('axios')`，手机端没有 npm，只能把官方 dist 包取回来交给宿主。
 * 固定版本 + 官方 CDN + sha256 校验：版本不再漂移，下载到的字节必须就是这里写死的那一份，
 * 否则不认。取回来缓存在本机，之后离线也能导入与运行。
 *
 * 风险（导入界面也会告诉用户）：脚本本身仍然是用户从网上下载的可执行代码，
 * 它可以用 axios 访问任意 HTTP 地址；这一层只保证依赖库没有被换掉。
 */
internal object LxDesktopLibraries {
    /** 官方 dist 包：[url] 是 npm 官方源，[sha256] 是这份文件的摘要。 */
    private data class Library(val name: String, val version: String, val url: String, val sha256: String)

    private val LIBRARIES = listOf(
        Library(
            name = "axios",
            version = "1.7.9",
            url = "https://cdn.jsdelivr.net/npm/axios@1.7.9/dist/axios.min.js",
            sha256 = "9cf48244581d6cb6486d6702f7372292284faef2489a3be419ac1bc70606be72",
        ),
        Library(
            name = "he",
            version = "1.2.0",
            url = "https://cdn.jsdelivr.net/npm/he@1.2.0/he.js",
            sha256 = "76c554d5bbfd032fe620595076a50abea5124b9cbd4e9ffe6ac94a4f855aeceb",
        ),
    )

    private const val MAX_BYTES = 1_000_000
    private val mutex = Mutex()

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private fun cacheDir(context: Context): File = File(context.filesDir, "lx-libs")

    /**
     * 取齐全部依赖的源码，交给宿主 `__lx_desktop_provide_library__(name, source)`。
     * 缓存命中直接用；缺失或校验不过就重新下载。下载失败时抛 [IllegalStateException]，导入中止。
     */
    suspend fun ensure(context: Context): Map<String, String> = mutex.withLock {
        val lazy = LinkedHashMap<String, String>()
        for (library in LIBRARIES) {
            val file = File(cacheDir(context), "${library.name}-${library.version}.js")
            val cached = file.takeIf { it.isFile }?.readTextSample()
            if (cached != null && cached.sha256 == library.sha256) {
                lazy[library.name] = cached.source
                continue
            }
            val source = download(library)
            runCatching { writeCache(file, source) }
            lazy[library.name] = source
        }
        lazy
    }

    private class Cached(val source: String, val sha256: String)

    private fun File.readTextSample(): Cached? = runCatching {
        val bytes = readBytes()
        Cached(String(bytes, Charsets.UTF_8), bytes.sha256())
    }.getOrNull()

    private fun writeCache(file: File, source: String) {
        file.parentFile?.mkdirs()
        file.writeBytes(source.toByteArray(Charsets.UTF_8))
    }

    private suspend fun download(library: Library): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(library.url).header("Accept", "application/javascript").build()
        val response = try {
            client().newCall(request).execute()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            throw IllegalStateException("下载 ${library.name}@${library.version} 失败：${error.message}")
        }
        response.use {
            if (!it.isSuccessful) throw IllegalStateException("下载 ${library.name}@${library.version} 失败：HTTP ${it.code}")
            val bytes = it.body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BYTES) throw IllegalStateException("${library.name} 超过 ${MAX_BYTES / 1024} KB，中止")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val actual = bytes.sha256()
            if (actual != library.sha256) {
                AppLog.w("LxSources", "${library.name} 校验失败：期望 ${library.sha256}，实际 $actual")
                throw IllegalStateException(
                    "${library.name}@${library.version} 校验失败（下载到的文件与官方版本不一致），已中止导入",
                )
            }
            String(bytes, Charsets.UTF_8)
        }
    }

    fun summary(): String = LIBRARIES.joinToString("、") { "${it.name}@${it.version}" }
}

internal fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
