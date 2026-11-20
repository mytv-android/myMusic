package com.mytv.mymusic

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeFormatter

/** 一份留在本机的崩溃报告；[summary] 是可用于 GitHub 新 issue 的短环境摘要。 */
data class CrashReport(val text: String, val summary: String)

/**
 * Java / Kotlin 未捕获异常的本机报告。只安装线程默认 handler，不注册 native 信号处理，
 * 因而不声称能捕获 native 崩溃；也不会自动上传，只有用户主动复制或打开 GitHub 才会离开本机。
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val DIRECTORY = "crash-reports"
    private const val REPORT_FILE = "last-crash.txt"
    private const val NOTIFIED_FILE = "last-crash.notified"
    private const val TEMP_FILE = "last-crash.tmp"

    private val lock = Any()

    @Volatile
    private var installed = false

    /** Application.onCreate 一进来就调用；写文件失败也不能挡住原来的崩溃处理链。 */
    fun install(context: Context) {
        if (installed) return
        synchronized(lock) {
            if (installed) return
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                    val logs = runCatching { AppLog.snapshot().map { it.format() } }.getOrDefault(emptyList())
                    val text = CrashReportText.buildReport(
                        environment = CrashEnvironment.current(appContext),
                        crashedAt = System.currentTimeMillis(),
                        threadName = thread.name,
                        threadId = thread.id,
                        stackTrace = stack,
                        recentLogs = logs,
                    )
                    writeReport(appContext, text)
                } catch (writeError: Throwable) {
                    runCatching { Log.e(TAG, "无法写入本机崩溃报告", writeError) }
                } finally {
                    // Android 默认会在这里结束进程并记录崩溃；无论落盘成功与否都必须交还原 handler。
                    previous?.uncaughtException(thread, error)
                }
            }
            installed = true
        }
    }

    /** 下次启动只取一次；报告本身保留，仍可从「设置 › 关于 › 问题反馈」打开。 */
    fun consumePending(context: Context): CrashReport? = synchronized(lock) {
        val notified = File(directory(context), NOTIFIED_FILE)
        if (notified.exists()) return@synchronized null
        val report = readLocked(context) ?: return@synchronized null
        runCatching { notified.writeText("notified", Charsets.UTF_8) }
        report
    }

    fun read(context: Context): CrashReport? = synchronized(lock) { readLocked(context) }

    fun delete(context: Context): Boolean = synchronized(lock) {
        val directory = directory(context)
        File(directory, REPORT_FILE).delete()
        File(directory, TEMP_FILE).delete()
        File(directory, NOTIFIED_FILE).delete()
        !File(directory, REPORT_FILE).exists()
    }

    fun issueUrl(context: Context, summary: String = currentSummary(context)): String = CrashReportText.issueUrl(summary)

    fun currentSummary(context: Context): String = CrashEnvironment.current(context).summary()

    private fun readLocked(context: Context): CrashReport? {
        val file = File(directory(context), REPORT_FILE)
        if (!file.isFile) return null
        return runCatching {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return@runCatching null
            CrashReport(text, CrashReportText.summaryFromReport(text) ?: currentSummary(context))
        }.getOrNull()
    }

    private fun writeReport(context: Context, text: String) {
        synchronized(lock) {
            val directory = directory(context)
            if (!directory.exists()) directory.mkdirs()
            val target = File(directory, REPORT_FILE)
            val temp = File(directory, TEMP_FILE)
            temp.writeText(text, Charsets.UTF_8)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            // 新报告重新获得一次启动提示；标记只在报告成功落地后删除。
            File(directory, NOTIFIED_FILE).delete()
        }
    }

    private fun directory(context: Context): File = File(context.filesDir, DIRECTORY)
}

/** 生成报告时固定下来的设备与版本；不包含序列号、账号、Android ID 等标识。 */
internal data class CrashEnvironment(
    val versionName: String,
    val versionCode: Long,
    val androidRelease: String,
    val api: Int,
    val manufacturer: String,
    val model: String,
    val device: String,
    val abi: String,
) {
    fun summary(): String = CrashReportText.oneLine(
        CrashReportText.sanitize(
            "myMusic $versionName ($versionCode) · Android $androidRelease (API $api) · " +
                "$manufacturer $model ($device) · ABI $abi",
        ),
    )

    companion object {
        fun current(context: Context): CrashEnvironment {
            val info = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            return CrashEnvironment(
                versionName = info?.versionName ?: "unknown",
                versionCode = when {
                    info == null -> -1
                    Build.VERSION.SDK_INT >= 28 -> info.longVersionCode
                    else -> @Suppress("DEPRECATION") info.versionCode.toLong()
                },
                androidRelease = Build.VERSION.RELEASE,
                api = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                device = Build.DEVICE,
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            )
        }
    }
}

/** 不依赖 Android 运行时、可由 JVM 单测直接覆盖的格式化和脱敏逻辑。 */
internal object CrashReportText {
    private const val ENVIRONMENT_PREFIX = "Environment: "
    private const val ISSUE_URL = "https://github.com/mytv-android/myMusic/issues/new"
    private const val MAX_ISSUE_SUMMARY = 120

    private val REDACT_HEADER = Regex(
        """(?i)(\b(?:cookie|set-cookie|authorization|proxy-authorization)\b\s*[=:]\s*)(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\r\n]+)""",
    )
    private val REDACT_KEY_VALUE = Regex(
        """(?i)(["']?)\b(cookie|set-cookie|authorization|proxy-authorization|media-user-token|access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|client[_-]?secret|token|password|passwd|secret|sessionid|session_id|mstoken|a_bogus|sig|signature)\1(\s*[=:]\s*)("[^"\r\n]*"|'[^'\r\n]*'|[^;\s,}&]+)""",
    )
    private val REDACT_URL_USER_INFO = Regex("""(?i)\b(https?://)[^/\s@]+@""")

    fun sanitize(text: String): String = AppLog.redact(text)
        .replace(REDACT_URL_USER_INFO, "$1<redacted>@")
        .replace(REDACT_HEADER, "$1<redacted>")
        .replace(REDACT_KEY_VALUE) { match ->
            val raw = match.groupValues[4]
            val value = when {
                raw.startsWith('"') -> "\"<redacted>\""
                raw.startsWith('\'') -> "'<redacted>'"
                else -> "<redacted>"
            }
            val quote = match.groupValues[1]
            "$quote${match.groupValues[2]}$quote${match.groupValues[3]}$value"
        }

    fun oneLine(text: String): String = text.replace(Regex("[\\s\\p{Cc}]+"), " ").trim()

    fun buildReport(
        environment: CrashEnvironment,
        crashedAt: Long,
        threadName: String,
        threadId: Long,
        stackTrace: String,
        recentLogs: List<String>,
    ): String = buildString {
        appendLine("myMusic crash report")
        appendLine("Captured: ${DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(crashedAt))}")
        appendLine(ENVIRONMENT_PREFIX + environment.summary())
        appendLine("Thread: ${oneLine(sanitize(threadName))} (id=$threadId)")
        appendLine("Exception:")
        val stack = sanitize(stackTrace)
        append(stack)
        if (!stack.endsWith('\n')) append('\n')
        appendLine("Recent AppLog (${recentLogs.size} entries):")
        if (recentLogs.isEmpty()) {
            appendLine("(none)")
        } else {
            recentLogs.forEach { appendLine(sanitize(it)) }
        }
        appendLine("Scope: Java/Kotlin uncaught exceptions only; native crashes do not invoke this handler.")
    }

    fun summaryFromReport(text: String): String? = text.lineSequence()
        .firstOrNull { it.startsWith(ENVIRONMENT_PREFIX) }
        ?.removePrefix(ENVIRONMENT_PREFIX)
        ?.takeIf { it.isNotBlank() }

    fun issueUrl(summary: String): String {
        val shortSummary = oneLine(sanitize(summary)).take(MAX_ISSUE_SUMMARY).ifBlank { "myMusic unknown" }
        val body = buildString {
            appendLine("请说明操作步骤、实际结果和能否复现。")
            appendLine()
            appendLine("环境：$shortSummary")
            appendLine()
            append("如有崩溃报告，请在应用中复制完整报告并粘贴到这里。链接不含堆栈，也不会自动上传；粘贴前请检查个人信息。")
        }
        return ISSUE_URL + "?title=" + encode("myMusic 问题反馈") + "&body=" + encode(body)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
