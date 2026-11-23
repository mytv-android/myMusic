package com.mytv.mymusic.data.subsonic

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@OptIn(UnstableApi::class)
class SubsonicRoutingDataSourceFactory(
    private val clients: () -> List<SubsonicClient>,
    private val fallback: DataSource.Factory,
    private val onRangeIgnored: (String) -> Unit = {},
) : DataSource.Factory {
    override fun createDataSource(): DataSource = Source()

    private inner class Source : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null
        private var originalUri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            originalUri = dataSpec.uri
            val target = dataSpec.uri.toString().toHttpUrlOrNull()
            val client = if (target == null) null else selectClient(clients(), target)
            val source = if (client == null || target == null) {
                fallback.createDataSource()
            } else {
                OkHttpDataSource.Factory(client.mediaHttpClient).createDataSource()
            }
            listeners.forEach(source::addTransferListener)
            delegate = source
            val openedSpec = if (client == null || target == null) dataSpec else dataSpec.withUri(Uri.parse(client.authorize(target).toString()))
            val length = source.open(openedSpec)
            if (client != null && dataSpec.position > 0 && source.responseHeaders.entries.any {
                    it.key.equals("Accept-Ranges", true) && it.value.any { value -> value.equals("none", true) }
                }
            ) {
                onRangeIgnored(dataSpec.uri.toString())
            }
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(delegate) { "open() 之前调用了 read()" }.read(buffer, offset, length)

        override fun getUri(): Uri? = originalUri ?: delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                delegate?.close()
            } finally {
                delegate = null
                originalUri = null
            }
        }
    }

    companion object {
        internal fun selectClient(clients: List<SubsonicClient>, target: HttpUrl): SubsonicClient? {
            val matches = clients.filter { it.matches(target) }
            val routeId = target.queryParameter(SUBSONIC_ROUTE_ACCOUNT)
            return if (routeId.isNullOrBlank()) matches.singleOrNull() else matches.firstOrNull { it.account.id == routeId }
        }

        fun handles(account: SubsonicAccount, url: String): Boolean {
            val target = url.toHttpUrlOrNull() ?: return false
            val base = account.baseUrl ?: return false
            if (target.scheme != base.scheme || !target.host.equals(base.host, true) || target.port != base.port) return false
            val prefix = base.encodedPath.let { if (it.endsWith('/')) it else "$it/" }
            return target.encodedPath.startsWith(prefix)
        }
    }
}
