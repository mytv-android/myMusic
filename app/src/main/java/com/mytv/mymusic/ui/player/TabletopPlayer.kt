package com.mytv.mymusic.ui.player

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.data.CrossfadeAnimation
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TabletopTop
import com.mytv.mymusic.playback.SleepTimerState
import com.mytv.mymusic.ui.CoverBox
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.adaptive.FoldSplit
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.WavySeekBar
import com.mytv.mymusic.ui.player.stage.LyricStage
import com.mytv.mymusic.ui.player.stage.StageBinding
import com.mytv.mymusic.ui.player.stage.StageInsets
import com.mytv.mymusic.ui.player.stage.ToolbarAnimationButton
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * 桌面姿态、双横的播放页（横折痕把屏幕分成上下两块）：上看，下控。
 *
 * - 上半屏（立着的那一半）：顶栏是收起、音源胶囊、更多；下面按设置（[top]）放歌词、形状封面或大时钟。
 *   工具栏里的「歌词」、更多菜单里的「全屏歌词」（[lyricsMode]）把上半屏临时换成歌词，再点一次换回去；
 *   设置本来就是歌词时工具栏里不放这个按钮。换源结果浮在上半屏底部正中。
 * - 下半屏（平放在桌上、用手点的那一面）：小封面（上半屏是封面时不放）、歌名、收藏、波浪进度、连接按钮组、浮动工具栏。
 *   这些控件不随空闲淡出：PlayerScreen 在桌面姿态下不让播放页进入空闲。
 *
 * 分界取折痕上下缘再各让 16dp；双横的铰链本身有宽度，不另让（见 [tabletopFrame]）。下半屏放不下时先压间距、
 * 换小号按钮组，还放不下整组等比缩小（[tabletopControlSlots]），任何高度都不重叠、不过折痕。
 * 进度只在各自的 leaf 里读；整页只随切歌、播放 / 暂停、上半屏换内容这类低频变化重组。
 */
@Composable
internal fun TabletopPlayer(
    song: Song,
    playing: Boolean,
    duration: Long,
    position: State<Long>,
    favorite: Boolean,
    sleep: SleepTimerState,
    lyrics: ParsedLyrics,
    lyricsLoading: Boolean,
    active: State<Int>,
    stage: StageBinding,
    sourceProvider: String?,
    sourceDetail: String,
    playbackMode: String,
    notice: String,
    deepLink: String,
    actions: MusicActions,
    top: TabletopTop,
    lyricsMode: Boolean,
    onLyricsMode: (Boolean) -> Unit,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTimer: () -> Unit,
    onFavoriteBurst: () -> Unit,
    onOpenSource: () -> Unit,
    onSkip: (Int) -> Unit,
    pulse: BackdropPulse,
    coverShape: CoverShapeChoice,
    transitionAnimation: CrossfadeAnimation,
) {
    val window = LocalAppWindow.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val shown = if (lyricsMode) TabletopTop.Lyrics else top
    val waveScale = remember(pulse) { { pulseWaveScale(pulse) } }
    val waveBpm = remember(pulse) { { pulseWaveBpm(pulse) } }

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val split = playerFoldSplit(
            window.fold, insets, density, direction, width, height,
            minSegment = with(density) { TabletopMinHalf.toPx() },
        )?.takeIf { !it.vertical }
        val frame = tabletopFrame(height, density.density, split)
        // 下半屏放得下就用手机播放页的大号按钮组（256×88），否则换小号；按钮组用哪档要在组合阶段定，按估计的高度判断
        val roomy = with(density) {
            val seek = 32.dp.toPx() + 16.sp.toPx()
            val needed = TabletopMetaHeight.toPx() + seek + TransportGroupSize.Phone.height.toPx() + TabletopToolbarHeight.toPx() +
                TabletopIdealGaps.sum().dp.toPx()
            frame.bottomHeight >= needed
        }
        val policy = remember(frame) { TabletopPolicy(frame) }
        Layout(
            contents = listOf<@Composable () -> Unit>(
                { TabletopTopBar(song, sourceProvider, sourceDetail, playbackMode, deepLink, actions, onClose, onOpenSource, onLyricsMode) },
                {
                    TabletopStage(
                        shown = shown,
                        song = song,
                        playing = playing,
                        lyrics = lyrics,
                        lyricsLoading = lyricsLoading,
                        active = active,
                        stage = stage,
                        position = position,
                        actions = actions,
                        pulse = pulse,
                        coverShape = coverShape,
                        transitionAnimation = transitionAnimation,
                        wide = window.widthDp >= 600,
                        onSkip = onSkip,
                        onFavoriteBurst = onFavoriteBurst,
                    )
                },
                { WideNotice(notice) },
                { TabletopMeta(song, favorite, actions, thumb = shown != TabletopTop.Cover) },
                { WavySeekBar(position, duration, playing, actions::seek, Modifier.fillMaxWidth(), amplitudeScale = waveScale, waveBpm = waveBpm, live = song.isLive) },
                {
                    TransportButtonGroup(
                        playing = playing,
                        onPrevious = { actions.transport("previous") },
                        onPlayPause = { actions.transport("play") },
                        onNext = { actions.transport("next") },
                        size = if (roomy) TransportGroupSize.Phone else TransportGroupSize.Compact,
                    )
                },
                {
                    PlayerToolbar(height = TabletopToolbarHeight) {
                        if (top != TabletopTop.Lyrics) ToolbarLyricsButton(on = lyricsMode, onToggle = onLyricsMode)
                        if (shown == TabletopTop.Lyrics) ToolbarAnimationButton(stage.onOpenSheet)
                        ToolbarQueueButton(onOpenQueue)
                        ToolbarSleepButton(sleep, onOpenTimer)
                        ToolbarSourceButton(onOpenSource)
                        MoreMenu(song, deepLink, actions, onOpenSource, onLyricsMode)
                    }
                },
            ),
            modifier = Modifier.fillMaxSize(),
            measurePolicy = policy,
        )
    }
}

/** 上半屏顶栏：收起、音源胶囊、更多，和手机播放页的顶栏一样，只是不随空闲淡出。 */
@Composable
private fun TabletopTopBar(
    song: Song,
    sourceProvider: String?,
    sourceDetail: String,
    playbackMode: String,
    deepLink: String,
    actions: MusicActions,
    onClose: () -> Unit,
    onOpenSource: () -> Unit,
    onLyricsMode: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(TabletopBarHeight).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "收起播放页", tint = MaterialTheme.colorScheme.onSurface) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SourcePill(song, sourceProvider, sourceDetail, playbackMode, onOpenSource)
        }
        MoreMenu(song, deepLink, actions, onOpenSource, onLyricsMode)
    }
}

/** 上半屏顶栏下面的内容：歌词、形状封面或大时钟，换的时候交叉淡入淡出。 */
@Composable
private fun TabletopStage(
    shown: TabletopTop,
    song: Song,
    playing: Boolean,
    lyrics: ParsedLyrics,
    lyricsLoading: Boolean,
    active: State<Int>,
    stage: StageBinding,
    position: State<Long>,
    actions: MusicActions,
    pulse: BackdropPulse,
    coverShape: CoverShapeChoice,
    transitionAnimation: CrossfadeAnimation,
    wide: Boolean,
    onSkip: (Int) -> Unit,
    onFavoriteBurst: () -> Unit,
) {
    Crossfade(shown, Modifier.fillMaxSize(), MaterialTheme.motionScheme.defaultEffectsSpec(), label = "tabletopTop") { what ->
        when (what) {
            // 立在桌上隔着一段距离看：大折叠用平板专注歌词的字号，小折叠用平板歌词栏的字号；太宽时一行不超过 760dp
            // 选了别的歌词动画时上半屏换成舞台，文字区左右和歌词列表一样留 24dp、最宽 760dp；
            // 舞台上下边（顶栏下沿、折痕上方）硬切很显眼，各渐隐 [TabletopStageFade]，文字区让出这两条带
            TabletopTop.Lyrics -> {
                val insets = remember { StageInsets() }
                LyricStage(
                    mode = stage.mode,
                    lyrics = stage.lyrics,
                    loading = lyricsLoading,
                    position = position,
                    playing = playing,
                    song = song,
                    prefs = stage.prefs,
                    textSp = TABLETOP_STAGE_SP,
                    insets = insets,
                    edgeFade = TabletopStageFade,
                    modifier = Modifier.fillMaxSize().layout { measurable, constraints ->
                        val side = maxOf(24.dp.toPx(), (constraints.maxWidth - TabletopLyricsMaxWidth.toPx()) / 2f + 24.dp.toPx())
                        val fade = TabletopStageFade.toPx()
                        insets.set(top = fade, bottom = 8.dp.toPx() + fade, start = side, end = side)
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
                ) {
                    // 经典照原样排，不用舞台的安全区
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        LyricsList(
                            lyrics,
                            active,
                            actions::seek,
                            Modifier.widthIn(max = TabletopLyricsMaxWidth).padding(horizontal = 24.dp),
                            loading = lyricsLoading,
                            position = position,
                            playing = playing,
                            sizing = if (wide) LyricsSizing.TabletFocus else LyricsSizing.Tablet,
                        )
                    }
                }
            }
            TabletopTop.Cover -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CoverStage(
                    cover = song.cover,
                    title = song.title,
                    trackKey = song.key,
                    playing = playing,
                    shapeChoice = coverShape,
                    pulse = pulse,
                    transitionAnimation = transitionAnimation,
                    onSkip = onSkip,
                    onDoubleTap = onFavoriteBurst,
                    // 小折叠是手机形态，迷你播放器的封面照样飞到这里
                    sharedElement = true,
                    modifier = Modifier.tabletopCoverSize(),
                )
            }
            TabletopTop.Clock -> TabletopClock(song.title, Modifier.fillMaxSize().padding(horizontal = 24.dp))
        }
    }
}

/** 上半屏的封面：上半屏去掉左右各 48dp、上下各 16dp 后的短边，最大 360dp。光晕画在边界外，外层不裁剪。 */
private fun Modifier.tabletopCoverSize(): Modifier = layout { measurable, constraints ->
    val side = minOf(
        constraints.maxWidth - 96.dp.roundToPx(),
        constraints.maxHeight - 32.dp.roundToPx(),
        TabletopCoverMax.roundToPx(),
    ).coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(side, side) { placeable.place(0, 0) }
}

/**
 * 上半屏的大时钟：和电视环境模式的时钟同一套刷新和 12/24 小时制（[rememberMinuteClock]），细体等宽数字，
 * 最大 96sp，放不下逐级缩小；下面一行是日期和正在播放的歌名。
 */
@Composable
private fun TabletopClock(title: String, modifier: Modifier) {
    val context = LocalContext.current
    val now by rememberMinuteClock()
    val use24Hour = remember(context, now) { DateFormat.is24HourFormat(context) }
    val calendar = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    val date = clockDateText(calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.DAY_OF_WEEK))
    val description = clockDescription(hour, minute, use24Hour)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            clockText(hour, minute, use24Hour),
            modifier = Modifier.semantics { contentDescription = description },
            style = MaterialTheme.typography.displayLarge.merge(TabletopClockStyle),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 40.sp, maxFontSize = 96.sp, stepSize = 4.sp),
        )
        Text(
            "$date · $title",
            Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 下半屏的歌名行：56dp 圆角小封面（上半屏是封面时不放）、歌名和歌手、收藏。切歌时文字淡出淡入。 */
@Composable
private fun TabletopMeta(song: Song, favorite: Boolean, actions: MusicActions, thumb: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(TabletopMetaHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (thumb) CoverBox(song.cover, song.title, Modifier.size(TabletopMetaHeight), RoundedCornerShape(16.dp), fontSize = 22f)
        val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        AnimatedContent(
            targetState = song,
            modifier = Modifier.weight(1f),
            transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)).using(null) },
            contentKey = { it.key },
            label = "tabletopTitle",
        ) { current ->
            Column {
                Text(
                    current.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp),
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    current.artist,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FavoriteToggle(song, favorite, actions)
    }
}

/**
 * 上下两块的摆放。上半屏：顶栏贴顶，内容占顶栏以下到上半屏底边，换源结果浮在上半屏底部正中；
 * 下半屏：歌名行、进度、按钮组、工具栏按 [tabletopControlSlots] 竖排，宽不超过 560dp、水平居中。
 * 内容（封面的光晕画在边界外）最先摆放，垫在顶栏和下半屏控件下面。
 */
private class TabletopPolicy(private val frame: TabletopFrame) : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val bar = measurables[SlotTopBar].firstOrNull()?.measure(Constraints(maxWidth = width, maxHeight = frame.topHeight))
        val barHeight = bar?.height ?: 0
        val stage = measurables[SlotStage].firstOrNull()
            ?.measure(Constraints.fixed(width, (frame.topHeight - barHeight).coerceAtLeast(0)))
        val notice = measurables[SlotNotice].firstOrNull()?.measure(Constraints(maxWidth = (width - 48.dp.roundToPx()).coerceAtLeast(0)))

        val blockWidth = minOf(width - 40.dp.roundToPx(), TabletopBlockMax.roundToPx()).coerceAtLeast(0)
        val rows = arrayOf(
            measurables[SlotMeta].firstOrNull()?.measure(Constraints.fixedWidth(blockWidth)),
            measurables[SlotSeek].firstOrNull()?.measure(Constraints.fixedWidth(blockWidth)),
            measurables[SlotGroup].firstOrNull()?.measure(Constraints(maxWidth = blockWidth)),
            measurables[SlotToolbar].firstOrNull()?.measure(Constraints(maxWidth = blockWidth)),
        )
        val slots = tabletopControlSlots(
            height = frame.bottomHeight,
            blocks = IntArray(rows.size) { rows[it]?.height ?: 0 },
            ideal = IntArray(TabletopIdealGaps.size) { TabletopIdealGaps[it].dp.roundToPx() },
            min = IntArray(TabletopMinGaps.size) { TabletopMinGaps[it].dp.roundToPx() },
        )
        val s = slots.scale
        val center = width / 2f
        return layout(width, height) {
            stage?.place(0, barHeight)
            bar?.place(0, 0)
            notice?.let { it.place((width - it.width) / 2, (frame.topHeight - it.height - 8.dp.roundToPx()).coerceAtLeast(barHeight)) }
            // 放不下时整组以顶边中点为原点等比缩小：x' = 中线 + (x − 中线)·s，y' = 下半屏顶边 + y·s
            fun Placeable.placeRow(y: Int) {
                val x = (width - this.width) / 2
                if (s >= 1f) {
                    place(x, frame.bottomTop + y)
                } else {
                    placeWithLayer((center + (x - center) * s).roundToInt(), frame.bottomTop + (y * s).roundToInt()) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = s
                        scaleY = s
                    }
                }
            }
            rows.forEachIndexed { i, row -> row?.placeRow(slots.tops[i]) }
        }
    }
}

/** 上下两块的分界（像素，本地坐标）：上半屏是 `[0, topHeight)`，下半屏从 [bottomTop] 起、高 [bottomHeight]。 */
@Immutable
internal data class TabletopFrame(val topHeight: Int, val bottomTop: Int, val bottomHeight: Int)

/**
 * 按横折痕（[split]，像素、本地坐标）分上下：上半屏到折痕上缘、下半屏从折痕下缘起（半开的折痕已各让 16dp，
 * 铰链按自己的宽度让）。竖折痕不管；量不到横折痕（不相交、太贴边）时按中线分，中线两边各让 16dp。
 */
internal fun tabletopFrame(height: Int, dp: Float, split: FoldSplit?): TabletopFrame {
    val fold = split?.takeIf { !it.vertical }
    val top: Int
    val bottom: Int
    if (fold != null) {
        top = fold.firstEnd.roundToInt().coerceIn(0, height)
        bottom = fold.secondStart.roundToInt().coerceIn(top, height)
    } else {
        val margin = (16 * dp).roundToInt()
        top = (height / 2 - margin).coerceAtLeast(0)
        bottom = (height / 2 + margin).coerceAtMost(height)
    }
    return TabletopFrame(top, bottom, height - bottom)
}

/** [tabletopControlSlots] 的结果（像素，缩放前，相对下半屏顶边）：各块的顶边和整组的缩放（1 表示不缩）。 */
internal class TabletopControlSlots(val tops: IntArray, val scale: Float)

/**
 * 下半屏的竖向分配：[blocks] 是歌名行、进度、按钮组、工具栏的实际高度，[ideal]、[min] 是它们之间的间距（比块少一个）。
 * 放得下理想间距就整组竖向居中；放不下先把间距从理想值压向最小值，整组贴着下半屏顶边（折痕已经让过安全距离）；
 * 最小间距也放不下就按最小间距排，整组等比缩小到 [height] 以内。
 */
internal fun tabletopControlSlots(height: Int, blocks: IntArray, ideal: IntArray, min: IntArray): TabletopControlSlots {
    require(ideal.size == blocks.size - 1 && min.size == ideal.size) { "间距要比块少一个" }
    val fixed = blocks.sum()
    val room = height - fixed
    val idealSum = ideal.sum()
    val minSum = min.sum()
    val gaps = IntArray(ideal.size)
    var start = 0
    var scale = 1f
    when {
        room >= idealSum -> {
            ideal.copyInto(gaps)
            start = (room - idealSum) / 2
        }
        room >= minSum -> {
            val fraction = (room - minSum).toFloat() / (idealSum - minSum).coerceAtLeast(1)
            for (i in gaps.indices) gaps[i] = min[i] + ((ideal[i] - min[i]) * fraction).toInt()
        }
        else -> {
            min.copyInto(gaps)
            scale = (height.toFloat() / (fixed + minSum).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    }
    val tops = IntArray(blocks.size)
    var y = start
    for (i in blocks.indices) {
        tops[i] = y
        y += blocks[i] + if (i < gaps.size) gaps[i] else 0
    }
    return TabletopControlSlots(tops, scale)
}

private const val SlotTopBar = 0
private const val SlotStage = 1
private const val SlotNotice = 2
private const val SlotMeta = 3
private const val SlotSeek = 4
private const val SlotGroup = 5
private const val SlotToolbar = 6

private val TabletopBarHeight = 52.dp
private val TabletopMetaHeight = 56.dp
private val TabletopToolbarHeight = 60.dp
private val TabletopBlockMax = 560.dp
private val TabletopCoverMax = 360.dp
private val TabletopLyricsMaxWidth = 760.dp
private val TabletopStageFade = 20.dp

/** 横折痕切出的哪一块比这还矮，就当没有折痕（按中线分）。 */
private val TabletopMinHalf = 160.dp

// 间距（dp）：歌名行 → 进度 → 按钮组 → 工具栏，理想值对照设计稿
private val TabletopIdealGaps = intArrayOf(16, 14, 14)
private val TabletopMinGaps = intArrayOf(8, 4, 8)

private val TabletopClockStyle = TextStyle(
    fontSize = 96.sp,
    lineHeight = 1.em,
    fontWeight = FontWeight.Light,
    letterSpacing = (-0.02).em,
    fontFeatureSettings = "tnum",
)

/** 桌面姿态上半屏舞台的正文字号（sp）。 */
private const val TABLETOP_STAGE_SP = 34f
