package com.mytv.mymusic.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/** MediaMetadataRetriever 读到的标签；拿不到的项为空 / 0。 */
data class LocalFileTags(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val bitrate: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val mimeType: String,
    val picture: ByteArray?,
)

/**
 * 标签和内嵌封面。扫描第一遍只登记文件，这一步放在第二遍、一首一首读，可以随时停。
 * 封面缩到 600px 以内存成 JPEG：原图常有几 MB，直接给列表和通知用太浪费内存。
 */
object LocalMetadata {
    private const val COVER_MAX_PX = 600

    fun read(context: Context, uri: Uri): LocalFileTags {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun text(key: Int) = runCatching { retriever.extractMetadata(key) }.getOrNull()?.trim().orEmpty()
            fun number(key: Int) = text(key).toLongOrNull() ?: 0L
            val sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                number(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE).toInt()
            } else {
                0
            }
            val bits = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                number(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE).toInt()
            } else {
                0
            }
            return LocalFileTags(
                title = text(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = text(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    .ifBlank { text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) },
                album = text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = number(MediaMetadataRetriever.METADATA_KEY_DURATION),
                bitrate = number(MediaMetadataRetriever.METADATA_KEY_BITRATE).toInt(),
                sampleRate = sampleRate,
                bitsPerSample = bits,
                mimeType = text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                picture = runCatching { retriever.embeddedPicture }.getOrNull(),
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun coverDir(context: Context): File = File(context.filesDir, "local_covers")

    /** 内嵌封面缩小后写缓存，返回 `file://` 地址；解不出图返回空。文件名取身份的十六进制部分，同一首歌重扫覆盖同一个文件。 */
    fun saveCover(context: Context, localId: String, bytes: ByteArray): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ""
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_MAX_PX && bounds.outHeight / (sample * 2) >= COVER_MAX_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return ""
        val scale = COVER_MAX_PX.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        val dir = coverDir(context).apply { mkdirs() }
        // 只取十六进制字符拼文件名：身份串哪怕被同步数据带坏，也拼不出跳出目录的路径
        val name = localId.removePrefix(LocalIdentity.PREFIX).filter { it in '0'..'9' || it in 'a'..'f' }.take(32)
        if (name.isEmpty()) return ""
        val file = File(dir, "$name.jpg")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out) }
        bitmap.recycle()
        // 文件名跟着内容身份走：内容一变身份就变，同一个地址永远是同一张图，图片缓存不会串
        return Uri.fromFile(file).toString()
    }
}
