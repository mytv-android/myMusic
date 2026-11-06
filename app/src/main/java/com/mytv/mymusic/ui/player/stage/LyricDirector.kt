package com.mytv.mymusic.ui.player.stage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.LyricAutoSwitch
import com.mytv.mymusic.data.LyricStagePrefs
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TempoRule
import com.mytv.mymusic.playback.PlaybackClock
import com.mytv.mymusic.playback.RhythmAnalyzer
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.player.RhythmFeed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.random.Random

/**
 * 每首歌用哪个歌词动画。固定、随机（lyric-style-switcher 的思路）、按速度（bpm-style-director 的思路）都按说明自己实现。
 *
 * 优先级：这首歌固定用的（[LyricStagePrefs.pins]）> 这次在面板里手动点的 > 固定 / 随机 / 按速度。
 * 手动点、改设置立刻换；自动换只在不打断演唱的时候换（[canSwitchAt]），等不到合适的空当就留到下一首。
 * 随机抽到的模式和测到的速度按歌记下来（最近 2000 首），再听时一开始就是同一个。
 */
@Stable
internal class LyricDirector {
    /** 正在显示的模式 id。 */
    var shown by mutableStateOf<String?>(null)
        internal set

    /** 这次运行里手动点过的：歌曲 key → 模式 id。手动优先，这首歌不再自动换；不落盘。 */
    private val manual = HashMap<String, String>()
    private var manualVersion by mutableIntStateOf(0)

    /** 正在显示的模式，没定下来时按经典。 */
    val mode: LyricVisualizer get() = LyricVisualizers.byId(shown.orEmpty())

    internal fun manualFor(songKey: String): String? {
        manualVersion // 订阅手动选择的变化
        return manual[songKey]
    }

    internal fun setManual(songKey: String, id: String) {
        manual[songKey] = id
        manualVersion++
    }
}

/**
 * 面板里点了某个模式：固定模式下改设置；自动模式下只这首歌手动优先（随机模式顺便把记忆改成它，下次还是它）。
 * 这首歌已固定用某个模式时，改的是固定的那个。都立刻换上。
 */
internal fun LyricDirector.pick(song: Song, prefs: LyricStagePrefs, id: String, actions: MusicActions) {
    val key = song.key
    when {
        key.isNotBlank() && prefs.pins.containsKey(key) -> actions.setLyricStage { it.copy(pins = it.pins + (key to id)) }
        prefs.auto == LyricAutoSwitch.Fixed -> actions.setLyricStage { it.copy(visualizer = id) }
        else -> {
            setManual(key, id)
            if (prefs.auto == LyricAutoSwitch.Random) actions.rememberLyric(key) { it.copy(mode = id) }
        }
    }
    shown = id
}

/** “这首歌固定用它”：开就把正在显示的模式写进 pins，关就删掉。 */
internal fun LyricDirector.pin(song: Song, on: Boolean, actions: MusicActions) {
    val key = song.key
    if (key.isBlank()) return
    val id = shown ?: return
    actions.setLyricStage { if (on) it.copy(pins = it.pins + (key to id)) else it.copy(pins = it.pins - key) }
}

/**
 * 播放页里调一次，切歌、改设置时重新决定。按速度且这首歌还没测过速度时，播放中开着律动分析（离开播放页就释放）。
 */
@Composable
internal fun rememberLyricDirector(
    song: Song,
    prefs: LyricStagePrefs,
    lyrics: ParsedLyrics,
    lyricsLoading: Boolean,
    position: State<Long>,
    playing: Boolean,
    actions: MusicActions,
): LyricDirector {
    val director = remember { LyricDirector() }
    val context = LocalContext.current
    val analyzer = remember(context) { (context.applicationContext as? MyMusicApp)?.rhythmAnalyzer }
    val key = song.key
    val currentActions by rememberUpdatedState(actions)

    // 随机、按速度的结论；null 表示还没定（按速度时没测出速度），保持正在显示的
    var auto by remember(key, prefs.auto) { mutableStateOf<String?>(null) }
    var measuring by remember(key, prefs.auto) { mutableStateOf(false) }
    LaunchedEffect(key, prefs.auto, prefs.tempoRules) {
        when (prefs.auto) {
            LyricAutoSwitch.Fixed -> auto = null
            LyricAutoSwitch.Random -> {
                val remembered = currentActions.lyricMemory(key)?.mode?.takeIf(::isKnownMode)
                auto = remembered ?: pickRandomMode(randomCandidates(), exclude = director.shown, random = Random.Default).also { id ->
                    currentActions.rememberLyric(key) { it.copy(mode = id) }
                }
            }
            LyricAutoSwitch.Tempo -> {
                val remembered = currentActions.lyricMemory(key)?.bpm?.takeIf { it > 0f }
                if (remembered != null) {
                    auto = modeForTempo(prefs.tempoRules, remembered)
                    return@LaunchedEffect
                }
                analyzer ?: return@LaunchedEffect
                measuring = true
                val bpm = awaitTempo(analyzer, position)
                measuring = false
                auto = modeForTempo(prefs.tempoRules, bpm)
                currentActions.rememberLyric(key) { it.copy(bpm = bpm) }
            }
        }
    }
    if (measuring && analyzer != null) RhythmFeed(analyzer, playing)

    val pinned = prefs.pins[key]?.takeIf(::isKnownMode)
    val manual = director.manualFor(key)
    val wanted = pinned ?: manual ?: when (prefs.auto) {
        LyricAutoSwitch.Fixed -> prefs.visualizer
        else -> auto
    }
    val immediate = pinned != null || manual != null || prefs.auto == LyricAutoSwitch.Fixed
    if (director.shown == null) director.shown = wanted ?: prefs.visualizer

    val lines = remember(lyrics) { stageLines(lyrics) }
    val waiting by rememberUpdatedState(lyricsLoading)
    LaunchedEffect(wanted, immediate, lines) {
        if (wanted == null || wanted == director.shown) return@LaunchedEffect
        if (!immediate) {
            // 歌词还在解析时 lines 是上一首的，等解析完再判断
            snapshotFlow { !waiting && canSwitchAt(lines, position.value) }.first { it }
        }
        director.shown = wanted
    }
    return director
}

/** 等节拍网格锁定（置信度到 0.5）；切歌后头 4 秒的网格可能还是上一首的，不算。 */
private suspend fun awaitTempo(analyzer: RhythmAnalyzer, position: State<Long>): Float {
    while (true) {
        if (position.value >= TEMPO_SETTLE_MS) {
            val audible = analyzer.clock.audibleUs(System.nanoTime())
            if (audible != PlaybackClock.UNSET) {
                val grid = analyzer.beats.gridAt(audible / 1e6)
                if (grid.confidence >= 0.5f && grid.periodS > 0.0) return grid.bpm.toFloat()
            }
        }
        delay(TEMPO_POLL_MS)
    }
}

private fun isKnownMode(id: String): Boolean = LyricVisualizers.all.any { it.id == id }

/** 随机的候选：除了经典的全部模式。 */
private fun randomCandidates(): List<String> = LyricVisualizers.all.map { it.id }.filter { it != ClassicVisualizer.id }

/** 随机抽一个，不和 [exclude]（上一首的）重复；只有一个候选时就是它。 */
internal fun pickRandomMode(candidates: List<String>, exclude: String?, random: Random): String {
    val pool = candidates.filter { it != exclude }.ifEmpty { candidates }
    return pool.getOrElse(random.nextInt(pool.size.coerceAtLeast(1))) { ClassicVisualizer.id }
}

/** 按速度选档：每档左闭右开，[TempoRule.maxBpm] 是上限；超过所有上限按最后一档。 */
internal fun modeForTempo(rules: List<TempoRule>, bpm: Float): String? {
    if (rules.isEmpty()) return null
    return (rules.sortedBy { it.maxBpm }.firstOrNull { bpm < it.maxBpm } ?: rules.maxBy { it.maxBpm }).mode
}

/**
 * 这一刻能不能自动换模式（不打断演唱）：第一句之前还剩 0.4 秒以上；或者在两句之间，空当至少 1.5 秒、离下一句还有 0.8 秒以上；
 * 或者最后一句已经唱完。没有同步歌词时随时可以换。
 */
internal fun canSwitchAt(lines: List<StageLine>, ms: Long): Boolean {
    if (lines.isEmpty()) return true
    val first = lines.first()
    if (ms < first.startMs) return first.startMs - ms >= SWITCH_LEAD_MS
    val nextIndex = lines.indexOfFirst { it.startMs > ms }
    if (nextIndex < 0) return ms >= lines.last().endMs
    val previous = lines[nextIndex - 1]
    val next = lines[nextIndex]
    if (ms < previous.endMs) return false
    return next.startMs - previous.endMs >= SWITCH_GAP_MS && next.startMs - ms >= SWITCH_GAP_LEAD_MS
}

private const val SWITCH_LEAD_MS = 400L
private const val SWITCH_GAP_MS = 1_500L
private const val SWITCH_GAP_LEAD_MS = 800L
private const val TEMPO_SETTLE_MS = 4_000L
private const val TEMPO_POLL_MS = 1_000L
