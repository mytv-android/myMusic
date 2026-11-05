package com.mytv.mymusic

import com.mytv.mymusic.data.ListenTaskState
import com.mytv.mymusic.data.ListenTasks
import com.mytv.mymusic.ui.library.claimableDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ListenTaskTest {
    private val today: String = LocalDate.now().toString()
    private val yesterday: String = LocalDate.now().minusDays(1).toString()

    private fun state(
        todaySeconds: Int = 0,
        streakDays: Int = 0,
        lastListenDay: String = "",
        claimedDaily15: String = "",
        claimedDaily30: String = "",
    ) = ListenTaskState(
        todayDate = today,
        todaySeconds = todaySeconds,
        streakDays = streakDays,
        lastListenDay = lastListenDay,
        claimedDaily15 = claimedDaily15,
        claimedDaily30 = claimedDaily30,
    )

    @Test
    fun listenThresholdsGateTheDailyTasks() {
        val below = state(todaySeconds = ListenTasks.DAILY_FIRST_TARGET_SECONDS - 1)
        assertFalse(below.firstDone)
        assertFalse(below.secondDone)

        val first = state(todaySeconds = ListenTasks.DAILY_FIRST_TARGET_SECONDS)
        assertTrue(first.firstDone)
        assertFalse(first.secondDone)

        val second = state(todaySeconds = ListenTasks.DAILY_SECOND_TARGET_SECONDS)
        assertTrue(second.firstDone)
        assertTrue(second.secondDone)
    }

    @Test
    fun claimableDaysCountsUnclaimedTasksOnly() {
        // 听满 30 分钟：两个每日任务都能领，共 2 天
        assertEquals(2, state(todaySeconds = ListenTasks.DAILY_SECOND_TARGET_SECONDS).claimableDays())
        // 领过 15 分钟那个就只剩 1 天
        assertEquals(
            1,
            state(
                todaySeconds = ListenTasks.DAILY_SECOND_TARGET_SECONDS,
                claimedDaily15 = today,
            ).claimableDays(),
        )
        // 两个都领过就没有了
        assertEquals(
            0,
            state(
                todaySeconds = ListenTasks.DAILY_SECOND_TARGET_SECONDS,
                claimedDaily15 = today,
                claimedDaily30 = today,
            ).claimableDays(),
        )
        // 没达标什么都没有
        assertEquals(0, state().claimableDays())
    }

    @Test
    fun streakRewardNeedsSevenConsecutiveDays() {
        val six = state(todaySeconds = ListenTasks.DAILY_SECOND_TARGET_SECONDS, streakDays = 6, lastListenDay = today)
        assertEquals(2, six.claimableDays())
        val seven = state(todaySeconds = ListenTasks.DAILY_SECOND_TARGET_SECONDS, streakDays = 7, lastListenDay = today)
        // 2 天每日 + 7 天连续
        assertEquals(9, seven.claimableDays())
    }

    @Test
    fun staleStreakDoesNotCount() {
        // 昨天之前达标过的连续记录，隔了一天就作废
        val stale = state(streakDays = 5, lastListenDay = LocalDate.now().minusDays(3).toString())
        assertEquals(0, stale.effectiveStreak())
        // 昨天刚达标，连续还认
        val fresh = state(streakDays = 5, lastListenDay = yesterday)
        assertEquals(5, fresh.effectiveStreak())
        val now = state(streakDays = 5, lastListenDay = today)
        assertEquals(5, now.effectiveStreak())
    }

    @Test
    fun vipActiveFollowsExpiry() {
        assertFalse(ListenTaskState().vipActive)
        assertFalse(ListenTaskState(vipUntil = System.currentTimeMillis() - 1_000).vipActive)
        assertTrue(ListenTaskState(vipUntil = System.currentTimeMillis() + 60_000).vipActive)
    }
}
