package com.mytv.mymusic.ui.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.IconToggleButtonShapes
import androidx.compose.material3.IconButtonDefaults
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.LyricsBackgroundMode
import com.mytv.mymusic.data.RhythmIntensity
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TabletopTop
import com.mytv.mymusic.playback.SleepTimerState
import com.mytv.mymusic.data.LyricTextPrefs
import com.mytv.mymusic.ui.LyricPipeline
import com.mytv.mymusic.ui.withSubline
import com.mytv.mymusic.ui.player.stage.AnimationSheet
import com.mytv.mymusic.ui.player.stage.StageBinding
import com.mytv.mymusic.ui.player.stage.pick
import com.mytv.mymusic.ui.player.stage.pin
import com.mytv.mymusic.ui.player.stage.rememberLyricDirector
import com.mytv.mymusic.ui.LyricsParser
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.adaptive.modeLabel
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.Posture
import com.mytv.mymusic.ui.components.ProviderIcon
import com.mytv.mymusic.ui.components.tvFocusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 沉浸式播放页，固定深色 Vibrant 配色（由外层 PlayerTheme 提供），背景是流光场（[LyricsBackdrop]）。
 * 手机：形状封面、标题、两行歌词、波浪进度、连接按钮组和浮动工具栏竖排，歌词页里封面缩成左上角的小圆；
 * 平板横屏：左栏播放、右栏整栏歌词，可进专注歌词；电视：封面 + 整栏歌词 + 浮动控制条，空闲进入环境模式。
 * 折叠屏书本姿态、双竖：平板布局按折痕分成左右两页（竖着握的大折叠也走它）；桌面姿态、双横：上看下控的
 * [TabletopPlayer]，控件不自动隐藏。只在播放页本来就开着时换排法，半开不会自己打开播放页。
 *
 * @param sheetOpen 播放页上开着队列或定时面板（换源面板从 [MusicUiState.sourcePickerTrack] 自己判断）：期间不进入空闲。
 */
@Composable
fun PlayerScreen(
    state: MusicUiState,
    position: State<Long>,
    favorites: List<SavedSong>,
    sleep: SleepTimerState,
    actions: MusicActions,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTimer: () -> Unit,
    sheetOpen: Boolean = false,
    lyricsRequest: Boolean = false,
    onLyricsRequestHandled: () -> Unit = {},
) {
    val song = state.current ?: return
    val window = LocalAppWindow.current
    var lyricsMode by rememberSaveable { mutableStateOf(false) }
    // 桌面歌词组件点进来：直接进歌词视图
    LaunchedEffect(lyricsRequest) {
        if (!lyricsRequest) return@LaunchedEffect
        lyricsMode = true
        onLyricsRequestHandled()
    }
    var burst by remember { mutableIntStateOf(0) }
    // 预测性返回：手势过程中播放页缩小、下移、圆角变大，松手才真正收起
    var backProgress by remember { mutableFloatStateOf(0f) }
    // 桌面姿态、双横（电视的姿态总是 Normal）；书本姿态、双竖只在平板宽度上分页，竖着握的大折叠也走平板布局
    val tabletop = window.posture == Posture.Tabletop
    val book = window.posture == Posture.Book && window.widthDp >= 600
    val wideLayout = book || (window.widthDp >= 600 && window.widthDp > window.heightDp)
    // 暂停时也照样计时：控制栏几秒后淡出，按确认键或点按唤醒。桌面姿态下半屏是平放着用手点的那一面，不进入空闲
    var animationOpen by rememberSaveable { mutableStateOf(false) }
    val idle = rememberIdleState(holdAwake = sheetOpen || animationOpen || state.sourcePickerTrack != null || tabletop)
    // 电视环境模式下按住左右键切歌的按键状态，跨重组保留到松开
    val holdSkip = remember { HoldSkip() }
    PredictiveBackHandler { events ->
        // 电视上控制栏亮着时，返回键先收起控制栏；已经在环境模式下才退出播放页，和常见视频播放器一致
        val hideControls = window.isTv && !lyricsMode && idle.canAutoHide &&
            !idle.idle && state.sourcePickerTrack == null
        // 桌面姿态上半屏本来就放歌词时，歌词视图和平常一个样子，返回键直接收起播放页
        val lyricsView = lyricsMode && !(tabletop && state.tabletopTop == TabletopTop.Lyrics)
        try {
            events.collect { if (!lyricsView && !hideControls) backProgress = it.progress }
            when {
                hideControls -> idle.sleep()
                lyricsView -> lyricsMode = false
                else -> onClose()
            }
        } catch (_: CancellationException) {
        } finally {
            backProgress = 0f
        }
    }
    val pulse = rememberBackdropPulse()
    val favorite = remember(favorites, song.key) { favorites.any { it.key == song.key } }
    // 切歌后新歌词取回来之前 state.lyrics 还是上一首的（解析失败跳过、取词慢时会一直挂着）：没对上这首就按“还在取”处理
    val lyricsReady = state.lyricsKey == song.key
    // 解析放在后台；状态不随原文重建，换歌词文字设置时先保留上一份结果，等新原文解析完成再替换。
    val lyricsState = rememberParsedLyrics(if (lyricsReady) state.lyrics else "", state.lyricText)
    val stageLyrics = lyricsState.lyrics
    // 经典列表、两行预览只认 translation：按“歌词下面那一行”换成罗马音或两行都要；动画舞台自己按设置取
    val lyrics = remember(stageLyrics, state.lyricStage.subline) { stageLyrics.withSubline(state.lyricStage.subline) }
    val lyricsLoading = lyricsState.loading || !lyricsReady
    val director = rememberLyricDirector(song, state.lyricStage, stageLyrics, lyricsLoading, position, state.playing, actions)
    val stageMode = director.mode
    val stage = remember(stageMode, state.lyricStage, stageLyrics) {
        StageBinding(stageMode, state.lyricStage, stageLyrics) { animationOpen = true }
    }
    // 自己画背景的动画（动感波光）铺满歌词页时，底下的流光背景停掉；等歌词页淡入完再停，免得过渡中闪一下纯色。
    // 桌面姿态的舞台只占上半屏，下半屏的控件还要流光背景
    val stageCoversBackdrop = lyricsMode && !stageMode.hostBackground && !window.isTv && !tabletop
    var backdropOff by remember { mutableStateOf(false) }
    LaunchedEffect(stageCoversBackdrop) {
        if (stageCoversBackdrop) {
            delay(BACKDROP_OFF_DELAY_MS)
            backdropOff = true
        } else {
            backdropOff = false
        }
    }
    // 进度每 100ms 变一次；派生状态只记录依赖，不在这里读取，当前行由歌词 leaf 消费
    val active = remember(lyrics, position) {
        derivedStateOf {
            if (lyrics.synced) LyricsParser.activeLineIndex(lyrics.lines, position.value) else -1
        }
    }

    // 播放页退出（含退场动画）时清掉上报的底部控件高度，更新胶囊不再为已经不存在的控件让位
    val bottomChrome = LocalPlayerBottomChrome.current
    DisposableEffect(bottomChrome) {
        onDispose { bottomChrome.clearAll() }
    }

    // 换源结果在音源 chip 下方停留 8 秒
    LaunchedEffect(state.playbackNotice) {
        if (state.playbackNotice.isNotBlank() && state.deepLink.isBlank()) {
            delay(8_000)
            actions.clearNotice()
        }
    }

    val source = state.currentSource
    val sourceProvider = source?.providerName
    val sourceDetail = source?.qualityLabel?.takeUnless { it == "音质未知" }.orEmpty()
    val playbackMode = modeLabel(state.aggregation.playbackMode)
    val onSkip: (Int) -> Unit = { direction -> actions.transport(if (direction > 0) "next" else "previous") }
    val onOpenSource: () -> Unit = {
        state.currentTrack?.let { track -> actions.openSource(track, state.canonicalQueue.ifEmpty { listOf(track) }) }
    }
    val onFavoriteBurst: () -> Unit = {
        if (!favorite) actions.favorite(song)
        burst++
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 1f - 0.08f * backProgress
                scaleX = scale
                scaleY = scale
                translationY = backProgress * 48.dp.toPx()
                shape = RoundedCornerShape(32.dp * backProgress)
                clip = backProgress > 0f
            }
            .background(MaterialTheme.colorScheme.surface)
            // 电视上焦点不能跑到播放页下面的页面里
            .then(if (window.isTv) Modifier.focusProperties { onExit = { cancelFocusChange() } }.focusGroup() else Modifier)
            .wakeOnInput(
                idle,
                consumeKeyWhenIdle = window.isTv,
                holdSkip = holdSkip.takeIf { window.isTv },
                onSkip = onSkip,
                onAnyKey = { com.mytv.mymusic.playback.CrossfadeStatus.dismissActive() },
            ),
    ) {
        val wideBackdrop = !tabletop && (window.isTv || wideLayout)
        // 桌面姿态上半屏此刻放什么：设置里选的，或者工具栏、更多菜单临时换成的歌词
        val tabletopShown = if (lyricsMode) TabletopTop.Lyrics else state.tabletopTop
        LyricsBackdrop(
            cover = song.cover,
            playing = state.playing,
            mode = if (backdropOff) LyricsBackgroundMode.Off else state.lyricsBackground,
            intensity = state.rhythmIntensity,
            wide = wideBackdrop,
            pulse = pulse,
            rhythmOffsetMs = state.rhythmOffsetMs,
            // 手机歌词页、平板专注歌词整体压暗一些；电视底部额外加深，给控制条和细进度留对比度。
            // 桌面姿态上下排：上半屏是歌词或时钟时按手机歌词页压暗，是封面时按手机播放页
            scrim = when {
                window.isTv -> BackdropScrim.Tv
                tabletop -> if (tabletopShown == TabletopTop.Cover) BackdropScrim.Phone else BackdropScrim.PhoneLyrics
                wideBackdrop -> if (lyricsMode) BackdropScrim.WideFocus else BackdropScrim.Wide
                lyricsMode -> BackdropScrim.PhoneLyrics
                else -> BackdropScrim.Phone
            },
        )
        when {
            window.isTv -> TvPlayer(
                song = song,
                playing = state.playing,
                duration = state.duration,
                position = position,
                favorite = favorite,
                sleep = sleep,
                lyrics = lyrics,
                lyricsLoading = lyricsLoading,
                active = active,
                stage = stage,
                sourceProvider = sourceProvider,
                sourceDetail = sourceDetail,
                playbackMode = playbackMode,
                notice = state.playbackNotice,
                actions = actions,
                idle = idle,
                pulse = pulse,
                coverShape = state.coverShape,
                transitionAnimation = state.crossfadeAnimation,
                ambientClock = state.tvAmbientClock,
                onOpenQueue = onOpenQueue,
                onOpenTimer = onOpenTimer,
                onOpenSource = onOpenSource,
            )
            tabletop -> TabletopPlayer(
                song = song,
                playing = state.playing,
                duration = state.duration,
                position = position,
                favorite = favorite,
                sleep = sleep,
                lyrics = lyrics,
                lyricsLoading = lyricsLoading,
                active = active,
                stage = stage,
                sourceProvider = sourceProvider,
                sourceDetail = sourceDetail,
                playbackMode = playbackMode,
                notice = state.playbackNotice,
                deepLink = state.deepLink,
                actions = actions,
                top = state.tabletopTop,
                lyricsMode = lyricsMode,
                onLyricsMode = { lyricsMode = it },
                onClose = onClose,
                onOpenQueue = onOpenQueue,
                onOpenTimer = onOpenTimer,
                onFavoriteBurst = onFavoriteBurst,
                onOpenSource = onOpenSource,
                onSkip = onSkip,
                pulse = pulse,
                coverShape = state.coverShape,
                transitionAnimation = state.crossfadeAnimation,
            )
            wideLayout -> WidePlayer(
                song = song,
                playing = state.playing,
                duration = state.duration,
                position = position,
                favorite = favorite,
                sleep = sleep,
                lyrics = lyrics,
                lyricsLoading = lyricsLoading,
                active = active,
                stage = stage,
                sourceProvider = sourceProvider,
                sourceDetail = sourceDetail,
                playbackMode = playbackMode,
                notice = state.playbackNotice,
                deepLink = state.deepLink,
                actions = actions,
                idle = idle,
                lyricsMode = lyricsMode,
                onLyricsMode = { lyricsMode = it },
                onClose = onClose,
                onFavoriteBurst = onFavoriteBurst,
                onOpenSource = onOpenSource,
                onOpenQueue = onOpenQueue,
                onOpenTimer = onOpenTimer,
                onSkip = onSkip,
                pulse = pulse,
                coverShape = state.coverShape,
                transitionAnimation = state.crossfadeAnimation,
            )
            else -> PhonePlayer(
                song = song,
                playing = state.playing,
                duration = state.duration,
                position = position,
                favorite = favorite,
                sleep = sleep,
                lyrics = lyrics,
                lyricsLoading = lyricsLoading,
                active = active,
                stage = stage,
                sourceProvider = sourceProvider,
                sourceDetail = sourceDetail,
                playbackMode = playbackMode,
                notice = state.playbackNotice,
                deepLink = state.deepLink,
                actions = actions,
                idle = idle,
                lyricsMode = lyricsMode,
                onLyricsMode = { lyricsMode = it },
                onClose = onClose,
                onOpenQueue = onOpenQueue,
                onOpenTimer = onOpenTimer,
                onFavoriteBurst = onFavoriteBurst,
                onOpenSource = onOpenSource,
                onSkip = onSkip,
                pulse = pulse,
                coverShape = state.coverShape,
                transitionAnimation = state.crossfadeAnimation,
            )
        }
        HeartBurst(burst, heartBurstPlacement(window.fold, book, tabletop))
        if (animationOpen) {
            AnimationSheet(
                prefs = state.lyricStage,
                song = song,
                shown = stageMode.id,
                actions = actions,
                onPick = { id -> director.pick(song, state.lyricStage, id, actions) },
                onPin = { on -> director.pin(song, on, actions) },
                onDismiss = { animationOpen = false },
            )
        }
        if (state.rhythmDebug && rhythmDebugAvailable()) {
            RhythmDebugOverlay(pulse, state.rhythmOffsetMs, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp))
        }
    }
}

/**
 * [rememberParsedLyrics] 返回的状态持有器：已解析歌词和当前原文是否仍在解析。
 * [loading] 在原文尚未解析完时立即为 true（包括解析任务刚切换的那一帧），不会闪过
 * “没有歌词”或把未同步原文里的时间戳当歌词显示。
 */
internal class ParsedLyricsState(val lyrics: ParsedLyrics, val loading: Boolean)

/**
 * 切歌时保留上一份解析结果，取消旧任务后只发布当前原文的结果。解析走 [LyricPipeline]（拆单行双语、简转繁、补罗马音），
 * 改了这几项开关原文不变，不算加载中，结果出来直接替换。
 */
@Composable
internal fun rememberParsedLyrics(raw: String, text: LyricTextPrefs = LyricTextPrefs()): ParsedLyricsState {
    val lyrics by androidx.compose.runtime.produceState(ParsedLyrics(emptyList(), "", false), raw, text) {
        val parsed = withContext(Dispatchers.Default) { LyricPipeline.prepare(raw, text) }
        ensureActive()
        value = parsed
    }
    // 纯派生值在本帧就反映新请求，不在组合中修改普通对象或快照状态。
    return ParsedLyricsState(lyrics, loading = raw.isNotBlank() && lyrics.raw != raw)
}

// ---------- 共用片段 ----------

/** 自己画背景的动画铺满歌词页后，等这么久（歌词页淡入完）再停流光背景。 */
private const val BACKDROP_OFF_DELAY_MS = 700L

/**
 * 空闲时控件淡出的透明度，只在图层里读。空闲状态在效果协程里收集：返回值的函数不是独立的重组作用域，
 * 在这里直接读 idle 会让调用它的整个播放页跟着空闲状态重组。
 */
@Composable
internal fun rememberIdleAlpha(idle: IdleState, spec: AnimationSpec<Float> = spring()): State<Float> {
    val alpha = remember { Animatable(1f) }
    val currentSpec by rememberUpdatedState(spec)
    LaunchedEffect(idle, alpha) {
        snapshotFlow { idle.idle }.collectLatest { alpha.animateTo(if (it) 0f else 1f, currentSpec) }
    }
    return alpha.asState()
}

@Composable
internal fun MoreMenu(
    song: Song,
    deepLink: String,
    actions: MusicActions,
    onOpenSource: () -> Unit,
    onLyricsMode: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurface) }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
            DropdownMenuItem(text = { Text("全屏歌词") }, leadingIcon = { Icon(Icons.Filled.Lyrics, null) }, onClick = { open = false; onLyricsMode(true) })
            // 收起播放页，露出队列海报墙
            DropdownMenuItem(text = { Text("海报墙") }, leadingIcon = { Icon(Icons.Filled.GridView, null) }, onClick = { open = false; actions.openPosterWall() })
            DropdownMenuItem(text = { Text("选择音源") }, leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) }, onClick = { open = false; onOpenSource() })
            DropdownMenuItem(text = { Text("下载到本机") }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = { open = false; actions.download(song) })
            if (deepLink.isNotBlank()) {
                DropdownMenuItem(text = { Text("在平台打开") }, onClick = { open = false; actions.openDeepLink() })
            }
        }
    }
}

/**
 * 进度条波幅倍数：只在律动“标准”“强烈”档下随低频强弱“呼吸”，其余情况固定 1；设计稿规定最大 1 倍。
 * 跟的是低频慢包络 [BackdropPulse.swell]（0.3s 起、1s 落），不是逐帧低频：指示器把波幅变化做成 500ms 补间，
 * 补间进行中不接新值，逐帧低频交给它只会变成每半秒随机抽一个样，和节拍对不上；慢包络本身就比 500ms 慢，抽样也跟得住。
 * 繁忙段落的包络在 0.5–0.7，加上 0.3 后接近满幅，安静段落落到一半左右。只在绘制阶段调用（WavySeekBar 的 amplitudeScale）。
 */
internal fun pulseWaveScale(pulse: BackdropPulse): Float =
    if (pulse.reactive && pulse.intensity != RhythmIntensity.Soft) (0.3f + pulse.swell).coerceAtMost(1f) else 1f

/**
 * 进度条的波速跟拍子：“标准”“强烈”档锁上拍子后每拍走一个波长，其余情况 0（用指示器默认的每秒一个波长）。
 * [BackdropPulse.bpm] 只在变化超过 2% 时更新，可以在组合阶段读（WavySeekBar 的 waveBpm）。
 */
internal fun pulseWaveBpm(pulse: BackdropPulse): Float =
    if (pulse.reactive && pulse.intensity != RhythmIntensity.Soft) pulse.bpm else 0f

/**
 * 收藏切换按钮（M3E）：48dp，未收藏是透明底的圆，收藏后变成 14dp 圆角的 primaryContainer 方块，
 * 圆角随快速空间弹簧过渡。读屏文案与原来的收藏按钮一致。
 */
@Composable
internal fun FavoriteToggle(song: Song, favorite: Boolean, actions: MusicActions, modifier: Modifier = Modifier) {
    IconToggleButton(
        checked = favorite,
        onCheckedChange = { actions.favorite(song) },
        shapes = IconToggleButtonShapes(
            shape = CircleShape,
            pressedShape = RoundedCornerShape(12.dp),
            checkedShape = RoundedCornerShape(14.dp),
        ),
        modifier = modifier
            .size(48.dp)
            .tvFocusRing(if (favorite) RoundedCornerShape(14.dp) else CircleShape)
            .semantics { stateDescription = if (favorite) "已收藏" else "未收藏" },
        colors = IconButtonDefaults.iconToggleButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "收藏")
    }
}

/**
 * 音源胶囊：32dp 高、16dp 圆角，主色圆点 + 平台名 + “· 音质”，右侧小箭头，点开换源面板。
 * 读屏文案沿用音源 chip（含自动 / 固定模式），胶囊上不显示模式。
 */
@Composable
internal fun SourcePill(
    song: Song,
    sourceProvider: String?,
    sourceDetail: String,
    playbackMode: String,
    onOpenSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val provider = sourceProvider ?: if (song.url.startsWith("file:")) "本机" else song.source
    Surface(
        onClick = onOpenSource,
        modifier = modifier
            .height(32.dp)
            .tvFocusRing(RoundedCornerShape(16.dp))
            .semantics { contentDescription = "音源：$provider $sourceDetail，$playbackMode。点按切换音源" },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 8.dp).clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ProviderIcon(id = song.source, name = provider, size = 18.dp, corner = 5.dp)
            Text(provider, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (sourceDetail.isNotBlank()) {
                Text(
                    "· $sourceDetail",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp))
        }
    }
}

/** 控件淡出后不响应点按：唤醒的那一下只用来显示控件，不会误触看不见的按钮。 */
internal fun Modifier.blockTouchesWhen(block: Boolean): Modifier = if (!block) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
