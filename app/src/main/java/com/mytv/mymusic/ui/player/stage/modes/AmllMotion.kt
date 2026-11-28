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
 */
// 2026-11-28 myMusic 改写：取自 AMLL core 0.7.2（@4d8e0270）的 lyric-player/base/spring.ts 的延迟目标队列
// （到点才生效、后来的覆盖先来的、立即改目标会清掉还没生效的那个）。
// 改动：AMLL 每句一个弹簧（按句间隔取刚度）。对照 Apple Music（iOS 16.2 录屏逐帧拟合：每句都是 430ms 的
// cubic-bezier(0.25, 0.1, 0.25, 1)、不回弹、换句距离不同时长不变；Android 7.0.3 的换句插值器是同一条曲线）
// 换成固定时长的贝塞尔过渡（AmllGlide）。改目标时按 Core Animation 的叠加动画处理：新的一段从 0 走到差值、
// 和还没走完的几段叠在一起，所以中途改目标不跳、速度连续。弹簧求解器（上游注明 MIT · pushkine）整个删掉了。
// 另有 CSS 过渡（AmllTween）和几条贝塞尔缓动（AmllEase，用 Compose 自带的求解）。
package com.mytv.mymusic.ui.player.stage.modes

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 固定时长的叠加过渡：每改一次目标加一段（从 0 走到新旧目标的差，[seconds] 秒，按 [easing]），
 * 位置 = 目标 − 每段还没走完的部分。目标可以带延迟（排队，到点才生效，后来的覆盖先来的）。
 */
internal class AmllGlide(position: Double = 0.0, private var seconds: Double = AMLL_GLIDE_SECONDS, private var easing: Easing = AmllEase.css) {
    var position: Double = position
        private set
    var target: Double = position
        private set
    private val deltas = DoubleArray(MAX_SEGMENTS)
    private val elapsed = DoubleArray(MAX_SEGMENTS)
    private var segments = 0
    private var queued = false
    private var queuedTarget = 0.0
    private var queuedTime = 0.0

    /** 到位：没有在走的段，也没有排队的目标。 */
    fun arrived(): Boolean = segments == 0 && !queued

    /** 换时长和曲线（下一段起生效，已经在走的段照旧）。 */
    fun setCurve(seconds: Double, easing: Easing) {
        this.seconds = seconds
        this.easing = easing
    }

    /** 直接放到 [value]，清掉在走的段和排队的目标。 */
    fun setPosition(value: Double) {
        target = value
        position = value
        segments = 0
        queued = false
    }

    /** 改目标。[delay] 秒后才生效（排队，后来的覆盖先来的）；立即改会清掉排队的目标。 */
    fun setTarget(value: Double, delay: Double = 0.0) {
        if (delay > 0.0) {
            queued = true
            queuedTarget = value
            queuedTime = delay
            return
        }
        queued = false
        retarget(value, 0.0)
    }

    /** 走 [dt] 秒：在走的段往前推，排队的目标到点就加一段（从到点那一刻算起）。 */
    fun update(dt: Double) {
        for (k in 0 until segments) elapsed[k] += dt
        if (queued) {
            queuedTime -= dt
            if (queuedTime <= 0.0) {
                queued = false
                retarget(queuedTarget, min(dt, -queuedTime))
            }
        }
        settle()
    }

    private fun retarget(value: Double, startedAgo: Double) {
        val delta = value - target
        if (abs(delta) < 0.001) return
        if (seconds <= 0.0) {
            setPosition(value)
            return
        }
        if (segments == MAX_SEGMENTS) {
            // 段太多（短时间里连着改了很多次）：从此刻的位置重新起一段，不跳，只是丢掉一点速度
            val here = position
            segments = 0
            target = here
            deltas[0] = value - here
        } else {
            deltas[segments] = delta
        }
        elapsed[segments] = startedAgo
        segments++
        target = value
        settle()
    }

    private fun settle() {
        var k = 0
        var left = 0.0
        while (k < segments) {
            val f = elapsed[k] / seconds
            if (f >= 1.0) {
                // 走完的段移掉（后面的往前挪）
                for (j in k until segments - 1) {
                    deltas[j] = deltas[j + 1]
                    elapsed[j] = elapsed[j + 1]
                }
                segments--
                continue
            }
            left += deltas[k] * (1.0 - easing.transform(max(0.0, f).toFloat()))
            k++
        }
        position = target - left
    }

    private companion object {
        const val MAX_SEGMENTS = 8
    }
}

/**
 * CSS 过渡：改目标时从当前值出发，按 [Easing] 走 [duration] 秒（透明度、模糊、亮暗两层交叉淡入）。
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

/** 用到的缓动曲线。 */
internal object AmllEase {
    /**
     * CSS 的 ease，也是 Apple Music 换句的曲线：iOS 录屏拟合出来的就是它（误差 0.6–0.8px），
     * Android 版歌词页的插值器常量也是它。句子位移、缩放、三点位置、透明度、模糊、亮暗都用它。
     */
    val css: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** CSS 的 ease-out：逐词上浮。 */
    val cssOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** CSS 的 ease-in-out：和声行展开收起（Apple 网页版 0.3 秒）。 */
    val cssInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** 强调的前半段（放大发光）和后半段（收回）。 */
    val emphasisIn: Easing = CubicBezierEasing(0.2f, 0.4f, 0.58f, 1f)
    val emphasisOut: Easing = CubicBezierEasing(0.3f, 0f, 0.58f, 1f)

    /** 间奏三点：每颗点亮（Apple Android 版的 PathInterpolator(0, 0.25, 1, 0.58)）、进场淡入。 */
    val dotLighting: Easing = CubicBezierEasing(0f, 0.25f, 1f, 0.58f)
    val dotEnter: Easing = CubicBezierEasing(0.59f, 0.02f, 0.07f, 1f)

    /** 三点退场：先用 [css] 放大，再按这条缩小并淡出（Apple Android 版 PathInterpolator(0.25, 0, 1, 0.2)）。 */
    val dotExit: Easing = CubicBezierEasing(0.25f, 0f, 1f, 0.2f)
}

internal fun amllClamp01(x: Double): Double = min(1.0, max(0.0, x))

internal fun amllClamp01(x: Float): Float = min(1f, max(0f, x))

/** 换句时每句的位移时长（秒）：iOS 录屏两次换句都是 430ms。 */
internal const val AMLL_GLIDE_SECONDS = 0.43
