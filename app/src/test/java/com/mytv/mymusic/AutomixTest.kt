package com.mytv.mymusic

import com.mytv.mymusic.data.CrossfadeMode
import com.mytv.mymusic.playback.AlbumSlot
import com.mytv.mymusic.playback.AutomixAnalysis
import com.mytv.mymusic.playback.AutomixMethod
import com.mytv.mymusic.playback.AutomixPlanner
import com.mytv.mymusic.playback.AutomixSection
import com.mytv.mymusic.playback.BeatGrid
import com.mytv.mymusic.playback.CrossfadeReport
import com.mytv.mymusic.playback.CrossfadeStatus
import com.mytv.mymusic.playback.CrossfadeTransition
import com.mytv.mymusic.playback.CrossfadeUpcoming
import com.mytv.mymusic.playback.LyricSpan
import com.mytv.mymusic.playback.SAME_ALBUM_METHOD
import com.mytv.mymusic.playback.estimateKeyFromChroma
import com.mytv.mymusic.playback.transitionSecondsLabel
import com.mytv.mymusic.ui.LyricPipeline
import com.mytv.mymusic.ui.LyricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixTest {
    private val majorProfile = doubleArrayOf(
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88,
    )
    private val minorProfile = doubleArrayOf(
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17,
    )

    private fun rotate(profile: DoubleArray, root: Int): DoubleArray =
        DoubleArray(12) { profile[Math.floorMod(it - root, 12)] }

    @Test
    fun `a major key profile is detected at its root`() {
        val (root, minor, confidence) = estimateKeyFromChroma(rotate(majorProfile, 0))
        assertEquals(0, root)
        assertFalse(minor)
        assertTrue(confidence > 0.3f)
    }

    @Test
    fun `an a minor profile is detected as a minor key`() {
        val (root, minor, _) = estimateKeyFromChroma(rotate(minorProfile, 9))
        assertEquals(9, root)
        assertTrue(minor)
    }

    @Test
    fun `silence has no key`() {
        val (root, _, confidence) = estimateKeyFromChroma(DoubleArray(12))
        assertEquals(-1, root)
        assertEquals(0f, confidence, 0.001f)
    }

    @Test
    fun `analysis survives a database round trip`() {
        val analysis = AutomixAnalysis(
            songKey = "netease:1",
            durationMs = 200_000,
            beatGrid = BeatGrid.NONE,
            loudnessDb = -11.5f,
            keyIndex = 9,
            keyMinor = true,
            keyConfidence = 0.4f,
            introMs = 1_200,
            outroMs = 198_000,
            sections = listOf(AutomixSection(0, 0.6f), AutomixSection(32_000, 1f)),
            updatedAt = 123L,
        )
        val restored = AutomixAnalysis.fromEntity(analysis.toEntity())
        assertEquals(analysis.songKey, restored.songKey)
        assertEquals(analysis.durationMs, restored.durationMs)
        assertEquals(analysis.loudnessDb, restored.loudnessDb, 0.001f)
        assertEquals(analysis.keyIndex, restored.keyIndex)
        assertEquals(analysis.introMs, restored.introMs)
        assertEquals(analysis.outroMs, restored.outroMs)
        assertEquals(analysis.sections, restored.sections)
    }

    @Test
    fun `an analysis is only reused when the duration still matches`() {
        val entity = AutomixAnalysis(
            songKey = "qq:2",
            durationMs = 180_000,
            beatGrid = BeatGrid.NONE,
            loudnessDb = -12f,
            keyIndex = 0,
            keyMinor = false,
            keyConfidence = 0f,
            introMs = 0,
            outroMs = 180_000,
            sections = emptyList(),
            updatedAt = 0,
        ).toEntity()
        assertTrue(AutomixAnalysis.matches(entity, "qq:2", 181_000))
        assertFalse(AutomixAnalysis.matches(entity, "qq:2", 185_000))
        assertFalse(AutomixAnalysis.matches(entity, "qq:3", 180_000))
    }

    private fun analysis(
        key: String,
        bpm: Double,
        confidence: Float = 0.9f,
        t0S: Double = 0.0,
        downbeatClear: Boolean = true,
        introMs: Long = 2_000,
        outroMs: Long = 198_000,
        durationMs: Long = 200_000,
    ) = AutomixAnalysis(
        songKey = key,
        durationMs = durationMs,
        beatGrid = BeatGrid(
            periodS = 60.0 / bpm,
            t0S = t0S,
            k0 = 0L,
            confidence = confidence,
            downbeat = 0,
            downbeatClear = downbeatClear,
            strengthIndex = LongArray(0),
            strengthValue = FloatArray(0),
        ),
        loudnessDb = -10f,
        keyIndex = 0,
        keyMinor = false,
        keyConfidence = 0.5f,
        introMs = introMs,
        outroMs = outroMs,
        sections = emptyList(),
        updatedAt = 0,
    )

    @Test
    fun `two confident grids with matching tempo cut on a downbeat`() {
        val plan = AutomixPlanner.plan(
            analysis("a", 120.0),
            analysis("b", 124.0),
            6,
            durationMs = 200_000,
        )
        assertEquals(AutomixMethod.BeatCut, plan.method)
        // 上一首 198 秒之后是静音（outroMs）：切点在最后有声处之前两秒内的小节首拍上，
        // 距文件末尾还要加上裁掉的那 2 秒静音
        assertEquals(2_000L, plan.endMarginMs)
        assertTrue(plan.startAtRemainingMs - plan.endMarginMs in 500..3_200)
        assertTrue(plan.fadeMs in 400..1_200)
        // 变速把上一首的尾巴拉到和下一首一致
        assertEquals(124.0 / 120.0, plan.outgoingSpeed.toDouble(), 0.001)
        // 下一首 2 秒才出声：从第一个有声处（往前 150ms）之后的第一个小节首拍进，第 4 拍 = 1.935 秒
        assertEquals(1_935L, plan.incomingStartMs)
        // 切拍的交叉点就是切点
        assertEquals(0L, plan.switchAtMs)
    }

    @Test
    fun `a long clean outro and a real intro carry over instead of cutting`() {
        val plan = AutomixPlanner.plan(
            analysis("a", 100.0, confidence = 0.2f, outroMs = 196_000),
            analysis("b", 100.0, confidence = 0.2f, introMs = 3_000),
            6,
            durationMs = 200_000,
        )
        assertEquals(AutomixMethod.OutroCarry, plan.method)
        assertTrue(plan.echoMix > 0f)
    }

    @Test
    fun `one analysed song with a confident grid hands off the bass`() {
        val plan = AutomixPlanner.plan(
            analysis("a", 120.0, introMs = 300),
            analysis("b", 120.0, confidence = 0.2f, introMs = 300),
            6,
            durationMs = 200_000,
        )
        assertEquals(AutomixMethod.BassHandoff, plan.method)
        assertTrue(plan.bassCutoffHz > 0f)
    }

    @Test
    fun `without analysis it falls back to a plain crossfade`() {
        val plan = AutomixPlanner.plan(null, null, 8, durationMs = 200_000)
        assertEquals(AutomixMethod.Crossfade, plan.method)
        assertEquals(8_000L, plan.fadeMs)
        assertEquals(1f, plan.outgoingSpeed, 0.001f)
        // 没分析就没有静音可裁：淡化在文件末尾前 250ms 收完，下一首从头放
        assertEquals(AutomixPlanner.END_MARGIN_MS, plan.endMarginMs)
        assertEquals(8_000L + AutomixPlanner.END_MARGIN_MS, plan.startAtRemainingMs)
        assertEquals(0L, plan.incomingStartMs)
        // 交叉淡化是 Apple 的曲线：两边在 t ≈ 0.33 一样响
        assertEquals(2_630L, plan.switchAtMs)
    }

    // ---------- 首尾静音 ----------

    @Test
    fun `smart transitions end the fade at the last audible moment and skip the silent intro`() {
        val plan = AutomixPlanner.plan(
            analysis("a", 100.0, confidence = 0.2f, outroMs = 194_000),
            analysis("b", 100.0, confidence = 0.2f, introMs = 600),
            6,
            durationMs = 200_000,
        )
        assertEquals(AutomixMethod.Crossfade, plan.method)
        // 最后 6 秒是静音：淡化在 194 秒收完，所以提前 6 + 6 秒开始
        assertEquals(6_000L, plan.endMarginMs)
        assertEquals(12_000L, plan.startAtRemainingMs)
        // 下一首从第一个有声处往前 150ms 开始
        assertEquals(450L, plan.incomingStartMs)
    }

    @Test
    fun `an outro carry also trims both ends`() {
        val plan = AutomixPlanner.plan(
            analysis("a", 100.0, confidence = 0.2f, outroMs = 196_000),
            analysis("b", 100.0, confidence = 0.2f, introMs = 3_000),
            6,
            durationMs = 200_000,
        )
        assertEquals(AutomixMethod.OutroCarry, plan.method)
        assertEquals(4_000L, plan.endMarginMs)
        assertEquals(plan.fadeMs + 4_000L, plan.startAtRemainingMs)
        assertEquals(2_850L, plan.incomingStartMs)
    }

    @Test
    fun `an implausible outro is not trusted`() {
        // 最后有声处在一半以前多半是分析出了岔子：不裁，照文件末尾淡
        val plan = AutomixPlanner.plan(
            analysis("a", 100.0, confidence = 0.2f, outroMs = 60_000),
            analysis("b", 100.0, confidence = 0.2f, introMs = 0),
            6,
            durationMs = 200_000,
        )
        assertEquals(AutomixPlanner.END_MARGIN_MS, plan.endMarginMs)
        assertEquals(0L, plan.incomingStartMs)
    }

    @Test
    fun `a plain crossfade never trims and follows the 1 to 12 second slider`() {
        val one = AutomixPlanner.fixedFade(1)
        assertEquals(1_000L, one.fadeMs)
        assertEquals(1_000L + AutomixPlanner.END_MARGIN_MS, one.startAtRemainingMs)
        assertEquals(0L, one.incomingStartMs)
        assertEquals(1f, one.outgoingSpeed, 0f)
        assertEquals(AutomixMethod.Crossfade, one.method)
        assertEquals(12_000L, AutomixPlanner.fixedFade(12).fadeMs)
        // 范围外的旧值按边界取
        assertEquals(12_000L, AutomixPlanner.fixedFade(30).fadeMs)
        assertEquals(1_000L, AutomixPlanner.fixedFade(0).fadeMs)
        // 智能过渡自己的时长范围不变
        assertEquals(4_000L, AutomixPlanner.plan(null, null, 1, durationMs = 200_000).fadeMs)
        assertEquals(10_000L, AutomixPlanner.plan(null, null, 12, durationMs = 200_000).fadeMs)
    }

    // ---------- 换页点 ----------

    /** 等功率的接法（这里用低频交接）：交叉点在淡化正中间。 */
    private val equalPower8s = AutomixPlanner.plan(null, null, 8, durationMs = 200_000).copy(method = AutomixMethod.BassHandoff)

    @Test
    fun `a plain crossfade crosses over a third of the way in`() {
        val crossfade = AutomixPlanner.fixedFade(6)
        assertEquals(1_973L, AutomixPlanner.crossoverMs(AutomixMethod.Crossfade, 6_000))
        assertEquals(1_973L, AutomixPlanner.switchAtMs(crossfade, 6_000, 190_000, null, null))
        assertEquals(3_000L, AutomixPlanner.crossoverMs(AutomixMethod.BassHandoff, 6_000))
        assertEquals(3_000L, AutomixPlanner.crossoverMs(AutomixMethod.OutroCarry, 6_000))
        assertEquals(0L, AutomixPlanner.crossoverMs(AutomixMethod.BeatCut, 6_000))
    }

    @Test
    fun `without lyrics on either side the page turns at the crossover`() {
        val outgoing = LyricSpan(firstStartMs = 10_000, lastEndMs = 180_000)
        assertEquals(4_000L, AutomixPlanner.switchAtMs(equalPower8s, 8_000, 190_000, null, null))
        assertEquals(4_000L, AutomixPlanner.switchAtMs(equalPower8s, 8_000, 190_000, outgoing, null))
        assertEquals(
            4_000L,
            AutomixPlanner.switchAtMs(equalPower8s, 8_000, 190_000, null, LyricSpan(1_000, 150_000)),
        )
        // 淡化被压短时交叉点跟着实际时长走
        assertEquals(3_000L, AutomixPlanner.switchAtMs(equalPower8s, 6_000, 190_000, null, null))
    }

    @Test
    fun `the page turns 300 ms before the next song starts singing if that comes first`() {
        // 上一首 185 秒就唱完了；下一首第 2 秒开口：2000 - 300 = 1700ms 换页，比交叉点 4 秒早
        val at = AutomixPlanner.switchAtMs(
            equalPower8s,
            8_000,
            outgoingStartMs = 192_000,
            outgoing = LyricSpan(10_000, 185_000),
            incoming = LyricSpan(2_000, 170_000),
        )
        assertEquals(1_700L, at)
    }

    @Test
    fun `the page never turns before the last line of the old song is sung`() {
        // 下一首一上来就唱，但上一首最后一句要到淡化第 5 秒才唱完：等它唱完
        val at = AutomixPlanner.switchAtMs(
            equalPower8s,
            8_000,
            outgoingStartMs = 190_000,
            outgoing = LyricSpan(10_000, 195_000),
            incoming = LyricSpan(500, 170_000),
        )
        assertEquals(5_000L, at)
        // 唱到淡化结束以后：淡完才换（换页点不出淡化范围）
        val late = AutomixPlanner.switchAtMs(
            equalPower8s,
            8_000,
            outgoingStartMs = 190_000,
            outgoing = LyricSpan(10_000, 199_500),
            incoming = LyricSpan(500, 170_000),
        )
        assertEquals(8_000L, late)
    }

    @Test
    fun `a late first line keeps the crossover`() {
        // 下一首前奏很长（第 20 秒才唱）：交叉点更早，用交叉点
        val at = AutomixPlanner.switchAtMs(
            equalPower8s,
            8_000,
            outgoingStartMs = 192_000,
            outgoing = LyricSpan(10_000, 185_000),
            incoming = LyricSpan(20_000, 170_000),
        )
        assertEquals(4_000L, at)
    }

    @Test
    fun `the first line is measured from where the next song starts playing`() {
        // 下一首从 450ms 开始放（裁掉了开头静音），第一句在它自己的 2 秒：淡化第 2000 - 450 - 300 = 1250ms 换页
        val trimmed = equalPower8s.copy(incomingStartMs = 450L)
        val at = AutomixPlanner.switchAtMs(
            trimmed,
            8_000,
            outgoingStartMs = 192_000,
            outgoing = LyricSpan(10_000, 185_000),
            incoming = LyricSpan(2_000, 170_000),
        )
        assertEquals(1_250L, at)
    }

    @Test
    fun `a beat cut turns the page on the cut unless the old song is still singing`() {
        val cut = AutomixPlanner.plan(analysis("a", 120.0), analysis("b", 124.0), 6, durationMs = 200_000)
        assertEquals(AutomixMethod.BeatCut, cut.method)
        assertEquals(0L, AutomixPlanner.switchAtMs(cut, cut.fadeMs, 195_000, null, null))
        assertEquals(
            0L,
            AutomixPlanner.switchAtMs(cut, cut.fadeMs, 195_000, LyricSpan(10_000, 190_000), LyricSpan(5_000, 150_000)),
        )
        // 上一首最后一个字拖到切点后 600ms：等它唱完
        val held = AutomixPlanner.switchAtMs(cut, cut.fadeMs, 195_000, LyricSpan(10_000, 195_600), LyricSpan(5_000, 150_000))
        assertTrue("held=$held", held in 560..600)
    }

    @Test
    fun `a sped up outgoing song finishes its last line sooner in real time`() {
        // 上一首往 1.06 倍变速：进度走 5 秒要的真实时间少于 5 秒，但比一下到位的 5/1.06 多（前 1/4 是渐变）
        val wall = AutomixPlanner.outgoingWallMs(5_000, 8_000, 1.06f)
        assertTrue("wall=$wall", wall < 5_000L)
        assertTrue("wall=$wall", wall > (5_000 / 1.06).toLong())
        // 原速就是原样
        assertEquals(5_000L, AutomixPlanner.outgoingWallMs(5_000, 8_000, 1f))
        // 淡化开始前就唱完了：原样（负数）交给上面取最大值
        assertEquals(-1_000L, AutomixPlanner.outgoingWallMs(-1_000, 8_000, 1.06f))
    }

    @Test
    fun `lyric spans skip credit lines and estimate the end of a line-timed last line`() {
        val lrc = """
            [00:00.00]作词 : 某人
            [00:00.50]作曲 : 某人
            [00:12.00]第一句
            [00:15.00]第二句
            [00:18.00]最后一句
        """.trimIndent()
        val span = LyricPipeline.sungSpan(LyricsParser.parse(lrc).lines)!!
        assertEquals(12_000L, span.firstStartMs)
        // 一句的常见长度是 3 秒（相邻开头间隔的中位数）
        assertEquals(21_000L, span.lastEndMs)
    }

    @Test
    fun `lyric spans use the last word of word-timed lyrics`() {
        val lrc = """
            [00:12.00]<00:12.000>第</00:12.400><00:12.400>一</00:13.000>
            [00:15.00]<00:15.000>最</00:15.400><00:15.400>后</00:16.800>
        """.trimIndent()
        val span = LyricPipeline.sungSpan(LyricsParser.parse(lrc).lines)!!
        assertEquals(12_000L, span.firstStartMs)
        assertEquals(16_800L, span.lastEndMs)
    }

    @Test
    fun `unsynced or empty lyrics have no span`() {
        assertNull(LyricPipeline.sungSpan(LyricsParser.parse("只有文字没有时间").lines))
        assertNull(LyricPipeline.sungSpan(LyricsParser.parse("").lines))
        assertNull(LyricPipeline.sungSpan(LyricsParser.parse("[00:00.00]作词：某人").lines))
    }

    // ---------- 同一张专辑 ----------

    @Test
    fun `the same album in order plays gaplessly`() {
        assertTrue(AutomixPlanner.sameAlbum("netease:42", "netease:42", shuffle = false))
        // 随机播放时不算整张专辑连着听
        assertFalse(AutomixPlanner.sameAlbum("netease:42", "netease:42", shuffle = true))
        assertFalse(AutomixPlanner.sameAlbum("netease:42", "netease:43", shuffle = false))
        // 没有专辑 id 不能当成同一张
        assertFalse(AutomixPlanner.sameAlbum("", "", shuffle = false))
        // 不同平台同号的专辑不是同一张
        assertFalse(AutomixPlanner.sameAlbum("netease:42", "qq:42", shuffle = false))
    }

    @Test
    fun `with track numbers only the very next track plays gaplessly`() {
        // Apple Music 的判据：同一张专辑、同一张碟、轨号正好加一
        assertTrue(AutomixPlanner.sameAlbum("qq:7", "qq:7", false, AlbumSlot(1, 3), AlbumSlot(1, 4)))
        // 同一张专辑跳着放照样过渡
        assertFalse(AutomixPlanner.sameAlbum("qq:7", "qq:7", false, AlbumSlot(1, 3), AlbumSlot(1, 5)))
        assertFalse(AutomixPlanner.sameAlbum("qq:7", "qq:7", false, AlbumSlot(1, 4), AlbumSlot(1, 3)))
        // 换碟不算（第一张的末轨到第二张的首轨也不算）
        assertFalse(AutomixPlanner.sameAlbum("qq:7", "qq:7", false, AlbumSlot(1, 3), AlbumSlot(2, 4)))
        // 不知道碟号按第一张
        assertTrue(AutomixPlanner.sameAlbum("qq:7", "qq:7", false, AlbumSlot(0, 3), AlbumSlot(1, 4)))
        // 随机播放碰巧排到下一轨也无缝接
        assertTrue(AutomixPlanner.sameAlbum("qq:7", "qq:7", true, AlbumSlot(1, 3), AlbumSlot(1, 4)))
        // 只有一首知道轨号：退回只看专辑和随机
        assertTrue(AutomixPlanner.sameAlbum("qq:7", "qq:7", false, AlbumSlot(1, 3), AlbumSlot.Unknown))
        assertFalse(AutomixPlanner.sameAlbum("qq:7", "qq:7", true, AlbumSlot(1, 3), AlbumSlot.Unknown))
        // 专辑不同时轨号连着也不算
        assertFalse(AutomixPlanner.sameAlbum("qq:7", "qq:8", false, AlbumSlot(1, 3), AlbumSlot(1, 4)))
    }

    @Test
    fun `a gapless handover is reported without a duration`() {
        val report = CrossfadeReport("潮汐来信", "雾港", SAME_ALBUM_METHOD, 0f)
        assertEquals("同一张专辑，直接接", report.headline)
        assertEquals("《潮汐来信》→《雾港》", report.detail)
    }

    // ---------- 变速渐变 ----------

    @Test
    fun `tempo ramps to the target in 8 steps across the first quarter`() {
        val target = 1.048f
        assertEquals(1f, AutomixPlanner.tempoAt(0f, target), 0f)
        val samples = (0..1_000).map { AutomixPlanner.tempoAt(it / 1_000f, target) }
        val distinct = samples.distinct()
        // 原速 + 8 级
        assertEquals(9, distinct.size)
        // 只往目标走，不回头
        samples.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        // 每级一样大
        distinct.zipWithNext().forEach { (a, b) -> assertEquals((target - 1f) / 8f, b - a, 1e-5f) }
        // 1/4 处到位，之后保持
        assertEquals(target, AutomixPlanner.tempoAt(0.25f, target), 1e-6f)
        assertEquals(target, AutomixPlanner.tempoAt(0.6f, target), 1e-6f)
        assertEquals(target, AutomixPlanner.tempoAt(1f, target), 1e-6f)
        // 第一级在 1/32 处，之前还是原速
        assertEquals(1f, AutomixPlanner.tempoAt(0.03f, target), 0f)
        assertEquals(1f + (target - 1f) / 8f, AutomixPlanner.tempoAt(0.032f, target), 1e-6f)
        // 往慢了变也一样分 8 级
        assertEquals(9, (0..1_000).map { AutomixPlanner.tempoAt(it / 1_000f, 0.95f) }.distinct().size)
        // 不变速就一直是原速
        assertTrue((0..100).all { AutomixPlanner.tempoAt(it / 100f, 1f) == 1f })
    }

    // ---------- 播放队列那一行 ----------

    @Test
    fun `the queue row describes the mode until the next song is ready`() {
        val off = CrossfadeStatus.queueLine(false, CrossfadeMode.Automix, 6, null)
        assertFalse(off.on)
        assertEquals("切歌过渡", off.title)
        assertEquals("整首播完再直接切", off.summary)
        // 方式是不过渡时开关也显示关着（打开会改成智能过渡）
        assertFalse(CrossfadeStatus.queueLine(true, CrossfadeMode.None, 6, null).on)

        val fade = CrossfadeStatus.queueLine(true, CrossfadeMode.Fade, 6, null)
        assertTrue(fade.on)
        assertEquals("交叉淡化", fade.title)
        assertEquals("交叉淡化 · 6 秒", fade.summary)

        val smart = CrossfadeStatus.queueLine(true, CrossfadeMode.Automix, 6, null)
        assertEquals("智能过渡", smart.title)
        assertEquals("智能过渡 · 按两首歌挑接法", smart.summary)

        val ready = CrossfadeStatus.queueLine(
            true,
            CrossfadeMode.Automix,
            6,
            CrossfadeUpcoming("a", "b", "切拍 · ${transitionSecondsLabel(1_200)}"),
        )
        assertEquals("切拍 · 1.2 秒", ready.summary)
        assertEquals("6 秒", transitionSecondsLabel(6_000))
    }

    @Test
    fun `a transition reports its progress from the start time`() {
        val transition = CrossfadeTransition("晴天", "稻香", "低频交接", 6_000, startedAtMs = 1_000)
        assertEquals(0f, transition.progressAt(1_000), 0.001f)
        assertEquals(0.5f, transition.progressAt(4_000), 0.001f)
        assertEquals(1f, transition.progressAt(9_000), 0.001f)
    }

    @Test
    fun `a report explains the method or the fallback`() {
        val mixed = CrossfadeReport("晴天", "稻香", "交叉淡化", 5.6f)
        assertEquals("交叉淡化 · 5.6 秒", mixed.headline)
        assertEquals("《晴天》→《稻香》", mixed.detail)

        val fallback = CrossfadeReport("晴天", "稻香", "直接切", 0f, "下一首是加密整曲")
        assertEquals("已退回直接切", fallback.headline)
        assertEquals("《晴天》→《稻香》 · 下一首是加密整曲", fallback.detail)
    }
}
