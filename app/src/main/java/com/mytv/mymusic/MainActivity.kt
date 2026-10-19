package com.mytv.mymusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicViewModel
import com.mytv.mymusic.ui.MyMusicApp
import com.mytv.mymusic.ui.song

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: MusicViewModel = viewModel()
            val state by model.state.collectAsState()
            val favorites by model.favorites.collectAsState()
            val history by model.history.collectAsState()
            val downloads by model.downloads.collectAsState()
            val playlists by model.localPlaylists.collectAsState()
            val dark = when (state.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            val context = LocalContext.current
            val dynamic = android.os.Build.VERSION.SDK_INT >= 31
            val colors = when {
                dynamic && dark -> dynamicDarkColorScheme(context)
                dynamic -> dynamicLightColorScheme(context)
                dark -> darkColorScheme(primary = Color(0xFF52DBC4), background = Color(0xFF101817))
                else -> lightColorScheme(primary = Color(0xFF006B5F), background = Color(0xFFF7FBF9))
            }
            MaterialTheme(colors) { Surface { MyMusicApp(state, calculateWindowSizeClass(this).widthSizeClass, favorites.map { it.song() }, history.map { it.song() }, downloads, playlists, MusicActions(model)) } }
        }
    }
}
