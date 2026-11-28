package com.mytv.mymusic.data.local

import android.annotation.TargetApi
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.OutputStream

/**
 * 把已下载的音频（连同同名 .lrc）复制一份到公共 Download/myMusic，给别的播放器或电脑拷走用。
 * 应用内那份照旧留在私有目录，离线播放和占用统计不受影响。
 * Android 10 起走 MediaStore，不要任何权限；更早的系统写公共目录，调用方先申请 WRITE_EXTERNAL_STORAGE。
 */
object DownloadExport {
    /** 公共目录里的文件夹名（Download/myMusic）。 */
    const val FOLDER = "myMusic"

    /** 同名文件直接替换，重复导出不会叠出“文件 (1)”；音频文件已不在时抛异常。 */
    fun export(context: Context, audio: File) {
        if (!audio.isFile) error("本地文件已不存在")
        putFile(context, audio.name, mimeOf(audio.name)) { output -> audio.inputStream().use { it.copyTo(output, 256 * 1024) } }
        val lyric = LocalLrc.readSidecar(audio) ?: return
        val name = LocalLrc.sidecarName(audio.name)
        putFile(context, name, "text/plain") { output -> output.write(lyric.toByteArray(Charsets.UTF_8)) }
    }

    private fun putFile(context: Context, name: String, mime: String, write: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= 29) {
            putFileModern(context, name, mime, write)
            return
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use(write)
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mime), null)
    }

    @TargetApi(29)
    private fun putFileModern(context: Context, name: String, mime: String, write: (OutputStream) -> Unit) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"
        // 先删同名旧文件：只删得掉自己导出过的，删不掉（换过设备/重装后归属丢失）就让系统另起名字
        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf("$relative/", name),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    resolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null)
                }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("无法写入 Download/$FOLDER")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: error("无法写入 Download/$FOLDER")
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    private fun mimeOf(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }
}
