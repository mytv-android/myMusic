package com.mytv.mymusic.data.lx

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mytv.mymusic.AppLog
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 面板上「读电视上的文件夹」：电视侧枚举存储根、列目录、按路径导入脚本。 */
internal data class LxStorageRoot(val id: String, val label: String, val path: String)

internal data class LxFolderEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long)

/** 一页目录：[path] 为空表示存储根列表，[parent] 为空表示没有上一级（回到根列表）。 */
internal data class LxFolderPage(
    val path: String,
    val parent: String,
    val entries: List<LxFolderEntry>,
)

/**
 * 电视存储的只读视图。网页面板（手机）用它翻电视的文件夹，所以**只列目录、只认 .js**，
 * 不提供读任意文件内容的入口；路径一律先收敛到下面的存储根里，拒绝穿越。
 */
internal interface TvStorageProvider {
    /** 电视侧还没有列目录的权限时为 true，面板据此提示去授权。 */
    fun needsPermission(): Boolean

    fun roots(): List<LxStorageRoot>

    /** [path] 为空返回存储根列表；返回 null 表示不在存储根下面或读不了。 */
    fun list(path: String): LxFolderPage?

    /** 只接受存储根下面的 .js 文件，否则 null（导入时的最终校验）。 */
    fun resolveScript(path: String): File?
}

/** 单页最多列这么多目录和脚本：电视上一个文件夹可能有上千项，全塞进 JSON 没意义。 */
private const val MAX_DIRS = 200
private const val MAX_SCRIPTS = 200

internal class LxTvStorage(private val context: Context) : TvStorageProvider {

    override fun needsPermission(): Boolean = !canBrowseStorage(context)

    override fun roots(): List<LxStorageRoot> {
        val found = LinkedHashMap<String, File>()
        val labels = HashMap<String, String>()
        fun offer(defaultLabel: String, path: File?) {
            val canonical = path?.let { runCatching { it.canonicalFile }.getOrNull() } ?: return
            if (!canonical.isDirectory) return
            if (found.containsKey(canonical.path)) return
            found[canonical.path] = canonical
            labels[canonical.path] = defaultLabel
        }
        val primary = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
        offer("内部存储", primary)
        // app-specific 外部目录的上级就是所在卷的根；内部卷和可移动卷都会给一个（取不到的卷是 null）
        context.getExternalFilesDirs(null).forEach { dir ->
            val root = dir?.let { volumeRoot(it) } ?: return@forEach
            offer(if (root == primary) "内部存储" else "SD 卡 / U 盘", root)
        }
        // 兜底：有些设备只给内部卷，可移动卷直接扫 /storage
        runCatching { File("/storage").listFiles() }.getOrNull()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            if (child.name == "emulated" || child.name == "self") return@forEach
            offer("SD 卡 / U 盘", child)
        }
        // 同名卷（两块 U 盘）用目录名区分，否则用户在面板里分不清
        val used = HashSet<String>()
        return found.entries.mapIndexed { index, (path, _) ->
            val base = labels[path].orEmpty()
            val label = if (used.add(base)) base else "$base（${File(path).name}）"
            LxStorageRoot(index.toString(), label, path)
        }
    }

    override fun list(path: String): LxFolderPage? {
        if (path.isBlank()) {
            return LxFolderPage("", "", roots().map { LxFolderEntry(it.label, it.path, true, 0L) })
        }
        val dir = confine(path) ?: return null
        if (!dir.isDirectory) return null
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return null
        val folders = ArrayList<LxFolderEntry>()
        val scripts = ArrayList<LxFolderEntry>()
        for (child in children) {
            when {
                child.isDirectory -> folders += LxFolderEntry(child.name, child.path, true, 0L)
                child.isFile && child.name.endsWith(".js", ignoreCase = true) ->
                    scripts += LxFolderEntry(child.name, child.path, false, child.length())
            }
        }
        folders.sortBy { it.name.lowercase() }
        scripts.sortBy { it.name.lowercase() }
        // 已经在某个存储根上：再上一级就是根列表
        val atRoot = roots().any { it.path == dir.path }
        val parent = if (atRoot) "" else dir.parent.orEmpty()
        return LxFolderPage(dir.path, parent, folders.take(MAX_DIRS) + scripts.take(MAX_SCRIPTS))
    }

    override fun resolveScript(path: String): File? {
        val file = confine(path) ?: return null
        if (!file.isFile || !file.name.endsWith(".js", ignoreCase = true)) return null
        return file
    }

    /** 读一个电视上的脚本：和手机上传、本地选择走同一条大小与编码限制。 */
    suspend fun readScript(file: File): String = withContext(Dispatchers.IO) {
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_SCRIPT_BYTES) throw IllegalArgumentException("脚本超过 2 MB，无法导入")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        decodeUtf8(bytes)
    }

    /** 面板点了一个 .js：读出来交给 [LxSources.importLocal]，和另两条渠道同一套校验。 */
    suspend fun importScript(path: String): LxSourceRecord {
        val file = resolveScript(path) ?: throw IllegalArgumentException("这个文件不在可浏览的存储里，或不是 .js 脚本")
        return LxSources.importLocal(readScript(file))
    }

    /**
     * 把面板给的路径收敛到存储根下面：先规范化（消掉 `..` 和符号链接），再要求它是某个根的
     * 自身或子孙。面板因此只能碰电视的共享存储，碰不到应用私有目录或系统分区。
     */
    private fun confine(path: String): File? = confineToRoots(path, roots())
}

/**
 * [path] 规范化后必须是 [roots] 里某个根的自身或子孙，否则返回 null。
 * 单独抽出来是为了能在单测里用临时目录验穿越（`..`、符号链接、根以外的绝对路径）。
 */
internal fun confineToRoots(path: String, roots: List<LxStorageRoot>): File? {
    if (path.isBlank()) return null
    val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
    val root = roots.firstOrNull { root ->
        candidate.path == root.path || candidate.path.startsWith(root.path + File.separatorChar)
    } ?: run {
        AppLog.w("LxTvStorage", "面板要了存储根以外的路径：$path")
        return null
    }
    return candidate
}

/** 面板服务在单测里的默认值：没有电视存储可看。 */
internal object EmptyTvStorage : TvStorageProvider {
    override fun needsPermission(): Boolean = false
    override fun roots(): List<LxStorageRoot> = emptyList()
    override fun list(path: String): LxFolderPage? = null
    override fun resolveScript(path: String): File? = null
}

/** 列目录要的权限：Android 11 起是「所有文件管理」，更早只要读存储。 */
internal fun canBrowseStorage(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

/** 电视上要点的那个权限开关；返回 false 表示这台设备连设置页都没有，只能靠手机上传。 */
internal fun requestStorageAccess(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val attempts = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (intent in attempts) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
                return true
            }
        }
    }
    return runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
}

/** 读写失败时给一句人能懂的话，别把 IOException 的类名抛到面板上。 */
internal fun storageErrorMessage(error: Throwable): String = when (error) {
    is IOException -> "电视读这个文件失败"
    else -> error.message?.lineSequence()?.firstOrNull()?.take(200).orEmpty().ifBlank { "导入失败，未保存" }
}

/** `/storage/<卷>/Android/data/<包>/files` → `/storage/<卷>`。 */
private fun volumeRoot(appFiles: File): File? {
    val path = appFiles.path
    val marker = "${File.separator}Android${File.separator}data${File.separator}"
    val at = path.indexOf(marker)
    if (at > 0) return File(path.substring(0, at))
    return appFiles.parentFile?.parentFile?.parentFile
}
