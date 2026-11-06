package com.mytv.mymusic.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * 按地址挑缓存：直播走直连，其余照旧进磁盘缓存。
 *
 * HLS 直播每 10 秒重拉一次清单，地址不变。清单一旦进了缓存，之后每次都读到打开时那一份，
 * 播放器发现媒体序号一直不动就抛 PlaylistStuckException（约一分半断流）；直播切片也是一次性的，
 * 缓存既没用又占地方。
 */
internal class LiveBypassDataSourceFactory(
    private val cached: DataSource.Factory,
    private val direct: DataSource.Factory,
    private val isLive: (Uri) -> Boolean,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = Source()

    private inner class Source : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            val source = (if (isLive(dataSpec.uri)) direct else cached).createDataSource()
            listeners.forEach(source::addTransferListener)
            delegate = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(delegate) { "open() 之前调用了 read()" }.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate?.uri

        override fun close() {
            delegate?.close()
            delegate = null
        }
    }
}
