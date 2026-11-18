package com.mytv.mymusic.ui.player.stage

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.mytv.mymusic.data.LyricStagePrefs
import com.mytv.mymusic.data.StagePreset
import com.mytv.mymusic.data.TempoRule
import kotlin.random.Random

/**
 * 动画面板、舞台和换法共用的“卡片 id → 模式 / 设置”查法。面板里出现的、能被固定、进随机池、配给按速度某一档、
 * 给某首歌固定的，都是 [cards] 里的卡片；认不出的 id（以后删掉的模式）一律按经典。
 */

/** 卡片 id 的基础模式部分（`rush#k3f9` → `rush`）；不带 `#` 的就是它自己。 */
internal fun baseIdOf(id: String): String = id.substringBefore('#')

/** 现有的预设（id 带 `#`、base 模式还在）。 */
internal fun LyricStagePrefs.presetOf(id: String): StagePreset? =
    if ('#' !in id) null else presets.firstOrNull { it.id == id }?.takeIf { LyricVisualizers.all.any { mode -> mode.id == it.base } }

/**
 * 卡片 id 对应的模式：内置模式；现有的预设是 [PresetVisualizer]；删掉的预设退回它的基础模式；认不出的按经典。
 */
internal fun LyricStagePrefs.modeOf(id: String): LyricVisualizer {
    val base = LyricVisualizers.byId(baseIdOf(id))
    if ('#' !in id || base.id == ClassicVisualizer.id) return base
    val preset = presetOf(id) ?: return base
    return PresetVisualizer(base, id, preset.name)
}

/** 面板里卡片的顺序：内置模式按 [LyricVisualizer.order]，每个模式后面按创建顺序接它的预设。 */
internal fun LyricStagePrefs.cards(): List<LyricVisualizer> = LyricVisualizers.all.flatMap { mode ->
    listOf(mode) + presets.filter { it.base == mode.id && mode.id != ClassicVisualizer.id }.map { PresetVisualizer(mode, it.id, it.name) }
}

/** 这个 id 是不是一张现有的卡片（内置模式或现有的预设）。 */
internal fun LyricStagePrefs.isKnown(id: String): Boolean =
    if ('#' in id) presetOf(id) != null && baseIdOf(id) != ClassicVisualizer.id else LyricVisualizers.all.any { it.id == id }

/** 这张卡片存下来的设置（只有改过的项）：预设是它自己的那套，删掉的预设按基础模式的。 */
internal fun LyricStagePrefs.settingsFor(id: String): Map<String, String> =
    presetOf(id)?.settings ?: pluginSettings[baseIdOf(id)].orEmpty()

/** 写这张卡片的设置：先按模式的设置表清理；内置模式清理完是空的（全是默认值）就删掉这一项，预设写进它自己。 */
internal fun LyricStagePrefs.withSettings(id: String, raw: Map<String, String>): LyricStagePrefs {
    val clean = sanitizeSettings(modeOf(id).settings, raw)
    if (presetOf(id) != null) return copy(presets = presets.map { if (it.id == id) it.copy(settings = clean) else it })
    val key = baseIdOf(id)
    return copy(pluginSettings = if (clean.isEmpty()) pluginSettings - key else pluginSettings + (key to clean))
}

/**
 * 风格预设当成一个模式：样子、行为全照基础模式 [base]，只有 id 和名字是自己的，设置由宿主按 id 另取（[settingsFor]）。
 * 成员一个个显式转发（不用 by 委托，@Composable 成员委托编译器处理不好）。相等只看 id：改预设设置时舞台不会再淡入淡出一次。
 */
internal class PresetVisualizer(val base: LyricVisualizer, override val id: String, val name: String) : LyricVisualizer {
    /** 面板里的名字：“Rush · 海盐”；名字本来就以模式名开头（默认的“Rush 1”）时不再重复。 */
    override val label: String = presetLabel(base.label, name)
    override val origin: ModOrigin? get() = base.origin
    override val order: Int get() = base.order
    override val settings: List<LyricSetting> get() = base.settings
    override val hiddenSettings: Int get() = base.hiddenSettings
    override val hostBackground: Boolean get() = base.hostBackground
    override val hostSubtitles: Boolean get() = base.hostSubtitles
    override val wantsAudio: Boolean get() = base.wantsAudio
    override fun wantsAudio(settings: SettingValues): Boolean = base.wantsAudio(settings)
    override val interactive: Boolean get() = base.interactive
    override val drawsWithoutLyrics: Boolean get() = base.drawsWithoutLyrics
    override val minTextHeight: Dp get() = base.minTextHeight
    override fun subtitleInset(ctx: LyricStageContext, stage: Size, density: Density): Float = base.subtitleInset(ctx, stage, density)

    @Composable
    override fun Background(ctx: LyricStageContext, modifier: Modifier) = base.Background(ctx, modifier)

    @Composable
    override fun Stage(ctx: LyricStageContext, modifier: Modifier) = base.Stage(ctx, modifier)

    override fun equals(other: Any?): Boolean = other is PresetVisualizer && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "PresetVisualizer($id)"
}

/** 预设在面板里的名字，见 [PresetVisualizer.label]。 */
internal fun presetLabel(baseLabel: String, name: String): String =
    if (name.startsWith(baseLabel)) name else "$baseLabel · $name"

/** 新预设的 id：`base#` 加 4 位小写字母或数字，和 [existing] 都不重复。 */
internal fun newPresetId(base: String, existing: Collection<String>, random: Random): String {
    while (true) {
        val id = base + "#" + String(CharArray(PRESET_ID_LENGTH) { PRESET_ID_CHARS[random.nextInt(PRESET_ID_CHARS.length)] })
        if (id !in existing) return id
    }
}

/** 新预设的默认名：“Rush 1”，编号取没用过的最小的。 */
internal fun defaultPresetName(baseLabel: String, presets: List<StagePreset>): String {
    val names = presets.map { it.name }.toSet()
    var n = 1
    while ("$baseLabel $n" in names) n++
    return "$baseLabel $n"
}

/** 预设名最多几个字。 */
internal const val PRESET_NAME_MAX = 20

/**
 * 删掉预设 [id]：用到它的地方（所有歌都用、按速度某一档、某首歌固定、随机池）都改回它的基础模式，随机池去重。
 * 返回删完的设置和改了几处。记忆里存的不动，读的时候认不出就跳过。
 */
internal fun LyricStagePrefs.withoutPreset(id: String): Pair<LyricStagePrefs, Int> {
    val base = baseIdOf(id)
    var count = 0
    val visualizer = if (visualizer == id) base.also { count++ } else visualizer
    val rules = tempoRules.map { rule -> if (rule.mode == id) rule.copy(mode = base).also { count++ } else rule }
    val pins = pins.mapValues { (_, mode) -> if (mode == id) base.also { count++ } else mode }
    val pool = if (id in randomPool) (randomPool - id + base).also { count++ } else randomPool
    return copy(
        presets = presets.filter { it.id != id },
        visualizer = visualizer,
        tempoRules = rules,
        pins = pins,
        randomPool = pool,
    ) to count
}

/**
 * 撤销删除：把 [preset] 放回原来的位置，[before]（删之前的样子）里用到它、现在还停在基础模式上的地方改回它；
 * 删了以后又改过的地方不动。
 */
internal fun LyricStagePrefs.withPresetRestored(preset: StagePreset, before: LyricStagePrefs): LyricStagePrefs {
    val id = preset.id
    val base = preset.base
    if (presets.any { it.id == id }) return this
    val at = before.presets.indexOfFirst { it.id == id }.let { if (it < 0) presets.size else it.coerceAtMost(presets.size) }
    val restored = presets.toMutableList().apply { add(at, preset) }
    val pool = if (id in before.randomPool && base in randomPool) {
        randomPool + id - (if (base in before.randomPool) emptySet() else setOf(base))
    } else {
        randomPool
    }
    return copy(
        presets = restored,
        visualizer = if (before.visualizer == id && visualizer == base) id else visualizer,
        tempoRules = tempoRules.mapIndexed { i, rule ->
            if (before.tempoRules.getOrNull(i)?.mode == id && rule.mode == base) rule.copy(mode = id) else rule
        },
        pins = pins.mapValues { (song, mode) -> if (before.pins[song] == id && mode == base) id else mode },
        randomPool = pool,
    )
}

private const val PRESET_ID_LENGTH = 4
private const val PRESET_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

/** 默认的随机池：除经典外的全部内置模式。 */
internal fun defaultPool(): List<String> = LyricVisualizers.all.map { it.id }.filter { it != ClassicVisualizer.id }

/**
 * 实际用的随机池，按卡片顺序：存的是空的（旧数据、没动过）就是 [defaultPool]；否则去掉认不出的 id，
 * 去完一个不剩也退回默认。
 */
internal fun LyricStagePrefs.effectivePool(): List<String> {
    if (randomPool.isEmpty()) return defaultPool()
    return cards().map { it.id }.filter { it in randomPool }.ifEmpty { defaultPool() }
}

/** 随机换法这一首用哪个：[remembered] 为 true 时是记忆里的，不用再写记忆。 */
internal data class RandomPick(val id: String, val remembered: Boolean)

/**
 * 随机换法挑模式（固定过的歌由调用方先处理）：记忆里有这首、而且认得出，就用记住的，即使它已经不在池里；
 * 记住的已经认不出（删掉的模式）就当没记过。否则从 [pool] 里抽一个，不和上一首 [previous] 重复；池是空的按默认池抽。
 */
internal fun chooseRandomMode(
    remembered: String?,
    pool: List<String>,
    previous: String?,
    known: (String) -> Boolean,
    random: Random,
): RandomPick {
    if (remembered != null && remembered.isNotEmpty() && known(remembered)) return RandomPick(remembered, remembered = true)
    return RandomPick(pickRandomMode(pool.ifEmpty { defaultPool() }, exclude = previous, random = random), remembered = false)
}

/** 速度 [bpm] 落在第几档（[rules] 里的下标），规则和 [modeForTempo] 一样：左闭右开，超过所有上限按最后一档。 */
internal fun tempoTier(rules: List<TempoRule>, bpm: Float): Int {
    if (rules.isEmpty()) return -1
    val rule = rules.sortedBy { it.maxBpm }.firstOrNull { bpm < it.maxBpm } ?: rules.maxBy { it.maxBpm }
    return rules.indexOf(rule)
}

/**
 * 现在就换模式吗：固定、这首歌固定用的（[immediate]）和刚点了“保存并应用”的（[applyPending]）立刻换；
 * 其余（随机、按速度自己换的）要等歌词到了（不在 [lyricsWaiting]）、而且此刻不打断演唱（[canSwitch]）。
 */
internal fun shouldSwitchNow(immediate: Boolean, applyPending: Boolean, lyricsWaiting: Boolean, canSwitch: Boolean): Boolean =
    immediate || applyPending || (!lyricsWaiting && canSwitch)
