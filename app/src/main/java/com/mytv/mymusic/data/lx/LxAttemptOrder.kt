package com.mytv.mymusic.data.lx

import com.mytv.mymusic.data.Song

/** 统一洛雪候选项的展开顺序：脚本在外层，平台歌曲在内层。 */
internal fun orderedLxAttempts(
    records: List<LxSourceRecord>,
    songs: List<Song>,
    isFailed: (songKey: String, recordId: String) -> Boolean = { _, _ -> false },
): List<Pair<LxSourceRecord, Song>> {
    val candidates = songs.filter { LxPlatform.sourceKey(it.source) != null }.distinctBy { it.key }
    return buildList {
        for (record in records) {
            if (!record.enabled) continue
            for (song in candidates) {
                val source = LxPlatform.sourceKey(song.source) ?: continue
                if (isFailed(song.key, record.id)) continue
                // 桌面版插件没有平台能力表：任何平台的歌都让它试一次
                if (record.desktop || record.capabilities[source]?.actions?.contains("musicUrl") == true) {
                    add(record to song)
                }
            }
        }
    }
}
