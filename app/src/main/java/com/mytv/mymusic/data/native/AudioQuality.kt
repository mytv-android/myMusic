package com.mytv.mymusic.data.native

/**
 * 与平台无关的音质标识。平台把自己的档位（网易云的 jymaster、QQ 的 AI00、咪咕的 ZQ32……）
 * 映射到这套标识上，界面与换源逻辑就不必知道每个平台的私有档名。
 *
 * 移植自 music-lib 的 `model/quality.go`（AGPL-3.0）。
 */
enum class AudioQuality(val id: String) {
    Auto("auto"),
    Preview("preview"),
    Low("low"),
    Standard("standard"),
    Higher("higher"),
    High("high"),
    Lossless("lossless"),
    HiRes("hires"),
    Ultra("ultra"),
    Spatial("spatial"),
    Vivid("vivid"),
    Atmos("atmos"),
    Atmos51("atmos_51"),
    Master("master");

    /**
     * 设置页音质偏好按钮上的短标签。只列常用档：全景声、母带这些档是否可选取决于平台和账号，
     * 放在这里会让用户选一个大多数平台都给不了的值。
     */
    val preferenceLabel: String
        get() = when (this) {
            Auto -> "自动"
            Standard -> "标准"
            High -> "高品质"
            Lossless -> "无损"
            HiRes -> "Hi-Res"
            else -> id
        }

    companion object {
        /** 接受规范值与常见的码率别名；认不出来返回 null，由调用方按“不支持这个档”处理。 */
        fun normalize(value: String?): AudioQuality? = when (value?.trim()?.lowercase()) {
            null, "", "auto" -> Auto
            "preview", "sample", "trial", "try" -> Preview
            "low", "64", "64k", "lq" -> Low
            "standard", "128", "128k", "m500", "pq" -> Standard
            "higher", "192", "192k", "medium" -> Higher
            "high", "320", "320k", "m800", "hq", "exhigh", "highest" -> High
            "lossless", "flac", "f000" -> Lossless
            "hires", "hi_res", "hi-res", "24bit", "sq", "zq", "zq24" -> HiRes
            "ultra", "640", "640k", "ogg", "o801" -> Ultra
            "spatial", "sky", "jyeffect" -> Spatial
            "vivid" -> Vivid
            "atmos", "q000", "dolby" -> Atmos
            "atmos_51", "spatial_51", "5.1", "q001" -> Atmos51
            "master", "ai00", "zq32", "jymaster" -> Master
            else -> null
        }
    }
}

/** 一个可选的音质档位：界面直接显示 [label]，取流用 [value]。 */
data class AudioQualityOption(
    val value: AudioQuality,
    val label: String,
    val format: String = "",
    /** 标称码率（kbps），无损档没有标称值时为 0。 */
    val bitrate: Int = 0,
    /** 需要会员/订阅才能拿到。 */
    val vip: Boolean = false,
) {
    /** 换源 chip 与账号卡片上的一行摘要，例如“无损音质 · FLAC”。 */
    val summary: String
        get() = listOfNotNull(
            label,
            format.uppercase().takeIf { it.isNotBlank() },
            bitrate.takeIf { it > 0 }?.let { "$it kbps" },
        ).joinToString(" · ")
}

/**
 * 平台的音质阶梯：按从高到低排列，索引越小越高级。
 *
 * 两个判断都按阶梯索引而不是全局枚举序：
 * - 不同平台的高级档不一样（QQ 最高是臻品母带，酷狗最高是 Hi-Res），全局序会把
 *   一个平台没有的档算成“比它的最高档还高”，让「实际是否达标」永远为假。
 * - 会员能力是一个区间：拿到 320k 也意味着 192k/128k 可用，即使单曲的低档端点
 *   一时拒绝探测，[ladderFrom] 也把低档一起放回来。
 */
class AudioQualityLadder private constructor(val options: List<AudioQualityOption>) {

    fun optionFor(quality: AudioQuality): AudioQualityOption? =
        options.firstOrNull { it.value == quality }

    fun indexOf(quality: AudioQuality): Int = options.indexOfFirst { it.value == quality }

    fun supports(quality: AudioQuality): Boolean = indexOf(quality) >= 0

    /** 从 [from] 档开始往下的全部档位（含 [from]）；越界返回空表。 */
    fun ladderFrom(from: AudioQuality): List<AudioQualityOption> {
        val index = indexOf(from)
        if (index < 0) return emptyList()
        return options.subList(index, options.size).toList()
    }

    /** 实际档位是否达到了请求档位。请求 auto/preview 这类非阶梯档时按“不比较”处理。 */
    fun atLeast(actual: AudioQuality, requested: AudioQuality): Boolean {
        val requestedIndex = indexOf(requested)
        if (requestedIndex < 0) return true
        val actualIndex = indexOf(actual)
        // 同一档也算达标：请求无损、拿回无损，不能判成不达标
        return actualIndex in 0..requestedIndex
    }

    companion object {
        fun of(options: List<AudioQualityOption>): AudioQualityLadder {
            require(options.isNotEmpty()) { "audio quality ladder must not be empty" }
            require(options.map { it.value }.distinct().size == options.size) {
                "audio quality ladder has duplicate tiers"
            }
            return AudioQualityLadder(options.toList())
        }
    }
}

/**
 * 按平台返回的档位与接口实际上给的档位推断音质。先看平台档名，档名缺失时按
 * 格式 + 码率猜——这是 music-lib 里每个平台都有的 `xAudioQuality(bitrate, ext, level)`。
 */
internal fun inferAudioQuality(level: String, ext: String, bitrateKbps: Int, byLevel: Map<String, AudioQuality>): AudioQuality {
    byLevel[level.trim().lowercase()]?.let { return it }
    val format = ext.trim().lowercase()
    return when {
        format == "wav" && bitrateKbps >= 2000 -> AudioQuality.Master
        (format == "flac" || format == "wav") && bitrateKbps >= 1000 -> AudioQuality.HiRes
        format == "flac" || format == "wav" || bitrateKbps >= 700 -> AudioQuality.Lossless
        bitrateKbps >= 280 -> AudioQuality.High
        bitrateKbps >= 160 -> AudioQuality.Higher
        bitrateKbps >= 96 -> AudioQuality.Standard
        else -> AudioQuality.Low
    }
}
