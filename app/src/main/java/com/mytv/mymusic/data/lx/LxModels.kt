package com.mytv.mymusic.data.lx

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.native.PlayableStream
import kotlinx.serialization.Serializable

/** 一个落雪脚本对某个内置平台声明的能力。键是落雪协议里的 kw/kg/tx/wy/mg/local。 */
@Serializable
data class LxCapability(
    val name: String = "",
    val actions: List<String> = emptyList(),
    val qualities: List<String> = emptyList(),
)

@Serializable
data class LxSourceRecord(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val homepage: String = "",
    val sourceUrl: String = "",
    val scriptHash: String = "",
    val enabled: Boolean = true,
    val importedAt: Long = 0,
    val updatedAt: Long = 0,
    val capabilities: Map<String, LxCapability> = emptyMap(),
)

data class LxScriptMetadata(
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val homepage: String,
)

data class LxUpdateNotice(
    val sourceId: String,
    val name: String,
    val log: String,
    val updateUrl: String = "",
)

data class LxResolvedStream(
    val record: LxSourceRecord,
    val quality: String,
    val stream: PlayableStream,
)

/** 播放层只依赖这个小接口，单元测试不用加载 Android 或 QuickJS。 */
interface LxPlayback {
    val generation: Long get() = 0
    fun supports(providerId: String): Boolean
    suspend fun resolve(song: Song, quality: String = "auto"): PlayableStream?
}

object LxPlatform {
    private val providerToLx = mapOf(
        "netease" to "wy",
        "qq" to "tx",
        "kugou" to "kg",
        "kuwo" to "kw",
        "migu" to "mg",
        "local" to "local",
    )

    val labels = linkedMapOf(
        "kw" to "酷我",
        "kg" to "酷狗",
        "tx" to "QQ",
        "wy" to "网易云",
        "mg" to "咪咕",
        "local" to "本地",
    )

    fun sourceKey(providerId: String): String? = providerToLx[providerId]
    fun providerId(sourceKey: String): String? = providerToLx.entries.firstOrNull { it.value == sourceKey }?.key
}

internal object LxScriptHeaders {
    private val fields = listOf("name", "description", "version", "author", "homepage")

    fun parse(script: String): LxScriptMetadata {
        require(script.isNotBlank()) { "脚本内容为空" }
        require(script.toByteArray(Charsets.UTF_8).size <= MAX_SCRIPT_BYTES) { "脚本超过 2 MB，无法导入" }
        val header = script.take(16_384)
        val values = fields.associateWith { field ->
            Regex(
                """(?im)^\s*(?:/\*+|\*/?|//)?\s*@$field\s+([^\r\n*]+)""",
            ).find(header)?.groupValues?.get(1)?.trim().orEmpty()
        }
        require(values["name"].orEmpty().isNotBlank()) { "缺少 @name，不是有效的落雪自定义音源脚本" }
        return LxScriptMetadata(
            name = values["name"].orEmpty().clean(120),
            description = values["description"].orEmpty().clean(500),
            version = values["version"].orEmpty().clean(60),
            author = values["author"].orEmpty().clean(120),
            homepage = values["homepage"].orEmpty().clean(2048),
        )
    }

    private fun String.clean(maxLength: Int): String = trim()
        .filterNot { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        .take(maxLength)
}

internal const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024
