package com.mytv.mymusic.ui.player.stage

import com.mytv.mymusic.data.DefaultTempoRules
import com.mytv.mymusic.data.LyricAutoSwitch
import com.mytv.mymusic.data.LyricStagePrefs
import com.mytv.mymusic.data.StagePreset
import com.mytv.mymusic.data.decodePresets
import com.mytv.mymusic.ui.player.stage.modes.JumpVisualizer
import com.mytv.mymusic.ui.player.stage.modes.RushVisualizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StagePresetTest {

    private val salt = StagePreset("rush#salt", "rush", "海盐", mapOf("colors" to "custom", "customBackground" to "#2C5F5A"))
    private val night = StagePreset("rush#ngt1", "rush", "Rush 1", mapOf("align" to "center"))
    private val jump = StagePreset("jump#j0b1", "jump", "高跳", mapOf("arc" to "2.0"))
    private val prefs = LyricStagePrefs(
        presets = listOf(salt, night, jump),
        pluginSettings = mapOf("rush" to mapOf("size" to "1.2")),
    )

    @Test
    fun presetIdsResolveToPresetVisualizers() {
        val mode = prefs.modeOf("rush#salt")
        assertTrue(mode is PresetVisualizer)
        mode as PresetVisualizer
        assertEquals("rush#salt", mode.id)
        assertEquals("海盐", mode.name)
        assertEquals("Rush · 海盐", mode.label)
        // 名字已经以模式名开头时不重复
        assertEquals("Rush 1", prefs.modeOf("rush#ngt1").label)
        assertSame(RushVisualizer, mode.base)
        // 成员都照基础模式
        assertEquals(RushVisualizer.origin, mode.origin)
        assertEquals(RushVisualizer.order, mode.order)
        assertSame(RushVisualizer.settings, mode.settings)
        assertEquals(RushVisualizer.hiddenSettings, mode.hiddenSettings)
        assertEquals(RushVisualizer.hostBackground, mode.hostBackground)
        assertEquals(RushVisualizer.hostSubtitles, mode.hostSubtitles)
        assertEquals(RushVisualizer.wantsAudio, mode.wantsAudio)
        assertEquals(RushVisualizer.interactive, mode.interactive)
        assertEquals(RushVisualizer.drawsWithoutLyrics, mode.drawsWithoutLyrics)
        assertEquals(RushVisualizer.minTextHeight, mode.minTextHeight)
        val rhythm = SettingValues(mode.settings, mapOf("background" to "rhythm"))
        assertEquals(RushVisualizer.wantsAudio(rhythm), mode.wantsAudio(rhythm))
        assertTrue(mode.wantsAudio(rhythm))
        val jumpMode = prefs.modeOf("jump#j0b1")
        assertEquals(JumpVisualizer.minTextHeight, jumpMode.minTextHeight)
        // 相等只看 id：改设置、改名都不让舞台重新淡入
        assertEquals(mode, PresetVisualizer(RushVisualizer, "rush#salt", "别的名字"))
        assertEquals(mode.hashCode(), PresetVisualizer(RushVisualizer, "rush#salt", "x").hashCode())
        assertNotEquals(mode, RushVisualizer)
    }

    @Test
    fun deletedPresetsFallBackToTheirBase() {
        assertSame(RushVisualizer, prefs.modeOf("rush#gone"))
        assertSame(ClassicVisualizer, prefs.modeOf("bogus#x"))
        assertSame(ClassicVisualizer, prefs.modeOf("bogus"))
        assertSame(RushVisualizer, prefs.modeOf("rush"))
        assertTrue(prefs.isKnown("rush#salt"))
        assertFalse(prefs.isKnown("rush#gone"))
        assertFalse(prefs.isKnown("bogus#x"))
        // 经典不能有预设
        val classic = LyricStagePrefs(presets = listOf(StagePreset("classic#abcd", "classic", "旧")))
        assertSame(ClassicVisualizer, classic.modeOf("classic#abcd"))
        assertFalse(classic.isKnown("classic#abcd"))
    }

    @Test
    fun settingsComeFromThePresetOrTheBase() {
        assertEquals(salt.settings, prefs.settingsFor("rush#salt"))
        assertEquals(mapOf("size" to "1.2"), prefs.settingsFor("rush"))
        // 删掉的预设按基础模式的
        assertEquals(mapOf("size" to "1.2"), prefs.settingsFor("rush#gone"))
        // 写预设只改它自己，清理照基础模式的设置表
        val written = prefs.withSettings("rush#salt", mapOf("align" to "end", "bogus" to "1", "size" to "1.0"))
        assertEquals(mapOf("align" to "end"), written.settingsFor("rush#salt"))
        assertEquals(mapOf("size" to "1.2"), written.settingsFor("rush"))
        assertEquals(prefs.pluginSettings, written.pluginSettings)
        // 草稿里只拷预设的设置
        val saved = prefs.copy(presets = listOf(salt.copy(name = "已改名"), night, jump))
        val merged = saved.withDraft(written)
        assertEquals(mapOf("align" to "end"), merged.settingsFor("rush#salt"))
        assertEquals("已改名", merged.presets.first().name)
        assertTrue(isDirty(prefs, draftOf(prefs).withSettings("rush#salt", emptyMap())))
        assertFalse(isDirty(prefs, draftOf(prefs)))
        assertEquals(listOf("「Rush · 海盐」的设置：改了 2 项"), animationDiff(prefs, draftOf(prefs).withSettings("rush#salt", mapOf("align" to "end"))))
    }

    @Test
    fun cardsPutPresetsAfterTheirBase() {
        val ids = prefs.cards().map { it.id }
        assertEquals(ids.indexOf("rush") + 1, ids.indexOf("rush#salt"))
        assertEquals(ids.indexOf("rush") + 2, ids.indexOf("rush#ngt1"))
        assertEquals(ids.indexOf("jump") + 1, ids.indexOf("jump#j0b1"))
        assertEquals(LyricVisualizers.all.size + 3, ids.size)
        // 预设能进随机池，但默认池只有内置模式
        assertFalse("rush#salt" in defaultPool())
        val pooled = prefs.copy(randomPool = setOf("rush#salt", "aurora", "rush#gone"))
        assertEquals(listOf("rush#salt", "aurora"), pooled.effectivePool())
    }

    @Test
    fun deletingAPresetReplacesEveryUse() {
        val used = prefs.copy(
            visualizer = "rush#salt",
            auto = LyricAutoSwitch.Random,
            tempoRules = DefaultTempoRules.mapIndexed { i, rule -> if (i == 1 || i == 3) rule.copy(mode = "rush#salt") else rule },
            pins = mapOf("song-a" to "rush#salt", "song-b" to "aurora"),
            randomPool = setOf("rush#salt", "rush", "aurora"),
        )
        val (after, count) = used.withoutPreset("rush#salt")
        assertEquals(5, count)
        assertEquals("rush", after.visualizer)
        assertEquals(listOf("rush", "rush"), listOf(after.tempoRules[1].mode, after.tempoRules[3].mode))
        assertEquals(DefaultTempoRules[0].mode, after.tempoRules[0].mode)
        assertEquals(mapOf("song-a" to "rush", "song-b" to "aurora"), after.pins)
        assertEquals(setOf("rush", "aurora"), after.randomPool)
        assertEquals(listOf("rush#ngt1", "jump#j0b1"), after.presets.map { it.id })
        assertEquals(0, prefs.withoutPreset("rush#salt").second)

        // 撤销：放回原位，只改回还停在基础模式上的
        val changedSince = after.copy(pins = after.pins + ("song-a" to "vinyl"))
        val restored = changedSince.withPresetRestored(salt, used)
        assertEquals(listOf("rush#salt", "rush#ngt1", "jump#j0b1"), restored.presets.map { it.id })
        assertEquals("rush#salt", restored.visualizer)
        assertEquals("rush#salt", restored.tempoRules[1].mode)
        assertEquals("rush#salt", restored.tempoRules[3].mode)
        assertEquals("vinyl", restored.pins["song-a"])
        // 池里原来就有 rush，两个都留着
        assertEquals(setOf("rush#salt", "rush", "aurora"), restored.randomPool)
        // 原来池里没有 rush 的：rush 是替换进来的，撤销时拿掉
        val onlyPreset = prefs.copy(randomPool = setOf("rush#salt", "aurora"))
        val back = onlyPreset.withoutPreset("rush#salt").first.withPresetRestored(salt, onlyPreset)
        assertEquals(setOf("rush#salt", "aurora"), back.randomPool)
        // 已经加回来了就不重复加
        assertSame(restored, restored.withPresetRestored(salt, used))
    }

    @Test
    fun newIdsAreShortAndUnique() {
        val existing = mutableSetOf("rush#salt")
        val random = Random(7)
        repeat(200) {
            val id = newPresetId("rush", existing, random)
            assertTrue(id, Regex("^rush#[a-z0-9]{4}$").matches(id))
            assertTrue(existing.add(id))
        }
    }

    @Test
    fun defaultNamesSkipUsedNumbers() {
        assertEquals("Rush 1", defaultPresetName("Rush", emptyList()))
        assertEquals("Rush 2", defaultPresetName("Rush", listOf(night)))
        assertEquals("Rush 1", defaultPresetName("Rush", listOf(salt)))
        val many = listOf(night, night.copy(id = "rush#n2", name = "Rush 2"), night.copy(id = "rush#n4", name = "Rush 4"))
        assertEquals("Rush 3", defaultPresetName("Rush", many))
        assertEquals("Rush · 海盐", presetLabel("Rush", "海盐"))
        assertEquals("Rush 3", presetLabel("Rush", "Rush 3"))
    }

    @Test
    fun decodeDropsBrokenPresets() {
        val raw = """[
            {"id":"rush#salt","base":"rush","name":"海盐","settings":{"colors":"custom"}},
            {"id":"rush#salt","base":"rush","name":"重复"},
            {"id":"rushsalt","base":"rush","name":"没有井号"},
            {"id":"jump#abcd","base":"rush","name":"对不上"},
            {"id":"classic#abcd","base":"classic","name":"经典"},
            {"id":"rush#nnnn","base":"rush","name":"  "},
            {"id":"vinyl#v1v1","base":"vinyl","name":"唱片","extra":1}
        ]"""
        val presets = decodePresets(raw)
        assertEquals(listOf("rush#salt", "vinyl#v1v1"), presets.map { it.id })
        assertEquals("海盐", presets[0].name)
        assertEquals(mapOf("colors" to "custom"), presets[0].settings)
        assertEquals(emptyList<StagePreset>(), decodePresets(null))
        assertEquals(emptyList<StagePreset>(), decodePresets("not json"))
    }

    @Test
    fun rememberedDeletedPresetIsSkippedWhenDrawing() {
        repeat(20) { seed ->
            val pick = chooseRandomMode("rush#gone", listOf("rush#salt", "aurora"), null, prefs::isKnown, Random(seed))
            assertFalse(pick.remembered)
            assertTrue(pick.id in listOf("rush#salt", "aurora"))
        }
        assertEquals(RandomPick("rush#salt", remembered = true), chooseRandomMode("rush#salt", listOf("aurora"), null, prefs::isKnown, Random(0)))
    }
}
