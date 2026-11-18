package com.mytv.mymusic.ui.player.share

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.mytv.mymusic.ui.poster.coverUrlAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 卡片要画的图，都是取好的软件位图（卡片录进图层再导出，API 26–27 回放时画硬件位图会崩；AsyncImage 是异步的，
 * 点得快会录到空白）。模糊在 CPU 上预先做好（[softOf]）：RenderEffect 只有 API 31 起才有，录进图层回放时也不保证带上，
 * 预览和导出会不一样。
 *
 * @param art 封面（最长边 1080px），[artSoft] 是它的模糊小图（叠卡、对句的背景）。
 * @param picture 自选背景图（叠卡背景、封面卡的大图），[pictureSoft] 是它的模糊小图。
 * @param ready 该取的都取完了（成功或失败），保存、分享按钮这时才能点。
 */
@Immutable
internal class ShareImages(
    val art: ImageBitmap? = null,
    val artSoft: ImageBitmap? = null,
    val picture: ImageBitmap? = null,
    val pictureSoft: ImageBitmap? = null,
    val ready: Boolean = false,
)

/**
 * 取封面 [cover] 和自选图 [picture]（只这一次分享用）。自选图读不出来（权限被收回、云端图下不来）时回调 [onPictureFailed]，
 * 由调用方提示并清掉。
 */
@Composable
internal fun rememberShareImages(cover: String, picture: Uri?, onPictureFailed: () -> Unit): State<ShareImages> {
    val context = LocalContext.current
    val failed = rememberUpdatedState(onPictureFailed)
    return produceState(ShareImages(), cover, picture) {
        val art = loadShareBitmap(context, coverUrlAt(cover, SHARE_COVER_PX).takeIf { cover.isNotBlank() }, "$cover#share")
        val artSoft = art?.let { softOf(it) }
        // 先放出封面，自选图慢一点也不耽误预览
        value = ShareImages(art?.asImageBitmap(), artSoft?.asImageBitmap(), ready = picture == null)
        if (picture == null) return@produceState
        val chosen = loadShareBitmap(context, picture, "$picture#share")
        if (chosen == null) failed.value()
        value = ShareImages(art?.asImageBitmap(), artSoft?.asImageBitmap(), chosen?.asImageBitmap(), chosen?.let { softOf(it) }?.asImageBitmap(), ready = true)
    }
}

/** 软件位图，最长边 [SHARE_IMAGE_PX]；取不到为 null。 */
private suspend fun loadShareBitmap(context: Context, data: Any?, cacheKey: String): Bitmap? {
    if (data == null) return null
    val request = ImageRequest.Builder(context)
        .data(data)
        .size(SHARE_IMAGE_PX)
        .allowHardware(false)
        .memoryCacheKey(cacheKey)
        .build()
    val result = runCatching { SingletonImageLoader.get(context).execute(request) }.getOrNull() as? SuccessResult ?: return null
    val bitmap = runCatching { result.image.toBitmap() }.getOrNull() ?: return null
    return if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
}

/** 模糊小图：缩到 [SHARE_SOFT_PX] 宽，三遍盒式模糊。画的时候铺满拉伸，效果接近卡片上 10–12px 的高斯模糊。 */
private suspend fun softOf(source: Bitmap): Bitmap = withContext(Dispatchers.Default) {
    val w = SHARE_SOFT_PX
    val h = (source.height * w / source.width.coerceAtLeast(1)).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(source, w, h, true)
    val pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    Bitmap.createBitmap(boxBlur(pixels, w, h, SHARE_BLUR_RADIUS), w, h, Bitmap.Config.ARGB_8888)
}

/**
 * 三遍盒式模糊（每遍先横后竖，半径 [radius]），四个通道一起模糊，边上按最近的像素补。纯函数：输入不改，返回新数组。
 * 三遍盒式的结果接近高斯模糊，σ ≈ √((2r+1)² − 1) / 2。
 */
internal fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
    if (radius <= 0 || w <= 0 || h <= 0) return pixels.copyOf()
    val channels = Array(4) { c -> FloatArray(w * h) { i -> ((pixels[i] ushr (24 - c * 8)) and 0xFF).toFloat() } }
    val scratch = FloatArray(w * h)
    repeat(3) {
        channels.forEach { channel ->
            boxPass(channel, scratch, w, h, radius, horizontal = true)
            boxPass(scratch, channel, w, h, radius, horizontal = false)
        }
    }
    return IntArray(w * h) { i ->
        var argb = 0
        for (c in 0 until 4) argb = argb or (channels[c][i].let { kotlin.math.round(it).toInt().coerceIn(0, 255) } shl (24 - c * 8))
        argb
    }
}

/** 一遍一维盒式平均：滑动窗口，边上按最近的像素补。 */
private fun boxPass(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val stride = if (horizontal) 1 else w
    val window = 2 * r + 1
    for (line in 0 until lines) {
        val base = if (horizontal) line * w else line
        fun at(i: Int) = src[base + i.coerceIn(0, length - 1) * stride]
        var sum = 0f
        for (i in -r..r) sum += at(i)
        for (i in 0 until length) {
            dst[base + i * stride] = sum / window
            sum += at(i + r + 1) - at(i - r)
        }
    }
}

/** 封面取图的档位（[coverUrlAt]）和解码后的最长边。 */
private const val SHARE_COVER_PX = 800
private const val SHARE_IMAGE_PX = 1080

/** 模糊小图宽 180px（卡片 720px 的四分之一）、半径 3（Rush 的 10–12px 模糊除以 4）。 */
private const val SHARE_SOFT_PX = 180
private const val SHARE_BLUR_RADIUS = 3
