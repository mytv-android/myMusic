package com.mytv.mymusic.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.mytv.mymusic.data.ListeningMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

/** 歌单组件放哪一张：我喜欢、最近播放或一张自建歌单。 */
sealed interface WidgetListSource {
    data object Favorites : WidgetListSource
    data object History : WidgetListSource
    data class Local(val id: String) : WidgetListSource

    val token: String
        get() = when (this) {
            Favorites -> "favorites"
            History -> "history"
            is Local -> "local:$id"
        }

    companion object {
        fun parse(token: String?): WidgetListSource = when {
            token == "history" -> History
            token != null && token.startsWith("local:") && token.length > 6 -> Local(token.substring(6))
            else -> Favorites
        }
    }
}

/** 一个组件实例的设置（按 appWidgetId 存）。没配过的项用默认值，添加组件时可以跳过配置页。 */
data class WidgetConfig(
    val background: WidgetBackground = WidgetBackground.Solid,
    /** 歌词组件：有翻译时显示在当前句下面。 */
    val translation: Boolean = true,
    /** 听歌模式组件绑定的平台和模式；空着时用第一个已登录平台的第一个模式。 */
    val modeProvider: String = "",
    val mode: ListeningMode? = null,
    /** 榜单组件：平台和榜单；空着时用榜单页当前的平台和第一张榜。 */
    val chartSource: String = "",
    val chartId: String = "",
    val chartName: String = "",
    val list: WidgetListSource = WidgetListSource.Favorites,
)

/** 组件设置存在一个 SharedPreferences 里：只有几个键，组件和配置页在同一进程，不值得为它开 DataStore。 */
object WidgetPrefs {
    private const val FILE = "widgets"
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** 每次写入加一，组件读它来重组。 */
    val version = MutableStateFlow(0)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context, appWidgetId: Int): WidgetConfig {
        val p = prefs(context)
        val k = "$appWidgetId."
        return WidgetConfig(
            background = WidgetBackground.entries.find { it.name == p.getString(k + "bg", null) } ?: WidgetBackground.Solid,
            translation = p.getBoolean(k + "translation", true),
            modeProvider = p.getString(k + "modeProvider", null).orEmpty(),
            mode = p.getString(k + "mode", null)?.let { runCatching { json.decodeFromString<ListeningMode>(it) }.getOrNull() },
            chartSource = p.getString(k + "chartSource", null).orEmpty(),
            chartId = p.getString(k + "chartId", null).orEmpty(),
            chartName = p.getString(k + "chartName", null).orEmpty(),
            list = WidgetListSource.parse(p.getString(k + "list", null)),
        )
    }

    fun put(context: Context, appWidgetId: Int, config: WidgetConfig) {
        val k = "$appWidgetId."
        prefs(context).edit {
            putString(k + "bg", config.background.name)
            putBoolean(k + "translation", config.translation)
            putString(k + "modeProvider", config.modeProvider)
            config.mode?.let { putString(k + "mode", json.encodeToString(ListeningMode.serializer(), it)) } ?: remove(k + "mode")
            putString(k + "chartSource", config.chartSource)
            putString(k + "chartId", config.chartId)
            putString(k + "chartName", config.chartName)
            putString(k + "list", config.list.token)
        }
        version.value++
    }

    fun remove(context: Context, appWidgetId: Int) {
        val k = "$appWidgetId."
        val p = prefs(context)
        p.edit { p.all.keys.filter { it.startsWith(k) }.forEach(::remove) }
    }
}
