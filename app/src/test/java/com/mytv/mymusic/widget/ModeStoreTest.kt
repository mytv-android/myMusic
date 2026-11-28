package com.mytv.mymusic.widget

import com.mytv.mymusic.data.ListeningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 小组件「自动」挑哪个模式。
 *
 * 回归的是：心动模式排在网易云列表第一位，但账号没收藏过歌时一首都出不来，
 * 「自动」不能拿它当默认，否则没收藏过的用户点一次组件报一次错。
 */
class ModeStoreTest {
    @Test
    fun autoDefaultSkipsModesThatNeedSavedSongs() {
        val modes = listOf(
            ListeningMode(id = "heartbeat", name = "心动模式", source = "netease", extra = mapOf("need_saved_songs" to "true")),
            ListeningMode(id = "fm:DEFAULT", name = "默认模式", source = "netease"),
        )
        assertEquals("fm:DEFAULT", ModeStore.autoDefault(modes)?.id)
    }

    @Test
    fun autoDefaultTakesFirstModeWhenNoneNeedSavedSongs() {
        val modes = listOf(
            ListeningMode(id = "fm:DEFAULT", name = "默认模式", source = "netease"),
            ListeningMode(id = "fm:FAMILIAR", name = "熟悉模式", source = "netease"),
        )
        assertEquals("fm:DEFAULT", ModeStore.autoDefault(modes)?.id)
    }

    @Test
    fun autoDefaultFallsBackToFirstWhenEveryModeNeedsSavedSongs() {
        // 全都要求有歌时退回第一个：不能因为挑不到「安全」的就把整个平台跳过
        val modes = listOf(
            ListeningMode(id = "a", source = "x", extra = mapOf("need_saved_songs" to "true")),
            ListeningMode(id = "b", source = "x", extra = mapOf("need_saved_songs" to "true")),
        )
        assertEquals("a", ModeStore.autoDefault(modes)?.id)
    }

    @Test
    fun autoDefaultHandlesEmptyList() {
        assertNull(ModeStore.autoDefault(emptyList()))
    }
}
