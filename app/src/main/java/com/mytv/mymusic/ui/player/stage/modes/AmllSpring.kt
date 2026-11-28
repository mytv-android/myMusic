/*
 * AMLL (Apple Music-like Lyrics), https://github.com/amll-dev/applemusic-like-lyrics
 * Copyright (C) the AMLL contributors (amll-dev)
 * Modified for myMusic on 2026-11-28 (Kotlin / Jetpack Compose rewrite); the changes are listed below.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * The spring solver below (amllSolve) carries the upstream note
 * "MIT License github.com/pushkine/"; its MIT notice is kept in THIRD_PARTY_NOTICES.md.
 */
// 2026-11-28 myMusic 改写：取自 AMLL core 0.7.2（@4d8e0270）的 utils/spring.ts、utils/derivative.ts 和 lyric-player/base/spring.ts。
// 求解（阻尼振子解析解）、延迟目标队列、到位判定（位置、一阶和二阶导数都小于 0.01）、按句间隔取刚度的规则照原样；
// 改动：时间单位直接用秒（原版用 Duration），求解器写成对象免得每次改目标都新建闭包；
// 原版能延迟改参数，但歌词播放器只用立即改，这里只保留立即改（立即改照样会清掉还没生效的延迟目标，和原版一样）。
// 另加两个小工具：CSS 过渡（AmllTween）和 AMLL 用到的几条贝塞尔缓动（AmllEase，用 Compose 自带的求解）。
package com.mytv.mymusic.ui.player.stage.modes

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 阻尼振子从 [from]（初速 [velocity]）走向 [to] 的解析解（AMLL utils/spring.ts 的 solveSpring，MIT · pushkine）。
 * 阻尼比 ≥ 1（或 [soft]）时按临界阻尼的式子算，否则按欠阻尼。[at] 的 t 是秒，t < 0 时停在起点。
 */
internal class AmllSolver {
    private var constant = true
    private var constantValue = 0.0
    private var critical = true
    private var from = 0.0
    private var to = 0.0
    private var delta = 0.0
    private var angular = 0.0
    private var leftover = 0.0
    private var dfm = 0.0
    private var dm = 0.0

    /** 恒等于 [value]（原版 setPosition 之后的 `() => targetPosition`）。 */
    fun hold(value: Double) {
        constant = true
        constantValue = value
    }

    fun solve(from: Double, velocity: Double, to: Double, mass: Double, damping: Double, stiffness: Double, soft: Boolean) {
        constant = false
        this.from = from
        this.to = to
        delta = to - from
        if (soft || 1.0 <= damping / (2.0 * sqrt(stiffness * mass))) {
            critical = true
            angular = -sqrt(stiffness / mass)
            leftover = -angular * delta - velocity
        } else {
            critical = false
            val dampingFrequency = sqrt(4.0 * mass * stiffness - damping.pow(2.0))
            leftover = (damping * delta - 2.0 * mass * velocity) / dampingFrequency
            dfm = 0.5 * dampingFrequency / mass
            dm = -(0.5 * damping) / mass
        }
    }

    fun at(t: Double): Double {
        if (constant) return constantValue
        if (t < 0.0) return from
        return if (critical) {
            to - (delta + t * leftover) * exp(t * angular)
        } else {
            to - (cos(t * dfm) * delta + sin(t * dfm) * leftover) * exp(t * dm)
        }
    }

    /** 速度：中心差分，步长 0.001 秒（utils/derivative.ts）。 */
    fun velocity(t: Double): Double = if (constant) 0.0 else (at(t + H) - at(t - H)) / (2 * H)

    /** 加速度：对速度再做一次中心差分。 */
    fun acceleration(t: Double): Double = if (constant) 0.0 else (velocity(t + H) - velocity(t - H)) / (2 * H)

    private companion object {
        const val H = 0.001
    }
}

/**
 * AMLL 的弹簧（utils/spring.ts 的 Spring）：目标可以带延迟（排队，到点才生效），改目标时从当前位置和速度接着走。
 * 参数没设过时按求解器的默认值（质量 1、阻尼 10、刚度 100）。
 */
internal class AmllSpring(position: Double = 0.0) {
    var position: Double = position
        private set
    var target: Double = position
        private set
    private var time = 0.0
    private var mass = 1.0
    private var damping = 10.0
    private var stiffness = 100.0
    private var soft = false
    private val solver = AmllSolver().apply { hold(position) }
    private var queued = false
    private var queuedTarget = 0.0
    private var queuedTime = 0.0

    /** 到位：离目标、速度、加速度都小于 0.01，而且没有排队的目标。 */
    fun arrived(): Boolean =
        abs(target - position) < 0.01 &&
            abs(solver.velocity(time)) < 0.01 &&
            abs(solver.acceleration(time)) < 0.01 &&
            !queued

    /** 直接放到 [value]，速度清零。 */
    fun setPosition(value: Double) {
        target = value
        position = value
        solver.hold(value)
        queued = false
    }

    /** 走 [dt] 秒：先按当前解推位置，再看排队的目标到没到点。 */
    fun update(dt: Double) {
        time += dt
        position = solver.at(time)
        if (queued) {
            queuedTime -= dt
            if (queuedTime <= 0.0) setTarget(queuedTarget)
        }
        if (arrived()) setPosition(target)
    }

    /** 改参数（null 的项不变），立即生效：清掉排队的目标，从当前位置和速度重新求解（原版 updateParams 不带延迟时的行为）。 */
    fun setParams(stiffness: Double? = null, damping: Double? = null, mass: Double? = null) {
        queued = false
        stiffness?.let { this.stiffness = it }
        damping?.let { this.damping = it }
        mass?.let { this.mass = it }
        resetSolver()
    }

    /**
     * 改目标。[delay] 秒后才生效（排队，后来的覆盖先来的）；立即改且和现在的目标差不到 0.001 时只清掉排队、不重新求解。
     */
    fun setTarget(value: Double, delay: Double = 0.0) {
        if (delay <= 0.0 && abs(target - value) < 0.001) {
            queued = false
            return
        }
        if (delay > 0.0) {
            queued = true
            queuedTarget = value
            queuedTime = delay
        } else {
            queued = false
            target = value
            resetSolver()
        }
    }

    private fun resetSolver() {
        val velocity = solver.velocity(time)
        time = 0.0
        solver.solve(position, velocity, target, mass, damping, stiffness, soft)
    }
}

/** 纵向弹簧的参数：刚度、阻尼（质量另设，句子用 0.9）。 */
internal class AmllSpringPolicy(val stiffness: Double, val damping: Double)

/**
 * 句子纵向滚动的弹簧（lyric-player/base/spring.ts 的 getPosYSpringPolicy）：跳转、间奏、第一句（[intervalMs] 为 null）
 * 用慢弹簧 90 / 15；播完用 140 / 22；正常播放按这句和上一句的开始间隔（夹到 100–800ms）取刚度，间隔越短越硬：
 * 比例 1 − (间隔 − 100) / 700 开五次方根，刚度在 170–220 之间插值，阻尼 = 2.2 × √刚度。
 */
internal fun amllPosYPolicy(seeking: Boolean, interlude: Boolean, intervalMs: Double?, endOfSong: Boolean = false): AmllSpringPolicy {
    if (seeking || interlude) return AmllSpringPolicy(AMLL_SLOW_STIFFNESS, AMLL_SLOW_DAMPING)
    if (endOfSong) return AmllSpringPolicy(AMLL_MEDIUM_STIFFNESS, AMLL_MEDIUM_DAMPING)
    if (intervalMs == null) return AmllSpringPolicy(AMLL_SLOW_STIFFNESS, AMLL_SLOW_DAMPING)
    val clamped = min(max(intervalMs, AMLL_MIN_INTERVAL), AMLL_MAX_INTERVAL)
    var ratio = 1 - (clamped - AMLL_MIN_INTERVAL) / (AMLL_MAX_INTERVAL - AMLL_MIN_INTERVAL)
    ratio = ratio.pow(AMLL_INTERVAL_EXPONENT)
    val stiffness = AMLL_MIN_STIFFNESS + ratio * (AMLL_MAX_STIFFNESS - AMLL_MIN_STIFFNESS)
    return AmllSpringPolicy(stiffness, sqrt(stiffness) * AMLL_DAMPING_MULTIPLIER)
}

/**
 * CSS 过渡：改目标时从当前值出发，按 [Easing] 走 [duration] 秒（原版靠 CSS transition 做的透明度、模糊、亮暗两层交叉淡入）。
 */
internal class AmllTween(initial: Float) {
    var value: Float = initial
        private set
    var target: Float = initial
        private set
    private var from = initial
    private var elapsed = 0f
    private var duration = 0f
    private var easing: Easing = AmllEase.css

    val running: Boolean get() = value != target

    fun animateTo(next: Float, seconds: Float, curve: Easing) {
        if (next == target) return
        from = value
        target = next
        elapsed = 0f
        duration = seconds
        easing = curve
        if (seconds <= 0f) value = next
    }

    fun snap() {
        value = target
    }

    fun snapTo(next: Float) {
        target = next
        value = next
    }

    fun update(dt: Float) {
        if (value == target) return
        elapsed += dt
        value = if (elapsed >= duration) target else from + (target - from) * easing.transform(elapsed / duration)
    }
}

/** AMLL 用到的缓动曲线。 */
internal object AmllEase {
    /** CSS 的 ease：透明度、模糊过渡。 */
    val css: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** CSS 的 ease-out：逐词上浮、亮暗两层交叉淡入。 */
    val cssOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** 强调的前半段（放大发光）和后半段（收回）。 */
    val emphasisIn: Easing = CubicBezierEasing(0.2f, 0.4f, 0.58f, 1f)
    val emphasisOut: Easing = CubicBezierEasing(0.3f, 0f, 0.58f, 1f)

    /** 间奏三点：点亮、进场淡入、退场蓄力、退场收缩、退场淡出。 */
    val dotLighting: Easing = CubicBezierEasing(0.56f, 0.01f, 0.45f, 1f)
    val dotEnter: Easing = CubicBezierEasing(0.59f, 0.02f, 0.07f, 1f)
    val dotExit1: Easing = CubicBezierEasing(0.14f, 0.06f, 0.25f, 1f)
    val dotExit2: Easing = CubicBezierEasing(0.29f, 0.03f, 1f, 0.38f)
    val dotFade: Easing = CubicBezierEasing(0.43f, 0.08f, 0.83f, 0.31f)
}

internal fun amllClamp01(x: Double): Double = min(1.0, max(0.0, x))

internal fun amllClamp01(x: Float): Float = min(1f, max(0f, x))

internal const val AMLL_SLOW_STIFFNESS = 90.0
internal const val AMLL_SLOW_DAMPING = 15.0
internal const val AMLL_MEDIUM_STIFFNESS = 140.0
internal const val AMLL_MEDIUM_DAMPING = 22.0
private const val AMLL_MIN_INTERVAL = 100.0
private const val AMLL_MAX_INTERVAL = 800.0
private const val AMLL_MIN_STIFFNESS = 170.0
private const val AMLL_MAX_STIFFNESS = 220.0
private const val AMLL_DAMPING_MULTIPLIER = 2.2
private const val AMLL_INTERVAL_EXPONENT = 0.2
