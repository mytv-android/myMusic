package com.mytv.mymusic.data.native

import java.net.URI

/**
 * Apple Music 整曲媒体清单的本地重写。
 *
 * 为什么要把清单落成本地文件（机制全部经 Python + pywidevine 实测，2026-11-06）：
 *  - 清单里有两条 Widevine EXT-X-KEY，各保各的段：默认 key（公开的预取密钥）保开头
 *    30s 左右，轮换 key 保正片。开头那 30s **是真实音乐、不是静音**（实测解出来峰值
 *    0.98、RMS 0.26），所以不能丢——丢了就是"没有前奏、直接从第一句开始唱"。
 *  - 两把 key 各自对应 init 里一个样本条目（`enca` + `tenc`），tfhd 的
 *    sample_description_index 指认：不标或 1 = 预取条目，2 = 正片条目。
 *  - Apple 把两个 `tenc.default_KID` 都写成全零，安卓 CDM 按 kid 查表会报
 *    ERROR_DRM_NO_LICENSE（真机 6006），所以两个条目要分别改写成本段的 kid。
 *  - 授权端点按 challenge 里的 kid 发密钥（与 `uri` 字段无关，实测过），两个 CDM
 *    会话各拿各的 key 就行，前提是 session manager 开了 multiSession。
 *
 * 所以重写也就两件事：
 *  1. init 段的 tenc kid 按样本条目分别改写（见 [patchInitSegmentKids]）；
 *  2. 相对段地址补成绝对地址——清单落成本地文件给播放器读，原相对路径会失效。
 *
 * （正则一律用普通转义字符串写：raw string 结尾要带引号时得写四个引号，这里踩过
 * `"""…:"""` 多了一个引号变成内容、模式永不匹配、单测红得莫名其妙的坑。）
 */
internal object AppleHlsRewrite {

    /** 绝对地址方案：这些开头的 URI 保持原样，不参与 resolve。 */
    private val ABSOLUTE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    private val URI_ATTR = Regex("URI=\"([^\"]+)\"")

    fun rewrite(content: String, playlistUrl: String): String {
        val base = runCatching { URI(playlistUrl) }.getOrNull() ?: return content
        val separator = if (content.contains("\r\n")) "\r\n" else "\n"
        val endsWithNewline = content.endsWith("\n")
        var lines = content.split("\r\n", "\n")
        // 结尾换行会在 split 里多出一个空元素，先摘掉，最后统一补回，避免多拼一个换行
        if (endsWithNewline && lines.isNotEmpty() && lines.last().isEmpty()) lines = lines.dropLast(1)

        val out = StringBuilder(content.length)
        var first = true
        lines.forEach { line ->
            val rewritten = when {
                line.isBlank() -> line
                !line.startsWith("#") -> absolutize(line, base)
                line.startsWith("#EXT-X-MAP") -> absolutizeAttr(line, base)
                else -> line
            }
            if (!first) out.append(separator)
            out.append(rewritten)
            first = false
        }
        if (endsWithNewline) out.append(separator)
        return out.toString()
    }

    /**
     * 按 key 块把清单切成"默认 key 保的那段"和"轮换 key 保的那段"。
     *
     * 为什么要切开：Media3 的 HLS 每个媒体清单只按**一把** key 建 DRM 会话（实测：两把
     * key 放同一份清单时整轨都按后出现的那把解，开头 30s 用默认 key 加密的段解出来是
     * 静音，而且不报错），所以两段必须各交一份只有一把 key 的清单，播放侧再拼起来。
     *
     * 返回 intro = null 表示只有一把 key，整份清单就是 main。
     */
    internal data class Split(val intro: String?, val main: String)

    internal fun splitAtSecondKeyBlock(content: String): Split {
        val separator = if (content.contains("\r\n")) "\r\n" else "\n"
        val endsWithNewline = content.endsWith("\n")
        var lines = content.split("\r\n", "\n")
        if (endsWithNewline && lines.isNotEmpty() && lines.last().isEmpty()) lines = lines.dropLast(1)
        // 去掉结尾的 ENDLIST，切完统一补
        lines = lines.filterIndexed { index, line -> !(line.startsWith("#EXT-X-ENDLIST") && index == lines.lastIndex) }

        val blockStarts = lines.indices.filter { i ->
            lines[i].startsWith("#EXT-X-KEY") && (i == 0 || !lines[i - 1].startsWith("#EXT-X-KEY"))
        }
        val cut = blockStarts.getOrNull(1) ?: return Split(null, content)

        val headerEnd = blockStarts.first()
        val header = lines.subList(0, headerEnd)
        val mapLines = lines.subList(headerEnd, cut).filter { it.startsWith("#EXT-X-MAP") }
        fun join(parts: List<String>) = (parts + "#EXT-X-ENDLIST").joinToString(separator) +
            if (endsWithNewline) separator else ""

        val intro = join(header + lines.subList(headerEnd, cut))
        // main 也要 MAP：init 段对两条 key 各自的样本条目都有效
        val main = join(header + mapLines + lines.subList(cut, lines.size))
        return Split(intro, main)
    }

    /**
     * 把 init 段里每个样本条目 `tenc` 的 default_KID 改成该条目对应的 kid。
     *
     * Apple 的 tenc default_KID 恒为全零（enhancedHls 和 web 的 lossy 都一样，实抓确认），
     * 而授权回来的密钥带着 PSSH 里那个 kid。安卓的 CDM 按样本的 kid 严格查表，
     * 全零对不上任何密钥，于是 MediaCodec 报
     * `CryptoException: requested key has not been loaded: ERROR_DRM_NO_LICENSE`
     * （真机 6006）。Chromium 的 CDM 容忍全零，所以网页版照常播。
     *
     * 两个条目各归各的 key（预取条目 = 默认 key，正片条目 = 轮换 key），所以 tenc 按出现
     * 顺序对 [kids]；条目比 kid 多时沿用最后一个。只改 16 字节、盒子长度不变，不会破坏
     * 任何偏移。tenc 布局：fourcc 之后 version(1) flags(3) reserved(1) crypt_skip(1)
     * isProtected(1) ivSize(1) 然后 16 字节 KID。
     */
    internal fun patchInitSegmentKids(init: ByteArray, kids: List<ByteArray>): ByteArray {
        if (kids.isEmpty() || kids.any { it.size != 16 }) return init
        val out = init.copyOf()
        val marker = "tenc".toByteArray()
        var i = 0
        var seen = 0
        while (i <= out.size - marker.size) {
            if (matchesAt(out, i, marker)) {
                val kidAt = i + marker.size + 8
                if (kidAt >= 0 && kidAt + 16 <= out.size) {
                    kids[minOf(seen, kids.lastIndex)].copyInto(out, kidAt)
                }
                seen++
                i += marker.size
            } else {
                i++
            }
        }
        return out
    }

    private fun matchesAt(data: ByteArray, at: Int, pattern: ByteArray): Boolean {
        if (at + pattern.size > data.size) return false
        for (j in pattern.indices) if (data[at + j] != pattern[j]) return false
        return true
    }

    /** URI 属性重写：data:/skd:/http(s): 原样，相对的补绝对。 */
    private fun absolutizeAttr(line: String, base: URI): String =
        URI_ATTR.replace(line) { match ->
            val value = match.groupValues[1]
            if (ABSOLUTE.containsMatchIn(value)) match.value
            else "URI=\"${absolutize(value, base)}\""
        }

    private fun absolutize(value: String, base: URI): String {
        if (ABSOLUTE.containsMatchIn(value)) return value
        return runCatching { base.resolve(value.trim()).toString() }.getOrDefault(value)
    }
}
