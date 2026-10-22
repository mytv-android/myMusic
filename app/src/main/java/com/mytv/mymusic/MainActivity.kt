package com.mytv.mymusic

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mytv.mymusic.ui.MusicViewModel
import com.mytv.mymusic.ui.MyMusicApp
import com.mytv.mymusic.ui.theme.MyMusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 三键导航下系统会在导航栏铺一层白色遮罩，盖住我们自己的磨砂底栏
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            val model: MusicViewModel = viewModel()
            val state by model.state.collectAsState()
            // 播放页固定深色，打开时系统栏图标跟着变浅
            val dark = state.appearance.isDark(isSystemInDarkTheme()) || (state.playerOpen && state.current != null)
            LaunchedEffect(dark) {
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
                MyMusicApp(model)
            }
        }
    }
}
