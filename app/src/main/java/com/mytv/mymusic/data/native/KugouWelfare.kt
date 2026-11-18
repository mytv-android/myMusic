package com.mytv.mymusic.data.native

import com.mytv.mymusic.AppLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 酷狗概念版（客户端包名 `com.kugou.android.lite`）「听歌领会员」的客户端。
 *
 * 概念版和普通酷狗音乐不是同一套账号体系：它有自己的 appid（3116）、签名盐和登录 Token。
 * 本 App 的酷狗登录走的就是概念版 appid，所以这里直接复用同一个 Cookie，不需要再登一次。
 *
 * 接口（多个开源实现的结论一致，见 THIRD_PARTY_NOTICES）：
 * - `POST /youth/v1/recharge/receive_vip_listen_song`：领一天的畅听 VIP。
 *   `status=1` 成功，`error_code=131001` 今天已经领过，`20028` 账号风控，`20018` 登录过期。
 * - `POST /youth/v1/listen_song/upgrade_vip_reward`：把当天领到的畅听 VIP 升级成概念版 VIP
 *   （音质更高），失败不影响上一步。
 * - `GET /youth/v1/activity/get_month_vip_record`：本月领取记录，只用来展示，解析不到就算了。
 * - `GET kugouvip.kugou.com/v1/get_union_vip?busi_type=concept`：概念版 / 畅听 VIP 的生效状态和到期时间。
 *
 * 签名和公共参数由 [KugouProvider.kugouYouthRequest] 统一构造
 * （`MD5(salt + 排序参数 + body + salt)`），和云盘写入、账号资料走同一套算法。
 * 领取日期按北京时间算：服务端会拒绝「日期小于今天」的请求，用设备本地时间在跨时区时会领不到。
 */
object KugouWelfare {
    const val KUGOU_PROVIDER_ID = "kugou"

    private const val TAG = "Kugou"

    private const val CLAIM_PATH = "/youth/v1/recharge/receive_vip_listen_song"
    private const val UPGRADE_PATH = "/youth/v1/listen_song/upgrade_vip_reward"
    private const val MONTH_RECORD_PATH = "/youth/v1/activity/get_month_vip_record"

    /** 领取来源：概念版听歌领会员活动。 */
    private const val SOURCE_ID = "90139"

    /** 今天已经领过了；对领取来说这也算达成目的。 */
    const val ERROR_ALREADY = 131001

    /** 账号被风控，只能去官方客户端领。 */
    const val ERROR_RISK = 20028

    /** 登录态过期，要重新扫码。 */
    const val ERROR_LOGIN_EXPIRED = 20018

    /** 记录里可能放日期串的字段名。 */
    private val RECORD_DATE_KEYS =
        listOf("date", "day", "receive_day", "vip_date", "create_time", "time")

    /** 记录列表可能再套一层的字段名。 */
    private val RECORD_LIST_KEYS = listOf("list", "record", "records", "days", "data")

    private val RECORD_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 概念版 / 畅听 VIP 的生效状态，外加本月领取记录（拿不到就不给）。 */
    suspend fun status(cookie: String): KugouWelfareInfo {
        val credentials = kugouConceptCredentials(cookie)
            ?: return KugouWelfareInfo(status = 0, loggedIn = false, statusMsg = "未登录酷狗音乐")
        val provider = kugouProvider()
            ?: return KugouWelfareInfo(statusMsg = "酷狗音乐组件未就绪")

        val vipData = runCatching {
            provider.fetchUnionVip(
                cookie = cookie,
                token = credentials.token,
                userid = credentials.userid,
                mid = credentials.mid,
                dfid = credentials.dfid,
            )
        }.onFailure {
            AppLog.w(TAG, "概念版 VIP 状态请求失败：${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()

        // 领取记录是锦上添花：解析不到不影响 VIP 状态，只是不显示「本月已领几天」
        val record = runCatching { provider.kugouYouthRequest(cookie, MONTH_RECORD_PATH) }
            .onFailure { AppLog.w(TAG, "概念版领取记录请求失败：${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
            ?.let { parseMonthRecord(it) }

        val info = KugouWelfareInfo(
            status = vipData?.int("status") ?: 0,
            statusMsg = vipData?.string("error_msg", "error").orEmpty(),
            loggedIn = true,
            conceptVipActive = vipData.productActive("svip"),
            conceptVipExpireAt = vipData.productExpireAt("svip"),
            listenVipActive = vipData.productActive("tvip"),
            listenVipExpireAt = vipData.productExpireAt("tvip"),
            monthClaimedDays = record?.first ?: -1,
            todayClaimed = record?.second ?: false,
        )
        AppLog.i(
            TAG,
            "概念版天天送 status=${info.status} 概念VIP=${info.conceptVipActive}" +
                "(到期=${info.conceptVipExpireAt}) 畅听VIP=${info.listenVipActive}" +
                "(到期=${info.listenVipExpireAt}) 本月已领=${info.monthClaimedDays} 今天已领=${info.todayClaimed}",
        )
        return info
    }

    /** 领一天的畅听 VIP。 */
    suspend fun claimDayVip(cookie: String): KugouRewardResult {
        if (kugouConceptCredentials(cookie) == null) {
            return KugouRewardResult(message = "未登录酷狗音乐")
        }
        val raw = youthPost(cookie, CLAIM_PATH, mapOf("source_id" to SOURCE_ID))
        return parseReward(raw, successMessage = "领取成功，获得 1 天畅听 VIP").also {
            AppLog.i(TAG, "概念版领取听歌VIP success=${it.success} already=${it.already} code=${it.errorCode}")
        }
    }

    /** 把今天领到的畅听 VIP 升级成概念版 VIP（音质更高）；失败不影响领取结果。 */
    suspend fun upgradeConceptVip(cookie: String): KugouRewardResult {
        val credentials = kugouConceptCredentials(cookie)
            ?: return KugouRewardResult(message = "未登录酷狗音乐")
        val raw = youthPost(cookie, UPGRADE_PATH, mapOf("kugouid" to credentials.userid, "ad_type" to "1"))
        return parseReward(raw, successMessage = "概念版 VIP 升级成功").also {
            AppLog.i(TAG, "概念版升级VIP success=${it.success} already=${it.already} code=${it.errorCode}")
        }
    }

    /**
     * 一次做完「领取 + 升级」：手动点击和启动时的自动领取都走这里。
     * 升级失败不影响领取的结果，所以两步分开返回。
     */
    suspend fun claimAll(cookie: String, upgrade: Boolean = true): KugouClaimOutcome {
        val claim = claimDayVip(cookie)
        // 领到了（含「今天已经领过了」）才值得升级；风控、登录过期就不要再打接口
        val upgraded = if (upgrade && (claim.success || claim.already)) upgradeConceptVip(cookie) else null
        return KugouClaimOutcome(claim = claim, upgrade = upgraded)
    }

    private suspend fun youthPost(cookie: String, path: String, extra: Map<String, String>): String {
        val provider = kugouProvider() ?: throw IllegalStateException("酷狗音乐组件未就绪")
        // 只有领取要带日期；升级接口没有这个参数
        val params = if (path == CLAIM_PATH) extra + ("receive_day" to beijingToday()) else extra
        return provider.kugouYouthRequest(cookie, path, params)
    }

    private fun kugouProvider(): KugouProvider? =
        NativeProviders.get(KUGOU_PROVIDER_ID) as? KugouProvider

    /** 概念版接口要的四个凭证；缺 token / userid 就当没登录。 */
    private fun kugouConceptCredentials(cookie: String): KugouConceptCredentials? {
        val map = parseKugouCookie(cookie)
        val token = map["token"].orEmpty().trim()
        val userid = map["userid"].orEmpty().trim()
        if (token.isEmpty() || userid.isEmpty() || userid == "0") return null
        return KugouConceptCredentials(
            token = token,
            userid = userid,
            mid = firstNonBlank(map["KUGOU_API_MID"], map["kg_mid"], map["mid"], "-"),
            dfid = firstNonBlank(map["dfid"], map["KUGOU_API_DFID"], map["kg_dfid"], "-"),
        )
    }

    /**
     * 解析 youth 接口的返回：`status=1` 成功，失败看 `error_code`。
     * `error_code=131001`（今天已领）单独归一成 [KugouRewardResult.already]，
     * 界面上它和成功是同一种结果。
     *
     * @param successMessage 成功时给用户看的一句话，领取和升级各有各的说法。
     */
    fun parseReward(raw: String, successMessage: String = "领取成功"): KugouRewardResult {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return KugouRewardResult(message = "返回无法解析")
        val status = root.int("status") ?: 0
        val errorCode = root.int("error_code", "errcode") ?: 0
        val errorMsg = root.string("error_msg", "error", "message").orEmpty()
        val message = when {
            errorCode == ERROR_ALREADY -> "今天已经领过了"
            errorCode == ERROR_RISK -> "账号被风控，请到酷狗概念版客户端里领"
            errorCode == ERROR_LOGIN_EXPIRED -> "登录已过期，请重新扫码登录酷狗"
            status == 1 -> successMessage
            errorMsg.isNotBlank() -> errorMsg
            else -> "领取失败（错误码 $errorCode）"
        }
        return KugouRewardResult(
            success = status == 1,
            already = errorCode == ERROR_ALREADY,
            errorCode = errorCode,
            message = message,
        )
    }

    /**
     * 本月领取记录。返回「本月已领几天」和「今天是否已领」。
     *
     * 这个接口没有公开文档，所以按已知字段兜底解析：只认能明确读出 `yyyy-MM-dd` 的记录，
     * 读不出就返回 null，界面不显示这一段，不猜。
     */
    fun parseMonthRecord(raw: String, today: String = beijingToday()): Pair<Int, Boolean>? {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val data = root.obj("data") ?: return null
        val dates = mutableSetOf<String>()
        collectRecordDates(data, dates)
        if (dates.isEmpty()) return null
        val month = today.substring(0, 7)
        return dates.count { it.startsWith(month) } to (today in dates)
    }

    /** 记录可能是对象数组、日期字符串数组，也可能再套一层 list / record / days。 */
    private fun collectRecordDates(node: JsonElement, out: MutableSet<String>) {
        when (node) {
            is JsonPrimitive -> node.content.trim().takeIf { RECORD_DATE.matches(it) }?.let { out.add(it) }
            is JsonArray -> node.forEach { collectRecordDates(it, out) }
            is JsonObject -> {
                RECORD_DATE_KEYS.forEach { key ->
                    (node[key] as? JsonPrimitive)?.content?.trim()
                        ?.takeIf { RECORD_DATE.matches(it) }?.let { out.add(it) }
                }
                RECORD_LIST_KEYS.forEach { key -> node[key]?.let { collectRecordDates(it, out) } }
            }
        }
    }

    /** 服务端的「今天」按北京时间算，和领取日期保持一致。 */
    fun beijingToday(): String {
        val now = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
        return "%04d-%02d-%02d".format(now.year, now.monthValue, now.dayOfMonth)
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    // ---------------- JSON 小工具 ----------------

    private fun JsonObject.int(vararg keys: String): Int? {
        for (key in keys) {
            val value = (this[key] as? JsonPrimitive)?.content?.trim().orEmpty()
            value.toIntOrNull()?.let { return it }
            value.toDoubleOrNull()?.let { return it.toInt() }
        }
        return null
    }

    private fun JsonObject.long(vararg keys: String): Long? {
        for (key in keys) {
            val value = (this[key] as? JsonPrimitive)?.content?.trim().orEmpty()
            value.toLongOrNull()?.let { return it }
            value.toDoubleOrNull()?.let { return it.toLong() }
        }
        return null
    }

    private fun JsonObject.string(vararg keys: String): String? {
        for (key in keys) {
            val element = this[key] as? JsonPrimitive ?: continue
            if (element.content.isNotBlank()) return element.content.trim()
        }
        return null
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.product(productType: String): JsonObject? =
        (this["busi_vip"] as? JsonArray)?.firstOrNull { item ->
            (item as? JsonObject)?.string("product_type") == productType
        } as? JsonObject

    /** busi_vip 里某个产品是否生效：is_vip / vip_type 大于 0 都算。 */
    private fun JsonObject?.productActive(productType: String): Boolean {
        val product = this?.product(productType) ?: return false
        return (product.int("is_vip") ?: 0) > 0 || (product.int("vip_type") ?: 0) > 0
    }

    /** 产品到期时间；服务端给的是秒，界面用毫秒。 */
    private fun JsonObject?.productExpireAt(productType: String): Long {
        val product = this?.product(productType) ?: return 0L
        val seconds = product.long("vip_end_time", "expire_time", "end_time") ?: 0L
        return if (seconds > 0) seconds * 1000L else 0L
    }
}

/** 概念版接口要的四个凭证。 */
data class KugouConceptCredentials(
    val token: String,
    val userid: String,
    val mid: String,
    val dfid: String,
)

/**
 * 概念版「听歌领会员」的状态。VIP 生效与到期以服务端为准，本机不存。
 *
 * @param status 服务端 status：1=正常，0=失败，-1=本机还没请求过。
 * @param monthClaimedDays 本月已领天数；服务端没给可解析的记录时是 -1，界面不显示。
 */
data class KugouWelfareInfo(
    val status: Int = -1,
    val statusMsg: String = "",
    /** 是否已登录酷狗（概念版）账号。 */
    val loggedIn: Boolean = true,
    /** 概念版 VIP（svip）是否生效中。 */
    val conceptVipActive: Boolean = false,
    /** 概念版 VIP 到期时间（毫秒）。 */
    val conceptVipExpireAt: Long = 0L,
    /** 畅听 VIP（tvip）是否生效中。 */
    val listenVipActive: Boolean = false,
    /** 畅听 VIP 到期时间（毫秒）。 */
    val listenVipExpireAt: Long = 0L,
    /** 本月已领天数，-1 表示服务端没给。 */
    val monthClaimedDays: Int = -1,
    /** 今天这一天的免费 VIP 是否已经到手。 */
    val todayClaimed: Boolean = false,
) {
    val ok: Boolean get() = status == 1

    /** 任一种免费 VIP 生效中。 */
    val vipActive: Boolean get() = conceptVipActive || listenVipActive

    /** 生效中的那个 VIP 的到期时间，都没有就是 0。 */
    val vipExpireAt: Long get() = maxOf(conceptVipExpireAt, listenVipExpireAt)

    /** 会员档位文案：升级过的显示概念版，只领了畅听的显示畅听。 */
    val vipLabel: String
        get() = when {
            conceptVipActive -> "概念版 VIP"
            listenVipActive -> "畅听 VIP"
            else -> "免费 VIP"
        }
}

/** 一次 youth 接口调用的结果。 */
data class KugouRewardResult(
    val success: Boolean = false,
    /** 今天已经领过 / 升级过；对领取来说这也算达成目的。 */
    val already: Boolean = false,
    val errorCode: Int = 0,
    val message: String = "",
)

/** 「领取 + 升级」一次的总体结果。 */
data class KugouClaimOutcome(
    val claim: KugouRewardResult,
    /** 没尝试升级时是 null（用户关了自动升级，或者领取就没成功）。 */
    val upgrade: KugouRewardResult? = null,
) {
    val success: Boolean get() = claim.success
    val already: Boolean get() = claim.already

    /** 给界面和提示框的一句话结果。 */
    val message: String
        get() = listOfNotNull(claim.message, upgrade?.message?.takeIf { it.isNotBlank() })
            .joinToString("；")
}
