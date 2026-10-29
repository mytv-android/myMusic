package com.mytv.mymusic.ui.poster

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.components.TintedGradient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * 小格的字号和留白，取原型的三档：标题字号 = 格宽 × 系数，夹在上下限之间；序号小牌手机用小一号的。
 */
internal enum class TileStyle(
    val titleMin: Float,
    val titleFactor: Float,
    val titleMax: Float,
    val artistSp: Float,
    val metaSide: Dp,
    val metaBottom: Dp,
    val metaGap: Dp,
    val badgeInset: Dp,
    val badgeHeight: Dp,
    val badgePad: Dp,
    val badgeSp: Float,
    val badgeRadius: Dp,
) {
    Phone(12.5f, 0.1f, 21f, 10.5f, 8.dp, 7.dp, 1.dp, 6.dp, 16.dp, 5.dp, 9.5f, 5.dp),
    Tablet(17f, 0.095f, 36f, 12.5f, 12.dp, 11.dp, 3.dp, 10.dp, 20.dp, 7.dp, 11f, 6.dp),
    Tv(14f, 0.095f, 30f, 11.5f, 12.dp, 11.dp, 3.dp, 10.dp, 20.dp, 7.dp, 11f, 6.dp),
    ;

    /** 格宽 [widthDp] 时的标题字号（sp）。 */
    fun titleSp(widthDp: Float): Float = (widthDp * titleFactor).coerceIn(titleMin, titleMax)

    /** 没有封面时字体海报的大标题：标题字号的 1.35 倍，三行刚好放得下最小的 2×2 格。 */
    fun posterSp(widthDp: Float): Float = titleSp(widthDp) * POSTER_TITLE_SCALE
}

internal fun tileStyleFor(metrics: WallMetrics): TileStyle = when (metrics) {
    WallMetrics.Phone -> TileStyle.Phone
    WallMetrics.Tablet -> TileStyle.Tablet
    WallMetrics.Tv -> TileStyle.Tv
}

/** 序号小牌上的字：两位起，不够补零。 */
internal fun wallBadgeText(index: Int): String = (index + 1).toString().padStart(2, '0')

/** 读屏文案：“第 3 首，独上西楼，邓丽君，正在播放”。 */
internal fun tileDescription(index: Int, song: Song, current: Boolean): String = buildString {
    append("第 ").append(index + 1).append(" 首，").append(song.title)
    if (song.artist.isNotBlank()) append("，").append(song.artist)
    if (current) append("，正在播放")
}

/** 关灯时压 82% 黑：除了正在播放的那张大卡，其余都压，焦点格也一样（原型里关灯压过焦点）。 */
internal fun lightsOutDim(grown: Boolean, current: Boolean, lightsOut: Boolean): Boolean = lightsOut && !(grown && current)

/** 染色层透明度：surface × 染色强度，大卡和焦点格淡掉（[lift] 0 → 1）。 */
internal fun tintDimAlpha(tint: Float, lift: Float): Float = tint * (1f - lift.coerceIn(0f, 1f))

internal const val LIGHTS_OUT_ALPHA = 0.82f
private const val POSTER_TITLE_SCALE = 1.35f
private const val SETTLE_TIMEOUT_MS = 1000L
private const val LIFT_MS = 300
private const val SHADE_ALPHA_MID = 0.24f
private const val SHADE_ALPHA_END = 0.9f
private const val ARTIST_ALPHA = 0.78f
private const val BADGE_PLATE = 0.62f
private const val BADGE_PLATE_CURRENT = 0.78f
private const val GHOST_TINT = 0.06f
private const val GHOST_RING = 0.09f
private const val SHIMMER_PEAK = 0.13f
private val GROWN_ELEVATION = 18.dp
private val LIT_ELEVATION = 8.dp
private val CURRENT_OUTLINE = 2.dp
private val GROWN_OUTLINE = 1.5.dp
private val GHOST_RING_WIDTH = 1.5.dp
private val POSTER_TOP_GAP = 6.dp
private val RECT_THRESHOLD = Rect(0.1f, 0.1f, 0.1f, 0.1f)

internal fun WallRect.asRect(): Rect = Rect(x, y, right, bottom)

/**
 * 所有格子共用的环境：配色、设置、动效规格。快照状态只在用到的阶段读——染色强度、关灯只在绘制里读，
 * 改设置时格子只重绘；序号开关决定组合不组合小牌，改它时才重组。
 */
@Stable
internal class TileEnv(val wall: PosterWallState, val scope: CoroutineScope) {
    val palette: WallPalette get() = wall.palette
    var metrics by mutableStateOf(WallMetrics.Phone)
    var style by mutableStateOf(TileStyle.Phone)
    var tint by mutableFloatStateOf(0.52f)
    var lightsOut by mutableStateOf(false)
    var showIndex by mutableStateOf(true)
    var titleFamily by mutableStateOf<FontFamily?>(null)

    /** 减少动画：重排、圆角都直接跳到终点。 */
    var reduceMotion = false

    /** 重排（defaultSpatial）、焦点圆角（fastSpatial）、淡入淡出。 */
    var rectSpec: FiniteAnimationSpec<Rect> = spring(0.8f, 380f)
    var radiusSpec: FiniteAnimationSpec<Float> = spring(0.8f, 380f)
    var focusSpec: FiniteAnimationSpec<Float> = spring(0.6f, 800f)
}

/**
 * 一格的动画状态，组合时创建、离开组合时注销。位置（墙面 dp）和圆角各是一个 Animatable：
 * 刚进组合的格子直接在终点，之后目标变了（放大重排、焦点圆角）才走弹簧，所以只有重排的那一块在动。
 * [layerBlock] 只在图层阶段读状态：入场波、占位格透明度、形状、阴影都不触发重组。
 */
@Stable
internal class TileAnim(private val env: TileEnv, initial: WallTile, density: Float) : RememberObserver {
    private val wall = env.wall
    val pos: SlotPos = initial.pos

    var tile by mutableStateOf(initial)
        private set
    val rect = Animatable(initial.rect.asRect(), Rect.VectorConverter, RECT_THRESHOLD)
    private var rectTarget = initial.rect.asRect()

    private var radiusTarget = env.metrics.cornerRadius(initial.cells, initial.grown)
    val radius = Animatable(radiusTarget)

    /** 染色层淡掉的程度：放大、获得焦点时 0 → 1。 */
    val lift = Animatable(if (initial.grown) 1f else 0f)
    private var liftTarget = lift.value

    /** 占位格的透明度（逐格变淡）；真格子为 1。 */
    var ghostAlpha by mutableFloatStateOf(1f)
        private set

    /** 焦点环亮在这一格上。 */
    val lit: State<Boolean> = derivedStateOf { wall.litFocus == pos }

    /** 小封面成功加载后在内存缓存里的键：大卡和换档时拿它垫底。[lastCoverKey] 是不订阅的那份。 */
    var coverKey by mutableStateOf<String?>(null)
        private set
    var lastCoverKey: String? = null
        private set
    private var coverPx = if (initial.grown) 0 else (initial.rect.w * density).roundToInt()

    val onCoverLoaded: (AsyncImagePainter.State.Success) -> Unit = { s ->
        val k = s.result.memoryCacheKey?.key
        lastCoverKey = k
        coverKey = k
    }

    private var rectJob: Job? = null
    private var radiusJob: Job? = null
    private var liftJob: Job? = null
    private var shapePx = -1f
    private var shape: Shape = RectangleShape

    /** 封面按格子像素取档：只升不降，放大时不升（大卡自己取大图），这样重排来回不会反复换图。 */
    fun coverPxFor(t: WallTile, density: Float): Int {
        if (!t.grown) coverPx = max(coverPx, (t.rect.w * density).roundToInt())
        return max(1, coverPx)
    }

    /** 组合后调：目标变了才起动画；减少动画时直接跳到终点。 */
    fun update(t: WallTile, isLit: Boolean, ghost: Float) {
        val old = tile
        if (t != old) tile = t
        if (ghostAlpha != ghost) ghostAlpha = ghost
        val snap = env.reduceMotion
        val target = t.rect.asRect()
        if (target != rectTarget) {
            rectTarget = target
            rectJob?.cancel()
            rectJob = env.scope.launch { if (snap) rect.snapTo(target) else rect.animateTo(target, env.rectSpec) }
        }
        val r = env.metrics.cornerRadius(t.cells, t.grown) + if (isLit) WallMetrics.FOCUS_RADIUS_BOOST else 0f
        if (r != radiusTarget) {
            radiusTarget = r
            val spec = if (t.cells != old.cells || t.grown != old.grown) env.radiusSpec else env.focusSpec
            radiusJob?.cancel()
            radiusJob = env.scope.launch { if (snap) radius.snapTo(r) else radius.animateTo(r, spec) }
        }
        val l = if (isLit || t.grown) 1f else 0f
        if (l != liftTarget) {
            liftTarget = l
            liftJob?.cancel()
            liftJob = env.scope.launch { if (snap) lift.snapTo(l) else lift.animateTo(l, tween(LIFT_MS)) }
        }
    }

    /** 入场波里这一格走到哪了：不在波里为 1。 */
    fun entrance(t: WallTile): Float {
        val w = wall.wave ?: return 1f
        val d = WallEntrance.delayOf(w, t.song, t.rect, env.metrics.pitch)
        return if (d < 0f) 1f else WallEntrance.progress(w.style, wall.waveTime - d)
    }

    val layerBlock: GraphicsLayerScope.() -> Unit = {
        val t = tile
        val p = entrance(t)
        alpha = ghostAlpha * p.coerceIn(0f, 1f)
        if (p != 1f && wall.wave?.style == WaveStyle.Full) {
            translationY = -WallEntrance.RISE_DP.dp.toPx() * (1f - p)
            val s = WallEntrance.FROM_SCALE + (1f - WallEntrance.FROM_SCALE) * p
            scaleX = s
            scaleY = s
        } else {
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
        }
        shape = shapeFor(radius.value.dp.toPx())
        clip = true
        shadowElevation = when {
            t.grown -> GROWN_ELEVATION.toPx()
            lit.value -> LIT_ELEVATION.toPx()
            else -> 0f
        }
    }

    /** 圆角变了才换一个形状对象：图层只在形状换了时重算轮廓。 */
    private fun shapeFor(px: Float): Shape {
        if (px != shapePx) {
            shapePx = px
            shape = RoundedCornerShape(px)
        }
        return shape
    }

    override fun onRemembered() {
        wall.tileAnims[pos] = this
    }

    override fun onForgotten() {
        if (wall.tileAnims[pos] === this) wall.tileAnims.remove(pos)
        rectJob?.cancel()
        radiusJob?.cancel()
        liftJob?.cancel()
    }

    override fun onAbandoned() = Unit
}

private enum class TilePart { Face, Slot, Badge, Meta, Poster }

/**
 * 一格。墙按动画中的尺寸给固定约束；文字按目标宽度排一次，重排的弹簧走动时不重新排字（溢出部分被圆角裁掉）。
 * 放大的那格只画封面垫底，上面是 [grownContent]（大卡由页面提供，墙负责槽位、形状、描边、阴影和位置）。
 */
@Composable
internal fun PosterTile(
    tile: WallTile,
    song: Song?,
    ghostAlpha: Float?,
    current: Boolean,
    env: TileEnv,
    grownContent: (@Composable BoxScope.(index: Int, current: Boolean, settled: Boolean) -> Unit)?,
) {
    val density = LocalDensity.current.density
    val anim = remember { TileAnim(env, tile, density) }
    val isLit by anim.lit
    SideEffect { anim.update(tile, isLit, ghostAlpha ?: 1f) }
    if (ghostAlpha != null || song == null) {
        GhostTile(anim, env)
    } else {
        SongTile(tile, song, current, anim, env, density, grownContent)
    }
}

@Composable
private fun SongTile(
    tile: WallTile,
    song: Song,
    current: Boolean,
    anim: TileAnim,
    env: TileEnv,
    density: Float,
    grownContent: (@Composable BoxScope.(index: Int, current: Boolean, settled: Boolean) -> Unit)?,
) {
    val context = LocalContext.current
    val wall = env.wall
    val palette = env.palette
    val style = env.style
    // 地址按档位取（240 / 480 / 800），解码按格子实际像素：一屏几十格，电视盒子上内存比清晰度要紧
    val px = anim.coverPxFor(tile, density)
    val tier = coverTier(px)
    val url = remember(song.cover, tier) { song.coverAt(tier) }
    var failed by remember(url) { mutableStateOf(false) }
    val fallback = url.isBlank() || failed
    val request = if (fallback) {
        null
    } else {
        remember(context, url, px) {
            ImageRequest.Builder(context)
                .data(url)
                .size(px)
                .precision(Precision.INEXACT)
                .placeholderMemoryCacheKey(anim.lastCoverKey)
                .build()
        }
    }
    val semantics = if (tile.grown) {
        Modifier
    } else {
        val index = tile.song
        val description = tileDescription(index, song, current)
        Modifier.clearAndSetSemantics {
            contentDescription = description
            onClick(label = "放大") {
                wall.growTile(anim.pos)
                true
            }
            onLongClick(label = "歌曲操作") {
                wall.onLongPress(index)
                true
            }
        }
    }
    Layout(
        content = {
            TileFace(anim, env, request, current, fallback) { failed = true }
            if (tile.grown) {
                if (grownContent != null) {
                    val settled = rememberSettled(anim, tile)
                    Box(Modifier.layoutId(TilePart.Slot)) { grownContent(tile.song, current, settled) }
                }
            } else {
                if (env.showIndex) TileBadge(wallBadgeText(tile.song), current, style, palette)
                val widthDp = tile.rect.w
                if (fallback) PosterTitle(song.title, style.posterSp(widthDp), env)
                TileMeta(if (fallback) null else song.title, song.artist, style, style.titleSp(widthDp), fallback, env)
            }
        },
        modifier = Modifier
            .layoutId(anim)
            .then(semantics)
            .drawWithCache {
                val stroke = Stroke(GROWN_OUTLINE.toPx())
                val half = stroke.width / 2
                onDrawWithContent {
                    drawContent()
                    // 大卡描一圈 1.5dp 的主色；焦点环亮在大卡上时换成焦点环（墙画）
                    if (anim.tile.grown && !anim.lit.value) {
                        drawRoundRect(
                            color = palette.scheme.primary.copy(alpha = 0.7f),
                            topLeft = Offset(half, half),
                            size = Size(size.width - stroke.width, size.height - stroke.width),
                            cornerRadius = CornerRadius(max(0f, anim.radius.value.dp.toPx() - half)),
                            style = stroke,
                        )
                    }
                }
            },
        measurePolicy = remember(anim, env) { TileMeasurePolicy(anim, env) },
    )
}

/** 大卡的弹簧落定了，或者放大后已经过了 1 秒（低端盒子上弹簧慢，歌词不能一直不出来）。 */
@Composable
private fun rememberSettled(anim: TileAnim, tile: WallTile): Boolean {
    var late by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SETTLE_TIMEOUT_MS)
        late = true
    }
    val target = tile.rect.asRect()
    val atRest by remember(target) { derivedStateOf { anim.rect.value == target } }
    return late || atRest
}

/** 封面、染色 / 关灯层、底部渐变、当前歌曲描边。颜色全在绘制阶段读。 */
@Composable
private fun TileFace(
    anim: TileAnim,
    env: TileEnv,
    request: ImageRequest?,
    current: Boolean,
    fallback: Boolean,
    onError: () -> Unit,
) {
    Box(
        Modifier
            .layoutId(TilePart.Face)
            .drawWithCache {
                val shade = TintedGradient(
                    Brush.verticalGradient(
                        0.34f to Color.Transparent,
                        0.56f to Color.White.copy(alpha = SHADE_ALPHA_MID),
                        1f to Color.White.copy(alpha = SHADE_ALPHA_END),
                    ),
                )
                val outline = Stroke(CURRENT_OUTLINE.toPx())
                val half = outline.width / 2
                onDrawWithContent {
                    val s = env.palette.scheme
                    val t = anim.tile
                    drawRect(if (fallback) s.primaryContainer else s.surfaceContainerHigh)
                    if (fallback) drawGrain()
                    drawContent()
                    if (lightsOutDim(t.grown, current, env.lightsOut)) {
                        drawRect(Color.Black.copy(alpha = LIGHTS_OUT_ALPHA))
                    } else if (!t.grown) {
                        val a = tintDimAlpha(env.tint, anim.lift.value)
                        if (a > 0f) drawRect(s.surface.copy(alpha = a))
                    }
                    if (!fallback && !t.grown) shade.draw(this, s.surface)
                    if (current && !t.grown) {
                        drawRoundRect(
                            color = s.primary,
                            topLeft = Offset(half, half),
                            size = Size(size.width - outline.width, size.height - outline.width),
                            cornerRadius = CornerRadius(max(0f, anim.radius.value.dp.toPx() - half)),
                            style = outline,
                        )
                    }
                }
            },
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = anim.onCoverLoaded,
                onError = { onError() },
            )
        }
    }
}

@Composable
private fun TileBadge(text: String, current: Boolean, style: TileStyle, palette: WallPalette) {
    val textStyle = remember(style) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = style.badgeSp.sp,
            lineHeight = style.badgeSp.sp,
            letterSpacing = 0.08.em,
            fontFeatureSettings = "tnum",
        )
    }
    BasicText(
        text = text,
        modifier = Modifier
            .layoutId(TilePart.Badge)
            .height(style.badgeHeight)
            .drawBehind {
                val s = palette.scheme
                drawRoundRect(
                    color = s.surface.copy(alpha = if (current) BADGE_PLATE_CURRENT else BADGE_PLATE),
                    cornerRadius = CornerRadius(style.badgeRadius.toPx()),
                )
            }
            .padding(horizontal = style.badgePad)
            .wrapContentHeight(),
        style = textStyle,
        maxLines = 1,
        softWrap = false,
        color = { if (current) palette.scheme.primary else palette.scheme.onSurface },
    )
}

/**
 * 标题（Roboto Flex 800，最多三行）和歌手（一行）。没有封面时标题改由 [PosterTitle] 大字排，这里只留歌手。
 * 高度由 [TileMeasurePolicy] 限在序号小牌下面：大字号时先保歌手，标题让出行数、省略号收尾，不顶到小牌上。
 */
@Composable
private fun TileMeta(
    title: String?,
    artist: String,
    style: TileStyle,
    titleSp: Float,
    fallback: Boolean,
    env: TileEnv,
) {
    val family = env.titleFamily
    val palette = env.palette
    val titleStyle = remember(titleSp, family) {
        TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.ExtraBold,
            fontSize = titleSp.sp,
            lineHeight = (titleSp * 1.02f).sp,
            letterSpacing = (-0.02).em,
            lineBreak = LineBreak.Heading,
        )
    }
    val artistStyle = remember(style, family) {
        TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.SemiBold,
            fontSize = style.artistSp.sp,
            lineHeight = (style.artistSp * 1.3f).sp,
        )
    }
    Column(Modifier.layoutId(TilePart.Meta), verticalArrangement = Arrangement.spacedBy(style.metaGap)) {
        if (title != null) {
            BasicText(
                text = title,
                modifier = Modifier.weight(1f, fill = false),
                style = titleStyle,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = { palette.scheme.onSurface },
            )
        }
        if (artist.isNotBlank()) {
            BasicText(
                text = artist,
                style = artistStyle,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                color = {
                    val s = palette.scheme
                    (if (fallback) s.onPrimaryContainer else s.onSurface).copy(alpha = ARTIST_ALPHA)
                },
            )
        }
    }
}

/** 没有封面的曲目：歌名大字直接排在格子里，铺 primaryContainer 和一层颗粒，像一张字体海报。 */
@Composable
private fun PosterTitle(title: String, sp: Float, env: TileEnv) {
    val family = env.titleFamily
    val palette = env.palette
    val textStyle = remember(sp, family) {
        TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Black,
            fontSize = sp.sp,
            lineHeight = (sp * 1.0f).sp,
            letterSpacing = (-0.03).em,
            lineBreak = LineBreak.Heading,
        )
    }
    BasicText(
        text = title,
        modifier = Modifier.layoutId(TilePart.Poster),
        style = textStyle,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        color = { palette.scheme.onPrimaryContainer },
    )
}

/** 听歌模式队尾的占位格：主色淡淡混进底色、一圈细边，续取时走一道微光。读屏跳过。 */
@Composable
private fun GhostTile(anim: TileAnim, env: TileEnv) {
    val wall = env.wall
    Spacer(
        Modifier
            .layoutId(anim)
            .clearAndSetSemantics { }
            .drawWithCache {
                val ring = Stroke(GHOST_RING_WIDTH.toPx())
                val half = ring.width / 2
                val shimmer = ShimmerBand()
                onDrawBehind {
                    val s = env.palette.scheme
                    drawRect(s.primary.copy(alpha = GHOST_TINT).compositeOver(s.surfaceContainerHigh))
                    drawRoundRect(
                        color = s.onSurface.copy(alpha = GHOST_RING),
                        topLeft = Offset(half, half),
                        size = Size(size.width - ring.width, size.height - ring.width),
                        cornerRadius = CornerRadius(max(0f, anim.radius.value.dp.toPx() - half)),
                        style = ring,
                    )
                    val a = wall.shimmerAlpha
                    if (a > 0f) shimmer.draw(this, wall.shimmerPhase, a * SHIMMER_PEAK, s.onSurface)
                    if (env.lightsOut) drawRect(Color.Black.copy(alpha = LIGHTS_OUT_ALPHA))
                }
            },
    )
}

/**
 * 格子内部的摆放：封面和大卡槽铺满（动画中的尺寸），序号贴左上，标题歌手贴底；
 * 文字宽度按目标尺寸算，弹簧走动时不重新排字。
 */
private class TileMeasurePolicy(private val anim: TileAnim, private val env: TileEnv) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val style = env.style
        // 文字按落定后的尺寸排：格子在动画里变大变小时不重新折行
        val targetW = (anim.tile.rect.w * density).roundToInt()
        val targetH = (anim.tile.rect.h * density).roundToInt()
        val side = style.metaSide.roundToPx()
        val inner = max(0, targetW - 2 * side)
        val f = measurables.firstOrNull { it.layoutId == TilePart.Face }?.measure(Constraints.fixed(w, h))
        val g = measurables.firstOrNull { it.layoutId == TilePart.Slot }?.measure(Constraints.fixed(w, h))
        val b = measurables.firstOrNull { it.layoutId == TilePart.Badge }?.measure(Constraints())
        val bi = style.badgeInset.roundToPx()
        val gap = POSTER_TOP_GAP.roundToPx()
        val posterY = if (b != null) bi + b.height + gap else side
        val metaBottom = style.metaBottom.roundToPx()
        // 标题和歌手只能用到小牌下沿以下；字号放大时省略，不叠到小牌上、不冒出格子
        val metaRoom = max(0, targetH - metaBottom - posterY)
        val m = measurables.firstOrNull { it.layoutId == TilePart.Meta }
            ?.measure(Constraints(minWidth = inner, maxWidth = inner, maxHeight = metaRoom))
        val posterRoom = max(0, metaRoom - (m?.let { it.height + gap } ?: 0))
        val p = measurables.firstOrNull { it.layoutId == TilePart.Poster }
            ?.measure(Constraints(minWidth = inner, maxWidth = inner, maxHeight = posterRoom))
        return layout(w, h) {
            f?.place(0, 0)
            g?.place(0, 0)
            b?.place(bi, bi)
            p?.place(side, posterY)
            if (m != null) m.place(side, h - metaBottom - m.height)
        }
    }
}

/**
 * 占位格上的微光：原型是 100° 的线性渐变，宽 260%，背景位置 130% → −130%。
 * 换算成格子坐标：光带中心 x = 宽 × (−0.78 + 4.16 × 相位)，半宽 0.39 × 宽。shader 只按尺寸建一次，每帧平移画布。
 */
private class ShimmerBand {
    private val paint = Paint()
    private var w = -1f
    private var h = -1f
    private var lastTint = Color.Unspecified

    fun draw(scope: DrawScope, phase: Float, alpha: Float, tint: Color) = with(scope) {
        val width = size.width
        val height = size.height
        if (width != w || height != h) {
            w = width
            h = height
            val hw = width * SHIMMER_HALF
            val dx = sin(SHIMMER_ANGLE) * hw
            val dy = -cos(SHIMMER_ANGLE) * hw
            paint.shader = LinearGradientShader(
                from = Offset(-dx, height / 2 - dy),
                to = Offset(dx, height / 2 + dy),
                colors = listOf(Color.Transparent, Color.White, Color.Transparent),
                tileMode = TileMode.Clamp,
            )
        }
        if (tint != lastTint) {
            paint.colorFilter = ColorFilter.tint(tint)
            lastTint = tint
        }
        paint.alpha = alpha
        val c = width * (SHIMMER_START + SHIMMER_TRAVEL * phase)
        drawIntoCanvas {
            it.translate(c, 0f)
            it.drawRect(-c, 0f, width - c, height, paint)
            it.translate(-c, 0f)
        }
    }

    private companion object {
        const val SHIMMER_HALF = 0.39f
        const val SHIMMER_START = -0.78f
        const val SHIMMER_TRAVEL = 4.16f
        const val SHIMMER_ANGLE = (100.0 / 180.0 * Math.PI).toFloat()
    }
}

/** 字体海报上的颗粒：一张 96px 的灰度噪点平铺，所有格子共用一个 Paint（只在主线程画）。 */
private val GrainPaint: Paint by lazy(LazyThreadSafetyMode.NONE) {
    val n = GRAIN_SIZE
    val random = Random(7)
    val pixels = IntArray(n * n) {
        val v = random.nextInt(256)
        (GRAIN_ALPHA shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bitmap = Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    Paint().apply { shader = ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated) }
}

private const val GRAIN_SIZE = 96
private const val GRAIN_ALPHA = 30

private fun DrawScope.drawGrain() {
    drawIntoCanvas { it.drawRect(0f, 0f, size.width, size.height, GrainPaint) }
}
