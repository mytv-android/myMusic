/*
 * Copyright (C) 2026  Shubham Gorai
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
// 2026-11-18 myMusic 改写：取自 Rush（shub39/Rush@5a952bf）的 SpotifyShareCard、RushedShareCard、VerticalShareCard、
// CoupletShareCard、AlbumArt、HypnoticShareCard、QuoteShareCard、MessyCard、BratShareCard，SharePage 的 ScaledToFit、
// CardFrame（9:16 画布），与 Util.kt 的 rotateVertically。px 排版改成 1px = 1dp 的局部密度（CardPixels）；Card / LazyColumn
// 换成 Box / Column、去掉 1900px 的最大高度（整张都录进图层）；Rush 的 Spacer(padding(x)) 实际占 2x，这里写成明确的高度；
// 字体换成 Roboto Flex 的字重、字宽、斜度轴；取色换成舞台的封面量化色；乱排的随机数改成按句子和种子确定；模糊改成
// CPU 预模糊；流光取静止帧；纯中文的竖排歌名歌手改成每字一行；新增「播放页」样式、副行（译文）、省略行。
package com.mytv.mymusic.ui.player.share

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.data.ShareCardStyle
import com.mytv.mymusic.ui.player.stage.modes.RushHypnoticShader
import com.mytv.mymusic.ui.player.stage.modes.drawRushHypnoticStill
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------- 数据 ----------

/** 卡片上的文字：歌名、歌手、已按样式截好的行（[rowsForStyle]），乱排的种子。 */
@Immutable
internal class ShareCardData(
    val title: String,
    val artist: String,
    val rows: List<ShareRow>,
    val messySeed: Long,
)

/** 播放页样式的颜色，都取自会话打开那一刻的 PlayerTheme；[glow] 是左上角光晕。 */
@Immutable
internal data class PlayerCardColors(val surface: Color, val text: Color, val accent: Color, val glow: Color, val track: Color)

/**
 * 卡片外观。[container] / [content] 是配色给的底色、字色；[corner] 是卡片圆角（这一层里 1dp = 1px）；
 * [artShape] 是封面形状；[translation] 是否带副行；[hypnotic] 是流光卡的 6 个色。
 */
@Immutable
internal class ShareCardLook(
    val style: ShareCardStyle,
    val container: Color,
    val content: Color,
    val corner: Dp,
    val artShape: Shape,
    val translation: Boolean,
    val player: PlayerCardColors,
    val hypnotic: List<Color>,
)

/** 卡片排版宽度（px），和 Rush 一样；全屏画布 1080×1920。 */
internal const val SHARE_CARD_WIDTH = 720
internal const val SHARE_CANVAS_WIDTH = 1080
internal const val SHARE_CANVAS_HEIGHT = 1920

// ---------- 公共件 ----------

/**
 * 卡片和 9:16 画布按像素排版：这一层里 1.dp = 1px、1.sp = 1px，和屏幕密度、应用字号设置都无关，
 * 导出的 PNG 每台设备一样（Rush 的 pxToDp / pxToSp 是同样的效果）。
 */
@Composable
internal fun CardPixels(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f), content = content)

/** 把内容录进 [layer] 再画出来；放在卡片修饰链最外层，圆角裁切和底色都在里面，导出的 PNG 圆角外是透明的。 */
internal fun Modifier.recordInto(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/**
 * 按内容本来的大小排一次，再等比缩放到放得下（[allowUpscale] 时也可以放大）。缩放在摆放时做（placeWithLayer），
 * 只量一次，也不影响录进图层的内容（Rush SharePage 的 ScaledToFit，原样移植）。
 */
@Composable
internal fun ScaledToFit(allowUpscale: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(modifier = modifier, content = content) { measurables, constraints ->
        val placeable = measurables.first().measure(Constraints())
        val widthScale = when {
            !constraints.hasBoundedWidth || placeable.width <= 0 -> Float.MAX_VALUE
            else -> constraints.maxWidth.toFloat() / placeable.width
        }
        val heightScale = when {
            !constraints.hasBoundedHeight || placeable.height <= 0 -> Float.MAX_VALUE
            else -> constraints.maxHeight.toFloat() / placeable.height
        }
        val fitScale = minOf(widthScale, heightScale).takeIf { it != Float.MAX_VALUE } ?: 1f
        val scale = if (allowUpscale) fitScale else fitScale.coerceAtMost(1f)
        layout((placeable.width * scale).roundToInt(), (placeable.height * scale).roundToInt()) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/**
 * 全屏分享的 1080×1920 画布（Rush 的 CardFrame），[full] 为 false 时只画卡片。画布色由调用方给（卡片底色和表面色各一半）。
 * 卡片原样居中；比画布还高（8 句长句加副行）时整体缩进画布，不裁。
 */
@Composable
internal fun ShareCanvas(full: Boolean, color: Color, layer: GraphicsLayer?, card: @Composable () -> Unit) {
    if (!full) {
        card()
        return
    }
    Box(
        Modifier
            .requiredSize(SHARE_CANVAS_WIDTH.dp, SHARE_CANVAS_HEIGHT.dp)
            .then(if (layer != null) Modifier.recordInto(layer) else Modifier)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        ScaledToFit(allowUpscale = false) { card() }
    }
}

/**
 * 一张卡片。[modifier] 放在最外层（录制、宽度由调用方给，通常是 `Modifier.recordInto(layer)`），卡片自己定 720px 宽。
 * 必须放在 [CardPixels] 里。
 */
@Composable
internal fun ShareCard(data: ShareCardData, look: ShareCardLook, images: ShareImages, modifier: Modifier = Modifier) {
    val card = modifier.requiredWidth(SHARE_CARD_WIDTH.dp)
    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides look.content) {
        when (look.style) {
            ShareCardStyle.Spotify -> SpotifyCard(data, look, images, card)
            ShareCardStyle.Rushed -> RushedCard(data, look, images, card)
            ShareCardStyle.Vertical -> VerticalCard(data, look, images, card)
            ShareCardStyle.Couplet -> CoupletCard(data, look, images, card)
            ShareCardStyle.AlbumArt -> AlbumArtCard(data, look, images, card)
            ShareCardStyle.Hypnotic -> HypnoticCard(data, look, images, card)
            ShareCardStyle.Quote -> QuoteCard(data, look, images, card)
            ShareCardStyle.Messy -> MessyCard(data, look, images, card)
            ShareCardStyle.Brat -> BratCard(data, look, card)
            ShareCardStyle.Player -> PlayerCard(data, look, images, card)
        }
    }
}

/** 一段文字；只有一行的（歌名、歌手）放不下时省略。 */
@Composable
private fun CardText(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** 副行（翻译 / 罗马音）：正文下面 6px，字号 [size]、行高 1.25、中等字重、八成不透明。 */
@Composable
private fun SubLine(row: ShareRow.Line, look: ShareCardLook, size: Int, color: Color = look.content) {
    if (!look.translation || row.sub.isBlank()) return
    CardText(
        row.sub,
        ShareFonts.style(500, size, (size * 1.25f).roundToInt(), color.copy(alpha = color.alpha * 0.8f)),
        Modifier.padding(top = 6.dp),
    )
}

/** 两组不连续的句子中间的省略行，字号同正文。 */
@Composable
private fun GapLine(size: Int, color: Color) {
    Text(
        "⋯",
        style = ShareFonts.style(700, size, size, color.copy(alpha = color.alpha * 0.45f)).copy(letterSpacing = 0.2.em),
    )
}

/**
 * 封面（或自选图）裁成 [shape]。没有图（取失败、本地歌没封面）时画字色 18% 的同形状块，正中歌名首字。
 */
@Composable
private fun CardArt(image: ImageBitmap?, shape: Shape, title: String, color: Color, modifier: Modifier) {
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        } else {
            BoxWithConstraints(Modifier.matchParentSize().background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                val side = minOf(constraints.maxWidth, constraints.maxHeight).takeIf { it != Constraints.Infinity } ?: 100
                val initial = title.trim().let { if (it.isEmpty()) "♪" else it.substring(0, Character.charCount(it.codePointAt(0))) }
                Text(initial, style = ShareFonts.style(800, (side * 0.42f).roundToInt(), (side * 0.5f).roundToInt(), color))
            }
        }
    }
}

/** 铺满父级的模糊背景图（Crop），上面盖一层 [overlay]。没有图时只有那层底色（[overlay] 不透明度拉满）。 */
@Composable
private fun SoftBackground(image: ImageBitmap?, overlay: Color, base: Color, modifier: Modifier) {
    Box(modifier) {
        if (image != null) {
            Image(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
            Spacer(Modifier.matchParentSize().background(overlay))
        } else {
            Spacer(Modifier.matchParentSize().background(base))
        }
    }
}

/** 一列歌词：每句 [body] 样式，副行字号 [subSize]；省略行字号同正文。 */
@Composable
private fun LyricRows(rows: List<ShareRow>, look: ShareCardLook, body: TextStyle, subSize: Int, spacing: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.dp)) {
        rows.forEach { row ->
            when (row) {
                ShareRow.Gap -> GapLine(body.fontSize.value.roundToInt(), look.content)
                is ShareRow.Line -> Column {
                    CardText(row.text, body)
                    SubLine(row, look, subSize)
                }
            }
        }
    }
}

private fun ShareCardData.firstLine(): ShareRow.Line? = rows.firstOrNull { it is ShareRow.Line } as? ShareRow.Line

// ---------- 1. 色块 Spotify ----------

@Composable
private fun SpotifyCard(data: ShareCardData, look: ShareCardLook, images: ShareImages, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(look.corner)).background(look.container)) {
        SpotifyBody(data, look, images)
    }
}

/** 色块卡的内容（流光卡也用它，底色透明）。 */
@Composable
private fun SpotifyBody(data: ShareCardData, look: ShareCardLook, images: ShareImages) {
    Column(Modifier.padding(48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardArt(images.art, look.artShape, data.title, look.content, Modifier.size(140.dp))
            Column(Modifier.padding(horizontal = 16.dp)) {
                CardText(data.title, ShareFonts.style(700, 32, 28, look.content), maxLines = 1)
                CardText(data.artist, ShareFonts.style(400, 26, 26, look.content), maxLines = 1)
            }
        }
        Spacer(Modifier.height(64.dp))
        LyricRows(data.rows, look, ShareFonts.style(700, 42, 48, look.content), subSize = 26, spacing = 16)
        Spacer(Modifier.height(16.dp))
    }
}

// ---------- 2. 叠卡 Rushed ----------

@Composable
private fun RushedCard(data: ShareCardData, look: ShareCardLook, images: ShareImages, modifier: Modifier) {
    val shape = RoundedCornerShape(look.corner)
    Box(modifier.clip(shape)) {
        SoftBackground(images.pictureSoft ?: images.artSoft, look.container.copy(alpha = 0.5f), look.container, Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(48.dp).align(Alignment.BottomStart)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.Start) {
                data.rows.forEach { row ->
                    when (row) {
                        ShareRow.Gap -> Box(Modifier.clip(shape).background(look.container).padding(horizontal = 16.dp)) {
                            GapLine(42, look.content)
                        }
                        is ShareRow.Line -> Column(Modifier.clip(shape).background(look.container).padding(16.dp)) {
                            CardText(row.text, ShareFonts.style(700, 42, 48, look.content))
                            SubLine(row, look, 26)
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 小封面始终是专辑封面，自选图只换背景（Rush 同）
                CardArt(images.art, look.artShape, data.title, look.content, Modifier.size(100.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    CardText(data.title, ShareFonts.style(800, 32, 32, look.content), maxLines = 1)
                    CardText(data.artist, ShareFonts.style(400, 28, 24, look.content), maxLines = 1)
                }
            }
        }
    }
}

// ---------- 3. 竖排 Vertical ----------

@Composable
private fun VerticalCard(data: ShareCardData, look: ShareCardLook, images: ShareImages, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(look.corner)).background(look.container).padding(48.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CardArt(images.art, look.artShape, data.title, look.content, Modifier.size(100.dp))
            Spacer(Modifier.height(16.dp))
            Row {
                VerticalLabel(data.artist, ShareFonts.style(400, 24, 24, look.content))
                VerticalLabel(data.title, ShareFonts.style(800, 32, 28, look.content))
            }
        }
        Spacer(Modifier.width(32.dp))
        // Rush 是 600 字重再被合成粗体；这里直接用 700 的轴值
        LyricRows(
            data.rows,
            look,
            ShareFonts.style(700, 42, 44, look.content, width = 80, slant = -10),
            subSize = 24,
            spacing = 16,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 竖着写的歌名、歌手，最长 [VERTICAL_LABEL_MAX]px。纯中日韩文字每字一行直排（旋转 90° 后汉字是横躺的）；
 * 其余照 Rush 顺时针旋转 90°，从上往下读。
 */
@Composable
private fun VerticalLabel(text: String, style: TextStyle) {
    if (isCjkOnly(text)) {
        val size = style.fontSize.value
        val lines = (VERTICAL_LABEL_MAX / size).toInt().coerceAtLeast(1)
        CardText(
            text.filterNot { it.isWhitespace() }.codePoints().toArray().joinToString("\n") { String(Character.toChars(it)) },
            style.copy(lineHeight = style.fontSize, textAlign = TextAlign.Center),
            maxLines = lines,
        )
    } else {
        CardText(text, style, Modifier.rotateVertically().widthIn(max = VERTICAL_LABEL_MAX.dp), maxLines = 1)
    }
}

private const val VERTICAL_LABEL_MAX = 380

/** 去掉空白和标点后全是汉字、假名、谚文（至少一个字）。 */
internal fun isCjkOnly(text: String): Boolean {
    var any = false
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        if (Character.isWhitespace(cp) || isPunctuation(cp)) continue
        when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
            -> any = true
            else -> return false
        }
    }
    return any
}

private fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
    -> true
    else -> false
}

/** 顺时针转 90°、宽高对调（Rush Util.kt 的 rotateVertically，原样移植）。 */
private fun Modifier.rotateVertically(): Modifier = this
    .rotate(90f)
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.height, placeable.width) {
            placeable.place(
                x = -(placeable.width / 2 - placeable.height / 2),
                y = -(placeable.height / 2 - placeable.width / 2),
            )
        }
    }

// ---------- 4. 对句 Couplet ----------

@Composable
private fun CoupletCard(data: ShareCardData, look: ShareCardLook, images: ShareImages, modifier: Modifier) {
    val lines = data.rows.filterIsInstance<ShareRow.Line>()
    Box(modifier.clip(RoundedCornerShape(look.corner))) {
        SoftBackground(images.artSoft, look.container.copy(alpha = 0.7f), look.container, Modifier.matchParentSize())
        Column(Modifier.padding(48.dp)) {
            lines.getOrNull(0)?.let { first ->
                // Rush 的 flexFontEmphasis() 默认：字重 1000、字宽 120
                CardText(first.text, ShareFonts.style(1000, 48, 50, look.content, width = 120))
                SubLine(first, look, 26)
            }
            // 只选了一句时不画第二句（Rush 会画「...」）
            lines.getOrNull(1)?.let { second ->
                Spacer(Modifier.height(16.dp))
                CardText(second.text, ShareFonts.style(400, 36, 38, look.content))
                SubLine(second, look, 22)
            }
            Spacer(Modifier.height(128.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardArt(images.art, look.artShape, data.title, look.content, Modifier.size(100.dp))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    CardText(data.title, ShareFonts.style(800, 24, 26, look.content), maxLines = 1)
                    CardText(data.artist, ShareFonts.style(400, 18, 20, look.content), maxLines = 1)
                }
            }
        }
    }
}

// ---------- 5. 封面 Album Art ----------

@Composable
private fun AlbumArtCard(data: ShareCardData, look: ShareCardLook, images: ShareImages, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(look.corner)).background(look.container).padding(30.dp)) {
        CardText(data.artist.ifBlank { data.title }, ShareFonts.style(1000, 80, 82, look.content, width = 120), maxLines = 2)
        Spacer(Modifier.height(28.dp))
        CardArt(images.picture ?: images.art, look.artShape, data.title, look.content, Modifier.fillMaxWidth().aspectRatio(1f))
    }
}

// ---------- 6. 流光 Hypnotic ----------

@Composable
private fun HypnoticCard(data: ShareCardData, look: ShareCardLook, images: ShareImages, modifier: Modifier) {
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) RushHypnoticShader.createOrNull() else null }
    Box(modifier.clip(RoundedCornerShape(look.corner))) {
        Spacer(Modifier.matchParentSize().drawBehind { drawRushHypnoticStill(shader, look.hypnotic) })
        SpotifyBody(data, look, images)
    }
}

// ---------- 7. 引言 Quote ----------

@Composable
private fun QuoteCard(data: ShareCardData, look: ShareCardLook, images: ShareImages, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(look.corner)).background(look.container)) {
        CornerArt(look, images, data.title)
        Column(Modifier.padding(48.dp)) {
            Icon(Icons.Filled.FormatQuote, null, Modifier.size(60.dp), tint = look.content)
            Spacer(Modifier.height(32.dp))
            data.firstLine()?.let { line ->
                CardText(line.text, ShareFonts.style(600, 50, 60, look.content))
                SubLine(line, look, 26)
            }
            Spacer(Modifier.height(168.dp))
            Column(Modifier.fillMaxWidth(0.7f)) {
                CardText(data.title, ShareFonts.style(800, 28, 30, look.content), maxLines = 1)
                CardText(data.artist, ShareFonts.style(400, 22, 22, look.content), maxLines = 1)
            }
        }
    }
}

/** 引言、乱排卡右下角露一半的封面，背后衬一个字色的同形状（被卡片裁掉一部分）。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CornerArt(look: ShareCardLook, images: ShareImages, title: String) {
    Box(Modifier.align(Alignment.BottomEnd).offset(93.dp, 93.dp).size(350.dp).background(look.content, look.artShape))
    CardArt(images.art, look.artShape, title, look.container, Modifier.align(Alignment.BottomEnd).offset(80.dp, 80.dp).size(300.dp))
}

// ---------- 8. 乱排 Messy ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessyCard(data: ShareCardData, look: ShareCardLook, images: ShareImages, modifier: Modifier) {
    val shape = RoundedCornerShape(look.corner)
    val text = data.firstLine()?.text.orEmpty()
    val words = remember(text, data.messySeed) { messyWords(text, data.messySeed) }
    Box(modifier.clip(shape).background(look.container)) {
        CornerArt(look, images, data.title)
        Column(Modifier.fillMaxWidth().padding(48.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                words.forEach { word ->
                    val back = if (word.highlight) look.content else look.container
                    val fore = if (word.highlight) look.container else look.content
                    Box(Modifier.rotate(word.angle.toFloat()).clip(shape).background(back).padding(8.dp)) {
                        Text(
                            word.text,
                            style = TextStyle(
                                fontFamily = ShareFonts.flex(word.weight, word.width),
                                fontWeight = FontWeight(word.weight),
                                fontSize = word.size.sp,
                                letterSpacing = 0.sp,
                                color = fore,
                                fontSynthesis = if (Build.VERSION.SDK_INT >= 26) FontSynthesis.None else FontSynthesis.Weight,
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(128.dp))
            Column(Modifier.fillMaxWidth(0.7f)) {
                CardText(data.title, ShareFonts.style(800, 32, 32, look.content), maxLines = 1)
                CardText(data.artist, ShareFonts.style(400, 20, 22, look.content), maxLines = 1)
            }
        }
    }
}

// ---------- 9. 粗糙 Brat ----------

@Composable
private fun BratCard(data: ShareCardData, look: ShareCardLook, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(look.corner)).background(look.container)) {
        Box(Modifier.fillMaxWidth()) {
            // 两端对齐只拉伸空格，中文没有空格，按左对齐显示
            CardText(
                data.firstLine()?.text.orEmpty(),
                ShareFonts.style(300, 100, 120, look.content, width = 60).copy(textAlign = TextAlign.Justify),
                Modifier.padding(64.dp),
            )
        }
        Column(Modifier.fillMaxWidth().background(Color.Black).padding(16.dp)) {
            CardText(data.title, ShareFonts.style(800, 20, 22, Color.White), maxLines = 1)
            CardText(data.artist, ShareFonts.style(400, 16, 18, Color.White), maxLines = 1)
        }
    }
}

// ---------- 10. 播放页（本应用） ----------

/**
 * 照全屏歌词页：PlayerTheme 表面色、左上角光晕、封面跟随「封面形状」、歌手用主色，第一句是逐字填色停在 62% 的样子，
 * 底部一段静止的波浪进度和「♪ myMusic」。
 */
@Composable
private fun PlayerCard(data: ShareCardData, look: ShareCardLook, images: ShareImages, modifier: Modifier) {
    val p = look.player
    Column(
        modifier
            .clip(RoundedCornerShape(look.corner))
            .background(p.surface)
            .drawBehind {
                // 稿子：radial-gradient(120% 60% at 20% 0%, glow, transparent 70%)，横 1.2 倍宽、竖 0.6 倍高的椭圆
                val center = Offset(size.width * 0.2f, 0f)
                val radius = size.width * 1.2f
                scale(1f, (size.height * 0.6f) / radius, pivot = center) {
                    drawCircle(Brush.radialGradient(0f to p.glow, 0.7f to p.glow.copy(alpha = 0f), center = center, radius = radius), radius, center)
                }
            }
            .padding(48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CardArt(images.art, look.artShape, data.title, p.text, Modifier.size(120.dp))
            Column {
                CardText(data.title, ShareFonts.style(800, 36, 44, p.text), maxLines = 1)
                CardText(data.artist, ShareFonts.style(600, 28, 34, p.accent), maxLines = 1)
            }
        }
        Spacer(Modifier.height(48.dp))
        val rowLook = remember(look) {
            ShareCardLook(look.style, look.container, p.text, look.corner, look.artShape, look.translation, p, look.hypnotic)
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            var first = true
            data.rows.forEach { row ->
                when (row) {
                    ShareRow.Gap -> GapLine(44, p.text)
                    is ShareRow.Line -> Column {
                        val body = ShareFonts.style(800, 46, 56, p.text)
                        CardText(row.text, if (first) body.copy(brush = karaokeBrush(p.text)) else body)
                        SubLine(row, rowLook, 26)
                    }
                }
                if (row is ShareRow.Line) first = false
            }
        }
        Spacer(Modifier.height(48.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(330.dp, 26.dp)) {
                val mid = size.height / 2f
                val sung = size.width * 0.58f
                val path = Path().apply {
                    moveTo(2f, mid)
                    var x = 2f
                    while (x <= sung) {
                        lineTo(x, mid + 4f * sin(x / sung * 7f * PI.toFloat()))
                        x += 2f
                    }
                }
                drawPath(path, p.accent, style = Stroke(width = 4f, cap = StrokeCap.Round))
                drawLine(p.track, Offset(size.width * 0.6f, mid), Offset(size.width - 2f, mid), strokeWidth = 4f, cap = StrokeCap.Round)
            }
            CardText("♪ myMusic", ShareFonts.style(800, 24, 30, p.accent), maxLines = 1)
        }
    }
}

/** 第一句的逐字填色：前 62% 原色，后面四成不透明（全屏歌词当前句唱到一半的样子，静止，导出确定）。 */
private fun karaokeBrush(text: Color): Brush = Brush.horizontalGradient(
    0f to text,
    0.62f to text,
    0.62f to text.copy(alpha = 0.4f),
    1f to text.copy(alpha = 0.4f),
)
