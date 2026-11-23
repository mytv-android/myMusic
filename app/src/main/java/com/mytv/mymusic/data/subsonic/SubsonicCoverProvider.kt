package com.mytv.mymusic.data.subsonic

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.mytv.mymusic.MyMusicApp
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/** 只给本进程的图片组件用：可见封面按需下载到 cacheDir，再次打开直接读本地文件。 */
class SubsonicCoverProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("只读")
        val segments = uri.pathSegments
        if (segments.size != 2) throw FileNotFoundException("封面地址无效")
        val app = context?.applicationContext as? MyMusicApp ?: throw FileNotFoundException("应用尚未就绪")
        val accountId = segments[0]
        val coverArt = segments[1]
        val size = uri.getQueryParameter("size")?.toIntOrNull()?.coerceIn(64, 1200) ?: 300
        val client = app.subsonic.routingClients().firstOrNull { it.account.id == accountId }
            ?: throw FileNotFoundException("服务器账号不可用")
        val file = app.subsonic.covers.file(accountId, coverArt, size)
        if (file.length() <= 0) {
            try {
                runBlocking { app.subsonic.covers.ensure(client, coverArt, size) }
            } catch (error: Exception) {
                throw FileNotFoundException(error.message ?: "封面不可用")
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/*"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
