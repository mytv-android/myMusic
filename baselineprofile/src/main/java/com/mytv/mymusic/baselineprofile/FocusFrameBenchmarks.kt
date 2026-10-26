package com.mytv.mymusic.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test

/**
 * 聚焦测量：只跑生产使用的 Baseline Profile 编译模式，用于同一设备上的前后交替对照。
 * 完整三场景、两种编译模式仍由 FrameBenchmarks 提供。
 */
class FocusFrameBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun chartsScroll() = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForCharts()
        },
    ) {
        scrollCharts()
    }
}
