package com.mytv.mymusic.ui.player.stage

import com.mytv.mymusic.data.LyricStagePrefs
import com.mytv.mymusic.data.TempoRule
import kotlin.random.Random

/**
 * 动画面板、舞台和换法共用的“卡片 id → 模式 / 设置”查法。面板里出现的、能被固定、进随机池、配给按速度某一档、
 * 给某首歌固定的，都是 [cards] 里的卡片；认不出的 id（以后删掉的模式）一律按经典。
 */

/** 卡片 id 的基础模式部分（`rush#k3f9` → `rush`）；不带 `#` 的就是它自己。 */
internal fun baseIdOf(id: String): String = id.substringBefore('#')

/** 卡片 id 对应的模式；认不出的按经典。 */
internal fun LyricStagePrefs.modeOf(id: String): LyricVisualizer = LyricVisualizers.byId(id)

/** 面板里卡片的顺序：内置模式按 [LyricVisualizer.order]。 */
internal fun LyricStagePrefs.cards(): List<LyricVisualizer> = LyricVisualizers.all

/** 这个 id 是不是一张现有的卡片。 */
internal fun LyricStagePrefs.isKnown(id: String): Boolean = LyricVisualizers.all.any { it.id == id }

/** 这张卡片存下来的设置（只有改过的项）。 */
internal fun LyricStagePrefs.settingsFor(id: String): Map<String, String> = pluginSettings[id].orEmpty()

/** 写这张卡片的设置：先按模式的设置表清理，清理完是空的（全是默认值）就删掉这一项。 */
internal fun LyricStagePrefs.withSettings(id: String, raw: Map<String, String>): LyricStagePrefs {
    val clean = sanitizeSettings(modeOf(id).settings, raw)
    return copy(pluginSettings = if (clean.isEmpty()) pluginSettings - id else pluginSettings + (id to clean))
}

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
