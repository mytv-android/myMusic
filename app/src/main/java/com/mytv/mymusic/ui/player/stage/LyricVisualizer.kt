package com.mytv.mymusic.ui.player.stage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.LyricSubline
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.RHYTHM_BAND_COUNT
import com.mytv.mymusic.ui.LyricLine
import com.mytv.mymusic.ui.LyricWord
import com.mytv.mymusic.ui.ParsedLyrics
import kotlin.math.roundToInt

/*
 * 全屏歌词动画的接口，照 Folium v1.x 的 visualizers 扩展点搬成 Kotlin。
 * 每个模式是一个编进 APK 的类，不下载、不动态加载代码；来源模组和许可写在 THIRD_PARTY_NOTICES。
 *
 * 舞台铺满整页，文字排在 [StageInsets] 划出的安全区里（顶栏和控件之间），背景类效果可以铺到安全区外面。
 * 分层（由下往上）：宿主背景（或模式自己的 [LyricVisualizer.Background]）→ 模式的 [LyricVisualizer.Stage]
 * → 宿主副行（翻译或罗马音）→ 频谱图层 → 页面控件。
 *
 * 帧和时钟：宿主只跑一个帧循环，每帧更新 [StageClock] 和 [StageAudio] 再推进 [StageClock] 的帧计数。
 * 模式在绘制阶段（drawBehind / drawWithCache 的 onDraw / graphicsLayer）读 [StageClock.ms] 等值，就会每帧重画而不重组；
 * 在组合阶段只读低频变化的东西（[LyricStageContext.lineIndex]、设置）。
 */

/** 一个歌词动画模式。编进 APK，不动态加载。 */
interface LyricVisualizer {
    /** 存进设置的 id，比如 "rings"。 */
    val id: String

    /** 面板里显示的名字，比如 “52Hz”。 */
    val label: String

    /** 来源模组；经典是 null。 */
    val origin: ModOrigin?

    /** 面板里的顺序，小的在前。 */
    val order: Int

    /** 放进动画面板的设置项，宿主据此生成表单。 */
    val settings: List<LyricSetting> get() = emptyList()

    /** 原模组还有多少项没放进面板（保持原默认值），面板底部写明。 */
    val hiddenSettings: Int get() = 0

    /** false：宿主不画流光背景，改由 [Background] 画。 */
    val hostBackground: Boolean get() = true

    /** false：模式自己排翻译，宿主副行让位。 */
    val hostSubtitles: Boolean get() = true

    /** true 才去开律动分析（[LyricStageContext.audio] 才有值）。 */
    val wantsAudio: Boolean get() = false

    /** 没有同步歌词（纯音乐、电台）时也画；false 时宿主显示和经典一样的“这首歌没有歌词”。 */
    val drawsWithoutLyrics: Boolean get() = false

    /** 模式占了安全区底部（比如跑马灯带）时，宿主副行往上让多少。 */
    fun subtitleInset(ctx: LyricStageContext): Dp = 0.dp

    /** [hostBackground] 为 false 时铺满舞台的背景。 */
    @Composable
    fun Background(ctx: LyricStageContext, modifier: Modifier) {}

    /** 舞台本体，铺满整页；文字放在 [StageDisplay.safeRect] 里。 */
    @Composable
    fun Stage(ctx: LyricStageContext, modifier: Modifier)
}

/** 来源模组：名字、版本、作者、许可。 */
@Immutable
data class ModOrigin(val mod: String, val version: String, val author: String, val license: String)

/**
 * 设置表的一项。读的时候合并默认值、夹紧数值，写的时候丢掉未知键（见 [SettingValues]、[sanitizeSettings]），
 * 规则和 Folium 一样。要移植的模组没用到 text、color、secret，这三种不做。
 */
sealed interface LyricSetting {
    val key: String
    val label: String
    val summary: String?

    /** 数字：面板里是 −/+ 步进行（和“跟拍校准”同款）。 */
    class Number(
        override val key: String,
        override val label: String,
        val min: Float,
        val max: Float,
        val step: Float,
        val default: Float,
        val format: (Float) -> String = ::formatTimes,
        override val summary: String? = null,
    ) : LyricSetting {
        /** 夹到范围内并对齐刻度；按 4 位小数取整，0.4 + 3 × 0.1 存成 0.7 而不是 0.70000005。 */
        fun clamp(value: Float): Float {
            if (value.isNaN()) return default
            val snapped = min.toDouble() + ((value - min) / step).roundToInt() * step.toDouble()
            return (Math.round(snapped * 10_000.0) / 10_000.0).toFloat().coerceIn(min, max)
        }
    }

    /** 选项：面板里是连接按钮组。[options] 是（值, 显示名）。 */
    class Choice(
        override val key: String,
        override val label: String,
        val options: List<Pair<String, String>>,
        val default: String,
        override val summary: String? = null,
    ) : LyricSetting

    /** 开关：面板里是开关行。 */
    class Toggle(
        override val key: String,
        override val label: String,
        val default: Boolean,
        override val summary: String? = null,
    ) : LyricSetting
}

/** ×1.25 这种倍数写法，去掉多余的 0。 */
fun formatTimes(value: Float): String = "×" + trimNumber(value)

fun formatPercent(value: Float): String = "${(value * 100).roundToInt()}%"

fun formatSeconds(value: Float): String = trimNumber(value) + " 秒"

fun formatInt(value: Float): String = value.roundToInt().toString()

private fun trimNumber(value: Float): String {
    val rounded = (value * 100).roundToInt() / 100f
    return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString().trimEnd('0').trimEnd('.')
}

/** 已合并默认值、已夹紧的设置值。读不到或类型不对的项都按默认。 */
@Immutable
class SettingValues(private val schema: List<LyricSetting>, private val raw: Map<String, String>) {
    fun number(key: String): Float {
        val setting = schema.firstOrNull { it.key == key } as? LyricSetting.Number ?: error("没有数字设置 $key")
        return raw[key]?.toFloatOrNull()?.let(setting::clamp) ?: setting.default
    }

    fun toggle(key: String): Boolean {
        val setting = schema.firstOrNull { it.key == key } as? LyricSetting.Toggle ?: error("没有开关设置 $key")
        return raw[key]?.toBooleanStrictOrNull() ?: setting.default
    }

    fun choice(key: String): String {
        val setting = schema.firstOrNull { it.key == key } as? LyricSetting.Choice ?: error("没有选项设置 $key")
        return raw[key]?.takeIf { value -> setting.options.any { it.first == value } } ?: setting.default
    }

    /** 和默认值不同的项数（面板“恢复默认”是否可用）。 */
    val changed: Int get() = sanitizeSettings(schema, raw).size

    override fun equals(other: Any?): Boolean = other is SettingValues && other.schema === schema && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()
}

/** 写之前清理：丢掉未知键、类型不对的值和等于默认值的项，数字夹紧到合法刻度。 */
fun sanitizeSettings(schema: List<LyricSetting>, raw: Map<String, String>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (setting in schema) {
        val value = raw[setting.key] ?: continue
        when (setting) {
            is LyricSetting.Number -> value.toFloatOrNull()?.let(setting::clamp)
                ?.takeIf { it != setting.default }?.let { out[setting.key] = it.toString() }
            is LyricSetting.Toggle -> value.toBooleanStrictOrNull()
                ?.takeIf { it != setting.default }?.let { out[setting.key] = it.toString() }
            is LyricSetting.Choice -> value.takeIf { v -> setting.options.any { it.first == v } && v != setting.default }
                ?.let { out[setting.key] = it }
        }
    }
    return out
}

/**
 * 一句歌词。[endMs] 是句末：有逐字时间时取最后一个字的结束，没有时取下一句开始（最长 10 秒，长间奏中间算句间），
 * 最后一句没有逐字时按 5 秒算。两句之间 [LyricStageContext.lineIndex] 是 −1。
 */
@Immutable
class StageLine(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<LyricWord>,
    val translation: String,
    val romanization: String,
) {
    /** 这一句的时长（至少 1 毫秒）。 */
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1L)

    /** 宿主副行（或模式自己排的那一行）按 [subline] 放什么；没有罗马音时“罗马音”回退成翻译。 */
    fun subtitle(subline: LyricSubline): String = when (subline) {
        LyricSubline.Translation -> translation
        LyricSubline.Romanization -> romanization.ifBlank { translation }
        LyricSubline.Both -> listOf(romanization, translation).filter { it.isNotBlank() }.joinToString("\n")
    }
}

/**
 * 一个字（按码位拆，和 JS 的 Array.from 一样）什么时候唱。[space] 的字没有时间，起止都等于句首。
 */
@Immutable
class StageChar(val text: String, val startMs: Long, val endMs: Long, val space: Boolean) {
    /** [ms] 时这个字唱到哪儿，0..1。 */
    fun progress(ms: Long): Float =
        if (space) 0f else ((ms - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
}

/** 按码位拆字（emoji、增补平面的汉字算一个）。 */
fun splitCodePoints(text: String): List<String> {
    val out = ArrayList<String>(text.length)
    var i = 0
    while (i < text.length) {
        val count = Character.charCount(text.codePointAt(i))
        out += text.substring(i, (i + count).coerceAtMost(text.length))
        i += count
    }
    return out
}

/**
 * 每个字的起止时间。有逐字时间且字数（不算空白）对得上时，按逐字时间把每个词的时长均分给它的字；
 * 否则把句长的 [spread] 均分给每个字（52Hz 用 0.85，虹光用 1）。空白字 [StageChar.space] 为 true。
 */
fun StageLine.charTimings(spread: Float = 0.85f): List<StageChar> {
    val chars = splitCodePoints(text)
    val visible = chars.count { it.isNotBlank() }
    val fromWords = ArrayList<LongArray>(visible)
    for (word in words) {
        val wordChars = splitCodePoints(word.text).filter { it.isNotBlank() }
        if (wordChars.isEmpty()) continue
        val duration = (word.endMs - word.startMs).coerceAtLeast(1L)
        wordChars.forEachIndexed { i, _ ->
            fromWords += longArrayOf(word.startMs + duration * i / wordChars.size, word.startMs + duration * (i + 1) / wordChars.size)
        }
    }
    val useWords = visible > 0 && fromWords.size == visible
    val span = (durationMs * spread).toLong().coerceAtLeast(200L)
    var cursor = 0
    return chars.map { char ->
        if (char.isBlank()) return@map StageChar(char, startMs, startMs, space = true)
        val i = cursor++
        if (useWords) {
            StageChar(char, fromWords[i][0], fromWords[i][1], space = false)
        } else {
            StageChar(char, startMs + span * i / visible, startMs + span * (i + 1) / visible, space = false)
        }
    }
}

/** 把解析好的歌词换成舞台用的句子（只用同步歌词；没有就是空表）。 */
fun stageLines(lyrics: ParsedLyrics): List<StageLine> {
    if (!lyrics.synced) return emptyList()
    val lines = lyrics.lines
    return lines.mapIndexed { i, line -> stageLine(i, line, lines.getOrNull(i + 1)?.timeMs) }
}

internal fun stageLine(index: Int, line: LyricLine, nextStartMs: Long?): StageLine {
    val wordEnd = line.words.maxOfOrNull { it.endMs }
    val end = when {
        wordEnd != null -> if (nextStartMs != null) wordEnd.coerceAtMost(nextStartMs) else wordEnd
        nextStartMs != null -> nextStartMs.coerceAtMost(line.timeMs + MAX_PLAIN_LINE_MS)
        else -> line.timeMs + LAST_PLAIN_LINE_MS
    }
    return StageLine(
        index = index,
        startMs = line.timeMs,
        endMs = end.coerceAtLeast(line.timeMs + 1),
        text = line.text,
        words = line.words,
        translation = line.translation,
        romanization = line.romanization,
    )
}

/** [ms] 时正在唱的句子，句间和第一句之前是 −1。[hint] 是上一帧的结果，顺着往后找通常一两步就到。 */
fun stageLineIndex(lines: List<StageLine>, ms: Long, hint: Int = -1): Int {
    if (lines.isEmpty()) return -1
    var i = hint.coerceIn(0, lines.lastIndex)
    if (lines[i].startMs > ms) {
        while (i > 0 && lines[i].startMs > ms) i--
        if (lines[i].startMs > ms) return -1
    } else {
        while (i < lines.lastIndex && lines[i + 1].startMs <= ms) i++
    }
    return if (ms < lines[i].endMs) i else -1
}

private const val MAX_PLAIN_LINE_MS = 10_000L
private const val LAST_PLAIN_LINE_MS = 5_000L

/**
 * 舞台时钟。宿主在帧循环里写，模式在绘制阶段读：读 [ms]、[seconds] 就订阅了每一帧。
 * 减少动态效果时宿主不跑帧循环，[ms] 只在换句时更新一次，[seconds] 停住，模式应画成静帧（整句亮着、没有环境动画）。
 */
@Stable
class StageClock internal constructor() {
    private val msState = mutableLongStateOf(0L)
    private val animState = mutableLongStateOf(0L)
    private val dtState = mutableFloatStateOf(0f)

    /** 歌词时间（毫秒），播放中逐帧外推。 */
    val ms: Long get() = msState.longValue

    /** 动画时钟（秒）：只在播放时走，暂停、减少动态效果时停。环境动画（星星闪、唱片转）用它。 */
    val seconds: Float get() = animState.longValue / 1e9f

    /** 和上一帧的间隔（秒），最多 0.1；暂停时为 0。物理积分用。 */
    val dt: Float get() = dtState.floatValue

    internal fun publish(ms: Long, animNanos: Long, dt: Float) {
        msState.longValue = ms
        animState.longValue = animNanos
        dtState.floatValue = dt
    }
}

/**
 * 律动信号，只有 [LyricVisualizer.wantsAudio] 的模式和频谱图层打开时才有。宿主每帧按“此刻听到的位置”取分析帧再写这里，
 * 模式在绘制阶段读（先读一下 [StageClock.ms] 订阅帧）。字段都是普通变量，不触发重组。
 */
@Stable
class StageAudio internal constructor() {
    /** 平滑后的响度 0..1（folium 的 getPower）。 */
    var power = 0f
        internal set
    var bass = 0f
        internal set
    var mid = 0f
        internal set
    var treble = 0f
        internal set

    /** 低频起拍的包络：起拍时到 1，约 0.25 秒落回 0。 */
    var kick = 0f
        internal set

    /** 到现在为止的低频起拍数，只增不减；模式记下上次看到的值，变大就是来了新拍。 */
    var kickCount = 0
        internal set

    /** 拍子网格锁住时的速度（BPM），没锁上是 0。 */
    var bpm = 0f
        internal set

    /** 48 段频谱柱 0..1（folium 的 getBands / 频谱）。 */
    val bands = FloatArray(RHYTHM_BAND_COUNT)

    /** 电平最高处的频率（Hz）。 */
    var peakHz = 0f
        internal set

    /** 分析器在出数据（播放中、听到了声音）。 */
    var live = false
        internal set
}

/** 舞台配色：播放页的深色 Vibrant（PlayerTheme）加封面主色。 */
@Immutable
data class StageColors(
    val text: Color,
    val dim: Color,
    val accent: Color,
    val accentAlt: Color,
    val surface: Color,
    /** 封面量化出的 4 个主色，取不到时用配色补。 */
    val cover: List<Color>,
)

/**
 * 安全区：文字区相对舞台四边的内边距（px）。父布局在测量阶段写，模式在布局 / 绘制阶段读，不重组。
 */
@Stable
class StageInsets {
    var top by mutableFloatStateOf(0f)
    var bottom by mutableFloatStateOf(0f)
    var start by mutableFloatStateOf(0f)
    var end by mutableFloatStateOf(0f)

    fun set(top: Float, bottom: Float, start: Float, end: Float) {
        this.top = top
        this.bottom = bottom
        this.start = start
        this.end = end
    }

    /** 尺寸为 [size] 的舞台上的安全区。 */
    fun rect(size: Size): Rect = Rect(start, top, (size.width - end).coerceAtLeast(start), (size.height - bottom).coerceAtLeast(top))
}

/** 显示参数。 */
@Immutable
class StageDisplay(
    /** 正文基准字号（sp）：手机 30、桌面姿态 34、平板专注和电视 40；模式按自己的字号设置再乘。 */
    val textSp: Float,
    /** 减少动态效果（应用设置或系统“移除动画”）。 */
    val reduceMotion: Boolean,
    val tv: Boolean,
    /** 横向的舞台（平板、电视、横屏）。 */
    val wide: Boolean,
    val insets: StageInsets,
) {
    /** 安全区，在绘制阶段用。 */
    fun safeRect(size: Size): Rect = insets.rect(size)

    /** 电视上星星、年轮这类数量按六成画。 */
    val countScale: Float get() = if (tv) 0.6f else 1f
}

/**
 * 给模式的上下文。一首歌一份：换歌、换设置时宿主重建；当前句、时钟、律动都是可观察的状态。
 */
@Stable
class LyricStageContext internal constructor(
    /** 这首歌的句子，带句末时间。 */
    val lines: List<StageLine>,
    val song: Song,
    /** 由歌曲 key 算出的种子：随机效果同一首歌每次一样，也方便截图测试。 */
    val seed: Long,
    val clock: StageClock,
    /** 当前句，句间为 −1。 */
    val lineIndex: State<Int>,
    val paused: State<Boolean>,
    val colors: StageColors,
    val display: StageDisplay,
    val settings: SettingValues,
    /** [LyricVisualizer.wantsAudio] 的模式才有。 */
    val audio: StageAudio?,
    /** 歌词下面那一行放什么。 */
    val subline: LyricSubline,
) {
    /** 当前句（句间为 null）。组合阶段读会随换句重组。 */
    val currentLine: StageLine? get() = lines.getOrNull(lineIndex.value)
}

/**
 * 长句排版：最多 [maxLines] 行，放不下就把字号乘 0.88 再试，最多 6 次。返回最后一次的排版结果。
 * 一行放得下时结果的宽度是字宽而不是 [maxWidth]（[align] 只在多行时起作用），摆放时按结果自己的宽度居中。
 */
fun fitText(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    maxWidth: Int,
    maxLines: Int = 3,
    align: TextAlign = TextAlign.Center,
): TextLayoutResult {
    var current = style.copy(textAlign = align)
    var result = measurer.measure(text, current, constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(1)))
    repeat(6) {
        if (result.lineCount <= maxLines && !result.hasVisualOverflow) return result
        current = current.copy(fontSize = current.fontSize * 0.88f, lineHeight = if (current.lineHeight.isSp) current.lineHeight * 0.88f else current.lineHeight)
        result = measurer.measure(text, current, constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(1)))
    }
    return result
}

/**
 * 可复现的随机数（mulberry32）。原版模组用 Math.random，移植改成以歌曲为种子：同一首歌每次画面一样。
 */
class StageRandom(seed: Long) {
    private var state = (seed xor (seed ushr 32)).toInt()

    /** 0 ≤ x < 1（取高 24 位，Float 放得下，不会舍入成 1）。 */
    fun next(): Float {
        state += 0x6D2B79F5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)) ushr 8) / 16777216f
    }

    fun range(min: Float, max: Float): Float = min + (max - min) * next()

    fun int(until: Int): Int = (next() * until).toInt().coerceIn(0, (until - 1).coerceAtLeast(0))
}

/** 字符串的 32 位 FNV-1a 哈希，用来从歌名、歌词派生种子。 */
fun stableHash(text: String): Long {
    var h = 0x811C9DC5L
    for (c in text) {
        h = h xor c.code.toLong()
        h = (h * 0x01000193L) and 0xFFFFFFFFL
    }
    return h
}
