package com.mytv.mymusic.data.lx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 不依赖网络；在有设备跑 androidTest 时验证真实 QuickJS、Promise 和 setTimeout 回桥。 */
@RunWith(AndroidJUnit4::class)
class LxScriptEngineDeviceTest {
    @Test
    fun promiseAndTimerResolveMusicUrl() = runBlocking {
        val script = """
            /**
             * @name Runtime test
             * @version 1
             */
            const { EVENT_NAMES, on, send } = globalThis.lx
            on(EVENT_NAMES.request, ({ source, action, info }) => {
              if (source !== 'wy' || action !== 'musicUrl') return Promise.reject('bad request')
              return new Promise(resolve => setTimeout(() => {
                resolve('https://cdn.example.com/' + info.musicInfo.songmid + '.mp3')
              }, 10))
            })
            send(EVENT_NAMES.inited, { sources: {
              wy: { name: '网易云', type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k'] }
            }})
        """.trimIndent()
        val engine = LxScriptEngine(
            appContext = InstrumentationRegistry.getInstrumentation().targetContext,
            sourceId = "lx_test",
            metadata = LxScriptHeaders.parse(script),
            rawScript = script,
            client = OkHttpClient(),
            onUpdateNotice = {},
        )
        try {
            val capabilities = engine.start()
            assertTrue(capabilities["wy"]?.qualities?.contains("320k") == true)
            val url = engine.musicUrl("wy", "320k", Song(id = "42", source = "netease"))
            assertEquals("https://cdn.example.com/42.mp3", url)
        } finally {
            engine.close()
        }
    }

    @Test
    fun infiniteLoopIsInterrupted() = runBlocking {
        val script = "/** @name Infinite loop */\nwhile (true) {}"
        val engine = LxScriptEngine(
            appContext = InstrumentationRegistry.getInstrumentation().targetContext,
            sourceId = "lx_loop",
            metadata = LxScriptHeaders.parse(script),
            rawScript = script,
            client = OkHttpClient(),
            onUpdateNotice = {},
            initTimeoutMs = 200,
        )
        val error = runCatching { engine.start() }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(engine.isClosed)
        engine.close()
    }
}
