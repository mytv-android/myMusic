package com.mytv.mymusic.aggregate

import com.mytv.mymusic.data.Song
import java.text.Normalizer
import java.util.Locale

data class NormalizedTrack(
    val titleKey: String,
    val displayTitle: String,
    val artists: List<String>,
    val artistKeys: List<String>,
    val primaryArtistKey: String,
    val rawArtistKey: String,
    val albumKey: String,
    val version: VersionKind,
    val durationSec: Int,
    val durationReliable: Boolean,
    val blockKey: String,
    val isVip: Boolean,
    val providerId: String,
) {
    val artistKey: String get() = artistKeys.joinToString("|")
}

object TrackNormalizer {
    private val bracketRegex = Regex("""[\(\[（【][^\)\]）】]*[\)\]）】]""")
    private val featRegex = Regex("""(?i)(?:^|[\s\(（\[])(?:feat\.?|ft\.?|featuring)\s+([^\)\]）】]+)""")
    private val leadingTrackNo = Regex("""^\s*\d{1,3}\s*[.\-、]\s*""")
    private val multiSpace = Regex("""\s+""")
    private val noiseRegex = Regex("""(?i)\b(4k|8k|hd|mv|official\s*video|official\s*audio|lyric\s*video|visualizer|audio|video)\b|官方\s*mv|官方版|高清|完整版|无损|mv版|现场版""")
    private val dramaRegex = Regex("""(?i)[-–—]?\s*(电视剧|电影|网剧|动漫|动画|游戏)?\s*《[^》]*》\s*(主题曲|片尾曲|插曲|推广曲|宣传曲|原声带|ost)?|(电视剧|电影|网剧|动漫|动画|游戏)\s*[^,，。;；]*\s*(主题曲|片尾曲|插曲|推广曲|宣传曲|原声带|ost)""")
    private val artistSeparator = Regex("""\s*(?:/|、|,|，|;|；|&|＆|\bfeat\.?|\bft\.?|\bwith\b|\s+x\s+)\s*""", RegexOption.IGNORE_CASE)
    private val punctuation = Regex("""[^\p{L}\p{N}]+""")

    private val liveWord = Regex("""(?i)\blive\b|现场|演唱会|concert""")
    private val remixWord = Regex("""(?i)\bremix\b|混音|dj\s*版|club\s*mix""")
    private val instrumentalWord = Regex("""(?i)\binstrumental\b|伴奏|off\s*vocal|karaoke|纯音乐|纯伴奏""")
    private val acousticWord = Regex("""(?i)\bacoustic\b|unplugged|不插电""")
    private val coverWord = Regex("""(?i)\bcover\b|翻唱|女声版|男声版|童声版|钢琴版|吉他版|piano\s*version|guitar\s*version""")
    private val demoWord = Regex("""(?i)\bdemo\b|小样""")

    fun normalize(song: Song, providerId: String = song.source): NormalizedTrack {
        val rawTitle = song.name.trim().ifBlank { "未命名歌曲" }
        val rawArtist = song.artist.trim()
        val version = detectVersion(rawTitle)
        val titleArtists = extractFeatArtists(rawTitle)
        val artists = (splitArtists(rawArtist) + titleArtists).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val cleanedTitle = cleanTitle(rawTitle, artists)
        val titleKey = keyOf(cleanedTitle).ifBlank { keyOf(rawTitle) }
        val artistKeys = artists.map(::keyOf).filter { it.isNotBlank() }.distinct()
        val durationSec = song.duration.coerceAtLeast(0)
        val durationReliable = durationSec > 0 && !(providerId == "apple" && durationSec <= 35)
        val blockKey = if (titleKey.isBlank()) "" else titleKey.take(2) + ":" + durationBucket(durationSec, durationReliable)
        return NormalizedTrack(
            titleKey = titleKey,
            displayTitle = rawTitle,
            artists = artists,
            artistKeys = artistKeys,
            primaryArtistKey = artistKeys.firstOrNull().orEmpty(),
            rawArtistKey = keyOf(rawArtist),
            albumKey = keyOf(song.album),
            version = version,
            durationSec = durationSec,
            durationReliable = durationReliable,
            blockKey = blockKey,
            isVip = song.isVip,
            providerId = providerId,
        )
    }

    fun keyOf(value: String): String {
        if (value.isBlank()) return ""
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('“', '"')
            .replace('”', '"')
            .replace('—', '-')
            .replace('–', '-')
            .replace('－', '-')
            .replace("\u200B", "")
            .replace("\uFEFF", "")
        return punctuation.replace(normalized, "")
    }

    fun splitArtists(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return value.split(artistSeparator).map { it.trim() }.filter { it.isNotBlank() && !it.equals("未知歌手", true) }
    }

    fun detectVersion(value: String): VersionKind {
        val text = value.lowercase(Locale.ROOT)
        return when {
            liveWord.containsMatchIn(text) -> VersionKind.Live
            remixWord.containsMatchIn(text) -> VersionKind.Remix
            instrumentalWord.containsMatchIn(text) -> VersionKind.Instrumental
            acousticWord.containsMatchIn(text) -> VersionKind.Acoustic
            coverWord.containsMatchIn(text) -> VersionKind.Cover
            demoWord.containsMatchIn(text) -> VersionKind.Demo
            else -> VersionKind.Studio
        }
    }

    private fun extractFeatArtists(title: String): List<String> {
        val match = featRegex.find(title) ?: return emptyList()
        val tail = match.groupValues.getOrNull(1).orEmpty()
        return tail.split(artistSeparator).map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun cleanTitle(rawTitle: String, artists: List<String>): String {
        var title = Normalizer.normalize(rawTitle, Normalizer.Form.NFKC)
        title = leadingTrackNo.replace(title, "")
        title = featRegex.replace(title, " ")
        title = bracketRegex.replace(title, " ")
        title = noiseRegex.replace(title, " ")
        title = dramaRegex.replace(title, " ")
        title = title.replace('《', ' ').replace('》', ' ')
        title = title.replace(Regex("""(?i)[-–—]\s*(live|remix|acoustic|instrumental|demo|cover|伴奏|现场|翻唱|混音)\s*$"""), " ")
        title = title.replace(Regex("""(?i)\s*[-–—]\s*(remaster(ed)?|deluxe|explicit|clean|edition|version)\s*$"""), " ")
        title = title.replace(Regex("""\s*[-–—]\s*$"""), " ")
        title = multiSpace.replace(title, " ").trim()

        if (artists.isNotEmpty()) {
            val dashParts = title.split(Regex("""\s+[-–—]\s+""")).map { it.trim() }.filter { it.isNotBlank() }
            if (dashParts.size == 2) {
                val leftIsArtist = artists.any { keyOf(it) == keyOf(dashParts[0]) }
                val rightIsArtist = artists.any { keyOf(it) == keyOf(dashParts[1]) }
                title = when {
                    leftIsArtist && !rightIsArtist -> dashParts[1]
                    rightIsArtist && !leftIsArtist -> dashParts[0]
                    else -> title
                }
            }
            val artistKey = artists.map(::keyOf).firstOrNull { it.isNotBlank() && titleKeyStartsWithArtist(title, it) }
            if (artistKey != null) {
                val rawPrefix = artists.firstOrNull { keyOf(it) == artistKey }
                if (rawPrefix != null) title = title.removePrefix(rawPrefix).trim(' ', '-', '–', '—')
            }
        }
        return title.ifBlank { rawTitle }
    }

    private fun titleKeyStartsWithArtist(title: String, artistKey: String): Boolean {
        val titleKey = keyOf(title)
        return artistKey.isNotBlank() && titleKey.startsWith(artistKey) && titleKey.length > artistKey.length
    }

    private fun durationBucket(durationSec: Int, reliable: Boolean): Int {
        if (!reliable) return -1
        return (durationSec + 2) / 5 * 5
    }
}
