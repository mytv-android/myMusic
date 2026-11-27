package com.mytv.mymusic.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mytv.mymusic.aggregate.AggregationSettings
import com.mytv.mymusic.data.local.LocalMusicSettings
import com.mytv.mymusic.data.local.LocalTrackDao
import com.mytv.mymusic.data.local.LocalRefDao
import com.mytv.mymusic.data.local.LocalRefEntity
import com.mytv.mymusic.data.local.LocalRemoteEntity
import com.mytv.mymusic.data.local.LocalTrackEntity
import com.mytv.mymusic.data.local.LocalUploadDao
import com.mytv.mymusic.data.local.LocalUploadEntity
import com.mytv.mymusic.data.subsonic.SubsonicTrackDao
import com.mytv.mymusic.data.subsonic.SubsonicTrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore by preferencesDataStore("mymusic")

// 每首歌的随机结果和速度单独一个文件：切一首歌写一次，放进主设置会让整个界面跟着收到一次新设置
private val Context.lyricMemoryStore by preferencesDataStore("lyric_memory")

@Serializable
data class EqBandSetting(val freqHz: Int = 0, val gainDb: Float = 0f)

data class EqualizerSettings(
    val enabled: Boolean = false,
    val presetId: String = "flat",
    val customBands: List<EqBandSetting> = emptyList(),
)

enum class LyricsBackgroundMode { Off, Ambient, Reactive }

private fun String?.toLyricsBackgroundMode(): LyricsBackgroundMode =
    LyricsBackgroundMode.entries.firstOrNull { it.name == this } ?: LyricsBackgroundMode.Ambient

/**
 * 歌词背景“律动”跟拍的幅度：柔和只有光晕和流光跟拍；标准再加上封面弹跳和小节首拍的光环；强烈弹得更明显、每拍都有光环。
 * 旧版存的 Soft / Standard 照常读出。
 */
enum class RhythmIntensity { Soft, Standard, Strong }

private fun String?.toRhythmIntensity(): RhythmIntensity =
    RhythmIntensity.entries.firstOrNull { it.name == this } ?: RhythmIntensity.Standard

/** 跟拍校准的范围（毫秒）。 */
const val RHYTHM_OFFSET_MAX_MS = 200

/** 播放页歌词下面那一行（经典列表的第二行、其他动画模式的宿主副行）放什么。没有罗马音时“罗马音”回退成翻译。 */
enum class LyricSubline { Translation, Romanization, Both }

/** 全屏歌词每首歌换不换动画：固定、随机、按速度。 */
enum class LyricAutoSwitch { Fixed, Random, Tempo }

/** 按速度换动画的一档：每分钟拍数低于 [maxBpm]（左闭右开，上一档的上限是这一档的下限）时用 [mode]。 */
@Serializable
data class TempoRule(val maxBpm: Int, val mode: String)

/** 四档速度的默认对应：慢、中、快、很快。 */
val DefaultTempoRules = listOf(
    TempoRule(90, "vinyl"),
    TempoRule(120, "rings"),
    TempoRule(150, "wave"),
    TempoRule(TEMPO_RULE_OPEN_END, "jump"),
)

/** 最后一档没有上限。 */
const val TEMPO_RULE_OPEN_END = 999

/**
 * 歌词文字变换（见 LyricPipeline），对播放页、通知栏、桌面小组件、海报墙都生效；默认全关，关着时歌词和原来一模一样。
 */
data class LyricTextPrefs(
    val splitBilingual: Boolean = false,
    val traditional: Boolean = false,
    val autoRomanize: Boolean = false,
)

/** 播放页歌词字体：第一字体缺字时用第二字体，再用系统字体。名称只用于界面显示。 */
data class LyricFontPrefs(
    val primaryName: String = "",
    val fallbackName: String = "",
)

/**
 * 全屏歌词动画（设置 › 播放页 › 歌词动画，和全屏歌词页右上角 ✦ 打开的面板是同一份）。
 * [pluginSettings] 是“模式 id → { 设置键: 值 }”，只存改过的项，读的时候按模式的设置表合并默认值、夹紧。
 * [pins] 是“歌曲 key → 固定用的模式”。每首歌随机抽到的模式和测到的速度记在另一处（见 SettingsStore.lyricMemory），
 * 不放进这里：每首歌都可能写一次，放这里会让整个界面跟着重组。
 */
data class LyricStagePrefs(
    val visualizer: String = "classic",
    val auto: LyricAutoSwitch = LyricAutoSwitch.Fixed,
    val tempoRules: List<TempoRule> = DefaultTempoRules,
    val spectrum: Boolean = false,
    val subline: LyricSubline = LyricSubline.Translation,
    val pluginSettings: Map<String, Map<String, String>> = emptyMap(),
    val pins: Map<String, String> = emptyMap(),
    /** 首次进入全屏歌词页时 ✦ 外面的呼吸提示，打开过一次面板就不再显示。 */
    val hintSeen: Boolean = false,
    /** 随机池（模式 id）：随机换法从这里面抽。空＝除经典外的全部模式（旧数据、没动过时都是空）。 */
    val randomPool: Set<String> = emptySet(),
    /** 取色面板“最近用过”：`#RRGGBB`，新的在前，最多 [RECENT_COLORS_LIMIT] 个。点了“完成”就写，不进面板草稿。 */
    val recentColors: List<String> = emptyList(),
    /**
     * 风格预设：某个模式的一套设置存成的卡片，按创建顺序。卡片 id 是 `base#xxxx`，和内置模式一样能固定、进随机池、
     * 配给按速度某一档、给某首歌固定；只存在本机。预设的设置在面板草稿里（[StagePreset.settings]），新建、改名、删除点了就写。
     */
    val presets: List<StagePreset> = emptyList(),
)

const val RECENT_COLORS_LIMIT = 8

/** 一个风格预设：[base] 模式的一套设置 [settings]（只存改过的项），面板里叫 [name]。 */
@Serializable
data class StagePreset(
    val id: String,
    val base: String,
    val name: String,
    val settings: Map<String, String> = emptyMap(),
)

/**
 * 读预设：丢掉 id 不是 `base#xxxx`、和 base 对不上、base 是经典、名字空着的条目，同一个 id 只留第一个。
 * base 模式还在不在由界面判断（删掉的模式的预设不显示，也认不出）。
 */
internal fun decodePresets(raw: String?): List<StagePreset> {
    if (raw.isNullOrBlank()) return emptyList()
    val all = runCatching { presetJson.decodeFromString<List<StagePreset>>(raw) }.getOrDefault(emptyList())
    return all.filter { preset ->
        '#' in preset.id && preset.id.substringBefore('#') == preset.base && preset.base != "classic" && preset.name.isNotBlank()
    }.distinctBy { it.id }
}

private val presetJson = Json { ignoreUnknownKeys = true }

/** 每首歌记下的随机结果和速度（最近 [LYRIC_MEMORY_LIMIT] 首）。 */
@Serializable
data class LyricMemoryEntry(val mode: String = "", val bpm: Float = 0f)

const val LYRIC_MEMORY_LIMIT = 2000

/** JSON 字符串数组 → 集合；读不出来（没写过、格式不对）时为空。 */
internal fun decodeStringSet(raw: String?): Set<String> = decodeStringList(raw).toSet()

/** JSON 字符串数组 → 列表（保持顺序）；读不出来时为空。 */
internal fun decodeStringList(raw: String?): List<String> =
    if (raw.isNullOrBlank()) emptyList() else runCatching { Json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

/** 播放页封面形状：随机（每次切歌换一种，不和上一首重复）或固定成某一种 MaterialShapes。 */
enum class CoverShapeChoice { Random, Cookie12, Cookie9, Cookie7, Sunny, SoftBurst, Clover8 }

private fun String?.toCoverShapeChoice(): CoverShapeChoice =
    CoverShapeChoice.entries.firstOrNull { it.name == this } ?: CoverShapeChoice.Random

/** 方式：先分析再挑接法、固定长度的等功率淡化，或者干脆不过渡。 */
enum class CrossfadeMode(val label: String) {
    Automix("智能过渡"),
    Fade("交叉淡化"),
    None("不过渡"),
}

/**
 * GitHub 加速镜像；空串是源站直连。
 *
 * 2026-11-23 实测（Release 资产 / `api.github.com` / raw 三类端点，成都教育网出口）：
 * - gh-proxy.org / gh-proxy.com：三类全通，且代理 `api.github.com`。
 * - ghfile.geekertao.top / ghfast.top / gh.3w.pm / cors.isteed.cc：只转发资产与 raw，**不代理 API**。
 * - ghproxy.net：可用但 2~4.5s，仅作兜底。
 * - raw.githack.com / rawcdn.githack.com / githack.com：只服务 raw/blob，最后兜底。
 *
 * 已剔除（域名不再解析 / TLS 不握手 / 403 只许 git / 变成落地页）：ghp.ci、hub.gitmirror.com、
 * github.moeyy.xyz、gh.cc、ghproxy.cn、gh-proxy.net、mirror.ghproxy.com、gh-proxy.ygxz.in、
 * gh.llkk.cc、gh.api.99988866.xyz、ghproxy.com、ghproxy.link、ghps.cc、gh.ddlc.top、
 * kkgithub.com、bgithub.xyz、githubfast.com、gitclone.com，以及 100 余个社区流传的 `gh.*`
 * 前缀代理。判定「活」的唯一标准是能前缀式抓回 raw 正文（HTTP 200 且不是 HTML 反爬页），
 * 网页能否打开不作数；实测脚本与完整记录见 gitee `mytv-android/my-mirror`。
 *
 * 运行时还会从 gitee `my-mirror` 拉最新清单与这里的内置值合并，见 [GithubMirrors]。
 * 排序铁律：**镜像 / 国内直连优先，raw 最后兜底**——按 [tier] 升序取，同档位保持声明顺序。
 */
enum class GithubMirror(
    val id: String,
    val label: String,
    /** [MirrorKind.PREFIX] 拼在原 URL 前；[MirrorKind.RAW_HOST] 只存主机名；直连为空串。 */
    val prefix: String,
    val kind: MirrorKind,
    /** 是否代理 `api.github.com`（不代理的线路只能用于下载资产与 raw） */
    val proxiesApi: Boolean,
    val tier: Int,
) {
    /** 并发测速所有线路，选最快可达者（原有行为） */
    Auto("auto", "自动测速", "", MirrorKind.PREFIX, true, TIER_MODE),
    Direct("direct", "GitHub 直连", "", MirrorKind.PREFIX, true, TIER_DIRECT),
    ProxyOrg("gh-proxy.org", "gh-proxy.org", "https://gh-proxy.org/", MirrorKind.PREFIX, true, TIER_FAST),
    ProxyCom("gh-proxy.com", "gh-proxy.com", "https://gh-proxy.com/", MirrorKind.PREFIX, true, TIER_FAST),
    Geekertao("ghfile.geekertao.top", "ghfile.geekertao.top", "https://ghfile.geekertao.top/", MirrorKind.PREFIX, false, TIER_FAST),
    Ghfast("ghfast.top", "ghfast.top", "https://ghfast.top/", MirrorKind.PREFIX, false, TIER_FAST),
    ThreeW("gh.3w.pm", "gh.3w.pm", "https://gh.3w.pm/", MirrorKind.PREFIX, false, TIER_FAST),
    CorsIsteed("cors.isteed.cc", "cors.isteed.cc", "https://cors.isteed.cc/", MirrorKind.PREFIX, false, TIER_FAST),
    ProxyNet("ghproxy.net", "ghproxy.net", "https://ghproxy.net/", MirrorKind.PREFIX, false, TIER_SLOW),
    RawGithack("raw.githack.com", "raw.githack.com", "raw.githack.com", MirrorKind.RAW_HOST, false, TIER_RAW_ONLY),
    RawcdnGithack("rawcdn.githack.com", "rawcdn.githack.com", "rawcdn.githack.com", MirrorKind.RAW_HOST, false, TIER_RAW_ONLY),
    Githack("githack.com", "githack.com", "githack.com", MirrorKind.RAW_HOST, false, TIER_RAW_ONLY),
    ;

    /** 具体代理线路（自动测速与直连另行展开）。 */
    val isProxy: Boolean get() = this != Auto && this != Direct

    fun toRoute(): MirrorRoute =
        MirrorRoute(id, label, prefix, kind, proxiesApi, tier, enabled = true)

    companion object {
        val proxies: List<GithubMirror> = entries.filter { it.isProxy }

        fun fromName(name: String?): GithubMirror = entries.firstOrNull { it.name == name } ?: Auto
    }
}

/** 播放页上怎么画出这次接歌：不画、围着封面的一整圈，或沿封面外轮廓走一圈。 */
enum class CrossfadeAnimation(val label: String) {
    Off("关闭"),
    Ring("屏幕圆环"),
    Outline("封面描边"),
}

/** 折叠屏桌面姿态（半开、竖着立起来的那一半）播放页上半屏放什么；下半屏固定是控制。 */
enum class TabletopTop { Lyrics, Cover, Clock }

private fun String?.toTabletopTop(): TabletopTop =
    TabletopTop.entries.firstOrNull { it.name == this } ?: TabletopTop.Lyrics

private fun String?.toPosterTint(): PosterTint =
    PosterTint.entries.firstOrNull { it.name == this } ?: PosterTint.Medium

private fun String?.toCollectionView(): CollectionView =
    CollectionView.entries.firstOrNull { it.name == this } ?: CollectionView.List

private fun String?.toMiniPlayerTarget(): MiniPlayerTarget =
    MiniPlayerTarget.entries.firstOrNull { it.name == this } ?: MiniPlayerTarget.Player

class SettingsStore(private val context: Context) {
    private val providerKey = stringPreferencesKey("provider")
    private val chartsSourceKey = stringPreferencesKey("charts_source")
    private val playlistsSourceKey = stringPreferencesKey("playlists_source")
    private val startupTabKey = stringPreferencesKey("startup_tab")
    private val playlistsModeKey = stringPreferencesKey("playlists_mode")
    private val listenGroupKey = stringPreferencesKey("listen_group")
    private val themeKey = stringPreferencesKey("theme")
    private val layoutModeKey = stringPreferencesKey("layout_mode")
    private val cookiesKey = stringPreferencesKey("cookies")
    private val cookieUpdatedAtKey = stringPreferencesKey("cookie_updated_at")
    private val sodaSignerUrlKey = stringPreferencesKey("soda_signer_url")
    private val sodaSignerTokenKey = stringPreferencesKey("soda_signer_token")
    private val sodaSignerEnabledKey = booleanPreferencesKey("soda_signer_enabled")
    private val aggregationKey = stringPreferencesKey("aggregation")
    private val localMusicKey = stringPreferencesKey("local_music")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")
    private val reduceMotionKey = booleanPreferencesKey("reduce_motion")
    private val volumeNormalizationKey = booleanPreferencesKey("volume_normalization")
    private val audioQualityKey = stringPreferencesKey("audio_quality")
    private val showBottomNavKey = booleanPreferencesKey("show_bottom_nav")
    private val equalizerEnabledKey = booleanPreferencesKey("equalizer_enabled")
    private val equalizerPresetKey = stringPreferencesKey("equalizer_preset")
    private val equalizerBandsKey = stringPreferencesKey("equalizer_bands")
    private val searchHistoryKey = stringPreferencesKey("search_history")
    private val lastUpdateCheckKey = longPreferencesKey("last_update_check")
    private val colorSourceKey = stringPreferencesKey("color_source")
    private val customSeedKey = intPreferencesKey("custom_seed")
    private val browseStyleKey = stringPreferencesKey("browse_style")
    private val playerStyleKey = stringPreferencesKey("player_style")
    private val contrastKey = floatPreferencesKey("contrast")
    private val fontScaleKey = floatPreferencesKey("font_scale")
    private val amoledKey = booleanPreferencesKey("amoled")
    private val blurModeKey = stringPreferencesKey("blur_mode")
    private val lastSeedKey = intPreferencesKey("last_seed")
    private val sleepFadeKey = booleanPreferencesKey("sleep_fade")
    private val crossfadeEnabledKey = booleanPreferencesKey("crossfade_enabled")
    private val crossfadeSecondsKey = intPreferencesKey("crossfade_seconds")
    private val crossfadeAnimationKey = stringPreferencesKey("crossfade_animation")
    private val crossfadeModeKey = stringPreferencesKey("crossfade_mode")
    private val cacheLimitKey = intPreferencesKey("cache_limit_mb")
    private val lyricsBackgroundKey = stringPreferencesKey("lyrics_background")
    private val notificationLyricsKey = booleanPreferencesKey("notification_lyrics")
    private val floatingLyricsEnabledKey = booleanPreferencesKey("floating_lyrics_enabled")
    private val lyriconProviderEnabledKey = booleanPreferencesKey("lyricon_provider_enabled")
    private val lyriconSubscriberEnabledKey = booleanPreferencesKey("lyricon_subscriber_enabled")
    /** Apple Music 匿名歌词走第三方中转（zarz / paxsenix / AMLL）的总开关。 */
    private val thirdPartyLyricsKey = booleanPreferencesKey("third_party_lyrics")
    /** 原平台无词或缺封面时，查询 LRCLIB / Apple 公开搜索。 */
    private val metadataFallbackKey = booleanPreferencesKey("metadata_fallback")
    private val rhythmIntensityKey = stringPreferencesKey("rhythm_intensity")
    private val coverShapeKey = stringPreferencesKey("cover_shape")
    private val tvAmbientClockKey = booleanPreferencesKey("tv_ambient_clock")
    private val tabletopTopKey = stringPreferencesKey("tabletop_top")
    private val keepScreenOnKey = booleanPreferencesKey("tv_keep_screen_on")
    private val tvBackgroundPlayKey = booleanPreferencesKey("tv_background_play")
    private val rhythmOffsetKey = intPreferencesKey("rhythm_offset_ms")
    private val rhythmDebugKey = booleanPreferencesKey("rhythm_debug")
    // 海报墙：墙内设置六项 + 详情页视图 + 迷你播放器去处，和播放页的设置一样只存在本机
    private val posterTintKey = stringPreferencesKey("poster_tint")
    private val posterLightsOutKey = booleanPreferencesKey("poster_lights_out")
    private val posterIndexKey = booleanPreferencesKey("poster_index")
    private val posterTranslationKey = booleanPreferencesKey("poster_translation")
    private val posterFollowKey = booleanPreferencesKey("poster_follow")
    private val posterTvDriftKey = booleanPreferencesKey("poster_tv_drift")
    // 视角远近：手机、平板、电视各一份（按墙的格距档存）
    private val posterZoomPhoneKey = floatPreferencesKey("poster_zoom_phone")
    private val posterZoomTabletKey = floatPreferencesKey("poster_zoom_tablet")
    private val posterZoomTvKey = floatPreferencesKey("poster_zoom_tv")
    private val collectionViewKey = stringPreferencesKey("collection_view")
    private val miniPlayerTargetKey = stringPreferencesKey("mini_player_target")
    // 播放上报：把播放记录回传给平台，让推荐流跟着口味走；关掉后一个请求都不发
    private val playReportEnabledKey = booleanPreferencesKey("play_report_enabled")
    // 汽水上报用的设备标识：每次启动重新生成就没法积累口味，所以落盘
    private val sodaReportDeviceIdKey = stringPreferencesKey("soda_report_device_id")
    // 汽水扫码登录的护照设备身份：和设备指纹是两套东西，落盘后所有二维码复用同一个
    private val sodaLoginDeviceIdKey = stringPreferencesKey("soda_login_device_id")
    private val sodaLoginInstallIdKey = stringPreferencesKey("soda_login_install_id")
    // 身份是什么时候生成/轮换的：同步文件按它判新旧（轮换 = 一份带新时间戳的身份）
    private val sodaLoginDeviceUpdatedAtKey = longPreferencesKey("soda_login_device_updated_at")
    // 酷狗扫码登录的设备身份种子：落盘后所有登录复用同一个（mid 参与客户端签名）
    private val kugouLoginDeviceKey = stringPreferencesKey("kugou_login_device")
    // 网易云播放上报（weblog）的设备号：和 MUSIC_U 拼在同一个 Cookie 头里，服务端按它归并听歌排行
    private val neteaseWeblogDeviceKey = stringPreferencesKey("netease_weblog_device")
    // 设备号上次变化的时间：同步文件按它判新旧
    private val neteaseWeblogDeviceUpdatedAtKey = longPreferencesKey("netease_weblog_device_updated_at")
    // MHub（music.op.wiki）登录时上报的设备号：只在登录 body 里出现一次，落盘后每次登录都是同一台
    private val mhubLoginDeviceKey = stringPreferencesKey("mhub_login_device")
    // 酷狗概念版「听歌领会员」：启动时自动领一天的开关，和上次自动领取的日期（只用来去重）
    private val kugouWelfareAutoKey = booleanPreferencesKey("kugou_welfare_auto")
    private val kugouWelfareLastDateKey = stringPreferencesKey("kugou_welfare_last_date")
    // 全屏歌词动画与歌词文字变换
    private val lyricVisualizerKey = stringPreferencesKey("lyric_visualizer")
    private val lyricVisualizerAutoKey = stringPreferencesKey("lyric_visualizer_auto")
    private val lyricTempoRulesKey = stringPreferencesKey("lyric_tempo_rules")
    private val lyricVisualizerPinsKey = stringPreferencesKey("lyric_visualizer_pins")
    private val lyricVisualizerMemoryKey = stringPreferencesKey("lyric_visualizer_memory")
    private val lyricPluginSettingsKey = stringPreferencesKey("lyric_plugin_settings")
    private val lyricSpectrumKey = booleanPreferencesKey("lyric_spectrum")
    private val lyricSublineKey = stringPreferencesKey("lyric_subline")
    private val lyricHintSeenKey = booleanPreferencesKey("lyric_visualizer_hint_seen")
    private val lyricRandomPoolKey = stringPreferencesKey("lyric_random_pool")
    private val lyricRecentColorsKey = stringPreferencesKey("lyric_recent_colors")
    private val lyricStagePresetsKey = stringPreferencesKey("lyric_stage_presets")
    private val lyricSplitBilingualKey = booleanPreferencesKey("lyric_split_bilingual")
    private val lyricTraditionalKey = booleanPreferencesKey("lyric_traditional")
    private val lyricAutoRomanizeKey = booleanPreferencesKey("lyric_auto_romanize")
    private val lyricFontPrimaryKey = stringPreferencesKey("lyric_font_primary")
    private val lyricFontFallbackKey = stringPreferencesKey("lyric_font_fallback")
    /** 更新走哪条 GitHub 镜像线路；见 [GithubMirror]。 */
    private val githubMirrorKey = stringPreferencesKey("github_mirror")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val state: Flow<AppSettings> = context.dataStore.data.map { values ->
        AppSettings(
            provider = values[providerKey] ?: "netease",
            chartsSource = values[chartsSourceKey] ?: "",
            playlistsSource = values[playlistsSourceKey] ?: "",
            startupTab = values[startupTabKey] ?: "",
            playlistsMode = values[playlistsModeKey] ?: "",
            listenGroup = values[listenGroupKey] ?: "",
            theme = values[themeKey] ?: "system",
            layoutMode = values[layoutModeKey] ?: "Auto",
            cookies = runCatching { json.decodeFromString<Map<String, String>>(values[cookiesKey] ?: "{}") }
                .getOrDefault(emptyMap()),
            sodaSignerUrl = values[sodaSignerUrlKey] ?: "",
            sodaSignerToken = values[sodaSignerTokenKey] ?: "",
            sodaSignerEnabled = values[sodaSignerEnabledKey] ?: true,
            thirdPartyLyrics = values[thirdPartyLyricsKey] ?: true,
            metadataFallback = values[metadataFallbackKey] ?: true,
            aggregation = runCatching {
                json.decodeFromString<AggregationSettings>(values[aggregationKey] ?: "")
            }.getOrDefault(AggregationSettings()),
            localMusic = runCatching {
                json.decodeFromString<LocalMusicSettings>(values[localMusicKey] ?: "")
            }.getOrDefault(LocalMusicSettings()),
            dynamicColor = values[dynamicColorKey] ?: false,
            reduceMotion = values[reduceMotionKey] ?: false,
            volumeNormalization = values[volumeNormalizationKey] ?: false,
            audioQuality = values[audioQualityKey] ?: "auto",
            showBottomNav = values[showBottomNavKey] ?: true,
            lyricsBackground = values[lyricsBackgroundKey].toLyricsBackgroundMode(),
            notificationLyrics = values[notificationLyricsKey] ?: false,
            floatingLyricsEnabled = values[floatingLyricsEnabledKey] ?: false,
            lyriconProviderEnabled = values[lyriconProviderEnabledKey] ?: true,
            lyriconSubscriberEnabled = values[lyriconSubscriberEnabledKey] ?: false,
            rhythmIntensity = values[rhythmIntensityKey].toRhythmIntensity(),
            coverShape = values[coverShapeKey].toCoverShapeChoice(),
            tvAmbientClock = values[tvAmbientClockKey] ?: true,
            tabletopTop = values[tabletopTopKey].toTabletopTop(),
            keepScreenOn = values[keepScreenOnKey] ?: false,
            tvBackgroundPlay = values[tvBackgroundPlayKey] ?: false,
            rhythmOffsetMs = (values[rhythmOffsetKey] ?: 0).coerceIn(-RHYTHM_OFFSET_MAX_MS, RHYTHM_OFFSET_MAX_MS),
            rhythmDebug = values[rhythmDebugKey] ?: false,
            poster = posterPrefs(values),
            swipe = SwipePrefsCodec.read(values),
            share = SharePrefsCodec.read(values),
            collectionView = values[collectionViewKey].toCollectionView(),
            miniPlayerTarget = values[miniPlayerTargetKey].toMiniPlayerTarget(),
            playReportEnabled = values[playReportEnabledKey] ?: true,
            sodaReportDeviceId = values[sodaReportDeviceIdKey] ?: "",
            sodaLoginDeviceId = values[sodaLoginDeviceIdKey] ?: "",
            sodaLoginInstallId = values[sodaLoginInstallIdKey] ?: "",
            sodaLoginDeviceUpdatedAt = values[sodaLoginDeviceUpdatedAtKey] ?: 0L,
            kugouLoginDevice = values[kugouLoginDeviceKey] ?: "",
            neteaseWeblogDevice = values[neteaseWeblogDeviceKey] ?: "",
            neteaseWeblogDeviceUpdatedAt = values[neteaseWeblogDeviceUpdatedAtKey] ?: 0L,
            mhubLoginDevice = values[mhubLoginDeviceKey] ?: "",
            kugouWelfareAuto = values[kugouWelfareAutoKey] ?: true,
            kugouWelfareLastClaimDate = values[kugouWelfareLastDateKey] ?: "",
            lyricStage = lyricStagePrefs(values),
            lyricFonts = LyricFontPrefs(
                primaryName = values[lyricFontPrimaryKey] ?: "",
                fallbackName = values[lyricFontFallbackKey] ?: "",
            ),
            lyricText = LyricTextPrefs(
                splitBilingual = values[lyricSplitBilingualKey] ?: false,
                traditional = values[lyricTraditionalKey] ?: false,
                autoRomanize = values[lyricAutoRomanizeKey] ?: false,
            ),
            equalizer = EqualizerSettings(
                enabled = values[equalizerEnabledKey] ?: false,
                presetId = values[equalizerPresetKey] ?: "flat",
                customBands = runCatching {
                    json.decodeFromString<List<EqBandSetting>>(values[equalizerBandsKey] ?: "[]")
                }.getOrDefault(emptyList()),
            ),
            searchHistory = runCatching {
                json.decodeFromString<List<String>>(values[searchHistoryKey] ?: "[]")
            }.getOrDefault(emptyList()),
            lastUpdateCheck = values[lastUpdateCheckKey] ?: 0L,
            // 旧版“系统取色”开关迁移为“系统壁纸”配色来源
            colorSource = values[colorSourceKey]
                ?: if (values[dynamicColorKey] == true) "Wallpaper" else "Cover",
            customSeed = values[customSeedKey] ?: 0xFF006B5F.toInt(),
            browseStyle = values[browseStyleKey] ?: "TonalSpot",
            playerStyle = values[playerStyleKey] ?: "Vibrant",
            contrast = values[contrastKey] ?: 0f,
            fontScale = values[fontScaleKey] ?: 1f,
            amoled = values[amoledKey] ?: false,
            blurMode = values[blurModeKey] ?: "Auto",
            lastSeed = values[lastSeedKey] ?: 0,
            sleepFade = values[sleepFadeKey] ?: true,
            crossfadeEnabled = values[crossfadeEnabledKey] ?: false,
            crossfadeSeconds = (values[crossfadeSecondsKey] ?: 6).coerceIn(4, 10),
            crossfadeAnimation = values[crossfadeAnimationKey] ?: CrossfadeAnimation.Ring.name,
            crossfadeMode = values[crossfadeModeKey] ?: CrossfadeMode.Automix.name,
            cacheLimitMb = values[cacheLimitKey] ?: 0,
            githubMirror = GithubMirror.fromName(values[githubMirrorKey]),
        )
    }

    suspend fun provider(value: String) = context.dataStore.edit { it[providerKey] = value }
    suspend fun chartsSource(value: String) = context.dataStore.edit { it[chartsSourceKey] = value }
    suspend fun playlistsSource(value: String) = context.dataStore.edit { it[playlistsSourceKey] = value }
    suspend fun startupTab(value: String) = context.dataStore.edit { it[startupTabKey] = value }
    suspend fun playlistsMode(value: String) = context.dataStore.edit { it[playlistsModeKey] = value }
    suspend fun listenGroup(value: String) = context.dataStore.edit { it[listenGroupKey] = value }
    suspend fun theme(value: String) = context.dataStore.edit { it[themeKey] = value }
    suspend fun layoutMode(value: String) = context.dataStore.edit { it[layoutModeKey] = value }
    suspend fun dynamicColor(value: Boolean) = context.dataStore.edit { it[dynamicColorKey] = value }
    suspend fun reduceMotion(value: Boolean) = context.dataStore.edit { it[reduceMotionKey] = value }
    suspend fun volumeNormalization(value: Boolean) = context.dataStore.edit { it[volumeNormalizationKey] = value }
    suspend fun audioQuality(value: String) = context.dataStore.edit { it[audioQualityKey] = value }
    suspend fun showBottomNav(value: Boolean) = context.dataStore.edit { it[showBottomNavKey] = value }
    suspend fun colorSource(value: String) = context.dataStore.edit { it[colorSourceKey] = value }
    suspend fun customSeed(value: Int) = context.dataStore.edit { it[customSeedKey] = value }
    suspend fun browseStyle(value: String) = context.dataStore.edit { it[browseStyleKey] = value }
    suspend fun playerStyle(value: String) = context.dataStore.edit { it[playerStyleKey] = value }
    suspend fun contrast(value: Float) = context.dataStore.edit { it[contrastKey] = value }
    suspend fun fontScale(value: Float) = context.dataStore.edit { it[fontScaleKey] = value }
    suspend fun amoled(value: Boolean) = context.dataStore.edit { it[amoledKey] = value }
    suspend fun blurMode(value: String) = context.dataStore.edit { it[blurModeKey] = value }
    suspend fun lyricsBackground(value: LyricsBackgroundMode) = context.dataStore.edit { it[lyricsBackgroundKey] = value.name }
    suspend fun notificationLyrics(value: Boolean) = context.dataStore.edit { it[notificationLyricsKey] = value }
    suspend fun floatingLyricsEnabled(value: Boolean) = context.dataStore.edit { it[floatingLyricsEnabledKey] = value }
    suspend fun lyriconProviderEnabled(value: Boolean) = context.dataStore.edit { it[lyriconProviderEnabledKey] = value }
    suspend fun lyriconSubscriberEnabled(value: Boolean) = context.dataStore.edit { it[lyriconSubscriberEnabledKey] = value }
    suspend fun rhythmIntensity(value: RhythmIntensity) = context.dataStore.edit { it[rhythmIntensityKey] = value.name }
    suspend fun coverShape(value: CoverShapeChoice) = context.dataStore.edit { it[coverShapeKey] = value.name }
    suspend fun tvAmbientClock(value: Boolean) = context.dataStore.edit { it[tvAmbientClockKey] = value }
    suspend fun tabletopTop(value: TabletopTop) = context.dataStore.edit { it[tabletopTopKey] = value.name }
    suspend fun keepScreenOn(value: Boolean) = context.dataStore.edit { it[keepScreenOnKey] = value }
    suspend fun tvBackgroundPlay(value: Boolean) = context.dataStore.edit { it[tvBackgroundPlayKey] = value }
    suspend fun rhythmOffsetMs(value: Int) =
        context.dataStore.edit { it[rhythmOffsetKey] = value.coerceIn(-RHYTHM_OFFSET_MAX_MS, RHYTHM_OFFSET_MAX_MS) }
    suspend fun rhythmDebug(value: Boolean) = context.dataStore.edit { it[rhythmDebugKey] = value }
    suspend fun collectionView(value: CollectionView) = context.dataStore.edit { it[collectionViewKey] = value.name }
    suspend fun miniPlayerTarget(value: MiniPlayerTarget) = context.dataStore.edit { it[miniPlayerTargetKey] = value.name }
    suspend fun playReportEnabled(value: Boolean) = context.dataStore.edit { it[playReportEnabledKey] = value }

    /** 酷狗概念版「听歌领会员」的自动领取开关。 */
    suspend fun kugouWelfareAuto(value: Boolean) = context.dataStore.edit { it[kugouWelfareAutoKey] = value }

    /** 记一次自动领取的日期（北京时间），同一天不再重复打接口。 */
    suspend fun kugouWelfareLastClaimDate(value: String) =
        context.dataStore.edit { it[kugouWelfareLastDateKey] = value }

    /**
     * 酷狗扫码登录的设备身份种子。落盘后所有登录复用同一个。
     *
     * 为什么必须持久化：酷狗的 `mid` 参与客户端签名（`loginWebGet` / `androidHeaders`
     * 都带它，服务端按它认设备），而 `initLoginDevice()` 原来每次登录都生成一套新的
     * GUID/MID/MAC/DEV，等于服务端眼里「这个账号每次换一台设备登录」。四个字段由
     * `KugouProvider` 从这个种子推导，形状仍归它说了算，这里只存种子。
     *
     * 种子优先取**已登录 Cookie 里那份 GUID**：另一台设备登录后 Cookie 会整份同步过来，
     * 认下它的 GUID 当种子，下一次登录就复用同一台设备，不会把 Cookie 里的设备又换回本机的。
     */
    suspend fun kugouLoginDevice(): String {
        val values = context.dataStore.data.first()
        cookieField(values[cookiesKey], "KUGOU_API_GUID")?.let { fromCookie ->
            if (fromCookie != values[kugouLoginDeviceKey]) {
                context.dataStore.edit { it[kugouLoginDeviceKey] = fromCookie }
            }
            return fromCookie
        }
        val existing = values[kugouLoginDeviceKey]
        if (!existing.isNullOrBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString().replace("-", "")
        context.dataStore.edit { it[kugouLoginDeviceKey] = generated }
        return generated
    }

    /** 从一整串 cookie 里取一个字段的值；没有就 null。 */
    private fun cookieField(raw: String?, name: String): String? {
        if (raw.isNullOrBlank()) return null
        for (pair in raw.split(';')) {
            val trimmed = pair.trim()
            if (trimmed.isEmpty()) continue
            val index = trimmed.indexOf('=')
            if (index <= 0) continue
            if (trimmed.substring(0, index).trim() == name) {
                return trimmed.substring(index + 1).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /**
     * 网易云播放上报（weblog）的设备号：第一次随机落盘，之后一直复用。
     *
     * 为什么必须持久化：这个值是直接拼进 Cookie 头和 `MUSIC_U` 一起发出去的
     * （见 `NetEasePlayReporter.send`），服务端按它把同一个账号的播放归并成
     * 「最近播放 / 听歌排行」。`MUSIC_U` 跟着 Cookie 同步跨设备共用，设备号却是每进程
     * 一个随机串，等于同一账号每次重启都是一台新设备，历史永远攒不起来——和汽水修复前
     * 同形。原来「读 cookie 里的 deviceId」是个死分支：扫码登录只回 MUSIC_U/__csrf，
     * 拼出来的 cookie 串里从来没有这个字段。
     *
     * 所以它跟着同步走（见 `SyncNeteaseIdentity`）：Cookie 和它本来就是同一套凭据。
     */
    suspend fun neteaseWeblogDevice(): String {
        val values = context.dataStore.data.first()
        val existing = values[neteaseWeblogDeviceKey]
        if (!existing.isNullOrBlank()) {
            // 老版本生成的没记时间戳：补一个，不然同步里判不了新旧
            if ((values[neteaseWeblogDeviceUpdatedAtKey] ?: 0L) > 0L) return existing
            context.dataStore.edit { it[neteaseWeblogDeviceUpdatedAtKey] = System.currentTimeMillis() }
            return existing
        }
        val generated = com.mytv.mymusic.data.native.NativeCrypto.randomString(32)
        context.dataStore.edit {
            it[neteaseWeblogDeviceKey] = generated
            it[neteaseWeblogDeviceUpdatedAtKey] = System.currentTimeMillis()
        }
        return generated
    }

    /**
     * MHub（music.op.wiki）登录时上报的设备号：第一次随机落盘，之后每次登录都用同一个。
     *
     * 它只在登录 body 里出现一次，`mhub_session` 单独就能用，所以**不跨设备同步**
     * （同步会让所有安装共用一个 device_id，服务端反而更分不清是哪台设备）。但得落盘：
     * 原来每个进程一个新 UUID，重登几次服务端就攒下一堆陌生设备，正是汽水那条教训的同形问题。
     */
    suspend fun mhubLoginDevice(): String {
        val existing = context.dataStore.data.first()[mhubLoginDeviceKey]
        if (!existing.isNullOrBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { it[mhubLoginDeviceKey] = generated }
        return generated
    }

    /**
     * 同步带回来的网易云上报设备号写进本机。判新旧在合并那一层
     * （`SyncMerge.mergeNeteaseIdentity` 按时间取新的），这里只负责落，
     * 时间戳只向前走，免得回退之后下次合并判错。
     */
    suspend fun adoptNeteaseWeblogDevice(deviceId: String, updatedAt: Long): Boolean {
        val id = deviceId.trim()
        if (id.isEmpty()) return false
        var adopted = false
        context.dataStore.edit {
            val same = it[neteaseWeblogDeviceKey] == id
            val current = it[neteaseWeblogDeviceUpdatedAtKey] ?: 0L
            val time = maxOf(updatedAt, current)
            if (same && time == current) return@edit
            it[neteaseWeblogDeviceKey] = id
            it[neteaseWeblogDeviceUpdatedAtKey] = time
            adopted = true
        }
        return adopted
    }

    /**
     * 汽水上报的设备标识：第一次用随机值落盘，之后一直复用。
     * 官方客户端靠它把同一个人的播放行为串起来，每次启动都换就等于没有历史。
     *
     * 它同时是业务请求的设备指纹（`SodaProvider.pcAppParams` 没配签名器时就用它），
     * 所以和登录身份一起同步：两台设备对登录口和业务口报同一个设备号，才是服务端眼里
     * 的同一台设备。生成时把身份那套的时间戳一起往前推，好让新值传到别的设备。
     */
    suspend fun sodaReportDeviceId(): String {
        val existing = context.dataStore.data.first()[sodaReportDeviceIdKey]
        if (!existing.isNullOrBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString().replace("-", "")
        context.dataStore.edit {
            it[sodaReportDeviceIdKey] = generated
            it[sodaLoginDeviceUpdatedAtKey] = System.currentTimeMillis()
        }
        return generated
    }

    /**
     * 汽水扫码登录的护照设备身份（`device_id` 16 位 / `install_id` 15 位纯数字）：
     * 第一次用随机值落盘，之后所有二维码复用同一个。
     *
     * 为什么必须持久化：汽水服务端按 `device_id` 做风控，同一个身份持续使用后才补发
     * `odin_tt`；每次登录都换新设备会被当成新设备，扫码后容易 `error_code=7`，
     * 拿到的会话也短命（对齐 libresoda `docs/QR-LOGIN.md` 与 sodam config 的
     * `login_device_id`：退出登录不清除，只有显式重置才轮换）。
     *
     * 身份和登录 Cookie 是**同一套凭据**，所以它跟着同步走，连播放上报/业务请求用的
     * 设备指纹一起（见 `SyncSodaIdentity`）：三份各用各的话，另一台设备在服务端眼里
     * 就是「拿着别人签发的会话 + 一个陌生设备」。
     */
    suspend fun sodaLoginDevice(): Pair<String, String> {
        val current = context.dataStore.data.first()
        val deviceId = current[sodaLoginDeviceIdKey]?.takeIf { it.isNotBlank() }
        val installId = current[sodaLoginInstallIdKey]?.takeIf { it.isNotBlank() }
        val stamped = current[sodaLoginDeviceUpdatedAtKey] ?: 0L
        // 老版本生成的身份没记时间戳：补一个，不然同步里判不了新旧
        if (deviceId != null && installId != null && stamped > 0L) return deviceId to installId
        val generated = if (deviceId != null && installId != null) {
            deviceId to installId
        } else {
            randomLoginDeviceId(16) to randomLoginDeviceId(15)
        }
        context.dataStore.edit {
            it[sodaLoginDeviceIdKey] = generated.first
            it[sodaLoginInstallIdKey] = generated.second
            it[sodaLoginDeviceUpdatedAtKey] = System.currentTimeMillis()
        }
        return generated
    }

    /** 轮换登录设备身份：只在用户显式重置登录设备时调（风控被盯上时的一条出路）。 */
    suspend fun resetSodaLoginDevice(): Pair<String, String> {
        val generated = randomLoginDeviceId(16) to randomLoginDeviceId(15)
        context.dataStore.edit {
            it[sodaLoginDeviceIdKey] = generated.first
            it[sodaLoginInstallIdKey] = generated.second
            it[sodaLoginDeviceUpdatedAtKey] = System.currentTimeMillis()
        }
        return generated
    }

    /**
     * 同步带回来的汽水设备身份（登录身份 + 上报指纹）写进本机。
     *
     * 为什么要同步：护照服务按 `device_id` 风控并按它补发 `odin_tt`，会话也签给这个
     * 身份；业务请求的设备指纹又是另一份。Cookie 本来就整份同步，身份却各用各的话，
     * 另一台设备在服务端眼里就是「拿着别人签发的会话 + 一个陌生设备」——正是风控最
     * 喜欢的那种形状。三份并成一份，两台设备才是服务端眼里的一台设备。
     *
     * 判新旧的逻辑在合并那一层（`SyncMerge.mergeSodaIdentity` 按时间取新的），这里和
     * Cookie 一样：合并结果说什么就写什么。时间戳只向前走，免得回退之后下次合并判错。
     */
    suspend fun adoptSodaIdentity(
        deviceId: String,
        installId: String,
        reportDeviceId: String,
        updatedAt: Long,
    ): Boolean {
        val id = deviceId.trim()
        val install = installId.trim()
        if (id.isEmpty() || install.isEmpty()) return false
        // 上报指纹允许带空（另一台没开播放上报）：空不覆盖本机已有的那份
        val report = reportDeviceId.trim()
        var adopted = false
        context.dataStore.edit {
            val currentReport = it[sodaReportDeviceIdKey]?.takeIf { value -> value.isNotBlank() }.orEmpty()
            val effectiveReport = report.ifBlank { currentReport }
            val same = it[sodaLoginDeviceIdKey] == id &&
                it[sodaLoginInstallIdKey] == install &&
                currentReport == effectiveReport
            val current = it[sodaLoginDeviceUpdatedAtKey] ?: 0L
            val time = maxOf(updatedAt, current)
            if (same && time == current) return@edit
            it[sodaLoginDeviceIdKey] = id
            it[sodaLoginInstallIdKey] = install
            if (effectiveReport.isNotEmpty()) it[sodaReportDeviceIdKey] = effectiveReport
            it[sodaLoginDeviceUpdatedAtKey] = time
            adopted = true
        }
        return adopted
    }

    /** 同步读取整套汽水设备身份和时间戳，避免两次读之间不一致。 */
    suspend fun sodaIdentitySnapshot(): SodaIdentitySnapshot {
        val values = context.dataStore.data.first()
        return SodaIdentitySnapshot(
            deviceId = values[sodaLoginDeviceIdKey]?.takeIf { it.isNotBlank() }.orEmpty(),
            installId = values[sodaLoginInstallIdKey]?.takeIf { it.isNotBlank() }.orEmpty(),
            reportDeviceId = values[sodaReportDeviceIdKey]?.takeIf { it.isNotBlank() }.orEmpty(),
            updatedAt = values[sodaLoginDeviceUpdatedAtKey] ?: 0L,
        )
    }

    /** 同步读取网易云上报设备号和时间戳，避免两次读之间不一致。 */
    suspend fun neteaseWeblogDeviceSnapshot(): NeteaseIdentitySnapshot {
        val values = context.dataStore.data.first()
        return NeteaseIdentitySnapshot(
            deviceId = values[neteaseWeblogDeviceKey]?.takeIf { it.isNotBlank() }.orEmpty(),
            updatedAt = values[neteaseWeblogDeviceUpdatedAtKey] ?: 0L,
        )
    }

    /** 纯数字、首位非 0（对齐 libresoda `QrDevice::generate` 的 `digits()`）。 */
    private fun randomLoginDeviceId(length: Int): String {
        val random = java.security.SecureRandom()
        return buildString(length) {
            append(1 + random.nextInt(8))
            repeat(length - 1) { append(random.nextInt(10)) }
        }
    }
    suspend fun lastSeed(value: Int) = context.dataStore.edit { it[lastSeedKey] = value }
    suspend fun sleepFade(value: Boolean) = context.dataStore.edit { it[sleepFadeKey] = value }
    suspend fun crossfadeEnabled(value: Boolean) = context.dataStore.edit { it[crossfadeEnabledKey] = value }
    suspend fun crossfadeSeconds(value: Int) = context.dataStore.edit { it[crossfadeSecondsKey] = value.coerceIn(4, 10) }
    suspend fun crossfadeAnimation(value: CrossfadeAnimation) =
        context.dataStore.edit { it[crossfadeAnimationKey] = value.name }
    suspend fun crossfadeMode(value: CrossfadeMode) =
        context.dataStore.edit { it[crossfadeModeKey] = value.name }
    suspend fun cacheLimitMb(value: Int) = context.dataStore.edit { it[cacheLimitKey] = value }
    suspend fun githubMirror(value: GithubMirror) = context.dataStore.edit { it[githubMirrorKey] = value.name }

    /** 墙内设置：在同一次 edit 里读出当前值再写回，连着点两个开关也不会互相覆盖。 */
    suspend fun poster(transform: (PosterPrefs) -> PosterPrefs) = context.dataStore.edit {
        val value = transform(posterPrefs(it))
        it[posterTintKey] = value.tint.name
        it[posterLightsOutKey] = value.lightsOut
        it[posterIndexKey] = value.showIndex
        it[posterTranslationKey] = value.translation
        it[posterFollowKey] = value.follow
        it[posterTvDriftKey] = value.tvDrift
        it[posterZoomPhoneKey] = PosterZoom.snap(value.zoom.phone)
        it[posterZoomTabletKey] = PosterZoom.snap(value.zoom.tablet)
        it[posterZoomTvKey] = PosterZoom.snap(value.zoom.tv)
    }

    /** 滑动操作（设置 › 滑动操作）：只在本机，不进同步。 */
    suspend fun swipe(transform: (SwipePrefs) -> SwipePrefs) = context.dataStore.edit {
        SwipePrefsCodec.write(it, transform(SwipePrefsCodec.read(it)))
    }

    /** 歌词分享页的选项：只在本机，不进同步。 */
    suspend fun share(transform: (SharePrefs) -> SharePrefs) = context.dataStore.edit {
        SharePrefsCodec.write(it, transform(SharePrefsCodec.read(it)))
    }

    private fun posterPrefs(values: Preferences) = PosterPrefs(
        tint = values[posterTintKey].toPosterTint(),
        lightsOut = values[posterLightsOutKey] ?: false,
        showIndex = values[posterIndexKey] ?: true,
        translation = values[posterTranslationKey] ?: true,
        follow = values[posterFollowKey] ?: true,
        tvDrift = values[posterTvDriftKey] ?: true,
        zoom = PosterZoom(
            phone = PosterZoom.snap(values[posterZoomPhoneKey] ?: PosterZoom.DEFAULT),
            tablet = PosterZoom.snap(values[posterZoomTabletKey] ?: PosterZoom.DEFAULT),
            tv = PosterZoom.snap(values[posterZoomTvKey] ?: PosterZoom.DEFAULT),
        ),
    )

    private fun lyricStagePrefs(values: Preferences) = LyricStagePrefs(
        visualizer = values[lyricVisualizerKey] ?: "classic",
        auto = LyricAutoSwitch.entries.firstOrNull { it.name == values[lyricVisualizerAutoKey] } ?: LyricAutoSwitch.Fixed,
        tempoRules = runCatching { json.decodeFromString<List<TempoRule>>(values[lyricTempoRulesKey] ?: "") }
            .getOrNull()?.takeIf { it.size == DefaultTempoRules.size } ?: DefaultTempoRules,
        spectrum = values[lyricSpectrumKey] ?: false,
        subline = LyricSubline.entries.firstOrNull { it.name == values[lyricSublineKey] } ?: LyricSubline.Translation,
        pluginSettings = runCatching {
            json.decodeFromString<Map<String, Map<String, String>>>(values[lyricPluginSettingsKey] ?: "{}")
        }.getOrDefault(emptyMap()),
        pins = runCatching { json.decodeFromString<Map<String, String>>(values[lyricVisualizerPinsKey] ?: "{}") }
            .getOrDefault(emptyMap()),
        hintSeen = values[lyricHintSeenKey] ?: false,
        randomPool = decodeStringSet(values[lyricRandomPoolKey]),
        recentColors = decodeStringList(values[lyricRecentColorsKey]).take(RECENT_COLORS_LIMIT),
        presets = decodePresets(values[lyricStagePresetsKey]),
    )

    /** 全屏歌词动画的设置：同一次 edit 里读出当前值再写回，连着改几项不会互相覆盖。 */
    suspend fun lyricStage(transform: (LyricStagePrefs) -> LyricStagePrefs) = context.dataStore.edit {
        val value = transform(lyricStagePrefs(it))
        it[lyricVisualizerKey] = value.visualizer
        it[lyricVisualizerAutoKey] = value.auto.name
        it[lyricTempoRulesKey] = json.encodeToString(value.tempoRules)
        it[lyricSpectrumKey] = value.spectrum
        it[lyricSublineKey] = value.subline.name
        it[lyricPluginSettingsKey] = json.encodeToString(value.pluginSettings)
        it[lyricVisualizerPinsKey] = json.encodeToString(value.pins)
        it[lyricHintSeenKey] = value.hintSeen
        it[lyricRandomPoolKey] = json.encodeToString(value.randomPool.toList())
        it[lyricRecentColorsKey] = json.encodeToString(value.recentColors.take(RECENT_COLORS_LIMIT))
        it[lyricStagePresetsKey] = json.encodeToString(value.presets)
    }

    suspend fun lyricFonts(primaryName: String? = null, fallbackName: String? = null) = context.dataStore.edit {
        primaryName?.let { name -> it[lyricFontPrimaryKey] = name }
        fallbackName?.let { name -> it[lyricFontFallbackKey] = name }
    }

    suspend fun lyricText(transform: (LyricTextPrefs) -> LyricTextPrefs) = context.dataStore.edit {
        val current = LyricTextPrefs(
            splitBilingual = it[lyricSplitBilingualKey] ?: false,
            traditional = it[lyricTraditionalKey] ?: false,
            autoRomanize = it[lyricAutoRomanizeKey] ?: false,
        )
        val value = transform(current)
        it[lyricSplitBilingualKey] = value.splitBilingual
        it[lyricTraditionalKey] = value.traditional
        it[lyricAutoRomanizeKey] = value.autoRomanize
    }

    /** 这首歌记下的随机结果和速度；没记过返回 null。 */
    suspend fun lyricMemory(songKey: String): LyricMemoryEntry? {
        if (songKey.isBlank()) return null
        return decodeLyricMemory(context.lyricMemoryStore.data.first()[lyricVisualizerMemoryKey])[songKey]
    }

    /** 更新这首歌的记录并挪到最新；超过 [LYRIC_MEMORY_LIMIT] 首时丢掉最久没听的。 */
    suspend fun rememberLyric(songKey: String, update: (LyricMemoryEntry) -> LyricMemoryEntry) {
        if (songKey.isBlank()) return
        context.lyricMemoryStore.edit {
            val memory = decodeLyricMemory(it[lyricVisualizerMemoryKey])
            val entry = update(memory.remove(songKey) ?: LyricMemoryEntry())
            memory[songKey] = entry
            while (memory.size > LYRIC_MEMORY_LIMIT) memory.remove(memory.keys.first())
            it[lyricVisualizerMemoryKey] = json.encodeToString<Map<String, LyricMemoryEntry>>(memory)
        }
    }

    /** 记着随机结果（模式不为空）的歌有几首。 */
    suspend fun lyricMemoryModeCount(): Int =
        decodeLyricMemory(context.lyricMemoryStore.data.first()[lyricVisualizerMemoryKey]).values.count { it.mode.isNotEmpty() }

    /**
     * 忘掉所有随机结果：模式清空、测到的速度留着，只剩模式的整条删掉。返回“歌曲 key → 原来的模式”，撤销时交给 [restoreLyricModes]。
     */
    suspend fun forgetLyricModes(): Map<String, String> {
        val forgotten = LinkedHashMap<String, String>()
        context.lyricMemoryStore.edit {
            val memory = decodeLyricMemory(it[lyricVisualizerMemoryKey])
            val kept = LinkedHashMap<String, LyricMemoryEntry>()
            for ((key, entry) in memory) {
                if (entry.mode.isNotEmpty()) forgotten[key] = entry.mode
                if (entry.bpm > 0f) kept[key] = entry.copy(mode = "")
            }
            it[lyricVisualizerMemoryKey] = json.encodeToString<Map<String, LyricMemoryEntry>>(kept)
        }
        return forgotten
    }

    /** 撤销 [forgetLyricModes]：只写回现在模式还空着的歌（这期间重新抽过的不动），不改顺序，超出上限时丢最久的。 */
    suspend fun restoreLyricModes(modes: Map<String, String>) {
        if (modes.isEmpty()) return
        context.lyricMemoryStore.edit {
            val memory = decodeLyricMemory(it[lyricVisualizerMemoryKey])
            val restored = LinkedHashMap<String, LyricMemoryEntry>()
            // 按原来的先后排回去：撤销的条目在前（更早），这期间新记的在后
            for ((key, mode) in modes) {
                val current = memory[key]
                restored[key] = if (current == null) LyricMemoryEntry(mode = mode) else if (current.mode.isEmpty()) current.copy(mode = mode) else current
            }
            for ((key, entry) in memory) if (key !in restored) restored[key] = entry
            while (restored.size > LYRIC_MEMORY_LIMIT) restored.remove(restored.keys.first())
            it[lyricVisualizerMemoryKey] = json.encodeToString<Map<String, LyricMemoryEntry>>(restored)
        }
    }

    /** JSON 对象按写入顺序解码，最早的在前面。 */
    private fun decodeLyricMemory(raw: String?): LinkedHashMap<String, LyricMemoryEntry> =
        runCatching { LinkedHashMap(json.decodeFromString<Map<String, LyricMemoryEntry>>(raw ?: "{}")) }
            .getOrDefault(LinkedHashMap())

    suspend fun sodaSigner(url: String, token: String, enabled: Boolean = true) = context.dataStore.edit {
        it[sodaSignerUrlKey] = url.trim()
        it[sodaSignerTokenKey] = token.trim()
        it[sodaSignerEnabledKey] = enabled
    }

    suspend fun thirdPartyLyrics(enabled: Boolean) = context.dataStore.edit {
        it[thirdPartyLyricsKey] = enabled
    }

    suspend fun metadataFallback(enabled: Boolean) = context.dataStore.edit {
        it[metadataFallbackKey] = enabled
    }

    suspend fun cookie(provider: String, cookie: String) = context.dataStore.edit {
        val current = runCatching {
            json.decodeFromString<Map<String, String>>(it[cookiesKey] ?: "{}")
        }.getOrDefault(emptyMap()).toMutableMap()
        val updatedAt = runCatching {
            json.decodeFromString<Map<String, Long>>(it[cookieUpdatedAtKey] ?: "{}")
        }.getOrDefault(emptyMap()).toMutableMap()
        if (cookie.isBlank()) current.remove(provider) else current[provider] = cookie
        updatedAt[provider] = System.currentTimeMillis()
        it[cookiesKey] = json.encodeToString(current)
        it[cookieUpdatedAtKey] = json.encodeToString(updatedAt)
    }

    /** 只有当前值仍等于调用方读到的那一份才写入，避免迟到的认证探测覆盖删除或新账号配置。 */
    internal suspend fun cookieIfCurrent(provider: String, expectedRaw: String, cookie: String): Boolean {
        var written = false
        context.dataStore.edit {
            val current = runCatching {
                json.decodeFromString<Map<String, String>>(it[cookiesKey] ?: "{}")
            }.getOrDefault(emptyMap()).toMutableMap()
            if (current[provider] != expectedRaw) return@edit
            current[provider] = cookie
            val updatedAt = runCatching {
                json.decodeFromString<Map<String, Long>>(it[cookieUpdatedAtKey] ?: "{}")
            }.getOrDefault(emptyMap()).toMutableMap()
            updatedAt[provider] = System.currentTimeMillis()
            it[cookiesKey] = json.encodeToString(current)
            it[cookieUpdatedAtKey] = json.encodeToString(updatedAt)
            written = true
        }
        return written
    }

    /** 同步读取 Cookie 和修改时间，避免两次 DataStore 读取之间出现不一致。 */
    suspend fun cookieSnapshot(): CookieSnapshot {
        val values = context.dataStore.data.first()
        return CookieSnapshot(
            cookies = runCatching {
                json.decodeFromString<Map<String, String>>(values[cookiesKey] ?: "{}")
            }.getOrDefault(emptyMap()),
            updatedAt = runCatching {
                json.decodeFromString<Map<String, Long>>(values[cookieUpdatedAtKey] ?: "{}")
            }.getOrDefault(emptyMap()),
        )
    }

    /**
     * 应用同步合并结果。updatedAt 里出现但 active 里没有的平台视为已退出登录。
     * removeAbsent 为 true 时（用云端覆盖本机）连未出现在快照里的平台一起清掉。
     */
    suspend fun applyCookies(
        active: Map<String, String>,
        updatedAt: Map<String, Long>,
        removeAbsent: Boolean,
    ) = context.dataStore.edit {
        val current = runCatching {
            json.decodeFromString<Map<String, String>>(it[cookiesKey] ?: "{}")
        }.getOrDefault(emptyMap()).toMutableMap()
        val times = runCatching {
            json.decodeFromString<Map<String, Long>>(it[cookieUpdatedAtKey] ?: "{}")
        }.getOrDefault(emptyMap()).toMutableMap()
        if (removeAbsent) {
            current.keys.filterNot { provider -> provider in updatedAt }.forEach { current.remove(it) }
            times.keys.filterNot { provider -> provider in updatedAt }.forEach { times.remove(it) }
        }
        updatedAt.forEach { (provider, time) ->
            if (active.containsKey(provider)) {
                current[provider] = active.getValue(provider)
            } else {
                current.remove(provider)
            }
            times[provider] = time
        }
        it[cookiesKey] = json.encodeToString(current)
        it[cookieUpdatedAtKey] = json.encodeToString(times)
    }

    suspend fun saveAggregation(value: AggregationSettings) = context.dataStore.edit {
        it[aggregationKey] = json.encodeToString(value)
    }

    /** 设置 › 本地音乐：在 edit 里读旧值再改，扫描写“上次扫描时间”和用户改开关同时发生也不会互相覆盖。 */
    suspend fun localMusic(transform: (LocalMusicSettings) -> LocalMusicSettings) = context.dataStore.edit {
        val current = runCatching { json.decodeFromString<LocalMusicSettings>(it[localMusicKey] ?: "") }
            .getOrDefault(LocalMusicSettings())
        it[localMusicKey] = json.encodeToString(transform(current))
    }

    suspend fun saveEqualizer(value: EqualizerSettings) = context.dataStore.edit {
        it[equalizerEnabledKey] = value.enabled
        it[equalizerPresetKey] = value.presetId
        it[equalizerBandsKey] = json.encodeToString(value.customBands)
    }

    suspend fun addSearchHistory(query: String) = context.dataStore.edit {
        val value = query.trim()
        if (value.isBlank()) return@edit
        val current = runCatching {
            json.decodeFromString<List<String>>(it[searchHistoryKey] ?: "[]")
        }.getOrDefault(emptyList()).toMutableList()
        current.remove(value)
        current.add(0, value)
        it[searchHistoryKey] = json.encodeToString(current.take(20))
    }

    suspend fun removeSearchHistory(query: String) = context.dataStore.edit {
        val current = runCatching {
            json.decodeFromString<List<String>>(it[searchHistoryKey] ?: "[]")
        }.getOrDefault(emptyList()).filterNot { item -> item == query }
        it[searchHistoryKey] = json.encodeToString(current)
    }

    suspend fun clearSearchHistory() = context.dataStore.edit { it[searchHistoryKey] = "[]" }

    suspend fun lastUpdateCheck(value: Long) = context.dataStore.edit { it[lastUpdateCheckKey] = value }
}

data class CookieSnapshot(
    val cookies: Map<String, String> = emptyMap(),
    val updatedAt: Map<String, Long> = emptyMap(),
)

/**
 * 汽水的一整套设备身份：扫码登录的护照身份、播放上报/业务请求的设备指纹，
 * 连它们上次变化的时间（同步按时间判新旧）。三份并成一份，两台设备在服务端眼里
 * 才是同一台设备。
 */
data class SodaIdentitySnapshot(
    val deviceId: String = "",
    val installId: String = "",
    val reportDeviceId: String = "",
    val updatedAt: Long = 0L,
)

/**
 * 网易云播放上报的设备号，连它上次变化的时间（同步按时间判新旧）。
 * 它和 [com.mytv.mymusic.data.sync.SyncCookie] 里那条网易云 Cookie 是同一套凭据：
 * 值直接拼进 Cookie 头和 `MUSIC_U` 一起发，服务端按它归并「最近播放 / 听歌排行」。
 */
data class NeteaseIdentitySnapshot(
    val deviceId: String = "",
    val updatedAt: Long = 0L,
)

data class AppSettings(
    val provider: String = "netease",
    val chartsSource: String = "",
    val playlistsSource: String = "",
    /** 冷启动打开的顶层入口（TopTab.route），空表示默认「榜单」。 */
    val startupTab: String = "",
    /** 歌单页上次用的浏览方式（PlaylistMode 名字），空表示默认「推荐」。 */
    val playlistsMode: String = "",
    /** 听歌模式页上次选的分组，空表示「全部」。 */
    val listenGroup: String = "",
    val theme: String = "system",
    /** 界面布局：Auto / Phone / Tablet / Tv。 */
    val layoutMode: String = "Auto",
    val cookies: Map<String, String> = emptyMap(),
    /**
     * 汽水签名器（sodam / libmssdk 的 /sign 协议）。地址和 Token 留空表示用内置的
     * SodaM 公共签名服务；[sodaSignerEnabled] 关掉后只用公开试听流。
     */
    val sodaSignerUrl: String = "",
    val sodaSignerToken: String = "",
    val sodaSignerEnabled: Boolean = true,
    /**
     * Apple Music 匿名歌词是否走第三方中转（zarz / paxsenix / AMLL）。默认开；
     * 关掉后 Apple 歌词只试 Apple 自己的接口，匿名用户等于没有歌词。
     */
    val thirdPartyLyrics: Boolean = true,
    /** 原平台无词或缺封面时，用公共歌词库和公开封面搜索补全。 */
    val metadataFallback: Boolean = true,
    val aggregation: AggregationSettings = AggregationSettings(),
    /** 设置 › 本地音乐（扫描范围、匹配、播放兜底）。 */
    val localMusic: LocalMusicSettings = LocalMusicSettings(),
    val dynamicColor: Boolean = false,
    val reduceMotion: Boolean = false,
    val volumeNormalization: Boolean = false,
    /** 播放音质偏好：`auto` 或某个 `AudioQuality.id`。平台不支持所选档位时按 auto 处理。 */
    val audioQuality: String = "auto",
    val showBottomNav: Boolean = true,
    val lyricsBackground: LyricsBackgroundMode = LyricsBackgroundMode.Ambient,
    /** 系统媒体播放条第二行显示当前歌词（顶掉歌手名）；默认关。 */
    val notificationLyrics: Boolean = false,
    /** 系统悬浮窗歌词；默认关，开启前还要取得系统悬浮窗权限。 */
    val floatingLyricsEnabled: Boolean = false,
    /** 词幕 Provider 默认开；Subscriber 只在用户主动打开后连接。两项都只存在本机。 */
    val lyriconProviderEnabled: Boolean = true,
    val lyriconSubscriberEnabled: Boolean = false,
    val rhythmIntensity: RhythmIntensity = RhythmIntensity.Standard,
    val coverShape: CoverShapeChoice = CoverShapeChoice.Random,
    /** 电视环境模式右上角是否显示时钟。 */
    val tvAmbientClock: Boolean = true,
    /** 折叠屏桌面姿态播放页的上半屏：默认歌词。 */
    val tabletopTop: TabletopTop = TabletopTop.Lyrics,
    /**
     * 电视播放时给窗口挂 FLAG_KEEP_SCREEN_ON：屏幕不超时，系统屏保就不会启动。
     * 默认关：屏幕会一直亮着，OLED 有烧屏风险；息屏听歌、离开应用都不受影响。
     */
    val keepScreenOn: Boolean = false,
    /**
     * 电视上离开应用后继续播放（后台播放）。默认关：按 Home、切到别的应用、退出应用都会暂停，
     * 和手机、平板相反——那边一直是后台播放。
     */
    val tvBackgroundPlay: Boolean = false,
    /** 跟拍校准：律动画面相对“听到的位置”再晚多少毫秒（负数提前），−200..200。 */
    val rhythmOffsetMs: Int = 0,
    /** 播放页角落显示跟拍调试信息（只在可调试的安装包里能打开）。 */
    val rhythmDebug: Boolean = false,
    /** 海报墙的墙内设置，队列墙和详情页海报视图共用。 */
    val poster: PosterPrefs = PosterPrefs(),
    /** 歌曲列表左右滑的按钮和拉到头（设置 › 滑动操作）。 */
    val swipe: SwipePrefs = SwipePrefs(),
    /** 歌词分享页上次用的样式、配色等选项。 */
    val share: SharePrefs = SharePrefs(),
    /** 详情页上次用的视图（列表 / 海报），不单独做设置项。 */
    val collectionView: CollectionView = CollectionView.List,
    /** 点按迷你播放器打开播放页还是海报墙（设置 › 播放页）。 */
    val miniPlayerTarget: MiniPlayerTarget = MiniPlayerTarget.Player,
    /** 播放上报总开关：关掉后任何平台都不再回传播放记录。 */
    val playReportEnabled: Boolean = true,
    /** 汽水上报用的设备标识，空表示还没生成过（见 SettingsStore.sodaReportDeviceId）。 */
    val sodaReportDeviceId: String = "",
    /**
     * 汽水扫码登录的护照设备身份（`device_id` / `install_id`），空表示还没生成过
     * （见 SettingsStore.sodaLoginDevice）。和设备指纹 [sodaReportDeviceId] 是两套东西。
     */
    val sodaLoginDeviceId: String = "",
    val sodaLoginInstallId: String = "",
    /** 汽水设备身份上次变化的时间；同步按它判新旧（见 SettingsStore.adoptSodaIdentity）。 */
    val sodaLoginDeviceUpdatedAt: Long = 0L,
    /** 酷狗扫码登录的设备身份种子（见 SettingsStore.kugouLoginDevice）。 */
    val kugouLoginDevice: String = "",
    /** 网易云播放上报的设备号，空表示还没生成过（见 SettingsStore.neteaseWeblogDevice）。 */
    val neteaseWeblogDevice: String = "",
    /** 网易云上报设备号上次变化的时间；同步按它判新旧（见 adoptNeteaseWeblogDevice）。 */
    val neteaseWeblogDeviceUpdatedAt: Long = 0L,
    /** MHub 登录时上报的设备号（见 SettingsStore.mhubLoginDevice）。 */
    val mhubLoginDevice: String = "",
    /** 酷狗概念版「听歌领会员」是否在启动时自动领一天。 */
    val kugouWelfareAuto: Boolean = true,
    /** 上次自动领取的日期（北京时间 yyyy-MM-dd），空表示还没自动领过。 */
    val kugouWelfareLastClaimDate: String = "",
    /** 全屏歌词动画。 */
    val lyricStage: LyricStagePrefs = LyricStagePrefs(),
    val lyricFonts: LyricFontPrefs = LyricFontPrefs(),
    /** 歌词文字变换（拆单行双语、简转繁、补罗马音）。 */
    val lyricText: LyricTextPrefs = LyricTextPrefs(),
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val searchHistory: List<String> = emptyList(),
    val lastUpdateCheck: Long = 0L,
    val colorSource: String = "Cover",
    val customSeed: Int = 0xFF006B5F.toInt(),
    val browseStyle: String = "TonalSpot",
    val playerStyle: String = "Vibrant",
    val contrast: Float = 0f,
    /** 界面文字缩放倍数（1 为标准），见 Appearance.fontScale。 */
    val fontScale: Float = 1f,
    val amoled: Boolean = false,
    val blurMode: String = "Auto",
    val lastSeed: Int = 0,
    val sleepFade: Boolean = true,
    /** 切歌过渡总开关：自动接下一首时把上一首的结尾和下一首的开头叠起来。 */
    val crossfadeEnabled: Boolean = false,
    /** 交叉淡化的时长（秒），只在这一步用；智能过渡在后续阶段自己决定长度。 */
    val crossfadeSeconds: Int = 6,
    /** 播放页上画不画这次过渡：`Off` / `Ring` / `Outline`，见 [CrossfadeAnimation]。 */
    val crossfadeAnimation: String = "Ring",
    /** 交叉淡化还是智能过渡，见 [CrossfadeMode]。 */
    val crossfadeMode: String = "Automix",
    /** 播放缓存上限（MB），0 表示按设备默认：电视 256，其他 512。 */
    val cacheLimitMb: Int = 0,
    /** 更新检查与下载用哪条 GitHub 线路。 */
    val githubMirror: GithubMirror = GithubMirror.Auto,
)

// 同一首歌可以同时在“喜欢”和“最近播放”里，所以主键是 (key, bucket)
@Entity(tableName = "saved_songs", primaryKeys = ["key", "bucket"])
data class SavedSong(val key: String, val bucket: String, val payload: String, val updatedAt: Long)

/** 取消收藏的墓碑，用来把删除同步到其他设备。 */
@Entity(tableName = "saved_song_tombstones", primaryKeys = ["key", "bucket"])
data class SavedSongTombstone(
    val key: String,
    val bucket: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
)

/** 下载记录状态。进行中的记录只活在当前进程里，进程重启后由 ViewModel 清理。 */
const val DOWNLOAD_ACTIVE = "下载中"
const val DOWNLOAD_FAILED = "下载失败"
const val DOWNLOAD_DONE = "已完成"

@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey val key: String,
    val payload: String,
    val path: String,
    val state: String,
    val updatedAt: Long,
    /** 失败原因，只对 [DOWNLOAD_FAILED] 有意义。 */
    val error: String = "",
)

/** 切歌过渡的离线分析结果：节拍网格、响度、调性、首尾和段落，按曲目 key 存。 */
@Entity(tableName = "automix_analysis")
data class AutomixAnalysisEntity(
    @PrimaryKey @ColumnInfo(name = "song_key") val songKey: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val bpm: Double,
    @ColumnInfo(name = "beat_period_s") val beatPeriodS: Double,
    @ColumnInfo(name = "beat_t0_s") val beatT0S: Double,
    @ColumnInfo(name = "beat_k0") val beatK0: Long,
    @ColumnInfo(name = "beat_confidence") val beatConfidence: Float,
    val downbeat: Int,
    @ColumnInfo(name = "downbeat_clear") val downbeatClear: Boolean,
    @ColumnInfo(name = "key_index") val keyIndex: Int,
    @ColumnInfo(name = "key_minor") val keyMinor: Boolean,
    @ColumnInfo(name = "key_confidence") val keyConfidence: Float,
    @ColumnInfo(name = "loudness_db") val loudnessDb: Float,
    @ColumnInfo(name = "intro_ms") val introMs: Long,
    @ColumnInfo(name = "outro_ms") val outroMs: Long,
    /** `起点毫秒:能量,起点毫秒:能量`，能量 0..1；空表示没分段。 */
    val sections: String,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val dirty: Boolean = true,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    /** 绑定的平台歌单：provider + 平台歌单 id。非空表示这个歌单跟随平台更新。 */
    @ColumnInfo(name = "link_provider", defaultValue = "") val linkProvider: String = "",
    @ColumnInfo(name = "link_id", defaultValue = "") val linkId: String = "",
    /** 平台歌单封面；绑定时封面拼贴优先用它。 */
    @ColumnInfo(name = "link_cover", defaultValue = "") val linkCover: String = "",
    /** 上次成功从平台刷新的时间，用于节流。 */
    @ColumnInfo(name = "link_synced_at", defaultValue = "0") val linkSyncedAt: Long = 0L,
) {
    val bound get() = linkProvider.isNotBlank() && linkId.isNotBlank()
}

@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlist_id", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["playlist_id"], name = "idx_entries_playlist")],
)
data class PlaylistEntryEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val position: Int,
    @ColumnInfo(name = "song_key") val songKey: String,
    val payload: String,
)

@Entity(tableName = "sync_accounts")
data class SyncAccountEntity(
    @PrimaryKey val provider: String,
    @ColumnInfo(name = "remote_id") val remoteId: String = "",
    @ColumnInfo(name = "file_name") val fileName: String = "mymusic-library.json",
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long = 0L,
    @ColumnInfo(name = "remote_revision") val remoteRevision: String = "",
    @ColumnInfo(name = "auto_sync") val autoSync: Boolean = true,
    @ColumnInfo(name = "wifi_only") val wifiOnly: Boolean = false,
    @ColumnInfo(name = "remote_url") val remoteUrl: String = "",
    @ColumnInfo(name = "account_label") val accountLabel: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)

@Entity(tableName = "sync_base")
data class SyncBaseEntity(
    @PrimaryKey @ColumnInfo(name = "account_id") val accountId: String,
    val snapshot: String,
    @ColumnInfo(name = "remote_revision") val remoteRevision: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "sync_conflicts")
data class SyncConflictEntity(
    @PrimaryKey @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "local_snapshot") val localSnapshot: String,
    @ColumnInfo(name = "remote_snapshot") val remoteSnapshot: String,
    @ColumnInfo(name = "base_snapshot") val baseSnapshot: String,
    @ColumnInfo(name = "detected_at") val detectedAt: Long,
)

@Entity(tableName = "canonical_tracks")
data class CanonicalTrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artists: String,
    val album: String,
    @ColumnInfo(name = "duration_sec") val durationSec: Int,
    val version: String,
    val cover: String,
    val confidence: Float,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "canonical_sources",
    foreignKeys = [
        ForeignKey(
            entity = CanonicalTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["canonical_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["canonical_id"], name = "idx_sources_canonical"),
        Index(value = ["provider_id"], name = "idx_sources_provider"),
    ],
)
data class CanonicalSourceEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    val payload: String,
    @ColumnInfo(name = "match_score")
    val matchScore: Float,
    @ColumnInfo(name = "match_method")
    val matchMethod: String,
    val availability: String,
    @ColumnInfo(name = "is_preview")
    val isPreview: Boolean,
    @ColumnInfo(name = "checked_at")
    val checkedAt: Long,
)

@Entity(tableName = "match_cache", primaryKeys = ["source_key", "target_provider"])
data class MatchCacheEntity(
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "target_provider")
    val targetProvider: String,
    @ColumnInfo(name = "target_key")
    val targetKey: String,
    val score: Float,
    val payload: String,
    @ColumnInfo(name = "checked_at")
    val checkedAt: Long,
)

@Entity(tableName = "track_overrides")
data class TrackOverrideEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String,
    val action: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Dao
interface MusicDao {
    @Query("SELECT * FROM saved_songs WHERE bucket = :bucket ORDER BY updatedAt DESC")
    fun songs(bucket: String): Flow<List<SavedSong>>
    @Query("SELECT * FROM saved_songs WHERE bucket = :bucket ORDER BY updatedAt DESC")
    suspend fun songsOnce(bucket: String): List<SavedSong>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(song: SavedSong)
    @Query("DELETE FROM saved_songs WHERE key = :key AND bucket = :bucket") suspend fun remove(bucket: String, key: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveTombstone(tombstone: SavedSongTombstone)
    @Query("DELETE FROM saved_song_tombstones WHERE key = :key AND bucket = :bucket")
    suspend fun clearTombstone(bucket: String, key: String)
    @Query("SELECT * FROM saved_song_tombstones WHERE bucket = :bucket")
    suspend fun tombstones(bucket: String): List<SavedSongTombstone>
    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC") fun downloads(): Flow<List<DownloadRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDownload(record: DownloadRecord)
    @Query("DELETE FROM downloads WHERE key = :key") suspend fun deleteDownload(key: String)
    @Query("SELECT * FROM automix_analysis WHERE song_key = :key LIMIT 1")
    suspend fun automixAnalysis(key: String): AutomixAnalysisEntity?
    @Upsert suspend fun saveAutomixAnalysis(entry: AutomixAnalysisEntity)
    @Query("DELETE FROM automix_analysis WHERE updated_at < :cutoff")
    suspend fun pruneAutomixAnalysis(cutoff: Long)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM playlists WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    fun playlists(): Flow<List<PlaylistEntity>>
    @Query("SELECT * FROM playlists WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    suspend fun playlistsOnce(): List<PlaylistEntity>
    @Query("SELECT * FROM playlists")
    suspend fun allPlaylistsOnce(): List<PlaylistEntity>
    @Query("SELECT * FROM playlists WHERE id = :id")
    fun playlist(id: String): Flow<PlaylistEntity?>
    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun playlistOnce(id: String): PlaylistEntity?
    @Query("SELECT * FROM playlist_entries WHERE playlist_id = :id ORDER BY position")
    fun entries(id: String): Flow<List<PlaylistEntryEntity>>
    @Query("SELECT * FROM playlist_entries WHERE playlist_id = :id ORDER BY position")
    suspend fun entriesOnce(id: String): List<PlaylistEntryEntity>
    @Query("SELECT * FROM playlist_entries ORDER BY playlist_id, position")
    suspend fun allEntriesOnce(): List<PlaylistEntryEntity>
    @Query("SELECT playlist_id, COUNT(*) AS total FROM playlist_entries GROUP BY playlist_id")
    fun entryCounts(): Flow<List<PlaylistEntryCount>>
    @Query("SELECT COUNT(*) FROM playlist_entries")
    suspend fun entryCount(): Int
    /** 哪些（没删的）歌单里有这几个 key 之一：播放页「加到歌单」按钮和面板的勾选态。 */
    @Query(
        "SELECT DISTINCT e.playlist_id FROM playlist_entries e JOIN playlists p ON p.id = e.playlist_id " +
            "WHERE p.deleted_at IS NULL AND e.song_key IN (:keys)"
    )
    fun playlistsWithSongs(keys: List<String>): Flow<List<String>>
    // 必须 upsert 不能 INSERT OR REPLACE：REPLACE 先删旧行再插入，playlist_entries 的
    // ON DELETE CASCADE 会把整份曲目连带删掉（刷新绑定歌单、解除绑定都会把歌曲清空）
    @Upsert suspend fun putPlaylist(playlist: PlaylistEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEntries(entries: List<PlaylistEntryEntity>)
    @Query("DELETE FROM playlist_entries WHERE playlist_id = :id") suspend fun clearEntries(id: String)
    @Query("UPDATE playlists SET name = :name, updated_at = :time, dirty = 1 WHERE id = :id")
    suspend fun renamePlaylist(id: String, name: String, time: Long)
    @Query("UPDATE playlists SET dirty = 1, updated_at = :time WHERE id = :id")
    suspend fun markDirty(id: String, time: Long)
    @Query("UPDATE playlists SET dirty = :dirty, updated_at = :time WHERE id = :id")
    suspend fun markClean(id: String, time: Long, dirty: Boolean = false)
    @Query("UPDATE playlists SET deleted_at = :time, updated_at = :time, dirty = 1 WHERE id = :id")
    suspend fun softDelete(id: String, time: Long)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: String)
    @Query("DELETE FROM playlists WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun pruneDeleted(cutoff: Long)
    @Query("DELETE FROM playlists")
    suspend fun clearPlaylists()

    @Transaction
    suspend fun replaceEntries(id: String, entries: List<PlaylistEntryEntity>) {
        clearEntries(id)
        if (entries.isNotEmpty()) putEntries(entries)
    }
}

data class PlaylistEntryCount(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val total: Int,
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_accounts ORDER BY updated_at DESC")
    fun accounts(): Flow<List<SyncAccountEntity>>
    @Query("SELECT * FROM sync_accounts LIMIT 1")
    suspend fun accountOnce(): SyncAccountEntity?
    @Query("SELECT * FROM sync_accounts WHERE provider = :provider")
    suspend fun account(provider: String): SyncAccountEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccount(account: SyncAccountEntity)
    @Query("DELETE FROM sync_accounts") suspend fun clearAccounts()
    @Query("SELECT * FROM sync_base WHERE account_id = :id")
    suspend fun base(id: String): SyncBaseEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBase(base: SyncBaseEntity)
    @Query("DELETE FROM sync_base") suspend fun clearBases()
    @Query("SELECT * FROM sync_conflicts ORDER BY detected_at DESC")
    fun conflicts(): Flow<List<SyncConflictEntity>>
    @Query("SELECT * FROM sync_conflicts ORDER BY detected_at DESC")
    suspend fun conflictsOnce(): List<SyncConflictEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConflict(conflict: SyncConflictEntity)
    @Query("DELETE FROM sync_conflicts WHERE playlist_id = :id") suspend fun deleteConflict(id: String)
    @Query("DELETE FROM sync_conflicts") suspend fun clearConflicts()
}

@Dao
interface AggregateDao {
    // 同 putPlaylist：REPLACE 会级联删掉这个曲目下的 canonical_sources
    @Upsert suspend fun saveTrack(track: CanonicalTrackEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSources(sources: List<CanonicalSourceEntity>)
    @Query("SELECT * FROM canonical_sources WHERE canonical_id = :canonicalId ORDER BY provider_id")
    suspend fun sources(canonicalId: String): List<CanonicalSourceEntity>
    @Query("SELECT * FROM canonical_sources WHERE source_key = :sourceKey LIMIT 1")
    suspend fun source(sourceKey: String): CanonicalSourceEntity?
    @Query("SELECT * FROM match_cache WHERE source_key = :sourceKey AND target_provider = :targetProvider LIMIT 1")
    suspend fun match(sourceKey: String, targetProvider: String): MatchCacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveMatch(entry: MatchCacheEntity)
    @Query("DELETE FROM match_cache") suspend fun clearMatches()
    @Query("DELETE FROM match_cache WHERE checked_at < :cutoff") suspend fun pruneMatches(cutoff: Long)
    @Query("DELETE FROM canonical_tracks WHERE updated_at < :cutoff") suspend fun pruneTracks(cutoff: Long)
    @Query("SELECT * FROM track_overrides") suspend fun overrides(): List<TrackOverrideEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveOverride(entry: TrackOverrideEntity)
    @Query("DELETE FROM track_overrides") suspend fun clearOverrides()
}

@Database(
    entities = [
        SavedSong::class,
        SavedSongTombstone::class,
        DownloadRecord::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        SyncAccountEntity::class,
        SyncBaseEntity::class,
        SyncConflictEntity::class,
        CanonicalTrackEntity::class,
        CanonicalSourceEntity::class,
        MatchCacheEntity::class,
        TrackOverrideEntity::class,
        AutomixAnalysisEntity::class,
        LocalTrackEntity::class,
        LocalRefEntity::class,
        LocalRemoteEntity::class,
        LocalUploadEntity::class,
        SubsonicTrackEntity::class,
    ],
    version = 13,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): MusicDao
    abstract fun library(): LibraryDao
    abstract fun sync(): SyncDao
    abstract fun aggregate(): AggregateDao
    abstract fun localTracks(): LocalTrackDao
    abstract fun localRefs(): LocalRefDao
    abstract fun localUploads(): LocalUploadDao
    abstract fun subsonicTracks(): SubsonicTrackDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `canonical_tracks` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artists` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `duration_sec` INTEGER NOT NULL,
                        `version` TEXT NOT NULL,
                        `cover` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `canonical_sources` (
                        `source_key` TEXT NOT NULL,
                        `canonical_id` TEXT NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `match_score` REAL NOT NULL,
                        `match_method` TEXT NOT NULL,
                        `availability` TEXT NOT NULL,
                        `is_preview` INTEGER NOT NULL,
                        `checked_at` INTEGER NOT NULL,
                        PRIMARY KEY(`source_key`),
                        FOREIGN KEY(`canonical_id`) REFERENCES `canonical_tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sources_canonical` ON `canonical_sources` (`canonical_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sources_provider` ON `canonical_sources` (`provider_id`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `match_cache` (
                        `source_key` TEXT NOT NULL,
                        `target_provider` TEXT NOT NULL,
                        `target_key` TEXT NOT NULL,
                        `score` REAL NOT NULL,
                        `payload` TEXT NOT NULL,
                        `checked_at` INTEGER NOT NULL,
                        PRIMARY KEY(`source_key`, `target_provider`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `track_overrides` (
                        `source_key` TEXT NOT NULL,
                        `canonical_id` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`source_key`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlists` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `dirty` INTEGER NOT NULL,
                        `deleted_at` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_entries` (
                        `playlist_id` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `song_key` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        PRIMARY KEY(`playlist_id`, `position`),
                        FOREIGN KEY(`playlist_id`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_entries_playlist` ON `playlist_entries` (`playlist_id`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_accounts` (
                        `provider` TEXT NOT NULL,
                        `remote_id` TEXT NOT NULL,
                        `file_name` TEXT NOT NULL,
                        `last_synced_at` INTEGER NOT NULL,
                        `remote_revision` TEXT NOT NULL,
                        `auto_sync` INTEGER NOT NULL,
                        `wifi_only` INTEGER NOT NULL,
                        `remote_url` TEXT NOT NULL,
                        `account_label` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`provider`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_base` (
                        `account_id` TEXT NOT NULL,
                        `snapshot` TEXT NOT NULL,
                        `remote_revision` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`account_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_conflicts` (
                        `playlist_id` TEXT NOT NULL,
                        `local_snapshot` TEXT NOT NULL,
                        `remote_snapshot` TEXT NOT NULL,
                        `base_snapshot` TEXT NOT NULL,
                        `detected_at` INTEGER NOT NULL,
                        PRIMARY KEY(`playlist_id`)
                    )
                    """.trimIndent()
                )

                val json = Json { ignoreUnknownKeys = true }
                val now = System.currentTimeMillis()
                val cursor = db.query("SELECT id, name, songs FROM local_playlists ORDER BY id")
                cursor.use {
                    while (it.moveToNext()) {
                        val name = it.getString(1).ifBlank { "未命名歌单" }
                        val songs = runCatching {
                            json.decodeFromString<List<Song>>(it.getString(2) ?: "[]")
                        }.getOrDefault(emptyList())
                        val id = UUID.randomUUID().toString()
                        db.execSQL(
                            "INSERT INTO playlists (id, name, created_at, updated_at, dirty, deleted_at) VALUES (?, ?, ?, ?, 1, NULL)",
                            arrayOf<Any?>(id, name, now, now),
                        )
                        songs.forEachIndexed { index, song ->
                            db.execSQL(
                                "INSERT INTO playlist_entries (playlist_id, position, song_key, payload) VALUES (?, ?, ?, ?)",
                                arrayOf<Any?>(id, index, song.key, json.encodeToString(song)),
                            )
                        }
                    }
                }
            }
        }

        /** saved_songs 原来只用 key 做主键，写最近播放会把同一首歌的“喜欢”记录替换掉。 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_songs_new` (
                        `key` TEXT NOT NULL,
                        `bucket` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`, `bucket`)
                    )
                    """.trimIndent()
                )
                db.execSQL("INSERT OR REPLACE INTO `saved_songs_new` (`key`, `bucket`, `payload`, `updatedAt`) SELECT `key`, `bucket`, `payload`, `updatedAt` FROM `saved_songs`")
                db.execSQL("DROP TABLE `saved_songs`")
                db.execSQL("ALTER TABLE `saved_songs_new` RENAME TO `saved_songs`")
            }
        }

        /** 收藏同步需要墓碑表来传播取消收藏。 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_song_tombstones` (
                        `key` TEXT NOT NULL,
                        `bucket` TEXT NOT NULL,
                        `deleted_at` INTEGER NOT NULL,
                        PRIMARY KEY(`key`, `bucket`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** 平台歌单绑定：link_* 四列记录绑定的平台歌单和刷新时间。 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `link_provider` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `link_id` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `link_cover` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `link_synced_at` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `automix_analysis` (
                        `song_key` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        `bpm` REAL NOT NULL,
                        `beat_period_s` REAL NOT NULL,
                        `beat_t0_s` REAL NOT NULL,
                        `beat_k0` INTEGER NOT NULL,
                        `beat_confidence` REAL NOT NULL,
                        `downbeat` INTEGER NOT NULL,
                        `downbeat_clear` INTEGER NOT NULL,
                        `key_index` INTEGER NOT NULL,
                        `key_minor` INTEGER NOT NULL,
                        `key_confidence` REAL NOT NULL,
                        `loudness_db` REAL NOT NULL,
                        `intro_ms` INTEGER NOT NULL,
                        `outro_ms` INTEGER NOT NULL,
                        `sections` TEXT NOT NULL,
                        PRIMARY KEY(`song_key`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** 下载失败要把原因留在记录里，下载页才能提示为什么失败。 */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `error` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 本地音乐表。列全是 NOT NULL、不带 SQL 默认值：Room 打开库时按实体校验表结构，
         * 实体没写默认值这里也不能写，否则校验对不上。云端（remote_*）几列先建好，上传落地时不必再迁移。
         */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_tracks` (
                        `local_id` TEXT NOT NULL,
                        `uri` TEXT NOT NULL,
                        `origin` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `relative_path` TEXT NOT NULL,
                        `data_path` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `duration_sec` INTEGER NOT NULL,
                        `size` INTEGER NOT NULL,
                        `mime_type` TEXT NOT NULL,
                        `modified_at` INTEGER NOT NULL,
                        `added_at` INTEGER NOT NULL,
                        `bitrate` INTEGER NOT NULL,
                        `sample_rate` INTEGER NOT NULL,
                        `bits_per_sample` INTEGER NOT NULL,
                        `tags_read` INTEGER NOT NULL,
                        `cover_path` TEXT NOT NULL,
                        `lrc_uri` TEXT NOT NULL,
                        `missing` INTEGER NOT NULL,
                        `match_provider` TEXT NOT NULL,
                        `match_id` TEXT NOT NULL,
                        `match_confidence` REAL NOT NULL,
                        `match_method` TEXT NOT NULL,
                        `matched_at` INTEGER NOT NULL,
                        `match_payload` TEXT NOT NULL,
                        `remote_provider` TEXT NOT NULL,
                        `remote_id` TEXT NOT NULL,
                        `remote_playable_id` TEXT NOT NULL,
                        `remote_status` TEXT NOT NULL,
                        `remote_updated_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`local_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_local_tracks_uri` ON `local_tracks` (`uri`)")
            }
        }

        /**
         * 本地歌的跨设备线索（同步 v3）：local_refs 记文件名、大小、文件所在设备、匹配；
         * local_remotes 记云端副本，一首歌在几个云端各一行。只建新表，不碰已有数据。
         */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_refs` (
                        `local_id` TEXT NOT NULL,
                        `file_name` TEXT NOT NULL,
                        `file_size` INTEGER NOT NULL,
                        `device` TEXT NOT NULL,
                        `match_provider` TEXT NOT NULL,
                        `match_id` TEXT NOT NULL,
                        `match_confidence` REAL NOT NULL,
                        `match_method` TEXT NOT NULL,
                        `matched_at` INTEGER NOT NULL,
                        `match_payload` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`local_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_remotes` (
                        `local_id` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `remote_id` TEXT NOT NULL,
                        `playable_id` TEXT NOT NULL,
                        `file_name` TEXT NOT NULL,
                        `file_size` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `uploaded_at` TEXT NOT NULL,
                        `ext` TEXT NOT NULL,
                        `md5` TEXT NOT NULL,
                        `cloud_song_id` TEXT NOT NULL,
                        `check_state` TEXT NOT NULL,
                        `checked_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`local_id`, `provider`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * 本地歌的上传队列（cloud_uploads）：进程被杀、重启后按它把还没传完的放回来。
         * 只记身份、位置、状态和失败原因，不放音频；只建新表。
         */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_uploads` (
                        `local_id` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `error_kind` TEXT NOT NULL,
                        `error_message` TEXT NOT NULL,
                        `error_code` INTEGER NOT NULL,
                        `attempts` INTEGER NOT NULL,
                        PRIMARY KEY(`local_id`, `provider`)
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `subsonic_tracks` (
                        `account_id` TEXT NOT NULL,
                        `song_id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `album_id` TEXT NOT NULL,
                        `duration` INTEGER NOT NULL,
                        `size` INTEGER NOT NULL,
                        `bitrate` INTEGER NOT NULL,
                        `suffix` TEXT NOT NULL,
                        `cover_art` TEXT NOT NULL,
                        `extra_json` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`account_id`, `song_id`)
                    )
                    """.trimIndent()
                )
            }
        }

        internal val ALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)

        fun create(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "mymusic.db")
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }
}
