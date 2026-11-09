package com.mytv.mymusic.data.native

/** 写平台歌单失败的原因；界面按它给不同的提示和下一步。 */
enum class PlaylistWriteFailure {
    /** 没登录或登录态过期。 */
    NotLoggedIn,

    /** 歌单不是当前账号创建的（收藏的别人的歌单）。 */
    NotOwner,

    /** 歌单里找不到某首歌，或者平台不认这个曲目 id。 */
    SongNotFound,

    /** 平台没有实现这个写操作。 */
    Unsupported,

    /** 网络、平台错误等其他失败。 */
    Other,
}

class PlaylistWriteException(
    message: String,
    val failure: PlaylistWriteFailure = PlaylistWriteFailure.Other,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
