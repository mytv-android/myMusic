package com.mytv.mymusic.ui.player.stage.modes

import androidx.compose.animation.core.EaseOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.mytv.mymusic.ui.player.stage.LyricSetting
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import com.mytv.mymusic.ui.player.stage.LyricVisualizer
import com.mytv.mymusic.ui.player.stage.ModOrigin
import com.mytv.mymusic.ui.player.stage.StageLine
import com.mytv.mymusic.ui.player.stage.fitText
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * 动感波光（wave-light 1.5.1，作者 pomepii，MIT）：仿 QQ 音乐的“动感波光”。数值、时序、配色照原模组。
 *
 * - 背景自己画：一个主色（封面主色或主题色）派生出整套配色。底色是压暗的主色铺成的椭圆径向渐变（135%×100%，中心在 44% 高处）；
 *   左右屏边各一组同心声波，每组是一团核心光晕 + 9 圈同心圈（逐圈此起彼伏地胀缩、明暗呼吸，竖向渐变描边让上下两端淡出，
 *   像水面反光）+ 3 道从核心往外扩、越走越淡的声波 + 3 段缓慢正反转的亮弧；两组之间还有一团慢慢游走的淡光。全部按加色叠在底色上。
 * - 当前句居中放大，按“字”（汉字、假名单个，拉丁字母和数字连成的词整词）从中间往两边浮现：中间的先出来，往外每隔一个晚 80ms，
 *   外侧的从自己那一侧滑进来，同时由模糊变清楚。
 * - 句子背后是这句里“分量最重的词”的超大水印（主色压暗、常驻柔焦，入场时由大变小、由糊变清）。选词照原模组：按词切分，
 *   分数 = min(字数, 8)×14 + min(2.5 秒, 唱这个词的时长)×18，分数在最高分 94% 以内的用整句哈希稳定地挑一个，
 *   单字向分量更重的邻词借字；“作词：方文山”这类署名行取冒号后面的名字。
 * - 翻译（按“歌词下面那一行”取翻译或罗马音）排在水印下沿和底部之间，淡入。
 * - 句间、空行保持上一句，不闪回；第一句之前显示歌名。
 *
 * 移植取舍：
 * - 分词用 java.text.BreakIterator：Android 上由 ICU 实现，和 Chromium 的 Intl.Segmenter 是同一套词典；原模组没有分词器时的正则退路用不上。
 * - 原模组的圈带 canvas shadowBlur 辉光，但阴影透明度是“描边透明度 × 1.3 倍描边透明度”，实际不到 1%，看不出来，这里不画。
 * - “亮度”在原模组里只乘到了中间那团游走的淡光上，设置说明却是“波纹与光晕的整体强度”，这里照说明乘到全部波光上。
 * - 中间那团淡光用第二主色（封面第二色 / 主题 accentAlt），其余照原模组只用一个主色。
 *   封面主色取宿主量化好的 [com.mytv.mymusic.ui.player.stage.StageColors.cover] 第一色，再照原模组 dominantColor 的方式夹饱和度、明度。
 * - 原模组暂停时波纹照样流动；这里跟宿主的动画时钟走，暂停时停住，和其他模式一致，也省电。
 *   每帧只重画背景那一层，文字、水印都在各自的小图层里，入场结束后不再重画。
 * - 入场模糊、水印柔焦在 Android 12+ 用 RenderEffect，每个字一个只有字那么大的图层，入场结束撤掉模糊；
 *   以下只做透明度和位移，水印改用更低的透明度代替柔焦。
 * - 原模组的尺寸按页面 CSS 像素算，这里把 1 CSS 像素当 1dp；正文字号改按宿主的基准字号（×1.5），模糊、阴影按字号折算。
 * - 减少动态效果：背景停在原模组静态预览用的相位（第 12 秒），整句、水印、翻译直接显示。
 */
object WaveLightVisualizer : LyricVisualizer {
    override val id = "wave"
    override val label = "动感波光"
    override val origin = ModOrigin("wave-light", "1.5.1", "pomepii", "MIT")
    override val order = 40
    override val hostBackground = false
    override val hostSubtitles = false
    override val wantsAudio = false
    override val drawsWithoutLyrics = false

    override val settings: List<LyricSetting> = listOf(
        LyricSetting.Choice("waveSource", "波光颜色", listOf("cover" to "封面", "theme" to "主题"), "cover"),
        LyricSetting.Number("brightness", "亮度", 0.3f, 2f, 0.1f, 1f),
        LyricSetting.Number("speed", "速度", 0.2f, 3f, 0.1f, 1f),
        LyricSetting.Toggle("keyword", "背后的大字水印", true, summary = "取这句里分量最重的词"),
        LyricSetting.Number("textScale", "字号", 0.6f, 1.8f, 0.05f, 1f),
    )

    @Composable
    override fun Background(ctx: LyricStageContext, modifier: Modifier) {
        val palette = rememberWavePalette(ctx)
        val brightness = ctx.settings.number("brightness")
        val speed = ctx.settings.number("speed")
        Box(modifier) {
            // 底色只随尺寸、配色变，单独一层，不跟着声波逐帧重画
            Spacer(
                Modifier.fillMaxSize().drawWithCache {
                    val center = Offset(size.width * 0.5f, size.height * 0.44f)
                    val radiusY = size.height.coerceAtLeast(1f)
                    // CSS radial-gradient(135% 100% at 50% 44%)：椭圆，横半径 135% 宽、竖半径 100% 高；圆形渐变横向拉伸得到
                    val stretch = size.width * 1.35f / radiusY
                    val wash = Brush.radialGradient(
                        0f to palette.bgMid,
                        0.58f to palette.bgEdge,
                        1f to palette.bgDeep,
                        center = center,
                        radius = radiusY,
                    )
                    onDrawBehind {
                        withTransform({ scale(stretch, 1f, center) }) {
                            drawRect(wash, Offset(center.x - center.x / stretch, 0f), Size(size.width / stretch, size.height))
                        }
                    }
                },
            )
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    val t = if (ctx.display.reduceMotion) STILL_T * speed else ctx.clock.seconds * speed
                    drawWaves(t, palette, brightness)
                },
            )
        }
    }

    @Composable
    override fun Stage(ctx: LyricStageContext, modifier: Modifier) {
        val palette = rememberWavePalette(ctx)
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val family = MaterialTheme.typography.bodyLarge.fontFamily
        val keywordOn = ctx.settings.toggle("keyword")
        val hold = remember(ctx.lines) { WaveHold() }
        val shown = hold.resolve(ctx.lines, ctx.lineIndex.value)
        val line = ctx.lines.getOrNull(shown)
        // 第一句之前只显示歌名（不带歌手）
        val text = line?.text?.trim().orEmpty().ifEmpty { ctx.song.name.trim().ifEmpty { "♪" } }
        val subtitle = line?.subtitle(ctx.subline)?.trim().orEmpty()
        val keyword = remember(text, line, keywordOn) {
            if (keywordOn) waveKeyword(text, line?.let { it.durationMs / 1000f } ?: 0f) else ""
        }
        Box(modifier) {
            key(shown, text, subtitle, keyword) {
                WaveLine(ctx, palette, WaveContent(text, subtitle, keyword), measurer, family)
            }
        }
    }
}

/** 一句的显示内容：正文、副行、水印词。任何一项变了就整句重新入场。 */
@Immutable
private data class WaveContent(val text: String, val subtitle: String, val keyword: String)

/**
 * 一句：水印、逐字、副行各是一个只有自己那么大的图层，位置在布局阶段由 [WaveScene] 算；
 * 入场的透明度、位移、模糊都在 graphicsLayer 里按动画时钟改，不重组、不重画字形。
 */
@Composable
private fun WaveLine(ctx: LyricStageContext, palette: WavePalette, content: WaveContent, measurer: TextMeasurer, family: FontFamily?) {
    val tokens = remember(content.text) { waveTokens(content.text) }
    val entrance = remember { WaveVinylKit.Entrance(waveEntranceMs(tokens)) }
    val scene = remember(ctx, palette, measurer, family) { WaveScene(ctx, palette, content, tokens, measurer, family) }
    Box(Modifier.fillMaxSize()) {
        if (content.keyword.isNotEmpty()) {
            ScenePart(scene, WaveScene.KEYWORD, layer = {
                val p = WaveVinylKit.EnterEasing.transform((entrance.elapsedMs(ctx) / KEYWORD_ENTER_MS).coerceIn(0f, 1f))
                val grow = 1.06f - 0.06f * p
                scaleX = grow
                scaleY = grow
                val opacity = KEYWORD_OPACITY * (if (WaveVinylBlur.supported) 1f else LOW_API_KEYWORD)
                applyFade(p * opacity, WaveVinylBlur.of(lerp(scene.keywordEnterSigma, scene.keywordSigma, p)))
            }) { scene.drawKeyword(this) }
        }
        tokens.forEachIndexed { i, token ->
            if (token.space) return@forEachIndexed
            ScenePart(scene, i, layer = {
                val p = waveTokenProgress(entrance.elapsedMs(ctx), token.dist)
                translationX = token.shift * scene.em * (1f - p)
                applyFade(p, WaveVinylBlur.of(TOKEN_BLUR_EM * scene.em * (1f - p)))
            }) { scene.drawToken(this, i) }
        }
        if (content.subtitle.isNotEmpty()) {
            ScenePart(scene, WaveScene.SUBTITLE, layer = {
                val p = EaseOut.transform((entrance.elapsedMs(ctx) / SUBTITLE_ENTER_MS).coerceIn(0f, 1f))
                applyFade(p, WaveVinylBlur.of(SUBTITLE_BLUR_EM * scene.subtitleEm * (1f - p)))
            }) { scene.drawSubtitle(this) }
        }
    }
}

/** 透明度和模糊：有模糊时离屏合成，没有时按绘制逐个乘透明度（不用离屏）。 */
private fun GraphicsLayerScope.applyFade(fade: Float, blur: RenderEffect?) {
    alpha = fade.coerceIn(0f, 1f)
    renderEffect = blur
    compositingStrategy = if (blur != null) CompositingStrategy.Offscreen else CompositingStrategy.ModulateAlpha
}

/** 场景里的一块：在布局阶段按 [WaveScene] 的结果定大小和位置（图层只有这么大），绘制交给 [draw]。 */
@Composable
private fun ScenePart(scene: WaveScene, part: Int, layer: GraphicsLayerScope.() -> Unit, draw: DrawScope.() -> Unit) {
    Spacer(
        Modifier
            .layout { measurable, constraints ->
                val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
                val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
                scene.measure(this, width, height)
                val box = scene.box(part)
                val placeable = measurable.measure(Constraints.fixed(box.width.coerceAtLeast(0), box.height.coerceAtLeast(0)))
                layout(width, height) { placeable.place(box.left, box.top) }
            }
            .graphicsLayer(layer)
            .drawBehind(draw),
    )
}

/** 一块字形：排好的文字和它在所属图层里的左上角。 */
private class WavePiece(val layout: TextLayoutResult, val at: Offset)

/**
 * 一句的排版。所有图层在布局阶段调 [measure]，按尺寸和安全区记忆，只在它们变化时重排；
 * 字形、位置、阴影都在这里算好，绘制阶段只画。
 */
private class WaveScene(
    private val ctx: LyricStageContext,
    private val palette: WavePalette,
    private val content: WaveContent,
    private val tokens: List<WaveToken>,
    private val measurer: TextMeasurer,
    private val family: FontFamily?,
) {
    private val textScale = ctx.settings.number("textScale")
    private var measuredWidth = -1
    private var measuredHeight = -1
    private var measuredSafe = Rect.Zero

    private val tokenBoxes = Array(tokens.size) { IntRect.Zero }
    private val tokenPieces = arrayOfNulls<List<WavePiece>>(tokens.size)
    private var keywordBox = IntRect.Zero
    private var keywordPiece: WavePiece? = null
    private var subtitleBox = IntRect.Zero
    private var subtitlePiece: WavePiece? = null
    private var lineShadow: Shadow = Shadow.None
    private var keywordShadow: Shadow = Shadow.None
    private var subtitleShadow: Shadow = Shadow.None

    /** 正文（排进安全区后）的字号，px。入场位移、模糊按它折算。 */
    var em = 0f
        private set
    var subtitleEm = 0f
        private set
    var keywordSigma = 0f
        private set
    var keywordEnterSigma = 0f
        private set

    private val lineColor = palette.tint.copy(alpha = 0.97f)
    private val keywordColor = palette.keyword.copy(alpha = 0.88f)
    private val subtitleColor = palette.tint.copy(alpha = 0.68f)

    fun box(part: Int): IntRect = when (part) {
        KEYWORD -> keywordBox
        SUBTITLE -> subtitleBox
        else -> tokenBoxes[part]
    }

    fun measure(density: Density, width: Int, height: Int) {
        // 读安全区就订阅了它：顶栏、控件高度变了会重新测量
        val safe = ctx.display.safeRect(Size(width.toFloat(), height.toFloat()))
        if (width == measuredWidth && height == measuredHeight && safe == measuredSafe) return
        measuredWidth = width
        measuredHeight = height
        measuredSafe = safe
        with(density) { layoutAll(safe) }
    }

    private fun Density.layoutAll(safe: Rect) {
        val css = density
        val baseSp = ctx.display.textSp * LINE_SCALE * textScale
        // 正文：居中、左右各留 7%，最多 3 行，按 text-wrap: balance 均衡换行
        val lineStyle = TextStyle(
            fontFamily = family,
            fontSize = baseSp.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 1.3.em,
            lineBreak = LineBreak.Heading,
        )
        val line = fitText(measurer, content.text, lineStyle, (safe.width * 0.86f).toInt(), maxLines = 3)
        val fitted = line.layoutInput.style
        em = fitted.fontSize.toPx()
        // 短句的排版宽度就是字宽，长句是最大宽度；都按自己的宽度居中
        val lineLeft = safe.center.x - line.size.width / 2f
        val lineTop = safe.center.y - line.size.height / 2f
        val lineBottom = lineTop + line.size.height
        val lineShadowSigma = max(10f * css, safe.height * 0.03f) / 2f
        lineShadow = Shadow(palette.keyword.copy(alpha = 0.5f), Offset(0f, css), WaveVinylKit.sigmaToRadius(lineShadowSigma))
        val tokenMargin = ceil(max(3f * TOKEN_BLUR_EM * em, 3f * lineShadowSigma + css) + 2f).toInt()
        val glyphStyle = fitted.copy(textAlign = TextAlign.Unspecified)
        tokens.forEachIndexed { i, token ->
            if (token.space) return@forEachIndexed
            val pieces = ArrayList<WavePiece>(1)
            var bounds: Rect? = null
            // 长到一行放不下的词会被折开：每行一块，各自对齐到原排版里的位置
            var a = token.start
            while (a < token.end) {
                val row = line.getLineForOffset(a)
                var b = a
                var left = Float.MAX_VALUE
                while (b < token.end && line.getLineForOffset(b) == row) {
                    left = min(left, line.getBoundingBox(b).left)
                    b += Character.charCount(content.text.codePointAt(b))
                }
                val glyph = measurer.measure(content.text.substring(a, b), glyphStyle, softWrap = false, maxLines = 1)
                val at = Offset(lineLeft + left, lineTop + line.getLineBaseline(row) - glyph.firstBaseline)
                pieces += WavePiece(glyph, at)
                val rect = Rect(at, Size(glyph.size.width.toFloat(), glyph.size.height.toFloat()))
                bounds = bounds?.let { Rect(min(it.left, rect.left), min(it.top, rect.top), max(it.right, rect.right), max(it.bottom, rect.bottom)) } ?: rect
                a = b
            }
            val box = bounds?.let { inflate(it, tokenMargin) } ?: IntRect.Zero
            tokenBoxes[i] = box
            tokenPieces[i] = pieces.map { WavePiece(it.layout, it.at - Offset(box.left.toFloat(), box.top.toFloat())) }
        }

        // 水印：越长字越小，按安全区宽高折算；字号也决定副行的位置，所以水印关掉时照样按 3 个字算
        var keywordPx = waveKeywordFontPx(safe.width, safe.height, content.keyword.length.takeIf { it > 0 } ?: 3, textScale, css)
        if (content.keyword.isNotEmpty()) {
            var layout = measureKeyword(keywordPx)
            val room = safe.width * 0.96f
            if (layout.size.width > room) {
                keywordPx *= room / layout.size.width
                layout = measureKeyword(keywordPx)
            }
            keywordSigma = max(2f * css, keywordPx * 0.025f)
            keywordEnterSigma = KEYWORD_ENTER_BLUR * keywordPx
            val shadowSigma = max(8f * css, safe.height * 0.02f) / 2f
            keywordShadow = Shadow(palette.keyword.copy(alpha = 0.3f), Offset.Zero, WaveVinylKit.sigmaToRadius(shadowSigma))
            // 边距：模糊外溢 + 入场时放大 6%
            val margin = ceil(3f * max(keywordEnterSigma, shadowSigma) + 0.04f * max(layout.size.width, layout.size.height) + 2f).toInt()
            val at = Offset(safe.center.x - layout.size.width / 2f, safe.center.y - layout.size.height / 2f)
            val box = inflate(Rect(at, Size(layout.size.width.toFloat(), layout.size.height.toFloat())), margin)
            keywordBox = box
            keywordPiece = WavePiece(layout, at - Offset(box.left.toFloat(), box.top.toFloat()))
        }

        // 副行：水印下沿和底部之间（原模组 75% 高处 + 水印字号的 1/4），不压正文、不出安全区
        if (content.subtitle.isNotEmpty()) {
            val subSp = baseSp * 0.4f
            subtitleEm = subSp.sp.toPx()
            val style = TextStyle(
                fontFamily = family,
                fontSize = subSp.sp,
                fontWeight = FontWeight.Normal,
                lineHeight = 1.5.em,
                textAlign = TextAlign.Center,
            )
            val layout = measurer.measure(
                content.subtitle,
                style,
                overflow = TextOverflow.Ellipsis,
                maxLines = 3,
                constraints = Constraints(maxWidth = (safe.width * 0.7f).toInt().coerceAtLeast(1)),
            )
            val half = layout.size.height / 2f
            val centerY = (safe.top + safe.height * 0.75f + keywordPx / 4f)
                .coerceAtLeast(lineBottom + subtitleEm * 0.6f + half)
                .coerceAtMost(safe.bottom - half)
            val shadowSigma = 8f * css / 2f
            subtitleShadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, css), WaveVinylKit.sigmaToRadius(shadowSigma))
            val margin = ceil(3f * max(SUBTITLE_BLUR_EM * subtitleEm, shadowSigma) + css + 2f).toInt()
            val at = Offset(safe.center.x - layout.size.width / 2f, centerY - half)
            val box = inflate(Rect(at, Size(layout.size.width.toFloat(), layout.size.height.toFloat())), margin)
            subtitleBox = box
            subtitlePiece = WavePiece(layout, at - Offset(box.left.toFloat(), box.top.toFloat()))
        }
    }

    private fun Density.measureKeyword(px: Float): TextLayoutResult = measurer.measure(
        content.keyword,
        TextStyle(fontFamily = family, fontSize = px.toSp(), fontWeight = FontWeight.ExtraBold, letterSpacing = 0.02.em),
        softWrap = false,
        maxLines = 1,
    )

    fun drawToken(scope: DrawScope, i: Int) {
        val pieces = tokenPieces[i] ?: return
        for (piece in pieces) scope.drawText(piece.layout, lineColor, piece.at, shadow = lineShadow)
    }

    fun drawKeyword(scope: DrawScope) {
        val piece = keywordPiece ?: return
        scope.drawText(piece.layout, keywordColor, piece.at, shadow = keywordShadow)
    }

    fun drawSubtitle(scope: DrawScope) {
        val piece = subtitlePiece ?: return
        scope.drawText(piece.layout, subtitleColor, piece.at, shadow = subtitleShadow)
    }

    private fun inflate(rect: Rect, margin: Int): IntRect = IntRect(
        floor(rect.left).toInt() - margin,
        floor(rect.top).toInt() - margin,
        ceil(rect.right).toInt() + margin,
        ceil(rect.bottom).toInt() + margin,
    )

    companion object {
        const val KEYWORD = -1
        const val SUBTITLE = -2
    }
}

// ---------------------------------------------------------------------------------------------
// 配色
// ---------------------------------------------------------------------------------------------

/** 一个主色派生出的整套配色（原模组 computePalette），外加中间那团淡光的第二色 [drift]。 */
@Immutable
internal class WavePalette(
    val wave: Color,
    val tint: Color,
    val keyword: Color,
    val bgMid: Color,
    val bgEdge: Color,
    val bgDeep: Color,
    val drift: Color,
)

/** 声波色：主色的色相，饱和度 ×1.15+0.06 夹到 0.45–0.9，明度 0.58。 */
internal fun waveColor(base: Color): Color {
    val (h, s, _) = WaveVinylKit.hsl(base)
    return WaveVinylKit.fromHsl(h, (s * 1.15f + 0.06f).coerceIn(0.45f, 0.9f), 0.58f)
}

/** 原模组的 computePalette：歌词是同色相的亮淡色，水印是主色压到四成，底色压到 24% / 7% / 4%。 */
internal fun wavePalette(base: Color, second: Color): WavePalette {
    val (h, s, _) = WaveVinylKit.hsl(base)
    return WavePalette(
        wave = waveColor(base),
        tint = WaveVinylKit.fromHsl(h, min(0.45f, s * 0.35f + 0.06f), 0.92f),
        keyword = WaveVinylKit.scale(base, 0.4f),
        bgMid = WaveVinylKit.scale(base, 0.24f),
        bgEdge = WaveVinylKit.scale(base, 0.07f),
        bgDeep = WaveVinylKit.scale(base, 0.04f),
        drift = waveColor(second),
    )
}

@Composable
private fun rememberWavePalette(ctx: LyricStageContext): WavePalette {
    val source = ctx.settings.choice("waveSource")
    val hasCover = ctx.song.cover.isNotBlank()
    return remember(ctx.colors, source, hasCover) {
        // 没有封面时原模组退到主题色
        if (source == "cover" && hasCover) {
            wavePalette(WaveVinylKit.dominant(ctx.colors.cover[0]), WaveVinylKit.dominant(ctx.colors.cover[1]))
        } else {
            wavePalette(ctx.colors.accent, ctx.colors.accentAlt)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 声波
// ---------------------------------------------------------------------------------------------

/** 两组声波贴着左右屏边，圆心在屏边上（或略往里），一半圈在屏幕外。[t] 是乘过速度的秒数。 */
private fun DrawScope.drawWaves(t: Float, palette: WavePalette, brightness: Float) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val baseR = min(w, h) * 0.62f
    val cy = h * (0.45f + 0.02f * sin(t * 0.09f))
    val cxL = w * 0.015f + w * 0.012f * sin(t * 0.06f + 1.2f)
    val cxR = w * 0.985f + w * 0.012f * sin(t * 0.055f + 3.9f)
    drawCluster(cxL, cy, baseR, t, 1, brightness, palette.wave)
    drawCluster(cxR, cy * 0.98f, baseR * 1.04f, t * 1.06f, 2, 0.92f * brightness, palette.wave)
    // 两组之间一团慢慢游走的淡光，添点纵深
    val drift = Offset(w * (0.5f + 0.18f * sin(t * 0.08f + 0.7f)), h * (0.42f + 0.08f * cos(t * 0.06f + 2.1f)))
    val driftR = h * 0.4f
    drawCircle(
        Brush.radialGradient(
            0f to palette.drift.copy(alpha = (0.05f * brightness).coerceAtMost(1f)),
            1f to palette.drift.copy(alpha = 0f),
            center = drift,
            radius = driftR,
        ),
        driftR,
        drift,
        blendMode = BlendMode.Plus,
    )
}

/** 一组声波：核心光晕 + 9 圈呼吸的同心圈 + 3 道外扩的声波 + 3 段转动的亮弧。 */
private fun DrawScope.drawCluster(cx: Float, cy: Float, baseR: Float, t: Float, seed: Int, alphaScale: Float, wave: Color) {
    val h = size.height
    val center = Offset(cx, cy)
    val coreR = (baseR * 0.9f).coerceAtLeast(1f)
    drawCircle(
        Brush.radialGradient(
            0f to wave.copy(alpha = (0.15f * alphaScale).coerceAtMost(1f)),
            0.55f to wave.copy(alpha = (0.06f * alphaScale).coerceAtMost(1f)),
            1f to wave.copy(alpha = 0f),
            center = center,
            radius = coreR,
        ),
        coreR,
        center,
        blendMode = BlendMode.Plus,
    )
    for (i in 1..WAVE_RINGS) {
        val radius = waveRingRadius(i, baseR, t, seed)
        drawCircle(
            bandBrush(wave, cy, radius),
            radius,
            center,
            alpha = waveRingAlpha(i, t, seed, alphaScale).coerceAtMost(1f),
            style = Stroke(waveRingWidth(i, seed, h)),
            blendMode = BlendMode.Plus,
        )
    }
    for (k in 0 until WAVE_PULSES) {
        val phase = wavePulsePhase(k, t, seed)
        val alpha = alphaScale * 0.17f * (1f - phase).pow(1.6f)
        if (alpha <= 0.004f) continue
        val radius = baseR * (0.1f + 1.04f * phase)
        drawCircle(
            bandBrush(wave, cy, radius),
            radius,
            center,
            alpha = alpha.coerceAtMost(1f),
            style = Stroke(max(0.8f, h * (0.0018f + 0.0035f * (1f - phase)))),
            blendMode = BlendMode.Plus,
        )
    }
    for (k in 0 until WAVE_ARCS) {
        val salt = k + seed * 31
        val r1 = waveRand(salt, 4)
        val r2 = waveRand(salt, 6)
        val radius = baseR * (0.28f + 0.72f * r1) * (1f + 0.02f * sin(t * 0.2f + k))
        val start = waveArcStart(k, t, seed)
        val span = 0.9f + 1.5f * r2
        val alpha = max(0.01f, alphaScale * (0.09f + 0.08f * waveRand(salt, 5)) * (0.7f + 0.3f * sin(t * 0.3f + k * 2.2f)))
        drawArc(
            Brush.verticalGradient(0f to wave.copy(alpha = 0f), 0.5f to wave, 1f to wave.copy(alpha = 0f), startY = cy - radius, endY = cy + radius),
            startAngle = Math.toDegrees(start.toDouble()).toFloat(),
            sweepAngle = Math.toDegrees(span.toDouble()).toFloat(),
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
            alpha = alpha.coerceAtMost(1f),
            style = Stroke(max(1f, h * (0.0018f + 0.0035f * r1))),
            blendMode = BlendMode.Plus,
        )
    }
}

/** 竖向分带的描边：圈的上下两端淡出、中间最亮（水面反光）；透明度由绘制时的 alpha 乘上去。 */
private fun bandBrush(wave: Color, cy: Float, radius: Float): Brush = Brush.verticalGradient(
    0f to wave.copy(alpha = 0f),
    0.3f to wave.copy(alpha = 0.6f),
    0.5f to wave,
    0.7f to wave.copy(alpha = 0.6f),
    1f to wave.copy(alpha = 0f),
    startY = cy - radius,
    endY = cy + radius,
)

/** 原模组的按序号伪随机（每帧一样，画面稳定）：frac(sin((i+1)·127.1 + salt·311.7)·43758.5453)。用 StrictMath 和 JS 的 Math.sin 逐位一致。 */
internal fun waveRand(i: Int, salt: Int): Float {
    val v = StrictMath.sin((i + 1) * 127.1 + salt * 311.7) * 43758.5453
    return (v - floor(v)).toFloat()
}

/** 第 [i] 圈（1..9）的半径：均匀铺开，再按各自的节奏胀缩 ±15%，往外一圈比一圈晚（像喇叭推出去的波）。 */
internal fun waveRingRadius(i: Int, baseR: Float, t: Float, seed: Int): Float {
    val r1 = waveRand(i + seed * 13, 1)
    val swell = 1f + 0.15f * sin(t * (0.65f + 0.15f * r1) - i * 0.62f + seed * 2.1f)
    return baseR * (0.16f + 0.92f * i / WAVE_RINGS) * swell
}

/** 第 [i] 圈的透明度：越往外越淡，按自己的节奏呼吸，最低 0.008。 */
internal fun waveRingAlpha(i: Int, t: Float, seed: Int, alphaScale: Float): Float {
    val r2 = waveRand(i + seed * 13, 2)
    val breathe = 0.6f + 0.4f * sin(t * (0.45f + 0.1f * r2) - i * 0.55f + seed)
    return max(0.008f, alphaScale * (0.03f + 0.095f * (1f - i.toFloat() / WAVE_RINGS)) * breathe)
}

/** 第 [i] 圈的线宽（px），按页面高度折算，最细 0.8。 */
internal fun waveRingWidth(i: Int, seed: Int, height: Float): Float =
    max(0.8f, height * (0.0012f + 0.005f * waveRand(i + seed * 13, 2)))

/** 第 [k] 道外扩声波走到哪儿了（0..1，0 在核心、1 走到最外）；三道错开 1/3。 */
internal fun wavePulsePhase(k: Int, t: Float, seed: Int): Float {
    val v = t * (0.1f + 0.018f * waveRand(seed, 7)) + k / 3f + seed * 0.21f
    return v - floor(v)
}

/** 第 [k] 段亮弧的起始角（弧度）：单数段反着转。 */
internal fun waveArcStart(k: Int, t: Float, seed: Int): Float {
    val r1 = waveRand(k + seed * 31, 4)
    return t * (0.05f + 0.07f * r1) * (if (k % 2 == 1) -1f else 1f) + k * 2.4f + seed * 1.7f
}

// ---------------------------------------------------------------------------------------------
// 文字
// ---------------------------------------------------------------------------------------------

/** 句间、空行保持上一句（原模组的 heldIdx）：从当前句往回找最近的非空句；句间用上次显示的；都没有时是 −1（显示歌名）。 */
internal class WaveHold {
    private var held = -1

    fun resolve(lines: List<StageLine>, index: Int): Int {
        var j = (if (index >= 0) index else held).coerceAtMost(lines.lastIndex)
        while (j >= 0 && lines[j].text.isBlank()) j--
        if (j >= 0) held = j
        return j
    }
}

/**
 * 入场的一个单位：一个汉字或假名、一串拉丁字母数字（词不拆开）、或一个标点 / 空白。
 * [dist] 是离中间的距离（按单位数），[shift] 是从哪侧滑进来、滑多远（em，往外最多算 4 个单位）。
 */
internal class WaveToken(val start: Int, val end: Int, val space: Boolean, val dist: Float, val shift: Float)

/** 按原模组的规则切单位，空白也占位置（影响离中间的距离）。 */
internal fun waveTokens(text: String): List<WaveToken> {
    val spans = ArrayList<IntArray>()
    var run = -1
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val n = Character.charCount(cp)
        when {
            isKanaOrHan(cp) -> {
                if (run >= 0) spans += intArrayOf(run, i, 0).also { run = -1 }
                spans += intArrayOf(i, i + n, 0)
            }
            isWordPart(cp) -> if (run < 0) run = i
            else -> {
                if (run >= 0) spans += intArrayOf(run, i, 0).also { run = -1 }
                spans += intArrayOf(i, i + n, if (isBlank(cp)) 1 else 0)
            }
        }
        i += n
    }
    if (run >= 0) spans += intArrayOf(run, text.length, 0)
    val mid = (spans.size - 1) / 2f
    return spans.mapIndexed { index, span ->
        val dist = abs(index - mid)
        WaveToken(span[0], span[1], span[2] == 1, dist, sign(index - mid) * min(dist, 4f) * 0.7f)
    }
}

/** 离中间 [dist] 个单位的字入场到哪儿了（0..1，已缓动）：晚 100 + 80·dist 毫秒开始，用 520 毫秒。 */
internal fun waveTokenProgress(elapsedMs: Float, dist: Float): Float =
    WaveVinylKit.EnterEasing.transform(((elapsedMs - (100f + dist * 80f)) / TOKEN_ENTER_MS).coerceIn(0f, 1f))

/** 整句入场（最外的字、水印、副行都到位）要多久，毫秒。 */
internal fun waveEntranceMs(tokens: List<WaveToken>): Float {
    val far = tokens.filterNot { it.space }.maxOfOrNull { it.dist } ?: 0f
    return max(100f + far * 80f + TOKEN_ENTER_MS, max(KEYWORD_ENTER_MS, SUBTITLE_ENTER_MS))
}

/** 水印字号（px）：越长越小，max(28, min(高×0.42, 宽×0.8 / 长度) × (0.55 + 0.45×字号设置)) × 0.8；[css] 是 1 CSS 像素合多少 px。 */
internal fun waveKeywordFontPx(width: Float, height: Float, length: Int, textScale: Float, css: Float): Float =
    max(28f * css, min(height * 0.42f, width * 0.8f / length.coerceAtLeast(1)) * (0.55f + 0.45f * textScale)) * 0.8f

// 汉字（扩展 A、基本区、兼容区）和假名单个成字；增补平面的字和原模组一样算进字母串
private fun isKanaOrHan(cp: Int): Boolean = cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF || cp in 0x3040..0x30FF

private fun isWordPart(cp: Int): Boolean = isLetterOrNumber(cp) || cp == '\''.code || cp == 0x2019 || cp == '&'.code || cp == '.'.code || cp == '-'.code

private fun isLetterOrNumber(cp: Int): Boolean = when (Character.getType(cp)) {
    Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
    Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(),
    Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
    -> true
    else -> false
}

private fun isBlank(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

// ---------------------------------------------------------------------------------------------
// 选水印词（原模组 keywordOf）
// ---------------------------------------------------------------------------------------------

/** 分词结果里的一段。[wordLike] 为 false 的是标点、空白。[score] 由 [waveKeyword] 打分时填。 */
internal class WaveWord(val text: String, val k: Int, val wordLike: Boolean) {
    var chars = 0
    var score = 0f
}

/** 按词切分（Android 上是 ICU 的词典分词，和 Intl.Segmenter 一样）；含字母或数字的段算词。 */
internal fun waveSegmentWords(text: String): List<WaveWord> {
    if (text.isEmpty()) return emptyList()
    val iterator = BreakIterator.getWordInstance()
    iterator.setText(text)
    val out = ArrayList<WaveWord>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        val part = text.substring(start, end)
        out += WaveWord(part, out.size, WORD_LIKE.containsMatchIn(part))
        start = end
        end = iterator.next()
    }
    return out
}

/**
 * 这句的水印词。[lineSeconds] 是这句唱多久（0 表示不知道，按每字 0.32 秒估）；[segment] 是分词器，测试时可换。
 * 署名行（作词、作曲……带冒号）取最后一个冒号后的名字，8 个字以内整个用；其余按词打分挑最重的，单字向邻词借字凑够 2 个，
 * 去掉空白标点后最多 12 个字。
 */
internal fun waveKeyword(text: String, lineSeconds: Float, segment: (String) -> List<WaveWord> = ::waveSegmentWords): String {
    val t = text.trim()
    if (t.isEmpty()) return ""
    if (CREDIT.containsMatchIn(t) && COLON.containsMatchIn(t)) {
        val raw = t.split(COLON).last().trim()
        val exact = raw.replace(EDGE_MARKS, "")
        if (exact.isNotEmpty()) {
            if (exact.codePointCount(0, exact.length) <= 8) return exact
            val words = segment(raw)
            val hero = pickHero(words, lineSeconds)
            return if (hero != null) cleanKeyword(growHero(hero, words)) else takeUnits(exact, 8)
        }
    }
    if (cleanKeyword(t).isEmpty()) return ""
    val words = segment(t)
    val hero = pickHero(words, lineSeconds)
    return if (hero != null) cleanKeyword(growHero(hero, words)) else takeUnits(cleanKeyword(t), 4)
}

/** 每个词的分数：min(字数, 8)×14 + min(2.5, 唱这个词的秒数)×18；一个词唱多久按它占全句的字数比例分句长。 */
private fun scoreWords(words: List<WaveWord>, lineSeconds: Float): List<WaveWord> {
    val content = words.filter { it.wordLike && visibleChars(it.text) > 0 }
    val total = content.sumOf { visibleChars(it.text) }.takeIf { it > 0 } ?: 1
    val span = (if (lineSeconds > 0f) lineSeconds else total * 0.32f).coerceIn(0.4f, 16f)
    for (word in content) {
        word.chars = visibleChars(word.text)
        val sung = min(HERO_TIME_CAP, span * word.chars / total)
        word.score = min(word.chars, HERO_CHAR_CAP) * HERO_CHAR_POINTS + sung * HERO_TIME_POINTS
    }
    return content
}

/** 分数最高的词；和它差不多重（94% 以内）的几个之间用整句哈希稳定地挑，换句时水印会换位置，同一句不会变。 */
private fun pickHero(words: List<WaveWord>, lineSeconds: Float): WaveWord? {
    val content = scoreWords(words, lineSeconds)
    if (content.isEmpty()) return null
    var best = content[0]
    for (word in content) if (word.score > best.score) best = word
    val near = content.filter { it.score >= best.score * HERO_TIE }
    if (near.size < 2) return best
    val seed = fnv1a(words.joinToString("|") { it.text })
    return near[floor(rand01(seed + 7) * near.size).toInt() % near.size]
}

/** 单字撑不起大水印：向分量更重的相邻词借字，直到够 2 个字（只借真正的词，不跨标点）。 */
private fun growHero(hero: WaveWord, words: List<WaveWord>): String {
    var lo = hero.k
    var hi = hero.k
    var text = hero.text
    while (visibleChars(text) < HERO_MIN_CHARS) {
        val prev = if (lo > 0 && words[lo - 1].wordLike) words[lo - 1] else null
        val next = if (hi < words.lastIndex && words[hi + 1].wordLike) words[hi + 1] else null
        if (prev == null && next == null) break
        if (prev != null && (next == null || prev.score >= next.score)) {
            lo -= 1
            text = prev.text + text
        } else if (next != null) {
            hi += 1
            text += next.text
        }
    }
    return text
}

private fun cleanKeyword(text: String): String = takeUnits(text.replace(MARKS, ""), 12)

/** 前 [count] 个 UTF-16 单元（JS 的 slice），不把代理对切成半个。 */
private fun takeUnits(text: String, count: Int): String {
    if (text.length <= count) return text
    val end = if (Character.isHighSurrogate(text[count - 1])) count - 1 else count
    return text.substring(0, end)
}

/** 不算空白的字数（按码位）。 */
private fun visibleChars(text: String): Int {
    var count = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (!isBlank(cp)) count++
        i += Character.charCount(cp)
    }
    return count
}

/** 32 位 FNV-1a，按码位（和原模组的 strHash 一样）。 */
private fun fnv1a(text: String): Long {
    var h = 0x811C9DC5.toInt()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        h = (h xor cp) * 16777619
        i += Character.charCount(cp)
    }
    return h.toLong() and 0xFFFFFFFFL
}

private fun rand01(n: Long): Double {
    val v = StrictMath.sin(n * 12.9898) * 43758.5453
    return v - floor(v)
}

private val WORD_LIKE = Regex("[\\p{L}\\p{N}]")
private val MARKS = Regex("[\\s\\p{Z}\\p{P}\\p{S}]+")
private val EDGE_MARKS = Regex("^[\\s\\p{Z}\\p{P}\\p{S}]+|[\\s\\p{Z}\\p{P}\\p{S}]+$")
private val COLON = Regex("[:：]")
private val CREDIT = Regex(
    "(作词|作詞|作曲|编曲|編曲|填词|填詞|谱曲|譜曲|监制|監製|和声|和聲|混音|混響|母带|母帶|录音|錄音|制作|製作|出品|发行|發行|改编|改編|翻唱|原唱|演唱|主唱|词|曲|op|sp|吉他|钢琴|鋼琴|贝斯|貝斯|鼓|提琴|" +
        "lyrics?|compos\\w*|arrang\\w*|written\\s+by|produced\\s+by|mix\\w*|master\\w*|record\\w*|vocal\\w*|perform\\w*|remix\\w*|guitar|bass|piano|drum\\w*|violin)",
    RegexOption.IGNORE_CASE,
)

// 选词打分，和宿主 sonnet 排版同一套常数
private const val HERO_CHAR_POINTS = 14f
private const val HERO_CHAR_CAP = 8
private const val HERO_TIME_POINTS = 18f
private const val HERO_TIME_CAP = 2.5f
private const val HERO_MIN_CHARS = 2
private const val HERO_TIE = 0.94f

private const val WAVE_RINGS = 9
private const val WAVE_PULSES = 3
private const val WAVE_ARCS = 3

/** 减少动态效果时背景停在的相位：原模组静态预览画的是第 12 秒。 */
private const val STILL_T = 12f

/** 正文字号相对宿主基准字号的倍数（原模组按页高 8.5%，比普通歌词大一截）。 */
private const val LINE_SCALE = 1.5f

private const val TOKEN_ENTER_MS = 520f
private const val KEYWORD_ENTER_MS = 550f
private const val SUBTITLE_ENTER_MS = 500f

/** 入场模糊（标准差）按字号折算：原模组正文约 72px 时字是 14px、副行（0.4 倍）是 4px，水印入场 18px（约 0.088 倍水印字号）。 */
private const val TOKEN_BLUR_EM = 14f / 72f
private const val SUBTITLE_BLUR_EM = 4f / (72f * 0.4f)
private const val KEYWORD_ENTER_BLUR = 0.088f

/** 水印整体不透明度 0.92（另有颜色本身的 0.88）；没有模糊时再压到 55%，免得一个清楚的大字抢正文。 */
private const val KEYWORD_OPACITY = 0.92f
private const val LOW_API_KEYWORD = 0.55f
