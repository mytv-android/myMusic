package com.mytv.mymusic.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/** 自动接歌的四种接法。 */
enum class AutomixMethod(val label: String) {
    BeatCut("切拍"),
    BassHandoff("低频交接"),
    OutroCarry("尾奏承接"),
    EqualPowerFade("等功率淡化"),
}

/**
 * 一首歌唱词的时间范围（歌曲自己的进度，毫秒）：第一句开始、最后一句唱完。署名行和间奏标记不算。
 * 由 ViewModel 取到歌词时算好登记（见 [CrossfadeLyrics]），接歌时用来挑换页点。
 */
data class LyricSpan(val firstStartMs: Long, val lastEndMs: Long)

/**
 * 这一对歌曲这一次怎么接：
 * - [startAtRemainingMs] 距离上一首结束（文件末尾）还有多少毫秒时开始叠；
 * - [fadeMs] 叠多久；
 * - [endMarginMs] 淡化要在文件末尾前这么久收完：裁掉的尾部静音，没分析时留 [AutomixPlanner.END_MARGIN_MS]；
 * - [incomingStartMs] 下一首从哪个位置开始放（裁掉开头静音；切拍时对齐它的小节首拍）；
 * - [outgoingSpeed] 上一首的目标变速倍率（1 表示不变），淡化前 1/4 分 8 步渐变过去（[AutomixPlanner.tempoAt]）；
 * - [bassCutoffHz] 低频交接的交接频率，0 表示不切频段；
 * - [echoMix] 尾奏承接时叠的回声；
 * - [switchAtMs] 淡化开始后多久换主播放器（换页）。这里是交叉点；开始淡化时控制器再按两首的歌词
 *   用 [AutomixPlanner.switchAtMs] 定一次。
 */
internal data class TransitionPlan(
    val method: AutomixMethod,
    val startAtRemainingMs: Long,
    val fadeMs: Long,
    val incomingStartMs: Long,
    val outgoingSpeed: Float,
    val bassCutoffHz: Float,
    val echoMix: Float,
    val endMarginMs: Long = AutomixPlanner.END_MARGIN_MS,
    val switchAtMs: Long = AutomixPlanner.crossoverMs(method, fadeMs),
)

/**
 * 按两首歌的离线分析挑一种接法。原则和参考文档一致：能对齐拍子就切拍，尾奏收得干净就让它自然承接，
 * 两首都有明显低频就用频段交接，其余情况退回等功率淡化。分析缺一项就退一档，不硬凑。
 *
 * 智能过渡先裁首尾静音：上一首的淡出在分析出的「最后有声处」结束，下一首从「第一个有声处 − 150ms」开始放。
 */
internal object AutomixPlanner {
    /** 没有分析结果时淡化在文件末尾前这么久收完：淡完立刻换主，最后这一点本来也已经听不见了。 */
    const val END_MARGIN_MS = 250L

    /** 下一首从第一个有声处往前留这么一点开始放，分析的窗口粒度不会把第一个字吃掉。 */
    const val INTRO_LEAD_MS = 150L

    /** 换页点最晚在下一首第一句前这么久：新歌词先飞上来，第一句再亮。 */
    const val FIRST_LINE_LEAD_MS = 300L

    /** 变速在淡化的前 1/4 里分这么多步走到目标速度，避免一下变调的感觉。 */
    const val TEMPO_STEPS = 8
    private const val TEMPO_RAMP_SHARE = 0.25f

    /** 智能过渡自己的时长范围；交叉淡化的 1–12 秒滑块不影响它。 */
    private const val AUTOMIX_MIN_SECONDS = 4
    private const val AUTOMIX_MAX_SECONDS = 10

    /** 交叉淡化（设置里那个滑块）的时长范围。 */
    const val FADE_MIN_SECONDS = 1
    const val FADE_MAX_SECONDS = 12

    fun plan(
        outgoing: AutomixAnalysis?,
        incoming: AutomixAnalysis?,
        configuredSeconds: Int,
        durationMs: Long,
    ): TransitionPlan {
        val fadeMs = configuredSeconds.coerceIn(AUTOMIX_MIN_SECONDS, AUTOMIX_MAX_SECONDS) * 1000L
        val speed = tempoMatch(outgoing, incoming)
        val endMs = audibleEnd(outgoing, durationMs)
        // 尾部静音不算进淡化：淡化在最后有声处收完，后面那段静音直接不放
        val margin = (durationMs - endMs).coerceAtLeast(END_MARGIN_MS)
        val trailing = durationMs - endMs
        val introStart = introStart(incoming)

        beatCut(outgoing, incoming, endMs, introStart)?.let { cut ->
            return cut.copy(
                startAtRemainingMs = cut.startAtRemainingMs + trailing,
                outgoingSpeed = speed,
                endMarginMs = margin,
            )
        }

        val outroTail = outgoing?.let { it.durationMs - it.outroMs } ?: 0L
        val intro = incoming?.introMs ?: 0L
        if (outgoing != null && incoming != null && outroTail > 1_500L && intro >= 800L) {
            val carry = (fadeMs + 2_000L).coerceAtMost(12_000L)
            return TransitionPlan(
                method = AutomixMethod.OutroCarry,
                startAtRemainingMs = carry + margin,
                fadeMs = carry,
                incomingStartMs = introStart,
                outgoingSpeed = 1f,
                bassCutoffHz = 0f,
                echoMix = 0.25f,
                endMarginMs = margin,
            )
        }

        val confident = listOfNotNull(outgoing, incoming).count { it.beatGrid.confidence >= 0.5f }
        if (confident >= 1 && outgoing != null && incoming != null) {
            return TransitionPlan(
                method = AutomixMethod.BassHandoff,
                startAtRemainingMs = fadeMs + margin,
                fadeMs = fadeMs,
                incomingStartMs = introStart,
                outgoingSpeed = speed,
                bassCutoffHz = 300f,
                echoMix = 0f,
                endMarginMs = margin,
            )
        }

        return TransitionPlan(
            method = AutomixMethod.EqualPowerFade,
            startAtRemainingMs = fadeMs + margin,
            fadeMs = fadeMs,
            incomingStartMs = introStart,
            outgoingSpeed = speed,
            bassCutoffHz = 0f,
            echoMix = 0f,
            endMarginMs = margin,
        )
    }

    /** 交叉淡化（不是智能过渡）：固定等功率，不看分析、不裁静音、不变速。 */
    fun fixedFade(seconds: Int): TransitionPlan {
        val fadeMs = seconds.coerceIn(FADE_MIN_SECONDS, FADE_MAX_SECONDS) * 1000L
        return TransitionPlan(
            method = AutomixMethod.EqualPowerFade,
            startAtRemainingMs = fadeMs + END_MARGIN_MS,
            fadeMs = fadeMs,
            incomingStartMs = 0L,
            outgoingSpeed = 1f,
            bassCutoffHz = 0f,
            echoMix = 0f,
        )
    }

    /**
     * 交叉点（距淡化开始）：两边一样响的那一刻。等功率、低频交接、尾奏承接都是淡化正中间（t = 0.5）；
     * 切拍就是切点，也就是淡化开始那一下。
     */
    fun crossoverMs(method: AutomixMethod, fadeMs: Long): Long =
        if (method == AutomixMethod.BeatCut) 0L else fadeMs / 2

    /**
     * 换页点（距淡化开始的毫秒数）：交叉点和下一首第一句前 0.3 秒取早的，但不早于上一首最后一句唱完。
     * 两首里有一首的歌词时间不知道就用交叉点。结果落在 0..[fadeMs] 内，到 [fadeMs] 就是淡完才换。
     *
     * [outgoingStartMs] / [TransitionPlan.incomingStartMs] 是淡化开始时两首各自的进度；上一首在变速，
     * 它唱完最后一句要按渐变后的速度折成真实时间（[outgoingWallMs]）。
     */
    fun switchAtMs(
        plan: TransitionPlan,
        fadeMs: Long,
        outgoingStartMs: Long,
        outgoing: LyricSpan?,
        incoming: LyricSpan?,
    ): Long {
        val crossover = crossoverMs(plan.method, fadeMs)
        if (outgoing == null || incoming == null) return crossover.coerceIn(0L, fadeMs)
        val lastSung = outgoingWallMs(outgoing.lastEndMs - outgoingStartMs, fadeMs, plan.outgoingSpeed)
        val firstSung = incoming.firstStartMs - plan.incomingStartMs - FIRST_LINE_LEAD_MS
        return maxOf(lastSung, minOf(crossover, firstSung)).coerceIn(0L, fadeMs)
    }

    /**
     * 淡化进度 [t]（0..1）时上一首的速度：前 1/4 均分 8 段，每过一段往目标走 1/8；
     * t = 0 时还是原速，t ≥ 0.25 时到 [target]。
     */
    fun tempoAt(t: Float, target: Float): Float {
        if (target == 1f) return 1f
        val segment = TEMPO_RAMP_SHARE / TEMPO_STEPS
        val step = floor(t.coerceIn(0f, 1f) / segment + 1e-4f).toInt().coerceIn(0, TEMPO_STEPS)
        return 1f + (target - 1f) * step / TEMPO_STEPS
    }

    /**
     * 上一首的进度往前走 [mediaMs] 要多少真实时间：速度按 [tempoAt] 在前 1/4 分段渐变，之后保持 [target]。
     * [mediaMs] ≤ 0（淡化开始前就唱完了）原样返回。
     */
    fun outgoingWallMs(mediaMs: Long, fadeMs: Long, target: Float): Long {
        if (mediaMs <= 0L || target == 1f || fadeMs <= 0L) return mediaMs
        val segment = fadeMs * TEMPO_RAMP_SHARE / TEMPO_STEPS
        var left = mediaMs.toDouble()
        var wall = 0.0
        for (step in 0 until TEMPO_STEPS) {
            val speed = 1.0 + (target - 1.0) * step / TEMPO_STEPS
            val covered = segment * speed
            if (left <= covered) return (wall + left / speed).roundToLong()
            left -= covered
            wall += segment
        }
        return (wall + left / target).roundToLong()
    }

    /** 下一首和这首是同一张专辑、没开随机：不叠，直接无缝接（专辑本来就是连着听的）。 */
    fun sameAlbum(currentAlbumKey: String, nextAlbumKey: String, shuffle: Boolean): Boolean =
        !shuffle && currentAlbumKey.isNotBlank() && currentAlbumKey == nextAlbumKey

    /** 上一首最后有声处；没分析或者结果不可信（不到一半）就是文件末尾。 */
    private fun audibleEnd(outgoing: AutomixAnalysis?, durationMs: Long): Long {
        val outro = outgoing?.outroMs ?: return durationMs
        if (outro <= 0L || outro < durationMs / 2) return durationMs
        return outro.coerceAtMost(durationMs)
    }

    /** 下一首第一个有声处往前 150ms；没分析或者结果不可信就从头放。 */
    private fun introStart(incoming: AutomixAnalysis?): Long {
        val analysis = incoming ?: return 0L
        val intro = analysis.introMs
        if (intro <= 0L || (analysis.durationMs > 0L && intro >= analysis.durationMs / 2)) return 0L
        return (intro - INTRO_LEAD_MS).coerceAtLeast(0L)
    }

    /**
     * 两首都锁定了拍子、小节首拍清楚、速度差得不多时，在上一首最后一个小节首拍上切。
     * [endMs] 是上一首最后有声处，[introStartMs] 是下一首裁掉开头静音后的起点。
     */
    private fun beatCut(
        outgoing: AutomixAnalysis?,
        incoming: AutomixAnalysis?,
        endMs: Long,
        introStartMs: Long,
    ): TransitionPlan? {
        val a = outgoing?.beatGrid ?: return null
        val b = incoming?.beatGrid ?: return null
        if (a.confidence < 0.5f || b.confidence < 0.5f) return null
        if (!a.downbeatClear || !b.downbeatClear) return null
        if (a.periodS <= 0.0 || b.periodS <= 0.0) return null
        val ratio = b.bpm / a.bpm
        if (ratio !in 0.94..1.06) return null

        val endS = endMs / 1000.0
        // 搜索窗口至少放得下一个 4/4 小节，否则半秒一拍的歌可能整段里没有一个小节首拍
        val earliest = endS - maxOf(2.0, a.periodS * 4)
        val latest = endS - 0.5
        if (latest <= earliest) return null
        val k = floor(latest / a.periodS).toLong()
        var cutS = Double.NaN
        for (step in 0 until 16) {
            val index = k - step
            val t = a.t0S + index * a.periodS
            if (t in earliest..latest && a.isDownbeat(index)) {
                cutS = t
                break
            }
        }
        if (cutS.isNaN()) return null
        val remaining = ((endS - cutS) * 1000).roundToLong().coerceIn(500L, 3_200L)
        val fade = (remaining - END_MARGIN_MS).coerceAtLeast(400L).coerceAtMost(1_200L)

        // 下一首对齐它自己在有声处（往前 150ms）之后的第一个小节首拍，舞台切进来就是正拍
        val fromS = introStartMs / 1000.0
        var incomingStart = introStartMs
        val firstIndex = ceil((fromS - b.t0S) / b.periodS - 1e-9).toLong()
        for (step in 0 until 8) {
            val index = firstIndex + step
            val t = b.t0S + index * b.periodS
            if (t >= 0.0 && b.isDownbeat(index)) {
                incomingStart = (t * 1000).roundToLong().coerceAtMost(introStartMs + 4_000L)
                break
            }
        }
        return TransitionPlan(
            method = AutomixMethod.BeatCut,
            startAtRemainingMs = remaining,
            fadeMs = fade,
            incomingStartMs = incomingStart,
            outgoingSpeed = 1f,
            bassCutoffHz = 0f,
            echoMix = 0f,
        )
    }

    /** 两首速度差在 ±6% 以内时，把上一首的尾巴变速到和下一首一致；超过就不对齐。 */
    private fun tempoMatch(outgoing: AutomixAnalysis?, incoming: AutomixAnalysis?): Float {
        val a = outgoing?.bpm ?: return 1f
        val b = incoming?.bpm ?: return 1f
        if (a <= 0.0 || b <= 0.0) return 1f
        val ratio = (b / a).toFloat()
        return if (ratio in 0.94f..1.06f && abs(ratio - 1f) > 0.005f) ratio else 1f
    }
}
