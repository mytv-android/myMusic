package com.mytv.mymusic.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal const val RHYTHM_TARGET_FPS = 30

/** A hop larger than the FFT window would leave short transients unanalysed. */
internal fun rhythmHopSamples(sampleRateHz: Int): Int =
    (sampleRateHz / RHYTHM_TARGET_FPS).coerceIn(1, RHYTHM_FFT_SIZE)

/**
 * Taps decoded playback PCM without using the microphone or modifying the samples.
 * FFT work stays off ExoPlayer's audio thread.
 *
 * AudioSink 是一块一块灌数据的：起播、跳转后一口气灌满半秒的缓冲，平时一次几十毫秒，一块里常有好几个分析点。
 * 每个分析点的窗口都按顺序排队交给分析线程（[WINDOW_POOL] 个窗口轮着用），节拍跟踪要的是连续的起拍曲线；
 * 只在分析线程落后太多、窗口用完时才丢帧，丢掉的帧跟踪器按空帧补。
 *
 * 取样点在 AudioTrack 之前，分析比耳朵早一个缓冲（约半秒）。每个分析帧带上窗口末尾的媒体时刻 [RhythmFrame.ptsUs]，
 * 放进 [timeline]；界面按 [clock] 推算的“此刻听到的位置”取帧，画面就和声音对上了。
 * pts 的推算：[RhythmAudioSink] 在每次 handleBuffer 前报上这个缓冲的 pts；TeeAudioProcessor 每次清空（跳转、换格式）后
 * 收到的第一个样本就是那时正在交给 AudioSink 的缓冲的第一个样本，以它为锚，之后按样本数往后数。和 DefaultAudioSink 自己算位置的办法一样。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class RhythmAnalyzer {
    private val _frames = MutableStateFlow(RhythmFrame())

    /** 最新的一帧，不管播没播到；界面按听到的位置取帧请用 [timeline]。 */
    val frames: StateFlow<RhythmFrame> = _frames.asStateFlow()

    /** 此刻听到的位置，由 [RhythmAudioSink] 在音频渲染线程上更新。 */
    val clock = PlaybackClock()

    /** 带 pts 的分析帧，界面播到才取。 */
    val timeline = RhythmTimeline()

    /** 节拍跟踪的结果，界面按听到的位置取网格（[BeatState.gridAt]）。跳转、切歌、停止分析时清空。 */
    @Volatile
    var beats: BeatState = BeatState.EMPTY
        private set

    @Volatile
    private var active = false

    @Volatile
    private var sampleRateHz = 0

    @Volatile
    private var channelCount = 0

    @Volatile
    private var encoding = C.ENCODING_PCM_16BIT

    private val generation = AtomicInteger()
    private val song = AtomicInteger()
    private val frameLock = Any()

    // Guarded by frameLock. Never cleared on reset, so the UI never sees the count move and mistake a reset for a kick.
    private var kickCount = 0
    private var executor: ExecutorService? = null

    // The ring is audio-thread confined; each queued job owns one window from the pool until it finishes.
    private val ring = FloatArray(RHYTHM_FFT_SIZE)
    private val windows = ArrayBlockingQueue<FloatArray>(WINDOW_POOL).apply {
        repeat(WINDOW_POOL) { add(FloatArray(RHYTHM_FFT_SIZE)) }
    }
    private var ringGeneration = -1
    private var ringWrite = 0
    private var ringSize = 0
    private var hopSamples = 1
    private var samplesUntilHop = 1

    // 下面这些只在音频渲染线程上读写：pts 锚点和无缝衔接的分界
    private var inputPtsUs = 0L
    private var needAnchor = true
    private var anchorPtsUs = 0L
    private var streamFrames = 0L
    private var outputOffsetUs = Long.MIN_VALUE
    private var streamChanged = false
    private var boundaryPtsUs = NO_BOUNDARY
    private var streamSerial = 0
    private var streamStartUs = 0L

    // Only the analysis executor accesses these three fields.
    private val analysis = RhythmAnalysis()
    private var analysisGeneration = -1
    private var analysisStream = 0
    private var analysisSong = 0
    private var lastAnalysisPtsUs = NO_BOUNDARY
    private val tracker = BeatTracker()
    private var trackerRevision = -1
    private var previousGrid: BeatGrid? = null
    private var previousUntilS = Double.NEGATIVE_INFINITY

    @Synchronized
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        reset()
    }

    /** Drops the previous track's gain history and any queued visual result. */
    fun reset() {
        synchronized(frameLock) {
            generation.incrementAndGet()
            timeline.clear()
            beats = BeatState.EMPTY
            _frames.value = RhythmFrame(kickCount = kickCount)
        }
    }

    /**
     * 播放器换了一首（点歌、上一首 / 下一首、换队列）。这种跳转渲染器的偏移不变，音频线程上和同一首里拖进度分不出来；
     * 不说的话节拍跟踪会沿用上一首的速度、跳转后一秒多就按旧速度锁上。分析线程处理下一帧时把速度清掉。
     */
    fun onNewSong() {
        song.incrementAndGet()
    }

    /** [RhythmAudioSink]：AudioSink 正要处理 pts 为 [ptsUs] 的缓冲（同一个缓冲分几次交时每次都报）。 */
    internal fun onSinkInput(ptsUs: Long) {
        inputPtsUs = ptsUs
        // 输出换到了下一段（无缝接到下一首、单曲循环），而且这次没有清空：这个缓冲就是新一段的开头
        if (streamChanged && !needAnchor) {
            streamChanged = false
            boundaryPtsUs = ptsUs
        }
    }

    /** [RhythmAudioSink]：渲染器换了输出段的偏移。第一次和换到另一项时偏移会变，同一项里跳转不变。 */
    internal fun onOutputStreamOffset(offsetUs: Long) {
        if (offsetUs == outputOffsetUs) return
        outputOffsetUs = offsetUs
        streamChanged = true
    }

    val audioBufferSink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            this@RhythmAnalyzer.sampleRateHz = sampleRateHz
            this@RhythmAnalyzer.channelCount = channelCount
            this@RhythmAnalyzer.encoding = encoding
            hopSamples = rhythmHopSamples(sampleRateHz)
            // 处理链清空了：下一个样本是那时交给 AudioSink 的缓冲的第一个样本，重新取锚
            needAnchor = true
            streamFrames = 0L
            reset()
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (sampleRateHz <= 0 || channelCount <= 0) return
            val bytesPerSample = when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_FLOAT -> 4
                else -> return
            }
            val frameBytes = bytesPerSample * channelCount
            if (frameBytes <= 0) return
            if (needAnchor) {
                needAnchor = false
                anchorPtsUs = inputPtsUs
                streamFrames = 0L
                // 清空后的第一段：换到了别的歌就换一个段号；同一首里跳转保持段号
                if (streamChanged) streamSerial++
                streamStartUs = inputPtsUs
                streamChanged = false
                boundaryPtsUs = NO_BOUNDARY
            }
            // 不分析时也要数样本，锚点才一直有效
            if (!active) {
                streamFrames += buffer.remaining() / frameBytes
                return
            }
            val currentGeneration = generation.get()
            if (ringGeneration != currentGeneration) {
                ringGeneration = currentGeneration
                ringWrite = 0
                ringSize = 0
                samplesUntilHop = hopSamples
            }
            val input = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
            while (input.remaining() >= frameBytes) {
                var mixed = 0f
                repeat(channelCount) {
                    mixed += when (encoding) {
                        C.ENCODING_PCM_16BIT -> (input.short / 32768f).coerceIn(-1f, 1f)
                        else -> input.float.coerceIn(-1f, 1f)
                    }
                }
                appendSample(mixed / channelCount, currentGeneration)
            }
        }
    }

    private fun appendSample(sample: Float, currentGeneration: Int) {
        streamFrames++
        ring[ringWrite] = sample
        ringWrite = (ringWrite + 1) % ring.size
        if (ringSize < ring.size) ringSize++
        if (ringSize < ring.size) return
        if (--samplesUntilHop > 0) return
        samplesUntilHop = hopSamples
        scheduleAnalysis(currentGeneration)
    }

    private fun scheduleAnalysis(currentGeneration: Int) {
        // 窗口末尾的媒体时刻：锚点加上之后数过的样本。分界要在排队前判断，分析任务排不上时也不能漏掉
        val ptsUs = anchorPtsUs + streamFrames * 1_000_000L / sampleRateHz
        if (boundaryPtsUs != NO_BOUNDARY && ptsUs >= boundaryPtsUs) {
            streamStartUs = boundaryPtsUs
            boundaryPtsUs = NO_BOUNDARY
            streamSerial++
        }
        val window = windows.poll() ?: return
        val stream = streamSerial
        val streamStart = streamStartUs
        val hop = hopSamples
        val oldest = (ringWrite - ring.size + ring.size) % ring.size
        repeat(ring.size) { index ->
            window[index] = ring[(oldest + index) % ring.size]
        }
        val rate = sampleRateHz
        try {
            analysisExecutor().execute {
                try {
                    if (!active || generation.get() != currentGeneration) return@execute
                    // 跳转、切歌（清空过处理链）或者无缝接到了下一段：上一段的增益历史作废
                    if (analysisGeneration != currentGeneration || analysisStream != stream) {
                        analysis.reset()
                        tracker.configure(rate.toDouble() / hop)
                        if (analysisGeneration != currentGeneration) {
                            // 清空过处理链：同一首里跳转（段号没变）留着速度，换了歌整个重来
                            tracker.reset(keepTempo = analysisStream == stream)
                            previousGrid = null
                        } else {
                            // 无缝接到下一段：分析先进了新的一段，界面播到分界之前继续用旧网格
                            previousGrid = tracker.grid()
                            previousUntilS = streamStart / 1e6
                            tracker.reset(keepTempo = false)
                        }
                        trackerRevision = -1
                        analysisGeneration = currentGeneration
                        analysisStream = stream
                        lastAnalysisPtsUs = NO_BOUNDARY
                    }
                    // 换了一首：不管上面是不是按跳转留了速度，都从头来（播放器的通知可能比清空处理链早，也可能晚一点）
                    val currentSong = song.get()
                    if (currentSong != analysisSong) {
                        analysisSong = currentSong
                        tracker.reset(keepTempo = false)
                        previousGrid = null
                        trackerRevision = -1
                    }
                    // 按媒体时间算间隔：起播和跳转后 AudioSink 一口气灌满缓冲，墙上时钟的间隔不代表音乐走了多久
                    val dtMs = if (lastAnalysisPtsUs == NO_BOUNDARY) {
                        1000f / RHYTHM_TARGET_FPS
                    } else {
                        ((ptsUs - lastAnalysisPtsUs) / 1_000f).coerceIn(1f, 200f)
                    }
                    lastAnalysisPtsUs = ptsUs
                    val result = analysis.analyzePcm(window, rate, dtMs)
                    tracker.push(ptsUs / 1e6, result.fluxBass, result.fluxMid)
                    synchronized(frameLock) {
                        // Stale-generation results are dropped without being counted.
                        if (active && generation.get() == currentGeneration) {
                            if (result.kick) kickCount++
                            val frame = result.copy(kickCount = kickCount, ptsUs = ptsUs)
                            timeline.push(frame)
                            _frames.value = frame
                            if (tracker.revision != trackerRevision) {
                                trackerRevision = tracker.revision
                                beats = BeatState(tracker.grid(), previousGrid, previousUntilS)
                            }
                        }
                    }
                } finally {
                    windows.offer(window)
                }
            }
        } catch (_: RuntimeException) {
            // Analysis is visual-only; executor rejection must not interrupt playback.
            windows.offer(window)
        }
    }

    @Synchronized
    private fun analysisExecutor(): ExecutorService =
        executor?.takeUnless { it.isShutdown } ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "myMusic-rhythm").apply {
                priority = Thread.NORM_PRIORITY - 1
                isDaemon = true
            }
        }.also { executor = it }

    private companion object {
        const val NO_BOUNDARY = Long.MIN_VALUE

        /** 排队中的分析窗口上限：16 帧约半秒，盖得住起播时一口气灌进来的缓冲。 */
        const val WINDOW_POOL = 16
    }
}
