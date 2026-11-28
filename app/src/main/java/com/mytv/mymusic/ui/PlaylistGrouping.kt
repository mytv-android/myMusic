package com.mytv.mymusic.ui

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.local.LOCAL_SOURCE
import com.mytv.mymusic.data.lx.LxPlatform

/** 歌单分组 id：未绑定平台的歌单归本地组（空串），绑定了的按平台 id 分。 */
internal fun playlistGroupOf(playlist: LocalPlaylistSummary): String =
    if (playlist.bound) playlist.linkProvider else ""

/** 音源 id → 歌单分组 id：本地文件归本地歌单组，其余平台 id 原样。 */
internal fun playlistGroupOfSource(source: String): String = if (source == LOCAL_SOURCE) "" else source

/**
 * 「加到歌单」面板打开时默认落在哪个分组（候选 id，空串是本地）。
 *
 * 正在播放的这首按**实际取流源**算（换到 QQ 取流就落 QQ 组）；`lx` 只是取流通道，
 * 自己不会绑定平台歌单，所以退回歌曲原本所属的平台（[primarySong]，即聚合里的主来源）。
 * 不在播放的歌（列表里对别的歌点「加到歌单」）只用它自己的来源，不借用当前播放源。
 *
 * 这只决定面板打开时停在哪一组：加入 / 移出写回哪个平台仍按歌单绑定走，和这里不是一套规则。
 */
internal fun defaultPlaylistGroup(
    song: Song,
    currentSong: Song?,
    currentSourceId: String?,
    primarySong: Song?,
): String {
    val playing = currentSong != null && song.key.isNotBlank() && currentSong.key == song.key
    if (!playing) return playlistGroupOfSource(song.source)
    // lx 不参与分组：换成 lx 取流时看切换前那首歌原本属于哪个平台
    val streaming = currentSourceId?.takeIf { it.isNotBlank() && it != LxPlatform.PROVIDER_ID }
    if (streaming != null) return playlistGroupOfSource(streaming)
    return playlistGroupOfSource(primarySong?.source ?: song.source)
}

/**
 * 把候选分组落到实际存在的 tab 上：候选没有歌单（或算不出来）先退本地组；
 * 本地也没有未绑定歌单时落到「全部」（null），保证打开面板时总有一个合法的选中项。
 */
internal fun resolvePlaylistGroup(candidate: String?, groups: Collection<String>): String? {
    if (!candidate.isNullOrBlank() && candidate in groups) return candidate
    if ("" in groups) return ""
    return null
}
