/*
 * AMLL (Apple Music-like Lyrics), https://github.com/amll-dev/applemusic-like-lyrics
 * Copyright (C) the AMLL contributors (amll-dev)
 * Modified for myMusic on 2026-11-28 (Kotlin / Jetpack Compose rewrite); the changes are listed below.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
// 2026-11-28 myMusic 改写：取自 AMLL core 0.7.2（@4d8e0270）的 lyric-player/base/index.ts（syncTime、calcLayout、
// updateSpringParams、pause / resume、resetScroll）、lyric-player/base/group.ts 和 dom/lyric-group.ts（每组的位移、和声展开、
// 缩放、透明度、模糊）、lyric-player/base/scroll.ts（拖动、惯性、停手 0.5 秒后才允许回到当前句）。
// 照原样：每组一个纵向位移、错峰延迟、当前句和别的句子大小不同、和声缩小并从主句下面滑出（−80% → 0）、亮暗两层切换、
// 透明度和模糊 0.4 秒过渡、暂停时全部回到 100% 并展开和声；跳转时不错峰并重新开始间奏三点；拖动时去掉模糊、跟手，
// 惯性每 16.7ms 乘 0.95。
// 改动：DOM 换成一个自定义 Layout，每组一个子节点，位置、透明度、模糊在图层里读，逐帧只重画不重组；
// 缩放和位移用同一个错峰延迟（原版缩放不延迟，稿子定的）；和声展开时的高度直接按这一帧的状态算（原版等 ResizeObserver 再排一次）；
// 恢复自动对齐不要求当前句在视口里（停手之后等到下一次换句就回来）；跳转靠舞台时钟判定（见 amllDetectSeek）；
// 鼠标滚轮、底栏、隐藏已唱句、关闭弹簧不搬。减少动画时一切直接到位，三点静止。
// 2026-11-28 对照 Apple Music 改（iOS 16.2 录屏逐帧拟合为主，Android 7.0.3 反编译、网页版 MusicKit 为辅）：
// - 位移不用弹簧：每组 430ms 的 cubic-bezier(0.25, 0.1, 0.25, 1)（AmllGlide），错峰照旧；缩放跟着走同一条；
// - 换句提前 500ms：时间线、焦点、亮暗、间奏三点都按“播放时间 + 500ms”算，扫光、上浮、强调按真实时间
//   （录屏里新句在开唱前约 0.5 秒变亮并开始滚，滚完再开始扫光）；
// - 别的句子 98%（Android 版 dimen，原版 97%）；亮层进出 0.1 秒（录屏里一两帧就亮了，网页版 color .1s）；
// - 和声 0.65 倍字号、唱到的 35%，展开收起 0.3 秒 ease-in-out（网页版）；
// - 停手 4.5 秒后才允许回到当前句（网页版 4500ms，原版 0.5 秒）；
// - 换歌不再从屏下两个屏高处飞入（Apple 三端都没有这种做法）：新歌词直接摆好，整列 430ms 淡入；
// - 焦点句字的墨迹上沿对齐到屏高 24.6%：按整块屏幕算（舞台外面的系统栏也算上，见 StageInsets.outerTop），
//   对齐的是参照字（汉字为主的歌用“国”，否则“H”）的墨迹，不是行框。
package com.mytv.mymusic.ui.player.stage.modes

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.ui.player.TvFrameGate
import com.mytv.mymusic.ui.player.share.LocalLyricPickProbe
import com.mytv.mymusic.ui.player.share.lyricPickTarget
import com.mytv.mymusic.ui.player.share.pickShareAction
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import com.mytv.mymusic.ui.player.stage.StageDisplay
import com.mytv.mymusic.ui.player.stage.StageLine
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 一首歌的 AMLL 歌词状态：时间线、焦点、排版和每组的弹簧 / 过渡。单位是 dp（原版的 CSS 像素）。
 * 主线程上用：测量阶段 [measure]（排字、第一次摆放），帧循环里 [frame]，手势 [dragStart] / [dragBy] / [dragEnd]。
 * 图层和绘制读 [frame]（每帧变）和 [versions]（这一组要重画时变）两种状态。
 */
internal class AmllEngine(val song: AmllSong, private val fadeIn: Boolean) {
    val groups: List<AmllGroup> = song.groups
    val count: Int = groups.size
    private val timeline = AmllTimeline().apply {
        setBounds(
            DoubleArray(count) { groups[it].startMs },
            DoubleArray(count) { groups[it].endMs },
            song.interludes,
            BooleanArray(count) { !groups[it].main.timed },
        )
    }
    private val focus = AmllFocus()
    private val layout = AmllLayout()
    val dots = AmllDots()

    private val posY = Array(count) { AmllGlide(0.0) }
    private val scale = Array(count) { AmllGlide(FULL_SCALE) }
    private val bgScale = Array(count) { AmllGlide(FULL_SCALE, BG_SECONDS, AmllEase.cssInOut) }
    private val bgSlide = Array(count) { AmllGlide(BG_HIDDEN_SLIDE, BG_SECONDS, AmllEase.cssInOut) }

    /** 换歌时整列淡入。 */
    private val appear = AmllTween(1f)
    private val lit = Array(count) { AmllTween(0f) }
    private val opacity = Array(count) { AmllTween(1f) }
    private val blur = Array(count) { AmllTween(0f) }
    private val bgOpacity = Array(count) { AmllTween(0f) }
    private val motion = Array(count) { AmllTween(0f) }
    private val active = BooleanArray(count)
    private val enabled = BooleanArray(count)
    private val frozenMs = DoubleArray(count)
    private val dirty = BooleanArray(count)
    private val heights = DoubleArray(count)
    private val collapsed = DoubleArray(count)
    private val expanded = DoubleArray(count)
    private val mainGeom = arrayOfNulls<AmllLineGeom>(count)
    private val bgGeom = arrayOfNulls<AmllLineGeom>(count)

    /** 每组的重画计数：只有这一组的字（亮度、扫光、上浮、缩放、和声）变了才加。 */
    val versions = Array(count) { mutableIntStateOf(0) }

    /** 帧计数：图层（位置、透明度、模糊）和三点每帧读。 */
    val frame = mutableIntStateOf(0)

    /** 帧循环停着时，手势、尺寸、设置变了用它叫醒。 */
    val wake = mutableIntStateOf(0)

    /** 子节点的高度（px，按和声展开的高度给，收起时多出来的那截空着）。 */
    var childHeights = IntArray(count)
        private set
    var placed = false
        private set
    var unit = 1f
        private set
    var dotsSize = AmllDotsSize(30.0, 800.0, 20.0)
        private set
    private var widthDp = 0.0
    private var heightDp = 0.0
    private var viewportDp = 0.0
    private var fontDp = 30.0
    private var padXDp = 20.0

    /** 焦点的字顶对齐到歌词栏里的这个位置（dp）：屏高的 24.6%，换算到歌词栏里（见 [amllAlignTop]）。 */
    private var alignTopDp = 0.0

    /** 行框上边到字的墨迹上沿（dp）：对齐的是墨迹，录屏量的就是它。 */
    private var inkDp = 0.0
    private var geometryKey: Any? = null

    private var blurStrength = 1f
    private var emphasis = true
    var reduceMotion = false
        private set
    private var configured = false

    private var playing = true

    /** 舞台时钟的真实歌词时间：扫光、上浮、强调用它；时间线用它加上 [LEAD_MS]。 */
    private var clockMs = 0.0
    private var lastMs = Double.NaN
    private var lastNs = NO_FRAME

    /** 上一次处理帧的时刻（帧循环停下时不清）：判跳转按真实经过的时间算，减少动画时时钟隔几秒才动一次。 */
    private var lastSeenNs = NO_FRAME

    /** 刚摆好、舞台时钟可能还没发布（新开的舞台头一两帧是 0）：这段时间里的跳转按重新摆放处理，不滚过去。 */
    private var fresh = false
    private var startedAt = NO_TIME

    private var scrollOffset = 0.0
    private var suspended = false
    private var touchScrolled = false
    private var interacting = false
    private var scrollEndAt = NO_TIME
    private var inertia = 0.0

    private val mainFrame = AmllLineFrame()
    private val bgFrame = AmllLineFrame()
    private val paint = Paint()

    /** 面板设置：景深强度（Android 12 以下按 0）、长音强调、减少动画。变了下一帧按 ConfigChange 重排。 */
    fun configure(blurStrength: Float, emphasis: Boolean, reduceMotion: Boolean) {
        val strength = if (Build.VERSION.SDK_INT >= 31) blurStrength else 0f
        if (configured && strength == this.blurStrength && emphasis == this.emphasis && reduceMotion == this.reduceMotion) return
        configured = true
        this.blurStrength = strength
        this.emphasis = emphasis
        this.reduceMotion = reduceMotion
        if (placed) {
            calcLayout(AmllLayoutReason.ConfigChange)
            if (reduceMotion) snapAll()
            for (i in 0 until count) dirty[i] = true
            wake.intValue++
        }
    }

    /**
     * 测量：[widthPx] × [heightPx] 是歌词栏，[stageWidthPx] × [stageHeightPx] 是整个舞台（窄屏留白、三点高度、对齐位置用），
     * [columnTopPx] 是歌词栏在舞台里的上边，[outerTopPx]、[outerBottomPx] 是舞台外面上下还有多少屏幕。
     * 字号或宽度变了重新排字；第一次有尺寸时按 [clockMs] 摆好。
     */
    fun measure(
        widthPx: Int,
        heightPx: Int,
        stageWidthPx: Int,
        stageHeightPx: Int,
        columnTopPx: Int,
        outerTopPx: Int,
        outerBottomPx: Int,
        cache: AmllTextCache,
        fontPx: Float,
        density: Float,
        clockMs: Double,
        isPlaying: Boolean,
    ) {
        unit = density
        val newWidth = widthPx / density.toDouble()
        val newHeight = heightPx / density.toDouble()
        val newAlign = amllAlignTop(
            stageHeightPx / density.toDouble(),
            columnTopPx / density.toDouble(),
            newHeight,
            outerTopPx / density.toDouble(),
            outerBottomPx / density.toDouble(),
        )
        val resized = newWidth != widthDp || newHeight != heightDp || newAlign != alignTopDp
        alignTopDp = newAlign
        widthDp = newWidth
        heightDp = newHeight
        viewportDp = stageHeightPx / density.toDouble()
        fontDp = fontPx / density.toDouble()
        padXDp = if (stageWidthPx / density <= NARROW_SCREEN_DP) NARROW_PAD_DP else fontDp
        dotsSize = AmllDotsSize(fontDp, viewportDp, padXDp)
        val key = listOf(widthPx, fontPx, cache, padXDp)
        val rebuilt = key != geometryKey
        if (rebuilt) {
            geometryKey = key
            buildGeometry(widthPx, cache, fontPx, density)
        }
        if (!placed) {
            if (widthPx > 0 && heightPx > 0) start(clockMs, isPlaying)
        } else if (rebuilt || resized) {
            calcLayout(AmllLayoutReason.Resize)
            if (reduceMotion) snapAll()
            wake.intValue++
        }
    }

    private fun buildGeometry(widthPx: Int, cache: AmllTextCache, fontPx: Float, density: Float) {
        val padX = (padXDp * density).toFloat()
        val box = (widthPx - padX * 2).coerceAtLeast(1f)
        val bgFont = fontPx * BG_FONT_RATIO
        // 参照字的墨迹上沿：汉字为主的歌按“国”，否则按大写 H（画出来量，换字体也对）
        val cjk = groups.count { group -> group.main.units.any { amllIsCjk(it.text) } } * 2 >= count
        inkDp = cache.inkTop(if (cjk) "国" else "H", Density(density)) / density.toDouble()
        if (childHeights.size != count) childHeights = IntArray(count)
        for (i in 0 until count) {
            val group = groups[i]
            val main = amllLayoutLine(group.main, cache, box, fontPx, false, group.duet, song.duet, group.startMs, group.endMs)
            val bg = group.bg?.let { amllLayoutLine(it, cache, box, bgFont, true, group.duet, song.duet, group.startMs, group.endMs) }
            mainGeom[i] = main
            bgGeom[i] = bg
            val shut = fontPx * GROUP_PAD_EM * 2 + main.height
            val open = if (bg != null) shut + fontPx * BG_GAP_EM + bgBlockPx(bg, bgFont) else shut
            collapsed[i] = shut / density.toDouble()
            expanded[i] = open / density.toDouble()
            childHeights[i] = open.roundToInt().coerceAtLeast(1)
            dirty[i] = true
            versions[i].intValue++
        }
    }

    private fun bgBlockPx(bg: AmllLineGeom, bgFont: Float): Float = bgFont * BG_LINE_PAD_EM * 2 + bg.height

    /** 第一次摆放（rebuildLyricView）：按跳转同步一次时间再整体排一次，直接到位；换歌时整列淡入。 */
    private fun start(ms: Double, isPlaying: Boolean) {
        placed = true
        playing = isPlaying
        if (!isPlaying) dots.pause()
        if (fadeIn && !reduceMotion) {
            appear.snapTo(0f)
            appear.animateTo(1f, APPEAR_SECONDS, AmllEase.css)
        }
        place(ms)
        fresh = true
        startedAt = SystemClock.uptimeMillis()
        wake.intValue++
    }

    /** 按 [ms] 整体摆一次：跳转同步时间、RebuildView 排版，直接到位。 */
    private fun place(ms: Double) {
        lastMs = ms
        clockMs = ms
        syncTime(ms + LEAD_MS, seek = true)
        calcLayout(AmllLayoutReason.RebuildView)
        snapAll()
    }

    /**
     * 推进一帧：[ms] 是舞台时钟的歌词时间。返回还有没有在动（false 时帧循环可以停下等时钟或手势）。
     */
    fun frame(nowNs: Long, ms: Double, isPlaying: Boolean): Boolean {
        if (!placed) return false
        val dt = if (lastNs == NO_FRAME) 0.0 else ((nowNs - lastNs) / 1e9).coerceIn(0.0, MAX_FRAME_S)
        lastNs = nowNs
        val elapsedMs = if (lastSeenNs == NO_FRAME) 0.0 else max(0.0, (nowNs - lastSeenNs) / 1e6)
        lastSeenNs = nowNs
        if (isPlaying != playing) {
            playing = isPlaying
            if (isPlaying) dots.resume() else dots.pause()
            calcLayout(AmllLayoutReason.ConfigChange)
            for (i in 0 until count) if (groups[i].bg != null) dirty[i] = true
        }
        val seek = amllDetectSeek(lastMs, ms, elapsedMs, playing)
        val timeMoved = ms != lastMs
        if (seek && fresh && SystemClock.uptimeMillis() - startedAt < FRESH_WINDOW_MS) {
            // 新开的舞台按 0 摆好以后时钟才到真实位置：重新摆，不当成跳转从头滚过去
            fresh = false
            place(ms)
        } else {
            if (timeMoved) fresh = false
            lastMs = ms
            clockMs = ms
            syncTime(ms + LEAD_MS, seek)
        }

        if (inertia != 0.0 && !interacting) {
            val dtMs = dt * 1000
            if (abs(inertia) > INERTIA_STOP && dtMs > 0) {
                scrollOffset = layout.clampOffset(scrollOffset - inertia * dtMs)
                inertia *= INERTIA_DECAY.pow(dtMs / BASE_FRAME_MS)
                calcLayout(AmllLayoutReason.ContinuousScroll)
            } else if (abs(inertia) <= INERTIA_STOP) {
                inertia = 0.0
                scrollEndAt = SystemClock.uptimeMillis()
            }
        }

        val dtF = dt.toFloat()
        var moving = appear.running
        appear.update(dtF)
        for (i in 0 until count) {
            val layerBusy = !posY[i].arrived() || opacity[i].running || blur[i].running
            val drawBusy = !scale[i].arrived() || !bgScale[i].arrived() || !bgSlide[i].arrived() ||
                lit[i].running || bgOpacity[i].running || motion[i].running
            if (layerBusy) {
                posY[i].update(dt)
                opacity[i].update(dtF)
                blur[i].update(dtF)
            }
            if (drawBusy) {
                scale[i].update(dt)
                bgScale[i].update(dt)
                bgSlide[i].update(dt)
                lit[i].update(dtF)
                bgOpacity[i].update(dtF)
                motion[i].update(dtF)
            }
            // 唱着的句子扫光和上浮跟着时间走；停着的句子只有落回、淡出的那一小段要画
            val live = drawBusy || (enabled[i] && timeMoved) || dirty[i]
            if (live) {
                versions[i].intValue++
                dirty[i] = false
            }
            moving = moving || layerBusy || drawBusy
        }
        // 暂停时三点的样子只跟着歌词时间走，时间不动就不用逐帧推（跳转会改时钟、把帧循环叫醒）
        val dotsBusy = (playing && dots.showing) || !dots.posY.arrived()
        dots.update(dt * 1000)
        if (reduceMotion) snapAll()
        frame.intValue++
        if (reduceMotion) return inertia != 0.0 || interacting
        val singing = playing && enabled.any { it }
        return moving || dotsBusy || inertia != 0.0 || interacting || singing
    }

    /** 帧循环停下前记一下这一刻，醒来时帧间隔从 0 算（不把停着的时间当一帧）。 */
    fun idle() {
        lastNs = NO_FRAME
    }

    private fun snapAll() {
        appear.snap()
        for (i in 0 until count) {
            posY[i].setPosition(posY[i].target)
            scale[i].setPosition(scale[i].target)
            bgScale[i].setPosition(bgScale[i].target)
            bgSlide[i].setPosition(bgSlide[i].target)
            lit[i].snap()
            opacity[i].snap()
            blur[i].snap()
            bgOpacity[i].snap()
            motion[i].snap()
        }
        dots.posY.setPosition(dots.posY.target)
    }

    /** syncTime：按提前过的时间 [ms] 推进时间线，按差异开关各组的逐字动画、决定要不要回到当前句，再排一次。 */
    private fun syncTime(ms: Double, seek: Boolean) {
        val wasFocusOnInterlude = timeline.focusOnInterlude
        timeline.sync(ms, seek)
        dots.syncClock(ms)
        if (!timeline.hasChanged) return
        val jumped = timeline.timeJumped
        for (index in timeline.removedHighlighted) disable(index)
        if (jumped) {
            for (index in timeline.highlighted) enable(index)
        } else {
            for (index in timeline.addedHighlighted) enable(index)
        }
        if (jumped) {
            if (!touchScrolled) resetScroll()
        } else if ((timeline.scrollToChanged || wasFocusOnInterlude) &&
            !timeline.focusOnInterlude &&
            timeline.playing.contains(timeline.scrollToIndex) &&
            suspended &&
            canResumeAutoAlign()
        ) {
            resetScroll()
        }
        calcLayout(if (jumped) AmllLayoutReason.Seek else AmllLayoutReason.PlaybackTick)
    }

    private fun enable(index: Int) {
        if (index !in 0 until count) return
        enabled[index] = true
        motion[index].snapTo(1f)
        dirty[index] = true
    }

    private fun disable(index: Int) {
        if (index !in 0 until count) return
        enabled[index] = false
        frozenMs[index] = clockMs
        motion[index].animateTo(0f, SETTLE_SECONDS, LinearEasing)
        if (reduceMotion) motion[index].snap()
        dirty[index] = true
    }

    private fun canResumeAutoAlign(): Boolean =
        scrollEndAt != NO_TIME && SystemClock.uptimeMillis() - scrollEndAt >= AUTO_ALIGN_RESUME_MS

    /** calcLayout：间奏三点、焦点、每组的目标位置（带错峰）、缩放、亮暗、透明度、模糊。 */
    private fun calcLayout(reason: AmllLayoutReason) {
        if (!placed) return
        val interlude = timeline.activeInterlude
        val canDisplayInterlude = if (interlude != null) {
            dots.setInterlude(interlude.startMs, interlude.endMs, timeline.currentTime, reason.resetInterlude, interlude.anchor)
        } else {
            dots.clearInterlude(immediate = reduceMotion)
            false
        }
        focus.resolve(timeline, count, suspended, canDisplayInterlude)
        val scrollTo = timeline.scrollToIndex
        val latest = if (timeline.latestHighlighted >= 0) timeline.latestHighlighted else scrollTo
        for (i in 0 until count) {
            active[i] = timeline.highlighted.contains(i) || (i >= scrollTo && i < latest)
            heights[i] = if (groups[i].bg != null && (active[i] || !playing)) expanded[i] else collapsed[i]
        }
        val dotMargin = fontDp * DOT_MARGIN_EM
        val interludeHeight = dotsSize.height + dotMargin * 2
        layout.setHeights(heights)
        val anchor = amllInterludeAnchor(if (canDisplayInterlude) interlude else null, focus)
        layout.begin(heightDp, focus.kind, focus.index, interludeHeight, anchor, alignTopDp, fontDp * GROUP_PAD_EM + inkDp, dotMargin)
        scrollOffset = layout.clampOffset(scrollOffset)
        layout.commit(heightDp, scrollOffset)
        if (layout.hasInterlude && canDisplayInterlude && interlude != null) {
            val nextDuet = groups.getOrNull(interlude.anchor + 1)?.duet ?: false
            val x = if (nextDuet) widthDp - dotsSize.width else 0.0
            dots.setTransform(x, layout.interludeY + dotMargin, reason.snapPosY)
        }
        val narrow = widthDp <= AMLL_NARROW_DP
        val delays = amllStaggerDelays(layout.ys, heights, count, scrollTo, reason.disableStagger)
        for (i in 0 until count) {
            val isActive = active[i]
            val inViewport = layout.inViewport[i]
            val delay = delays[i]
            val y = layout.ys[i]
            val slide = if (isActive || !playing) 0.0 else BG_HIDDEN_SLIDE
            if (reason.snapPosY) {
                posY[i].setPosition(y)
                bgSlide[i].setPosition(slide)
            } else {
                posY[i].setTarget(y, delay)
                bgSlide[i].setTarget(slide, delay)
            }
            scale[i].setTarget(if (!isActive && playing) INACTIVE_SCALE else FULL_SCALE, delay)
            bgScale[i].setTarget(if (!isActive && playing) BG_INACTIVE_SCALE else FULL_SCALE, delay)
            // 亮层进出当前句都是 0.1 秒（iOS 录屏里一两帧就亮了；网页版 color .1s）
            lit[i].animateTo(if (isActive) 1f else 0f, LIT_SECONDS, AmllEase.css)
            bgOpacity[i].animateTo(if (isActive || !playing) 1f else 0f, BG_FADE_SECONDS, AmllEase.css)
            opacity[i].animateTo(amllOpacity(inViewport), GROUP_FADE_SECONDS, AmllEase.css)
            val blurLevel = amllBlur(i, isActive, inViewport, scrollTo, latest, narrow, touchScrolled, blurStrength)
            blur[i].animateTo(blurLevel, GROUP_FADE_SECONDS, AmllEase.css)
        }
    }

    // —— 拖动 ——

    fun dragStart() {
        inertia = 0.0
        scrollEndAt = NO_TIME
        if (!interacting) {
            interacting = true
            suspended = true
            touchScrolled = true
            calcLayout(AmllLayoutReason.InteractionStart)
        }
        wake.intValue++
    }

    /** 手指往下移 [dyDp]：内容跟着往下。 */
    fun dragBy(dyDp: Double) {
        scrollOffset = layout.clampOffset(scrollOffset - dyDp)
        calcLayout(AmllLayoutReason.ContinuousScroll)
        wake.intValue++
    }

    /** 松手，[speedDpPerMs] 是手指的速度（往下为正）；够快就接着惯性滑。 */
    fun dragEnd(speedDpPerMs: Double) {
        interacting = false
        val speed = if (abs(speedDpPerMs) < INERTIA_MIN) 0.0 else speedDpPerMs
        if (abs(speed) > INERTIA_STOP) {
            inertia = speed
        } else {
            inertia = 0.0
            scrollEndAt = SystemClock.uptimeMillis()
        }
        wake.intValue++
    }

    /** 回到自动对齐（点句、跳转）。 */
    fun resetScroll() {
        scrollOffset = 0.0
        inertia = 0.0
        suspended = false
        touchScrolled = false
        wake.intValue++
    }

    // —— 绘制 ——

    fun layerY(index: Int): Float = (posY[index].position * unit).toFloat()

    fun layerAlpha(index: Int): Float = opacity[index].value * appear.value

    /** 模糊（CSS 的 blur(σ)，σ 是 dp）换成图层的模糊效果。 */
    fun layerBlur(index: Int): androidx.compose.ui.graphics.RenderEffect? {
        val sigma = blur[index].value
        if (sigma <= 0.01f) return null
        return RushBlur.of(amllBlurRadius(sigma * unit))
    }

    fun dotsX(): Float = (dots.left * unit).toFloat()

    fun dotsY(): Float = (dots.posY.position * unit).toFloat()

    /** 画第 [index] 组：和声（在下面，从主句底下滑出）再主句。 */
    fun drawGroup(index: Int, scope: DrawScope, color: Color) {
        val main = mainGeom[index] ?: return
        val group = groups[index]
        val em = (fontDp * unit).toFloat()
        val padX = (padXDp * unit).toFloat()
        val padTop = em * GROUP_PAD_EM
        val origin = if (group.duet) padX + main.boxWidth else padX
        val time = if (enabled[index]) clockMs else frozenMs[index]
        val still = reduceMotion
        fill(mainFrame, index, time, still)
        bgGeom[index]?.let { bg ->
            val shown = bgOpacity[index].value
            if (shown > 0.001f) {
                val bgEm = em * BG_FONT_RATIO
                val open = active[index] || !playing
                val top = if (open) padTop + main.height + em * BG_GAP_EM else (collapsed[index] * unit).toFloat()
                val block = bgBlockPx(bg, bgEm)
                val slide = bgSlide[index].position
                val progress = amllClamp01(1 - abs(slide) / -BG_HIDDEN_SLIDE).toFloat()
                val wrapperScale = 0.8f + 0.2f * progress
                val shift = (slide / 100 * block).toFloat()
                val lineScale = (bgScale[index].position / 100).toFloat()
                fill(bgFrame, index, time, still)
                bgFrame.alpha = BG_LINE_ALPHA * shown
                scope.withTransform({
                    translate(origin, top + shift)
                    scale(wrapperScale, wrapperScale, Offset.Zero)
                    translate(-origin, -top)
                    scale(lineScale, lineScale, Offset(origin, top + block / 2))
                    translate(padX, top + bgEm * BG_LINE_PAD_EM)
                }) {
                    drawAmllLine(bg, bgFrame, color, paint)
                }
            }
        }
        val s = (scale[index].position / 100).toFloat()
        scope.withTransform({
            scale(s, s, Offset(origin, padTop + main.height / 2))
            translate(padX, padTop)
        }) {
            drawAmllLine(main, mainFrame, color, paint)
        }
    }

    private fun fill(frame: AmllLineFrame, index: Int, time: Double, still: Boolean) {
        frame.lit = lit[index].value
        frame.sweepMs = time
        frame.motionMs = time
        frame.motion = if (still) 0f else motion[index].value
        frame.fullLit = still
        frame.emphasis = emphasis && !still
        frame.alpha = 1f
    }

    fun drawDots(scope: DrawScope, color: Color) {
        val shown = appear.value
        if (shown <= 0.001f) return
        scope.drawAmllDots(dots, dotsSize, if (shown < 1f) color.copy(alpha = color.alpha * shown) else color, unit, still = reduceMotion)
    }

    private companion object {
        const val NO_FRAME = Long.MIN_VALUE
        const val NO_TIME = Long.MIN_VALUE
        const val MAX_FRAME_S = 0.1

        /** 换句提前量：iOS 录屏里新句在开唱前约 0.5 秒变亮并开始滚（430ms 滚完，再过一两帧开始扫光）。 */
        const val LEAD_MS = 500.0
        const val FULL_SCALE = 100.0
        const val INACTIVE_SCALE = 98.0
        const val BG_INACTIVE_SCALE = 75.0
        const val BG_HIDDEN_SLIDE = -80.0
        const val BG_SECONDS = 0.3
        const val LIT_SECONDS = 0.1f
        const val BG_FADE_SECONDS = 0.3f
        const val APPEAR_SECONDS = 0.43f
        const val GROUP_FADE_SECONDS = 0.4f
        const val SETTLE_SECONDS = 0.25f
        const val GROUP_PAD_EM = 0.4f
        const val BG_GAP_EM = 0.3f
        const val BG_LINE_PAD_EM = 0.2f
        const val BG_FONT_RATIO = AMLL_BG_FONT_RATIO
        const val BG_LINE_ALPHA = 0.35f
        const val DOT_MARGIN_EM = 0.4
        const val NARROW_SCREEN_DP = 500f
        const val NARROW_PAD_DP = 20.0
        const val AUTO_ALIGN_RESUME_MS = 4_500L

        /** 刚摆好之后多久以内的跳转按重新摆放算（舞台时钟头一两帧还没发布）。 */
        const val FRESH_WINDOW_MS = 1_000L
        const val INERTIA_MIN = 0.1
        const val INERTIA_STOP = 0.05
        const val INERTIA_DECAY = 0.95
        const val BASE_FRAME_MS = 1000.0 / 60
    }
}

/** 记住上一份歌词：换了一份（换歌、歌词到了）才淡入；第一次显示直接出现。 */
private class AmllMount {
    var lines: List<StageLine>? = null

    fun fadeIn(next: List<StageLine>, reduceMotion: Boolean): Boolean {
        val first = lines == null
        val fresh = lines !== next
        lines = next
        return !first && fresh && next.isNotEmpty() && !reduceMotion
    }
}

/**
 * AMLL 的歌词：排在舞台安全区里（宽度最多 24 个字号、靠起始边），上下两端渐隐；所有句子在一个自定义 Layout 里。
 * 拖动、点一句跳过去（[StageDisplay.touch] 时），长按选句分享。
 */
@Composable
internal fun AmllLyrics(ctx: LyricStageContext, look: AmllLook, color: State<Color>, modifier: Modifier = Modifier) {
    val display = ctx.display
    val reduceMotion = display.reduceMotion
    val song = remember(ctx.lines, ctx.subline, look.voices) { amllSong(ctx.lines, ctx.subline, look.voices) }
    val mount = remember { AmllMount() }
    val engine = remember(song) { AmllEngine(song, mount.fadeIn(ctx.lines, reduceMotion)) }
    SideEffect { engine.configure(look.blur, look.emphasis, reduceMotion) }
    val current = rememberUpdatedState(ctx)

    val fontSp = display.textSp * look.size
    val typography = MaterialTheme.typography.bodyLarge
    val lyricFont = ctx.font
    val styles = remember(typography, fontSp, lyricFont) {
        val base = typography.copy(
            fontFamily = lyricFont?.family ?: typography.fontFamily,
            fontSize = fontSp.sp,
            lineHeight = (fontSp * LINE_HEIGHT_EM).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = LETTER_SPACING_EM.em,
            textMotion = TextMotion.Animated,
        )
        val bgSp = fontSp * AMLL_BG_FONT_RATIO
        val bg = base.copy(fontSize = bgSp.sp, lineHeight = (bgSp * LINE_HEIGHT_EM).sp)
        val sub = base.copy(fontSize = (fontSp * 0.5f).sp, lineHeight = (fontSp * 0.75f).sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
        val bgSub = sub.copy(fontSize = (bgSp * 0.5f).sp, lineHeight = (bgSp * 0.75f).sp)
        AmllStyles(base, bg, sub, bgSub)
    }
    val measurer = rememberTextMeasurer(cacheSize = 0)
    // 排字缓存按歌换：一直开着歌词页时不把每首歌的字都攒着
    val cache = remember(measurer, styles, song) { AmllTextCache(measurer, styles) }
    val localDensity = LocalDensity.current
    val fontPx = with(localDensity) { fontSp.sp.toPx() }
    val pick = LocalLyricPickProbe.current.takeIf { display.touch }
    val haptics = LocalHapticFeedback.current
    val shape = remember(fontPx, localDensity) { RoundedCornerShape(with(localDensity) { (fontPx * 0.25f).toDp() }) }
    val onTap: (Int) -> Unit = remember(engine, haptics) {
        { index: Int ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            engine.resetScroll()
            current.value.seek(engine.groups[index].startMs.toLong())
        }
    }

    AmllFrames(engine, ctx, display.tv)

    val stage = remember { IntArray(5) }
    Layout(
        content = {
            for (i in 0 until engine.count) {
                key(i) { AmllGroupNode(engine, i, color, pick, display.touch, shape, onTap) }
            }
            Spacer(
                Modifier
                    .graphicsLayer {
                        engine.frame.intValue
                        translationX = engine.dotsX()
                        translationY = engine.dotsY()
                    }
                    .drawBehind {
                        engine.frame.intValue
                        engine.drawDots(this, color.value)
                    },
            )
        },
        modifier = modifier
            .fillMaxSize()
            .amllSafeArea(display, fontPx, stage)
            .amllEdgeFade()
            .then(if (display.touch) Modifier.amllDrag(engine) else Modifier),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val clockMs = Snapshot.withoutReadObservation { ctx.clock.ms.toDouble() }
        val isPlaying = Snapshot.withoutReadObservation { !ctx.paused.value }
        engine.measure(width, height, stage[0], stage[1], stage[2], stage[3], stage[4], cache, fontPx, density, clockMs, isPlaying)
        val heights = engine.childHeights
        val placeables = measurables.mapIndexed { i, m ->
            if (i < engine.count) {
                m.measure(Constraints.fixed(width, heights.getOrElse(i) { 1 }))
            } else {
                val size = engine.dotsSize
                m.measure(Constraints.fixed((size.width * density).roundToInt().coerceAtLeast(1), (size.height * density).roundToInt().coerceAtLeast(1)))
            }
        }
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

/** 一组句子：位置、透明度、模糊在图层里每帧读；字只在这一组的重画计数变了时重画。 */
@Composable
private fun AmllGroupNode(
    engine: AmllEngine,
    index: Int,
    color: State<Color>,
    pick: com.mytv.mymusic.ui.player.share.LyricPickProbe?,
    touch: Boolean,
    shape: RoundedCornerShape,
    onTap: (Int) -> Unit,
) {
    val line = engine.groups[index].line
    Spacer(
        Modifier
            .graphicsLayer {
                engine.frame.intValue
                translationY = engine.layerY(index)
                alpha = engine.layerAlpha(index)
                renderEffect = engine.layerBlur(index)
            }
            .drawBehind {
                engine.versions[index].intValue
                engine.drawGroup(index, this, color.value)
            }
            .lyricPickTarget(pick, line)
            .then(if (pick?.enabled == true) Modifier.semantics { customActions = listOf(pickShareAction(pick, line)) } else Modifier)
            .then(if (touch) Modifier.clip(shape).clickable(onClickLabel = "从这一句播放") { onTap(index) } else Modifier),
    )
}

/**
 * 帧循环：有东西在动（或在唱）时每帧推进，电视上限 30 帧；都停稳了就等时钟、暂停状态或手势变了再跑。
 */
@Composable
private fun AmllFrames(engine: AmllEngine, ctx: LyricStageContext, tv: Boolean) {
    val clock = ctx.clock
    val paused = ctx.paused
    LaunchedEffect(engine, tv) {
        while (true) {
            val busy = withFrameNanos { now ->
                if (tv && !TvFrameGate.due(now)) true else engine.frame(now, clock.ms.toDouble(), !paused.value)
            }
            if (!busy) {
                val stamp = Triple(clock.ms, paused.value, engine.wake.intValue)
                snapshotFlow { Triple(clock.ms, paused.value, engine.wake.intValue) }.first { it != stamp }
                engine.idle()
            }
        }
    }
}

/** 竖向拖动：跟手、松手按速度惯性（速度换成 dp / ms，往下为正）。 */
private fun Modifier.amllDrag(engine: AmllEngine): Modifier = pointerInput(engine) {
    val tracker = VelocityTracker()
    detectVerticalDragGestures(
        onDragStart = {
            tracker.resetTracking()
            engine.dragStart()
        },
        onDragEnd = {
            val velocity = tracker.calculateVelocity().y
            engine.dragEnd(velocity / density / 1000.0)
        },
        onDragCancel = { engine.dragEnd(0.0) },
    ) { change, dragAmount ->
        tracker.addPosition(change.uptimeMillis, change.position)
        change.consume()
        engine.dragBy(dragAmount / density.toDouble())
    }
}

/**
 * 歌词栏放进安全区：宽度最多 24 个字号、靠起始边。[stage] 记下整个舞台的宽高、歌词栏的上边、舞台外面上下的屏幕
 * （测量歌词栏时用），只在布局阶段读安全区。
 */
private fun Modifier.amllSafeArea(display: StageDisplay, fontPx: Float, stage: IntArray): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    stage[0] = width
    stage[1] = height
    val rect = display.safeRect(Size(width.toFloat(), height.toFloat()))
    stage[2] = rect.top.roundToInt()
    stage[3] = display.insets.outerTop.roundToInt()
    stage[4] = display.insets.outerBottom.roundToInt()
    val columnWidth = min(rect.width, fontPx * MAX_COLUMN_EM).roundToInt().coerceAtLeast(0)
    val columnHeight = rect.height.roundToInt().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(columnWidth, columnHeight))
    layout(width, height) { placeable.placeRelative(rect.left.roundToInt(), rect.top.roundToInt()) }
}

/**
 * 焦点字顶在歌词栏里的位置（dp）：屏高的 24.6% 换算到歌词栏里。屏幕 = 舞台 [stageDp] 加上舞台外面上下的
 * [outerTopDp]、[outerBottomDp]（手机播放页让开的状态栏、导航栏；iPhone 录屏量的是整块屏幕）；
 * 再减去舞台外面的上边和歌词栏上边 [columnTopDp]。不落进上端渐隐区（[FADE_TOP] 或栏高 12%），也不低于栏高的一半。
 */
internal fun amllAlignTop(
    stageDp: Double,
    columnTopDp: Double,
    columnDp: Double,
    outerTopDp: Double = 0.0,
    outerBottomDp: Double = 0.0,
): Double {
    val fade = min(FADE_TOP.value.toDouble(), columnDp * 0.12)
    val screen = stageDp + outerTopDp + outerBottomDp
    return (screen * AMLL_ANCHOR_TOP - outerTopDp - columnTopDp).coerceIn(fade, max(fade, columnDp * 0.5))
}

/** 上下两端渐隐（上 44dp、下 60dp，矮的时候按高度收），离屏合成一次再用 DstIn 擦；超出栏的部分裁掉。 */
private fun Modifier.amllEdgeFade(): Modifier = this
    .graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        clip = true
    }
    .drawWithCache {
        val h = size.height
        val top = if (h > 0f) min(FADE_TOP.toPx(), h * 0.12f) / h else 0f
        val bottom = if (h > 0f) min(FADE_BOTTOM.toPx(), h * 0.16f) / h else 0f
        val mask = Brush.verticalGradient(0f to Color.Transparent, top to Color.Black, 1f - bottom to Color.Black, 1f to Color.Transparent)
        onDrawWithContent {
            drawContent()
            if (h > 0f) drawRect(mask, blendMode = BlendMode.DstIn)
        }
    }

/** 和声行的字号：主句的 0.65 倍（Apple 网页版和 Android 版都是 34 配 22）。 */
internal const val AMLL_BG_FONT_RATIO = 0.65f

/** 行高 1.22 字号、字距 −0.01 字号（AMLL 的默认样式）。 */
private const val LINE_HEIGHT_EM = 1.22f
private const val LETTER_SPACING_EM = -0.01f

/** 歌词栏最宽 24 个字号。 */
private const val MAX_COLUMN_EM = 24f
private val FADE_TOP = 44.dp
private val FADE_BOTTOM = 60.dp
