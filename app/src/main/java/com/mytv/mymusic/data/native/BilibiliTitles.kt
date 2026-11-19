package com.mytv.mymusic.data.native

/** 一条 B 站稿件拆出来的曲目：歌名 + 歌手。歌手为空表示拆不出来。 */
internal data class BilibiliTrackTitle(val title: String, val artist: String)

/**
 * B 站没有「歌曲」这一层：搜索和合集里的每条都是一个稿件，标题是 UP 主手写的宣传文案，
 * 接口里的「歌手」那一栏实际是 UP 主名。聚合匹配按歌名 + 歌手打分，拿 UP 主名去比一律判
 * 「歌手不匹配」直接拒掉候选；而塞一个错的歌手比留空更糟，所以这里的取舍是：
 *
 *  - 能认出歌手就一定给（`周杰伦《晴天》`、`晴天-周杰伦`）；
 *  - 认不准就留空，交给歌名和时长去打分，别猜。
 *
 * 认得的写法：书名号里是歌名（`周杰伦——《晴天》`、`《晴天》- 周杰伦`）；`A - B` 干净的一侧是歌手；
 * `十年（陈奕迅）`、`陈奕迅演唱《十年》` 这类。
 */
/** B 站搜索接口的时长是 `4:30` / `1:02:03` 这种字符串，换成秒。 */
internal fun parseDurationText(text: String): Int {
    val parts = text.trim().split(':')
    if (parts.isEmpty() || parts.size > 3) return 0
    var seconds = 0
    for (part in parts) {
        val value = part.trim().toIntOrNull() ?: return 0
        seconds = seconds * 60 + value
    }
    return seconds
}

internal object BilibiliTitleParser {

    private val htmlTag = Regex("""</?[a-zA-Z][^>]*>""")
    private val bookTitle = Regex("""《([^》]{1,80})》""")
    private val bracket = Regex("""【[^】]*】|\[[^\]]*]|（[^）]*）|\([^)]*\)""")
    private val bracketInner = Regex("""【([^】]*)】|\[([^\]]*)]|（([^）]*)）|\(([^)]*)\)""")
    private val leadingBracket = Regex("""^\s*(?:【[^】]*】|\[[^\]]*]|（[^）]*）|\([^)]*\))\s*""")
    private val trailingBracket = Regex("""^(.*?)[（(]([^（）()]{1,16})[）)]$""")
    private val separator = Regex("""\s*(?:-{1,2}|—|–|~|丨|\||｜|/|／)\s*""")
    private val spaces = Regex("""\s+""")
    private val tokenSplit = Regex("""[\s\-—–~丨|｜/／:：、,，。!！?？"“”‘’()（）\[\]【】<>《》]+""")
    private val edge = " \t-—–~丨|｜:：、,，。·\"'“”‘’​"

    /** 画质、版本、演奏形式这类字样：出现在歌名一侧无妨（会被去掉），出现在歌手一侧说明这侧不是歌手。 */
    private val decoration = Regex(
        """(?i)\bhi-?\s*res\b|\bflac\b|\bmv\b|\bktv\b|\bdj\b|\bhd\b|\bremix\b|\bcover\b|\blive\b|""" +
            """\bversion\b|\bofficial\b|\bvideo\b|\blyric\b|\bstudio\b|\bmp[34]\b|\d+\s*[kp]\b|\d+\s*fps\b|""" +
            """2160p|1080p|4k|8k|无损音质|无损|高音质|音质|超清|高清|修复版?|完整版?|官方(?:版|mv)?|动态歌词|歌词|""" +
            """纯享|精选|合集|歌单|循环|现场|演唱会|自制版|鼓谱|简谱|吉他谱|贝斯谱|钢琴谱|琴谱|教程|""" +
            """演唱|原唱|伴奏|国语|粤语|英语|日语|韩语|版""",
    )

    /** 散文里高频、名字里几乎没有的字：出现就说明这枚 token 是句子，不是人名。 */
    private val proseChars = "的了是也都在很就都又还这那你我他她它们和与或而把被让给对从向为以及吗呢吧啊呀么"
    private val roleSuffix = Regex("""(?i)(?:演唱|翻唱|原唱|现场|live|cover)$""")
    private val uploadFileNoise = Regex("""(?i)^(?:studio_video_\d+|lv_\d+[^\s]*|[a-z0-9_.-]{16,})$""")

    /** 比标题用的键：去掉标点空格、统一大小写，只用来判断两段文字是不是同一首歌。 */
    fun keyOf(value: String): String = value.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")

    /** 分P稿件的 part 有时是上传的文件名，不是曲目名。 */
    fun isUploadFileName(value: String): Boolean {
        val text = value.trim()
        return text.isEmpty() || uploadFileNoise.matches(text)
    }

    fun parse(rawTitle: String, uploader: String): List<BilibiliTrackTitle> {
        val text = htmlTag.replace(rawTitle, " ").trim()
        if (text.isEmpty()) return listOf(BilibiliTrackTitle("", ""))

        bookTitle.find(text)?.let { book ->
            val inside = book.groupValues[1].split('/', '／').first()
            val before = text.substring(0, book.range.first)
            val after = text.substring(book.range.last + 1).substringBefore('《')
            // `《晴天》- 周杰伦`：书名号后面直接跟分隔符时歌手在后面；
            // 否则歌手通常紧挨在书名号前面（`周杰伦《晴天》`）
            val afterIsArtist = after.trimStart().let { it.isNotEmpty() && it.first() in "-—–~丨|｜" }
            val near = if (afterIsArtist) {
                artistNear(after, fromEnd = false) ?: artistNear(before, fromEnd = true)
            } else {
                artistNear(before, fromEnd = true) ?: artistNear(after, fromEnd = false)
            }
            val artist = near ?: bracketedArtist(before) ?: bracketedArtist(after)
            return listOf(BilibiliTrackTitle(titleOf(inside).ifBlank { titleOf(text) }, artist.orEmpty()))
        }

        var body = text
        while (true) {
            val match = leadingBracket.find(body) ?: break
            if (match.range.first != 0 || match.range.isEmpty()) break
            body = body.removeRange(match.range)
        }
        body = body.trim(*edge.toCharArray())
        if (body.isEmpty()) return listOf(BilibiliTrackTitle(titleOf(text), ""))

        val parts = separator.split(body).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 2) {
            val left = nameLike(parts[0])
            val right = nameLike(parts[1])
            return when {
                left != null && right == null -> listOf(BilibiliTrackTitle(titleOf(parts[1]), left))
                right != null && left == null -> listOf(BilibiliTrackTitle(titleOf(parts[0]), right))
                // 「晴天-周杰伦」和「周杰伦-晴天」都合法，两种都给，让匹配挑中的那个。
                // 两侧都通过了 nameLike，谁是歌手都可信
                else -> listOf(
                    BilibiliTrackTitle(titleOf(parts[0]), parts[1]),
                    BilibiliTrackTitle(titleOf(parts[1]), parts[0]),
                )
            }
        }
        if (parts.size > 2) {
            val artist = parts.drop(1).firstNotNullOfOrNull(::nameLike)
            return listOf(BilibiliTrackTitle(titleOf(parts[0]), artist.orEmpty()))
        }

        val trailing = trailingBracket.find(body)
        val trailingArtist = trailing?.let { nameLike(it.groupValues[2]) }
        if (trailing != null && trailingArtist != null) {
            return listOf(BilibiliTrackTitle(titleOf(trailing.groupValues[1]), trailingArtist))
        }

        // `晴天 周杰伦 动态鼓谱`：空格分段里第二段像人名
        val tokens = spaces.split(body).filter { it.isNotBlank() }
        val artist = tokens.drop(1).firstNotNullOfOrNull(::nameLike) ?: bracketedArtist(body)
        return listOf(BilibiliTrackTitle(titleOf(body), artist.orEmpty()))
    }

    /** 书名号一侧的文字里挑歌手：`fromEnd` 为真时从贴着书名号的那头往回找。 */
    private fun artistNear(raw: String, fromEnd: Boolean): String? {
        val tokens = tokenSplit.split(bracket.replace(raw, " ")).map { it.trim() }.filter { it.isNotEmpty() }
        return (if (fromEnd) tokens.asReversed() else tokens).firstNotNullOfOrNull(::nameLike)
    }

    /** 括号里像人名的当歌手：`十年【陈奕迅】`。 */
    private fun bracketedArtist(text: String): String? =
        bracketInner.findAll(text).firstNotNullOfOrNull { match ->
            match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.let(::nameLike)
        }

    /**
     * 一段文字能不能当歌手。宁可返回 null 让匹配只看歌名，也不要把「这才是」「国语」这类词塞进去——
     * 错的歌手会让整个候选被硬性拒绝，比留空差得多。
     */
    private fun nameLike(raw: String?): String? {
        if (raw == null) return null
        // 「陈奕迅演唱」「五月天现场」：结尾的动作词是描述，不是名字的一部分
        val text = roleSuffix.replace(raw, "").trim(*edge.toCharArray())
        if (text.isEmpty() || text.length > 16) return null
        if (text.any { it.isDigit() }) return null
        if (decoration.containsMatchIn(text)) return null
        if (text.any { it in proseChars }) return null
        if (text.any { it in "【】[]（）()《》<>|｜/:：、，。!！?？\"“”‘’" }) return null
        if (text.none { it.isLetter() }) return null
        return text
    }

    private fun titleOf(raw: String): String {
        val cleaned = decoration.replace(bracket.replace(raw, " "), " ")
            .replace(spaces, " ")
            .trim(*edge.toCharArray())
        return cleaned.ifBlank { raw.trim() }
    }
}
