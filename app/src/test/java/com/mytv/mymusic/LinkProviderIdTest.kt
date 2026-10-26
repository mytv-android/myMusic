package com.mytv.mymusic

import com.mytv.mymusic.ui.linkProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkProviderIdTest {
    @Test
    fun qqShareLinkMapsToQq() {
        // 用户反馈里的形态：i2.y.qq.com 的歌单分享页
        assertEquals("qq", linkProviderId("https://i2.y.qq.com/n3/other/pages/d/taoge/index.html?id=8279242283"))
        assertEquals("qq", linkProviderId("https://c6.y.qq.com/base/fcgi-bin/u?__=abc123"))
        assertEquals("qq", linkProviderId("HTTPS://Y.QQ.COM/n/ryqq/playlist/8279242283"))
    }

    @Test
    fun neteaseLinksMapToNetease() {
        assertEquals("netease", linkProviderId("https://music.163.com/#/playlist?id=7544356473"))
        assertEquals("netease", linkProviderId("https://163cn.tv/AbCdEf"))
    }

    @Test
    fun otherPlatformLinksMapToTheirProviders() {
        assertEquals("kugou", linkProviderId("https://h5.kugou.com/playlist.html?id=123"))
        assertEquals("kuwo", linkProviderId("https://mobi.kuwo.cn/xxx"))
        assertEquals("migu", linkProviderId("https://m.music.migu.cn/v3/music/playlist/123"))
        assertEquals("bilibili", linkProviderId("https://b23.tv/AbCdEf"))
        assertEquals("apple", linkProviderId("https://music.apple.com/cn/playlist/pl.u-xxx"))
        assertEquals("joox", linkProviderId("https://www.joox.com/playlist/xxx"))
        assertEquals("qianqian", linkProviderId("https://music.91q.com/songlist/123"))
        assertEquals("soda", linkProviderId("https://www.qishui.com/playlist/123"))
    }

    @Test
    fun fiveSingWinsOverKugouDomain() {
        assertEquals("fivesing", linkProviderId("http://5sing.kugou.com/yc/3235389.html"))
    }

    @Test
    fun unknownHostReturnsBlank() {
        assertEquals("", linkProviderId("https://example.com/song/1"))
        assertEquals("", linkProviderId("随便一段文字，没有链接"))
    }
}
