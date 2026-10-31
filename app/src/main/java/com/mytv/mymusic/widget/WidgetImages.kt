package com.mytv.mymusic.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.material3.MaterialShapes
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.graphics.shapes.RoundedPolygon
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.ui.player.PausedCoverPolygon
import com.mytv.mymusic.ui.player.coverPolygon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 组件里封面的外形。播放中用当前这首抽到的形状，暂停用圆角方形，榜单第一名用拱形，列表行是圆角方块。 */
sealed interface WidgetShape {
    data class Cover(val choice: CoverShapeChoice) : WidgetShape
    data object Paused : WidgetShape
    data object Arch : WidgetShape
    data object Circle : WidgetShape
    data object Cookie9 : WidgetShape

    /** 快捷入口 2×2 的四瓣饼干底板（瓣本来就在四角，不用转）。 */
    data object Cookie4 : WidgetShape

    /** 圆角方块，[radiusDp] 是圆角。 */
    data class Rounded(val radiusDp: Float) : WidgetShape
}

/**
 * 组件用的位图：封面按组件里的尺寸取图（最大 480px）并裁好形状再交给桌面，形状蒙版和进度波浪也在这里画。
 * 封面是异步的：[cover] 先返回缓存里的，没有就在后台取，取完给等这张图的那几款组件的 [version] 加一，组件重组后再拿。
 * 蒙版和波浪只用来着色，存成 ALPHA_8，只有 ARGB 的四分之一大。
 */
object WidgetImages {
    const val MAX_COVER_PX = 480

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 每款组件一个计数：榜单一行行的封面取回来，只重组榜单，不连带正在播放
    private val loaded = WidgetKind.entries.associateWith { MutableStateFlow(0) }

    // 组件读的是限过速的计数：第一张图立刻显示，之后连续取回的图最多每 [IMAGE_BATCH_MS] 重组一次，
    // 免得三十行的榜单一张图推一次整个列表
    private val versions = loaded.mapValues { (_, flow) ->
        // StateFlow 本身只留最新值：等待期间来的几次合成一次
        flow.transform { emit(it); delay(IMAGE_BATCH_MS) }.stateIn(scope, SharingStarted.Eagerly, 0)
    }

    /** [kind] 等的封面有新取回来的。 */
    fun version(kind: WidgetKind): StateFlow<Int> = versions.getValue(kind)

    // 正在取的图（缓存键）→ 等它的组件
    private val waiting = ConcurrentHashMap<String, MutableSet<WidgetKind>>()

    // 按字节算，约 12MB：桌面上几个组件的封面和列表缩略图都放得下
    private val covers = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val masks = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val loading: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // 原图带透明的封面（缓存键）：显示时垫一块底色
    private val translucent: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // 取图失败的地址一分钟内不再试，免得没网时每次重组都发请求
    private val failed = ConcurrentHashMap<String, Long>()

    /** 裁好形状的封面；还没取到返回 null 并在后台开始取，取回后通知 [kind]。[url] 为空直接返回 null（显示占位）。 */
    fun cover(context: Context, url: String, sizePx: Int, shape: WidgetShape, kind: WidgetKind? = null): Bitmap? {
        if (url.isBlank()) return null
        val px = sizePx.coerceIn(1, MAX_COVER_PX)
        val key = "$url|$px|${shape.key}"
        covers.get(key)?.let { return it }
        val failedAt = failed[key]
        if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < RETRY_MS) return null
        if (kind != null) waiting.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(kind)
        if (loading.add(key)) {
            val app = context.applicationContext
            scope.launch {
                var done = false
                try {
                    val source = load(app, url, px)
                    val bitmap = source?.let { clip(it, px, shape, app.resources.displayMetrics.density) }
                    if (source != null && bitmap != null) {
                        if (hasTransparency(source)) translucent.add(key) else translucent.remove(key)
                        covers.put(key, bitmap)
                        failed.remove(key)
                        done = true
                    } else {
                        failed[key] = SystemClock.elapsedRealtime()
                    }
                } finally {
                    loading.remove(key)
                    // 取失败的继续显示占位，不用重组
                    val kinds = waiting.remove(key).orEmpty()
                    if (done) kinds.forEach { loaded.getValue(it).value++ }
                }
            }
        }
        return null
    }

    /** [cover] 取回的这张原图带透明（白色图标这类），要垫底色才看得清。 */
    fun isTranslucent(url: String, sizePx: Int, shape: WidgetShape): Boolean =
        "$url|${sizePx.coerceIn(1, MAX_COVER_PX)}|${shape.key}" in translucent

    /** 已经在缓存里的封面（不触发加载）。预览和“封面加载中”判断用。 */
    fun cached(url: String, sizePx: Int, shape: WidgetShape): Bitmap? =
        if (url.isBlank()) null else covers.get("$url|${sizePx.coerceIn(1, MAX_COVER_PX)}|${shape.key}")

    /** 形状蒙版（只有透明度），用时按配色着色：封面占位、识曲按钮、四瓣底板。 */
    fun mask(sizePx: Int, shape: WidgetShape, density: Float): Bitmap {
        val px = sizePx.coerceIn(1, MAX_COVER_PX)
        val key = "$px|${shape.key}"
        masks.get(key)?.let { return it }
        val bitmap = createBitmap(px, px, Bitmap.Config.ALPHA_8)
        Canvas(bitmap).drawPath(path(shape, px.toFloat(), density), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        masks.put(key, bitmap)
        return bitmap
    }

    /**
     * M3E 波浪进度条的两层蒙版（只有透明度）：[WaveBitmaps.played] 是已播段（播放时是波浪、暂停时拉直）加末端的点，按 primary 着色；
     * [WaveBitmaps.rest] 是未播段直线，按 secondaryContainer 着色。几何和原型一致：高 12dp、振幅 2.4dp、波长 15dp、线宽 3.5dp。
     */
    fun wave(widthDp: Float, fraction: Float, playing: Boolean, density: Float): WaveBitmaps {
        // 宽度按 1/200 取整做缓存键：10 秒一推，一首歌几十个键，LRU 会自己淘汰
        val step = (fraction.coerceIn(0f, 1f) * WAVE_STEPS).roundToInt()
        val key = "wave|${widthDp.roundToInt()}|$step|$playing|$density"
        masks.get("$key|p")?.let { played -> masks.get("$key|r")?.let { rest -> return WaveBitmaps(played, rest) } }
        val w = max(1, (widthDp * density).roundToInt())
        val h = max(1, (WAVE_HEIGHT_DP * density).roundToInt())
        val frac = step / WAVE_STEPS.toFloat()
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * density
            strokeCap = Paint.Cap.ROUND
            color = 0xFFFFFFFF.toInt()
        }
        val mid = WAVE_HEIGHT_DP / 2f
        val x1 = max(0.5f, frac * (widthDp - 10f))
        val played = createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(played).apply {
            val path = Path()
            if (playing) {
                fun y(x: Float) = mid + 2.4f * sin(x / 15f * 2f * PI.toFloat())
                path.moveTo(0f, y(0f) * density)
                var x = 1.5f
                while (x < x1) {
                    path.lineTo(x * density, y(x) * density)
                    x += 1.5f
                }
                path.lineTo(x1 * density, y(x1) * density)
            } else {
                path.moveTo(0f, mid * density)
                path.lineTo(x1 * density, mid * density)
            }
            drawPath(path, stroke)
            drawCircle((widthDp - 2f) * density, mid * density, 2f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        }
        val rest = createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val gs = x1 + 6f
        if (gs < widthDp - 7f) Canvas(rest).drawLine(gs * density, mid * density, (widthDp - 7f) * density, mid * density, stroke)
        masks.put("$key|p", played)
        masks.put("$key|r", rest)
        return WaveBitmaps(played, rest)
    }

    private suspend fun load(context: Context, url: String, px: Int): Bitmap? {
        // 单独的缓存键：和 App 里的大封面、取色小图互不顶掉
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(px)
            .allowHardware(false)
            .memoryCacheKey("$url#widget$px")
            .build()
        val result = runCatching { SingletonImageLoader.get(context).execute(request) }.getOrNull() as? SuccessResult ?: return null
        return runCatching { result.image.toBitmap() }.getOrNull()
    }

    /** 隔几个像素抽查透明度：有一成以上的点基本透明就算透明底的图。照片封面一般没有 alpha 通道，直接跳过。 */
    private fun hasTransparency(source: Bitmap): Boolean {
        if (!source.hasAlpha()) return false
        val step = maxOf(1, minOf(source.width, source.height) / 24)
        var total = 0
        var clear = 0
        for (y in 0 until source.height step step) {
            for (x in 0 until source.width step step) {
                total++
                if (Color.alpha(source[x, y]) < 32) clear++
            }
        }
        return clear * 10 >= total
    }

    /** 居中裁成正方形，再按形状裁掉外面（抗锯齿，外面透明）。 */
    private fun clip(source: Bitmap, px: Int, shape: WidgetShape, density: Float): Bitmap {
        val out = createBitmap(px, px)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawPath(path(shape, px.toFloat(), density), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val side = min(source.width, source.height)
        val src = Rect((source.width - side) / 2, (source.height - side) / 2, (source.width + side) / 2, (source.height + side) / 2)
        canvas.drawBitmap(source, src, RectF(0f, 0f, px.toFloat(), px.toFloat()), paint)
        return out
    }

    private fun path(shape: WidgetShape, size: Float, density: Float): Path = when (shape) {
        is WidgetShape.Rounded -> Path().apply {
            val r = min(shape.radiusDp * density, size / 2f)
            addRoundRect(RectF(0f, 0f, size, size), r, r, Path.Direction.CW)
        }
        WidgetShape.Circle -> Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
        else -> polygonPath(polygon(shape), size)
    }

    private fun polygon(shape: WidgetShape): RoundedPolygon = when (shape) {
        is WidgetShape.Cover -> coverPolygon(shape.choice)
        WidgetShape.Paused -> PausedCoverPolygon
        WidgetShape.Arch -> MaterialShapes.Arch
        WidgetShape.Cookie9 -> MaterialShapes.Cookie9Sided
        // MaterialShapes 的四瓣饼干本来就是四角凸出，正好放快捷入口四角的按钮，不用再转
        WidgetShape.Cookie4 -> MaterialShapes.Cookie4Sided
        WidgetShape.Circle, is WidgetShape.Rounded -> MaterialShapes.Circle
    }

    /** MaterialShapes 是单位方框里的三次曲线，放大到 [size] 写成 Path。 */
    private fun polygonPath(polygon: RoundedPolygon, size: Float): Path {
        val path = Path()
        var first = true
        for (c in polygon.cubics) {
            if (first) {
                path.moveTo(c.anchor0X * size, c.anchor0Y * size)
                first = false
            }
            path.cubicTo(c.control0X * size, c.control0Y * size, c.control1X * size, c.control1Y * size, c.anchor1X * size, c.anchor1Y * size)
        }
        path.close()
        return path
    }

    private val WidgetShape.key: String
        get() = when (this) {
            is WidgetShape.Cover -> "cover-${choice.name}"
            WidgetShape.Paused -> "paused"
            WidgetShape.Arch -> "arch"
            WidgetShape.Circle -> "circle"
            WidgetShape.Cookie9 -> "cookie9"
            WidgetShape.Cookie4 -> "cookie4"
            is WidgetShape.Rounded -> "r$radiusDp"
        }

    private const val RETRY_MS = 60_000L
    private const val IMAGE_BATCH_MS = 1_500L
    private const val WAVE_STEPS = 200
    const val WAVE_HEIGHT_DP = 12f
}

data class WaveBitmaps(val played: Bitmap, val rest: Bitmap)
