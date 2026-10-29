package com.mytv.mymusic.ui.poster

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.ui.LyricLine
import com.mytv.mymusic.ui.LyricWord
import com.mytv.mymusic.ui.LyricsParser
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.player.KaraokeLine
import com.mytv.mymusic.ui.theme.LocalAppearance
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 大卡里的一句歌词：原文行和它的翻译。播放页把翻译当成同一时间戳的独立行列出（QQ、酷我把罗马音 / 翻译
 * 写在原文后面、用同一个时间戳），这里把它们并回原文下面。
 */
@Immutable
internal data class PosterLine(val line: LyricLine, val translation: String = "")

/** 定下来的字号（sp）：正文和翻译。整首歌只算一次（[posterLyricSize]）。 */
@Immutable
internal data class PosterLyricSize(val bodySp: Float, val translationSp: Float)

/**
 * 同一时间戳的连续几行并成一句：第一行是原文，后面的取最后一行当翻译（QQ 和酷我都按原文、罗马音、翻译的顺序写，
 * 只有罗马音时就显示罗马音）。只处理同步歌词；未同步的返回空，大卡按“没有歌词”显示。
 */
internal fun posterLines(lyrics: ParsedLyrics): List<PosterLine> {
    if (!lyrics.synced) return emptyList()
    val out = ArrayList<PosterLine>(lyrics.lines.size)
    var i = 0
    val lines = lyrics.lines
    while (i < lines.size) {
        val head = lines[i]
        var j = i + 1
        while (j < lines.size && lines[j].timeMs == head.timeMs) j++
        val translation = if (j - i > 1) lines[j - 1].text.trim() else ""
        out += PosterLine(head, translation.takeUnless { it == head.text.trim() }.orEmpty())
        i = j
    }
    return out
}

/** 正文行高（字号倍数），对照原型的 1.18。 */
internal const val PosterBodyLineHeight = 1.18f
/** 翻译行高。 */
internal const val PosterTranslationLineHeight = 1.3f
/** 前后行的缩放。 */
internal const val PosterContextScale = 0.72f
/** 行间距（正文字号倍数），对照原型的 0.42em。 */
internal const val PosterLineGapEm = 0.42f
/** 原文和翻译之间的间距（dp）。 */
internal const val PosterTranslationGapDp = 4f
/** 当前行中心在歌词框里的高度比例，和 folia 同值。 */
internal const val PosterLyricAnchor = 0.46f
/** 字号下限：大字号设置下歌词框放不下时也不小于它。 */
internal const val PosterMinLyricSp = 14f
internal const val PosterMinTranslationSp = 10f

/**
 * 字号预算：歌词框要放下“3 行正文 + 2 行上下文（缩到 [PosterContextScale]）+ 2 行翻译”加上两道行距和原文到翻译的间距，
 * 按框高 [boxPx] 反推能放下的最大正文字号，限在 [PosterMinLyricSp]..[maxSp]。翻译字号按 [maxTranslationSp]/[maxSp]
 * 的比例跟着正文走。这首歌没有翻译时不留翻译那两行。[pxPerSp] 是 density × fontScale，[pxPerDp] 是 density。
 * 只按高度算、不看歌词内容：同一首歌换行时字号不变。
 */
internal fun posterLyricSize(
    boxPx: Float,
    pxPerSp: Float,
    pxPerDp: Float,
    maxSp: Float,
    maxTranslationSp: Float,
    hasTranslation: Boolean,
): PosterLyricSize {
    val ratio = if (maxSp > 0f) maxTranslationSp / maxSp else 0f
    val minTranslation = min(PosterMinTranslationSp, maxTranslationSp)
    // 每 1sp 正文字号占多高（以 sp 计）：正文三行、上下文两行、两道行距
    val bodyPerSp = 3 * PosterBodyLineHeight + 2 * PosterBodyLineHeight * PosterContextScale + 2 * PosterLineGapEm
    val fit = when {
        pxPerSp <= 0f -> 0f
        !hasTranslation -> boxPx / (pxPerSp * bodyPerSp)
        else -> {
            val gapPx = PosterTranslationGapDp * pxPerDp
            // 翻译两行跟着正文按比例缩；缩到下限以下时翻译按下限占高，正文再让出这部分
            val scaled = (boxPx - gapPx) / (pxPerSp * (bodyPerSp + 2 * PosterTranslationLineHeight * ratio))
            if (scaled * ratio >= minTranslation) {
                scaled
            } else {
                (boxPx - gapPx - 2 * PosterTranslationLineHeight * minTranslation * pxPerSp) / (pxPerSp * bodyPerSp)
            }
        }
    }
    // 取 0.5sp 的整数倍，同一台设备上不同歌的字号不会差出零点几的碎值
    val floored = floor(fit * 2f) / 2f
    val body = floored.coerceAtMost(maxSp).coerceAtLeast(min(PosterMinLyricSp, maxSp))
    return PosterLyricSize(body, (body * ratio).coerceAtLeast(minTranslation))
}

/** 离当前行 [distance] 行（可以是小数，滚动途中连续变化）时的缩放：当前行 1，前后行 0.72，更远 0.65。 */
internal fun posterLineScale(distance: Float): Float {
    val d = distance.coerceAtLeast(0f)
    return when {
        d <= 1f -> 1f - (1f - PosterContextScale) * d
        d <= 2f -> PosterContextScale - (PosterContextScale - 0.65f) * (d - 1f)
        else -> 0.65f
    }
}

/** 透明度：当前行 1，前后行 0.62，再远 0.22（几乎看不见），第三行起为 0。 */
internal fun posterLineAlpha(distance: Float): Float {
    val d = distance.coerceAtLeast(0f)
    return when {
        d <= 1f -> 1f - 0.38f * d
        d <= 2f -> 0.62f - 0.4f * (d - 1f)
        d <= 3f -> 0.22f * (3f - d)
        else -> 0f
    }
}

/** 模糊半径（dp）：前后行 0.7，更远 1.2；只在允许模糊的设备上用。 */
internal fun posterLineBlurDp(distance: Float): Float {
    val d = distance.coerceAtLeast(0f)
    return when {
        d <= 1f -> 0.7f * d
        d <= 2f -> 0.7f + 0.5f * (d - 1f)
        else -> 1.2f
    }
}

/** 翻译只跟在当前行下面：离开当前行时随滚动收起、淡出。 */
internal fun posterTranslationReveal(distance: Float): Float = (1f - distance.coerceAtLeast(0f)).coerceIn(0f, 1f)

/**
 * 歌词框两端渐隐（对照原型的遮罩：顶上 0–16% 淡入，底下 80–100% 淡出），按行的中心 [center] 整行乘一个透明度。
 * 原型是整框一张渐变遮罩；那样当前行逐字填色时整框每帧都要离屏重合成，这里改成每行各自的透明度，只在图层里设。
 */
internal fun posterEdgeFade(center: Float, height: Float): Float {
    if (height <= 0f) return 1f
    val y = center / height
    return when {
        y < 0.16f -> (y / 0.16f).coerceIn(0f, 1f)
        y > 0.8f -> ((1f - y) / 0.2f).coerceIn(0f, 1f)
        else -> 1f
    }
}

/**
 * 滚动位置 [pos]（窗口里的行号，可以是小数）对应的锚点：前后两行中心按小数部分插值。
 * [tops]、[heights] 是窗口里各行此刻的顶边和视觉高度（px）。
 */
internal fun posterAnchorCenter(tops: FloatArray, heights: FloatArray, pos: Float): Float {
    if (tops.isEmpty()) return 0f
    val p = pos.coerceIn(0f, (tops.size - 1).toFloat())
    val k0 = floor(p).toInt()
    val k1 = min(k0 + 1, tops.size - 1)
    val c0 = tops[k0] + heights[k0] / 2f
    val c1 = tops[k1] + heights[k1] / 2f
    return c0 + (c1 - c0) * (p - k0)
}

/** 没有逐字时间轴的行，逐字填色扫完整行时长的这么多，和原型一致，留一点停顿再换行。 */
private const val LinearSweepShare = 0.8f
/** 逐字线性扫的上限：间奏前最后一句不会慢慢扫十几秒。 */
private const val LinearSweepMaxMsPerChar = 500L
/** 最后一句后面没有下一行时，按这么长扫。 */
private const val LastLineMs = 5_000L

/**
 * 当前行交给播放页的逐字填色（[KaraokeLine]）用的数据：有逐字时间轴（QRC / YRC / KRC）原样返回；
 * 没有的按整行时长线性分给每个字，下一行的开始（最后一行用歌曲时长）算行尾。
 */
internal fun posterKaraokeLine(lines: List<PosterLine>, index: Int, durationMs: Long): LyricLine {
    val line = lines[index].line
    if (line.words.isNotEmpty()) return line
    val next = lines.getOrNull(index + 1)?.line?.timeMs
    val end = when {
        next != null && next > line.timeMs -> next
        durationMs > line.timeMs -> durationMs
        else -> line.timeMs + LastLineMs
    }
    return line.copy(words = linearWords(line.text, line.timeMs, end))
}

/**
 * 把一行文字按字切成逐字数据，时长按字数平均分：中日韩文字一字一段，西文一个词一段（连着后面的空格），
 * 这样逐字填色的 FlowRow 换行时不会把一个英文词拆开。扫的总时长是行长的 [LinearSweepShare]，每字不超过 [LinearSweepMaxMsPerChar]。
 */
internal fun linearWords(text: String, startMs: Long, endMs: Long): List<LyricWord> {
    val tokens = ArrayList<String>()
    val weights = ArrayList<Int>()
    val current = StringBuilder()
    var weight = 0
    var wide = false
    var trailingSpace = false
    fun flush() {
        if (current.isNotEmpty()) {
            tokens += current.toString()
            weights += weight.coerceAtLeast(1)
        }
        current.clear()
        weight = 0
        trailingSpace = false
    }
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val n = Character.charCount(cp)
        if (Character.isWhitespace(cp)) {
            // 空白跟在前一段后面；行首的空白丢掉（播放页的整行文本也是去掉首尾空白的）
            if (current.isNotEmpty()) {
                current.appendCodePoint(cp)
                trailingSpace = true
            }
        } else {
            val isWide = cp >= WideCodePoint
            if (current.isNotEmpty() && (isWide || wide || trailingSpace)) flush()
            current.appendCodePoint(cp)
            weight++
            wide = isWide
        }
        i += n
    }
    flush()
    if (tokens.isEmpty()) return emptyList()
    val total = weights.sum()
    val span = ((endMs - startMs).coerceAtLeast(1L) * LinearSweepShare).toLong()
        .coerceAtMost(total * LinearSweepMaxMsPerChar)
        .coerceAtLeast(1L)
    var at = startMs
    var sum = 0
    return tokens.mapIndexed { index, token ->
        sum += weights[index]
        val end = startMs + span * sum / total
        LyricWord(at, end, token).also { at = end }
    }
}

/** 从 CJK 部首补充区起都按“宽字”一字一段：中日韩文字、全角标点、谚文。 */
private const val WideCodePoint = 0x2E80

/** 当前行前后各组合几行：滚一行时离开的那一行已经透明，进来的那一行还透明，不会闪。 */
private const val LyricWindow = 3

/**
 * 大卡中间的三行歌词：当前行锚在歌词框 [PosterLyricAnchor] 高处、逐字填色，前后行缩到 0.72、透明度 0.62、
 * 允许模糊时轻微模糊，更远的几乎看不见；翻译只跟在当前行下面（[showTranslation]）。
 * 字号按 [posterLyricSize] 在第一次量到歌词框时定下，整首歌（[lines] 不变）都用它。
 * 当前行序号由进度派生，只在换行时重组；换行的滚动、缩放、透明度、模糊都由一个空间弹簧驱动，只在摆放阶段读，逐帧不重组。
 * 逐字填色复用播放页的 [KaraokeLine]。[lines] 只传同步歌词（[posterLines] 的结果），为空时什么都不画。
 */
@Composable
internal fun PosterLyrics(
    lines: List<PosterLine>,
    position: State<Long>,
    duration: Long,
    playing: Boolean,
    showTranslation: Boolean,
    maxSp: Float,
    maxTranslationSp: Float,
    blur: Boolean,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return
    val density = LocalDensity.current
    val hasTranslation = remember(lines) { lines.any { it.translation.isNotEmpty() } }
    val starts = remember(lines) { lines.map { it.line } }
    // 进度每 500ms 变一次；派生状态只在换行时通知，读它的只有歌词列
    val active = remember(starts, position) { derivedStateOf { LyricsParser.activeLineIndex(starts, position.value) } }
    // 歌词框高度只在第一次量到、或者框变了大小时变（大卡按放大到位的尺寸排版，平时不变）
    val boxHeight = remember { mutableIntStateOf(0) }
    val measured by remember { derivedStateOf { boxHeight.intValue > 0 } }
    // 字号按这首歌（lines）第一次量到的框高定下，之后框高再变也不跟着变；只按“这首歌有没有翻译”留预算、
    // 不看翻译开关，在墙内设置里开关翻译时字号不跳
    val size = remember(lines, density, maxSp, maxTranslationSp, measured) {
        if (!measured) {
            null
        } else {
            posterLyricSize(
                // 不订阅框高：框高变了这里不重组，字号也不跟着变
                boxPx = Snapshot.withoutReadObservation { boxHeight.intValue }.toFloat(),
                pxPerSp = density.density * density.fontScale,
                pxPerDp = density.density,
                maxSp = maxSp,
                maxTranslationSp = maxTranslationSp,
                hasTranslation = hasTranslation,
            )
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { boxHeight.intValue = it.height },
    ) {
        // 第一次测量之后才知道字号，空一帧再组合，免得先按错的字号排一遍
        if (size != null) LyricColumn(lines, active, position, duration, playing, showTranslation, size, blur)
    }
}

@Composable
private fun LyricColumn(
    lines: List<PosterLine>,
    active: State<Int>,
    position: State<Long>,
    duration: Long,
    playing: Boolean,
    showTranslation: Boolean,
    size: PosterLyricSize,
    blur: Boolean,
) {
    val center = active.value.coerceIn(0, lines.lastIndex)
    val reduceMotion = LocalAppearance.current.reduceMotion
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    // 滚动位置（行号，可以是小数）：锚点、缩放、透明度、模糊、翻译的展开都从它算
    val scroll = remember(lines) { Animatable(center.toFloat()) }
    LaunchedEffect(scroll, center) {
        // 拖动进度这类一下跳好几行的，中间的行没组合过，直接定位
        if (reduceMotion || abs(center - scroll.value) >= LyricWindow) {
            scroll.snapTo(center.toFloat())
        } else {
            scroll.animateTo(center.toFloat(), spec)
        }
    }
    val typography = MaterialTheme.typography
    // 所有行都按当前行的字号排版、不放大，别的行在图层里缩小；缩放途中 Animated 让字形不按像素取整
    val body = remember(typography, size) {
        typography.headlineSmall.copy(
            fontSize = size.bodySp.sp,
            lineHeight = (size.bodySp * PosterBodyLineHeight).sp,
            letterSpacing = (-0.01).em,
            fontWeight = FontWeight.Black,
            textMotion = TextMotion.Animated,
        )
    }
    val translation = remember(typography, size) {
        typography.bodyMedium.copy(
            fontSize = size.translationSp.sp,
            lineHeight = (size.translationSp * PosterTranslationLineHeight).sp,
            fontWeight = FontWeight.SemiBold,
            textMotion = TextMotion.Animated,
        )
    }
    // 播放 / 暂停只有当前行的逐字填色关心
    val playingState = rememberUpdatedState(playing)
    val first = (center - LyricWindow).coerceAtLeast(0)
    val last = (center + LyricWindow).coerceAtMost(lines.lastIndex)
    Layout(
        content = {
            // 每行固定两个节点：原文、翻译（没有或关掉时是空占位），摆放时按下标成对取
            for (index in first..last) {
                key(index) {
                    LyricText(lines, index, index == center, position, duration, playingState, body)
                    val text = lines[index].translation
                    if (showTranslation && text.isNotEmpty()) {
                        Text(
                            text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == center) Modifier else Modifier.clearAndSetSemantics {}),
                            style = translation,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Spacer(Modifier)
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val child = Constraints(maxWidth = width)
        val placeables = measurables.map { it.measure(child) }
        val gapPx = (size.bodySp * PosterLineGapEm).sp.toPx()
        val translationGapPx = PosterTranslationGapDp.dp.toPx()
        val maxBlurPx = if (blur) 1.dp.toPx() else 0f
        val origin = if (layoutDirection == LayoutDirection.Rtl) TransformOrigin(1f, 0f) else TransformOrigin(0f, 0f)
        val count = placeables.size / 2
        layout(width, height) {
            // 只在摆放阶段读滚动位置：弹簧逐帧只重新摆放、更新图层，不重新测量、不重组
            val pos = scroll.value
            val tops = FloatArray(count)
            val heights = FloatArray(count)
            var y = 0f
            for (k in 0 until count) {
                val d = abs(first + k - pos)
                val text = placeables[k * 2]
                val tr = placeables[k * 2 + 1]
                val reveal = if (tr.height > 0) posterTranslationReveal(d) else 0f
                tops[k] = y
                heights[k] = (text.height + (translationGapPx + tr.height) * reveal) * posterLineScale(d)
                y += heights[k] + gapPx
            }
            val offset = height * PosterLyricAnchor - posterAnchorCenter(tops, heights, pos - first)
            for (k in 0 until count) {
                val d = abs(first + k - pos)
                val scale = posterLineScale(d)
                val top = offset + tops[k]
                val alpha = posterLineAlpha(d) * posterEdgeFade(top + heights[k] / 2f, height.toFloat())
                // 1dp 按原型的 0.7 / 1.2 缩放；不允许模糊（电视、Android 12 以下）时只降透明度
                val blurPx = posterLineBlurDp(d) * maxBlurPx
                val text = placeables[k * 2]
                val tr = placeables[k * 2 + 1]
                // 逐字填色是暗字、亮字两层叠着画，透明度要整体乘（离屏），否则淡入途中暗字会透出来
                val karaoke = first + k == center
                text.placeWithLayer(0, top.roundToInt()) { lyricLayer(scale, alpha, blurPx, origin, karaoke) }
                if (tr.height > 0) {
                    val reveal = posterTranslationReveal(d)
                    tr.placeWithLayer(0, (top + (text.height + translationGapPx) * scale).roundToInt()) {
                        lyricLayer(scale, alpha * reveal, blurPx, origin, layered = false)
                    }
                }
            }
        }
    }
}

/**
 * 一行歌词的图层：缩放、透明度、模糊。[layered] 为 true（逐字填色的当前行）时按默认策略合成，透明度小于 1 时离屏；
 * 其余不模糊的行把透明度直接乘到绘制上，0.62 / 0.22 的行不用各开一个离屏图层。当前行停稳后透明度是 1，也不离屏。
 */
private fun GraphicsLayerScope.lyricLayer(scale: Float, alpha: Float, blurPx: Float, origin: TransformOrigin, layered: Boolean) {
    scaleX = scale
    scaleY = scale
    transformOrigin = origin
    this.alpha = alpha
    val blurred = blurPx > 0.05f
    renderEffect = if (blurred) BlurEffect(blurPx, blurPx, TileMode.Decal) else null
    compositingStrategy = if (blurred || layered) CompositingStrategy.Auto else CompositingStrategy.ModulateAlpha
}

/** 原文：当前行交给逐字填色，别的行是整行文本（字重一样，变成当前行时不重新折行）；只有当前行给读屏。 */
@Composable
private fun LyricText(
    lines: List<PosterLine>,
    index: Int,
    current: Boolean,
    position: State<Long>,
    duration: Long,
    playing: State<Boolean>,
    style: TextStyle,
) {
    if (current) {
        val karaoke = remember(lines, index, duration) { posterKaraokeLine(lines, index, duration) }
        if (karaoke.words.isNotEmpty()) {
            // 和花瓣旋转、跳动的条一起限 30 帧（见 wallFrameDue），墙不跟着屏幕刷新率满帧重画
            KaraokeLine(karaoke, position, playing.value, style, capFrames = true)
            return
        }
    }
    Text(
        lines[index].line.text,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (current) Modifier else Modifier.clearAndSetSemantics {}),
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
