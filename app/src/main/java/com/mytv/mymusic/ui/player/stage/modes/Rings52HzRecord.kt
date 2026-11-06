package com.mytv.mymusic.ui.player.stage.modes

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * 52Hz 的“年轮记录”（照 stage.mjs、rippleFilter.mjs、beatDetector.mjs）：拍点、响度包络、年轮的生灭和半径、
 * 4 个年轮源槽位（一句歌词或一个髓心各占一个，换句后旧句的年轮带着旧字形继续往外走），以及打包成着色器 uniform。
 * 时间都是歌曲时间（秒）：暂停冻结，往回跳整份记录重来。纯 Kotlin，单元测试直接跑。
 */

/** 响度跟随（照 createEnergyFollower）：除以缓慢回落的峰值，快起慢落，安静时也留 [ENERGY_FLOOR] 的细线。 */
internal class RingsEnergy {
    private var peak = 0.2f
    private var envelope = 0f

    fun reset() {
        envelope = 0f
    }

    fun step(power: Float, bass: Float, sensitivity: Float, dt: Float): Float {
        val raw = 0.6f * power + 0.4f * bass
        peak = maxOf(raw, peak * exp(-dt / 8f), 0.08f)
        val target = min(1f, raw / peak * sensitivity)
        val rate = if (target > envelope) 1f - exp(-dt / 0.03f) else 1f - exp(-dt / 0.35f)
        envelope += (target - envelope) * rate
        return ENERGY_FLOOR + (1f - ENERGY_FLOOR) * envelope
    }
}

/**
 * 拍点闸门。原版自己做低频谱通量检测，灵敏度除阈值；这里直接用宿主的低频起拍计数（宿主已有 280ms 的不应期，
 * 和原版的 MIN_GAP 一样），灵敏度折算成间隔：低于 1 时拉长最小间隔（0.28 秒 ÷ 灵敏度，拍点变稀），
 * 高于 1 时缩短补拍间隔（2 秒 ÷ 灵敏度，安静段也更密）。任何时候超过补拍间隔没有拍点都补一拍（原版 MAX_GAP）。
 */
internal class RingsBeatGate {
    private var last = Double.NEGATIVE_INFINITY

    fun reset() {
        last = Double.NEGATIVE_INFINITY
    }

    /** [time] 时来了 [kicks] 个新起拍（可以是 0），返回这一帧要不要发一圈年轮。 */
    fun step(time: Double, kicks: Int, sensitivity: Float): Boolean {
        val since = time - last
        val onset = kicks > 0 && since >= ringsMinGap(sensitivity)
        if (onset || since >= ringsFillerGap(sensitivity)) {
            last = time
            return true
        }
        return false
    }
}

internal fun ringsMinGap(sensitivity: Float): Float = MIN_GAP_S / min(max(sensitivity, 0.1f), 1f)

internal fun ringsFillerGap(sensitivity: Float): Float = MAX_GAP_S / max(sensitivity, 1f)

/** 年轮源的形状：[kind] 是 [SHAPE_RECTS]（每个元素 cx、cy、半宽、半高）或 [SHAPE_POLYGON]（每个元素一条边 x0、y0、x1、y1）。 */
internal class RingShape(
    val kind: Int,
    val fine: FloatArray,
    val coarse: FloatArray,
    /** 年轮从字形外多远开始（原版 RING_GAP 0.55 倍字号）；髓心为 0。 */
    val gap: Float,
    val corner: Float,
    /** 外接框 cx、cy、半宽、半高：着色器用它提前跳过够不着年轮的像素。 */
    val box: FloatArray,
    /** 兜底画法：髓心的稠密轮廓（x、y 交错）和中心；矩形源为 null。 */
    val outline: FloatArray? = null,
    val center: FloatArray? = null,
) {
    val fineCount: Int get() = fine.size / 4
    val coarseCount: Int get() = coarse.size / 4
}

internal fun rectShape(fine: List<RingRect>, coarse: List<RingRect>, size: Float): RingShape {
    fun pack(rects: List<RingRect>) = FloatArray(rects.size * 4).also { out ->
        rects.forEachIndexed { i, r ->
            out[i * 4] = r.cx
            out[i * 4 + 1] = r.cy
            out[i * 4 + 2] = r.hw
            out[i * 4 + 3] = r.hh
        }
    }
    val box = boundsOf(fine)
    return RingShape(SHAPE_RECTS, pack(fine), pack(coarse), size * RING_GAP, size * RING_CORNER, floatArrayOf(box.cx, box.cy, box.hw, box.hh))
}

/** 闭合多边形 → 一条边一个元素。 */
internal fun polygonEdges(points: FloatArray): FloatArray {
    val count = points.size / 2
    return FloatArray(count * 4).also { out ->
        for (i in 0 until count) {
            val k = (i + 1) % count
            out[i * 4] = points[i * 2]
            out[i * 4 + 1] = points[i * 2 + 1]
            out[i * 4 + 2] = points[k * 2]
            out[i * 4 + 3] = points[k * 2 + 1]
        }
    }
}

internal fun pithShape(outline: FloatArray, center: FloatArray): RingShape {
    var left = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    for (i in 0 until outline.size / 2) {
        left = min(left, outline[i * 2])
        right = max(right, outline[i * 2])
        top = min(top, outline[i * 2 + 1])
        bottom = max(bottom, outline[i * 2 + 1])
    }
    return RingShape(
        SHAPE_POLYGON,
        polygonEdges(decimate(outline, PITH_POINTS / PITH_FINE_POINTS)),
        polygonEdges(decimate(outline, PITH_POINTS / PITH_COARSE_POINTS)),
        gap = 0f,
        corner = 0f,
        box = floatArrayOf((left + right) / 2f, (top + bottom) / 2f, (right - left) / 2f, (bottom - top) / 2f),
        outline = outline,
        center = center,
    )
}

/** 一个槽位：正在或曾经发年轮的源，和它发年轮的歌曲时间窗口 [start, end]。 */
internal class RingSlot {
    var key: String? = null
    var shape: RingShape? = null
    var start = 1.0
    var end = 0.0

    /** 越新越大，元素预算先给新的。 */
    var stamp = 0L
}

/** 一圈年轮：出生时间、当时的响度、属于哪个槽位；被挤掉时记下时间，0.35 秒内淡出。 */
internal class Ring(val birth: Double, val energy: Float, val slot: Int) {
    var evictedAt = Double.NaN
}

internal class RingsRecord {
    val slots = Array(SLOT_COUNT) { RingSlot() }
    val rings = ArrayList<Ring>()
    var openSlot = -1
        private set
    private var stamp = 0L

    val openKey: String? get() = slots.getOrNull(openSlot)?.key

    /** 从 [time] 起改由 [key]（形状 [shape]）发年轮，null 是停发；[always] 为 true 时窗口从远古开始（静帧）。 */
    fun switchSource(key: String?, shape: RingShape?, time: Double, always: Boolean = false) {
        if (openSlot >= 0) slots[openSlot].end = time
        openSlot = -1
        if (key == null || shape == null) return
        var target = slots.indexOfFirst { it.key == null }
        if (target < 0) target = slots.indices.minBy { slots[it].end }
        rings.removeAll { it.slot == target }
        slots[target].apply {
            this.key = key
            this.shape = shape
            start = if (always) -1e9 else time
            end = OPEN_END
            stamp = ++this@RingsRecord.stamp
        }
        openSlot = target
    }

    /** 跳转后旧记录作废：只留正在发的源（从 [time] 重新发），其余清空。 */
    fun reset(time: Double) {
        rings.clear()
        slots.forEachIndexed { i, slot ->
            if (i == openSlot) {
                slot.start = time
            } else {
                slot.key = null
                slot.shape = null
                slot.start = 1.0
                slot.end = 0.0
            }
        }
    }

    /** 整个清空（尺寸变了，形状都要重算）。 */
    fun clear() {
        openSlot = -1
        reset(0.0)
    }

    /** 在正在发的源上发一圈；超过 [cap] 圈时最老的开始淡出。 */
    fun addRing(time: Double, energy: Float, cap: Int) {
        if (openSlot < 0) return
        rings += Ring(time, energy, openSlot)
        var alive = rings.count { it.evictedAt.isNaN() }
        for (ring in rings) {
            if (alive <= cap) break
            if (ring.evictedAt.isNaN()) {
                ring.evictedAt = time
                alive--
            }
        }
        // 淡出中的也有上限，免得着色器数组装不下
        while (rings.size > cap + MAX_DYING) rings.removeAt(0)
    }

    /** 走到淡出距离、淡出完的年轮删掉；没有年轮也不在发的槽位腾出来。 */
    fun prune(time: Double, speed: Float, fadeDistance: Float) {
        rings.removeAll { ring ->
            (time - ring.birth) * speed >= fadeDistance || (!ring.evictedAt.isNaN() && time - ring.evictedAt >= EVICT_FADE_S)
        }
        slots.forEachIndexed { i, slot ->
            if (i != openSlot && slot.key != null && rings.none { it.slot == i }) {
                slot.key = null
                slot.shape = null
                slot.start = 1.0
                slot.end = 0.0
            }
        }
    }

    /** 静帧（照 seedStaticRecord）：假装一直在放，每 0.72 秒一拍，响度按两个正弦起伏，铺到淡出距离为止。 */
    fun seedStatic(time: Double, speed: Float, fadeDistance: Float, cap: Int) {
        rings.clear()
        if (openSlot < 0) return
        slots[openSlot].start = -1e9
        for (k in 0 until cap) {
            val birth = time - k * STATIC_BEAT_S
            if ((time - birth) * speed >= fadeDistance) break
            val sample = Math.floorMod(floor(birth * HISTORY_RATE).toLong(), HISTORY_SIZE.toLong()).toInt()
            rings.add(0, Ring(birth, staticEnergy(sample), openSlot))
        }
    }
}

/** 静帧的“历史响度”（原版 seedStaticRecord 的公式）。 */
internal fun staticEnergy(sample: Int): Float =
    0.2f + 0.45f * (0.5f + 0.5f * sin(sample * 0.13f)) * (0.5f + 0.5f * sin(sample * 0.031f))

/** 线的半宽（dp）：响度 0→1 对应 0.5→2.4。 */
internal fun ringHalfWidthDp(energy: Float): Float = 0.5f + 1.9f * energy.coerceIn(0f, 1f).pow(1.5f)

/** 线的强度：轻拍也有四分之一。 */
internal fun ringStrength(energy: Float): Float = 0.25f + 0.75f * energy

/** 年轮之间的底色（响度大的那段木头深一点）。 */
internal fun ringBand(energy: Float): Float = energy * energy * 0.05f

/** 按走过的距离淡出，到 [fadeDistance]（半对角线，屏幕边缘）时为 0。 */
internal fun ringFade(distance: Float, fadeDistance: Float): Float =
    (1f - distance / fadeDistance).coerceIn(0f, 1f).pow(1.8f)

/** 槽位开始、停止发年轮处的软边（原版 inWindow）。 */
internal fun ringWindow(birth: Double, start: Double, end: Double): Float =
    (smooth(start - 0.02, start + 0.25, birth) * (1.0 - smooth(end - 0.25, end + 0.02, birth))).toFloat()

/** 被挤掉的年轮 0.35 秒淡出。 */
internal fun ringEvictFade(time: Double, evictedAt: Double): Float =
    if (evictedAt.isNaN()) 1f else (1.0 - (time - evictedAt) / EVICT_FADE_S).coerceIn(0.0, 1.0).toFloat()

/** 累加的强度 → 不透明度（原版 1 − e^(−1.6·total)，再乘浓度）。 */
internal fun ringAlpha(total: Float, opacity: Float): Float = (1f - exp(-total * 1.6f)) * opacity

internal fun smooth(edge0: Double, edge1: Double, x: Double): Double {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
    return t * t * (3 - 2 * t)
}

/** 字的点亮进度（照 lyricText.mjs）：用时取字长，夹在 0.12–0.45 秒。 */
internal fun ringsLightProgress(ms: Long, startMs: Long, endMs: Long): Float {
    val duration = (endMs - startMs).coerceIn(LIGHT_MIN_MS, LIGHT_MAX_MS)
    return ((ms - startMs).toFloat() / duration).coerceIn(0f, 1f)
}

internal fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)

/** 点亮过程中鼓一下：正弦拱起，最多 12%。 */
internal fun ringsSwell(progress: Float): Float =
    if (progress > 0f && progress < 1f) SWELL * sin(PI.toFloat() * progress) else 0f

/**
 * 元素预算：槽位按新到旧排好（[fine]、[coarse] 是各自的元素数），新的尽量用细形状，放不下就用粗的。
 * 返回每个槽位是否用细形状。
 */
internal fun planFine(fine: IntArray, coarse: IntArray, budget: Int): BooleanArray {
    val out = BooleanArray(fine.size)
    var left = budget - coarse.sum()
    for (i in fine.indices) {
        val extra = fine[i] - coarse[i]
        if (extra <= left) {
            out[i] = true
            left -= extra
        }
    }
    return out
}

/** 着色器的 uniform（数组长度固定）。 */
internal class RingUniforms {
    val elem = FloatArray(SHADER_ELEMENTS * 4)
    val slotEnd = FloatArray(SLOT_COUNT)
    val slotKind = FloatArray(SLOT_COUNT)
    val slotCorner = FloatArray(SLOT_COUNT)
    val slotGap = FloatArray(SLOT_COUNT)
    val slotMax = FloatArray(SLOT_COUNT)
    val slotBox = FloatArray(SLOT_COUNT * 4)
    val ringEnd = FloatArray(SLOT_COUNT)
    val ring = FloatArray(SHADER_RINGS * 4)
    var elemCount = 0
    var ringCount = 0
}

/**
 * 把记录打包成 uniform：元素按槽位连续存，[RingUniforms.slotEnd] 是累计的终止下标；年轮也按槽位分组，组内从老到新
 * （半径从大到小），每组最后一个是“现在”（半径 0，只带当前响度的底色），着色器据此画两圈之间的底色。
 * 没有年轮、也不在发的槽位种类填 −1。[dp] 是每 dp 的像素数，[nowEnergy] 是当前响度。
 */
internal fun packRingUniforms(record: RingsRecord, time: Double, speed: Float, dp: Float, nowEnergy: Float, out: RingUniforms) {
    val slots = record.slots
    val used = BooleanArray(SLOT_COUNT) { i ->
        slots[i].shape != null && (i == record.openSlot || record.rings.any { it.slot == i })
    }
    val order = (0 until SLOT_COUNT).filter { used[it] }.sortedByDescending { slots[it].stamp }
    val plan = planFine(
        IntArray(order.size) { slots[order[it]].shape!!.fineCount },
        IntArray(order.size) { slots[order[it]].shape!!.coarseCount },
        SHADER_ELEMENTS,
    )
    var e = 0
    var r = 0
    for (s in 0 until SLOT_COUNT) {
        val shape = slots[s].shape
        out.slotMax[s] = 0f
        if (!used[s] || shape == null) {
            out.slotKind[s] = -1f
            out.slotEnd[s] = e.toFloat()
            out.ringEnd[s] = r.toFloat()
            continue
        }
        val elements = if (plan[order.indexOf(s)]) shape.fine else shape.coarse
        val count = min(elements.size / 4, SHADER_ELEMENTS - e)
        elements.copyInto(out.elem, e * 4, 0, count * 4)
        e += count
        out.slotEnd[s] = e.toFloat()
        out.slotKind[s] = shape.kind.toFloat()
        out.slotCorner[s] = shape.corner
        out.slotGap[s] = shape.gap
        shape.box.copyInto(out.slotBox, s * 4)
        val slot = slots[s]
        for (ring in record.rings) {
            if (ring.slot != s || r >= SHADER_RINGS - 1) continue
            val radius = ((time - ring.birth) * speed).toFloat()
            if (radius < 0f) continue
            val weight = ringWindow(ring.birth, slot.start, slot.end) * ringEvictFade(time, ring.evictedAt)
            val halfWidth = ringHalfWidthDp(ring.energy) * dp
            out.ring[r * 4] = radius
            out.ring[r * 4 + 1] = halfWidth
            out.ring[r * 4 + 2] = ringStrength(ring.energy) * weight
            out.ring[r * 4 + 3] = ringBand(ring.energy) * weight
            out.slotMax[s] = max(out.slotMax[s], radius + halfWidth + AA_DP * dp)
            r++
        }
        if (s == record.openSlot && r < SHADER_RINGS) {
            out.ring[r * 4] = 0f
            out.ring[r * 4 + 1] = 0f
            out.ring[r * 4 + 2] = 0f
            out.ring[r * 4 + 3] = ringBand(nowEnergy) * ringWindow(time, slot.start, slot.end)
            r++
        }
        out.ringEnd[s] = r.toFloat()
    }
    for (i in e * 4 until out.elem.size) out.elem[i] = 0f
    for (i in r * 4 until out.ring.size) out.ring[i] = 0f
    out.elemCount = e
    out.ringCount = r
}

internal const val SHAPE_RECTS = 0
internal const val SHAPE_POLYGON = 1
internal const val SLOT_COUNT = 4
internal const val SHADER_ELEMENTS = 64
internal const val SHADER_RINGS = 32
/** 着色器路径同时最多几圈（电视再乘 0.6）；原版不设上限，到屏幕边缘自然淡没，这里只是保护。 */
internal const val RING_CAP_SHADER = 24
/** 低版本兜底同时最多几圈。 */
internal const val RING_CAP_FALLBACK = 4
internal const val LINE_ELEMENT_CAP = 24
internal const val PITH_FINE_POINTS = 24
internal const val PITH_COARSE_POINTS = 12
internal const val ENERGY_FLOOR = 0.06f
internal const val RING_GAP = 0.55f
internal const val RING_CORNER = 0.14f
internal const val AA_DP = 1.2f
internal const val STATIC_BEAT_S = 0.72
internal const val HISTORY_RATE = 60
internal const val HISTORY_SIZE = 1024
internal const val OPEN_END = 1e9
private const val MIN_GAP_S = 0.28f
private const val MAX_GAP_S = 2f
private const val MAX_DYING = 4
private const val EVICT_FADE_S = 0.35
private const val LIGHT_MIN_MS = 120L
private const val LIGHT_MAX_MS = 450L
private const val SWELL = 0.12f
