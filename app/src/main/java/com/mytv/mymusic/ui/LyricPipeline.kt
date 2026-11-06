package com.mytv.mymusic.ui

import android.os.Build
import androidx.annotation.RequiresApi
import com.mytv.mymusic.data.LyricSubline
import com.mytv.mymusic.data.LyricTextPrefs

/**
 * 歌词文字变换：播放页、通知栏歌词、桌面小组件、海报墙都从这里拿歌词，几处看到的一致。
 * 和 Folium 的 lyrics.transform 一样，每次都从原始歌词重新算，关掉开关就回到原样，不保存改写后的结果。
 *
 * 顺序：解析（[LyricsParser]，三层同时间戳时中间那层存成罗马音）→ 拆单行双语 → 简转繁 → 补罗马音。
 * 每一步都是纯函数；简繁和转写交给 [ScriptConverter]，单元测试里换成假的。
 */
object LyricPipeline {
    fun prepare(raw: String, prefs: LyricTextPrefs, converter: ScriptConverter = SystemScriptConverter): ParsedLyrics {
        val parsed = LyricsParser.parse(raw)
        if (!parsed.synced || prefs == LyricTextPrefs()) return parsed
        var lines = parsed.lines
        if (prefs.splitBilingual) lines = splitBilingual(lines)
        if (prefs.traditional && converter.traditionalAvailable) lines = lines.map { toTraditional(it, converter) }
        if (prefs.autoRomanize && converter.romanizeAvailable) lines = lines.map { romanize(it, converter) }
        return if (lines === parsed.lines) parsed else parsed.copy(lines = lines)
    }

    // ---------- 拆单行双语（lyric-split-translation 的规则，按说明自己实现） ----------

    /** 候选分隔符，按优先级排；“//”要先于“/”认，免得一行里的“//”被当成两个“/”。 */
    private val Dividers = listOf("//", "／", "｜", "|", "/")

    /**
     * “原文 / 译文”写在同一行的歌词拆成原文和翻译。先整首评估：
     * 只用全曲出现最多的那种分隔符；系数 =（能拆的行 + 4）/（总行数 − 剔除的行）大于 0.8 才动手，
     * 剔除空行、只有分隔符的行、间奏标记和作词作曲这类署名行。整首判定是为了不误伤歌词本身里的斜杠，比如“AC/DC”。
     * 逐行：分隔符在行首或行尾的不拆，拆出来两段都要非空；已经有翻译的行不动。有逐字时间的行拆完后逐字时间只留原文那段。
     */
    fun splitBilingual(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return lines
        val divider = Dividers.maxByOrNull { d -> lines.count { splitPoint(it.text, d) != null } } ?: return lines
        val splittable = lines.count { it.translation.isBlank() && splitPoint(it.text, divider) != null }
        if (splittable == 0) return lines
        val excluded = lines.count { excludedFromSplit(it.text) }
        val total = (lines.size - excluded).coerceAtLeast(1)
        if ((splittable + 4).toFloat() / total <= SPLIT_THRESHOLD) return lines
        return lines.map { line ->
            if (line.translation.isNotBlank() || excludedFromSplit(line.text)) return@map line
            val at = splitPoint(line.text, divider) ?: return@map line
            val original = line.text.substring(0, at).trim()
            val translation = line.text.substring(at + divider.length).trim()
            line.copy(text = original, translation = translation, words = wordsWithin(line.words, original))
        }
    }

    /**
     * 这一行按 [divider] 拆的位置：只出现一次时就是它；出现多次时只认两边带空格的那一处（“原文 / 译文”），
     * 认不出就不拆。行首、行尾的分隔符不算。
     */
    internal fun splitPoint(text: String, divider: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val hits = ArrayList<Int>(2)
        var from = 0
        while (true) {
            val at = text.indexOf(divider, from)
            if (at < 0) break
            // “/”不认“//”里的那两个
            val partOfDouble = divider == "/" && (text.startsWith("//", at) || (at > 0 && text[at - 1] == '/'))
            if (!partOfDouble) hits += at
            from = at + divider.length
        }
        val at = when {
            hits.isEmpty() -> return null
            hits.size == 1 -> hits[0]
            else -> hits.singleOrNull { text.getOrNull(it - 1)?.isWhitespace() == true && text.getOrNull(it + divider.length)?.isWhitespace() == true }
                ?: return null
        }
        val before = text.substring(0, at).trim()
        val after = text.substring(at + divider.length).trim()
        if (before.isEmpty() || after.isEmpty()) return null
        if (Dividers.any { before == it || after == it }) return null
        return at
    }

    private val InterludeRegex = Regex("""^[\s♪♫♬…·.\-~～*＊]*$""")
    private val CreditRegex = Regex("""^\s*(作词|作詞|作曲|编曲|編曲|制作人|製作人|监制|監製|混音|母带|和声|吉他|贝斯|鼓|演唱|原唱|翻唱|词|曲|Lyrics|Composer|Arranger|Producer)\s*[:：]""", RegexOption.IGNORE_CASE)

    private fun excludedFromSplit(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (Dividers.any { d -> t.replace(d, "").isBlank() }) return true
        if (InterludeRegex.matches(t)) return true
        return CreditRegex.containsMatchIn(t)
    }

    /** 拆行后逐字时间只留原文那段：按字数从头数，正好落在原文末尾才保留，否则整行去掉逐字（按句显示）。 */
    private fun wordsWithin(words: List<LyricWord>, original: String): List<LyricWord> {
        if (words.isEmpty()) return words
        val target = original.filterNot { it.isWhitespace() }
        val kept = ArrayList<LyricWord>()
        val acc = StringBuilder()
        for (word in words) {
            if (acc.length >= target.length) break
            kept += word
            acc.append(word.text.filterNot { it.isWhitespace() })
        }
        return if (acc.toString() == target) kept else emptyList()
    }

    // ---------- 简转繁 ----------

    /** 原文和翻译都转；含假名的那段跳过，免得把日文汉字转坏。逐字的字也逐个转，整行文本按逐字重新拼。 */
    fun toTraditional(line: LyricLine, converter: ScriptConverter): LyricLine {
        val words = if (line.words.isNotEmpty() && !hasKana(line.text)) {
            line.words.map { it.copy(text = convertIfHan(it.text, converter)) }
        } else {
            line.words
        }
        val text = when {
            hasKana(line.text) -> line.text
            words !== line.words -> words.joinToString("") { it.text }.trim()
            else -> convertIfHan(line.text, converter)
        }
        val translation = if (hasKana(line.translation)) line.translation else convertIfHan(line.translation, converter)
        return if (text == line.text && translation == line.translation && words == line.words) line
        else line.copy(text = text, translation = translation, words = words)
    }

    private fun convertIfHan(text: String, converter: ScriptConverter): String =
        if (text.any(::isHan)) converter.toTraditional(text) else text

    // ---------- 罗马音 ----------

    /** 歌词自带罗马音就用它；没有时只给不含汉字、含韩文 / 假名 / 西里尔字母的行补上（汉字会被读成汉语拼音）。 */
    fun romanize(line: LyricLine, converter: ScriptConverter): LyricLine {
        if (line.romanization.isNotBlank()) return line
        val text = line.text
        if (text.any(::isHan) || !text.any(::needsRomanization)) return line
        val roman = converter.romanize(text).trim()
        return if (roman.isBlank() || roman == text.trim()) line else line.copy(romanization = roman)
    }

    internal fun isHan(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    internal fun hasKana(text: String): Boolean = text.any {
        val script = Character.UnicodeScript.of(it.code)
        script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
    }

    private fun needsRomanization(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.CYRILLIC -> true
        else -> false
    }

    private const val SPLIT_THRESHOLD = 0.8f
}

/** 简繁转换和罗马音转写。系统实现用 ICU；单元测试换成假的。 */
interface ScriptConverter {
    val traditionalAvailable: Boolean
    val romanizeAvailable: Boolean
    fun toTraditional(text: String): String
    fun romanize(text: String): String
}

/**
 * 系统自带的 ICU（android.icu.text.Transliterator，Android 10 起才有）。不加库、不加数据文件。
 * 简转繁是逐字的（“里”不会变成“裡”）；韩文、西里尔字母的拼法和常见写法不同。
 * 转写器实例不保证线程安全，调用时加锁；解析都在后台线程，量也不大。
 */
object SystemScriptConverter : ScriptConverter {
    override val traditionalAvailable: Boolean get() = Build.VERSION.SDK_INT >= 29
    override val romanizeAvailable: Boolean get() = Build.VERSION.SDK_INT >= 29

    private val lock = Any()
    private var hant: Any? = null
    private var latin: Any? = null

    override fun toTraditional(text: String): String {
        if (Build.VERSION.SDK_INT < 29) return text
        return synchronized(lock) { Icu.transliterate(hant ?: Icu.create("Hans-Hant").also { hant = it }, text) ?: text }
    }

    override fun romanize(text: String): String {
        if (Build.VERSION.SDK_INT < 29) return ""
        return synchronized(lock) {
            Icu.transliterate(latin ?: Icu.create(ROMANIZE_ID).also { latin = it }, text) ?: ""
        }
    }

    @RequiresApi(29)
    private object Icu {
        fun create(id: String): Any? = runCatching { android.icu.text.Transliterator.getInstance(id) }.getOrNull()
        fun transliterate(instance: Any?, text: String): String? =
            (instance as? android.icu.text.Transliterator)?.let { runCatching { it.transliterate(text) }.getOrNull() }
    }

    private const val ROMANIZE_ID = "Hiragana-Latin; Katakana-Latin; Hangul-Latin; Cyrillic-Latin"
}

/**
 * 经典列表、手机两行预览这类只认 [LyricLine.translation] 的地方，按“歌词下面那一行”把要显示的内容放进 translation：
 * 罗马音（没有就回退翻译）或两行都要。选“翻译”时原样返回。
 */
fun ParsedLyrics.withSubline(subline: LyricSubline): ParsedLyrics {
    if (subline == LyricSubline.Translation || lines.none { it.romanization.isNotBlank() }) return this
    return copy(
        lines = lines.map { line ->
            val shown = when (subline) {
                LyricSubline.Translation -> line.translation
                LyricSubline.Romanization -> line.romanization.ifBlank { line.translation }
                LyricSubline.Both -> listOf(line.romanization, line.translation).filter { it.isNotBlank() }.joinToString("\n")
            }
            if (shown == line.translation) line else line.copy(translation = shown)
        },
    )
}
