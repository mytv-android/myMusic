package com.mytv.mymusic

import com.mytv.mymusic.data.native.neteaseLevelCandidates
import com.mytv.mymusic.data.native.neteaseVipLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseQualityTest {

    @Test
    fun `unknown level starts from master and floors at exhigh`() {
        assertEquals(listOf("jymaster", "sky", "exhigh"), neteaseLevelCandidates(""))
        assertEquals(listOf("jymaster", "sky", "exhigh"), neteaseLevelCandidates("whatever"))
    }

    @Test
    fun `hires song falls one step then floors at exhigh`() {
        assertEquals(listOf("hires", "lossless", "exhigh"), neteaseLevelCandidates("hires"))
    }

    @Test
    fun `master song tries master then sky`() {
        assertEquals(listOf("jymaster", "sky", "exhigh"), neteaseLevelCandidates("jymaster"))
    }

    @Test
    fun `lossless song asks lossless before exhigh`() {
        assertEquals(listOf("lossless", "exhigh"), neteaseLevelCandidates("lossless"))
    }

    @Test
    fun `exhigh song only asks for exhigh`() {
        assertEquals(listOf("exhigh"), neteaseLevelCandidates("exhigh"))
    }

    @Test
    fun `level matching is case insensitive`() {
        assertEquals(neteaseLevelCandidates("hires"), neteaseLevelCandidates("HIRES"))
    }

    @Test
    fun `vip labels map to netease vip types`() {
        assertEquals("黑胶SVIP", neteaseVipLabel(12))
        assertEquals("黑胶VIP", neteaseVipLabel(11))
        assertEquals("会员", neteaseVipLabel(10))
        assertNull(neteaseVipLabel(0))
    }
}
