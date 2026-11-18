package com.mytv.mymusic.ui.poster

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import com.mytv.mymusic.data.PosterZoom
import com.mytv.mymusic.ui.adaptive.FoldAnchor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 墙这一帧的输入，PosterWall 每次组合后交给 [PosterWallState.configure]。
 * [insetTop] / [insetBottom] 是浮层让出的高度（dp），大卡居中在两者之间。
 */
internal data class WallConfig(
    val metrics: WallMetrics,
    val field: WallField,
    val current: Int,
    val follow: Boolean,
    val isTv: Boolean,
    val drift: Boolean,
    val reduceMotion: Boolean,
    val appending: Boolean,
    val insetTop: Float = 0f,
    val insetBottom: Float = 0f,
    /** 播放页盖在墙上时为 false：帧循环停着，镜头的移动（切歌跟随）直接到位，揭开时已经停在新歌上。 */
    val active: Boolean = true,
    /** 换了一份队列（首数一样也算）时变：整面墙重排、重新入场。 */
    val resetKey: Any? = null,
    /** 正在播放那首的 key：序号变了 key 没变是队列重排（“下一首播放”把前面的歌挪到它后面），不算切歌，镜头不跟。 */
    val currentKey: String? = null,
    /** 墙上开着面板（设置、歌曲操作、换源）：按键进了面板的窗口，墙收不到，空闲计时一直归零，浮层不淡出、不起漂。 */
    val holdAwake: Boolean = false,
    /** 有分页的折痕时大卡那一段（墙本地 dp，见 [wallSpecFor]）；null 是普通窗口。 */
    val segment: WallSegment? = null,
)

/**
 * 墙上用的配色。切歌时整个 App 的配色走 450ms 过渡，每帧都是一份新的 ColorScheme；
 * 只有一个叶子组合读 MaterialTheme 再写进来，格子在绘制阶段读这里，过渡期间只重绘、不重组。
 */
@Stable
internal class WallPalette {
    var scheme: ColorScheme by mutableStateOf(darkColorScheme())
}

/** 换了一份 [WallField] 时墙怎么处理：不变、听歌模式续上一批（已有格子不动）、整面墙重排重新入场。 */
internal enum class FieldChange { Same, Append, Reset }

internal fun fieldChange(old: WallField, new: WallField): FieldChange = when {
    old == new -> FieldChange.Same
    old is WallField.FeedBand && new is WallField.FeedBand &&
        old.rows == new.rows && old.ghosts == new.ghosts && new.songs > old.songs -> FieldChange.Append
    else -> FieldChange.Reset
}

/**
 * 按设备取格距。电视固定 [WallMetrics.Tv]；其余看窗口放不放得下平板的 606dp 大卡：左右各留 24dp，
 * 竖直方向还要让出状态栏、详情页顶上的胶囊和底下的工具栏、底部播放条和手势条（合计约 [TABLET_CHROME_HEIGHT]）。
 * 放不下但装得下 451dp 的大卡（横屏平板、600dp 宽的小平板）用 [WallMetrics.ShortTablet]，再小（手机、横屏手机）用手机格距。
 */
internal fun wallMetricsFor(isTv: Boolean, widthDp: Int, heightDp: Int): WallMetrics = when {
    isTv -> WallMetrics.Tv
    min(widthDp, heightDp) >= WallMetrics.Tablet.grownSize + 2 * TABLET_SIDE_MARGIN &&
        heightDp >= WallMetrics.Tablet.grownSize + TABLET_CHROME_HEIGHT -> WallMetrics.Tablet
    min(widthDp, heightDp) >= WallMetrics.ShortTablet.grownSize + 2 * TABLET_SIDE_MARGIN &&
        heightDp >= WallMetrics.ShortTablet.grownSize + TABLET_CHROME_HEIGHT -> WallMetrics.ShortTablet
    else -> WallMetrics.Phone
}

private const val TABLET_SIDE_MARGIN = 24f

/** 平板详情页的海报视图竖直方向要让出的高度：状态栏 ~32 + 胶囊一排 64 + 工具栏一排 92 + 底部播放条 ~80 + 手势条 ~48。 */
private const val TABLET_CHROME_HEIGHT = 320f

/**
 * 这张歌单没在放时的镜头：不放大任何格，对准第一块，第 1 位（总在块的左上角）在左上附近。
 * 手机取原型：第 1 位贴到顶栏下方 4dp、左边 12dp；平板和电视水平居中这一块，块比可用区矮就竖直居中，否则顶到浮层下沿。
 * 块比屏幕宽（电视详情页左边让出导航栏后只剩 870dp 上下）时不居中，和手机一样左边留 12dp，第 1 位不被切掉。
 */
internal fun initialCamera(metrics: WallMetrics, viewport: WallViewport): WallPoint {
    if (metrics.kind == WallKind.Phone) return WallPoint(-12f, -viewport.insetTop - 4f)
    val x = min(metrics.blockWidth / 2 - viewport.width / 2, -12f)
    val spare = viewport.usableHeight - metrics.blockHeight
    val top = viewport.insetTop + max(0f, spare / 2)
    return WallPoint(x, -top)
}

/** 镜头所在的格距网格坐标；只有跨过一条格线，要组合的格子才可能变。 */
internal fun cullCell(camAxis: Float, pitch: Float): Int = floor(camAxis / pitch).toInt()

/**
 * 图层以屏幕点 [origin] 为中心缩小到 1 / [boost] 时要组合的格子：视口按 [boost] 倍、以 [origin] 为中心放大后再按上面同样剔除。
 */
internal fun WallScene.cullTiles(cx: Int, cy: Int, viewport: WallViewport, boost: Float, origin: WallPoint): List<WallTile> {
    val p = metrics.pitch
    val left = cx * p + origin.x - origin.x * boost
    val top = cy * p + origin.y - origin.y * boost
    return visibleTiles(WallPoint(left, top), WallViewport(viewport.width * boost + p, viewport.height * boost + p))
}

/** 大卡显示尺寸按视口的大卡那一段重算（转屏、浮层高度、折痕分段、视角都会改它）。 */
internal fun WallScene.sizedFor(viewport: WallViewport): WallScene = withGrownDisplay(grownDisplaySize(metrics, viewport.cardArea))

/**
 * 网格坐标 ([cx], [cy]) 时要组合的格子。镜头在 [cx]·p ..< ([cx] + 1)·p 之间走，所以按格点起算、视口放宽一个格距，
 * 再由 [WallScene.visibleTiles] 四周各外扩两个格距：任何时刻实际屏幕外都至少留着两个格距的格子。
 */
internal fun WallScene.cullTiles(cx: Int, cy: Int, viewport: WallViewport): List<WallTile> {
    val p = metrics.pitch
    return visibleTiles(WallPoint(cx * p, cy * p), WallViewport(viewport.width + p, viewport.height + p))
}

/**
 * 镜头不动时，帧循环最多睡多久就得醒来推进空闲计时（秒）：电视浮层 6 秒淡出、开着漂移时 30 秒起漂。
 * null 表示没有定时的事，等下一次操作。
 */
internal fun idleWaitSeconds(
    isTv: Boolean,
    chromeIdle: Boolean,
    idleSeconds: Double,
    driftEnabled: Boolean,
    drifting: Boolean,
): Double? {
    var wait: Double? = null
    if (isTv && !chromeIdle) wait = WallCamera.IDLE_CHROME_AFTER - idleSeconds
    if (driftEnabled && !drifting) {
        val d = WallCamera.DRIFT_AFTER - idleSeconds
        wait = if (wait == null) d else min(wait, d)
    }
    return wait?.let { max(0.0, it) }
}

/**
 * 海报墙的状态：镜头、放大的格、方向键焦点、空闲 / 漂移、入场波和占位格微光。
 *
 * 屏幕层要用的：
 * - [grownIndex] 放大的是第几首（-1 没有）；[currentGrown] 正在播放的那首就是放大的那张——为 false 且 [hasCurrent] 时“定位”按钮高亮，
 *   [hasCurrent] 为 false（详情页这张歌单没在放）时“定位”该置灰；
 * - [locate] “定位”：镜头弹到离屏幕中心最近的那一份并放大；[growSong] 放大某一首（“下一首播放”之后跟着它）；
 * - [takeFocus] 电视进页面时调：焦点交给墙并选中离可用区中心最近的那格（顶排没有能拿焦点的按钮，焦点只能在墙上）；
 * - [handleBack] 墙这一层的返回（见 [wallBackStep]），返回 false 时页面离开；面板由页面自己先关；
 * - [chromeVisible] 电视空闲 6 秒或漂移中为 false，浮层跟着淡出；[wake] 有操作时调，返回 true 表示刚才在漂移、这一下只用来唤醒；
 *   页面根节点挂 [posterWallWake] 就不用自己调；
 * - [grownFocused] 墙的方向键焦点停在大卡上（给 GrownPoster 的 focused），[grownCoverKey] 大卡底下那格小封面的内存缓存键；
 * - [focusRequester] 墙节点的 Compose 焦点；电视上 PosterWall 默认自己请求一次。
 *
 * 镜头位置是快照状态但只在布局的摆放阶段读，镜头动的时候格子不重组；要组合哪些格只在镜头跨过格线时变（[composedTiles]）。
 */
@Stable
internal class PosterWallState internal constructor(
    random: Random = Random.Default,
    private val clock: () -> Long = System::nanoTime,
) {
    internal val camera = WallCamera(random = random)
    internal val palette = WallPalette()
    val focusRequester = FocusRequester()

    /**
     * 墙在窗口里的位置，折痕按它切（[rememberWallSpec]）。页面挂在根节点上、预测性返回的缩放之外：
     * 和墙同一个矩形，返回手势缩小整页时折痕位置不跟着抖。
     */
    val foldAnchor = FoldAnchor()

    /** 镜头左上角（dp），摆放阶段读。 */
    internal var camX by mutableFloatStateOf(0f)
        private set
    internal var camY by mutableFloatStateOf(0f)
        private set
    private var cellX by mutableIntStateOf(0)
    private var cellY by mutableIntStateOf(0)

    internal var scene by mutableStateOf<WallScene?>(null)
        private set
    internal var viewport by mutableStateOf<WallViewport?>(null)
        private set

    /** 换队列重排时加一，格子的 key 带上它，整面墙重新组合、重新入场，不从旧位置弹过去。 */
    internal var generation by mutableIntStateOf(0)
        private set

    /** 方向键焦点：墙自己记，不进 Compose 焦点系统。 */
    internal var focus by mutableStateOf<SlotPos?>(null)
        private set
    private var current by mutableIntStateOf(-1)
    private var currentKey: String? = null

    var chromeVisible by mutableStateOf(true)
        private set
    var isDrifting by mutableStateOf(false)
        private set

    internal var wave by mutableStateOf<WallWave?>(null)
        private set
    internal var waveTime by mutableFloatStateOf(0f)
        private set
    internal var shimmerPhase by mutableFloatStateOf(0f)
        private set
    internal var shimmerAlpha by mutableFloatStateOf(0f)
        private set

    /** Compose 焦点在墙节点本身 / 在墙里的子节点（大卡的按钮）上。 */
    internal var wallFocused by mutableStateOf(false)
        private set
    internal var focusInside by mutableStateOf(false)
        private set

    /** 组合中的格子的动画状态，焦点环和大卡描边按它的实时位置画。 */
    internal val tileAnims = mutableStateMapOf<SlotPos, TileAnim>()

    /**
     * 换视角倍数时整面墙的过渡缩放：[rescale] 后格子已经按新格距摆好，先用图层缩回旧的大小（[layerScale] = 旧 / 新），
     * 再由 PosterWall 动画到 1；缩放中心 [layerOrigin] 是屏幕点（dp）。只在图层和绘制阶段读，不重组。
     */
    internal var layerScale by mutableFloatStateOf(1f)
    internal var layerOrigin by mutableStateOf(WallPoint(0f, 0f))
        private set

    /** 每次需要把 [layerScale] 动画回 1 时加一，PosterWall 收集它。 */
    internal var settleRequests by mutableIntStateOf(0)
        private set

    /**
     * 图层缩小时（拉近的过渡、捏合拉远）屏幕上露出的范围比视口大：要组合的格子按 [cullBoost] 倍的范围算，
     * 以 [cullOrigin] 为中心向外放；过渡结束回到 1。
     */
    private var cullBoost by mutableFloatStateOf(1f)
    private var cullOrigin = WallPoint(0f, 0f)

    /** 换倍数时加一：格子的位置、圆角这一次直接跳到新值（镜头也是跳的），不从旧格距弹过去。 */
    internal var scaleEpoch = 0
        private set

    /**
     * 捏合松手吸附后、设置写回之前用这个倍数（PosterWall 读它，等 prefs 回来一样了再清掉），
     * 免得松手那一帧墙先跳回旧倍数。
     */
    internal var zoomOverride by mutableStateOf<Float?>(null)

    /** 设 [zoomOverride] 那一刻设置里的倍数：设置不再是它（写回来了或被别处改了）时 PosterWall 就不再用临时倍数。 */
    internal var zoomOverrideBase = 1f
        private set

    /** 捏合吸附出新倍数时调：PosterWall 用 SideEffect 设成写设置。 */
    internal var onZoomCommit: (Float) -> Unit = {}

    /** 正在捏合（两指按着）。 */
    internal var pinching = false
        private set
    private var pinchBase = 1f
    private var pinchLive = 1f

    /** 下一次 [rescale] 的锚点（屏幕点）：捏合松手时是两指中点。 */
    private var pendingAnchor: WallPoint? = null

    /** 长按（触摸、读屏、长按 OK）和菜单键，参数是第几首、是不是菜单键（菜单键打开的面板焦点直接落在整页操作上）。 */
    internal var onLongPress: (index: Int, fromMenu: Boolean) -> Unit = { _, _ -> }
    internal var onGrownConfirm: (index: Int, current: Boolean) -> Unit = { _, _ -> }

    private var config: WallConfig? = null
    private var initialized = false
    private var dragFrom: WallPoint? = null
    private var lastTick = 0L
    private val confirm = ConfirmPress()
    private var swallow: WallKey? = null
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val frame: (Long) -> Unit = { now ->
        val dt = (now - lastTick).coerceAtLeast(0L) / 1e9f
        lastTick = max(lastTick, now)
        tick(dt)
    }

    val grownIndex: Int
        get() {
            val s = scene ?: return -1
            val g = s.grown ?: return -1
            return s.songAt(g.bx, g.by, g.slot)
        }

    val hasCurrent: Boolean get() = current >= 0

    val currentGrown: Boolean get() = current >= 0 && grownIndex == current

    /** 方向键焦点所在格坐的第几首；-1 没有焦点。占位格也返回它的序号。 */
    val focusedIndex: Int
        get() {
            val s = scene ?: return -1
            val f = focus ?: return -1
            return s.songAt(f.bx, f.by, f.slot)
        }

    /** 焦点环此刻该亮在哪格：墙节点有 Compose 焦点、浮层没淡出时才亮。 */
    internal val litFocus: SlotPos? get() = focus.takeIf { chromeVisible && wallFocused }

    val grownFocused: Boolean
        get() {
            val f = litFocus ?: return false
            return f == scene?.grown
        }

    /** 大卡底下那格小封面在 Coil 内存缓存里的键，GrownPoster 加载大图前拿它垫底。 */
    val grownCoverKey: String? get() = scene?.grown?.let { tileAnims[it]?.coverKey }

    internal val metrics: WallMetrics get() = scene?.metrics ?: config?.metrics ?: WallMetrics.Phone

    /** 大卡这一次显示的边长（dp），见 grownDisplaySize。 */
    internal val grownDisplay: Float get() = scene?.grownDisplay ?: metrics.grownSize

    /** 要组合的格子；只在放大格、视口变化或镜头跨过格线时重算，结果相同不通知。 */
    internal val composedTiles: List<WallTile> by derivedStateOf(structuralEqualityPolicy()) {
        val s = scene
        val v = viewport
        val boost = cullBoost
        when {
            s == null || v == null -> emptyList()
            boost > 1f -> s.cullTiles(cellX, cellY, v, boost, cullOrigin)
            else -> s.cullTiles(cellX, cellY, v)
        }
    }

    internal fun isGhost(song: Int): Boolean = scene?.field?.isGhost(song) == true

    // ------------------------------------------------------------------ 输入：配置、视口

    /** 每次组合后调：切歌跟随、换队列重排、续一批、漂移开关、浮层高度都从这里进来。 */
    internal fun configure(c: WallConfig) {
        val prev = config
        config = c
        if (camera.driftEnabled != c.drift) {
            camera.driftEnabled = c.drift
            lastTick = clock()
        }
        // 面板开、关都算一次操作：关上之后空闲计时从这时算起，不会一关就淡出
        if (prev != null && prev.holdAwake != c.holdAwake) {
            camera.wake()
            lastTick = clock()
        }
        val vp = viewport
        if (vp != null && (vp.insetTop != c.insetTop || vp.insetBottom != c.insetBottom || vp.segment != c.segment)) changeInsets(vp, c)
        val s = scene
        if (!initialized || s == null || prev == null) {
            tryInit()
        } else if (prev.metrics.kind != c.metrics.kind || prev.resetKey != c.resetKey) {
            // 换设备档（转屏、折叠）或换队列：整面墙重来
            reset()
        } else {
            // 只换了视角倍数：不重排、不重放入场，镜头按倍数换算
            if (s.metrics.zoom != c.metrics.zoom) rescale(s.metrics, c.metrics, pendingAnchor.also { pendingAnchor = null })
            when (fieldChange(s.field, c.field)) {
                FieldChange.Reset -> reset()
                FieldChange.Append -> {
                    val from = (s.field as WallField.FeedBand).songs
                    val ns = (scene ?: s).withField(c.field)
                    scene = ns
                    viewport?.let { camera.bounds = ns.bounds(it) }
                    startWave(WallWave.Batch(from, (c.field as WallField.FeedBand).songs, waveStyle(c)))
                    applyCurrent(c)
                }
                FieldChange.Same -> applyCurrent(c)
            }
        }
        publish()
        signal()
    }

    /** 视口尺寸（dp），布局后由 onSizeChanged 交进来。第一次拿到尺寸时才初始化。 */
    internal fun setViewportSize(width: Float, height: Float) {
        val c = config
        val old = viewport
        val vp = WallViewport(
            width,
            height,
            c?.insetTop ?: old?.insetTop ?: 0f,
            c?.insetBottom ?: old?.insetBottom ?: 0f,
            if (c != null) c.segment else old?.segment,
        )
        if (vp == old) return
        viewport = vp
        val s0 = scene
        if (!initialized || s0 == null) {
            tryInit()
        } else {
            val s = s0.sizedFor(vp)
            scene = s
            camera.bounds = s.bounds(vp)
            // 转屏、分屏改了尺寸：大卡留在中间
            val g = s.grown
            if (g != null && (old == null || old.width != width || old.height != height)) camera.snapTo(s.centerTarget(g, vp))
        }
        publish()
        signal()
    }

    /**
     * 浮层量出来的高度晚于第一帧到、或者浮层换了高度、或者折痕的分段变了（墙的位置第一次摆放后才知道、折起展开）：
     * 大卡要是还停在原来的正中，就跟着挪到新的正中；用户已经把墙拖走了就不动。
     */
    private fun changeInsets(old: WallViewport, c: WallConfig) {
        val vp = old.copy(insetTop = c.insetTop, insetBottom = c.insetBottom, segment = c.segment)
        viewport = vp
        val s0 = scene ?: return
        if (!initialized) return
        val s = s0.sizedFor(vp)
        scene = s
        camera.bounds = s.bounds(vp)
        val g = s.grown ?: return
        if (camera.restingPoint == s0.centerTarget(g, old)) {
            val target = s.centerTarget(g, vp)
            if (c.reduceMotion || !camera.isAnimating) camera.snapTo(target) else camera.animateTo(target)
        }
    }

    private fun applyCurrent(c: WallConfig) {
        if (c.current == current) {
            currentKey = c.currentKey
            return
        }
        // 同一首歌换了序号：队列重排了，不是切歌
        val reordered = c.currentKey != null && c.currentKey == currentKey
        current = c.current
        currentKey = c.currentKey
        if (c.follow && !reordered) follow(c.current, moveFocus = true)
    }

    /** 第一次有了配置和视口：正在放的那首放大并直接对准（不动画）；没在放就对准第一块。然后起入场波。 */
    private fun tryInit() {
        val c = config ?: return
        val vp = viewport ?: return
        val base = WallScene(c.metrics, c.field).sizedFor(vp)
        current = c.current
        currentKey = c.currentKey
        focus = null
        camera.bounds = base.bounds(vp)
        camera.snapTo(WallPoint(0f, 0f))
        val home = base.followTarget(c.current, camera.position, vp)
        if (home != null) {
            val s = base.withGrown(home)
            scene = s
            camera.snapTo(s.centerTarget(home, vp))
        } else {
            scene = base
            camera.snapTo(initialCamera(c.metrics, vp))
        }
        initialized = true
        camera.wake()
        lastTick = clock()
        publish()
        startWave(WallWave.Open(camera.position, waveStyle(c)))
    }

    private fun reset() {
        initialized = false
        generation++
        tryInit()
    }

    /**
     * 只换视角倍数（设置里的滑块）：格子按新格距原地重排，不重放入场，[tileAnims] 按位置保留。
     * 镜头：大卡正停在正中就换完还在正中（稿子「正在播放的那张留在原位」）；否则以 [anchor]（屏幕点，默认屏幕中心）为锚点换算，
     * 锚点下还是同一处墙面。方向键焦点被拉远后的大卡盖住时挪到大卡上。
     */
    private fun rescale(old: WallMetrics, new: WallMetrics, anchor: WallPoint? = null) {
        val s = scene ?: return
        val vp = viewport ?: return
        val g = s.grown
        val resting = g != null && camera.restingPoint == s.centerTarget(g, vp)
        val cam = camera.restingPoint
        val ns = WallScene(new, s.field, g).sizedFor(vp)
        scene = ns
        camera.bounds = ns.bounds(vp)
        val target = when {
            anchor != null -> zoomCamera(cam, anchor, new.zoom / old.zoom)
            resting -> ns.centerTarget(g, vp)
            else -> zoomCamera(cam, WallPoint(vp.width / 2, vp.height / 2), new.zoom / old.zoom)
        }
        // 换倍数算一次操作：漂移停下，空闲计时从头算
        camera.wake()
        camera.snapTo(target)
        scaleEpoch++
        val f = focus
        if (f != null && g != null && f != g && ns.coveredByGrown(f, ns.rectAt(f))) focus = g
        // 视觉过渡：图层先缩回旧的大小，再动画到 1。中心取锚点；大卡停在正中时取大卡那一段的中心
        val origin = when {
            anchor != null -> anchor
            resting -> vp.cardArea.let { WallPoint(it.x + it.w / 2, it.y + it.h / 2) }
            else -> WallPoint(vp.width / 2, vp.height / 2)
        }
        startLayerSettle(layerScale * old.zoom / new.zoom, origin)
    }

    /** 从 [scale] 开始把整面墙的图层动画回 1（PosterWall 收集 [settleRequests]）。 */
    private fun startLayerSettle(scale: Float, origin: WallPoint) {
        layerOrigin = origin
        layerScale = scale
        // 捏合时为了拉远多组合的那一圈到这里按新格距重算：只留过渡要用的
        if (scale < 1f) cullOrigin = origin
        cullBoost = if (scale < 1f) 1f / scale else 1f
        settleRequests++
    }

    // ------------------------------------------------------------------ 双指捏合

    /** 两指按下：记下起始倍数；捏合中只改图层缩放，不重排。 */
    internal fun beginPinch(origin: WallPoint) {
        pinching = true
        pinchBase = metrics.zoom
        pinchLive = pinchBase
        layerOrigin = origin
        layerScale = 1f
        // 停掉还在走的过渡（PosterWall 收到请求时看到正在捏合，什么也不做）
        settleRequests++
        signal()
    }

    /**
     * 两指间距是开始时的 [ratio] 倍、中点在屏幕点 [origin]：墙的图层以中点缩放到 捏合倍数 / 起始倍数。
     * 拉远时屏幕上露出的范围比视口大，第一次缩小时把要组合的范围放大到最远（[PINCH_MIN]）那么大。
     * 返回此刻吸附会落到的刻度（跨过刻度时界面震一下）。
     */
    internal fun pinchTo(ratio: Float, origin: WallPoint): Float {
        if (!pinching) return PosterZoom.snap(metrics.zoom)
        pinchLive = pinchZoom(pinchBase, ratio)
        layerOrigin = origin
        layerScale = pinchLive / pinchBase
        if (layerScale < 1f && cullBoost < pinchBase / PINCH_MIN) {
            cullOrigin = origin
            cullBoost = pinchBase / PINCH_MIN
        }
        return PosterZoom.snap(pinchLive)
    }

    /**
     * 松手：吸附到最近的刻度。没换档就把图层动画回 1；换了档就记下锚点、先用 [zoomOverride] 生效、交给 [onZoomCommit] 写设置，
     * 下一次 [configure] 里 [rescale] 按两指中点换算镜头，图层从松手时的大小接着缩放到 1。
     */
    internal fun endPinch() {
        if (!pinching) return
        pinching = false
        val snapped = PosterZoom.snap(pinchLive)
        if (snapped == pinchBase) {
            startLayerSettle(layerScale, layerOrigin)
        } else {
            pendingAnchor = layerOrigin
            zoomOverrideBase = pinchBase
            zoomOverride = snapped
            onZoomCommit(snapped)
        }
        signal()
    }

    /** 过渡缩放走完了：图层回到 1，多组合的格子放掉。 */
    internal fun layerSettled() {
        layerScale = 1f
        cullBoost = 1f
    }

    private fun waveStyle(c: WallConfig): WaveStyle = when {
        c.reduceMotion -> WaveStyle.Plain
        c.isTv -> WaveStyle.Fade
        else -> WaveStyle.Full
    }

    private fun startWave(w: WallWave) {
        wave = w
        waveTime = 0f
        signal()
    }

    // ------------------------------------------------------------------ 放大与镜头

    /** 放大 [p]：点格子、切歌跟随、“定位”居中（[center]）；方向键 OK 只在出了安全区时平移最小距离。 */
    internal fun growAt(p: SlotPos, center: Boolean) {
        val s = scene ?: return
        val vp = viewport ?: return
        val ns = if (s.grown == p) s else s.withGrown(p)
        scene = ns
        moveCamera(if (center) ns.centerTarget(p, vp) else ns.revealTarget(p, camera.restingPoint, vp))
    }

    private fun moveCamera(target: WallPoint?) {
        if (target != null) {
            val c = config
            if (c?.reduceMotion == true || c?.active == false) camera.snapTo(target) else camera.animateTo(target)
        }
        publish()
        signal()
    }

    private fun follow(song: Int, moveFocus: Boolean) {
        val s = scene ?: return
        val vp = viewport ?: return
        val p = s.followTarget(song, camera.position, vp) ?: return
        growAt(p, center = true)
        if (moveFocus && focus != null) focus = p
    }

    /** “定位正在播放”。漂移中按的话，唤醒本身已经弹回去了。 */
    fun locate() {
        if (wake()) return
        follow(current, moveFocus = false)
    }

    /**
     * 放大第 [song] 首离屏幕中心最近的那一份并居中：“下一首播放”把这首挪了位，让它在新位置上接着放大，留在眼前。
     * 方向键焦点跟过去；Compose 焦点原来在旧那张卡的按钮上的话，卡换了位置按钮就没了，交回墙。
     */
    fun growSong(song: Int) {
        val s = scene ?: return
        val vp = viewport ?: return
        val p = s.followTarget(song, camera.position, vp) ?: return
        growAt(p, center = true)
        if (focus != null) focus = p
        if (focusInside) requestWallFocus()
    }

    /**
     * 有操作：空闲计时归零、浮层回来。返回 true 表示刚才在漂移——镜头弹回正在播放的大卡（焦点留在原来的格子），
     * 调用方不再执行这次操作本身。
     */
    fun wake(): Boolean {
        val was = camera.wake()
        lastTick = clock()
        if (was) follow(current, moveFocus = false)
        publish()
        signal()
        return was
    }

    /** 返回键，见 [wallBackStep]。返回 false 时页面离开。 */
    fun handleBack(): Boolean {
        val drifting = camera.isDrifting
        if (!drifting) wake()
        val s = scene
        val g = s?.grown
        val grownIsOther = g != null && s.songAt(g.bx, g.by, g.slot) != current
        when (wallBackStep(drifting, focusInside, grownIsOther, focus != null, config?.isTv == true)) {
            WallBackStep.Wake -> wake()
            WallBackStep.ReturnFocus -> requestWallFocus()
            WallBackStep.Collapse -> scene = s?.withGrown(null)
            WallBackStep.ClearFocus -> focus = null
            WallBackStep.Leave -> return false
        }
        return true
    }

    internal fun requestWallFocus() {
        try {
            focusRequester.requestFocus()
        } catch (_: IllegalStateException) {
            // 墙还没挂上（或已经离开）：没有可交的焦点
        }
    }

    // ------------------------------------------------------------------ 触摸

    /** 手指按下。返回 true 表示刚才在漂移，这一下只唤醒，整个手势吞掉。 */
    internal fun onPointerDown(): Boolean = wake()

    internal fun beginDrag() {
        dragFrom = camera.beginDrag()
        lastTick = clock()
        publish()
    }

    /** 手指从开始拖动处移了 ([dx], [dy]) dp；墙跟着手指走，镜头反向。 */
    internal fun dragBy(dx: Float, dy: Float) {
        val from = dragFrom ?: return
        camera.dragTo(WallPoint(from.x - dx, from.y - dy), from)
        publish()
    }

    /** 松手，[vx] / [vy] 是手指速度 dp/s。 */
    internal fun endDrag(vx: Float, vy: Float) {
        val from = dragFrom ?: return
        dragFrom = null
        camera.fling(vx, vy, from)
        publish()
        signal()
    }

    /** 点按屏幕上 ([x], [y]) dp：点到没放大的格就放大并居中；点大卡、缝、占位格不动。点格子不是播放。 */
    internal fun tapAt(x: Float, y: Float) {
        val s = scene ?: return
        val p = s.slotAt(WallPoint(camera.x + x, camera.y + y)) ?: return
        growTile(p)
    }

    /** 放大 [p] 并居中（点按、读屏的“放大”）；已经是大卡、占位格不动。漂移中这一下只唤醒。 */
    internal fun growTile(p: SlotPos) {
        if (wake()) return
        val s = scene ?: return
        if (p == s.grown || isGhost(s.songAt(p.bx, p.by, p.slot))) return
        growAt(p, center = true)
    }

    /** 长按屏幕上 ([x], [y]) dp：返回是否落在一首歌上（已交给 [onLongPress]）。 */
    internal fun longPressAt(x: Float, y: Float): Boolean {
        val s = scene ?: return false
        val p = s.slotAt(WallPoint(camera.x + x, camera.y + y)) ?: return false
        val song = s.songAt(p.bx, p.by, p.slot)
        if (isGhost(song)) return false
        onLongPress(song, false)
        return true
    }

    // ------------------------------------------------------------------ 按键

    /**
     * 预览阶段（根节点到焦点节点，墙和页面根节点都会过一遍）：漂移中按的第一下只唤醒，连同它的自动重复和松开一起吞掉；
     * 返回键不在这里（[handleBack] 自己处理唤醒），音量、媒体键不吞。返回 true 表示吃掉。
     */
    internal fun previewKey(key: WallKey, down: Boolean): Boolean {
        if (key == WallKey.Passthrough) return false
        val held = swallow
        if (held != null) {
            if (key == held) {
                if (!down) swallow = null
                return true
            }
            swallow = null
        }
        if (down && wake()) {
            swallow = key
            confirm.cancel()
            return true
        }
        return false
    }

    /**
     * 墙节点本身有 Compose 焦点时的按键：方向键移焦点，OK 放大 / 交给大卡，长按 OK 或菜单键是歌曲操作。
     * 墙是无限的，方向键总有邻格，焦点出不去：电视上墙外不放能拿焦点的按钮，整页操作都在长按 / 菜单键的面板里。
     */
    internal fun onKey(key: WallKey, down: Boolean, repeatCount: Int, longPress: Boolean): Boolean {
        when (key) {
            WallKey.Passthrough, WallKey.Other -> return false
            WallKey.Menu -> if (down && repeatCount == 0) longPressFocused(fromMenu = true)
            WallKey.Confirm -> if (down) {
                if (confirm.onDown(repeatCount, longPress) == ConfirmResult.Long) longPressFocused(fromMenu = false)
            } else if (confirm.onUp() == ConfirmResult.Short) {
                confirmFocused()
            }
            // 带子的尽头没有邻格：不吃掉，焦点按普通规则移出墙
            else -> if (down) return moveFocus(key.dx, key.dy)
        }
        return true
    }

    /** 电视进页面：焦点交给墙，没有选中格就直接选中离可用区中心最近的那格（这张歌单正在放时就是大卡）。 */
    fun takeFocus() {
        requestWallFocus()
        if (focus == null) moveFocus(0, 0)
    }

    internal fun onWallFocus(isFocused: Boolean, hasFocus: Boolean) {
        wallFocused = isFocused
        focusInside = hasFocus && !isFocused
        if (!isFocused) confirm.cancel()
    }

    /**
     * 第一下只选中离可用区中心最近的格；之后按空间位置找邻格。焦点格出了安全区才移镜头，只移最小距离。
     * 返回 false 表示这个方向上没有格子（听歌模式带子的边）。
     */
    internal fun moveFocus(dx: Int, dy: Int): Boolean {
        val s = scene ?: return false
        val vp = viewport ?: return false
        val f = focus
        val next = when {
            f == null -> s.initialFocus(camera.position, vp)
            dx == 0 && dy == 0 -> f
            else -> s.focusNeighbour(f, dx, dy)
        } ?: return false
        focus = next
        moveCamera(s.revealTarget(next, camera.restingPoint, vp))
        return true
    }

    /**
     * OK：没有焦点先选一格；焦点在没放大的格上就放大它（不居中，只平移最小距离）；在大卡上交给 [onGrownConfirm]
     * （页面决定：正在播放的卡播放 / 暂停，别的歌从这首播放，或者把焦点交进卡里）。占位格不动作。
     */
    private fun confirmFocused() {
        val s = scene ?: return
        val f = focus
        if (f == null) {
            moveFocus(0, 0)
            return
        }
        val song = s.songAt(f.bx, f.by, f.slot)
        if (song < 0 || isGhost(song)) return
        if (f == s.grown) onGrownConfirm(song, song == current) else growAt(f, center = false)
    }

    /**
     * 长按 OK、菜单键：焦点格的歌曲操作。还没选中格（刚进来、返回键清掉了）时先选中离中心最近的那格，
     * 菜单键要的是整页操作，不该因为没选格就没反应。
     */
    private fun longPressFocused(fromMenu: Boolean) {
        val s = scene ?: return
        val f = focus ?: if (moveFocus(0, 0)) focus else null
        if (f == null) return
        val song = s.songAt(f.bx, f.by, f.slot)
        if (song < 0 || isGhost(song)) return
        onLongPress(song, fromMenu)
    }

    // ------------------------------------------------------------------ 帧循环

    /** 镜头在动、入场波在走、占位格在闪时要逐帧推进；否则帧循环睡到下一个空闲阈值或下一次操作。 */
    internal val needsFrames: Boolean get() = camera.isAnimating || wave != null || shimmerBusy

    private val shimmerTarget: Float
        get() = if (config?.appending == true && scene?.field is WallField.FeedBand) 1f else 0f

    private val shimmerBusy: Boolean
        get() {
            val target = shimmerTarget
            return shimmerAlpha != target || (shimmerAlpha > 0f && config?.reduceMotion != true)
        }

    /**
     * 帧循环。只在 [needsFrames] 时按帧走；静止时按空闲阈值定时醒来推进计时（电视浮层淡出、到点起漂），
     * 不空转 60 帧。在 PosterWall 的 LaunchedEffect 里跑。
     */
    internal suspend fun runFrames() {
        lastTick = clock()
        while (true) {
            if (needsFrames) {
                withFrameNanos(frame)
            } else {
                val wait = idleWaitSeconds(
                    isTv = config?.isTv == true,
                    chromeIdle = camera.chromeIdle,
                    idleSeconds = camera.idleSeconds,
                    driftEnabled = camera.driftEnabled,
                    drifting = camera.isDrifting,
                )
                if (wait == null) {
                    wakeups.receive()
                } else {
                    withTimeoutOrNull(max(1L, ceil(wait * 1000).toLong())) { wakeups.receive() }
                }
                val now = clock()
                val dt = (now - lastTick).coerceAtLeast(0L) / 1e9f
                lastTick = max(lastTick, now)
                tick(dt)
            }
        }
    }

    /** 推进 [dt] 秒：镜头、入场波、微光，然后把结果写回快照状态。 */
    internal fun tick(dt: Float) {
        // 开着面板时每次醒来都把空闲计时归零（不发信号：帧循环按空闲阈值定时醒，不会空转）
        if (config?.holdAwake == true) camera.wake()
        camera.step(dt)
        val w = wave
        if (w != null) {
            val t = waveTime + dt
            if (t >= WallEntrance.duration(w.style)) {
                wave = null
                waveTime = 0f
            } else {
                waveTime = t
            }
        }
        advanceShimmer(dt)
        publish()
    }

    private fun advanceShimmer(dt: Float) {
        val target = shimmerTarget
        if (config?.reduceMotion == true) {
            // 减少动画：不扫，光带停在格子正中
            shimmerAlpha = target
            shimmerPhase = SHIMMER_STILL
            return
        }
        val step = dt / SHIMMER_FADE
        shimmerAlpha = if (shimmerAlpha < target) min(target, shimmerAlpha + step) else max(target, shimmerAlpha - step)
        shimmerPhase = if (shimmerAlpha > 0f) (shimmerPhase + dt / SHIMMER_PERIOD) % 1f else 0f
    }

    /** 把镜头写回快照状态。相同的值不触发失效，所以每帧调也只在真的动了时才重新摆放。 */
    private fun publish() {
        camX = camera.x
        camY = camera.y
        val p = metrics.pitch
        cellX = cullCell(camera.x, p)
        cellY = cullCell(camera.y, p)
        isDrifting = camera.isDrifting
        chromeVisible = !(config?.isTv == true && camera.chromeIdle)
    }

    private fun signal() {
        wakeups.trySend(Unit)
    }

    companion object {
        /** 捏合时允许略超出刻度两端，松手吸附回来（见 pinchZoom）。 */
        const val PINCH_MIN = 0.45f
        const val PINCH_MAX = 1.35f

        /** 微光扫一遍的秒数、开关时的淡入淡出秒数（原型 1.4s / 0.3s）。 */
        const val SHIMMER_PERIOD = 1.4f
        const val SHIMMER_FADE = 0.3f

        /** 原型减少动画时背景位置停在 50%：相位 (1.3 − 0.5) / 2.6，光带中心正好在格子中间。 */
        const val SHIMMER_STILL = 0.8f / 2.6f
    }
}
