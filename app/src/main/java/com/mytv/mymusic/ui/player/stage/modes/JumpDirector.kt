package com.mytv.mymusic.ui.player.stage.modes

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * 跳一跳的“导演”（照 visualizer-jump 1.2.0 的 client.paintFrame 与 jumpCourse / jumpTimeline / jumpBoard 的逐帧函数）：
 * 给定歌词时刻，算出这一帧小人在哪、镜头对着哪、每根柱子沉了多少 / 升到哪、哪几圈落地环正在散开。纯计算，不碰 android.*。
 *
 * 一切都只由时刻算出来、不读上一帧：拖进度之后第一帧就是那一刻该有的样子，暂停就是同一帧反复画。
 * 结果写进一个复用的 [JumpPose]（按整首歌最长的一行开好数组），逐帧不分配对象。位置都是格（相对整条路的原点）。
 */

// ---- 落地那一下（jumpCourse） ----

/** 落地环散开要多久（秒）。 */
internal const val JUMP_IMPACT_SECONDS = 0.36

/** 一圈落地环带几粒尘土。 */
internal const val JUMP_IMPACT_DUST = 6

private const val TAKEOFF_RING_SCALE = 0.78f
private const val TAKEOFF_RING_FADE = 0.7f

/**
 * 滞空时身子的压扁 / 拉长（1 = 原样）：起跳那一下蹲（0.1 处，8%）、落地那一下压（0.9 处，10%），空中拉长 5%，
 * 夹在 0.88..1.07。宽度按 1 + (1 − 压扁) × 0.5 反着变，体积看上去不变。
 */
internal fun jumpHopSquash(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    val charge = exp(-((t - 0.1f) / 0.08f).pow(2)) * 0.08f
    val impact = exp(-((t - 0.9f) / 0.07f).pow(2)) * 0.1f
    val stretch = sin(PI.toFloat() * ((t - 0.16f) / 0.72f).coerceIn(0f, 1f)) * 0.05f
    return (1f - charge - impact + stretch).coerceIn(0.88f, 1.07f)
}

/**
 * 柱子被踩下去多少（格，乘 lift 才是屏幕上的高度）：踩上去 0.09 秒内沉到 0.45 格（缓出），离开时弹回来——
 * 衰减余弦（周期 0.3 秒、阻尼 7），1.4 秒后归零。[release] 不晚于 [press] 时（这根柱子根本没被踩过）一直是 0。
 */
internal fun jumpPillarSpring(time: Double, press: Double, release: Double, strength: Float = 1f): Float {
    if (strength <= 0f || !press.isFinite() || time < press) return 0f
    val depth = 0.45f * strength
    if (release.isFinite() && release <= press) return 0f
    if (!release.isFinite() || time < release) {
        val t = ((time - press) / 0.09).coerceIn(0.0, 1.0)
        return depth * (1f - (1.0 - t).pow(3).toFloat())
    }
    val tau = time - release
    if (tau > 1.4) return 0f
    val decay = exp(-7.0 * tau)
    if (decay < 0.004) return 0f
    return (depth * decay * cos(2.0 * PI * tau / 0.3)).toFloat()
}

/** 落地环此刻的半径系数（× 环的基准半径）：[u] 是 0..1 的进度，从 0.62 缓出散到 1。 */
internal fun jumpBurstRadius(u: Float): Float = 0.62f + 0.38f * (1f - (1f - u.coerceIn(0f, 1f)).pow(3))

/** 落地环此刻的浓度：(1 − u)^1.35。 */
internal fun jumpBurstFade(u: Float): Float = (1f - u.coerceIn(0f, 1f)).pow(1.35f)

/** 第 [index] 粒尘土此刻的位置（× 环的基准半径；y 还要再乘俯角的压扁）。 */
internal fun jumpBurstDust(u: Float, index: Int, out: FloatArray) {
    val t = u.coerceIn(0f, 1f)
    val spread = 1f - (1f - t).pow(3)
    val angle = (2.0 * PI * index / JUMP_IMPACT_DUST + 0.42).toFloat()
    val reach = spread * (0.62f + 0.38f * ((index % 3) / 2f))
    out[0] = cos(angle) * reach
    out[1] = sin(angle) * 0.5f * reach
}

// ---- 升起、轨迹（jumpTimeline） ----

/**
 * 一行柱子从地里升起来（原版 resolveRowReveal）：第 [order] 块晚 0.07 秒，每块 0.4 秒升到位（缓出）；
 * 整行超出 [budget] 就按比例压缩，单块至少 0.12 秒。[start] 是 NaN 时一直在（前两行一开始就立着）。
 */
internal fun jumpRowReveal(time: Double, start: Double, budget: Double, order: Int, count: Int): Float {
    if (!start.isFinite()) return 1f
    var rise = 0.4
    var stagger = 0.07
    val total = rise + stagger * (max(1, count) - 1)
    if (budget > 0 && total > budget) {
        val scale = budget / total
        stagger *= scale
        rise = max(0.12, rise * scale)
    }
    val begin = start + max(0, order) * stagger
    if (time <= begin) return 0f
    val t = (time - begin) / rise
    if (t >= 1) return 1f
    return (1.0 - (1.0 - t).pow(3)).toFloat()
}

/** 已经跳过的那一段轨迹淡到哪（原版 resolveTrailFade）：4 跳的时间（夹在 0.8..6 秒）里从 1 淡到 0.22。 */
internal fun jumpTrailFade(time: Double, landTime: Double, gap: Double): Float {
    if (!(time > landTime)) return 1f
    val span = (gap * 4).coerceIn(0.8, 6.0)
    val age = ((time - landTime) / span).coerceIn(0.0, 1.0)
    return (0.22 + 0.78 * (1 - age)).toFloat()
}

/** 这一刻路上的“当前行”（原版 resolveBoardRows）：上一行的收尾窗结束（= 这一行开口）就换过来。 */
internal fun jumpBoardRow(windows: List<JumpWindow>, time: Double): Int {
    if (windows.isEmpty()) return -1
    var row = 0
    for (i in 1 until windows.size) {
        if (time >= windows[i - 1].afterEnd) row = i
    }
    return row
}

/** 小人这一刻的状态（原版 resolveRunnerState）：在哪两块之间、滞空走到几成。 */
internal class JumpRunnerState {
    var from = 0
    var to = 0
    var progress = 0.0
    var airborne = false

    fun set(from: Int, to: Int, progress: Double, airborne: Boolean) {
        this.from = from
        this.to = to
        this.progress = progress
        this.airborne = airborne
    }
}

/** 原版 resolveRunnerState：行内的跳由时间表给；跨行那一跳在空中时停在末块上、进度给跨行那一跳。 */
internal fun jumpRunnerState(timeline: JumpTimeline, time: Double, out: JumpRunnerState) {
    val last = timeline.count - 1
    if (last < 0) return out.set(0, 0, 1.0, false)
    if (time < timeline.start) return out.set(0, 0, 0.0, false)
    val shift = timeline.gapShift
    if (shift != null && shift.end > shift.begin && time > shift.begin && time < shift.end) {
        return out.set(last, last, (time - shift.begin) / (shift.end - shift.begin), true)
    }
    for (hop in timeline.hops) {
        if (time < hop.liftTime) return out.set(hop.from, hop.from, 1.0, false)
        if (time < hop.landTime) {
            return out.set(hop.from, hop.to, (time - hop.liftTime) / max(1e-6, hop.landTime - hop.liftTime), true)
        }
    }
    out.set(last, last, 1.0, false)
}

/** 镜头走到哪两站之间（原版 resolveCameraPath）。 */
internal class JumpCameraState {
    var index = 0
    var progress = 0.0
}

internal fun jumpCameraPath(timeline: JumpTimeline, time: Double, out: JumpCameraState) {
    val stops = timeline.cameraTimes
    out.index = 0
    out.progress = 0.0
    if (stops.size <= 1 || time <= stops[0]) return
    for (i in 0 until stops.size - 1) {
        val end = stops[i + 1]
        if (time >= end) continue
        out.index = i
        out.progress = (time - stops[i]) / max(1e-6, end - stops[i])
        return
    }
    out.index = max(0, stops.size - 2)
    out.progress = 1.0
}

/**
 * 跨行那一段镜头的折线权重（原版 resolvePathWeights）：前 [share] 成从起点滑到拐角，之后从拐角滑到终点。
 * 写进 [out]：起点、拐角、终点三个权重。
 */
internal fun jumpPathWeights(phase: Double, share: Double, out: FloatArray) {
    val t = phase.coerceIn(0.0, 1.0)
    val corner = if (share.isFinite()) share.coerceIn(0.0, 1.0) else 0.5
    if (t <= corner) {
        val local = if (corner > 0) t / corner else 0.0
        out[0] = (1 - local).toFloat(); out[1] = local.toFloat(); out[2] = 0f
    } else {
        val local = if (corner < 1) (t - corner) / (1 - corner) else 1.0
        out[0] = 0f; out[1] = (1 - local).toFloat(); out[2] = local.toFloat()
    }
}

/** 一段区间里走到几成（原版 phaseAtGap）：没开始 0、走完 1。 */
private fun phaseOf(begin: Double, end: Double, time: Double): Double = when {
    !(end > begin) || time <= begin -> 0.0
    time >= end -> 1.0
    else -> (time - begin) / (end - begin)
}

private fun airborneIn(begin: Double, end: Double, time: Double): Boolean = end > begin && time > begin && time < end

// ---- 一帧（client.paintFrame） ----

/** 画面上的三行：上一行、当前行、下一行（[JumpPose.rows] 的下标）。 */
internal const val JUMP_PREV = 0
internal const val JUMP_CUR = 1
internal const val JUMP_NEXT = 2

/**
 * 这一帧要画的东西（格）。[rows] 是上一行 / 当前行 / 下一行在路上的行号（没有 −1）；[sink] / [reveal] 按同样的三行分开，
 * 每块一个数；[focusX], [focusY] 是镜头对着的那一点（柱顶高度），[lookDir] 是路往哪边走（1 右上、−1 左上，跨行时插值）；
 * [trailHop] 是当前行已经跳过的那几跳；impact* 是正在散开的落地 / 起跳环（u 是 0..1 的进度）。
 */
internal class JumpPose(capacity: Int) {
    val rows = IntArray(3) { -1 }
    val sink = Array(3) { FloatArray(max(1, capacity)) }
    val reveal = Array(3) { FloatArray(max(1, capacity)) }
    var focusX = 0f
    var focusY = 0f
    var lookDir = 1f
    var runner = false
    var feetX = 0f
    var feetY = 0f
    var groundX = 0f
    var groundY = 0f
    var squash = 1f
    var trailCount = 0
    val trailHop = IntArray(max(1, capacity))
    val trailFade = FloatArray(max(1, capacity))
    val impactCapacity = max(1, capacity) * 2 + 4
    var impactCount = 0
    val impactX = FloatArray(impactCapacity)
    val impactY = FloatArray(impactCapacity)
    val impactU = FloatArray(impactCapacity)
    val impactScale = FloatArray(impactCapacity)
    val impactFade = FloatArray(impactCapacity)
    val impactDust = BooleanArray(impactCapacity)
    internal val runnerState = JumpRunnerState()
    internal val camera = JumpCameraState()
    internal val weights = FloatArray(3)

    fun reset() {
        rows.fill(-1)
        runner = false
        squash = 1f
        trailCount = 0
        impactCount = 0
    }

    internal fun addImpact(time: Double, landTime: Double, x: Float, y: Float, scale: Float, fade: Float, dust: Boolean) {
        if (!landTime.isFinite() || impactCount >= impactCapacity) return
        val u = (time - landTime) / JUMP_IMPACT_SECONDS
        if (u < 0 || u > 1) return
        val i = impactCount++
        impactX[i] = x
        impactY[i] = y
        impactU[i] = u.toFloat()
        impactScale[i] = scale
        impactFade[i] = fade
        impactDust[i] = dust
    }
}

/**
 * 算这一帧（原版 paintFrame 的逻辑部分）。小人：行内的跳、跨行那一跳（先到下一行首块上等开口）、第一行开场前原地一跳；
 * 镜头：跟着时间表的站点走，跨行时沿“末块 → 拐角 → 下一行首块”的折线走；柱子：踩下去、弹回来、后面的行从地里升起；
 * 落地与起跳各踩出一圈环（停够 0.36 秒的才有起跳环）。
 */
internal fun jumpPose(course: JumpCourse, time: Double, pose: JumpPose) {
    pose.reset()
    val rows = course.rows
    val ri = jumpBoardRow(course.windows, time)
    if (ri < 0) return
    val p = course.projection
    val fly = course.config.flySeconds.toDouble()
    val row = rows[ri]
    val prevRow = rows.getOrNull(ri - 1)
    val nextRow = rows.getOrNull(ri + 1)
    pose.rows[JUMP_PREV] = if (prevRow != null) ri - 1 else -1
    pose.rows[JUMP_CUR] = ri
    pose.rows[JUMP_NEXT] = if (nextRow != null) ri + 1 else -1
    val timeline = row.timeline
    val cur = row.units
    val last = cur.lastIndex

    val holdBegin = timeline.shiftHold?.begin ?: Double.NaN
    val holdEnd = timeline.shiftHold?.end ?: Double.NaN
    val waiting = holdEnd > holdBegin && time >= holdBegin && time < holdEnd
    val openingAt = row.window.writeStart
    val debut = ri == 0
    val lead = fly.coerceIn(0.2, 0.9)
    val hasEntrance = debut && openingAt >= lead
    val sinkless = debut && !hasEntrance

    for (role in 0..2) {
        val r = pose.rows[role]
        if (r < 0) continue
        val target = rows[r]
        val count = target.units.size
        for (k in 0 until count) {
            pose.reveal[role][k] = jumpRowReveal(time, target.revealStart, target.revealBudget, k, count)
            pose.sink[role][k] = if (sinkless && role == JUMP_CUR && k == 0) {
                0f
            } else {
                jumpPillarSpring(time, target.timeline.pressTimes[k], target.timeline.releaseTimes[k]) * p.lift
            }
        }
    }

    // 跨行那一跳：没有跨行窗（最后一行）时退回“末块落地 → 收尾”那一截
    val shift = timeline.gapShift
    val shiftBegin = if (shift != null && shift.end > shift.begin) shift.begin else timeline.tailSpan.begin
    val shiftEnd = if (shift != null && shift.end > shift.begin) shift.end else timeline.tailSpan.end
    val shiftAirborne = airborneIn(shiftBegin, shiftEnd, time)
    jumpRunnerState(timeline, time, pose.runnerState)
    val state = pose.runnerState
    val canCross = nextRow != null
    val entranceAirborne = hasEntrance && airborneIn(openingAt - lead, openingAt, time)

    var toRole = JUMP_CUR
    val fromIdx: Int
    val toIdx: Int
    val progress: Double
    var hopping = false
    when {
        shiftAirborne && canCross -> {
            fromIdx = last; toRole = JUMP_NEXT; toIdx = 0; hopping = true
            progress = phaseOf(shiftBegin, shiftEnd, time)
        }
        waiting && canCross -> {
            fromIdx = last; toRole = JUMP_NEXT; toIdx = 0; hopping = true
            progress = 1.0
        }
        entranceAirborne -> {
            fromIdx = 0; toIdx = 0
            progress = phaseOf(openingAt - lead, openingAt, time)
        }
        else -> {
            fromIdx = state.from.coerceIn(0, last); toIdx = state.to.coerceIn(0, last)
            progress = state.progress
        }
    }
    val airborne = shiftAirborne || entranceAirborne || state.airborne
    val toUnits = if (toRole == JUMP_NEXT) nextRow!!.units else cur

    // 镜头
    jumpCameraPath(timeline, time, pose.camera)
    val tailBegin = timeline.cameraTail?.begin ?: Double.NaN
    val tailEnd = timeline.cameraTail?.end ?: Double.NaN
    val tailValid = nextRow != null && tailEnd > tailBegin
    val tailing = tailValid && time >= tailBegin && time < timeline.tailSpan.end
    if ((hopping || tailing) && nextRow != null) {
        val a = cur[last]
        val b = nextRow.units[0]
        val phase = if (tailValid) phaseOf(tailBegin, tailEnd, time) else 1.0
        val first = hypot(row.cornerX - a.tx, row.cornerY - a.ty)
        val second = hypot(b.tx - row.cornerX, b.ty - row.cornerY)
        val share = if (first + second > 1e-6f) (first / (first + second)).toDouble() else 0.5
        jumpPathWeights(phase, share, pose.weights)
        val w = pose.weights
        val sum = max(1e-6f, w[0] + w[1] + w[2])
        pose.focusX = (a.tx * w[0] + row.cornerX * w[1] + b.tx * w[2]) / sum
        pose.focusY = (a.ty * w[0] + row.cornerY * w[1] + b.ty * w[2]) / sum
        pose.lookDir = row.direction + (nextRow.direction - row.direction) * phase.toFloat()
    } else {
        val i = min(pose.camera.index, last)
        val j = min(pose.camera.index + 1, last)
        val k = pose.camera.progress.coerceIn(0.0, 1.0).toFloat()
        pose.focusX = cur[i].tx + (cur[j].tx - cur[i].tx) * k
        pose.focusY = cur[i].ty + (cur[j].ty - cur[i].ty) * k
        pose.lookDir = row.direction.toFloat()
    }

    // 小人：两块柱顶之间插值，空中再抬一个抛物线；脚下那一点跟着柱子一起沉
    val from = cur[fromIdx]
    val to = toUnits[toIdx]
    val t = progress.coerceIn(0.0, 1.0).toFloat()
    val sinkFrom = pose.sink[JUMP_CUR][fromIdx]
    val sinkTo = pose.sink[toRole][toIdx]
    val sink = sinkFrom + (sinkTo - sinkFrom) * t
    pose.runner = true
    pose.groundX = from.tx + (to.tx - from.tx) * t
    pose.groundY = from.ty + (to.ty - from.ty) * t + sink
    pose.feetX = pose.groundX
    pose.feetY = pose.groundY - jumpArcLift(course) * sin(PI.toFloat() * t)
    pose.squash = if (airborne) jumpHopSquash(t) else 1f

    // 当前行已经跳过的轨迹
    timeline.hops.forEachIndexed { index, hop ->
        if (hop.to > last || time < hop.landTime || pose.trailCount >= pose.trailHop.size) return@forEachIndexed
        pose.trailHop[pose.trailCount] = index
        pose.trailFade[pose.trailCount] = jumpTrailFade(time, hop.landTime, timeline.gap)
        pose.trailCount++
    }

    // 落地环 / 起跳环
    val curSink = pose.sink[JUMP_CUR]
    for (hop in timeline.hops) {
        if (hop.to <= last) {
            val u = cur[hop.to]
            pose.addImpact(time, hop.landTime, u.tx, u.ty + curSink[hop.to], 1f, 1f, true)
        }
        if (hop.from <= last && hop.liftTime - hop.start >= JUMP_IMPACT_SECONDS) {
            val u = cur[hop.from]
            pose.addImpact(time, hop.liftTime, u.tx, u.ty + curSink[hop.from], TAKEOFF_RING_SCALE, TAKEOFF_RING_FADE, false)
        }
    }
    if (waiting && nextRow != null) {
        val u = nextRow.units[0]
        pose.addImpact(time, holdBegin, u.tx, u.ty + pose.sink[JUMP_NEXT][0], 1f, 1f, true)
    }
    val crossedAt = prevRow?.timeline?.shiftLand?.takeIf { it.isFinite() } ?: openingAt
    if (!sinkless && (ri > 0 || hasEntrance)) {
        pose.addImpact(time, crossedAt, cur[0].tx, cur[0].ty + curSink[0], 1f, 1f, true)
    }
    val lastLanding = timeline.landings[last]
    if (shiftBegin - lastLanding >= JUMP_IMPACT_SECONDS) {
        pose.addImpact(time, shiftBegin, cur[last].tx, cur[last].ty + curSink[last], TAKEOFF_RING_SCALE, TAKEOFF_RING_FADE, false)
    }
}

/** 跳跃高度（格）：旋钮值 × 竖直一格在屏幕上的高度。 */
internal fun jumpArcLift(course: JumpCourse): Float = course.config.arc * course.projection.lift

/** 小人的尺寸（格）：身高 2.6 格 × 尺寸旋钮，乘竖直缩短。 */
internal fun jumpRunnerCells(course: JumpCourse): Float = course.projection.lift * JUMP_RUNNER_RATIO * JUMP_RUNNER_SCALE

/**
 * 减少动态效果的静帧：只摆当前句（句间按时刻取路上的当前行），柱子全部立好、不沉，小人站在唱到的那一块上
 * （没有逐字时间就站在第一块），没有跳、没有环、没有轨迹。
 */
internal fun jumpStillPose(course: JumpCourse, lineIndex: Int, time: Double, pose: JumpPose) {
    pose.reset()
    if (course.rows.isEmpty()) return
    var ri = course.rowOfLine.getOrElse(lineIndex) { -1 }
    if (ri < 0) ri = jumpBoardRow(course.windows, time)
    pose.rows[JUMP_CUR] = ri
    val row = course.rows[ri]
    val units = row.units
    for (k in units.indices) {
        pose.reveal[JUMP_CUR][k] = 1f
        pose.sink[JUMP_CUR][k] = 0f
    }
    val at = jumpStillUnit(row, time)
    val unit = units[at]
    pose.runner = true
    pose.feetX = unit.tx
    pose.feetY = unit.ty
    pose.groundX = unit.tx
    pose.groundY = unit.ty
    pose.focusX = unit.tx
    pose.focusY = unit.ty
    pose.lookDir = row.direction.toFloat()
}

/** 静帧里小人站哪一块：有逐字时间时是最后一块已经开唱的字块（不算歇脚柱），否则第一块。 */
internal fun jumpStillUnit(row: JumpRow, time: Double): Int {
    if (!row.hasWords) return 0
    var at = 0
    for (k in row.units.indices) {
        if (row.units[k].rest) continue
        if (row.timeline.landings[k] <= time + 1e-9) at = k
    }
    return at
}

/**
 * 静帧取景：把当前行（柱子、悬浮的字、站在柱顶的小人）整个框进 [width] × [height]，横向留一成二、纵向留一成六，
 * 格子不超过 [cellCap]（动起来时那一屏的格子，静帧不比它更大）。返回 [格子, 原点 x, 原点 y]（像素，相对这块区域的左上角）。
 */
internal fun jumpStillFrame(row: JumpRow, runnerCells: Float, width: Float, height: Float, cellCap: Float, runnerAt: Int): FloatArray {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (unit in row.units) {
        val box = unit.box
        val cylinder = unit.cylinder
        if (box != null) {
            minX = min(minX, unit.sx + box.minX); maxX = max(maxX, unit.sx + box.maxX)
            minY = min(minY, unit.sy + box.minY); maxY = max(maxY, unit.sy + box.maxY)
        } else if (cylinder != null) {
            minX = min(minX, unit.sx - cylinder.rx); maxX = max(maxX, unit.sx + cylinder.rx)
            minY = min(minY, unit.ty - cylinder.ry); maxY = max(maxY, unit.sy + cylinder.ry)
        }
    }
    val stand = row.units.getOrNull(runnerAt)
    if (stand != null) {
        minY = min(minY, stand.ty - runnerCells)
        minX = min(minX, stand.tx - runnerCells * 0.45f)
        maxX = max(maxX, stand.tx + runnerCells * 0.45f)
    }
    if (minX > maxX) return floatArrayOf(max(1f, cellCap), width / 2f, height / 2f)
    val bw = max(0.5f, maxX - minX)
    val bh = max(0.5f, maxY - minY)
    val cell = max(1f, min(cellCap, min(width * 0.88f / bw, height * 0.84f / bh)))
    return floatArrayOf(cell, width / 2f - (minX + maxX) / 2f * cell, height / 2f - (minY + maxY) / 2f * cell)
}

// ---- 译文的那一句（jumpTranslation.pickTranslationLine / jumpTimeline.resolveTranslationReveal） ----

/**
 * 这一刻译文属于哪一句：优先宿主的当前句（它有能读的副行、也已经开口）；取不到（句间 −1、这句没有副行）就按时刻找
 * 最后一句已经开口的，那一句没有副行就不显示（−1）。[starts] 每句的起点（毫秒），[readable] 每句的副行有没有能读的字。
 */
internal fun jumpTranslationLine(starts: LongArray, readable: BooleanArray, index: Int, timeMs: Long): Int {
    if (index in starts.indices && readable[index] && timeMs >= starts[index]) return index
    for (cursor in starts.indices.reversed()) {
        if (timeMs < starts[cursor]) continue
        return if (readable[cursor]) cursor else -1
    }
    return -1
}

/** 译文换句时淡入（0.22 秒，1 − (1 − t)^2.2）。 */
internal fun jumpTranslationReveal(time: Double, start: Double): Float {
    if (!start.isFinite()) return 1f
    if (time <= start) return 0f
    val t = min(1.0, (time - start) / 0.22)
    return (1.0 - (1.0 - t).pow(2.2)).toFloat()
}
