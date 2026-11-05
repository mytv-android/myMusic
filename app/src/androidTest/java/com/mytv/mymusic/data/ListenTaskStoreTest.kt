package com.mytv.mymusic.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * 「天天听歌」账本的设备端测试：DataStore 落盘、任务领取、VIP 激活的真实路径。
 * 账本是应用真实的 DataStore，两个用例共享，所以断言都按“当前状态推期望”，重复跑也成立。
 * 第一次跑完会在本机留下 2 天 VIP 和半小时听歌记录，正好用来看福利中心的会员态界面。
 */
@RunWith(AndroidJUnit4::class)
class ListenTaskStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun listenClaimAndActivate() = runBlocking {
        val store = ListenTaskStore(context)
        val today = LocalDate.now().toString()

        // 听 21 分钟：跨过 15 分钟那条线（不断言 30 分钟那条，另一个用例可能先加过时间）
        store.addListening(21 * 60 * 1000L)
        assertTrue(store.state.first().firstDone)

        // 15 分钟任务：今天没领过才领得到，重复领返回 0
        val expect15 = if (store.state.first().claimedDaily15 != today) 1 else 0
        assertEquals(expect15, store.claim(ListenTasks.DAILY_15))
        assertEquals(0, store.claim(ListenTasks.DAILY_15))

        // 再听 10 分钟凑满 30，领第二个每日任务
        store.addListening(10 * 60 * 1000L)
        assertTrue(store.state.first().secondDone)
        val expect30 = if (store.state.first().claimedDaily30 != today) 1 else 0
        assertEquals(expect30, store.claim(ListenTasks.DAILY_30))

        // 激活：余额全部转成到期时间，之后从当前到期时间（或现在）往后续
        val before = store.state.first()
        assertTrue("余额至少含本次领到的 ${expect15 + expect30} 天", before.vipDays >= expect15 + expect30)
        if (before.vipDays > 0) {
            assertTrue(store.activateVip())
            val after = store.state.first()
            assertTrue(after.vipActive)
            assertEquals(0, after.vipDays)
            val span = after.vipUntil - System.currentTimeMillis()
            assertTrue("激活时长要覆盖余额，实际 $span", span >= before.vipDays * ListenTasks.DAY_MS - 60_000)
            // 余额空了再激活没意义
            assertFalse(store.activateVip())
        }
    }

    @Test
    fun listeningSurvivesNewStoreInstance() = runBlocking {
        // 落盘后重新建 store（等于换进程）还能读回来
        val store = ListenTaskStore(context)
        store.addListening(16 * 60 * 1000L)
        val fresh = ListenTaskStore(context)
        assertTrue(fresh.state.first().firstDone)
    }
}
