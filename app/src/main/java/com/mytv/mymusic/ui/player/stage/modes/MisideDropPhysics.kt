package com.mytv.mymusic.ui.player.stage.modes

import com.mytv.mymusic.ui.player.stage.StageRandom
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * MiSide 掉落的纯逻辑：粒子积分、落地 / 回弹 / 停住判定、地面行淘汰、挤出层、翻转投影，以及按歌词时间驱动的“打字 → 掉落”控制器。
 * 不碰 android.*，单元测试直接喂数。长度单位一律是屏幕像素；原模组的 px 数值乘 unit（原模组 1px 折成多少屏幕像素）后再传进来。
 */

/** 面板里的 7 项设置（其余 7 项按原模组默认值写死在下面的常量里）。 */
internal class MisideConfig(
    /** true：逐字（唱到就掉）；false：整句（唱完整行掉）。 */
    val word: Boolean,
    /** 原模组的 px 字号（24–120，默认 56）。 */
    val fontSize: Float,
    /** 原模组 px/s²。 */
    val gravity: Float,
    val elasticity: Float,
    /** 落地后停多久才开始淡出（秒）。 */
    val settleHold: Float,
    /** 地上最多留几行（按句子算）。 */
    val keepRows: Int,
    /** 7 层灰阶挤出 + 深投影；关掉只剩一层柔和投影。 */
    val glowBoost: Boolean,
)

/** 原模组的基准字号（px）：字号设置、重力、速度都以它为准折算。 */
internal const val MISIDE_BASE_FONT = 56f

/** 原模组隐藏项的默认值：掉落延迟 0、提前量 0、打字机间隔 0、落地淡出开、淡出 1.5 秒、翻转上限 16°、地面在 82% 处。 */
internal const val MISIDE_FADE_S = 1.5f
internal const val MISIDE_TILT_LIMIT = 16f
internal const val MISIDE_GROUND_RATIO = 0.82f
internal const val MISIDE_DROP_DELAY_S = 0f

/** 整句一起落时字与字错开 14ms（原模组 dropLine 的 stagger）。 */
internal const val MISIDE_STAGGER_S = 0.014f

/** 换句时还在半空的字至少以 650px/s 往下砸，免得上一句悬着和下一句同时挂着。 */
internal const val MISIDE_HURRY_VY = 650f

/** 往回或往前跳超过这么多毫秒算跳转：清空地面和粒子，从当前句重新开始。 */
internal const val MISIDE_JUMP_MS = 1500L

private const val MAX_FALL_SPEED = 2600f
private const val BOUNCE_MIN_SPEED = 50f
private const val MAX_SUBSTEP_S = 0.025f
private const val OFFSCREEN_MARGIN = 800f
private const val MAX_KEEP_ROWS = 8

/**
 * 一个掉落中的字。[x]、[y] 是字框左上角（舞台坐标），[w]、[h] 是字框大小；角度单位是度。
 * [delay] 大于 0 时还没开始掉（原地显示，计时只在播放时走）。
 */
internal class MisideParticle<G>(
    val glyph: G,
    /** 来自哪一句（句子下标），按它淘汰地面行。 */
    val row: Int,
    var x: Float,
    var y: Float,
    val w: Float,
    val h: Float,
    var vx: Float,
    var vy: Float,
    var vrx: Float,
    var vry: Float,
    var vrz: Float,
    /** 这个字自己的重力（原模组给每个字 ±12% 的随机）。 */
    val g: Float,
    /** 这个字自己的回弹系数。 */
    val rest: Float,
    var delay: Float,
) {
    var rx = 0f
    var ry = 0f
    var rz = 0f
    var settled = false

    /** 落地后过了多久（秒，只在播放时走）。 */
    var held = 0f

    /** 落地淡出的不透明度 0..1。 */
    var fade = 1f

    val started: Boolean get() = delay <= 0f
}

/**
 * 生成一个粒子，随机数的取法和顺序照原模组 spawn()：横向速度、上抛速度、三个角速度、重力、回弹。
 * 整句一起落时横向散开收到三成，否则像爆开。[unit] 是原模组 1px 折成的屏幕像素。
 */
internal fun <G> spawnMisideParticle(
    glyph: G,
    row: Int,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    cfg: MisideConfig,
    unit: Float,
    delay: Float,
    random: StageRandom,
): MisideParticle<G> {
    val spread = if (cfg.word) 1f else 0.3f
    val vx = (random.next() - 0.5f) * 170f * spread * unit
    val vy = (-60f - random.next() * 80f) * unit
    val vrx = (random.next() - 0.5f) * 56f * spread
    val vry = (random.next() - 0.5f) * 56f * spread
    val vrz = (random.next() - 0.5f) * 300f * spread
    val g = cfg.gravity * (0.88f + random.next() * 0.24f) * unit
    val rest = cfg.elasticity * (0.85f + random.next() * 0.25f)
    return MisideParticle(glyph, row, x, y, w, h, vx, vy, vrx, vry, vrz, g, rest, delay)
}

/**
 * 粒子池：积分、落地判定、落地后的停留与淡出、地面行淘汰。坐标都是舞台绝对坐标，换句时不清。
 */
internal class MisideField<G> {
    val particles = ArrayList<MisideParticle<G>>()

    /** 淘汰时留下的句子下标（设置最多 8 行），复用不分配。 */
    private val keep = IntArray(MAX_KEEP_ROWS)

    fun clear() = particles.clear()

    /** 换句时：还在空中的字至少以 [minVy] 往下砸（原模组 frame() 里的“强制急坠”）。 */
    fun hurry(minVy: Float) {
        for (p in particles) if (!p.settled) p.vy = max(p.vy, minVy)
    }

    /**
     * 推进 [dt] 秒（暂停时 dt = 0，什么都不动）。大于 25ms 的一帧拆成几步，电视 30 帧也不会穿地。
     * [groundY] 是地面线，字框底边落在它上面；[stageHeight] 往下 800px 以外的字直接回收。
     */
    fun step(
        dt: Float,
        groundY: Float,
        stageHeight: Float,
        unit: Float,
        settleHold: Float,
        fadeTime: Float = MISIDE_FADE_S,
        tiltLimit: Float = MISIDE_TILT_LIMIT,
    ) {
        if (dt <= 0f) return
        val steps = ceil(dt / MAX_SUBSTEP_S).toInt().coerceAtLeast(1)
        val h = dt / steps
        repeat(steps) { substep(h, groundY, stageHeight, unit, settleHold, fadeTime, tiltLimit) }
    }

    private fun substep(dt: Float, groundY: Float, stageHeight: Float, unit: Float, settleHold: Float, fadeTime: Float, tiltLimit: Float) {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            if (!p.started) {
                p.delay -= dt
                continue
            }
            if (p.settled) {
                p.held += dt
                if (p.held <= settleHold) continue
                p.fade = 1f - (p.held - settleHold) / fadeTime
                if (p.fade <= 0f) iterator.remove()
                continue
            }
            if (!p.y.isFinite() || p.y > stageHeight + OFFSCREEN_MARGIN * unit) {
                iterator.remove()
                continue
            }
            integrateMiside(p, dt, groundY, unit, tiltLimit)
        }
    }

    /**
     * 只留最近 [keepRows] 句的落地字（按生成顺序从后往前数句子）；还在空中的不动。原模组的 pruneRows：
     * 地上不会把旧行往上推，超出的整行直接回收。
     */
    fun prune(keepRows: Int) {
        if (particles.isEmpty()) return
        val limit = keepRows.coerceIn(0, keep.size)
        var kept = 0
        var i = particles.size - 1
        while (i >= 0) {
            val row = particles[i].row
            if (!isKept(row, kept)) {
                if (kept == limit) break
                keep[kept++] = row
            }
            i--
        }
        particles.removeAll { it.settled && !isKept(it.row, kept) }
    }

    private fun isKept(row: Int, count: Int): Boolean {
        for (k in 0 until count) if (keep[k] == row) return true
        return false
    }

    /**
     * 离线把所有字模拟到落定（减少动态效果的静帧用），最多模拟 [maxSeconds] 秒；回弹很大、到时还在跳的字直接按落定摆在地上。
     */
    fun settleAll(groundY: Float, stageHeight: Float, unit: Float, maxSeconds: Float = 6f) {
        var t = 0f
        while (t < maxSeconds && particles.any { !it.settled }) {
            // 停留时间给无穷大：静帧要的是“刚落定”的样子，不能让它们淡掉
            step(1f / 60f, groundY, stageHeight, unit, settleHold = Float.MAX_VALUE)
            t += 1f / 60f
        }
        for (p in particles) {
            if (p.settled) continue
            p.delay = 0f
            p.y = groundY - p.h
            p.rx = 0f
            p.ry = 0f
            p.settled = true
        }
    }
}

/**
 * 一步积分（原模组 step() 里的空中部分）：重力加速、水平阻尼、俯仰 / 偏航在 ±[tiltLimit] 之间来回、平面旋转自由转；
 * 碰到地面时速度大于 50px/s 就按回弹系数反弹（水平速度 ×0.7、角速度 ×0.4），否则停住：俯仰偏航归零、平面旋转保留。
 */
internal fun <G> integrateMiside(p: MisideParticle<G>, dt: Float, groundY: Float, unit: Float, tiltLimit: Float = MISIDE_TILT_LIMIT) {
    p.vy = min(MAX_FALL_SPEED * unit, p.vy + p.g * dt)
    p.vx *= 0.985f.pow(dt * 60f)
    p.x += p.vx * dt
    p.y += p.vy * dt
    p.rx += p.vrx * dt
    if (p.rx > tiltLimit) {
        p.rx = tiltLimit
        p.vrx = -abs(p.vrx)
    } else if (p.rx < -tiltLimit) {
        p.rx = -tiltLimit
        p.vrx = abs(p.vrx)
    }
    p.ry += p.vry * dt
    if (p.ry > tiltLimit) {
        p.ry = tiltLimit
        p.vry = -abs(p.vry)
    } else if (p.ry < -tiltLimit) {
        p.ry = -tiltLimit
        p.vry = abs(p.vry)
    }
    p.rz += p.vrz * dt
    val floor = groundY - p.h
    if (p.y >= floor) {
        p.y = floor
        if (abs(p.vy) > BOUNCE_MIN_SPEED * unit) {
            p.vy *= -p.rest
            p.vx *= 0.7f
            p.vrx *= 0.4f
            p.vry *= 0.4f
            p.vrz *= 0.4f
        } else {
            // 落地平躺，厚度正对观众；保留平面旋转，像随手散落
            p.vx = 0f
            p.vy = 0f
            p.vrx = 0f
            p.vry = 0f
            p.vrz = 0f
            p.rx = 0f
            p.ry = 0f
            p.settled = true
            p.held = 0f
        }
    }
}

/** 回弹这类小数：保留两位、去掉多余的 0（0.34、0.4、0）。 */
internal fun formatMisideDecimal(value: Float): String {
    val rounded = (value * 100).roundToInt() / 100f
    return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString().trimEnd('0').trimEnd('.')
}

/** 挤出层数。 */
internal const val MISIDE_EXTRUDE_LAYERS = 7

/**
 * 第 [layer] 层挤出（0 是贴着字面的 1px 层，6 是最深的 7px 层）的灰度：深色主题从 168 递减到 64。
 * 同色叠层只会读成光晕，明度递减才读成厚度。
 */
internal fun misideExtrudeGray(layer: Int): Int =
    (168f + (64f - 168f) * layer.coerceIn(0, MISIDE_EXTRUDE_LAYERS - 1) / (MISIDE_EXTRUDE_LAYERS - 1)).roundToInt()

/** 第 [layer] 层挤出往右下偏多少（原模组 1–7px，乘 [unit]）。 */
internal fun misideExtrudeOffset(layer: Int, unit: Float): Float = (layer + 1) * unit

/**
 * CSS `rotateX(rx) rotateY(ry) rotateZ(rz)` 作用在平面字上、不加透视（原模组的父元素没有 perspective）时，
 * 投影到屏幕上的 2×2 线性变换：x' = a·x + b·y，y' = c·x + d·y。写进 [out]（a、b、c、d）。
 */
internal fun misideTilt(rxDeg: Float, ryDeg: Float, rzDeg: Float, out: FloatArray) {
    val rx = Math.toRadians(rxDeg.toDouble())
    val ry = Math.toRadians(ryDeg.toDouble())
    val rz = Math.toRadians(rzDeg.toDouble())
    val cx = cos(rx)
    val sx = sin(rx)
    val cy = cos(ry)
    val sy = sin(ry)
    val cz = cos(rz)
    val sz = sin(rz)
    out[0] = (cy * cz).toFloat()
    out[1] = (-cy * sz).toFloat()
    out[2] = (cx * sz + sx * sy * cz).toFloat()
    out[3] = (cx * cz - sx * sy * sz).toFloat()
}

/** 打字区里的一个字：字框左上角（舞台坐标）、字框大小、出现时间。 */
internal class MisideChar<G>(val glyph: G, val startMs: Long, val x: Float, val y: Float, val w: Float, val h: Float)

/** 排好版的一句。 */
internal class MisideLine<G>(val index: Int, val endMs: Long, val chars: List<MisideChar<G>>)

/**
 * 歌词时间驱动的控制器：打字机逐字显形 → 整句唱完（或逐字唱到）交给粒子 → 粒子落地、停留、淡出、按行淘汰。
 * 绘制阶段每帧调一次 [advance]；暂停时 dt = 0，物理和停留计时都停。
 */
internal class MisideDrop<G>(private val seed: Long) {
    val field = MisideField<G>()

    /** 打字区当前显示的句子（句间为 null）。 */
    var shown: MisideLine<G>? = null
        private set

    private var shownIndex = NO_LINE
    private var dropped = BooleanArray(0)
    private var lastMs = Long.MIN_VALUE
    private var random = StageRandom(seed)

    /** 清空地面、粒子和打字区（跳转、舞台尺寸变化）。 */
    fun reset() {
        field.clear()
        shown = null
        shownIndex = NO_LINE
        dropped = BooleanArray(0)
    }

    /** 打字区第 [i] 个字此刻显示没有：唱到了、还没交给粒子。 */
    fun typed(i: Int, ms: Long): Boolean {
        val line = shown ?: return false
        return i < dropped.size && !dropped[i] && ms >= line.chars[i].startMs
    }

    /**
     * 推进一帧。[line] 是此刻的句子（句间为 null），同一句换了排版（转屏、安全区变化）时沿用已掉落的标记。
     */
    fun advance(line: MisideLine<G>?, ms: Long, dt: Float, cfg: MisideConfig, groundY: Float, stageHeight: Float, unit: Float) {
        val jumped = lastMs != Long.MIN_VALUE && abs(ms - lastMs) > MISIDE_JUMP_MS
        lastMs = ms
        if (jumped) reset()
        val index = line?.index ?: -1
        if (index != shownIndex) {
            val old = shown
            if (old != null) {
                // 换句：空中的字急坠，旧句没落的字整行落下
                field.hurry(MISIDE_HURRY_VY * unit)
                dropAll(old, cfg, unit)
            }
            field.prune(cfg.keepRows)
            adopt(line, index)
            // 跳转进一句中间：逐字模式下已经唱过的字算掉过了，不一口气全砸下来
            if (jumped && cfg.word && line != null) {
                for (i in line.chars.indices) if (line.chars[i].startMs <= ms) dropped[i] = true
            }
        } else if (line != null && line !== shown) {
            val keep = dropped.takeIf { it.size == line.chars.size }
            adopt(line, index)
            if (keep != null) dropped = keep
        }
        val current = shown
        if (current != null) {
            if (cfg.word) {
                dropDue(current, ms, cfg, unit)
            } else if (ms >= current.endMs) {
                dropAll(current, cfg, unit)
            }
        }
        field.step(dt, groundY, stageHeight, unit, cfg.settleHold)
        field.prune(cfg.keepRows)
    }

    private fun adopt(line: MisideLine<G>?, index: Int) {
        shown = line
        shownIndex = index
        dropped = BooleanArray(line?.chars?.size ?: 0)
        // 每句一个随机序列：同一首歌同一句每次掉法一样
        random = StageRandom(seed xor (index.toLong() * LINE_SEED_MIX))
    }

    /** 整句落：还没落的字从左到右错开 14ms 交给粒子。 */
    private fun dropAll(line: MisideLine<G>, cfg: MisideConfig, unit: Float) {
        var n = 0
        for (i in line.chars.indices) {
            if (dropped[i]) continue
            spawn(line, i, cfg, unit, MISIDE_DROP_DELAY_S + n * MISIDE_STAGGER_S)
            n++
        }
    }

    /** 逐字落：唱到的字马上交给粒子（原模组“唱到就逐字崩落”）。 */
    private fun dropDue(line: MisideLine<G>, ms: Long, cfg: MisideConfig, unit: Float) {
        for (i in line.chars.indices) {
            if (dropped[i] || line.chars[i].startMs > ms) continue
            spawn(line, i, cfg, unit, MISIDE_DROP_DELAY_S)
        }
    }

    private fun spawn(line: MisideLine<G>, i: Int, cfg: MisideConfig, unit: Float, delay: Float) {
        val c = line.chars[i]
        dropped[i] = true
        if (c.w <= 0f || c.h <= 0f) return
        field.particles += spawnMisideParticle(c.glyph, line.index, c.x, c.y, c.w, c.h, cfg, unit, delay, random)
    }

    private companion object {
        const val NO_LINE = Int.MIN_VALUE
        const val LINE_SEED_MIX = -0x61C8864680B583EBL
    }
}
