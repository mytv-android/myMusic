package com.mytv.mymusic.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * 「天天听歌」福利中心的账本，机制对齐汽水音乐的「VIP 天天送」：
 * 每天听歌达标领 VIP 天数，连续听歌奖励更大，天数随时激活续期。
 * 全部只存本机（DataStore），不经过任何账号体系。
 */
data class ListenTaskState(
    /** [todaySeconds] 归属的日期（yyyy-MM-dd）；跨天后读出来要先对今天。 */
    val todayDate: String = "",
    /** 当天实际播放累计的秒数（跳转、暂停不计）。 */
    val todaySeconds: Int = 0,
    /** 连续听歌达标天数；断了一天重新计。 */
    val streakDays: Int = 0,
    /** 最近一个达标（听满 [DAILY_FIRST_TARGET_SECONDS]）的日期。 */
    val lastListenDay: String = "",
    /** 领了还没激活的 VIP 天数。 */
    val vipDays: Int = 0,
    /** VIP 到期时间（毫秒时间戳）；0 或已过期为非 VIP。 */
    val vipUntil: Long = 0L,
    /** 三个任务的最近领取日期 / 领取轮数，用来防止重复领。 */
    val claimedDaily15: String = "",
    val claimedDaily30: String = "",
    val streakClaims: Int = 0,
) {
    val vipActive: Boolean get() = vipUntil > System.currentTimeMillis()
    val todayMinutes: Int get() = todaySeconds / 60
    val firstDone: Boolean get() = todaySeconds >= ListenTasks.DAILY_FIRST_TARGET_SECONDS
    val secondDone: Boolean get() = todaySeconds >= ListenTasks.DAILY_SECOND_TARGET_SECONDS

    /** 连续天数只认昨天和今天：昨天没达标，之前的连续记录作废。 */
    fun effectiveStreak(today: String = LocalDate.now().toString()): Int = when (lastListenDay) {
        today, LocalDate.parse(today).minusDays(1).toString() -> streakDays
        else -> 0
    }
}

/** 任务领取结果：返回领到的天数，0 表示没领成（未达标或已领过）。 */
object ListenTasks {
    const val DAILY_15 = "daily15"
    const val DAILY_30 = "daily30"
    const val STREAK = "streak"
    const val DAILY_FIRST_TARGET_SECONDS = 15 * 60
    const val DAILY_SECOND_TARGET_SECONDS = 30 * 60
    const val STREAK_TARGET_DAYS = 7
    const val STREAK_REWARD_DAYS = 7
    const val DAY_MS = 24L * 60 * 60 * 1000
}

private val Context.listenTaskDataStore by preferencesDataStore("listen_tasks")

class ListenTaskStore(context: Context) {
    private val dataStore = context.listenTaskDataStore

    private val todayDateKey = stringPreferencesKey("today_date")
    private val todaySecondsKey = intPreferencesKey("today_seconds")
    private val streakDaysKey = intPreferencesKey("streak_days")
    private val lastListenDayKey = stringPreferencesKey("last_listen_day")
    private val vipDaysKey = intPreferencesKey("vip_days")
    private val vipUntilKey = longPreferencesKey("vip_until")
    private val claimedDaily15Key = stringPreferencesKey("claimed_daily15")
    private val claimedDaily30Key = stringPreferencesKey("claimed_daily30")
    private val streakClaimsKey = intPreferencesKey("streak_claims")

    /**
     * 跨天就把当天时长归零再累加，界面因此永远只看到「今天」的进度。
     * 第一次跨过达标线时记连续天数：昨天也达标就 +1，否则从 1 开始。
     */
    val state: Flow<ListenTaskState> = dataStore.data.map { values ->
        val stored = ListenTaskState(
            todayDate = values[todayDateKey] ?: "",
            todaySeconds = values[todaySecondsKey] ?: 0,
            streakDays = values[streakDaysKey] ?: 0,
            lastListenDay = values[lastListenDayKey] ?: "",
            vipDays = values[vipDaysKey] ?: 0,
            vipUntil = values[vipUntilKey] ?: 0L,
            claimedDaily15 = values[claimedDaily15Key] ?: "",
            claimedDaily30 = values[claimedDaily30Key] ?: "",
            streakClaims = values[streakClaimsKey] ?: 0,
        )
        if (stored.todayDate == LocalDate.now().toString()) stored else stored.copy(todaySeconds = 0)
    }

    /** 播放中按进度差累计；[ms] 应只含真正播出来的时间。 */
    suspend fun addListening(ms: Long) {
        if (ms <= 0) return
        val today = LocalDate.now().toString()
        dataStore.edit { values ->
            val current = snapshot(values)
            val base = if (current.todayDate == today) current.todaySeconds else 0
            val total = (base + ms / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            var updated = current.copy(todayDate = today, todaySeconds = total)
            if (base < ListenTasks.DAILY_FIRST_TARGET_SECONDS && total >= ListenTasks.DAILY_FIRST_TARGET_SECONDS) {
                val yesterday = LocalDate.parse(today).minusDays(1).toString()
                updated = updated.copy(
                    streakDays = if (current.lastListenDay == yesterday) current.streakDays + 1 else 1,
                    lastListenDay = today,
                )
            }
            write(values, updated)
        }
    }

    /** 领取任务奖励，返回领到的 VIP 天数（0 = 未达标或今天已领过）。 */
    suspend fun claim(taskId: String): Int {
        val today = LocalDate.now().toString()
        var granted = 0
        dataStore.edit { values ->
            val current = snapshot(values)
            val todaySeconds = if (current.todayDate == today) current.todaySeconds else 0
            val updated = when (taskId) {
                ListenTasks.DAILY_15 ->
                    if (todaySeconds >= ListenTasks.DAILY_FIRST_TARGET_SECONDS && current.claimedDaily15 != today) {
                        granted = 1
                        current.copy(vipDays = current.vipDays + 1, claimedDaily15 = today)
                    } else current
                ListenTasks.DAILY_30 ->
                    if (todaySeconds >= ListenTasks.DAILY_SECOND_TARGET_SECONDS && current.claimedDaily30 != today) {
                        granted = 1
                        current.copy(vipDays = current.vipDays + 1, claimedDaily30 = today)
                    } else current
                ListenTasks.STREAK -> {
                    val streak = current.effectiveStreak(today)
                    if (streak >= ListenTasks.STREAK_TARGET_DAYS) {
                        granted = ListenTasks.STREAK_REWARD_DAYS
                        // 领完重新累计，下一轮连续重新开始
                        current.copy(
                            vipDays = current.vipDays + ListenTasks.STREAK_REWARD_DAYS,
                            streakDays = 0,
                            streakClaims = current.streakClaims + 1,
                        )
                    } else current
                }
                else -> current
            }
            if (updated != current) write(values, updated)
        }
        return granted
    }

    /** 把余额天数全部激活：从当前到期时间（或现在，取晚的）往后续。 */
    suspend fun activateVip(): Boolean {
        val now = System.currentTimeMillis()
        var activated = false
        dataStore.edit { values ->
            val current = snapshot(values)
            if (current.vipDays <= 0) return@edit
            activated = true
            write(
                values,
                current.copy(
                    vipUntil = maxOf(current.vipUntil, now) + current.vipDays * ListenTasks.DAY_MS,
                    vipDays = 0,
                ),
            )
        }
        return activated
    }

    private fun snapshot(values: androidx.datastore.preferences.core.Preferences) = ListenTaskState(
        todayDate = values[todayDateKey] ?: "",
        todaySeconds = values[todaySecondsKey] ?: 0,
        streakDays = values[streakDaysKey] ?: 0,
        lastListenDay = values[lastListenDayKey] ?: "",
        vipDays = values[vipDaysKey] ?: 0,
        vipUntil = values[vipUntilKey] ?: 0L,
        claimedDaily15 = values[claimedDaily15Key] ?: "",
        claimedDaily30 = values[claimedDaily30Key] ?: "",
        streakClaims = values[streakClaimsKey] ?: 0,
    )

    private fun write(values: androidx.datastore.preferences.core.MutablePreferences, state: ListenTaskState) {
        values[todayDateKey] = state.todayDate
        values[todaySecondsKey] = state.todaySeconds
        values[streakDaysKey] = state.streakDays
        values[lastListenDayKey] = state.lastListenDay
        values[vipDaysKey] = state.vipDays
        values[vipUntilKey] = state.vipUntil
        values[claimedDaily15Key] = state.claimedDaily15
        values[claimedDaily30Key] = state.claimedDaily30
        values[streakClaimsKey] = state.streakClaims
    }
}
