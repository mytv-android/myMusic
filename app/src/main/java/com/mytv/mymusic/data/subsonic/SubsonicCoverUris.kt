package com.mytv.mymusic.data.subsonic

import java.net.URI
import java.net.URLEncoder

object SubsonicCoverUris {
    const val AUTHORITY = "com.mytv.mymusic.subsonic-covers"

    fun create(providerId: String, coverArt: String, size: Int = 300): String {
        if (!providerId.startsWith(SUBSONIC_PROVIDER_PREFIX) || coverArt.isBlank()) return ""
        val account = providerId.removePrefix(SUBSONIC_PROVIDER_PREFIX)
        return "content://$AUTHORITY/${path(account)}/${path(coverArt)}?size=$size"
    }

    fun isPortable(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme == "content" && uri.host == AUTHORITY && uri.rawPath.split('/').count { it.isNotEmpty() } == 2
    }

    private fun path(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
