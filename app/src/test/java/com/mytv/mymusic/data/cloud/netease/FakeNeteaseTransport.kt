package com.mytv.mymusic.data.cloud.netease

import com.mytv.mymusic.data.native.PlayableStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 记下每次调用的“加密前参数表”，按 path 回脚本化的响应。weapi 加密后看不出参数，
 * 测试就断言这里记下的 [calls]。
 */
internal class FakeNeteaseTransport : NeteaseCloudTransport {
    data class Call(val path: String, val params: JsonObject, val eapi: Boolean) {
        fun str(key: String): String = (params[key] as? JsonPrimitive)?.content.orEmpty()
    }

    val calls = CopyOnWriteArrayList<Call>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    /** path → (参数, 这是第几次调用这个 path，从 1 起) → 响应正文；抛 IOException 模拟超时 / 断线。 */
    val handlers = ConcurrentHashMap<String, (JsonObject, Int) -> String>()

    fun count(path: String): Int = calls.count { it.path == path }

    fun callsTo(path: String): List<Call> = calls.filter { it.path == path }

    fun on(path: String, handler: (JsonObject, Int) -> String) {
        handlers[path] = handler
    }

    fun respond(path: String, body: String) = on(path) { _, _ -> body }

    override suspend fun weapi(path: String, params: JsonObject, cookie: String): String = handle(path, params, false)

    override suspend fun eapi(path: String, params: JsonObject, cookie: String): String = handle(path, params, true)

    private fun handle(path: String, params: JsonObject, eapi: Boolean): String {
        calls += Call(path, params, eapi)
        val n = counts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
        val handler = handlers[path] ?: throw IOException("HTTP 404 Not Found: 测试没有准备 $path")
        return handler(params, n)
    }
}

internal object NoPlatform : NeteasePlatform {
    override suspend fun stream(cookie: String, songId: String): PlayableStream = throw IllegalStateException("no stream")

    override suspend fun lyrics(cookie: String, songId: String): String = ""
}
