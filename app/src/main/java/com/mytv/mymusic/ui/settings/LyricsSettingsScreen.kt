package com.mytv.mymusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.data.LyricsBackgroundMode
import com.mytv.mymusic.data.RhythmIntensity
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SwitchRow
import com.mytv.mymusic.ui.components.segmentShape
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.player.label
import com.mytv.mymusic.ui.player.polygon

internal val LyricsBackgroundMode.label: String
    get() = when (this) {
        LyricsBackgroundMode.Off -> "关闭"
        LyricsBackgroundMode.Ambient -> "动态"
        LyricsBackgroundMode.Reactive -> "律动"
    }

internal val RhythmIntensity.label: String
    get() = when (this) {
        RhythmIntensity.Soft -> "柔和"
        RhythmIntensity.Standard -> "标准"
    }

/**
 * 设置 → 播放页（枚举仍叫 Lyrics）：封面形状、歌词背景、律动强度，电视上再加环境模式时钟。
 * 都只存在本机，改了立即生效。
 */
@Composable
fun LyricsSettingsScreen(
    state: MusicUiState,
    actions: MusicActions,
    onBack: () -> Unit,
    embedded: Boolean = false,
) {
    val window = LocalAppWindow.current
    val mode = state.lyricsBackground
    SettingsPage(SettingsSection.Lyrics, embedded, onBack) {
        item(key = "shapeHead", contentType = "sectionHeader") { SectionLabel("封面形状") }
        item(key = "shapes", contentType = "coverShapes") {
            CoverShapePicker(
                selected = state.coverShape,
                cover = state.current?.cover.orEmpty(),
                tv = window.isTv,
                onSelect = actions::setCoverShape,
            )
        }
        item(key = "shapeNote", contentType = "note") {
            SettingsNote("随机：每次切歌换一种，不和上一首重复。选了具体形状后所有歌都用它。暂停时都会变成圆角方形。")
        }
        item(key = "backgroundHead", contentType = "sectionHeader") { SectionLabel("显示歌词背景") }
        item(key = "background", contentType = "lyricsOptions") {
            Column {
                ConnectedToggleGroup(
                    options = LyricsBackgroundMode.entries,
                    selected = mode,
                    onSelect = actions::setLyricsBackground,
                    label = { it.label },
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                )
                SettingsNote(
                    when (mode) {
                        LyricsBackgroundMode.Off -> "播放页使用纯色背景，封面和歌词内容不受影响。"
                        LyricsBackgroundMode.Ambient -> "取自封面颜色的几团光在背景里缓慢漂移，不随音乐变化。"
                        LyricsBackgroundMode.Reactive -> "音乐调节光团的漂移速度和光量：响的段落流得快、亮一些，安静时慢下来。画面不再跟着鼓点晃动，也不会闪白。"
                    },
                )
            }
        }
        // 强度只对“律动”有意义；开启“减少动画”时背景回退成静止，但选项照常显示
        if (mode == LyricsBackgroundMode.Reactive) {
            item(key = "intensityHead", contentType = "sectionHeader") { SectionLabel("律动强度") }
            item(key = "intensity", contentType = "toggleGroup") {
                ConnectedToggleGroup(
                    options = RhythmIntensity.entries,
                    selected = state.rhythmIntensity,
                    onSelect = actions::setRhythmIntensity,
                    label = { it.label },
                    // 双栏里两档按钮和上面三档的单个按钮差不多宽，不拉满整栏
                    modifier = Modifier.widthIn(max = if (window.twoPane) 320.dp else 480.dp).fillMaxWidth(),
                )
            }
            item(key = "intensityNote", contentType = "note") {
                SettingsNote("柔和只改流速和光量，没有鼓点光环。标准下光团随低频胀缩，鼓点时从封面散开一圈很淡的光环。")
            }
            item(key = "reactiveNote", contentType = "note") {
                SettingsNote(
                    "律动直接分析本机正在播放的音频，不申请麦克风权限，也不保存或上传音频。" +
                        "暂停、离开播放页或开启“减少动画”时停止分析；重新播放后会重新适应当前歌曲的动态范围。",
                )
            }
        }
        // 强制电视布局也算电视：环境模式只在电视播放页出现
        if (window.isTv) {
            item(key = "tvHead", contentType = "sectionHeader") { SectionLabel("电视") }
            item(key = "tvClock", contentType = "switch") {
                SwitchRow(
                    title = "环境模式显示时钟",
                    checked = state.tvAmbientClock,
                    onCheckedChange = actions::setTvAmbientClock,
                    index = 0,
                    count = 1,
                    summary = "空闲 4 秒进入环境模式后，右上角显示时间（跟随系统 12/24 小时制）",
                )
            }
        }
    }
}

/**
 * 形状选择器：手机和平板是卡片里的 4 列网格，电视一行 7 个（窄窗口的强制电视布局会自动换行）。
 * 预览直接用当前播放的封面裁成对应形状，没在播放时用 primaryContainer 填色。
 * 电视右栏只有约 496dp，预览缩到 54dp、间距 2dp，7 个正好排进一行（488dp）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoverShapePicker(
    selected: CoverShapeChoice,
    cover: String,
    tv: Boolean,
    onSelect: (CoverShapeChoice) -> Unit,
) {
    val context = LocalContext.current
    val previewSize = if (tv) 54.dp else 56.dp
    val px = with(LocalDensity.current) { previewSize.roundToPx() }
    // 6 个预览共用一个小尺寸请求，内存缓存里只有一份
    val request = remember(context, cover, px) {
        cover.takeIf { it.isNotBlank() }?.let { ImageRequest.Builder(context).data(it).size(px).build() }
    }
    val option: @Composable (CoverShapeChoice) -> Unit = { choice ->
        CoverShapeOption(choice, choice == selected, previewSize, request) { onSelect(choice) }
    }
    if (tv) {
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CoverShapeChoice.entries.forEach { option(it) }
        }
        return
    }
    Column(
        Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .clip(segmentShape(0, 1))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .selectableGroup(),
    ) {
        CoverShapeChoice.entries.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { choice ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { option(choice) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 单个选项：形状预览 + 名字。选中时外面 4dp 处画一圈 2.5dp 主色描边，右上角挂对勾。
 * 内边距（左右 7dp、上下 8dp）刚好容下描边和对勾，电视焦点环画在整个选项外面，和选中描边分得开。
 * 对勾往右上探出 4dp：再多就会被选项 20dp 的圆角裁掉一角（6dp 时约裁 2dp），加大内边距又会让电视一行排不下 7 个。
 */
@Composable
private fun CoverShapeOption(
    choice: CoverShapeChoice,
    selected: Boolean,
    previewSize: Dp,
    cover: ImageRequest?,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val optionShape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .tvFocusRing(optionShape)
            .clip(optionShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(previewSize)
                .drawBehind {
                    if (selected) {
                        val stroke = 2.5.dp.toPx()
                        drawCircle(
                            scheme.primary,
                            radius = size.minDimension / 2 + 4.dp.toPx() + stroke / 2,
                            style = Stroke(stroke),
                        )
                    }
                },
        ) {
            ShapePreview(choice, cover)
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = BadgeOverhang, y = -BadgeOverhang)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = scheme.onPrimary)
                }
            }
        }
        Text(
            choice.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** “随机”画成九瓣饼干加随机图标；具体形状裁当前封面，图还没加载出来时露出底色。 */
@Composable
private fun ShapePreview(choice: CoverShapeChoice, cover: ImageRequest?) {
    val scheme = MaterialTheme.colorScheme
    val polygon = choice.polygon
    Box(
        Modifier
            .fillMaxSize()
            .clip((polygon ?: MaterialShapes.Cookie9Sided).toShape())
            .background(scheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        when {
            polygon == null -> Icon(Icons.Filled.Shuffle, null, Modifier.size(26.dp), tint = scheme.onPrimaryContainer)
            cover != null -> AsyncImage(
                model = cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private val BadgeOverhang = 4.dp
