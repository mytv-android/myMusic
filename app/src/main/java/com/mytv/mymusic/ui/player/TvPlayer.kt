package com.mytv.mymusic.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.SleepTimerState
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.components.WavySeekBar
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.formatTime
import com.mytv.mymusic.ui.theme.LocalAppearance
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 电视播放页（按 960×540dp 设计稿）：左边形状封面和音源胶囊，右边歌名和整栏歌词，底部一条浮动控制条。
 * 空闲 4 秒进入环境模式：控制条和胶囊淡出，封面放大一点，歌词向下扩展、当前行放大，底部只留一条细进度，
 * 右上角显示时钟（[ambientClock]，设置里可关）。
 *
 * 控制条分两行：上行按钮组 + 定时 / 收藏 / 队列 / 音源，下行整宽的波浪进度。进度条要吃掉左右键快退快进，
 * 按设计稿把它夹在按钮组和四个按钮中间的话，焦点一到进度条就再也走不到右边的按钮；分两行后用上下键换行。
 *
 * 重组边界：环境模式只在进出时重组一次；过渡进度只在测量、摆放和图层里读，播放进度只在小组件和绘制阶段读。
 * 过渡中歌词栏逐帧变高、字号逐帧变大，只重新测量歌词列表，不重组（见 [LyricsList]）。
 * 环境模式里每帧在动的只有背景、封面旋转和当前行的逐字填色，三者共用 30 帧节拍（[TvFrameGate]）。
 */
@Composable
internal fun TvPlayer(
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
    actions: MusicActions,
    idle: IdleState,
    pulse: BackdropPulse,
    coverShape: CoverShapeChoice,
    ambientClock: Boolean,
    onOpenQueue: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenSource: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val reduceMotion = LocalAppearance.current.reduceMotion
    val playFocus = remember { FocusRequester() }
    val idleFocus = remember { FocusRequester() }
    val dockRow = remember { FocusRequester() }
    val ambient = idle.idle
    // 环境模式下焦点交给一个不可见的占位节点，保证第一下按键能被唤醒逻辑收到；唤醒后回到播放键
    LaunchedEffect(ambient) { runCatching { if (ambient) idleFocus.requestFocus() else playFocus.requestFocus() } }
    // 只在环境模式下可聚焦，否则在最左侧控件或歌词上按左键，焦点会跳到这个看不见的节点
    Box(Modifier.size(1.dp).focusRequester(idleFocus).focusProperties { canFocus = ambient }.focusable())

    // 位置和大小走慢速空间弹簧，淡入淡出走默认效果弹簧；减少动画时位置直接到位，只保留淡入淡出
    val stage = animateFloatAsState(if (ambient) 1f else 0f, if (reduceMotion) snap() else motion.slowSpatialSpec(), label = "tvStage")
    val fade = animateFloatAsState(if (ambient) 1f else 0f, motion.defaultEffectsSpec(), label = "tvFade")
    val chromeAlpha = remember(fade) { { 1f - fade.value } }
    val waveScale = remember(pulse) { { pulseWaveScale(pulse) } }
    val policy = remember(stage, ambient, ambientClock, pulse) { TvStagePolicy(stage, ambient, ambientClock, pulse) }

    Layout(
        contents = listOf<@Composable () -> Unit>(
            {
                CoverStage(
                    cover = song.cover,
                    title = song.title,
                    trackKey = song.key,
                    playing = playing,
                    shapeChoice = coverShape,
                    pulse = pulse,
                )
            },
            { TvMeta(song, stage, ambient) },
            {
                // 只展示：换源走控制条上的按钮，这里不能拿遥控器焦点，否则多出一个和右下角重复的焦点位
                SourcePill(
                    song,
                    sourceProvider,
                    sourceDetail,
                    playbackMode,
                    onOpenSource,
                    Modifier.graphicsLayer { alpha = chromeAlpha() }.focusProperties { canFocus = false },
                )
            },
            {
                LyricsList(
                    lyrics,
                    active,
                    actions::seek,
                    modifier = Modifier
                        // 歌词行能聚焦（确认键从这一句播放），上下键在行间走，往下要走完整首才回到控制条；
                        // 左边没有别的焦点位，按左键直接回控制条上刚才那个按钮
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                            if (!dockRow.restoreFocusedChild()) playFocus.requestFocus()
                            true
                        },
                    loading = lyricsLoading,
                    position = position,
                    playing = playing,
                    sizing = if (ambient) LyricsSizing.TvAmbient else LyricsSizing.Tv,
                )
            },
            { TvNotice(notice, chromeAlpha) },
            {
                TvDock(
                    song = song,
                    playing = playing,
                    ambient = ambient,
                    duration = duration,
                    position = position,
                    favorite = favorite,
                    sleep = sleep,
                    actions = actions,
                    playFocus = playFocus,
                    rowFocus = dockRow,
                    waveScale = waveScale,
                    alpha = chromeAlpha,
                    onOpenQueue = onOpenQueue,
                    onOpenTimer = onOpenTimer,
                    onOpenSource = onOpenSource,
                )
            },
            { TvThinProgress(position, duration, fade) },
            { if (ambientClock) TvClock(fade) },
        ),
        modifier = Modifier.fillMaxSize(),
        measurePolicy = policy,
    )
}

/** 歌名 34sp 粗体、歌手 20sp primary；环境模式缩到 30 / 19sp（图层缩放，过渡中不重新排版）。切歌时整块淡出淡入。 */
@Composable
private fun TvMeta(song: Song, stage: State<Float>, ambient: Boolean) {
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val titleStyle = MaterialTheme.typography.displaySmall.merge(TitleStyle)
    val artistStyle = MaterialTheme.typography.titleLarge.merge(ArtistStyle)
    AnimatedContent(
        targetState = song,
        transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)).using(null) },
        contentKey = { it.key },
        label = "tvTitle",
    ) { shown ->
        Layout(
            content = {
                Text(
                    shown.title,
                    style = titleStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    shown.artist,
                    style = artistStyle,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        ) { measurables, constraints ->
            // 排版宽度按目标模式定（只在进出环境模式时变一次），过渡中只改缩放和摆放
            val titleTarget = if (ambient) TitleAmbientScale else 1f
            val artistTarget = if (ambient) ArtistAmbientScale else 1f
            val title = measurables[0].measure(Constraints(maxWidth = (constraints.maxWidth / titleTarget).roundToInt()))
            val artist = measurables[1].measure(Constraints(maxWidth = (constraints.maxWidth / artistTarget).roundToInt()))
            val p = stage.value
            val titleScale = lerp(1f, TitleAmbientScale, p)
            val artistScale = lerp(1f, ArtistAmbientScale, p)
            val artistY = (title.height * titleScale + 4.dp.toPx()).roundToInt()
            val width = max(title.width * titleScale, artist.width * artistScale).roundToInt()
            layout(width, artistY + (artist.height * artistScale).roundToInt()) {
                title.placeWithLayer(0, 0) {
                    scaleX = titleScale
                    scaleY = titleScale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                artist.placeWithLayer(0, artistY) {
                    scaleX = artistScale
                    scaleY = artistScale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

/**
 * 浮动控制条：surfaceContainerHighest 90%、38dp 圆角。上行按钮组和四个圆形按钮，下行进度条和时间。
 * 环境模式下只是淡出（[alpha]），不移出组合：焦点占位和唤醒逻辑依赖播放键一直在。
 */
@Composable
private fun TvDock(
    song: Song,
    playing: Boolean,
    ambient: Boolean,
    duration: Long,
    position: State<Long>,
    favorite: Boolean,
    sleep: SleepTimerState,
    actions: MusicActions,
    playFocus: FocusRequester,
    rowFocus: FocusRequester,
    waveScale: () -> Float,
    alpha: () -> Float,
    onOpenQueue: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenSource: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha() }
            .blockTouchesWhen(ambient)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f), RoundedCornerShape(DockCorner))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .focusRequester(rowFocus)
                .focusProperties {
                    // 从进度条按上键回到刚才离开的那个按钮，而不是按几何位置挑一个；
                    // 程序请求（初始焦点、唤醒回到播放键）方向是 Enter，不改道
                    onEnter = { if (requestedFocusDirection != FocusDirection.Enter) rowFocus.restoreFocusedChild() }
                    onExit = { rowFocus.saveFocusedChild() }
                }
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButtonGroup(
                playing = playing,
                onPrevious = { actions.transport("previous") },
                onPlayPause = { actions.transport("play") },
                onNext = { actions.transport("next") },
                size = TransportGroupSize.Tv,
                playFocusRequester = playFocus,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DockAction(Icons.Filled.Bedtime, if (sleep.active) "定时 ${sleepLabel(sleep)}" else "定时关闭", onOpenTimer, sleep.active)
                DockAction(
                    if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    "收藏",
                    { actions.favorite(song) },
                    favorite,
                    state = if (favorite) "已收藏" else "未收藏",
                )
                DockAction(Icons.AutoMirrored.Filled.QueueMusic, "播放队列", onOpenQueue)
                DockAction(Icons.Filled.SwapHoriz, "换音源", onOpenSource)
            }
        }
        // 时间放在进度条两侧而不是下方：两行的控制条已经比设计稿高，再多一行会压到封面下的音源胶囊
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ElapsedTime(position)
            WavySeekBar(
                position = position,
                duration = duration,
                // 环境模式下控制条看不见，波浪不再起伏
                playing = playing && !ambient,
                onSeek = actions::seek,
                modifier = Modifier.weight(1f).tvFocusRing(RoundedCornerShape(16.dp), scale = 1f),
                showTimes = false,
                amplitudeScale = waveScale,
            )
            Text(formatTime(duration), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 已播放时间：按秒重组，只有这个小组件读进度。 */
@Composable
private fun ElapsedTime(position: State<Long>) {
    val seconds by remember(position) { derivedStateOf { position.value / 1_000L } }
    Text(formatTime(seconds * 1_000L), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 控制条上的圆形按钮：48dp，未开是 12% onSurface 底，开着（定时中、已收藏）是 primaryContainer。 */
@Composable
private fun DockAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    on: Boolean = false,
    state: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .tvFocusRing(CircleShape)
            .semantics {
                role = Role.Button
                state?.let { stateDescription = it }
            },
        shape = CircleShape,
        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label) }
    }
}

/** 换源结果：浮在控制条上方 8 秒，不挤动歌词；环境模式下随控制条淡出。 */
@Composable
private fun TvNotice(notice: String, alpha: () -> Float) {
    if (notice.isBlank()) return
    Surface(
        Modifier.graphicsLayer { this.alpha = alpha() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
    ) {
        Text(
            notice,
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 环境模式底部的细进度线（3dp）。只在环境模式（含淡入淡出途中）组合，进度只在绘制阶段读。 */
@Composable
private fun TvThinProgress(position: State<Long>, duration: Long, fade: State<Float>) {
    val shown by remember(fade) { derivedStateOf { fade.value > 0.001f } }
    if (!shown) return
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val fill = MaterialTheme.colorScheme.primary
    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(track, cornerRadius = radius)
                val fraction = if (duration > 0) (position.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
                drawRoundRect(fill, size = Size(size.width * fraction, size.height), cornerRadius = radius)
            },
    )
}

/** 环境模式右上角的时钟，随环境模式淡入淡出；不在环境模式时不组合，不挂系统时间广播。 */
@Composable
private fun TvClock(fade: State<Float>) {
    val shown by remember(fade) { derivedStateOf { fade.value > 0.001f } }
    if (shown) AmbientClock(Modifier.graphicsLayer { alpha = fade.value })
}

/**
 * 电视播放页的摆放：过渡进度 [stage]（0 控制条显示，1 环境模式）只在测量和摆放阶段读，逐帧不重组。
 * 封面按环境模式的大小测量一次，默认模式用图层缩小，过渡中不重新排版封面；歌词栏逐帧改高度（只重新测量列表）。
 * 封面中心按实际位置写给 [pulse]，背景的鼓点光环从这里散开，任何屏幕尺寸都对得上。
 */
private class TvStagePolicy(
    private val stage: State<Float>,
    private val ambient: Boolean,
    private val clock: Boolean,
    private val pulse: BackdropPulse,
) : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val p = stage.value
        val unit = density
        val side = (TvDp.SideInset * unit).roundToInt()
        val dock = measurables[SlotDock].first().measure(Constraints.fixedWidth((width - side * 2).coerceAtLeast(0)))
        val dockTop = height - (TvDp.DockBottom * unit).roundToInt() - dock.height
        val pill = measurables[SlotPill].firstOrNull()?.measure(Constraints(maxWidth = width))
        val frame = tvStageFrame(width.toFloat(), height.toFloat(), dockTop.toFloat(), (pill?.height ?: 0).toFloat(), clock, unit)

        val coverSide = frame.coverAmbient.roundToInt()
        val cover = measurables[SlotCover].first().measure(Constraints.fixed(coverSide, coverSide))
        val meta = measurables[SlotMeta].firstOrNull()?.measure(Constraints(maxWidth = frame.titleWidth(ambient).roundToInt().coerceAtLeast(0)))
        val metaY = TvDp.MetaTop * unit
        val textX = frame.textX(p)
        val lyricsTop = frame.lyricsTop(p, metaY + (meta?.height ?: 0))
        val lyricsHeight = (frame.lyricsBottom(p) - lyricsTop).roundToInt().coerceAtLeast(0)
        // 歌词行自带 8dp 横向内边距，整栏往左挪 8dp，字和歌名左对齐
        val rowPadding = (8 * unit).roundToInt()
        val lyrics = measurables[SlotLyrics].first()
            .measure(Constraints.fixed((frame.lyricsWidth.roundToInt() + rowPadding * 2).coerceAtLeast(0), lyricsHeight))
        val notice = measurables[SlotNotice].firstOrNull()?.measure(Constraints(maxWidth = frame.lyricsWidth.roundToInt().coerceAtLeast(0)))
        val thin = measurables[SlotThin].firstOrNull()
            ?.measure(Constraints.fixed((width - side * 2).coerceAtLeast(0), (TvDp.ThinHeight * unit).roundToInt()))
        val clockText = measurables[SlotClock].firstOrNull()?.measure(Constraints())

        return layout(width, height) {
            pulse.rippleOrigin = frame.coverCenter(p, width.toFloat(), height.toFloat())
            val coverScale = frame.coverSize(p) / coverSide.coerceAtLeast(1)
            cover.placeWithLayer((TvDp.CoverStart * unit).roundToInt(), frame.coverY(p).roundToInt()) {
                scaleX = coverScale
                scaleY = coverScale
                transformOrigin = TransformOrigin(0f, 0f)
            }
            meta?.place(textX.roundToInt(), metaY.roundToInt())
            pill?.place((TvDp.CoverStart * unit).roundToInt(), frame.pillY.roundToInt())
            lyrics.place(textX.roundToInt() - rowPadding, lyricsTop.roundToInt())
            thin?.place(side, height - (TvDp.DockBottom * unit).roundToInt())
            clockText?.place(width - (TvDp.ClockEnd * unit).roundToInt() - clockText.width, (TvDp.ClockTop * unit).roundToInt())
            notice?.place(frame.textX(0f).roundToInt(), dockTop - (TvDp.NoticeGap * unit).roundToInt() - notice.height)
            // 最后摆放：控制条盖在歌词栏和光晕上面
            dock.place(side, dockTop)
        }
    }
}

/**
 * [tvStageFrame] 的结果（px）。默认模式和环境模式的端点在这里定好，过渡中的值按进度 p 插值。
 * 位置按设计稿从左上（封面、文字）、右上（时钟）、底边（控制条、细进度）量；屏幕更大时歌词栏变宽变高。
 */
internal class TvStageFrame(
    /** 默认模式封面边长：设计稿 280，屏幕矮时缩小，保证音源胶囊不压到控制条。 */
    val coverDefault: Float,
    /** 环境模式封面边长：比默认大 20dp（设计稿 300）。 */
    val coverAmbient: Float,
    /** 歌词栏可见文字宽度：两种模式相同，进出环境模式不重新折行。 */
    val lyricsWidth: Float,
    val pillY: Float,
    private val titleDefaultWidth: Float,
    private val titleAmbientWidth: Float,
    private val lyricsDefaultBottom: Float,
    private val lyricsAmbientBottom: Float,
    private val unit: Float,
) {
    fun coverSize(p: Float): Float = lerp(coverDefault, coverAmbient, p)

    fun coverY(p: Float): Float = lerp(TvDp.CoverTop, TvDp.CoverTopAmbient, p) * unit

    /** 歌名和歌词栏的左边：紧跟封面右边 48dp，封面放大时一起右移。 */
    fun textX(p: Float): Float = TvDp.CoverStart * unit + coverSize(p) + TvDp.TextGap * unit

    /** 歌名区的视觉宽度上限；环境模式右边给时钟留位（时钟关了就不留）。 */
    fun titleWidth(ambient: Boolean): Float = if (ambient) titleAmbientWidth else titleDefaultWidth

    /** 歌词栏顶边：设计稿位置，歌名折成两行时往下让。 */
    fun lyricsTop(p: Float, metaBottom: Float): Float =
        max(lerp(TvDp.LyricsTop, TvDp.LyricsTopAmbient, p) * unit, metaBottom + TvDp.MetaGap * unit)

    fun lyricsBottom(p: Float): Float = lerp(lyricsDefaultBottom, lyricsAmbientBottom, p)

    /** 封面中心占屏幕宽高的比例（背景光环的散开点，摆放时写给 [BackdropPulse.rippleOrigin]）。 */
    fun coverCenter(p: Float, width: Float, height: Float): Offset {
        val half = coverSize(p) / 2f
        return Offset((TvDp.CoverStart * unit + half) / width, (coverY(p) + half) / height)
    }
}

/**
 * 纯函数，便于测试：[width] × [height] 是屏幕，[dockTop] 是控制条顶边，[pillHeight] 是音源胶囊高度，[unit] 是每 dp 的像素数。
 */
internal fun tvStageFrame(width: Float, height: Float, dockTop: Float, pillHeight: Float, clock: Boolean, unit: Float): TvStageFrame {
    val room = dockTop - TvDp.PillDockGap * unit - pillHeight - TvDp.PillGap * unit - TvDp.CoverTop * unit
    val coverDefault = room.coerceIn(TvDp.CoverMin * unit, TvDp.CoverMax * unit)
    val coverAmbient = coverDefault + TvDp.CoverGrow * unit
    val textDefault = TvDp.CoverStart * unit + coverDefault + TvDp.TextGap * unit
    val textAmbient = TvDp.CoverStart * unit + coverAmbient + TvDp.TextGap * unit
    val titleAmbientEnd = if (clock) TvDp.TitleClockEnd else TvDp.TitleEnd
    return TvStageFrame(
        coverDefault = coverDefault,
        coverAmbient = coverAmbient,
        lyricsWidth = (width - textDefault - TvDp.LyricsEnd * unit).coerceAtLeast(0f),
        pillY = TvDp.CoverTop * unit + coverDefault + TvDp.PillGap * unit,
        titleDefaultWidth = (width - textDefault - TvDp.TitleEnd * unit).coerceAtLeast(0f),
        titleAmbientWidth = (width - textAmbient - titleAmbientEnd * unit).coerceAtLeast(0f),
        lyricsDefaultBottom = dockTop - TvDp.LyricsDockGap * unit,
        lyricsAmbientBottom = height - TvDp.LyricsAmbientBottom * unit,
        unit = unit,
    )
}

/** 设计稿（960×540dp）里的尺寸，单位 dp。 */
internal object TvDp {
    const val SideInset = 48f
    const val DockBottom = 24f
    const val CoverStart = 72f
    const val CoverTop = 50f
    const val CoverTopAmbient = 60f
    const val CoverMax = 280f
    const val CoverMin = 160f
    const val CoverGrow = 20f
    const val TextGap = 48f
    const val MetaTop = 46f
    const val MetaGap = 12f
    const val TitleEnd = 56f
    const val TitleClockEnd = 150f
    const val LyricsTop = 136f
    const val LyricsTopAmbient = 128f
    const val LyricsEnd = 60f
    const val LyricsDockGap = 12f
    const val LyricsAmbientBottom = 42f
    const val PillGap = 20f
    const val PillDockGap = 16f
    const val NoticeGap = 10f
    const val ThinHeight = 3f
    const val ClockEnd = 56f
    const val ClockTop = 40f
}

private const val SlotCover = 0
private const val SlotMeta = 1
private const val SlotPill = 2
private const val SlotLyrics = 3
private const val SlotNotice = 4
private const val SlotDock = 5
private const val SlotThin = 6
private const val SlotClock = 7

private val DockCorner = 38.dp
private const val TitleAmbientScale = 30f / 34f
private const val ArtistAmbientScale = 19f / 20f
private val TitleStyle = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black)
private val ArtistStyle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
