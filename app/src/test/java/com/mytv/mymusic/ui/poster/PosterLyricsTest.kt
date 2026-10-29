package com.mytv.mymusic.ui.poster

import com.mytv.mymusic.ui.LyricLine
import com.mytv.mymusic.ui.LyricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterLyricsTest {
    // ---------- 翻译合并 ----------

    @Test
    fun translationOnSameTimestampFoldsUnderOriginal() {
        // 酷我 / QQ 的写法：翻译和原文同一个时间戳，紧跟在原文后面
        val parsed = LyricsParser.parse(
            "[00:26.500]<00:26.500>难<00:26.900>道\n[00:26.500]Is it wrong\n[00:30.000]下一句\n",
        )
        val lines = posterLines(parsed)
        assertEquals(2, lines.size)
        assertEquals("难道", lines[0].line.text)
        assertEquals("Is it wrong", lines[0].translation)
        // 逐字时间轴留在原文上
        assertEquals(2, lines[0].line.words.size)
        assertEquals("", lines[1].translation)
    }

    @Test
    fun withRomajiAndTranslationTheLastOneWins() {
        // QQ 的顺序：原文、罗马音、翻译
        val parsed = LyricsParser.parse("[00:10.00]君の名は\n[00:10.00]kimi no na wa\n[00:10.00]你的名字\n[00:14.00]次")
        val lines = posterLines(parsed)
        assertEquals(listOf("君の名は", "次"), lines.map { it.line.text })
        assertEquals("你的名字", lines[0].translation)
    }

    @Test
    fun translationIdenticalToOriginalIsDropped() {
        val lines = posterLines(LyricsParser.parse("[00:01.00]Hello\n[00:01.00]Hello\n[00:03.00]World"))
        assertEquals("", lines[0].translation)
    }

    @Test
    fun unsyncedLyricsGiveNoLines() {
        assertTrue(posterLines(LyricsParser.parse("没有时间戳的歌词\n第二行")).isEmpty())
        assertTrue(posterLines(LyricsParser.parse("")).isEmpty())
    }

    // ---------- 字号预算 ----------

    private fun budgetPx(size: PosterLyricSize, pxPerSp: Float, pxPerDp: Float, hasTranslation: Boolean): Float {
        val body = size.bodySp * pxPerSp
        var total = 3 * body * PosterBodyLineHeight + 2 * body * PosterBodyLineHeight * PosterContextScale + 2 * body * PosterLineGapEm
        if (hasTranslation) total += 2 * size.translationSp * pxPerSp * PosterTranslationLineHeight + PosterTranslationGapDp * pxPerDp
        return total
    }

    @Test
    fun phoneBoxGetsThePrototypeSize() {
        // 手机大卡 332dp、内边距 16：标签、歌名、控件行之外的歌词框约 163dp，正好放下原型的 20sp
        val size = posterLyricSize(boxPx = 163.5f * 3, pxPerSp = 3f, pxPerDp = 3f, maxSp = 20f, maxTranslationSp = 12.5f, hasTranslation = true)
        assertEquals(20f, size.bodySp, 0.001f)
        assertEquals(12.5f, size.translationSp, 0.001f)
    }

    @Test
    fun largerFontScaleShrinksToFitTheSameBox() {
        val size = posterLyricSize(boxPx = 163.5f * 3, pxPerSp = 3f * 1.3f, pxPerDp = 3f, maxSp = 20f, maxTranslationSp = 12.5f, hasTranslation = true)
        assertEquals(15.5f, size.bodySp, 0.001f)
        // 翻译按同一比例跟着缩，但不小于 10sp
        assertEquals(PosterMinTranslationSp, size.translationSp, 0.001f)
        assertTrue(budgetPx(size, 3.9f, 3f, true) <= 163.5f * 3)
    }

    @Test
    fun budgetAlwaysFitsUnlessClampedToTheFloor() {
        for (hasTranslation in listOf(true, false)) {
            var box = 40f
            while (box < 1200f) {
                val size = posterLyricSize(box, pxPerSp = 2.75f, pxPerDp = 2.75f, maxSp = 34f, maxTranslationSp = 16f, hasTranslation = hasTranslation)
                assertTrue(size.bodySp in PosterMinLyricSp..34f)
                // 0.5sp 的整数倍
                assertEquals(0f, (size.bodySp * 2f) % 1f, 0.0001f)
                if (size.bodySp > PosterMinLyricSp) {
                    assertTrue("box=$box $size", budgetPx(size, 2.75f, 2.75f, hasTranslation) <= box + 0.01f)
                }
                box += 7.5f
            }
        }
    }

    @Test
    fun songsWithoutTranslationGetAtLeastAsLargeAFont() {
        var box = 100f
        while (box < 900f) {
            val with = posterLyricSize(box, 3f, 3f, 26f, 14f, hasTranslation = true)
            val without = posterLyricSize(box, 3f, 3f, 26f, 14f, hasTranslation = false)
            assertTrue(without.bodySp >= with.bodySp)
            box += 10f
        }
    }

    @Test
    fun budgetNeverExceedsMaxOrDropsBelowFloor() {
        assertEquals(34f, posterLyricSize(10_000f, 3f, 3f, 34f, 16f, true).bodySp, 0.001f)
        assertEquals(PosterMinLyricSp, posterLyricSize(0f, 3f, 3f, 34f, 16f, true).bodySp, 0.001f)
        assertEquals(PosterMinLyricSp, posterLyricSize(-5f, 0f, 3f, 34f, 16f, true).bodySp, 0.001f)
    }

    // ---------- 逐字线性扫 ----------

    @Test
    fun linearWordsSplitCjkPerCharAndLatinPerWord() {
        val cjk = linearWords("但愿人长久", 1_000, 6_000)
        assertEquals(listOf("但", "愿", "人", "长", "久"), cjk.map { it.text })
        val latin = linearWords("Is it wrong", 0, 3_000)
        assertEquals(listOf("Is ", "it ", "wrong"), latin.map { it.text })
        val mixed = linearWords("我 love 你", 0, 3_000)
        assertEquals(listOf("我 ", "love ", "你"), mixed.map { it.text })
        assertEquals("我 love 你", mixed.joinToString("") { it.text })
    }

    @Test
    fun linearWordsSweepEightyPercentOfTheLineContiguously() {
        val words = linearWords("明月几时有", 10_000, 13_000)
        assertEquals(10_000L, words.first().startMs)
        assertEquals(12_400L, words.last().endMs)
        for (i in 1 until words.size) assertEquals(words[i - 1].endMs, words[i].startMs)
        assertTrue(words.all { it.endMs > it.startMs })
    }

    @Test
    fun linearSweepIsCappedBeforeLongInterludes() {
        // 间奏前最后一句：离下一句 30 秒，也不按 24 秒慢慢扫，每字最多 0.5 秒
        val words = linearWords("送别", 0, 30_000)
        assertEquals(1_000L, words.last().endMs)
        assertTrue(linearWords("   ", 0, 1_000).isEmpty())
    }

    @Test
    fun karaokeLineKeepsVerbatimWordsAndSynthesizesTheRest() {
        val parsed = LyricsParser.parse("[00:01.00]<00:01.00>独<00:01.50>上\n[00:04.00]西楼")
        val lines = posterLines(parsed)
        assertEquals(lines[0].line, posterKaraokeLine(lines, 0, 60_000))
        val last = posterKaraokeLine(lines, 1, 10_000)
        assertEquals(listOf("西", "楼"), last.words.map { it.text })
        assertEquals(4_000L, last.words.first().startMs)
        // 最后一行扫到歌曲时长的 80%（这里被每字 0.5 秒的上限截住）
        assertEquals(5_000L, last.words.last().endMs)
        val noDuration = posterKaraokeLine(listOf(PosterLine(LyricLine(2_000, "末行"))), 0, 0)
        assertEquals(2_000L + 1_000L, noDuration.words.last().endMs)
    }

    // ---------- 行的样子 ----------

    @Test
    fun neighbourLinesMatchThePrototype() {
        assertEquals(1f, posterLineScale(0f), 0f)
        assertEquals(0.72f, posterLineScale(1f), 0.0001f)
        assertEquals(0.65f, posterLineScale(2f), 0.0001f)
        assertEquals(1f, posterLineAlpha(0f), 0f)
        assertEquals(0.62f, posterLineAlpha(1f), 0.0001f)
        assertEquals(0.22f, posterLineAlpha(2f), 0.0001f)
        assertEquals(0f, posterLineAlpha(3f), 0f)
        assertEquals(0f, posterLineBlurDp(0f), 0f)
        assertEquals(0.7f, posterLineBlurDp(1f), 0.0001f)
        assertEquals(1.2f, posterLineBlurDp(2f), 0.0001f)
        assertEquals(1f, posterTranslationReveal(0f), 0f)
        assertEquals(0f, posterTranslationReveal(1f), 0f)
    }

    @Test
    fun lineStyleChangesContinuouslyAndMonotonically() {
        var d = 0f
        var scale = posterLineScale(0f)
        var alpha = posterLineAlpha(0f)
        var blur = posterLineBlurDp(0f)
        while (d < 3.5f) {
            d += 0.01f
            val s = posterLineScale(d)
            val a = posterLineAlpha(d)
            val b = posterLineBlurDp(d)
            assertTrue(s <= scale + 1e-6f && scale - s < 0.01f)
            assertTrue(a <= alpha + 1e-6f && alpha - a < 0.01f)
            assertTrue(b >= blur - 1e-6f && b - blur < 0.01f)
            scale = s
            alpha = a
            blur = b
        }
        // 弹簧过冲到负数也按距离算
        assertEquals(posterLineAlpha(0.05f), posterLineAlpha(-0.05f).coerceAtMost(1f), 0.05f)
    }

    @Test
    fun edgesFadeOnlyNearTheTopAndBottom() {
        assertEquals(1f, posterEdgeFade(46f, 100f), 0f)
        assertEquals(0f, posterEdgeFade(0f, 100f), 0f)
        assertEquals(0.5f, posterEdgeFade(8f, 100f), 0.0001f)
        assertEquals(1f, posterEdgeFade(80f, 100f), 0.0001f)
        assertEquals(0.5f, posterEdgeFade(90f, 100f), 0.0001f)
        assertEquals(0f, posterEdgeFade(130f, 100f), 0f)
        assertEquals(1f, posterEdgeFade(10f, 0f), 0f)
    }

    @Test
    fun anchorInterpolatesBetweenLineCentres() {
        val tops = floatArrayOf(0f, 30f, 90f)
        val heights = floatArrayOf(20f, 50f, 20f)
        assertEquals(10f, posterAnchorCenter(tops, heights, 0f), 0.0001f)
        assertEquals(55f, posterAnchorCenter(tops, heights, 1f), 0.0001f)
        assertEquals(77.5f, posterAnchorCenter(tops, heights, 1.5f), 0.0001f)
        // 越界时贴住两端
        assertEquals(100f, posterAnchorCenter(tops, heights, 5f), 0.0001f)
        assertEquals(10f, posterAnchorCenter(tops, heights, -1f), 0.0001f)
        assertEquals(0f, posterAnchorCenter(FloatArray(0), FloatArray(0), 0f), 0f)
    }
}
