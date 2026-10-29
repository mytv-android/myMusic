package com.mytv.mymusic.ui.poster

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.components.DeviceKind
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.TintedGradient
import com.mytv.mymusic.ui.components.WavySeekBar
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.player.CookiePlayButton
import com.mytv.mymusic.ui.player.TvFrameGate
import com.mytv.mymusic.ui.player.ambientSpinEnabled
import com.mytv.mymusic.ui.player.rememberParsedLyrics
import com.mytv.mymusic.ui.player.rememberSystemAnimationsOff
import com.mytv.mymusic.ui.theme.LocalAppearance
import com.mytv.mymusic.ui.theme.LocalBlurPolicy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * 大卡在各端的尺寸和字号，对照原型：卡片边长（墙的 6×6，和 WallMetrics.grownSize 一致）、内边距、
 * 歌词最大字号和翻译字号、顶部歌名字号、“别的歌”底部大标题字号。
 */
internal enum class GrownPosterStyle(
    val card: Dp,
    val padding: Dp,
    val lyricSp: Float,
    val translationSp: Float,
    val titleSp: Float,
    val bigTitleSp: Float,
) {
    Phone(332.dp, 16.dp, lyricSp = 20f, translationSp = 12.5f, titleSp = 18f, bigTitleSp = 34f),
    Tablet(606.dp, 26.dp, lyricSp = 34f, translationSp = 16f, titleSp = 22f, bigTitleSp = 60f),
    Tv(451.dp, 20.dp, lyricSp = 26f, translationSp = 14f, titleSp = 19f, bigTitleSp = 46f),
    ;

    /** 没有歌词时歌词区换成的大歌名：歌词最大字号的 1.7 倍。 */
    val plainTitleSp: Float get() = lyricSp * 1.7f

    companion object {
        fun of(kind: DeviceKind): GrownPosterStyle = when (kind) {
            DeviceKind.Phone -> Phone
            DeviceKind.Tablet -> Tablet
            DeviceKind.Tv -> Tv
        }

        /** 按墙的格距取：横屏手机窗口宽 ≥ 600 算平板，但墙用手机格距，大卡只有 332dp，排版也要跟着用手机这一档。 */
        fun of(metrics: WallMetrics): GrownPosterStyle = when (metrics) {
            WallMetrics.Phone -> Phone
            WallMetrics.Tablet -> Tablet
            WallMetrics.Tv -> Tv
        }
    }
}

/** 封面怎么处理：模糊半径（dp）、压一层 surface 的比例、放大倍数（模糊时放大一点盖住发虚的边）、是否提饱和度。 */
@Immutable
internal data class CoverTreatment(val blurDp: Float, val dim: Float, val zoom: Float, val saturate: Boolean)

/** 正在播放、有歌词时盖的 surface 比例。 */
internal const val NowCoverDim = 0.62f
/** 没有歌词时压暗减半（原型里是同一层 surface 再乘 0.55）。 */
internal const val PlainCoverDimShare = 0.55f
/** 不能模糊（电视、Android 12 以下、低内存、关了模糊）时改成压得更暗，歌词照样看得清。 */
internal const val NoBlurCoverDim = 0.76f
/**
 * 模糊半径。原型的 2px 是对着程序画的平滑封面调的，真封面细节多，2dp 盖不住，歌词压在上面不好认；
 * 这里取 12dp，封面的大色块和构图还认得出来。
 */
internal const val NowCoverBlurDp = 12f

/**
 * 三种状态各自的封面处理：别的歌清晰不压；正在播放没有歌词时不模糊、压暗减半；
 * 有歌词时允许模糊就模糊 + 62% surface，否则只压暗。
 */
internal fun coverTreatment(current: Boolean, hasLyrics: Boolean, blurAllowed: Boolean): CoverTreatment = when {
    !current -> CoverTreatment(blurDp = 0f, dim = 0f, zoom = 1f, saturate = false)
    !hasLyrics -> CoverTreatment(blurDp = 0f, dim = NowCoverDim * PlainCoverDimShare, zoom = 1f, saturate = false)
    blurAllowed -> CoverTreatment(blurDp = NowCoverBlurDp, dim = NowCoverDim, zoom = 1.06f, saturate = true)
    else -> CoverTreatment(blurDp = 0f, dim = NoBlurCoverDim, zoom = 1f, saturate = false)
}

/** 序号两位补零：“正在播放 · 03”“第 07 首”。 */
internal fun posterNumber(number: Int): String = number.toString().padStart(2, '0')

/** “正在播放”标签里三根跳动的条：0.8 秒从 3dp 长到 10dp 再回落，三根错开相位。 */
private const val EqHalfPeriodS = 0.8f
private const val EqMinDp = 3f
private const val EqMaxDp = 10f
private val EqLeads = floatArrayOf(0f, 0.3f, 0.55f)

/** 第 [leadS] 秒相位的那根条在 [timeS] 时的高度（dp）：往返的缓入缓出，和原型的 ease-in-out alternate 一致。 */
internal fun eqBarHeight(timeS: Float, leadS: Float): Float {
    val local = (timeS + leadS).mod(EqHalfPeriodS * 2)
    val p = local / EqHalfPeriodS
    val sweep = if (p <= 1f) p else 2f - p
    val eased = (1f - cos(PI * sweep).toFloat()) / 2f
    return EqMinDp + (EqMaxDp - EqMinDp) * eased
}

/** 花瓣播放键 14 秒转一圈，比封面轮廓（40 秒）快，按帧间隔累加，角度保持在 0..360。 */
private const val CookieSpinPeriodS = 14f

internal fun advanceCookieSpin(angle: Float, elapsedNanos: Long): Float =
    (angle + elapsedNanos / 1_000_000_000f * 360f / CookieSpinPeriodS) % 360f

/** 底部控件行和“别的歌”操作行的最小高度（48dp 触控高度）。 */
private val ControlsHeight = 48.dp
private val ChipShape = RoundedCornerShape(8.dp)
private val SeekFocusShape = RoundedCornerShape(16.dp)
private const val CoverSaturation = 1.15f

/**
 * 海报墙的 6×6 大卡。同一个卡按内容分三种：
 * 1. 正在播放、有歌词：封面模糊后压 62% surface（不能模糊时只压暗）；顶上“正在播放 · 03”标签（带三根跳动的条）和歌名歌手，
 *    中间是 [PosterLyrics]，底下是花瓣播放键、波浪进度条和“打开播放页”，悬停、获得焦点或按空格时上一首 / 下一首从播放键旁展开；
 * 2. 正在播放、没有歌词（纯音乐、取不到、未同步）：封面不模糊、压暗减半，歌词区换成放大的歌名；
 * 3. 别的歌：封面清晰，左上角“第 07 首 · 来源”，底部大标题和“播放 / 下一首播放 / 更多”。
 *
 * 墙负责外面的圆角裁切、描边和阴影，这里铺满给定的卡片框。内容按放大到位的卡片尺寸排一次版，放大途中整体缩放跟着卡走；
 * 放大的弹簧落定（[settled]）前只有封面和标题在动：歌词这时还不组合，标签的条、花瓣旋转和进度条的波浪也不动。
 * 逐帧变化的值（[position]、[coverScale]、花瓣旋转、跳动条）都只在绘制或图层里读。
 *
 * @param number 1 起的序号。
 * @param lyrics 当前歌曲的原始歌词；没有或不是当前歌时传空串。
 * @param lyricsLoading 切歌后这首的歌词还没取回来：先按“有歌词”排、歌词区留空，不显示上一首的歌词，也不闪“暂无歌词”。
 * @param lightsOut 墙开着关灯：别的歌的大卡自己把封面压到 82% 黑（墙的关灯层在大卡底下），文字和按钮照常亮。
 * @param focused 墙自己的焦点（电视方向键）停在这张卡上：和获得 Compose 焦点一样展开上一首 / 下一首。
 * @param primaryFocusRequester 挂在正在播放卡的播放键、别的歌卡的“播放”上，墙把焦点交进卡里时用。
 * @param coverModifier 加在封面层上，给 SharedCover 飞入飞出用。
 * @param coverPlaceholderKey 小格封面在 Coil 内存缓存里的键：大图加载完之前先拿它垫着，放大时不闪底色。
 */
@Composable
internal fun GrownPoster(
    song: Song,
    number: Int,
    sourceName: String,
    current: Boolean,
    playing: Boolean,
    position: State<Long>,
    duration: Long,
    lyrics: String,
    showTranslation: Boolean,
    settled: Boolean,
    coverScale: () -> Float = { 1f },
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenPlayer: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    primaryFocusRequester: FocusRequester? = null,
    coverModifier: Modifier = Modifier,
    coverPlaceholderKey: String? = null,
    lyricsLoading: Boolean = false,
    lightsOut: Boolean = false,
) {
    val window = LocalAppWindow.current
    val style = GrownPosterStyle.of(wallMetricsFor(window.isTv, window.widthDp, window.heightDp))
    val cardPx = with(LocalDensity.current) { style.card.roundToPx() }
    // RenderEffect 要 Android 12+；电视即使在外观里强开模糊也只压暗
    val blurAllowed = LocalBlurPolicy.current.enabled && Build.VERSION.SDK_INT >= 31 && !window.isTv
    // 解析放在后台（播放页同一条路径）；不是当前歌、歌词还没取回来时不解析
    val fetching = current && lyricsLoading
    val raw = if (current && !fetching) lyrics else ""
    val parsed = rememberParsedLyrics(raw)
    val lines = remember(parsed.lyrics) { posterLines(parsed.lyrics) }
    // 原文还在取、还在解析时先按“有歌词”排（不闪成大歌名），但不显示上一份结果
    val hasLyrics = fetching || (raw.isNotBlank() && (parsed.loading || lines.isNotEmpty()))
    val shownLines = if (fetching || parsed.loading) emptyList() else lines
    val treatment = coverTreatment(current, hasLyrics, blurAllowed)

    // 上一首 / 下一首展开的三个来源，各自是状态，只有控件行读
    val hover = remember { MutableInteractionSource() }
    val cardFocus = remember { mutableStateOf(false) }
    val keyReveal = remember { mutableStateOf(false) }
    val reveal = remember(focused) { ControlsReveal(hover, cardFocus, keyReveal, focused) }
    Box(
        modifier
            .fillMaxSize()
            .hoverable(hover)
            .onFocusChanged {
                cardFocus.value = it.hasFocus
                if (!it.hasFocus) keyReveal.value = false
            }
            // 空格只展开，不吃掉按键：焦点在按钮上时按钮照样响应
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Spacebar && event.type == KeyEventType.KeyDown) keyReveal.value = true
                false
            },
    ) {
        PosterCover(song, cardPx, treatment, coverScale, coverModifier, coverPlaceholderKey)
        PosterScrims(treatment.dim, blackout = lightsOut && !current)
        // 换状态（这张卡开始 / 不再播放）时内容重新淡入，对照原型的 npIn
        SettleIn(current, Modifier.fillMaxSize().atCardSize(cardPx)) {
            if (current) {
                NowCard(
                    song = song,
                    number = number,
                    style = style,
                    playing = playing,
                    position = position,
                    duration = duration,
                    hasLyrics = hasLyrics,
                    lines = shownLines,
                    showTranslation = showTranslation,
                    settled = settled,
                    blurLyrics = blurAllowed,
                    reveal = reveal,
                    focusRequester = primaryFocusRequester,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeek = onSeek,
                    onOpenPlayer = onOpenPlayer,
                )
            } else {
                OtherCard(song, number, sourceName, style, primaryFocusRequester, onPlay, onPlayNext, onMore)
            }
        }
    }
}

/** 上一首 / 下一首什么时候展开：悬停、卡里有 Compose 焦点、按过空格，或者墙的焦点在这张卡上。 */
@Immutable
private class ControlsReveal(
    val hover: InteractionSource,
    val cardFocus: State<Boolean>,
    val keyReveal: State<Boolean>,
    val focused: Boolean,
)

/**
 * 内容按放大到位的卡片尺寸 [cardPx] 排一次版，卡片放大 / 收起途中只按比例缩小摆放（只缩不放，低版本上文字不糊），
 * 子项的测量约束不变，文字不逐帧重新折行。墙给的框比这个大时按实际大小排。
 */
private fun Modifier.atCardSize(cardPx: Int): Modifier = layout { measurable, constraints ->
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else cardPx
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else cardPx
    val fullWidth = max(width, cardPx)
    val fullHeight = max(height, cardPx)
    val placeable = measurable.measure(Constraints.fixed(fullWidth, fullHeight))
    layout(width, height) {
        val scale = min(width.toFloat() / fullWidth, height.toFloat() / fullHeight)
        placeable.placeWithLayer(0, 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

/** 第一次组合（或 [key] 变了）时淡入并上移 12dp；减少动画时直接显示。 */
@Composable
private fun SettleIn(key: Any, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduceMotion = LocalAppearance.current.reduceMotion
    val shown = remember(key) { Animatable(if (reduceMotion) 1f else 0f) }
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    LaunchedEffect(shown) { shown.animateTo(1f, spec) }
    Box(
        modifier.graphicsLayer {
            alpha = shown.value
            translationY = (1f - shown.value) * 12.dp.toPx()
        },
    ) { content() }
}

/**
 * 封面层：按大卡像素取档（[coverAt]），模糊、放大、跟拍缩放都在图层里做。跟拍缩放在外层、模糊在内层，
 * 跟拍逐帧变的只是外层的变换，模糊过的内容不用重画。
 */
@Composable
private fun PosterCover(
    song: Song,
    cardPx: Int,
    treatment: CoverTreatment,
    coverScale: () -> Float,
    coverModifier: Modifier,
    placeholderKey: String?,
) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val blur = animateFloatAsState(treatment.blurDp, effects, label = "posterCoverBlur")
    val zoom = animateFloatAsState(treatment.zoom, effects, label = "posterCoverZoom")
    val context = LocalContext.current
    val url = remember(song.cover, cardPx) { song.coverAt(cardPx) }
    val request = remember(context, url, cardPx, placeholderKey) {
        // 固定按大卡尺寸取图：卡片从小格长大时 AsyncImage 按第一次的约束取图会拿到小图
        ImageRequest.Builder(context)
            .data(url)
            .size(cardPx)
            .precision(Precision.INEXACT)
            .placeholderMemoryCacheKey(placeholderKey)
            .build()
    }
    val saturate = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(CoverSaturation) }) }
    Box(
        Modifier
            .fillMaxSize()
            .then(coverModifier)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .graphicsLayer {
                val scale = zoom.value * coverScale()
                scaleX = scale
                scaleY = scale
            },
    ) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (treatment.saturate) saturate else null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val radius = blur.value.dp.toPx()
                        renderEffect = if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Clamp) else null
                    },
            )
        }
    }
}

/**
 * 压在封面上的 surface 层和上下两道渐变（顶上 60% 到 26% 处透明，底下 92% 到 54% 处透明），给标签、歌词、控件垫底。
 * [blackout]（关灯时别的歌的大卡）先把封面压 82% 黑，和小格的关灯层一样。
 * 渐变缓存在 drawWithCache 里，配色只作为滤镜色在绘制时套上（和 CollectionScreen 的头部同一个做法）。
 */
@Composable
private fun PosterScrims(dim: Float, blackout: Boolean) {
    val surface = MaterialTheme.colorScheme.surface
    val amount = animateFloatAsState(dim, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "posterDim")
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val top = TintedGradient(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.6f), 0.26f to Color.Transparent))
                val bottom = TintedGradient(Brush.verticalGradient(0.54f to Color.Transparent, 1f to Color.White.copy(alpha = 0.92f)))
                onDrawBehind {
                    if (blackout) drawRect(Color.Black, alpha = LIGHTS_OUT_ALPHA)
                    val alpha = amount.value
                    if (alpha > 0.001f) drawRect(surface, alpha = alpha)
                    top.draw(this, surface)
                    bottom.draw(this, surface)
                }
            },
    )
}

@Composable
private fun NowCard(
    song: Song,
    number: Int,
    style: GrownPosterStyle,
    playing: Boolean,
    position: State<Long>,
    duration: Long,
    hasLyrics: Boolean,
    lines: List<PosterLine>,
    showTranslation: Boolean,
    settled: Boolean,
    blurLyrics: Boolean,
    reveal: ControlsReveal,
    focusRequester: FocusRequester?,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(style.padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PosterChip(
                text = "正在播放 · ${posterNumber(number)}",
                spoken = "正在播放，第 $number 首",
                container = scheme.primaryContainer,
                content = scheme.onPrimaryContainer,
            ) { EqBars(animate = playing && settled, color = scheme.onPrimaryContainer) }
            // 没有歌词时歌名放大到歌词区，这里不重复
            if (hasLyrics) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        song.title,
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = style.titleSp.sp,
                            lineHeight = (style.titleSp * 1.2f).sp,
                            fontWeight = FontWeight.ExtraBold,
                        ),
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (song.artist.isNotBlank()) {
                        Text(
                            song.artist,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 12.5.sp,
                                lineHeight = (12.5f * 1.35f).sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = scheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!hasLyrics) {
                PlainTitle(song, style)
            } else if (settled && lines.isNotEmpty()) {
                // 放大的弹簧落定后才组合歌词
                SettleIn(lines, Modifier.fillMaxSize()) {
                    PosterLyrics(
                        lines = lines,
                        position = position,
                        duration = duration,
                        playing = playing,
                        showTranslation = showTranslation,
                        maxSp = style.lyricSp,
                        maxTranslationSp = style.translationSp,
                        blur = blurLyrics,
                    )
                }
            }
        }
        // 大字号设置下进度条下面的时间会比 48dp 高，按内容撑开
        Box(Modifier.fillMaxWidth().heightIn(min = ControlsHeight), contentAlignment = Alignment.Center) {
            NowControls(
                playing = playing,
                settled = settled,
                position = position,
                duration = duration,
                reveal = reveal,
                focusRequester = focusRequester,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeek = onSeek,
                onOpenPlayer = onOpenPlayer,
            )
        }
    }
}

/** 没有歌词：歌词区换成放大的歌名，下面一行“歌手 · 暂无歌词”，贴着控件行。 */
@Composable
private fun PlainTitle(song: Song, style: GrownPosterStyle) {
    Column(
        Modifier.fillMaxSize().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
    ) {
        Text(
            song.title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.displaySmall.copy(
                fontSize = style.plainTitleSp.sp,
                lineHeight = (style.plainTitleSp * 0.98f).sp,
                letterSpacing = (-0.03).em,
                fontWeight = FontWeight.ExtraBold,
                lineBreak = LineBreak.Heading,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(song.artist, "暂无歌词").filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = (13f * 1.3f).sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 控件行：花瓣播放键（播放时慢转）、展开的上一首 / 下一首、波浪进度条和“打开播放页”。
 * 展开状态只在这里读；弹簧落定前花瓣不转、波浪拉平。读屏在播放键上另给“上一首 / 下一首”两个操作，收起时也能用。
 */
@Composable
private fun NowControls(
    playing: Boolean,
    settled: Boolean,
    position: State<Long>,
    duration: Long,
    reveal: ControlsReveal,
    focusRequester: FocusRequester?,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val hovered by reveal.hover.collectIsHoveredAsState()
    val expanded = hovered || reveal.cardFocus.value || reveal.keyReveal.value || reveal.focused
    val motion = MaterialTheme.motionScheme
    // 和封面旋转一样把系统“移除动画”也当成减少动画：旋转是自己跑的帧循环
    val reduceMotion = LocalAppearance.current.reduceMotion || rememberSystemAnimationsOff()
    val tv = LocalAppWindow.current.isTv
    val angle = remember { mutableFloatStateOf(0f) }
    val spin = settled && ambientSpinEnabled(playing, reduceMotion)
    LaunchedEffect(spin, tv) {
        // 暂停时停在当前角度：12 瓣花瓣转到哪儿都是正的
        if (!spin) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                // 电视上和封面旋转、逐字填色共用 30 帧节拍，按真实间隔累加，转速不变
                if (!tv || TvFrameGate.due(now)) {
                    angle.floatValue = advanceCookieSpin(angle.floatValue, now - last)
                    last = now
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CookiePlayButton(
            playing = playing,
            onClick = onPlayPause,
            iconSize = 24.dp,
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("上一首") { onPrevious(); true },
                        CustomAccessibilityAction("下一首") { onNext(); true },
                    )
                },
            focusRequester = focusRequester,
            rotation = { angle.floatValue },
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandHorizontally(motion.defaultSpatialSpec(), expandFrom = Alignment.Start) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkHorizontally(motion.defaultSpatialSpec(), shrinkTowards = Alignment.Start) + fadeOut(motion.fastEffectsSpec()),
        ) {
            // 间距放在展开的内容里，收起时不留一道空缝
            Row(Modifier.padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CardIconButton(Icons.Filled.SkipPrevious, "上一首", onPrevious)
                CardIconButton(Icons.Filled.SkipNext, "下一首", onNext)
            }
        }
        WavySeekBar(
            position = position,
            duration = duration,
            // 落定前波浪拉平：放大途中只有封面和标题在动
            playing = playing && settled,
            onSeek = onSeek,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .tvFocusRing(SeekFocusShape, scale = 1f),
        )
        CardIconButton(Icons.AutoMirrored.Filled.OpenInNew, "打开播放页", onOpenPlayer)
    }
}

/** 别的歌：左上角“第 07 首 · 来源”，底部大标题和歌手，最下面一排“播放 / 下一首播放 / 更多”。 */
@Composable
private fun OtherCard(
    song: Song,
    number: Int,
    sourceName: String,
    style: GrownPosterStyle,
    focusRequester: FocusRequester?,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onMore: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val label = listOf("第 ${posterNumber(number)} 首", sourceName).filter { it.isNotBlank() }.joinToString(" · ")
    Column(Modifier.fillMaxSize().padding(style.padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PosterChip(
            text = label,
            spoken = if (sourceName.isBlank()) "第 $number 首" else "第 $number 首，来自$sourceName",
            container = scheme.surface.copy(alpha = 0.62f),
            content = scheme.onSurface,
        )
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
        ) {
            Text(
                song.title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = style.bigTitleSp.sp,
                    lineHeight = (style.bigTitleSp * 0.95f).sp,
                    letterSpacing = (-0.035).em,
                    fontWeight = FontWeight.ExtraBold,
                    lineBreak = LineBreak.Heading,
                ),
                color = scheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (song.artist.isNotBlank()) {
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = (15f * 1.3f).sp, fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = ControlsHeight),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val labelStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            // M3E 按钮：按下时圆角收小（快速空间弹簧）
            Button(
                onClick = onPlay,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .height(44.dp)
                    .tvFocusRing(CircleShape)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                contentPadding = PaddingValues(start = 14.dp, end = 18.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("播放", style = labelStyle, maxLines = 1)
            }
            FilledTonalButton(
                onClick = onPlayNext,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.height(44.dp).tvFocusRing(CircleShape),
                contentPadding = PaddingValues(horizontal = 18.dp),
            ) {
                Text("下一首播放", style = labelStyle, maxLines = 1)
            }
            CardIconButton(Icons.Filled.MoreVert, "更多", onMore)
        }
    }
}

/** 卡上的小标签：24dp 高（大字号时跟着字长高）、8dp 圆角，等宽数字。读屏读 [spoken]（“正在播放，第 3 首”），不读“· 03”。 */
@Composable
private fun PosterChip(
    text: String,
    spoken: String,
    container: Color,
    content: Color,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .heightIn(min = 24.dp)
            .background(container, ChipShape)
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        leading?.invoke()
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.04.em,
                fontFeatureSettings = "tnum",
            ),
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * “正在播放”标签里三根跳动的条。[animate] 为 false（暂停、放大途中）时停在当前高度；减少动画时一直是静止的错落高度。
 * 时间只在绘制里读，帧循环逐帧只重画这一小块。
 */
@Composable
private fun EqBars(animate: Boolean, color: Color) {
    val reduceMotion = LocalAppearance.current.reduceMotion || rememberSystemAnimationsOff()
    val tv = LocalAppWindow.current.isTv
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate, reduceMotion, tv) {
        if (!animate || reduceMotion) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                if (!tv || TvFrameGate.due(now)) {
                    clock.floatValue = (clock.floatValue + (now - last) / 1_000_000_000f) % (EqHalfPeriodS * 2)
                    last = now
                }
            }
        }
    }
    Spacer(
        Modifier
            .size(10.dp, EqMaxDp.dp)
            .drawBehind {
                val t = clock.floatValue
                val bar = 2.dp.toPx()
                val step = 4.dp.toPx()
                val radius = CornerRadius(1.dp.toPx())
                for (i in EqLeads.indices) {
                    val h = eqBarHeight(t, EqLeads[i]).dp.toPx()
                    drawRoundRect(color, topLeft = Offset(i * step, size.height - h), size = Size(bar, h), cornerRadius = radius)
                }
            },
    )
}

/** 卡上的 40dp 圆形图标钮：onSurface 12% 底；电视上套焦点环。 */
@Composable
private fun CardIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp).tvFocusRing(CircleShape),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(icon, description, Modifier.size(20.dp))
    }
}
