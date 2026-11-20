package com.mytv.mymusic.data.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** 每个文件打开前都按最新设置和系统授权重查，而不是沿用扫描开始时的快照。 */
internal object LocalSourceAccess {
    fun isReadable(
        origin: String,
        mediaStoreEnabled: Boolean,
        audioPermissionGranted: Boolean,
        configuredFolders: Set<String>,
        folderGranted: (String) -> Boolean,
    ): Boolean = if (origin == LOCAL_ORIGIN_MEDIA) {
        mediaStoreEnabled && audioPermissionGranted
    } else {
        origin in configuredFolders && folderGranted(origin)
    }
}

/** 来源开关、权限或文件夹失效时，扫描与匹配都不能继续持有旧授权跑下去。 */
internal object LocalSourceWork {
    fun cancel(scan: Job?, matching: Job?) {
        scan?.cancel()
        matching?.cancel()
    }
}

/** 自动匹配必须等索引明确加载，并且能在这台设备、当前启用的来源里找到文件。 */
internal object LocalMatchEligibility {
    fun isEligible(
        row: LocalTrackEntity,
        indexLoaded: Boolean,
        onDevice: Boolean?,
        attempted: Set<String>,
        audioBlocked: Boolean,
    ): Boolean = indexLoaded &&
        onDevice == true &&
        !row.missing &&
        row.tagsRead &&
        !row.matchSettled &&
        row.localId !in attempted &&
        !audioBlocked
}

/** 匹配工作的统一异常边界：普通异常只结束当前工作，协程取消必须继续传播。 */
internal object LocalMatchExceptionBoundary {
    suspend fun run(onError: (Exception) -> Unit, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            onError(error)
        }
    }
}
