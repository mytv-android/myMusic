package com.mytv.mymusic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mytv.mymusic.data.sync.LanPanelStarted
import com.mytv.mymusic.data.sync.TvInputPanelServer
import com.mytv.mymusic.data.sync.TvInputPanelSnapshot
import com.mytv.mymusic.data.sync.TvInputTarget
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * 在设备上跑「手机扫码后打开面板」的 HTTP 契约，用真实 NanoHTTPD 和真实打包资产。
 *
 * issue #33：扫码后网页打不开，只看到 {"error":"页面不存在"}。原因是面板只认
 * `/s/<令牌>/` 这一个形状，而手机浏览器和扫码器常把结尾的 `/` 吃掉；电视上显示的又只有
 * ip:端口，照着敲同样落到 404。这里把两种形状都钉住。
 */
@RunWith(AndroidJUnit4::class)
class TvInputPanelDeviceTest {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val servers = mutableListOf<TvInputPanelServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        client.connectionPool.evictAll()
    }

    @Test
    fun scannedUrlOpensEvenWhenBrowserDropsTrailingSlash() {
        val started = start()
        val origin = started.url.substringBefore("/s/")
        val token = started.url.substringAfter("/s/").trimEnd('/')

        get(origin + "/s/$token/").use { response ->
            assertEquals(200, response.code)
            assertTrue(response.body.string().contains("input.js"))
        }
        // 少一个斜线也要能打开，并且相对路径的资源跟着重定向后拿得到
        get(origin + "/s/$token").use { response ->
            assertEquals(200, response.code)
            assertTrue(response.body.string().contains("input.js"))
        }
        for (asset in listOf("input.css", "input.js", "theme.css", "api/session", "api/status")) {
            get(origin + "/s/$token/$asset").use { response ->
                assertEquals("GET /s/$token/$asset", 200, response.code)
            }
        }
    }

    @Test
    fun bareHostAddressTellsTheUserToScanInsteadOfRawJson() {
        val started = start()
        get(started.url.substringBefore("/s/")).use { response ->
            assertEquals(404, response.code)
            assertTrue(response.body.string().contains("二维码"))
        }
    }

    private fun start(): LanPanelStarted {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = TvInputPanelServer(
            assets = { name ->
                runCatching { context.assets.open("tv-input/$name").use { it.readBytes() } }.getOrNull()
            },
            snapshot = {
                TvInputPanelSnapshot(
                    target = TvInputTarget("lx-url", "自定义音源 · 脚本地址", hasValue = true),
                )
            },
        )
        servers += server
        return server.startPanel()
    }

    private fun get(url: String): okhttp3.Response =
        client.newCall(Request.Builder().url(url).get().build()).execute()
}
