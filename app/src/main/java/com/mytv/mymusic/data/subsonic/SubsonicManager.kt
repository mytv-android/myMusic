package com.mytv.mymusic.data.subsonic

import android.content.Context
import com.mytv.mymusic.data.AppDatabase
import com.mytv.mymusic.data.SettingsStore
import com.mytv.mymusic.data.native.NativeProviders
import com.mytv.mymusic.data.playreport.PlayReportHub
import com.mytv.mymusic.data.webdav.CertificateProbe
import com.mytv.mymusic.data.webdav.WebDavCertificate
import com.mytv.mymusic.data.webdav.pinTrust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

class SubsonicSecurityStore(context: Context) {
    private val prefs = context.getSharedPreferences("subsonic_security", Context.MODE_PRIVATE)

    fun pinned(account: SubsonicAccount): WebDavCertificate? {
        val base = account.baseUrl ?: return null
        if (prefs.getString(originKey(account.id), null) != origin(account)) return null
        return WebDavCertificate.decode(prefs.getString(account.id, null))
    }

    fun trust(account: SubsonicAccount, certificate: WebDavCertificate) {
        prefs.edit()
            .putString(account.id, certificate.encode())
            .putString(originKey(account.id), origin(account))
            .apply()
    }

    fun reconcile(account: SubsonicAccount) {
        val storedOrigin = prefs.getString(originKey(account.id), null) ?: return
        if (storedOrigin != origin(account)) remove(account.id)
    }

    fun remove(accountId: String) {
        prefs.edit().remove(accountId).remove(originKey(accountId)).apply()
    }

    private fun origin(account: SubsonicAccount): String = account.baseUrl?.let {
        "${it.scheme}://${it.host}:${it.port}"
    }.orEmpty()

    private fun originKey(accountId: String) = "$accountId.origin"
}

class SubsonicManager(
    context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val database: AppDatabase? = null,
) {
    private val appContext = context.applicationContext
    private val baseHttp = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private val lock = Any()
    private val clients = mutableMapOf<String, SubsonicClient>()
    private val registered = mutableSetOf<String>()
    private var reconciled = false

    val covers = SubsonicCoverCache(appContext)
    val security = SubsonicSecurityStore(appContext)
    val revision = MutableStateFlow(0L)
    @Volatile
    var onLocalChange: () -> Unit = {}

    @Volatile
    private var routes: List<SubsonicClient> = emptyList()

    @Synchronized
    fun syncAccounts(cookies: Map<String, String>) {
        val accounts = SubsonicAccount.fromCookies(cookies)
        val currentIds = accounts.mapTo(mutableSetOf()) { it.providerId }
        val arrived = if (reconciled) {
            accounts.filter { account -> synchronized(lock) { account.providerId !in registered } }
        } else {
            emptyList()
        }
        reconciled = true
        val removed = synchronized(lock) { registered - currentIds }
        accounts.forEach { account ->
            security.reconcile(account)
            val previous = synchronized(lock) { clients[account.providerId] }
            val libraryChanged = previous != null && !sameSubsonicLibrary(previous.account, account)
            if (libraryChanged) {
                covers.removeAccount(account.id)
                scope.launch(Dispatchers.IO) {
                    database?.subsonicTracks()?.deleteAccount(account.id)
                    // 清完再挂回缓存；第一次重建期间不做离线回退，避免读到旧服务器的曲目。
                    syncAccounts(settings.cookieSnapshot().cookies)
                }
            }
            val client = clientFor(account)
            val reporter = PlayReportHub.reporter(account.providerId) as? SubsonicPlayReporter
            if (reporter != null && previous != null && sameSubsonicLibrary(previous.account, account)) {
                reporter.updateClient(client)
            } else {
                PlayReportHub.unregister(account.providerId)
                PlayReportHub.register(SubsonicPlayReporter(client))
            }
            val cache = if (libraryChanged) null else database?.let { SubsonicTrackCache(it.subsonicTracks(), client.account, covers) }
            NativeProviders.register(
                SubsonicProvider(account, covers = covers, trackCache = cache, sharedClient = client),
            )
            synchronized(lock) { registered += account.providerId }
        }
        removed.forEach { providerId ->
            NativeProviders.unregister(providerId)
            PlayReportHub.unregister(providerId)
            val accountId = providerId.removePrefix(SUBSONIC_PROVIDER_PREFIX)
            synchronized(lock) { clients.remove(providerId); registered.remove(providerId) }
            covers.removeAccount(accountId)
            security.remove(accountId)
            scope.launch(Dispatchers.IO) { database?.subsonicTracks()?.deleteAccount(accountId) }
        }
        routes = accounts.map { clientFor(it) }
        revision.value += 1
        if (arrived.isNotEmpty()) scope.launch(Dispatchers.IO) { enable(arrived) }
    }

    fun routingClients(): List<SubsonicClient> = routes

    suspend fun openDownload(rawUrl: String): Response? {
        val target = rawUrl.toHttpUrlOrNull() ?: return null
        val accountId = target.queryParameter(SUBSONIC_ROUTE_ACCOUNT) ?: return null
        val client = synchronized(lock) { clients[SUBSONIC_PROVIDER_PREFIX + accountId] } ?: return null
        if (!client.matches(target)) return null
        return client.openMedia(target)
    }

    suspend fun testConnection(account: SubsonicAccount): SubsonicConnection {
        val normalized = account.normalized()
        val client = SubsonicClient(normalized, httpFor(normalized))
        return client.testConnection()
    }

    suspend fun save(account: SubsonicAccount) {
        enable(listOf(account))
        settings.cookie(account.providerId, account.encode())
        onLocalChange()
    }

    suspend fun remove(account: SubsonicAccount) {
        settings.cookie(account.providerId, "")
        onLocalChange()
    }

    suspend fun probeCertificate(account: SubsonicAccount): WebDavCertificate? = withContext(Dispatchers.IO) {
        val base = account.baseUrl ?: return@withContext null
        if (base.scheme != "https") return@withContext null
        val probe = CertificateProbe(baseHttp)
        val url = base.newBuilder()
            .addPathSegments("rest/ping.view")
            .addQueryParameter("v", "1.16.1")
            .addQueryParameter("c", "myMusic")
            .build()
        runCatching { probe.client.newCall(Request.Builder().url(url).get().build()).execute().close() }
        probe.result()
    }

    private suspend fun enable(accounts: List<SubsonicAccount>) {
        if (accounts.isEmpty()) return
        val current = settings.state.first().aggregation
        val missing = accounts.map { it.providerId }.filterNot { it in current.enabledProviders }
        if (missing.isNotEmpty()) {
            settings.saveAggregation(current.copy(enabledProviders = current.enabledProviders + missing))
        }
    }

    private fun clientFor(account: SubsonicAccount): SubsonicClient = synchronized(lock) {
        val existing = clients[account.providerId]
        if (existing != null && sameSubsonicCredentials(existing.account, account)) {
            existing.adopt(account)
            return@synchronized existing
        }
        lateinit var created: SubsonicClient
        created = SubsonicClient(account, httpFor(account), onAccountChanged = { updated ->
            scope.launch(Dispatchers.IO) { persistProbe(created, updated) }
        }).also { clients[account.providerId] = it }
        created
    }

    private suspend fun persistProbe(client: SubsonicClient, updated: SubsonicAccount) {
        val currentRaw = settings.cookieSnapshot().cookies[updated.providerId] ?: return
        val current = SubsonicAccount.decode(currentRaw) ?: return
        val currentClient = synchronized(lock) { clients[updated.providerId] }
        if (currentClient !== client || !sameSubsonicCredentials(current, updated)) return
        val merged = mergeSubsonicProbe(current, updated).encode()
        if (settings.cookieIfCurrent(updated.providerId, currentRaw, merged)) onLocalChange()
    }

    private fun httpFor(account: SubsonicAccount): OkHttpClient {
        val base = account.baseUrl
        val pin = security.pinned(account)
        if (base == null || base.scheme != "https" || pin == null) return baseHttp
        return baseHttp.newBuilder().apply { pinTrust(baseHttp, base.host, pin.sha256) }.build()
    }
}
