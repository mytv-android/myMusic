package com.mytv.mymusic.data.transfer

import com.mytv.mymusic.aggregate.TrackNormalizer
import com.mytv.mymusic.data.Song
import kotlin.math.abs

/** 文件里的一首映射完的结果：[song] 为 null 时 [skip] 是原因。[platform] 是本应用的平台 id（本地是 local，认不出的是 other）。 */
data class ImportedTrack(val song: Song?, val skip: SkipReason?, val platform: String)

/**
 * 文件里的一个歌单。[sourceId] 是它在这份文件里的身份（洛雪是歌单 id，myMusic 文件是序号），预览的勾选按它记。
 * [fixed] 只有洛雪的 default / love，以及 myMusic 文件里的「我的收藏」（[LxFixed.Love]）。
 */
data class ImportedList(
    val sourceId: String,
    val name: String,
    val fixed: LxFixed?,
    val tracks: List<ImportedTrack>,
    /** 能导入的首数，按平台。 */
    val platformCounts: Map<String, Int>,
    /** 导不了的首数，按平台（预览里画删除线）；格式不对、重复不在里面。 */
    val skippedPlatforms: Map<String, Int>,
    val skipped: Map<SkipReason, Int>,
    val migu: Int,
    val miguResolved: Int,
) {
    val songs: List<Song> get() = tracks.mapNotNull { it.song }
    val importable: Int get() = tracks.count { it.song != null }

    /** 预览行上写的首数：文件里这个歌单的歌（去掉格式不对、重复之后）。 */
    val shown: Int get() = tracks.size - (skipped[SkipReason.Duplicate] ?: 0)
}

/** 本机同名的歌单；[bound] 的不能合并（合并会写回平台）。 */
data class SameName(val playlistId: String, val name: String, val bound: Boolean)

data class ImportPlan(
    val fileName: String,
    /** 「洛雪音乐」/「myMusic」。 */
    val sourceLabel: String,
    /** 洛雪 v1 旧格式，预览加一句「旧版格式，已按洛雪的规则转换」。 */
    val legacy: Boolean,
    /** myMusic 文件 version > 1。 */
    val newerVersion: Boolean,
    /** 不含洛雪的临时列表。 */
    val lists: List<ImportedList>,
    /** 临时列表的首数，只在「导不了的」里说。 */
    val skippedTemp: Int,
    /** [ImportedList.sourceId] → 本机同名歌单。 */
    val sameName: Map<String, SameName>,
    /** 电视扫码时手机传来的。 */
    val fromPhone: Boolean = false,
    /** 本机已有的歌单名，算「新建」的名字用。 */
    val existingNames: Set<String> = emptySet(),
) {
    val lx: Boolean get() = sourceLabel == LX_LABEL
    val migu: Int get() = lists.sumOf { it.migu }
    val miguResolved: Int get() = lists.sumOf { it.miguResolved }

    companion object {
        const val LX_LABEL = "洛雪音乐"
        const val MYMUSIC_LABEL = "myMusic"
    }
}

enum class ImportTarget(val label: String) {
    Favorites("合并到收藏"),
    Merge("合并到同名歌单"),
    New("新建歌单"),
}

data class ImportChoice(val include: Boolean, val target: ImportTarget)

data class ImportResult(
    val lists: Int,
    val added: Int,
    val alreadyIn: Int,
    val favorites: Int,
    val failed: Int,
    val created: List<String> = emptyList(),
    val firstError: String = "",
)

/** 这一行能选的目标：收藏 → 合并到收藏 / 新建；有没绑定的同名 → 合并 / 新建；其余只能新建。 */
internal fun targetsFor(plan: ImportPlan, list: ImportedList): List<ImportTarget> {
    if (list.fixed == LxFixed.Love) return listOf(ImportTarget.Favorites, ImportTarget.New)
    val same = plan.sameName[list.sourceId]
    return if (same != null && !same.bound) listOf(ImportTarget.Merge, ImportTarget.New) else listOf(ImportTarget.New)
}

/**
 * 默认选择（设计稿 06 节、方案 9.2）：收藏合并进收藏；有没绑定的同名歌单按全局开关（[mergeSameName]）合并或另建；
 * 同名的是绑定歌单只能另建（名字加「（导入）」）；一首能导的都没有的不勾。
 */
internal fun defaultChoices(plan: ImportPlan, mergeSameName: Boolean): Map<String, ImportChoice> =
    plan.lists.associate { list ->
        val include = list.importable > 0
        val target = when {
            list.fixed == LxFixed.Love -> ImportTarget.Favorites
            plan.sameName[list.sourceId]?.bound == false && mergeSameName -> ImportTarget.Merge
            else -> ImportTarget.New
        }
        list.sourceId to ImportChoice(include, target)
    }

/** 全局「合并 / 另建一个」改了：只动有没绑定的同名、用户没单独改过的行。 */
internal fun applySameNameToggle(
    plan: ImportPlan,
    choices: Map<String, ImportChoice>,
    overridden: Set<String>,
    mergeSameName: Boolean,
): Map<String, ImportChoice> = choices.mapValues { (id, choice) ->
    val list = plan.lists.firstOrNull { it.sourceId == id } ?: return@mapValues choice
    val same = plan.sameName[id]
    if (id in overridden || list.fixed == LxFixed.Love || same == null || same.bound) choice
    else choice.copy(target = if (mergeSameName) ImportTarget.Merge else ImportTarget.New)
}

/** 名字比较：去首尾空白、忽略大小写。 */
internal fun nameKey(name: String): String = name.trim().lowercase()

/** 「跑步」被占了 →「跑步（2）」，再占 →「跑步（3）」。 */
internal fun newListName(wanted: String, existing: Set<String>): String {
    val base = wanted.trim().ifBlank { "未命名歌单" }
    val taken = existing.mapTo(HashSet(), ::nameKey)
    if (nameKey(base) !in taken) return base
    var n = 2
    while (nameKey("$base（$n）") in taken) n++
    return "$base（$n）"
}

/** 新建时用的名字：同名的是绑定歌单时先加「（导入）」，再避开已有的名字。 */
internal fun newNameFor(plan: ImportPlan, list: ImportedList, existing: Set<String> = plan.existingNames): String {
    val wanted = if (plan.sameName[list.sourceId]?.bound == true) "${list.name}（导入）" else list.name
    return newListName(wanted, existing)
}

/** 预览行上的目标文字。 */
internal fun targetLabel(plan: ImportPlan, list: ImportedList, target: ImportTarget): String = when (target) {
    ImportTarget.Favorites -> target.label
    ImportTarget.Merge -> "合并到「${plan.sameName[list.sourceId]?.name ?: list.name}」"
    ImportTarget.New -> "新建歌单「${newNameFor(plan, list)}」"
}

/** 去重用的 key：酷狗 hash 大小写不敏感，按小写比。 */
internal fun importKey(song: Song): String = if (song.source == "kugou") "kugou:${song.id.lowercase()}" else song.key

/** 勾上的、能导的首数（底部按钮上的数）。 */
internal fun selectedCount(plan: ImportPlan, choices: Map<String, ImportChoice>): Pair<Int, Int> {
    val picked = plan.lists.filter { choices[it.sourceId]?.include == true && it.importable > 0 }
    return picked.size to picked.sumOf { it.importable }
}

/** 「导不了的」那段话：本地没找到、临时列表、咪咕换取、其它平台、格式不对或重复。 */
internal fun importSkippedText(plan: ImportPlan): String {
    fun sum(reason: SkipReason) = plan.lists.sumOf { it.skipped[reason] ?: 0 }
    val parts = mutableListOf<String>()
    val localMissing = sum(SkipReason.LocalNotFound)
    if (localMissing > 0) parts += "本地文件 $localMissing 首在这台设备上没找到同名歌曲"
    if (plan.skippedTemp > 0) parts += "临时列表（temp）${plan.skippedTemp} 首不导入"
    if (plan.migu > 0) {
        val missed = plan.migu - plan.miguResolved
        parts += if (missed > 0) {
            "咪咕 ${plan.migu} 首用 copyrightId 换取，$missed 首没换到，先按版权号保存，可能放不了"
        } else {
            "咪咕 ${plan.migu} 首已用 copyrightId 换取"
        }
    }
    val unknown = sum(SkipReason.UnknownSource) + sum(SkipReason.MissingId)
    if (unknown > 0) parts += "其它平台或缺少 id 的 $unknown 首本应用放不了"
    val bad = sum(SkipReason.BadFormat) + sum(SkipReason.Duplicate)
    if (bad > 0) parts += "格式不对或重复的 $bad 首"
    return if (parts.isEmpty()) "" else parts.joinToString("；") + "。"
}

// ---------------- 本地歌查找 ----------------

/** 本机本地音乐里的一首（显示用的 Song + 文件名），按歌名、歌手建索引。 */
data class LocalCandidate(val song: Song, val fileName: String)

class LocalMatchIndex(candidates: List<LocalCandidate>) {
    private val byId = candidates.associateBy { it.song.id }
    private val byTitle: Map<String, List<Pair<LocalCandidate, List<String>>>> = candidates
        .map { candidate -> TrackNormalizer.normalize(candidate.song).let { it.titleKey to (candidate to it.artistKeys) } }
        .filter { it.first.isNotBlank() }
        .groupBy({ it.first }, { it.second })

    fun byId(id: String): Song? = byId[id]?.song

    /**
     * 歌名相同、歌手对得上（主歌手相同，或文件里歌手是空的）的算命中；几首都中时先挑时长差 ≤ 3 秒的，
     * 再挑文件名相同的，最后取第一首。都不中返回 null。
     */
    fun pick(title: String, artist: String, durationSec: Int, fileName: String): Song? {
        val wanted = TrackNormalizer.normalize(Song(name = title, artist = artist, duration = durationSec))
        val sameTitle = byTitle[wanted.titleKey].orEmpty()
        val hits = sameTitle.filter { (_, artists) ->
            wanted.artistKeys.isEmpty() || artists.firstOrNull() == wanted.primaryArtistKey || artists.any { it in wanted.artistKeys }
        }.map { it.first }
        if (hits.isEmpty()) return null
        if (hits.size == 1) return hits.single().song
        val closeInTime = if (durationSec > 0) hits.filter { it.song.duration > 0 && abs(it.song.duration - durationSec) <= 3 } else emptyList()
        val pool = closeInTime.ifEmpty { hits }
        val sameFile = if (fileName.isNotBlank()) pool.filter { it.fileName.equals(fileName, ignoreCase = true) } else emptyList()
        return (sameFile.ifEmpty { pool }).first().song
    }
}

/** [LocalMatchIndex.pick] 的纯函数入口，测试用。 */
internal fun pickLocal(candidates: List<LocalCandidate>, title: String, artist: String, durationSec: Int, fileName: String): Song? =
    LocalMatchIndex(candidates).pick(title, artist, durationSec, fileName)
