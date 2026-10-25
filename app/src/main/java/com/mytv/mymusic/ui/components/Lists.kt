package com.mytv.mymusic.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.ui.Cover

/** 分段列表：相邻项间隔 2dp，组外角 16dp、组内角 4dp。 */
val SegmentGap = 2.dp
private val Outer = 16.dp
private val Inner = 4.dp

/** 列表 key：id 可能重复时后出现的加序号，否则 Lazy 列表会因重复 key 崩溃；不要拿位置当 key。 */
internal fun <T> distinctKeys(items: List<T>, id: (T) -> String): List<String> {
    val seen = HashMap<String, Int>()
    return items.map { item ->
        val base = id(item)
        val count = (seen[base] ?: 0) + 1
        seen[base] = count
        if (count == 1) base else "$base#$count"
    }
}

fun segmentShape(index: Int, count: Int): Shape = when {
    count <= 1 -> RoundedCornerShape(Outer)
    index == 0 -> RoundedCornerShape(Outer, Outer, Inner, Inner)
    index == count - 1 -> RoundedCornerShape(Inner, Inner, Outer, Outer)
    else -> RoundedCornerShape(Inner)
}

/** 一组分段卡片：传入子项数量，每个子项自己按 index 取形状。 */
@Composable
fun SegmentedGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SegmentGap), content = content)
}

/** 小节标题：不是卡片，左缩进 4dp 对齐卡片内文字。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (RowScope.() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        trailing?.invoke(this)
    }
}

/**
 * 分段歌曲行：封面 48dp、圆角 12dp，最小高度 64dp（随字号增长）。
 * 当前播放的行用 secondaryContainer 底色并显示均衡器标记。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    title: String,
    subtitle: String,
    cover: String,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rank: Int? = null,
    playing: Boolean = false,
    badges: @Composable (RowScope.() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = onMore,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
) {
    val shape = segmentShape(index, count)
    val background = if (playing) MaterialTheme.colorScheme.secondaryContainer else container
    val content = if (playing) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .tvFocusRing(shape, scale = 1.02f)
            .clip(shape)
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClick?.let { "更多操作" })
            // 正在播放只靠底色和字重区分，读屏要单独说出来
            .semantics { if (playing) stateDescription = "正在播放" }
            .padding(start = if (rank != null) 8.dp else 12.dp, end = if (onMore != null) 0.dp else 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank != null) {
            Text(
                rank.toString(),
                Modifier.width(32.dp).semantics { contentDescription = "第 $rank 名" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (rank <= 3) FontWeight.Black else FontWeight.Medium,
                color = if (rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Cover(cover, title, 48.dp, corner = 12.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                badges?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) { it() }
                    Box(Modifier.width(6.dp))
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (playing) content.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke(this)
        if (onMore != null) {
            IconButton(onClick = onMore, modifier = Modifier.tvFocusRing(CircleShape)) {
                Icon(Icons.Filled.MoreVert, "更多操作：$title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 设置项 / 导航项：图标在 40dp 圆形容器里，右侧是值或箭头。 */
@Composable
fun ListRow(
    title: String,
    index: Int,
    count: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTone: IconTone = IconTone.Secondary,
    summary: String = "",
    value: String = "",
    selected: Boolean = false,
    danger: Boolean = false,
    showChevron: Boolean = onClick != null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val shape = if (selected) RoundedCornerShape(Outer) else segmentShape(index, count)
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .tvFocusRing(shape, scale = 1.02f)
            .clip(shape)
            .background(background)
            .then(if (onClick != null) Modifier.combinedClickable(onClick = onClick) else Modifier)
            // 选中只靠底色表现（双栏左栏、分类列表），读屏要读出“已选择”
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            leading != null -> leading()
            icon != null -> ToneIcon(icon, iconTone)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = when {
                    danger -> MaterialTheme.colorScheme.error
                    selected -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value.isNotBlank()) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
        trailing?.invoke(this)
        if (showChevron && trailing == null) {
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 开关行：整行可点，读屏角色是开关。 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    summary: String = "",
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    ListRow(
        title = title,
        index = index,
        count = count,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        // Switch 的 onCheckedChange 为 null 时不带开关状态语义，读屏的开 / 关要由整行提供
        modifier = modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        icon = icon,
        summary = summary,
        showChevron = false,
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

enum class IconTone { Primary, Secondary, Tertiary, Error }

@Composable
fun ToneIcon(icon: ImageVector, tone: IconTone = IconTone.Secondary, size: Dp = 40.dp) {
    val (bg, fg) = when (tone) {
        IconTone.Primary -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        IconTone.Secondary -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        IconTone.Tertiary -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        IconTone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.55f), tint = fg)
    }
}
