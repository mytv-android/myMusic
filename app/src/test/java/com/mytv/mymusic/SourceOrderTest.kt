package com.mytv.mymusic

import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.ui.settings.orderedEnabledSources
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceOrderTest {
    private val providers = listOf(
        ProviderInfo(id = "netease", name = "网易云音乐", search = true),
        ProviderInfo(id = "qq", name = "QQ 音乐", search = true),
        ProviderInfo(id = "kugou", name = "酷狗音乐", search = true),
    )

    @Test
    fun enabledSourcesFollowSavedOrderInsteadOfRegistrationOrder() {
        val ordered = orderedEnabledSources(providers, listOf("kugou", "netease", "qq"))

        assertEquals(listOf("kugou", "netease", "qq"), ordered.map { it.id })
    }

    @Test
    fun enabledSourcesIgnoreUnavailableAndDuplicateIds() {
        val ordered = orderedEnabledSources(providers, listOf("qq", "missing", "qq", "netease"))

        assertEquals(listOf("qq", "netease"), ordered.map { it.id })
    }
}
