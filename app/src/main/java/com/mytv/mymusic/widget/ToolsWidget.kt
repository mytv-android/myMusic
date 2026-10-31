package com.mytv.mymusic.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import com.mytv.mymusic.R
import kotlin.math.roundToInt

enum class ToolsBp { Mini, Search, Cookie }

private val ToolsBreakpoints = listOf(
    Breakpoint(ToolsBp.Mini, 0.dp, 0.dp),
    Breakpoint(ToolsBp.Search, Cells.W3, 0.dp),
    Breakpoint(ToolsBp.Cookie, Cells.W2, Cells.H2),
)

/**
 * 快捷入口：2×2 是四瓣饼干底板，中间听歌识曲、四角搜索 / 我喜欢 / 听歌模式 / 定时关闭；4×1 是搜索条；1×1 只留识曲。
 * 没有外框背景，底板和搜索条自己带底色。识曲要用麦克风只能在前台录，所以点了打开 App 并立刻开始听。
 */
class ToolsWidget : MusicWidget(WidgetKind.Tools) {
    @Composable
    override fun Content(appWidgetId: Int, palette: WidgetPalette, config: WidgetConfig) {
        ToolsLayout(palette, config.background, interactive = true)
    }

    @Composable
    override fun Preview(palette: WidgetPalette) {
        ToolsLayout(palette, WidgetBackground.Solid, interactive = false)
    }
}

@Composable
fun ToolsLayout(palette: WidgetPalette, background: WidgetBackground, interactive: Boolean) {
    val context = LocalContext.current
    val size = LocalSize.current
    val bp = pickBreakpoint(ToolsBreakpoints, size).key
    fun open(target: WidgetTarget): Action? = if (interactive) WidgetActions.open(context, target) else null
    val style = if (background == WidgetBackground.Clear) WidgetBackground.Glass else background
    val alpha = if (style == WidgetBackground.Glass) WidgetPalette.GLASS_ALPHA else 1f
    val density = context.resources.displayMetrics.density
    when (bp) {
        ToolsBp.Mini -> Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val side = minOf(64.dp, size.width - 4.dp, size.height - 4.dp).coerceAtLeast(36.dp)
            Box(
                modifier = GlanceModifier.size(side).background(
                    ImageProvider(WidgetImages.mask((side.value * density).roundToInt(), WidgetShape.Cookie9, density)),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = ColorFilter.tint(palette.color { primaryContainer }),
                ).onClick(open(WidgetTarget.Recognize)).describe("听歌识曲"),
                contentAlignment = Alignment.Center,
            ) {
                WIcon(R.drawable.ic_w_eq, side * 0.44f, palette.color { onPrimaryContainer })
            }
        }
        ToolsBp.Search -> Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().height(56.dp).tinted(R.drawable.widget_pill, palette.color { surface }, alpha)
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight().onClick(open(WidgetTarget.Search)).describe("搜索歌曲、歌手或粘贴链接"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WBrandMark(32.dp, palette.color { primary }, palette.color { onSurface })
                    Spacer(GlanceModifier.width(8.dp))
                    WText("搜索歌曲、歌手或粘贴链接", 14.sp, palette.color { onSurfaceVariant })
                }
                WIconButton(R.drawable.ic_w_eq, 40.dp, 20.dp, palette, open(WidgetTarget.Recognize), "听歌识曲")
            }
        }
        ToolsBp.Cookie -> Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val side: Dp = minOf(size.width, size.height)
            val a = (side.value * 0.27f - 22f).dp
            val b = (side.value * 0.73f - 22f).dp
            Box(
                modifier = GlanceModifier.size(side).background(
                    ImageProvider(WidgetImages.mask((side.value * density).roundToInt(), WidgetShape.Cookie4, density)),
                    alpha = alpha,
                    contentScale = ContentScale.FillBounds,
                    colorFilter = ColorFilter.tint(palette.color { surface }),
                ),
            ) {
                CornerButton(a, a, R.drawable.ic_w_search, "搜索", open(WidgetTarget.Search), palette)
                CornerButton(b, a, R.drawable.ic_w_fav, "我喜欢", open(WidgetTarget.Favorites), palette)
                CornerButton(a, b, R.drawable.ic_w_headphones, "听歌模式", open(WidgetTarget.Modes), palette)
                CornerButton(b, b, R.drawable.ic_w_bedtime, "定时关闭", open(WidgetTarget.Timer), palette)
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = GlanceModifier.size(64.dp).tinted(R.drawable.widget_oval, palette.color { primary })
                            .onClick(open(WidgetTarget.Recognize)).describe("听歌识曲"),
                        contentAlignment = Alignment.Center,
                    ) {
                        WIcon(R.drawable.ic_w_eq, 30.dp, palette.color { onPrimary })
                    }
                }
            }
        }
    }
}

@Composable
private fun CornerButton(x: Dp, y: Dp, icon: Int, label: String, action: Action?, palette: WidgetPalette) {
    Box(modifier = GlanceModifier.padding(start = x, top = y)) {
        WIconButton(icon, 44.dp, 22.dp, palette, action, label)
    }
}
