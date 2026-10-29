package com.mytv.mymusic.data

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
)
