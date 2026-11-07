package com.mytv.mymusic.playback

import kotlin.math.abs
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
 * 这一对歌曲这一次怎么接：
 * - [startAtRemainingMs] 距离上一首结束还有多少毫秒时开始叠；
 * - [fadeMs] 叠多久；
 * - [incomingStartMs] 下一首从哪个位置开始放（切拍时对齐它的小节首拍）；
 * - [outgoingSpeed] 上一首的变速倍率（1 表示不变），让两首的拍子在这几秒里对得上；
 * - [bassCutoffHz] 低频交接的交接频率，0 表示不切频段；
 * - [echoMix] 尾奏承接时叠的回声。
 */
internal data class TransitionPlan(
    val method: AutomixMethod,
    val startAtRemainingMs: Long,
    val fadeMs: Long,
    val incomingStartMs: Long,
    val outgoingSpeed: Float,
    val bassCutoffHz: Float,
    val echoMix: Float,
)

/**
 * 按两首歌的离线分析挑一种接法。原则和参考文档一致：能对齐拍子就切拍，尾奏收得干净就让它自然承接，
 * 两首都有明显低频就用频段交接，其余情况退回等功率淡化。分析缺一项就退一档，不硬凑。
 */
internal object AutomixPlanner {
    fun plan(
        outgoing: AutomixAnalysis?,
        incoming: AutomixAnalysis?,
        configuredSeconds: Int,
        durationMs: Long,
    ): TransitionPlan {
        val fadeMs = configuredSeconds.coerceIn(4, 10) * 1000L
        val speed = tempoMatch(outgoing, incoming)

        beatCut(outgoing, incoming, durationMs)?.let { return it.copy(outgoingSpeed = speed) }

        val outroTail = outgoing?.let { it.durationMs - it.outroMs } ?: 0L
        val intro = incoming?.introMs ?: 0L
        if (outgoing != null && incoming != null && outroTail > 1_500L && intro >= 800L) {
            return TransitionPlan(
                method = AutomixMethod.OutroCarry,
                startAtRemainingMs = (fadeMs + 2_000L).coerceAtMost(12_000L),
                fadeMs = (fadeMs + 2_000L).coerceAtMost(12_000L),
                incomingStartMs = 0L,
                outgoingSpeed = 1f,
                bassCutoffHz = 0f,
                echoMix = 0.25f,
            )
        }

        val confident = listOfNotNull(outgoing, incoming).count { it.beatGrid.confidence >= 0.5f }
        if (confident >= 1 && outgoing != null && incoming != null) {
            return TransitionPlan(
                method = AutomixMethod.BassHandoff,
                startAtRemainingMs = fadeMs,
                fadeMs = fadeMs,
                incomingStartMs = 0L,
                outgoingSpeed = speed,
                bassCutoffHz = 300f,
                echoMix = 0f,
            )
        }

        return TransitionPlan(
            method = AutomixMethod.EqualPowerFade,
            startAtRemainingMs = fadeMs,
            fadeMs = fadeMs,
            incomingStartMs = 0L,
            outgoingSpeed = speed,
            bassCutoffHz = 0f,
            echoMix = 0f,
        )
    }

    /** 两首都锁定了拍子、小节首拍清楚、速度差得不多时，在上一首最后一个小节首拍上切。 */
    private fun beatCut(outgoing: AutomixAnalysis?, incoming: AutomixAnalysis?, durationMs: Long): TransitionPlan? {
        val a = outgoing?.beatGrid ?: return null
        val b = incoming?.beatGrid ?: return null
        if (a.confidence < 0.5f || b.confidence < 0.5f) return null
        if (!a.downbeatClear || !b.downbeatClear) return null
        if (a.periodS <= 0.0 || b.periodS <= 0.0) return null
        val ratio = b.bpm / a.bpm
        if (ratio !in 0.94..1.06) return null

        val endS = durationMs / 1000.0
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
        val fade = (remaining - 250L).coerceAtLeast(400L).coerceAtMost(1_200L)

        // 下一首对齐它自己的第一个小节首拍，舞台切进来就是正拍
        var incomingStart = 0L
        val firstIndex = floor((0.0 - b.t0S) / b.periodS).toLong()
        for (step in 0 until 8) {
            val index = firstIndex + step
            val t = b.t0S + index * b.periodS
            if (t >= 0.0 && b.isDownbeat(index)) {
                incomingStart = (t * 1000).roundToLong().coerceAtMost(4_000L)
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
