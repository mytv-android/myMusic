package com.mytv.mymusic.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import com.mytv.mymusic.AppLog
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 智能过渡的离线分析：把下一首完整解码成 PCM，算出拍网格、响度、调性、首尾静音和段落。
 *
 * 只分析能提前拿到完整音频的歌：本地 / 已下载直接读文件，在线歌曲走播放缓存边下边存（用完删临时文件），
 * 直播、DRM 整曲、试听片段不进来。一首歌只分析一次，结果交给 [AutomixAnalysisStore]。
 */
class AutomixAnalyzer(
    private val context: Context,
    private val cache: SimpleCache?,
    private val store: AutomixAnalysisStore,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "automix-analysis").apply { isDaemon = true }
    }
    private val queued = ConcurrentHashMap.newKeySet<String>()

    /** [song] 得是已经解析出地址的队列项；同一首重复请求会被合并，已经分析过的直接跳过。 */
    fun request(song: Song) {
        if (song.url.isBlank() || song.isLive) return
        if (!song.extra[AppleWidevineDrmCallback.EXTRA_DRM_SCHEME].isNullOrBlank()) return
        if (song.url.endsWith(".m3u8", ignoreCase = true)) return
        val key = song.key
        if (key.isBlank() || store.cached(key) != null) return
        if (!queued.add(key)) return
        executor.execute {
            try {
                analyze(song)
            } catch (error: Throwable) {
                AppLog.w("Automix", "分析失败《${song.title}》：${error.javaClass.simpleName}: ${error.message?.take(120)}")
            } finally {
                queued.remove(key)
            }
        }
    }

    private fun analyze(song: Song) {
        val expectedMs = song.duration * 1000L
        val cached = runBlocking { store.get(song.key, expectedMs) }
        if (cached != null) return
        val directory = File(context.cacheDir, "automix").apply { mkdirs() }
        val temp = File(directory, "audio.tmp")
        try {
            val bytes = copyAudio(song, temp)
            if (bytes <= 0L) return
            val features = decodeAndMeasure(temp) ?: return
            if (features.durationMs < MIN_DURATION_MS) return
            val analysis = features.toAnalysis(song.key, System.currentTimeMillis())
            runBlocking { store.put(analysis) }
            AppLog.i(
                "Automix",
                "分析完成《${song.title}》：${"%.0f".format(analysis.bpm)} BPM · " +
                    "${keyLabel(analysis.keyIndex, analysis.keyMinor)} · ${"%.1f".format(analysis.loudnessDb)} dBFS · " +
                    "前奏 ${analysis.introMs / 1000}s / 尾奏 ${analysis.outroMs / 1000}s · " +
                    "置信度 ${"%.2f".format(analysis.beatGrid.confidence)}",
            )
        } finally {
            runCatching { temp.delete() }
        }
    }

    /** 把整首音频落到临时文件：本地直接抄，在线走播放缓存（缓存里有的就不再下载）。 */
    private fun copyAudio(song: Song, target: File): Long {
        val uri = Uri.parse(song.url)
        if (uri.scheme == "file") {
            val source = uri.path?.let(::File) ?: return 0L
            if (!source.exists()) return 0L
            if (source.length() > MAX_AUDIO_BYTES) return 0L
            source.copyTo(target, overwrite = true)
            return target.length()
        }
        val mediaCache = cache ?: return 0L
        val dataSource = CacheDataSource.Factory()
            .setCache(mediaCache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
            .createDataSource()
        return try {
            dataSource.open(DataSpec(uri))
            var total = 0L
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    total += read
                    if (total > MAX_AUDIO_BYTES) return 0L
                }
            }
            total
        } catch (error: Throwable) {
            AppLog.w("Automix", "取音频失败《${song.title}》：${error.message?.take(100)}")
            0L
        } finally {
            runCatching { dataSource.close() }
        }
    }

    /** MediaExtractor + MediaCodec 解成 PCM，逐跳送进离线特征提取。 */
    private fun decodeAndMeasure(file: File): OfflineFeatures? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val features = OfflineFeatures(sampleRate, channels)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        features.configure(sampleRate, channels)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outIndex >= 0) {
                        val buffer = decoder.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            features.push(buffer, encoding)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            features.finish()
        } catch (error: Throwable) {
            AppLog.w("Automix", "解码失败：${error.javaClass.simpleName}: ${error.message?.take(120)}")
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val MAX_AUDIO_BYTES = 64L * 1024 * 1024
        const val MIN_DURATION_MS = 8_000L
    }
}

/**
 * 流式特征：每 1/30 秒取最近 2048 个样本过一遍 [RhythmAnalysis]，喂 [BeatTracker]；
 * 同时累计响度、调性和每一跳的能量。分析线程独占，不做同步。
 */
private class OfflineFeatures(sampleRate: Int, channels: Int) {
    private var rate = sampleRate
    private var channelCount = channels
    private var hop = max(1, sampleRate / 30)
    private val ring = FloatArray(RHYTHM_FFT_SIZE)
    private var ringPos = 0
    private var ringCount = 0
    private var sinceHop = 0
    private var sampleIndex = 0L
    private var loudnessSquares = 0.0
    private var sampleTotal = 0L

    private var analysis = RhythmAnalysis()
    private var tracker = BeatTracker(30.0)
    private var lastRms = 0f

    private val windowScratch = FloatArray(RHYTHM_FFT_SIZE)
    private val chroma = DoubleArray(12)
    private var chromaHops = 0
    private val fft = RealFft(RHYTHM_FFT_SIZE)
    private val fftReal = FloatArray(RHYTHM_FFT_SIZE)
    private val fftImag = FloatArray(RHYTHM_FFT_SIZE)
    private val hann = FloatArray(RHYTHM_FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (RHYTHM_FFT_SIZE - 1))).toFloat()
    }

    private val levels = ArrayList<Float>(4096)
    private var firstActiveMs = -1L
    private var lastActiveMs = 0L

    fun configure(sampleRate: Int, channels: Int) {
        if (sampleRate == rate && channels == channelCount) return
        rate = sampleRate
        channelCount = channels.coerceAtLeast(1)
        hop = max(1, rate / 30)
        analysis = RhythmAnalysis()
        tracker = BeatTracker(30.0)
        ringPos = 0
        ringCount = 0
        sinceHop = 0
    }

    fun push(buffer: ByteBuffer, encoding: Int) {
        val input = buffer.order(ByteOrder.nativeOrder())
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> while (input.remaining() >= 4 * channelCount) {
                var mixed = 0f
                repeat(channelCount) { mixed += input.float }
                pushSample(mixed / channelCount)
            }
            AudioFormat.ENCODING_PCM_8BIT -> while (input.remaining() >= channelCount) {
                var mixed = 0f
                repeat(channelCount) { mixed += ((input.get().toInt() and 0xFF) - 128) / 128f }
                pushSample(mixed / channelCount)
            }
            else -> while (input.remaining() >= 2 * channelCount) {
                var mixed = 0f
                repeat(channelCount) { mixed += input.short / 32768f }
                pushSample(mixed / channelCount)
            }
        }
    }

    private fun pushSample(sample: Float) {
        val value = sample.coerceIn(-1f, 1f)
        loudnessSquares += value.toDouble() * value
        sampleTotal++
        sampleIndex++
        ring[ringPos] = value
        ringPos = (ringPos + 1) % RHYTHM_FFT_SIZE
        if (ringCount < RHYTHM_FFT_SIZE) ringCount++
        sinceHop++
        if (ringCount < RHYTHM_FFT_SIZE || sinceHop < hop) return
        sinceHop -= hop
        analyseWindow()
    }

    private fun analyseWindow() {
        for (i in 0 until RHYTHM_FFT_SIZE) {
            windowScratch[i] = ring[(ringPos + i) % RHYTHM_FFT_SIZE]
        }
        var squares = 0f
        for (sample in windowScratch) squares += sample * sample
        lastRms = sqrt(squares / RHYTHM_FFT_SIZE)
        val endS = sampleIndex.toDouble() / rate
        val frame = analysis.analyzePcm(windowScratch, rate, 1000f / 30f)
        tracker.push(endS, frame.fluxBass, frame.fluxMid)
        levels += rhythmLoudness(lastRms)
        if (lastRms >= ACTIVE_RMS) {
            val endMs = (endS * 1000).toLong()
            if (firstActiveMs < 0) firstActiveMs = (endMs - RHYTHM_FFT_SIZE * 1000L / rate).coerceAtLeast(0)
            lastActiveMs = endMs
        }
        if (chromaHops++ % 3 == 0) accumulateChroma()
    }

    private fun accumulateChroma() {
        for (i in 0 until RHYTHM_FFT_SIZE) {
            fftReal[i] = windowScratch[i] * hann[i]
            fftImag[i] = 0f
        }
        fft.transform(fftReal, fftImag)
        val binHz = rate.toDouble() / RHYTHM_FFT_SIZE
        val from = ceil(CHROMA_MIN_HZ / binHz).toInt()
        val to = floor(CHROMA_MAX_HZ / binHz).toInt().coerceAtMost(RHYTHM_FFT_SIZE / 2 - 1)
        for (bin in from..to) {
            val frequency = bin * binHz
            if (frequency <= 0.0) continue
            val magnitude = kotlin.math.hypot(fftReal[bin].toDouble(), fftImag[bin].toDouble())
            if (magnitude <= 0.0) continue
            val midi = 69.0 + 12.0 * kotlin.math.ln(frequency / 440.0) / kotlin.math.ln(2.0)
            val pitchClass = Math.floorMod(midi.roundToInt(), 12)
            chroma[pitchClass] += magnitude * magnitude
        }
    }

    fun finish(): OfflineFeatures = this

    val durationMs: Long get() = sampleTotal * 1000L / rate

    fun toAnalysis(songKey: String, now: Long): AutomixAnalysis {
        val grid = tracker.grid()
        val (keyIndex, minor, keyConfidence) = estimateKey(chroma)
        return AutomixAnalysis(
            songKey = songKey,
            durationMs = durationMs,
            beatGrid = grid,
            loudnessDb = if (sampleTotal > 0) (10.0 * log10((loudnessSquares / sampleTotal).coerceAtLeast(1e-12))).toFloat() else -120f,
            keyIndex = keyIndex,
            keyMinor = minor,
            keyConfidence = keyConfidence,
            introMs = if (firstActiveMs >= 0) firstActiveMs else 0L,
            outroMs = if (lastActiveMs > 0) lastActiveMs else durationMs,
            sections = segment(grid),
            updatedAt = now,
        )
    }

    /** 有可信拍网格就按 8 小节的段落取平均能量，没有就按 30 秒切。 */
    private fun segment(grid: BeatGrid): List<AutomixSection> {
        if (levels.isEmpty()) return emptyList()
        val hopS = hop.toDouble() / rate
        val blocks = ArrayList<AutomixSection>()
        if (grid.periodS > 0.0 && grid.confidence >= 0.4f) {
            val blockS = grid.periodS * 32
            var start = if (grid.t0S.isFinite()) max(0.0, grid.t0S) else 0.0
            while (start < durationMs / 1000.0 - 1.0 && blocks.size < MAX_SECTIONS) {
                blocks += AutomixSection((start * 1000).roundToInt().toLong(), blockEnergy(start, blockS, hopS))
                start += blockS
            }
        } else {
            val blockS = 30.0
            var start = 0.0
            while (start < durationMs / 1000.0 - 1.0 && blocks.size < MAX_SECTIONS) {
                blocks += AutomixSection((start * 1000).roundToInt().toLong(), blockEnergy(start, blockS, hopS))
                start += blockS
            }
        }
        val peak = blocks.maxOfOrNull { it.energy } ?: 0f
        if (peak <= 0.001f) return emptyList()
        return blocks.map { it.copy(energy = (it.energy / peak).coerceIn(0f, 1f)) }
    }

    private fun blockEnergy(startS: Double, blockS: Double, hopS: Double): Float {
        val from = (startS / hopS).toInt().coerceIn(0, levels.lastIndex)
        val to = ((startS + blockS) / hopS).toInt().coerceIn(from, levels.lastIndex)
        var sum = 0f
        for (i in from..to) sum += levels[i]
        return sum / (to - from + 1)
    }

    private fun estimateKey(chroma: DoubleArray): Triple<Int, Boolean, Float> = estimateKeyFromChroma(chroma)

    private companion object {
        /** 起奏检测用的有效声音门槛：约 -44 dBFS。 */
        const val ACTIVE_RMS = 0.006f
        const val CHROMA_MIN_HZ = 55.0
        const val CHROMA_MAX_HZ = 2_000.0
        const val MAX_SECTIONS = 64
    }
}

/** Krumhansl-Kessler 调性轮廓。 */
private val MAJOR_PROFILE = doubleArrayOf(
    6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88,
)
private val MINOR_PROFILE = doubleArrayOf(
    6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17,
)

/** 12 个半音的色度能量 → 最可能的调（根音、是否小调、置信度 0..1）。 */
internal fun estimateKeyFromChroma(chroma: DoubleArray): Triple<Int, Boolean, Float> {
    val total = chroma.sum()
    if (total <= 1e-9) return Triple(-1, false, 0f)
    val normalized = DoubleArray(12) { chroma[it] / total }
    var bestMajor = -1
    var bestMajorScore = Double.NEGATIVE_INFINITY
    var bestMinor = -1
    var bestMinorScore = Double.NEGATIVE_INFINITY
    for (root in 0 until 12) {
        val major = keyCorrelation(normalized, root, MAJOR_PROFILE)
        val minor = keyCorrelation(normalized, root, MINOR_PROFILE)
        if (major > bestMajorScore) { bestMajorScore = major; bestMajor = root }
        if (minor > bestMinorScore) { bestMinorScore = minor; bestMinor = root }
    }
    val minor = bestMinorScore > bestMajorScore
    val best = if (minor) bestMinorScore else bestMajorScore
    val second = if (minor) bestMajorScore else bestMinorScore
    val confidence = ((best - second) / max(0.05, abs(best))).coerceIn(0.0, 1.0).toFloat()
    return Triple(if (minor) bestMinor else bestMajor, minor, confidence)
}

private fun keyCorrelation(chroma: DoubleArray, root: Int, profile: DoubleArray): Double {
    var meanChroma = 0.0
    var meanProfile = 0.0
    for (i in 0 until 12) {
        meanChroma += chroma[i]
        meanProfile += profile[i]
    }
    meanChroma /= 12
    meanProfile /= 12
    var numerator = 0.0
    var chromaVar = 0.0
    var profileVar = 0.0
    for (i in 0 until 12) {
        val c = chroma[(root + i) % 12] - meanChroma
        val p = profile[i] - meanProfile
        numerator += c * p
        chromaVar += c * c
        profileVar += p * p
    }
    val denominator = sqrt(chromaVar * profileVar)
    return if (denominator > 1e-9) numerator / denominator else 0.0
}

private val KEY_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

internal fun keyLabel(index: Int, minor: Boolean): String {
    if (index !in KEY_NAMES.indices) return "未知调"
    return KEY_NAMES[index] + if (minor) " 小调" else " 大调"
}
