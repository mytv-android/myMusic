package com.mytv.mymusic.data

import kotlin.math.abs

/** 海报墙染色：当前封面取色得到的 surface 色盖在格子上的比例。 */
enum class PosterTint(val alpha: Float) {
    Off(0f),
    Weak(0.32f),
    Medium(0.52f),
    Strong(0.70f),
}

/** 歌单 / 专辑 / 榜单 / 本地歌单详情页用哪种视图，记住上次的选择（collection_view）。 */
enum class CollectionView { List, Poster }

/**
 * 点按迷你播放器（手机底栏、平板底部播放条、正在播放面板、电视导航栏的正在播放）打开什么（mini_player_target）。
 * 手机迷你播放器上滑、写着“打开播放页 / 全屏播放”的按钮不看它，总是打开播放页。
 */
enum class MiniPlayerTarget { Player, PosterWall }

/**
 * 墙内设置：队列海报墙和详情页海报视图共用一份，只存在本机，不进同步文件
 * （AppStore 的 poster_* 键；同步和导出的快照只有歌单、收藏和 Cookie）。
 */
data class PosterPrefs(
    val tint: PosterTint = PosterTint.Medium,
    /** 关灯：除正在播放的那首外，格子压 82% 黑。 */
    val lightsOut: Boolean = false,
    val showIndex: Boolean = true,
    val translation: Boolean = true,
    /** 切歌时镜头跟到新歌那一份并放大。 */
    val follow: Boolean = true,
    /** 电视空闲 30 秒后镜头缓慢漂移；只在电视上显示这个开关。 */
    val tvDrift: Boolean = true,
    /** 视角远近（格距倍数），手机、平板、电视各记一份。 */
    val zoom: PosterZoom = PosterZoom(),
)

/** 视角按哪一档存：按墙的格距档，不按窗口类型——横屏手机窗口算平板，但墙用手机格距，记在手机那份。 */
enum class ZoomScope { Phone, Tablet, Tv }

/**
 * 海报墙视角：格距乘的倍数，0.5（远）… 1.25（近），只停在 [STEPS] 四个刻度上。
 * 三种设备的屏幕差太多，各存一份（AppStore 的 poster_zoom_phone / tablet / tv），不进同步。
 */
data class PosterZoom(val phone: Float = 1f, val tablet: Float = 1f, val tv: Float = 1f) {
    fun of(scope: ZoomScope): Float = when (scope) {
        ZoomScope.Phone -> phone
        ZoomScope.Tablet -> tablet
        ZoomScope.Tv -> tv
    }

    /** 改一档的值；写之前先吸附到刻度。 */
    fun with(scope: ZoomScope, value: Float): PosterZoom {
        val v = snap(value)
        return when (scope) {
            ZoomScope.Phone -> copy(phone = v)
            ZoomScope.Tablet -> copy(tablet = v)
            ZoomScope.Tv -> copy(tv = v)
        }
    }

    companion object {
        val STEPS = listOf(0.5f, 0.75f, 1f, 1.25f)
        const val MIN = 0.5f
        const val MAX = 1.25f
        const val DEFAULT = 1f

        /** 吸附到最近的刻度；NaN、无穷当作默认 1×，越界落到两端。 */
        fun snap(value: Float): Float {
            if (value.isNaN() || value.isInfinite()) return DEFAULT
            return STEPS.minBy { abs(it - value) }
        }

        /** 上一档 / 下一档（电视左右键、滑块按键）；到头了停在端点。 */
        fun step(value: Float, delta: Int): Float {
            val i = STEPS.indexOf(snap(value))
            return STEPS[(i + delta).coerceIn(0, STEPS.lastIndex)]
        }
    }
}
