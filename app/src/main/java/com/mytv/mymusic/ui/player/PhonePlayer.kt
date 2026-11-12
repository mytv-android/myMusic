package com.mytv.mymusic.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.util.lerp
import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.data.CrossfadeAnimation
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.SleepTimerState
import com.mytv.mymusic.ui.LyricLine
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.WavySeekBar
import com.mytv.mymusic.ui.player.stage.AnimationSheetButton
import com.mytv.mymusic.ui.player.stage.LyricStage
import com.mytv.mymusic.ui.player.stage.StageBinding
import com.mytv.mymusic.ui.player.stage.StageInsets
import com.mytv.mymusic.ui.theme.LocalAppearance
import kotlin.math.roundToInt

/**
 * 手机播放页：顶栏（收起、音源胶囊、更多）→ 形状封面 → 标题 → 两行歌词 → 波浪进度 → 连接按钮组 → 浮动工具栏。
 * 没有同步歌词时两行歌词那块不占位（收起有过渡）。
 * 歌词页里封面沿空间弹簧缩到左上角并形变成圆；空闲 4 秒后顶栏、按钮组、工具栏淡出，歌词扩成三行，进度变成细线，
 * 淡出的进度条拉平、两行预览的逐字填色停住，看不见的动画不再逐帧跑。
 *
 * 矮窗口（[com.mytv.mymusic.ui.components.AppWindow.short]，折叠屏上下分屏后约 400×400dp）换一种排法，见 [phoneShortSlots]：
 * 歌词 / 队列 / 定时 / 更多并进顶栏、取代底部工具栏（音源在更多菜单里），封面缩到左边，右边是歌名、歌手和一行歌词，
 * 下面是进度和小号按钮组；空闲时同样淡出，只是不换成三行歌词。歌词页和平常一样。
 *
 * 重组边界：空闲状态只在各个小组件里读（拦截点按、停动画），淡出透明度、歌词页过渡进度只在图层和摆放阶段读；
 * 播放页主体只随播放 / 暂停、切歌、进出歌词页、歌词有无这类低频变化重组。
 */
@Composable
internal fun PhonePlayer(
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
    idle: IdleState,
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
    val motion = MaterialTheme.motionScheme
    val reduceMotion = LocalAppearance.current.reduceMotion
    val chrome = rememberIdleAlpha(idle, motion.defaultEffectsSpec())
    val lyricsTransition = updateTransition(lyricsMode, label = "phoneLyrics")
    // 两套界面交叉淡入淡出，封面沿空间弹簧缩到左上角；两个进度只在图层 / 摆放阶段读
    val pageFade = lyricsTransition.animateFloat({ if (reduceMotion) snap() else motion.defaultEffectsSpec() }, label = "pageFade") { if (it) 1f else 0f }
    val coverMove = lyricsTransition.animateFloat({ if (reduceMotion) snap() else motion.defaultSpatialSpec() }, label = "coverMove") { if (it) 1f else 0f }
    // 过渡结束前两套界面都在组合里；稳定后只留一套，后台不跑歌词列表和逐字歌词的帧循环
    val showDefault = !lyricsTransition.targetState || !lyricsTransition.currentState
    val showLyrics = lyricsTransition.targetState || lyricsTransition.currentState
    val defaultAlpha = remember(pageFade) { { 1f - pageFade.value } }
    val lyricsAlpha = remember(pageFade) { { pageFade.value } }
    val chromeAlpha = remember(pageFade, chrome) { { chrome.value * (1f - pageFade.value) } }
    val waveScale = remember(pulse) { { pulseWaveScale(pulse) } }
    val waveBpm = remember(pulse) { { pulseWaveBpm(pulse) } }
    // 没有同步歌词时两行预览不占位。解析中 lyrics 还是上一份结果，占位保持不变，结果出来只变一次；
    // 刚打开播放页就在解析时先按有歌词留着，绝大多数歌都有，免得打开后再撑开一次
    val previewHeld = remember { booleanArrayOf(lyrics.synced || lyricsLoading) }
    if (!lyricsLoading) previewHeld[0] = lyrics.synced
    val previewSpace = animateFloatAsState(
        if (previewHeld[0]) 1f else 0f,
        if (reduceMotion) snap() else motion.defaultSpatialSpec(),
        label = "previewSpace",
    )
    val coverOnTop = rememberUpdatedState(lyricsMode)
    val short = LocalAppWindow.current.short
    val bottomChrome = LocalPlayerBottomChrome.current
    // 这一份是播放页默认界面和控制区的量法；离开组合（换设备形态、关播放页）时撤掉
    DisposableEffect(bottomChrome) { onDispose { bottomChrome.clear(PlayerBottomChrome.Panel) } }
    val policy = remember(coverMove, previewSpace, coverOnTop, short, bottomChrome) {
        PhoneStagePolicy(coverMove, previewSpace, coverOnTop, short, bottomChrome)
    }

    Layout(
        contents = listOf<@Composable () -> Unit>(
            {
                if (showDefault) {
                    if (short) {
                        PhoneShortTopBar(song, sleep, deepLink, actions, onClose, onOpenSource, onLyricsMode, onOpenQueue, onOpenTimer, idle, lyricsMode, chromeAlpha)
                    } else {
                        PhoneTopBar(song, sourceProvider, sourceDetail, playbackMode, deepLink, actions, onClose, onOpenSource, onLyricsMode, idle, lyricsMode, chromeAlpha)
                    }
                }
            },
            { if (showDefault) PhoneMeta(song, favorite, actions, idle, chrome, lyricsMode, defaultAlpha, short) },
            {
                if (showDefault) {
                    PhoneLyricsPreview(lyrics, active, position, playing, idle, lyricsMode, if (short) defaultAlpha else chromeAlpha, short) { onLyricsMode(true) }
                }
            },
            {
                if (showDefault) {
                    PhoneControls(
                        song = song,
                        position = position,
                        duration = duration,
                        playing = playing,
                        sleep = sleep,
                        deepLink = deepLink,
                        actions = actions,
                        idle = idle,
                        blocked = lyricsMode,
                        alpha = chromeAlpha,
                        waveScale = waveScale,
                        waveBpm = waveBpm,
                        onLyricsMode = onLyricsMode,
                        onOpenQueue = onOpenQueue,
                        onOpenTimer = onOpenTimer,
                        onOpenSource = onOpenSource,
                        short = short,
                    )
                }
            },
            { if (showDefault) PhoneNotice(notice, defaultAlpha) },
            // 矮窗口放不下三行歌词：空闲时封面旁那一行歌词留着就够了
            { if (showDefault && !short) PhoneIdleLyrics(lyrics, active, position, playing, chrome, defaultAlpha) },
            { if (showDefault) PhoneThinProgress(position, duration, chrome, defaultAlpha, song.isLive) },
            {
                if (showLyrics) {
                    PhoneLyricsPage(
                        song = song,
                        favorite = favorite,
                        lyrics = lyrics,
                        lyricsLoading = lyricsLoading,
                        active = active,
                        stage = stage,
                        position = position,
                        playing = playing,
                        actions = actions,
                        idle = idle,
                        chrome = chrome,
                        blocked = !lyricsMode,
                        alpha = lyricsAlpha,
                        onLyricsMode = onLyricsMode,
                        onOpenQueue = onOpenQueue,
                        onSkip = onSkip,
                    )
                }
            },
            {
                CoverStage(
                    cover = song.cover,
                    title = song.title,
                    trackKey = song.key,
                    playing = playing,
                    shapeChoice = coverShape,
                    pulse = pulse,
                    collapsed = lyricsMode,
                    transitionAnimation = transitionAnimation,
                    onSkip = onSkip,
                    onDoubleTap = onFavoriteBurst,
                    sharedElement = true,
                    // 歌词页左上角的小圆就是“回到封面”按钮；图层缩放后点按范围也跟着缩到小圆
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
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        measurePolicy = policy,
    )
}

/** 空闲（或歌词页淡出途中）拦截点按：唤醒的那一下不会点到看不见的按钮。只有这个小盒子随空闲状态重组。 */
@Composable
private fun IdleGuard(idle: IdleState, blocked: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.blockTouchesWhen(idle.idle || blocked)) { content() }
}

@Composable
private fun PhoneTopBar(
    song: Song,
    sourceProvider: String?,
    sourceDetail: String,
    playbackMode: String,
    deepLink: String,
    actions: MusicActions,
    onClose: () -> Unit,
    onOpenSource: () -> Unit,
    onLyricsMode: (Boolean) -> Unit,
    idle: IdleState,
    blocked: Boolean,
    alpha: () -> Float,
) {
    IdleGuard(idle, blocked, Modifier.graphicsLayer { this.alpha = alpha() }) {
        Row(
            Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "收起播放页", tint = MaterialTheme.colorScheme.onSurface) }
            // 顶部始终是音源；定时只在工具栏里显示剩余时间
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                SourcePill(song, sourceProvider, sourceDetail, playbackMode, onOpenSource)
            }
            MoreMenu(song, deepLink, actions, onOpenSource, onLyricsMode)
        }
    }
}

/** 矮窗口的顶栏：收起在左，歌词、队列、定时、更多靠右，取代底部的浮动工具栏；音源从更多菜单里换。 */
@Composable
private fun PhoneShortTopBar(
    song: Song,
    sleep: SleepTimerState,
    deepLink: String,
    actions: MusicActions,
    onClose: () -> Unit,
    onOpenSource: () -> Unit,
    onLyricsMode: (Boolean) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTimer: () -> Unit,
    idle: IdleState,
    blocked: Boolean,
    alpha: () -> Float,
) {
    IdleGuard(idle, blocked, Modifier.graphicsLayer { this.alpha = alpha() }) {
        Row(
            Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "收起播放页", tint = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.weight(1f))
            ToolbarLyricsButton(on = false, onToggle = onLyricsMode)
            ToolbarQueueButton(onOpenQueue)
            ToolbarSleepButton(sleep, onOpenTimer)
            MoreMenu(song, deepLink, actions, onOpenSource, onLyricsMode)
        }
    }
}

/**
 * 歌名 30sp 粗体，两行内放不下才逐级缩小；歌手名 primary 色。切歌时整块淡出淡入。
 * 矮窗口（[short]）里放在封面右边，歌名改成 22sp 起、最小 16sp。
 */
@Composable
private fun PhoneMeta(
    song: Song,
    favorite: Boolean,
    actions: MusicActions,
    idle: IdleState,
    chrome: State<Float>,
    blocked: Boolean,
    alpha: () -> Float,
    short: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        AnimatedContent(
            targetState = song,
            modifier = Modifier.weight(1f),
            transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)).using(null) },
            contentKey = { it.key },
            label = "title",
        ) { shown ->
            Column {
                Text(
                    shown.title,
                    style = if (short) {
                        MaterialTheme.typography.titleLarge.copy(lineHeight = 1.2.em)
                    } else {
                        MaterialTheme.typography.displaySmall.copy(lineHeight = 1.18.em)
                    },
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = if (short) {
                        TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 22.sp, stepSize = 2.sp)
                    } else {
                        TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = 30.sp, stepSize = 2.sp)
                    },
                )
                Text(
                    shown.artist,
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 空闲时收藏和其他控件一起淡出，歌名留着
        IdleGuard(idle, blocked, Modifier.graphicsLayer { this.alpha = chrome.value }) {
            FavoriteToggle(song, favorite, actions)
        }
    }
}

/**
 * 两行歌词：当前行逐字填色，下一行 40% 透明；点按进入歌词页。没有同步歌词时不占位。
 * 矮窗口（[short]）里放在歌名下面，只放当前这一行（折行最多占两行高），空闲时也不淡出：那时它就是封面旁唯一的歌词。
 */
@Composable
private fun PhoneLyricsPreview(
    lyrics: ParsedLyrics,
    active: State<Int>,
    position: State<Long>,
    playing: Boolean,
    idle: IdleState,
    blocked: Boolean,
    alpha: () -> Float,
    short: Boolean = false,
    onOpen: () -> Unit,
) {
    if (!lyrics.synced) return
    IdleGuard(
        idle,
        blocked,
        Modifier
            .fillMaxWidth()
            .height(if (short) ShortPreviewHeight else PreviewHeight)
            .clipToBounds()
            .graphicsLayer { this.alpha = alpha() },
    ) {
        // 空闲时预览淡出、换成三行歌词：这里的逐字填色停住，不再空跑帧循环。只有这个小块随空闲状态重组
        val awake = short || !idle.idle
        Box(Modifier.fillMaxSize().clickable(onClickLabel = "打开全屏歌词", onClick = onOpen)) {
            val index = active.value
            val line = lyrics.lines.getOrNull(index) ?: return@Box
            if (short) {
                CurrentLyric(line, position, playing, MaterialTheme.typography.bodyLarge.merge(ShortPreviewStyle))
                return@Box
            }
            val next = lyrics.lines.getOrNull(index + 1)?.text.orEmpty()
            Column {
                CurrentLyric(line, position, playing && awake, MaterialTheme.typography.bodyLarge.merge(PreviewCurrentStyle))
                if (next.isNotBlank()) {
                    Text(
                        next,
                        style = MaterialTheme.typography.bodyLarge.merge(PreviewNextStyle),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 空闲时的三行歌词：当前行 24sp 逐字填色，后两行 18sp，最后一行再淡一些。只在空闲（含淡入淡出途中）时组合。 */
@Composable
private fun PhoneIdleLyrics(
    lyrics: ParsedLyrics,
    active: State<Int>,
    position: State<Long>,
    playing: Boolean,
    chrome: State<Float>,
    pageAlpha: () -> Float,
) {
    val shown by remember(chrome) { derivedStateOf { chrome.value < 0.999f } }
    if (!shown || !lyrics.synced) return
    Box(Modifier.fillMaxWidth().clipToBounds().graphicsLayer { alpha = (1f - chrome.value) * pageAlpha() }) {
        val index = active.value
        val line = lyrics.lines.getOrNull(index) ?: return@Box
        val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
        val next = MaterialTheme.typography.bodyLarge.merge(IdleNextStyle)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CurrentLyric(line, position, playing, MaterialTheme.typography.bodyLarge.merge(IdleCurrentStyle))
            // 外语歌的翻译跟着当前行；这一页只露三行，后两行不挤翻译
            if (line.translation.isNotBlank()) {
                Text(
                    line.translation,
                    style = MaterialTheme.typography.bodyLarge.merge(IdleTranslationStyle),
                    color = dim.copy(alpha = dim.alpha * 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            lyrics.lines.getOrNull(index + 1)?.let { Text(it.text, style = next, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            lyrics.lines.getOrNull(index + 2)?.let {
                Text(it.text, style = next, color = dim.copy(alpha = dim.alpha * 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CurrentLyric(line: LyricLine, position: State<Long>, playing: Boolean, style: TextStyle) {
    if (line.words.isNotEmpty()) {
        KaraokeLine(line, position, playing, style)
    } else {
        Text(line.text, style = style, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 空闲时的细进度线（3dp）。进度只在绘制阶段读。 */
@Composable
private fun PhoneThinProgress(position: State<Long>, duration: Long, chrome: State<Float>, pageAlpha: () -> Float, live: Boolean = false) {
    val shown by remember(chrome) { derivedStateOf { chrome.value < 0.999f } }
    if (!shown) return
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val fill = MaterialTheme.colorScheme.primary
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .graphicsLayer { alpha = (1f - chrome.value) * pageAlpha() }
            .drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(track, cornerRadius = radius)
                // 直播没有可走的进度：HLS 会给个窗口时长，照画就成了假进度
                val fraction = if (!live && duration > 0) (position.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
                drawRoundRect(fill, size = Size(size.width * fraction, size.height), cornerRadius = radius)
            },
    )
}

@Composable
private fun PhoneControls(
    song: Song,
    position: State<Long>,
    duration: Long,
    playing: Boolean,
    sleep: SleepTimerState,
    deepLink: String,
    actions: MusicActions,
    idle: IdleState,
    blocked: Boolean,
    alpha: () -> Float,
    waveScale: () -> Float,
    waveBpm: () -> Float,
    onLyricsMode: (Boolean) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenSource: () -> Unit,
    short: Boolean = false,
) {
    IdleGuard(idle, blocked, Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha() }) {
        // 空闲时整块淡出：进度条按暂停拉平，波浪不在看不见的地方继续动。只有这一块随空闲状态重组
        val awake = !idle.idle
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            WavySeekBar(
                position,
                duration,
                playing && awake,
                actions::seek,
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                amplitudeScale = waveScale,
                waveBpm = waveBpm,
                live = song.isLive,
            )
            // 矮窗口：小号按钮组，工具栏已经并进顶栏
            TransportButtonGroup(
                playing = playing,
                onPrevious = { actions.transport("previous") },
                onPlayPause = { actions.transport("play") },
                onNext = { actions.transport("next") },
                modifier = Modifier.padding(top = 8.dp),
                size = if (short) TransportGroupSize.Compact else TransportGroupSize.Phone,
            )
            if (!short) {
                PlayerToolbar(Modifier.padding(top = 18.dp)) {
                    ToolbarLyricsButton(on = false, onToggle = onLyricsMode)
                    ToolbarQueueButton(onOpenQueue)
                    ToolbarSleepButton(sleep, onOpenTimer)
                    ToolbarSourceButton(onOpenSource)
                    MoreMenu(song, deepLink, actions, onOpenSource, onLyricsMode)
                }
            }
        }
    }
}

/** 换源结果：浮在按钮组上方 8 秒，不挤动布局。 */
@Composable
private fun PhoneNotice(notice: String, alpha: () -> Float) {
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
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 歌词页：左上角留给缩小成圆的封面（由封面舞台自己画），旁边是歌名、歌手、✦（歌词动画面板）和收藏；
 * 底部只有小号按钮组和“歌词 / 队列”，空闲时底部淡出并拦截点按（全屏歌词不放进度条）。
 * 中间是歌词舞台（[LyricStage]）：铺满整页，垫在顶栏和控件下面，文字排在两者之间的安全区里；
 * 经典模式就是原来的左对齐歌词列表，位置和原来一样（安全区 = 原来列表占的那块）。
 */
@Composable
private fun PhoneLyricsPage(
    song: Song,
    favorite: Boolean,
    lyrics: ParsedLyrics,
    lyricsLoading: Boolean,
    active: State<Int>,
    stage: StageBinding,
    position: State<Long>,
    playing: Boolean,
    actions: MusicActions,
    idle: IdleState,
    chrome: State<Float>,
    blocked: Boolean,
    alpha: () -> Float,
    onLyricsMode: (Boolean) -> Unit,
    onOpenQueue: () -> Unit,
    onSkip: (Int) -> Unit,
) {
    val insets = remember { StageInsets() }
    val bottomChrome = LocalPlayerBottomChrome.current
    DisposableEffect(bottomChrome) { onDispose { bottomChrome.clear(PlayerBottomChrome.PanelLyrics) } }
    Layout(
        contents = listOf<@Composable () -> Unit>(
            {
                LyricStage(
                    mode = stage.mode,
                    lyrics = stage.lyrics,
                    loading = lyricsLoading,
                    position = position,
                    playing = playing,
                    song = song,
                    prefs = stage.prefs,
                    textSp = PHONE_STAGE_SP,
                    insets = insets,
                    modifier = Modifier.fillMaxSize(),
                    seek = actions::seek,
                ) { modifier ->
                    LyricsList(
                        lyrics,
                        active,
                        actions::seek,
                        modifier,
                        loading = lyricsLoading,
                        position = position,
                        playing = playing,
                        sizing = LyricsSizing.PhoneLyrics,
                    )
                }
            },
            {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = LyricsCoverStart + LyricsCoverSize + 14.dp, end = 8.dp, top = LyricsCoverTop)
                        .height(LyricsCoverSize),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            song.artist,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AnimationSheetButton(hint = !stage.prefs.hintSeen, onClick = stage.onOpenSheet)
                    FavoriteToggle(song, favorite, actions)
                }
            },
            {
                // 全屏歌词不放进度条（要跳转点歌词行就行），底部只留小号按钮组和工具栏
                IdleGuard(idle, false, Modifier.padding(start = 20.dp, end = 16.dp, bottom = 8.dp).graphicsLayer { this.alpha = chrome.value }) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TransportButtonGroup(
                            playing = playing,
                            onPrevious = { onSkip(-1) },
                            onPlayPause = { actions.transport("play") },
                            onNext = { onSkip(1) },
                            size = TransportGroupSize.Compact,
                        )
                        PlayerToolbar {
                            ToolbarLyricsButton(on = true, onToggle = onLyricsMode)
                            ToolbarQueueButton(onOpenQueue)
                        }
                    }
                }
            },
        ),
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha() }.blockTouchesWhen(blocked),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val header = measurables[1].first().measure(Constraints(maxWidth = width, maxHeight = height))
        val controls = measurables[2].first().measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
        bottomChrome.report(PlayerBottomChrome.PanelLyrics, controls.height)
        // 安全区 = 原来歌词列表占的那块：顶栏下面、控件上面，左右各 16dp
        val side = 16.dp.toPx()
        insets.set(top = header.height.toFloat(), bottom = controls.height.toFloat(), start = side, end = side)
        val stagePlaceable = measurables[0].first().measure(Constraints.fixed(width, height))
        layout(width, height) {
            stagePlaceable.place(0, 0)
            header.place(0, 0)
            controls.place(0, height - controls.height)
        }
    }
}

/**
 * 手机播放页的摆放：顶栏贴顶，控件区贴底，中间按 [phoneStageSlots] 分给封面、标题和歌词预览；
 * 预览的占位按 [previewSpace]（0..1）收放，只在收放的那一下逐帧重新测量。
 * 歌词页时封面图层以左上角为原点缩放平移到 [LyricsCoverSize] 的小圆处；进度只在图层里读，不重新测量。
 * 歌词页稳定后默认界面已移出组合，封面仍按最后一次的默认位置算起点，回到默认页时没有跳变。
 * 封面最先摆放，光晕垫在标题、收藏和顶栏下面；[coverOnTop]（要进歌词页）时提到最上层，小圆封面盖在歌词页上。
 * [short]（矮窗口）时按 [phoneShortSlots] 摆放。
 */
private class PhoneStagePolicy(
    private val coverMove: State<Float>,
    private val previewSpace: State<Float>,
    private val coverOnTop: State<Boolean>,
    private val short: Boolean,
    private val bottomChrome: PlayerBottomChrome,
) : MultiContentMeasurePolicy {
    private var lastCover = 0
    private var lastCoverX = 0
    private var lastCoverY = 0
    private var lastMetaY = 0
    private var lastPreviewY = 0
    private var lastShortControls = -1

    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        if (short) return measureShort(measurables, constraints)
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val full = Constraints(maxWidth = width, maxHeight = height)
        val side = 24.dp.roundToPx()
        val inner = (width - side * 2).coerceAtLeast(0)
        val top = measurables[SlotTop].firstOrNull()?.measure(full)
        val meta = measurables[SlotMeta].firstOrNull()?.measure(Constraints.fixedWidth((width - 40.dp.roundToPx()).coerceAtLeast(0)))
        val previewBudget = (PreviewHeight.toPx() * previewSpace.value).roundToInt()
        val preview = measurables[SlotPreview].firstOrNull()?.measure(Constraints(maxWidth = inner, maxHeight = previewBudget))
        val controls = measurables[SlotControls].firstOrNull()?.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
        val bottomMargin = 8.dp.roundToPx()
        val controlsY = height - bottomMargin - (controls?.height ?: 0)
        // 歌词页稳定后默认控制区已移出组合：这时不报，让歌词页那一份说了算
        if (controls != null) bottomChrome.report(PlayerBottomChrome.Panel, height - controlsY)

        if (meta != null && controls != null) {
            val slots = phoneStageSlots(
                top = top?.height ?: TopBarHeight.roundToPx(),
                bottom = controlsY,
                meta = meta.height,
                preview = previewBudget,
                coverMax = (width - 80.dp.roundToPx()).coerceIn(CoverMin.roundToPx(), CoverMax.roundToPx()),
                coverMin = CoverMin.roundToPx(),
                ideal = IntArray(4) { IdealGaps[it].roundToPx() },
                min = IntArray(4) { MinGaps[it].roundToPx() },
            )
            lastCover = slots.cover
            lastCoverX = (width - slots.cover) / 2
            lastCoverY = slots.coverTop
            lastMetaY = slots.metaTop
            lastPreviewY = slots.previewTop
        } else if (lastCover == 0) {
            // 直接在歌词页打开（例如旋转屏幕后）：按理想尺寸估一个起点
            lastCover = (width - 80.dp.roundToPx()).coerceIn(CoverMin.roundToPx(), CoverMax.roundToPx())
            lastCoverX = (width - lastCover) / 2
            lastCoverY = TopBarHeight.roundToPx() + IdealGaps[0].roundToPx()
            lastMetaY = lastCoverY + lastCover + IdealGaps[1].roundToPx()
            lastPreviewY = lastMetaY
        }
        val coverSize = lastCover
        val coverX = lastCoverX
        val coverY = lastCoverY
        val metaY = lastMetaY
        val previewY = lastPreviewY

        val notice = measurables[SlotNotice].firstOrNull()?.measure(Constraints(maxWidth = inner))
        val thinY = height - bottomMargin - 30.dp.roundToPx()
        val idleLyrics = measurables[SlotIdleLyrics].firstOrNull()
            ?.measure(Constraints(maxWidth = inner, maxHeight = (thinY - 16.dp.roundToPx() - previewY).coerceAtLeast(0)))
        val thin = measurables[SlotThin].firstOrNull()?.measure(Constraints.fixedWidth(inner))
        val lyricsPage = measurables[SlotLyrics].firstOrNull()?.measure(Constraints.fixed(width, height))
        val cover = measurables[SlotCover].first().measure(Constraints.fixed(coverSize, coverSize))

        val miniSize = LyricsCoverSize.toPx()
        val anchorX = LyricsCoverStart.toPx()
        val anchorY = LyricsCoverTop.toPx()
        return layout(width, height) {
            // 最先摆放：光晕画在封面边界外（1.7 倍），压在歌名、收藏和顶栏上会让字发灰（Android 12 以下约 25%）。
            // 进歌词页时（含收起途中）提到最上层，小圆封面盖在歌词页上；回封面时一开始就放回下面，光晕淡入时已在字下
            cover.placeWithLayer(coverX, coverY, zIndex = if (coverOnTop.value) 1f else 0f) {
                val t = coverMove.value
                transformOrigin = TransformOrigin(0f, 0f)
                val scale = lerp(1f, miniSize / coverSize.coerceAtLeast(1), t)
                scaleX = scale
                scaleY = scale
                translationX = (anchorX - coverX) * t
                translationY = (anchorY - coverY) * t
            }
            top?.place(0, 0)
            meta?.place(side, metaY)
            preview?.place(side, previewY)
            controls?.place(0, controlsY)
            idleLyrics?.place(side, previewY)
            thin?.place(side, thinY)
            notice?.let { it.place((width - it.width) / 2, controlsY - it.height - 6.dp.roundToPx()) }
            lyricsPage?.place(0, 0)
        }
    }

    /**
     * 矮窗口：顶栏贴顶，进度 + 小号按钮组贴底，中间一行左边封面、右边歌名和一行歌词（[phoneShortSlots]）。
     * 封面位置不依赖量到的文字，歌词页稳定后（控件已移出组合）按上一次量到的控件高度照样算出来，回封面时没有跳变。
     */
    private fun MeasureScope.measureShort(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val full = Constraints(maxWidth = width, maxHeight = height)
        val top = measurables[SlotTop].firstOrNull()?.measure(full)
        val controls = measurables[SlotControls].firstOrNull()?.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
        controls?.let { lastShortControls = it.height }
        if (lastShortControls < 0) lastShortControls = ShortControlsEstimate.roundToPx()
        val slots = phoneShortSlots(width, height, top?.height ?: TopBarHeight.roundToPx(), lastShortControls, density)
        bottomChrome.report(PlayerBottomChrome.Panel, height - slots.controlsY)

        val meta = measurables[SlotMeta].firstOrNull()?.measure(Constraints.fixedWidth(slots.textWidth))
        val metaHeight = meta?.height ?: 0
        val textGap = 6.dp.roundToPx()
        val previewBudget = (slots.areaBottom - slots.areaTop - metaHeight - textGap).coerceIn(0, ShortPreviewHeight.roundToPx())
        val preview = measurables[SlotPreview].firstOrNull()?.measure(Constraints(maxWidth = slots.textWidth, maxHeight = previewBudget))
        // 歌名和那一行歌词作为一块，对着封面竖向居中；比封面高时往下伸，但不过控件区
        val block = metaHeight + (preview?.let { textGap + it.height } ?: 0)
        val textY = (slots.coverTop + (slots.cover - block) / 2)
            .coerceAtMost(slots.areaBottom - block)
            .coerceAtLeast(slots.areaTop)

        val notice = measurables[SlotNotice].firstOrNull()?.measure(Constraints(maxWidth = (width - 48.dp.roundToPx()).coerceAtLeast(0)))
        val side = 24.dp.roundToPx()
        val thin = measurables[SlotThin].firstOrNull()?.measure(Constraints.fixedWidth((width - side * 2).coerceAtLeast(0)))
        val lyricsPage = measurables[SlotLyrics].firstOrNull()?.measure(Constraints.fixed(width, height))
        val coverSize = slots.cover
        val cover = measurables[SlotCover].first().measure(Constraints.fixed(coverSize, coverSize))

        val miniSize = LyricsCoverSize.toPx()
        val anchorX = LyricsCoverStart.toPx()
        val anchorY = LyricsCoverTop.toPx()
        return layout(width, height) {
            // 同默认排法：封面最先摆放，进歌词页时提到最上层
            cover.placeWithLayer(slots.coverX, slots.coverTop, zIndex = if (coverOnTop.value) 1f else 0f) {
                val t = coverMove.value
                transformOrigin = TransformOrigin(0f, 0f)
                val scale = lerp(1f, miniSize / coverSize.coerceAtLeast(1), t)
                scaleX = scale
                scaleY = scale
                translationX = (anchorX - slots.coverX) * t
                translationY = (anchorY - slots.coverTop) * t
            }
            top?.place(0, 0)
            meta?.place(slots.textX, textY)
            preview?.place(slots.textX, textY + metaHeight + textGap)
            controls?.place(0, slots.controlsY)
            // 空闲时的细进度线落在波浪进度的中线上（进度区 32dp 高）
            thin?.let { it.place(side, slots.controlsY + 16.dp.roundToPx() - it.height / 2) }
            notice?.let { it.place((width - it.width) / 2, slots.controlsY - it.height - 6.dp.roundToPx()) }
            lyricsPage?.place(0, 0)
        }
    }
}

/** [phoneShortSlots] 的结果（像素）：封面边长和位置、右边文字列的起点和宽，中间一行的上下界，以及控件区顶边。 */
internal class PhoneShortSlots(
    val cover: Int,
    val coverX: Int,
    val coverTop: Int,
    val textX: Int,
    val textWidth: Int,
    val areaTop: Int,
    val areaBottom: Int,
    val controlsY: Int,
)

/**
 * 矮窗口（约 400×400dp）的摆放（像素），[dp] 是每 dp 的像素数：[top] 是顶栏底边，[controls] 是进度加小号按钮组的高度，
 * 贴底、离底边 8dp。中间一行（顶栏下 4dp 到控件区上 12dp）左边是封面，取这一行的高、窗口宽的 40%、168dp 三者最小，
 * 离左边 20dp、竖向居中；封面右边 16dp 起是歌名和一行歌词，右边留 12dp。封面不超过这一行，所以和顶栏、控件都不重叠。
 */
internal fun phoneShortSlots(width: Int, height: Int, top: Int, controls: Int, dp: Float): PhoneShortSlots {
    val controlsY = height - (8 * dp).roundToInt() - controls
    val areaTop = top + (4 * dp).roundToInt()
    val areaBottom = (controlsY - (12 * dp).roundToInt()).coerceAtLeast(areaTop)
    val room = areaBottom - areaTop
    val cover = minOf(room.toFloat(), width * 0.4f, 168 * dp).toInt().coerceAtLeast(0)
    val coverX = (20 * dp).roundToInt()
    val coverTop = areaTop + (room - cover) / 2
    val textX = coverX + cover + (16 * dp).roundToInt()
    val textWidth = (width - textX - (12 * dp).roundToInt()).coerceAtLeast(0)
    return PhoneShortSlots(cover, coverX, coverTop, textX, textWidth, areaTop, areaBottom, controlsY)
}

/** [phoneStageSlots] 的结果（像素）。 */
internal class PhoneStageSlots(val cover: Int, val coverTop: Int, val metaTop: Int, val previewTop: Int)

/**
 * 手机播放页中段的竖向分配（像素）：[top] 是顶栏底边，[bottom] 是控件区顶边，[meta] 是标题区实际高度，
 * [preview] 是给两行歌词预留的高度（没有同步歌词时为 0，收放过渡中是中间值；空间富余时封面大小不变，只是整组重新居中）。
 * 先保证封面 [coverMax]，空间不够时先把四段间距（顶栏→封面、封面→标题、标题→歌词、歌词→控件）从理想值压向最小值，
 * 还不够才缩封面，最小 [coverMin]；空间有富余时平分到封面上方和控件上方，让中间这组居中。
 */
internal fun phoneStageSlots(
    top: Int,
    bottom: Int,
    meta: Int,
    preview: Int,
    coverMax: Int,
    coverMin: Int,
    ideal: IntArray,
    min: IntArray,
): PhoneStageSlots {
    val room = bottom - top - meta - preview
    val idealSum = ideal.sum()
    val minSum = min.sum()
    val gaps = IntArray(4)
    val cover: Int
    when {
        room >= coverMax + idealSum -> {
            cover = coverMax
            ideal.copyInto(gaps)
            val slack = room - coverMax - idealSum
            gaps[0] += slack / 2
            gaps[3] += slack - slack / 2
        }
        room >= coverMax + minSum -> {
            cover = coverMax
            val fraction = (room - coverMax - minSum).toFloat() / (idealSum - minSum).coerceAtLeast(1)
            for (i in gaps.indices) gaps[i] = min[i] + ((ideal[i] - min[i]) * fraction).toInt()
        }
        else -> {
            cover = (room - minSum).coerceAtLeast(coverMin)
            min.copyInto(gaps)
        }
    }
    val coverTop = top + gaps[0]
    val metaTop = coverTop + cover + gaps[1]
    return PhoneStageSlots(cover, coverTop, metaTop, metaTop + meta + gaps[2])
}

private const val SlotTop = 0
private const val SlotMeta = 1
private const val SlotPreview = 2
private const val SlotControls = 3
private const val SlotNotice = 4
private const val SlotIdleLyrics = 5
private const val SlotThin = 6
private const val SlotLyrics = 7
private const val SlotCover = 8

private val TopBarHeight = 52.dp
private val PreviewHeight = 56.dp
private val CoverMax = 320.dp
private val CoverMin = 200.dp
private val IdealGaps = listOf(18.dp, 22.dp, 20.dp, 14.dp)
private val MinGaps = listOf(8.dp, 14.dp, 12.dp, 10.dp)
private val LyricsCoverSize = 52.dp
private val LyricsCoverStart = 20.dp
private val LyricsCoverTop = 12.dp

/** 手机歌词页舞台的正文字号（sp）。 */
private const val PHONE_STAGE_SP = 30f

/** 矮窗口：封面旁那一行歌词最多折成两行的高度；控件区（进度 + 8dp + 56dp 小号按钮组）的估计高度。 */
private val ShortPreviewHeight = 48.dp
private val ShortControlsEstimate = 112.dp

private val PreviewCurrentStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
private val ShortPreviewStyle = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
private val PreviewNextStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
private val IdleCurrentStyle = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold)
private val IdleNextStyle = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
private val IdleTranslationStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
