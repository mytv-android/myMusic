package com.mytv.mymusic.data.local

import android.content.Context
import java.util.UUID

/** 会打开本机音频的高风险阶段。 */
internal enum class LocalProcessingStage(val keyPrefix: String) {
    Tags("tags:"),
    Audio("audio:"),
}

internal enum class LocalProcessingIssueKind {
    /** native 调用抛出了普通异常，已经正常收尾。 */
    OrdinaryFailure,
    /** 上次进程留下 running 标记；只能视为崩溃隔离，不能断言具体信号或崩溃点。 */
    CrashQuarantine,
}

/** 同步、持久的最小键值接口；纯 JVM 测试用 Map，Android 实现用 SharedPreferences.commit。 */
internal interface LocalCheckpointStorage {
    fun all(): Map<String, String>
    fun get(key: String): String?
    fun put(key: String, value: String): Boolean
    fun remove(key: String): Boolean
}

private class AndroidLocalCheckpointStorage(context: Context) : LocalCheckpointStorage {
    private val preferences = context.getSharedPreferences("local_processing_checkpoints", Context.MODE_PRIVATE)

    override fun all(): Map<String, String> = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }.toMap()

    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(key: String, value: String): Boolean =
        preferences.edit().putString(key, value).commit()

    override fun remove(key: String): Boolean = preferences.edit().remove(key).commit()
}

internal data class LocalProcessingAttempt internal constructor(
    val localId: String,
    val stage: LocalProcessingStage,
    internal val key: String,
    internal val runningValue: String,
)

internal data class LocalProcessingIssue internal constructor(
    val localId: String,
    val stage: LocalProcessingStage,
    val kind: LocalProcessingIssueKind,
    internal val key: String,
    internal val persistedValue: String,
)

/**
 * native 调用前的持久检查点。它不能防止第一次 native 崩溃，只保证崩溃留下的 running 在下个进程
 * 转为隔离状态，不再被启动扫描 / 自动匹配反复命中。普通失败和取消都有正常收尾，不冒充崩溃。
 *
 * 所有写操作都是同步提交；[begin] 拿不到持久标记时返回 null，调用方必须跳过文件。
 */
internal class LocalProcessingCheckpoints(
    private val storage: LocalCheckpointStorage,
) {
    constructor(context: Context) : this(AndroidLocalCheckpointStorage(context))

    init {
        recoverInterruptedAttempts()
    }

    @Synchronized
    private fun recoverInterruptedAttempts() {
        storage.all().forEach { (key, value) ->
            if (value.startsWith(RUNNING_PREFIX)) storage.put(key, CRASHED)
        }
    }

    /** null 表示正在别处处理、已失败 / 隔离，或者标记没能同步落盘；三种情况都不能进入 native。 */
    @Synchronized
    fun begin(localId: String, stage: LocalProcessingStage): LocalProcessingAttempt? {
        val key = stage.keyPrefix + localId
        if (storage.get(key) != null) return null
        val value = RUNNING_PREFIX + UUID.randomUUID().toString()
        if (!storage.put(key, value)) return null
        return LocalProcessingAttempt(localId, stage, key, value)
    }

    /** 正常返回（包括“没有匹配到”）：native 危险区已经离开，不需要保留标记。 */
    @Synchronized
    fun complete(attempt: LocalProcessingAttempt) {
        if (storage.get(attempt.key) == attempt.runningValue) storage.remove(attempt.key)
    }

    /** 普通异常：与 running 遗留成的崩溃隔离明确区分，自动流程同样不再反复试。 */
    @Synchronized
    fun fail(attempt: LocalProcessingAttempt) {
        if (storage.get(attempt.key) == attempt.runningValue) storage.put(attempt.key, FAILED)
    }

    /** 协程取消不是文件故障：清掉本次标记，之后重新启用来源时可以再试。 */
    @Synchronized
    fun cancel(attempt: LocalProcessingAttempt) {
        if (storage.get(attempt.key) == attempt.runningValue) storage.remove(attempt.key)
    }

    @Synchronized
    fun isBlocked(localId: String, stage: LocalProcessingStage): Boolean =
        storage.get(stage.keyPrefix + localId) != null

    @Synchronized
    fun issue(localId: String, stage: LocalProcessingStage): LocalProcessingIssue? =
        issueFrom(stage.keyPrefix + localId, storage.get(stage.keyPrefix + localId))

    /** 用户显式重试的候选；进行中的 running 不在里面，避免清掉另一项正在用的标记。 */
    @Synchronized
    fun issues(): List<LocalProcessingIssue> = storage.all().mapNotNull { (key, value) -> issueFrom(key, value) }

    /** 只清读取后仍未变化的失败 / 隔离状态；由调用方先恢复对应数据库字段。 */
    @Synchronized
    fun clear(issue: LocalProcessingIssue) {
        if (storage.get(issue.key) == issue.persistedValue) storage.remove(issue.key)
    }

    private fun issueFrom(key: String, value: String?): LocalProcessingIssue? {
        if (value == null || value.startsWith(RUNNING_PREFIX)) return null
        val stage = LocalProcessingStage.entries.firstOrNull { key.startsWith(it.keyPrefix) } ?: return null
        val kind = when (value) {
            FAILED -> LocalProcessingIssueKind.OrdinaryFailure
            // 无法识别的遗留值也按隔离处理，不能 fail-open。
            else -> LocalProcessingIssueKind.CrashQuarantine
        }
        return LocalProcessingIssue(key.removePrefix(stage.keyPrefix), stage, kind, key, value)
    }

    private companion object {
        const val RUNNING_PREFIX = "v1:running:"
        const val FAILED = "v1:failed"
        const val CRASHED = "v1:crashed"
    }
}
