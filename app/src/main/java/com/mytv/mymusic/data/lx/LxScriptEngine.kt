package com.mytv.mymusic.data.lx

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import com.mytv.mymusic.lxruntime.LxQuickJsInterrupt
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 一个脚本一个 QuickJS 实例；QuickJS 的所有调用都固定在它自己的 HandlerThread 上。 */
internal class LxScriptEngine(
    private val appContext: Context,
    private val sourceId: String,
    private val metadata: LxScriptMetadata,
    private val rawScript: String,
    private val client: OkHttpClient,
    private val onUpdateNotice: (LxUpdateNotice) -> Unit,
    private val initTimeoutMs: Long = 30_000,
    private val requestTimeoutMs: Long = 25_000,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val thread = HandlerThread("LX-${metadata.name.take(20)}")
    private val handler: Handler
    private var context: QuickJSContext? = null
    private val key = UUID.randomUUID().toString()
    private val initialized = CompletableDeferred<Map<String, LxCapability>>()
    private val scriptRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val httpRequests = ConcurrentHashMap<String, Call>()
    private val closing = AtomicBoolean(false)
    @Volatile
    private var interruptHandle = 0L

    val isClosed: Boolean get() = closing.get()

    init {
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
            initialized.completeExceptionally(IllegalStateException(error.message ?: "脚本线程异常"))
            scriptRequests.values.forEach { it.completeExceptionally(error) }
        }
        thread.start()
        handler = Handler(thread.looper)
    }

    suspend fun start(): Map<String, LxCapability> {
        handler.post {
            if (closing.get()) {
                destroyOnThread()
                return@post
            }
            runCatching { createEnvironment() }.onFailure { error ->
                initialized.completeExceptionally(IllegalStateException(error.readableMessage()))
            }
        }
        return try {
            withTimeoutOrNull(initTimeoutMs) { initialized.await() }
                ?: throw IllegalStateException("初始化超时")
        } catch (error: Throwable) {
            close()
            if (error is CancellationException) throw error
            throw IllegalStateException("初始化失败：${error.readableMessage()}")
        }
    }

    suspend fun musicUrl(source: String, quality: String?, song: com.mytv.mymusic.data.Song): String {
        check(initialized.isCompleted && !closing.get()) { "音源脚本尚未就绪" }
        val requestKey = "my_${UUID.randomUUID()}"
        val result = CompletableDeferred<String>()
        scriptRequests[requestKey] = result
        val payload = buildJsonObject {
            put("requestKey", requestKey)
            put("data", LxProtocol.requestPayload(source, quality, song))
        }.toString()
        handler.post {
            if (!closing.get()) callJs("request", payload)
        }
        return try {
            withTimeoutOrNull(requestTimeoutMs) { result.await() }
                ?: run {
                    close()
                    throw IllegalStateException("脚本取流超时")
                }
        } finally {
            scriptRequests.remove(requestKey)
        }
    }

    fun close() {
        interruptHandle.takeIf { it != 0L }?.let(LxQuickJsInterrupt::interrupt)
        if (!closing.compareAndSet(false, true)) return
        if (Thread.currentThread() == thread) destroyOnThread() else handler.post { destroyOnThread() }
    }

    private fun createEnvironment() {
        loadQuickJs()
        val js = QuickJSContext.create()
        context = js
        installInterruptHandler(js)
        js.setMemoryLimit(MEMORY_LIMIT_BYTES)
        js.setMaxStackSize(512 * 1024)
        js.setConsole(object : QuickJSContext.Console {
            override fun log(info: String) { Log.d(TAG, "[${metadata.name}] $info") }
            override fun info(info: String) { Log.i(TAG, "[${metadata.name}] $info") }
            override fun warn(info: String) { Log.w(TAG, "[${metadata.name}] $info") }
            override fun error(info: String) { Log.e(TAG, "[${metadata.name}] $info") }
        })
        val global = js.globalObject
        global.setProperty("__lx_native_call__", JSCallFunction { args ->
            if (args.firstOrNull() == key) {
                val action = args.getOrNull(1) as? String
                val data = args.getOrNull(2) as? String
                if (action != null && data != null) handleNativeAction(action, data)
            }
            null
        })
        global.setProperty("__lx_native_call__set_timeout", JSCallFunction { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@JSCallFunction null
            val delay = (args.getOrNull(1) as? Number)?.toLong()?.coerceIn(0, 86_400_000L) ?: 0L
            handler.postDelayed({ if (!closing.get()) callJs("__set_timeout__", id.toString()) }, delay)
            null
        })
        global.setProperty("__lx_native_call__utils_str2b64", JSCallFunction { args ->
            runCatching {
                Base64.encodeToString((args[0] as String).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }.getOrDefault("")
        })
        global.setProperty("__lx_native_call__utils_b642buf", JSCallFunction { args ->
            runCatching {
                Base64.decode(args[0] as String, Base64.DEFAULT).joinToString(",", "[", "]") { it.toString() }
            }.getOrDefault("[]")
        })
        global.setProperty("__lx_native_call__utils_str2md5", JSCallFunction { args ->
            runCatching {
                val decoded = URLDecoder.decode(args[0] as String, "UTF-8")
                MessageDigest.getInstance("MD5").digest(decoded.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        })
        global.setProperty("__lx_native_call__utils_aes_encrypt", JSCallFunction { args ->
            runCatching {
                aesEncrypt(args[0] as String, args[1] as String, args[2] as String, args[3] as String)
            }.getOrDefault("")
        })
        global.setProperty("__lx_native_call__utils_rsa_encrypt", JSCallFunction { args ->
            runCatching {
                rsaEncrypt(args[0] as String, args[1] as String, args[2] as String)
            }.getOrDefault("")
        })
        val preload = appContext.assets.open("lx/user-api-preload.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        js.evaluate(preload, "lx-user-api-preload.js")
        val setup = global.getJSFunction("lx_setup")
        try {
            setup.call(
                key,
                sourceId,
                metadata.name,
                metadata.description,
                metadata.version,
                metadata.author,
                metadata.homepage,
                rawScript,
            )
        } finally {
            setup.release()
        }
        js.evaluate(rawScript, "${metadata.name}.js")
    }

    private fun installInterruptHandler(js: QuickJSContext) {
        val runtimeField = QuickJSContext::class.java.getDeclaredField("runtime")
        runtimeField.isAccessible = true
        val handle = LxQuickJsInterrupt.install(runtimeField.getLong(js))
        check(handle != 0L) { "QuickJS 执行中断不可用" }
        interruptHandle = handle
        if (closing.get()) LxQuickJsInterrupt.interrupt(handle)
    }

    private fun handleNativeAction(action: String, data: String) {
        if (closing.get()) return
        when (action) {
            "init" -> {
                runCatching { LxProtocol.parseInit(data) }.fold(
                    onSuccess = { if (!initialized.isCompleted) initialized.complete(it) },
                    onFailure = { if (!initialized.isCompleted) initialized.completeExceptionally(it) },
                )
            }
            "request" -> startHttpRequest(data)
            "cancelRequest" -> {
                val requestKey = runCatching { json.parseToJsonElement(data).jsonPrimitive.content }.getOrNull()
                if (requestKey != null) httpRequests.remove(requestKey)?.cancel()
            }
            "response" -> handleScriptResponse(data)
            "showUpdateAlert" -> {
                val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
                onUpdateNotice(
                    LxUpdateNotice(
                        sourceId = sourceId,
                        name = root["name"]?.jsonPrimitive?.contentOrNull ?: metadata.name,
                        log = root["log"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        updateUrl = root["updateUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    ),
                )
            }
        }
    }

    private fun handleScriptResponse(data: String) {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        val requestKey = root["requestKey"]?.jsonPrimitive?.contentOrNull ?: return
        val pending = scriptRequests.remove(requestKey) ?: return
        val success = root["status"]?.jsonPrimitive?.contentOrNull == "true"
        if (!success) {
            pending.completeExceptionally(IllegalStateException(root["errorMessage"]?.jsonPrimitive?.contentOrNull ?: "脚本取流失败"))
            return
        }
        val result = root["result"] as? JsonObject
        val url = (result?.get("data") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
        if (url.isNullOrBlank()) pending.completeExceptionally(IllegalStateException("脚本没有返回播放地址"))
        else pending.complete(url)
    }

    private fun startHttpRequest(data: String) {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        val requestKey = root["requestKey"]?.jsonPrimitive?.contentOrNull ?: return
        val url = root["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val options = root["options"] as? JsonObject ?: JsonObject(emptyMap())
        val request = try {
            buildLxRequest(url, options)
        } catch (error: Throwable) {
            postHttpResponse(requestKey, null, error.readableMessage())
            return
        }
        val timeout = options["timeout"]?.jsonPrimitive?.doubleOrNull?.toLong()?.coerceIn(1_000, 60_000) ?: 20_000L
        val requestClient = client.newBuilder().callTimeout(timeout, TimeUnit.MILLISECONDS).build()
        val call = requestClient.newCall(request)
        httpRequests[requestKey] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                httpRequests.remove(requestKey)
                postHttpResponse(requestKey, null, if (call.isCanceled()) "请求已取消" else e.message.orEmpty())
            }

            override fun onResponse(call: Call, response: Response) {
                httpRequests.remove(requestKey)
                response.use {
                    try {
                        val bytes = it.body.byteStream().readLimited(MAX_RESPONSE_BYTES + 1)
                        if (bytes.size > MAX_RESPONSE_BYTES) throw IOException("响应超过 4 MB 限制")
                        val binary = options["binary"]?.jsonPrimitive?.contentOrNull == "true"
                        val body = if (binary) {
                            buildJsonArray { bytes.forEach { byte -> add(byte.toInt()) } }
                        } else {
                            val text = bytes.toString(Charsets.UTF_8)
                            runCatching { json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
                        }
                        val result = buildJsonObject {
                            put("statusCode", it.code)
                            put("statusMessage", it.message)
                            put("url", it.request.url.toString())
                            put("headers", buildJsonObject {
                                it.headers.toMultimap().forEach { (name, values) -> put(name, values.joinToString(",")) }
                            })
                            put("body", body)
                        }
                        postHttpResponse(requestKey, result, null)
                    } catch (error: Throwable) {
                        postHttpResponse(requestKey, null, error.readableMessage())
                    }
                }
            }
        })
    }

    private fun postHttpResponse(requestKey: String, response: JsonObject?, error: String?) {
        val payload = buildJsonObject {
            put("requestKey", requestKey)
            put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
            put("response", response ?: JsonNull)
        }.toString()
        handler.post { if (!closing.get()) callJs("response", payload) }
    }

    private fun callJs(action: String, data: String) {
        val js = context ?: return
        try {
            val function = js.globalObject.getJSFunction("__lx_native__")
            try {
                val returned = function.call(key, action, data)
                if (returned is JSObject) returned.release()
            } finally {
                function.release()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "[${metadata.name}] $action: ${error.message}")
            if (!initialized.isCompleted) initialized.completeExceptionally(error)
        }
    }

    private fun destroyOnThread() {
        httpRequests.values.forEach { it.cancel() }
        httpRequests.clear()
        val error = IllegalStateException("音源已关闭")
        scriptRequests.values.forEach { it.completeExceptionally(error) }
        scriptRequests.clear()
        interruptHandle.takeIf { it != 0L }?.let { handle ->
            runCatching { LxQuickJsInterrupt.release(handle) }
            interruptHandle = 0L
        }
        runCatching { context?.destroy() }
        context = null
        thread.quitSafely()
    }

    private fun aesEncrypt(data: String, key: String, iv: String, mode: String): String {
        val cipher = Cipher.getInstance(mode)
        val secret = SecretKeySpec(Base64.decode(key, Base64.DEFAULT), "AES")
        if (iv.isEmpty()) {
            cipher.init(Cipher.ENCRYPT_MODE, secret)
        } else {
            val ivBytes = ByteArray(16)
            val source = Base64.decode(iv, Base64.DEFAULT)
            source.copyInto(ivBytes, endIndex = minOf(source.size, ivBytes.size))
            cipher.init(Cipher.ENCRYPT_MODE, secret, IvParameterSpec(ivBytes))
        }
        return Base64.encodeToString(cipher.doFinal(Base64.decode(data, Base64.DEFAULT)), Base64.NO_WRAP)
    }

    private fun rsaEncrypt(data: String, publicKey: String, padding: String): String {
        val spec = X509EncodedKeySpec(Base64.decode(publicKey.trim(), Base64.DEFAULT))
        val key = KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = Cipher.getInstance(padding)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.doFinal(Base64.decode(data, Base64.DEFAULT)), Base64.NO_WRAP)
    }

    private companion object {
        const val TAG = "LxScriptEngine"
        const val MEMORY_LIMIT_BYTES = 32 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

        @Volatile
        var loaderReady = false

        @Synchronized
        fun loadQuickJs() {
            if (loaderReady) return
            QuickJSLoader.init()
            loaderReady = true
        }
    }
}

internal fun Throwable.readableMessage(): String {
    val message = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    return message.lineSequence().firstOrNull().orEmpty().take(500)
}

private fun java.io.InputStream.readLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(limit, 32 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (total < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - total))
        if (count < 0) break
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}
