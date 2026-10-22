package com.mytv.mymusic.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import com.mytv.mymusic.data.EqBandSetting
import com.mytv.mymusic.data.EqualizerSettings
import com.mytv.mymusic.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.log10

enum class EqualizerAvailability { NoSession, Available, Unsupported }

data class EqualizerBand(
    val band: Int,
    val freqHz: Int,
    val levelDb: Float,
    val minDb: Float,
    val maxDb: Float,
)

data class EqualizerUiState(
    val availability: EqualizerAvailability = EqualizerAvailability.NoSession,
    val enabled: Boolean = false,
    val presetId: String = "flat",
    val bands: List<EqualizerBand> = emptyList(),
    val outputLabel: String = "扬声器",
    val effective: Boolean = false,
    val error: String = "",
)

data class EqPreset(
    val id: String,
    val label: String,
    val curve: List<Pair<Int, Float>> = emptyList(),
)

object EqPresets {
    val flat = EqPreset("flat", "平直")
    val pop = EqPreset(
        "pop", "流行",
        listOf(60 to 4f, 230 to 2f, 910 to 0f, 3600 to 2f, 14000 to 3f),
    )
    val rock = EqPreset(
        "rock", "摇滚",
        listOf(60 to 5f, 230 to 2f, 910 to -1f, 3600 to 3f, 14000 to 4f),
    )
    val jazz = EqPreset(
        "jazz", "爵士",
        listOf(60 to 3f, 230 to 1f, 910 to 0f, 3600 to 2f, 14000 to 3f),
    )
    val vocal = EqPreset(
        "vocal", "人声",
        listOf(60 to -2f, 230 to -1f, 910 to 3f, 3600 to 2f, 14000 to 0f),
    )
    val all = listOf(flat, pop, rock, jazz, vocal)

    fun byId(id: String): EqPreset? = all.firstOrNull { it.id == id }
}

class EqualizerController(
    private val context: Context,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(EqualizerUiState())
    val state: StateFlow<EqualizerUiState> = _state.asStateFlow()

    private var effect: Equalizer? = null
    private var attachedSession = 0
    private var current = EqualizerSettings()
    private var persistJob: kotlinx.coroutines.Job? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = updateOutput()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = updateOutput()
    }

    init {
        runCatching {
            audioManager?.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        }
        updateOutput()
        _state.update { it.copy(bands = previewBands(current)) }
        scope.launch {
            settings.state.collect { value ->
                val changed = value.equalizer != current
                current = value.equalizer
                if (changed) {
                    effect?.let { eq ->
                        runCatching { eq.enabled = current.enabled }
                        applyCurve(eq)
                    }
                }
                publish()
            }
        }
    }

    @Synchronized
    fun attach(sessionId: Int, force: Boolean = false) {
        if (sessionId <= 0 || (!force && sessionId == attachedSession)) return
        releaseEffect()
        attachedSession = sessionId
        try {
            val eq = Equalizer(0, sessionId)
            effect = eq
            eq.enabled = current.enabled
            applyCurve(eq)
            _state.update {
                it.copy(
                    availability = EqualizerAvailability.Available,
                    error = "",
                    effective = current.enabled,
                )
            }
            publish()
        } catch (error: UnsupportedOperationException) {
            unsupported("当前设备不支持均衡器")
        } catch (error: IllegalStateException) {
            unsupported("当前输出设备未开放音效控制")
        } catch (error: Exception) {
            unsupported(error.message ?: "均衡器初始化失败")
        }
    }

    @Synchronized
    fun detach() {
        releaseEffect()
        attachedSession = 0
        _state.update {
            it.copy(
                availability = EqualizerAvailability.NoSession,
                effective = false,
                bands = previewBands(current),
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        current = current.copy(enabled = enabled)
        effect?.let { eq -> runCatching { eq.enabled = enabled } }
        persist()
        publish()
    }

    fun selectPreset(presetId: String) {
        if (presetId == "custom" && current.customBands.isEmpty()) {
            saveAsCustom()
            return
        }
        current = current.copy(presetId = presetId)
        effect?.let(::applyCurve)
        persist()
        publish()
    }

    fun setBandLevel(bandIndex: Int, levelDb: Float) {
        val eq = effect
        val step = 0.5f
        val value = (levelDb / step).toInt() * step
        if (eq == null) {
            val preview = previewBands(current)
            val band = preview.getOrNull(bandIndex) ?: return
            val clamped = value.coerceIn(band.minDb, band.maxDb)
            val updated = preview.map { if (it.band == bandIndex) it.copy(levelDb = clamped) else it }
            current = current.copy(
                presetId = "custom",
                customBands = updated.map { EqBandSetting(it.freqHz, it.levelDb) },
            )
            persist(debounce = true)
            publish(updated)
            return
        }
        val range = runCatching { eq.bandLevelRange }.getOrNull() ?: return
        val minDb = range[0] / 100f
        val maxDb = range[1] / 100f
        val clamped = value.coerceIn(minDb, maxDb)
        runCatching { eq.setBandLevel(bandIndex.toShort(), (clamped * 100).toInt().toShort()) }
        val bands = readBands(eq)
        current = current.copy(
            presetId = "custom",
            customBands = bands.map { EqBandSetting(it.freqHz, it.levelDb) },
        )
        persist(debounce = true)
        publish(bands)
    }

    fun resetFlat() {
        current = current.copy(presetId = "flat", customBands = emptyList())
        effect?.let(::applyCurve)
        persist()
        publish()
    }

    fun saveAsCustom() {
        val eq = effect
        val bands = if (eq != null) readBands(eq) else previewBands(current)
        current = current.copy(
            presetId = "custom",
            customBands = bands.map { EqBandSetting(it.freqHz, it.levelDb) },
        )
        persist()
        publish()
    }

    private fun persist(debounce: Boolean = false) {
        persistJob?.cancel()
        persistJob = scope.launch {
            if (debounce) kotlinx.coroutines.delay(300)
            settings.saveEqualizer(current)
        }
    }

    private fun releaseEffect() {
        runCatching { effect?.release() }
        effect = null
    }

    private fun unsupported(message: String) {
        releaseEffect()
        _state.update {
            it.copy(
                availability = EqualizerAvailability.Unsupported,
                effective = false,
                error = message,
                bands = previewBands(current),
                enabled = current.enabled,
                presetId = current.presetId,
            )
        }
    }

    private fun publish(bands: List<EqualizerBand>? = null) {
        val eq = effect
        val actual = bands ?: eq?.let(::readBands)
        _state.update {
            it.copy(
                enabled = current.enabled,
                presetId = current.presetId,
                bands = actual ?: previewBands(current),
                effective = current.enabled && eq != null,
                outputLabel = currentOutputLabel,
                availability = when {
                    eq != null -> EqualizerAvailability.Available
                    it.availability == EqualizerAvailability.Unsupported -> EqualizerAvailability.Unsupported
                    else -> EqualizerAvailability.NoSession
                },
                error = if (eq == null && it.availability == EqualizerAvailability.Unsupported) it.error else "",
            )
        }
    }

    private fun readBands(eq: Equalizer): List<EqualizerBand> {
        val count = runCatching { eq.numberOfBands.toInt() }.getOrDefault(0)
        val range = runCatching { eq.bandLevelRange }.getOrDefault(shortArrayOf(-1500, 1500))
        return (0 until count).mapNotNull { index ->
            runCatching {
                EqualizerBand(
                    band = index,
                    freqHz = eq.getCenterFreq(index.toShort()) / 1000,
                    levelDb = eq.getBandLevel(index.toShort()) / 100f,
                    minDb = range[0] / 100f,
                    maxDb = range[1] / 100f,
                )
            }.getOrNull()
        }
    }

    private fun applyCurve(eq: Equalizer) {
        val range = runCatching { eq.bandLevelRange }.getOrDefault(shortArrayOf(-1500, 1500))
        val minDb = range[0] / 100f
        val maxDb = range[1] / 100f
        val count = runCatching { eq.numberOfBands.toInt() }.getOrDefault(0)
        val curve = curvePoints(current)
        repeat(count) { index ->
            runCatching {
                val freq = eq.getCenterFreq(index.toShort()) / 1000
                val target = interpolate(curve, freq).coerceIn(minDb, maxDb)
                eq.setBandLevel(index.toShort(), (target * 100).toInt().toShort())
            }
        }
        runCatching { eq.enabled = current.enabled }
    }

    private fun curvePoints(value: EqualizerSettings): List<Pair<Int, Float>> =
        if (value.presetId == "custom" && value.customBands.isNotEmpty()) {
            value.customBands.sortedBy { it.freqHz }.map { it.freqHz to it.gainDb }
        } else {
            EqPresets.byId(value.presetId)?.curve?.takeIf { it.isNotEmpty() }
                ?: listOf(60 to 0f, 230 to 0f, 910 to 0f, 3600 to 0f, 14000 to 0f)
        }

    private fun previewBands(value: EqualizerSettings): List<EqualizerBand> {
        val curve = curvePoints(value)
        return STANDARD_FREQS.mapIndexed { index, freq ->
            EqualizerBand(
                band = index,
                freqHz = freq,
                levelDb = interpolate(curve, freq),
                minDb = -10f,
                maxDb = 10f,
            )
        }
    }

    private fun interpolate(points: List<Pair<Int, Float>>, freq: Int): Float {
        if (points.isEmpty()) return 0f
        if (freq <= points.first().first) return points.first().second
        if (freq >= points.last().first) return points.last().second
        val sorted = points.sortedBy { it.first }
        for (i in 0 until sorted.lastIndex) {
            val (f1, v1) = sorted[i]
            val (f2, v2) = sorted[i + 1]
            if (freq in f1..f2) {
                val ratio = if (f2 == f1) 0f else {
                    (log10(freq.toDouble()) - log10(f1.toDouble())) /
                        (log10(f2.toDouble()) - log10(f1.toDouble()))
                }.toFloat()
                return v1 + (v2 - v1) * ratio
            }
        }
        return 0f
    }

    private val currentOutputLabel: String
        get() {
            val device = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull { it.isSink } ?: return "扬声器"
            return when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> if (device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) "蓝牙音箱" else "蓝牙耳机"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音频"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
                else -> device.productName?.toString().orEmpty().ifBlank { "当前输出设备" }
            }
        }

    private fun updateOutput() {
        _state.update { it.copy(outputLabel = currentOutputLabel) }
        val session = attachedSession
        if (session > 0) {
            attach(session, force = true)
        }
    }

    private companion object {
        val STANDARD_FREQS = listOf(60, 230, 910, 3600, 14000)
    }
}
