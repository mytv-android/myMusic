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
        instrument.runOnMainSync {
            val p = ExoPlayer.Builder(context).build()
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
        log("PROGRESS position=$position playing=$playing state=$state err=$err")
        assertTrue("播了 6 秒进度还是 $position ms —— 没有真正出声", position > 2_000)
        log("RESULT ok=$ok position=$position state=${stateRef.get()} error=${error?.errorCodeName ?: "none"}")
        instrument.runOnMainSync { player.get()?.release() }

        assertTrue("等待超时，没到 READY 也没报错", ok)
        assertTrue("播放失败: ${error?.errorCodeName} ${error?.message}", error == null)
        assertTrue("READY 了但进度没走（$position ms），可能没有真正出声", position >= 0)
    }
}
