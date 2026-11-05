package com.mytv.mymusic.aggregate

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.native.SodaProvider

/**
 * 取流之后才知道只是片段的源。Apple 曾经无条件算在内——那时整曲只有 FairPlay，安卓上
 * 无解；现在改走设备自带的 Widevine CDM（见 `AppleWidevineDrmCallback`），能不能放整曲
 * 由 `AppleProvider.playableStream` 实际取流后回答：拿到整曲就 `preview = false`，只退回
 * 片段才 `preview = true`，交给 `SourceResolver` 去换源。
 */
fun isPreviewSource(providerId: String, song: Song): Boolean =
    providerId == "soda" && song.isVip && !SodaProvider.isSignerConfigured()

fun availabilityOf(source: TrackSource, cookie: String): Availability {
    val song = source.song
    if (song.isInvalid) return Availability.Unavailable
    if (source.availability == Availability.Playable || source.availability == Availability.Unavailable) {
        return source.availability
    }
    if (source.isPreview || isPreviewSource(source.providerId, song)) return Availability.PreviewOnly
    if (song.isVip && cookie.isBlank()) return Availability.VipOnly
    if (song.url.isNotBlank()) return Availability.Playable
    return source.availability
}
