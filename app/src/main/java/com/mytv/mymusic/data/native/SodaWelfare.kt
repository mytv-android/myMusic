package com.mytv.mymusic.data.native

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 汽水音乐「天天送会员」（App 里叫 treasure / 天天送）的客户端。
 *
 * 机制是反编译汽水安卓端（com.luna.music）得到的，接口和官方客户端完全一致：
 * - `POST /luna/commerce/v2/commerce_info`：任务中心状态。未登录也能调，只回配置；
 *   登录后带回听歌时长阈值（reward_tasks_time_ms，毫秒）、已完成任务数、免费 VIP 到期时间。
 * - `POST /luna/commerce/v2/apply_free_vip`：空 body，把已达标但还没领的免费 VIP 发到账号上。
 *   未登录返回 status_code=1000003「未登录」。
 *
 * 听歌时长由服务端累计（本 App 的播放上报已经在做，见 PlayReportHub / SodaPlayReporter），
 * 所以这里只读状态和领取，不自己算天数。
 */
object SodaWelfare {
    private const val API_BASE = "https://api.qishui.com"

    /** 未登录（服务端 status_code=1000003）。 */
    const val STATUS_NOT_LOGGED_IN = 1000003

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun commerceInfo(cookie: String): SodaCommerceInfo {
        val response = NativeHttp.postJson(
            "$API_BASE/luna/commerce/v2/commerce_info?${SodaProvider.welfareAppParams()}",
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
        return parseCommerceInfo(response.body)
    }

    /** 把已达标的免费 VIP 领到账号上；空 body，成败看 apply_status。 */
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

    fun parseCommerceInfo(raw: String): SodaCommerceInfo {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return SodaCommerceInfo()
        val statusCode = root.int("status_code") ?: 0
        val statusMsg = root.string("status_info", "status_msg").orEmpty()
        if (statusCode != 0) {
            return SodaCommerceInfo(statusCode = statusCode, statusMsg = statusMsg)
        }
        val treasure = root.obj("treasure")
        val entitlement = root.obj("play_entitlement") ?: treasure?.obj("play_entitlement")
        val taskTimes = entitlement?.arrayLong("reward_tasks_time_ms")
            ?: treasure?.arrayLong("reward_tasks_time_ms")
            .orEmpty()
        return SodaCommerceInfo(
            statusCode = statusCode,
            statusMsg = statusMsg,
            // 入口授权关掉时任务中心整体不可用
            entranceAuthorized = treasure?.obj("config")?.boolean("entrance_authorize") ?: true,
            pageUrl = treasure?.string("page_url").orEmpty(),
            rewardTasksTimeMs = taskTimes,
            rewardTasksFinished = entitlement?.int("reward_tasks_finished") ?: 0,
            expireAt = entitlement?.long("expire_at") ?: 0L,
            hasPlayEntitlement = entitlement?.boolean("has_play_entitlement") ?: false,
            aggregateRewardAmount = treasure?.long("aggregate_reward_amount") ?: 0L,
        )
    }

    fun parseApplyResult(raw: String): SodaApplyFreeVipResult {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return SodaApplyFreeVipResult(false, "返回无法解析")
        val statusCode = root.int("status_code") ?: 0
        val statusMsg = root.string("status_info", "status_msg").orEmpty()
        val applyStatus = root.int("apply_status")
        val toast = root.string("toast").orEmpty()
        val success = statusCode == 0 && applyStatus == SodaWelfare.ApplyFreeVipStatus.Success
        val message = when {
            toast.isNotBlank() -> toast
            success -> "已领取免费 VIP"
            statusCode == STATUS_NOT_LOGGED_IN -> "未登录汽水音乐"
            statusMsg.isNotBlank() -> statusMsg
            else -> "领取失败（$statusCode）"
        }
        return SodaApplyFreeVipResult(success = success, message = message, applyStatus = applyStatus)
    }

    /** 官方客户端的 apply_status 取值，见 ApplyFreeVipStatus。 */
    object ApplyFreeVipStatus {
        const val Success = 1
    }
}

/** 天天送会员的任务状态。全部来自服务端，本机不存。 */
data class SodaCommerceInfo(
    val statusCode: Int = -1,
    val statusMsg: String = "",
    val entranceAuthorized: Boolean = true,
    val pageUrl: String = "",
    /** 听歌时长阈值（毫秒），按从小到大排；达到第几个就完成第几个任务。 */
    val rewardTasksTimeMs: List<Long> = emptyList(),
    val rewardTasksFinished: Int = 0,
    /** 免费 VIP 到期时间（毫秒），0 表示当前没有。 */
    val expireAt: Long = 0L,
    val hasPlayEntitlement: Boolean = false,
    val aggregateRewardAmount: Long = 0L,
) {
    val notLoggedIn: Boolean get() = statusCode == SodaWelfare.STATUS_NOT_LOGGED_IN
    val ok: Boolean get() = statusCode == 0
    val hasTasks: Boolean get() = rewardTasksTimeMs.isNotEmpty()
    val tasksTotal: Int get() = rewardTasksTimeMs.size
    val allTasksDone: Boolean get() = hasTasks && rewardTasksFinished >= tasksTotal
    val vipActive: Boolean get() = expireAt > System.currentTimeMillis()
    /** 今天听完这些分钟数就能领（把阈值转成分钟，去掉重复值）。 */
    val taskMinutes: List<Int>
        get() = rewardTasksTimeMs.map { (it / 60_000L).toInt().coerceAtLeast(1) }.distinct()
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
