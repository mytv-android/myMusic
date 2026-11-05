package com.mytv.mymusic.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * 本机听歌计时：给「汽水天天送会员」的听歌任务当一个本地进度参考。
 * 真正的任务阈值、完成情况和免费 VIP 都以汽水服务端为准，见
 * [com.mytv.mymusic.data.native.SodaWelfare]，这里不存任何会员状态。
 */
data class ListenTaskState(
    /** [todaySeconds] 归属的日期（yyyy-MM-dd）；跨天后读出来要先对今天。 */
    val todayDate: String = "",
    /** 当天实际播放累计的秒数（跳转、暂停不计）。 */
    val todaySeconds: Int = 0,
) {
    val todayMinutes: Int get() = todaySeconds / 60
}

private val Context.listenTaskDataStore by preferencesDataStore("listen_tasks")

class ListenTaskStore(context: Context) {
    private val dataStore = context.listenTaskDataStore

    private val todayDateKey = stringPreferencesKey("today_date")
    private val todaySecondsKey = intPreferencesKey("today_seconds")

    /** 跨天就把当天时长归零，界面因此永远只看到「今天」听了多久。 */
    val state: Flow<ListenTaskState> = dataStore.data.map { values ->
        val stored = ListenTaskState(
            todayDate = values[todayDateKey] ?: "",
            todaySeconds = values[todaySecondsKey] ?: 0,
        )
        if (stored.todayDate == LocalDate.now().toString()) stored else ListenTaskState()
    }

    /** 播放中按进度差累计；[ms] 应只含真正播出来的时间。 */
    suspend fun addListening(ms: Long) {
        if (ms <= 0) return
        val today = LocalDate.now().toString()
        dataStore.edit { values ->
            val base = if ((values[todayDateKey] ?: "") == today) values[todaySecondsKey] ?: 0 else 0
            values[todayDateKey] = today
            values[todaySecondsKey] = (base + ms / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
}
