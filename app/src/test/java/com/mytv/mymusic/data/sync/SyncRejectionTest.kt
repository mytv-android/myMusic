package com.mytv.mymusic.data.sync

import com.mytv.mymusic.data.local.LocalRefEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「不匹配」随同步走：写成 method "none"、置信度 1、平台和 ID 为空。
 * 用户的决定压过自动匹配；两个用户决定之间最后一次说了算；合并结果和先后顺序无关。
 */
class SyncRejectionTest {
    private val auto = SyncMatch("netease", "186016", confidence = 1f, method = SyncMatch.METHOD_TAG, matchedAt = "2026-11-09T10:00:00Z")
    private val audio = SyncMatch("netease", "186016", confidence = 0.92f, method = "audio", matchedAt = "2026-11-09T10:00:00Z")
    private val manualOld = SyncMatch("qq", "003", confidence = 1f, method = SyncMatch.METHOD_MANUAL, matchedAt = "2026-11-01T00:00:00Z")
    private val manualNew = manualOld.copy(id = "004", matchedAt = "2026-11-09T12:00:00Z")
    private val rejectedMid = SyncMatch.rejection("2026-11-05T00:00:00Z")

    private fun both(a: SyncMatch?, b: SyncMatch?): SyncMatch? {
        val ab = SyncRefMerge.match(a, b)
        val ba = SyncRefMerge.match(b, a)
        assertEquals("合并结果不能取决于哪边是本机", ab, ba)
        return ab
    }

    @Test
    fun rejectionWireFormat() {
        val r = SyncMatch.rejection("2026-11-09T00:00:00Z")
        assertEquals("none", r.method)
        assertEquals(1f, r.confidence)
        assertEquals("", r.provider)
        assertEquals("", r.id)
        assertTrue(r.rejected)
        assertTrue(SyncRefMerge.isUserDecision(r))
    }

    @Test
    fun newerRejectionBeatsOlderManual() {
        assertEquals(rejectedMid, both(manualOld, rejectedMid))
    }

    @Test
    fun rejectionBeatsAutoRegardlessOfTime() {
        // 自动匹配更晚、分数也满：机器的判断不能推翻用户点的“不匹配”
        assertEquals(rejectedMid, both(auto, rejectedMid))
        assertEquals(rejectedMid, both(audio, rejectedMid))
    }

    @Test
    fun newerManualBeatsOlderRejection() {
        // 拒绝之后又在「换一个匹配」里手选了一首：重新匹配赢
        assertEquals(manualNew, both(rejectedMid, manualNew))
    }

    @Test
    fun sameInstantDecisionsResolveDeterministically() {
        val rejectAt = SyncMatch.rejection(manualNew.matchedAt)
        val winner = both(rejectAt, manualNew)
        assertTrue(winner == rejectAt || winner == manualNew)
        // 三台设备不管按什么顺序合并，都得出同一个
        assertEquals(winner, both(both(rejectAt, auto), manualNew))
        assertEquals(winner, both(rejectAt, both(manualNew, auto)))
    }

    @Test
    fun nullSideKeepsTheOther() {
        assertEquals(rejectedMid, both(null, rejectedMid))
    }

    @Test
    fun refRowStoresRejectionAsNoMatch() {
        val row = LocalRefEntity(
            localId = "sha256:" + "ab".repeat(32),
            matchMethod = SyncMatch.METHOD_REJECTED,
            matchConfidence = 1f,
            matchedAt = 1_762_646_400_000,
        )
        assertTrue(row.rejected)
        assertFalse("拒绝不是匹配", row.matched)
        val wire = row.syncMatch()!!
        assertTrue(wire.rejected)
        assertEquals("", wire.provider)
        assertEquals(SyncTime.toMillis(wire.matchedAt), row.matchedAt)

        // 旧数据里平台和 ID 还留着、方法却是 none：照样当成没有匹配
        val stale = row.copy(matchProvider = "netease", matchId = "186016")
        assertFalse(stale.matched)
        assertTrue(stale.syncMatch()!!.rejected)
    }
}
