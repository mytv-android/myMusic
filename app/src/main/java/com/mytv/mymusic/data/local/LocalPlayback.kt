package com.mytv.mymusic.data.local

import com.mytv.mymusic.data.Song

/** 一首本地歌在这台设备上能从哪儿放（规格 3.4 / 10.1）。 */
sealed interface LocalPlayable {
    /** 本机文件：[uri] 是 content:// 地址，[song] 是带当前显示信息的那首。 */
    data class File(val uri: String, val song: Song) : LocalPlayable

    /** 本机没有文件，用匹配到的平台版本（设置里「没有文件时用平台版本」开着）。 */
    data class Platform(val song: Song, val confidence: Float) : LocalPlayable

    /**
     * 哪里都放不了。[reason] 直接给用户看；[searchable] 表示歌名歌手像样，
     * 调用方还可以按名字去平台找一首（只在 [fallbackAllowed] 时）。
     */
    data class Unavailable(val reason: String, val searchable: Boolean, val fallbackAllowed: Boolean) : LocalPlayable
}

/** 解析一步要用到的东西。[row] 为空表示这台设备上从没扫到过这首（别的设备加进歌单的）。 */
class LocalResolveRequest(
    val song: Song,
    val row: LocalTrackEntity?,
    val item: LocalItem?,
    val prefs: LocalMusicSettings,
    /** 跳过本机文件：文件刚放失败（格式不支持、权限被收回）时换源用，免得又拿回同一个文件。 */
    val skipFile: Boolean,
)

/**
 * 解析链上的一步，返回 null 交给下一步。现在是「本机文件 → 平台版本」两步；
 * 云盘 / WebDAV 副本（P5）用 [LocalLibrary.addResolveStep] 插在两者之间，不用改播放侧。
 */
fun interface LocalResolveStep {
    suspend fun resolve(request: LocalResolveRequest): LocalPlayable?
}

/** 歌单 / 收藏里一首本地歌在这台设备上的状态，画徽标用。 */
enum class LocalAvailability {
    /** 本机有文件。 */
    OnDevice,
    /** 扫到过，但文件不在了（删了、搬走后还没重扫、所在文件夹移出了扫描范围）。 */
    Missing,
    /** 这台设备上从没扫到过：别的设备加进来的。 */
    Elsewhere,
}
