package com.mytv.mymusic.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProcessingCheckpointsTest {
    private class MapStorage : LocalCheckpointStorage {
        val values = LinkedHashMap<String, String>()
        var acceptsWrites = true

        override fun all(): Map<String, String> = values.toMap()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String): Boolean {
            if (!acceptsWrites) return false
            values[key] = value
            return true
        }

        override fun remove(key: String): Boolean {
            if (!acceptsWrites) return false
            values.remove(key)
            return true
        }
    }

    @Test
    fun interruptedNativeAttemptIsQuarantinedAfterRestart() {
        val storage = MapStorage()
        val firstProcess = LocalProcessingCheckpoints(storage)
        val attempt = firstProcess.begin("sha256:a", LocalProcessingStage.Tags)
        assertNotNull(attempt)
        // 不调用 complete/fail/cancel，模拟 native signal 让整个进程直接消失。

        val restarted = LocalProcessingCheckpoints(storage)
        val issue = restarted.issue("sha256:a", LocalProcessingStage.Tags)
        assertEquals(LocalProcessingIssueKind.CrashQuarantine, issue?.kind)
        assertTrue(restarted.isBlocked("sha256:a", LocalProcessingStage.Tags))
        assertNull("启动重扫不能再次进入同一首", restarted.begin("sha256:a", LocalProcessingStage.Tags))

        restarted.clear(issue!!) // 用户显式重新扫描时才解除
        assertNotNull(restarted.begin("sha256:a", LocalProcessingStage.Tags))
    }

    @Test
    fun ordinaryFailureStaysDistinctFromCrashAndNeedsExplicitRetry() {
        val storage = MapStorage()
        val firstProcess = LocalProcessingCheckpoints(storage)
        val attempt = firstProcess.begin("sha256:a", LocalProcessingStage.Audio)!!
        firstProcess.fail(attempt)

        val restarted = LocalProcessingCheckpoints(storage)
        val issue = restarted.issue("sha256:a", LocalProcessingStage.Audio)
        assertEquals(LocalProcessingIssueKind.OrdinaryFailure, issue?.kind)
        assertNull(restarted.begin("sha256:a", LocalProcessingStage.Audio))

        restarted.clear(issue!!)
        assertNotNull(restarted.begin("sha256:a", LocalProcessingStage.Audio))
    }

    @Test
    fun normalCompletionAndCancellationDoNotBecomeCrashQuarantine() {
        val storage = MapStorage()
        val firstProcess = LocalProcessingCheckpoints(storage)
        firstProcess.complete(firstProcess.begin("sha256:a", LocalProcessingStage.Tags)!!)
        firstProcess.cancel(firstProcess.begin("sha256:a", LocalProcessingStage.Audio)!!)

        val restarted = LocalProcessingCheckpoints(storage)
        assertTrue(restarted.issues().isEmpty())
        assertFalse(restarted.isBlocked("sha256:a", LocalProcessingStage.Tags))
        assertFalse(restarted.isBlocked("sha256:a", LocalProcessingStage.Audio))
    }

    @Test
    fun checkpointPersistenceFailureFailsClosedBeforeNativeCall() {
        val storage = MapStorage().apply { acceptsWrites = false }
        val checkpoints = LocalProcessingCheckpoints(storage)
        assertNull(checkpoints.begin("sha256:a", LocalProcessingStage.Tags))
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun staleAttemptCannotFinishANewerAttempt() {
        val storage = MapStorage()
        val checkpoints = LocalProcessingCheckpoints(storage)
        val stale = checkpoints.begin("sha256:a", LocalProcessingStage.Audio)!!
        storage.remove(stale.key)
        val current = checkpoints.begin("sha256:a", LocalProcessingStage.Audio)!!

        checkpoints.complete(stale)
        checkpoints.fail(stale)
        assertEquals(current.runningValue, storage.get(current.key))
        assertTrue(checkpoints.isBlocked("sha256:a", LocalProcessingStage.Audio))
    }
}
