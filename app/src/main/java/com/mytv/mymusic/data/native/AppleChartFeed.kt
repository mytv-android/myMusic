package com.mytv.mymusic.data.native

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Apple Music 榜单（catalog `/v1/catalog/{sf}/charts`）响应解析。
 *
 * 这个端点和 marketingtools 的 RSS 数据同源，但快得多：RSS 实测能把 us/jp/kr 拖到 30s
 * 才回甚至直接 502/504，而 catalog 同一批商店都在 2s 内。而且它一个请求就带全了——
 * 封面、试听、`extend=extendedAssetUrls` 的整曲主清单，不必再补一次 lookup。
 *
 * 抽成独立对象是为了能单测：响应是抓来的，没有真实样例守着，Apple 一改结构就得有人报错。
 * （`results` 是按 type 分桶的 map，桶里又是榜单对象的数组，两层都不是直觉形状。）
 */
internal object AppleChartFeed {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Response(val results: Map<String, List<Block>> = emptyMap())

    @Serializable
    data class Block(
        /** `most-played` 之类；同一 type 下可能有多个榜单。 */
        val chart: String = "",
        val name: String = "",
        val data: List<Entry> = emptyList(),
    )

    /** 榜单里的单首歌。只留用到的字段，其余交给 [json] 的 ignoreUnknownKeys 丢掉。 */
    @Serializable
    data class Entry(
        val id: String = "",
        val attributes: SongAttributes = SongAttributes(),
    )

    /**
     * 字段都在 `attributes` 里包着一层，和普通 catalog 资源一样——拍平了会一个都取不到，
     * 而且因为全都有默认值，解析不报错，症状是榜单有条目但歌名/艺人/封面全是空的。
     */
    @Serializable
    data class SongAttributes(
        val name: String = "",
        val artistName: String = "",
        val albumName: String = "",
        val durationInMillis: Int = 0,
        val artwork: Artwork = Artwork(),
        val url: String = "",
        val previews: List<Preview> = emptyList(),
        val isrc: String = "",
        /** `extend=extendedAssetUrls` 才回；整曲是加密的，见 AppleProvider.playableStream。 */
        val extendedAssetUrls: AssetUrls = AssetUrls(),
    )

    @Serializable
    data class Artwork(val url: String = "")

    @Serializable
    data class Preview(val url: String = "")

    @Serializable
    data class AssetUrls(val enhancedHls: String = "")

    /**
     * 取出 [media]（`songs`）那一桶榜单，返回第一个有数据的。
     *
     * 返回 null 表示没数据——调用方要当错误处理，不能当空榜单：type 拼错时 Apple 返回
     * 200 且 `results` 里干脆没有这个 key，静默兜底会让榜单页看起来像"这个平台没榜"。
     */
    fun parse(body: String, media: String): Block? {
        val blocks = json.decodeFromString<Response>(body).results[media] ?: return null
        return blocks.firstOrNull { it.data.isNotEmpty() }
    }
}
