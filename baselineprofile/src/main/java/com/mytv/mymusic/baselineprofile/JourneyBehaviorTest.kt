package com.mytv.mymusic.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 单次行为回归，不采集帧时间，也不将 debug 包结果作为性能数据。 */
@RunWith(AndroidJUnit4::class)
class JourneyBehaviorTest {
    @Test
    fun criticalJourneysReachEveryExpectedScreen() {
        MacrobenchmarkScope(PACKAGE_NAME, false).apply {
            killProcess()
            pressHome()
            startActivityAndWait()
            waitForCharts()
            scrollCharts()
            startPlayback()
            openAndClosePlayer()
            visitTabs()
        }
    }

    @Test
    fun missingWindowFailsAndSavesDiagnostics() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "journey-failures")
        val started = System.currentTimeMillis()
        val scope = MacrobenchmarkScope(PACKAGE_NAME, false)
        scope.pressHome()
        val failure = runCatching { scope.waitForCharts() }.exceptionOrNull()
        assertTrue("缺少应用窗口必须使旅程失败", failure is AssertionError)
        assertTrue("失败应保留页面层级", directory.listFiles().orEmpty().any { it.extension == "xml" && it.lastModified() >= started && it.length() > 0 })
        assertTrue("失败应保留截图", directory.listFiles().orEmpty().any { it.extension == "png" && it.lastModified() >= started && it.length() > 0 })
    }
}
