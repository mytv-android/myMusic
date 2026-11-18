package com.mytv.mymusic.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.aggregate.TrackNormalizer
import com.mytv.mymusic.data.DOWNLOAD_ACTIVE
import com.mytv.mymusic.data.DOWNLOAD_DONE
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.SwipeAction
import com.mytv.mymusic.data.SwipePrefs
import com.mytv.mymusic.data.local.LOCAL_SOURCE
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.artist.splitArtistNames
import com.mytv.mymusic.ui.song
import com.mytv.mymusic.ui.theme.LocalAppearance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

// ======================= 对外的类型 =======================

/** 按钮的配色，都取 colorScheme（跟着封面取色和深浅色走）。 */
enum class SwipeTone { Secondary, Tertiary, Primary, Neutral, Muted, Error }

/** 滑开后的一个按钮（或拉到头）。[label] 是按钮上的字，[spoken] 是读屏操作菜单和悬停提示里的说法。 */
@Immutable
class SwipeItem(
    val action: SwipeAction,
    val label: String,
    val spoken: String,
    val icon: ImageVector,
    val tone: SwipeTone,
    val enabled: Boolean = true,
    val run: () -> Unit,
)

/** 一行这一刻能用的按钮：按设置里的顺序；Full 为 null 表示这一侧拉不到头。 */
@Immutable
class SwipeContent(
    val start: List<SwipeItem>,
    val startFull: SwipeItem?,
    val end: List<SwipeItem>,
    val endFull: SwipeItem?,
) {
    /** 读屏「操作」菜单：两侧按钮 + 两个拉到头，按动作去重，去掉禁用的。 */
    val accessibility: List<SwipeItem> =
        (start + end + listOfNotNull(startFull, endFull)).filter { it.enabled }.distinctBy { it.action }

    companion object {
        val Empty = SwipeContent(emptyList(), null, emptyList(), null)
    }
}

/**
 * 一行的滑动（[rememberRowSwipe]）。[content] 读收藏 / 下载状态，只在滑开、悬停、读屏取操作时读，
 * 平时列表不因下载进度、收藏变化重组。
 */
@Stable
class RowSwipe internal constructor(
    internal val content: State<SwipeContent>,
    /** false：不装拖动（读屏开着），只留读屏操作和悬停按钮。 */
    val gestures: Boolean,
    /** 鼠标悬停时行尾出按钮（手机以外的窗口）。 */
    val hover: Boolean,
    internal val coordinator: SwipeCoordinator?,
)

/** 列表告诉滑动「删除」在这里是什么意思；没有可删的列表传 null（榜单、搜索、专辑、歌手页、本地音乐）。 */
class SwipeDelete(
    /** 按钮上的字：删除 / 移出 / 删文件 / 取消收藏。 */
    val label: String,
    /** 读屏：从最近播放删除 / 从歌单移出 / 删除下载文件 / 移出播放队列。 */
    val spoken: String,
    /** 收藏页：删除就是取消收藏，两个按钮都在时只留「取消收藏」。 */
    val sameAsUnfavorite: Boolean = false,
    val icon: ImageVector = Icons.Filled.Delete,
    /** 撤销、确认框都由列表自己做（复用各页已有的逻辑）。 */
    val run: () -> Unit,
)

/** 提示 + 撤销。外壳给的是全局 Snackbar；队列面板在面板里换成自己的（面板是单独窗口，外壳提示看不见）。 */
fun interface SwipeNotifier {
    fun show(message: String, undo: (() -> Unit)?)
}

/** 同时只开一行；列表一滚就全部收起。 */
@Stable
class SwipeCoordinator {
    var open: Any? by mutableStateOf(null)
        private set

    fun claim(token: Any) {
        open = token
    }

    /** 按到了 [token] 这一行：别的行开着就收起。 */
    fun touch(token: Any) {
        if (open != null && open !== token) open = null
    }

    fun release(token: Any) {
        if (open === token) open = null
    }

    fun closeAll() {
        open = null
    }

    /** 挂在 NavHost 和面板里的列表外面：手指一滚就收起。 */
    val scrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f && open != null) open = null
            return Offset.Zero
        }
    }
}

/** 列表滑动用的全局环境（外壳提供，电视上不提供）：动作入口、设置、收藏 / 下载状态。 */
@Stable
class SongSwipeEnv(
    val actions: MusicActions,
    val prefs: State<SwipePrefs>,
    /** 收藏：key → 原记录（取消收藏的撤销按原时间写回）。 */
    val favorites: State<Map<String, SavedSong>>,
    /** 下载：key → DOWNLOAD_DONE / DOWNLOAD_ACTIVE / DOWNLOAD_FAILED。 */
    val downloads: State<Map<String, String>>,
    val screenReader: State<Boolean>,
    val openAddToPlaylist: (Song) -> Unit,
    val coordinator: SwipeCoordinator,
)

val LocalSongSwipeEnv = staticCompositionLocalOf<SongSwipeEnv?> { null }
val LocalSwipeNotifier = staticCompositionLocalOf<SwipeNotifier?> { null }

// ======================= 动作解析（纯函数） =======================

internal data class SwipeDeleteFacts(val label: String, val sameAsUnfavorite: Boolean = false)

internal data class SwipeFacts(
    val favorite: Boolean = false,
    /** DOWNLOAD_* 或 null（没下载过）。 */
    val download: String? = null,
    val local: Boolean = false,
    val live: Boolean = false,
    /** 已经扣掉 hideArtist / hideAlbum。 */
    val hasArtist: Boolean = true,
    val hasAlbum: Boolean = true,
    val delete: SwipeDeleteFacts? = null,
)

internal data class ResolvedAction(val action: SwipeAction, val enabled: Boolean, val label: String)

internal data class ResolvedSwipe(
    val start: List<ResolvedAction>,
    val startFull: ResolvedAction?,
    val end: List<ResolvedAction>,
    val endFull: ResolvedAction?,
) {
    companion object {
        val Empty = ResolvedSwipe(emptyList(), null, emptyList(), null)
    }
}

/**
 * 设置 + 这一行的情况 → 两侧按钮和拉到头。不出现的按钮不占位（另一个占满）；禁用的（已下载、下载中）照样显示。
 * 拉到头的动作不出现或禁用时这一侧没有「拉到头」：拖到露出宽度就停，不震、不执行。
 */
internal fun resolveSwipe(prefs: SwipePrefs, facts: SwipeFacts): ResolvedSwipe {
    if (!prefs.enabled) return ResolvedSwipe.Empty
    fun resolve(action: SwipeAction): ResolvedAction? = when (action) {
        SwipeAction.None -> null
        SwipeAction.Download -> when {
            facts.local || facts.live -> null
            facts.download == DOWNLOAD_DONE -> ResolvedAction(action, false, "已下载")
            facts.download == DOWNLOAD_ACTIVE -> ResolvedAction(action, false, "下载中")
            else -> ResolvedAction(action, true, action.label)
        }
        SwipeAction.Unfavorite -> if (facts.favorite) ResolvedAction(action, true, action.label) else null
        SwipeAction.Delete -> facts.delete?.let { ResolvedAction(action, true, it.label) }
        SwipeAction.OpenArtist -> if (!facts.live && facts.hasArtist) ResolvedAction(action, true, action.label) else null
        SwipeAction.OpenAlbum -> if (!facts.live && facts.hasAlbum) ResolvedAction(action, true, action.label) else null
        else -> ResolvedAction(action, true, action.label)
    }
    var start = SwipePrefs.clean(prefs.start).mapNotNull(::resolve)
    var end = SwipePrefs.clean(prefs.end).mapNotNull(::resolve)
    // 收藏页：「删除」就是「取消收藏」，两个都露出来时只留一个
    if (facts.delete?.sameAsUnfavorite == true && (start + end).any { it.action == SwipeAction.Unfavorite }) {
        start = start.filter { it.action != SwipeAction.Delete }
        end = end.filter { it.action != SwipeAction.Delete }
    }
    return ResolvedSwipe(
        start = start,
        startFull = resolve(prefs.startFull)?.takeIf { it.enabled },
        end = end,
        endFull = resolve(prefs.endFull)?.takeIf { it.enabled },
    )
}

/** 动作的图标和配色。「删除」的图标由列表给（下载页是描边的垃圾桶，和行尾按钮一致）。 */
internal fun swipeIcon(action: SwipeAction, deleteIcon: ImageVector = Icons.Filled.Delete): ImageVector = when (action) {
    SwipeAction.Download -> Icons.Filled.Download
    SwipeAction.AddToPlaylist -> Icons.Filled.LibraryAdd
    SwipeAction.Favorite -> Icons.Filled.Favorite
    SwipeAction.Unfavorite -> Icons.Filled.FavoriteBorder
    SwipeAction.Delete -> deleteIcon
    SwipeAction.PlayNext -> Icons.Filled.QueuePlayNext
    SwipeAction.AddToQueue -> Icons.AutoMirrored.Filled.PlaylistAdd
    SwipeAction.OpenArtist -> Icons.Filled.Person
    SwipeAction.OpenAlbum -> Icons.Filled.Album
    SwipeAction.ChooseSource -> Icons.Filled.SwapHoriz
    SwipeAction.None -> Icons.Filled.SwapHoriz
}

internal fun swipeTone(action: SwipeAction): SwipeTone = when (action) {
    SwipeAction.Download, SwipeAction.PlayNext, SwipeAction.AddToQueue -> SwipeTone.Secondary
    SwipeAction.AddToPlaylist -> SwipeTone.Tertiary
    SwipeAction.Favorite -> SwipeTone.Primary
    SwipeAction.Unfavorite -> SwipeTone.Neutral
    SwipeAction.OpenArtist, SwipeAction.OpenAlbum, SwipeAction.ChooseSource, SwipeAction.None -> SwipeTone.Muted
    SwipeAction.Delete -> SwipeTone.Error
}

/** 解析结果配上图标、配色和执行；[spoken] / [run] 由调用方按真实列表或设置页演示给。 */
internal fun swipeContentOf(
    resolved: ResolvedSwipe,
    deleteIcon: ImageVector,
    spoken: (ResolvedAction) -> String,
    run: (SwipeAction) -> () -> Unit,
): SwipeContent {
    fun item(r: ResolvedAction) = SwipeItem(
        action = r.action,
        label = r.label,
        spoken = spoken(r),
        icon = swipeIcon(r.action, deleteIcon),
        tone = swipeTone(r.action),
        enabled = r.enabled,
        run = run(r.action),
    )
    return SwipeContent(
        start = resolved.start.map(::item),
        startFull = resolved.startFull?.let(::item),
        end = resolved.end.map(::item),
        endFull = resolved.endFull?.let(::item),
    )
}

/** 查看歌手打开哪一位：歌手名拆开后第一位不是 [hide]（就是这一页的歌手）的；没有返回 null。 */
internal fun swipeArtistToOpen(artist: String, hide: String): String? {
    val hidden = TrackNormalizer.keyOf(hide)
    return splitArtistNames(artist).firstOrNull { hidden.isEmpty() || TrackNormalizer.keyOf(it) != hidden }
}

// ======================= 每行的入口 =======================

/**
 * 一行歌的滑动内容。返回 null 时 SongRow 照原样画、不能滑：没有外壳环境（电视、预览）、[song] 为空、
 * [enabled] 为 false（多选中）、设置里关掉了滑动（连读屏操作也不挂）。
 * [favoriteKeys]：哪些 key 收藏了就算「已收藏」（搜索结果是聚合曲目，收藏的可能是另一个平台的版本），默认就是这首。
 * [onChooseSource]：「选择音源」换成列表自己的入口（搜索结果按聚合曲目打开，带着所有平台的版本）。
 */
@Composable
fun rememberRowSwipe(
    song: Song?,
    queue: List<Song>,
    delete: SwipeDelete? = null,
    enabled: Boolean = true,
    hideArtist: String = "",
    hideAlbum: String = "",
    favoriteKeys: List<String>? = null,
    onChooseSource: (() -> Unit)? = null,
): RowSwipe? {
    val env = LocalSongSwipeEnv.current ?: return null
    if (song == null || !enabled || !env.prefs.value.enabled) return null
    val window = LocalAppWindow.current
    val latestSong = rememberUpdatedState(song)
    val latestQueue = rememberUpdatedState(queue)
    val latestDelete = rememberUpdatedState(delete)
    val latestChooseSource = rememberUpdatedState(onChooseSource)
    val latestNotifier = rememberUpdatedState(LocalSwipeNotifier.current)
    val deleteFacts = delete?.let { SwipeDeleteFacts(it.label, it.sameAsUnfavorite) }
    val deleteSpoken = delete?.spoken.orEmpty()
    val deleteIcon = delete?.icon ?: Icons.Filled.Delete
    val keys = favoriteKeys ?: listOf(song.key)
    val content = remember(env, song.key, keys, deleteFacts, deleteSpoken, deleteIcon, hideArtist, hideAlbum) {
        derivedStateOf {
            val current = latestSong.value
            val favorites = env.favorites.value
            val artist = swipeArtistToOpen(current.artist, hideArtist)
            val facts = SwipeFacts(
                favorite = keys.any { it in favorites },
                download = env.downloads.value[current.key],
                local = current.source == LOCAL_SOURCE,
                live = current.isLive,
                hasArtist = artist != null,
                hasAlbum = current.album.isNotBlank() &&
                    TrackNormalizer.keyOf(current.album) != TrackNormalizer.keyOf(hideAlbum),
                delete = deleteFacts,
            )
            val notify: (String, (() -> Unit)?) -> Unit = { text, undo -> latestNotifier.value?.show(text, undo) }
            swipeContentOf(
                resolved = resolveSwipe(env.prefs.value, facts),
                deleteIcon = deleteIcon,
                spoken = { r ->
                    when (r.action) {
                        SwipeAction.Delete -> deleteSpoken.ifBlank { r.label }
                        SwipeAction.OpenArtist -> "查看歌手：${artist.orEmpty()}"
                        SwipeAction.OpenAlbum -> "查看专辑：${current.album}"
                        SwipeAction.Download -> if (r.enabled) "下载「${current.title}」" else r.label
                        else -> r.label
                    }
                },
                run = { action ->
                    {
                        val target = latestSong.value
                        val actions = env.actions
                        when (action) {
                            SwipeAction.Download -> actions.download(target)
                            SwipeAction.AddToPlaylist -> env.openAddToPlaylist(target)
                            SwipeAction.Favorite -> if (keys.any { it in env.favorites.value }) {
                                // 已收藏时只提示，不会变成取消（取消在另一侧，免得手滑）
                                notify("「${target.title}」已在收藏里", null)
                            } else {
                                actions.favorite(target)
                                notify("已收藏「${target.title}」", null)
                            }
                            SwipeAction.Unfavorite -> keys.firstNotNullOfOrNull { env.favorites.value[it] }?.let { record ->
                                actions.favorite(runCatching { record.song() }.getOrDefault(target))
                                notify("已取消收藏「${target.title}」") { actions.restoreSaved(listOf(record)) }
                            }
                            SwipeAction.Delete -> latestDelete.value?.run?.invoke()
                            SwipeAction.PlayNext -> actions.playSongNext(target)
                            SwipeAction.AddToQueue -> actions.addToQueue(target)
                            SwipeAction.OpenArtist -> swipeArtistToOpen(target.artist, hideArtist)?.let { actions.openArtistByName(it, target) }
                            SwipeAction.OpenAlbum -> actions.openAlbumOf(target)
                            SwipeAction.ChooseSource ->
                                latestChooseSource.value?.invoke() ?: actions.openSourceForSong(target, latestQueue.value)
                            SwipeAction.None -> Unit
                        }
                    }
                },
            )
        }
    }
    val gestures = !env.screenReader.value
    val hover = !window.isPhone && !window.isTv
    return remember(content, gestures, hover, env) { RowSwipe(content, gestures, hover, env.coordinator) }
}

/**
 * 设置页的示例行：按 [prefs] 画，动作只提示「演示：下载」不真执行。
 * 按「没下载、不是本地歌、可删」算，好让所有按钮都看得到；取消收藏按「已收藏」算，不然默认配置左滑只剩「删除」。
 */
@Composable
fun rememberDemoRowSwipe(prefs: SwipePrefs, notify: (String) -> Unit): RowSwipe {
    val env = LocalSongSwipeEnv.current
    val window = LocalAppWindow.current
    val latestNotify = rememberUpdatedState(notify)
    val coordinator = env?.coordinator ?: remember { SwipeCoordinator() }
    val content = remember(prefs) {
        mutableStateOf(
            swipeContentOf(
                resolved = resolveSwipe(prefs.copy(enabled = true), SwipeFacts(favorite = true, delete = SwipeDeleteFacts("删除"))),
                deleteIcon = Icons.Filled.Delete,
                spoken = { it.label },
                run = { action -> { latestNotify.value("演示：${action.label}") } },
            ),
        )
    }
    val gestures = env?.screenReader?.value != true
    val hover = !window.isPhone && !window.isTv
    return remember(content, gestures, hover, coordinator) { RowSwipe(content, gestures, hover, coordinator) }
}

// ======================= 几何与手势门（纯函数） =======================

internal enum class SwipeAnchor { FullEnd, OpenEnd, Closed, OpenStart, FullStart }

internal enum class SwipeClaim { Undecided, Horizontal, Vertical }

/** 露出按钮的宽度（稿子锚点 ±128）：一侧两个按钮各 64dp，一个按钮 128dp。 */
internal val SwipeOpenWidth = 128.dp
private val SwipeFullCap = 240.dp
private val SwipeFullGap = 32.dp
private val SwipeSlop = 16.dp
private val SwipeEdge = 24.dp
private val SwipeFlingVelocity = 800.dp
private val SwipeMinFlingOffset = 24.dp

/** tan 30°：纵向位移不超过横向的这么多倍才算横滑。 */
internal const val SwipeAngleTan = 0.57735026f

/** 「拉到头」：行宽 60%，最多 [capPx]（240dp）；至少比露出宽度多 [gapPx]（32dp），窄行时不和露出挤在一起。 */
internal fun swipeFullDistance(rowWidthPx: Float, openPx: Float, capPx: Float, gapPx: Float): Float =
    maxOf(minOf(rowWidthPx * 0.6f, capPx), openPx + gapPx)

/**
 * 这一行这一刻能停的位置（px，带方向：正 = 露出起始边）。一侧没有按钮就没有 Open，没有拉到头就没有 Full。
 * 纯 Kotlin，JVM 单测直接测；组件里再转成 DraggableAnchors。
 */
internal data class SwipeStops(val openStart: Float?, val fullStart: Float?, val openEnd: Float?, val fullEnd: Float?) {
    val isEmpty: Boolean get() = openStart == null && fullStart == null && openEnd == null && fullEnd == null

    /** [offset] 是否已经过了这一侧的拉到头。 */
    fun inFull(offset: Float): Boolean =
        (fullStart != null && offset >= fullStart) || (fullEnd != null && offset <= fullEnd)

    companion object {
        val None = SwipeStops(null, null, null, null)
    }
}

internal fun swipeStops(
    hasStartButtons: Boolean,
    hasStartFull: Boolean,
    hasEndButtons: Boolean,
    hasEndFull: Boolean,
    openPx: Float,
    fullPx: Float,
): SwipeStops = SwipeStops(
    openStart = openPx.takeIf { hasStartButtons },
    fullStart = fullPx.takeIf { hasStartFull },
    openEnd = (-openPx).takeIf { hasEndButtons },
    fullEnd = (-fullPx).takeIf { hasEndFull },
)

/** Closed 永远在 0；拉到头的锚点放到 [limitPx]（行宽），拖过拉到头还能再跟手一段，松手时按 [SwipeStops] 判断。 */
internal fun SwipeStops.toAnchors(limitPx: Float): DraggableAnchors<SwipeAnchor> = DraggableAnchors {
    SwipeAnchor.Closed at 0f
    openStart?.let { SwipeAnchor.OpenStart at it }
    fullStart?.let { SwipeAnchor.FullStart at maxOf(it, limitPx) }
    openEnd?.let { SwipeAnchor.OpenEnd at it }
    fullEnd?.let { SwipeAnchor.FullEnd at minOf(it, -limitPx) }
}

/**
 * 松手落到哪：过了 Full 就是 Full；没到 Full 时过了 Open 的一半，或者朝那边甩（≥ [flingPx]/s 且已经移出
 * [minFlingOffsetPx]）就是 Open；反向甩、不到一半、或者这一侧没有按钮都回 Closed。速度、位移都是带方向的 px。
 */
internal fun swipeSettle(offset: Float, velocity: Float, stops: SwipeStops, flingPx: Float, minFlingOffsetPx: Float): SwipeAnchor {
    if (offset > 0f) {
        if (stops.fullStart != null && offset >= stops.fullStart) return SwipeAnchor.FullStart
        val open = stops.openStart ?: return SwipeAnchor.Closed
        return when {
            velocity <= -flingPx -> SwipeAnchor.Closed
            offset >= open / 2f -> SwipeAnchor.OpenStart
            velocity >= flingPx && offset >= minFlingOffsetPx -> SwipeAnchor.OpenStart
            else -> SwipeAnchor.Closed
        }
    }
    if (offset < 0f) {
        if (stops.fullEnd != null && offset <= stops.fullEnd) return SwipeAnchor.FullEnd
        val open = stops.openEnd ?: return SwipeAnchor.Closed
        return when {
            velocity >= flingPx -> SwipeAnchor.Closed
            offset <= open / 2f -> SwipeAnchor.OpenEnd
            velocity <= -flingPx && offset <= -minFlingOffsetPx -> SwipeAnchor.OpenEnd
            else -> SwipeAnchor.Closed
        }
    }
    return SwipeAnchor.Closed
}

/** 横向先到 [slopPx]（16dp）且角度小于 30° 才归行，否则交给列表纵向滚动；没走够就再等。 */
internal fun swipeClaim(dx: Float, dy: Float, slopPx: Float): SwipeClaim {
    if (hypot(dx, dy) < slopPx) return SwipeClaim.Undecided
    return if (abs(dy) <= abs(dx) * SwipeAngleTan) SwipeClaim.Horizontal else SwipeClaim.Vertical
}

/** 离窗口左右边 [edgePx] 以内按下不接：留给系统返回手势。 */
internal fun inEdgeZone(xInWindow: Float, windowWidth: Float, edgePx: Float): Boolean =
    xInWindow < edgePx || xInWindow > windowWidth - edgePx

/** 边缘让位取 24dp 和系统手势区左右值里最大的。 */
internal fun swipeEdgePx(minPx: Float, gestureLeftPx: Float, gestureRightPx: Float): Float =
    maxOf(minPx, gestureLeftPx, gestureRightPx)

// ======================= 行容器 =======================

/** 一行的拖动状态：只借 AnchoredDraggableState 管偏移、锚点夹边和吸附动画，拖动由 [swipeGesture] 自己喂。 */
@Stable
internal class SwipeRowState(private val scope: CoroutineScope) {
    val token = Any()
    val drag = AnchoredDraggableState(SwipeAnchor.Closed, DraggableAnchors { SwipeAnchor.Closed at 0f })
    /** 这次拖动用的停靠位置（开始拖时按当时的按钮算）；底层的铺满色块按它判断。 */
    var stops by mutableStateOf(SwipeStops.None)
    var dragging by mutableStateOf(false)
    var spec: AnimationSpec<Float> = spring()
    var reduceMotion = false
    var coordinator: SwipeCoordinator? = null
    /** 行在窗口里的左边和宽度（px），只在手势里读，不触发重组。 */
    var leftInWindow = 0f
    var width = 0f
    private var job: Job? = null

    val offset: Float get() = drag.offset.let { if (it.isNaN()) 0f else it }

    fun stopAnimation() {
        job?.cancel()
        job = null
    }

    fun settle(target: SwipeAnchor) {
        job?.cancel()
        job = scope.launch {
            if (reduceMotion) drag.snapTo(target) else drag.animateTo(target, spec)
        }
        if (target == SwipeAnchor.Closed) coordinator?.release(token)
    }

    fun close() {
        if (offset != 0f || drag.targetValue != SwipeAnchor.Closed) settle(SwipeAnchor.Closed)
    }
}

/**
 * 能滑的歌曲行外框：外层 Box 接手势、裁圆角，底下是按钮层，上面是跟手平移的前景行（调用方的内容）。
 * [rowModifier] 是前景行的点按、语义和内边距；[content] 的参数是鼠标是否悬停在这一行上。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SwipeRowFrame(
    swipe: RowSwipe,
    shape: Shape,
    modifier: Modifier,
    background: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    playing: Boolean,
    padding: PaddingValues,
    content: @Composable RowScope.(hovered: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val row = remember { SwipeRowState(scope) }
    row.spec = MaterialTheme.motionScheme.fastSpatialSpec()
    row.reduceMotion = LocalAppearance.current.reduceMotion
    row.coordinator = swipe.coordinator
    val coordinator = swipe.coordinator
    if (coordinator != null) {
        // 别的行开始拖、列表一滚：这一行收起
        LaunchedEffect(row, coordinator) {
            snapshotFlow { coordinator.open }.collect { open ->
                if (open !== row.token && !row.dragging) row.close()
            }
        }
    }
    var hovered by remember { mutableStateOf(false) }
    val exposed by remember(row) { derivedStateOf { row.offset != 0f } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .tvFocusRing(shape, scale = 1.02f)
            .clip(shape)
            .onGloballyPositioned {
                row.leftInWindow = it.positionInWindow().x
                row.width = it.size.width.toFloat()
            }
            .then(if (swipe.gestures) Modifier.swipeGesture(row, swipe, rtl) else Modifier)
            .then(
                if (swipe.hover) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                when (event.type) {
                                    PointerEventType.Enter, PointerEventType.Move ->
                                        if (event.changes.any { it.type == PointerType.Mouse }) hovered = true
                                    PointerEventType.Exit -> hovered = false
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        if (exposed) SwipeUnderlay(row, swipe, Modifier.matchParentSize())
        Row(
            Modifier
                .offset { IntOffset((row.offset * if (rtl) -1f else 1f).roundToInt(), 0) }
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .background(background)
                .combinedClickable(
                    // 开着时点前景只是收起，不放歌
                    onClick = { if (row.offset != 0f) row.close() else onClick() },
                    onLongClick = onLongClick,
                    onLongClickLabel = onLongClick?.let { "更多操作" },
                )
                .semantics {
                    if (playing) stateDescription = "正在播放"
                    // 读屏「操作」菜单里挂同样的动作（读屏开着时单指横滑是下一项，不能抢）
                    val items = swipe.content.value.accessibility
                    if (items.isNotEmpty()) {
                        customActions = items.map { item -> CustomAccessibilityAction(item.spoken) { item.run(); true } }
                    }
                }
                .padding(padding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content(swipe.hover && hovered)
        }
    }
}

/** 底层：露出的按钮 + 拉到头的铺满色块。只在偏移不为 0 时组合。 */
@Composable
private fun SwipeUnderlay(row: SwipeRowState, swipe: RowSwipe, modifier: Modifier) {
    val content = swipe.content.value
    val towardStart by remember(row) { derivedStateOf { row.offset > 0f } }
    val buttons = if (towardStart) content.start else content.end
    val full = if (towardStart) content.startFull else content.endFull
    // 行内容变了（比如取消收藏后按钮没了），开着的这一侧什么都没有了：收起
    LaunchedEffect(buttons.isEmpty() && full == null) {
        if (buttons.isEmpty() && full == null && !row.dragging) row.close()
    }
    val atFull by remember(row) { derivedStateOf { row.stops.inFull(row.offset) } }
    Box(modifier) {
        if (buttons.isNotEmpty()) {
            Row(
                Modifier
                    .align(if (towardStart) Alignment.CenterStart else Alignment.CenterEnd)
                    .width(SwipeOpenWidth)
                    .fillMaxHeight(),
            ) {
                buttons.forEach { item ->
                    SwipeButton(item, Modifier.weight(1f).fillMaxHeight()) {
                        item.run()
                        row.close()
                    }
                }
            }
        }
        if (full != null) {
            val (bg, fg) = toneColors(full.tone)
            val scale by animateFloatAsState(if (atFull) 1.25f else 1f, spring(), label = "swipeFullIcon")
            // 有按钮的一侧：过了拉到头才铺满（稿子），中途不和按钮叠成一片；没有按钮的一侧跟着拖动渐显
            val shown by animateFloatAsState(if (atFull) 1f else 0f, tween(150), label = "swipeFullFlood")
            val gradual = buttons.isEmpty()
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = if (gradual) {
                            val fullPx = (if (towardStart) row.stops.fullStart else row.stops.fullEnd)?.let(::abs) ?: Float.MAX_VALUE
                            maxOf(shown, (abs(row.offset) / fullPx).coerceIn(0f, 1f))
                        } else {
                            shown
                        }
                    }
                    .background(bg),
                contentAlignment = if (towardStart) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Row(
                    Modifier.padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(full.icon, null, Modifier.size(26.dp).graphicsLayer { scaleX = scale; scaleY = scale }, tint = fg)
                    Text(
                        if (atFull) "松手${full.label}" else full.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = fg,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeButton(item: SwipeItem, modifier: Modifier, onClick: () -> Unit) {
    val (bg, fg) = toneColors(item.tone)
    Column(
        modifier
            .background(bg)
            .clickable(enabled = item.enabled, role = Role.Button, onClickLabel = item.spoken, onClick = onClick)
            .then(if (item.enabled) Modifier else Modifier.semantics { disabled() })
            .padding(horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val fade = if (item.enabled) Modifier else Modifier.alpha(0.38f)
        Icon(item.icon, null, fade.size(22.dp), tint = fg)
        // 两个按钮各 64dp，「下一首播放」「加到播放队列」放不下 11sp，缩到放得下为止
        Text(
            item.label,
            fade.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = 0.5.sp),
        )
    }
}

@Composable
internal fun toneColors(tone: SwipeTone): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        SwipeTone.Secondary -> scheme.secondaryContainer to scheme.onSecondaryContainer
        SwipeTone.Tertiary -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        SwipeTone.Primary -> scheme.primaryContainer to scheme.onPrimaryContainer
        SwipeTone.Neutral -> scheme.surfaceContainerHighest to scheme.onSurface
        SwipeTone.Muted -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
        SwipeTone.Error -> scheme.errorContainer to scheme.onErrorContainer
    }
}

/**
 * 鼠标悬停时行尾的一排圆形按钮：两侧按钮按「起始边 + 结束边」顺序，不含拉到头。
 * 每个都有读屏说法和悬停提示；点了执行，和滑开后点按钮一样。
 */
@Composable
internal fun SwipeHoverButtons(swipe: RowSwipe) {
    val content = swipe.content.value
    val items = (content.start + content.end).distinctBy { it.action }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { item ->
            val (bg, fg) = toneColors(item.tone)
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(item.spoken) } },
                state = rememberTooltipState(),
            ) {
                FilledTonalIconButton(
                    onClick = item.run,
                    enabled = item.enabled,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = bg, contentColor = fg),
                ) {
                    Icon(item.icon, item.spoken, Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * 横滑手势：按下后横向先走够 16dp、角度小于 30° 才认领，之后消费移动、喂给 AnchoredDraggableState；
 * 纵向先到、或者列表已经认领了纵向滚动就退出。离窗口左右边缘 24dp（或系统手势区）内按下不接；
 * 有悬停按钮的窗口里鼠标不拖。没认领就松手是点按，交给前景行的 clickable。
 */
@Composable
private fun Modifier.swipeGesture(row: SwipeRowState, swipe: RowSwipe, rtl: Boolean): Modifier {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val gestures = WindowInsets.systemGestures
    val edge = swipeEdgePx(
        with(density) { SwipeEdge.toPx() },
        gestures.getLeft(density, layoutDirection).toFloat(),
        gestures.getRight(density, layoutDirection).toFloat(),
    )
    val windowWidth = LocalWindowInfo.current.containerSize.width.toFloat()
    val haptic = LocalHapticFeedback.current
    val latest = rememberUpdatedState(SwipeGestureEnv(edge, windowWidth, if (rtl) -1f else 1f, swipe))
    return pointerInput(row) {
        val slop = SwipeSlop.toPx()
        val openPx = SwipeOpenWidth.toPx()
        val capPx = SwipeFullCap.toPx()
        val gapPx = SwipeFullGap.toPx()
        val flingPx = SwipeFlingVelocity.toPx()
        val minFlingPx = SwipeMinFlingOffset.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val env = latest.value
            if (down.type == PointerType.Mouse && env.swipe.hover) return@awaitEachGesture
            if (inEdgeZone(row.leftInWindow + down.position.x, env.windowWidth, env.edge)) return@awaitEachGesture
            env.swipe.coordinator?.touch(row.token)
            val content = env.swipe.content.value
            val stops = swipeStops(
                hasStartButtons = content.start.isNotEmpty(),
                hasStartFull = content.startFull != null,
                hasEndButtons = content.end.isNotEmpty(),
                hasEndFull = content.endFull != null,
                openPx = openPx,
                fullPx = swipeFullDistance(row.width, openPx, capPx, gapPx),
            )
            // 两侧都没东西、也没开着：这一行不能滑
            if (stops.isEmpty && row.offset == 0f) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var total = Offset.Zero
            var claimed = false
            var inFull = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (claimed) change.consume()
                    break
                }
                tracker.addPointerInputChange(change)
                if (!claimed) {
                    if (change.isConsumed) break
                    total += change.positionChange()
                    when (swipeClaim(total.x, total.y, slop)) {
                        SwipeClaim.Vertical -> break
                        SwipeClaim.Undecided -> {
                            // 列表（祖先）这一下认领了纵向滚动：Final 阶段能看到它的消费
                            val final = awaitPointerEvent(PointerEventPass.Final)
                            if (final.changes.any { it.id == down.id && it.isConsumed }) break
                            continue
                        }
                        SwipeClaim.Horizontal -> {
                            claimed = true
                            row.stopAnimation()
                            row.dragging = true
                            row.stops = stops
                            row.drag.updateAnchors(stops.toAnchors(row.width))
                            env.swipe.coordinator?.claim(row.token)
                            row.drag.dispatchRawDelta(total.x * env.direction)
                            change.consume()
                        }
                    }
                } else {
                    row.drag.dispatchRawDelta(change.positionChange().x * env.direction)
                    change.consume()
                }
                val nowFull = stops.inFull(row.offset)
                if (nowFull != inFull) {
                    inFull = nowFull
                    haptic.performHapticFeedback(
                        if (nowFull) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentFrequentTick,
                    )
                }
            }
            if (!claimed) return@awaitEachGesture
            row.dragging = false
            val velocity = tracker.calculateVelocity().x * env.direction
            when (swipeSettle(row.offset, velocity, stops, flingPx, minFlingPx)) {
                // 拉到头：跨过那一下已经震过一次（稿子「有一次震动」），松手直接执行再收回
                SwipeAnchor.FullStart -> {
                    env.swipe.content.value.startFull?.takeIf { it.enabled }?.run?.invoke()
                    row.settle(SwipeAnchor.Closed)
                }
                SwipeAnchor.FullEnd -> {
                    env.swipe.content.value.endFull?.takeIf { it.enabled }?.run?.invoke()
                    row.settle(SwipeAnchor.Closed)
                }
                else -> row.settle(swipeSettle(row.offset, velocity, stops, flingPx, minFlingPx))
            }
        }
    }
}

private class SwipeGestureEnv(val edge: Float, val windowWidth: Float, val direction: Float, val swipe: RowSwipe)
