package com.mytv.mymusic.ui.player.stage.modes

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 动感波光、黑胶唱片共用的小工具。两个模组同一作者（pomepii），取色和入场写法一样：
 * 封面主色的归一化（原模组 dominantColor 的最后一步）、HSL 换算、CSS 模糊换算、句子入场计时。
 * 都是纯计算，单元测试直接调；碰 Android 的模糊缓存另放在 [WaveVinylBlur]。
 */
internal object WaveVinylKit {
    /** 原模组入场动画用的 cubic-bezier(0.2, 0.8, 0.3, 1)。 */
    val EnterEasing = CubicBezierEasing(0.2f, 0.8f, 0.3f, 1f)

    /** [Entrance] 完成后返回的“已过去”毫秒数，比任何入场都长。 */
    const val DONE_MS = 1e9f

    /** 颜色的 HSL，h 在 0..1，和原模组的 rgbToHsl 一样。 */
    fun hsl(color: Color): FloatArray {
        val r = color.red
        val g = color.green
        val b = color.blue
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val l = (hi + lo) / 2f
        if (hi == lo) return floatArrayOf(0f, 0f, l)
        val d = hi - lo
        val s = if (l > 0.5f) d / (2f - hi - lo) else d / (hi + lo)
        val h = when (hi) {
            r -> ((g - b) / d + if (g < b) 6f else 0f) / 6f
            g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        return floatArrayOf(h, s, l)
    }

    /** HSL（h 在 0..1）换回颜色。 */
    fun fromHsl(h: Float, s: Float, l: Float, alpha: Float = 1f): Color =
        Color.hsl((h * 360f).mod(360f), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))

    /**
     * 封面主色归一成能用的色：饱和度 ×1.2+0.08 夹到 0.42–0.85，明度夹到 0.46–0.62。
     * 原模组对封面缩图加权平均后做的就是这一步；这里的输入是宿主已经量化好的封面主色。
     */
    fun dominant(color: Color): Color {
        val (h, s, l) = hsl(color)
        return fromHsl(h, (s * 1.2f + 0.08f).coerceIn(0.42f, 0.85f), l.coerceIn(0.46f, 0.62f))
    }

    /** RGB 各乘 [k]（原模组 scaleRgb：把颜色压暗）。 */
    fun scale(color: Color, k: Float): Color =
        Color((color.red * k).coerceIn(0f, 1f), (color.green * k).coerceIn(0f, 1f), (color.blue * k).coerceIn(0f, 1f), color.alpha)

    /**
     * CSS 的模糊标准差（filter: blur(σ)，或 shadowBlur / text-shadow 的一半）换成 Android 的模糊半径：
     * RenderEffect、setShadowLayer、BlurMaskFilter 都按半径收，σ = 0.57735·r + 0.5。
     */
    fun sigmaToRadius(sigma: Float): Float = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0f)

    /**
     * 一句的入场计时：第一次在绘制阶段被问到时记下动画时钟，之后按动画时钟算过了多少毫秒。
     * 暂停、减少动态效果时直接算入场完成，不会卡在半透明；完成后不再读时钟，图层也就不再逐帧重画。
     * 用动画时钟而不是自己的帧循环：电视上跟着宿主限 30 帧。
     */
    class Entrance(private val totalMs: Float) {
        private var startS = Float.NaN
        var done = false
            private set

        /** 在绘制阶段（graphicsLayer / onDraw）调用。 */
        fun elapsedMs(ctx: LyricStageContext): Float = when {
            done -> DONE_MS
            ctx.display.reduceMotion || ctx.paused.value -> finish()
            else -> advance(ctx.clock.seconds)
        }

        /** 动画时钟走到 [nowS] 秒时，入场过去了多少毫秒；第一次调用时记为起点。 */
        fun advance(nowS: Float): Float {
            if (done) return DONE_MS
            if (startS.isNaN()) startS = nowS
            val elapsed = (nowS - startS) * 1000f
            if (elapsed >= totalMs) done = true
            return elapsed.coerceAtLeast(0f)
        }

        /** 直接算入场完成（首屏不播入场、暂停、减少动态效果）。 */
        fun finish(): Float {
            done = true
            return DONE_MS
        }
    }
}

/**
 * Android 12+ 的模糊效果缓存。逐帧变化的模糊按半径 0.5px 取整复用同一个对象，不每帧新建 RenderEffect。
 * 只在主线程（布局、绘制阶段）用。
 */
internal object WaveVinylBlur {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 31

    private const val STEPS = 240
    private val cache = arrayOfNulls<RenderEffect>(STEPS)

    /** 标准差 [sigma]（px）的模糊；小到看不出（半径不到 0.5px）时返回 null，图层就不必离屏。 */
    fun of(sigma: Float): RenderEffect? {
        if (!supported) return null
        val radius = WaveVinylKit.sigmaToRadius(sigma)
        if (radius < 0.5f) return null
        val step = (radius * 2f).roundToInt().coerceAtMost(STEPS - 1)
        return cache[step] ?: BlurEffect(step / 2f, step / 2f, TileMode.Decal).also { cache[step] = it }
    }
}
