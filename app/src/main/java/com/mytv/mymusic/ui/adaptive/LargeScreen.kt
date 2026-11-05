package com.mytv.mymusic.ui.adaptive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.aggregate.PlaybackMode
import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.SleepTimerState
import com.mytv.mymusic.ui.Cover
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.TopTab
import com.mytv.mymusic.ui.openLabel
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.PlayButtonSize
import com.mytv.mymusic.ui.components.PlayPauseButton
import com.mytv.mymusic.ui.components.SourceChip
import com.mytv.mymusic.ui.components.WavySeekBar
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.player.CoverStage
import com.mytv.mymusic.ui.player.TransportButtonGroup
import com.mytv.mymusic.ui.player.TransportGroupSize
import com.mytv.mymusic.ui.player.sleepLabel
import com.mytv.mymusic.ui.theme.LocalBlurPolicy
import com.mytv.mymusic.ui.theme.PlayerTheme
import com.mytv.mymusic.ui.theme.frosted

/** 平板导航栏：四个入口在上，设置在底部。FAB 菜单不和导航栏同屏，新建入口改在页面里用 Split button。 */
@Composable
fun TabletNavRail(selected: String, onSelect: (String) -> Unit, onSettings: () -> Unit) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight().width(96.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Spacer(Modifier.height(20.dp))
        TopTab.entries.forEach { tab ->
            val on = selected == tab.route
            NavigationRailItem(
                selected = on,
                onClick = { onSelect(tab.route) },
                icon = { Icon(if (on) tab.selectedIcon else tab.icon, null) },
                label = { Text(tab.label) },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        val on = selected == "settings"
        NavigationRailItem(
            selected = on,
            onClick = onSettings,
            icon = { Icon(if (on) Icons.Filled.Settings else Icons.Outlined.Settings, null) },
            label = { Text("设置") },
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

/** 底部播放条高度（不含系统导航栏），Snackbar 要让开这么多。 */
val BottomPlayerBarHeight = 88.dp

/** 播放条按折痕分两段时每段至少这么宽：左段要放封面、歌名和上一首 / 播放 / 下一首。 */
internal val BottomPlayerBarSplitMin = 260.dp

/**
 * 840–1199dp：底部播放条，把右侧空间还给内容。
 * [visible] 为 false 时只保留同高占位，不读取进度、不保留波浪/时间观察。
 */
@Composable
fun BottomPlayerBar(
    state: MusicUiState,
    position: State<Long>,
    actions: MusicActions,
    onOpenQueue: () -> Unit,
    visible: Boolean = true,
) {
    if (!visible) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(BottomPlayerBarHeight),
        )
        return
    }
    val song = state.current ?: return
    val blur = LocalBlurPolicy.current.enabled
    val container = MaterialTheme.colorScheme.surfaceContainer
    // 播放条横跨侧栏右边的整块内容区，折痕要按它自己的位置量（扣掉左右留白后）
    val anchor = rememberFoldAnchor()
    // 只在竖折痕（书本、双竖）上分两段；左段放不下封面、歌名和三个按钮时当没有折痕。
    // 没有折痕时不读 anchor：平板首次摆放后不必为它多重组一次
    val folded = LocalAppWindow.current.fold != null
    val split = if (folded) rememberFoldSplit(anchor, minSegment = BottomPlayerBarSplitMin)?.takeIf { it.vertical } else null
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .frosted(if (blur) container.copy(alpha = 0.85f) else container)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(BottomPlayerBarHeight)
            .padding(horizontal = 16.dp)
            .foldAnchor(anchor),
    ) {
        // 分两段时左页只有约 290dp（Pixel Fold），竖着的平板整条也只有约 570dp：
        // 封面、间距和播放键都收小一号，给歌名多留点地方
        val compact = split != null || maxWidth < BottomPlayerBarCompactBelow
        val info: @Composable (Modifier) -> Unit = { modifier ->
            Row(
                modifier
                    .clip(RoundedCornerShape(16.dp))
                    // 整块（封面 + 歌名）按设置去播放页或海报墙；右边的“打开播放页”按钮总是打开播放页
                    .clickable(onClickLabel = state.miniPlayerTarget.openLabel, onClick = actions::openNowPlaying)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
            ) {
                Cover(song.cover, song.title, if (compact) 48.dp else 56.dp, corner = if (compact) 12.dp else 14.dp)
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${song.artist} · ${state.currentSource?.providerName ?: song.source}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val transport: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else BottomPlayerBarGap),
            ) {
                IconButton(onClick = { actions.transport("previous") }) { Icon(Icons.Filled.SkipPrevious, "上一首") }
                PlayPauseButton(state.playing, { actions.transport("play") }, size = if (compact) PlayButtonSize.Small else PlayButtonSize.Medium)
                IconButton(onClick = { actions.transport("next") }) { Icon(Icons.Filled.SkipNext, "下一首") }
            }
        }
        val seek: @Composable (Modifier) -> Unit = { modifier ->
            WavySeekBar(
                position = position,
                duration = state.duration,
                playing = state.playing,
                onSeek = actions::seek,
                modifier = modifier,
            )
        }
        val tail: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else BottomPlayerBarGap),
            ) {
                IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列") }
                IconButton(onClick = actions::openPlayer) { Icon(Icons.Filled.OpenInFull, "打开播放页") }
            }
        }
        if (split == null) {
            // 播放键组和右边两个钮按实际大小先量，剩下的再分给歌名和进度条（见 bottomBarInfoWidth）
            Layout(
                content = {
                    info(Modifier)
                    transport()
                    seek(Modifier)
                    tail()
                },
                modifier = Modifier.fillMaxSize(),
            ) { measurables, constraints ->
                val height = constraints.maxHeight
                val gap = (if (compact) BottomPlayerBarCompactGap else BottomPlayerBarGap).roundToPx()
                val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = height)
                val transportP = measurables[1].measure(loose)
                val tailP = measurables[3].measure(loose)
                val rest = (constraints.maxWidth - transportP.width - tailP.width - gap * 3).coerceAtLeast(0)
                val infoW = bottomBarInfoWidth(rest.toDp()).roundToPx().coerceIn(0, rest)
                val seekW = rest - infoW
                val infoP = measurables[0].measure(Constraints(minWidth = infoW, maxWidth = infoW, maxHeight = height))
                val seekP = measurables[2].measure(Constraints(minWidth = seekW, maxWidth = seekW, maxHeight = height))
                layout(constraints.maxWidth, height) {
                    var x = 0
                    listOf(infoP, transportP, seekP, tailP).forEach { p ->
                        p.place(x, (height - p.height) / 2)
                        x += p.width + gap
                    }
                }
            }
        } else {
            // 书本、双竖：左页封面、歌名、上一首 / 播放 / 下一首，右页进度、队列、展开；折痕两侧留空，什么都不压
            val density = LocalDensity.current
            val start = with(density) { split.firstEnd.toDp() }
            val gap = with(density) { (split.secondStart - split.firstEnd).toDp() }
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(start), verticalAlignment = Alignment.CenterVertically) {
                    info(Modifier.weight(1f))
                    transport()
                }
                Spacer(Modifier.width(gap))
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BottomPlayerBarGap),
                ) {
                    seek(Modifier.weight(1f))
                    tail()
                }
            }
        }
    }
}

/** 播放条里各块之间的间距；紧凑时收到 8dp，省下来给歌名。 */
private val BottomPlayerBarGap = 12.dp
internal val BottomPlayerBarCompactGap = 8.dp

/** 播放条内容区（扣掉左右 16dp）窄于这个宽度时用小一号的封面和播放键：竖着的平板约 570dp，横着的 840dp 起约 710dp。 */
internal val BottomPlayerBarCompactBelow = 640.dp

/**
 * 歌名块最宽 280dp；地方够时至少 184dp——扣掉 4dp 内边距、48dp 封面和 8dp 间距后还有 120dp，
 * 放得下 7 个 titleMedium 汉字（16sp + 0.15sp 字距），但进度条要先留够 [BottomPlayerBarSeekMin]。
 */
internal val BottomPlayerBarInfoMax = 280.dp
internal val BottomPlayerBarInfoMin = 184.dp
internal val BottomPlayerBarSeekMin = 120.dp

/**
 * 不分两段时歌名块的宽：[rest] 是扣掉播放键组、队列 / 展开两个钮和间距后，歌名和进度条一起分的宽度。
 * 歌名块拿一半，最多 [BottomPlayerBarInfoMax]；一半不到 [BottomPlayerBarInfoMin] 时补到它，
 * 但不挤得进度条短于 [BottomPlayerBarSeekMin]——再窄就两边一起收。
 */
internal fun bottomBarInfoWidth(rest: Dp): Dp {
    val floor = minOf(BottomPlayerBarInfoMin, (rest - BottomPlayerBarSeekMin).coerceAtLeast(0.dp))
    return (rest * 0.5f).coerceIn(floor, BottomPlayerBarInfoMax)
}

/**
 * ≥1200dp：右侧常驻 380dp 播放窗格，使用播放页的深色 Vibrant 配色。
 * [visible] 为 false 时只保留同宽占位，不读取进度、不保留波浪/字体/倒计时观察。
 */
@Composable
fun NowPlayingPane(
    state: MusicUiState,
    position: State<Long>,
    favorites: List<SavedSong>,
    actions: MusicActions,
    onOpenQueue: () -> Unit,
    onOpenTimer: () -> Unit,
    sleep: SleepTimerState,
    visible: Boolean = true,
) {
    // 滚动状态放在提前返回之前：窗格隐藏再出现时仍停在原来的位置。
    val scrollState = rememberScrollState()
    if (!visible) {
        // 占位也使用播放页深色主题，但仍提前返回，不组合进度、队列等动态内容。
        // 占位没有可聚焦节点，这里也不主动 requestFocus，窗格恢复时不会抢走页面焦点。
        PlayerTheme {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(380.dp)
                    .statusBarsPadding()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .navigationBarsPadding(),
            )
        }
        return
    }
    PlayerTheme {
        Box(
            Modifier
                .fillMaxHeight()
                .width(380.dp)
                // 状态栏图标跟浏览页主题走（浅色主题下是深色图标），深色窗格不能铺到状态栏下面
                .statusBarsPadding()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .navigationBarsPadding(),
        ) {
            val song = state.current
            if (song == null) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.MusicNote, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Text("还没有在播放", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "从榜单、歌单或搜索结果开始，播放器会一直留在这里。",
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                return@Box
            }
            val favorite = remember(favorites, song.key) { favorites.any { it.key == song.key } }
            Column(
                Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PaneCover(song, state.playing, state.coverShape, state.miniPlayerTarget.openLabel, actions::openNowPlaying)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(song.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    FilledTonalIconToggleButton(
                        checked = favorite,
                        onCheckedChange = { actions.favorite(song) },
                        modifier = Modifier.tvFocusRing(CircleShape),
                    ) {
                        Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (favorite) "取消收藏" else "收藏")
                    }
                }
                state.currentSource?.let { source ->
                    SourceChip(
                        providerName = source.providerName,
                        providerId = source.providerId,
                        detail = source.qualityLabel.takeUnless { it == "音质未知" }.orEmpty(),
                        mode = modeLabel(state.aggregation.playbackMode),
                        onClick = {
                            state.currentTrack?.let { track -> actions.openSource(track, state.canonicalQueue.ifEmpty { listOf(track) }) }
                        },
                    )
                }
                WavySeekBar(position, state.duration, state.playing, actions::seek)
                // 和播放页同一个连接按钮组，用小号
                TransportButtonGroup(
                    playing = state.playing,
                    onPrevious = { actions.transport("previous") },
                    onPlayPause = { actions.transport("play") },
                    onNext = { actions.transport("next") },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    size = TransportGroupSize.Compact,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = onOpenTimer) {
                        Icon(Icons.Filled.Bedtime, if (sleep.active) "定时关闭：${sleepLabel(sleep)}" else "定时关闭", tint = if (sleep.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = actions::openPlayer) { Icon(Icons.Filled.OpenInFull, "全屏播放", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                val index = state.queue.indexOfFirst { it.key == song.key }
                val upcomingStart = index + 1
                val upcomingEnd = (upcomingStart + 5).coerceAtMost(state.queue.size)
                if (index >= 0 && upcomingStart < upcomingEnd) {
                    Text("接下来播放", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    for (queueIndex in upcomingStart until upcomingEnd) {
                        val next = state.queue[queueIndex]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { actions.playQueueIndex(queueIndex) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Cover(next.cover, next.title, 44.dp, corner = 10.dp)
                            Column(Modifier.weight(1f)) {
                                Text(next.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(next.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 窗格封面：和播放页同一个形状封面（同一首歌同一种形状，播放时轮廓慢转，暂停变圆角方形），不带光晕和手势：
 * 光晕会被窗格的滚动区域裁出硬边。整块按设置点开播放页或海报墙，按下时略微缩小；读屏只读 [openLabel]。
 */
@Composable
private fun PaneCover(song: Song, playing: Boolean, shape: CoverShapeChoice, openLabel: String, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = animateFloatAsState(if (pressed) 0.96f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "paneCoverPress")
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            // 形状封面不是矩形，矩形水波纹不合适，按压反馈交给缩放
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            // 封面图本身不读，可点的封面要有名字
            .semantics { contentDescription = openLabel },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = press.value
                    scaleY = press.value
                }
                // 封面舞台自带“某某的封面”描述，这里统一读外层的 openLabel
                .clearAndSetSemantics {},
        ) {
            CoverStage(
                cover = song.cover,
                title = song.title,
                trackKey = song.key,
                playing = playing,
                shapeChoice = shape,
                modifier = Modifier.fillMaxSize(),
                halo = false,
            )
        }
    }
}

fun modeLabel(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.Auto -> "自动"
    PlaybackMode.Priority -> "优先"
    PlaybackMode.Pinned -> "固定"
}
