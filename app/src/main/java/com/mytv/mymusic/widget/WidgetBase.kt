package com.mytv.mymusic.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.os.BundleCompat
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.LocalAppWidgetOptions
import androidx.compose.runtime.collectAsState
import com.mytv.mymusic.BackgroundWork
import com.mytv.mymusic.MainActivity
import com.mytv.mymusic.data.AppSettings

/**
 * 六款组件的共同部分：SizeMode.Exact（在 Composable 里按 LocalSize 自己挑档，每次只带一份封面位图）、
 * 开始组合前等设置和播放状态就绪、读这个实例的设置和当前配色。
 */
abstract class MusicWidget(protected val kind: WidgetKind) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetUpdater.ensureStarted(context)
        val data = WidgetData.get(context)
        data.awaitReady()
        val appWidgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)
        val fallback = WidgetColors.palette(context, data.settings.value ?: AppSettings(), 0)
        provideContent {
            // 会话活着时它自己跟着下面这些 StateFlow 重组推送，WidgetUpdater 就不再重复推；会话超时关掉后由它重新拉起
            DisposableEffect(appWidgetId) {
                WidgetUpdater.sessionStarted(appWidgetId)
                onDispose { WidgetUpdater.sessionEnded(appWidgetId) }
            }
            val palette by data.palette.collectAsState()
            val prefsVersion by WidgetPrefs.version.collectAsState()
            // Android 12 以下桌面不会自己按夜间模式挑颜色，系统切深浅后要重组（DayNight 颜色在推送时定）
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) data.night.collectAsState().value
            val config = remember(prefsVersion) { WidgetPrefs.get(context, appWidgetId) }
            CompositionLocalProvider(LocalWidgetKind provides kind) {
                Content(appWidgetId, palette ?: fallback, config)
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val data = WidgetData.get(context)
        val palette = WidgetColors.palette(context, data.settings.value ?: AppSettings(), 0)
        provideContent { Preview(palette) }
    }

    @Composable
    abstract fun Content(appWidgetId: Int, palette: WidgetPalette, config: WidgetConfig)

    /** Android 15 起组件选择器里的实时预览：示例数据，不读真实的播放状态。 */
    @Composable
    abstract fun Preview(palette: WidgetPalette)

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        runCatching { GlanceAppWidgetManager(context).getAppWidgetId(glanceId) }.getOrNull()?.let {
            WidgetPrefs.remove(context, it)
            WidgetUpdater.forget(it)
        }
        super.onDelete(context, glanceId)
    }
}

/** 接收器：桌面一放上组件就开始监听播放状态。 */
abstract class MusicWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Glance 的推送和重组走 WorkManager，DE 上下文里起不来：这里直接不接，别把进程带崩
        if (!BackgroundWork.available(context)) return
        WidgetUpdater.ensureStarted(context)
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}

class NowPlayingWidgetReceiver : MusicWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

class LyricsWidgetReceiver : MusicWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LyricsWidget()
}

class ModeWidgetReceiver : MusicWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ModeWidget()
}

class ChartWidgetReceiver : MusicWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChartWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ChartWidgetWorker.schedule(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // 新放上的榜单组件还没有缓存：马上取一次
        if (appWidgetIds.any { id -> WidgetPrefs.get(context, id).let { ChartStore.target(context, it) }?.let { ChartStore.read(context, it.source, it.id) } == null }) {
            ChartWidgetWorker.refreshNow(context)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        ChartWidgetWorker.cancel(context)
    }
}

class ListWidgetReceiver : MusicWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ListWidget()
}

class ToolsWidgetReceiver : MusicWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ToolsWidget()
}

/**
 * 这个实例现在会显示的所有尺寸，和 SizeMode.Exact 下 Glance 各组合一份的那几个一致：Android 12 起桌面直接给尺寸表，
 * 更早的按竖屏（最小宽 × 最大高）、横屏（最大宽 × 最小高）两份。拿不到时只算当前这份。
 */
@Composable
fun widgetSizes(): List<DpSize> {
    val options = LocalAppWidgetOptions.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val sizes = BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        if (!sizes.isNullOrEmpty()) return sizes.map { DpSize(it.width.dp, it.height.dp) }
    }
    val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
    val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
    val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
    val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
    if (minW == 0 || maxW == 0 || minH == 0 || maxH == 0) return listOf(LocalSize.current)
    return listOf(DpSize(minW.dp, maxH.dp), DpSize(maxW.dp, minH.dp))
}

/** 打开 App 首页（空状态的“打开 myMusic”）。 */
@Composable
fun launchAppAction(): Action {
    val context = LocalContext.current
    return actionStartActivity(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
    )
}
