package com.mytv.mymusic

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 设备上的整曲端到端验证：真实 Apple 接口取流 → 真实清单重写 → 真实 ExoPlayer +
 * 设备自带 Widevine CDM → 真实授权 → 真解码。
 *
 * 需要 cookie（含 media-user-token），通过 instrumentation 参数传入：
 *   cookie=media-user-token=xxxx
 * 没传就跳过，不阻塞常规测试。
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AppleFullTrackDeviceTest {

    private val tag = "AppleE2E"

    private fun log(msg: String) = android.util.Log.i(tag, msg)

    @Test
    fun playFullTrack(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val cookie = args.getString("cookie").orEmpty()
        if (cookie.isBlank()) {
            log("SKIP 没有传 cookie")
            return@runBlocking
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppleTokenCache.attach(context)

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
            val playlistFile = java.io.File(stream.url.removePrefix("file://"))
            if (playlistFile.isFile) {
                playlistFile.readLines().filter { it.startsWith("#EXT-X-MAP") || it.startsWith("#EXT-X-KEY") }
                    .forEach { log("PLAYLIST " + it.take(120)) }
            }
            val initFile = java.io.File(context.cacheDir, "apple-6829122414-init.mp4")
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
                            .setMultiSession(false)
                            .build(),
                    )
                }
            }
            .build()

        val manager = DefaultDrmSessionManager.Builder().build(
            AppleWidevineDrmCallback(
                cookieProvider = { cookie },
                adamId = adamId,
                pssh = pssh,
            ),
        )
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context))
            .setDrmSessionManagerProvider { manager }
        val source: MediaSource = factory.createMediaSource(mediaItem)

        // ExoPlayer 必须在主线程创建和操作
        val instrument = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        val stateRef = java.util.concurrent.atomic.AtomicInteger(0)
        var error: PlaybackException? = null
        val player = java.util.concurrent.atomic.AtomicReference<ExoPlayer?>(null)
        // PCM 探针：抓送到 AudioSink 的采样，算峰值。进度在走也可能只是静音，
        // 只有波形有振幅才叫"有声音"。
        val pcmPeak = java.util.concurrent.atomic.AtomicInteger(0)
        val pcmFrames = java.util.concurrent.atomic.AtomicLong(0)
        val pcmSink = object : androidx.media3.exoplayer.audio.TeeAudioProcessor.AudioBufferSink {
            private var bytesPerSample = 2
            private var channels = 2
            override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                channels = channelCount.coerceAtLeast(1)
                bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
            }

            override fun handleBuffer(buffer: java.nio.ByteBuffer) {
                val copy = buffer.duplicate()
                val n = copy.remaining()
                val bytes = ByteArray(n)
                copy.get(bytes)
                if (bytesPerSample == 2) {
                    var i = 0
                    while (i + 1 < n) {
                        val v = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                        val a = kotlin.math.abs(v)
                        pcmPeak.accumulateAndGet(a) { x, y -> maxOf(x, y) }
                        i += 2
                    }
                } else {
                    var i = 0
                    while (i + 3 < n) {
                        val bits = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[i + 2].toInt() and 0xFF) shl 16) or ((bytes[i + 3].toInt() and 0xFF) shl 24)
                        val f = java.lang.Float.intBitsToFloat(bits)
                        val a = (kotlin.math.abs(f) * 32768).toInt()
                        pcmPeak.accumulateAndGet(a) { x, y -> maxOf(x, y) }
                        i += 4
                    }
                }
                pcmFrames.addAndGet((n / (bytesPerSample * channels)).toLong())
            }

        }
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    // 固定 16 位整数输出，PCM 探针就不用管浮点分支
                    .setEnableFloatOutput(false)
                    .setAudioProcessors(
                        arrayOf(androidx.media3.exoplayer.audio.TeeAudioProcessor(pcmSink)),
                    )
                    .build()
        }

        instrument.runOnMainSync {
            val p = ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
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
        // READY 只是"能开始播放"，必须让进度真的走起来才算出声：
        // 位置前进意味着样本被解密、解码并交给音频输出。
        Thread.sleep(6_000)
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
        log("PROGRESS position=$position playing=$playing state=$state err=$err pcmPeak=${pcmPeak.get()} pcmFrames=${pcmFrames.get()}")
        assertTrue("播了 6 秒进度还是 $position ms —— 没有真正出声", position > 2_000)
        assertTrue("没收到任何 PCM 数据", pcmFrames.get() > 0)
        // 静音段峰值是 0~1；真实音乐样本通常在数千以上（16 位满刻度 32767）
        assertTrue(
            "PCM 峰值只有 ${pcmPeak.get()}（16 位满刻度 32767）—— 解码出来基本是静音",
            pcmPeak.get() > 500,
        )
        log("RESULT ok=$ok position=$position state=${stateRef.get()} error=${error?.errorCodeName ?: "none"}")
        instrument.runOnMainSync { player.get()?.release() }

        assertTrue("等待超时，没到 READY 也没报错", ok)
        assertTrue("播放失败: ${error?.errorCodeName} ${error?.message}", error == null)
        assertTrue("READY 了但进度没走（$position ms），可能没有真正出声", position >= 0)
    }
}
