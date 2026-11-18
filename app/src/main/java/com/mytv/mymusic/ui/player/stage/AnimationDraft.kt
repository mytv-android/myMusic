package com.mytv.mymusic.ui.player.stage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mytv.mymusic.data.LyricAutoSwitch
import com.mytv.mymusic.data.LyricStagePrefs

/**
 * 动画面板的草稿：面板里改换法、卡片、随机池、按速度四档、频谱和各模式的设置都先改草稿，点“保存并应用”才写进设置。
 * 草稿只管这几项（[withDraft] 拷的那些）；这首歌固定用哪个（pins）、✦ 的呼吸提示、忘掉记忆这类点了就生效，不进草稿。
 *
 * 草稿就是一份 [LyricStagePrefs]，随机池物化成显式集合（存的空集＝默认池，见 [effectivePool]），这样“清空”和“默认”分得开。
 */

/** 打开面板时建草稿：随机池换成实际用的那几个。 */
internal fun draftOf(saved: LyricStagePrefs): LyricStagePrefs = saved.copy(randomPool = saved.effectivePool().toSet())

/**
 * 把草稿那几项拷到已保存的设置上，其余（pins、提示、副行……）保持已保存的：
 * 随机池等于默认池时写回空集（保持“空＝默认”）；不在随机换法、池又被清空了，就保留原来的池。
 * 预设只拷设置（按 id），预设的增删改名点了就写，不在草稿里；草稿里没有的预设原样保留。
 */
internal fun LyricStagePrefs.withDraft(draft: LyricStagePrefs): LyricStagePrefs {
    val pool = when {
        draft.randomPool.isEmpty() && draft.auto != LyricAutoSwitch.Random -> randomPool
        draft.randomPool == defaultPool().toSet() -> emptySet()
        else -> draft.randomPool
    }
    return copy(
        visualizer = draft.visualizer,
        auto = draft.auto,
        tempoRules = draft.tempoRules,
        spectrum = draft.spectrum,
        pluginSettings = draft.pluginSettings,
        randomPool = pool,
        presets = presets.map { preset -> draft.presets.firstOrNull { it.id == preset.id }?.let { preset.copy(settings = it.settings) } ?: preset },
    )
}

/**
 * 草稿和已保存的有没有不同。两边都先套到同一份已保存设置上再比，草稿以外的字段（中途写了 pins、提示）不会造成假差异。
 * 随机换法下池被清空了算改动（保存不了，但关面板要问）。
 */
internal fun isDirty(saved: LyricStagePrefs, draft: LyricStagePrefs): Boolean {
    if (draft.auto == LyricAutoSwitch.Random && draft.randomPool.isEmpty()) return true
    return saved.withDraft(draft) != saved.withDraft(draftOf(saved))
}

/** 随机换法至少要选 1 个模式才能保存。 */
internal fun canSave(draft: LyricStagePrefs): Boolean = !(draft.auto == LyricAutoSwitch.Random && draft.randomPool.isEmpty())

/** 保存后的随机池（集合），用来比较和显示个数。 */
private fun poolAfterSave(saved: LyricStagePrefs, draft: LyricStagePrefs): Set<String> = when {
    draft.randomPool.isEmpty() -> if (draft.auto == LyricAutoSwitch.Random) emptySet() else saved.effectivePool().toSet()
    else -> draft.randomPool.filter(draft::isKnown).toSet()
}

/** 这张卡片清理后的设置在草稿和已保存的之间差几项。 */
private fun settingChanges(saved: LyricStagePrefs, draft: LyricStagePrefs, id: String): Int {
    val schema = draft.modeOf(id).settings
    val before = sanitizeSettings(schema, saved.settingsFor(id))
    val after = sanitizeSettings(schema, draft.settingsFor(id))
    return (before.keys + after.keys).count { before[it] != after[it] }
}

/**
 * 改动摘要，一行一处：保存条的“N 处改动”和关面板时的对话框都用它。
 * 写法照稿子：“换法：固定 → 随机”“所有歌都用：Rush → 黑胶唱片”“随机池：9 个 → 6 个”“「慢」：黑胶唱片 → Rush”
 * “频谱：关 → 开”“「Rush」的设置：改了 3 项”。
 */
internal fun animationDiff(saved: LyricStagePrefs, draft: LyricStagePrefs): List<String> {
    val out = ArrayList<String>()
    if (saved.auto != draft.auto) out += "换法：${saved.auto.label} → ${draft.auto.label}"
    if (saved.visualizer != draft.visualizer) {
        out += "所有歌都用：${saved.modeOf(saved.visualizer).label} → ${draft.modeOf(draft.visualizer).label}"
    }
    val poolBefore = saved.effectivePool().toSet()
    val poolAfter = poolAfterSave(saved, draft)
    if (poolBefore != poolAfter) out += "随机池：${poolBefore.size} 个 → ${poolAfter.size} 个"
    draft.tempoRules.forEachIndexed { index, rule ->
        val before = saved.tempoRules.getOrNull(index)?.mode
        if (before != rule.mode) {
            out += "「${TEMPO_NAMES.getOrElse(index) { "第 ${index + 1} 档" }}」：" +
                "${saved.modeOf(before.orEmpty()).label} → ${draft.modeOf(rule.mode).label}"
        }
    }
    if (saved.spectrum != draft.spectrum) out += "频谱：${onOff(saved.spectrum)} → ${onOff(draft.spectrum)}"
    // 预设只比已经存下来的（刚新建、还没写回来的那一下不算改动）
    val ids = (saved.pluginSettings.keys + draft.pluginSettings.keys) + saved.presets.map { it.id }
    val ordered = draft.cards().map { it.id }.filter { it in ids } + ids.filter { id -> draft.cards().none { it.id == id } }
    for (id in ordered) {
        val n = settingChanges(saved, draft, id)
        if (n > 0) out += "「${draft.modeOf(id).label}」的设置：改了 $n 项"
    }
    return out
}

private fun onOff(on: Boolean) = if (on) "开" else "关"

/**
 * 舞台露在面板旁边（平板、横屏、书本姿态、电视）时，草稿画成哪个模式：
 * 1. 正在改设置的那个（[editTarget]，用户在设置区的下拉里明确选的），而且它的设置确实改了；
 * 2. 这首歌固定用的 [pinned]；
 * 3. 固定换法：草稿选的那个；
 * 4. 随机：记住的 [remembered]（认得出就用，即使不在池里）→ 正在显示的 [shown]（在草稿池里才用）→ 草稿池第一个；
 * 5. 按速度：测到过速度 [bpm] 就按草稿的四档换算，没测到就是正在显示的。
 */
internal fun previewId(
    draft: LyricStagePrefs,
    saved: LyricStagePrefs,
    pinned: String?,
    remembered: String?,
    shown: String?,
    bpm: Float?,
    editTarget: String?,
): String {
    if (editTarget != null && settingChanges(saved, draft, editTarget) > 0) return editTarget
    pinned?.takeIf(draft::isKnown)?.let { return it }
    val fallback = shown ?: draft.visualizer
    return when (draft.auto) {
        LyricAutoSwitch.Fixed -> draft.visualizer
        LyricAutoSwitch.Random -> {
            val pool = draft.cards().map { it.id }.filter { it in draft.randomPool }
            remembered?.takeIf { it.isNotEmpty() && draft.isKnown(it) }
                ?: shown?.takeIf { it in pool }
                ?: pool.firstOrNull()
                ?: fallback
        }
        LyricAutoSwitch.Tempo -> bpm?.takeIf { it > 0f }?.let { modeForTempo(draft.tempoRules, it) } ?: fallback
    }
}

/**
 * 面板的草稿状态。宽屏上面板（Dialog）和舞台不在一个窗口，所以由播放页持有，面板和舞台读同一份。
 * [editTarget] 只在用户从设置区的下拉里明确选了才不为空；[asking] 是“保存并应用这些改动？”对话框；
 * [saving] 是点了保存、设置还没写回来的那一下（保存按钮先禁用）。
 */
@Stable
internal class AnimationDraftState(initial: LyricStagePrefs) {
    var draft by mutableStateOf(initial)
    var editTarget by mutableStateOf<String?>(null)
    var asking by mutableStateOf(false)
    var saving by mutableStateOf(false)

    /** 保存时算出的“这首歌保存后用哪个”：舞台换到它之前保存条还按“保存中”处理，舞台继续画它（不闪回旧模式）。 */
    var appliedId: String? = null

    /** 对话框里选了“保存并应用”：设置写回来后再关面板。 */
    var closeAfterSave = false

    fun update(transform: (LyricStagePrefs) -> LyricStagePrefs) {
        draft = transform(draft)
    }

    /** 放弃改动：回到已保存的样子。 */
    fun discard(saved: LyricStagePrefs) {
        draft = draftOf(saved)
        editTarget = null
    }
}

/** 面板开着时有一份草稿（每次打开重新建），关着时为 null。 */
@Composable
internal fun rememberAnimationDraft(open: Boolean, saved: LyricStagePrefs): AnimationDraftState? =
    if (!open) null else remember { AnimationDraftState(draftOf(saved)) }

/** 按速度四档的名字。 */
internal val TEMPO_NAMES = listOf("慢", "中", "快", "很快")
