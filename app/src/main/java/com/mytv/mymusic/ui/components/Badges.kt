package com.mytv.mymusic.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.aggregate.availabilityOf
import com.mytv.mymusic.ui.availabilityLabel
import com.mytv.mymusic.ui.providerMark

/**
 * 平台可用性徽标。状态不只靠颜色：完整是实心圆，试听是空心圆，VIP 是圆角方，没有音源加删除线。
 * 读屏合并成一句，例如“QQ 音乐完整，网易云 VIP”。
 */
@Composable
fun PlatformBadges(track: CanonicalTrack, cookies: Map<String, String>, modifier: Modifier = Modifier, max: Int = 4) {
    val items = track.sources.map { it.providerName to availabilityOf(it, cookies[it.providerId].orEmpty()) }
    val spoken = items.joinToString("，") { (name, status) -> "$name${availabilityLabel(status)}" }
    Row(
        modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.take(max).forEach { (name, status) -> PlatformBadge(providerMark(name), status) }
        if (items.size > max) {
            Text("+${items.size - max}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PlatformBadge(mark: String, status: Availability) {
    val color = when (status) {
        Availability.Playable -> MaterialTheme.colorScheme.primary
        Availability.PreviewOnly -> MaterialTheme.colorScheme.tertiary
        Availability.VipOnly -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        StatusGlyph(status, color, 8.dp)
        Text(
            mark,
            style = MaterialTheme.typography.labelSmall,
            color = if (status == Availability.Unavailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (status == Availability.Unavailable) TextDecoration.LineThrough else null,
        )
    }
}

@Composable
fun StatusGlyph(status: Availability, color: Color, glyph: Dp) {
    val base = Modifier.size(glyph)
    when (status) {
        Availability.Playable -> Box(base.background(color, CircleShape))
        Availability.PreviewOnly -> Box(base.border(1.5.dp, color, CircleShape))
        Availability.VipOnly -> Box(base.background(color, RoundedCornerShape(2.dp)))
        Availability.Unavailable -> Box(
            base.drawWithContent {
                drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5.dp.toPx())
            },
        )
        Availability.Unknown -> Box(base.border(1.dp, color.copy(alpha = 0.5f), CircleShape))
    }
}

/**
 * 音源 chip：显示当前平台和音质，右侧标出“自动 / 固定”。换源结果直接写在 chip 下方，不弹 Snackbar。
 */
@Composable
fun SourceChip(
    providerName: String,
    detail: String,
    mode: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f),
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner = animateDpAsState(if (pressed) 16.dp else 12.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "srcCorner")
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(36.dp)
            .graphicsLayer {
                shape = RoundedCornerShape(corner.value)
                clip = true
            }
            .tvFocusRing(RoundedCornerShape(12.dp))
            .semantics { contentDescription = "音源：$providerName $detail，$mode。点按切换音源" },
        shape = RectangleShape,
        color = container,
        contentColor = content,
        interactionSource = interaction,
    ) {
        // chip 已有完整描述；读屏会把描述和子文字连着读，所以里面的文字不再暴露
        Row(
            Modifier.padding(start = 12.dp, end = 6.dp).clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Text(
                listOf(providerName, detail).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                mode,
                Modifier
                    .background(content.copy(alpha = 0.14f), RoundedCornerShape(7.dp))
                    .padding(horizontal = 7.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
            )
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
    }
}
