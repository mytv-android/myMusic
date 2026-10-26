package com.mytv.mymusic.data.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.mytv.mymusic.data.SettingsStore
import com.mytv.mymusic.data.sync.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Serializable
data class UpdateManifest(
    val schema: String = "",
    val channel: String = "stable",
    val versionCode: Long = 0,
    val versionName: String = "",
    val tag: String = "",
    val publishedAt: String = "",
    val minSdk: Int = 0,
    val apk: UpdateApk = UpdateApk(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class UpdateApk(
    val url: String = "",
    @SerialName("size") val size: Long = 0,
    @SerialName("sha256") val sha256: String = "",
)

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val tag: String,
    val publishedAt: String,
    val size: Long,
    val url: String,
    val sha256: String,
    val notes: List<String>,
)

enum class UpdatePhase { Idle, Checking, Available, Downloading, Verifying, Ready, Error }

data class UpdateUiState(
    val currentVersionName: String = "",
    val currentVersionCode: Long = 0,
    val phase: UpdatePhase = UpdatePhase.Idle,
    val info: UpdateInfo? = null,
    val progress: Float = 0f,
    val phaseLabel: String = "",
    val message: String = "",
    val error: String = "",
    val file: File? = null,
    val permissionRequired: Boolean = false,
    val skippedCode: Long = 0,
)

enum class InstallResult { Launched, NeedsPermission, Failed }

class UpdateRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    @Volatile
    private var activeCall: okhttp3.Call? = null

    @Volatile
    private var cancelRequested = false

    private var installResultReceiver: BroadcastReceiver? = null

    init {
        val info = currentPackageInfo()
        _state.value = UpdateUiState(
            currentVersionName = info?.name.orEmpty(),
            currentVersionCode = info?.code ?: 0L,
        )
    }

    private data class CurrentVersion(val name: String, val code: Long)

    private fun currentPackageInfo(): CurrentVersion? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        CurrentVersion(info.versionName.orEmpty(), code)
    }.getOrNull()

    suspend fun check(manual: Boolean): Boolean = withContext(Dispatchers.IO) {
        _state.update { it.copy(phase = UpdatePhase.Checking, phaseLabel = "正在检查更新", error = "", permissionRequired = false) }
        try {
            val request = Request.Builder()
                .url(MANIFEST_URL)
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "myMusic-Android")
                .build()
            val raw = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("检查更新失败（HTTP ${response.code}）")
                response.body.string()
            }
            val manifest = json.decodeFromString<UpdateManifest>(raw)
            if (manifest.schema != "mymusic.update") throw IOException("更新清单格式不受支持")
            settings.lastUpdateCheck(System.currentTimeMillis())
            val current = currentPackageInfo() ?: throw IOException("无法读取当前版本")
            if (manifest.minSdk > 0 && Build.VERSION.SDK_INT < manifest.minSdk) {
                throw IOException("这个更新需要 Android ${manifest.minSdk} 或更高版本")
            }
            if (manifest.versionCode <= current.code) {
                _state.update {
                    it.copy(
                        currentVersionName = current.name,
                        currentVersionCode = current.code,
                        phase = UpdatePhase.Idle,
                        info = null,
                        message = "已是最新版本",
                        error = "",
                        phaseLabel = "",
                    )
                }
                return@withContext true
            }
            if (manifest.apk.url.isBlank() || manifest.apk.sha256.isBlank()) {
                throw IOException("更新清单缺少安装包地址或校验信息")
            }
            val info = UpdateInfo(
                versionCode = manifest.versionCode,
                versionName = manifest.versionName,
                tag = manifest.tag,
                publishedAt = manifest.publishedAt,
                size = manifest.apk.size,
                url = manifest.apk.url,
                sha256 = manifest.apk.sha256,
                notes = manifest.notes,
            )
            _state.update {
                it.copy(
                    currentVersionName = current.name,
                    currentVersionCode = current.code,
                    phase = UpdatePhase.Available,
                    info = info,
                    message = if (manual) "发现新版本 ${info.versionName}（构建 ${info.versionCode}）" else "",
                    error = "",
                    phaseLabel = "",
                )
            }
            true
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    phase = if (manual) UpdatePhase.Error else UpdatePhase.Idle,
                    phaseLabel = "",
                    error = if (manual) error.message ?: "检查更新失败" else "",
                    message = if (manual) "" else it.message,
                )
            }
            false
        }
    }

    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        val info = _state.value.info ?: return@withContext false
        val current = _state.value.phase
        if (current == UpdatePhase.Downloading || current == UpdatePhase.Verifying) return@withContext false
        cancelRequested = false
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "myMusic-${info.versionName}.apk")
        if (info.size > 0 && directory.usableSpace < info.size + 8L * 1024 * 1024) {
            _state.update {
                it.copy(
                    phase = UpdatePhase.Error,
                    error = "存储空间不足，至少需要 ${formatSize(info.size)} 可用空间",
                    file = null,
                )
            }
            return@withContext false
        }
        _state.update {
            it.copy(
                phase = UpdatePhase.Downloading,
                progress = 0f,
                phaseLabel = "正在下载",
                error = "",
                file = null,
                permissionRequired = false,
            )
        }
        try {
            val request = Request.Builder()
                .url(info.url)
                .header("User-Agent", "myMusic-Android")
                .build()
            val call = http.newCall(request)
            activeCall = call
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载失败（HTTP ${response.code}）")
                val body = response.body
                val total = info.size.takeIf { it > 0 } ?: body.contentLength()
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                val progressGate = DownloadProgressGate()
                val buffer = ByteArray(64 * 1024)
                target.outputStream().use { output ->
                    body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            copied += read
                            if (total > 0 && progressGate.shouldEmit(System.nanoTime(), complete = copied >= total)) {
                                _state.update { it.copy(progress = (copied.toFloat() / total).coerceIn(0f, 1f)) }
                            }
                        }
                    }
                }
                if (info.size > 0 && copied != info.size) {
                    throw IOException("下载大小与更新清单不一致")
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    throw IOException("更新包 SHA-256 校验失败")
                }
            }
            activeCall = null
            if (cancelRequested) {
                target.delete()
                cancelRequested = false
                _state.update { it.copy(phase = UpdatePhase.Idle, phaseLabel = "", progress = 0f, message = "已取消下载", error = "", file = null) }
                return@withContext false
            }
            _state.update { it.copy(phase = UpdatePhase.Verifying, phaseLabel = "正在校验 SHA-256 与签名", progress = 1f) }
            verifyApk(target, info)
            _state.update {
                it.copy(
                    phase = UpdatePhase.Ready,
                    phaseLabel = "校验通过",
                    progress = 1f,
                    file = target,
                    error = "",
                    message = "更新包校验通过，等待确认安装",
                )
            }
            true
        } catch (error: Exception) {
            activeCall = null
            target.delete()
            if (cancelRequested) {
                _state.update { it.copy(phase = UpdatePhase.Idle, phaseLabel = "", progress = 0f, message = "已取消下载", error = "", file = null) }
            } else {
                _state.update {
                    it.copy(
                        phase = UpdatePhase.Error,
                        phaseLabel = "",
                        error = error.message ?: "更新包下载失败",
                        file = null,
                    )
                }
            }
            cancelRequested = false
            false
        }
    }

    fun cancelDownload() {
        cancelRequested = true
        activeCall?.cancel()
        activeCall = null
        _state.update {
            it.copy(
                phase = UpdatePhase.Idle,
                phaseLabel = "",
                progress = 0f,
                message = "已取消下载",
                error = "",
                file = null,
            )
        }
    }

    private fun verifyApk(file: File, info: UpdateInfo) {
        val current = currentPackageInfo() ?: throw IOException("无法读取当前版本")
        if (info.versionCode <= current.code) throw IOException("更新清单版本不高于当前版本，已拒绝安装")
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("无法读取更新包信息")
        if (archive.packageName != context.packageName) {
            throw IOException("更新包包名与当前应用不一致，已拒绝安装")
        }
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archiveCode != info.versionCode) {
            throw IOException("更新包版本号与清单不一致，已拒绝安装")
        }
        val archiveInfo = archive.applicationInfo ?: throw IOException("无法读取更新包签名")
        archiveInfo.sourceDir = file.absolutePath
        val archiveSignatures = signaturesOf(archive)
        val installed = installedSignatures()
        if (archiveSignatures.isEmpty() || installed.isEmpty() || archiveSignatures.none { it in installed }) {
            throw IOException("更新包签名与当前安装不一致，无法覆盖安装。请从当前渠道重新下载。")
        }
    }

    private fun signaturesOf(info: android.content.pm.PackageInfo): Set<String> = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        signatures.map { sha256(it.toByteArray()) }.toSet()
    }.getOrDefault(emptySet())

    private fun installedSignatures(): Set<String> = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        signaturesOf(context.packageManager.getPackageInfo(context.packageName, flags))
    }.getOrDefault(emptySet())

    /**
     * 唤起系统安装器。小米电视等部分 ROM 没有为 content:// 的 ACTION_VIEW 注册安装器，
     * startActivity 会直接抛 ActivityNotFoundException，因此按
     * ACTION_VIEW → ACTION_INSTALL_PACKAGE → PackageInstaller 会话依次尝试，
     * 全部不可用时把更新包导出到外部目录并提示手动安装。
     */
    suspend fun install(file: File): InstallResult {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(permissionRequired = true, error = "请先允许本应用安装未知来源的应用") }
            return InstallResult.NeedsPermission
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onFailure {
            Log.w(TAG, "无法生成更新包的 content URI", it)
        }.getOrNull()
        if (uri != null) {
            if (startInstallerActivity(Intent.ACTION_VIEW, uri)) return InstallResult.Launched
            if (startInstallerActivity(Intent.ACTION_INSTALL_PACKAGE, uri)) return InstallResult.Launched
        }
        if (withContext(Dispatchers.IO) { commitInstallSession(file) }) return InstallResult.Launched
        val savedPath = withContext(Dispatchers.IO) { exportApkForManualInstall(file) }
        val tip = if (savedPath != null) {
            "系统没有可用的安装器，更新包已保存到 $savedPath，请用文件管理器安装"
        } else {
            "系统没有可用的安装器，请通过应用市场或 U 盘安装更新包"
        }
        _state.update { state -> state.copy(error = tip) }
        return InstallResult.Failed
    }

    /** 用隐式 Action 唤起系统安装器；没有任何 Activity 能承接时返回 false。 */
    private fun startInstallerActivity(action: String, apkUri: Uri): Boolean = try {
        val intent = Intent(action)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setDataAndType(apkUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        Log.w(TAG, "$action 没有可响应的系统安装器", error)
        false
    } catch (error: Exception) {
        Log.w(TAG, "$action 唤起安装器失败", error)
        false
    }

    /**
     * 走系统 PackageInstaller 会话安装，不依赖安装器 Activity 的 IntentFilter：
     * 即使 ROM 没注册 ACTION_VIEW 安装入口，也能由系统安装服务接管。
     */
    private fun commitInstallSession(file: File): Boolean {
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("myMusic.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        // 写完后必须写透，否则系统读到的是不完整的包；minSdk 23 没有 Session.fsync（API 24）
                        output.flush()
                    }
                }
                registerInstallResultReceiver()
                session.commit(installResultSender(sessionId))
            }
            true
        } catch (error: Exception) {
            Log.w(TAG, "PackageInstaller 会话安装失败", error)
            if (sessionId >= 0) {
                runCatching { installer.abandonSession(sessionId) }
            }
            false
        }
    }

    private fun installResultSender(sessionId: Int): IntentSender {
        val intent = Intent(INSTALL_RESULT_ACTION).setPackage(context.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 31) {
            // 系统安装服务要把结果写回这个 Intent，必须可变
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    private fun registerInstallResultReceiver() {
        if (installResultReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // 部分 ROM 把安装确认界面交给调用方拉起；最终结果仍会回到这里，先不注销
                    confirmIntentOf(intent)?.let { confirm ->
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { receiverContext.startActivity(confirm) }
                            .onFailure { Log.w(TAG, "拉起安装确认界面失败", it) }
                    }
                    return
                }
                unregisterInstallResultReceiver()
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    _state.update { it.copy(error = "", message = "更新包已安装") }
                } else {
                    _state.update {
                        it.copy(error = "安装失败${if (detail.isNullOrBlank()) "" else "：$detail"}")
                    }
                }
            }
        }
        installResultReceiver = receiver
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(INSTALL_RESULT_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterInstallResultReceiver() {
        val receiver = installResultReceiver ?: return
        installResultReceiver = null
        runCatching { context.unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "注销安装结果监听失败", it) }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntentOf(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    /** 把更新包复制到外部目录供文件管理器安装，返回路径；失败返回 null。 */
    private fun exportApkForManualInstall(file: File): String? = try {
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        val target = File(directory, file.name)
        file.copyTo(target, overwrite = true)
        target.absolutePath
    } catch (error: Exception) {
        Log.w(TAG, "导出更新包失败", error)
        null
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            _state.update { state -> state.copy(error = "无法打开安装权限设置：${it.message}") }
        }
    }

    fun openReleasePage() {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun skipCurrent() {
        val info = _state.value.info ?: return
        _state.update { it.copy(skippedCode = info.versionCode) }
    }

    fun clearError() {
        _state.update { it.copy(error = "") }
    }

    suspend fun rememberSkipped(code: Long) {
        settings.skippedUpdateCode(code)
    }

    companion object {
        private const val TAG = "UpdateRepository"
        private const val INSTALL_RESULT_ACTION = "com.mytv.mymusic.action.INSTALL_RESULT"
        const val MANIFEST_URL = "https://github.com/mytv-android/myMusic/releases/latest/download/update.json"
        const val RELEASES_URL = "https://github.com/mytv-android/myMusic/releases"
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
