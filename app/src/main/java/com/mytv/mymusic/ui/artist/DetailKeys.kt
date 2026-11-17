package com.mytv.mymusic.ui.artist

import com.mytv.mymusic.aggregate.TrackNormalizer
import com.mytv.mymusic.data.AlbumSummary
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.ui.CollectionKind

/** 详情页缓存最多留几页：够一条正常的返回栈用，太早的页被挤掉后返回时按 key 重新加载。 */
internal const val MAX_COLLECTION_PAGES = 16

/** 详情页 key：`类型|平台|id`。进路由前要 Uri.encode（id 里可能有 / 和 :）。 */
internal fun collectionKey(kind: CollectionKind, source: String, id: String) = "${kind.name}|$source|$id"

/** 拆 key：(类型, 平台, id)；认不出来返回 null。按名字找的专辑（`name:` 开头的 id）没有平台 id，不能直接重载。 */
internal fun parseCollectionKey(key: String): Triple<CollectionKind, String, String>? {
    val parts = key.split('|', limit = 3)
    if (parts.size != 3) return null
    val kind = CollectionKind.entries.find { it.name == parts[0] } ?: return null
    if (parts[1].isBlank() || parts[2].isBlank() || parts[2].startsWith("name:")) return null
    return Triple(kind, parts[1], parts[2])
}

/** 专辑头部有没有比名字、封面多的信息（发行时间、厂牌、类型、简介、歌手 id），有才值得在详情回来之前先显示。 */
internal fun AlbumSummary.hasHeaderInfo(): Boolean =
    publishTime.isNotBlank() || company.isNotBlank() || type.isNotBlank() || description.isNotBlank() || artistId.isNotBlank()

/** 专辑详情回来后和打开时带的头部合并：详情里有的用详情的，没有的保留原来的（歌手专辑列表里的类型等）。 */
internal fun mergeAlbumHeader(seed: AlbumSummary?, detail: AlbumSummary): AlbumSummary {
    if (seed == null) return detail
    return detail.copy(
        id = detail.id.ifBlank { seed.id },
        name = detail.name.ifBlank { seed.name },
        cover = detail.cover.ifBlank { seed.cover },
        artist = detail.artist.ifBlank { seed.artist },
        artistId = detail.artistId.ifBlank { seed.artistId },
        publishTime = detail.publishTime.ifBlank { seed.publishTime },
        type = detail.type.ifBlank { seed.type },
        trackCount = detail.trackCount.takeIf { it > 0 } ?: seed.trackCount,
        company = detail.company.ifBlank { seed.company },
        description = detail.description.ifBlank { seed.description },
        link = detail.link.ifBlank { seed.link },
    )
}

/**
 * 从专辑搜索结果里挑名字对得上的那张：专辑名规整后相同且歌手也对得上的最好，其次只有专辑名相同的，
 * 再次是名字互相包含的。都对不上返回 null——专辑搜错了比找不到更糟（会把别人的歌放出来）。
 */
internal fun pickAlbum(name: String, artist: String, candidates: List<Playlist>): Playlist? {
    val target = TrackNormalizer.keyOf(name)
    if (target.isEmpty() || candidates.isEmpty()) return null
    val artists = splitArtistNames(artist).map(TrackNormalizer::keyOf).filter { it.isNotEmpty() }
    fun artistMatches(item: Playlist): Boolean {
        if (artists.isEmpty()) return true
        val names = splitArtistNames(item.creator).map(TrackNormalizer::keyOf)
        return names.any { it in artists }
    }
    val sameName = candidates.filter { TrackNormalizer.keyOf(it.name) == target }
    return sameName.firstOrNull(::artistMatches)
        ?: sameName.firstOrNull()
        ?: candidates.firstOrNull {
            val key = TrackNormalizer.keyOf(it.name)
            key.isNotEmpty() && (key.contains(target) || target.contains(key)) && artistMatches(it)
        }
}
