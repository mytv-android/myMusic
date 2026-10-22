package com.mytv.mymusic.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile 采集：冷启动、榜单滚动、打开播放页，再加上其他三个入口。
 *
 * 重新生成（连接 API 33+ 或已 root 的设备 / 模拟器）：
 * `ANDROID_SERIAL=<设备> ./gradlew :app:generateReleaseBaselineProfile -Pandroid.testInstrumentationRunnerArguments.class=com.mytv.mymusic.baselineprofile.BaselineProfileGenerator`
 * 产物写入 app/src/release/generated/baselineProfiles/。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** Startup Profile 只含冷启动路径，越精简 R8 的启动布局优化越有效。 */
    @Test
    fun startup() = rule.collect(packageName = PACKAGE_NAME, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        waitForCharts()
    }

    @Test
    fun criticalJourneys() = rule.collect(packageName = PACKAGE_NAME) {
        pressHome()
        startActivityAndWait()
        waitForCharts()
        scrollCharts()
        startPlayback()
        openAndClosePlayer()
        visitTabs()
    }
}
