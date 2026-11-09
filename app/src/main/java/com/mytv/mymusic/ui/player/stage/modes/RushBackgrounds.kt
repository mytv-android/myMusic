/*
 * Copyright (C) 2026  Shubham Gorai
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
// 2026-11-09 myMusic 改写：取自 Rush 7.2.1 的 Util.kt（ApplyLyricsBackground）、HypnoticVisualizer、CurveVisualizer、
// WaveVisualizer、GradientVisualizer。两个 AGSL 着色器和三种律动的画法、弹簧刚度照原样；改动：律动输入从系统 Visualizer
// 的波形字节换成舞台的低 / 中 / 高频（0..1，按交互稿换算到原来的 0..128 刻度），不需要录音权限；
// RuntimeShader 只建一次、每帧只更新 uniform（原版每次重组都新建）；弹簧在绘制阶段按舞台时钟积分，减少动画时背景停住。
package com.mytv.mymusic.ui.player.stage.modes

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.ui.player.loadBackdropCover
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import com.mytv.mymusic.ui.player.stage.StageAudio
import com.mytv.mymusic.ui.player.stage.StageClock
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 背景用到的颜色，都是带过渡的状态，只在绘制阶段读。 */
internal class RushBackgroundColors(
    val background: State<Color>,
    val wave: State<Color>,
    val hypnoticLight: State<Color>,
    val hypnoticDark: State<Color>,
)

/** 按设置画背景：纯色、封面（模糊后盖 50% 底色）、流光，或三种律动之一。 */
@Composable
internal fun RushBackground(ctx: LyricStageContext, look: RushLook, colors: RushBackgroundColors, modifier: Modifier) {
    when (look.background) {
        RUSH_BG_COVER -> RushCoverBackground(ctx.song.cover, colors.background, modifier)
        RUSH_BG_HYPNOTIC -> RushHypnoticBackground(ctx.clock, colors, modifier)
        RUSH_BG_RHYTHM -> when (look.rhythm) {
            RUSH_RHYTHM_GRADIENT -> RushGradientBackground(ctx.clock, ctx.audio, colors, modifier)
            RUSH_RHYTHM_CURVE -> RushCurveBackground(ctx.clock, ctx.audio, colors, modifier)
            else -> RushWaveBackground(ctx.clock, ctx.audio, colors, modifier)
        }
        else -> Spacer(modifier.drawBehind { drawRect(colors.background.value) })
    }
}

/**
 * 封面：和流光背景同一张 24px 小图（同一个缓存键，通常已经在内存里），铺满后模糊 80dp（Android 12 起；
 * 更早的版本只靠小图放大的柔化），上面盖一层 50% 的底色，歌词才看得清。
 */
@Composable
private fun RushCoverBackground(url: String, background: State<Color>, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, url) { value = loadBackdropCover(context, url) }
    Box(modifier.drawBehind { drawRect(background.value) }) {
        image?.let { art ->
            Spacer(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (Build.VERSION.SDK_INT >= 31) {
                            val radius = RUSH_COVER_BLUR.toPx()
                            renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                        }
                        clip = true
                    }
                    .drawBehind {
                        // 居中裁成铺满
                        val scale = maxOf(size.width / art.width, size.height / art.height)
                        val srcW = (size.width / scale).coerceAtMost(art.width.toFloat())
                        val srcH = (size.height / scale).coerceAtMost(art.height.toFloat())
                        drawImage(
                            art,
                            srcOffset = IntOffset(((art.width - srcW) / 2f).roundToInt(), ((art.height - srcH) / 2f).roundToInt()),
                            srcSize = IntSize(srcW.roundToInt().coerceAtLeast(1), srcH.roundToInt().coerceAtLeast(1)),
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.High,
                        )
                    },
            )
        }
        Spacer(Modifier.fillMaxSize().drawBehind { drawRect(background.value.copy(alpha = 0.5f)) })
    }
}

/** 流光：Android 13 起是 Rush 的 AGSL 着色器（6 个色按正弦权重混合、慢慢上下流动），更早退回上下渐变（Rush 自己也这样退）。 */
@Composable
private fun RushHypnoticBackground(clock: StageClock, colors: RushBackgroundColors, modifier: Modifier) {
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) RushHypnoticShader.createOrNull() else null }
    Spacer(
        modifier.drawBehind {
            val palette = rushGradientColors(colors.hypnoticLight.value, colors.hypnoticDark.value, RUSH_HYPNOTIC_COLORS)
            if (shader != null && Build.VERSION.SDK_INT >= 33) {
                shader.update(clock.seconds * RUSH_SHADER_TIME_SCALE, size.width, size.height, palette)
                drawRect(shader.brush)
            } else {
                drawRect(Brush.verticalGradient(palette))
            }
        },
    )
}

/** 波浪：下半屏一道曲线，左端跟中频、中间跟低频、右端跟高频，弹簧刚度 50、不回弹（Rush 的 WaveVisualizer）。 */
@Composable
private fun RushWaveBackground(clock: StageClock, audio: StageAudio?, colors: RushBackgroundColors, modifier: Modifier) {
    val springs = remember { RushBands(RUSH_WAVE_STIFFNESS) }
    val path = remember { Path() }
    Spacer(
        modifier.drawBehind {
            springs.advance(clock, audio, RUSH_WAVE_INPUT_SCALE)
            drawRect(colors.background.value)
            val w = size.width
            val half = size.height / 2f
            val yMid = half * (1f - springs.mid / RUSH_WAVE_RANGE)
            val yBass = half * (1f - springs.bass / RUSH_WAVE_RANGE)
            val yTreble = half * (1f - springs.treble / RUSH_WAVE_RANGE)
            path.reset()
            path.moveTo(0f, size.height)
            path.lineTo(0f, yMid)
            path.cubicTo(w / 4f, yMid, w / 4f, yBass, w / 2f, yBass)
            path.cubicTo(w * 3f / 4f, yBass, w * 3f / 4f, yTreble, w, yTreble)
            path.lineTo(w, size.height)
            path.close()
            drawPath(path, colors.wave.value)
        },
    )
}

/** 渐变：上面底色、下面波浪色，分界随低频往上推，弹簧刚度 25（Rush 的 GradientVisualizer）。 */
@Composable
private fun RushGradientBackground(clock: StageClock, audio: StageAudio?, colors: RushBackgroundColors, modifier: Modifier) {
    val springs = remember { RushBands(RUSH_GRADIENT_STIFFNESS) }
    Spacer(
        modifier.drawBehind {
            springs.advance(clock, audio, RUSH_WAVE_INPUT_SCALE)
            val wave = colors.wave.value
            val edge = (1f - springs.bass / RUSH_WAVE_RANGE).coerceIn(0f, 1f)
            drawRect(Brush.verticalGradient(0f to colors.background.value, edge to wave, 1f to wave))
        },
    )
}

/** 条纹：Android 13 起是 Rush 的 AGSL 横条纹，低频加粗、中高频扭动，弹簧刚度 200；更早退回纯色波浪色（Rush 也这样退）。 */
@Composable
private fun RushCurveBackground(clock: StageClock, audio: StageAudio?, colors: RushBackgroundColors, modifier: Modifier) {
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) RushCurveShader.createOrNull() else null }
    val springs = remember { RushBands(RUSH_CURVE_STIFFNESS) }
    Spacer(
        modifier.drawBehind {
            if (shader != null && Build.VERSION.SDK_INT >= 33) {
                springs.advance(clock, audio, RUSH_CURVE_INPUT_SCALE)
                shader.update(
                    clock.seconds * RUSH_SHADER_TIME_SCALE,
                    size.width,
                    size.height,
                    colors.background.value,
                    colors.wave.value,
                    springs.bass,
                    springs.mid,
                    springs.treble,
                )
                drawRect(shader.brush)
            } else {
                drawRect(colors.wave.value)
            }
        },
    )
}

/**
 * 低 / 中 / 高三段的弹簧（临界阻尼，Rush 用的 DampingRatioNoBouncy）。在绘制阶段按舞台动画时钟积分：
 * 同一帧画两次也只走一步；暂停、减少动画时时钟不走，背景就停住。输入是 0..1 的电平乘 [scale]（原版 0..128 的刻度）。
 */
internal class RushBands(stiffness: Float) {
    private val bassSpring = RushSpring(stiffness)
    private val midSpring = RushSpring(stiffness)
    private val trebleSpring = RushSpring(stiffness)
    private var lastSeconds = Float.NaN

    val bass: Float get() = bassSpring.value
    val mid: Float get() = midSpring.value
    val treble: Float get() = trebleSpring.value

    fun advance(clock: StageClock, audio: StageAudio?, scale: Float) {
        val now = clock.seconds
        val dt = if (lastSeconds.isNaN()) 0f else (now - lastSeconds).coerceIn(0f, 0.1f)
        lastSeconds = now
        if (dt <= 0f) return
        bassSpring.step((audio?.bass ?: 0f) * scale, dt)
        midSpring.step((audio?.mid ?: 0f) * scale, dt)
        trebleSpring.step((audio?.treble ?: 0f) * scale, dt)
    }
}

/** 质量为 1、阻尼比为 1 的弹簧，按解析解走一步，帧间隔不均也不会发散。 */
internal class RushSpring(stiffness: Float) {
    private val omega = sqrt(stiffness)
    var value = 0f
        private set
    private var velocity = 0f

    fun step(target: Float, dt: Float) {
        val offset = value - target
        val decay = exp(-omega * dt)
        val k = velocity + omega * offset
        value = target + (offset + k * dt) * decay
        velocity = (velocity - omega * k * dt) * decay
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class RushHypnoticShader private constructor(private val shader: RuntimeShader) {
    val brush = ShaderBrush(shader)
    private val uniforms = FloatArray(RUSH_HYPNOTIC_COLORS * 3)

    fun update(time: Float, width: Float, height: Float, colors: List<Color>) {
        colors.forEachIndexed { i, c ->
            uniforms[i * 3] = c.red
            uniforms[i * 3 + 1] = c.green
            uniforms[i * 3 + 2] = c.blue
        }
        shader.setFloatUniform("uResolution", width, height, if (height > 0f) width / height else 1f)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uColor", uniforms)
    }

    companion object {
        fun createOrNull(): RushHypnoticShader? = try {
            RushHypnoticShader(RuntimeShader(HYPNOTIC_AGSL))
        } catch (_: RuntimeException) {
            null
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class RushCurveShader private constructor(private val shader: RuntimeShader) {
    val brush = ShaderBrush(shader)

    fun update(time: Float, width: Float, height: Float, background: Color, wave: Color, bass: Float, mid: Float, treble: Float) {
        shader.setFloatUniform("uResolution", width, height, if (height > 0f) width / height else 1f)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("cardBackground", background.red, background.green, background.blue)
        shader.setFloatUniform("cardWaveBackground", wave.red, wave.green, wave.blue)
        shader.setFloatUniform("uBass", bass)
        shader.setFloatUniform("uMid", mid)
        shader.setFloatUniform("uTreble", treble)
    }

    companion object {
        fun createOrNull(): RushCurveShader? = try {
            RushCurveShader(RuntimeShader(CURVE_AGSL))
        } catch (_: RuntimeException) {
            null
        }
    }
}

private const val HYPNOTIC_AGSL = """
uniform float uTime;
uniform vec3 uResolution;
const int MAX_COLORS = 6;
uniform vec3 uColor[MAX_COLORS];

vec4 main(vec2 fragCoord) {
    vec2 uv = fragCoord.xy / uResolution.xy;
    float t = uTime * 0.1;
    vec3 finalColor = vec3(0.0);
    float totalWeight = 0.0;
    for (int i = 0; i < MAX_COLORS; i++) {
        float fi = float(i);
        float wave = sin(uv.y * 3.0 + t + fi * 2.0 * 3.14159 / float(MAX_COLORS));
        wave = (wave + 1.0) * 0.5;
        float weight = pow(wave, 4.0);
        finalColor += uColor[i] * weight;
        totalWeight += weight;
    }
    return vec4(finalColor / max(totalWeight, 0.0001), 1.0);
}
"""

private const val CURVE_AGSL = """
uniform float uTime;
uniform vec3 uResolution;
uniform vec3 cardBackground;
uniform vec3 cardWaveBackground;
uniform float uBass;
uniform float uMid;
uniform float uTreble;

vec4 main(vec2 fragCoord) {
    vec2 uv = fragCoord / uResolution.xy;
    float lineCount = 3.0;
    float baseWidth = 0.1;
    float lineWidth = baseWidth + uBass * 0.005;
    float distortAmp = (uMid * 0.001) + (uTreble * 0.001);
    float distortion = sin(uv.x * 10.0 + uTime * 0.2) * distortAmp;
    distortion += sin(uv.x * 25.0 + uTime * 0.5) * distortAmp * 0.5;
    float pattern = fract((uv.y + distortion) * lineCount);
    float line = step(1.0 - lineWidth, pattern);
    vec3 color = mix(cardBackground, cardWaveBackground, line);
    return vec4(color, 1.0);
}
"""

internal const val RUSH_BG_SOLID = "solid"
internal const val RUSH_BG_COVER = "cover"
internal const val RUSH_BG_HYPNOTIC = "hypnotic"
internal const val RUSH_BG_RHYTHM = "rhythm"
internal const val RUSH_RHYTHM_WAVE = "wave"
internal const val RUSH_RHYTHM_GRADIENT = "gradient"
internal const val RUSH_RHYTHM_CURVE = "curve"

/** 原版的着色器时间是“帧数 / 10”（按 60 帧算）：秒 × 1000 / 16.6 / 10。 */
private const val RUSH_SHADER_TIME_SCALE = 1000f / 16.6f / 10f
/** 原版律动的刻度是 0..128（波形字节的绝对值）。 */
private const val RUSH_WAVE_RANGE = 128f
/** 0..1 的电平换到原版刻度：波浪、渐变按 100，条纹按 60（条纹的线宽对低频很敏感）。 */
private const val RUSH_WAVE_INPUT_SCALE = 100f
private const val RUSH_CURVE_INPUT_SCALE = 60f
/** Spring.StiffnessVeryLow / 25 / Spring.StiffnessLow。 */
private const val RUSH_WAVE_STIFFNESS = 50f
private const val RUSH_GRADIENT_STIFFNESS = 25f
private const val RUSH_CURVE_STIFFNESS = 200f
private val RUSH_COVER_BLUR = 80.dp
