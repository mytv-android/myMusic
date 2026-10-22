package com.mytv.mymusic.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * 和 LeastRecentlyUsedCacheEvictor 一样按最近使用淘汰，但上限可以在运行中调整：
 * 设置里改了播放缓存上限后立即生效，不需要重建 SimpleCache。
 */
@UnstableApi
class AdjustableLruEvictor(maxBytes: Long) : CacheEvictor {
    @Volatile
    var maxBytes: Long = maxBytes
        private set

    private val spans = TreeSet<CacheSpan> { a, b ->
        val byTime = a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
        if (byTime != 0) byTime else a.compareTo(b)
    }
    private var currentSize = 0L
    private var cache: Cache? = null

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != androidx.media3.common.C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        this.cache = cache
        spans.add(span)
        currentSize += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /** 调低上限时立刻按最近使用淘汰多出的部分。在 IO 线程调用。 */
    fun updateMaxBytes(value: Long) {
        maxBytes = value
        // SimpleCache 的方法都在它自身上加锁，回调也在锁内，这里同样持锁，避免和播放线程同时改 spans
        cache?.let { target -> synchronized(target) { evict(target, 0) } }
    }

    private fun evict(cache: Cache, required: Long) {
        while (currentSize + required > maxBytes && spans.isNotEmpty()) {
            cache.removeSpan(spans.first())
        }
    }
}
