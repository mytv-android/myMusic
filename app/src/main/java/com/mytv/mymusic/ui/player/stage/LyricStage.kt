package com.mytv.mymusic.ui.player.stage

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.LyricStagePrefs
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.PlaybackClock
import com.mytv.mymusic.playback.RhythmAnalyzer
import com.mytv.mymusic.playback.RhythmTimeline
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.player.RhythmFeed
import com.mytv.mymusic.ui.player.TvFrameGate
import com.mytv.mymusic.ui.player.blobColorCache
import com.mytv.mymusic.ui.player.coverBlobColors
import com.mytv.mymusic.ui.player.rememberSystemAnimationsOff
import com.mytv.mymusic.ui.theme.LocalAppearance
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * 全屏歌词舞台的宿主：按 [mode] 画歌词动画，铺满整页，文字排在 [insets] 划出的安全区里。
 *
 * - 经典模式、没有同步歌词（加载中、未同步的纯文本、没有歌词且模式不画空场）、窗口高度不到 320dp 时，
 *   交给 [classic]（现有的 LyricsList，参数原样），画面和原来一个像素都不差；[classic] 为 null 时什么都不画。
 * - 其他模式：一个帧循环（电视限 30 帧）推进 [StageClock]、当前句和律动；模式切换时交叉淡入淡出。
 *   页面不在组合里时循环自然停；暂停时只在跳转后更新一次时间；减少动态效果时不跑循环，只在换句时更新。
 * - 宿主副行（翻译或罗马音）画在安全区底部，开着频谱时往上让 70dp；频谱图层在左下角。
 */
@Composable
internal fun LyricStage(
    mode: LyricVisualizer,
    lyrics: ParsedLyrics,
    loading: Boolean,
    position: State<Long>,
    playing: Boolean,
    song: Song,
    prefs: LyricStagePrefs,
    textSp: Float,
    insets: StageInsets,
    modifier: Modifier = Modifier,
    edgeFade: Dp = 0.dp,
    classic: (@Composable (Modifier) -> Unit)? = null,
) {
    BoxWithConstraints(modifier) {
        val noLyrics = lyrics.raw.isBlank() && !loading
        val tooShort = maxHeight < MIN_STAGE_HEIGHT
        val useClassic = mode.id == ClassicVisualizer.id || tooShort ||
            (noLyrics && !mode.drawsWithoutLyrics) || (!noLyrics && !lyrics.synced)
        if (useClassic) {
            classic?.invoke(Modifier.fillMaxSize().stageSafePadding(insets))
            return@BoxWithConstraints
        }
        Crossfade(mode, Modifier.fillMaxSize(), MaterialTheme.motionScheme.defaultEffectsSpec(), label = "lyricStageMode") { shown ->
            StageHost(shown, lyrics, position, playing, song, prefs, textSp, insets, edgeFade)
        }
    }
}

/**
 * 播放页传给各排法的舞台参数：正在显示的模式、动画设置、没按“歌词下面那一行”改写过的歌词（舞台自己按设置取副行），
 * 和打开动画面板。
 */
@androidx.compose.runtime.Immutable
internal class StageBinding(
    val mode: LyricVisualizer,
    val prefs: LyricStagePrefs,
    val lyrics: ParsedLyrics,
    val onOpenSheet: () -> Unit,
) {
    /** 不是经典：舞台接管整页（手机歌词页、平板专注、电视环境模式）。 */
    val custom: Boolean get() = mode.id != ClassicVisualizer.id
}

/** 经典列表放进安全区：按安全区留边，安全区在测量阶段才知道，这里只在布局阶段读。 */
internal fun Modifier.stageSafePadding(insets: StageInsets): Modifier = layout { measurable, constraints ->
    val left = insets.start.toInt()
    val top = insets.top.toInt()
    val horizontal = left + insets.end.toInt()
    val vertical = top + insets.bottom.toInt()
    val width = if (constraints.hasBoundedWidth) (constraints.maxWidth - horizontal).coerceAtLeast(0) else Constraints.Infinity
    val height = if (constraints.hasBoundedHeight) (constraints.maxHeight - vertical).coerceAtLeast(0) else Constraints.Infinity
    val placeable = measurable.measure(
        Constraints(
            minWidth = width.takeIf { constraints.hasBoundedWidth && constraints.minWidth > 0 } ?: 0,
            maxWidth = width,
            minHeight = height.takeIf { constraints.hasBoundedHeight && constraints.minHeight > 0 } ?: 0,
            maxHeight = height,
        ),
    )
    layout(constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: placeable.width + horizontal,
        constraints.maxHeight.takeIf { constraints.hasBoundedHeight } ?: placeable.height + vertical) {
        placeable.place(left, top)
    }
}

/**
 * 舞台上下边缘渐隐 [edge]（最多各占四分之一高）。桌面姿态的上半屏夹在顶栏和折痕之间，年轮、波光这类铺开的效果
 * 在两条边上硬切很显眼；整块离屏合成一次再用竖向遮罩擦。调用方要把文字安全区让出这两条带。
 */
private fun Modifier.stageEdgeFade(edge: Dp): Modifier {
    if (edge <= 0.dp) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val band = if (size.height > 0f) (edge.toPx() / size.height).coerceAtMost(0.25f) else 0f
            val mask = Brush.verticalGradient(
                0f to Color.Transparent,
                band to Color.Black,
                1f - band to Color.Black,
                1f to Color.Transparent,
            )
            onDrawWithContent {
                drawContent()
                if (band > 0f) drawRect(mask, blendMode = BlendMode.DstIn)
            }
        }
}

@Composable
private fun StageHost(
    mode: LyricVisualizer,
    lyrics: ParsedLyrics,
    position: State<Long>,
    playing: Boolean,
    song: Song,
    prefs: LyricStagePrefs,
    textSp: Float,
    insets: StageInsets,
    edgeFade: Dp,
) {
    val context = LocalContext.current
    val window = LocalAppWindow.current
    val reduceMotion = LocalAppearance.current.reduceMotion || rememberSystemAnimationsOff()
    val tv = window.isTv
    val lines = remember(lyrics) { stageLines(lyrics) }
    val clock = remember { StageClock() }
    val lineIndex = remember { mutableIntStateOf(-1) }
    val paused = rememberUpdatedState(!playing)
    val analyzer = remember(context) { (context.applicationContext as? MyMusicApp)?.rhythmAnalyzer }
    val wantsAudio = analyzer != null && !reduceMotion && (mode.wantsAudio || prefs.spectrum)
    val audio = remember(wantsAudio) { if (wantsAudio) StageAudio() else null }
    if (wantsAudio) RhythmFeed(analyzer, playing)

    val scheme = MaterialTheme.colorScheme
    val coverColors by produceState(blobColorCache.get(song.cover), song.cover) { value = coverBlobColors(context, song.cover) }
    val colors = remember(scheme.onSurface, scheme.primary, scheme.tertiary, scheme.surface, coverColors) {
        val fallback = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primaryContainer)
        StageColors(
            text = scheme.onSurface,
            dim = scheme.onSurface.copy(alpha = 0.28f),
            accent = scheme.primary,
            accentAlt = scheme.tertiary,
            surface = scheme.surface,
            cover = List(4) { coverColors?.getOrNull(it) ?: fallback[it] },
        )
    }
    val display = remember(textSp, reduceMotion, tv, window.widthDp, window.heightDp, insets) {
        StageDisplay(textSp, reduceMotion, tv, wide = tv || window.widthDp > window.heightDp, insets = insets)
    }
    val raw = prefs.pluginSettings[mode.id].orEmpty()
    val settings = remember(mode, raw) { SettingValues(mode.settings, raw) }
    val ctx = remember(lines, song.key, colors, display, settings, audio, prefs.subline, mode) {
        LyricStageContext(
            lines = lines,
            song = song,
            seed = stableHash(song.key.ifBlank { song.title + song.artist }),
            clock = clock,
            lineIndex = lineIndex,
            paused = paused,
            colors = colors,
            display = display,
            settings = settings,
            audio = audio.takeIf { mode.wantsAudio },
            subline = prefs.subline,
        )
    }

    StageFrames(lines, clock, lineIndex, position, playing, reduceMotion, tv, audio, analyzer)

    Box(Modifier.fillMaxSize().stageEdgeFade(edgeFade)) {
        if (!mode.hostBackground) mode.Background(ctx, Modifier.fillMaxSize().bleedToWindowEdges())
        mode.Stage(ctx, Modifier.fillMaxSize())
        if (mode.hostSubtitles) {
            val lift = (if (prefs.spectrum && audio != null) SpectrumLift else 0.dp) + mode.subtitleInset(ctx)
            HostSubtitle(ctx, lift)
        }
        if (prefs.spectrum && audio != null) SpectrumLayer(audio, clock, insets)
        StageSemantics(ctx)
    }
}

/** 背景往外多铺的四边（像素）。 */
private data class Bleed(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * 自己画背景的模式（动感波光）：舞台和窗口边缘之间只隔着系统栏的留白时，背景往外铺到窗口边缘，
 * 免得状态栏、导航栏那一条露出底下的纯色（流光背景这时已经停了）。隔着别的内容（分栏、边距）的那几边不铺。
 * 背景只按自己的尺寸画，不读安全区，铺大一点不影响文字位置。
 */
@Composable
private fun Modifier.bleedToWindowEdges(): Modifier {
    val bars = WindowInsets.safeDrawing
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    var bleed by remember { mutableStateOf(Bleed(0, 0, 0, 0)) }
    return this
        .onPlaced { coords ->
            val root = coords.findRootCoordinates()
            val at = root.localPositionOf(coords, Offset.Zero)
            val left = at.x.roundToInt()
            val top = at.y.roundToInt()
            val right = root.size.width - left - coords.size.width
            val bottom = root.size.height - top - coords.size.height
            fun pick(gap: Int, bar: Int) = if (gap in 1..bar + 1) gap else 0
            val next = Bleed(
                pick(left, bars.getLeft(density, direction)),
                pick(top, bars.getTop(density)),
                pick(right, bars.getRight(density, direction)),
                pick(bottom, bars.getBottom(density)),
            )
            if (next != bleed) bleed = next
        }
        .layout { measurable, constraints ->
            val b = bleed
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            if (b == Bleed(0, 0, 0, 0) || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
                val placeable = measurable.measure(constraints)
                return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            val placeable = measurable.measure(Constraints.fixed(width + b.left + b.right, height + b.top + b.bottom))
            layout(width, height) { placeable.place(-b.left, -b.top) }
        }
}

/**
 * 舞台的帧循环。播放中每帧：歌词时间 = 最近一次进度 + 帧间隔外推（不往回跳 120ms 以内的抖动），
 * 算当前句，取律动帧，再发布时钟。暂停时不跑循环，只在进度变化（跳转）时发布一次；减少动态效果时只在换句时发布。
 */
@Composable
private fun StageFrames(
    lines: List<StageLine>,
    clock: StageClock,
    lineIndex: androidx.compose.runtime.MutableIntState,
    position: State<Long>,
    playing: Boolean,
    reduceMotion: Boolean,
    tv: Boolean,
    audio: StageAudio?,
    analyzer: RhythmAnalyzer?,
) {
    val feed = remember(analyzer, audio) { if (analyzer != null && audio != null) StageAudioFeed(analyzer, audio) else null }
    if (reduceMotion || !playing) {
        LaunchedEffect(lines, reduceMotion, playing) {
            var anim = 0L
            snapshotFlow { position.value }.collect { ms ->
                val index = stageLineIndex(lines, ms, lineIndex.intValue)
                // 减少动态效果：只在换句时更新一次画面；暂停：跳转后更新到新位置
                if (reduceMotion && index == lineIndex.intValue && clock.ms != 0L) return@collect
                lineIndex.intValue = index
                if (!reduceMotion) anim = (clock.seconds * 1e9).toLong()
                feed?.clear()
                clock.publish(ms, anim, 0f)
            }
        }
        return
    }
    LaunchedEffect(lines, tv, feed) {
        var basePos = position.value
        var baseNanos = withFrameNanos { it }
        var lastMs = basePos
        var lastNanos = baseNanos
        var anim = (clock.seconds * 1e9).toLong()
        while (true) {
            withFrameNanos { now ->
                if (tv && !TvFrameGate.due(now)) return@withFrameNanos
                val pos = position.value
                if (pos != basePos) {
                    basePos = pos
                    baseNanos = now
                }
                val estimate = basePos + (now - baseNanos) / 1_000_000
                // 进度每 100ms 校准一次，外推会略超前；小幅回拉不往回走，跳转（大幅往回）照跳
                val ms = if (estimate < lastMs && lastMs - estimate < JITTER_MS) lastMs else estimate
                val dt = ((now - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
                anim += (dt * 1e9).toLong()
                lastNanos = now
                lastMs = ms
                val index = stageLineIndex(lines, ms, lineIndex.intValue)
                if (index != lineIndex.intValue) lineIndex.intValue = index
                feed?.advance(now, dt)
                clock.publish(ms, anim, dt)
            }
        }
    }
}

/**
 * 按“此刻听到的位置”从分析器取帧，写进 [StageAudio]：响度、三段电平平滑，48 段频谱取最新一帧，低频起拍记数并做包络。
 * 和律动背景各用各的游标，互不影响。
 */
private class StageAudioFeed(private val analyzer: RhythmAnalyzer, private val audio: StageAudio) {
    private val cursor = RhythmTimeline.Cursor()
    private var lastKickCount = NO_KICK
    private var targetPower = 0f
    private var targetBass = 0f
    private var targetMid = 0f
    private var targetTreble = 0f
    private var quietS = 0f

    fun clear() {
        audio.power = 0f
        audio.bass = 0f
        audio.mid = 0f
        audio.treble = 0f
        audio.kick = 0f
        audio.bands.fill(0f)
        audio.live = false
    }

    fun advance(frameNanos: Long, dt: Float) {
        val audible = analyzer.clock.audibleUs(frameNanos)
        var latest: com.mytv.mymusic.playback.RhythmFrame? = null
        if (audible != PlaybackClock.UNSET) {
            analyzer.timeline.drain(cursor, audible) { frame ->
                if (lastKickCount != NO_KICK && frame.kickCount > lastKickCount) {
                    audio.kickCount += frame.kickCount - lastKickCount
                    audio.kick = 1f
                }
                lastKickCount = frame.kickCount
                latest = frame
            }
            val grid = analyzer.beats.gridAt(audible / 1e6)
            audio.bpm = if (grid.confidence >= 0.5f && grid.periodS > 0.0) grid.bpm.toFloat() else 0f
        }
        latest?.let { frame ->
            targetPower = frame.loudness
            targetBass = frame.bass
            targetMid = frame.mid
            targetTreble = frame.treble
            if (frame.bands.size == audio.bands.size) frame.bands.copyInto(audio.bands)
            audio.peakHz = frame.peakHz
            quietS = 0f
        } ?: run { quietS += dt }
        // 半秒没有新帧（暂停、缓冲、没声音）：电平落回 0
        if (quietS > 0.5f) {
            targetPower = 0f
            targetBass = 0f
            targetMid = 0f
            targetTreble = 0f
            for (i in audio.bands.indices) audio.bands[i] *= 0.85f
        }
        audio.live = quietS <= 0.5f && audible != PlaybackClock.UNSET
        audio.power = smooth(audio.power, targetPower, dt, 0.06f, 0.25f)
        audio.bass = smooth(audio.bass, targetBass, dt, 0.05f, 0.2f)
        audio.mid = smooth(audio.mid, targetMid, dt, 0.05f, 0.2f)
        audio.treble = smooth(audio.treble, targetTreble, dt, 0.05f, 0.2f)
        audio.kick = (audio.kick * exp(-dt / KICK_DECAY_S)).let { if (it < 0.01f) 0f else it }
    }

    private fun smooth(current: Float, target: Float, dt: Float, up: Float, down: Float): Float {
        val tau = if (target > current) up else down
        return current + (target - current) * (1f - exp(-dt / tau))
    }

    private companion object {
        const val NO_KICK = -1
        const val KICK_DECAY_S = 0.12f
    }
}

/** 读屏：舞台对外是一个文本节点，内容是当前整句加翻译，不逐字播报。 */
@Composable
private fun StageSemantics(ctx: LyricStageContext) {
    val line = ctx.currentLine
    val description = line?.let { listOf(it.text, it.subtitle(ctx.subline)).filter { s -> s.isNotBlank() }.joinToString("，") }.orEmpty()
    Spacer(Modifier.fillMaxSize().clearAndSetSemantics { if (description.isNotEmpty()) contentDescription = description })
}

private val MIN_STAGE_HEIGHT = 320.dp
private val SpectrumLift = 70.dp
private const val JITTER_MS = 120L
