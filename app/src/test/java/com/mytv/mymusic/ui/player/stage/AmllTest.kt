package com.mytv.mymusic.ui.player.stage

import com.mytv.mymusic.data.LyricSubline
import com.mytv.mymusic.ui.LyricWord
import com.mytv.mymusic.ui.player.stage.modes.AMLL_FOCUS_INTERLUDE
import com.mytv.mymusic.ui.player.stage.modes.AMLL_FOCUS_LINE
import com.mytv.mymusic.ui.player.stage.modes.AmllFocus
import com.mytv.mymusic.ui.player.stage.modes.AmllGlide
import com.mytv.mymusic.ui.player.stage.modes.AmllInterlude
import com.mytv.mymusic.ui.player.stage.modes.AmllLayout
import com.mytv.mymusic.ui.player.stage.modes.AmllLineText
import com.mytv.mymusic.ui.player.stage.modes.AmllTimeline
import com.mytv.mymusic.ui.player.stage.modes.AmllVisualizer
import com.mytv.mymusic.ui.player.stage.modes.AmllWord
import com.mytv.mymusic.ui.player.stage.modes.RushVisualizer
import com.mytv.mymusic.ui.player.stage.modes.amllAlignTop
import com.mytv.mymusic.ui.player.stage.modes.amllBackgroundSplit
import com.mytv.mymusic.ui.player.stage.modes.amllBlur
import com.mytv.mymusic.ui.player.stage.modes.amllBlurLabel
import com.mytv.mymusic.ui.player.stage.modes.amllChunkWords
import com.mytv.mymusic.ui.player.stage.modes.amllDetectSeek
import com.mytv.mymusic.ui.player.stage.modes.amllInterludeAnchor
import com.mytv.mymusic.ui.player.stage.modes.amllInterludes
import com.mytv.mymusic.ui.player.stage.modes.amllIsCjk
import com.mytv.mymusic.ui.player.stage.modes.amllLook
import com.mytv.mymusic.ui.player.stage.modes.amllOpacity
import com.mytv.mymusic.ui.player.stage.modes.amllRiseProgress
import com.mytv.mymusic.ui.player.stage.modes.amllSong
import com.mytv.mymusic.ui.player.stage.modes.amllVoicePrefix
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AmllTest {

    // ---- 句组：对唱前缀、和声括号 ----

    @Test
    fun voicePrefixesAndBackgroundParensMakeGroups() {
        val lines = listOf(
            line(0, 0, 2000, "男：Hello world", translation = "你好世界"),
            line(1, 2000, 4000, "女：Is it me (is it me)"),
            line(2, 4000, 5000, "（啦啦啦）"),
            line(3, 5000, 6000, "Fine day"),
            line(4, 6000, 7000, "（ooh）"),
        )
        val song = amllSong(lines, LyricSubline.Translation, voices = true)
        assertEquals(4, song.groups.size)
        val (g0, g1, g2, g3) = song.groups
        assertEquals("Hello world", g0.main.text())
        assertEquals("你好世界", g0.main.subtitle)
        assertFalse(g0.duet)
        assertNull(g0.bg)

        // 句末一段括号是和声：主句去掉括号和前面的空白
        assertEquals("Is it me", g1.main.text())
        assertEquals("is it me", g1.bg?.text())
        assertTrue(g1.duet)

        // 整句在括号里、上一组已经有和声：单独成组，去掉括号；声部沿用上一个前缀
        assertEquals("啦啦啦", g2.main.text())
        assertNull(g2.bg)
        assertTrue(g2.duet)

        // 整句在括号里、上一组还没有和声：并进上一组，时间并起来
        assertEquals("Fine day", g3.main.text())
        assertEquals("ooh", g3.bg?.text())
        assertEquals(5000.0, g3.startMs, 0.0)
        assertEquals(7000.0, g3.endMs, 0.0)
        assertEquals(3, g3.line)
        assertTrue(song.duet)
    }

    @Test
    fun voicesOffKeepsLinesAsIs() {
        val lines = listOf(
            line(0, 0, 2000, "男：Hello world"),
            line(1, 2000, 4000, "女：Is it me (is it me)"),
            line(2, 4000, 5000, "（ooh）"),
        )
        val song = amllSong(lines, LyricSubline.Translation, voices = false)
        assertEquals(listOf("男：Hello world", "女：Is it me (is it me)", "（ooh）"), song.groups.map { it.main.text() })
        assertTrue(song.groups.all { it.bg == null && !it.duet })
        assertFalse(song.duet)
    }

    @Test
    fun prefixAndParenHelpers() {
        assertEquals('男' to 2, amllVoicePrefix("男：你好"))
        assertEquals('女' to 5, amllVoicePrefix(" 女 : hi"))
        assertEquals('合' to 2, amllVoicePrefix("合:一起"))
        assertNull(amllVoicePrefix("男人：你好"))
        assertNull(amllBackgroundSplit("没有括号"))
        assertNull(amllBackgroundSplit("空的 ()"))
        assertNull(amllBackgroundSplit("(在中间) 不算"))
        val tail = amllBackgroundSplit("Is it me (is (it) me) ")
        assertNotNull(tail)
        assertFalse(tail!!.whole)
        assertEquals(8, tail.mainTo)
        assertEquals("is (it) me", "Is it me (is (it) me) ".substring(tail.bgFrom, tail.bgTo))
        assertTrue(amllBackgroundSplit("（整句）")!!.whole)
        assertTrue(amllIsCjk("晚安"))
        assertFalse(amllIsCjk("晚，"))
        assertFalse(amllIsCjk("ok"))
    }

    @Test
    fun timedWordsDriveEmphasis() {
        val words = listOf(
            word("晚", 0, 1200), word("安", 1200, 1500), word(" ", 1500, 1500),
            word("sooo", 1500, 2700), word(" ", 2700, 2700), word("a", 2700, 4200),
        )
        val timed = amllSong(listOf(line(0, 0, 4200, "晚安 sooo a", words)), LyricSubline.Translation, voices = true).groups.single().main
        assertTrue(timed.timed)
        val units = timed.units.filter { !it.space }
        assertEquals(listOf("晚", "安", "sooo", "a"), units.map { it.text })
        // 唱满 1 秒：中日韩按字，拉丁单词还要 2–7 个字母
        assertEquals(listOf(true, false, true, false), units.map { it.emphasize })
        assertTrue(units.last().lastWord)
        assertFalse(units.first().lastWord)
        assertEquals(listOf("晚", "安", "sooo", "a"), timed.words.map { it.text })

        // 只有句级时间：均分给每个字，不做长音强调
        val plain = amllSong(listOf(line(0, 0, 8000, "晚安 sooo")), LyricSubline.Translation, voices = true).groups.single().main
        assertFalse(plain.timed)
        assertTrue(plain.units.none { it.emphasize })
        assertEquals(listOf("晚", "安", " ", "sooo"), plain.units.map { it.text })
    }

    @Test
    fun interludesNeedSevenSeconds() {
        val inter = amllInterludes(doubleArrayOf(8000.0, 10000.0, 30000.0), doubleArrayOf(9500.0, 12000.0, 31000.0))
        assertEquals(listOf(AmllInterlude(0.0, 8000.0, -1), AmllInterlude(12000.0, 30000.0, 1)), inter)
        // 重叠的句子按到目前为止最晚的句末算
        val overlap = amllInterludes(doubleArrayOf(0.0, 1000.0, 15000.0), doubleArrayOf(10000.0, 2000.0, 16000.0))
        assertTrue(overlap.isEmpty())
        val song = amllSong(listOf(line(0, 0, 2000, "a"), line(1, 10000, 12000, "b")), LyricSubline.Translation, voices = true)
        assertEquals(listOf(AmllInterlude(2000.0, 10000.0, 0)), song.interludes)
    }

    // ---- 时间线 ----

    @Test
    fun finishedLineLingersUntilTheNextStarts() {
        val t = AmllTimeline()
        t.setBounds(doubleArrayOf(0.0, 1000.0, 2000.0, 2500.0), doubleArrayOf(900.0, 2000.0, 3000.0, 3200.0), emptyList())
        t.sync(100.0)
        assertEquals(setOf(0), t.playing)
        assertEquals(setOf(0), t.highlighted)
        assertEquals(0, t.scrollToIndex)

        t.sync(950.0)
        assertTrue(t.playing.isEmpty())
        assertEquals(setOf(0), t.highlighted)
        assertEquals(listOf(0), t.removedPlaying)

        t.sync(1000.0)
        assertEquals(setOf(1), t.highlighted)
        assertEquals(listOf(0), t.removedHighlighted)
        assertEquals(1, t.scrollToIndex)
        assertTrue(t.scrollToChanged)

        // 重叠的两句一起亮，焦点给靠前的那句
        t.sync(2600.0)
        assertEquals(setOf(2, 3), t.highlighted)
        assertEquals(2, t.scrollToIndex)
        assertEquals(3, t.latestHighlighted)
        assertFalse(t.timeJumped)

        // 往回跳：重新推导
        t.sync(1500.0)
        assertTrue(t.timeJumped)
        assertEquals(setOf(1), t.playing)
        assertEquals(setOf(1), t.highlighted)
        assertEquals(1, t.scrollToIndex)

        // 播完：全部灭掉
        t.sync(4000.0)
        assertTrue(t.endOfSong)
        assertTrue(t.endOfSongChanged)
        assertTrue(t.highlighted.isEmpty())
        assertEquals(-1, t.latestHighlighted)
    }

    @Test
    fun lineTimedLinesLetGoAtTheirOwnEndWhenOverlapping() {
        // 两句重叠：只有行时间戳的，前一句唱到自己的结束就熄，焦点交给还在唱的那句
        val lineLevel = AmllTimeline()
        lineLevel.setBounds(doubleArrayOf(0.0, 1000.0), doubleArrayOf(1500.0, 2000.0), emptyList(), booleanArrayOf(true, true))
        lineLevel.sync(500.0)
        lineLevel.sync(1100.0)
        assertEquals(setOf(0, 1), lineLevel.highlighted)
        assertEquals(0, lineLevel.scrollToIndex)
        lineLevel.sync(1600.0)
        assertEquals(setOf(1), lineLevel.highlighted)
        assertEquals(listOf(0), lineLevel.removedHighlighted)
        assertEquals(1, lineLevel.scrollToIndex)
        assertTrue(lineLevel.scrollToChanged)

        // 有逐字时间的：重叠的这组一起亮着
        val timed = AmllTimeline()
        timed.setBounds(doubleArrayOf(0.0, 1000.0), doubleArrayOf(1500.0, 2000.0), emptyList(), booleanArrayOf(false, false))
        timed.sync(500.0)
        timed.sync(1100.0)
        timed.sync(1600.0)
        assertEquals(setOf(0, 1), timed.highlighted)
        assertEquals(0, timed.scrollToIndex)

        // 没人在唱（空档）时行时间戳的句子也照样留到下一句开始
        val gap = AmllTimeline()
        gap.setBounds(doubleArrayOf(0.0, 3000.0), doubleArrayOf(1000.0, 4000.0), emptyList(), booleanArrayOf(true, true))
        gap.sync(500.0)
        gap.sync(2000.0)
        assertEquals(setOf(0), gap.highlighted)
    }

    @Test
    fun interludeTakesFocusWhenNothingIsLit() {
        val starts = doubleArrayOf(0.0, 10000.0)
        val ends = doubleArrayOf(2000.0, 12000.0)
        val interludes = amllInterludes(starts, ends)
        val t = AmllTimeline()
        t.setBounds(starts, ends, interludes)
        val focus = AmllFocus()
        t.sync(500.0)
        focus.resolve(t, 2, suspended = false, canDisplayInterlude = true)
        assertEquals(AMLL_FOCUS_LINE, focus.kind)
        assertEquals(0, focus.index)

        t.sync(3000.0)
        assertSame(interludes[0], t.activeInterlude)
        assertTrue(t.interludeChanged)
        assertTrue(t.focusOnInterlude)
        assertTrue(t.highlighted.isEmpty())
        focus.resolve(t, 2, suspended = false, canDisplayInterlude = true)
        assertEquals(AMLL_FOCUS_INTERLUDE, focus.kind)
        assertEquals(0, focus.index)
        assertEquals(0, amllInterludeAnchor(t.activeInterlude, focus))
        // 三点放不下（间奏太短）时焦点给后面那句
        val noDots = AmllFocus()
        noDots.resolve(t, 2, suspended = false, canDisplayInterlude = false)
        assertEquals(AMLL_FOCUS_LINE, noDots.kind)
        assertEquals(1, noDots.index)

        t.sync(10000.0)
        assertNull(t.activeInterlude)
        assertFalse(t.focusOnInterlude)
        assertEquals(setOf(1), t.highlighted)
        // 手势挂起时焦点冻在三点上，间奏结束后落到后面那句
        focus.resolve(t, 2, suspended = true, canDisplayInterlude = true)
        assertEquals(AMLL_FOCUS_LINE, focus.kind)
        assertEquals(1, focus.index)
        assertNull(amllInterludeAnchor(null, focus))
    }

    @Test
    fun seekDetectionLeavesRoomForClockJitter() {
        assertFalse(amllDetectSeek(Double.NaN, 5000.0, 16.0, true))
        assertFalse(amllDetectSeek(1000.0, 1016.0, 16.0, true))
        assertFalse(amllDetectSeek(1000.0, 980.0, 16.0, true))
        assertTrue(amllDetectSeek(1000.0, 960.0, 16.0, true))
        assertFalse(amllDetectSeek(1000.0, 1600.0, 16.0, true))
        assertTrue(amllDetectSeek(1000.0, 1700.0, 16.0, true))
        // 暂停时帧间隔不算
        assertTrue(amllDetectSeek(1000.0, 1610.0, 100.0, false))
    }

    // ---- 排版 ----

    @Test
    fun layoutPutsTheFocusTextTopAtTheAnchor() {
        val layout = AmllLayout()
        layout.setHeights(doubleArrayOf(100.0, 50.0, 80.0))
        // 字顶对齐 246（组的上边再往下 10 是字）：焦点组的上边落在 236，和折几行、组多高无关
        layout.begin(1000.0, AMLL_FOCUS_LINE, 1, 0.0, null, alignTop = 246.0, lineInset = 10.0)
        assertEquals(-100.0, layout.minOffset, 0.0)
        assertEquals(0.0, layout.maxOffset, 0.0)
        layout.commit(1000.0, 0.0)
        assertArrayEquals(doubleArrayOf(136.0, 236.0, 286.0), layout.ys.copyOf(3), 1e-9)
        assertFalse(layout.hasInterlude)
        assertEquals(-100.0, layout.clampOffset(-500.0), 0.0)
        assertEquals(0.0, layout.clampOffset(50.0), 0.0)
        layout.commit(1000.0, -100.0)
        assertArrayEquals(doubleArrayOf(236.0, 336.0, 386.0), layout.ys.copyOf(3), 1e-9)

        // 三点挂在第 0 组后面：后面的组整体下移，焦点组还在原处
        layout.begin(1000.0, AMLL_FOCUS_LINE, 1, 40.0, 0, alignTop = 246.0, lineInset = 10.0, interludeInset = 8.0)
        layout.commit(1000.0, 0.0)
        assertArrayEquals(doubleArrayOf(96.0, 236.0, 286.0), layout.ys.copyOf(3), 1e-9)
        assertTrue(layout.hasInterlude)
        assertEquals(196.0, layout.interludeY, 1e-9)

        // 焦点是第一句之前的三点：三点的上边再往下 8 对齐
        layout.begin(1000.0, AMLL_FOCUS_INTERLUDE, -1, 60.0, -1, alignTop = 246.0, lineInset = 10.0, interludeInset = 8.0)
        layout.commit(1000.0, 0.0)
        assertEquals(238.0, layout.interludeY, 1e-9)
        assertEquals(298.0, layout.ys[0], 1e-9)

        // 焦点越界：不排
        layout.begin(1000.0, AMLL_FOCUS_LINE, 5, 0.0, null, alignTop = 246.0)
        assertFalse(layout.valid)
        layout.commit(1000.0, 0.0)
        assertTrue(layout.inViewport.take(3).none { it })
    }

    @Test
    fun farGroupsLeaveTheViewport() {
        val layout = AmllLayout()
        layout.setHeights(doubleArrayOf(100.0, 2000.0, 100.0))
        layout.begin(1000.0, AMLL_FOCUS_LINE, 0, 0.0, null, alignTop = 300.0)
        layout.commit(1000.0, 0.0)
        assertArrayEquals(doubleArrayOf(300.0, 400.0, 2400.0), layout.ys.copyOf(3), 1e-9)
        // 视口上下各留 300 + 40% 视口高
        assertEquals(listOf(true, true, false), layout.inViewport.take(3))
    }

    @Test
    fun depthBlurAndOpacity() {
        assertEquals(4f, amllBlur(0, false, true, 2, 2, narrow = false, touchScrolled = false, strength = 1f), 1e-6f)
        assertEquals(3.2f, amllBlur(0, false, true, 2, 2, narrow = true, touchScrolled = false, strength = 1f), 1e-6f)
        assertEquals(3f, amllBlur(5, false, true, 2, 3, narrow = false, touchScrolled = false, strength = 1f), 1e-6f)
        assertEquals(5f, amllBlur(20, false, true, 2, 3, narrow = false, touchScrolled = false, strength = 1f), 1e-6f)
        assertEquals(7.5f, amllBlur(20, false, true, 2, 3, narrow = false, touchScrolled = false, strength = 1.5f), 1e-6f)
        assertEquals(1.5f, amllBlur(5, false, true, 2, 3, narrow = false, touchScrolled = false, strength = 0.5f), 1e-6f)
        assertEquals(0f, amllBlur(2, true, true, 2, 2, narrow = false, touchScrolled = false, strength = 1f), 0f)
        assertEquals(0f, amllBlur(0, false, false, 2, 2, narrow = false, touchScrolled = false, strength = 1f), 0f)
        assertEquals(0f, amllBlur(0, false, true, 2, 2, narrow = false, touchScrolled = true, strength = 1f), 0f)
        assertEquals(0f, amllBlur(0, false, true, 2, 2, narrow = false, touchScrolled = false, strength = 0f), 0f)
        // 亮着的组不再打 85 折
        assertEquals(0f, amllOpacity(inViewport = false), 0f)
        assertEquals(1f, amllOpacity(inViewport = true), 0f)
    }

    // ---- 声明与设置默认值 ----

    @Test
    fun declarationMatchesTheBrief() {
        assertEquals("amll", AmllVisualizer.id)
        assertEquals("AMLL", AmllVisualizer.label)
        assertEquals(2, AmllVisualizer.order)
        assertEquals(ModOrigin("AMLL", "core 0.7.2", "amll-dev", "AGPL-3.0", kind = "开源组件"), AmllVisualizer.origin)
        assertEquals(8, AmllVisualizer.hiddenSettings)
        assertFalse(AmllVisualizer.hostBackground)
        assertFalse(AmllVisualizer.hostSubtitles)
        assertTrue(AmllVisualizer.interactive)
        assertSame(RushVisualizer, LyricVisualizers.all[1])
        assertSame(AmllVisualizer, LyricVisualizers.all[2])
        assertSame(AmllVisualizer, LyricVisualizers.byId("amll"))
    }

    @Test
    fun settingsDefaultsMatchTheDesign() {
        val schema = AmllVisualizer.settings
        assertEquals(listOf("background", "size", "blur", "emphasis", "voices"), schema.map { it.key })
        assertEquals(listOf("背景", "字号", "景深模糊", "长音强调", "对唱与和声"), schema.map { it.label })
        // 面板里不放小字说明
        assertTrue(schema.all { it.summary == null })
        assertSame(schema, AmllVisualizer.settings)

        val defaults = SettingValues(schema, emptyMap())
        val look = amllLook(defaults)
        assertEquals("mesh", look.background)
        assertEquals(1f, look.size, 0f)
        // 单测里没定设备档位，按能实时模糊算：默认“中”
        assertEquals(1f, look.blur, 0f)
        assertTrue(look.emphasis)
        assertTrue(look.voices)
        assertEquals(0, defaults.changed)

        val background = schema[0] as LyricSetting.Choice
        assertEquals(listOf("mesh" to "网格", "flow" to "流光", "solid" to "纯色"), background.options)
        assertEquals("mesh", SettingValues(schema, mapOf("background" to "bogus")).choice("background"))

        val size = schema[1] as LyricSetting.Number
        assertEquals(0.8f, size.min, 0f)
        assertEquals(1.4f, size.max, 0f)
        assertEquals(0.1f, size.step, 0f)
        assertEquals(1.4f, SettingValues(schema, mapOf("size" to "1.6")).number("size"), 1e-6f)

        val blur = schema[2] as LyricSetting.Slider
        assertEquals(0f, blur.min, 0f)
        assertEquals(1.5f, blur.max, 0f)
        assertEquals(listOf(0f, 0.5f, 1f, 1.5f), blur.ticks)
        assertEquals(listOf("关", "弱", "中", "强"), blur.tickLabels)
        assertEquals(31, blur.minSdk)
        assertEquals(listOf("关", "弱", "中", "强"), listOf(0f, 0.5f, 1f, 1.5f).map(blur.format))
        assertEquals("关", amllBlurLabel(0.005f))

        val custom = amllLook(SettingValues(schema, mapOf("background" to "solid", "blur" to "1.5", "emphasis" to "false", "voices" to "false")))
        assertEquals("solid", custom.background)
        assertEquals(1.5f, custom.blur, 0f)
        assertFalse(custom.emphasis)
        assertFalse(custom.voices)
    }

    @Test
    fun onlyTheMeshBackgroundListens() {
        val schema = AmllVisualizer.settings
        assertTrue(AmllVisualizer.wantsAudio(SettingValues(schema, emptyMap())))
        assertFalse(AmllVisualizer.wantsAudio(SettingValues(schema, mapOf("background" to "flow"))))
        assertFalse(AmllVisualizer.wantsAudio(SettingValues(schema, mapOf("background" to "solid"))))
    }

    @Test
    fun blurDefaultsOffWithoutLiveBlur() {
        val field = StageDevice::class.java.getDeclaredField("liveBlur").apply { isAccessible = true }
        val on = AmllVisualizer.settings
        val previous = field.getBoolean(null)
        try {
            field.setBoolean(null, false)
            val off = AmllVisualizer.settings
            assertNotSame(on, off)
            assertSame(off, AmllVisualizer.settings)
            assertEquals(on.map { it.key }, off.map { it.key })
            assertEquals(0f, amllLook(SettingValues(off, emptyMap())).blur, 0f)
            assertEquals(0f, (off[2] as LyricSetting.Slider).default, 0f)
        } finally {
            field.setBoolean(null, previous)
        }
        assertSame(on, AmllVisualizer.settings)
    }

    @Test
    fun fullWidthAndNoBreakSpacesSplitWordsLikeAsciiSpaces() {
        // JS 的 \s 含 U+3000、U+00A0：和半角空格分出一样的词组
        fun chunks(space: Char) = amllChunkWords(listOf(AmllWord("你好${space}世界", 0.0, 1000.0), AmllWord("ok", 1000.0, 1500.0)))
            .map { group -> group.map { it.text.replace(space, ' ') to it.startMs } }
        assertEquals(chunks(' '), chunks('\u3000'))
        assertEquals(chunks(' '), chunks('\u00A0'))
    }

    private fun AmllLineText.text(): String = units.joinToString("") { it.text }

    private fun line(index: Int, start: Long, end: Long, text: String, words: List<LyricWord> = emptyList(), translation: String = "") =
        StageLine(index, start, end, text, words, translation, "")

    private fun word(text: String, start: Long, end: Long) = LyricWord(start, end, text)

    // ---- Apple Music 的换句曲线 ----

    @Test
    fun glideMatchesTheIphoneRecording() {
        // iOS 16.2 录屏（B 站 BV13K41127Tc，30 帧）里当前句离停稳位置还差多少 px：38805ms 出发，430ms 走完 160px
        val times = doubleArrayOf(38800.0, 38833.3, 38866.7, 38900.0, 38933.3, 38966.7, 39000.0, 39033.3, 39066.7, 39100.0, 39133.3, 39166.7, 39200.0, 39233.3, 39300.0)
        val measured = doubleArrayOf(160.0, 154.0, 132.0, 104.0, 78.0, 57.0, 39.0, 26.0, 17.0, 11.0, 6.0, 4.0, 2.0, 1.0, 0.0)
        val glide = AmllGlide(160.0)
        var clock = 38805.0
        glide.setTarget(0.0)
        times.forEachIndexed { k, t ->
            if (t >= clock) {
                glide.update((t - clock) / 1000.0)
                clock = t
            }
            val shown = if (t < 38805.0) 160.0 else glide.position
            assertEquals("t=$t", measured[k], shown, 2.0)
        }
        assertTrue(glide.arrived())
        assertEquals(0.0, glide.position, 0.0)
    }

    @Test
    fun glideRetargetsWithoutJumpingAndHonoursDelays() {
        val glide = AmllGlide(0.0)
        glide.setTarget(100.0)
        repeat(12) { glide.update(1.0 / 60) }
        val before = glide.position
        assertTrue(before in 1.0..99.0)
        // 中途改目标：位置不跳，接着往新目标走，430ms 后到位
        glide.setTarget(200.0)
        assertEquals(before, glide.position, 1e-9)
        var last = before
        repeat(26) {
            glide.update(1.0 / 60)
            assertTrue("moves forward", glide.position >= last - 1e-9)
            last = glide.position
        }
        assertEquals(200.0, glide.position, 1e-6)
        assertTrue(glide.arrived())

        // 延迟目标：到点前不动，到点后再走 430ms
        val delayed = AmllGlide(0.0)
        delayed.setTarget(50.0, delay = 0.1)
        delayed.update(0.05)
        assertEquals(0.0, delayed.position, 0.0)
        assertFalse(delayed.arrived())
        delayed.update(0.05)
        delayed.update(0.43)
        assertEquals(50.0, delayed.position, 1e-6)
        assertTrue(delayed.arrived())

        // 直接放：清掉在走的段和排队的目标
        delayed.setTarget(80.0, delay = 0.2)
        delayed.setPosition(10.0)
        delayed.update(0.5)
        assertEquals(10.0, delayed.position, 0.0)
    }

    @Test
    fun focusTopSitsAQuarterDownTheStage() {
        // 舞台 800、歌词栏从 100 开始：24.6% 处在栏里的 96.8
        assertEquals(96.8, amllAlignTop(800.0, 100.0, 600.0), 1e-9)
        // 歌词栏开得太低：不落进上端渐隐区（44dp）
        assertEquals(44.0, amllAlignTop(800.0, 300.0, 400.0), 1e-9)
        // 很高的舞台：最多到栏高的一半
        assertEquals(150.0, amllAlignTop(2000.0, 0.0, 300.0), 1e-9)
        // 舞台让开了状态栏 50、导航栏 40：按整块屏幕 800 算，24.6% 是 196.8，在舞台里是 146.8，在栏里是 66.8
        assertEquals(66.8, amllAlignTop(710.0, 80.0, 500.0, 50.0, 40.0), 1e-9)
        // 手机实测：屏高 2400px（2.625 倍），状态栏 136、导航栏 126，栏从舞台 330 开始：字顶在屏幕 590.4px
        val density = 2.625
        val column = amllAlignTop(2138 / density, 330 / density, 1700 / density, 136 / density, 126 / density)
        assertEquals(590.4, (column * density) + 330 + 136, 1e-6)
    }

    @Test
    fun wordsRiseAfterATenthOfASecondOverTheirOwnLength() {
        assertEquals(0f, amllRiseProgress(1050.0, 1000.0, 400.0), 0f)
        assertEquals(0f, amllRiseProgress(1100.0, 1000.0, 400.0), 0f)
        assertEquals(0.5f, amllRiseProgress(1300.0, 1000.0, 400.0), 1e-6f)
        assertEquals(1f, amllRiseProgress(1500.0, 1000.0, 400.0), 0f)
        // 时长为 0 的词：到点就上去
        assertEquals(1f, amllRiseProgress(1101.0, 1000.0, 0.0), 0f)
    }
}
