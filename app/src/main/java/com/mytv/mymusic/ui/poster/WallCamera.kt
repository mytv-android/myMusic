package com.mytv.mymusic.ui.poster

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** 收回边界后的镜头位置，以及哪条轴被挡住了。 */
internal data class CamClamp(val point: WallPoint, val hitX: Boolean, val hitY: Boolean)

/**
 * 把镜头收回边界。[from] 是这次手势或漂移开始时的位置：本来就在界外（比如刚居中到边上的大卡）时，
 * 边界放宽到 from，不往回拽，只是不许再往外走。[bounds] 为 null（普通墙）时不限制。
 */
internal fun clampCam(cam: WallPoint, from: WallPoint, bounds: WallBounds?): CamClamp {
    if (bounds == null) return CamClamp(cam, hitX = false, hitY = false)
    val x = clampLoose(cam.x, min(bounds.x0, from.x), max(bounds.x1, from.x))
    val y = clampLoose(cam.y, min(bounds.y0, from.y), max(bounds.y1, from.y))
    return CamClamp(WallPoint(x, y), x != cam.x, y != cam.y)
}

/**
 * 把 [p] 居中到可用区时镜头该去的位置（点格子放大、切歌跟随、“定位”都用它）。
 * 先放大再居中：[WallScene.grown] 应已是 [p]，这样量的是 6×6 大卡。
 * 听歌模式的带子有边：尽量铺满整屏不露空边，但大卡必须整张落在可用区里。
 */
internal fun WallScene.centerTarget(p: SlotPos, viewport: WallViewport): WallPoint {
    val r = rectAt(p)
    val top = viewport.insetTop
    val vh = viewport.usableHeight
    var tx = r.centerX - viewport.width / 2
    var ty = r.centerY - top - vh / 2
    val f = fillRange(viewport)
    if (f != null) {
        tx = clampLoose(clampLoose(tx, f.x0, f.x1), r.right - viewport.width, r.x)
        ty = clampLoose(clampLoose(ty, f.y0, f.y1), r.bottom - top - vh, r.y - top)
    }
    return WallPoint(tx, ty)
}

/**
 * 方向键焦点出了安全区（可用区四边内缩 [WallMetrics.safeMargin]）才移镜头，而且只移最小距离，
 * 正在播放的大卡因此尽量留在画面里；格子比安全区还大时改为居中。[base] 是镜头当前的落点
 * （弹簧还在走就用弹簧终点，见 [WallCamera.restingPoint]）。不用动返回 null。
 */
internal fun WallScene.revealTarget(p: SlotPos, base: WallPoint, viewport: WallViewport): WallPoint? {
    val r = rectAt(p)
    val m = metrics.safeMargin
    val inset = viewport.insetTop
    val vh = viewport.usableHeight
    var tx = base.x
    var ty = base.y
    val left = tx + m
    val right = tx + viewport.width - m
    val top = ty + inset + m
    val bottom = top + vh - 2 * m
    if (r.w > right - left) tx = r.centerX - viewport.width / 2
    else if (r.x < left) tx -= left - r.x
    else if (r.right > right) tx += r.right - right
    if (r.h > bottom - top) ty = r.centerY - inset - vh / 2
    else if (r.y < top) ty -= top - r.y
    else if (r.bottom > bottom) ty += r.bottom - bottom
    return if (tx == base.x && ty == base.y) null else WallPoint(tx, ty)
}

/**
 * 海报墙镜头：弹簧居中、甩动惯性、电视空闲漂移，外加空闲计时。不依赖 Compose：界面每帧调 [step]，
 * 读 [x] / [y] 平移墙面（取整到像素由界面做）。三种运动同一时刻只走一种，优先级与原型一致：弹簧 > 惯性 > 漂移。
 * 弹簧和惯性按解析解推进，漂移用中点法、碰边按越界量反弹，所以 30 / 60 / 120 帧的结果一致。
 * 内部用 Double 累加，长时间漂移也不丢精度。
 *
 * 听歌模式要设 [bounds]（[WallScene.bounds]）；普通墙保持 null，无边。
 */
internal class WallCamera(
    x: Float = 0f,
    y: Float = 0f,
    private val random: Random = Random.Default,
) {
    private var cx = x.toDouble()
    private var cy = y.toDouble()

    val x: Float get() = cx.toFloat()
    val y: Float get() = cy.toFloat()
    val position: WallPoint get() = WallPoint(x, y)

    /** 拖动、甩动、漂移的边界；null 表示无边。 */
    var bounds: WallBounds? = null

    /** 弹簧终点；null 表示没在弹。 */
    var target: WallPoint? = null
        private set
    private var svx = 0.0
    private var svy = 0.0

    private var fling: Fling? = null
    private var drift: Drift? = null

    /** 空闲漂移开关：只在电视上、且墙内设置开着时为 true。改动时视作一次操作。 */
    var driftEnabled = false
        set(value) {
            if (field == value) return
            field = value
            wake()
        }

    /** 距上次操作的秒数。 */
    var idleSeconds = 0.0
        private set

    /** 浮层该淡出了：空闲 [IDLE_CHROME_AFTER] 秒，或已在漂移。 */
    val chromeIdle: Boolean get() = drift != null || idleSeconds >= IDLE_CHROME_AFTER

    val isDrifting: Boolean get() = drift != null
    val isFlinging: Boolean get() = fling != null
    val isAnimating: Boolean get() = target != null || fling != null || drift != null

    /** 镜头最终会停在哪：弹簧还在走就是弹簧终点。 */
    val restingPoint: WallPoint get() = target ?: position

    /** 直接跳到 [p]（不动画、或系统关了动画）。 */
    fun snapTo(p: WallPoint) {
        cx = p.x.toDouble()
        cy = p.y.toDouble()
        target = null
        svx = 0.0
        svy = 0.0
        fling = null
    }

    /** 弹到 [p]：临界阻尼，约 0.45 秒到位；中途改目标时保留当前速度，接得上。 */
    fun animateTo(p: WallPoint) {
        fling = null
        target = p
    }

    /**
     * 开始拖动：停下弹簧、惯性和漂移，算一次操作，返回起点（之后 [dragTo] / [fling] 的 from）。
     * 电视上“漂移中按的第一下只唤醒”要由调用方先看 [wake] 的返回值。
     */
    fun beginDrag(): WallPoint {
        target = null
        svx = 0.0
        svy = 0.0
        fling = null
        drift = null
        idleSeconds = 0.0
        return position
    }

    /** 拖到 [p]，按本次手势起点 [from] 收边。 */
    fun dragTo(p: WallPoint, from: WallPoint): CamClamp {
        val c = clampCam(p, from, bounds)
        cx = c.point.x.toDouble()
        cy = c.point.y.toDouble()
        return c
    }

    /**
     * 松手甩出：[vx] / [vy] 是手指（也就是墙面内容）的速度 dp/s，镜头朝反方向走，速度按 e^(−4.2t) 衰减，
     * 低于 8dp/s 停；哪条轴碰到边界（按手势起点 [from] 放宽）就停哪条。
     */
    fun fling(vx: Float, vy: Float, from: WallPoint) {
        target = null
        fling = if (hypot(vx, vy) < FLING_STOP_SPEED) null else Fling(vx.toDouble(), vy.toDouble(), from.x.toDouble(), from.y.toDouble())
    }

    /**
     * 开始漂移。普通墙随机朝向；听歌模式（[alongBand]）朝左或朝右，沿着带子横着漂。
     * 一般不用直接调：[driftEnabled] 时空闲满 [DRIFT_AFTER] 秒 [step] 会自己开始。
     */
    fun startDrift(alongBand: Boolean = bounds != null) {
        fling = null
        val a0 = if (alongBand) (if (random.nextDouble() < 0.5) 0.0 else PI) else random.nextDouble() * 2 * PI
        drift = Drift(a0, cx, cy)
    }

    /**
     * 有操作：空闲计时归零、停下漂移。返回刚才是否在漂移——是的话这一下只用来唤醒，
     * 调用方把镜头弹回正在播放的大卡，不执行按键本身。
     */
    fun wake(): Boolean {
        val was = drift != null
        drift = null
        idleSeconds = 0.0
        return was
    }

    /** 推进 [dt] 秒；返回这一帧镜头是否动了。 */
    fun step(dt: Float): Boolean {
        val raw = max(0.0, dt.toDouble())
        val before = idleSeconds
        idleSeconds += raw
        // 卡顿太久（切后台回来）丢掉多出的时间，免得镜头一下跳很远
        var motion = min(raw, MAX_FRAME_DT)
        var moved = false
        if (driftEnabled && drift == null && before < DRIFT_AFTER && idleSeconds >= DRIFT_AFTER) {
            // 在帧中间到点：前半段照常走，后半段已经在漂，起步时刻不随帧率变
            val pre = min(motion, DRIFT_AFTER - before)
            moved = advance(pre)
            startDrift()
            motion -= pre
        }
        return advance(motion) || moved
    }

    private fun advance(total: Double): Boolean {
        var left = total
        var moved = false
        while (left > 1e-12) {
            val h = min(left, MAX_SUBSTEP)
            left -= h
            moved = when {
                target != null -> stepSpring(h)
                fling != null -> stepFling(h)
                drift != null -> stepDrift(h)
                else -> false
            } || moved
        }
        return moved
    }

    /** 临界阻尼弹簧 x'' = −ω²(x − T) − 2ωx' 的解析解：e(t) = (e₀ + (v₀ + ωe₀)t)·e^(−ωt)。 */
    private fun stepSpring(h: Double): Boolean {
        val t = target ?: return false
        val w = SPRING_OMEGA
        val k = exp(-w * h)
        val ox = cx - t.x
        val oy = cy - t.y
        val bx = svx + w * ox
        val by = svy + w * oy
        val nx = (ox + bx * h) * k
        val ny = (oy + by * h) * k
        svx = (svx - w * bx * h) * k
        svy = (svy - w * by * h) * k
        cx = t.x + nx
        cy = t.y + ny
        if (abs(nx) < SETTLE_DISTANCE && abs(ny) < SETTLE_DISTANCE && hypot(svx, svy) < SETTLE_SPEED) {
            cx = t.x.toDouble()
            cy = t.y.toDouble()
            target = null
            svx = 0.0
            svy = 0.0
        }
        return true
    }

    /** 惯性：速度 v₀e^(−kt)，这段时间走过 v₀(1 − e^(−kh))/k；速度降到 8dp/s 的那一刻精确停下。 */
    private fun stepFling(h: Double): Boolean {
        val f = fling ?: return false
        val speed = hypot(f.vx, f.vy)
        if (speed < FLING_STOP_SPEED) {
            fling = null
            return false
        }
        val untilStop = ln(speed / FLING_STOP_SPEED) / FLING_DECAY
        val hh = min(h, untilStop)
        val k = exp(-FLING_DECAY * hh)
        val travel = (1 - k) / FLING_DECAY
        cx -= f.vx * travel
        cy -= f.vy * travel
        val b = bounds
        if (b != null) {
            val nx = clampLooseD(cx, min(b.x0.toDouble(), f.fromX), max(b.x1.toDouble(), f.fromX))
            val ny = clampLooseD(cy, min(b.y0.toDouble(), f.fromY), max(b.y1.toDouble(), f.fromY))
            if (nx != cx) f.vx = 0.0
            if (ny != cy) f.vy = 0.0
            cx = nx
            cy = ny
        }
        f.vx *= k
        f.vy *= k
        if (hh >= untilStop) fling = null
        return true
    }

    /**
     * 漂移：方向是两条慢正弦叠加，一直在拐弯但没有急转；起步 3 秒内从 0 加速到 10dp/s。
     * 碰到边界那条轴掉头，越界的那一点按镜面折回界内。
     */
    private fun stepDrift(h: Double): Boolean {
        val d = drift ?: return false
        val tm = d.t + h / 2
        val ang = d.a0 + DRIFT_SWAY_1 * sin(tm / DRIFT_TURN_1) + DRIFT_SWAY_2 * sin(tm / DRIFT_TURN_2)
        val sp = DRIFT_SPEED * min(1.0, tm / DRIFT_RAMP)
        d.t += h
        var nx = cx + d.fx * cos(ang) * sp * h
        var ny = cy + d.fy * sin(ang) * sp * h
        val b = bounds
        if (b != null) {
            val lx = min(b.x0.toDouble(), d.fromX)
            val hx = max(b.x1.toDouble(), d.fromX)
            val ly = min(b.y0.toDouble(), d.fromY)
            val hy = max(b.y1.toDouble(), d.fromY)
            if (nx > hx || nx < lx) {
                nx = clampLooseD(if (nx > hx) 2 * hx - nx else 2 * lx - nx, lx, hx)
                d.fx = -d.fx
            }
            if (ny > hy || ny < ly) {
                ny = clampLooseD(if (ny > hy) 2 * hy - ny else 2 * ly - ny, ly, hy)
                d.fy = -d.fy
            }
        }
        cx = nx
        cy = ny
        return true
    }

    private class Fling(var vx: Double, var vy: Double, val fromX: Double, val fromY: Double)

    /** [a0] 起始朝向；[fx] / [fy] 碰边后翻成 −1；[fromX] / [fromY] 是开始漂移时的位置，收边按它放宽。 */
    private class Drift(val a0: Double, val fromX: Double, val fromY: Double) {
        var t = 0.0
        var fx = 1.0
        var fy = 1.0
    }

    companion object {
        /** 弹簧角频率：临界阻尼，约 0.45 秒到位（folia 是 0.42 秒 ease）。 */
        const val SPRING_OMEGA = 13.0
        const val SETTLE_DISTANCE = 0.3
        const val SETTLE_SPEED = 4.0

        /** 惯性衰减率 1/s 和停下的速度 dp/s。 */
        const val FLING_DECAY = 4.2
        const val FLING_STOP_SPEED = 8.0

        /** 电视：多少秒不操作浮层淡出、镜头开始漂移。 */
        const val IDLE_CHROME_AFTER = 6.0
        const val DRIFT_AFTER = 30.0

        /** 漂移速度 dp/s、起步加速时间 s。 */
        const val DRIFT_SPEED = 10.0
        const val DRIFT_RAMP = 3.0

        /** 朝向 = a0 + 0.9·sin(t/19) + 0.5·sin(t/31)，与原型同式。 */
        const val DRIFT_SWAY_1 = 0.9
        const val DRIFT_TURN_1 = 19.0
        const val DRIFT_SWAY_2 = 0.5
        const val DRIFT_TURN_2 = 31.0

        /** 单帧最多推进的时间、内部子步长。 */
        const val MAX_FRAME_DT = 0.25
        const val MAX_SUBSTEP = 0.05

        private fun clampLooseD(v: Double, lo: Double, hi: Double): Double = min(hi, max(lo, v))
    }
}
