package com.mytv.mymusic.data.local

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.IOException
import java.util.Locale

/**
 * 列出这台设备上的音频：系统媒体库（要读取音乐权限）和用户用系统文件夹选择器授权的目录（不要权限）。
 * 这里只列文件、不打开文件；算身份和读标签在 [LocalLibrary] 里分两遍做。
 */
object LocalScanner {
    private val audioExtensions = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wave", "amr", "mka", "3gp", "mp4", "alac", "aiff", "aif",
    )
    private val notAudioMimes = setOf("audio/x-mpegurl", "audio/mpegurl", "audio/x-scpls", "audio/x-ms-asx")
    private val skippedFolders = listOf("Ringtones/", "Notifications/", "Alarms/")

    /**
     * 媒体库里的音乐。排除铃声、通知、闹钟目录和 myMusic 自己的下载目录（下载的歌已经在「下载」里）；
     * 落在已授权文件夹里的跳过，交给文件夹扫描——那边能顺带找到同名 .lrc。
     */
    fun queryMediaStore(context: Context, folders: List<LocalFolder>): List<ScannedFile> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.MIME_TYPE)
            @Suppress("DEPRECATION")
            add(MediaStore.Audio.Media.DATA)
            if (modern) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
                add(MediaStore.Audio.Media.VOLUME_NAME)
            }
        }.toTypedArray()
        val ownDownloads = "Android/data/${context.packageName}/"
        val covered = folders.mapNotNull { coveredPath(it.uri) }
        val result = ArrayList<ScannedFile>()
        val cursor = context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        ) ?: throw IOException("媒体库查询失败")
        cursor.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val added = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val modified = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val mime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            @Suppress("DEPRECATION")
            val data = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val relative = if (modern) c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1
            val volume = if (modern) c.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME) else -1
            while (c.moveToNext()) {
                val path = if (data >= 0) c.getString(data).orEmpty() else ""
                val relativePath = (if (relative >= 0) c.getString(relative) else null) ?: relativeFromData(path)
                val volumeName = (if (volume >= 0) c.getString(volume) else null) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                if (relativePath.startsWith(ownDownloads) || path.contains("/$ownDownloads")) continue
                if (skippedFolders.any { relativePath.startsWith(it) }) continue
                if (covered.any { it.covers(volumeName, relativePath) }) continue
                val itemId = c.getLong(id)
                val bytes = c.getLong(size)
                if (bytes <= 0) continue
                result += ScannedFile(
                    uri = ContentUris.withAppendedId(collection, itemId).toString(),
                    origin = LOCAL_ORIGIN_MEDIA,
                    displayName = c.getString(name).orEmpty().ifBlank { path.substringAfterLast('/') },
                    relativePath = relativePath,
                    size = bytes,
                    // 媒体库的修改时间是秒，统一成毫秒，和文件夹扫描的 LAST_MODIFIED 一致
                    modifiedAt = c.getLong(modified) * 1000L,
                    mimeType = c.getString(mime).orEmpty(),
                    title = c.getString(title).orEmpty(),
                    artist = c.getString(artist).orEmpty(),
                    album = c.getString(album).orEmpty(),
                    durationMs = c.getLong(duration),
                    dataPath = path,
                    addedAt = c.getLong(added) * 1000L,
                )
            }
        }
        return result
    }

    /** /storage/emulated/0/Music/a.mp3 → Music/（Android 9 及以下没有 RELATIVE_PATH 列）。 */
    internal fun relativeFromData(path: String): String {
        val marker = Regex("""^/storage/(?:emulated/\d+|[^/]+)/""").find(path) ?: return ""
        val rest = path.substring(marker.range.last + 1)
        val slash = rest.lastIndexOf('/')
        return if (slash < 0) "" else rest.substring(0, slash + 1)
    }

    /** 外部存储提供方的文件夹在媒体库里对应哪一段路径（卷名 + 相对路径前缀）。别的提供方（网盘、U 盘应用）对不上，返回 null。 */
    private data class CoveredPath(val volume: String, val prefix: String) {
        fun covers(volumeName: String, relativePath: String): Boolean {
            val sameVolume = when (volume) {
                "primary" -> volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY || volumeName == "external"
                else -> volumeName.equals(volume, ignoreCase = true)
            }
            return sameVolume && (prefix.isEmpty() || relativePath.startsWith(prefix, ignoreCase = true))
        }
    }

    private fun coveredPath(treeUri: String): CoveredPath? {
        val uri = Uri.parse(treeUri)
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val volume = docId.substringBefore(':')
        val path = docId.substringAfter(':', "").trim('/')
        return CoveredPath(volume, if (path.isEmpty()) "" else "$path/")
    }

    /**
     * 递归列出文件夹里的音频。跳过点开头的隐藏目录；每个目录里顺带找同名 .lrc，记下它的文档 uri。
     * 根目录读不出来就抛异常：调用方据此认为这个文件夹这次没扫成，不把里面的歌标成“文件不在了”。
     */
    fun walkTree(context: Context, folder: LocalFolder, isActive: () -> Boolean = { true }): List<ScannedFile> {
        val treeUri = Uri.parse(folder.uri)
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val result = ArrayList<ScannedFile>()
        val pending = ArrayDeque<Pair<String, String>>()
        pending += rootId to "${folder.name.trim('/')}/"
        var first = true
        while (pending.isNotEmpty() && isActive()) {
            val (docId, relativePath) = pending.removeFirst()
            val children = try {
                listChildren(resolver, treeUri, docId)
            } catch (error: Exception) {
                if (first) throw IOException("读不了文件夹 ${folder.name}", error)
                continue
            }
            first = false
            val lrcs = children.filter { !it.isDir && it.name.endsWith(".lrc", ignoreCase = true) }
            for (child in children) {
                if (child.isDir) {
                    if (!child.name.startsWith(".")) pending += child.id to "$relativePath${child.name}/"
                    continue
                }
                if (!isAudio(child.name, child.mime) || child.size <= 0) continue
                val lrcName = LocalLrc.findSidecar(child.name, lrcs.map { it.name })
                val lrc = lrcs.firstOrNull { it.name == lrcName }
                result += ScannedFile(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id).toString(),
                    origin = folder.uri,
                    displayName = child.name,
                    relativePath = relativePath,
                    size = child.size,
                    modifiedAt = child.modified,
                    mimeType = child.mime,
                    lrcUri = lrc?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id).toString() }.orEmpty(),
                )
            }
        }
        return result
    }

    private data class Child(val id: String, val name: String, val mime: String, val size: Long, val modified: Long) {
        val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun listChildren(resolver: ContentResolver, treeUri: Uri, docId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = resolver.query(uri, projection, null, null, null) ?: throw IOException("文件夹查询失败")
        val children = ArrayList<Child>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                children += Child(
                    id = id,
                    name = c.getString(1).orEmpty(),
                    mime = c.getString(2).orEmpty(),
                    size = if (c.isNull(3)) 0L else c.getLong(3),
                    modified = if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
        return children
    }

    internal fun isAudio(name: String, mime: String): Boolean {
        val lower = mime.lowercase(Locale.ROOT)
        if (lower in notAudioMimes) return false
        if (lower.startsWith("audio/")) return true
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in audioExtensions &&
            (lower.isEmpty() || lower == "application/octet-stream" || lower.startsWith("video/mp4") || lower == "video/3gpp")
    }

    /** 文件夹选择器给的 tree uri 的显示名：外部存储取最后一段路径，别的提供方问一下它的名字。 */
    fun folderName(context: Context, treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        val fromQuery = runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        if (treeUri.authority == EXTERNAL_STORAGE_AUTHORITY) {
            val path = docId.substringAfter(':', "").trim('/')
            if (path.isNotEmpty()) return path
        }
        return fromQuery?.takeIf { it.isNotBlank() } ?: docId.substringAfter(':').ifBlank { "文件夹" }
    }

    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
}
