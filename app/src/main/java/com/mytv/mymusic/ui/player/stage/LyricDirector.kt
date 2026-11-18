package com.mytv.mymusic.ui.player.stage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import com.mytv.mymusic.data.LyricMemoryEntry
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
 * 优先级：这首歌固定用的（[LyricStagePrefs.pins]）> 固定 / 随机 / 按速度。随机时记忆里有这首就用记住的（即使已经不在池里），
 * 没有才从随机池里抽。
 * 固定、这首歌固定用的立刻换；随机、按速度自己换时只在不打断演唱的时候换（[canSwitchAt]），等不到合适的空当就留到下一首。
 * 面板里点了“保存并应用”、改了这首歌固定用哪个（[applyNow]）：按新设置重算这首歌，马上换，不等空当（舞台用 0.4 秒淡入淡出）；
 * 按速度且还没测出速度时，测到了立刻换。
 * 随机抽到的模式和测到的速度按歌记下来（最近 2000 首），再听时一开始就是同一个。
 */
@Stable
internal class LyricDirector {
    /** 正在显示的模式 id。 */
    var shown by mutableStateOf<String?>(null)
        internal set

    /** 按现在的设置这首歌该用的；和 [shown] 不同时是在等空当换。还没定下来（按速度正在测）时为 null。 */
    var wanted by mutableStateOf<String?>(null)
        internal set

    /** 这首歌为什么用 [wanted]，面板“这首歌”一行用。 */
    var decision by mutableStateOf<SongDecision>(SongDecision.Unknown)
        internal set

    /** 记忆里这首歌的条目（随机结果、测到的速度）；切歌后重新读，写记忆时同步更新。面板的舞台预览用。 */
    var memory by mutableStateOf<LyricMemoryEntry?>(null)
        internal set

    /** 上一次换模式是不是 [applyNow] 触发的：是的话舞台用 0.4 秒淡入淡出，不用默认的过渡。 */
    var appliedSwitch by mutableStateOf(false)
        internal set

    /** 待立即应用的歌曲 key；换上了或者切歌了就清掉。 */
    internal var applyFor by mutableStateOf<String?>(null)

    /** 立即应用时随机换法要是还没记过这首，用这个（面板预览的那个），保存后画面和预览一致。 */
    internal var applyHint: String? = null

    internal var applyToken by mutableIntStateOf(0)

    /** 这次运行里抽到的（歌曲 key → 模式），用来分辨“这次抽到的”和“上次记住的”。 */
    internal val drawn = HashMap<String, String>()

    /** 按新设置重算 [songKey] 这首、马上换（不等空当）。[hint]：随机换法下这首还没记过时直接用它。 */
    fun applyNow(songKey: String, hint: String? = null) {
        applyFor = songKey
        applyHint = hint
        applyToken++
    }

    /** 正在显示的模式，没定下来时按经典。 */
    fun mode(prefs: LyricStagePrefs): LyricVisualizer = prefs.modeOf(shown.orEmpty())
}

/** 这首歌用这个模式的理由。 */
internal sealed interface SongDecision {
    /** 这首歌固定用它。 */
    data class Pinned(val id: String) : SongDecision

    /** 固定换法：所有歌都用它。 */
    data class Fixed(val id: String) : SongDecision

    /** 随机：上次听时抽到的，已记住。 */
    data class Remembered(val id: String) : SongDecision

    /** 随机：这次从池里抽到的。 */
    data class Drawn(val id: String) : SongDecision

    /** 按速度：测得 [bpm]，落在第 [tier] 档。 */
    data class Tempo(val id: String, val bpm: Float, val tier: Int) : SongDecision

    /** 按速度：正在听速度。 */
    data object Measuring : SongDecision

    /** 按速度：这台设备听不出速度（没有律动分析），先用正在显示的。 */
    data object NoAnalyzer : SongDecision

    /** 还没定下来。 */
    data object Unknown : SongDecision
}

/** 决定出来的模式；正在测、听不出时为 null。 */
internal val SongDecision.modeId: String?
    get() = when (this) {
        is SongDecision.Pinned -> id
        is SongDecision.Fixed -> id
        is SongDecision.Remembered -> id
        is SongDecision.Drawn -> id
        is SongDecision.Tempo -> id
        else -> null
    }

/** 这首歌固定用 [id]（null：取消固定）。写进 pins 后立刻换；取消固定在随机、按速度下也立刻换，不等空当。 */
internal fun LyricDirector.pin(song: Song, id: String?, actions: MusicActions) {
    val key = song.key
    if (key.isBlank()) return
    actions.setLyricStage { if (id != null) it.copy(pins = it.pins + (key to id)) else it.copy(pins = it.pins - key) }
    applyNow(key)
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
    val currentPrefs by rememberUpdatedState(prefs)

    // 换歌：没来得及用的“立即应用”作废
    LaunchedEffect(key) {
        if (director.applyFor != key) {
            director.applyFor = null
            director.applyHint = null
        }
    }

    val pinned = prefs.pins[key]?.takeIf(prefs::isKnown)
    // 随机、按速度的结论；null 表示还没定
    var auto by remember(key, prefs.auto) { mutableStateOf<SongDecision?>(null) }
    var measuring by remember(key, prefs.auto) { mutableStateOf(false) }
    LaunchedEffect(key, prefs.auto, prefs.tempoRules, prefs.randomPool, pinned, director.applyToken) {
        measuring = false
        val memory = currentActions.lyricMemory(key)
        director.memory = memory
        // 固定过的歌什么都不测、不抽；固定换法不用记忆
        if (pinned != null || prefs.auto == LyricAutoSwitch.Fixed) {
            auto = null
            return@LaunchedEffect
        }
        when (prefs.auto) {
            LyricAutoSwitch.Fixed -> Unit
            LyricAutoSwitch.Random -> {
                val pool = prefs.effectivePool()
                val remembered = memory?.mode?.takeIf { it.isNotEmpty() && prefs.isKnown(it) }
                val hint = director.applyHint?.takeIf { director.applyFor == key && it in pool }
                val pick = if (remembered == null && hint != null) {
                    RandomPick(hint, remembered = false)
                } else {
                    chooseRandomMode(remembered, pool, director.shown, prefs::isKnown, Random.Default)
                }
                if (!pick.remembered) {
                    currentActions.rememberLyric(key) { it.copy(mode = pick.id) }
                    director.memory = (memory ?: LyricMemoryEntry()).copy(mode = pick.id)
                    director.drawn[key] = pick.id
                }
                auto = if (!pick.remembered || director.drawn[key] == pick.id) {
                    SongDecision.Drawn(pick.id)
                } else {
                    SongDecision.Remembered(pick.id)
                }
            }
            LyricAutoSwitch.Tempo -> {
                val remembered = memory?.bpm?.takeIf { it > 0f }
                if (remembered != null) {
                    auto = tempoDecision(prefs.tempoRules, remembered)
                    return@LaunchedEffect
                }
                if (analyzer == null) {
                    auto = SongDecision.NoAnalyzer
                    return@LaunchedEffect
                }
                auto = SongDecision.Measuring
                measuring = true
                val bpm = awaitTempo(analyzer, position)
                measuring = false
                auto = tempoDecision(currentPrefs.tempoRules, bpm)
                currentActions.rememberLyric(key) { it.copy(bpm = bpm) }
                director.memory = (director.memory ?: LyricMemoryEntry()).copy(bpm = bpm)
            }
        }
    }
    if (measuring && analyzer != null) RhythmFeed(analyzer, playing)

    val decision = when {
        pinned != null -> SongDecision.Pinned(pinned)
        prefs.auto == LyricAutoSwitch.Fixed -> SongDecision.Fixed(prefs.visualizer)
        else -> auto ?: SongDecision.Unknown
    }
    val wanted = decision.modeId
    val immediate = pinned != null || prefs.auto == LyricAutoSwitch.Fixed
    if (director.shown == null) director.shown = wanted ?: prefs.visualizer
    SideEffect {
        director.decision = decision
        director.wanted = wanted
    }

    val lines = remember(lyrics) { stageLines(lyrics) }
    val waiting by rememberUpdatedState(lyricsLoading)
    LaunchedEffect(wanted, immediate, lines, director.applyToken) {
        // 想要的和正在显示的一样：不消费“立即应用”，等新设置到了、想要的变了再用
        if (wanted == null || wanted == director.shown) return@LaunchedEffect
        if (!immediate && director.applyFor != key) {
            // 歌词还在解析时 lines 是上一首的，等解析完再判断
            snapshotFlow {
                shouldSwitchNow(false, director.applyFor == key, waiting, canSwitchAt(lines, position.value))
            }.first { it }
        }
        val forced = director.applyFor == key
        director.appliedSwitch = forced
        director.shown = wanted
        if (forced) {
            director.applyFor = null
            director.applyHint = null
        }
    }
    return director
}

private fun tempoDecision(rules: List<TempoRule>, bpm: Float): SongDecision {
    val id = modeForTempo(rules, bpm) ?: return SongDecision.NoAnalyzer
    return SongDecision.Tempo(id, bpm, tempoTier(rules, bpm))
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
