package com.mytv.mymusic.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「汽水天天送会员」的本地听歌计时：只存当天秒数，跨天归零，重启进程还能读回来。
 * 账本是应用真实的 DataStore，用例之间共享，所以断言都按“当前状态推期望”，重复跑也成立。
 */
@RunWith(AndroidJUnit4::class)
class ListenTaskStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun listeningAccumulatesAndPersists() = runBlocking {
        val store = ListenTaskStore(context)
        val before = store.state.first().todaySeconds

        store.addListening(11 * 60 * 1000L)
        val after = store.state.first()
        assertEquals(before + 11 * 60, after.todaySeconds)
        // 设备上今天可能已经听过一段：分钟数也按“之前的秒数 + 11 分钟”推，不能写死 11
        assertEquals((before + 11 * 60) / 60, after.todayMinutes)

        // 落盘后重新建 store（等于换进程）还能读回来
        val fresh = ListenTaskStore(context)
        assertTrue(fresh.state.first().todaySeconds >= after.todaySeconds)
    }

    @Test
    fun negativeListeningIgnored() = runBlocking {
        val store = ListenTaskStore(context)
        val before = store.state.first().todaySeconds
        store.addListening(0L)
        store.addListening(-5_000L)
        assertEquals(before, store.state.first().todaySeconds)
    }
}
