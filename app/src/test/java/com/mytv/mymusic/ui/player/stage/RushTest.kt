package com.mytv.mymusic.ui.player.stage

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.materialkolor.hct.Hct
import com.materialkolor.ktx.blend
import com.materialkolor.ktx.darken
import com.materialkolor.ktx.lighten
import com.materialkolor.ktx.toColor
import com.materialkolor.ktx.toHct
import com.mytv.mymusic.ui.LyricWord
import com.mytv.mymusic.ui.player.lyricAnchorError
import com.mytv.mymusic.ui.player.stage.modes.RUSH_COLORS_CUSTOM
import com.mytv.mymusic.ui.player.stage.modes.RUSH_COLORS_MUTED
import com.mytv.mymusic.ui.player.stage.modes.RUSH_COLORS_PLAYER
import com.mytv.mymusic.ui.player.stage.modes.RUSH_COLORS_VIBRANT
import com.mytv.mymusic.ui.player.stage.modes.RushAlign
import com.mytv.mymusic.ui.player.stage.modes.RushSpring
import com.mytv.mymusic.ui.player.stage.modes.RushVisualizer
import com.mytv.mymusic.ui.player.stage.modes.rushAnchorPadding
import com.mytv.mymusic.ui.player.stage.modes.rushBlurDp
import com.mytv.mymusic.ui.player.stage.modes.rushBlurLabel
import com.mytv.mymusic.ui.player.stage.modes.rushCurrentRow
import com.mytv.mymusic.ui.player.stage.modes.rushDotProgress
import com.mytv.mymusic.ui.player.stage.modes.rushGradientColors
import com.mytv.mymusic.ui.player.stage.modes.rushIsCjk
import com.mytv.mymusic.ui.player.stage.modes.rushLineProgress
import com.mytv.mymusic.ui.player.stage.modes.rushLook
import com.mytv.mymusic.ui.player.stage.modes.rushPalette
import com.mytv.mymusic.ui.player.stage.modes.rushRows
import com.mytv.mymusic.ui.player.stage.modes.rushShouldSnap
import com.mytv.mymusic.ui.player.stage.modes.rushTokens
import com.mytv.mymusic.ui.player.stage.modes.rushWordWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class RushTest {

    // ---- 锚点与滚动 ----

    @Test
    fun anchorPaddingPutsTheFirstAndLastRowsAtOneThird() {
        val (top, bottom) = rushAnchorPadding(900f)
        assertEquals(300f, top, 1e-3f)
        assertEquals(600f, bottom, 1e-3f)
    }

    @Test
    fun scrollToItemThenAnchorErrorCentersTheRowAtOneThird() {
        // 视口 900px、上留白 300px：scrollToItem 后这一行顶在 300 处，误差是半个行高；滚过去以后中心正好在 1/3
        val viewport = 900
        val start = -300
        val size = 120
        val error = lyricAnchorError(itemOffset = 0, itemSize = size, viewportStartOffset = start, viewportHeight = viewport, anchor = 1f / 3f)
        assertEquals(60f, error, 1e-3f)
        val after = lyricAnchorError(itemOffset = -error.toInt(), itemSize = size, viewportStartOffset = start, viewportHeight = viewport, anchor = 1f / 3f)
        assertEquals(0f, after, 1e-3f)
        // 中心离视口顶 = offset − viewportStart + size / 2
        assertEquals(viewport / 3f, -error - start + size / 2f, 1e-3f)
    }

    @Test
    fun farJumpsSnapNearOnesScroll() {
        assertFalse(rushShouldSnap(target = 12, firstVisible = 2, placed = true, reduceMotion = false, resized = false))
        assertTrue(rushShouldSnap(target = 13, firstVisible = 2, placed = true, reduceMotion = false, resized = false))
        assertTrue(rushShouldSnap(target = 0, firstVisible = 40, placed = true, reduceMotion = false, resized = false))
        assertTrue(rushShouldSnap(target = 3, firstVisible = 2, placed = false, reduceMotion = false, resized = false))
        assertTrue(rushShouldSnap(target = 3, firstVisible = 2, placed = true, reduceMotion = true, resized = false))
        assertTrue(rushShouldSnap(target = 3, firstVisible = 2, placed = true, reduceMotion = false, resized = true))
    }

    @Test
    fun rowsAddAnIntroAndLongInterludes() {
        val lines = listOf(
            line(0, 4_000, 6_000, "第一句"),
            line(1, 6_500, 8_000, "第二句"),
            // 上一句 8 秒唱完，下一句 13 秒才来：中间 5 秒算间奏
            line(2, 13_000, 15_000, "第三句"),
        )
        val rows = rushRows(lines)
        assertEquals(listOf("intro", "l0", "l1", "gap1", "l2"), rows.map { it.key })
        assertNull(rows[0].line)
        assertEquals(0L, rows[0].startMs)
        assertEquals(4_000L, rows[0].nextMs)
        assertEquals(6_500L, rows[1].nextMs)
        assertEquals(8_000L, rows[3].startMs)
        assertEquals(13_000L, rows[3].nextMs)
        assertNull(rows[4].nextMs)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        // 开场不到 1 秒不放三点
        assertEquals("l0", rushRows(listOf(line(0, 900, 2_000, "早"))).first().key)
        // 等歌词：一行占位三点
        val waiting = rushRows(emptyList())
        assertEquals(1, waiting.size)
        assertTrue(waiting[0].placeholder)
    }

    @Test
    fun currentRowIsTheLastStartedOne() {
        val rows = rushRows(listOf(line(0, 4_000, 6_000, "a"), line(1, 6_500, 8_000, "b"), line(2, 13_000, 15_000, "c")))
        assertEquals(0, rushCurrentRow(rows, 0))
        assertEquals(0, rushCurrentRow(rows, 3_999))
        assertEquals(1, rushCurrentRow(rows, 4_000))
        assertEquals(1, rushCurrentRow(rows, 6_499))
        assertEquals(2, rushCurrentRow(rows, 6_500))
        // 唱完到间奏开始之前仍停在上一句
        assertEquals(2, rushCurrentRow(rows, 7_999))
        assertEquals(3, rushCurrentRow(rows, 8_000))
        assertEquals(4, rushCurrentRow(rows, 13_000))
        assertEquals(4, rushCurrentRow(rows, 99_000))
        assertEquals(0, rushCurrentRow(rushRows(emptyList()), 5_000))
    }

    // ---- 模糊阶梯 ----

    @Test
    fun blurLadderAddsThreeDpPerLineUpToTen() {
        // 强度 1（“中”）就是原来开着的样子
        assertEquals(listOf(0f, 3f, 6f, 9f, 10f, 10f), (0..5).map { rushBlurDp(it, depth = 1f, manual = false) })
        assertEquals(6f, rushBlurDp(-2, depth = 1f, manual = false), 0f)
        assertEquals(0f, rushBlurDp(3, depth = 0f, manual = false), 0f)
        assertEquals(0f, rushBlurDp(3, depth = 1f, manual = true), 0f)
    }

    @Test
    fun blurDepthScalesStepAndCap() {
        val strong = (1..4).map { rushBlurDp(it, depth = 1.5f, manual = false) }
        assertEquals(listOf(4.5f, 9f, 13.5f, 15f), strong.map { (it * 100).roundToInt() / 100f })
        val weak = (1..4).map { rushBlurDp(it, depth = 0.5f, manual = false) }
        assertEquals(listOf(1.5f, 3f, 4.5f, 5f), weak.map { (it * 100).roundToInt() / 100f })
        assertEquals(0f, rushBlurDp(2, depth = 1.5f, manual = true), 0f)
    }

    @Test
    fun blurLabelNamesTheLevel() {
        assertEquals("关", rushBlurLabel(0f))
        assertEquals("关", rushBlurLabel(0.01f))
        assertEquals("弱 · 每离一句多 1.5dp，最多 5dp", rushBlurLabel(0.5f))
        assertEquals("中 · 每离一句多 3.0dp，最多 10dp", rushBlurLabel(1f))
        assertEquals("中 · 每离一句多 2.3dp，最多 8dp", rushBlurLabel(0.75f))
        assertEquals("强 · 每离一句多 4.5dp，最多 15dp", rushBlurLabel(1.5f))
        assertTrue(rushBlurLabel(0.74f).startsWith("弱"))
        assertTrue(rushBlurLabel(1.25f).startsWith("强"))
    }

    // ---- 间奏三点 ----

    @Test
    fun dotProgressStaggersByFifteenPercent() {
        assertEquals(0f, rushDotProgress(0f, 0), 0f)
        assertEquals(0.7f, rushDotProgress(0.5f, 0), 1e-5f)
        assertEquals(0.49f, rushDotProgress(0.5f, 1), 1e-5f)
        assertEquals(0.28f, rushDotProgress(0.5f, 2), 1e-5f)
        assertEquals(0f, rushDotProgress(0.2f, 2), 0f)
        assertEquals(1f, rushDotProgress(1f, 0), 0f)
        assertEquals(1f, rushDotProgress(1f, 1), 0f)
        assertEquals(0.98f, rushDotProgress(1f, 2), 1e-5f)
    }

    @Test
    fun lineProgressRunsToTheNextRow() {
        assertEquals(0f, rushLineProgress(500, 1_000, 3_000), 0f)
        assertEquals(0.5f, rushLineProgress(2_000, 1_000, 3_000), 1e-6f)
        assertEquals(1f, rushLineProgress(9_000, 1_000, 3_000), 0f)
        assertEquals(1f, rushLineProgress(0, 1_000, null), 0f)
        assertEquals(1f, rushLineProgress(0, 1_000, 1_000), 0f)
    }

    // ---- 逐字粗细 ----

    @Test
    fun wordWeightFollowsRushDurationTable() {
        assertEquals(300, rushWordWeight(0))
        assertEquals(300, rushWordWeight(500))
        assertEquals(500, rushWordWeight(501))
        assertEquals(500, rushWordWeight(1_000))
        assertEquals(700, rushWordWeight(1_001))
        assertEquals(700, rushWordWeight(1_500))
        assertEquals(900, rushWordWeight(1_501))
        assertEquals(900, rushWordWeight(4_000))
    }

    // ---- 拆词 ----

    @Test
    fun latinSyllablesStayTogetherAndSpacesBecomeGaps() {
        val l = line(0, 0, 3_000, "Welcome to Rush", words = listOf(word("Wel", 0, 300), word("come ", 300, 900), word("to ", 900, 1_200), word("Rush", 1_200, 3_000)))
        val tokens = rushTokens(l, wordTimed = true)
        assertEquals(listOf("Wel", "come", "to", "Rush"), tokens.map { it.text })
        assertEquals(listOf(false, false, true, true), tokens.map { it.gapBefore })
        assertEquals(listOf(true, false, true, true), tokens.map { it.breakBefore })
        assertTrue(tokens.none { it.cjk })
    }

    @Test
    fun cjkCharactersHaveNoGapsAndPunctuationSticks() {
        val l = line(0, 0, 2_000, "我爱你，", words = listOf(word("我", 0, 400), word("爱", 400, 800), word("你", 800, 1_500), word("，", 1_500, 1_600)))
        val tokens = rushTokens(l, wordTimed = true)
        assertEquals(listOf("我", "爱", "你", "，"), tokens.map { it.text })
        assertTrue(tokens.all { it.cjk })
        assertTrue(tokens.none { it.gapBefore })
        assertEquals(listOf(true, true, true, false), tokens.map { it.breakBefore })
        assertTrue(rushIsCjk("が"))
        assertTrue(rushIsCjk("사"))
        assertFalse(rushIsCjk("a"))
    }

    @Test
    fun plainLrcLinesLightUpAsAWhole() {
        val l = line(0, 5_000, 9_000, "你好 world")
        val tokens = rushTokens(l, wordTimed = false)
        assertEquals(listOf("你", "好", "world"), tokens.map { it.text })
        assertEquals(listOf(false, false, true), tokens.map { it.gapBefore })
        assertTrue(tokens.all { it.progress(4_999) == 0f })
        assertTrue(tokens.all { it.progress(5_000) == 1f })
    }

    @Test
    fun untimedLineInATimedSongSplitsByCharTimings() {
        val l = line(0, 0, 1_000, "ab cd")
        val tokens = rushTokens(l, wordTimed = true)
        assertEquals(listOf("ab", "cd"), tokens.map { it.text })
        // 4 个字分句长的 85%：第一个词占前两个字
        assertEquals(0L, tokens[0].startMs)
        assertEquals(425L, tokens[0].endMs)
        assertTrue(tokens[1].startMs >= tokens[0].endMs)
        assertEquals(0.5f, tokens[0].progress(212), 0.01f)
    }

    // ---- 配色对比度 ----

    @Test
    fun coverTonesStayDarkAndTextKeepsFourAndAHalfToOne() {
        val playerText = Color(0xFFE2E2E9) // 播放页深色主题的 onSurface（色调 90 左右）
        for (hue in 0 until 360 step 15) {
            for (chroma in listOf(0.0, 8.0, 30.0, 60.0, 100.0)) {
                for (tone in listOf(5.0, 20.0, 40.0, 55.0, 75.0, 95.0)) {
                    val seed = Hct.from(hue.toDouble(), chroma, tone).toColor()
                    val (vibrant, muted) = coverTones(seed)
                    val label = "hue=$hue chroma=$chroma tone=$tone"
                    assertTrue(label, vibrant.toHct().tone <= 40.6)
                    assertTrue(label, muted.toHct().tone <= vibrant.toHct().tone + 0.6)
                    assertTrue(label, contrastRatio(vibrant, readableOn(vibrant)) >= 4.5f)
                    assertTrue(label, contrastRatio(muted, readableOn(muted)) >= 4.5f)
                    // 页头、控件是播放页的浅色字，压在 Rush 底色上也要看得清
                    assertTrue(label, contrastRatio(vibrant, playerText) >= 4.5f)
                    assertTrue(label, contrastRatio(muted, playerText) >= 4.5f)
                }
            }
        }
    }

    @Test
    fun everyPaletteChoiceIsReadable() {
        val stage = StageColors(
            text = Color(0xFFE2E2E9),
            dim = Color(0x47E2E2E9),
            accent = Color(0xFFAAC7FF),
            accentAlt = Color(0xFFDCBCE0),
            surface = Color(0xFF111318),
            cover = List(4) { Color(0xFFE0485A) },
            coverVibrant = Color.Unspecified,
            onCoverVibrant = Color.Unspecified,
            coverMuted = Color.Unspecified,
            onCoverMuted = Color.Unspecified,
        ).let { base ->
            val (v, m) = coverTones(base.cover[0])
            base.copy(coverVibrant = v, onCoverVibrant = readableOn(v), coverMuted = m, onCoverMuted = readableOn(m))
        }
        for (choice in listOf(RUSH_COLORS_VIBRANT, RUSH_COLORS_MUTED, RUSH_COLORS_PLAYER)) {
            val palette = rushPalette(stage, choice)
            assertTrue(choice, contrastRatio(palette.background, palette.content) >= 4.5f)
            assertEquals(6, palette.hypnotic.size)
        }
        assertEquals(stage.coverVibrant, rushPalette(stage, "unknown").background)
        assertEquals(21f, contrastRatio(Color.White, Color.Black), 1e-3f)
        assertEquals(Color.Black, readableOn(Color.White))
        assertEquals(Color.White, readableOn(Color(0xFF202020)))
    }

    @Test
    fun customPaletteUsesTheTwoSettingColors() {
        val stage = sampleStage()
        val background = Color(0xFF1B2A4A)
        val text = Color(0xFFF4C7D0)
        val palette = rushPalette(stage, RUSH_COLORS_CUSTOM, background, text)
        assertEquals(background, palette.background)
        assertEquals(text, palette.content)
        assertEquals(background.blend(text, 0.25f), palette.wave)
        assertEquals(6, palette.hypnotic.size)
        assertEquals(background.lighten(2f), palette.hypnotic.first())
        assertEquals(background.darken(2f), palette.hypnotic.last())
        // 自定义不跟封面变
        val otherCover = stage.copy(coverVibrant = Color(0xFF203040), cover = List(4) { Color(0xFF00FF00) })
        assertEquals(palette.background, rushPalette(otherCover, RUSH_COLORS_CUSTOM, background, text).background)
    }

    @Test
    fun customColorsAreSettings() {
        val schema = RushVisualizer.settings
        val colors = schema[2] as LyricSetting.Choice
        assertEquals(listOf("vibrant", "muted", "player", "custom"), colors.options.map { it.first })
        assertEquals("自定义", colors.options.last().second)
        assertEquals(setOf("custom"), colors.shuffleExclude)
        val background = schema[3] as LyricSetting.Color
        val text = schema[4] as LyricSetting.Color
        assertEquals("背景", background.label)
        assertEquals("文字", text.label)
        assertEquals(SettingCondition("colors", setOf("custom")), background.visibleWhen)
        assertEquals(SettingCondition("colors", setOf("custom")), text.visibleWhen)
        // 初值取封面的鲜艳色和它的文字色
        val stage = sampleStage()
        assertEquals(stage.coverVibrant, background.seed!!(stage))
        assertEquals(stage.onCoverVibrant, text.seed!!(stage))

        val look = rushLook(SettingValues(schema, mapOf("colors" to "custom", "customBackground" to "#1b2a4a", "customText" to "#F4C7D0")))
        assertEquals("custom", look.colors)
        assertEquals(Color(0xFF1B2A4A), look.customBackground)
        assertEquals(Color(0xFFF4C7D0), look.customText)
        val defaults = rushLook(SettingValues(schema, emptyMap()))
        assertEquals(Color(0xFF2C5F5A), defaults.customBackground)
        assertEquals(Color(0xFFFFE9B8), defaults.customText)
        // 只有选了自定义才显示两个色块
        assertFalse(SettingValues(schema, emptyMap()).visible(background))
        assertTrue(SettingValues(schema, mapOf("colors" to "custom")).visible(text))
    }

    @Test
    fun autoTextColorIsAlwaysReadable() {
        for (hue in 0 until 360 step 36) {
            for (tone in listOf(8.0, 30.0, 55.0, 85.0)) {
                for (chroma in listOf(0.0, 40.0, 90.0)) {
                    val background = Hct.from(hue.toDouble(), chroma, tone).toColor()
                    val text = autoTextColor(background)
                    assertTrue("hue=$hue tone=$tone chroma=$chroma", contrastRatio(background, text) >= 4.5f)
                }
            }
        }
        // 底色亮配深字、底色暗配浅字，同色相、低彩度
        assertTrue(autoTextColor(Color(0xFFFFE9B8)).luminance() < 0.1f)
        assertTrue(autoTextColor(Color(0xFF1B2A4A)).luminance() > 0.6f)
        assertTrue(autoTextColor(Color(0xFF1B2A4A)).toHct().chroma <= 16.5)
    }

    @Test
    fun coverSwatchesAreSixDistinctColors() {
        val swatches = coverSwatches(sampleStage(cover = listOf(Color(0xFFE0485A), Color(0xFF3060C0), Color(0xFF40A070), Color(0xFFE0485A))))
        assertEquals(6, swatches.size)
        assertEquals(6, swatches.map { it.toHex() }.toSet().size)
        // 封面只有一种颜色时也凑得出来，不重复
        val flat = coverSwatches(sampleStage(cover = List(4) { Color(0xFF808080) }))
        assertEquals(flat.map { it.toHex() }.toSet().size, flat.size)
        assertTrue(flat.size in 3..6)
    }

    @Test
    fun recentColorsKeepTheNewestEight() {
        var recent = emptyList<String>()
        for (i in 1..10) recent = pushRecentColor(recent, Color(0xFF000000 + i))
        assertEquals(8, recent.size)
        assertEquals("#00000A", recent.first())
        recent = pushRecentColor(recent, Color(0xFF000005))
        assertEquals("#000005", recent.first())
        assertEquals(1, recent.count { it == "#000005" })
        assertEquals(listOf("#ABCDEF"), pushRecentColor(listOf("#abcdef"), Color(0xFFABCDEF)))
    }

    private fun sampleStage(cover: List<Color> = List(4) { Color(0xFFE0485A) }): StageColors {
        val (v, m) = coverTones(cover[0])
        return StageColors(
            text = Color(0xFFE2E2E9),
            dim = Color(0x47E2E2E9),
            accent = Color(0xFFAAC7FF),
            accentAlt = Color(0xFFDCBCE0),
            surface = Color(0xFF111318),
            cover = cover,
            coverVibrant = v,
            onCoverVibrant = readableOn(v),
            coverMuted = m,
            onCoverMuted = readableOn(m),
        )
    }

    @Test
    fun gradientColorsIncludeBothEnds() {
        val colors = rushGradientColors(Color.White, Color.Black, 6)
        assertEquals(6, colors.size)
        assertEquals(Color.White, colors.first())
        assertEquals(Color.Black, colors.last())
        assertEquals(0.6f, colors[2].red, 1e-5f)
    }

    // ---- 律动弹簧 ----

    @Test
    fun criticallyDampedSpringSettlesWithoutOvershoot() {
        val spring = RushSpring(50f)
        var peak = 0f
        repeat(300) {
            spring.step(100f, 1f / 60f)
            peak = maxOf(peak, spring.value)
        }
        assertEquals(100f, spring.value, 0.5f)
        assertTrue(peak <= 100.001f)
    }

    // ---- 声明与设置默认值 ----

    @Test
    fun declarationMatchesTheBrief() {
        assertEquals("rush", RushVisualizer.id)
        assertEquals("Rush", RushVisualizer.label)
        assertEquals(1, RushVisualizer.order)
        assertEquals(ModOrigin("Rush", "7.2.1", "shub39", "GPL-3.0", kind = "应用"), RushVisualizer.origin)
        assertEquals(2, RushVisualizer.hiddenSettings)
        assertFalse(RushVisualizer.hostBackground)
        assertFalse(RushVisualizer.hostSubtitles)
        assertTrue(RushVisualizer.interactive)
        assertFalse(RushVisualizer.wantsAudio)
        assertSame(RushVisualizer, LyricVisualizers.all[1])
        assertSame(RushVisualizer, LyricVisualizers.byId("rush"))
    }

    @Test
    fun settingsDefaultsMatchTheDesign() {
        val schema = RushVisualizer.settings
        assertEquals(
            listOf("background", "rhythm", "colors", "customBackground", "customText", "align", "size", "blur", "expressive"),
            schema.map { it.key },
        )
        // 同一台设备上每次拿到的是同一份表（设置值按表身份比较）
        assertSame(schema, RushVisualizer.settings)
        val defaults = SettingValues(schema, emptyMap())
        val look = rushLook(defaults)
        assertEquals("solid", look.background)
        assertEquals("wave", look.rhythm)
        assertEquals("vibrant", look.colors)
        assertEquals(RushAlign.Start, look.align)
        assertEquals(1f, look.size, 0f)
        // 单测里没定设备档位，按能实时模糊算：默认“中”
        assertEquals(1f, look.blur, 0f)
        assertFalse(look.expressive)
        assertEquals(0, defaults.changed)
        assertEquals("背景选“律动”时生效", schema[1].summary)

        val size = schema[6] as LyricSetting.Number
        assertEquals(0.8f, size.min, 0f)
        assertEquals(1.4f, size.max, 0f)
        assertEquals(0.1f, size.step, 0f)
        assertEquals(1.4f, SettingValues(schema, mapOf("size" to "1.6")).number("size"), 1e-6f)
        assertEquals(1.2f, SettingValues(schema, mapOf("size" to "1.2")).number("size"), 1e-6f)
        assertEquals("solid", SettingValues(schema, mapOf("background" to "bogus")).choice("background"))
        assertEquals(RushAlign.End, rushLook(SettingValues(schema, mapOf("align" to "end"))).align)

        val blur = schema[7] as LyricSetting.Slider
        assertEquals(0f, blur.min, 0f)
        assertEquals(1.5f, blur.max, 0f)
        assertEquals(listOf(0f, 0.5f, 1f, 1.5f), blur.ticks)
        assertEquals(listOf("关", "弱", "中", "强"), blur.tickLabels)
        assertEquals(31, blur.minSdk)
        assertEquals(1.5f, rushLook(SettingValues(schema, mapOf("blur" to "1.5"))).blur, 0f)
    }

    @Test
    fun oldBlurToggleMigrates() {
        val schema = RushVisualizer.settings
        // 旧版本存的是开关
        assertEquals(0f, rushLook(SettingValues(schema, mapOf("blur" to "false"))).blur, 0f)
        assertEquals(1f, rushLook(SettingValues(schema, mapOf("blur" to "true"))).blur, 0f)
        // 下次写设置时改写成数（等于默认的“中”就不存）
        assertEquals(mapOf("blur" to "0.0"), sanitizeSettings(schema, mapOf("blur" to "false")))
        assertEquals(emptyMap<String, String>(), sanitizeSettings(schema, mapOf("blur" to "true")))
    }

    @Test
    fun audioOnlyForTheRhythmBackground() {
        val schema = RushVisualizer.settings
        assertFalse(RushVisualizer.wantsAudio(SettingValues(schema, emptyMap())))
        for (bg in listOf("solid", "cover", "hypnotic")) {
            assertFalse(bg, RushVisualizer.wantsAudio(SettingValues(schema, mapOf("background" to bg))))
        }
        assertTrue(RushVisualizer.wantsAudio(SettingValues(schema, mapOf("background" to "rhythm"))))
        // 其它模式不改设置也照旧
        val rings = LyricVisualizers.byId("rings")
        assertEquals(rings.wantsAudio, rings.wantsAudio(SettingValues(rings.settings, emptyMap())))
    }

    private fun line(index: Int, start: Long, end: Long, text: String, words: List<LyricWord> = emptyList()) =
        StageLine(index, start, end, text, words, "", "")

    private fun word(text: String, start: Long, end: Long) = LyricWord(start, end, text)
}
