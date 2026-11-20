package com.mytv.mymusic.data

/** 平台歌曲评论。时间统一保留秒/毫秒原值，由界面按本机格式显示。 */
data class SongComment(
    val id: String,
    val userName: String,
    val avatar: String = "",
    val content: String,
    val likedCount: Long = 0,
    val liked: Boolean = false,
    val time: Long = 0,
    val timeText: String = "",
    val location: String = "",
)

/** [page] 从 1 开始；热门评论只在第一页返回。 */
data class SongCommentPage(
    val page: Int,
    val comments: List<SongComment>,
    val hotComments: List<SongComment> = emptyList(),
    val total: Long = 0,
    val hasMore: Boolean = false,
)
