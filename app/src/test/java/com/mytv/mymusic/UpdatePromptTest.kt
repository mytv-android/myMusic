package com.mytv.mymusic

import com.mytv.mymusic.data.update.UpdatePhase
import com.mytv.mymusic.data.update.UpdatePrompt
import com.mytv.mymusic.data.update.updatePromptFor
import org.junit.Assert.assertEquals
import org.junit.Test

/** 更新提示：有新版本就弹窗，用户点过「暂不更新」后只留提醒，下载和安装过程中不弹。 */
class UpdatePromptTest {
    @Test
    fun newVersionPopsDialog() {
        assertEquals(
            UpdatePrompt.Dialog,
            updatePromptFor(UpdatePhase.Available, versionCode = 130, postponed = 0),
        )
    }

    @Test
    fun postponedVersionOnlyReminds() {
        assertEquals(
            UpdatePrompt.Reminder,
            updatePromptFor(UpdatePhase.Available, versionCode = 130, postponed = 130),
        )
    }

    @Test
    fun newerVersionPopsAgainAfterPostpone() {
        assertEquals(
            UpdatePrompt.Dialog,
            updatePromptFor(UpdatePhase.Available, versionCode = 131, postponed = 130),
        )
    }

    @Test
    fun quietWhileDownloadingVerifyingOrReady() {
        for (phase in listOf(UpdatePhase.Downloading, UpdatePhase.Verifying, UpdatePhase.Ready)) {
            assertEquals(
                phase.name,
                UpdatePrompt.None,
                updatePromptFor(phase, versionCode = 130, postponed = 0),
            )
        }
    }

    @Test
    fun quietWithoutInfo() {
        assertEquals(
            UpdatePrompt.None,
            updatePromptFor(UpdatePhase.Available, versionCode = null, postponed = 0),
        )
    }

    @Test
    fun quietOnIdleAndError() {
        for (phase in listOf(UpdatePhase.Idle, UpdatePhase.Checking, UpdatePhase.Error)) {
            assertEquals(
                phase.name,
                UpdatePrompt.None,
                updatePromptFor(phase, versionCode = 130, postponed = 0),
            )
        }
    }
}
