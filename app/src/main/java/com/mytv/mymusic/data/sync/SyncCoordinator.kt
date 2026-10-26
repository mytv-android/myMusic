package com.mytv.mymusic.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mytv.mymusic.data.AppDatabase
import com.mytv.mymusic.data.LibraryDao
import com.mytv.mymusic.data.PlaylistEntity
import com.mytv.mymusic.data.PlaylistEntryEntity
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.SavedSongTombstone
import com.mytv.mymusic.data.SettingsStore
import com.mytv.mymusic.data.SyncAccountEntity
import com.mytv.mymusic.data.SyncBaseEntity
import com.mytv.mymusic.data.SyncConflictEntity
import com.mytv.mymusic.data.SyncDao
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class SyncStatus { NoAccount, Idle, Syncing, NeedsAttention, Error }

data class SyncConflictUi(
    val playlistId: String,
    val name: String,
    val localTracks: Int,
    val remoteTracks: Int,
    val localUpdatedAt: Long,
    val remoteUpdatedAt: Long,
    val localDeleted: Boolean,
    val remoteDeleted: Boolean,
)

data class InitialMergePreview(
    val provider: String,
    val providerLabel: String,
    val localPlaylists: Int,
    val localTracks: Int,
    val remotePlaylists: Int,
    val remoteTracks: Int,
    val localFavorites: Int = 0,
    val remoteFavorites: Int = 0,
    val localCookies: Int = 0,
    val remoteCookies: Int = 0,
)

data class SyncUiState(
    val account: SyncAccountEntity? = null,
    val providerLabel: String = "",
    val status: SyncStatus = SyncStatus.NoAccount,
    val phase: String = "",
    val message: String = "",
    val error: String = "",
    val conflicts: List<SyncConflictUi> = emptyList(),
    val playlistCount: Int = 0,
    val trackCount: Int = 0,
    val favoritesCount: Int = 0,
    val cookieCount: Int = 0,
    val initialMerge: InitialMergePreview? = null,
)

data class SyncEvent(val message: String, val error: Boolean = false)

private data class PendingInitial(
    val providerId: String,
    val accountLabel: String,
    val remoteId: String,
    val remoteFile: RemoteFile,
    val token: String,
    val local: SyncSnapshot,
    val remote: SyncSnapshot,
)

class SyncCoordinator(
    private val context: Context,
    private val db: AppDatabase,
    private val libraryDao: LibraryDao,
    private val syncDao: SyncDao,
    private val secretStore: SecretStore,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val providers: Map<String, PlaylistSyncProvider> = mapOf(
        "gist" to GistSyncProvider(),
        "gitee" to GiteeSyncProvider(),
    )
    fun availableProviders(): List<Pair<String, String>> =
        providers.values.map { it.id to it.label }

    private val mutex = Mutex()
    private var pendingInitial: PendingInitial? = null
    private var localChangeJob: Job? = null
    private val musicDao = db.dao()

    private val deviceId: String by lazy {
        val preferences = context.getSharedPreferences("mymusic_device", Context.MODE_PRIVATE)
        preferences.getString("device_id", null)?.takeIf { it.isNotBlank() } ?: run {
            val created = UUID.randomUUID().toString().replace("-", "").take(8)
            preferences.edit().putString("device_id", created).apply()
            created
        }
    }

    init {
        scope.launch {
            combine(
                syncDao.accounts(),
                syncDao.conflicts(),
                libraryDao.playlists(),
                libraryDao.entryCounts(),
                combine(musicDao.songs("favorite"), settings.state) { favorites, current ->
                    favorites.size to current.cookies.count { cookie -> cookie.value.isNotBlank() }
                },
            ) { accounts, conflicts, playlists, counts, extras ->
                Derived(
                    account = accounts.firstOrNull(),
                    conflicts = conflicts,
                    playlistCount = playlists.size,
                    trackCount = counts.sumOf { it.total },
                    favoritesCount = extras.first,
                    cookieCount = extras.second,
                )
            }.collect { derived ->
                _state.update { current ->
                    val account = derived.account
                    val status = when {
                        account == null -> SyncStatus.NoAccount
                        current.status == SyncStatus.Syncing -> SyncStatus.Syncing
                        current.status == SyncStatus.Error -> SyncStatus.Error
                        derived.conflicts.isNotEmpty() -> SyncStatus.NeedsAttention
                        else -> SyncStatus.Idle
                    }
                    current.copy(
                        account = account,
                        providerLabel = account?.let { providers[it.provider]?.label ?: it.provider }.orEmpty(),
                        status = status,
                        conflicts = derived.conflicts.map(::toConflictUi),
                        playlistCount = derived.playlistCount,
                        trackCount = derived.trackCount,
                        favoritesCount = derived.favoritesCount,
                        cookieCount = derived.cookieCount,
                    )
                }
            }
        }
    }

    private data class Derived(
        val account: SyncAccountEntity?,
        val conflicts: List<SyncConflictEntity>,
        val playlistCount: Int,
        val trackCount: Int,
        val favoritesCount: Int,
        val cookieCount: Int,
    )

    fun requestSync(manual: Boolean) {
        scope.launch { runSync(manual, notify = true) }
    }

    suspend fun runSync(manual: Boolean, notify: Boolean = false): Boolean = mutex.withLock {
        val account = syncDao.accountOnce()
        if (account == null) {
            _state.update { it.copy(status = SyncStatus.NoAccount, phase = "", message = "", error = "") }
            if (manual) emit("还没有连接同步账户。", error = true)
            return@withLock false
        }
        if (!manual && !account.autoSync) return@withLock false
        if (pendingInitial != null) {
            if (manual) emit("请先完成首次合并方式的选择。", error = true)
            return@withLock false
        }
        val provider = providers[account.provider]
        if (provider == null) {
            fail("同步后端不可用：${account.provider}")
            return@withLock false
        }
        val token = secretStore.token(account.provider)
        if (token.isNullOrBlank()) {
            fail("同步令牌已失效，请在设置中重新连接。")
            return@withLock false
        }
        if (account.remoteId.isBlank()) {
            fail("同步账户缺少远端文件，请在设置中重新连接。")
            return@withLock false
        }
        if (syncDao.conflictsOnce().isNotEmpty()) {
            _state.update {
                it.copy(
                    status = SyncStatus.NeedsAttention,
                    phase = "",
                    message = "还有 ${syncDao.conflictsOnce().size} 个歌单需要处理冲突，处理前不会上传。",
                    error = "",
                )
            }
            if (manual) emit("请先处理同步冲突。", error = true)
            return@withLock false
        }

        _state.update {
            it.copy(
                status = SyncStatus.Syncing,
                phase = "正在读取云端文件",
                message = "",
                error = "",
            )
        }
        return@withLock try {
            val remoteFile = provider.read(token, account.remoteId)
            val remote = SyncJson.decode(remoteFile.content)
            val local = localSnapshot()
            val base = syncDao.base(account.provider)?.snapshot?.let { raw ->
                runCatching { SyncJson.format.decodeFromString<SyncSnapshot>(raw) }.getOrNull()
            }
            if (base == null) {
                val remoteEmpty = !remote.hasUserData()
                val localEmpty = !local.hasUserData()
                when {
                    remoteEmpty && localEmpty -> {
                        saveAccount(account.copy(
                            accountLabel = account.accountLabel,
                            lastSyncedAt = System.currentTimeMillis(),
                            remoteRevision = remoteFile.revision,
                            remoteUrl = remoteFile.htmlUrl,
                        ))
                        saveBase(local, remoteFile.revision)
                        finish("同步已就绪")
                        true
                    }
                    remoteEmpty -> {
                        val uploaded = upload(provider, token, account, local, remoteFile)
                        saveBase(local, uploaded.revision)
                        saveAccount(account.copy(
                            lastSyncedAt = System.currentTimeMillis(),
                            remoteRevision = uploaded.revision,
                            remoteUrl = uploaded.htmlUrl,
                        ))
                        val aligned = reconcileAfterPush(provider, token, account, uploaded, local)
                        if (aligned) finish("已上传 ${local.summary()}")
                        aligned
                    }
                    localEmpty -> {
                        applySnapshot(remote, removeAbsent = true)
                        saveBase(remote, remoteFile.revision)
                        saveAccount(account.copy(
                            lastSyncedAt = System.currentTimeMillis(),
                            remoteRevision = remoteFile.revision,
                            remoteUrl = remoteFile.htmlUrl,
                        ))
                        finish("已从云端恢复 ${remote.summary()}")
                        true
                    }
                    else -> {
                        pendingInitial = PendingInitial(
                            providerId = account.provider,
                            accountLabel = account.accountLabel,
                            remoteId = account.remoteId,
                            remoteFile = remoteFile,
                            token = token,
                            local = local,
                            remote = remote,
                        )
                        _state.update {
                            it.copy(
                                status = SyncStatus.NeedsAttention,
                                phase = "",
                                initialMerge = initialPreview(
                                    provider = account.provider,
                                    providerLabel = provider.label,
                                    local = local,
                                    remote = remote,
                                ),
                                message = "本机和云端都有数据，请选择首次合并方式。",
                                error = "",
                            )
                        }
                        false
                    }
                }
            } else {
                _state.update { it.copy(phase = "正在合并歌单") }
                val result = SyncMerge.merge(base, local, remote)
                if (result.conflicts.isNotEmpty()) {
                    recordConflicts(result.conflicts)
                    if (result.playlists.isNotEmpty() || result.favorites.isNotEmpty() || result.cookies.isNotEmpty()) {
                        applySnapshot(
                            SyncSnapshot(
                                deviceId = deviceId,
                                playlists = result.playlists,
                                favorites = result.favorites,
                                cookies = result.cookies,
                            ),
                            removeAbsent = false,
                            dirty = true,
                        )
                    }
                    saveAccount(account.copy(remoteRevision = remoteFile.revision, remoteUrl = remoteFile.htmlUrl))
                    _state.update {
                        it.copy(
                            status = SyncStatus.NeedsAttention,
                            phase = "",
                            message = "有 ${result.conflicts.size} 个歌单需要处理冲突，处理前不会上传。",
                            error = "",
                        )
                    }
                    if (notify) emit("发现 ${result.conflicts.size} 个同步冲突，请先处理。", error = true)
                    false
                } else if (snapshotSignature(local) == snapshotSignature(remote)) {
                    saveBase(remote, remoteFile.revision)
                    saveAccount(account.copy(
                        lastSyncedAt = System.currentTimeMillis(),
                        remoteRevision = remoteFile.revision,
                        remoteUrl = remoteFile.htmlUrl,
                    ))
                    pruneTombstones()
                    finish("已是最新")
                    true
                } else {
                    if (snapshotSignature(localSnapshot()) != snapshotSignature(local)) {
                        fail("同步过程中本机数据有新的修改，请再次同步。")
                        return@withLock false
                    }
                    var currentRemoteFile = remoteFile
                    var merged = SyncSnapshot(
                        deviceId = deviceId,
                        updatedAt = SyncTime.nowIso(),
                        playlists = result.playlists,
                        favorites = result.favorites,
                        cookies = result.cookies,
                    )
                    var uploaded: RemoteFile? = null
                    var round = 0
                    while (uploaded == null) {
                        if (round++ >= MAX_PUSH_ROUNDS) {
                            fail("云端文件在同步期间持续变化，请稍后再试。")
                            return@withLock false
                        }
                        val latest = provider.read(token, account.remoteId)
                        if (!remoteMatches(latest, currentRemoteFile)) {
                            // 云端在合并期间被其他设备更新：按最新内容重新合并，避免覆盖对方
                            val latestSnapshot = SyncJson.decode(latest.content)
                            val latestLocal = localSnapshot()
                            val again = SyncMerge.merge(base, latestLocal, latestSnapshot)
                            if (again.conflicts.isNotEmpty()) {
                                recordConflicts(again.conflicts)
                                if (again.playlists.isNotEmpty() || again.favorites.isNotEmpty() || again.cookies.isNotEmpty()) {
                                    applySnapshot(
                                        SyncSnapshot(
                                            deviceId = deviceId,
                                            playlists = again.playlists,
                                            favorites = again.favorites,
                                            cookies = again.cookies,
                                        ),
                                        removeAbsent = false,
                                        dirty = true,
                                    )
                                }
                                saveAccount(account.copy(remoteRevision = latest.revision, remoteUrl = latest.htmlUrl))
                                _state.update {
                                    it.copy(
                                        status = SyncStatus.NeedsAttention,
                                        phase = "",
                                        message = "有 ${again.conflicts.size} 个歌单需要处理冲突，处理前不会上传。",
                                        error = "",
                                    )
                                }
                                if (notify) emit("发现 ${again.conflicts.size} 个同步冲突，请先处理。", error = true)
                                return@withLock false
                            }
                            if (snapshotSignature(latestLocal) == snapshotSignature(latestSnapshot)) {
                                saveBase(latestSnapshot, latest.revision)
                                saveAccount(account.copy(
                                    lastSyncedAt = System.currentTimeMillis(),
                                    remoteRevision = latest.revision,
                                    remoteUrl = latest.htmlUrl,
                                ))
                                pruneTombstones()
                                finish("已是最新")
                                return@withLock true
                            }
                            merged = SyncSnapshot(
                                deviceId = deviceId,
                                updatedAt = SyncTime.nowIso(),
                                playlists = again.playlists,
                                favorites = again.favorites,
                                cookies = again.cookies,
                            )
                            currentRemoteFile = latest
                            continue
                        }
                        applySnapshot(merged, removeAbsent = false, dirty = true)
                        _state.update { it.copy(phase = "正在上传数据") }
                        uploaded = upload(provider, token, account, merged, latest)
                    }
                    val finalSnapshot = merged
                    val finalUpload = uploaded ?: return@withLock false
                    saveBase(finalSnapshot, finalUpload.revision)
                    saveAccount(account.copy(
                        lastSyncedAt = System.currentTimeMillis(),
                        remoteRevision = finalUpload.revision,
                        remoteUrl = finalUpload.htmlUrl,
                    ))
                    val aligned = reconcileAfterPush(provider, token, account, finalUpload, finalSnapshot)
                    pruneTombstones()
                    if (aligned) finish("已同步 ${finalSnapshot.summary()}")
                    aligned
                }
            }
        } catch (error: SyncException) {
            fail(error.message)
            if (notify) emit(error.message, error = true)
            false
        } catch (error: Exception) {
            val message = error.message ?: "同步失败"
            fail(message)
            if (notify) emit(message, error = true)
            false
        }
    }

    suspend fun connect(providerId: String, token: String, remoteInput: String): Boolean {
        val provider = providers[providerId] ?: run {
            emit("不支持的同步后端", error = true)
            return false
        }
        _state.update {
            it.copy(
                status = SyncStatus.Syncing,
                phase = "正在验证令牌",
                message = "",
                error = "",
            )
        }
        return try {
            val accountInfo = provider.validate(token)
            _state.update { it.copy(phase = "正在读取云端文件") }
            val local = localSnapshot()
            val remoteId = extractRemoteId(remoteInput)
            if (remoteId.isBlank()) {
                _state.update { it.copy(phase = "正在创建私人 ${provider.label}") }
                val content = encodeForUpload(local)
                val remoteFile = provider.create(token, content)
                secretStore.saveToken(providerId, token)
                syncDao.clearAccounts()
                syncDao.clearBases()
                syncDao.clearConflicts()
                saveAccount(
                    SyncAccountEntity(
                        provider = providerId,
                        remoteId = remoteFile.id,
                        fileName = provider.fileName,
                        lastSyncedAt = System.currentTimeMillis(),
                        remoteRevision = remoteFile.revision,
                        autoSync = true,
                        wifiOnly = false,
                        remoteUrl = remoteFile.htmlUrl,
                        accountLabel = accountInfo.login,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                saveBase(local, remoteFile.revision)
                markClean(local)
                finish("已连接 ${provider.label}")
                emit("已连接 ${provider.label}")
                scheduleAutoSync()
                true
            } else {
                val remoteFile = provider.read(token, remoteId)
                val remote = SyncJson.decode(remoteFile.content)
                val remoteEmpty = !remote.hasUserData()
                val localEmpty = !local.hasUserData()
                when {
                    remoteEmpty && localEmpty -> {
                        secretStore.saveToken(providerId, token)
                        syncDao.clearAccounts()
                        syncDao.clearBases()
                        syncDao.clearConflicts()
                        saveAccount(
                            SyncAccountEntity(
                                provider = providerId,
                                remoteId = remoteFile.id,
                                fileName = provider.fileName,
                                lastSyncedAt = System.currentTimeMillis(),
                                remoteRevision = remoteFile.revision,
                                remoteUrl = remoteFile.htmlUrl,
                                accountLabel = accountInfo.login,
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        saveBase(remote, remoteFile.revision)
                        finish("已连接 ${provider.label}")
                        emit("已连接 ${provider.label}")
                        true
                    }
                    remoteEmpty -> {
                        secretStore.saveToken(providerId, token)
                        syncDao.clearAccounts()
                        syncDao.clearBases()
                        syncDao.clearConflicts()
                        val uploaded = provider.update(token, remoteFile.id, encodeForUpload(local))
                        val newAccount = SyncAccountEntity(
                            provider = providerId,
                            remoteId = remoteFile.id,
                            fileName = provider.fileName,
                            lastSyncedAt = System.currentTimeMillis(),
                            remoteRevision = uploaded.revision,
                            remoteUrl = uploaded.htmlUrl,
                            accountLabel = accountInfo.login,
                            updatedAt = System.currentTimeMillis(),
                        )
                        saveAccount(newAccount)
                        saveBase(local, uploaded.revision)
                        markClean(local)
                        val aligned = reconcileAfterPush(provider, token, newAccount, uploaded, local)
                        if (aligned) {
                            finish("已上传本机数据")
                            emit("已连接 ${provider.label}，本机数据已上传")
                        }
                        true
                    }
                    localEmpty -> {
                        secretStore.saveToken(providerId, token)
                        syncDao.clearAccounts()
                        syncDao.clearBases()
                        syncDao.clearConflicts()
                        applySnapshot(remote, removeAbsent = true)
                        saveAccount(
                            SyncAccountEntity(
                                provider = providerId,
                                remoteId = remoteFile.id,
                                fileName = provider.fileName,
                                lastSyncedAt = System.currentTimeMillis(),
                                remoteRevision = remoteFile.revision,
                                remoteUrl = remoteFile.htmlUrl,
                                accountLabel = accountInfo.login,
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        saveBase(remote, remoteFile.revision)
                        finish("已从云端恢复数据")
                        emit("已连接 ${provider.label}，已从云端恢复数据")
                        scheduleAutoSync()
                        true
                    }
                    else -> {
                        pendingInitial = PendingInitial(
                            providerId = providerId,
                            accountLabel = accountInfo.login,
                            remoteId = remoteFile.id,
                            remoteFile = remoteFile,
                            token = token,
                            local = local,
                            remote = remote,
                        )
                        _state.update {
                            it.copy(
                                status = SyncStatus.Idle,
                                phase = "",
                                initialMerge = initialPreview(
                                    provider = providerId,
                                    providerLabel = provider.label,
                                    local = local,
                                    remote = remote,
                                ),
                                message = "本机和云端都有数据，请选择首次合并方式。",
                                error = "",
                            )
                        }
                        false
                    }
                }
            }
        } catch (error: SyncException) {
            fail(error.message)
            emit(error.message, error = true)
            false
        } catch (error: Exception) {
            val message = error.message ?: "连接失败"
            fail(message)
            emit(message, error = true)
            false
        }
    }

    suspend fun resolveInitial(strategy: SyncStrategy): Boolean = mutex.withLock {
        val pending = pendingInitial ?: return@withLock false
        if (!ensureBackup()) return@withLock false
        _state.update { it.copy(status = SyncStatus.Syncing, phase = "正在合并本机与云端", error = "", message = "") }
        return@withLock try {
            val provider = providers[pending.providerId]
                ?: throw SyncException(SyncFailureKind.Unknown, "同步后端不可用")
            val content = SyncMerge.applyStrategy(pending.local, pending.remote, strategy)
            val merged = SyncSnapshot(
                deviceId = deviceId,
                updatedAt = SyncTime.nowIso(),
                playlists = content.playlists,
                favorites = content.favorites,
                cookies = content.cookies,
            )
            if (strategy == SyncStrategy.Remote) {
                db.withTransaction { libraryDao.clearPlaylists() }
            }
            applySnapshot(merged, removeAbsent = strategy == SyncStrategy.Remote)
            val uploaded = provider.update(pending.token, pending.remoteId, encodeForUpload(merged))
            syncDao.clearAccounts()
            syncDao.clearBases()
            syncDao.clearConflicts()
            secretStore.saveToken(pending.providerId, pending.token)
            val initialAccount = SyncAccountEntity(
                provider = pending.providerId,
                remoteId = pending.remoteId,
                fileName = provider.fileName,
                lastSyncedAt = System.currentTimeMillis(),
                remoteRevision = uploaded.revision,
                remoteUrl = uploaded.htmlUrl,
                accountLabel = pending.accountLabel,
                updatedAt = System.currentTimeMillis(),
            )
            saveAccount(initialAccount)
            saveBase(merged, uploaded.revision)
            pendingInitial = null
            val aligned = reconcileAfterPush(provider, pending.token, initialAccount, uploaded, merged)
            if (aligned) {
                finish("首次合并完成")
                emit("首次合并完成，已同步 ${merged.summary()}")
            }
            scheduleAutoSync()
            aligned
        } catch (error: Exception) {
            fail(error.message ?: "首次合并失败")
            emit(error.message ?: "首次合并失败", error = true)
            false
        }
    }

    suspend fun resolveConflict(playlistId: String, strategy: SyncStrategy): Boolean {
        val conflict = syncDao.conflictsOnce().firstOrNull { it.playlistId == playlistId } ?: return false
        if (!ensureBackup()) return false
        val local = SyncJson.decodePlaylist(conflict.localSnapshot)
        val remote = SyncJson.decodePlaylist(conflict.remoteSnapshot)
        val resolved = SyncMerge.resolve(local, remote, strategy)
        val remaining = try {
            mutex.withLock {
                db.withTransaction {
                    if (resolved == null) {
                        libraryDao.deletePlaylist(playlistId)
                    } else {
                        applyPlaylist(resolved, dirty = true)
                        libraryDao.markDirty(playlistId, System.currentTimeMillis())
                    }
                    syncDao.deleteConflict(playlistId)
                }
                syncDao.conflictsOnce().size
            }
        } catch (error: Exception) {
            emit(error.message ?: "处理冲突失败", error = true)
            return false
        }
        if (remaining > 0) {
            _state.update {
                it.copy(
                    status = SyncStatus.NeedsAttention,
                    message = "还有 $remaining 个冲突需要处理。",
                )
            }
            return true
        }
        return pushResolved()
    }

    private suspend fun pushResolved(): Boolean = mutex.withLock {
        val account = syncDao.accountOnce() ?: return@withLock false
        if (syncDao.conflictsOnce().isNotEmpty()) return@withLock false
        val provider = providers[account.provider] ?: run {
            fail("同步后端不可用：${account.provider}")
            return@withLock false
        }
        val token = secretStore.token(account.provider)
        if (token.isNullOrBlank()) {
            fail("同步令牌已失效，请在设置中重新连接。")
            return@withLock false
        }
        _state.update {
            it.copy(
                status = SyncStatus.Syncing,
                phase = "正在上传冲突处理结果",
                message = "",
                error = "",
            )
        }
        return@withLock try {
            val remoteFile = provider.read(token, account.remoteId)
            if (account.remoteRevision.isNotBlank() && remoteFile.revision.isNotBlank() &&
                remoteFile.revision != account.remoteRevision
            ) {
                fail("云端文件在冲突处理期间又发生变化，请重新同步。")
                emit("云端文件已变化，请重新同步。", error = true)
                return@withLock false
            }
            val local = localSnapshot()
            val uploaded = upload(provider, token, account, local, remoteFile)
            saveBase(local, uploaded.revision)
            saveAccount(
                account.copy(
                    lastSyncedAt = System.currentTimeMillis(),
                    remoteRevision = uploaded.revision,
                    remoteUrl = uploaded.htmlUrl,
                )
            )
            val aligned = reconcileAfterPush(provider, token, account, uploaded, local)
            pruneTombstones()
            if (aligned) {
                finish("冲突已处理，已同步 ${local.playlists.count { it.deletedAt == null }} 个歌单")
                emit("冲突已处理并完成同步")
            }
            aligned
        } catch (error: SyncException) {
            fail(error.message)
            emit(error.message, error = true)
            false
        } catch (error: Exception) {
            val message = error.message ?: "冲突处理结果上传失败"
            fail(message)
            emit(message, error = true)
            false
        }
    }

    suspend fun setAutoSync(enabled: Boolean) {
        val account = syncDao.accountOnce() ?: return
        syncDao.putAccount(account.copy(autoSync = enabled, updatedAt = System.currentTimeMillis()))
        if (enabled) onLocalChange()
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        val account = syncDao.accountOnce() ?: return
        syncDao.putAccount(account.copy(wifiOnly = enabled, updatedAt = System.currentTimeMillis()))
    }

    suspend fun disconnect() {
        pendingInitial = null
        secretStore.clear()
        syncDao.clearAccounts()
        syncDao.clearBases()
        syncDao.clearConflicts()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        _state.update {
            it.copy(
                account = null,
                providerLabel = "",
                status = SyncStatus.NoAccount,
                phase = "",
                message = "同步已断开，本机歌单保留。",
                error = "",
                conflicts = emptyList(),
                initialMerge = null,
            )
        }
    }

    fun cancelInitial() {
        val pending = pendingInitial
        pendingInitial = null
        if (pending != null) scope.launch {
            if (syncDao.account(pending.providerId) == null) {
                secretStore.removeToken(pending.providerId)
            }
        }
        _state.update {
            it.copy(
                initialMerge = null,
                status = if (it.account == null) SyncStatus.NoAccount else SyncStatus.Idle,
                message = "",
            )
        }
    }

    suspend fun export(uri: Uri): Boolean = try {
        val snapshot = localSnapshot(includeDeleted = false).copy(
            deviceId = deviceId,
            updatedAt = SyncTime.nowIso(),
        )
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(SyncJson.encode(snapshot).toByteArray(Charsets.UTF_8))
        } ?: error("无法写入所选文件")
        emit("已导出 ${snapshot.summary()}")
        true
    } catch (error: Exception) {
        emit(error.message ?: "导出失败", error = true)
        false
    }

    suspend fun import(uri: Uri, strategy: SyncStrategy = SyncStrategy.Merge): Boolean = try {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: error("无法读取所选文件")
        val remote = SyncJson.decode(raw)
        if (strategy == SyncStrategy.Remote && !ensureBackup()) return false
        val local = localSnapshot()
        val content = SyncMerge.applyStrategy(local, remote, strategy)
        val snapshot = SyncSnapshot(
            deviceId = deviceId,
            updatedAt = SyncTime.nowIso(),
            playlists = content.playlists,
            favorites = content.favorites,
            cookies = content.cookies,
        )
        if (strategy == SyncStrategy.Remote) {
            db.withTransaction { libraryDao.clearPlaylists() }
        }
        applySnapshot(snapshot, removeAbsent = strategy == SyncStrategy.Remote, dirty = true)
        emit("已导入 ${snapshot.summary()}")
        onLocalChange()
        true
    } catch (error: SyncException) {
        emit(error.message, error = true)
        false
    } catch (error: Exception) {
        emit(error.message ?: "导入失败", error = true)
        false
    }

    /** 本机一有修改就短暂防抖后立即同步，不再等 WorkManager 的 30 秒延迟。 */
    fun onLocalChange() {
        localChangeJob?.cancel()
        localChangeJob = scope.launch {
            delay(LOCAL_CHANGE_DEBOUNCE_MS)
            // 用独立协程执行同步：后续修改只会取消防抖计时，不会打断正在进行的同步
            scope.launch {
                val account = syncDao.accountOnce() ?: return@launch
                if (!account.autoSync) return@launch
                if (!networkAllowsSync(account.wifiOnly)) {
                    scheduleAutoSync()
                    return@launch
                }
                val success = runSync(manual = false, notify = false)
                // 离线或服务端临时失败时交给带网络约束的 WorkManager 重试
                if (!success && state.value.status == SyncStatus.Error) scheduleAutoSync()
            }
        }
    }

    fun scheduleAutoSync() {
        scope.launch {
            val account = syncDao.accountOnce() ?: return@launch
            if (!account.autoSync) return@launch
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (account.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun syncIfStaleOnForeground() {
        scope.launch {
            val account = syncDao.accountOnce() ?: return@launch
            if (!account.autoSync || syncDao.conflictsOnce().isNotEmpty()) return@launch
            val stale = System.currentTimeMillis() - account.lastSyncedAt > 15 * 60 * 1000
            if (!stale) return@launch
            if (!networkAllowsSync(account.wifiOnly)) {
                scheduleAutoSync()
                return@launch
            }
            runSync(manual = false, notify = false)
        }
    }

    /** 进入同步 / 音乐库 / 平台账号页面时拉取一次，避免两台设备长时间停在不同版本。 */
    fun syncIfStaleOnScreen() {
        scope.launch {
            val account = syncDao.accountOnce() ?: return@launch
            if (!account.autoSync || pendingInitial != null) return@launch
            if (syncDao.conflictsOnce().isNotEmpty()) return@launch
            if (!shouldPullOnScreen(account.lastSyncedAt, System.currentTimeMillis())) return@launch
            if (!networkAllowsSync(account.wifiOnly)) {
                scheduleAutoSync()
                return@launch
            }
            runSync(manual = false, notify = false)
        }
    }

    private fun networkAllowsSync(wifiOnly: Boolean): Boolean {
        if (!wifiOnly) return true
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun encodeForUpload(snapshot: SyncSnapshot): String {
        val content = SyncJson.encode(snapshot.copy(deviceId = deviceId, updatedAt = SyncTime.nowIso()))
        if (content.toByteArray(Charsets.UTF_8).size > MAX_SYNC_BYTES) {
            throw SyncException(
                SyncFailureKind.Unknown,
                "同步文件超过 900 KB，已停止自动上传。请导出本机备份并整理歌单后再同步。",
            )
        }
        return content
    }

    private suspend fun ensureBackup(): Boolean {
        val snapshot = localSnapshot(includeDeleted = false)
        if (!snapshot.hasUserData()) return true
        return try {
            withContext(Dispatchers.IO) {
                val directory = File(context.filesDir, "backups").apply { mkdirs() }
                val file = File(directory, "mymusic-${System.currentTimeMillis()}.json")
                file.writeText(SyncJson.encode(snapshot), Charsets.UTF_8)
                directory.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(10)
                    ?.forEach { old -> old.delete() }
            }
            emit("已在本机创建处理前备份（${snapshot.summary()}）")
            true
        } catch (error: Exception) {
            fail("无法创建本机备份，已取消本次操作：${error.message ?: "存储不可写"}")
            emit("无法创建本机备份，操作已取消。", error = true)
            false
        }
    }

    private suspend fun localSnapshot(includeDeleted: Boolean = true): SyncSnapshot {
        val cookieSnapshot = settings.cookieSnapshot()
        return buildSnapshot(
            playlists = libraryDao.allPlaylistsOnce(),
            entries = libraryDao.allEntriesOnce(),
            deviceId = deviceId,
            includeDeleted = includeDeleted,
            favorites = musicDao.songsOnce("favorite"),
            favoriteTombstones = musicDao.tombstones("favorite"),
            cookies = cookieSnapshot.cookies,
            cookieUpdatedAt = cookieSnapshot.updatedAt,
        )
    }

    private suspend fun applySnapshot(
        snapshot: SyncSnapshot,
        removeAbsent: Boolean,
        dirty: Boolean = false,
    ) {
        db.withTransaction {
            val activeIds = snapshot.playlists.map { it.id }.toSet()
            if (removeAbsent) {
                libraryDao.playlistsOnce()
                    .filterNot { it.id in activeIds }
                    .forEach { libraryDao.deletePlaylist(it.id) }
            }
            snapshot.playlists.forEach { playlist ->
                applyPlaylist(playlist, dirty = dirty)
            }
        }
        applyFavorites(snapshot.favorites, removeAbsent)
        applyCookies(snapshot.cookies, removeAbsent)
    }

    private suspend fun applyFavorites(favorites: List<SyncFavorite>, removeAbsent: Boolean) {
        val active = favorites.filter { it.deletedAt == null }.associateBy { it.key }
        if (removeAbsent) {
            musicDao.songsOnce("favorite")
                .filterNot { it.key in active }
                .forEach { record -> musicDao.remove("favorite", record.key) }
            musicDao.tombstones("favorite").forEach { tombstone ->
                musicDao.clearTombstone("favorite", tombstone.key)
            }
        }
        favorites.forEach { favorite ->
            if (favorite.deletedAt != null) {
                val deletedAt = SyncTime.toMillis(favorite.deletedAt).takeIf { it > 0 } ?: System.currentTimeMillis()
                musicDao.remove("favorite", favorite.key)
                musicDao.saveTombstone(SavedSongTombstone(favorite.key, "favorite", deletedAt))
            } else {
                val song = favorite.track?.toSong() ?: return@forEach
                val addedAt = SyncTime.toMillis(favorite.addedAt).takeIf { it > 0 } ?: System.currentTimeMillis()
                musicDao.clearTombstone("favorite", favorite.key)
                musicDao.save(
                    SavedSong(
                        key = favorite.key,
                        bucket = "favorite",
                        payload = SyncJson.format.encodeToString(Song.serializer(), song),
                        updatedAt = addedAt,
                    )
                )
            }
        }
    }

    private suspend fun applyCookies(cookies: List<SyncCookie>, removeAbsent: Boolean) {
        val active = mutableMapOf<String, String>()
        val updatedAt = mutableMapOf<String, Long>()
        cookies.forEach { cookie ->
            val time = if (cookie.deletedAt != null) {
                SyncTime.toMillis(cookie.deletedAt)
            } else {
                SyncTime.toMillis(cookie.updatedAt)
            }
            updatedAt[cookie.provider] = time
            if (cookie.deletedAt == null && cookie.value.isNotBlank()) {
                active[cookie.provider] = cookie.value
            }
        }
        settings.applyCookies(active, updatedAt, removeAbsent)
    }

    private fun SyncSnapshot.hasUserData(): Boolean =
        playlists.any { it.deletedAt == null } ||
            favorites.any { it.deletedAt == null } ||
            cookies.any { it.deletedAt == null && it.value.isNotBlank() }

    private fun SyncSnapshot.summary(): String {
        val playlistCount = playlists.count { it.deletedAt == null }
        val favoriteCount = favorites.count { it.deletedAt == null }
        val cookieCount = cookies.count { it.deletedAt == null && it.value.isNotBlank() }
        return buildString {
            append("$playlistCount 个歌单")
            if (favoriteCount > 0) append("、$favoriteCount 首收藏")
            if (cookieCount > 0) append("、$cookieCount 个平台账号")
        }
    }

    private fun initialPreview(
        provider: String,
        providerLabel: String,
        local: SyncSnapshot,
        remote: SyncSnapshot,
    ) = InitialMergePreview(
        provider = provider,
        providerLabel = providerLabel,
        localPlaylists = local.playlists.count { it.deletedAt == null },
        localTracks = local.playlists.sumOf { it.tracks.size },
        remotePlaylists = remote.playlists.count { it.deletedAt == null },
        remoteTracks = remote.playlists.sumOf { it.tracks.size },
        localFavorites = local.favorites.count { it.deletedAt == null },
        remoteFavorites = remote.favorites.count { it.deletedAt == null },
        localCookies = local.cookies.count { it.deletedAt == null && it.value.isNotBlank() },
        remoteCookies = remote.cookies.count { it.deletedAt == null && it.value.isNotBlank() },
    )

    private suspend fun applyPlaylist(playlist: SyncPlaylist, dirty: Boolean) {
        val deletedAt = playlist.deletedAt?.let(SyncTime::toMillis)
        val createdAt = SyncTime.toMillis(playlist.createdAt).takeIf { it > 0 } ?: System.currentTimeMillis()
        val updatedAt = SyncTime.toMillis(playlist.updatedAt).takeIf { it > 0 } ?: System.currentTimeMillis()
        libraryDao.putPlaylist(
            PlaylistEntity(
                id = playlist.id,
                name = playlist.name,
                createdAt = createdAt,
                updatedAt = updatedAt,
                dirty = dirty,
                deletedAt = deletedAt,
            )
        )
        if (deletedAt != null) {
            libraryDao.clearEntries(playlist.id)
        } else {
            libraryDao.replaceEntries(
                playlist.id,
                playlist.tracks.mapIndexed { index, track ->
                    PlaylistEntryEntity(
                        playlistId = playlist.id,
                        position = index,
                        songKey = track.key,
                        payload = SyncJson.format.encodeToString(Song.serializer(), track.toSong()),
                    )
                },
            )
        }
    }

    /**
     * 推送成功后再读一次云端。如果其他设备在推送前后也写了，把最新内容合回本机并再推一次，
     * 避免本机停在旧 revision；云端若再次变化则继续合并，最多往返几轮。
     */
    private suspend fun reconcileAfterPush(
        provider: PlaylistSyncProvider,
        token: String,
        account: SyncAccountEntity,
        uploaded: RemoteFile,
        pushed: SyncSnapshot,
    ): Boolean = try {
        reconcileAfterPushOrThrow(provider, token, account, uploaded, pushed)
    } catch (error: SyncException) {
        fail("已上传，但读取云端最新版本失败：${error.message}")
        scheduleAutoSync()
        false
    } catch (error: Exception) {
        val message = error.message ?: "请稍后重试"
        fail("已上传，但校验云端最新版本失败：$message")
        scheduleAutoSync()
        false
    }

    private suspend fun reconcileAfterPushOrThrow(
        provider: PlaylistSyncProvider,
        token: String,
        account: SyncAccountEntity,
        uploaded: RemoteFile,
        pushed: SyncSnapshot,
    ): Boolean {
        var base = pushed
        var lastUploaded = uploaded
        var round = 0
        while (true) {
            if (round++ >= MAX_PUSH_ROUNDS) {
                fail("云端文件在同步期间持续变化，请稍后再试。")
                return false
            }
            val latest = provider.read(token, account.remoteId)
            if (remoteMatches(latest, lastUploaded)) {
                saveBase(base, latest.revision)
                saveAccount(account.copy(
                    lastSyncedAt = System.currentTimeMillis(),
                    remoteRevision = latest.revision,
                    remoteUrl = latest.htmlUrl,
                ))
                return true
            }
            val remote = SyncJson.decode(latest.content)
            val local = localSnapshot()
            val result = SyncMerge.merge(base, local, remote)
            if (result.conflicts.isNotEmpty()) {
                recordConflicts(result.conflicts)
                if (result.playlists.isNotEmpty() || result.favorites.isNotEmpty() || result.cookies.isNotEmpty()) {
                    applySnapshot(
                        SyncSnapshot(
                            deviceId = deviceId,
                            playlists = result.playlists,
                            favorites = result.favorites,
                            cookies = result.cookies,
                        ),
                        removeAbsent = false,
                        dirty = true,
                    )
                }
                saveAccount(account.copy(remoteRevision = latest.revision, remoteUrl = latest.htmlUrl))
                _state.update {
                    it.copy(
                        status = SyncStatus.NeedsAttention,
                        phase = "",
                        message = "有 ${result.conflicts.size} 个歌单需要处理冲突，处理前不会上传。",
                        error = "",
                    )
                }
                return false
            }
            if (snapshotSignature(local) == snapshotSignature(remote)) {
                saveBase(remote, latest.revision)
                saveAccount(account.copy(
                    lastSyncedAt = System.currentTimeMillis(),
                    remoteRevision = latest.revision,
                    remoteUrl = latest.htmlUrl,
                ))
                return true
            }
            val merged = SyncSnapshot(
                deviceId = deviceId,
                updatedAt = SyncTime.nowIso(),
                playlists = result.playlists,
                favorites = result.favorites,
                cookies = result.cookies,
            )
            // 上传前再确认一次；若又变了，回到循环开头按最新内容重新合并
            val check = provider.read(token, account.remoteId)
            if (!remoteMatches(check, latest)) continue
            _state.update { it.copy(phase = "正在同步其他设备的更新") }
            applySnapshot(merged, removeAbsent = false, dirty = true)
            lastUploaded = upload(provider, token, account, merged, check)
            base = merged
        }
    }

    private suspend fun recordConflicts(conflicts: List<MergeConflict>) {
        db.withTransaction {
            syncDao.clearConflicts()
            conflicts.forEach { conflict ->
                syncDao.putConflict(
                    SyncConflictEntity(
                        playlistId = conflict.playlistId,
                        localSnapshot = SyncJson.encodePlaylist(conflict.local),
                        remoteSnapshot = SyncJson.encodePlaylist(conflict.remote),
                        baseSnapshot = SyncJson.encodePlaylist(conflict.base),
                        detectedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    private suspend fun upload(
        provider: PlaylistSyncProvider,
        token: String,
        account: SyncAccountEntity,
        snapshot: SyncSnapshot,
        remoteFile: RemoteFile,
    ): RemoteFile {
        val content = encodeForUpload(snapshot)
        val uploaded = if (account.remoteId.isBlank()) {
            provider.create(token, content)
        } else {
            provider.update(token, if (remoteFile.id.isBlank()) account.remoteId else remoteFile.id, content)
        }
        markClean(snapshot)
        return uploaded
    }

    private suspend fun markClean(snapshot: SyncSnapshot) {
        val uploaded = snapshot.playlists.associateBy { it.id }
        libraryDao.allPlaylistsOnce().forEach { playlist ->
            val remote = uploaded[playlist.id] ?: return@forEach
            if (playlist.dirty && playlist.updatedAt == SyncTime.toMillis(remote.updatedAt)) {
                libraryDao.markClean(playlist.id, playlist.updatedAt, dirty = false)
            }
        }
    }

    private suspend fun saveAccount(account: SyncAccountEntity) {
        syncDao.putAccount(account.copy(updatedAt = System.currentTimeMillis()))
    }

    private suspend fun saveBase(snapshot: SyncSnapshot, revision: String) {
        val account = syncDao.accountOnce() ?: return
        syncDao.putBase(
            SyncBaseEntity(
                accountId = account.provider,
                snapshot = SyncJson.encode(snapshot),
                remoteRevision = revision,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun pruneTombstones() {
        libraryDao.pruneDeleted(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
    }

    private fun finish(message: String) {
        _state.update {
            it.copy(
                status = if (it.conflicts.isEmpty()) SyncStatus.Idle else SyncStatus.NeedsAttention,
                phase = "",
                message = message,
                error = "",
                initialMerge = null,
            )
        }
    }

    private fun fail(message: String) {
        _state.update {
            it.copy(
                status = SyncStatus.Error,
                phase = "",
                error = message,
                message = "",
            )
        }
    }

    private fun emit(message: String, error: Boolean = false) {
        _events.tryEmit(SyncEvent(message, error))
    }

    private fun toConflictUi(conflict: SyncConflictEntity): SyncConflictUi {
        val local = SyncJson.decodePlaylist(conflict.localSnapshot)
        val remote = SyncJson.decodePlaylist(conflict.remoteSnapshot)
        return SyncConflictUi(
            playlistId = conflict.playlistId,
            name = local?.name ?: remote?.name ?: "未命名歌单",
            localTracks = local?.tracks?.size ?: 0,
            remoteTracks = remote?.tracks?.size ?: 0,
            localUpdatedAt = SyncTime.toMillis(local?.updatedAt).takeIf { it > 0 } ?: conflict.detectedAt,
            remoteUpdatedAt = SyncTime.toMillis(remote?.updatedAt).takeIf { it > 0 } ?: conflict.detectedAt,
            localDeleted = local?.deletedAt != null,
            remoteDeleted = remote?.deletedAt != null,
        )
    }

    companion object {
        const val WORK_NAME = "mymusic-library-sync"
        const val MAX_SYNC_BYTES = 900 * 1024
        const val LOCAL_CHANGE_DEBOUNCE_MS = 1_000L
        const val SCREEN_PULL_STALE_MS = 30_000L
        private const val MAX_PUSH_ROUNDS = 3
    }
}

/** 进入页面时是否值得拉取：比前台 15 分钟阈值短得多，但避免页面切换连续请求。 */
internal fun shouldPullOnScreen(lastSyncedAt: Long, now: Long): Boolean =
    now - lastSyncedAt >= SyncCoordinator.SCREEN_PULL_STALE_MS

/** revision 是首选依据；服务端没给 revision 时退回比较时间戳和内容。 */
internal fun remoteMatches(current: RemoteFile, other: RemoteFile): Boolean {
    if (current.revision.isNotBlank() && other.revision.isNotBlank()) return current.revision == other.revision
    return current.updatedAt == other.updatedAt && current.content == other.content
}
