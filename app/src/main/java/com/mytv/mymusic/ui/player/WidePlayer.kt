package com.mytv.mymusic.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalFloatingToolbar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.SleepTimerState
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.components.WavySeekBar
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.theme.LocalAppearance
import kotlin.math.roundToInt

/**
 * 平板横屏播放页（非电视、宽 ≥ 600dp 且横屏）：左栏播放，右栏整栏歌词。
 * 左栏自上而下是形状封面、标题与收藏、音源胶囊、波浪进度、连接按钮组、浮动工具栏；左上角收起，右上角更多。
 * 工具栏里的「歌词」进入专注歌词：左栏收成竖向浮动工具栏（小圆封面、上一首、播放、下一首、歌词、队列），
 * 歌词放大到 48sp，进度条横跨底部。过渡沿慢速空间弹簧；减少动画时位置直接切换，只留淡入淡出。
 *
 * 尺寸按设计稿 1280×800 摆放，其他窗口见 [wideStackSlots]：先压间距、再缩封面；
 * 还放不下就收起音源胶囊（工具栏里仍能换源）、换小号按钮组；横屏手机这类更矮的窗口整栏等比缩小，任何高度都不重叠。
 *
 * 重组边界同手机：空闲状态只在小组件里读（拦截点按），淡出透明度和过渡进度只在图层和摆放阶段读。
 */
@Composable
internal fun WidePlayer(
    song: Song,
    playing: Boolean,
    duration: Long,
    position: State<Long>,
    favorite: Boolean,
    sleep: SleepTimerState,
    lyrics: ParsedLyrics,
    lyricsLoading: Boolean,
    active: State<Int>,
    sourceProvider: String?,
    sourceDetail: String,
    playbackMode: String,
    notice: String,
    deepLink: String,
    actions: MusicActions,
    idle: IdleState,
    lyricsMode: Boolean,
    onLyricsMode: (Boolean) -> Unit,
    onClose: () -> Unit,
    onFavoriteBurst: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTimer: () -> Unit,
    onSkip: (Int) -> Unit,
    pulse: BackdropPulse,
    coverShape: CoverShapeChoice,
) {
    val motion = MaterialTheme.motionScheme
    val reduceMotion = LocalAppearance.current.reduceMotion
    val chrome = rememberIdleAlpha(idle, motion.defaultEffectsSpec())
    val focusTransition = updateTransition(lyricsMode, label = "wideFocus")
    // 位置和大小走慢速空间弹簧，两套界面的显隐走效果弹簧；两个进度只在图层 / 摆放阶段读
    val move = focusTransition.animateFloat({ if (reduceMotion) snap() else motion.slowSpatialSpec() }, label = "focusMove") { if (it) 1f else 0f }
    val fade = focusTransition.animateFloat({ motion.defaultEffectsSpec() }, label = "focusFade") { if (it) 1f else 0f }
    // 过渡结束前两套界面都在组合里；稳定后只留一套，后台不跑另一套的波浪进度
    val showDefault = !focusTransition.targetState || !focusTransition.currentState
    val showFocus = focusTransition.targetState || focusTransition.currentState
    val defaultAlpha = remember(fade) { { 1f - fade.value } }
    val focusAlpha = remember(fade) { { fade.value } }
    val defaultChrome = remember(fade, chrome) { { chrome.value * (1f - fade.value) } }
    val focusChrome = remember(fade, chrome) { { chrome.value * fade.value } }
    val waveScale = remember(pulse) { { pulseWaveScale(pulse) } }
    val waveBpm = remember(pulse) { { pulseWaveBpm(pulse) } }
    val focusTarget = rememberUpdatedState(lyricsMode)
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        // 收不收音源胶囊、按钮组用哪一档要在组合阶段定，只能按估计的高度判断；实际摆放在测量阶段按真实高度算
        val estimate = remember(density, constraints.maxWidth, constraints.maxHeight) {
            wideEstimate(density, constraints.maxWidth, constraints.maxHeight)
        }
        val full = estimate.full
        val policy = remember(move, focusTarget, estimate) { WideStagePolicy(move, focusTarget, estimate) }
        Layout(
            contents = listOf<@Composable () -> Unit>(
                {
                    if (showDefault) {
                        WideGuard(idle, lyricsMode, Modifier.graphicsLayer { alpha = defaultChrome() }) {
                            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "收起播放页", tint = MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                },
                {
                    if (showDefault) {
                        WideGuard(idle, lyricsMode, Modifier.graphicsLayer { alpha = defaultChrome() }) {
                            MoreMenu(song, deepLink, actions, onOpenSource, onLyricsMode)
                        }
                    }
                },
                { if (showDefault) WideMeta(song, favorite, actions, lyricsMode, defaultAlpha) },
                {
                    if (showDefault && full) {
                        // 空闲时跟着控制栏一起淡出，否则底部进度条和按钮组会一直留在画面上
                        WideGuard(idle, lyricsMode, Modifier.graphicsLayer { alpha = defaultChrome() }) {
                            SourcePill(song, sourceProvider, sourceDetail, playbackMode, onOpenSource)
                        }
                    }
                },
                {
                    if (showDefault) {
                        WideGuard(idle, lyricsMode, Modifier.graphicsLayer { alpha = defaultChrome() }) {
                            WavySeekBar(position, duration, playing, actions::seek, Modifier.fillMaxWidth(), amplitudeScale = waveScale, waveBpm = waveBpm)
                        }
                    }
                },
                {
                    if (showDefault) {
                        WideGuard(idle, lyricsMode, Modifier.graphicsLayer { alpha = defaultChrome() }) {
                            TransportButtonGroup(
                                playing = playing,
                                onPrevious = { actions.transport("previous") },
                                onPlayPause = { actions.transport("play") },
                                onNext = { actions.transport("next") },
                                size = if (full) TransportGroupSize.Tablet else TransportGroupSize.Compact,
                            )
                        }
                    }
                },
                {
                    if (showDefault) {
                        WideGuard(idle, lyricsMode, Modifier.graphicsLayer { alpha = defaultChrome() }) {
                            PlayerToolbar(height = ToolbarHeight) {
                                ToolbarLyricsButton(on = false, onToggle = onLyricsMode)
                                ToolbarQueueButton(onOpenQueue)
                                ToolbarSleepButton(sleep, onOpenTimer)
                                ToolbarSourceButton(onOpenSource)
                                MoreMenu(song, deepLink, actions, onOpenSource, onLyricsMode)
                            }
                        }
                    }
                },
                { if (showFocus) FocusHeader(song, focusAlpha) },
                { if (showFocus) FocusDock(playing, actions, idle, !lyricsMode, focusChrome, onLyricsMode, onOpenQueue) },
                {
                    if (showFocus) {
                        WideGuard(null, !lyricsMode, Modifier.graphicsLayer { alpha = focusAlpha() }) {
                            WavySeekBar(position, duration, playing, actions::seek, Modifier.fillMaxWidth(), amplitudeScale = waveScale, waveBpm = waveBpm)
                        }
                    }
                },
                {
                    LyricsList(
                        lyrics,
                        active,
                        actions::seek,
                        Modifier.fillMaxSize(),
                        loading = lyricsLoading,
                        position = position,
                        playing = playing,
                        sizing = if (lyricsMode) LyricsSizing.TabletFocus else LyricsSizing.Tablet,
                    )
                },
                { WideNotice(notice) },
                {
                    CoverStage(
                        cover = song.cover,
                        title = song.title,
                        trackKey = song.key,
                        playing = playing,
                        shapeChoice = coverShape,
                        pulse = pulse,
                        collapsed = lyricsMode,
                        onSkip = onSkip,
                        onDoubleTap = onFavoriteBurst,
                        // 专注歌词里工具栏顶上的小圆就是“回到封面”按钮；图层缩放后点按范围也跟着缩到小圆
                        modifier = if (lyricsMode) {
                            Modifier
                                .clickable(interactionSource = null, indication = null, role = Role.Button) { onLyricsMode(false) }
                                .semantics { contentDescription = "回到封面" }
                        } else {
                            Modifier
                        },
                    )
                },
            ),
            modifier = Modifier.fillMaxSize(),
            measurePolicy = policy,
        )
    }
}

/**
 * 拦截点按：过渡途中正在淡出的那套界面，以及空闲时淡出的控件（唤醒的那一下不会点到看不见的按钮）。
 * [idle] 为 null 的块空闲时不淡出，也就不订阅空闲状态；只有这个小盒子随空闲状态重组。
 */
@Composable
private fun WideGuard(idle: IdleState?, blocked: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.blockTouchesWhen(blocked || idle?.idle == true)) { content() }
}

/** 歌名 34sp 粗体，两行内放不下才逐级缩小；歌手名 19sp primary 色；右侧收藏。切歌时文字淡出淡入。 */
@Composable
private fun WideMeta(song: Song, favorite: Boolean, actions: MusicActions, blocked: Boolean, alpha: () -> Float) {
    Row(
        Modifier.graphicsLayer { this.alpha = alpha() }.blockTouchesWhen(blocked),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        AnimatedContent(
            targetState = song,
            modifier = Modifier.weight(1f),
            transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)).using(null) },
            contentKey = { it.key },
            label = "wideTitle",
        ) { shown ->
            Column {
                Text(
                    shown.title,
                    style = MaterialTheme.typography.displaySmall.copy(lineHeight = 1.18.em),
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 26.sp, maxFontSize = 34.sp, stepSize = 2.sp),
                )
                Text(
                    shown.artist,
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.titleLarge.merge(ArtistStyle),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FavoriteToggle(song, favorite, actions)
    }
}

/** 专注歌词顶部的“歌名 · 歌手”。 */
@Composable
private fun FocusHeader(song: Song, alpha: () -> Float) {
    Text(
        "${song.title} · ${song.artist}",
        Modifier.graphicsLayer { this.alpha = alpha() },
        style = MaterialTheme.typography.titleMedium.merge(HeaderStyle),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 专注歌词左侧的竖向浮动工具栏：顶上空出 64dp 给小圆封面（封面节点由舞台飞到这里，整页只有一个封面），
 * 下面是上一首、72dp 播放键、下一首，分隔线后是选中的「歌词」（点了退出专注）和队列。空闲时整条淡出。
 */
@Composable
private fun FocusDock(
    playing: Boolean,
    actions: MusicActions,
    idle: IdleState,
    blocked: Boolean,
    alpha: () -> Float,
    onLyricsMode: (Boolean) -> Unit,
    onOpenQueue: () -> Unit,
) {
    WideGuard(idle, blocked, Modifier.graphicsLayer { this.alpha = alpha() }) {
        VerticalFloatingToolbar(
            expanded = true,
            // 和横向工具栏定高一样定宽：不给宽度时它比内容宽得多
            modifier = Modifier.width(DockWidth),
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                toolbarContentColor = MaterialTheme.colorScheme.onSurface,
            ),
            contentPadding = PaddingValues(horizontal = DockSidePadding, vertical = DockPadding),
        ) {
            Column(
                Modifier.width(DockWidth - DockSidePadding * 2),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DockSpacing),
            ) {
                Spacer(Modifier.size(DockCoverSize))
                DockSkipButton(Icons.Filled.SkipPrevious, "上一首") { actions.transport("previous") }
                DockPlayButton(playing) { actions.transport("play") }
                DockSkipButton(Icons.Filled.SkipNext, "下一首") { actions.transport("next") }
                Box(Modifier.size(40.dp, 1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)))
                ToolbarLyricsButton(on = true, onToggle = onLyricsMode)
                ToolbarQueueButton(onOpenQueue)
            }
        }
    }
}

@Composable
private fun DockSkipButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .tvFocusRing(CircleShape)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(28.dp)) }
    }
}

/** 竖向工具栏里的播放键：72dp 花瓣形 primary 容器，按下时轻微收缩（快速空间弹簧，只在图层里读）。 */
@Composable
private fun DockPlayButton(playing: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val cookie = MaterialShapes.Cookie12Sided.toShape()
    val scale = animateFloatAsState(if (pressed) 0.92f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "dockPlayScale")
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(72.dp)
            .graphicsLayer {
                shape = cookie
                clip = true
                scaleX = scale.value
                scaleY = scale.value
            }
            .tvFocusRing(cookie)
            .semantics {
                role = Role.Button
                contentDescription = if (playing) "暂停" else "播放"
                stateDescription = if (playing) "正在播放" else "已暂停"
            },
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Crossfade(playing, label = "dockPlayIcon") { isPlaying ->
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, Modifier.size(36.dp))
            }
        }
    }
}

/** 换源结果：浮在歌词栏底部正中 8 秒，不挤动布局。 */
@Composable
private fun WideNotice(notice: String) {
    if (notice.isBlank()) return
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)) {
        Text(
            notice,
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

/** 组合阶段按字号估出的高度（像素）。默认布局不在组合里时（专注歌词稳定后）也用它们推算封面的起点。 */
@Immutable
private data class WideEstimate(
    val full: Boolean,
    val meta: Int,
    val pill: Int,
    val seek: Int,
    val group: Int,
    val toolbar: Int,
    val dock: Int,
    val header: Int,
)

/** 标题按一行估：组合阶段量不到实际折行，两行标题在测量时由封面让出高度。 */
private fun wideEstimate(density: Density, width: Int, height: Int): WideEstimate = with(density) {
    val meta = (TitleLineHeight.toPx() + ArtistStyle.lineHeight.toPx() + 2.dp.toPx()).roundToInt()
    val pill = PillHeight.roundToPx()
    // WavySeekBar：32dp 的进度区 + 一行 labelMedium 时间
    val seek = (32.dp.toPx() + 16.sp.toPx()).roundToInt()
    val toolbar = ToolbarHeight.roundToPx()
    val coverMax = wideColumns(width, density.density).coverMax
    val full = wideFullTier(height, density.density, meta, seek, coverMax)
    val group = (if (full) TransportGroupSize.Tablet else TransportGroupSize.Compact).height.roundToPx()
    WideEstimate(full, meta, pill, seek, group, toolbar, DockHeightEstimate.roundToPx(), HeaderStyle.lineHeight.roundToPx())
}

/**
 * 平板播放页的摆放。默认布局：左栏按 [wideStackSlots] 竖排，歌词栏在右；专注歌词按 [wideFocusFrame]。
 * 歌词列表按目标模式的尺寸测量，位置在图层里从一种模式平移到另一种；封面以左上角为原点缩放平移到竖向工具栏的小圆处。
 * 过渡进度只在图层里读，切换模式只重新测量一次。专注歌词稳定后默认布局已移出组合，封面按上一次量到的高度推算起点。
 */
private class WideStagePolicy(
    private val move: State<Float>,
    private val focus: State<Boolean>,
    private val estimate: WideEstimate,
) : MultiContentMeasurePolicy {
    private var lastMeta = estimate.meta
    private var lastSeek = estimate.seek
    private var lastGroup = estimate.group
    private var lastToolbar = estimate.toolbar

    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val dp = density
        val focused = focus.value
        val loose = Constraints(maxWidth = width, maxHeight = height)
        val cols = wideColumns(width, dp)

        val close = measurables[SlotClose].firstOrNull()?.measure(loose)
        val more = measurables[SlotMore].firstOrNull()?.measure(loose)
        val pill = measurables[SlotPill].firstOrNull()?.measure(loose)
        val group = measurables[SlotGroup].firstOrNull()?.measure(loose)
        val toolbar = measurables[SlotToolbar].firstOrNull()?.measure(loose)
        group?.let { lastGroup = it.height }
        toolbar?.let { lastToolbar = it.height }

        val full = estimate.full
        fun blocks() = if (full) {
            intArrayOf(lastMeta, pill?.height ?: estimate.pill, lastSeek, lastGroup, lastToolbar)
        } else {
            intArrayOf(lastMeta, lastSeek, lastGroup, lastToolbar)
        }
        // 标题和进度条与封面同宽、左边对齐。标题折几行取决于宽度，宽度又取决于封面：先按上次量到的标题高度定封面，
        // 再按封面宽度量标题，最后用实际高度重排一次（只有标题行数变了封面才会再变，差一点也不重叠）
        val planned = wideStackSlots(height, dp, blocks(), cols.coverMax, full)
        val block = wideBlockWidth(planned.cover, cols.block, dp)
        val meta = measurables[SlotMeta].firstOrNull()?.measure(Constraints.fixedWidth(block + 8.dp.roundToPx()))
        val seek = measurables[SlotSeek].firstOrNull()?.measure(Constraints.fixedWidth(block))
        meta?.let { lastMeta = it.height }
        seek?.let { lastSeek = it.height }
        val stack = wideStackSlots(height, dp, blocks(), cols.coverMax, full)
        val s = stack.scale
        val center = cols.left / 2f
        val blockX = (cols.left - block) / 2
        val coverSize = stack.cover
        val coverX = center - coverSize * s / 2f
        val coverY = stack.tops[0] * s

        // 竖向工具栏按自然高度测量，太矮时由摆放阶段等比缩小
        val dock = measurables[SlotDock].firstOrNull()?.measure(Constraints(maxWidth = width))
        val dockWidth = dock?.width ?: DockWidth.roundToPx()
        val frame = wideFocusFrame(width, height, dp, dockWidth, dock?.height ?: estimate.dock, estimate.header, lastSeek)
        val header = measurables[SlotHeader].firstOrNull()?.measure(Constraints(maxWidth = frame.lyricsWidth))
        val focusSeek = measurables[SlotFocusSeek].firstOrNull()?.measure(Constraints.fixedWidth(frame.seekWidth))
        val miniSize = DockCoverSize.toPx() * frame.dockScale
        val miniX = frame.dockX + (dockWidth - DockCoverSize.toPx()) / 2f * frame.dockScale
        val miniY = frame.dockY + DockPadding.toPx() * frame.dockScale

        // 歌词栏直接按目标模式的尺寸排版（只在切换时重排一次），位置在图层里随过渡平移
        val lyricsTop = DefaultLyricsTop.roundToPx()
        val defaultLyricsHeight = (height - lyricsTop - DefaultLyricsBottom.roundToPx()).coerceAtLeast(0)
        val lyricsX = if (focused) frame.contentX else cols.lyricsX
        val lyricsY = if (focused) frame.lyricsY else lyricsTop
        val lyricsWidth = if (focused) frame.lyricsWidth else cols.lyricsWidth
        val lyricsHeight = if (focused) frame.lyricsHeight else defaultLyricsHeight
        val lyrics = measurables[SlotLyrics].first().measure(Constraints.fixed(lyricsWidth, lyricsHeight))
        val notice = measurables[SlotNotice].firstOrNull()?.measure(Constraints(maxWidth = lyricsWidth))
        val cover = measurables[SlotCover].first().measure(Constraints.fixed(coverSize, coverSize))

        val slide = SlideDistance.toPx()
        val margin = CornerMargin.roundToPx()
        return layout(width, height) {
            // 左栏缩放以左栏顶边中点为原点：x' = 中线 + (x − 中线)·s，y' = y·s；退出默认布局时向左让开
            fun Placeable.placeInStack(x: Int, y: Int) {
                placeWithLayer((center + (x - center) * s).roundToInt(), (y * s).roundToInt()) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = s
                    scaleY = s
                    translationX = -move.value * slide
                }
            }
            lyrics.placeWithLayer(lyricsX, lyricsY) {
                val t = move.value
                translationX = lerp(cols.lyricsX.toFloat(), frame.contentX.toFloat(), t) - lyricsX
                translationY = lerp(lyricsTop.toFloat(), frame.lyricsY.toFloat(), t) - lyricsY
            }
            dock?.placeWithLayer(frame.dockX, frame.dockY) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = frame.dockScale
                scaleY = frame.dockScale
                translationX = -(1f - move.value) * slide
            }
            header?.place(frame.contentX, frame.headerY)
            focusSeek?.placeWithLayer(frame.contentX, frame.seekY) { translationY = (1f - move.value) * slide / 2f }
            // 封面压在竖向工具栏上面，但在标题等默认控件下面：光晕画在封面边界外，不能盖住歌名
            val coverLeft = coverX.roundToInt()
            val coverTop = coverY.roundToInt()
            cover.placeWithLayer(coverLeft, coverTop) {
                val t = move.value
                transformOrigin = TransformOrigin(0f, 0f)
                val scale = lerp(coverSize * s, miniSize, t) / coverSize.coerceAtLeast(1)
                scaleX = scale
                scaleY = scale
                translationX = lerp(coverX, miniX, t) - coverLeft
                translationY = lerp(coverY, miniY, t) - coverTop
            }
            var row = 1
            meta?.placeInStack(blockX, stack.tops[row])
            row++
            if (full) {
                pill?.placeInStack(blockX, stack.tops[row])
                row++
            }
            seek?.placeInStack(blockX, stack.tops[row++])
            group?.let { it.placeInStack((cols.left - it.width) / 2, stack.tops[row]) }
            row++
            toolbar?.let { it.placeInStack((cols.left - it.width) / 2, stack.tops[row]) }
            close?.place(margin, CornerTop.roundToPx())
            more?.let { it.place(width - margin - it.width, CornerTop.roundToPx()) }
            notice?.let { it.place(lyricsX + (lyricsWidth - it.width) / 2, lyricsY + lyricsHeight - it.height - 8.dp.roundToPx()) }
        }
    }
}

/**
 * 标题行和进度条的宽（像素）：跟封面同宽，封面缩小时一起变窄，左边和封面对齐；
 * 不窄于 200dp（小号按钮组的宽，再窄标题放不下几个字），不宽于 [blockMax]。
 */
internal fun wideBlockWidth(cover: Int, blockMax: Int, dp: Float): Int =
    cover.coerceIn((200 * dp).roundToInt().coerceAtMost(blockMax), blockMax)

/** 默认布局的横向分配（像素）：左栏宽、标题和进度的最大宽、封面上限，以及歌词栏的位置和宽度。 */
internal class WideColumns(val left: Int, val block: Int, val coverMax: Int, val lyricsX: Int, val lyricsWidth: Int)

/**
 * 默认布局的横向分配，[dp] 是每 dp 的像素数。设计稿 1280dp 宽：左栏 560、歌词栏 x 600 宽 616、右边留 64 给右上角的更多按钮。
 * 左栏按宽度的 43.75% 取，限制在 340–560dp；封面不超过 320dp，并且离左栏两边各留 72dp：
 * 封面顶边和左上角的收起按钮在同一高度，窄窗口里不能压到按钮上。歌词栏最宽 760dp，再宽一行字太长。
 */
internal fun wideColumns(width: Int, dp: Float): WideColumns {
    val left = (width * 0.4375f).coerceIn(340 * dp, 560 * dp).roundToInt().coerceAtMost(width)
    val block = minOf(320 * dp, left - 48 * dp).roundToInt().coerceAtLeast(0)
    val coverMax = minOf(320 * dp, left - 144 * dp).roundToInt().coerceAtLeast(0)
    val lyricsX = (left + 40 * dp).roundToInt()
    val lyricsWidth = (width - lyricsX - 64 * dp).coerceIn(0f, 760 * dp).roundToInt()
    return WideColumns(left, block, coverMax, lyricsX, lyricsWidth)
}

/** [wideStackSlots] 的结果（像素，缩放前）：封面边长、封面和下面各块的顶边，以及整栏的缩放（1 表示不缩）。 */
internal class WideStackSlots(val cover: Int, val tops: IntArray, val scale: Float)

/**
 * 左栏的竖向分配（像素）：封面在最上面，下面依次是 [blocks]（标题、[full] 时的音源胶囊、进度、按钮组、工具栏的实际高度）。
 * 先保证封面 [coverMax]，空间不够先把间距从设计稿的值压向最小值，再缩封面，最小 [WideCoverFloor]；
 * 还放不下（横屏手机）就按最小封面和最小间距排，整栏等比缩小到 [height] 以内。空间有富余时平分到顶部和底部，整栏竖向居中。
 */
internal fun wideStackSlots(height: Int, dp: Float, blocks: IntArray, coverMax: Int, full: Boolean): WideStackSlots {
    val ideal = (if (full) FullIdealGaps else ShortIdealGaps).toPx(dp)
    val min = (if (full) FullMinGaps else ShortMinGaps).toPx(dp)
    require(ideal.size == blocks.size + 2) { "间距要比块多两个（顶和底）" }
    val fixed = blocks.sum()
    val room = height - fixed
    val idealSum = ideal.sum()
    val minSum = min.sum()
    val floor = (WideCoverFloor * dp).roundToInt().coerceAtMost(coverMax)
    val gaps = IntArray(ideal.size)
    val cover: Int
    var scale = 1f
    when {
        room >= coverMax + idealSum -> {
            cover = coverMax
            ideal.copyInto(gaps)
            val slack = room - coverMax - idealSum
            gaps[0] += slack / 2
            gaps[gaps.lastIndex] += slack - slack / 2
        }
        room >= coverMax + minSum -> {
            cover = coverMax
            val fraction = (room - coverMax - minSum).toFloat() / (idealSum - minSum).coerceAtLeast(1)
            for (i in gaps.indices) gaps[i] = min[i] + ((ideal[i] - min[i]) * fraction).toInt()
        }
        room >= floor + minSum -> {
            cover = room - minSum
            min.copyInto(gaps)
        }
        else -> {
            cover = floor
            min.copyInto(gaps)
            scale = height.toFloat() / (fixed + floor + minSum).coerceAtLeast(1)
        }
    }
    val tops = IntArray(blocks.size + 1)
    var y = gaps[0]
    tops[0] = y
    y += cover + gaps[1]
    for (i in blocks.indices) {
        tops[i + 1] = y
        y += blocks[i] + gaps[i + 2]
    }
    return WideStackSlots(cover, tops, scale)
}

/**
 * 是否按完整的左栏排（音源胶囊 + 320×88 按钮组）：最小间距下封面还能有 [WideCoverComfort]（不超过 [coverMax]）。
 * 放不下就收起胶囊、换 200×56 的小号按钮组，把高度让给封面。[meta]、[seek] 是标题区和进度条的高度（像素）。
 */
internal fun wideFullTier(height: Int, dp: Float, meta: Int, seek: Int, coverMax: Int): Boolean {
    val blocks = meta + seek + ((PillHeight.value + TransportGroupSize.Tablet.height.value + ToolbarHeight.value) * dp).roundToInt()
    val comfort = (WideCoverComfort * dp).roundToInt().coerceAtMost(coverMax)
    return height - blocks - FullMinGaps.toPx(dp).sum() >= comfort
}

/** 专注歌词的摆放（像素）：竖向工具栏的位置和缩放，顶部标题行、歌词区和底部进度条。 */
internal class WideFocusFrame(
    val dockX: Int,
    val dockY: Int,
    val dockScale: Float,
    val contentX: Int,
    val headerY: Int,
    val lyricsY: Int,
    val lyricsWidth: Int,
    val lyricsHeight: Int,
    val seekY: Int,
    val seekWidth: Int,
)

/**
 * 设计稿 1280×800（状态栏下 772dp 高）：工具栏 x 32、宽 88；标题行 x 180、距顶 12；歌词宽 1000、高 610；
 * 进度条宽 980、底边留 40。工具栏的中心放在进度条以上区域 45% 的高度，窗口太矮放不下就等比缩小。
 */
internal fun wideFocusFrame(
    width: Int,
    height: Int,
    dp: Float,
    dockWidth: Int,
    dockHeight: Int,
    headerHeight: Int,
    seekHeight: Int,
): WideFocusFrame {
    val dockX = (width * 0.025f).coerceIn(16 * dp, 32 * dp).roundToInt()
    val contentX = (dockX + dockWidth + (width * 0.047f).coerceIn(32 * dp, 60 * dp)).roundToInt()
    val right = (width * 0.078f).coerceIn(32 * dp, 100 * dp)
    val lyricsWidth = (width - contentX - right).coerceIn(0f, 1100 * dp).roundToInt()
    val headerY = (12 * dp).roundToInt()
    val lyricsY = headerY + headerHeight + (22 * dp).roundToInt()
    val seekY = (height - seekHeight - (height * 0.052f).coerceIn(12 * dp, 40 * dp)).roundToInt()
    val lyricsHeight = (seekY - (18 * dp).roundToInt() - lyricsY).coerceAtLeast(0)
    val margin = (12 * dp).roundToInt()
    val dockScale = ((height - margin * 2).toFloat() / dockHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
    val shown = (dockHeight * dockScale).roundToInt()
    val dockY = (seekY * 0.45f - shown / 2f).roundToInt().coerceIn(margin, (height - margin - shown).coerceAtLeast(margin))
    val seekWidth = (lyricsWidth - 20 * dp).roundToInt().coerceAtLeast(0)
    return WideFocusFrame(dockX, dockY, dockScale, contentX, headerY, lyricsY, lyricsWidth, lyricsHeight, seekY, seekWidth)
}

private fun IntArray.toPx(dp: Float): IntArray = IntArray(size) { (this[it] * dp).roundToInt() }

private const val SlotClose = 0
private const val SlotMore = 1
private const val SlotMeta = 2
private const val SlotPill = 3
private const val SlotSeek = 4
private const val SlotGroup = 5
private const val SlotToolbar = 6
private const val SlotHeader = 7
private const val SlotDock = 8
private const val SlotFocusSeek = 9
private const val SlotLyrics = 10
private const val SlotNotice = 11
private const val SlotCover = 12

/** 封面不再缩小的边长（dp）；再矮就整栏等比缩小。 */
internal const val WideCoverFloor = 128f

/** 完整左栏要求的最小封面（dp）；低于它就收起音源胶囊、换小号按钮组。 */
internal const val WideCoverComfort = 240f

// 间距（dp）：顶 → 封面 → 标题 → [胶囊] → 进度 → 按钮组 → 工具栏 → 底。理想值对照设计稿，顶部 44 与收起按钮同高
private val FullIdealGaps = intArrayOf(44, 18, 10, 12, 12, 22, 30)
private val FullMinGaps = intArrayOf(12, 12, 8, 8, 6, 12, 12)
private val ShortIdealGaps = intArrayOf(44, 18, 16, 12, 22, 30)
private val ShortMinGaps = intArrayOf(12, 12, 8, 6, 12, 12)

private val ToolbarHeight = 56.dp
private val PillHeight = 32.dp
private val CornerMargin = 16.dp
private val CornerTop = 6.dp
private val DefaultLyricsTop = 28.dp
private val DefaultLyricsBottom = 56.dp
private val SlideDistance = 32.dp
private val DockWidth = 88.dp
private val DockPadding = 12.dp
private val DockSidePadding = 8.dp
private val DockSpacing = 10.dp
private val DockCoverSize = 64.dp

/** 竖向工具栏的自然高度：64 封面 + 48 + 72 + 48 + 1 分隔 + 48 + 48，六个 10dp 间距，上下各 12。 */
private val DockHeightEstimate = 413.dp

private val TitleLineHeight = (34 * 1.18f).sp
private val ArtistStyle = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
private val HeaderStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
