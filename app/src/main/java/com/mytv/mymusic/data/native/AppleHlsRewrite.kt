package com.mytv.mymusic.data.native

import java.net.URI

/**
 * Apple Music 整曲媒体清单的本地重写。
 *
 * 为什么要重写（机制全部经 Python + pywidevine 实测，2026-11-06）：
 *  - 清单里有两条 Widevine EXT-X-KEY。Media3 解析时 initData 取**第一条**（默认 key），
 *    而 Apple 的授权端点**一次只回挑战里那个 kid 的一把密钥**（双 kid 挑战直接 -1021）。
 *    Android 的 CDM 一个会话只能装一把，于是必须让 initData 落到轮换 key 上。
 *  - 但默认 key 不是没用：它保的是开头的**预取静音段**（Apple 让播放器零授权等待起播用的，
 *    实测 3 首歌的开头 15–33s 全是数字静音，每段一个 moof，tfhd 里 sample_description_index
 *    不标或标 1 = 预取条目；标 2 = 正片）。这些段 CDM 解不了（会话里没有默认 key），必须连同
 *    一起丢掉——好在其内容是纯静音，丢了无损。
 *
 * 所以重写三件事：
 *  1. 删掉默认 key 那行，initData 落到轮换 key；
 *  2. 探测并丢弃开头的预取静音段（每个段抓头 2KB 看 tfhd）；
 *  3. 相对段地址补成绝对地址——清单落成本地文件给播放器读，原相对路径会失效。
 *
 * （正则一律用普通转义字符串写：raw string 结尾要带引号时得写四个引号，这里踩过
 * `"""…:"""` 多了一个引号变成内容、模式永不匹配、单测红得莫名其妙的坑。）
 */
internal object AppleHlsRewrite {

    private val KEY_LINE = Regex("^#EXT-X-KEY:")

    /** 绝对地址方案：这些开头的 URI 保持原样，不参与 resolve。 */
    private val ABSOLUTE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    private val URI_ATTR = Regex("URI=\"([^\"]+)\"")

    /** 段的一组行：#EXTINF 起（行号 [start, start+count)），#EXT-X-BYTERANGE 可选，URI 行收尾。 */
    private data class SegmentGroup(val start: Int, val count: Int, val url: String?, val offset: Long, val length: Long)

    fun rewrite(content: String, playlistUrl: String): String {
        val base = runCatching { URI(playlistUrl) }.getOrNull() ?: return content
        val separator = if (content.contains("\r\n")) "\r\n" else "\n"
        val endsWithNewline = content.endsWith("\n")
        var lines = content.split("\r\n", "\n")
        // 结尾换行会在 split 里多出一个空元素，先摘掉，最后统一补回，避免多拼一个换行
        if (endsWithNewline && lines.isNotEmpty() && lines.last().isEmpty()) lines = lines.dropLast(1)
        // 要整块删掉"含默认 Widevine key 的那一组 #EXT-X-KEY"，不能只删 Widevine 那一行：
        // HLS 的 key 对后面所有段生效，而 Apple 每组 key 都捆着 FairPlay + PlayReady + Widevine
        // 三行。只删 Widevine 会把 FairPlay/PlayReady 两行留下来，于是紧跟其后的第一个段
        // 看着"有 key 但没有 Widevine"——Media3 当它没加密，不建 DRM 会话、不要 license，
        // 直接把密文喂解码器，症状是**无声且没有任何错误**。
        val dropKeyBlock = BooleanArray(lines.size)
        var blockStart = -1
        fun closeKeyBlock(endExclusive: Int) {
            if (blockStart < 0) return
            if (lines.subList(blockStart, endExclusive).any { isDefaultWidevineKey(it) }) {
                for (i in blockStart until endExclusive) dropKeyBlock[i] = true
            }
            blockStart = -1
        }
        lines.forEachIndexed { index, line ->
            if (KEY_LINE.containsMatchIn(line)) {
                if (blockStart < 0) blockStart = index
            } else {
                closeKeyBlock(index)
            }
        }
        closeKeyBlock(lines.size)

        val out = StringBuilder(content.length)
        var first = true
        lines.forEachIndexed { index, line ->
            if (dropKeyBlock[index]) return@forEachIndexed
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
     * 丢弃开头的预取静音段。输入应是 [rewrite] 的输出（段地址已绝对化）。
     * [segmentHead] 按需抓某个段文件的头部字节（Range 请求），失败返回 null——
     * 探测失败就停在原地，后面的段一律保留，宁可少丢不可错丢。
     *
     * 返回裁剪后的清单；不识别出预取段就原样返回。
     */
    suspend fun trimPrefetchSegments(
        content: String,
        segmentHead: suspend (url: String, offset: Long, length: Int) -> ByteArray?,
    ): String {
        val separator = if (content.contains("\r\n")) "\r\n" else "\n"
        val endsWithNewline = content.endsWith("\n")
        var lines = content.split("\r\n", "\n")
        if (endsWithNewline && lines.isNotEmpty() && lines.last().isEmpty()) lines = lines.dropLast(1)

        val groups = segmentGroups(lines)
        if (groups.isEmpty()) return content
        // 没有 byterange 的清单没法只抓头部，直接不动
        if (groups.first().length <= 0) return content

        var drop = 0
        for (group in groups) {
            if (group.url.isNullOrEmpty() || group.length <= 0) break
            if (drop >= MAX_PREFETCH_PROBES) break
            val head = runCatching {
                segmentHead(group.url, group.offset, PROBE_BYTES.toLong().coerceAtMost(group.length).toInt())
            }.getOrNull() ?: break
            when (isPrefetchFragment(head)) {
                true -> drop++
                false -> break
                null -> break // 看不懂就停，保住余下所有段
            }
        }
        if (drop == 0) return content

        // 只剔被丢段自己的行区间，头部的 #EXTM3U 等一行都不能动
        val droppedRanges = groups.subList(0, drop).map { it.start until it.start + it.count }
        return buildString {
            var first = true
            lines.forEachIndexed { index, line ->
                if (droppedRanges.any { index in it }) return@forEachIndexed
                if (!first) append(separator)
                append(line)
                first = false
            }
            if (endsWithNewline) append(separator)
        }
    }

    /** 预取段的探测上限：静音前导实测最多 30 多秒（两三段），别为一个坏清单打太多请求。 */
    private const val MAX_PREFETCH_PROBES = 6
    private const val PROBE_BYTES = 2048

    /**
     * 段头部的 tfhd 说了它用哪个样本条目：
     *  - sample_description_index 没标（默认 1）或标 1 → 预取条目（默认 key 那个）；
     *  - 标 2 → 正片条目（轮换 key）。
     * 返回 null 表示看不懂（没找到 tfhd / 结构意外）。
     */
    internal fun isPrefetchFragment(head: ByteArray): Boolean? {
        val at = indexOfAscii(head, "tfhd".toByteArray()) ?: return null
        if (at + 16 > head.size) return null
        val flags = ((head[at + 4].toInt() and 0xFF) shl 24 or (head[at + 5].toInt() and 0xFF) shl 16 or
            (head[at + 6].toInt() and 0xFF) shl 8 or (head[at + 7].toInt() and 0xFF)) and 0xFFFFFF
        var pos = at + 12 // 跳过 ver/flags(4) + track_id(4)
        if (flags and 0x1 != 0) pos += 8 // base_data_offset
        if (flags and 0x2 == 0) return true // 没标条目 → 默认第 1 条 → 预取
        if (pos + 4 > head.size) return null
        val sdi = be32(head, pos)
        return when {
            sdi <= 1 -> true
            else -> false
        }
    }

    private fun indexOfAscii(data: ByteArray, pattern: ByteArray): Int? {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return null
    }

    private fun be32(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) shl 24 or (data[at + 1].toInt() and 0xFF) shl 16 or
            (data[at + 2].toInt() and 0xFF) shl 8 or (data[at + 3].toInt() and 0xFF)

    /**
     * 把行流切成段组：`#EXTINF` 起，到**段 URI 那一行**结束。
     *
     * 边界必须卡在 URI 行，不能卡在下一个 `#EXTINF`：Apple 的 `#EXT-X-KEY` 块写在上一段
     * URI **之后**、下一段 `#EXTINF` **之前**，按"下一个 EXTINF"划段会把属于后续段的 key
     * 块算进被丢弃的那一段里一起删掉，结果整份清单一把 key 都不剩。
     */
    private fun segmentGroups(lines: List<String>): List<SegmentGroup> {
        val groups = mutableListOf<SegmentGroup>()
        var start = -1
        var url: String? = null
        var offset = 0L
        var length = 0L
        fun close(endExclusive: Int) {
            if (start >= 0 && endExclusive > start) {
                groups += SegmentGroup(start, endExclusive - start, url, offset, length)
            }
            start = -1
            url = null; offset = 0L; length = 0L
        }
        for (index in lines.indices) {
            val line = lines[index]
            if (line.startsWith("#EXTINF")) {
                close(index)
                start = index
            } else if (start >= 0) {
                if (line.isNotEmpty() && !line.startsWith("#")) {
                    url = line
                    close(index + 1) // 段止于 URI 行；后面的 key/MAP 属于下一段的前导
                    continue
                }
                val range = BYTERANGE.find(line)
                if (range != null) {
                    length = range.groupValues[1].toLongOrNull() ?: 0L
                    offset = range.groupValues[2].toLongOrNull() ?: 0L
                }
            }
        }
        close(lines.size)
        return groups
    }

    private val BYTERANGE = Regex("""#EXT-X-BYTERANGE:(\d+)@(\d+)""")

    /**
     * 把 init 段里所有 `tenc` 的 default_KID 改写成授权用的 kid。
     *
     * Apple 的 tenc default_KID 恒为全零（enhancedHls 和 web 的 lossy 都一样，实抓确认），
     * 而授权回来的密钥带着 PSSH 里那个 kid。安卓的 CDM 按样本的 kid 严格查表，
     * 全零对不上任何密钥，于是 MediaCodec 报
     * `CryptoException: requested key has not been loaded: ERROR_DRM_NO_LICENSE`
     * （真机 6006）。Chromium 的 CDM 容忍全零，所以网页版照常播。
     *
     * 只改 16 字节、盒子长度不变，不会破坏任何偏移。tenc 布局：fourcc 之后
     * version(1) flags(3) reserved(1) crypt_skip(1) isProtected(1) ivSize(1) 然后 16 字节 KID。
     */
    internal fun patchInitSegmentKid(init: ByteArray, kid: ByteArray): ByteArray {
        if (kid.size != 16) return init
        val out = init.copyOf()
        val marker = "tenc".toByteArray()
        var i = 0
        while (i <= out.size - marker.size) {
            if (matchesAt(out, i, marker)) {
                val kidAt = i + marker.size + 8
                if (kidAt >= 0 && kidAt + 16 <= out.size) kid.copyInto(out, kidAt)
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
