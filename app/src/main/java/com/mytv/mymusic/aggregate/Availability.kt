package com.mytv.mymusic.aggregate

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.native.SodaProvider

fun isPreviewSource(providerId: String, song: Song): Boolean =
    providerId == "apple" ||
        (providerId == "soda" && song.isVip && !SodaProvider.isSignerConfigured())

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
