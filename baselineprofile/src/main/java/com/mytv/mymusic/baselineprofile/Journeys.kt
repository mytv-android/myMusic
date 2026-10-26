package com.mytv.mymusic.baselineprofile

import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern

const val PACKAGE_NAME = "com.mytv.mymusic"
private const val WAIT_MS = 15_000L

val isTv: Boolean by lazy {
    InstrumentationRegistry.getInstrumentation().context.packageManager
        .hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

private fun MacrobenchmarkScope.failJourney(message: String): Nothing {
    val context = InstrumentationRegistry.getInstrumentation().context
    val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "journey-failures")
    directory.mkdirs()
    val prefix = "failure-${System.currentTimeMillis()}"
    runCatching { device.dumpWindowHierarchy(File(directory, "$prefix.xml")) }
    runCatching { device.takeScreenshot(File(directory, "$prefix.png")) }
    throw AssertionError("$message; diagnostics: ${directory.absolutePath}/$prefix")
}

private fun MacrobenchmarkScope.requireObject(selector: BySelector, label: String): UiObject2 =
    device.wait(Until.findObject(selector), WAIT_MS) ?: failJourney("未找到$label")

private fun MacrobenchmarkScope.requireGone(selector: BySelector, label: String) {
    if (!device.wait(Until.gone(selector), WAIT_MS)) failJourney("${label}没有退出")
}

/** 只验证榜单页面已经打开；手机用顶栏刷新键，电视用头图/卡片的播放入口。 */
fun MacrobenchmarkScope.waitForChartsPage() {
    requireObject(By.pkg(PACKAGE_NAME).depth(0), "应用窗口")
    if (isTv) {
        // 电视的“刷新榜单”在列表底部，未滚动时不组合；以榜单播放入口确认页面。
        if (!device.wait(Until.hasObject(By.descStartsWith("播放").pkg(PACKAGE_NAME)), WAIT_MS)) {
            failJourney("电视榜单没有加载出内容")
        }
    } else {
        requireObject(By.desc("刷新榜单").pkg(PACKAGE_NAME), "榜单页面")
    }
    device.waitForIdle()
}

/** 测量榜单屏幕：页面之外还要求至少一个播放入口/榜单卡片已经呈现。 */
fun MacrobenchmarkScope.waitForCharts() {
    waitForChartsPage()
    // 头图曲目与榜单卡片可能分批到达，只要求内容已出现，不把“播放全部”当唯一入口。
    if (!device.wait(Until.hasObject(By.descStartsWith("播放").pkg(PACKAGE_NAME)), WAIT_MS)) {
        failJourney("榜单没有加载出内容")
    }
    device.waitForIdle()
}

/** 优先头图按钮；头图曲目暂缺时，用卡片上的播放入口启动同一榜单。 */
private fun MacrobenchmarkScope.chartPlayButton(): UiObject2 {
    val hero = if (isTv) {
        By.desc(Pattern.compile("播放(?:全部)?：.+")).pkg(PACKAGE_NAME)
    } else {
        By.text("播放全部").pkg(PACKAGE_NAME)
    }
    device.wait(Until.findObject(hero), 2_000)?.let { return it }
    val anyPlay = By.descStartsWith("播放").pkg(PACKAGE_NAME)
    device.wait(Until.findObject(anyPlay), WAIT_MS)?.let { return it }
    if (!isTv) {
        repeat(16) {
            try {
                val list = device.findObjects(By.pkg(PACKAGE_NAME).scrollable(true))
                    .maxByOrNull { it.visibleBounds.height() }
                    ?: failJourney("榜单缺少可滚动列表")
                // Compose 列表 bounds 包括顶栏下方的遮挡区域，手势须从内容区内部开始。
                list.setGestureMargins(device.displayWidth / 5, device.displayHeight / 4, device.displayWidth / 5, device.displayHeight / 5)
                val more = list.scroll(Direction.UP, 0.9f)
                device.waitForIdle()
                device.findObject(anyPlay)?.let { return it }
                if (!more) failJourney("已回到榜单顶部但没有播放入口")
            } catch (_: StaleObjectException) {
                device.waitForIdle()
            }
        }
    }
    failJourney("未找到榜单播放入口")
}

/** 可达边界不算失败，但整段滚动必须至少发生一次视口或焦点变化。 */
fun MacrobenchmarkScope.scrollCharts() {
    if (isTv) {
        // 头图先出现、卡片行随后才可聚焦；方向键必须等卡片行实际进入组合。
        val rowReady = device.wait(Until.hasObject(By.text("热门榜单").pkg(PACKAGE_NAME)), WAIT_MS) ||
            device.wait(Until.hasObject(By.text("平台热歌").pkg(PACKAGE_NAME)), WAIT_MS)
        if (!rowReady) failJourney("电视榜单没有可聚焦卡片行")
        device.waitForIdle()
        var moved = false
        repeat(4) { moved = moveTvFocus(down = true) || moved }
        repeat(4) { moved = moveTvFocus(down = false) || moved }
        if (!moved) failJourney("电视榜单方向键没有改变焦点")
        return
    }
    var moved = false
    repeat(3) { moved = fling(Direction.DOWN) || moved }
    repeat(3) { moved = fling(Direction.UP) || moved }
    if (!moved) failJourney("榜单没有发生滚动")
}

private fun MacrobenchmarkScope.focusSignature(): String {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    repeat(3) {
        device.waitForIdle()
        // UiObject2 每读一个属性都会 refresh，焦点动画可能已回收那个语义节点。
        // 直接读取系统 findFocus 返回的一次性快照，不重复刷新同一个旧节点。
        val node = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node != null && node.packageName?.toString() == PACKAGE_NAME && node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            return "${node.text}|${node.contentDescription}|$bounds"
        }
    }
    failJourney("无法取得电视输入焦点快照")
}

private fun MacrobenchmarkScope.moveTvFocus(down: Boolean): Boolean {
    val before = focusSignature()
    if (down) device.pressDPadDown() else device.pressDPadUp()
    device.waitForIdle()
    return before != focusSignature()
}

private fun visibleSignature(node: UiObject2): List<String> =
    node.findObjects(By.pkg(PACKAGE_NAME)).map { "${it.text}|${it.contentDescription}|${it.visibleBounds}" }

/** 仅重试失效节点；缺少容器或其他异常必须失败，不能采集错误页面。 */
private fun MacrobenchmarkScope.fling(direction: Direction): Boolean {
    repeat(3) {
        try {
            val list = device.findObjects(By.pkg(PACKAGE_NAME).scrollable(true))
                .maxByOrNull { it.visibleBounds.height() }
                ?: failJourney("页面缺少可滚动列表")
            val before = visibleSignature(list)
            // Compose 列表 bounds 包括顶栏下方的遮挡区域，手势须从内容区内部开始。
            list.setGestureMargins(device.displayWidth / 5, device.displayHeight / 4, device.displayWidth / 5, device.displayHeight / 5)
            list.fling(direction)
            device.waitForIdle()
            val after = device.findObjects(By.pkg(PACKAGE_NAME).scrollable(true))
                .maxByOrNull { it.visibleBounds.height() }
                ?: failJourney("滚动后列表消失")
            return before != visibleSignature(after)
        } catch (_: StaleObjectException) {
            device.waitForIdle()
        }
    }
    failJourney("列表节点持续失效，滚动未完成")
}

private fun playerMarker(): BySelector = if (isTv) By.desc("上一首") else By.desc("收起播放页")

fun MacrobenchmarkScope.startPlayback() {
    // 焦点已被滚动旅程移动，明确定位当前头图播放入口而非盲按确定。
    chartPlayButton().click()
    requireObject(playerMarker(), "播放页")
    Thread.sleep(1_500)
    device.pressBack()
    requireGone(playerMarker(), "播放页")
    if (!isTv) requireObject(By.descStartsWith("正在播放"), "迷你播放器")
    device.waitForIdle()
}

fun MacrobenchmarkScope.openAndClosePlayer() {
    if (isTv) {
        chartPlayButton().click()
    } else {
        requireObject(By.descStartsWith("正在播放"), "迷你播放器").click()
    }
    requireObject(playerMarker(), "播放页")
    Thread.sleep(1_200)
    device.pressBack()
    requireGone(playerMarker(), "播放页")
    if (!isTv) requireObject(By.descStartsWith("正在播放"), "迷你播放器")
    device.waitForIdle()
}

/** 定位导航而非页面内同名标题；电视兼容收起的图标语义与展开的文字。 */
private fun MacrobenchmarkScope.openTab(tab: String) {
    repeat(3) {
        try {
            val textChoices = device.findObjects(By.text(tab).pkg(PACKAGE_NAME))
            val entry = if (isTv) {
                (device.findObjects(By.desc(tab).pkg(PACKAGE_NAME)) + textChoices)
                    .filter { it.visibleBounds.centerX() < device.displayWidth / 4 }
                    .minByOrNull { it.visibleBounds.centerX() }
            } else {
                textChoices.maxByOrNull { it.visibleBounds.centerY() }
            }
            if (entry != null) {
                entry.click()
                device.waitForIdle()
                return
            }
        } catch (_: StaleObjectException) {
            // 导航展开/收起时重新查找，不能持有旧节点继续点击。
        }
        device.waitForIdle()
    }
    failJourney("找不到导航入口：$tab")
}

/** 手机和电视的每个入口都必须打开并验证内容，不能在电视上静默跳过。 */
fun MacrobenchmarkScope.visitTabs() {
    for (tab in listOf("歌单", "我的", "搜索", "榜单")) {
        openTab(tab)
        requireObject(By.pkg(PACKAGE_NAME).depth(0), "$tab 页面")
        var contentReady = true
        when (tab) {
            "歌单" -> if (isTv) {
                requireObject(By.desc("搜索歌单"), "电视歌单页搜索入口")
            } else {
                requireObject(By.text("推荐"), "歌单推荐入口")
            }
            "我的" -> if (isTv) {
                requireObject(By.descStartsWith("喜欢的歌，"), "电视音乐库收藏区域")
            } else {
                requireObject(By.text("自建歌单"), "我的歌单区域")
            }
            "搜索" -> {
                requireObject(By.clazz("android.widget.EditText"), "搜索框")
                if (!isTv) {
                    // 手机搜索首页自动打开系统键盘；电视使用应用内遥控器键盘。
                    device.pressBack()
                    requireObject(By.text("榜单").pkg(PACKAGE_NAME), "收起键盘后的底部导航")
                }
            }
            "榜单" -> {
                // 导航回归只要求页面打开；内容未到齐时不做滚动，避免在骨架屏上伪称成功。
                waitForChartsPage()
                contentReady = device.wait(
                    Until.hasObject(By.descStartsWith("播放").pkg(PACKAGE_NAME)),
                    5_000,
                )
            }
        }
        android.util.Log.i("MyMusicJourney", "Verified tab: $tab; tv=$isTv; content=$contentReady")
        // 电视的方向键移动由 scrollCharts 验证；这里不伪称执行触摸滚动。
        if (!isTv && contentReady) {
            val scrollable = device.findObjects(By.pkg(PACKAGE_NAME).scrollable(true))
            if (scrollable.isNotEmpty()) fling(Direction.DOWN)
        }
    }
}
