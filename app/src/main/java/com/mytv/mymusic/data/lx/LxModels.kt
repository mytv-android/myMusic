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
    /** 落雪桌面版（Electron）插件：只取流，没有平台能力表。 */
    val desktop: Boolean = false,
    /**
     * 能力表的解析版本。客户端多认识一个平台（比如汽水 qs）时，老记录的能力表里就没有这个平台，
     * 启动时据此重探一次；与脚本版本无关，所以不能用 scriptHash 判断。
     */
    val capabilityVersion: Int = 0,
)

data class LxScriptMetadata(
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val homepage: String,
    /** 落雪桌面版（Electron）插件：CommonJS 模块，只支持取流。 */
    val desktop: Boolean = false,
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

data class LxPlaybackResult(
    val song: Song,
    val stream: PlayableStream,
)

/** 播放层只依赖这个小接口，单元测试不用加载 Android 或 QuickJS。 */
interface LxPlayback {
    val generation: Long get() = 0
    fun supports(providerId: String): Boolean
    suspend fun resolve(song: Song, quality: String = "auto"): PlayableStream?
    suspend fun resolve(songs: List<Song>, quality: String = "auto"): LxPlaybackResult? {
        for (song in songs) resolve(song, quality)?.let { return LxPlaybackResult(song, it) }
        return null
    }
}

object LxPlatform {
    const val PROVIDER_ID = "lx"
    const val PROVIDER_NAME = "洛雪"

    private val providerToLx = mapOf(
        "netease" to "wy",
        "qq" to "tx",
        "kugou" to "kg",
        "kuwo" to "kw",
        "migu" to "mg",
        "local" to "local",
        // 汽水音乐：落雪自定义源用 qs 代号，songId 就是汽水的 track id
        "soda" to "qs",
    )

    val labels = linkedMapOf(
        "kw" to "酷我",
        "kg" to "酷狗",
        "tx" to "QQ",
        "wy" to "网易云",
        "mg" to "咪咕",
        "qs" to "汽水",
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
        if (isDesktopModule(script)) return parseDesktop(script)
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

    /** 桌面版插件导出的是 CommonJS 模块，没声明 `globalThis.lx`，还依赖 Node 内置或三方包。 */
    fun isDesktopModule(script: String): Boolean =
        script.contains("module.exports") && (script.contains("require(\"") || script.contains("require('"))

    /**
     * 桌面版插件没有 `@name` 头（jsnz 那类只有 `platform` / `version` / `author` 字段），
     * 名字与版本从 exports 里取；取不到就用文件名兜底，不让导入因为缺注解直接失败。
     */
    private fun parseDesktop(script: String): LxScriptMetadata {
        fun field(name: String): String =
            Regex("""(?m)^\s*$name\s*:\s*["']([^"'\r\n]{1,200})["']""")
                .find(script)?.groupValues?.get(1)?.trim().orEmpty().clean(120)
        val name = field("platform").ifBlank { field("name") }
        require(name.isNotBlank()) { "缺少 @name，也不是能识别的落雪桌面版插件（没有导出 platform 名）" }
        return LxScriptMetadata(
            name = name,
            description = field("description"),
            version = field("version"),
            author = field("author"),
            homepage = field("srcUrl"),
            desktop = true,
        )
    }
}

internal const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024

/**
 * 能力表解析规则的版本：解析层多认识一个平台就加一。
 * 脚本没变，老记录的能力表却缺这个平台，只能靠版本号判断要不要重探。
 * v2 加了汽水 qs 的 Kotlin 侧映射；v3 补上 preload 白名单（v2 的历史记录探过但仍是空的）。
 */
internal const val LX_CAPABILITY_SCHEMA_VERSION = 3

/** 桌面插件没有平台能力表，永远不用重探。 */
internal fun LxSourceRecord.needsCapabilityRefresh(): Boolean =
    !desktop && capabilityVersion < LX_CAPABILITY_SCHEMA_VERSION
