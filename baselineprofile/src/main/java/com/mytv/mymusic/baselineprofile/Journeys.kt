package com.mytv.mymusic.baselineprofile

import android.content.pm.PackageManager
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

const val PACKAGE_NAME = "com.mytv.mymusic"
private const val WAIT_MS = 15_000L

/** 电视用遥控器走同样的路径：没有触摸滚动，用方向键翻行。 */
val isTv: Boolean by lazy {
    InstrumentationRegistry.getInstrumentation().context.packageManager
        .hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/** 冷启动后等榜单真正加载出来（“播放全部”出现说明首屏数据已到）。 */
fun MacrobenchmarkScope.waitForCharts() {
    device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), WAIT_MS)
    device.wait(Until.hasObject(By.text("播放全部")), WAIT_MS)
    device.waitForIdle()
}

/** 榜单页滚动：手机快速上下甩动，电视按方向键逐行移动焦点。 */
fun MacrobenchmarkScope.scrollCharts() {
    if (isTv) {
        repeat(4) {
            device.pressDPadDown()
            Thread.sleep(500)
        }
        repeat(4) {
            device.pressDPadUp()
            Thread.sleep(500)
        }
        return
    }
    repeat(3) { fling(Direction.DOWN) }
    repeat(3) { fling(Direction.UP) }
}

/** 每次都重新找列表：LazyColumn 重组后旧节点会失效（StaleObjectException）。 */
private fun MacrobenchmarkScope.fling(direction: Direction) {
    runCatching {
        // 页面里还有横向的轮播和 chip 行，取最高的那个可滚动节点，才是竖向列表
        val list = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() } ?: return
        // 手势避开屏幕边缘，免得触发系统返回
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(direction)
    }
    device.waitForIdle()
}

/** 开始播放：手机点“播放全部”（会自动打开播放页，再收起到迷你播放器）；电视默认焦点就在“播放全部”。 */
fun MacrobenchmarkScope.startPlayback() {
    if (isTv) {
        device.pressDPadCenter()
        device.wait(Until.hasObject(By.desc("上一首")), WAIT_MS)
        Thread.sleep(1_500)
        device.pressBack()
        device.waitForIdle()
        return
    }
    device.findObject(By.text("播放全部"))?.click()
    device.wait(Until.hasObject(By.desc("收起播放页")), WAIT_MS)
    Thread.sleep(1_500)
    device.pressBack()
    device.wait(Until.hasObject(By.descStartsWith("正在播放")), WAIT_MS)
    device.waitForIdle()
}

/** 打开播放页再收起：手机从迷你播放器点开（封面共享元素转场），电视从抽屉的“正在播放”或“播放全部”进入。 */
fun MacrobenchmarkScope.openAndClosePlayer() {
    if (isTv) {
        device.pressDPadCenter()
        device.wait(Until.hasObject(By.desc("上一首")), WAIT_MS)
        Thread.sleep(1_500)
        device.pressBack()
        device.waitForIdle()
        return
    }
    val mini = device.wait(Until.findObject(By.descStartsWith("正在播放")), WAIT_MS) ?: return
    mini.click()
    device.wait(Until.hasObject(By.desc("收起播放页")), WAIT_MS)
    Thread.sleep(1_200)
    device.pressBack()
    device.wait(Until.hasObject(By.descStartsWith("正在播放")), WAIT_MS)
    device.waitForIdle()
}

/** 切换底部入口并滚一下，覆盖歌单、搜索、我的三个页面的首帧和滚动。 */
fun MacrobenchmarkScope.visitTabs() {
    if (isTv) return
    for (tab in listOf("歌单", "我的", "搜索", "榜单")) {
        runCatching { device.findObject(By.text(tab))?.click() }.getOrNull() ?: continue
        device.waitForIdle()
        Thread.sleep(1_500)
        fling(Direction.DOWN)
    }
}
