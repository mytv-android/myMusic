package com.mytv.mymusic

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import com.mytv.mymusic.playback.ScreensaverState
import com.mytv.mymusic.ui.MyMusicApp
import com.mytv.mymusic.widget.ChartWidgetWorker
import com.mytv.mymusic.widget.WidgetLinks
import com.mytv.mymusic.ui.components.LocalDarkBarsRequests
import com.mytv.mymusic.ui.theme.LayoutMode
import com.mytv.mymusic.ui.theme.MyMusicTheme
import com.mytv.mymusic.ui.theme.isTelevision

class MainActivity : ComponentActivity() {
    private val model get() = (application as com.mytv.mymusic.MyMusicApp).musicViewModel
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ViewModel 是进程级的，activity 退出后它还在：全新启动时收起上次留下的播放页
        model.onUiLaunched(fresh = savedInstanceState == null)
        if (savedInstanceState == null) {
            openWidgetTarget(intent)
            // 桌面上有榜单组件时顺带刷一次榜（上次刷新超过半小时才刷）
            ChartWidgetWorker.refreshOnOpen(this)
        }
        enableEdgeToEdge()
        // 三键导航下系统会在导航栏铺一层白色遮罩，盖住我们自己的磨砂底栏
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            val model = remember { model }
            val state by model.state.collectAsState()
            // 手动布局模式同时锁定屏幕方向；自动保持系统默认
            LaunchedEffect(state.appearance.layout) {
                requestedOrientation = when (state.appearance.layout) {
                    LayoutMode.Auto -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    LayoutMode.Phone -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    LayoutMode.Tablet -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    LayoutMode.Tv -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }
            // 电视固定深色；播放页、海报墙、详情页的海报视图固定深色，打开时系统栏图标跟着变浅
            val tvDevice = remember { isTelevision() }
            val darkBars = remember { mutableIntStateOf(0) }
            val dark = tvDevice ||
                state.appearance.layout == LayoutMode.Tv ||
                state.appearance.isDark(isSystemInDarkTheme()) ||
                ((state.playerOpen || state.posterOpen) && state.current != null) ||
                darkBars.intValue > 0
            SideEffect(dark) {
                // Android 6–7 的导航栏图标只能是白色，导航栏颜色固定取 darkScrim；全透明时浅色主题下按键看不见
                val navDarkScrim = if (android.os.Build.VERSION.SDK_INT < 26) Color.argb(0x80, 0x1b, 0x1b, 0x1b) else Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, navDarkScrim) { dark },
                )
                if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            }
            MyMusicTheme(
                appearance = state.appearance,
                coverSeed = state.coverSeed.takeIf { it != 0 }?.let { androidx.compose.ui.graphics.Color(it) },
            ) {
                CompositionLocalProvider(LocalDarkBarsRequests provides darkBars) { MyMusicApp(model) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openWidgetTarget(intent)
    }

    /** 桌面小组件点开 App 时带着目标页（播放页、搜索、某个榜单……），交给界面去跳。 */
    private fun openWidgetTarget(intent: Intent?) {
        val target = WidgetLinks.parse(intent) ?: return
        WidgetLinks.consume(intent)
        model.openFromWidget(target)
    }

    /**
     * 电视通常不当背景音乐播放器：用户主动离开（按 Home、切到别的应用、从最近任务切换）时才暂停，
     * 手机和平板保留后台播放。
     *
     * 这条回调本身说明不了「用户自己走了」：别的界面被拉到前台时它照样会来（除非对方设了
     * FLAG_ACTIVITY_NO_USER_ACTION），电视的屏保就是这么弹的；息屏、系统弹窗也会让 activity 走一遍
     * 生命周期，人其实还留在应用里。在这儿直接暂停，就是「放一会儿屏保一弹就不响了」。
     * 所以先延一手，等屏保的广播落地、看清是谁盖的再决定，见 [pauseOnLeave]。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pauseOnLeave(resumeOnReturn = true)
    }

    override fun onStop() {
        super.onStop()
        // 电视根页面连按两次返回是 finish()：activity 直接结束，不算「用户离开」，onUserLeaveHint 不会来。
        // 退出应用是用户明确要停，回前台不接着放
        if (isFinishing) pauseOnLeave(resumeOnReturn = false)
    }

    override fun onDestroy() {
        handler.removeCallbacks(leaveGuard)
        super.onDestroy()
    }

    private fun pauseOnLeave(resumeOnReturn: Boolean) {
        // 旋转等配置变更只是重建 activity，人还在应用里
        if (isChangingConfigurations) return
        // 退出应用是用户明确要停，回前台不接着放：屏保在不在盖着都停
        if (!resumeOnReturn) {
            handler.removeCallbacks(leaveGuard)
            model.onAppBackgrounded(false)
            return
        }
        // 延一下再判断。屏保弹上来时这条回调照样会来（别的界面被拉到前台就会给前台的 activity 报
        // 「用户离开」），而屏保的 DREAMING_STARTED 广播可能比它还晚到；人也可能是在屏保上按了 Home
        // （屏保收掉、人去桌面，那才真的要停）。等广播落地、看清是谁盖的再决定
        handler.removeCallbacks(leaveGuard)
        handler.postDelayed(leaveGuard, LEAVE_GUARD_MS)
    }

    /** [pauseOnLeave] 里延后的那一下：屏保还盖着就不停，人真去了外面才暂停。 */
    private val leaveGuard = Runnable {
        // 屏保、息屏还盖着界面：是被它挡住的，音乐接着放。人已经回到应用里（屏保一闪而过）也什么都不做
        if (ScreensaverState.covered || isFinishing || lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return@Runnable
        }
        model.onAppBackgrounded(true)
    }

    private companion object {
        /** 等屏保的 DREAMING_STARTED 广播落地的时间：那之前看不清是不是屏保盖的。 */
        const val LEAVE_GUARD_MS = 600L
    }
}
