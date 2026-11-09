package com.mytv.mymusic.data.local

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.cloud.CloudRef
import com.mytv.mymusic.data.cloud.CloudStream

/** 一首本地歌在这台设备上能从哪儿放（规格 3.4 / 10.1）。 */
sealed interface LocalPlayable {
    /** 本机文件：[uri] 是 content:// 地址，[song] 是带当前显示信息的那首。 */
    data class File(val uri: String, val song: Song) : LocalPlayable

    /**
     * 云端副本（网易云盘 / WebDAV / QQ 云盘）。[stream] 的地址是临时的，只交给播放器，不进歌单、不进同步；
     * WebDAV 的认证和证书钉住由播放服务里的路由工厂按地址接上。[song] 仍是这首本地歌（身份不变）。
     */
    data class Cloud(val stream: CloudStream, val ref: CloudRef, val song: Song) : LocalPlayable

    /**
     * 本机没有文件，用匹配到的平台版本（设置里「没有文件时用平台版本」开着）。
     * [note] 是前面云端副本没用上的原因（“云端文件已删除”等），提示和失败说明里带上，
     * 不然用户只看到换了版本、不知道云端那份出了什么事。
     */
    data class Platform(val song: Song, val confidence: Float, val note: String = "") : LocalPlayable

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
    /** 同步来的线索（文件所在设备、匹配）；这台设备上从没有过这首的线索时为空。 */
    val ref: LocalRefEntity? = null,
    /** 这首的云端副本，每个云端一行。 */
    val remotes: List<LocalRemoteEntity> = emptyList(),
    /** 同步来的匹配（没有文件的设备上用）；这台设备点过“不匹配”时不给。 */
    val refMatch: Song? = null,
    /** 跳过云端副本：刚放失败的是同一份内容（同样的格式问题），换到平台版本。 */
    val skipCloud: Boolean = false,
) {
    /** 各步放不了的原因，按发生顺序；最后都不行时挑第一条给用户看（“云端文件已删除”比“只在 X 上”具体）。 */
    val notes: MutableList<String> = mutableListOf()
}

/**
 * 解析链上的一步，返回 null 交给下一步。顺序是「本机文件 → 云端副本 → 平台版本」
 * （[LocalCloudStep]、[LocalPlatformStep]）；还要加步骤时用 [LocalLibrary.addResolveStep] 插在平台版本前面。
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
