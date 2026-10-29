package com.mytv.mymusic.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 拍子网格，不可变：第 [k0] 拍在 [t0S] 秒（媒体时间，和 [RhythmFrame.ptsUs] 同一条轴），每拍 [periodS] 秒。
 * [periodS] 为 0 表示还没有网格；[confidence] 不到 0.5 时界面不按网格起拍。
 * 拍号对 4 取余等于 [downbeat] 的是小节首拍；[downbeatClear] 为 false 时四拍一样重（四拍底鼓），首拍只是每四拍算一次。
 */
class BeatGrid internal constructor(
    val periodS: Double,
    val t0S: Double,
    val k0: Long,
    val confidence: Float,
    val downbeat: Int,
    val downbeatClear: Boolean,
    private val strengthIndex: LongArray,
    private val strengthValue: FloatArray,
) {
    val bpm: Double get() = if (periodS > 0.0) 60.0 / periodS else 0.0

    /** 第 [index] 拍附近低频起拍的相对强度 0..1；还没量过时返回 NaN。 */
    fun strength(index: Long): Float {
        for (i in strengthIndex.indices.reversed()) if (strengthIndex[i] == index) return strengthValue[i]
        return Float.NaN
    }

    /** 拍号对 4 的位置是否是小节首拍。 */
    fun isDownbeat(index: Long): Boolean = Math.floorMod(index - downbeat, 4L) == 0L

    companion object {
        val NONE = BeatGrid(0.0, 0.0, 0L, 0f, 0, false, LongArray(0), FloatArray(0))
    }
}

/**
 * 发给界面的跟踪结果。无缝接到下一段时，分析已经进了新的一段，界面却还在播上一段：
 * 冻结一份旧网格，界面听到的位置还没到 [boundaryS] 时继续用它。
 */
class BeatState internal constructor(
    val grid: BeatGrid,
    private val previous: BeatGrid?,
    private val boundaryS: Double,
) {
    fun gridAt(tS: Double): BeatGrid = if (previous != null && tS < boundaryS) previous else grid

    companion object {
        val EMPTY = BeatState(BeatGrid.NONE, null, Double.NEGATIVE_INFINITY)
    }
}

/** 起拍落在窗口里的平均位置：检测到的帧比真实起拍晚这么多（秒，原型仿真标定）。界面把低频起拍交给拍子时钟时也扣掉它。 */
internal const val ONSET_DETECT_LAG_S = 0.03

/**
 * 节拍跟踪，跑在分析线程上（单线程使用）。输入每个分析帧的 pts 和两路谱通量，输出 [BeatGrid]。
 *
 * - 起拍曲线：低频通量 + 0.3 × 中频通量，减去滑动基线；留最近 8 秒。
 * - 速度：曲线先做高斯平滑（不平滑的话 30 帧/秒下整数帧的间隔占便宜，慢歌会被认成两倍速），
 *   在 60–180 BPM 间每 0.25 BPM 算错开一拍和两拍的自相关，乘一个偏向 100 BPM 的先验，取峰值再插值。
 *   换速度要连续几次都明显更好（刚锁上 6 秒内门槛放低）；差 3% 以内只微调。
 * - 相位：最近 4 秒里找一组等间隔的位置让落上的起拍最多，近的拍权重大；网格每次只往测量值拉 35%，拍号保持连续。
 * - 置信度：错开一拍（或两拍、一小节）后的相关程度 × 相位梳的峰有多突出 × 网格位置上有起拍的比例；没有拍的音乐前两者都平，
 *   随机的打击声偶尔凑巧等距时靠第三项压住（原型之外加的一项，随机起拍序列的误锁从 3–15% 降到 1.5% 以内）。
 * - 小节首拍：每拍附近的低频起拍强度按拍号对 4 取余累加，最重的一组。
 * - 跳转：同一首里往回走或一下跳出两秒，速度留着，1.5 秒后只重新对相位。
 *
 * 逐行对照方案页原型（docs/beat-sync.html 的 BeatTracker），常量相同。
 */
internal class BeatTracker(fps: Double = RHYTHM_TARGET_FPS.toDouble()) {
    var fps = fps
        private set
    private var size = historySize(fps)
    private var onset = FloatArray(size)
    private var bass = FloatArray(size)
    private var smoothed = FloatArray(size)
    private var smoothedCount = 0

    private var written = 0
    private var lastPts = Double.NaN
    private var baseline = 0.0
    private var since = 0

    private var hintBpm = 0.0
    private var hintConf = 0f

    private var period = 0.0
    private var t0 = 0.0
    private var k0 = 0L
    private var conf = 0f
    private var rawConf = 0f
    private var tempoConf = 0f
    private var phaseConf = 0f
    private var adoptedAt = Double.NEGATIVE_INFINITY
    private var fresh = false
    private var candidate = 0.0
    private var candidateHits = 0

    private val accent = DoubleArray(4)
    private var downbeat = 0
    private var downbeatClear = false
    private var counted = NOT_COUNTED
    private val strengthIndex = LongArray(STRENGTH_KEPT)
    private val strengthValue = FloatArray(STRENGTH_KEPT)
    private var strengthCount = 0
    private var peakStrength = 1.0

    /** 网格每变一次加一；调用方据此决定要不要发一份新快照。 */
    var revision = 0
        private set

    private val scores = DoubleArray(((MAX_BPM - MIN_BPM) / BPM_STEP).toInt() + 1)
    private val comb = DoubleArray(PHASE_STEPS)
    private val coverageHits = DoubleArray(64)

    /** 采样率决定的分析帧率（每秒帧数），换格式时设一次。 */
    fun configure(framesPerSecond: Double) {
        if (framesPerSecond == fps || !(framesPerSecond > 0.0)) return
        fps = framesPerSecond
        size = historySize(fps)
        onset = FloatArray(size)
        bass = FloatArray(size)
        smoothed = FloatArray(size)
        reset(keepTempo = false)
    }

    /**
     * 清空历史。[keepTempo]：同一首歌里跳转（拖进度条、暂停再播、单曲循环回开头）时速度不会变，
     * 留着它，1.5 秒后只重新对相位；换了一首歌就整个清掉，新歌前 4 秒没有网格。
     */
    fun reset(keepTempo: Boolean) {
        if (keepTempo && period > 0.0) {
            hintBpm = 60.0 / period
            hintConf = conf
        } else {
            hintBpm = 0.0
            hintConf = 0f
        }
        onset.fill(0f)
        bass.fill(0f)
        written = 0
        lastPts = Double.NaN
        baseline = 0.0
        since = 0
        period = 0.0
        t0 = 0.0
        k0 = 0L
        conf = 0f
        rawConf = 0f
        candidate = 0.0
        candidateHits = 0
        accent.fill(0.0)
        downbeat = 0
        downbeatClear = false
        counted = NOT_COUNTED
        strengthCount = 0
        peakStrength = 1.0
        revision++
    }

    /** 一个分析帧：[ptsS] 是窗口末尾的时刻（秒），两路通量见 [RhythmFrame.fluxBass]、[RhythmFrame.fluxMid]。 */
    fun push(ptsS: Double, fluxBass: Float, fluxMid: Float) {
        // 往回走或一下跳出两秒：历史作废、速度留着（切歌由调用方 reset(false)）
        if (!lastPts.isNaN() && (ptsS < lastPts - 0.001 || ptsS > lastPts + 2.0)) reset(keepTempo = true)
        // 中间丢了帧（分析任务排不上）按空帧补齐
        if (!lastPts.isNaN()) {
            val gap = Math.round((ptsS - lastPts) * fps).toInt() - 1
            repeat(min(gap, size).coerceAtLeast(0)) { write(0f, 0f) }
        }
        val raw = fluxBass + MID_WEIGHT * fluxMid
        baseline += (raw - baseline) * BASELINE_RATE
        write(max(0.0, raw - baseline).toFloat(), fluxBass)
        lastPts = ptsS
        if (++since >= ESTIMATE_EVERY) {
            if (written >= fps * TEMPO_AFTER_S) {
                since = 0
                estimate()
            } else if (hintBpm > 0.0 && written >= fps * RELOCK_AFTER_S) {
                since = 0
                relock()
            }
        }
    }

    /** 当前网格的不可变快照。 */
    fun grid(): BeatGrid = BeatGrid(
        periodS = period,
        t0S = t0,
        k0 = k0,
        confidence = conf,
        downbeat = downbeat,
        downbeatClear = downbeatClear,
        strengthIndex = strengthIndex.copyOf(strengthCount),
        strengthValue = strengthValue.copyOf(strengthCount),
    )

    private fun write(o: Float, b: Float) {
        onset[written % size] = o
        bass[written % size] = b
        written++
    }

    /** 按绝对帧号取值，小数部分线性插值；超出历史的部分当 0。 */
    private fun value(x: Double, values: FloatArray): Double {
        val i = floor(x).toInt()
        val f = x - i
        val lo = written - min(written, size)
        fun at(k: Int): Double = if (k < lo || k >= written) 0.0 else values[k % size].toDouble()
        return at(i) * (1 - f) + at(i + 1) * f
    }

    /** 三角核平滑后的起拍强度，峰值位置能插到帧以下。 */
    private fun smoothValue(x: Double): Double =
        value(x, onset) + 0.5 * (value(x - 1, onset) + value(x + 1, onset))

    private fun ptsOf(x: Double): Double = lastPts - (written - 1 - x) / fps

    private fun frameOf(t: Double): Double = written - 1 - (lastPts - t) * fps

    /** 速度图用的历史：σ≈1.2 帧的高斯平滑，小数间隔也能对上峰。 */
    private fun smoothHistory() {
        val m = min(written, size)
        val first = written - m
        for (i in 0 until m) {
            var sum = 0.0
            for (d in -3..3) {
                val k = first + i + d
                if (k >= first && k < written) sum += GAUSS[d + 3] * onset[k % size]
            }
            smoothed[i] = sum.toFloat()
        }
        smoothedCount = m
    }

    private fun history(x: Double): Double {
        val i = floor(x).toInt()
        val f = x - i
        fun at(k: Int): Double = if (k < 0 || k >= smoothedCount) 0.0 else smoothed[k].toDouble()
        return at(i) * (1 - f) + at(i + 1) * f
    }

    /** 错开 [lagS] 秒的皮尔逊相关系数。 */
    private fun rho(lagS: Double): Double {
        val p = lagS * fps
        val m = smoothedCount
        var mean = 0.0
        for (k in 0 until m) mean += smoothed[k]
        mean /= m
        var variance = 0.0
        for (k in 0 until m) variance += (smoothed[k] - mean) * (smoothed[k] - mean)
        if (variance < 1e-12 || p >= m - 1) return 0.0
        var c = 0.0
        var n = 0
        for (k in ceil(p).toInt() until m) {
            c += (smoothed[k] - mean) * (history(k - p) - mean)
            n++
        }
        return c / n / (variance / m)
    }

    /** 速度图：一拍和两拍间隔的自相关，乘偏向 100 BPM 的先验（慢歌的八分音符分解和弦很密，不压一下会被认成两倍速）。 */
    private fun score(bpm: Double, energy: Double): Double {
        val p = 60.0 / bpm * fps
        var s = 0.0
        for (k in ceil(2 * p).toInt() until smoothedCount) {
            val a = smoothed[k]
            if (a > 0f) s += a * (history(k - p) + 0.5 * history(k - 2 * p))
        }
        val octaves = ln(bpm / PRIOR_BPM) / ln(2.0) / PRIOR_WIDTH
        return s / energy * exp(-0.5 * octaves * octaves)
    }

    private fun estimate() {
        smoothHistory()
        val m = smoothedCount
        var energy = 0.0
        var recent = 0.0
        val recentFrom = m - (RECENT_S * fps).toInt()
        for (k in 0 until m) {
            val a = smoothed[k].toDouble()
            energy += a * a
            if (k >= recentFrom) recent += a
        }
        revision++
        // 几乎没有起拍（安静段、纯人声长音）：置信度往下走，网格保持不动
        if (energy < 1e-6 || recent / (RECENT_S * fps) < QUIET_ONSET) {
            rawConf = 0f
            updateConf()
            return
        }

        var best = 0
        for (i in scores.indices) {
            scores[i] = score(MIN_BPM + i * BPM_STEP, energy)
            if (scores[i] > scores[best]) best = i
        }
        val y0 = scores.getOrElse(best - 1) { scores[best] }
        val y1 = scores[best]
        val y2 = scores.getOrElse(best + 1) { scores[best] }
        val bestBpm = MIN_BPM + (best + parabolicOffset(y0, y1, y2)) * BPM_STEP

        val currentBpm = if (period > 0.0) 60.0 / period else 0.0
        val young = lastPts - adoptedAt < YOUNG_S
        val margin = if (young) 1.04 else 1.12
        val need = if (young) 2 else 3
        when {
            currentBpm == 0.0 -> adopt(bestBpm)
            abs(bestBpm / currentBpm - 1) < 0.03 -> period += (60.0 / bestBpm - period) * 0.15
            else -> {
                val current = score(currentBpm, energy)
                val better = y1 > current * margin
                val sameCandidate = abs(bestBpm / (if (candidate > 0.0) candidate else bestBpm) - 1) < 0.03
                candidateHits = if (better && sameCandidate) candidateHits + 1 else if (better) 1 else 0
                candidate = bestBpm
                if (candidateHits >= need) {
                    adopt(bestBpm)
                    candidateHits = 0
                }
            }
        }
        // 没有拍的音乐这个相关在 0.2 上下。说唱、切分重的律动常常一拍、两拍错开都不像，要错开一整小节才像
        // （真机录的说唱一拍 0 上下、一小节 0.4–0.46），所以也看一小节；错开后重叠不到 4 秒就不看，太短不可信
        val bar = if (4 * period * fps <= smoothedCount - RECENT_S * fps) rho(4 * period) else 0.0
        val r = maxOf(rho(period), rho(2 * period), bar)
        tempoConf = smooth01((r - 0.22) / (0.42 - 0.22))
        updatePhase()
        updateConf()
        countBeats()
    }

    /** 跳转后历史还不够估速度：沿用原来的速度，只找相位；相位梳的峰够突出就直接恢复置信度。 */
    private fun relock() {
        if (period == 0.0) adopt(hintBpm)
        smoothHistory()
        updatePhase()
        rawConf = hintConf * phaseConf
        conf = rawConf
        countBeats()
        revision++
    }

    private fun adopt(bpm: Double) {
        period = 60.0 / bpm
        t0 = 0.0
        k0 = 0L
        adoptedAt = lastPts
        accent.fill(0.0)
        counted = NOT_COUNTED
        strengthCount = 0
        fresh = true
    }

    private fun updatePhase() {
        val p = period * fps
        val last = written - 1.0
        val beats = floor(PHASE_S * fps / p).toInt()
        var bestJ = 0
        for (j in 0 until PHASE_STEPS) {
            val phase = j.toDouble() / PHASE_STEPS * p
            var c = 0.0
            var w = 1.0
            for (k in 0..beats) {
                c += w * smoothValue(last - phase - k * p)
                w *= 0.88
            }
            comb[j] = c
            if (c > comb[bestJ]) bestJ = j
        }
        var mean = 0.0
        for (j in 0 until PHASE_STEPS) mean += comb[j] / PHASE_STEPS
        phaseConf = smooth01(((comb[bestJ] - mean) / max(comb[bestJ], 1e-9) - 0.3) / (0.6 - 0.3))
        phaseConf *= coverage(last - bestJ.toDouble() / PHASE_STEPS * p, p)
        rawConf = tempoConf * phaseConf
        val off = parabolicOffset(
            comb[(bestJ + PHASE_STEPS - 1) % PHASE_STEPS],
            comb[bestJ],
            comb[(bestJ + 1) % PHASE_STEPS],
        )
        val measured = ptsOf(last - (bestJ + off) / PHASE_STEPS * p) - ONSET_DETECT_LAG_S
        if (fresh) {
            t0 = measured
            k0 = 0L
            fresh = false
            return
        }
        // 只往测量值慢慢拉：拍号保持连续，小节首拍的统计才不会乱
        val k = Math.round((measured - t0) / period)
        val predicted = t0 + k * period
        t0 = predicted + (measured - predicted) * PHASE_PULL
        k0 += k
    }

    /**
     * 整段历史里，从 [newest] 帧起每隔 [p] 帧的网格位置有几成落着像样的起拍（不到最强那一拍的 12% 不算），换成 0..1 的系数。
     * 随机出现的打击声偶尔几下凑巧等距，速度图和相位梳都会很突出；真有拍的音乐几乎每个网格位置都有东西。
     * 看整段 8 秒，过门里安静两小节也不会掉锁；跳转后历史还短，只数历史里的位置，不到 3 个不判断。
     */
    private fun coverage(newest: Double, p: Double): Float {
        val oldest = (written - min(written, size)).toDouble()
        var top = 0.0
        var positions = 0
        var x = newest
        while (x >= oldest + 1 && positions < coverageHits.size) {
            val hit = max(smoothValue(x), max(smoothValue(x - 1), smoothValue(x + 1)))
            coverageHits[positions++] = hit
            top = max(top, hit)
            x -= p
        }
        if (positions < 3) return 1f
        var covered = 0
        for (i in 0 until positions) if (coverageHits[i] >= COVERED * top) covered++
        return smooth01((covered.toDouble() / positions - COVERAGE_FLOOR) / COVERAGE_SPAN)
    }

    private fun updateConf() {
        conf += (rawConf - conf) * 0.25f
    }

    /** 已经完整听过的拍：量一下附近的低频起拍强度，记给小节首拍判断和界面的轻重。拍号用绝对拍号，网格挪动时不变。 */
    private fun countBeats() {
        if (period == 0.0) return
        val safe = lastPts - COUNT_MARGIN_S
        val kTo = floor((safe - t0) / period).toLong()
        var kFrom = if (counted == NOT_COUNTED) kTo - floor(3.0 / period).toLong() else counted - k0 + 1
        kFrom = max(kFrom, kTo - STRENGTH_KEPT)
        for (k in kFrom..kTo) {
            val x = frameOf(t0 + k * period + ONSET_DETECT_LAG_S)
            var s = 0.0
            for (d in -2..2) s = max(s, value(x + d, bass))
            val index = k0 + k
            val position = Math.floorMod(index, 4L).toInt()
            for (i in 0 until 4) if (i != position) accent[i] *= 0.97
            accent[position] = accent[position] * 0.85 + s
            peakStrength = max(s, peakStrength * 0.995)
            recordStrength(index, (s / peakStrength).toFloat())
            counted = index
        }
        var best = 0
        var sum = 0.0
        for (i in 0 until 4) {
            sum += accent[i]
            if (accent[i] > accent[best]) best = i
        }
        // 四拍一样重（四拍底鼓）时没有“首拍”可言：仍然每四拍算一次，只是不保证落在第一拍
        downbeatClear = accent[best] > sum / 4 * 1.25
        downbeat = best
    }

    private fun recordStrength(index: Long, value: Float) {
        if (strengthCount == STRENGTH_KEPT) {
            strengthIndex.copyInto(strengthIndex, 0, 1, STRENGTH_KEPT)
            strengthValue.copyInto(strengthValue, 0, 1, STRENGTH_KEPT)
            strengthCount--
        }
        strengthIndex[strengthCount] = index
        strengthValue[strengthCount] = value
        strengthCount++
    }

    private companion object {
        const val HISTORY_S = 8.0
        const val PHASE_S = 4.0
        const val MIN_BPM = 60.0
        const val MAX_BPM = 180.0
        const val BPM_STEP = 0.25
        const val ESTIMATE_EVERY = 8
        const val TEMPO_AFTER_S = 4.0
        const val RELOCK_AFTER_S = 1.5
        const val RECENT_S = 4.0
        const val QUIET_ONSET = 0.15
        const val YOUNG_S = 6.0
        const val PHASE_STEPS = 48
        const val PHASE_PULL = 0.35
        const val COUNT_MARGIN_S = 0.12
        const val STRENGTH_KEPT = 64
        const val NOT_COUNTED = Long.MIN_VALUE
        const val COVERED = 0.12
        const val COVERAGE_FLOOR = 0.4
        const val COVERAGE_SPAN = 0.3
        const val PRIOR_BPM = 100.0
        const val PRIOR_WIDTH = 0.7


        /** 军鼓、拍手那一路只帮着定拍，不让钢琴八分音符、拍手把速度拉成两倍或一半。 */
        const val MID_WEIGHT = 0.3f
        const val BASELINE_RATE = 0.06
        val GAUSS = doubleArrayOf(0.04, 0.11, 0.21, 0.28, 0.21, 0.11, 0.04)

        fun historySize(fps: Double): Int = ceil(fps * HISTORY_S).toInt().coerceAtLeast(16)

        fun parabolicOffset(a: Double, b: Double, c: Double): Double {
            val curvature = a - 2 * b + c
            return if (curvature < 0) (0.5 * (a - c) / curvature).coerceIn(-0.5, 0.5) else 0.0
        }

        fun smooth01(x: Double): Float {
            val t = x.coerceIn(0.0, 1.0)
            return (t * t * (3 - 2 * t)).toFloat()
        }
    }
}
