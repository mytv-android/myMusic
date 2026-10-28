package com.mytv.mymusic

import com.mytv.mymusic.data.native.neteaseLevelCandidates
import com.mytv.mymusic.data.native.neteaseMembershipLabel
import com.mytv.mymusic.data.native.neteaseVipLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseQualityTest {

    private val allLevels = listOf("jymaster", "sky", "jyeffect", "hires", "lossless", "exhigh")

    @Test
    fun `unknown level descends through every level`() {
        assertEquals(allLevels, neteaseLevelCandidates(""))
        assertEquals(allLevels, neteaseLevelCandidates("whatever"))
    }

    @Test
    fun `hires song falls through lossless before exhigh`() {
        assertEquals(listOf("hires", "lossless", "exhigh"), neteaseLevelCandidates("hires"))
    }

    @Test
    fun `master song keeps the full chain`() {
        assertEquals(allLevels, neteaseLevelCandidates("jymaster"))
    }

    @Test
    fun `vip-only master song still reaches lossless`() {
        assertEquals(
            listOf("jyeffect", "hires", "lossless", "exhigh"),
            neteaseLevelCandidates("jyeffect"),
        )
    }

    @Test
    fun `lossless song asks lossless before exhigh`() {
        assertEquals(listOf("lossless", "exhigh"), neteaseLevelCandidates("lossless"))
    }

    @Test
    fun `exhigh and standard songs only ask for exhigh`() {
        assertEquals(listOf("exhigh"), neteaseLevelCandidates("exhigh"))
        assertEquals(listOf("exhigh"), neteaseLevelCandidates("standard"))
        assertEquals(listOf("exhigh"), neteaseLevelCandidates("higher"))
    }

    @Test
    fun `level matching is case insensitive`() {
        assertEquals(neteaseLevelCandidates("hires"), neteaseLevelCandidates("HIRES"))
    }

    @Test
    fun `vip labels use decimal vipType flags`() {
        assertEquals("黑胶VIP", neteaseVipLabel(10))
        assertEquals("黑胶VIP", neteaseVipLabel(11))
        assertEquals("黑胶SVIP", neteaseVipLabel(110))
        assertEquals("会员", neteaseVipLabel(1))
        assertNull(neteaseVipLabel(0))
    }

    @Test
    fun `membership label reads product and level from vip info`() {
        val now = 1_000_000L
        val svip = json(
            """
            {"redVipLevel":7,"redplus":{"vipCode":300,"vipLevel":7,"expireTime":2000000},
             "associator":{"vipCode":100,"vipLevel":6,"expireTime":2000000}}
            """,
        )
        assertEquals("黑胶SVIP V7", neteaseMembershipLabel(svip, now))

        val vip = json(
            """
            {"redVipLevel":5,"redplus":null,
             "associator":{"vipCode":100,"vipLevel":5,"expireTime":2000000}}
            """,
        )
        assertEquals("黑胶VIP V5", neteaseMembershipLabel(vip, now))

        val musicPackage = json(
            """
            {"redVipLevel":0,"musicPackage":{"vipCode":220,"vipLevel":3,"expireTime":2000000}}
            """,
        )
        assertEquals("音乐包 V3", neteaseMembershipLabel(musicPackage, now))

        val expired = json(
            """
            {"redVipLevel":3,"associator":{"vipCode":100,"vipLevel":3,"expireTime":999}}
            """,
        )
        assertNull(neteaseMembershipLabel(expired, now))
        assertNull(neteaseMembershipLabel(null, now))
    }

    private fun json(text: String) = Json.parseToJsonElement(text.trimIndent()).jsonObject
}
