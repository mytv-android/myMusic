package com.mytv.mymusic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.native.MHubProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * music.op.wiki 链路探测：先探各个域名的 TLS 可达性，再走搜索 → playbacks → 真播放。
 * 任何一步挂掉都在日志里留下具体原因，不用猜。
 *
 * 探针分两种，正好把"绕过证书校验"这件事的两面都照出来：
 *  - [strictClient] 严格校验证书（系统默认）——在 Android 6/7 上两个 MHub 域名必然握手失败；
 *  - [MHubTls.client] 不校验——搜索/取流走这条。
 * 播放那一步由 ExoPlayer 经 MHubTls.RoutingFactory 自己走，测试里只用 DefaultDataSource 复现。
 */
@RunWith(AndroidJUnit4::class)
class MHubPlaybackDeviceTest {

    private val tag = "MHubE2E"

    private fun log(msg: String) = android.util.Log.i(tag, msg)

    private val strictClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 只诊断、不判失败：故意用严格校验证书的客户端，证明这两个域名在旧系统上就是握不上手。 */
    private fun probe(url: String) {
        try {
            strictClient.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                log("PROBE(strict) $url -> HTTP ${r.code}")
            }
        } catch (e: Exception) {
            log("PROBE(strict) $url -> FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Test
    fun searchPrepareAndPlay() {
        // 1) 先探 TLS：对照组是走主流 CA 的域名
        listOf(
            "https://music.163.com/",
            "https://y.qq.com/",
            "https://music.op.wiki/api/v1/platforms",
            "https://music.zr.wiki/",
        ).forEach(::probe)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = MHubProvider()
        val workDir = File(context.cacheDir, "mhub-probe").apply { mkdirs() }

        // 2) 搜索
        val songs = runBlocking { provider.searchSongs("", "七里香 周杰伦") }
        log("STEP search songs=${songs.size}")
        songs.take(5).forEach {
            log("  SONG id=${it.id} name=${it.name} artist=${it.artist} platform=${it.extra["platform"]}")
        }
        require(songs.isNotEmpty()) { "搜索没有返回歌曲" }
        val song = songs.first()

        // 3) 取流
        val stream = runBlocking { provider.playableStream("", song, workDir) }
        log("STEP stream url=${stream.url} ext=${stream.ext} bitrate=${stream.bitrate} preview=${stream.preview}")
        require(stream.url.isNotBlank()) { "没有拿到播放地址" }

        // 4) 裸 HTTP 读一段字节，确认地址本身可达
        readBytes(stream.url)

        // 5) 真播放
        play(context, stream.url)
    }

    private fun readBytes(url: String) {
        try {
            // 用 MHub 自己的客户端读：这条过了才说明"不验证证书"在接口/取流侧生效
            com.mytv.mymusic.data.native.MHubTls.client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-65535")
                    .header("User-Agent", "ExoPlayerLib/2.19.1 (Linux; Android 13)")
                    .build(),
            ).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                log("STEP http code=${response.code} bytes=${bytes.size} ctype=${response.header("Content-Type")}")
                require(response.code in 200..299 && bytes.isNotEmpty()) {
                    "音频地址不可读：HTTP ${response.code}"
                }
            }
        } catch (e: Exception) {
            log("STEP http FAIL ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun play(context: android.content.Context, url: String) {
        val playerRef = java.util.concurrent.atomic.AtomicReference<androidx.media3.exoplayer.ExoPlayer?>(null)
        val error = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val position = java.util.concurrent.atomic.AtomicLong(-1L)
        val state = java.util.concurrent.atomic.AtomicInteger(-1)
        val done = java.util.concurrent.CountDownLatch(1)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = androidx.media3.exoplayer.ExoPlayer.Builder(context)
                .setMediaSourceFactory(
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                        // 和 PlaybackService 里一样包一层：MHub 域名走 OkHttp（不验证证书），
                        // 其余走系统默认。用裸 DefaultDataSource 的话这条用例在 Android 6/7 上必挂。
                        com.mytv.mymusic.data.native.MHubTls.RoutingFactory(
                            fallback = androidx.media3.datasource.DefaultDataSource.Factory(context),
                        ),
                    ),
                )
                .build()
            playerRef.set(player)
            player.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    state.set(playbackState)
                    log("PLAY state=$playbackState (2=BUFFERING 3=READY 4=ENDED)")
                    if (playbackState == androidx.media3.common.Player.STATE_READY) {
                        position.set(player.currentPosition)
                        done.countDown()
                    }
                }

                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    error.set("${e.errorCodeName}: ${e.message}")
                    log("PLAY ERROR ${e.errorCodeName} code=${e.errorCode} msg=${e.message}")
                    e.cause?.let { log("  CAUSE ${it.javaClass.name}: ${it.message}") }
                    done.countDown()
                }
            })
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            player.prepare()
            player.play()
        }

        val finished = done.await(90, TimeUnit.SECONDS)
        log("STEP play finished=$finished state=${state.get()} pos=${position.get()} err=${error.get()}")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { playerRef.get()?.stop() }
            runCatching { playerRef.get()?.release() }
            playerRef.set(null)
        }
        require(finished && error.get() == null) { "播放失败：${error.get() ?: "超时未就绪"}" }
    }

    @Suppress("unused")
    private fun unused(song: Song) = song
}
