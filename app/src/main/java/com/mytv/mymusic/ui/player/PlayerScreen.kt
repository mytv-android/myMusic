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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material3.IconButtonShapes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import com.mytv.mymusic.ui.player.share.LocalLyricPickProbe
import com.mytv.mymusic.ui.player.share.LyricPickProbe
import com.mytv.mymusic.ui.player.share.ShareOverlay
import com.mytv.mymusic.ui.player.share.ShareSession
import com.mytv.mymusic.ui.player.share.initialPick
import com.mytv.mymusic.ui.player.share.pickLines
import com.mytv.mymusic.ui.player.share.shareLink
import com.mytv.mymusic.ui.theme.LocalAppearance
import com.mytv.mymusic.ui.theme.LocalSeedColor
import com.mytv.mymusic.ui.AddToPlaylistSheet
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.text.style.TextOverflow
import com.mytv.mymusic.ui.artist.splitArtistNames
import com.mytv.mymusic.ui.charts.CommentsSheet
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
import com.mytv.mymusic.ui.player.stage.SongDecision
import com.mytv.mymusic.ui.player.stage.SongRowState
import com.mytv.mymusic.ui.player.stage.StageBinding
import com.mytv.mymusic.ui.player.stage.StageTag
import com.mytv.mymusic.ui.player.stage.isDirty
import com.mytv.mymusic.ui.player.stage.isKnown
import com.mytv.mymusic.ui.player.stage.modeOf
import com.mytv.mymusic.ui.player.stage.previewId
import com.mytv.mymusic.ui.player.stage.withDraft
import com.mytv.mymusic.ui.player.stage.pin
import com.mytv.mymusic.ui.player.stage.rememberAnimationDraft
import com.mytv.mymusic.ui.player.stage.rememberLyricDirector
import com.mytv.mymusic.ui.LyricsParser
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.adaptive.modeLabel
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.Posture
import com.mytv.mymusic.ui.components.ProviderIcon
import com.mytv.mymusic.ui.components.RequestKeepScreenOn
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
    // 「加到歌单」面板：打开那一刻的这首。面板开着时切了歌，勾的还是打开时那首，不会悄悄改到下一首上
    var playlistSong by remember { mutableStateOf<Song?>(null) }
    // 歌词分享：全屏歌词长按任意一句进选句层，再进分享页；会话是长按那一刻的快照。电视不做分享
    val shareAllowed = !window.isTv
    var share by remember { mutableStateOf<ShareSession?>(null) }
    val pickProbe = remember { LyricPickProbe() }
    // 电视上开着歌词动画面板：直接进环境模式（舞台铺满，草稿画在上面）；其他设备开着面板时不进空闲
    val idle = rememberIdleState(
        holdAwake = sheetOpen || (animationOpen && !window.isTv) || playlistSong != null || state.sourcePickerTrack != null || tabletop,
        forceIdle = animationOpen && window.isTv,
    )
    // 电视环境模式（全屏歌词页）里快退快进的浮层提示：按左右方向键时显示，1.2 秒淡出
    val seekHint = remember { SeekHint() }
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
    // 这首（含同一首的其他平台版本）已经在任一自建歌单里：「加到歌单」按钮换成打勾的图标、填上底色
    val inPlaylists by remember(song.key) { actions.playlistsContaining(song) }.collectAsState(initial = emptySet())
    val addedToPlaylist = inPlaylists.isNotEmpty()
    val onAddToPlaylist: () -> Unit = { playlistSong = song }
    // 切歌后新歌词取回来之前 state.lyrics 还是上一首的（解析失败跳过、取词慢时会一直挂着）：没对上这首就按“还在取”处理
    val lyricsReady = state.lyricsKey == song.key
    // 解析放在后台；状态不随原文重建，换歌词文字设置时先保留上一份结果，等新原文解析完成再替换。
    val lyricsState = rememberParsedLyrics(if (lyricsReady) state.lyrics else "", state.lyricText)
    val stageLyrics = lyricsState.lyrics
    // 经典列表、两行预览只认 translation：按“歌词下面那一行”换成罗马音或两行都要；动画舞台自己按设置取
    val lyrics = remember(stageLyrics, state.lyricStage.subline) { stageLyrics.withSubline(state.lyricStage.subline) }
    val lyricsLoading = lyricsState.loading || !lyricsReady
    val director = rememberLyricDirector(song, state.lyricStage, stageLyrics, lyricsLoading, position, state.playing, actions)
    // 正在显示的模式（舞台不预览草稿时就画它）
    val shownMode = director.mode(state.lyricStage)
    // 动画面板的草稿：面板开着时才有，每次打开重新建
    val animationDraft = rememberAnimationDraft(animationOpen, state.lyricStage)
    // 桌面姿态上半屏此刻放什么：设置里选的，或者工具栏、更多菜单临时换成的歌词
    val tabletopShown = if (lyricsMode) TabletopTop.Lyrics else state.tabletopTop
    // 全屏看歌词、桌面姿态上半屏是歌词时常亮：屏幕超时不会在唱到一半时把画面熄掉，电视仍由设置里的开关决定
    RequestKeepScreenOn(!window.isTv && (lyricsMode || (tabletop && tabletopShown == TabletopTop.Lyrics)))
    // 舞台露在面板旁边：电视（开面板就进环境模式）、宽屏专注歌词（含书本姿态）、桌面姿态上半屏放歌词
    val stageBeside = window.isTv || (wideLayout && !tabletop && lyricsMode) || (tabletop && tabletopShown == TabletopTop.Lyrics)
    val pinnedId = state.lyricStage.pins[song.key]?.takeIf(state.lyricStage::isKnown)
    val memory = director.memory
    val rememberedMode = memory?.mode?.takeIf { it.isNotEmpty() }
    val knownBpm = memory?.bpm?.takeIf { it > 0f } ?: (director.decision as? SongDecision.Tempo)?.bpm
    // 舞台在旁边时草稿直接画在舞台上；点了保存、这首还没换上之前继续画预览的那个，不闪回旧模式
    val previewing = animationDraft != null && stageBeside &&
        (animationDraft.saving || isDirty(state.lyricStage, animationDraft.draft))
    val stagePrefs = if (previewing) state.lyricStage.withDraft(animationDraft!!.draft) else state.lyricStage
    val previewMode = if (previewing) {
        val draft = animationDraft!!
        stagePrefs.modeOf(previewId(draft.draft, state.lyricStage, pinnedId, rememberedMode, director.shown, knownBpm, draft.editTarget))
    } else {
        null
    }
    val stageMode = previewMode ?: shownMode
    val stageTag = when {
        previewing -> StageTag("预览：${stageMode.label} · 还没保存", preview = true)
        animationDraft != null && stageBeside -> StageTag("正在用：${shownMode.label}", preview = false)
        else -> null
    }
    // 面板（平板 400dp、电视 360dp）从右边盖住舞台的宽度，文字区让开；书本姿态面板在左页，不盖舞台
    val coverEnd = when {
        animationDraft == null || !stageBeside || book -> 0.dp
        window.isTv -> 400.dp
        window.widthDp < 900 -> 440.dp
        else -> 460.dp
    }
    val modeFade: FiniteAnimationSpec<Float>? = if (director.appliedSwitch) tween(APPLY_FADE_MS) else null
    // 旋转、折叠会换排法，绑定不跟着重建：打开面板时读这一刻的排法
    val focusFirst by rememberUpdatedState(wideLayout && !tabletop)
    val stage = remember(stageMode, stagePrefs, stageLyrics, stageTag, coverEnd, modeFade) {
        StageBinding(stageMode, stagePrefs, stageLyrics, stageTag, coverEnd, modeFade) {
            // 宽屏默认布局没有舞台：先进专注歌词，舞台在面板旁边才能预览
            if (focusFirst) lyricsMode = true
            animationOpen = true
        }
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

    val haptics = LocalHapticFeedback.current
    val seedColor = LocalSeedColor.current
    SideEffect {
        // 只在全屏歌词页（桌面姿态是上半屏放歌词时）、有歌词且不在加载时能长按选句；平板非专注时右栏那列歌词不是入口
        pickProbe.enabled = shareAllowed && share == null && !lyricsLoading && lyrics.raw.isNotBlank() &&
            (if (tabletop) tabletopShown == TabletopTop.Lyrics else lyricsMode)
        pickProbe.onRequest = { hit ->
            val lines = pickLines(lyrics.displayLines())
            if (lines.isNotEmpty()) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                share = ShareSession(
                    song = song,
                    link = shareLink(song, state.currentTrack),
                    lines = lines,
                    synced = lyrics.synced,
                    seed = seedColor,
                    initial = initialPick(lines, hit, active.value),
                )
                // 专注栏、标题、底部按钮在压暗层下面淡出
                idle.sleep()
            }
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
    val sourceProvider = source?.actualProviderName
    val sourceDetail = source?.qualityLabel?.takeUnless { it == "音质未知" }.orEmpty()
    val playbackMode = modeLabel(state.aggregation.playbackMode)
    val onSkip: (Int) -> Unit = { direction -> actions.transport(if (direction > 0) "next" else "previous") }
    // 电视全屏歌词页的快退快进：直播没有进度可拖，按了也不提示
    val onSeekBy: (Long) -> Unit = { delta ->
        if (!song.isLive) {
            seekHint.show(delta)
            actions.seek(seekStepTarget(position.value, delta, state.duration))
        }
    }
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
                onSkip = onSkip,
                onSeekBy = onSeekBy,
                onAnyKey = { com.mytv.mymusic.playback.CrossfadeStatus.dismissActive() },
                // 选句层、分享页开着时下面的控件留在淡出状态，点按不唤醒
                suspended = { share != null },
            ),
    ) {
        val wideBackdrop = !tabletop && (window.isTv || wideLayout)
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
        val shareFade = if (LocalAppearance.current.reduceMotion || rememberSystemAnimationsOff()) {
            snap()
        } else {
            MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        }
        // 选句层只有 0.6 的压暗，舞台、标题、按钮再一起淡到 0.2（稿子 .js-stage.picking .stl），不和列表叠字；背景不动。
        // 盖着的这层读屏也不该再读到
        val stageAlpha = animateFloatAsState(if (share != null) 0.2f else 1f, shareFade, label = "shareStageAlpha")
        val stageCover = if (share != null) Modifier.clearAndSetSemantics {} else Modifier
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = stageAlpha.value }.then(stageCover)) { CompositionLocalProvider(LocalLyricPickProbe provides pickProbe.takeIf { shareAllowed }) {
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
                    seekHint = seekHint,
                    addedToPlaylist = addedToPlaylist,
                    playlistSheetOpen = playlistSong != null,
                    animationSheetOpen = animationOpen,
                    onAddToPlaylist = onAddToPlaylist,
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
                    addedToPlaylist = addedToPlaylist,
                    onAddToPlaylist = onAddToPlaylist,
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
                    addedToPlaylist = addedToPlaylist,
                    onAddToPlaylist = onAddToPlaylist,
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
                    addedToPlaylist = addedToPlaylist,
                    onAddToPlaylist = onAddToPlaylist,
                )
            }
        } }
        HeartBurst(burst, heartBurstPlacement(window.fold, book, tabletop))
        // 记住最后一个会话，淡出时还画它
        val lastShare = remember { arrayOfNulls<ShareSession>(1) }
        share?.let { lastShare[0] = it }
        AnimatedVisibility(share != null, Modifier.fillMaxSize(), enter = fadeIn(shareFade), exit = fadeOut(shareFade)) {
            lastShare[0]?.let { session ->
                ShareOverlay(session, state, actions, active, currentKey = song.key) {
                    share = null
                    idle.poke()
                }
            }
        }
        if (animationDraft != null) {
            AnimationSheet(
                saved = state.lyricStage,
                draft = animationDraft,
                song = song,
                // 没有 key 的歌（个别电台）没法单独固定，不显示“这首歌”一行
                songRow = if (song.key.isBlank()) null else SongRowState(
                    shownId = shownMode.id,
                    decision = director.decision,
                    pinnedId = pinnedId,
                    remembered = rememberedMode,
                    bpm = knownBpm,
                    waitingFor = director.wanted?.takeIf { it != shownMode.id },
                    onPin = { id -> director.pin(song, id, actions) },
                ),
                stageBeside = stageBeside,
                actions = actions,
                onApplied = { hint -> director.applyNow(song.key, hint) },
                onDismiss = { animationOpen = false },
            )
        }
        playlistSong?.let { target ->
            AddToPlaylistSheet(song = target, providers = state.providers, actions = actions, onDismiss = { playlistSong = null })
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

/** “保存并应用”换模式时的淡入淡出。 */
private const val APPLY_FADE_MS = 400

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
    onAddToPlaylist: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var commentsOpen by remember(song.key) { mutableStateOf(false) }
    if (commentsOpen) CommentsSheet(song, actions, onDismiss = { commentsOpen = false })
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurface) }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
            DropdownMenuItem(text = { Text("全屏歌词") }, leadingIcon = { Icon(Icons.Filled.Lyrics, null) }, onClick = { open = false; onLyricsMode(true) })
            // 收起播放页，露出队列海报墙
            DropdownMenuItem(text = { Text("海报墙") }, leadingIcon = { Icon(Icons.Filled.GridView, null) }, onClick = { open = false; actions.openPosterWall() })
            DropdownMenuItem(text = { Text("选择音源") }, leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) }, onClick = { open = false; onOpenSource() })
            if (!song.isLive && actions.supportsComments(song)) {
                DropdownMenuItem(
                    text = { Text("查看评论") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Comment, null) },
                    onClick = { open = false; commentsOpen = true },
                )
            }
            // 和歌名行的「加到歌单」按钮是同一个面板；控件淡出、矮窗口里也总能在这里找到
            DropdownMenuItem(
                text = { Text("加到歌单…") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                onClick = { open = false; onAddToPlaylist() },
            )
            // 收起播放页，打开歌手页 / 专辑页；直播（电台）没有这两项
            if (!song.isLive) {
                splitArtistNames(song.artist).take(3).forEach { name ->
                    DropdownMenuItem(
                        text = { Text("歌手：$name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Filled.Person, null) },
                        onClick = { open = false; actions.openArtistByName(name, song) },
                    )
                }
                if (song.album.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text("专辑：${song.album}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Filled.Album, null) },
                        onClick = { open = false; actions.openAlbumOf(song) },
                    )
                }
            }
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
 * 收藏切换按钮（M3E）：48dp（专注歌词标题行是 40dp），未收藏是透明底的圆，收藏后变成 14dp 圆角的 primaryContainer 方块，
 * 圆角随快速空间弹簧过渡。读屏文案与原来的收藏按钮一致。
 * [onLongClick]：长按打开「加到歌单」面板（和旁边那个按钮同一个面板），读屏用户走长按操作。
 */
@Composable
internal fun FavoriteToggle(
    song: Song,
    favorite: Boolean,
    actions: MusicActions,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val checkedShape = RoundedCornerShape(checkedCorner(size))
    val haptics = LocalHapticFeedback.current
    IconToggleButton(
        checked = favorite,
        onCheckedChange = { actions.favorite(song) },
        shapes = IconToggleButtonShapes(
            shape = CircleShape,
            pressedShape = RoundedCornerShape(12.dp),
            checkedShape = checkedShape,
        ),
        modifier = modifier
            .size(size)
            .tvFocusRing(if (favorite) checkedShape else CircleShape)
            .then(
                if (onLongClick == null) {
                    Modifier
                } else {
                    Modifier
                        .longPressFirst {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        }
                        .semantics { onLongClick(label = "加到歌单") { onLongClick(); true } }
                },
            )
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

/** 选中态的圆角：48dp 按钮是 14dp，40dp 的小一号按 12dp，看上去一样方。 */
private fun checkedCorner(size: Dp): Dp = if (size >= 48.dp) 14.dp else 12.dp

/**
 * 「加到歌单」按钮：和收藏同尺寸，排在收藏左边，点了打开加到歌单面板（[AddToPlaylistSheet]）。
 * 这首已经在任一自建歌单里（[added]）时图标换成 PlaylistAddCheck，底色同收藏按钮的选中态（primaryContainer、14dp 圆角）。
 * 不是开关：点它不会直接加或移出，读屏读“加到歌单”加上现在在不在歌单里。
 */
@Composable
internal fun AddToPlaylistButton(added: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val shape = if (added) RoundedCornerShape(checkedCorner(size)) else CircleShape
    IconButton(
        onClick = onClick,
        shapes = IconButtonShapes(shape = shape, pressedShape = RoundedCornerShape(12.dp)),
        modifier = modifier
            .size(size)
            .tvFocusRing(shape)
            .semantics { stateDescription = if (added) "已在歌单里" else "不在歌单里" },
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (added) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (added) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(if (added) Icons.AutoMirrored.Filled.PlaylistAddCheck else Icons.AutoMirrored.Filled.PlaylistAdd, "加到歌单")
    }
}

/**
 * 长按：在 Initial 阶段抢在按钮自己的点按之前看。按满长按时长就触发，并吃掉这一下后面的事件，按钮不会再当成一次点按；
 * 没到时长就松手、或者手指移出触摸容差，事件原样交给按钮。
 */
private fun Modifier.longPressFirst(onLongPress: () -> Unit): Modifier = pointerInput(onLongPress) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var done = false
            while (!done) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                done = change == null || !change.pressed ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            }
            true
        }
        if (ended != null) return@awaitEachGesture
        onLongPress()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
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
