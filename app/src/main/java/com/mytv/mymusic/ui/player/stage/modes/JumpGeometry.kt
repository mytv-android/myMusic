package com.mytv.mymusic.ui.player.stage.modes

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 跳一跳的几何（照 visualizer-jump 1.2.0 的 jumpProject / jumpShape / jumpFrame / jumpTranslation）：纯计算，不碰 android.*。
 *
 * 一切都在“格”里算：一格 = 边长 1 的立方体，顶面菱形在屏幕上宽 1 格。投影、柱子的三个面、贴面字的矩阵都是格的线性函数，
 * 画的时候整体乘上 [JumpFrame.cellPx] 就是像素；只有描边粗细、环的最小半径这类原版写死像素的量由画的那一层另给。
 */

/** 字贴在柱子的哪一面（原模组 textFace）。 */
internal enum class JumpFace(val key: String) {
    Top("top"),
    Front("front"),
    Side("side"),
    Above("above"),
    ;

    companion object {
        fun of(key: String): JumpFace = entries.firstOrNull { it.key == key } ?: Top
    }
}

/**
 * 方位 45°、俯角 [pitchDeg] 的正交投影，一格宽 1。世界 +X 往左上、+Y 往右上（各走半格、抬 sinθ/2），+Z 往上抬 cosθ/√2；
 * 屏幕 y 向下为正。正交是仿射的，任何一个面上的字都能用一个 2D 矩阵贴上去（原版 jumpProject.createProjection）。
 */
internal class JumpProjection(pitchDeg: Float) {
    val pitch = pitchDeg.coerceIn(5f, 85f)
    val sin = sin(pitch * JUMP_DEG)
    val cos = cos(pitch * JUMP_DEG)

    /** 竖直 1 格在屏幕上抬多少（√2 来自 45° 方位的对角缩短）。 */
    val lift = cos / JUMP_SQRT2

    fun x(wx: Float, wy: Float): Float = -0.5f * wx + 0.5f * wy

    fun y(wx: Float, wy: Float, wz: Float): Float = -0.5f * sin * wx - 0.5f * sin * wy - lift * wz

    /** 沿 [along]（"x" / "y"）那条世界轴走一格的屏幕向量。 */
    fun axisX(alongX: Boolean): Float = if (alongX) -0.5f else 0.5f
    fun axisY(): Float = -0.5f * sin
}

// ---- 柱子的尺寸（jumpShape） ----

internal const val JUMP_MIN_UNIT_CELLS = 0.8f
internal const val JUMP_FACE_SPAN_CELLS = 1.35f
internal const val JUMP_REST_DIAMETER = 1.9f
private const val MIN_BLOCK_LEN = 0.6f
private const val MAX_BLOCK_LEN = 12f
private const val MIN_WALL = 0.8f
private const val MAX_WALL = 12f
internal const val JUMP_TEXT_FILL = 0.78f
private const val FACE_FILL_ALONG = 0.4f
private const val FACE_FILL_ACROSS = 0.76f
private const val SIDE_PILLAR_FLOOR = 0.35f
private const val SIDE_PILLAR_SCALE = 1.3f
private const val SIDE_PILLAR_CAP = 2.6f
private const val SIDE_STEM = 1.8f

/** 一根柱子：沿路的块长、柱身厚度（= 顶面横向）、柱高，都是格。 */
internal class JumpShape(val len: Float, val depth: Float, val height: Float)

/** 这一屏的柱高：旋钮值；字贴侧面时放宽一档，矮柱子也写得下字。 */
internal fun jumpPillarHeight(face: JumpFace, heightCells: Float): Float {
    val h = max(0.2f, heightCells)
    return if (face == JumpFace.Side) min(SIDE_PILLAR_CAP, max(SIDE_PILLAR_FLOOR, h * SIDE_PILLAR_SCALE)) else h
}

/** 字盒的长宽比：字宽（em）÷ 字高（1 em）。 */
internal fun jumpTextAspect(span: Float, glyph: Float = 1f): Float =
    max(JUMP_MIN_UNIT_CELLS, span) / glyph.coerceIn(0.4f, 1.4f)

/**
 * 一块柱子的形状：字顺着贴的那一面排，那个方向跟着字长，别的方向不动。顶面 / 正面 / 上方是块长随字宽，
 * 侧面是端头那面墙随字宽、块长固定成一截柱身。[height] 已经是 [jumpPillarHeight] 的结果。
 */
internal fun jumpBlockShape(face: JumpFace, span: Float, height: Float, glyph: Float = 1f): JumpShape {
    val aspect = jumpTextAspect(span, glyph)
    val h = max(0.2f, height)
    return if (face == JumpFace.Side) {
        val wall = (aspect / FACE_FILL_ALONG).coerceIn(MIN_WALL, MAX_WALL)
        JumpShape(SIDE_STEM, wall, h)
    } else {
        val len = (max(JUMP_MIN_UNIT_CELLS, aspect) / FACE_FILL_ALONG).coerceIn(MIN_BLOCK_LEN, MAX_BLOCK_LEN)
        val across = (h / FACE_FILL_ACROSS).coerceIn(min(JUMP_FACE_SPAN_CELLS, MIN_WALL), MAX_WALL)
        JumpShape(len, across, h)
    }
}

/** 柱顶那块字有多高（格）：译文的字号以它为尺子（原版 resolvePillarTypePx 除以格宽）。 */
internal fun jumpPillarTypeCells(face: JumpFace, height: Float): Float {
    val span = if (face == JumpFace.Side) height * FACE_FILL_ALONG else 1f
    val shape = jumpBlockShape(face, span, height)
    val roomW = (if (face == JumpFace.Side) shape.depth else shape.len) * JUMP_TEXT_FILL
    val roomH = (if (face == JumpFace.Side) shape.height else shape.depth) * JUMP_TEXT_FILL
    return max(0f, min(roomW, roomH))
}

// ---- 柱子的三个面与贴面字（jumpProject.boxFaces / placeText） ----

/**
 * 一根方柱在屏幕上的样子（格，原点是柱底中心）：顶面、正面（沿路长条面）、侧面（端头）三个四边形，各 8 个数（4 个点）；
 * 贴面字盒宽 [labelW]、高 [labelH]（字号就是字盒高），本地字盒 → 屏幕的仿射矩阵 [label]（a b c d e f：x' = a·x + c·y + e，y' = b·x + d·y + f）；
 * [minX]..[maxY] 是外接框（含悬浮的字），静帧取景用。
 */
internal class JumpBoxGeometry(
    val top: FloatArray,
    val front: FloatArray,
    val side: FloatArray,
    val labelW: Float,
    val labelH: Float,
    val label: FloatArray,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
)

/** 歇脚的圆柱（格，原点是柱底中心）：顶面椭圆的半轴 [rx]、[ry] 和圆心高度 [cy]（负数）。 */
internal class JumpCylinderGeometry(val rx: Float, val ry: Float, val cy: Float)

/** 圆柱的几何：世界里直径 d 的圆投成轴对齐的椭圆，横半轴 d·√2/4、纵半轴再乘 sinθ。 */
internal fun jumpCylinder(projection: JumpProjection, diameter: Float, height: Float): JumpCylinderGeometry {
    val rx = diameter * JUMP_SQRT2 / 4f
    return JumpCylinderGeometry(rx, rx * projection.sin, -projection.lift * height)
}

/**
 * 一根方柱的三个面和贴面字。盒子局部坐标：沿路 u ∈ [0, len]，横向 w ∈ [0, depth]，高 z ∈ [0, height]；
 * [alongX] 说明沿路的是世界 X（奇数行）还是 Y（偶数行）。字盒按 [aspect] 铺进旋钮选的那一面（[JUMP_TEXT_FILL] 成），
 * 面背着镜头（矩阵行列式为负）时只翻横轴，字心永远落在面心（四个角的平均）。
 */
internal fun jumpBox(projection: JumpProjection, alongX: Boolean, shape: JumpShape, face: JumpFace, aspect: Float): JumpBoxGeometry {
    val len = max(0.1f, shape.len)
    val depth = max(0.1f, shape.depth)
    val height = max(0f, shape.height)
    val cx = boxX(projection, alongX, len / 2f, depth / 2f)
    val cy = boxY(projection, alongX, len / 2f, depth / 2f, 0f)
    fun quad(vararg p: Float): FloatArray {
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val u = p[i * 3]
            val w = p[i * 3 + 1]
            val z = p[i * 3 + 2]
            out[i * 2] = boxX(projection, alongX, u, w) - cx
            out[i * 2 + 1] = boxY(projection, alongX, u, w, z) - cy
        }
        return out
    }
    val top = quad(0f, 0f, height, len, 0f, height, len, depth, height, 0f, depth, height)
    val front = quad(0f, 0f, height, len, 0f, height, len, 0f, 0f, 0f, 0f, 0f)
    val side = quad(0f, 0f, height, 0f, depth, height, 0f, depth, 0f, 0f, 0f, 0f)

    // 沿路一格、横向一格的屏幕向量；竖直面上的“下”是柱顶到柱底
    val alongVx = projection.axisX(alongX)
    val alongVy = projection.axisY()
    val crossVx = projection.axisX(!alongX)
    val crossVy = projection.axisY()
    val downY = projection.lift

    val label = FloatArray(6)
    val labelW: Float
    val labelH: Float
    if (face == JumpFace.Above) {
        // 悬浮：不错切，屏幕正立浮在柱顶中心之上，底边往上留四分之一字高
        val room = jumpTypeBox(len * hypot(alongVx, alongVy), depth, aspect)
        labelW = room[0]
        labelH = room[1]
        val c = centerOf(top)
        label[0] = 1f
        label[3] = 1f
        label[4] = c[0] - labelW / 2f
        label[5] = c[1] - labelH * 1.25f
    } else {
        // 面上的二维坐标系：u 是字的横排方向（每格），v 是字的“下”
        val ux: Float
        val uy: Float
        val vx: Float
        val vy: Float
        val uSpan: Float
        val vSpan: Float
        val quad: FloatArray
        when (face) {
            JumpFace.Front -> {
                ux = alongVx; uy = alongVy; vx = 0f; vy = downY; uSpan = len; vSpan = height; quad = front
            }
            JumpFace.Side -> {
                ux = -crossVx; uy = -crossVy; vx = 0f; vy = downY; uSpan = depth; vSpan = height; quad = side
            }
            else -> {
                ux = alongVx; uy = alongVy; vx = -crossVx; vy = -crossVy; uSpan = len; vSpan = depth; quad = top
            }
        }
        val room = jumpTypeBox(uSpan, vSpan, aspect)
        labelW = room[0]
        labelH = room[1]
        val sign = if (ux * vy - uy * vx < 0f) -1f else 1f
        val c = centerOf(quad)
        label[0] = sign * ux
        label[1] = sign * uy
        label[2] = vx
        label[3] = vy
        label[4] = c[0] - (sign * ux * labelW / 2f + vx * labelH / 2f)
        label[5] = c[1] - (sign * uy * labelW / 2f + vy * labelH / 2f)
    }

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (q in arrayOf(top, front, side)) {
        for (i in 0 until 4) {
            minX = min(minX, q[i * 2]); maxX = max(maxX, q[i * 2])
            minY = min(minY, q[i * 2 + 1]); maxY = max(maxY, q[i * 2 + 1])
        }
    }
    if (face == JumpFace.Above) {
        minX = min(minX, label[4]); maxX = max(maxX, label[4] + labelW)
        minY = min(minY, label[5])
    }
    return JumpBoxGeometry(top, front, side, labelW, labelH, label, minX, minY, maxX, maxY)
}

/**
 * 字盒铺进面的窗（格）：先按长宽比顶满窗的 [JUMP_TEXT_FILL] 成（两条边取先顶满的那条），出框就整体缩回，
 * 只缩一条边会把字压扁。返回 [宽, 高]，字号就是高。
 */
internal fun jumpTypeBox(roomWidth: Float, roomHeight: Float, aspect: Float): FloatArray {
    val a = max(0.05f, aspect)
    val w = max(0.05f, roomWidth) * JUMP_TEXT_FILL
    val h = max(0.05f, roomHeight) * JUMP_TEXT_FILL
    val baseW: Float
    val baseH: Float
    if (w / a <= h) {
        baseW = w; baseH = w / a
    } else {
        baseW = h * a; baseH = h
    }
    val fit = min(1f, min(w / baseW, h / baseH))
    return floatArrayOf(baseW * fit, baseH * fit)
}

private fun boxX(p: JumpProjection, alongX: Boolean, u: Float, w: Float): Float =
    if (alongX) p.x(u, w) else p.x(w, u)

private fun boxY(p: JumpProjection, alongX: Boolean, u: Float, w: Float, z: Float): Float =
    if (alongX) p.y(u, w, z) else p.y(w, u, z)

private fun centerOf(quad: FloatArray): FloatArray {
    var x = 0f
    var y = 0f
    for (i in 0 until 4) {
        x += quad[i * 2]
        y += quad[i * 2 + 1]
    }
    return floatArrayOf(x / 4f, y / 4f)
}

// ---- 取景（jumpFrame） ----

/** 取景的参照俯角：俯角往下调时格子封顶在这一档，不再把画面放大。 */
internal const val JUMP_REFERENCE_PITCH = 34f
private const val LANE_WIDTH_SHARE = 0.82f
private const val FIT_SHARE = 0.92f
private const val FRAME_PAD_CELLS = 0.7f

/** 一行最多几块：再长就把每块字数往粗里放宽。 */
internal const val JUMP_MAX_CHUNKS_PER_ROW = 5

/** 每块字数放宽到这么粗就停。 */
internal const val JUMP_MAX_CHUNK_CHARS = 10
private const val BUDGET_ROW_CELLS = JUMP_MAX_CHUNKS_PER_ROW + 2

/** 小人身高（格）÷ 小人尺寸。 */
internal const val JUMP_RUNNER_RATIO = 2.6f

/** 取景要用的几何（都是格）。 */
internal class JumpFrameInput(
    val face: JumpFace,
    val heightCells: Float,
    val widestDepth: Float,
    val widestCross: Float,
    val spacing: Float,
    val runnerScale: Float,
    val arc: Float,
)

/**
 * 一个俯角下的取景预算（格）：小人脚下（上一行 + 两段台阶 + 柱顶）与头顶（下一行 + 柱顶的字 + 人和跳跃高度）
 * 各要装下多少，以及三行一右一左折起来横着占多宽。按块数上限算，短句长句同一把尺子，换行不呼吸。
 */
internal class JumpBudget(val below: Float, val above: Float, val crown: Float, val crownTop: Float, val roadSpread: Float)

internal fun jumpBudget(pitchDeg: Float, input: JumpFrameInput): JumpBudget {
    val pitch = pitchDeg.coerceIn(5f, 85f)
    val s = sin(pitch * JUMP_DEG)
    val c = cos(pitch * JUMP_DEG)
    val widestDepth = max(0.1f, input.widestDepth)
    val widestCross = max(0.1f, input.widestCross)
    val rise = s / 2f
    val climb = (BUDGET_ROW_CELLS + widestDepth) * rise
    val lane = (BUDGET_ROW_CELLS + max(0.05f, input.spacing)) * rise
    val pillar = max(0.2f, input.heightCells) * c / JUMP_SQRT2
    val crown = if (input.face == JumpFace.Side) pillar + widestDepth * c / JUMP_SQRT2 else JUMP_TEXT_FILL * JUMP_FACE_SPAN_CELLS
    val runner = JUMP_RUNNER_RATIO * max(0.1f, input.runnerScale) * c / JUMP_SQRT2
    val arc = max(0f, input.arc) * c / JUMP_SQRT2
    val crownTop = crown + max(JUMP_TEXT_FILL, runner + arc) + FRAME_PAD_CELLS / 2f
    val below = 2f * climb + lane + crown + FRAME_PAD_CELLS / 2f
    val above = 2f * climb + lane + crownTop + FRAME_PAD_CELLS / 2f
    val cross = (widestDepth + widestCross) / JUMP_SQRT2
    val spread = max(
        max(3f * BUDGET_ROW_CELLS / JUMP_SQRT2, (BUDGET_ROW_CELLS + 2f * widestDepth) / JUMP_SQRT2),
        (BUDGET_ROW_CELLS + widestDepth) * rise + cross,
    )
    return JumpBudget(below, above, crown, crownTop, spread)
}

/** 这块地方（像素）在这份预算下格子最大多大：纵向两份预算、横向路宽，再压一层“不超过半屏高、三分之一屏宽”。 */
internal fun jumpFitCell(width: Float, height: Float, budget: JumpBudget): Float {
    val w = max(1f, width)
    val h = max(1f, height)
    return min(
        min(h * FIT_SHARE / (budget.below + budget.above), w * LANE_WIDTH_SHARE / max(1f, budget.roadSpread)),
        min(h / 2.8f, w / 3f),
    )
}

/** 一屏的取景：一格多少像素、小人脚下那块柱顶离安全区底边多高（像素）。 */
internal class JumpFrame(val cellPx: Float, val anchorBelowPx: Float)

/**
 * 取景（原版 resolveJumpFrame，镜头距离固定 1）：格子取“当前俯角”和“参照俯角”两份预算里小的那一个（俯角往下调不放大画面），
 * 不小于 [floorPx]（矮舞台也不把字缩没）。小人钉在离底边 below 格处。
 *
 * 移植补的一条：原版的地板一旦顶上去（桌面姿态那种又宽又矮的舞台），整份预算就装不下了，小人会被推出画面顶；
 * 这时按预算的上下比例摆，并且头顶至少留出柱顶的字、人和跳跃高度，格子大到连这一截都装不下时再缩回来。
 */
internal fun jumpFrame(width: Float, height: Float, pitchDeg: Float, input: JumpFrameInput, floorPx: Float): JumpFrame {
    val budget = jumpBudget(pitchDeg, input)
    val reference = jumpBudget(JUMP_REFERENCE_PITCH, input)
    val fit = min(jumpFitCell(width, height, budget), jumpFitCell(width, height, reference))
    val need = budget.crown + budget.crownTop
    val cell = min(max(floorPx, fit), max(1f, height) * 0.95f / need).coerceAtLeast(1f)
    val ideal = budget.below * cell
    val anchor = if ((budget.below + budget.above) * cell <= height) {
        ideal
    } else {
        val share = height * budget.below / (budget.below + budget.above)
        share.coerceIn(budget.crown * cell, max(budget.crown * cell, height - budget.crownTop * cell))
    }
    return JumpFrame(cell, anchor)
}

// ---- 译文（jumpTranslation） ----

/** 译文那一层显示的到底有没有能读的字：纯标记行（//、●●●）不算。 */
internal fun jumpReadable(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (Character.isLetterOrDigit(cp)) return true
        i += Character.charCount(cp)
    }
    return false
}

/**
 * 一句译文按“一行大概放几个字宽”折行：中日韩字之间随便断，拉丁只在词间（和连字符、撇号后）断，
 * 一个词自己超宽就独占一行。字宽按估算（CJK 1、拉丁 0.55、空白 0.35），原版就是这么估的。
 */
internal fun jumpWrapRows(text: String, capacity: Float): List<String> {
    val source = collapseSpaces(text)
    if (source.isEmpty()) return emptyList()
    val limit = max(1f, capacity)
    val tokens = ArrayList<String>()
    val token = StringBuilder()
    var i = 0
    while (i < source.length) {
        val cp = source.codePointAt(i)
        val n = Character.charCount(cp)
        if (jumpIsSpace(cp)) {
            if (token.isNotEmpty()) tokens += token.toString()
            token.setLength(0)
            tokens += " "
        } else {
            val last = if (token.isNotEmpty()) token.codePointBefore(token.length) else -1
            if (token.isNotEmpty() && (jumpIsWide(cp) || (last >= 0 && jumpIsLatinJoint(last)))) {
                tokens += token.toString()
                token.setLength(0)
            }
            token.appendCodePoint(cp)
        }
        i += n
    }
    if (token.isNotEmpty()) tokens += token.toString()
    val rows = ArrayList<String>()
    val row = StringBuilder()
    var width = 0f
    for (piece in tokens) {
        if (piece == " ") {
            if (row.isNotEmpty()) row.append(' ')
            continue
        }
        val w = estimateWidth(piece)
        if (row.isNotEmpty() && width + w > limit) {
            rows += row.toString().trim()
            row.setLength(0)
            width = 0f
        }
        row.append(piece)
        width += w
    }
    if (row.isNotBlank()) rows += row.toString().trim()
    return rows
}

/** 排好的一块译文：每行文字、字号、行高（像素）、最宽一行的实际宽度、整块高。 */
internal class JumpTranslationBlock(
    val rows: List<String>,
    val sizePx: Float,
    val lineHeight: Float,
    val width: Float,
    val height: Float,
)

/**
 * 译文字号：从目标字号起每档缩 6%，最多 24 档，取第一个“折出来不超过 [maxRows] 行、也不超过 [roomHeight] 高”的；
 * 不低于 [minSize]（柱顶那块字的五成五，再小不如不显示）。[measure] 量一行字在某字号下的宽度。
 * 段落（宿主“都显示”时翻译和罗马音各一段）各自折行再叠起来。
 */
internal fun jumpTranslationBlock(
    paragraphs: List<String>,
    room: Float,
    roomHeight: Float,
    target: Float,
    minSize: Float,
    maxRows: Int,
    lineHeightRatio: Float,
    minFloor: Float,
    measure: (String, Float) -> Float,
): JumpTranslationBlock? {
    val parts = paragraphs.map { collapseSpaces(it) }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val width = max(1f, room)
    val floor = max(minFloor, min(target * 0.95f, minSize))
    var best: JumpTranslationBlock? = null
    for (step in 0..24) {
        val size = max(floor, target * 0.94f.pow(step))
        val rows = parts.flatMap { jumpWrapRows(it, width / max(1f, size)) }
        val widest = rows.maxOfOrNull { measure(it, size) } ?: 0f
        val count = max(1, rows.size)
        val candidate = JumpTranslationBlock(rows, size, size * lineHeightRatio, max(1f, widest), count * size * lineHeightRatio)
        best = candidate
        if (count <= maxRows && candidate.height <= roomHeight) break
        if (size <= floor + 1e-4f) break
    }
    return best
}

/** 俯角 → 译文的变形量：(1 − sinθ) ÷ (1 − sin34°)，34° 正好是 1，最多 2.5。俯角越大越像正对镜头。 */
internal fun jumpTiltAmount(pitchDeg: Float): Float {
    val p = pitchDeg.coerceIn(1f, 89f)
    val reach = 1f - sin(p * JUMP_DEG)
    val base = max(1e-6f, 1f - sin(JUMP_REFERENCE_PITCH * JUMP_DEG))
    return (reach / base).coerceIn(0f, 2.5f)
}

/**
 * 逐字透视那一档里一行字的仿射部分：纵向压扁（参照俯角下是 cos34°）、往右上侧倾（0.18），
 * 靠上的行按离块底多远（[offsetFromBottom]）再收一点（最多收到 0.78）。原版是逐字的 matrix3d，还带一点真透视（近大远小，
 * 纵深按画面高的 1.1 倍算），单个字高里只差百分之二三，这里略去，换成每行一个仿射矩阵：以这一行的底边为原点（y 向上为负），
 * x' = x − lean·y，y' = squash·shrink·y。返回 [lean, squash × shrink]。
 */
internal fun jumpRowTilt(pitchDeg: Float, offsetFromBottom: Float, depth: Float): FloatArray {
    val amount = jumpTiltAmount(pitchDeg)
    val squash = 1f - (1f - cos(JUMP_REFERENCE_PITCH * JUMP_DEG)) * amount
    val lean = 0.18f * amount
    val floor = max(0.78f, 1f - 0.1f * amount)
    val shrink = max(floor, 1f - (max(0f, offsetFromBottom) / max(1f, depth)) * 0.9f * amount)
    return floatArrayOf(lean, squash * shrink)
}

/** 量过之后实在放不下才收：横向最多收到 0.86（再收字就变形），纵向按高度收。返回 [scaleX, scaleY]。 */
internal fun jumpTranslationFit(width: Float, room: Float, height: Float, roomHeight: Float): FloatArray =
    floatArrayOf(max(0.86f, min(1f, room / max(1f, width))), min(1f, roomHeight / max(1f, height)))

// ---- 小工具 ----

internal const val JUMP_DEG = (PI / 180.0).toFloat()
internal val JUMP_SQRT2 = sqrt(2f)

/** 空白（含全角空格）。 */
internal fun jumpIsSpace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

/** 中日韩、谚文、全角：一个字一格（和原版 WIDE_CHAR 同一组区间）。 */
internal fun jumpIsWide(cp: Int): Boolean =
    cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7AF || cp in 0xF900..0xFAFF ||
        cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6

/** 英文词里可以折断的接缝：连字符、撇号。 */
internal fun jumpIsLatinJoint(cp: Int): Boolean =
    cp == '-'.code || cp == 0x2010 || cp == 0x2011 || cp == 0x2013 || cp == 0x2014 || cp == '\''.code || cp == 0x2019

private fun estimateWidth(text: String): Float {
    var w = 0f
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        w += if (jumpIsSpace(cp)) 0.35f else if (jumpIsWide(cp)) 1f else 0.55f
        i += Character.charCount(cp)
    }
    return w
}

private fun collapseSpaces(text: String): String {
    val out = StringBuilder(text.length)
    var space = false
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (jumpIsSpace(cp)) {
            space = true
        } else {
            if (space && out.isNotEmpty()) out.append(' ')
            space = false
            out.appendCodePoint(cp)
        }
        i += Character.charCount(cp)
    }
    return out.toString()
}

/**
 * 柱面的不透明度（原版 resolveFaceOpacity）：[own] 是配色给这一面的浓度（顶面 / 正面 0.17、背光面 0.08、棱线 0.42），
 * [mix] 是旋钮（原模组 pillarOpacity 等，默认 0.8）。own·mix + (1 − own)·mix³：旋钮拉到 1 就是实心，拉到 0 就不画。
 */
internal fun jumpFaceOpacity(mix: Float, own: Float): Float {
    val t = mix.coerceIn(0f, 1f)
    val base = own.coerceIn(0f, 1f)
    return (base * t + (1f - base) * t * t * t).coerceIn(0f, 1f)
}

/** CSS 的模糊半径换成 Android 阴影半径：CSS 的 σ = b/2，Android 的 σ = 0.57735·r + 0.5。 */
internal fun jumpShadowRadius(blur: Float): Float = max(0.5f, (blur / 2f - 0.5f) / 0.57735f)
