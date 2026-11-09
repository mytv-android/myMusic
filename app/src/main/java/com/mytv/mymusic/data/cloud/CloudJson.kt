package com.mytv.mymusic.data.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 云盘接口的 JSON 读法。不用 @Serializable DTO：网易的 size / maxSize 有时是字符串有时是数字，
 * QQ 的 matched / timestamp 也是，字段还可能是 null；逐个宽松地取比让反序列化整体失败更稳。
 */
internal object CloudJson {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun parseObject(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonElement?.primitive(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }

internal fun JsonObject.str(key: String): String = this[key].primitive()?.content.orEmpty()

internal fun JsonObject.long(key: String): Long {
    val value = this[key].primitive() ?: return 0
    return value.longOrNull ?: value.doubleOrNull?.toLong() ?: 0
}

internal fun JsonObject.int(key: String): Int = long(key).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

internal fun JsonObject.has(key: String): Boolean = this[key].primitive() != null

internal fun JsonObject.bool(key: String): Boolean {
    val value = this[key].primitive() ?: return false
    return value.booleanOrNull ?: (value.content == "1")
}

/** 依次取几个候选字段，第一个非空的字符串。 */
internal fun JsonObject.firstStr(vararg keys: String): String =
    keys.firstNotNullOfOrNull { key -> str(key).takeIf { it.isNotBlank() } }.orEmpty()

internal fun JsonObject.firstLong(vararg keys: String): Long =
    keys.firstNotNullOfOrNull { key -> long(key).takeIf { it != 0L } } ?: 0L
