package com.mytv.mymusic.ui.poster

import com.mytv.mymusic.data.Song

/**
 * 按格子实际像素向上取档（240 / 480 / 800）请求封面，大卡不至于糊，小格也不用下原图。
 * 换档后地址不同，Coil 的缓存键自然按档位分开。认不出来的地址原样返回 [Song.cover]。
 */
internal fun Song.coverAt(px: Int): String = coverUrlAt(cover, px)

/** 封面档位：不小于 [px] 的最小一档，超过 800 也只取 800。 */
internal fun coverTier(px: Int): Int = COVER_TIERS.firstOrNull { it >= px } ?: COVER_TIERS.last()

/**
 * 把各平台的封面地址换成 [px] 对应的档位。各平台实际有哪些尺寸是 2026-10-29 拿真实封面逐档请求核对过的：
 * - 酷狗 `…/stdmusic/240/20230920/….jpg`（接口给的是 `{size}`，列表里换成了 240）：240 / 480 / 800 都有，
 *   歌单（soft/collection）、榜单（mcommon）、歌手头像（softhead）同样的 `/尺寸/日期/` 路径。
 * - QQ 音乐 `T002R300x300M000…`：只有 150 / 300 / 500 / 800（240、480、1000 都是 404），按 300 / 500 / 800 取。
 * - 网易云原始 picUrl 不带参数时是原图（1080–2000px、几 MB），加 `param=240y240` 这类参数，三档都有。
 * - 酷我 `/star/albumcover/500/…`：120–1000 各档都有。
 * - JOOX `image.joox.com/JOOXcover/0/<id>/300`：只有 100 / 300 / 500 / 640 / 1000（240、480、800 返回 400），按 300 / 500 / 1000 取。
 * - 汽水（抖音图床）`~c5_375x375.jpg` 和 `~tplv-…-resize:960:960.png` 任意尺寸都能出；
 *   后一种 png 比同尺寸 jpg 大七八倍（800px 约 1.1MB 对 120KB），换档时一并改成 jpg。
 * - Apple Music `…/600x600bb.jpg` 任意尺寸都能出（bb 是按边框等比缩）。
 * 其他平台（咪咕、千千、B 站、5sing、Jamendo）的地址没有统一的尺寸段，原样返回。
 */
internal fun coverUrlAt(url: String, px: Int): String {
    if (url.isBlank()) return url
    val tier = coverTier(px)
    // 酷狗接口原样的占位符：三档都有，直接填
    if (url.contains(KUGOU_PLACEHOLDER) && KUGOU_HOST.containsMatchIn(url)) return url.replace(KUGOU_PLACEHOLDER, tier.toString())
    QQ_SIZE.find(url)?.let { match ->
        val size = when (tier) {
            240 -> 300
            480 -> 500
            else -> 800
        }
        return url.replaceRange(match.groups[1]!!.range, "${size}x$size")
    }
    if (KUGOU_HOST.containsMatchIn(url)) {
        val match = KUGOU_SIZE.find(url) ?: return url
        return url.replaceRange(match.groups[1]!!.range, tier.toString())
    }
    KUWO_SIZE.find(url)?.let { match -> return url.replaceRange(match.groups[1]!!.range, tier.toString()) }
    if (NETEASE_HOST.containsMatchIn(url)) return neteaseAt(url, tier)
    JOOX_SIZE.find(url)?.let { match ->
        val size = when (tier) {
            240 -> 300
            480 -> 500
            else -> 1000
        }
        return url.replaceRange(match.groups[1]!!.range, size.toString())
    }
    SODA_TPLV.find(url)?.let { match ->
        return url.replaceRange(match.range, "${match.groupValues[1]}-resize:$tier:$tier.jpg")
    }
    SODA_C5.find(url)?.let { match -> return url.replaceRange(match.groups[1]!!.range, "${tier}x$tier") }
    APPLE_SIZE.find(url)?.let { match -> return url.replaceRange(match.groups[1]!!.range, "${tier}x$tier") }
    return url
}

/** 网易云：已有 param 就换掉，没有就加在查询串里，别的参数和锚点不动。 */
private fun neteaseAt(url: String, tier: Int): String {
    val param = "param=${tier}y$tier"
    NETEASE_PARAM.find(url)?.let { match -> return url.replaceRange(match.groups[1]!!.range, param) }
    val hash = url.indexOf('#')
    val head = if (hash >= 0) url.substring(0, hash) else url
    val tail = if (hash >= 0) url.substring(hash) else ""
    val joiner = when {
        !head.contains('?') -> "?"
        head.endsWith('?') || head.endsWith('&') -> ""
        else -> "&"
    }
    return head + joiner + param + tail
}

private val COVER_TIERS = intArrayOf(240, 480, 800)

private const val KUGOU_PLACEHOLDER = "{size}"
private val KUGOU_HOST = Regex("""^https?://[^/]*(kugou\.com|kgimg\.com)/""", RegexOption.IGNORE_CASE)

/** 酷狗图片路径里紧跟 8 位日期的那一段就是尺寸：`/stdmusic/240/20230920/`。 */
private val KUGOU_SIZE = Regex("""/(\d{2,4})/\d{8}/""")
private val QQ_SIZE = Regex("""y\.gtimg\.cn/music/photo_new/T002R(\d+x\d+)M000""", RegexOption.IGNORE_CASE)
private val KUWO_SIZE = Regex("""kuwo\.cn/star/albumcover/(\d+)/""", RegexOption.IGNORE_CASE)
private val NETEASE_HOST = Regex("""^https?://[^/]*music\.126\.net/""", RegexOption.IGNORE_CASE)
private val NETEASE_PARAM = Regex("""[?&](param=\d+y\d+)""")
private val JOOX_SIZE = Regex("""image\.joox\.com/JOOXcover/.+/(\d+)$""", RegexOption.IGNORE_CASE)

/** 汽水的两种模板：`~tplv-b829550vbb-resize:960:960.png` 和 `~c5_375x375.jpg`。 */
private val SODA_TPLV = Regex("""(~tplv-[\w-]+?)-resize:\d+:\d+\.(png|jpe?g|webp)$""")
private val SODA_C5 = Regex("""~c5_(\d+x\d+)\.(png|jpe?g|webp)$""")
private val APPLE_SIZE = Regex("""mzstatic\.com/image/thumb/.+/(\d+x\d+)bb\.(jpg|png|webp)$""", RegexOption.IGNORE_CASE)
