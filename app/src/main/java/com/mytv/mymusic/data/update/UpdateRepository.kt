package com.mytv.mymusic.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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

    fun install(file: File): InstallResult {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(permissionRequired = true, error = "请先允许本应用安装未知来源的应用") }
            return InstallResult.NeedsPermission
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            InstallResult.Launched
        }.getOrElse {
            _state.update { state -> state.copy(error = "无法打开系统安装器：${it.message}") }
            InstallResult.Failed
        }
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
