package com.mytv.mymusic.playback

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.MimeTypes

/**
 * 24 位 FLAC 只有 AOSP 软件解码器能解：老设备的厂商解码器（如 OMX.qti.audio.decoder.flac）
 * 只吃 16 位，表现为卡在 0 秒或直接解码失败，Android 6/8 上就是整首放不出来。
 * 播放器对 FLAC 优先选软件解码器；没有软件解码器的设备由取流侧退回 mp3。
 */
internal object FlacDecoderSupport {
    /** 能解 FLAC 的 AOSP 软件解码器名；这台设备没有就是 null。 */
    val softwareDecoderName: String? by lazy { findSoftwareDecoder() }

    val hasSoftwareDecoder: Boolean get() = softwareDecoderName != null

    private fun findSoftwareDecoder(): String? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(MimeTypes.AUDIO_FLAC, ignoreCase = true) } &&
                isSoftwareDecoder(info)
        }?.name
    }.getOrNull()

    private fun isSoftwareDecoder(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isSoftwareOnly
        val name = info.name.lowercase()
        return name.startsWith("omx.google.") ||
            name.startsWith("omx.android.") ||
            name.startsWith("c2.android.") ||
            name.startsWith("c2.google.")
    }
}
