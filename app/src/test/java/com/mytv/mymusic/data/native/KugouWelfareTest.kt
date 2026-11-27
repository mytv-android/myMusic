package com.mytv.mymusic.data.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酷狗概念版「听歌领会员」的返回解析。
 *
 * 这里只测解析：签名和公共参数由 [KugouProvider.kugouYouthRequest] 构造，
 * 和这个 App 已经在用的 get_union_vip / 云盘写入 / 账号资料同一套算法和盐值，
 * 那些接口线上是通的，所以没有为它单独造向量；真要联网验证得用真实账号，
 * 单测里跑不了（见 KugouWelfare 的 KDoc）。
 */
class KugouWelfareTest {

    @Test
    fun `claim success parses as claimed`() {
        val result = KugouWelfare.parseReward("""{"status":1,"message":"领取成功","data":"ok"}""")
        assertTrue(result.success)
        assertFalse(result.already)
        assertEquals(0, result.errorCode)
        assertEquals("领取成功", result.message)
    }

    @Test
    fun `already claimed today is treated as done`() {
        val result = KugouWelfare.parseReward(
            """{"status":0,"error_code":131001,"error_msg":"今日已领取"}""",
        )
        assertFalse(result.success)
        assertTrue(result.already)
        assertEquals(KugouWelfare.ERROR_ALREADY, result.errorCode)
        // 服务端的 error_msg 可能是「今日已领取」，界面上统一说「今天已经领过了」
        assertEquals("今天已经领过了", result.message)
    }

    @Test
    fun `risk controlled and expired login get their own copy`() {
        val risk = KugouWelfare.parseReward("""{"status":0,"error_code":20028}""")
        assertFalse(risk.success)
        assertFalse(risk.already)
        assertEquals(KugouWelfare.ERROR_RISK, risk.errorCode)
        assertTrue(risk.message.contains("风控"))

        val expired = KugouWelfare.parseReward("""{"status":0,"error_code":20018}""")
        assertEquals(KugouWelfare.ERROR_LOGIN_EXPIRED, expired.errorCode)
        assertTrue(expired.message.contains("重新扫码"))
    }

    @Test
    fun `unknown error keeps the server message`() {
        val result = KugouWelfare.parseReward("""{"status":0,"error_code":53001,"error_msg":"活动太火爆"}""")
        assertFalse(result.success)
        assertEquals(53001, result.errorCode)
        assertEquals("活动太火爆", result.message)
    }

    @Test
    fun `garbage response does not crash`() {
        val result = KugouWelfare.parseReward("<html>502 Bad Gateway</html>")
        assertFalse(result.success)
        assertFalse(result.already)
        assertEquals("返回无法解析", result.message)
    }

    @Test
    fun `month record counts this month and flags today`() {
        // 「今天」写死：原来取北京时间当天，每月 28 号都会和下面那个 "${month}-28" 撞成同一天，
        // 三条记录塌成两条，断言 3 必失败。parseMonthRecord 本来就收 today 参数，用它定点。
        val today = "2026-03-10"
        val month = today.substring(0, 7)
        val yesterday = java.time.LocalDate.parse(today).minusDays(1).toString()
        val raw = """
            {"status":1,"data":{"list":[
              {"date":"$yesterday","content":"1天畅听VIP"},
              {"date":"$today","content":"1天畅听VIP"},
              {"date":"${month}-28","content":"1天畅听VIP"}
            ]}}
        """.trimIndent()
        val record = KugouWelfare.parseMonthRecord(raw, today)
        assertEquals(3, record?.first)
        assertTrue(record?.second == true)
    }

    @Test
    fun `month record tolerates a plain date array`() {
        val today = KugouWelfare.beijingToday()
        val raw = """{"status":1,"data":{"days":["$today"]}}"""
        val record = KugouWelfare.parseMonthRecord(raw)
        assertEquals(1, record?.first)
        assertTrue(record?.second == true)
    }

    @Test
    fun `month record returns null when no date can be read`() {
        // 接口结构没定：读不出日期就返回 null，界面不显示「本月已领几天」，不猜
        assertNull(KugouWelfare.parseMonthRecord("""{"status":1,"data":{"total":7}}"""))
        assertNull(KugouWelfare.parseMonthRecord("""{"status":1,"data":{}}"""))
        assertNull(KugouWelfare.parseMonthRecord("""{"status":1}"""))
        assertNull(KugouWelfare.parseMonthRecord("not json"))
    }

    @Test
    fun `beijing today is a plain calendar date`() {
        val today = KugouWelfare.beijingToday()
        assertTrue(today, Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(today))
        assertEquals(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(), today)
    }

    @Test
    fun `vip info picks the higher tier and its expiry`() {
        val listen = KugouWelfareInfo(
            status = 1,
            listenVipActive = true,
            listenVipExpireAt = 1_000L,
        )
        assertTrue(listen.ok)
        assertTrue(listen.vipActive)
        assertEquals("畅听 VIP", listen.vipLabel)
        assertEquals(1_000L, listen.vipExpireAt)
        // 没读到领取记录时不显示本月天数
        assertEquals(-1, listen.monthClaimedDays)

        val concept = listen.copy(
            conceptVipActive = true,
            conceptVipExpireAt = 2_000L,
        )
        assertEquals("概念版 VIP", concept.vipLabel)
        assertEquals(2_000L, concept.vipExpireAt)
    }

    @Test
    fun `claim outcome joins both messages`() {
        val outcome = KugouClaimOutcome(
            claim = KugouRewardResult(success = true, message = "领取成功"),
            upgrade = KugouRewardResult(success = true, message = "概念版 VIP 升级成功"),
        )
        assertTrue(outcome.success)
        assertFalse(outcome.already)
        assertEquals("领取成功；概念版 VIP 升级成功", outcome.message)

        // 没尝试升级（领取就失败了）时只有领取那一句
        val failed = KugouClaimOutcome(claim = KugouRewardResult(message = "账号被风控，请到酷狗概念版客户端里领"))
        assertFalse(failed.success)
        assertNull(failed.upgrade)
        assertEquals("账号被风控，请到酷狗概念版客户端里领", failed.message)
    }

    @Test
    fun `kugou cookie splits into fields`() {
        val cookie = "token=abc123; userid=456; KUGOU_API_MID=mid1; dfid=dfid1"
        val map = parseKugouCookie(cookie)
        assertEquals("abc123", map["token"])
        assertEquals("456", map["userid"])
        assertEquals("mid1", map["KUGOU_API_MID"])
        assertEquals("dfid1", map["dfid"])
    }
}
