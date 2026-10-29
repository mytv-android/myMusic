package com.mytv.mymusic.ui.poster

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 入场怎么动：整套（下落 + 放大 + 淡入）、电视只淡入（仍按波排延迟）、减少动画时统一淡入不排波。 */
internal enum class WaveStyle { Full, Fade, Plain }

/**
 * 一次入场波。墙打开、换队列重排时是 [Open]：按格子相对屏幕左上角的步数排延迟；
 * 听歌模式续上一批是 [Batch]：只有新到的第 [from] ..< [to] 首按序号排延迟，其余格子不动。
 */
internal sealed interface WallWave {
    val style: WaveStyle

    /** [cam] 是起波时的镜头位置，步数按它算，镜头之后怎么动都不影响。 */
    data class Open(val cam: WallPoint, override val style: WaveStyle) : WallWave

    data class Batch(val from: Int, val to: Int, override val style: WaveStyle) : WallWave
}

/**
 * 入场波的时间曲线，取原型：左上角起一道波，每格延迟 min(0.34s, 步数 × 0.03s)，步数 = (屏幕 x + 屏幕 y) / 格距；
 * 每格 0.62 秒从上方 60dp 落下、0.88 → 1、透明度 0 → 1，曲线是 defaultSpatial 弹簧（阻尼 0.8、刚度 380）压进 0.62 秒。
 * 纯函数，图层每帧按 [progress] 取值。
 */
internal object WallEntrance {
    const val STEP_DELAY = 0.03f
    const val MAX_DELAY = 0.34f
    const val LAND_DURATION = 0.62f
    const val PLAIN_DURATION = 0.2f
    const val RISE_DP = 60f
    const val FROM_SCALE = 0.88f

    /** 原型 springCurve(0.8, 380) 落定（|1 − x| < 0.0015 且 |v| < 0.02）的时刻，CSS 把这段弹簧拉满 0.62 秒。 */
    private const val SPRING_SETTLE = 0.457
    private const val DAMPING = 0.8
    private val OMEGA = sqrt(380.0)
    private val OMEGA_D = OMEGA * sqrt(1 - DAMPING * DAMPING)

    fun delayForSteps(steps: Float): Float = min(MAX_DELAY, max(0f, steps) * STEP_DELAY)

    /** [delayOf] 的“不参与”：续一批时已有的格子。用负数而不是 null，图层每帧调它时不装箱。 */
    const val NOT_IN_WAVE = -1f

    /** 这一格在这次波里的延迟（秒）；[NOT_IN_WAVE] 表示它不参与。 */
    fun delayOf(wave: WallWave, song: Int, rect: WallRect, pitch: Float): Float = when (wave) {
        is WallWave.Batch -> when {
            song < wave.from || song >= wave.to -> NOT_IN_WAVE
            wave.style == WaveStyle.Plain -> 0f
            else -> delayForSteps((song - wave.from).toFloat())
        }
        is WallWave.Open ->
            if (wave.style == WaveStyle.Plain) 0f else delayForSteps(((rect.x - wave.cam.x) + (rect.y - wave.cam.y)) / pitch)
    }

    /**
     * 延迟之后第 [t] 秒的进度：t ≤ 0 为 0，走完为 1；整套和只淡入走弹簧（中途略过 1），统一淡入是线性。
     * 透明度要自己夹到 0..1。
     */
    fun progress(style: WaveStyle, t: Float): Float = when {
        t <= 0f -> 0f
        style == WaveStyle.Plain -> min(1f, t / PLAIN_DURATION)
        t >= LAND_DURATION -> 1f
        else -> spring(t / LAND_DURATION * SPRING_SETTLE).toFloat()
    }

    /** 整道波走完的时刻（从起波算）。 */
    fun duration(style: WaveStyle): Float = if (style == WaveStyle.Plain) PLAIN_DURATION else MAX_DELAY + LAND_DURATION

    /** 欠阻尼弹簧从 0 到 1 的阶跃响应。 */
    private fun spring(tau: Double): Double {
        val k = exp(-DAMPING * OMEGA * tau)
        return 1 - k * (cos(OMEGA_D * tau) + DAMPING * OMEGA / OMEGA_D * sin(OMEGA_D * tau))
    }
}
