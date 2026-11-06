package com.mytv.mymusic.data.native

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 汽水音乐「天天送会员」（App 里叫 treasure，活动名「VIP天天送」）的客户端。
 *
 * 接口来自反编译汽水安卓端（com.luna.music），和官方客户端一致：
 * - `POST /luna/commerce/v2/commerce_info`：任务中心状态。**请求体必须带 includes**，
 *   只发 `{}` 服务端就只回默认那一小块，天天送的活动状态根本不在里面。
 * - `POST /luna/commerce/v2/apply_free_vip`：空 body 领取，apply_status=0 表示成功。
 *
 * 这个账号实测到的机制（2026-11-05）：活动期 2026-10-30 ~ 2026-11-29，
 * 「每天打开汽水 APP 听歌可获得 1 天免费 VIP」，领到的天数记在 membership 上；
 * 听歌时长任务（play_entitlements.reward_tasks_time_ms）只有部分账号有。
 * 听歌时长由服务端累计（本 App 的播放上报已经在做，见 PlayReportHub / SodaPlayReporter），
 * 所以这里只读状态和点领取，不自己算天数。
 */
object SodaWelfare {
    private const val API_BASE = "https://api.qishui.com"

    /** 未登录（服务端 status_code=1000003）。 */
    const val STATUS_NOT_LOGGED_IN = 1000003

    /** 不带 includes 服务端只会回默认字段，活动状态、听歌任务、会员信息都拿不到。 */
    private const val COMMERCE_INFO_BODY =
        """{"includes":["treasure","play_entitlements","activity_free_vip","membership"]}"""

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun commerceInfo(cookie: String): SodaWelfareInfo {
        val response = NativeHttp.postJson(
            "$API_BASE/luna/commerce/v2/commerce_info?${SodaProvider.welfareAppParams()}",
            COMMERCE_INFO_BODY,
            headers = mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Accept" to "application/json",
                "x-luna-background-type" to "foreground",
                "x-luna-is-background-req" to "0",
                "x-luna-is-local-user" to "1",
            ),
            cookie = cookie,
            userAgent = SodaProvider.WELFARE_USER_AGENT,
        )
        return parseCommerceInfo(response.body)
    }

    /** 把今天的免费 VIP 领到账号上；空 body，成败看 apply_status。 */
    suspend fun applyFreeVip(cookie: String): SodaApplyFreeVipResult {
        val response = NativeHttp.postJson(
            "$API_BASE/luna/commerce/v2/apply_free_vip?${SodaProvider.welfareAppParams()}",
            "{}",
            headers = mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Accept" to "application/json",
                "x-luna-background-type" to "foreground",
                "x-luna-is-background-req" to "0",
                "x-luna-is-local-user" to "1",
            ),
            cookie = cookie,
            userAgent = SodaProvider.WELFARE_USER_AGENT,
        )
        return parseApplyResult(response.body)
    }

    fun parseCommerceInfo(raw: String, todayDate: String = todayDate()): SodaWelfareInfo {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return SodaWelfareInfo()
        val statusCode = root.int("status_code") ?: 0
        val statusMsg = root.string("status_info", "status_msg").orEmpty()
        if (statusCode != 0) {
            return SodaWelfareInfo(statusCode = statusCode, statusMsg = statusMsg)
        }
        val treasure = root.obj("treasure")
        val entitlement = root.obj("play_entitlements")
        val activity = root.obj("activity_free_vip")
        val membership = root.obj("membership")
        val nowSec = System.currentTimeMillis() / 1000

        // 天天送活动：按服务端给的活动起止时间判活跃，日历里 5=待领取、8=今天已领
        val startSec = activity?.long("start_time_sec") ?: 0L
        val endSec = activity?.long("end_time_sec") ?: 0L
        val calendar = activity?.arrayObj("calendar_dates").orEmpty()
        val claimedToday = activity?.string("latest_apply_date") == todayDate ||
            calendar.any { it.int("status") == CALENDAR_TODAY_CLAIMED }
        return SodaWelfareInfo(
            statusCode = statusCode,
            statusMsg = statusMsg,
            // 入口授权关掉时整个任务中心不可用
            entranceAuthorized = treasure?.obj("config")?.boolean("entrance_authorize") ?: true,
            pageUrl = treasure?.string("page_url").orEmpty(),
            activityActive = startSec in 1..nowSec && endSec > nowSec,
            activityStartDate = activity?.string("start_date").orEmpty(),
            activityEndDate = activity?.string("end_date").orEmpty(),
            appliedCount = activity?.int("applied_cnt") ?: 0,
            latestApplyDate = activity?.string("latest_apply_date").orEmpty(),
            todayClaimed = claimedToday,
            pendingDays = calendar.count { it.int("status") == CALENDAR_PENDING },
            rulesTitle = activity?.obj("activity_rules")?.string("title").orEmpty(),
            rules = activity?.obj("activity_rules")?.string("content").orEmpty(),
            // 听歌时长任务：只有部分账号配了阈值
            taskMinutes = entitlement?.arrayLong("reward_tasks_time_ms")
                ?.map { (it / 60_000L).toInt().coerceAtLeast(1) }
                ?.distinct()
                .orEmpty(),
            tasksFinished = entitlement?.int("reward_tasks_finished") ?: 0,
            vipActive = membership?.boolean("is_membership") ?: false,
            vipType = membership?.string("membership_type").orEmpty(),
            // 服务端给的是秒，界面用毫秒
            vipExpireAt = (membership?.long("expire_time") ?: 0L) * 1000L,
        )
    }

    fun parseApplyResult(raw: String): SodaApplyFreeVipResult {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return SodaApplyFreeVipResult(false, "返回无法解析")
        val statusCode = root.int("status_code") ?: 0
        val statusMsg = root.string("status_info", "status_msg").orEmpty()
        val applyStatus = root.int("apply_status")
        val toast = root.string("toast").orEmpty()
        val success = statusCode == 0 && applyStatus == ApplyFreeVipStatus.Success
        val message = when {
            toast.isNotBlank() -> toast
            success -> "已领取免费 VIP"
            statusCode == STATUS_NOT_LOGGED_IN -> "未登录汽水音乐"
            applyStatus == ApplyFreeVipStatus.HasApplied -> "今天已经领过了"
            applyStatus == ApplyFreeVipStatus.OutOfActivity -> "活动已结束"
            statusMsg.isNotBlank() -> statusMsg
            else -> "领取失败（$statusCode）"
        }
        return SodaApplyFreeVipResult(success = success, message = message, applyStatus = applyStatus)
    }

    /** 官方客户端的 apply_status 取值。 */
    object ApplyFreeVipStatus {
        const val Success = 0
        const val Risk = 1
        const val DeviceReachLimit = 2
        const val HasApplied = 3
        const val OutOfActivity = 13
    }

    private const val CALENDAR_PENDING = 5
    private const val CALENDAR_TODAY_CLAIMED = 8

    private fun todayDate(): String {
        val now = java.time.LocalDate.now()
        return "%04d-%02d-%02d".format(now.year, now.monthValue, now.dayOfMonth)
    }
}

/** 天天送会员的状态。全部来自服务端，本机不存。 */
data class SodaWelfareInfo(
    val statusCode: Int = -1,
    val statusMsg: String = "",
    /** 任务中心入口是否开放；关掉时活动整体不可用。 */
    val entranceAuthorized: Boolean = true,
    val pageUrl: String = "",
    /** 「VIP天天送」活动是否在期内。 */
    val activityActive: Boolean = false,
    val activityStartDate: String = "",
    val activityEndDate: String = "",
    /** 活动期内累计领到的天数。 */
    val appliedCount: Int = 0,
    /** 最近一次领取日期（yyyy-MM-dd）。 */
    val latestApplyDate: String = "",
    /** 今天这一天的免费 VIP 是否已经到手。 */
    val todayClaimed: Boolean = false,
    /** 日历里还标着「待领取」的天数。 */
    val pendingDays: Int = 0,
    val rulesTitle: String = "",
    /** 活动规则正文，服务端原文。 */
    val rules: String = "",
    /** 听歌时长阈值（分钟），按从小到大排；空的说明这个账号没有时长任务。 */
    val taskMinutes: List<Int> = emptyList(),
    val tasksFinished: Int = 0,
    /** 会员是否生效中（含免费 VIP）。 */
    val vipActive: Boolean = false,
    /** 会员类型：free_vip = 天天送送的，vip = 购买的。 */
    val vipType: String = "",
    /** 会员到期时间（毫秒）。 */
    val vipExpireAt: Long = 0L,
) {
    val notLoggedIn: Boolean get() = statusCode == SodaWelfare.STATUS_NOT_LOGGED_IN
    val ok: Boolean get() = statusCode == 0
    /** 有活动、今天还没领，才能点领取。 */
    val claimable: Boolean get() = ok && activityActive && !todayClaimed
    val hasTasks: Boolean get() = taskMinutes.isNotEmpty()
    val tasksTotal: Int get() = taskMinutes.size
    val allTasksDone: Boolean get() = hasTasks && tasksFinished >= tasksTotal
    /** 活动期文案，服务端没给日期时就只说「活动进行中」。 */
    val activityPeriod: String
        get() = when {
            activityStartDate.isNotBlank() && activityEndDate.isNotBlank() -> "$activityStartDate ~ $activityEndDate"
            activityActive -> "活动进行中"
            else -> ""
        }
}

data class SodaApplyFreeVipResult(
    val success: Boolean,
    val message: String,
    val applyStatus: Int? = null,
)

// ---------------- JSON 小工具 ----------------

private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.long(key: String): Long? =
    this[key]?.jsonPrimitive?.longOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    this[key]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.string(vararg path: String): String? {
    var node: JsonObject? = this
    for (key in path.dropLast(1)) node = node?.obj(key)
    val last = path.last()
    val element = node?.get(last) ?: return null
    return runCatching { element.jsonPrimitive }.getOrNull()?.takeIf { it.isString }?.content
}

private fun JsonObject.obj(key: String): JsonObject? =
    this[key] as? JsonObject

private fun JsonObject.arrayLong(key: String): List<Long>? =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
        runCatching { it.jsonPrimitive.longOrNull }.getOrNull()
    }

private fun JsonObject.arrayObj(key: String): List<JsonObject>? =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }
