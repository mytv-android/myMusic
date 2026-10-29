package com.mytv.mymusic.ui.player

import com.mytv.mymusic.playback.BeatGrid
import kotlin.math.exp

/**
 * 界面这一侧的拍子时钟，不依赖 Compose：每个显示帧拿“此刻听到的位置”去问拍子网格，决定要不要起一拍，
 * 再提供以拍点为锚的包络（拍前 50ms 平滑升起，之后按时间常数回落），都按真实时刻算，和帧率无关。
 *
 * 网格不可信（置信度低）时退回到对齐过时间的低频起拍，力度减半；连起拍也没有，就什么都不做，只剩段落的呼吸。
 * 逐行对照方案页原型（docs/beat-sync.html 的 BeatClock）。
 */
internal class BeatClock {
    /** 0..1：网格可信的程度，平滑过（0.35s 起、1.5s 落），过 0.5 才按网格起拍。 */
    var lock = 0f
        private set

    /** 起过的拍数，只增不减。 */
    var count = 0
        private set

    /** 最近一拍是不是小节首拍。 */
    var lastDownbeat = false
        private set

    /** 最近一拍的力度 0..1。 */
    var lastAccent = 0f
        private set

    /** 最近一拍是按网格起的（false 是退回到低频起拍）。 */
    var lastFromGrid = false
        private set

    /** 最近一拍的时刻（秒，媒体时间）。 */
    var lastBeatS = NEVER
        private set

    private var prevBeatS = NEVER
    private var prevAccent = 0f
    private var lastIndex = NO_INDEX
    private var lastT = Double.NaN

    // 暂停时没有新位置，包络按真实时间继续回落：记下暂停了多久，恢复时把拍点往前挪同样的时间
    private var idleS = 0.0

    private val kicks = DoubleArray(MAX_KICKS)
    private var kickHead = 0
    private var kickCount = 0

    fun reset() {
        val keep = lock
        clear()
        lock = keep
    }

    private fun clear() {
        lock = 0f
        lastBeatS = NEVER
        prevBeatS = NEVER
        lastAccent = 0f
        prevAccent = 0f
        lastDownbeat = false
        lastFromGrid = false
        lastIndex = NO_INDEX
        lastT = Double.NaN
        idleS = 0.0
        kickHead = 0
        kickCount = 0
    }

    /** 对齐过时间的低频起拍（秒，已扣掉检测延迟），网格不可信时用。 */
    fun pushKick(tS: Double) {
        if (kickCount == MAX_KICKS) {
            kickHead = (kickHead + 1) % MAX_KICKS
            kickCount--
        }
        kicks[(kickHead + kickCount) % MAX_KICKS] = tS
        kickCount++
    }

    private fun peekKick(): Double = kicks[kickHead]

    private fun dropKick() {
        kickHead = (kickHead + 1) % MAX_KICKS
        kickCount--
    }

    /** 暂停、还不知道听到哪儿时调用：不起拍，包络按 [dt] 继续回落。 */
    fun idle(dt: Float) {
        idleS += dt
    }

    /**
     * 听到的位置 [tS]（秒）、距上一帧 [dt] 秒、此刻该用的网格 [grid]。这一帧起了一拍就返回 true，
     * 拍的信息见 [lastBeatS]、[lastDownbeat]、[lastAccent]。
     */
    fun update(tS: Double, dt: Float, grid: BeatGrid): Boolean {
        if (idleS > 0.0) {
            // 恢复播放：暂停期间包络已经按真实时间落下去了，拍点跟着往前挪，不会一下弹回来
            lastBeatS -= idleS
            prevBeatS -= idleS
            idleS = 0.0
        }
        // 听到的位置往回走或一下跳出两秒（拖进度、循环）：上一拍的记录作废，否则新位置上的拍都被当成“太近”跳过
        if (!lastT.isNaN() && (tS < lastT - 0.05 || tS > lastT + 2.0)) reset()
        lastT = tS
        lock = follow(lock, if (grid.periodS > 0.0 && grid.confidence >= LOCK_ON) 1f else 0f, dt, 0.35f, 1.5f)
        var fired = false
        if (grid.periodS > 0.0 && lock > 0.5f) {
            val period = grid.periodS
            val k = Math.round((tS + PRE_S - grid.t0S) / period)
            val beat = grid.t0S + k * period
            val index = grid.k0 + k
            // 同一拍只起一次：网格每次微调都可能把这一拍挪几毫秒，按拍号和最小间隔去重
            if (beat <= tS + PRE_S && tS - beat < LATE_S && index != lastIndex && beat - lastBeatS > MIN_GAP * period) {
                val strength = grid.strength(index).takeUnless { it.isNaN() } ?: DEFAULT_STRENGTH
                fire(beat, 0.55f + 0.45f * strength.coerceIn(0f, 1f), grid.isDownbeat(index), fromGrid = true)
                lastIndex = index
                fired = true
            }
            while (kickCount > 0 && peekKick() <= tS) dropKick()
        } else {
            while (kickCount > 0 && peekKick() <= tS + PRE_S) {
                val kick = peekKick()
                dropKick()
                // 没有网格时的低频起拍不一定是鼓（换和弦、贝斯进来），只给一半的力度
                if (tS - kick < LATE_S && kick - lastBeatS > KICK_MIN_GAP_S) {
                    fire(kick, KICK_ACCENT, downbeat = false, fromGrid = false)
                    lastIndex = NO_INDEX
                    fired = true
                }
            }
        }
        return fired
    }

    private fun fire(atS: Double, accent: Float, downbeat: Boolean, fromGrid: Boolean) {
        prevBeatS = lastBeatS
        prevAccent = lastAccent
        lastBeatS = atS
        lastAccent = accent
        lastDownbeat = downbeat
        lastFromGrid = fromGrid
        count++
    }

    /** 以拍点为锚的包络：拍前 [PRE_S] 里平滑升到峰值，之后按 [tauS] 指数回落；和上一拍的尾巴取大，不会掉一下。 */
    fun envelope(tauS: Double): Float {
        if (lastT.isNaN()) return 0f
        val t = lastT + idleS
        return maxOf(one(t, lastBeatS, lastAccent, tauS), one(t, prevBeatS, prevAccent, tauS))
    }

    private fun one(t: Double, at: Double, accent: Float, tauS: Double): Float {
        val x = t - at
        if (x < -PRE_S) return 0f
        return accent * if (x < 0) smoothstep(((x + PRE_S) / PRE_S).toFloat()) else exp(-x / tauS).toFloat()
    }

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    companion object {
        /** 拍前多久开始起势（秒）。 */
        const val PRE_S = 0.05
        private const val LOCK_ON = 0.5f
        private const val MIN_GAP = 0.6
        private const val LATE_S = 0.12
        private const val KICK_MIN_GAP_S = 0.25
        private const val KICK_ACCENT = 0.45f
        private const val DEFAULT_STRENGTH = 0.7f
        private const val MAX_KICKS = 64
        private const val NEVER = -1e9
        private const val NO_INDEX = Long.MIN_VALUE
    }
}
