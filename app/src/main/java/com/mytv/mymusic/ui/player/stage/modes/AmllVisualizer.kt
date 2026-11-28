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
// 2026-11-28 myMusic 改写：AMLL core 0.7.2（@4d8e0270）的歌词播放器（lyric-player）和网格背景（bg-render/mesh-renderer）
// 做成 myMusic 的一种全屏歌词动画。面板里只放五项：背景（网格 · 流光 · 纯色；流光是宿主的流光 / 律动背景）、字号、
// 景深模糊（关 · 弱 · 中 · 强，乘 0 / 0.5 / 1 / 1.5）、长音强调、对唱与和声。其余 8 项保持 AMLL 默认：
// 对齐锚点（居中）、对齐位置（35%）、弹簧开关、缩放开关、隐藏已唱句（关）、渐变宽度（0.5 字）、位移和缩放弹簧参数、脏词遮罩（关）。
// 翻译 / 罗马音排在句子里（0.5 倍字号、30%），跟应用里“歌词下面那一行”的设置。
package com.mytv.mymusic.ui.player.stage.modes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.mytv.mymusic.ui.player.stage.LocalStageBackdrop
import com.mytv.mymusic.ui.player.stage.LyricSetting
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import com.mytv.mymusic.ui.player.stage.LyricVisualizer
import com.mytv.mymusic.ui.player.stage.ModOrigin
import com.mytv.mymusic.ui.player.stage.SettingValues
import com.mytv.mymusic.ui.player.stage.StageDevice

/**
 * AMLL：每句一个弹簧、从上往下一句晚 50ms 出发，当前句比别的句子大 3%，别的句子按距离发虚；
 * 唱到的字从左往右亮起并微微上浮，拖长的字逐字放大发光；和声轮到时从句子下面展开，「男：」「女：」对唱分左右；
 * 间奏出三颗会呼吸的点；背景默认是封面揉成的网格。可拖动、点一句跳过去。
 */
object AmllVisualizer : LyricVisualizer {
    override val id = "amll"
    override val label = "AMLL"
    override val origin = ModOrigin("AMLL", "core 0.7.2", "amll-dev", "AGPL-3.0", kind = "开源组件")
    override val order = 2

    override val hiddenSettings = 8
    override val hostBackground = false
    override val hostSubtitles = false
    override val interactive = true

    /** 景深模糊的默认值随设备（电视、低内存、Android 12 以下默认关），两份表各建一次，设置值的表身份不变。 */
    override val settings: List<LyricSetting>
        get() = if (StageDevice.liveBlur) SETTINGS_BLUR_ON else SETTINGS_BLUR_OFF

    /** 只有网格背景跟低频响度。 */
    override fun wantsAudio(settings: SettingValues): Boolean = settings.choice(AMLL_KEY_BACKGROUND) == AMLL_BG_MESH

    @Composable
    override fun Background(ctx: LyricStageContext, modifier: Modifier) {
        val look = remember(ctx.settings) { amllLook(ctx.settings) }
        when (look.background) {
            AMLL_BG_FLOW -> {
                val host = LocalStageBackdrop.current
                when {
                    // 宿主的流光背景还铺在下面（电视、桌面姿态）：直接透出来，不再画一份
                    host != null && host.hostVisible -> Unit
                    host != null -> host.draw(modifier)
                    // 缩略图里没有宿主背景：按封面色铺一层渐变代替
                    else -> {
                        val tones = ctx.colors.cover
                        Box(modifier.background(Brush.linearGradient(if (tones.size >= 2) tones.take(3) else listOf(ctx.colors.surface, ctx.colors.surface))))
                    }
                }
            }
            AMLL_BG_SOLID -> {
                val surface = animateColorAsState(ctx.colors.surface, colorSpec(ctx), label = "amllSurface")
                Box(modifier.background(surface.value))
            }
            else -> AmllMeshBackground(ctx, modifier)
        }
    }

    @Composable
    override fun Stage(ctx: LyricStageContext, modifier: Modifier) {
        val look = remember(ctx.settings) { amllLook(ctx.settings) }
        // 网格和流光都是深底，字用白色；纯色背景跟播放页的文字色
        val target = if (look.background == AMLL_BG_SOLID) ctx.colors.text else Color.White
        val color = animateColorAsState(target, colorSpec(ctx), label = "amllText")
        AmllLyrics(ctx, look, color, modifier)
    }

    /** 换封面时颜色过渡；减少动画时直接换。 */
    @Composable
    private fun colorSpec(ctx: LyricStageContext): AnimationSpec<Color> =
        if (ctx.display.reduceMotion) snap() else MaterialTheme.motionScheme.defaultEffectsSpec()
}

/** 当前设置的样子（组合阶段读一次）。 */
@Immutable
internal class AmllLook(
    val background: String,
    /** 字号倍数：×1 时手机 30sp，平板、电视按宿主基准等比。 */
    val size: Float,
    /** 景深强度倍数：0 关、0.5 弱、1 中、1.5 强。 */
    val blur: Float,
    val emphasis: Boolean,
    val voices: Boolean,
)

internal fun amllLook(settings: SettingValues): AmllLook = AmllLook(
    background = settings.choice(AMLL_KEY_BACKGROUND),
    size = settings.number(AMLL_KEY_SIZE),
    blur = settings.slider(AMLL_KEY_BLUR),
    emphasis = settings.toggle(AMLL_KEY_EMPHASIS),
    voices = settings.toggle(AMLL_KEY_VOICES),
)

private fun amllSettings(blurDefault: Boolean): List<LyricSetting> = listOf(
    LyricSetting.Choice(
        AMLL_KEY_BACKGROUND,
        "背景",
        listOf(AMLL_BG_MESH to "网格", AMLL_BG_FLOW to "流光", AMLL_BG_SOLID to "纯色"),
        AMLL_BG_MESH,
    ),
    LyricSetting.Number(AMLL_KEY_SIZE, "字号", 0.8f, 1.4f, 0.1f, 1f),
    LyricSetting.Slider(
        AMLL_KEY_BLUR,
        "景深模糊",
        0f,
        AMLL_BLUR_DEPTH_MAX,
        default = if (blurDefault) 1f else 0f,
        ticks = listOf(0f, 0.5f, 1f, AMLL_BLUR_DEPTH_MAX),
        tickLabels = listOf("关", "弱", "中", "强"),
        format = ::amllBlurLabel,
        minSdk = 31,
    ),
    LyricSetting.Toggle(AMLL_KEY_EMPHASIS, "长音强调", true),
    LyricSetting.Toggle(AMLL_KEY_VOICES, "对唱与和声", true),
)

/** 景深滑块的读数：关 · 弱 · 中 · 强。 */
internal fun amllBlurLabel(depth: Float): String = when {
    depth <= AMLL_BLUR_OFF -> "关"
    depth < 0.75f -> "弱"
    depth < 1.25f -> "中"
    else -> "强"
}

private val SETTINGS_BLUR_ON = amllSettings(blurDefault = true)
private val SETTINGS_BLUR_OFF = amllSettings(blurDefault = false)

internal const val AMLL_KEY_BACKGROUND = "background"
internal const val AMLL_KEY_SIZE = "size"
internal const val AMLL_KEY_BLUR = "blur"
internal const val AMLL_KEY_EMPHASIS = "emphasis"
internal const val AMLL_KEY_VOICES = "voices"
internal const val AMLL_BG_MESH = "mesh"
internal const val AMLL_BG_FLOW = "flow"
internal const val AMLL_BG_SOLID = "solid"

/** 景深强度上限（“强”）。 */
internal const val AMLL_BLUR_DEPTH_MAX = 1.5f

/** 强度不到这个就算关。 */
internal const val AMLL_BLUR_OFF = 0.01f
