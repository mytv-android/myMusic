package com.mytv.mymusic.data.local

import com.mytv.mymusic.data.Song
import java.util.Locale

/** 实际显示的标题、歌手、专辑。 */
data class LocalDisplay(val title: String, val artist: String, val album: String)

/**
 * 文件标签的判断：很多文件的标签是“Track 01 / 未知艺术家”，或者干脆就是文件名（媒体库没标签时拿文件名顶上）。
 * 这类“占位标签”既不能拿去平台搜索，也不该挡住匹配结果。
 */
object LocalTags {
    const val UNKNOWN_ARTIST = "未知歌手"

    private val junkTitle = Regex(
        """^(track|audio|untitled|unknown|未知|曲目|音轨|录音|新录音|音频|歌曲)[\s_\-#.]*\d*$""",
        RegexOption.IGNORE_CASE,
    )
    private val digits = Regex("""^[\d\s_\-.]+$""")
    private val junkArtists = setOf(
        "<unknown>", "unknown", "unknown artist", "未知", "未知艺术家", "未知歌手", "未知艺人",
        "various artists", "various", "群星", "artist",
    )

    fun baseName(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        return if (dot > 0) displayName.substring(0, dot) else displayName
    }

    fun isJunkTitle(title: String, displayName: String): Boolean {
        val value = title.trim()
        if (value.isEmpty() || value == "<unknown>") return true
        if (junkTitle.matches(value) || digits.matches(value)) return true
        // 媒体库没有标题标签时拿文件名（去掉扩展名）顶上，等于没有
        val base = baseName(displayName).trim()
        return base.isNotEmpty() && (value.equals(base, ignoreCase = true) || value.equals(displayName.trim(), ignoreCase = true))
    }

    fun isJunkArtist(artist: String): Boolean = artist.trim().lowercase(Locale.ROOT) in junkArtists || artist.isBlank()

    /** 标题和歌手都像样，才值得拿去平台按关键词搜。 */
    fun hasUsableTags(title: String, artist: String, displayName: String): Boolean =
        !isJunkTitle(title, displayName) && !isJunkArtist(artist)

    /**
     * 文件名常见的“歌手 - 歌名”。拆不出来时整个基础名当歌名、歌手为空。
     * 开头的音轨号（“01. ”“03 - ”）先去掉。
     */
    fun guessFromFileName(displayName: String): Pair<String, String> {
        val base = baseName(displayName).replace('_', ' ').trim()
            .replace(Regex("""^\d{1,3}\s*[.\-、)]\s*"""), "")
        val parts = base.split(Regex("""\s+[-–—]\s+|\s*[-–—]{2,}\s*"""), limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[1].trim() to parts[0].trim()
        } else {
            base to ""
        }
    }

    /** 「换一个匹配」的搜索框预填：标签像样用标签，否则用文件名猜的。 */
    fun searchQuery(title: String, artist: String, displayName: String): String {
        val goodTitle = !isJunkTitle(title, displayName)
        val goodArtist = !isJunkArtist(artist)
        if (goodTitle) return listOf(title.trim(), if (goodArtist) artist.trim() else "").filter { it.isNotEmpty() }.joinToString(" ")
        val (guessTitle, guessArtist) = guessFromFileName(displayName)
        return listOf(guessTitle, if (goodArtist) artist.trim() else guessArtist).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * 「换一个匹配」的预填：标签或文件名能用就用它们；两样都是占位（“Track 05.mp3”）而已经匹配上了，
     * 就用匹配到的歌名和歌手，好在其它平台找同一首。
     */
    fun rematchQuery(title: String, artist: String, displayName: String, match: Song?): String {
        val query = searchQuery(title, artist, displayName)
        val fileIsJunk = isJunkTitle(title, displayName) && isJunkTitle(guessFromFileName(displayName).first, "")
        if (!fileIsJunk || match == null || match.name.isBlank()) return query
        return listOf(match.name.trim(), match.artist.trim()).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * 文件自己什么都说不出、只能拿搜索词当参考时，把候选的歌手从搜索词里拿掉：
     * 搜“Aether Wisp X”时对 Wisp X 的歌就按《Aether》+ Wisp X 打分，不让歌手混进歌名把分数拉低。
     * 搜索词里没有候选的歌手就原样当歌名。
     */
    fun splitQuery(query: String, candidateArtist: String): Pair<String, String> {
        val text = query.trim()
        val hit = candidateArtist.split('/', '、', ',', '，', '&', ';', '；')
            .map { it.trim() }
            .filter { it.length >= 2 }
            .firstOrNull { text.contains(it, ignoreCase = true) }
            ?: return text to ""
        val title = text.replace(hit, " ", ignoreCase = true).replace(Regex("""\s+"""), " ").trim(' ', '-', '·')
        return if (title.isEmpty()) text to "" else title to hit
    }

    /**
     * 按「标题和歌手」设置决定显示什么。
     * - 文件：只用文件标签，没有标题时显示文件名；
     * - 只补空缺：标签是空的或占位的那一项换成匹配结果，像样的标签不覆盖；
     * - 匹配结果：匹配上了就整套用匹配结果。
     * 没匹配上的歌永远退回文件自己：标题缺就显示文件名（带扩展名，好认出是哪个文件）。
     */
    fun display(
        title: String,
        artist: String,
        album: String,
        displayName: String,
        match: Song?,
        policy: LocalTitlePolicy,
    ): LocalDisplay {
        val fileTitle = title.trim().takeUnless { it.isEmpty() || it == "<unknown>" }
        val fileArtist = artist.trim().takeUnless { it.isEmpty() || it == "<unknown>" }
        val fileAlbum = album.trim().takeUnless { it.isEmpty() || it == "<unknown>" }
        val fromFile = LocalDisplay(
            title = fileTitle ?: displayName,
            artist = fileArtist ?: UNKNOWN_ARTIST,
            album = fileAlbum.orEmpty(),
        )
        if (match == null) {
            // 没匹配：占位标题不如文件名好认
            return if (policy != LocalTitlePolicy.File && isJunkTitle(title, displayName)) fromFile.copy(title = displayName) else fromFile
        }
        return when (policy) {
            LocalTitlePolicy.File -> fromFile
            LocalTitlePolicy.Match -> LocalDisplay(
                title = match.name.ifBlank { fromFile.title },
                artist = match.artist.ifBlank { fromFile.artist },
                album = match.album.ifBlank { fromFile.album },
            )
            LocalTitlePolicy.FillGaps -> LocalDisplay(
                title = if (isJunkTitle(title, displayName)) match.name.ifBlank { fromFile.title } else fromFile.title,
                artist = if (isJunkArtist(artist)) match.artist.ifBlank { fromFile.artist } else fromFile.artist,
                album = fileAlbum ?: match.album,
            )
        }
    }
}
