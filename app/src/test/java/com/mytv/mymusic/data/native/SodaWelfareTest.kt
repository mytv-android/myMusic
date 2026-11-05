package com.mytv.mymusic.data.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天天送会员的返回解析。REAL_RESPONSE 是 2026-11-05 从 ldm2060 这个账号实测抓回来的
 * commerce_info（请求体带 includes），用来锁住字段名和层级：
 * 活动在 activity_free_vip、会员在 membership、听歌任务在 play_entitlements（复数）。
 */
class SodaWelfareTest {

    @Test
    fun parsesRealResponse() {
        val info = SodaWelfare.parseCommerceInfo(REAL_RESPONSE)
        assertTrue(info.ok)
        assertFalse(info.notLoggedIn)
        // 会员：天天送送的 free_vip 正在生效
        assertTrue(info.vipActive)
        assertEquals("free_vip", info.vipType)
        assertEquals(1793936053_000L, info.vipExpireAt) // 服务端给秒，解析成毫秒
        // 今天已经领过（日历里 status=8 那天）
        assertTrue(info.todayClaimed)
        assertFalse(info.claimable)
        assertEquals(3, info.appliedCount)
        assertEquals("2026-11-05", info.latestApplyDate)
        assertEquals("2026-10-30", info.activityStartDate)
        assertEquals("2026-11-29", info.activityEndDate)
        // 这个账号没有配听歌时长阈值
        assertFalse(info.hasTasks)
        assertTrue(info.taskMinutes.isEmpty())
        assertEquals(0, info.tasksFinished)
        // 活动规则原文要能展示
        assertTrue(info.rules.contains("每天打开汽水APP听歌可获得1天免费VIP"))
        assertTrue(info.pendingDays > 0)
    }

    @Test
    fun claimableWhenTodayNotClaimed() {
        val now = System.currentTimeMillis() / 1000
        val yesterday = java.time.LocalDate.now().minusDays(1).toString()
        val raw = """
            {"status_code":0,
             "activity_free_vip":{"start_date":"2026-10-30","end_date":"2026-11-29",
               "applied_cnt":1,"latest_apply_date":"$yesterday",
               "start_time_sec":${now - 3600},"end_time_sec":${now + 3600},
               "calendar_dates":[{"date":"6","status":5,"content":"待领取"}],
               "activity_rules":{"title":"VIP天天送活动规则","content":"每天打开汽水APP听歌可获得1天免费VIP。"}},
             "play_entitlements":{"expire_at":0,"reward_tasks_finished":0,"reward_tasks_time_ms":null},
             "membership":{"is_membership":false,"membership_type":"","expire_time":0},
             "treasure":{"config":{"entrance_authorize":true}}}
        """.trimIndent()
        val info = SodaWelfare.parseCommerceInfo(raw)
        assertTrue(info.activityActive)
        assertFalse(info.todayClaimed)
        assertTrue(info.claimable)
        assertFalse(info.vipActive)
        assertEquals("2026-10-30 ~ 2026-11-29", info.activityPeriod)
    }

    @Test
    fun activityOutsideWindowIsNotClaimable() {
        val now = System.currentTimeMillis() / 1000
        val raw = """
            {"status_code":0,
             "activity_free_vip":{"start_date":"2026-10-30","end_date":"2026-11-29",
               "applied_cnt":0,"latest_apply_date":"",
               "start_time_sec":${now - 7200},"end_time_sec":${now - 3600},
               "calendar_dates":[]},
             "membership":{"is_membership":false,"membership_type":"","expire_time":0}}
        """.trimIndent()
        val info = SodaWelfare.parseCommerceInfo(raw)
        assertFalse(info.activityActive)
        assertFalse(info.claimable)
        // 日期仍然照实展示，配合卡片里的「活动不在期内」
        assertEquals("2026-10-30 ~ 2026-11-29", info.activityPeriod)
    }

    @Test
    fun listenTaskThresholdsBecomeMinutes() {
        val raw = """
            {"status_code":0,
             "play_entitlements":{"expire_at":0,"reward_tasks_finished":1,
               "reward_tasks_time_ms":[900000,1800000,1800000]},
             "membership":{"is_membership":false,"membership_type":"","expire_time":0}}
        """.trimIndent()
        val info = SodaWelfare.parseCommerceInfo(raw)
        assertTrue(info.hasTasks)
        assertEquals(listOf(15, 30), info.taskMinutes)
        assertEquals(1, info.tasksFinished)
        assertFalse(info.allTasksDone)
    }

    @Test
    fun notLoggedInIsSurfaced() {
        val info = SodaWelfare.parseCommerceInfo("""{"status_code":1000003,"status_msg":"未登录"}""")
        assertTrue(info.notLoggedIn)
        assertFalse(info.ok)
        assertFalse(info.claimable)
    }

    @Test
    fun emptyBodyIsNotOk() {
        // 只发 {} 时服务端就回这一小块，解析出来不能当成有活动
        val info = SodaWelfare.parseCommerceInfo("""{"status_code":0,"treasure":{"config":{"entrance_authorize":false},"page_style":0},"libra_params_map":{}}""")
        assertTrue(info.ok)
        assertFalse(info.activityActive)
        assertFalse(info.claimable)
        assertFalse(info.vipActive)
        assertFalse(info.entranceAuthorized)
    }

    @Test
    fun applyResultUsesServerToast() {
        // 今天已领过：apply_status=2，服务端直接给了 toast
        val applied = SodaWelfare.parseApplyResult("""{"status_code":0,"apply_status":2,"toast":"今日已经领取过了～"}""")
        assertFalse(applied.success)
        assertEquals("今日已经领取过了～", applied.message)
        assertEquals(2, applied.applyStatus)

        val success = SodaWelfare.parseApplyResult("""{"status_code":0,"apply_status":0,"toast":"领取成功"}""")
        assertTrue(success.success)
        assertEquals("领取成功", success.message)

        // 没 toast 时按状态给说法
        val noToast = SodaWelfare.parseApplyResult("""{"status_code":0,"apply_status":3}""")
        assertFalse(noToast.success)
        assertEquals("今天已经领过了", noToast.message)

        val expired = SodaWelfare.parseApplyResult("""{"status_code":0,"apply_status":13}""")
        assertEquals("活动已结束", expired.message)

        val notLoggedIn = SodaWelfare.parseApplyResult("""{"status_code":1000003,"status_msg":"未登录"}""")
        assertFalse(notLoggedIn.success)
        assertEquals("未登录汽水音乐", notLoggedIn.message)
        assertNull(notLoggedIn.applyStatus)
    }

    private companion object {
        // REAL_RESPONSE 由脚本注入：2026-11-05 实测的 commerce_info 返回（calendar 截短）
        val REAL_RESPONSE = """
{
  "status_code": 0,
  "status_info": {
    "now": 1793872992,
    "status_msg": "success",
    "log_id": "2026110518031230BF3D35086B2C14C117"
  },
  "activity_free_vip": {
    "start_date": "2026-10-30",
    "end_date": "2026-11-29",
    "applied_cnt": 3,
    "latest_apply_date": "2026-11-05",
    "calendar_dates": [
      {
        "date": "25",
        "status": 1
      },
      {
        "date": "26",
        "status": 1
      },
      {
        "date": "27",
        "status": 1
      },
      {
        "date": "28",
        "status": 1
      },
      {
        "date": "29",
        "status": 1
      },
      {
        "date": "30",
        "status": 7
      },
      {
        "date": "31",
        "status": 7
      },
      {
        "date": "1",
        "status": 1
      },
      {
        "date": "6",
        "status": 5,
        "content": "待领取"
      }
    ],
    "show_tomorrow_info": false,
    "start_time_sec": 1793334703,
    "end_time_sec": 1795881599,
    "latest_apply_time_sec": 1793849653,
    "show_calendar_mask": false,
    "activity_rules": {
      "title": "VIP天天送活动规则",
      "content": "1. 活动期间，汽水VIP天天免费送。\n2. 每天打开汽水APP听歌可获得1天免费VIP。\n3. 如启动后VIP未成功赠送，请检查登录情况，退出登录后重新登录查看。\n4. 本活动为不定期活动，可参与活动的受邀用户范围可能发生变化，活动终止时间请以页面提示或平台通知为准。\n5. 购买的VIP天数和送会员赠送的VIP天数可叠加，购买VIP不会影响天天送会员活动奖励发放。\n6. 在法律允许范围内，平台有权对活动规则进行调整，相关变动将及时公布在活动页面。\n7. 参与本活动，则视为您已阅读、理解并同意本活动规则。"
    }
  },
  "trade": {
    "allow_did_purchase": false,
    "has_paid_order": false,
    "allow_non_cn_apple_id_purchase": false
  },
  "membership": {
    "expire_time": 1793936053,
    "in_grace_period": false,
    "is_membership": true,
    "membership_type": "free_vip",
    "is_paying_user": false,
    "is_about_to_expire": true,
    "membership_detail_map": {
      "vip": {
        "expire_time": 1793936053,
        "start_time": 1793849653
      }
    },
    "last_membership_type": "free_vip",
    "expire_time_max": 1793936053,
    "membership_detail_map_v2": {
      "free_vip": {
        "expire_time": 1793936053,
        "start_time": 1793849653
      }
    },
    "membership_landing_page": "vip_purchase"
  },
  "play_entitlements": {
    "expire_at": 0,
    "reward_tasks_finished": 0,
    "reward_tasks_time_ms": null,
    "video_complete_acquired_time_ms": null,
    "pre_release_expire_at": 0,
    "is_full_day_all": false
  },
  "treasure": {
    "main_icon": {
      "can_show": false,
      "label": null,
      "schema": ""
    },
    "config": {
      "cold_launch_prefetch": {
        "api_prefetch": false,
        "gecko_prefetch": false,
        "pre_creation": false,
        "pre_rendering": false
      },
      "ack_time": {
        "need_no_login_ack_time": false,
        "ack_time_enable": false,
        "ack_time_strategy": "ack_on_task_page_only",
        "need_no_login_real_ack_time": false
      },
      "entrance_authorize": false,
      "fallback_advert_config": {
        "rit": "",
        "task_ids": [],
        "trigger_interval": 0
      },
      "ack_time_config": {
        "listen_music": {
          "need_no_login_ack_time": false,
          "ack_time_enable": false,
          "ack_time_strategy": "ack_on_task_page_only",
          "need_no_login_real_ack_time": false
        },
        "listen_video": {
          "need_no_login_ack_time": false,
          "ack_time_enable": false,
          "ack_time_strategy": "ack_on_task_page_only"
        }
      },
      "need_hide_svip_withdraw": false,
      "ack_count_config": {
        "ack_count_enable": false,
        "batch_size": 12,
        "need_no_login_ack_count": false,
        "need_no_login_real_ack_time": false
      }
    },
    "page_style": 0
  },
  "libra_params_map": {
    "benefit_base": {},
    "incentive": {},
    "privilege": {},
    "vip_center": {}
  }
}
""".trimIndent()
    }
}
