package com.mytv.mymusic.data.native

/**
 * Apple Music 整曲 HLS 清单解析。
 *
 * `extendedAssetUrls.enhancedHls` 是主清单，下面挂一票音频档位的媒体清单；媒体清单里每条
 * `#EXT-X-KEY` 都是 FairPlay / PlayReady / Widevine 三重 SAMPLE-AES。安卓上只认 Widevine，
 * 所以这里只抽 Widevine 那条的 PSSH。
 *
 * 抽成独立对象是为了能单测——清单格式是抓来的，没有真实样例守着，Apple 一改格式就得有人报错。
 */
internal object AppleHlsManifest {

    /**
     * 档位优先级。AAC 256k 立体声放第一位：plain AAC，解码器必然有，也是 gamdl 的 `aac` 档。
     * ALAC / Atmos 故意不收——ALAC 在安卓上还得走 FairPlay wrapper，暂时没戏；Atmos 是 ec-3，
     * 解码器不全。
     */
    val AUDIO_GROUPS = listOf(
        "audio-stereo-256",
        "audio-stereo-128",
        "audio-HE-stereo-64",
        "audio-stereo-256-binaural",
        "audio-stereo-128-binaural",
        "audio-HE-stereo-64-binaural",
    )

    private val VARIANT_REGEX = Regex("""#EXT-X-STREAM-INF:([^\n]*)\r?\n([^\r\n#]+)""")
    private val AUDIO_REGEX = Regex("""AUDIO="([^"]+)"""")
    private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")

    /** Widevine 的 keyformat UUID。FairPlay / PlayReady 那两条在安卓上用不上，故意不抓。 */
    private val WIDEVINE_KEY_REGEX = Regex(
        """#EXT-X-KEY:METHOD=SAMPLE-AES,URI="([^"]+)",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"""",
    )

    /**
     * 覆盖 init segment 的那个 Widevine key，出现在 `#EXT-X-MAP` 之前。gamdl 的
     * `DRM_DEFAULT_KEY_MAPPING` 就是拿它当默认值跳过的——真正管音频数据的是它后面
     * 轮换出来的那条（`...AAABgSEAAAAABKkL9uYzIy...`）。
     *
     * 抄这个常量时踩过坑：gamdl 源码里它是跨两行的字符串字面量拼起来的，
     * `AAABgSE` 后面是 **11 个 A**，少数一个长度就从 99 变 98，跳过逻辑静默失效、
     * 测试还照样绿（因为 fixture 和常量抄的是同一个错值）。所以下面有单测盯着它的长度。
     */
    private const val WIDEVINE_DEFAULT_KEY_URI =
        "data:text/plain;base64,AAAAOHBzc2gAAAAA7e+LqXnWSs6jyCfc1R0h7QAAABgSEAAAAAAAAAAAczEvZTEgICBI88aJmwY="

    /**
     * 跳过用的默认 key 值本身。给测试盯长度用——抄漏一个 `A` 就会静默失效，
     * 而 fixture 和常量抄同一个错值时测试还是绿的。
     */
    internal fun defaultWidevineKeyUri(): String = WIDEVINE_DEFAULT_KEY_URI

    data class Variant(val group: String, val uri: String, val bitrateKbps: Int)

    fun variants(master: String): List<Variant> = VARIANT_REGEX.findAll(master).mapNotNull { match ->
        val attrs = match.groupValues[1]
        val uri = match.groupValues[2].trim()
        val bandwidth = BANDWIDTH_REGEX.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        // `/v1/play/assets` 回的清单是简版：只有 256/128/64 三个变体，没有 AUDIO="…"
        // 也没有 EXT-X-MEDIA，档位只能从带宽认。旧 enhancedHls 的组名照旧优先。
        val group = AUDIO_REGEX.find(attrs)?.groupValues?.get(1) ?: groupForBandwidth(bandwidth)
        if (group in AUDIO_GROUPS) Variant(group, uri, bandwidth / 1000) else null
    }.toList()

    /** 简版清单的档位判定：256k 实测带宽 ~289k、128k ~150k、HE-64 ~80k。 */
    private fun groupForBandwidth(bandwidth: Int): String = when {
        bandwidth >= 200_000 -> "audio-stereo-256"
        bandwidth >= 100_000 -> "audio-stereo-128"
        else -> "audio-HE-stereo-64"
    }

    /** 按 [AUDIO_GROUPS] 的顺序挑第一个；码率再高也不插队，省得挑到设备解不了的档。 */
    fun preferred(master: String): Variant? = variants(master).minByOrNull { AUDIO_GROUPS.indexOf(it.group) }

    /**
     * 从 `data:...;base64,<pssh box>` 里取出 WidevinePsshData 的 key_id（16 字节）。
     *
     * 用途是改写 init 段的 tenc：Apple 的 default_KID 恒为全零，而安卓 CDM 按 kid 严格
     * 查表，必须把它换成授权用的那个 kid 才能解出声音。
     */
    fun widevineKeyId(psshUri: String): ByteArray? {
        val base64 = psshUri.substringAfter("base64,", "")
        if (base64.isBlank()) return null
        val box = decodeBase64(base64) ?: return null
        if (box.size < 28) return null
        val payload = box.copyOfRange(28, box.size)
        // protobuf: field 2 (key_id), wire type 2 → 0x12 0x10 + 16 字节
        var i = 0
        while (i + 18 <= payload.size) {
            if (payload[i] == 0x12.toByte() && payload[i + 1] == 0x10.toByte()) {
                return payload.copyOfRange(i + 2, i + 18)
            }
            i++
        }
        return null
    }

    private const val B64 =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * 宽容的 base64 解码（忽略空白、允许省略 padding）。
     *
     * 不用 `android.util.Base64`：它在 JVM 单测里不可用，只能靠 returnDefaultValues 返回 null，
     * 会让这条逻辑永远测不到（Apple 的 PSSH 经常缺 padding，正是需要测的地方）。
     */
    internal fun decodeBase64(value: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream(value.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (ch in value) {
            if (ch == '=') break
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+' -> 62
                '/' -> 63
                ' ', '\n', '\r', '\t' -> continue
                else -> return null
            }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /**
     * 媒体清单里真正管音频数据的那个 Widevine key，返回**带 `data:text/plain;base64,` 前缀的完整串**
     * ——Apple 的授权接口要的就是这个形式（gamdl 直接把 `key.uri` 当 `uri` 传）。
     *
     * 跳过 [WIDEVINE_DEFAULT_KEY_URI] 那个坏的 init-segment key；万一 Apple 换了默认值、
     * 所有 key 都撞上，就退回最后一个（key 轮换里后来的覆盖前面的）。
     */
    fun widevinePssh(mediaPlaylist: String): String? =
        widevineKeyUris(mediaPlaylist).firstOrNull { it != WIDEVINE_DEFAULT_KEY_URI }
            ?: widevineKeyUris(mediaPlaylist).lastOrNull()

    /** 媒体清单里所有 Widevine key，按出现顺序去重：默认 key（预取段）在前，轮换 key（正片）在后。 */
    fun widevineKeyUris(mediaPlaylist: String): List<String> =
        WIDEVINE_KEY_REGEX.findAll(mediaPlaylist)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
}
