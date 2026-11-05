package com.mytv.mymusic

import com.mytv.mymusic.data.CachedAccountProfile
import com.mytv.mymusic.data.native.AccountInfo
import com.mytv.mymusic.ui.accountProfileOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountProfileCacheTest {

    @Test
    fun `fresh cache is not stale`() {
        val now = 1_000_000_000_000L
        val cached = CachedAccountProfile(
            providerId = "qq",
            nickname = "听歌的人",
            avatar = "https://q.qlogo.cn/1",
            detail = "豪华绿钻 V7",
            savedAt = now - 60_000L,
        )
        assertFalse(cached.isStale(now))
    }

    @Test
    fun `cache older than a day is stale`() {
        val now = 1_000_000_000_000L
        val cached = CachedAccountProfile(providerId = "qq", savedAt = now - CachedAccountProfile.STALE_MS - 1)
        assertTrue(cached.isStale(now))
    }

    @Test
    fun `cache without a timestamp counts as stale`() {
        // 没存到时间戳（旧版本写下的数据）宁可多拉一次，也不要永远不刷新
        assertTrue(CachedAccountProfile(providerId = "kugou").isStale())
    }

    @Test
    fun `profile without a nickname still keeps its membership`() {
        // Apple Music 只给订阅状态，昵称和头像是空的；退回平台名，会员等级照样显示
        val profile = accountProfileOf(
            "apple",
            "Apple Music",
            AccountInfo(nickname = "", avatar = "", detail = "Apple Music 会员"),
        )
        assertEquals("Apple Music", profile.nickname)
        assertEquals("", profile.avatar)
        assertEquals("Apple Music 会员", profile.detail)
    }

    @Test
    fun `full profile keeps nickname avatar and membership`() {
        val profile = accountProfileOf(
            "qq",
            "QQ 音乐",
            AccountInfo(nickname = "  听歌的人  ", avatar = "https://q.qlogo.cn/1", detail = "超级会员 V8"),
        )
        assertEquals("听歌的人", profile.nickname)
        assertEquals("https://q.qlogo.cn/1", profile.avatar)
        assertEquals("超级会员 V8", profile.detail)
    }
}
