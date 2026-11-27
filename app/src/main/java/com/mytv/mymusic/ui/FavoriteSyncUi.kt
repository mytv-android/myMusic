package com.mytv.mymusic.ui

import com.mytv.mymusic.data.FavoriteSyncIssue
import com.mytv.mymusic.data.native.PlaylistWriteException
import com.mytv.mymusic.data.native.PlaylistWriteFailure

/** 收藏写平台失败时给用户看的原因；平台自带的错误信息优先，避免都落成同一句通用提示。 */
internal fun favoriteFailureReason(error: Exception): String = when ((error as? PlaylistWriteException)?.failure) {
    PlaylistWriteFailure.NotLoggedIn -> "请重新登录"
    PlaylistWriteFailure.NotOwner -> "目标歌单已不可修改，请重新选择"
    PlaylistWriteFailure.SongNotFound -> "未找到对应的歌曲"
    PlaylistWriteFailure.Unsupported -> "目标歌单不支持此操作，请重新选择"
    else -> (error as? PlaylistWriteException)?.message?.takeIf { it.isNotBlank() }
        ?: (error as? FavoriteSyncIssue)?.message?.takeIf { it.isNotBlank() }
        ?: "请检查网络、登录和目标歌单"
}
