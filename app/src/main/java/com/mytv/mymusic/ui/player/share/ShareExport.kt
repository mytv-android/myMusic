package com.mytv.mymusic.ui.player.share

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 等这一帧画完（图层里是最新的样子），再把图层取成位图；图层还没画过（尺寸为 0）时返回 null。
 * API 28 起取出来是 HARDWARE 位图，压 PNG 前拷成软件位图。位图尺寸就是卡片的像素尺寸（非全屏 720 × 卡高，全屏 1080×1920），
 * 和设备密度无关。
 */
internal suspend fun captureLayer(layer: GraphicsLayer): Bitmap? {
    withFrameNanos { }
    if (layer.size.width <= 0 || layer.size.height <= 0) return null
    val bitmap = layer.toImageBitmap().asAndroidBitmap()
    return if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
}

/**
 * 压成 PNG 放进 cache/share/，给系统分享用。刚分享的文件不立刻删（目标应用可能过一会儿才读），每次导出时清掉一天前的。
 */
internal suspend fun writeShareCache(context: Context, bitmap: Bitmap, name: String): File = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, SHARE_CACHE_DIR).apply { mkdirs() }
    val stale = System.currentTimeMillis() - SHARE_CACHE_KEEP_MS
    dir.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
    File(dir, name).apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
}

/** 系统分享面板：PNG + 附带文字；ClipData 让面板顶上有缩略图，也一并授权读。 */
internal fun shareImageIntent(context: Context, file: File, text: String): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "分享歌词")
}

/**
 * 存到相册 Pictures/myMusic。Android 10 起走 MediaStore，不要任何权限；更早写公共目录（要 WRITE_EXTERNAL_STORAGE，
 * 调用方先申请）再通知媒体库。失败返回 null。
 */
internal suspend fun saveToGallery(context: Context, bitmap: Bitmap, name: String): Uri? = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= 29) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SHARE_ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }
            .getOrNull() ?: return@withContext null
        val written = runCatching {
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.getOrDefault(false)
        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        uri
    } else {
        runCatching {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), SHARE_ALBUM).apply { mkdirs() }
            val file = File(dir, name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/png"), null)
            Uri.fromFile(file)
        }.getOrNull()
    }
}

/** 相册里的文件夹名（Pictures/myMusic）。 */
internal const val SHARE_ALBUM = "myMusic"

private const val SHARE_CACHE_DIR = "share"
private const val SHARE_CACHE_KEEP_MS = 24 * 3600_000L
