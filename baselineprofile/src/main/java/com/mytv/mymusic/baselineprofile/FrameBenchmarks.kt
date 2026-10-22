package com.mytv.mymusic.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 三条关键路径的帧时间：冷启动、榜单滚动、打开播放页。
 * 每条都比较“不预编译”和“Baseline Profile”，看 Profile 的收益。
 *
 * 运行：`ANDROID_SERIAL=<设备> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mytv.mymusic.baselineprofile.FrameBenchmarks`
 * 结果在 baselineprofile/build/outputs/connected_android_test_additional_output/ 下的 *-benchmarkData.json。
 */
@RunWith(Parameterized::class)
class FrameBenchmarks(private val compilation: CompilationMode) {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        waitForCharts()
    }

    @Test
    fun chartsScroll() = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForCharts()
        },
    ) {
        scrollCharts()
    }

    @Test
    fun openPlayer() = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForCharts()
            // 手机：先开始播放，测量从迷你播放器打开；电视：每次从“播放全部”进入
            if (!isTv) startPlayback()
        },
    ) {
        openAndClosePlayer()
    }

    companion object {
        private const val ITERATIONS = 5

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): List<CompilationMode> = listOf(
            CompilationMode.None(),
            CompilationMode.Partial(BaselineProfileMode.Require),
        )
    }
}
