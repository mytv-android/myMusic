package com.mytv.mymusic.ui.player

import com.mytv.mymusic.data.RhythmIntensity
import com.mytv.mymusic.playback.RhythmFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * 律动强度的幅度表。两档共用平滑参数，只是幅度不同；柔和档没有鼓点光环。
 *
 * 整体亮度的起伏主要来自三处，都按“标准档逐帧亮度变化不超过约 3%、比动态多出的起伏不超过约 6 个百分点”压过：
 * - 光团半径：光团是屏幕混合的大片渐变，半径涨 14% 面积就涨三成，原来跟着 70ms 起音的低频走，一拍就是一次整屏提亮。
 *   现在半径跟慢包络 [FlowFieldMotion.swell]（0.3s 起 / 1s 落），随段落的低频强弱呼吸，不在单拍上跳。
 * - 鼓点：半径和光量同时在 50ms 里胀开，又和低频起音、光环叠在同一帧上，是逐帧跳变的主要来源，幅度减半。
 * - 封面层和光晕的不透明度：也改跟慢包络，幅度减小。
 * [bass] 是光团光量里仍跟逐帧低频的一小部分，保留拍点上的一点亮。
 */
internal class FlowIntensity private constructor(
    val speed: Float,
    val radius: Float,
    val mid: Float,
    val treble: Float,
    val bass: Float,
    val bloomRadius: Float,
    val bloomAlpha: Float,
    val cover: Float,
    val ripple: Boolean,
) {
    companion object {
        private val Soft = FlowIntensity(0.5f, 0.06f, 0.05f, 0.03f, 0.02f, 0.03f, 0.01f, 0.03f, ripple = false)
        private val Standard = FlowIntensity(1.3f, 0.12f, 0.08f, 0.06f, 0.03f, 0.05f, 0.02f, 0.05f, ripple = true)

        fun of(intensity: RhythmIntensity): FlowIntensity = when (intensity) {
            RhythmIntensity.Soft -> Soft
            RhythmIntensity.Standard -> Standard
        }
    }
}

/** 一团封面色光：中心沿李萨如曲线漂，半径按画面长边、位置按宽高的比例算。 */
internal class FlowBlob(
    val cx: Float,
    val cy: Float,
    val ax: Float,
    val ay: Float,
    val fx: Float,
    val fy: Float,
    val p: Float,
    val q: Float,
    val radius: Float,
    val alpha: Float,
)

internal val FlowBlobs = listOf(
    FlowBlob(0.26f, 0.24f, 0.22f, 0.13f, 1f, 1.3f, 0f, 1.1f, 0.66f, 0.36f),
    FlowBlob(0.76f, 0.6f, 0.18f, 0.18f, 0.8f, 1.1f, 2f, 0.3f, 0.6f, 0.28f),
    FlowBlob(0.34f, 0.84f, 0.25f, 0.1f, 1.2f, 0.9f, 4f, 2.2f, 0.56f, 0.25f),
    FlowBlob(0.72f, 0.16f, 0.15f, 0.14f, 1.5f, 1.7f, 1f, 3f, 0.36f, 0.16f),
)

/**
 * 流光场的运动状态，不依赖 Compose，方便单测。
 * 音乐只改速度、半径和光量；所有位置都由相位积分得到，所以不会跟着音量来回跳，暂停时慢慢停住而不是跳回原位。
 */
internal class FlowFieldMotion(moving: Boolean) {
    var intensity = RhythmIntensity.Standard
    var reactive = false
    var playing = moving

    var bass = 0f
        private set
    var mid = 0f
        private set
    var treble = 0f
        private set
    var loudness = 0f
        private set

    /** 低频的慢包络（0.3s 起、1s 落）：光团半径、封面层和光晕亮度、进度条波幅跟它，随段落的低频强弱起伏，不在单拍上跳。 */
    var swell = 0f
        private set
    var bloom = 0f
        private set
    private var kickEnvelope = 0f

    /** 0..1 的流动倍率：播放时趋向 1，暂停时趋向 0。 */
    var run = if (moving) 1f else 0f
        private set

    private var targetBass = 0f
    private var targetMid = 0f
    private var targetTreble = 0f
    private var targetLoudness = 0f

    // 相位用 Double 并按 20π 取模：所有频率系数都是 0.1 的整数倍，20π 是它们共同的周期，取模不会跳
    private val phases = doubleArrayOf(0.0, 1.7, 3.1, 4.4, 0.6)

    /** 底层封面的旋转角（弧度）。 */
    var angle = 0.0
        private set

    // 按出生先后排列，最多 2 圈
    private val rippleAges = FloatArray(MAX_RIPPLES)
    var rippleCount = 0
        private set

    var coverFade = 1f
        private set
    var colorFade = 1f
        private set

    private var lastFrameNanos = NO_FRAME_NS

    private val live: Boolean get() = reactive && playing

    private val flow: FlowIntensity get() = FlowIntensity.of(intensity)

    // 上一次看到的鼓点序号；NO_KICK 表示还没收到过帧，第一帧只记序号，不把分析器之前攒下的计数当成新鼓点
    private var lastKickCount = NO_KICK

    /** 分析帧：序号前进就算一次鼓点（中间被合并掉几帧也只闪一次）；不在律动时只记序号。 */
    fun feed(frame: RhythmFrame) {
        val kicked = lastKickCount != NO_KICK && frame.kickCount > lastKickCount
        lastKickCount = frame.kickCount
        if (!live) return
        targetBass = frame.bass
        targetMid = frame.mid
        targetTreble = frame.treble
        targetLoudness = frame.loudness
        if (kicked) {
            kickEnvelope = 1f
            if (flow.ripple) addRipple()
        }
    }

    fun startCoverFade() {
        coverFade = 0f
    }

    fun startColorFade() {
        colorFade = 0f
    }

    /** 按帧时间推进。第一帧只记时间；后台回来等长间隔最多按 [MAX_STEP_S] 走一步。 */
    fun advanceTo(frameTimeNanos: Long) {
        val last = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        if (last != NO_FRAME_NS) step(((frameTimeNanos - last) / 1e9f).coerceIn(0f, MAX_STEP_S))
    }

    /** 帧循环停下时调用，下次启动不把空闲时间算进去。 */
    fun stopClock() {
        lastFrameNanos = NO_FRAME_NS
    }

    fun step(dt: Float) {
        if (!live) {
            targetBass = 0f
            targetMid = 0f
            targetTreble = 0f
            targetLoudness = 0f
        }
        bass = follow(bass, targetBass, dt, 0.07f, 0.38f)
        swell = follow(swell, targetBass, dt, SWELL_UP_S, SWELL_DOWN_S)
        mid = follow(mid, targetMid, dt, 0.12f, 0.6f)
        treble = follow(treble, targetTreble, dt, 0.2f, 0.5f)
        loudness = follow(loudness, targetLoudness, dt, 0.5f, 1.2f)
        kickEnvelope *= exp(-dt / 0.45f)
        bloom = follow(bloom, kickEnvelope, dt, 0.05f, 0.3f)
        run = follow(run, if (playing) 1f else 0f, dt, 0.5f, 0.5f)

        val speed = run * (1f + flow.speed * loudness) * TAU / 26.0
        for (i in phases.indices) phases[i] = (phases[i] + dt * speed * (0.7 + i * 0.13)) % PHASE_PERIOD
        angle = (angle + dt * run * (1f + 0.5f * loudness) * TAU / 70.0) % TAU

        var kept = 0
        for (i in 0 until rippleCount) {
            val age = rippleAges[i] + dt
            if (age < RIPPLE_LIFE_S) rippleAges[kept++] = age
        }
        rippleCount = kept

        coverFade = (coverFade + dt / FADE_S).coerceAtMost(1f)
        colorFade = (colorFade + dt / FADE_S).coerceAtMost(1f)
        if (isSettled) settle()
    }

    /** 暂停后流动和光量都已经落到看不出的程度，帧循环可以停了。 */
    val isSettled: Boolean
        get() = !playing && run < EPSILON && bass < EPSILON && swell < EPSILON && mid < EPSILON && treble < EPSILON &&
            bloom < EPSILON && rippleCount == 0 && coverFade >= 1f && colorFade >= 1f

    /** 原地停住：相位和角度保留，只把残留的光量、光环和过渡清掉（减少动画时也用它定格）。 */
    fun settle() {
        bass = 0f
        swell = 0f
        mid = 0f
        treble = 0f
        loudness = 0f
        bloom = 0f
        kickEnvelope = 0f
        run = if (playing) run else 0f
        targetBass = 0f
        targetMid = 0f
        targetTreble = 0f
        targetLoudness = 0f
        rippleCount = 0
        coverFade = 1f
        colorFade = 1f
    }

    private fun addRipple() {
        if (rippleCount == MAX_RIPPLES) {
            rippleAges.copyInto(rippleAges, 0, 1, MAX_RIPPLES)
            rippleCount--
        }
        rippleAges[rippleCount++] = 0f
    }

    fun phase(index: Int): Double = phases[index]

    /** 封面中心的水平漂移，宽度的比例。 */
    val coverDriftX: Float get() = 0.06f * sin(phases[4]).toFloat()

    /** 封面中心的垂直漂移，高度的比例。 */
    val coverDriftY: Float get() = 0.04f * cos(phases[4] * 0.8).toFloat()

    /** 封面层的基础透明度；切歌时再乘上交叉淡入的进度。 */
    val coverAlpha: Float get() = 0.42f + flow.cover * swell

    fun blobX(index: Int): Float = FlowBlobs[index].let { it.cx + it.ax * sin(phases[index] * it.fx + it.p).toFloat() }

    fun blobY(index: Int): Float = FlowBlobs[index].let { it.cy + it.ay * sin(phases[index] * it.fy + it.q).toFloat() }

    /** 光团半径，画面长边的比例。 */
    fun blobRadius(index: Int): Float =
        FlowBlobs[index].radius * (1f + flow.radius * swell + flow.bloomRadius * bloom)

    /** 第二团跟中频、第四团跟高频，其余跟低频。 */
    fun blobAlpha(index: Int): Float {
        val signal = when (index) {
            1 -> flow.mid * mid
            3 -> flow.treble * treble
            else -> flow.bass * bass
        }
        return (FlowBlobs[index].alpha + signal + flow.bloomAlpha * bloom).coerceIn(0f, MAX_BLOB_ALPHA)
    }

    fun rippleAge(index: Int): Float = rippleAges[index]

    companion object {
        const val RIPPLE_LIFE_S = 0.9f
        private const val MAX_RIPPLES = 2
        private const val FADE_S = 0.45f
        private const val MAX_STEP_S = 0.1f
        private const val MAX_BLOB_ALPHA = 0.7f
        private const val EPSILON = 2e-3f
        private const val NO_KICK = -1
        private const val SWELL_UP_S = 0.3f
        private const val SWELL_DOWN_S = 1f
        private const val RIPPLE_RISE_S = 0.12f
        private const val TAU = 2.0 * PI
        private const val PHASE_PERIOD = 20.0 * PI

        /** 光环半径，画面长边的比例。 */
        fun rippleRadius(age: Float): Float = 0.16f + age * 0.7f

        /** 光环不透明度：0.12 秒淡入到 0.06 再随年龄淡出。一出生就满亮的话，鼓点那一帧会和光团胀开叠成一次跳亮。 */
        fun rippleAlpha(age: Float): Float =
            0.06f * (age / RIPPLE_RISE_S).coerceIn(0f, 1f) * (1f - age / RIPPLE_LIFE_S).coerceAtLeast(0f).pow(1.5f)
    }
}

/** 帧循环还没画过任何一帧。 */
internal const val NO_FRAME_NS = Long.MIN_VALUE

/** 电视上的帧间隔上限：30 帧。 */
internal const val TV_FRAME_INTERVAL_NS = 1_000_000_000L / 30

/**
 * 限帧：离上次推进不到 [minIntervalNanos] 的帧跳过，0 表示不限。留 1/8 的余量，垂直同步时间戳有抖动时
 * 60Hz 上也稳定地隔一帧画一帧（30 帧），不会偶尔拖成隔两帧；50Hz 上是 25 帧，120Hz 上隔三帧画一帧。
 */
internal fun backdropFrameDue(frameNanos: Long, lastNanos: Long, minIntervalNanos: Long): Boolean =
    lastNanos == NO_FRAME_NS || frameNanos - lastNanos >= minIntervalNanos - minIntervalNanos / 8

/** 与帧率无关的一阶平滑；上升和回落用不同的时间常数（秒）。 */
internal fun follow(current: Float, target: Float, dt: Float, up: Float, down: Float): Float {
    val tau = if (target > current) up else down
    return current + (target - current) * (1f - exp(-dt / tau))
}

/**
 * 几个帧循环共用的限帧节拍。各自按 [backdropFrameDue] 限到 30 帧的话相位会错开（背景画偶数帧、封面画奇数帧），
 * 叠起来窗口还是每个垂直同步都要重画。同一帧里（同一个 frameTimeNanos）第一个问到的决定这一帧画不画，后面的沿用，
 * 所有循环就落在同一批垂直同步上。只在主线程的帧回调里用。
 */
internal class FrameGate(private val minIntervalNanos: Long) {
    private var decidedFor = NO_FRAME_NS
    private var decision = false
    private var drawnAt = NO_FRAME_NS

    fun due(frameNanos: Long): Boolean {
        if (frameNanos != decidedFor) {
            decidedFor = frameNanos
            decision = backdropFrameDue(frameNanos, drawnAt, minIntervalNanos)
            if (decision) drawnAt = frameNanos
        }
        return decision
    }
}

/** 电视上背景、封面旋转和逐字歌词共用的 30 帧节拍：运动都按真实帧间隔积分，少画一半帧不改变速度，只省 GPU。 */
internal val TvFrameGate = FrameGate(TV_FRAME_INTERVAL_NS)
