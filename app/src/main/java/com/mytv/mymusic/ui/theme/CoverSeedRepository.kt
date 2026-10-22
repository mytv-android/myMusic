package com.mytv.mymusic.ui.theme

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.ktx.themeColorOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从封面取种子色。请求 128px 的软件位图，在 Default 线程量化取色，按封面地址缓存 64 条。
 */
class CoverSeedRepository(private val context: Context, private val imageLoader: ImageLoader) {
    private val cache = LruCache<String, Int>(64)

    fun cached(url: String): Int? = if (url.isBlank()) null else cache.get(url)

    suspend fun seedFor(url: String): Int? {
        if (url.isBlank()) return null
        cache.get(url)?.let { return it }
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(128)
            .allowHardware(false)
            .build()
        val result = imageLoader.execute(request) as? SuccessResult ?: return null
        val seed = withContext(Dispatchers.Default) {
            runCatching { result.image.toBitmap().asImageBitmap().themeColorOrNull()?.toArgb() }.getOrNull()
        } ?: return null
        cache.put(url, seed)
        return seed
    }
}
