package com.mytv.mymusic.ui.player.stage.modes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mytv.mymusic.ui.player.stage.StageLine
import com.mytv.mymusic.ui.player.stage.StageRandom
import com.mytv.mymusic.ui.player.stage.charTimings
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * 星潮·银河的纯逻辑：星星生灭调度、流星生成与补位、字的随机落点、字的生命周期、标点 / 拉丁判定、逐字时间修正。
 * 不碰 android.*，单元测试直接喂数。长度单位是屏幕像素；原模组的 px 数值乘 unit 后再传进来。
 */

/** 面板里的 8 项设置；其余 7 项（变化速度、辉光、星星大小、光罩深浅、光圈大小、缩放时间、流星大小）按原默认值写死。 */
internal class StardriftConfig(
    val density: Float,
    val fontScale: Float,
    /** 字唱完后继续停留的秒数。 */
    val hold: Float,
    /** 流星数量（0–3，可以是小数，见 [stardriftMeteorRate]）。 */
    val meteor: Float,
    val ring: Boolean,
    val ringLatin: Boolean,
    val punctHide: Boolean,
    val pulse: Boolean,
) {
    /** 从光点放大成字的时长：缩放时间（原默认 0.5 秒）不超过停留时间，停留为 0 时按 0.2 秒。 */
    val appearS: Float = min(max(0.2f, STARDRIFT_ZOOM_S), if (hold > 0f) hold else 0.2f)

    /** 变色缩小消失的时长：缩放时间的两倍。 */
    val fadeS: Float = appearS * 2f
}

/** 原模组“字缩放时间”的默认值（秒）。 */
internal const val STARDRIFT_ZOOM_S = 0.5f

/** 原模组的基准字号（px）。 */
internal const val STARDRIFT_BASE_FONT = 64f

/**
 * 稳定时屏幕上有多少颗星。原版每帧以 min(0.5, 密度/180) 的概率补一颗（上限 1.6 倍密度），
 * 少于 max(10, 0.6 倍密度) 时补满；星的平均寿命 3.2 秒，按 60 帧算出稳定数目。
 */
internal fun stardriftStarCount(density: Float, countScale: Float = 1f): Int {
    val perSecond = min(0.5f, max(4f, density) / 180f) * 60f
    val steady = min(density * 1.6f, perSecond * MEAN_STAR_LIFE_S)
    val alive = max(max(10f, density * 0.6f), steady)
    return (alive * countScale).roundToInt().coerceAtLeast(1)
}

/** 星星的一生里的亮度包络：前 15% 淡入，后 15% 淡出。 */
internal fun stardriftStarEnvelope(life: Float): Float = when {
    life < 0f || life >= 1f -> 0f
    life < 0.15f -> life / 0.15f
    life > 0.85f -> (1f - life) / 0.15f
    else -> 1f
}

/**
 * 星海：[count] 个星位，每个星位上一颗星按自己的寿命（1.6–4.8 秒）生灭，灭了马上在新位置以新的大小、闪烁节奏和颜色重生。
 * 第 k 轮的参数由（种子、星位、k）算出，不存历史：同一首歌同一时刻的星海每次一样，暂停、跳转都不用重建。
 * 约四成生在左上到右下的斜向银河带里，其余散落全屏。尺寸是屏幕像素，[unit] 是原模组 1px 折成的屏幕像素。
 */
internal class StardriftSky(private val seed: Long, val count: Int, val width: Float, val height: Float, private val unit: Float) {
    private val lifetimes = FloatArray(count)
    private val offsets = FloatArray(count)
    private val cycles = LongArray(count) { Long.MIN_VALUE }
    val x = FloatArray(count)
    val y = FloatArray(count)

    /** 基础半径（屏幕像素）：大部分小、少量大。 */
    val baseSize = FloatArray(count)

    /** 闪烁快慢。 */
    val speed = FloatArray(count)
    val phase = FloatArray(count)

    /** 颜色：小于 0.55 文字色、小于 0.85 强调色，其余第二强调色。 */
    val tint = FloatArray(count)

    init {
        val r = StageRandom(seed xor SKY_SALT)
        for (i in 0 until count) {
            lifetimes[i] = 1.6f + r.next() * 3.2f
            offsets[i] = r.next() * lifetimes[i]
        }
    }

    /** 第 [i] 颗星在 [t] 秒时走到一生的哪儿（0..1），顺手把这一轮的参数备好。 */
    fun life(i: Int, t: Float): Float {
        val local = t + offsets[i]
        val cycle = floor(local / lifetimes[i]).toLong()
        if (cycle != cycles[i]) {
            cycles[i] = cycle
            respawn(i, cycle)
        }
        return ((local - cycle * lifetimes[i]) / lifetimes[i]).coerceIn(0f, 0.9999f)
    }

    private fun respawn(i: Int, cycle: Long) {
        val r = StageRandom(seed xor (i.toLong() * STAR_MIX) xor (cycle * CYCLE_MIX))
        if (r.next() < 0.4f) {
            val t = r.next()
            x[i] = width * (0.15f + 0.7f * t) + (r.next() - 0.5f) * width * 0.3f
            y[i] = height * (0.25f + 0.5f * t) + (r.next() - 0.5f) * height * 0.26f
        } else {
            x[i] = r.next() * width
            y[i] = r.next() * height
        }
        baseSize[i] = (0.6f + r.next().pow(1.8f) * 2.4f) * unit
        speed[i] = 0.25f + r.next()
        phase[i] = r.next() * TAU
        tint[i] = r.next()
    }

    private companion object {
        const val SKY_SALT = 0x5EED_57A2L
        const val STAR_MIX = -0x61C8864680B583EBL
        const val CYCLE_MIX = 0x2545F4914F6CDD1DL
    }
}

/** 一颗流星：头部位置、速度（像素/秒）、拖尾长度、线宽、颜色。 */
internal class StardriftMeteor(var x: Float, var y: Float, val vx: Float, val vy: Float, val len: Float, val width: Float, val tint: Float)

/**
 * 从屏幕四边之外随机一处滑入，朝屏幕中心方向再偏 ±0.45 弧度，速度 420–780px/s、拖尾 110–200px、线宽 1.6–3px（原模组数值乘 [unit]）。
 */
internal fun spawnStardriftMeteor(r: StageRandom, width: Float, height: Float, unit: Float): StardriftMeteor {
    val side = r.int(4)
    val x: Float
    val y: Float
    when (side) {
        0 -> {
            x = r.next() * (width + 240f * unit) - 120f * unit
            y = -40f * unit - r.next() * 90f * unit
        }
        1 -> {
            x = width + 40f * unit + r.next() * 90f * unit
            y = r.next() * height
        }
        2 -> {
            x = r.next() * (width + 240f * unit) - 120f * unit
            y = height + 40f * unit + r.next() * 90f * unit
        }
        else -> {
            x = -40f * unit - r.next() * 90f * unit
            y = r.next() * height
        }
    }
    val angle = atan2(height / 2f - y, width / 2f - x) + (r.next() - 0.5f) * 0.9f
    val speed = (420f + r.next() * 360f) * unit
    return StardriftMeteor(
        x = x,
        y = y,
        vx = cos(angle) * speed,
        vy = sin(angle) * speed,
        len = (110f + r.next() * 90f) * unit,
        width = (1.6f + r.next() * 1.4f) * unit,
        tint = r.next(),
    )
}

/** 同屏最多几颗流星：数量设置向上取整，最多 3。 */
internal fun stardriftMeteorCap(amount: Float): Int = ceil(amount - 1e-3f).toInt().coerceIn(0, 3)

/**
 * 补流星的速率（每秒，泊松）。原版数量是整数：同屏少于设定数就以每帧 0.2 的概率补（约每秒 13.4 次，几乎立刻补上）。
 * 面板按 0.25 步进，小数部分按“有流星的时间占比”算：整数部分的流星照原版立刻补；最后那颗按占比 f 补，
 * 平均空档是一次穿屏时间 [crossSeconds] × (1 − f) / f，屏上有它的时间正好约占 f。
 */
internal fun stardriftMeteorRate(amount: Float, active: Int, crossSeconds: Float): Float {
    if (active >= stardriftMeteorCap(amount)) return 0f
    val whole = floor(amount + 1e-3f).toInt()
    if (active < whole) return STARDRIFT_METEOR_REFILL
    val frac = (amount - whole).coerceIn(0f, 0.999f)
    if (frac <= 1e-3f) return 0f
    return frac / (1f - frac) / crossSeconds.coerceAtLeast(0.1f)
}

/** 原版每帧 0.2 概率补流星，折成 60 帧下的每秒速率：−ln(0.8) × 60。 */
internal const val STARDRIFT_METEOR_REFILL = 13.39f

/** 流星池：按帧间隔移动、出界回收、按 [stardriftMeteorRate] 补位。暂停（dt = 0）时不动也不补。 */
internal class StardriftMeteors(seed: Long) {
    val meteors = ArrayList<StardriftMeteor>(3)
    private val random = StageRandom(seed xor METEOR_SALT)

    fun advance(dt: Float, amount: Float, width: Float, height: Float, unit: Float) {
        if (dt <= 0f) return
        val margin = 300f * unit
        meteors.removeAll { it.x < -margin || it.x > width + margin || it.y < -margin || it.y > height + margin }
        val cross = (hypot(width, height) + 2f * margin) / (600f * unit)
        val rate = stardriftMeteorRate(amount, meteors.size, cross)
        if (rate > 0f && random.next() < 1f - exp(-rate * dt)) meteors += spawnStardriftMeteor(random, width, height, unit)
        for (m in meteors) {
            m.x += m.vx * dt
            m.y += m.vy * dt
        }
    }

    private companion object {
        const val METEOR_SALT = 0x3E7E_0A11L
    }
}

/** 静帧里那颗定格的流星：从屏外生出，走到离屏幕中心还剩四成路程的地方。 */
internal fun frozenStardriftMeteor(seed: Long, width: Float, height: Float, unit: Float): StardriftMeteor {
    val m = spawnStardriftMeteor(StageRandom(seed xor FROZEN_METEOR_SALT), width, height, unit)
    val speed = hypot(m.vx, m.vy).coerceAtLeast(1f)
    val t = 0.6f * hypot(width / 2f - m.x, height / 2f - m.y) / speed
    m.x += m.vx * t
    m.y += m.vy * t
    return m
}

private const val FROZEN_METEOR_SALT = 0x0F0F_5EEDL

/**
 * 屏蔽标点的白名单（原模组 PUNCT_SET）：只认真正的句读标点，不用 Unicode 全标点类，免得把间隔号之外的符号型字符一起吞掉。
 */
private val PUNCT = setOf(
    "，", "。", "、", "！", "？", "；", "：", "“", "”", "‘", "’", "（", "）",
    "【", "】", "《", "》", "〈", "〉", "「", "」", "『", "』", "…", "—", "–", "·", "・", "‥",
    ",", ".", "!", "?", ";", ":", "(", ")", "[", "]", "{", "}", "\"", "'", "-", "~", "`",
    "@", "#", "$", "%", "^", "&", "*", "+", "=", "_", "/", "\\", "|", "<", ">",
)

internal fun isStardriftPunct(char: String): Boolean = char in PUNCT

/** “英文不亮圈”只管 ASCII 字母和数字（原模组 /^[A-Za-z0-9]$/）。 */
internal fun isStardriftLatin(char: String): Boolean =
    char.length == 1 && char[0].let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }

/** 一个可见字的起止（秒）。 */
internal class DriftTiming(val char: String, var start: Float, var end: Float)

/**
 * 当前句的可见字（空白不渲染也不占时间）及起止秒数。逐字时间沿用 [charTimings]（没有逐字时按整句均分）。
 * 再照原模组修一种数据错位：行尾字和前一字之间空出 3 秒以上、且占整句 30% 以上时，把行尾字挪到前一字后 0.1 秒。
 */
internal fun stardriftTimings(line: StageLine): List<DriftTiming> {
    val out = line.charTimings(spread = 1f).filter { !it.space }
        .map { DriftTiming(it.text, it.startMs / 1000f, it.endMs / 1000f) }
    stardriftFixTrailingGap(out, line.durationMs / 1000f)
    return out
}

/** 行尾字错位修正（见 [stardriftTimings]）。 */
internal fun stardriftFixTrailingGap(timings: List<DriftTiming>, lineSpanS: Float) {
    if (timings.size < 2) return
    val last = timings[timings.size - 1]
    val prev = timings[timings.size - 2]
    val gap = last.start - prev.end
    if (gap > MAX_TRAILING_GAP_S && gap > 0.3f * max(0.001f, lineSpanS)) {
        val shift = gap - 0.1f
        last.start -= shift
        last.end -= shift
    }
}

/**
 * 给 [count] 个字在 [area] 里挑随机落点，彼此（以及和 [avoid] 里还亮着的旧字）尽量相距 [minDist]。
 * 每个字最多试 [PLACE_TRIES] 次，都挤不下时取离别人最远的那次；落点一定在 [area] 里。
 */
internal fun placeDriftGlyphs(count: Int, area: Rect, minDist: Float, random: StageRandom, avoid: List<Offset> = emptyList()): List<Offset> {
    val placed = ArrayList<Offset>(count)
    repeat(count) {
        var best = Offset.Zero
        var bestDist = -1f
        for (k in 0 until PLACE_TRIES) {
            val p = Offset(area.left + random.next() * area.width, area.top + random.next() * area.height)
            val d = min(nearest(p, placed), nearest(p, avoid))
            if (d > bestDist) {
                best = p
                bestDist = d
            }
            if (d >= minDist) break
        }
        placed += best
    }
    return placed
}

private fun nearest(p: Offset, others: List<Offset>): Float {
    var d = Float.MAX_VALUE
    for (o in others) d = min(d, hypot(o.x - p.x, o.y - p.y))
    return d
}

/** 一帧里一个字的样子：缩放、不透明度、颜色（0 是强调色、1 是文字色）、光罩大小、辉光（原模组 px）。 */
internal class DriftLook {
    var scale = 1f
    var alpha = 1f
    var primary = 1f
    var ring = 0f
    var glow = 0f
}

/**
 * 字的生命周期（原模组）：光点放大成字（[appearS]，easeOut，颜色强调色→文字色）→ 唱的时候和唱完后停留 [holdS] 秒
 * （不透明度随音乐呼吸）→ 变回强调色并缩小消失（[fadeS]，easeIn）。光罩跟字一起长大，唱完后在一个缩放时间里快速缩没。
 * [local] 是距这个字开始的秒数；看不见时返回 false。
 */
internal fun driftLook(local: Float, appearS: Float, durS: Float, holdS: Float, fadeS: Float, pulse: Float, out: DriftLook): Boolean {
    val holdEnd = appearS + durS + holdS
    if (local < 0f || local > holdEnd + fadeS) return false
    when {
        local < appearS -> {
            val k = (local / appearS).coerceIn(0f, 1f)
            if (k <= 0f) return false
            val e = driftEaseOut(k)
            out.scale = 0.12f + 0.88f * e
            out.alpha = k
            out.primary = e
            out.glow = 24f * k * pulse
            out.ring = 0.12f + 0.88f * e
        }
        local < holdEnd -> {
            out.scale = 1f
            out.alpha = (0.8f + 0.2f * pulse).coerceAtMost(1f)
            out.primary = 1f
            out.glow = 18f * pulse
            out.ring = if (local < appearS + durS) 1f else 1f - ((local - appearS - durS) / max(0.2f, appearS)).coerceIn(0f, 1f)
        }
        else -> {
            val k = 1f - ((local - holdEnd) / fadeS).coerceIn(0f, 1f)
            val e = k * k * k
            out.scale = 0.12f + 0.88f * e
            out.alpha = k
            out.primary = e
            out.glow = 18f * k * pulse
            out.ring = 1f - ((local - appearS - durS) / max(0.2f, appearS)).coerceIn(0f, 1f)
        }
    }
    return true
}

/** 量好的一个字和它的时间。 */
internal class DriftSource<G>(val glyph: G, val timing: DriftTiming)

/** 量好的一句。 */
internal class DriftLine<G>(val index: Int, val chars: List<DriftSource<G>>)

/** 屏幕上的一个字：字形、起止（秒）、落点、是不是标点 / 拉丁；[line]、[slot] 是它来自哪句的第几个字。 */
internal class DriftGlyph<G>(
    var glyph: G,
    val char: String,
    val startS: Float,
    endS: Float,
    val x: Float,
    val y: Float,
    val line: Int = -1,
    val slot: Int = -1,
) {
    val durS: Float = max(0.05f, endS - startS)
    val punct: Boolean = isStardriftPunct(char)
    val latin: Boolean = isStardriftLatin(char)
}

/**
 * 屏幕上的字：换句时还没开始的旧字丢掉，已经开始的留下来走完自己的一生（停留、缩小消失），新句的字挑新落点、避开它们。
 * 往回跳或往前跳超过 0.5 秒时清空，按新位置重来（原模组同）；安全区变了也重来。
 */
internal class DriftLyrics<G>(private val seed: Long) {
    val glyphs = ArrayList<DriftGlyph<G>>()
    private var renderedIndex = NO_LINE
    private var shownLine: DriftLine<G>? = null
    private var lastMs = Long.MIN_VALUE
    private var area: Rect? = null

    fun advance(line: DriftLine<G>?, ms: Long, paused: Boolean, area: Rect, minDist: Float, cfg: StardriftConfig) {
        val jumped = lastMs != Long.MIN_VALUE && (ms < lastMs - BACK_JUMP_MS || ms > lastMs + FORWARD_JUMP_MS)
        lastMs = ms
        if (jumped || area != this.area) {
            glyphs.clear()
            renderedIndex = NO_LINE
            this.area = area
        }
        val time = ms / 1000f
        val index = line?.index ?: -1
        if (index != renderedIndex) {
            renderedIndex = index
            glyphs.removeAll { time < it.startS }
            if (line != null && line.chars.isNotEmpty()) {
                val avoid = glyphs.map { Offset(it.x, it.y) }
                val spots = placeDriftGlyphs(line.chars.size, area, minDist, driftLineRandom(seed, line.index), avoid)
                line.chars.forEachIndexed { i, c ->
                    glyphs += DriftGlyph(c.glyph, c.timing.char, c.timing.start, c.timing.end, spots[i].x, spots[i].y, line.index, i)
                }
            }
        } else if (line != null && line !== shownLine) {
            // 同一句重新量过（改了字号）：换上新字形，落点和时间不动
            for (g in glyphs) if (g.line == line.index) line.chars.getOrNull(g.slot)?.let { g.glyph = it.glyph }
        }
        shownLine = line
        if (!paused) {
            val tail = cfg.appearS + cfg.hold + cfg.fadeS
            glyphs.removeAll { time - it.startS > tail + it.durS }
        }
    }

    private companion object {
        const val NO_LINE = Int.MIN_VALUE
        const val BACK_JUMP_MS = 10L
        const val FORWARD_JUMP_MS = 500L
    }
}

/** 每句的落点用自己的随机序列：同一首歌同一句的字每次落在同样的地方。 */
internal fun driftLineRandom(seed: Long, index: Int): StageRandom = StageRandom(seed xor (index.toLong() * LINE_MIX) xor LINE_SALT)

private fun driftEaseOut(k: Float): Float {
    val u = 1f - k
    return 1f - u * u * u
}

private const val MEAN_STAR_LIFE_S = 3.2f
private const val MAX_TRAILING_GAP_S = 3f
private const val PLACE_TRIES = 12
private const val TAU = (Math.PI * 2).toFloat()
private const val LINE_MIX = -0x40A7B892E31B1A47L
private const val LINE_SALT = 0x57A2_D41FL
