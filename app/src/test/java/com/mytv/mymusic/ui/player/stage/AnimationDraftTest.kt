package com.mytv.mymusic.ui.player.stage

import com.mytv.mymusic.data.DefaultTempoRules
import com.mytv.mymusic.data.LyricAutoSwitch
import com.mytv.mymusic.data.LyricStagePrefs
import com.mytv.mymusic.data.LyricSubline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationDraftTest {

    private val saved = LyricStagePrefs(
        visualizer = "rush",
        auto = LyricAutoSwitch.Fixed,
        pins = mapOf("song-a" to "vinyl"),
        hintSeen = true,
        subline = LyricSubline.Romanization,
    )

    // ---- 草稿字段 ----

    @Test
    fun draftMaterializesTheDefaultPool() {
        assertEquals(defaultPool().toSet(), draftOf(saved).randomPool)
        val custom = saved.copy(randomPool = setOf("vinyl", "rush"))
        assertEquals(setOf("rush", "vinyl"), draftOf(custom).randomPool)
    }

    @Test
    fun withDraftOnlyCopiesDraftFields() {
        val draft = draftOf(saved).copy(
            visualizer = "vinyl",
            auto = LyricAutoSwitch.Tempo,
            spectrum = true,
            tempoRules = DefaultTempoRules.mapIndexed { i, r -> if (i == 0) r.copy(mode = "rush") else r },
            pluginSettings = mapOf("rush" to mapOf("align" to "center")),
            // 下面这些不是草稿字段，不该被写回
            pins = emptyMap(),
            hintSeen = false,
            subline = LyricSubline.Translation,
        )
        val result = saved.withDraft(draft)
        assertEquals("vinyl", result.visualizer)
        assertEquals(LyricAutoSwitch.Tempo, result.auto)
        assertTrue(result.spectrum)
        assertEquals("rush", result.tempoRules[0].mode)
        assertEquals(mapOf("rush" to mapOf("align" to "center")), result.pluginSettings)
        assertEquals(saved.pins, result.pins)
        assertTrue(result.hintSeen)
        assertEquals(LyricSubline.Romanization, result.subline)
    }

    @Test
    fun poolIsNormalizedOnSave() {
        // 等于默认池：写回空集
        val random = draftOf(saved).copy(auto = LyricAutoSwitch.Random)
        assertEquals(emptySet<String>(), saved.withDraft(random).randomPool)
        // 自己挑的几个原样写
        val picked = random.copy(randomPool = setOf("rush", "vinyl"))
        assertEquals(setOf("rush", "vinyl"), saved.withDraft(picked).randomPool)
        // 不在随机换法、池又被清空：保留原来的池
        val old = saved.copy(randomPool = setOf("aurora"))
        val cleared = draftOf(old).copy(randomPool = emptySet(), auto = LyricAutoSwitch.Fixed)
        assertEquals(setOf("aurora"), old.withDraft(cleared).randomPool)
    }

    @Test
    fun dirtyIgnoresNonDraftFields() {
        val draft = draftOf(saved)
        assertFalse(isDirty(saved, draft))
        // 面板开着时写了 pins、提示：不算改动
        val later = saved.copy(pins = saved.pins + ("song-b" to "rush"), hintSeen = false)
        assertFalse(isDirty(later, draft))
        assertTrue(isDirty(saved, draft.copy(spectrum = true)))
        // 保存后（存成规范形式）就不脏了
        val changed = draft.copy(auto = LyricAutoSwitch.Random, randomPool = setOf("rush", "vinyl"))
        assertTrue(isDirty(saved, changed))
        assertFalse(isDirty(saved.withDraft(changed), changed))
        // 选回默认池：保存后是空集，草稿是物化的默认池，也不脏
        val back = changed.copy(randomPool = defaultPool().toSet())
        assertFalse(isDirty(saved.withDraft(back), back))
    }

    @Test
    fun clearedRandomPoolIsDirtyButCannotSave() {
        val draft = draftOf(saved).copy(auto = LyricAutoSwitch.Random, randomPool = emptySet())
        assertTrue(isDirty(saved, draft))
        assertFalse(canSave(draft))
        assertTrue(canSave(draft.copy(randomPool = setOf("rush"))))
        assertTrue(canSave(draftOf(saved).copy(randomPool = emptySet())))
    }

    // ---- 改动摘要 ----

    @Test
    fun diffLinesFollowTheDraftWording() {
        val draft = draftOf(saved).copy(
            auto = LyricAutoSwitch.Random,
            visualizer = "vinyl",
            randomPool = setOf("rush", "vinyl", "aurora", "rings", "miside", "wave"),
            tempoRules = DefaultTempoRules.mapIndexed { i, r -> if (i == 0) r.copy(mode = "rush") else r },
            spectrum = true,
            pluginSettings = mapOf("rush" to mapOf("align" to "center", "size" to "1.2", "expressive" to "true")),
        )
        assertEquals(
            listOf(
                "换法：固定 → 随机",
                "所有歌都用：Rush → 黑胶唱片",
                "随机池：9 个 → 6 个",
                "「慢」：黑胶唱片 → Rush",
                "频谱：关 → 开",
                "「Rush」的设置：改了 3 项",
            ),
            animationDiff(saved, draft),
        )
        assertEquals(emptyList<String>(), animationDiff(saved, draftOf(saved)))
    }

    @Test
    fun diffCountsResetSettingsAndClearedPool() {
        val withSettings = saved.copy(pluginSettings = mapOf("vinyl" to mapOf("bogus" to "1")))
        // 存着的全是认不出的键：清理后没有差别
        assertEquals(emptyList<String>(), animationDiff(withSettings, draftOf(withSettings).copy(pluginSettings = emptyMap())))
        val rush = saved.copy(pluginSettings = mapOf("rush" to mapOf("align" to "end")))
        assertEquals(listOf("「Rush」的设置：改了 1 项"), animationDiff(rush, draftOf(rush).copy(pluginSettings = emptyMap())))
        val cleared = draftOf(saved).copy(auto = LyricAutoSwitch.Random, randomPool = emptySet())
        assertEquals(listOf("换法：固定 → 随机", "随机池：9 个 → 0 个"), animationDiff(saved, cleared))
    }

    // ---- 舞台预览哪个 ----

    @Test
    fun previewPrefersTheEditedTargetThenPinThenAuto() {
        val draft = draftOf(saved).copy(pluginSettings = mapOf("aurora" to mapOf("bogus" to "x")))
        // 编辑目标的设置没真改（认不出的键清理后是空的）：不算，按固定
        assertEquals("rush", previewId(draft, saved, null, null, "rush", null, editTarget = "aurora"))
        val edited = draftOf(saved).withSettings("rush", mapOf("align" to "end"))
        assertEquals("rush", previewId(edited, saved, "vinyl", null, "vinyl", null, editTarget = "rush"))
        // 没改设置：固定的歌用固定的
        assertEquals("vinyl", previewId(draftOf(saved), saved, "vinyl", null, "vinyl", null, editTarget = "rush"))
        // 固定的认不出就不算
        assertEquals("rush", previewId(draftOf(saved), saved, "rush#gone", null, "vinyl", null, editTarget = null))
        // 固定换法：草稿选的
        assertEquals("aurora", previewId(draftOf(saved).copy(visualizer = "aurora"), saved, null, null, "rush", null, null))
    }

    @Test
    fun randomPreviewUsesMemoryThenShownThenFirstInPool() {
        val draft = draftOf(saved).copy(auto = LyricAutoSwitch.Random, randomPool = setOf("wave", "aurora"))
        // 记住的，即使不在池里
        assertEquals("vinyl", previewId(draft, saved, null, "vinyl", "rush", null, null))
        // 记住的认不出：看正在显示的在不在池里
        assertEquals("wave", previewId(draft, saved, null, "rush#gone", "wave", null, null))
        // 正在显示的不在池里：池里第一个（按卡片顺序）
        assertEquals("aurora", previewId(draft, saved, null, null, "rush", null, null))
        // 池空了：正在显示的
        assertEquals("rush", previewId(draft.copy(randomPool = emptySet()), saved, null, null, "rush", null, null))
    }

    @Test
    fun tempoPreviewMapsBpmToTheDraftTiers() {
        val draft = draftOf(saved).copy(
            auto = LyricAutoSwitch.Tempo,
            tempoRules = DefaultTempoRules.mapIndexed { i, r -> if (i == 1) r.copy(mode = "rush") else r },
        )
        assertEquals("rush", previewId(draft, saved, null, null, "vinyl", 100f, null))
        assertEquals("vinyl", previewId(draft, saved, null, null, "vinyl", 60f, null))
        // 没测到速度：正在显示的
        assertEquals("aurora", previewId(draft, saved, null, null, "aurora", null, null))
    }
}
