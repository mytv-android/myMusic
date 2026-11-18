package com.mytv.mymusic.ui.player.stage

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LyricSettingsTest {

    private fun depth(default: Float) = LyricSetting.Slider(
        "blur", "景深模糊", 0f, 1.5f, default,
        ticks = listOf(0f, 0.5f, 1f, 1.5f), tickLabels = listOf("关", "弱", "中", "强"),
        legacyToggle = 0f to 1f,
    )

    private val palette = LyricSetting.Choice(
        "colors", "配色",
        listOf("vibrant" to "鲜艳", "muted" to "柔和", "player" to "播放页", "custom" to "自定义"),
        "vibrant",
        shuffleExclude = setOf("custom"),
    )
    private val background = LyricSetting.Color(
        "customBackground", "背景", "#2C5F5A",
        seed = { it.coverVibrant }, visibleWhen = SettingCondition("colors", setOf("custom")),
    )
    private val text = LyricSetting.Color(
        "customText", "文字", "#ffe9b8",
        seed = { it.onCoverVibrant }, visibleWhen = SettingCondition("colors", setOf("custom")),
    )
    private val align = LyricSetting.Choice("align", "对齐", listOf("start" to "左", "center" to "中", "end" to "右"), "start")
    private val size = LyricSetting.Number("size", "字号", 0.8f, 1.4f, 0.1f, 1f)
    private val bold = LyricSetting.Toggle("expressive", "逐字粗细", false)
    private val schema = listOf(palette, background, text, align, size, depth(1f), bold)

    // ---- 滑块 ----

    @Test
    fun sliderClampsAndRoundsToHundredths() {
        val slider = depth(1f)
        assertEquals(0.37f, slider.clamp(0.3712f), 0f)
        assertEquals(1.5f, slider.clamp(2.4f), 0f)
        assertEquals(0f, slider.clamp(-1f), 0f)
        assertEquals(1f, slider.clamp(Float.NaN), 0f)
        assertEquals(0.37f, SettingValues(listOf(slider), mapOf("blur" to "0.3712")).slider("blur"), 0f)
        assertEquals(1.5f, SettingValues(listOf(slider), mapOf("blur" to "9")).slider("blur"), 0f)
        assertEquals(1f, SettingValues(listOf(slider), mapOf("blur" to "NaN")).slider("blur"), 0f)
        assertEquals(1f, SettingValues(listOf(slider), mapOf("blur" to "much")).slider("blur"), 0f)
    }

    @Test
    fun sliderSnapsOnlyNearATick() {
        val slider = depth(1f)
        assertEquals(0.5f, slider.snap(0.43f), 0f)
        assertEquals(0.5f, slider.snap(0.57f), 0f)
        assertEquals(0.4f, slider.snap(0.4f), 0f)
        assertEquals(1.5f, slider.snap(1.45f), 0f)
        assertEquals(0.75f, slider.snap(0.75f), 0f)
        assertEquals(0.3f, LyricSetting.Slider("k", "k", 0f, 1f, 0f).snap(0.3f), 0f)
    }

    @Test
    fun oldToggleValuesMigrateToTheSliderEnds() {
        for (deviceDefault in listOf(0f, 1f)) {
            val slider = depth(deviceDefault)
            val label = "default=$deviceDefault"
            assertEquals(label, 0f, SettingValues(listOf(slider), mapOf("blur" to "false")).slider("blur"), 0f)
            assertEquals(label, 1f, SettingValues(listOf(slider), mapOf("blur" to "true")).slider("blur"), 0f)
            assertEquals(label, deviceDefault, SettingValues(listOf(slider), emptyMap()).slider("blur"), 0f)
        }
        // 写的时候换成数；等于这台设备的默认值就不存
        assertEquals(mapOf("blur" to "0.0"), sanitizeSettings(listOf(depth(1f)), mapOf("blur" to "false")))
        assertEquals(emptyMap<String, String>(), sanitizeSettings(listOf(depth(1f)), mapOf("blur" to "true")))
        assertEquals(mapOf("blur" to "1.0"), sanitizeSettings(listOf(depth(0f)), mapOf("blur" to "true")))
        assertEquals(emptyMap<String, String>(), sanitizeSettings(listOf(depth(0f)), mapOf("blur" to "false")))
        assertEquals(mapOf("blur" to "1.25"), sanitizeSettings(listOf(depth(1f)), mapOf("blur" to "1.2512")))
        // 没声明旧开关的滑块不认布尔值
        assertNull(LyricSetting.Slider("k", "k", 0f, 1f, 0.5f).parse("true"))
    }

    // ---- 颜色 ----

    @Test
    fun colorsNormalizeToUpperCaseHex() {
        assertEquals("#ABCDEF", parseHexColor("#abcdef")!!.toHex())
        assertEquals("#ABCDEF", parseHexColor(" abcdef ")!!.toHex())
        assertEquals(Color(0xFF2C5F5A), parseHexColor("#2C5F5A"))
        for (bad in listOf("", "#abc", "#abcdefff", "#ggggggg", "#12345g", "red")) assertNull(bad, parseHexColor(bad))
        assertEquals("#FFE9B8", text.defaultHex)

        assertEquals(mapOf("customBackground" to "#ABCDEF"), sanitizeSettings(schema, mapOf("customBackground" to "#abcdef")))
        // 非法的丢掉、等于默认值的丢掉（不管大小写）
        assertEquals(emptyMap<String, String>(), sanitizeSettings(schema, mapOf("customBackground" to "teal", "customText" to "#FFE9B8")))
        assertEquals(emptyMap<String, String>(), sanitizeSettings(schema, mapOf("customBackground" to "#2c5f5a")))

        val values = SettingValues(schema, mapOf("customBackground" to "nope", "customText" to "#102030"))
        assertEquals(Color(0xFF2C5F5A), values.color("customBackground"))
        assertEquals(Color(0xFF102030), values.color("customText"))
    }

    // ---- 显示条件 ----

    @Test
    fun visibleWhenFollowsTheCurrentChoice() {
        assertFalse(SettingValues(schema, emptyMap()).visible(background))
        assertTrue(SettingValues(schema, mapOf("colors" to "custom")).visible(background))
        // 不认识的值按默认值算
        assertFalse(SettingValues(schema, mapOf("colors" to "bogus")).visible(text))
        assertTrue(SettingValues(schema, emptyMap()).visible(align))
        // 条件指向不存在的项：不显示
        val orphan = LyricSetting.Color("x", "x", "#000000", visibleWhen = SettingCondition("missing", setOf("a")))
        assertFalse(SettingValues(listOf(orphan), emptyMap()).visible(orphan))
        // 看不见的值照样留着
        assertEquals("#102030", sanitizeSettings(schema, mapOf("customText" to "#102030"))["customText"])
    }

    @Test
    fun colorsSeedOnlyWhenTheyFirstShowUp() {
        val stage = sampleColors()
        val seeded = seedNewlyVisibleColors(schema, emptyMap(), mapOf("colors" to "custom"), stage)
        assertEquals(stage.coverVibrant.toHex(), seeded["customBackground"])
        assertEquals(stage.onCoverVibrant.toHex(), seeded["customText"])
        // 已经有值的不覆盖
        val kept = seedNewlyVisibleColors(schema, mapOf("customText" to "#010203"), mapOf("colors" to "custom", "customText" to "#010203"), stage)
        assertEquals("#010203", kept["customText"])
        assertEquals(stage.coverVibrant.toHex(), kept["customBackground"])
        // 本来就看得见、或者这一下没变得看得见：不动
        assertEquals(mapOf("colors" to "custom"), seedNewlyVisibleColors(schema, mapOf("colors" to "custom"), mapOf("colors" to "custom"), stage))
        assertEquals(mapOf("colors" to "muted"), seedNewlyVisibleColors(schema, emptyMap(), mapOf("colors" to "muted"), stage))
    }

    @Test
    fun colorsAfterTheirChoiceShareItsRow() {
        val visible = SettingValues(schema, mapOf("colors" to "custom")).let { v -> schema.filter(v::visible) }
        val rows = settingRows(visible)
        assertEquals(listOf("colors", "align", "size", "blur", "expressive"), rows.map { it.head?.key })
        assertEquals(listOf("customBackground", "customText"), rows[0].colors.map { it.key })
        assertTrue(rows.drop(1).all { it.colors.isEmpty() })
        // 没选自定义：色块不显示
        assertEquals(5, settingRows(SettingValues(schema, emptyMap()).let { v -> schema.filter(v::visible) }).size)
        // 不挂在选项后面的颜色单独一行
        val loose = LyricSetting.Color("tint", "色调", "#FFFFFF")
        val alone = settingRows(listOf(size, loose, background))
        assertEquals(listOf("size", null), alone.map { it.head?.key })
        assertEquals(listOf("tint", "customBackground"), alone[1].colors.map { it.key })
    }

    // ---- 随机搭配 ----

    @Test
    fun shuffleOnlyTouchesShuffleableChoices() {
        val raw = mapOf("size" to "1.3", "blur" to "0.5", "customBackground" to "#123456", "expressive" to "true", "colors" to "custom")
        repeat(40) { seed ->
            val out = shuffleSettings(schema, raw, Random(seed))
            assertEquals("1.3", out["size"])
            assertEquals("0.5", out["blur"])
            assertEquals("#123456", out["customBackground"])
            assertEquals("true", out["expressive"])
            // 不抽“自定义”
            assertNotEquals("custom", out["colors"])
            assertTrue(out["align"] in setOf("start", "center", "end"))
        }
    }

    @Test
    fun shuffleAlwaysChangesSomething() {
        val before = SettingValues(schema, emptyMap())
        repeat(60) { seed ->
            val out = SettingValues(schema, shuffleSettings(schema, emptyMap(), Random(seed)))
            assertTrue(before.choice("colors") != out.choice("colors") || before.choice("align") != out.choice("align"))
        }
        // 没有能抽的项：原样返回，也不显示骰子
        val fixed = listOf(size, LyricSetting.Choice("one", "单选", listOf("a" to "A", "b" to "B"), "a", shuffle = false))
        assertEquals(mapOf("size" to "1.2"), shuffleSettings(fixed, mapOf("size" to "1.2"), Random(0)))
        assertFalse(canShuffleSettings(fixed))
        assertTrue(canShuffleSettings(schema))
        assertFalse(canShuffleSettings(listOf(LyricSetting.Choice("c", "c", listOf("x" to "X", "custom" to "自定义"), "x", shuffleExclude = setOf("custom")))))
    }

    private fun sampleColors(): StageColors {
        val cover = Color(0xFFE0485A)
        val (vibrant, muted) = coverTones(cover)
        return StageColors(
            text = Color(0xFFE2E2E9),
            dim = Color(0x47E2E2E9),
            accent = Color(0xFFAAC7FF),
            accentAlt = Color(0xFFDCBCE0),
            surface = Color(0xFF111318),
            cover = List(4) { cover },
            coverVibrant = vibrant,
            onCoverVibrant = readableOn(vibrant),
            coverMuted = muted,
            onCoverMuted = readableOn(muted),
        )
    }
}
