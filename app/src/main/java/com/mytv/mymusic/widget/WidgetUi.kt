package com.mytv.mymusic.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.mytv.mymusic.R
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 一款组件的一档布局：最小宽高（dp）。列表按从小到大排，越靠后越优先。 */
data class Breakpoint<K>(val key: K, val width: Dp, val height: Dp)

/**
 * 断点按“占几格”取下限，不照搬原型 84×100dp 网格上的尺寸：真机桌面每格大小差得多（两格宽 130~190dp、
 * 两格高 150~230dp，五列桌面四格只有 260dp 左右）。档内的封面和按钮再按实际宽高收缩。
 */
object Cells {
    val W2 = 110.dp
    val W3 = 200.dp
    val W4 = 260.dp
    val H2 = 150.dp
    val H3 = 240.dp
}

/** 能放下的最靠后一档；一档都放不下时用第一档。 */
fun <K> pickBreakpoint(list: List<Breakpoint<K>>, size: DpSize): Breakpoint<K> =
    list.lastOrNull { it.width <= size.width && it.height <= size.height } ?: list.first()

/** 当前推送该用的那套颜色（Chronometer、阴影文字这些 RemoteViews 直接上色的地方用）。 */
fun WidgetPalette.resolve(context: Context, role: WidgetScheme.() -> Int): Int = when (mode) {
    WidgetThemeMode.Light -> light.role()
    WidgetThemeMode.Dark -> dark.role()
    WidgetThemeMode.System -> if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) dark.role() else light.role()
}

/** 白色形状图着色后当底：按钮、按钮组、标签。透明度走图片的 alpha，不靠颜色的 alpha（12 以下的着色会把白底透出来）。 */
fun GlanceModifier.tinted(@DrawableRes shape: Int, color: ColorProvider, alpha: Float = 1f): GlanceModifier =
    background(ImageProvider(shape), alpha = alpha, contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(color))

/** 组件外框的底色和圆角：Android 12 起用系统的组件圆角，更早用 24dp 的背景图。 */
fun GlanceModifier.widgetBackground(palette: WidgetPalette, style: WidgetBackground, role: WidgetScheme.() -> Int = { surface }): GlanceModifier = when {
    style == WidgetBackground.Clear -> this
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        background(palette.background(style, role)).cornerRadius(android.R.dimen.system_app_widget_background_radius)
    else -> tinted(R.drawable.widget_r24, palette.color(role = role), if (style == WidgetBackground.Glass) WidgetPalette.GLASS_ALPHA else 1f)
}

fun GlanceModifier.describe(text: String): GlanceModifier = semantics { contentDescription = text }

fun GlanceModifier.onClick(action: Action?): GlanceModifier = if (action == null) this else clickable(action)

@Composable
fun WText(
    text: String,
    size: TextUnit,
    color: ColorProvider,
    modifier: GlanceModifier = GlanceModifier,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = align),
        maxLines = maxLines,
    )
}

/** 原型里的几种文字：t 歌名、a 歌手、m 来源音质这类小字、t2 / a2 列表行。 */
object WType {
    val title = 15.sp
    val artist = 12.5.sp
    val meta = 11.5.sp
    val rowTitle = 13.5.sp
    val rowArtist = 11.5.sp
    val time = 11.sp
}

@Composable
fun WIcon(@DrawableRes icon: Int, size: Dp, color: ColorProvider, modifier: GlanceModifier = GlanceModifier, description: String? = null) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = description,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(color),
    )
}

/** 正在组合的是哪一款组件：封面取回来时只重组等这张图的那款。预览里是 null。 */
val LocalWidgetKind = staticCompositionLocalOf<WidgetKind?> { null }

/**
 * 封面：按 [shape] 裁好的位图。还没取到（或没有封面）时是 secondaryContainer 的同形状占位加音符，取回来后组件重组换上。
 */
@Composable
fun WCover(url: String, size: Dp, shape: WidgetShape, palette: WidgetPalette, modifier: GlanceModifier = GlanceModifier) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val px = (size.value * density).roundToInt()
    val kind = LocalWidgetKind.current
    // 在这里读封面计数：上层参数没变时 Compose 会跳过整段（榜单、歌单的行），只在顶层读重组不到这里
    if (kind != null) WidgetImages.version(kind).collectAsState().value
    val bitmap = WidgetImages.cover(context, url, px, shape, kind)
    if (bitmap != null) {
        if (WidgetImages.isTranslucent(url, px, shape)) {
            // 透明底的白色图标：同形状垫一块深一点的主色
            Box(
                modifier = modifier.size(size).background(
                    ImageProvider(WidgetImages.mask(px, shape, density)),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = ColorFilter.tint(palette.color { artTile }),
                ),
            ) {
                Image(provider = ImageProvider(bitmap), contentDescription = null, modifier = GlanceModifier.size(size), contentScale = ContentScale.Fit)
            }
        } else {
            Image(provider = ImageProvider(bitmap), contentDescription = null, modifier = modifier.size(size), contentScale = ContentScale.Fit)
        }
        return
    }
    Box(
        modifier = modifier.size(size).background(
            ImageProvider(WidgetImages.mask(px, shape, density)),
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(palette.color { secondaryContainer }),
        ),
        contentAlignment = Alignment.Center,
    ) {
        WIcon(R.drawable.ic_w_note, (size.value * 0.34f).dp, palette.color { onSecondaryContainer })
    }
}

/** 主色圆形播放键（原型的 .pb）。[ring] 给压在封面上的那颗加一圈底色描边。 */
@Composable
fun WPlayButton(
    playing: Boolean,
    size: Dp,
    palette: WidgetPalette,
    action: Action?,
    modifier: GlanceModifier = GlanceModifier,
    description: String = if (playing) "暂停，正在播放" else "播放，已暂停",
    ring: Dp = 0.dp,
    ringRole: WidgetScheme.() -> Int = { surface },
) {
    val button = @Composable { inner: GlanceModifier ->
        Box(
            modifier = inner.size(size).tinted(R.drawable.widget_oval, palette.color { primary }).onClick(action).describe(description),
            contentAlignment = Alignment.Center,
        ) {
            WIcon(if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play, (size.value * 0.5f).dp, palette.color { onPrimary })
        }
    }
    if (ring.value <= 0f) {
        button(modifier)
    } else {
        Box(
            modifier = modifier.size(size + ring * 2).tinted(R.drawable.widget_oval, palette.color(role = ringRole)),
            contentAlignment = Alignment.Center,
        ) { button(GlanceModifier) }
    }
}

/** 圆形次要按钮（.ib）：[tonal] 时是 secondaryContainer 底，否则只有图标。 */
@Composable
fun WIconButton(
    @DrawableRes icon: Int,
    size: Dp,
    iconSize: Dp,
    palette: WidgetPalette,
    action: Action?,
    description: String,
    modifier: GlanceModifier = GlanceModifier,
    tonal: Boolean = true,
    tint: ColorProvider? = null,
) {
    val base = modifier.size(size)
    Box(
        modifier = (if (tonal) base.tinted(R.drawable.widget_oval, palette.color { secondaryContainer }) else base)
            .onClick(action)
            .describe(description),
        contentAlignment = Alignment.Center,
    ) {
        WIcon(icon, iconSize, tint ?: if (tonal) palette.color { onSecondaryContainer } else palette.color { onSurfaceVariant })
    }
}

/**
 * 按钮组（.grp）：上一首、播放、下一首连成一条，两端 20dp 圆角、中间 8dp，间隔 2dp。
 * [width] 给定时铺满（上一首 : 播放 : 下一首 = 1 : 1.5 : 1）；不给时上一首 / 下一首 40dp、播放 50dp。
 */
@Composable
fun WButtonGroup(
    playing: Boolean,
    palette: WidgetPalette,
    actions: TransportActions,
    height: Dp,
    width: Dp? = null,
    prev: Boolean = true,
) {
    val gap = 2.dp
    val count = if (prev) 3 else 2
    val (side, middle) = if (width != null) {
        val units = (count - 1) + 1.5f
        val unit = (width.value - gap.value * (count - 1)) / units
        unit.dp to (unit * 1.5f).dp
    } else {
        40.dp to 50.dp
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (prev) {
            Segment(R.drawable.ic_w_prev, 20.dp, side, height, R.drawable.widget_seg_start, palette, accent = false, actions.previous, "上一首")
            Spacer(GlanceModifier.width(gap))
        }
        Segment(
            if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play, 24.dp, middle, height,
            if (prev) R.drawable.widget_seg_mid else R.drawable.widget_seg_start, palette, accent = true,
            actions.play, if (playing) "暂停，正在播放" else "播放，已暂停",
        )
        Spacer(GlanceModifier.width(gap))
        Segment(R.drawable.ic_w_next, 20.dp, side, height, R.drawable.widget_seg_end, palette, accent = false, actions.next, "下一首")
    }
}

@Composable
private fun Segment(
    @DrawableRes icon: Int,
    iconSize: Dp,
    width: Dp,
    height: Dp,
    @DrawableRes shape: Int,
    palette: WidgetPalette,
    accent: Boolean,
    action: Action?,
    description: String,
) {
    Box(
        modifier = GlanceModifier.width(width).height(height)
            .tinted(shape, if (accent) palette.color { primary } else palette.color { secondaryContainer })
            .onClick(action)
            .describe(description),
        contentAlignment = Alignment.Center,
    ) {
        WIcon(icon, iconSize, if (accent) palette.color { onPrimary } else palette.color { onSecondaryContainer })
    }
}

/** 播放、上一首、下一首三个按钮的动作。 */
data class TransportActions(val play: Action?, val previous: Action?, val next: Action?)

/** M3E 波浪进度条：已播段播放时是波浪、暂停时拉直（primary），未播段是 secondaryContainer 直线，末端一个主色点。 */
@Composable
fun WWave(width: Dp, fraction: Float, playing: Boolean, palette: WidgetPalette) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val wave = WidgetImages.wave(width.value, fraction, playing, density)
    Box(modifier = GlanceModifier.width(width).height(WidgetImages.WAVE_HEIGHT_DP.dp)) {
        Image(ImageProvider(wave.rest), null, GlanceModifier.fillMaxSize(), ContentScale.FillBounds, ColorFilter.tint(palette.color { secondaryContainer }))
        Image(ImageProvider(wave.played), null, GlanceModifier.fillMaxSize(), ContentScale.FillBounds, ColorFilter.tint(palette.color { primary }))
    }
}

/**
 * 计时文字（Chronometer）：系统每秒自己走字，组件不用推。[positionMs] 是现在听到的位置，[running] 时往前走，停着时只是普通文字；
 * [countDown] 时倒数到 0（定时关闭，Android 7 起）。[longestMs] 是这段计时会走到的最长时间，用来定宽度。
 *
 * Glance 给 AndroidRemoteViews 套的容器会撑满一行，把同一行后面的文字挤出去，所以宽度按最长的字算好给死；
 * 数字是等宽的（tnum），走字时宽度不变。
 */
@Composable
fun WChronometer(
    positionMs: Long,
    running: Boolean,
    palette: WidgetPalette,
    role: WidgetScheme.() -> Int = { onSurfaceVariant },
    countDown: Boolean = false,
    medium: Boolean = false,
    longestMs: Long = positionMs,
) {
    if (!running) {
        // 停着的 Chronometer 每次被桌面套用都按“现在 − base”重算一遍，暂停后过一会儿再翻到这页就多走了几秒：直接写死文字
        WText(
            elapsedText(if (countDown) positionMs + 999 else positionMs), WType.time, palette.color(role = role),
            weight = if (medium) FontWeight.Medium else FontWeight.Normal,
        )
        return
    }
    val context = LocalContext.current
    val modifier = GlanceModifier.width(chronometerWidth(context, longestMs, medium))
    val views = RemoteViews(context.packageName, if (medium) R.layout.widget_chronometer_medium else R.layout.widget_chronometer)
    val id = R.id.widget_chronometer
    val now = SystemClock.elapsedRealtime()
    if (countDown && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        views.setChronometerCountDown(id, true)
        // 倒数显示向上取整到秒，和 App 里的剩余时间一致
        views.setChronometer(id, now + positionMs + 999, null, running)
    } else {
        views.setChronometer(id, now - positionMs, null, running)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && palette.mode == WidgetThemeMode.System) {
        views.setColorInt(id, "setTextColor", palette.light.role(), palette.dark.role())
    } else {
        views.setTextColor(id, palette.resolve(context, role))
    }
    AndroidRemoteViews(views, modifier)
}

/** Chronometer 显示 [longestMs] 时的宽度（dp）：字号和布局里的 11sp 一致，跟系统字号缩放。 */
private fun chronometerWidth(context: Context, longestMs: Long, medium: Boolean): Dp {
    val metrics = context.resources.displayMetrics
    val paint = Paint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, WType.time.value, metrics)
        typeface = if (medium) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        fontFeatureSettings = "tnum"
    }
    return (ceil(paint.measureText(elapsedText(longestMs)) / metrics.density) + 2).dp
}

/** 已播 / 总长那一行：左边 Chronometer，右边总长。 */
@Composable
fun WTimes(positionMs: Long, durationMs: Long, playing: Boolean, palette: WidgetPalette, width: Dp) {
    Row(modifier = GlanceModifier.width(width), verticalAlignment = Alignment.CenterVertically) {
        WChronometer(positionMs, playing, palette, longestMs = maxOf(positionMs, durationMs))
        Spacer(GlanceModifier.defaultWeight())
        WText(elapsedText(durationMs), WType.time, palette.color { onSurfaceVariant })
    }
}

/** 定时关闭标签：tertiaryContainer 底，月亮图标加倒数。Android 6 没有倒数的 Chronometer，只显示到分钟。 */
@Composable
fun WTimerChip(remainingMs: Long, palette: WidgetPalette) {
    Row(
        modifier = GlanceModifier.tinted(R.drawable.widget_r10, palette.color { tertiaryContainer }).padding(start = 5.dp, top = 2.dp, end = 7.dp, bottom = 2.dp)
            .describe("定时关闭，还剩 ${ceil(remainingMs / 60_000.0).toInt()} 分钟"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WIcon(R.drawable.ic_w_bedtime, 12.dp, palette.color { onTertiaryContainer })
        Spacer(GlanceModifier.width(3.dp))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            WChronometer(remainingMs, running = true, palette = palette, role = { onTertiaryContainer }, countDown = true, medium = true, longestMs = remainingMs + 999)
        } else {
            WText("${ceil(remainingMs / 60_000.0).toInt()} 分钟", WType.time, palette.color { onTertiaryContainer }, weight = FontWeight.Medium)
        }
    }
}

/** 品牌标记方块（空状态）：primaryContainer 圆角底，框是 primary、音符是 onPrimaryContainer。 */
@Composable
fun WMarkTile(size: Dp, palette: WidgetPalette, modifier: GlanceModifier = GlanceModifier) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val px = (size.value * density).roundToInt()
    Box(
        modifier = modifier.size(size).background(
            ImageProvider(WidgetImages.mask(px, WidgetShape.Rounded(size.value * 0.3f), density)),
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(palette.color { primaryContainer }),
        ),
        contentAlignment = Alignment.Center,
    ) {
        WBrandMark(size, palette.color { primary }, palette.color { onPrimaryContainer })
    }
}

@Composable
fun WBrandMark(size: Dp, frame: ColorProvider, ink: ColorProvider) {
    Box(modifier = GlanceModifier.size(size)) {
        WIcon(R.drawable.ic_w_brand_frame, size, frame)
        WIcon(R.drawable.ic_w_brand_note, size, ink)
    }
}

/** 胶囊按钮（.pill）：secondaryContainer 底，图标加文字。 */
@Composable
fun WPill(@DrawableRes icon: Int, label: String, palette: WidgetPalette, action: Action?, modifier: GlanceModifier = GlanceModifier) {
    Row(
        modifier = modifier.height(40.dp).tinted(R.drawable.widget_pill, palette.color { secondaryContainer })
            .padding(horizontal = 16.dp).onClick(action).describe(label),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WIcon(icon, 18.dp, palette.color { onSecondaryContainer })
        Spacer(GlanceModifier.width(6.dp))
        WText(label, 13.5.sp, palette.color { onSecondaryContainer }, weight = FontWeight.Medium)
    }
}

/**
 * 透明背景歌词的一行字：白字加阴影。Glance 的 Text 不支持阴影，用 RemoteViews 嵌 TextView。
 * [dim] 时是 80% 白（下一句、翻译）。
 *
 * Glance 给 AndroidRemoteViews 套的容器在竖排里会撑满父布局的高，第一句占满、后面几句挤成 0 高：
 * 外面包一层 Box，按内容定高。
 */
@Composable
fun WShadowText(text: String, size: TextUnit, bold: Boolean, maxLines: Int, dim: Boolean, modifier: GlanceModifier = GlanceModifier.fillMaxWidth()) {
    val context = LocalContext.current
    val views = RemoteViews(context.packageName, if (bold) R.layout.widget_shadow_text_bold else R.layout.widget_shadow_text)
    val id = R.id.widget_shadow_text
    views.setTextViewText(id, text)
    views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size.value)
    views.setInt(id, "setMaxLines", maxLines)
    if (dim) views.setTextColor(id, 0xCCFFFFFF.toInt())
    Box(modifier = modifier) { AndroidRemoteViews(views, GlanceModifier.fillMaxWidth()) }
}

/** 和 Chronometer 走字同一个格式（DateUtils.formatElapsedTime：04:05、1:02:03），同一行里两种写法不会对不上。 */
fun elapsedText(ms: Long): String = DateUtils.formatElapsedTime((ms / 1000).coerceAtLeast(0))
