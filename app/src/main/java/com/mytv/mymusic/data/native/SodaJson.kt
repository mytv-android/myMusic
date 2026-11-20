package com.mytv.mymusic.data.native

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 汽水各接口回包的 JSON 取值小工具。名字带 `json` 前缀，免得和 SodaWelfare.kt 里
 * 同包的同名私有扩展撞车。
 */
internal object SodaJson {
    fun obj(root: JsonObject?, key: String): JsonObject? = root?.get(key) as? JsonObject

    fun array(root: JsonObject?, key: String): JsonArray? = root?.get(key) as? JsonArray

    fun string(root: JsonObject?, vararg path: String): String {
        var node: JsonObject? = root
        // 最后一段之前都在子对象里下钻
        for (key in path.dropLast(1)) node = node?.let { obj(it, key) }
        val element = node?.get(path.last()) ?: return ""
        val primitive = element as? JsonPrimitive ?: return ""
        return if (primitive.isString) primitive.content else ""
    }

    /** 数字 / 布尔按字符串取：服务端有的字段会给 `"123"` 这种字符串。 */
    fun text(root: JsonObject?, vararg path: String): String {
        var node: JsonObject? = root
        for (key in path.dropLast(1)) node = node?.let { obj(it, key) }
        val element = node?.get(path.last()) ?: return ""
        val primitive = element as? JsonPrimitive ?: return ""
        return primitive.content
    }

    fun int(root: JsonObject?, vararg path: String): Int? =
        text(root, *path).trim().toIntOrNull()

    fun long(root: JsonObject?, vararg path: String): Long? =
        text(root, *path).trim().toLongOrNull()

    fun bool(root: JsonObject?, vararg path: String): Boolean? {
        var node: JsonObject? = root
        for (key in path.dropLast(1)) node = node?.let { obj(it, key) }
        val primitive = node?.get(path.last()) as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        return when (primitive.content.trim().lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }
    }

    fun stringList(root: JsonObject?, key: String): List<String> =
        array(root, key)?.mapNotNull { element ->
            val primitive = element as? JsonPrimitive ?: return@mapNotNull null
            primitive.content.takeIf { it.isNotBlank() }
        }.orEmpty()

    fun objList(root: JsonObject?, key: String): List<JsonObject> =
        array(root, key)?.mapNotNull { it as? JsonObject }.orEmpty()

    /** 顶层 `status_code`；服务端省略这个字段时按 0（成功）算。 */
    fun statusCode(root: JsonObject?): Int = int(root, "status_code") ?: 0

    fun statusMessage(root: JsonObject?): String = string(root, "status_info", "status_msg").trim()

    /** 解析失败（不是 JSON、不是对象）时返回 null，调用方自己决定怎么报错。 */
    fun parse(raw: String): JsonObject? =
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

/** 方便在 SodaProvider 里直接 `jsonInt(root, "x")` 这么写。 */
internal fun jsonObj(root: JsonObject?, key: String): JsonObject? = SodaJson.obj(root, key)

internal fun jsonText(root: JsonObject?, vararg path: String): String = SodaJson.text(root, *path)

internal fun jsonInt(root: JsonObject?, vararg path: String): Int? = SodaJson.int(root, *path)

internal fun jsonLong(root: JsonObject?, vararg path: String): Long? = SodaJson.long(root, *path)

internal fun jsonBool(root: JsonObject?, vararg path: String): Boolean? = SodaJson.bool(root, *path)

internal fun jsonStringList(root: JsonObject?, key: String): List<String> = SodaJson.stringList(root, key)

internal fun jsonObjList(root: JsonObject?, key: String): List<JsonObject> = SodaJson.objList(root, key)

/** 长整型取不到的兜底：有的字段服务端给字符串、有的给数字。 */
internal fun JsonObject.jsonLongOrZero(key: String): Long = jsonLong(this, key) ?: 0L
