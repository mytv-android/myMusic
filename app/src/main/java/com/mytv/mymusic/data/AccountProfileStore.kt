package com.mytv.mymusic.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.accountProfileDataStore by preferencesDataStore("account_profiles")

/**
 * 平台账号资料（昵称、头像、会员等级）的落盘缓存。
 *
 * 资料是每个平台各自的用户信息接口，纯网络请求：失败一次就再也不重试（Cookie 不变就不会
 * 再触发），冷启动又要重新拉一遍。所以把上次成功的结果连时间戳一起存下来——
 * 界面一打开先显示上次那份，后台再静默刷新；刷新失败也保持上次的，不退化成一行「已登录」。
 */
@Serializable
data class CachedAccountProfile(
    val providerId: String = "",
    val nickname: String = "",
    val avatar: String = "",
    /** 会员等级，例如「豪华绿钻钻石 LV8」；没有会员的平台是空串。 */
    val detail: String = "",
    /** 上次成功拉到的时间（毫秒）。 */
    val savedAt: Long = 0L,
) {
    /** 超过 [STALE_MS] 才算旧，值得再拉一次。 */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean = now - savedAt > STALE_MS

    companion object {
        /** 一天：会员到期当天要能看出来，但没必要每次冷启动都去撞各平台的用户信息接口。 */
        const val STALE_MS = 24L * 60 * 60 * 1000
    }
}

class AccountProfileStore(context: Context) {
    private val dataStore = context.accountProfileDataStore
    private val profilesKey = stringPreferencesKey("profiles")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val profiles: Flow<Map<String, CachedAccountProfile>> = dataStore.data.map { values ->
        decode(values[profilesKey])
    }

    /** 一次性读一份快照；调用方拿去判断要不要重新拉，不必每次都读盘。 */
    suspend fun snapshot(): Map<String, CachedAccountProfile> = decode(
        dataStore.data.first()[profilesKey],
    )

    suspend fun save(providerId: String, nickname: String, avatar: String, detail: String) {
        if (providerId.isBlank()) return
        dataStore.edit { values ->
            val updated = decode(values[profilesKey]) + (
                providerId to CachedAccountProfile(
                    providerId = providerId,
                    nickname = nickname,
                    avatar = avatar,
                    detail = detail,
                    savedAt = System.currentTimeMillis(),
                )
                )
            values[profilesKey] = json.encodeToString(updated)
        }
    }

    suspend fun remove(providerId: String) {
        if (providerId.isBlank()) return
        dataStore.edit { values ->
            val current = decode(values[profilesKey])
            if (providerId !in current) return@edit
            values[profilesKey] = json.encodeToString(current - providerId)
        }
    }

    /** 读不出来就当没有：缓存只是锦上添花，坏一份不该让账号页打不开。 */
    private fun decode(raw: String?): Map<String, CachedAccountProfile> =
        runCatching { json.decodeFromString<Map<String, CachedAccountProfile>>(raw ?: "{}") }
            .getOrDefault(emptyMap())
}
