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
// 2026-11-28 myMusic 改写：取自 AMLL core 0.7.2（@4d8e0270）的 lyric-player/base/interlude-dots.ts（InterludeDotsBase）
// 和 lyric-player/dom/interlude-dots.ts、styles/lyric-player.module.css 的 .interludeDots。
// 进场（非开头的空档先等 0.5 秒、整组 180ms 淡入、三颗点各错开 80ms 用 750ms 淡入）、三段点亮（第三颗留 750ms 给退场补满）、
// 按主体时长取整周期的呼吸（主体不足 3 秒不呼吸）、退场先放大再缩小淡出、打断时 150ms 淡出照原样。
// 改动：演出时钟每帧直接对齐舞台的歌词时间，原版是按帧间隔累加再在推送进度时校准；画法从 DOM 换成 Canvas 画三个圆
// （点 0.3 字号、间距 0.18 字号，和 Apple Android 版 34sp 字配 10dp 点、6dp 间距一致）。
// 2026-11-28 按 Apple Music Android 7.0.3（player/A.java）改数值：呼吸 1 ↔ 1.2 倍（原版 1.25）；每颗点亮的曲线换成
// PathInterpolator(0, 0.25, 1, 0.58)，点亮后 85% 白（原版 90%）；退场 750ms 按 cubic-bezier(0.25, 0.1, 0.25, 1) 放大到 1.2 倍、
// 再 250ms 按 (0.25, 0, 1, 0.2) 缩到 0.5 倍并淡出（原版 0.4 倍、另三条曲线）；位置跟句子一样走 430ms 的贝塞尔过渡。
package com.mytv.mymusic.ui.player.stage.modes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * 间奏三点的演出状态。[setInterlude] 排一段演出，之后每帧 [update]；当前帧的样子在 [active]、[opacity]、[scale]、
 * [dotOpacities] 里，位置是 [left] 和 [posY]（弹簧平滑过的纵向位置，dp）。
 */
internal class AmllDots {
    val posY = AmllGlide(0.0)
    var left = 0.0
        private set
    private var top = 0.0
    private var snapNext = true
    private var playing = true
    private var phase = IDLE
    private var breathe = true
    private var fadeElapsedMs = 0.0
    private var fadeInitialOpacity = 0f
    private var startTime = 0.0
    private var endTime = 0.0
    private var anchorTime = 0.0
    private var currentTime = 0.0
    private var delayEndMs = 0.0
    private var bodyEndMs = 0.0
    private var totalEndMs = 0.0
    private var breathePeriodMs = BREATHE_BASE_PERIOD_MS
    private var segmentMs = 0.0
    private var dot3DurationMs = 0.0
    private var dot3Target = 0.0

    var active = false
        private set
    var scale = 1f
        private set
    var opacity = 0f
        private set
    val dotOpacities = FloatArray(3)

    /** 有演出（进行中或正在淡出）。 */
    val showing: Boolean get() = phase != IDLE

    /**
     * 排一段间奏 [startMs]..[endMs]。[forceReset]（跳转、换歌）时从 [currentMs] 重新计时并先等 0.5 秒；否则从间奏开头算，
     * 开头的间奏（[anchor] = −1）不等。返回这段间奏够不够长、要不要显示三点。
     */
    fun setInterlude(startMs: Double, endMs: Double, currentMs: Double, forceReset: Boolean, anchor: Int): Boolean {
        val same = startTime == startMs && endTime == endMs
        if (!forceReset && same && phase != FADING) return phase == PERFORMING
        cancelFadeOut()
        startTime = startMs
        endTime = endMs
        currentTime = currentMs
        if (forceReset) {
            anchorTime = currentTime
            delayEndMs = ENTER_HOLD_MS
        } else {
            anchorTime = startMs
            delayEndMs = if (anchor == -1) 0.0 else ENTER_HOLD_MS
        }
        val remaining = max(0.0, endMs - anchorTime)
        val bodyMs = remaining - delayEndMs - EXIT_TOTAL_MS
        if (bodyMs < DOT_ENTER_TOTAL_MS) {
            hidePerformance()
            return false
        }
        bodyEndMs = delayEndMs + bodyMs
        totalEndMs = bodyEndMs + EXIT_TOTAL_MS
        if (bodyMs < FALLBACK_HOLD_THRESHOLD_MS) {
            breathe = false
            dot3Target = 1.0
        } else {
            breathe = true
            val cycles = max(1.0, floor(bodyMs / BREATHE_BASE_PERIOD_MS))
            breathePeriodMs = bodyMs / cycles
            // JS 的 Math.round：.5 往正无穷舍入
            segmentMs = floor((bodyMs + DOT3_TRAILING_MS) / DOT_COUNT + 0.5)
            dot3DurationMs = bodyMs - segmentMs * 2
            dot3Target = dot3DurationMs / segmentMs
        }
        phase = PERFORMING
        snapNext = true
        return true
    }

    /** 清掉间奏区间并收场（默认 150ms 淡出），之后再进同一段间奏会重新演出。 */
    fun clearInterlude(immediate: Boolean = false) {
        startTime = 0.0
        endTime = 0.0
        dismiss(immediate)
    }

    fun dismiss(immediate: Boolean = false) {
        if (phase == IDLE) return
        if (immediate || !playing) {
            cancelFadeOut()
            hidePerformance()
            return
        }
        if (phase == FADING) return
        if (opacity <= 0f) {
            hidePerformance()
            return
        }
        fadeElapsedMs = 0.0
        fadeInitialOpacity = opacity
        phase = FADING
    }

    /** 摆到（[left], [top]）（dp）；演出进行中走过渡，刚排好的第一帧和 [immediate] 时直接放。 */
    fun setTransform(left: Double, top: Double, immediate: Boolean) {
        this.left = left
        this.top = top
        if (!immediate && phase != IDLE && !snapNext) {
            posY.setTarget(top)
        } else {
            snapNext = false
            posY.setPosition(top)
        }
        update(0.0)
    }

    fun pause() {
        playing = false
        if (phase == FADING) hidePerformance()
    }

    fun resume() {
        playing = true
    }

    /** 对齐到歌词时间 [ms]（毫秒）。 */
    fun syncClock(ms: Double) {
        currentTime = ms
    }

    /** 推进一帧（[deltaMs] 毫秒）。 */
    fun update(deltaMs: Double) {
        if (phase == IDLE) return
        if (phase == FADING) {
            fadeElapsedMs += deltaMs
            val progress = amllClamp01(fadeElapsedMs / DISMISS_FADE_MS)
            if (progress >= 1.0) {
                hidePerformance()
                return
            }
            opacity = fadeInitialOpacity * (1f - AmllEase.dotExit.transform(progress.toFloat()))
            active = true
            posY.update(deltaMs / 1000.0)
            return
        }
        resolve(max(0.0, currentTime - anchorTime))
        posY.update(deltaMs / 1000.0)
        if (!active) phase = IDLE
    }

    /** 离演出锚点 [elapsedMs] 时的样子（resolveSnapshot）。 */
    private fun resolve(elapsedMs: Double) {
        if (elapsedMs >= totalEndMs) {
            active = false
            scale = 1f
            opacity = 0f
            dotOpacities.fill(0f)
            return
        }
        active = true
        if (elapsedMs < delayEndMs) {
            opacity = 0f
            scale = 1f
            dotOpacities.fill(0f)
            return
        }
        val internal = elapsedMs - delayEndMs
        if (elapsedMs >= bodyEndMs) {
            val exit = elapsedMs - bodyEndMs
            val fadeT = amllClamp01((exit - (EXIT_TOTAL_MS - EXIT_FADE_MS)) / EXIT_FADE_MS)
            opacity = enterOpacity(internal) * (1f - AmllEase.dotExit.transform(fadeT.toFloat()))
            scale = if (exit < EXIT_PHASE1_MS) {
                1f + AmllEase.css.transform((exit / EXIT_PHASE1_MS).toFloat()) * (BREATHE_MAX_SCALE - 1f)
            } else {
                val t = amllClamp01((exit - EXIT_PHASE1_MS) / EXIT_PHASE2_MS)
                BREATHE_MAX_SCALE - AmllEase.dotExit.transform(t.toFloat()) * (BREATHE_MAX_SCALE - EXIT_MIN_SCALE)
            }
            val trailing = amllClamp01(exit / DOT3_TRAILING_MS)
            writeDots(internal, 1.0, 1.0, dot3Target + (1 - dot3Target) * trailing)
            return
        }
        opacity = enterOpacity(internal)
        if (!breathe) {
            scale = 1f
            writeDots(internal, 1.0, 1.0, 1.0)
            return
        }
        val cycleT = (internal % breathePeriodMs) / breathePeriodMs
        val progress = amllBreathing(cycleT)
        scale = if (progress <= 0.5) {
            (1 + progress / 0.5 * (BREATHE_MAX_SCALE - 1)).toFloat()
        } else {
            (BREATHE_MAX_SCALE - (progress - 0.5) / 0.5 * (BREATHE_MAX_SCALE - 1)).toFloat()
        }
        writeDots(
            internal,
            dotFraction(0.0, segmentMs, internal, 1.0),
            dotFraction(segmentMs, segmentMs, internal, 1.0),
            dotFraction(segmentMs * 2, dot3DurationMs, internal, dot3Target),
        )
    }

    private fun writeDots(internal: Double, a: Double, b: Double, c: Double) {
        dotOpacities[0] = dotOpacity(a) * dotEnterAlpha(0, internal)
        dotOpacities[1] = dotOpacity(b) * dotEnterAlpha(1, internal)
        dotOpacities[2] = dotOpacity(c) * dotEnterAlpha(2, internal)
    }

    private fun cancelFadeOut() {
        if (phase == FADING) phase = IDLE
    }

    private fun hidePerformance() {
        phase = IDLE
        active = false
        opacity = 0f
        scale = 1f
        dotOpacities.fill(0f)
    }

    private companion object {
        const val IDLE = 0
        const val PERFORMING = 1
        const val FADING = 2
        const val DOT3_TRAILING_MS = 750.0
        const val EXIT_PHASE1_MS = 750.0
        const val EXIT_PHASE2_MS = 250.0
        const val EXIT_TOTAL_MS = EXIT_PHASE1_MS + EXIT_PHASE2_MS
        const val EXIT_FADE_MS = 250.0
        const val DISMISS_FADE_MS = 150.0
        const val ENTER_HOLD_MS = 500.0
        const val ENTER_FADE_MS = 180.0
        const val DOT_ENTER_FADE_MS = 750.0
        const val DOT_ENTER_STAGGER_MS = 80.0
        const val DOT_ENTER_TOTAL_MS = 2 * DOT_ENTER_STAGGER_MS + DOT_ENTER_FADE_MS
        const val BREATHE_BASE_PERIOD_MS = 4000.0
        const val FALLBACK_HOLD_THRESHOLD_MS = 3000.0
        const val BREATHE_MAX_SCALE = 1.2f
        const val EXIT_MIN_SCALE = 0.5f
        const val DOT_INACTIVE_OPACITY = 0.2f
        const val DOT_ACTIVE_OPACITY = 0.85f
        const val DOT_COUNT = 3

        fun dotOpacity(fraction: Double): Float = DOT_INACTIVE_OPACITY + (DOT_ACTIVE_OPACITY - DOT_INACTIVE_OPACITY) * amllClamp01(fraction).toFloat()

        fun enterOpacity(internal: Double): Float = AmllEase.dotEnter.transform(amllClamp01(internal / ENTER_FADE_MS).toFloat())

        fun dotEnterAlpha(index: Int, internal: Double): Float {
            val t = amllClamp01((internal - index * DOT_ENTER_STAGGER_MS) / DOT_ENTER_FADE_MS).toFloat()
            return t * t
        }

        fun dotFraction(startDelay: Double, duration: Double, internal: Double, target: Double): Double {
            if (internal <= startDelay) return 0.0
            val local = (internal - startDelay) / duration
            return AmllEase.dotLighting.transform(amllClamp01(local).toFloat()) * target
        }
    }
}

/** 呼吸曲线（breathingProgress）：在 t 上叠了一点正弦，让放大和缩小都先慢后快再慢（Apple Android 版也是查表的 S 形曲线）。 */
internal fun amllBreathing(t: Double): Double {
    if (t <= 0) return 0.0
    if (t >= 1) return 1.0
    val angle = 4.0 * PI * t
    val s = sin(angle)
    val c = cos(angle)
    return t - 0.084 * s + 0.008 * (1 - c) + 0.0046 * s * (c - s)
}

/**
 * 三点的尺寸（dp，按字号 [fontDp] 和视口高 [viewportDp]）：点 0.3 字号、间距 0.18 字号，内容高 clamp(0.5 字号, 1% 视口高, 3 字号)，
 * 上下各留 0.4 字号、左右留 [padX]（和句子的左右留白一样）。
 */
internal class AmllDotsSize(fontDp: Double, viewportDp: Double, padX: Double) {
    val dot = fontDp * 0.3
    val gap = fontDp * 0.18
    val contentHeight = (viewportDp / 100.0).coerceIn(fontDp * 0.5, fontDp * 3.0)
    val width = padX * 2 + dot * 3 + gap * 2
    val height = fontDp * 0.8 + contentHeight
    val padX = padX
}

/**
 * 画三点：容器绕中心缩放 [AmllDots.scale]，整体透明度 [AmllDots.opacity]，每颗点各自的透明度。[unit] 是 dp → px。
 * [still]（减少动画）时有演出就画成静止的三颗 85% 的点。
 */
internal fun DrawScope.drawAmllDots(dots: AmllDots, size: AmllDotsSize, color: Color, unit: Float, still: Boolean = false) {
    val alpha = if (still) (if (dots.showing) 1f else 0f) else dots.opacity
    if (alpha <= 0.001f) return
    val w = (size.width * unit).toFloat()
    val h = (size.height * unit).toFloat()
    val s = if (still) 1f else dots.scale
    val cx = w / 2f
    val cy = h / 2f
    val radius = (size.dot * unit / 2).toFloat()
    for (i in 0 until 3) {
        val x = ((size.padX + size.dot / 2 + i * (size.dot + size.gap)) * unit).toFloat()
        val a = ((if (still) STILL_DOT_OPACITY else dots.dotOpacities[i]) * alpha).coerceIn(0f, 1f)
        if (a <= 0.001f) continue
        drawCircle(color.copy(alpha = color.alpha * a), radius * s, Offset(cx + (x - cx) * s, cy))
    }
}

private const val STILL_DOT_OPACITY = 0.85f
