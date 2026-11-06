package com.mytv.mymusic

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.native.AppleProvider
import com.mytv.mymusic.data.native.AppleTokenCache
import com.mytv.mymusic.playback.AppleWidevineDrmCallback
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 设备上的整曲端到端验证：真实 Apple 接口取流 → 真实清单重写 → 真实 ExoPlayer +
 * 设备自带 Widevine CDM → 真实授权 → 真解码 → 真 PCM 振幅。
 *
 * 需要 cookie（含 media-user-token），通过 instrumentation 参数传入：
 *   cookie=media-user-token=xxxx
 * 没传就跳过，不阻塞常规测试。
 *
 * 两个用例刻意只差一个变量——**上游数据源是否走 CacheDataSource**：
 * app 的 PlaybackService 是走缓存的，而段是同一个 URL 的多个 byte-range，
 * 缓存层如果处理不当会让 CDM 解出垃圾（真机 4003、模拟器不复现就是这个组合）。
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AppleFullTrackDeviceTest {

    private val tag = "AppleE2E"

    private fun log(msg: String) = android.util.Log.i(tag, msg)

    private fun positionOf(player: AtomicReference<ExoPlayer?>): Long {
        var pos = -1L
        InstrumentationRegistry.getInstrumentation().runOnMainSync { pos = player.get()?.currentPosition ?: -1L }
        return pos
    }

    private fun dumpAppLog() {
        com.mytv.mymusic.AppLog.snapshot()
            .filter { it.tag in setOf("AppleDrm", "AppleProvider", "MediaItem", "PlaybackDrm") }
            .forEach { log("APPLOG ${it.level}/${it.tag}: ${it.message.take(220)}") }
    }

    @Test
    fun playFullTrackPlainDataSource() {
        run(useCache = false)
    }

    @Test
    fun playFullTrackWithCache() {
        run(useCache = true)
    }

    private fun run(useCache: Boolean) {
        val args = InstrumentationRegistry.getArguments()
        val cookie = args.getString("cookie").orEmpty()
        if (cookie.isBlank()) {
            log("SKIP 没有传 cookie")
            return
        }
        runBlocking { play(cookie, useCache) }
    }

    private suspend fun play(cookie: String, useCache: Boolean) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppleTokenCache.attach(context)
        // Media3 的 DRM 日志（会话回收 / initData / 错误）默认不开，排 key 轮换必须看
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_ALL)

        val song = Song(
            id = "6829122414",
            name = "Hong Kong (feat. 王菲季候風)",
            artist = "The Weeknd",
            source = "apple",
            extra = mapOf("storefront" to "cn"),
        )
        val stream = AppleProvider().playableStream(cookie, song, context.cacheDir)
        log("stream url=${stream.url.take(60)} preview=${stream.preview} extra=${stream.extra.keys}")

        // 把落盘的清单和 init 段读回来，确认 kid 改写确实生效
        runCatching {
            val playlistFile = File(stream.url.removePrefix("file://"))
            if (playlistFile.isFile) {
                playlistFile.readLines().filter { it.startsWith("#EXT-X-MAP") || it.startsWith("#EXT-X-KEY") }
                    .forEach { log("PLAYLIST " + it.take(120)) }
            }
            val initFile = File(context.cacheDir, "apple-6829122414-init.mp4")
            if (initFile.isFile) {
                val bytes = initFile.readBytes()
                var i = 0
                while (i <= bytes.size - 4) {
                    if (bytes[i] == 't'.code.toByte() && bytes[i + 1] == 'e'.code.toByte() &&
                        bytes[i + 2] == 'n'.code.toByte() && bytes[i + 3] == 'c'.code.toByte()
                    ) {
                        val kid = bytes.copyOfRange(i + 12, i + 28)
                        log("INIT tenc kid=" + kid.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
                    }
                    i++
                }
            } else {
                log("INIT 没有本地 init 文件（未改写）")
            }
        }.onFailure { log("DUMP 失败 ${it.message}") }

        val scheme = stream.extra[AppleWidevineDrmCallback.EXTRA_DRM_SCHEME]
        val pssh = stream.extra[AppleWidevineDrmCallback.EXTRA_DRM_PSSH].orEmpty()
        val adamId = stream.extra[AppleWidevineDrmCallback.EXTRA_DRM_ADAM_ID].orEmpty()
        assertNotNull("没有 DRM scheme", scheme)
        assertTrue("pssh 为空", pssh.isNotBlank())

        val mediaItem = MediaItem.Builder()
            .setMediaId(song.key)
            .setUri(stream.url)
            .apply {
                if (scheme == AppleWidevineDrmCallback.DRM_SCHEME_WIDEVINE && pssh.isNotBlank()) {
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setLicenseUri(AppleWidevineDrmCallback.licenseUri(adamId, pssh))
                            .setMultiSession(true)
                            .build(),
                    )
                }
            }
            .build()

        // 和 PlaybackService 一致：按 MediaItem 的 licenseUri 建 manager。
        // 授权请求里的 uri 必须和这份清单的 key 是同一把——用另一把 key 的 PSSH 拿到的
        // license 即使 kid 相同也解不出正确的明文（实测：整段变成静音，不报错）。
        val managers = mutableMapOf<String, DefaultDrmSessionManager>()
        fun managerFor(licenseUri: String): androidx.media3.exoplayer.drm.DrmSessionManager {
            val params = AppleWidevineDrmCallback.parseQuery(licenseUri)
            return managers.getOrPut(licenseUri) {
                val kid = com.mytv.mymusic.data.native.AppleHlsManifest.widevineKeyId(params["pssh"].orEmpty())
                    ?.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                log("建 manager uri kid=$kid")
                DefaultDrmSessionManager.Builder()
                    .setMultiSession(true)
                    .setUuidAndExoMediaDrmProvider(
                        C.WIDEVINE_UUID,
                        AppleWidevineDrmCallback.SECURE_IF_AVAILABLE_PROVIDER,
                    )
                    .build(
                        AppleWidevineDrmCallback(
                            cookieProvider = { cookie },
                            adamId = params["adamId"].orEmpty(),
                            pssh = params["pssh"].orEmpty(),
                        ),
                    )
            }
        }
        val upstream: DataSource.Factory = DefaultDataSource.Factory(context)
        var cache: SimpleCache? = null
        val dataSourceFactory: DataSource.Factory = if (useCache) {
            val dir = File(context.cacheDir, "e2e-cache")
            dir.deleteRecursively()
            val c = SimpleCache(dir, NoOpCacheEvictor())
            cache = c
            CacheDataSource.Factory().setCache(c).setUpstreamDataSourceFactory(upstream)
        } else {
            upstream
        }
        log("数据源=${if (useCache) "CacheDataSource" else "DefaultDataSource"}")
        val factory = DefaultMediaSourceFactory(dataSourceFactory)
            .setDrmSessionManagerProvider { item ->
                managerFor(item.localConfiguration?.drmConfiguration?.licenseUri?.toString().orEmpty())
            }
        // 开头段（默认 key）是另一份清单，和 PlaybackService 一样拼成一个媒体项
        val introUrl = stream.extra[AppleWidevineDrmCallback.EXTRA_DRM_INTRO_URL]
        val source = if (introUrl.isNullOrBlank()) {
            factory.createMediaSource(mediaItem)
        } else {
            log("INTRO 拼开头段 $introUrl")
            val introItem = mediaItem.buildUpon()
                .setUri(introUrl)
                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().build())
                .setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                        .setLicenseUri(
                            AppleWidevineDrmCallback.licenseUri(
                                adamId,
                                com.mytv.mymusic.data.native.AppleHlsManifest.defaultWidevineKeyUri(),
                            ),
                        )
                        .setMultiSession(true)
                        .build(),
                )
                .build()
            androidx.media3.exoplayer.source.ConcatenatingMediaSource2.Builder()
                .add(factory.createMediaSource(introItem))
                .add(factory.createMediaSource(mediaItem))
                .setMediaItem(mediaItem)
                .build()
        }

        // PCM 探针：抓送到 AudioSink 的采样，算峰值。进度在走也可能只是静音，
        // 只有波形有振幅才叫"有声音"。
        val pcmPeak = AtomicInteger(0)
        val pcmFrames = AtomicLong(0)
        val pcmSink = object : TeeAudioProcessor.AudioBufferSink {
            private var bytesPerSample = 2
            private var channels = 2

            override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                channels = channelCount.coerceAtLeast(1)
                bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
            }

            override fun handleBuffer(buffer: ByteBuffer) {
                val copy = buffer.duplicate()
                val n = copy.remaining()
                val bytes = ByteArray(n)
                copy.get(bytes)
                var i = 0
                while (i + 1 < n) {
                    val v = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                    val a = kotlin.math.abs(v)
                    pcmPeak.accumulateAndGet(a) { x, y -> maxOf(x, y) }
                    i += 2
                }
                pcmFrames.addAndGet((n / (bytesPerSample * channels)).toLong())
            }
        }
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                // 固定 16 位整数输出，PCM 探针就不用管浮点分支
                .setEnableFloatOutput(false)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(pcmSink)))
                .build()
        }

        // ExoPlayer 必须在主线程创建和操作
        val instrument = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        val stateRef = AtomicInteger(0)
        var error: PlaybackException? = null
        val player = AtomicReference<ExoPlayer?>(null)
        instrument.runOnMainSync {
            val p = ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                .build()
            player.set(p)
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    stateRef.set(state)
                    log("state=$state (1=IDLE 2=BUFFERING 3=READY 4=ENDED)")
                    if (state == Player.STATE_READY) done.countDown()
                }

                override fun onPlayerError(e: PlaybackException) {
                    error = e
                    log("ERROR ${e.errorCodeName}(${e.errorCode}) ${e.message?.take(200)}")
                    log("ERROR cause=${e.cause?.javaClass?.simpleName}: ${e.cause?.message?.take(200)}")
                    done.countDown()
                }
            })
            p.setMediaSource(source)
            p.prepare()
            p.play()
        }
        val ok = done.await(60, TimeUnit.SECONDS)
        // READY 只是"能开始播放"，必须让进度真的走起来、并且 PCM 有振幅。
        // 开头 30s 是默认 key 保的预取段，按秒采样能看到"什么时候才真的有声"。
        val sampler = Thread {
            repeat(16) {
                Thread.sleep(2_000)
                log("T+${(it + 1) * 2}s pos=${positionOf(player)} peak=${pcmPeak.get()} frames=${pcmFrames.get()}")
            }
        }
        sampler.isDaemon = true
        sampler.start()
        Thread.sleep(32_000)
        var position = 0L
        var playing = false
        var state = 0
        var err: String? = null
        instrument.runOnMainSync {
            val p = player.get()
            position = p?.currentPosition ?: 0L
            playing = p?.isPlaying == true
            state = p?.playbackState ?: 0
            err = p?.playerError?.errorCodeName
        }
        log("RESULT cache=$useCache ok=$ok position=$position playing=$playing state=$state err=$err " +
            "pcmPeak=${pcmPeak.get()} pcmFrames=${pcmFrames.get()}")

        // 开头 30s 左右用的是默认 key（预取条目），后面是轮换 key。必须跨过这条边界还能解，
        // 否则就是"只有前奏能放"或者反过来——两种情况都算失败。
        val peakBeforeSeek = pcmPeak.get()
        val framesBeforeSeek = pcmFrames.get()
        instrument.runOnMainSync { player.get()?.seekTo(35_000) }
        Thread.sleep(8_000)
        var position2 = 0L
        var err2: String? = null
        var state2 = 0
        instrument.runOnMainSync {
            val p = player.get()
            position2 = p?.currentPosition ?: 0L
            err2 = p?.playerError?.errorCodeName
            state2 = p?.playbackState ?: 0
        }
        log(
            "RESULT-AFTER-KEY $useCache position=$position2 state=$state2 err=$err2 " +
                "pcmPeak=$peakBeforeSeek→${pcmPeak.get()} frames=$framesBeforeSeek→${pcmFrames.get()}",
        )

        instrument.runOnMainSync { player.get()?.release() }
        cache?.release()
        dumpAppLog()

        assertTrue("[cache=$useCache] 等待超时，没到 READY 也没报错", ok)
        assertTrue("[cache=$useCache] 播放失败: $err (${error?.message})", error == null)
        assertTrue("[cache=$useCache] 进度没走（$position ms）", position > 2_000)
        assertTrue("[cache=$useCache] 没收到任何 PCM 数据", pcmFrames.get() > 0)
        // 静音段峰值是 0~1；真实音乐样本通常数千以上（16 位满刻度 32767）
        assertTrue(
            "[cache=$useCache] PCM 峰值只有 ${pcmPeak.get()}（满刻度 32767）—— 解出来基本是静音",
            pcmPeak.get() > 500,
        )
        // 跨过 key 边界（35s 处属于轮换 key 的正片）必须继续能放
        assertTrue("[cache=$useCache] 跳转后报错: $err2", err2 == null)
        assertTrue("[cache=$useCache] 跳转后进度没走（$position2 ms）", position2 > 36_000)
        assertTrue(
            "[cache=$useCache] 跳转后没有新的 PCM 数据（${pcmFrames.get()} vs $framesBeforeSeek）",
            pcmFrames.get() > framesBeforeSeek,
        )
    }
}
