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
// 2026-11-09 myMusic 改写：Rush 7.2.1 的歌词页（LyricsPage 里画歌词和背景的部分）做成 myMusic 的一种全屏歌词动画。
// 设置照 LyricsPrefs 取跟全屏歌词有关的 7 项：背景（六种拆成“背景”和“律动样式”两行）、配色（自定义取色器换成“播放页”）、
// 对齐、字号、景深模糊、逐字粗细；行高、字距保持 Rush 默认（32sp / 0）。页头、分享卡片、LRCLIB 纠错、播放键发光不搬。
package com.mytv.mymusic.ui.player.stage.modes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mytv.mymusic.ui.player.stage.LyricSetting
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import com.mytv.mymusic.ui.player.stage.LyricVisualizer
import com.mytv.mymusic.ui.player.stage.ModOrigin
import com.mytv.mymusic.ui.player.stage.SettingValues
import com.mytv.mymusic.ui.player.stage.StageDevice

/**
 * Rush：封面取色铺满整页，左对齐的粗体大字，当前句停在安全区上三分之一处，前后的句子缩到 80% 并按距离模糊，
 * 正在唱的那句逐字羽化扫亮，间奏三个点。可拖动、点一句跳过去；背景六种，“律动”三种跟着音乐动。
 */
object RushVisualizer : LyricVisualizer {
    override val id = "rush"
    override val label = "Rush"
    override val origin = ModOrigin("Rush", "7.2.1", "shub39", "GPL-3.0", kind = "应用")
    override val order = 1

    /** 行高、字距保持 Rush 默认。 */
    override val hiddenSettings = 2
    override val hostBackground = false
    override val hostSubtitles = false
    override val interactive = true

    /** 景深模糊的默认值随设备（电视、低内存、Android 12 以下默认关），两份表各建一次，设置值的表身份不变。 */
    override val settings: List<LyricSetting>
        get() = if (StageDevice.liveBlur) SETTINGS_BLUR_ON else SETTINGS_BLUR_OFF

    /** 只有背景选“律动”时才要律动分析。 */
    override fun wantsAudio(settings: SettingValues): Boolean = settings.choice(RUSH_KEY_BACKGROUND) == RUSH_BG_RHYTHM

    @Composable
    override fun Background(ctx: LyricStageContext, modifier: Modifier) {
        val look = remember(ctx.settings) { rushLook(ctx.settings) }
        val palette = remember(ctx.colors, look.colors) { rushPalette(ctx.colors, look.colors) }
        val spec = colorSpec(ctx)
        val colors = RushBackgroundColors(
            background = animateColorAsState(palette.background, spec, label = "rushBackground"),
            wave = animateColorAsState(palette.wave, spec, label = "rushWave"),
            hypnoticLight = animateColorAsState(palette.hypnotic.first(), spec, label = "rushHypnoticLight"),
            hypnoticDark = animateColorAsState(palette.hypnotic.last(), spec, label = "rushHypnoticDark"),
        )
        RushBackground(ctx, look, colors, modifier)
    }

    @Composable
    override fun Stage(ctx: LyricStageContext, modifier: Modifier) {
        val look = remember(ctx.settings) { rushLook(ctx.settings) }
        val palette = remember(ctx.colors, look.colors) { rushPalette(ctx.colors, look.colors) }
        val content: State<Color> = animateColorAsState(palette.content, colorSpec(ctx), label = "rushContent")
        RushLyricList(ctx, look, content, modifier)
    }

    /** 换封面时底色和字色一起过渡；减少动画时直接换。 */
    @Composable
    private fun colorSpec(ctx: LyricStageContext): AnimationSpec<Color> =
        if (ctx.display.reduceMotion) snap() else MaterialTheme.motionScheme.defaultEffectsSpec()
}

/** 读当前设置。 */
internal fun rushLook(settings: SettingValues): RushLook = RushLook(
    background = settings.choice(RUSH_KEY_BACKGROUND),
    rhythm = settings.choice(RUSH_KEY_RHYTHM),
    colors = settings.choice(RUSH_KEY_COLORS),
    align = when (settings.choice(RUSH_KEY_ALIGN)) {
        RUSH_ALIGN_CENTER -> RushAlign.Center
        RUSH_ALIGN_END -> RushAlign.End
        else -> RushAlign.Start
    },
    size = settings.number(RUSH_KEY_SIZE),
    blur = settings.toggle(RUSH_KEY_BLUR),
    expressive = settings.toggle(RUSH_KEY_EXPRESSIVE),
)

private fun rushSettings(blurDefault: Boolean): List<LyricSetting> = listOf(
    LyricSetting.Choice(
        RUSH_KEY_BACKGROUND,
        "背景",
        listOf(RUSH_BG_SOLID to "纯色", RUSH_BG_COVER to "封面", RUSH_BG_HYPNOTIC to "流光", RUSH_BG_RHYTHM to "律动"),
        RUSH_BG_SOLID,
    ),
    LyricSetting.Choice(
        RUSH_KEY_RHYTHM,
        "律动样式",
        listOf(RUSH_RHYTHM_WAVE to "波浪", RUSH_RHYTHM_GRADIENT to "渐变", RUSH_RHYTHM_CURVE to "条纹"),
        RUSH_RHYTHM_WAVE,
        summary = "背景选“律动”时生效",
    ),
    LyricSetting.Choice(
        RUSH_KEY_COLORS,
        "配色",
        listOf(RUSH_COLORS_VIBRANT to "鲜艳", RUSH_COLORS_MUTED to "柔和", RUSH_COLORS_PLAYER to "播放页"),
        RUSH_COLORS_VIBRANT,
    ),
    LyricSetting.Choice(
        RUSH_KEY_ALIGN,
        "对齐",
        listOf(RUSH_ALIGN_START to "左", RUSH_ALIGN_CENTER to "中", RUSH_ALIGN_END to "右"),
        RUSH_ALIGN_START,
    ),
    LyricSetting.Number(RUSH_KEY_SIZE, "字号", 0.8f, 1.4f, 0.1f, 1f),
    LyricSetting.Toggle(RUSH_KEY_BLUR, "景深模糊", blurDefault, summary = "离当前句越远越模糊，拖动歌词时取消；Android 12 起"),
    LyricSetting.Toggle(RUSH_KEY_EXPRESSIVE, "逐字粗细", false, summary = "唱得越长的字越粗；需要字体有字重轴"),
)

private val SETTINGS_BLUR_ON = rushSettings(blurDefault = true)
private val SETTINGS_BLUR_OFF = rushSettings(blurDefault = false)

internal const val RUSH_KEY_BACKGROUND = "background"
internal const val RUSH_KEY_RHYTHM = "rhythm"
internal const val RUSH_KEY_COLORS = "colors"
internal const val RUSH_KEY_ALIGN = "align"
internal const val RUSH_KEY_SIZE = "size"
internal const val RUSH_KEY_BLUR = "blur"
internal const val RUSH_KEY_EXPRESSIVE = "expressive"
private const val RUSH_ALIGN_START = "start"
private const val RUSH_ALIGN_CENTER = "center"
private const val RUSH_ALIGN_END = "end"
