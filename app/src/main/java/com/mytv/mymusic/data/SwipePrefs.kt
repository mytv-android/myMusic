package com.mytv.mymusic.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * 列表左右滑能放的动作。[id] 写进 DataStore，改名时不能改 id。
 * [button] 为 false 的只能放在「拉到头」（不做事）。
 */
enum class SwipeAction(val id: String, val label: String, val button: Boolean = true) {
    Download("download", "下载"),
    AddToPlaylist("playlist", "加到歌单"),
    Favorite("favorite", "收藏"),
    Unfavorite("unfavorite", "取消收藏"),
    Delete("delete", "删除"),
    PlayNext("play_next", "下一首播放"),
    AddToQueue("queue", "加到播放队列"),
    OpenArtist("artist", "查看歌手"),
    OpenAlbum("album", "查看专辑"),
    ChooseSource("source", "选择音源"),
    None("none", "不做事", button = false),
    ;

    companion object {
        fun byId(id: String): SwipeAction? = entries.firstOrNull { it.id == id.trim() }
    }
}

/**
 * 左右滑的配置（设置 › 滑动操作）。只存在本机，不进同步（同步文件只有歌单、收藏和 Cookie）。
 * [start]：向「结束边」滑（从左往右排的界面里是向右滑）时从起始边露出的按钮，按显示顺序；[startFull]：这一侧拉到头。
 * [end] / [endFull] 同理。每侧 0–2 个按钮；按钮里不能有 [SwipeAction.None]，同一侧不重复，两侧之间可以重复。
 */
data class SwipePrefs(
    val enabled: Boolean = true,
    val start: List<SwipeAction> = listOf(SwipeAction.Download, SwipeAction.AddToPlaylist),
    val startFull: SwipeAction = SwipeAction.Favorite,
    val end: List<SwipeAction> = listOf(SwipeAction.Unfavorite, SwipeAction.Delete),
    val endFull: SwipeAction = SwipeAction.None,
) {
    fun sanitized(): SwipePrefs = copy(start = clean(start), end = clean(end))

    companion object {
        const val MAX_BUTTONS = 2
        internal fun clean(list: List<SwipeAction>): List<SwipeAction> =
            list.filter { it.button }.distinct().take(MAX_BUTTONS)
    }
}

/** 滑动的哪一侧：[Start] 是起始边露出的那组（向右滑），[End] 是结束边（向左滑）；从右往左排的界面里左右对调。 */
enum class SwipeSide { Start, End }

/** DataStore 里的 swipe_* 键和编解码。纯函数，单测直接拿 mutablePreferencesOf() 往返。 */
object SwipePrefsCodec {
    val enabled = booleanPreferencesKey("swipe_enabled")
    val start = stringPreferencesKey("swipe_start")
    val startFull = stringPreferencesKey("swipe_start_full")
    val end = stringPreferencesKey("swipe_end")
    val endFull = stringPreferencesKey("swipe_end_full")

    fun read(values: Preferences): SwipePrefs {
        val defaults = SwipePrefs()
        return SwipePrefs(
            enabled = values[enabled] ?: defaults.enabled,
            start = parseList(values[start], defaults.start),
            startFull = parseFull(values[startFull], defaults.startFull),
            end = parseList(values[end], defaults.end),
            endFull = parseFull(values[endFull], defaults.endFull),
        )
    }

    fun write(values: MutablePreferences, prefs: SwipePrefs) {
        val clean = prefs.sanitized()
        values[enabled] = clean.enabled
        values[start] = formatList(clean.start)
        values[startFull] = clean.startFull.id
        values[end] = formatList(clean.end)
        values[endFull] = clean.endFull.id
    }

    /** null（没写过）→ [fallback]；""（用户删光了）→ 空表；不认识的 id 丢掉；再按每侧规则清一遍。 */
    internal fun parseList(raw: String?, fallback: List<SwipeAction>): List<SwipeAction> {
        if (raw == null) return fallback
        if (raw.isBlank()) return emptyList()
        return SwipePrefs.clean(raw.split(',').mapNotNull { SwipeAction.byId(it) })
    }

    /** null 或认不出来 → [fallback]。 */
    internal fun parseFull(raw: String?, fallback: SwipeAction): SwipeAction =
        raw?.let { SwipeAction.byId(it) } ?: fallback

    internal fun formatList(list: List<SwipeAction>): String = list.joinToString(",") { it.id }
}

// ---- 设置页的编辑操作：纯函数，越界或不合规时原样返回 ----

fun SwipePrefs.buttons(side: SwipeSide): List<SwipeAction> = if (side == SwipeSide.Start) start else end

fun SwipePrefs.full(side: SwipeSide): SwipeAction = if (side == SwipeSide.Start) startFull else endFull

private fun SwipePrefs.withButtons(side: SwipeSide, list: List<SwipeAction>): SwipePrefs =
    if (side == SwipeSide.Start) copy(start = list) else copy(end = list)

/** 第 [index] 个按钮和它前后第 [delta] 个对调（上移 / 下移）。 */
fun SwipePrefs.moveButton(side: SwipeSide, index: Int, delta: Int): SwipePrefs {
    val list = buttons(side)
    val target = index + delta
    if (index !in list.indices || target !in list.indices || delta == 0) return this
    return withButtons(side, list.toMutableList().apply { this[index] = list[target]; this[target] = list[index] })
}

/**
 * 第 [index] 个按钮换成 [action]。同一侧已经有它就两个对调；[index] 等于按钮数时追加（满了或已经有它就不动）。
 * 「不做事」不能放按钮位。
 */
fun SwipePrefs.setButton(side: SwipeSide, index: Int, action: SwipeAction): SwipePrefs {
    if (!action.button) return this
    val list = buttons(side)
    if (index == list.size) {
        if (list.size >= SwipePrefs.MAX_BUTTONS || action in list) return this
        return withButtons(side, list + action)
    }
    if (index !in list.indices || list[index] == action) return this
    val other = list.indexOf(action)
    val next = list.toMutableList()
    if (other >= 0) next[other] = list[index]
    next[index] = action
    return withButtons(side, next)
}

fun SwipePrefs.removeButton(side: SwipeSide, index: Int): SwipePrefs {
    val list = buttons(side)
    if (index !in list.indices) return this
    return withButtons(side, list.filterIndexed { i, _ -> i != index })
}

fun SwipePrefs.setFull(side: SwipeSide, action: SwipeAction): SwipePrefs =
    if (side == SwipeSide.Start) copy(startFull = action) else copy(endFull = action)
