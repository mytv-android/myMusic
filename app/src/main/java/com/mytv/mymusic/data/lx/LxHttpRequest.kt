package com.mytv.mymusic.data.lx

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

private val LX_BODY_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

/** 将 lx.request 的选项转换成 OkHttp；字符串 body 已经是正文，不做二次 JSON 编码。 */
internal fun buildLxRequest(url: String, options: JsonObject): Request {
    require(url.startsWith("https://") || url.startsWith("http://")) { "只允许 HTTP/HTTPS 请求" }
    require(url.length <= 8192) { "请求地址过长" }
    val method = options["method"]?.jsonPrimitive?.contentOrNull?.uppercase().orEmpty().ifBlank { "GET" }
    val headerMap = linkedMapOf(
        "Accept" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Linux; Android) myMusic/LXSource",
    )
    (options["headers"] as? JsonObject)?.forEach { (name, value) ->
        headerMap[name] = when (value) {
            is JsonPrimitive -> value.content
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(",")
            else -> value.toString()
        }
    }
    var body = options["body"]
    var contentType = headerMap.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
    val form = options["form"] as? JsonObject ?: options["formData"] as? JsonObject
    if (form != null && body == null && method in LX_BODY_METHODS) {
        body = JsonPrimitive(form.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode((value as? JsonPrimitive)?.content ?: value.toString(), "UTF-8")}"
        })
        contentType = "application/x-www-form-urlencoded"
        headerMap["Content-Type"] = contentType
    } else if (body != null && body !is JsonNull && method in LX_BODY_METHODS && contentType == null) {
        contentType = "application/json"
        headerMap["Content-Type"] = contentType
    }
    val requestBody = if (method in LX_BODY_METHODS && body != null && body !is JsonNull) {
        val text = if (body is JsonPrimitive) body.content else body.toString()
        text.toRequestBody(contentType?.toMediaTypeOrNull())
    } else {
        null
    }
    return Request.Builder().url(url).headers(headerMap.toHeaders()).method(method, requestBody).build()
}
