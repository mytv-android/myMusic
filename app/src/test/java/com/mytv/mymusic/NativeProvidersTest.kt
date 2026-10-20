package com.mytv.mymusic

import com.mytv.mymusic.data.native.NativeProviders
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeProvidersTest {
    @Test
    fun allNativeProvidersAreRegistered() {
        val expected = setOf(
            "netease",
            "qq",
            "kugou",
            "kuwo",
            "migu",
            "qianqian",
            "soda",
            "fivesing",
            "jamendo",
            "joox",
            "bilibili",
            "apple",
        )
        val providers = NativeProviders.all()
        assertEquals(expected, providers.map { it.info.id }.toSet())
        assertEquals(expected.size, providers.size)
    }
}
