package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.AlbumSummary
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.normalizePublishTime
import com.mytv.mymusic.data.toAlbumSummary

/** 歌手资料里“键：值”的值超过这个长度（主要成就、从艺历程这类长段落）就不放进 facts，简介里已经有了。 */
internal const val ARTIST_FACT_MAX_LENGTH = 60

private val BREAK_TAG = Regex("""<\s*br\s*/?\s*>""", RegexOption.IGNORE_CASE)
private val BLOCK_END_TAG = Regex("""<\s*/\s*(?:p|div|li|h[1-6]|tr)\s*>""", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("""</?[A-Za-z][^<>]*>""")
private val HTML_ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9A-Fa-f]{1,6}|[A-Za-z][A-Za-z0-9]{1,7});""")
private val INLINE_SPACE = Regex("""[ \t    　]+""")
private val NAMED_ENTITIES = mapOf(
    "nbsp" to " ",
    "ensp" to " ",
    "emsp" to " ",
    "thinsp" to " ",
    "amp" to "&",
    "quot" to "\"",
    "apos" to "'",
    "lt" to "<",
    "gt" to ">",
    "ldquo" to "“",
    "rdquo" to "”",
    "lsquo" to "‘",
    "rsquo" to "’",
    "mdash" to "—",
    "ndash" to "–",
    "hellip" to "…",
    "middot" to "·",
    "bull" to "•",
    "times" to "×",
    "copy" to "©",
    "reg" to "®",
    "trade" to "™",
    "yen" to "¥",
    "deg" to "°",
)

/**
 * 歌手简介 / 专辑介绍转纯文本：先解 HTML 实体（有的平台转义两层，解两遍），
 * `<br>`、`</p>` 这类换成换行，其余标签去掉；每行压掉多余空白，段落之间最多空一行。
 */
internal fun artistPlainText(raw: String): String {
    if (raw.isBlank()) return ""
    var text = raw.replace("\r\n", "\n").replace('\r', '\n')
    repeat(2) { text = decodeHtmlEntities(text) }
    text = BREAK_TAG.replace(text, "\n")
    text = BLOCK_END_TAG.replace(text, "\n")
    text = HTML_TAG.replace(text, "")
    val out = StringBuilder()
    var pendingBlank = false
    for (rawLine in text.split('\n')) {
        // 酷我的段落尾巴上会留下转义剩的反斜杠
        val line = INLINE_SPACE.replace(rawLine, " ").trim().trimEnd('\\').trim()
        if (line.isEmpty()) {
            if (out.isNotEmpty()) pendingBlank = true
            continue
        }
        if (out.isNotEmpty()) out.append(if (pendingBlank) "\n\n" else "\n")
        out.append(line)
        pendingBlank = false
    }
    return out.toString()
}

private fun decodeHtmlEntities(value: String): String {
    if ('&' !in value) return value
    return HTML_ENTITY.replace(value) { match ->
        val token = match.groupValues[1]
        val code = when {
            token.startsWith("#x") || token.startsWith("#X") -> token.substring(2).toIntOrNull(16)
            token.startsWith("#") -> token.substring(1).toIntOrNull()
            else -> null
        }
        when {
            code != null -> runCatching { String(Character.toChars(code)) }.getOrDefault(match.value)
            else -> NAMED_ENTITIES[token.lowercase()] ?: match.value
        }
    }
}

/** QQ 音乐歌手百科（GetSingerDetail 的 wiki 字段）解析结果。 */
internal data class QQSingerWiki(
    val description: String = "",
    val facts: List<Pair<String, String>> = emptyList(),
)

private val QQ_WIKI_DESC = Regex("""<desc>(.*?)</desc>""", RegexOption.DOT_MATCHES_ALL)
private val QQ_WIKI_BASIC = Regex("""<basic>(.*?)</basic>""", RegexOption.DOT_MATCHES_ALL)
private val QQ_WIKI_ITEM = Regex(
    """<item>\s*<key>(.*?)</key>\s*<value>(.*?)</value>\s*</item>""",
    RegexOption.DOT_MATCHES_ALL,
)
private val CDATA = Regex("""<!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL)

/**
 * QQ 歌手百科是一段 XML：`<info><desc>简介</desc><basic><item><key>外文名</key><value>…</value></item>…</basic>
 * <other>从艺历程、荣誉记录…</other></info>`，文字都包在 CDATA 里。简介取 desc，facts 只取 basic 里的短条目。
 */
internal fun parseQQSingerWiki(xml: String): QQSingerWiki {
    if (xml.isBlank()) return QQSingerWiki()
    val description = QQ_WIKI_DESC.find(xml)?.groupValues?.get(1)?.let { artistPlainText(unwrapCdata(it)) }.orEmpty()
    val basic = QQ_WIKI_BASIC.find(xml)?.groupValues?.get(1).orEmpty()
    val facts = QQ_WIKI_ITEM.findAll(basic).mapNotNull { match ->
        val key = artistPlainText(unwrapCdata(match.groupValues[1])).replace('\n', ' ')
        val value = artistPlainText(unwrapCdata(match.groupValues[2])).replace('\n', ' ')
        if (key.isEmpty() || value.isEmpty() || value.length > ARTIST_FACT_MAX_LENGTH) null else key to value
    }.toList()
    return QQSingerWiki(description, facts)
}

private fun unwrapCdata(value: String): String {
    val parts = CDATA.findAll(value).map { it.groupValues[1] }.toList()
    return if (parts.isEmpty()) value else parts.joinToString("")
}

/**
 * 其他平台的专辑 Playlist 键名不统一（release_date / publish_date / record_label…）：
 * 先按 [toAlbumSummary] 转，缺的发行时间、厂牌再从这些键里补。
 */
internal fun Playlist.toLooseAlbumSummary(): AlbumSummary {
    val summary = toAlbumSummary()
    fun firstExtra(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> extra[key]?.trim()?.takeIf { it.isNotEmpty() } }.orEmpty()
    return summary.copy(
        publishTime = summary.publishTime.ifBlank { normalizePublishTime(firstExtra("release_date", "publish_date")) },
        company = summary.company.ifBlank { firstExtra("record_label", "publish_company", "publish_corp") },
    )
}
