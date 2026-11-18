package com.mytv.mymusic.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * 歌词分享卡片的样式：前九种照 Rush 的 CardTheme（[rushName]），最后一种是我们自己的播放页样式。
 * [lines]：卡上放几句，null = 按选的句数（含省略行），0 = 不放歌词。名字（[name]）写进 DataStore，改名时不能动。
 */
enum class ShareCardStyle(val label: String, val rushName: String, val lines: Int?) {
    Spotify("色块", "Spotify", null),
    Rushed("叠卡", "Rushed", null),
    Vertical("竖排", "Vertical", null),
    Couplet("对句", "Couplet", 2),
    AlbumArt("封面", "Album Art", 0),
    Hypnotic("流光", "Hypnotic", null),
    Quote("引言", "Quote", 1),
    Messy("乱排", "Messy", 1),
    Brat("粗糙", "Brat", 1),
    Player("播放页", "本应用", null),
}

/** 卡片配色：鲜艳 / 柔和是封面色（Rush 的 VIBRANT / MUTED），播放页是 PlayerTheme 的表面色和文字色，自定义是两个色块。 */
enum class ShareCardPalette(val label: String) { Vibrant("鲜艳"), Muted("柔和"), Player("播放页"), Custom("自定义") }

/** 卡片圆角：照 Rush，直角 0px、圆角 32px（卡片按 720px 宽排版）。 */
enum class ShareCardCorners(val label: String, val px: Int) { Square("直角", 0), Rounded("圆角", 32) }

/** 封面形状，顺序照 Rush 的 AlbumArtShape（7 列 × 3 行正好排满）；多边形映射在 ui/player/share/ShareCardShapes.kt。 */
enum class ShareCardShape {
    Circle, Sunny, VerySunny, Square, Slanted, Arch, Pill, Pentagon, Gem,
    Cookie4, Cookie6, Cookie9, Cookie12, Clover4, Clover8, Ghostish, Flower, SoftBurst, PuffyDiamond, Bun, Rectangle,
}

/** 「复制文字」和系统分享里附带的文字。 */
enum class ShareTextFormat(val label: String) {
    Lyrics("只有歌词"),
    WithSong("歌词 ＋ 歌名 · 歌手"),
    WithLink("歌词 ＋ 歌名 · 歌手 ＋ 链接"),
}

/**
 * 歌词分享页的选项（分享页里点了就存，下次打开还是这一套）。只存在本机，不进同步。
 * 一次分享的会话状态（乱排的种子、自选背景图、粗糙卡自动切配色的记忆）不在这里。
 */
data class SharePrefs(
    val style: ShareCardStyle = ShareCardStyle.Rushed,
    val palette: ShareCardPalette = ShareCardPalette.Vibrant,
    val corners: ShareCardCorners = ShareCardCorners.Rounded,
    val shape: ShareCardShape = ShareCardShape.Cookie12,
    /** 卡片放进 1080×1920 的画布（Rush 的全屏分享）。 */
    val fullScreen: Boolean = false,
    /** 每句下面带一行副行（翻译 / 罗马音，和全屏歌词同一份）。 */
    val translation: Boolean = false,
    val textFormat: ShareTextFormat = ShareTextFormat.WithSong,
    /** 自定义配色的底色、字色（ARGB）；默认就是粗糙卡的荧光绿底黑字。 */
    val customBackground: Int = DEFAULT_CUSTOM_BACKGROUND,
    val customContent: Int = DEFAULT_CUSTOM_CONTENT,
) {
    companion object {
        const val DEFAULT_CUSTOM_BACKGROUND: Int = 0xFFD8F05A.toInt()
        const val DEFAULT_CUSTOM_CONTENT: Int = 0xFF14140F.toInt()
    }
}

/** DataStore 里的 share_* 键和编解码。纯函数，单测直接拿 mutablePreferencesOf() 往返。 */
object SharePrefsCodec {
    val style = stringPreferencesKey("share_style")
    val palette = stringPreferencesKey("share_palette")
    val corners = stringPreferencesKey("share_corners")
    val shape = stringPreferencesKey("share_shape")
    val fullScreen = booleanPreferencesKey("share_full")
    val translation = booleanPreferencesKey("share_translation")
    val textFormat = stringPreferencesKey("share_text_format")
    val customBackground = intPreferencesKey("share_custom_bg")
    val customContent = intPreferencesKey("share_custom_fg")

    /** 枚举按名字认，认不出（没写过、以后删掉的取值）取默认。 */
    fun read(values: Preferences): SharePrefs {
        val defaults = SharePrefs()
        return SharePrefs(
            style = enumOr(values[style], defaults.style),
            palette = enumOr(values[palette], defaults.palette),
            corners = enumOr(values[corners], defaults.corners),
            shape = enumOr(values[shape], defaults.shape),
            fullScreen = values[fullScreen] ?: defaults.fullScreen,
            translation = values[translation] ?: defaults.translation,
            textFormat = enumOr(values[textFormat], defaults.textFormat),
            customBackground = values[customBackground] ?: defaults.customBackground,
            customContent = values[customContent] ?: defaults.customContent,
        )
    }

    fun write(values: MutablePreferences, value: SharePrefs) {
        values[style] = value.style.name
        values[palette] = value.palette.name
        values[corners] = value.corners.name
        values[shape] = value.shape.name
        values[fullScreen] = value.fullScreen
        values[translation] = value.translation
        values[textFormat] = value.textFormat.name
        values[customBackground] = value.customBackground
        values[customContent] = value.customContent
    }

    private inline fun <reified E : Enum<E>> enumOr(raw: String?, fallback: E): E =
        raw?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: fallback
}
