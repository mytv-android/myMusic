package com.mytv.mymusic

import com.mytv.mymusic.data.native.kugouMembershipLabel
import com.mytv.mymusic.data.native.qqMembershipLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountMembershipTest {

    @Test
    fun `qq super vip keeps its level`() {
        val data = json("""{"svip":1,"identity":{"vip":1,"level":8}}""")
        assertEquals("超级会员 V8", qqMembershipLabel(data))
    }

    @Test
    fun `qq green diamond falls back when level is missing`() {
        assertEquals("豪华绿钻 V7", qqMembershipLabel(json("""{"identity":{"vip":1,"level":7}}""")))
        assertEquals("豪华绿钻", qqMembershipLabel(json("""{"identity":{"ExpVip":1}}""")))
    }

    @Test
    fun `qq account without vip has no label`() {
        assertNull(qqMembershipLabel(json("""{"identity":{}}""")))
        assertNull(qqMembershipLabel(null))
    }

    @Test
    fun `kugou membership products map to labels`() {
        assertEquals("超级VIP", kugouMembershipLabel(0, 16, emptyMap()))
        assertEquals("豪华VIP", kugouMembershipLabel(6, 0, emptyMap()))
        assertEquals("概念VIP", kugouMembershipLabel(0, 0, mapOf("svip" to 1)))
        assertEquals("畅听VIP", kugouMembershipLabel(0, 0, mapOf("tvip" to 1)))
        assertEquals("VIP", kugouMembershipLabel(1, 0, emptyMap()))
        assertNull(kugouMembershipLabel(0, 0, emptyMap()))
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
