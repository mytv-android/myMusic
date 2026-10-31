package com.mytv.mymusic.widget

import android.content.Context
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 组件上的点击。播放、切歌、收藏、榜单 / 歌单 / 听歌模式起播都在后台做（Glance 的 ActionCallback 跑在广播接收器里），
 * 不打开 App；点组件属于用户操作，系统允许这时从后台拉起播放服务。封面、标题、搜索这些打开 App 的指定页面。
 */
object WidgetActions {
    internal val Command = ActionParameters.Key<String>("command")
    internal val WidgetId = ActionParameters.Key<Int>("widgetId")
    internal val StartKey = ActionParameters.Key<String>("startKey")
    internal val Shuffle = ActionParameters.Key<Boolean>("shuffle")

    fun transport(command: String): Action = actionRunCallback<TransportCallback>(actionParametersOf(Command to command))

    val play: Action get() = transport("play")

    fun transportActions(live: Boolean, hasPrevious: Boolean, hasNext: Boolean) = TransportActions(
        play = play,
        previous = if (hasPrevious || !live) transport("previous") else null,
        next = if (hasNext || !live) transport("next") else null,
    )

    val favorite: Action get() = actionRunCallback<FavoriteCallback>()

    /** 从 [startKey] 那首开始放整张榜单（[chart]）或歌单；[startKey] 为空时从第一首，[shuffle] 时随机。 */
    fun playList(appWidgetId: Int, chart: Boolean, startKey: String? = null, shuffle: Boolean = false): Action =
        actionRunCallback<PlayListCallback>(
            actionParametersOf(
                WidgetId to appWidgetId,
                Command to if (chart) "chart" else "list",
                StartKey to startKey.orEmpty(),
                Shuffle to shuffle,
            ),
        )

    fun playMode(appWidgetId: Int): Action = actionRunCallback<PlayModeCallback>(actionParametersOf(WidgetId to appWidgetId))

    val refreshCharts: Action get() = actionRunCallback<RefreshChartsCallback>()

    fun open(context: Context, target: WidgetTarget): Action = actionStartActivity(WidgetLinks.intent(context, target))
}

private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

/** 放不出来时（没网、音源失效）给个提示：组件上看不到 App 里的错误条。 */
private suspend fun notifyFailure(app: MyMusicApp, fallback: String) = onMain {
    val notice = app.musicViewModel.state.value.playbackNotice.ifBlank { fallback }
    Toast.makeText(app, notice, Toast.LENGTH_SHORT).show()
}

class TransportCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val command = parameters[WidgetActions.Command] ?: return
        val app = context.applicationContext as MyMusicApp
        val ok = onMain { app.musicViewModel.widgetTransport(command) }
        if (!ok && command == "play") notifyFailure(app, "暂时放不了这首")
    }
}

class FavoriteCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as MyMusicApp
        onMain { app.musicViewModel.toggleFavoriteFromWidget() }
    }
}

class PlayListCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as MyMusicApp
        val id = parameters[WidgetActions.WidgetId] ?: return
        val chart = parameters[WidgetActions.Command] == "chart"
        val songs = if (chart) chartSongs(app, id) else listSongs(app, id)
        if (songs.isEmpty()) return
        val start = parameters[WidgetActions.StartKey]?.takeIf { it.isNotBlank() }
        val shuffle = parameters[WidgetActions.Shuffle] == true
        val ok = onMain { app.musicViewModel.playListFromWidget(songs, start, shuffle) }
        if (!ok) notifyFailure(app, "暂时放不了这首")
    }

    private fun chartSongs(app: MyMusicApp, appWidgetId: Int): List<Song> {
        val target = ChartStore.target(app, WidgetPrefs.get(app, appWidgetId)) ?: return emptyList()
        return ChartStore.read(app, target.source, target.id)?.songs.orEmpty()
    }

    private suspend fun listSongs(app: MyMusicApp, appWidgetId: Int): List<Song> {
        val data = WidgetData.get(app)
        return when (val source = WidgetPrefs.get(app, appWidgetId).list) {
            WidgetListSource.Favorites -> data.favorites.value
            WidgetListSource.History -> data.history.value
            is WidgetListSource.Local -> withContext(Dispatchers.IO) {
                app.database.library().entriesOnce(source.id).mapNotNull { runCatching { listJson.decodeFromString<Song>(it.payload) }.getOrNull() }
            }
        }
    }

    private companion object {
        val listJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

class PlayModeCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as MyMusicApp
        val id = parameters[WidgetActions.WidgetId] ?: return
        val data = WidgetData.get(app)
        val settings = data.settings.value ?: return
        val (provider, mode) = ModeStore.resolve(app, WidgetPrefs.get(app, id), settings.cookies, ModeStore.providers(app)) ?: return
        val ok = onMain { app.musicViewModel.playModeFromWidget(provider, mode) }
        if (ok) return
        val notice = onMain { app.musicViewModel.state.value.playbackNotice }
        // 平台说要登录：组件换成“去登录”
        if ("登录" in notice) ModeStore.markLoginRequired(mode.id) else notifyFailure(app, "「${mode.name}」暂时放不了")
    }
}

class RefreshChartsCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ChartWidgetWorker.refreshNow(context)
    }
}
