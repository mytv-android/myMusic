package com.mytv.mymusic.widget

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.mytv.mymusic.MyMusicApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 组件选择器里的预览分三代：Android 11 及以下用 previewImage 的 PNG，12 起用 previewLayout，
 * 15 起再发布 Glance 实时生成的预览（[MusicWidget.Preview]，跟 App 的配色走）。
 * 系统对发布次数限流，每个版本只发一次；被限流的下次启动再试。
 */
object WidgetPreviews {
    private const val KEY = "previews.version"

    suspend fun publishIfNeeded(app: MyMusicApp) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val prefs = app.getSharedPreferences("widgets", Context.MODE_PRIVATE)
        val version = runCatching { PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0)) }.getOrDefault(0L)
        if (prefs.getLong(KEY, -1L) == version) return
        // 预览用 App 当前的配色：等设置读出来
        withTimeoutOrNull(5_000) { WidgetData.get(app).settings.first { it != null } }
        val manager = GlanceAppWidgetManager(app)
        var done = true
        for (kind in WidgetKind.entries) {
            val result = runCatching { manager.setWidgetPreviews(kind.receiver) }.getOrNull()
            if (result != GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) done = false
        }
        if (done) prefs.edit { putLong(KEY, version) }
    }
}
