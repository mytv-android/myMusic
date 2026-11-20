package com.mytv.mymusic.data.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LocalWorkRulesTest {
    private fun row(
        id: String = "sha256:a",
        tagsRead: Boolean = true,
        missing: Boolean = false,
        matchMethod: String = "",
    ) = LocalScanDiff.merge(
        previous = null,
        file = ScannedFile(
            uri = "content://media/1",
            origin = LOCAL_ORIGIN_MEDIA,
            displayName = "a.mp3",
            relativePath = "Music/",
            size = 100,
            modifiedAt = 1,
            mimeType = "audio/mpeg",
        ),
        localId = id,
        now = 1,
    ).copy(tagsRead = tagsRead, missing = missing, matchMethod = matchMethod)

    @Test
    fun mediaStoreDisableAndPermissionRevocationBothCloseSource() {
        fun readable(enabled: Boolean, granted: Boolean) = LocalSourceAccess.isReadable(
            origin = LOCAL_ORIGIN_MEDIA,
            mediaStoreEnabled = enabled,
            audioPermissionGranted = granted,
            configuredFolders = emptySet(),
            folderGranted = { false },
        )
        assertTrue(readable(enabled = true, granted = true))
        assertFalse(readable(enabled = false, granted = true))
        assertFalse(readable(enabled = true, granted = false))
    }

    @Test
    fun folderRemovalAndGrantRevocationBothCloseSource() {
        val folder = "content://tree/root"
        fun readable(configured: Boolean, granted: Boolean) = LocalSourceAccess.isReadable(
            origin = folder,
            mediaStoreEnabled = true,
            audioPermissionGranted = true,
            configuredFolders = if (configured) setOf(folder) else emptySet(),
            folderGranted = { granted },
        )
        assertTrue(readable(configured = true, granted = true))
        assertFalse(readable(configured = false, granted = true))
        assertFalse(readable(configured = true, granted = false))
    }

    @Test
    fun sourceChangeCancelsScanAndMatchingJobs() = runTest {
        val scan = launch { awaitCancellation() }
        val matching = launch { awaitCancellation() }
        LocalSourceWork.cancel(scan, matching)
        scan.join()
        matching.join()
        assertTrue(scan.isCancelled)
        assertTrue(matching.isCancelled)
    }

    @Test
    fun unloadedIndexAndMissingIndexItemFailClosed() {
        val row = row()
        assertFalse(LocalMatchEligibility.isEligible(row, false, null, emptySet(), false))
        assertFalse(LocalMatchEligibility.isEligible(row, true, null, emptySet(), false))
        assertFalse(LocalMatchEligibility.isEligible(row, true, false, emptySet(), false))
        assertTrue(LocalMatchEligibility.isEligible(row, true, true, emptySet(), false))
    }

    @Test
    fun matchingEligibilityRejectsIncompleteBlockedOrAlreadyAttemptedRows() {
        assertFalse(LocalMatchEligibility.isEligible(row(tagsRead = false), true, true, emptySet(), false))
        assertFalse(LocalMatchEligibility.isEligible(row(missing = true), true, true, emptySet(), false))
        assertFalse(LocalMatchEligibility.isEligible(row(matchMethod = LocalMatchMethod.NONE), true, true, emptySet(), false))
        assertFalse(LocalMatchEligibility.isEligible(row(), true, true, setOf("sha256:a"), false))
        assertFalse(LocalMatchEligibility.isEligible(row(), true, true, emptySet(), true))
    }

    @Test
    fun matchingBoundaryContainsOrdinaryException() = runTest {
        var reported: Exception? = null
        LocalMatchExceptionBoundary.run(onError = { reported = it }) {
            throw IOException("broken row")
        }
        assertEquals("broken row", reported?.message)
    }

    @Test
    fun matchingBoundaryRethrowsTheSameCancellation() = runTest {
        val cancel = CancellationException("source revoked")
        val thrown = runCatching {
            LocalMatchExceptionBoundary.run(onError = {}) { throw cancel }
        }.exceptionOrNull()
        assertSame(cancel, thrown)
    }
}
