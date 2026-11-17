package com.mytv.mymusic.ui.artist

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.AlbumSummary
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.CoverBox
import com.mytv.mymusic.ui.charts.DiscoverCard
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.theme.LocalAppearance

/** 专辑卡片下面那行：年份 · 类型，没有就是曲目数。 */
internal fun albumCardSubtitle(album: AlbumSummary): String {
    val parts = listOf(album.year.ifBlank { album.publishTime }, album.type).filter { it.isNotBlank() }
    return when {
        parts.isNotEmpty() -> parts.joinToString(" · ")
        album.trackCount > 0 -> "${album.trackCount} 首"
        else -> album.artist
    }
}

/** 歌手页专辑网格、专辑页底部“更多专辑”共用的卡片：16dp 圆角方封面 + 名字 + 年份类型。 */
@Composable
internal fun AlbumCard(album: AlbumSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val subtitle = albumCardSubtitle(album)
    DiscoverCard(
        cover = album.cover,
        title = album.name,
        subtitle = subtitle,
        onClick = onClick,
        modifier = modifier,
        coverShape = RoundedCornerShape(16.dp),
        spokenTitle = listOf("专辑", album.name, subtitle).filter { it.isNotBlank() }.joinToString("，"),
    )
}

/**
 * 专辑封面 + 从右侧探出来的一张黑胶：专辑页的标志。正在放这张专辑里的歌时唱片慢慢转（减少动态效果时不转）。
 * 唱片中心的标签取封面色 [label]（详情页头部渐变同一个颜色），在绘制阶段读，不引起重组。
 * 占位宽度 = [size] × 1.32，高度 = [size]。
 */
@Composable
internal fun VinylCover(
    cover: String,
    title: String,
    size: Dp,
    spinning: Boolean,
    label: State<Color>,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalAppearance.current.reduceMotion
    val disc = size * 0.92f
    val peek = size * 0.32f
    val angle = if (spinning && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "vinyl")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart),
            label = "vinylAngle",
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    val hole = MaterialTheme.colorScheme.surface
    Box(modifier.width(size + peek).height(size).clearAndSetSemantics {}) {
        Canvas(
            Modifier
                .size(disc)
                .align(Alignment.CenterStart)
                .offset(x = peek + size - disc - size * 0.04f)
                .graphicsLayer { rotationZ = angle.value },
        ) {
            val r = this.size.minDimension / 2f
            val c = center
            drawCircle(Color(0xFF111214), r, c)
            // 声槽：一圈圈很淡的同心圆，转起来能看出纹理
            var groove = r * 0.96f
            var step = 0
            while (groove > r * 0.40f) {
                drawCircle(
                    Color.White.copy(alpha = if (step % 3 == 0) 0.07f else 0.035f),
                    groove,
                    c,
                    style = Stroke(width = 1.dp.toPx()),
                )
                groove -= r * 0.045f
                step++
            }
            // 一道偏心的高光：唱片转的时候它跟着转，像灯光扫过声槽
            rotate(-35f, c) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.08f to Color.White.copy(alpha = 0.10f),
                        0.16f to Color.Transparent,
                        center = c,
                    ),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = true,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                )
            }
            drawCircle(label.value, r * 0.34f, c)
            drawCircle(Color.Black.copy(alpha = 0.18f), r * 0.34f, c, style = Stroke(width = 1.5.dp.toPx()))
            drawCircle(hole, r * 0.045f, c)
        }
        CoverBox(
            cover,
            title,
            Modifier
                .size(size)
                .shadow(14.dp, RoundedCornerShape(size * 0.07f), clip = false),
            RoundedCornerShape(size * 0.07f),
            size.value * 0.34f,
        )
    }
}

/**
 * 简介：默认收起成 [collapsedLines] 行，超出时显示“展开”；点整块切换。没超出就是普通文字，不可点。
 */
@Composable
internal fun ExpandableText(
    text: String,
    modifier: Modifier = Modifier,
    collapsedLines: Int = 3,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflowing by remember(text) { mutableStateOf(false) }
    val expandable = overflowing || expanded
    Column(
        modifier
            .animateContentSize()
            .then(
                if (expandable) {
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = if (expanded) "收起" else "展开") { expanded = !expanded }
                        .tvFocusRing(RoundedCornerShape(12.dp), scale = 1.01f)
                        .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                } else {
                    Modifier
                },
            ),
    ) {
        Text(
            text,
            style = style,
            color = color,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
        )
        if (expandable) {
            Text(
                if (expanded) "收起" else "展开",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 歌手胶囊：头像位用一个小人图标，名字 + 右箭头，点开歌手页。专辑页头部、简介里用。 */
@Composable
internal fun ArtistPill(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tv = LocalAppWindow.current.isTv
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = if (tv) 44.dp else 36.dp)
            .tvFocusRing(CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = "歌手：$name，打开歌手页"
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Person, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 头部的一个数字：大号数字 + 小号说明（“591 / 首歌曲”）。 */
@Composable
internal fun StatBlock(value: String, label: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = 0.72f), maxLines = 1)
    }
}

/** 数字缩写：过亿 / 过万用中文单位；不到 100 万 / 100 亿时留一位小数（1.9 万），再大就取整（5102 万）。 */
internal fun compactCount(value: Long): String = when {
    value >= 100_000_000L -> scaled(value / 100_000_000.0) + " 亿"
    value >= 10_000L -> scaled(value / 10_000.0) + " 万"
    else -> value.toString()
}

private fun scaled(value: Double): String =
    if (value >= 100) value.toLong().toString() else trimDecimal(value)

private fun trimDecimal(value: Double): String {
    val text = String.format(java.util.Locale.ROOT, "%.1f", value)
    return if (text.endsWith(".0")) text.dropLast(2) else text
}

/** 曲目总时长：“52 分钟” / “1 小时 12 分钟”；没有时长的歌不算。 */
internal fun totalDurationLabel(songs: List<Song>): String {
    val seconds = songs.sumOf { it.duration.coerceAtLeast(0).toLong() }
    if (seconds <= 0) return ""
    val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分钟" else "$minutes 分钟"
}

/** 专辑页头部的信息行：发行日期 · 类型 · 厂牌。 */
internal fun albumMetaLine(album: AlbumSummary?): String =
    listOfNotNull(album?.publishTime, album?.type, album?.company).filter { it.isNotBlank() }.joinToString(" · ")

/** 专辑的主歌手们（最多 3 个，用于头部胶囊）。 */
internal fun albumArtists(album: AlbumSummary?, songs: List<Song>, fallback: String): List<String> {
    val raw = album?.artist?.takeIf { it.isNotBlank() }
        ?: songs.firstOrNull()?.artist?.takeIf { it.isNotBlank() }
        ?: fallback
    return splitArtistNames(raw).take(3)
}

/** 头部的统计行之间的分隔点。 */
@Composable
internal fun Dot(color: Color) {
    Box(Modifier.padding(horizontal = 8.dp).size(3.dp).background(color, CircleShape))
}
