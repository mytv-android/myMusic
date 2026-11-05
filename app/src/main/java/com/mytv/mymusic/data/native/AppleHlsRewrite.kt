package com.mytv.mymusic.data.native

import java.net.URI

/**
 * Apple Music 整曲媒体清单的本地重写。
 *
 * 为什么要重写：清单里有两条 Widevine EXT-X-KEY——默认 key（盖住 init 段，实际不参与解密）
 * 和真正解音频段的轮换 key。Media3 解析清单时 DRM initData 取的是**第一条**（默认 key），
 * 于是 CDM 拿着默认 key 的 kid 去换授权；Apple 的 license 永远只回挑战里那个 kid 的密钥
 * （已用 pywidevine 实测双向验证），结果 CDM 手里没有音频段 tenc 所指 kid 的密钥，解出
 * 垃圾喂给解码器，症状是 ERROR_CODE_DECODING_FAILED(4003)。gamdl 没这个问题是因为它
 * 离线解密、手动按 tenc kid 配对，不经过 CDM 的严格 kid 匹配。
 *
 * 重写两件事：
 *  1. 删掉默认 key 那行（默认 key 只"覆盖"init 段，而 fMP4 的 moov 本来就是明文），
 *     让 initData 落到轮换 key 上；
 *  2. 相对段地址补成绝对地址——清单要落成本地文件给播放器读，原相对路径会失效。
 *
 * 抽成独立对象是为了能拿真清单做单测。
 *
 * （正则一律用普通转义字符串写：raw string 结尾要带引号时得写四个引号，这里踩过
 * `"""…:"""` 多了一个引号变成内容、模式永不匹配、单测红得莫名其妙的坑。）
 */
internal object AppleHlsRewrite {

    private val KEY_LINE = Regex("^#EXT-X-KEY:")

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
        lines.forEachIndexed { index, line ->
            val rewritten = when {
                line.isBlank() -> line
                isDefaultWidevineKey(line) -> null
                !line.startsWith("#") -> absolutize(line, base)
                line.startsWith("#EXT-X-MAP") -> absolutizeAttr(line, base)
                else -> line
            }
            if (rewritten != null) {
                if (index > 0) out.append(separator)
                out.append(rewritten)
            }
        }
        if (endsWithNewline) out.append(separator)
        return out.toString()
    }

    /** 默认 key 行：Widevine keyformat 且 URI 就是那把 init 段默认 key。internal 给单测钉住。 */
    internal fun isDefaultWidevineKey(line: String): Boolean {
        if (!KEY_LINE.containsMatchIn(line)) return false
        if (!line.contains("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")) return false
        val uri = URI_ATTR.find(line)?.groupValues?.getOrNull(1) ?: return false
        return uri == AppleHlsManifest.defaultWidevineKeyUri()
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
