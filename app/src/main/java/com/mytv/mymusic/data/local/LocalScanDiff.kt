package com.mytv.mymusic.data.local

/** 扫描阶段从媒体库 / 文件夹列出来的一个文件，还没算身份。 */
data class ScannedFile(
    val uri: String,
    val origin: String,
    val displayName: String,
    val relativePath: String,
    val size: Long,
    val modifiedAt: Long,
    val mimeType: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val dataPath: String = "",
    val lrcUri: String = "",
    val addedAt: Long = 0L,
)

/**
 * 增量扫描的对比：大小和修改时间都没变的文件直接沿用旧记录，不再打开算指纹；
 * 其余的交给调用方去算 localId。
 */
data class ScanPlan(
    /** 文件没变：沿用的记录（已经把“文件不在了”、.lrc 位置、来源刷新过）。 */
    val unchanged: List<LocalTrackEntity>,
    /** 新文件或改过的文件，要重新算身份。 */
    val toHash: List<ScannedFile>,
)

object LocalScanDiff {
    fun plan(existing: List<LocalTrackEntity>, scanned: List<ScannedFile>, now: Long): ScanPlan {
        // 同一个 uri 可能有一条旧的“文件不在了”记录和一条现行记录，优先拿现行的那条比
        val byUri = HashMap<String, LocalTrackEntity>(existing.size)
        for (row in existing) {
            val known = byUri[row.uri]
            if (known == null || (known.missing && !row.missing)) byUri[row.uri] = row
        }
        val unchanged = ArrayList<LocalTrackEntity>()
        val toHash = ArrayList<ScannedFile>()
        for (file in scanned) {
            val row = byUri[file.uri]
            if (row != null && row.size == file.size && row.modifiedAt == file.modifiedAt) {
                val refreshed = row.copy(
                    missing = false,
                    origin = file.origin,
                    lrcUri = file.lrcUri,
                    relativePath = file.relativePath.ifBlank { row.relativePath },
                    displayName = file.displayName.ifBlank { row.displayName },
                    dataPath = file.dataPath.ifBlank { row.dataPath },
                )
                unchanged += if (refreshed != row) refreshed.copy(updatedAt = now) else row
            } else {
                toHash += file
            }
        }
        return ScanPlan(unchanged, toHash)
    }

    /**
     * 算好身份后写回：已有这条记录（文件搬了家、或者原样又出现了）就只换位置和文件信息，
     * 匹配结果、内嵌封面都留着；新身份就建一条，标签待读。
     */
    fun merge(previous: LocalTrackEntity?, file: ScannedFile, localId: String, now: Long): LocalTrackEntity {
        val durationSec = (file.durationMs / 1000L).toInt().coerceAtLeast(0)
        if (previous != null) {
            return previous.copy(
                uri = file.uri,
                origin = file.origin,
                displayName = file.displayName,
                relativePath = file.relativePath,
                dataPath = file.dataPath,
                size = file.size,
                modifiedAt = file.modifiedAt,
                mimeType = file.mimeType.ifBlank { previous.mimeType },
                durationSec = if (previous.durationSec > 0) previous.durationSec else durationSec,
                lrcUri = file.lrcUri,
                missing = false,
                updatedAt = now,
            )
        }
        return LocalTrackEntity(
            localId = localId,
            uri = file.uri,
            origin = file.origin,
            displayName = file.displayName,
            relativePath = file.relativePath,
            dataPath = file.dataPath,
            title = file.title,
            artist = file.artist,
            album = file.album,
            durationSec = durationSec,
            size = file.size,
            mimeType = file.mimeType,
            modifiedAt = file.modifiedAt,
            addedAt = if (file.addedAt > 0) file.addedAt else now,
            bitrate = 0,
            sampleRate = 0,
            bitsPerSample = 0,
            tagsRead = false,
            coverPath = "",
            lrcUri = file.lrcUri,
            missing = false,
            matchProvider = "",
            matchId = "",
            matchConfidence = 0f,
            matchMethod = "",
            matchedAt = 0L,
            matchPayload = "",
            remoteProvider = "",
            remoteId = "",
            remotePlayableId = "",
            remoteStatus = "",
            remoteUpdatedAt = 0L,
            updatedAt = now,
        )
    }

    /**
     * 这次扫描完整走过的来源里，没再见到的身份标成“文件不在了”。
     * 只看 [scannedOrigins]：某个文件夹这次没读成（权限被收回、U 盘拔了），它的歌保持原状，不误标。
     */
    fun vanished(
        existing: List<LocalTrackEntity>,
        seenIds: Set<String>,
        scannedOrigins: Set<String>,
    ): List<String> = existing
        .filter { !it.missing && it.origin in scannedOrigins && it.localId !in seenIds }
        .map { it.localId }
}
