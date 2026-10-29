/*
 * 海报墙几何。版式机制改编自 chthollyphile/folia-major 的 Lattice（AGPL-3.0）：
 * https://github.com/chthollyphile/folia-major
 *
 * 12×8 格的块，块内 12 个海报位；4 个手画模板 × 4 种镜像，模板按块坐标线性步进、镜像按块坐标哈希，
 * 队列第 i 首占第 i 个位；一格放大到 6×6 时只重排它所在的块（重排表见 WallReflowTable）。
 * 纯 Kotlin，不依赖 Compose / Android，方便单测。
 */
package com.mytv.mymusic.ui.poster

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 块内一个海报位，单位是格：x 在 0..12、y 在 0..8 之内。 */
internal data class UnitRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h

    /** 短边格数，决定圆角档位。 */
    val shortSide: Int get() = min(w, h)
}

/** 墙面坐标（dp）里的矩形，宽高已经扣掉缝。 */
internal data class WallRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    val centerX: Float get() = x + w / 2
    val centerY: Float get() = y + h / 2
}

/** 墙面坐标里的一点。镜头位置就是屏幕左上角落在墙面上的坐标。 */
internal data class WallPoint(val x: Float, val y: Float)

/** 第 (bx, by) 块里的第 slot 个位。块坐标可以为负。 */
internal data class SlotPos(val bx: Int, val by: Int, val slot: Int)

/** 镜头左上角能去的范围，两端都含。 */
internal data class WallBounds(val x0: Float, val x1: Float, val y0: Float, val y1: Float)

/** 需要画的块范围，两端都含。 */
internal data class BlockRange(val bx0: Int, val bx1: Int, val by0: Int, val by1: Int)

/** 墙上的一格：位置、坐的第几首（占位格也是 >= 0）、块内格子和墙面矩形。 */
internal data class WallTile(
    val pos: SlotPos,
    val song: Int,
    val cells: UnitRect,
    val rect: WallRect,
    val grown: Boolean,
)

/**
 * 墙面视口。[width] × [height] 是整块屏幕；[insetTop] / [insetBottom] 是浮层（顶栏、底部按钮）让出的高度，
 * 中间才是放大卡要落进去的可用区。原型取值：手机 360×800、上 84 下 92；平板 1280×800 不让（歌单模式底部 88）；
 * 电视 960×540、上 80（顶部一排控件）。
 */
internal data class WallViewport(
    val width: Float,
    val height: Float,
    val insetTop: Float = 0f,
    val insetBottom: Float = 0f,
) {
    val usableHeight: Float get() = height - insetTop - insetBottom
}

/**
 * 每种设备的格距和圆角（dp）。格距 = 一格 + 一道缝，放大卡 6 格要放得进屏幕：
 * 手机 6×56−4 = 332，平板 6×102−6 = 606，电视 6×76−5 = 451（顶部让出 80 后正好放下）。
 * 圆角三档：短边 2 格 / 短边 3 格以上 / 放大卡，取原型 TILE_STYLE；电视焦点时再加 [FOCUS_RADIUS_BOOST]。
 * [feedRows] 是听歌模式带子的高度（块），[safeMargin] 是方向键焦点的安全区内缩。
 */
internal enum class WallMetrics(
    val pitch: Float,
    val gap: Float,
    val feedRows: Int,
    val radiusSmall: Float,
    val radiusLarge: Float,
    val radiusGrown: Float,
    val safeMargin: Float,
) {
    Phone(pitch = 56f, gap = 4f, feedRows = 2, radiusSmall = 8f, radiusLarge = 10f, radiusGrown = 24f, safeMargin = 24f),
    Tablet(pitch = 102f, gap = 6f, feedRows = 1, radiusSmall = 12f, radiusLarge = 16f, radiusGrown = 28f, safeMargin = 24f),
    Tv(pitch = 76f, gap = 5f, feedRows = 1, radiusSmall = 10f, radiusLarge = 14f, radiusGrown = 24f, safeMargin = 40f);

    val blockWidth: Float get() = WallLayout.BLOCK_W * pitch
    val blockHeight: Float get() = WallLayout.BLOCK_H * pitch

    /** 放大卡边长。 */
    val grownSize: Float get() = WallLayout.GROW * pitch - gap

    fun cornerRadius(cells: UnitRect, grown: Boolean): Float = when {
        grown -> radiusGrown
        cells.shortSide >= 3 -> radiusLarge
        else -> radiusSmall
    }

    companion object {
        const val FOCUS_RADIUS_BOOST = 8f
    }
}

/** 块的几何：模板、镜像、阅读顺序、放大重排、块内格到墙面 dp 的换算。 */
internal object WallLayout {
    const val BLOCK_W = 12
    const val BLOCK_H = 8
    const val SLOTS = 12
    const val GROW = 6

    /** 允许的格子尺寸（宽 × 高）；模板和重排都只用这些。 */
    val SIZES: List<Pair<Int, Int>> = listOf(
        2 to 2, 3 to 2, 2 to 3, 2 to 4, 4 to 2, 3 to 4, 4 to 3, 4 to 4, 6 to 2, 6 to 3, 6 to 4,
    )

    /** 四个手画模板，每个正好铺满 12×8、12 块。 */
    val TEMPLATES: List<List<UnitRect>> = listOf(
        rects(0, 0, 4, 4, 4, 0, 2, 2, 6, 0, 3, 2, 9, 0, 3, 4, 4, 2, 2, 3, 6, 2, 3, 2, 0, 4, 4, 2, 0, 6, 2, 2, 2, 6, 2, 2, 4, 5, 2, 3, 6, 4, 4, 4, 10, 4, 2, 4),
        rects(0, 0, 6, 3, 6, 0, 2, 2, 8, 0, 4, 3, 6, 2, 2, 2, 0, 3, 2, 3, 2, 3, 4, 3, 0, 6, 2, 2, 2, 6, 4, 2, 6, 4, 2, 4, 8, 3, 4, 2, 8, 5, 2, 3, 10, 5, 2, 3),
        rects(0, 0, 3, 2, 3, 0, 3, 2, 6, 0, 6, 4, 0, 2, 2, 4, 2, 2, 4, 4, 0, 6, 2, 2, 2, 6, 2, 2, 4, 6, 2, 2, 6, 4, 3, 2, 6, 6, 3, 2, 9, 4, 3, 2, 9, 6, 3, 2),
        rects(0, 0, 2, 4, 2, 0, 4, 3, 6, 0, 2, 2, 8, 0, 4, 2, 6, 2, 6, 4, 2, 3, 2, 2, 4, 3, 2, 2, 0, 4, 2, 4, 2, 5, 4, 3, 6, 6, 2, 2, 8, 6, 2, 2, 10, 6, 2, 2),
    )

    private fun rects(vararg v: Int): List<UnitRect> = v.toList().chunked(4) { UnitRect(it[0], it[1], it[2], it[3]) }

    private val READING = compareBy<UnitRect>({ it.y }, { it.x })

    /** 模板 × 镜像的 16 种块，已按阅读顺序（先行后列）排好：第 k 个位就是第 k 个矩形。 */
    private val BASE: List<List<UnitRect>> = List(16) { k -> mirrored(TEMPLATES[k / 4], k % 4).sortedWith(READING) }

    /** 镜像：第 1 位左右翻，第 2 位上下翻。 */
    fun mirrored(rects: List<UnitRect>, mirror: Int): List<UnitRect> = rects.map { r ->
        UnitRect(
            if ((mirror and 1) != 0) BLOCK_W - r.x - r.w else r.x,
            if ((mirror and 2) != 0) BLOCK_H - r.y - r.h else r.y,
            r.w,
            r.h,
        )
    }

    /** 模板按块坐标线性步进，相邻块总是不同模板。 */
    fun templateOf(bx: Int, by: Int): Int = (bx + by * 2).mod(4)

    /** 块坐标哈希，与原型的 32 位无符号结果逐位一致。 */
    fun blockHash(bx: Int, by: Int): Long {
        var h = bx * 374761393 + by * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return (h xor (h ushr 16)).toLong() and 0xFFFFFFFFL
    }

    fun mirrorOf(bx: Int, by: Int): Int = (blockHash(bx, by) % 4).toInt()

    /** 未放大时块内 12 个位，按阅读顺序。 */
    fun slotRects(template: Int, mirror: Int): List<UnitRect> = BASE[template * 4 + mirror]

    /** 第 [grownSlot] 位放大到 6×6 后整块的 12 个位，其余各位仍对应原来那首。 */
    fun reflowedRects(template: Int, mirror: Int, grownSlot: Int): List<UnitRect> =
        WallReflowTable.rects(template, mirror, grownSlot)

    /** 某块此刻的 12 个位：放大格在这块里就用重排结果，其他块不动。 */
    fun blockRects(bx: Int, by: Int, grown: SlotPos?): List<UnitRect> {
        val t = templateOf(bx, by)
        val m = mirrorOf(bx, by)
        return if (grown != null && grown.bx == bx && grown.by == by) reflowedRects(t, m, grown.slot) else slotRects(t, m)
    }

    /** 块内格子换成墙面 dp：左上角按格距对齐，宽高扣一道缝。 */
    fun world(bx: Int, by: Int, r: UnitRect, metrics: WallMetrics): WallRect {
        val p = metrics.pitch
        val g = metrics.gap
        return WallRect((bx * BLOCK_W + r.x) * p, (by * BLOCK_H + r.y) * p, r.w * p - g, r.h * p - g)
    }
}

/** 听歌模式队尾接的占位格数。 */
internal const val FEED_GHOSTS = 18

/** 一位坐的是第几首。 */
internal sealed interface WallField {
    /** 第 (bx, by) 块第 slot 位坐第几首；-1 表示这里不画。 */
    fun songAt(bx: Int, by: Int, slot: Int): Int

    /**
     * 普通墙：cols × rows 块组成一个重复单元，向四周无限平铺，单元里多出来的位从第 1 首接着循环。
     * 块坐标为负也按周期取模。[n] 为 0 时整面墙为空。
     */
    data class Looping(val n: Int, val cols: Int, val rows: Int) : WallField {
        override fun songAt(bx: Int, by: Int, slot: Int): Int =
            if (n <= 0) -1 else ((by.mod(rows) * cols + bx.mod(cols)) * WallLayout.SLOTS + slot) % n
    }

    /**
     * 听歌模式：推荐流不循环。块按列排成一条 [rows] 块高的带子往右长，第 i 首坐第 i 个位，
     * 已出的 [songs] 首后面接 [ghosts] 个占位格。续上新歌只是让占位格翻成封面，已有格子一个都不动。
     */
    data class FeedBand(val rows: Int, val songs: Int, val ghosts: Int = FEED_GHOSTS) : WallField {
        /** 带子上要画的格数：歌 + 占位格。 */
        val tiles: Int get() = songs + ghosts

        override fun songAt(bx: Int, by: Int, slot: Int): Int {
            if (bx < 0 || by < 0 || by >= rows) return -1
            val i = (bx * rows + by) * WallLayout.SLOTS + slot
            return if (i < tiles) i else -1
        }

        /** 第 i 首的位置：按列优先，先填满一列的 [rows] 块再往右。 */
        fun posOf(i: Int): SlotPos {
            val k = i.floorDiv(WallLayout.SLOTS)
            return SlotPos(k.floorDiv(rows), k.mod(rows), i.mod(WallLayout.SLOTS))
        }

        fun isGhost(i: Int): Boolean = i >= songs

        /** 占位格越往后越淡：第一个 1，最后一个约 0.2。 */
        fun ghostAlpha(i: Int): Float = 1f - (i - songs).toFloat() / ghosts * 0.85f
    }
}

/** 普通墙的重复单元：ceil(N/12) 块，偏横向。 */
internal fun fieldFor(n: Int): WallField.Looping {
    val blocks = max(1, ceil(n / WallLayout.SLOTS.toDouble()).toInt())
    val cols = ceil(sqrt(blocks * 2.2 / 1.5)).toInt()
    return WallField.Looping(n, cols, ceil(blocks / cols.toDouble()).toInt())
}

/** 听歌模式的带子，高度按设备取。 */
internal fun feedFor(metrics: WallMetrics, songs: Int): WallField.FeedBand = WallField.FeedBand(metrics.feedRows, songs)

/** 与原型的 clamp 相同：lo > hi 时取 hi（coerceIn 这时会抛异常）。 */
internal fun clampLoose(v: Float, lo: Float, hi: Float): Float = min(hi, max(lo, v))

/**
 * 墙面查询：哪一格坐哪首、格子在墙面上的位置、可见范围、找最近的一份、方向键焦点。
 * [grown] 是当前放大的格（只有它所在的块按重排表排）。不可变，放大格变了就换一个 [withGrown]。
 */
internal class WallScene(
    val metrics: WallMetrics,
    val field: WallField,
    val grown: SlotPos? = null,
) {
    fun withGrown(p: SlotPos?): WallScene = WallScene(metrics, field, p)

    fun withField(f: WallField): WallScene = WallScene(metrics, f, grown)

    fun songAt(bx: Int, by: Int, slot: Int): Int = field.songAt(bx, by, slot)

    fun rectsOf(bx: Int, by: Int): List<UnitRect> = WallLayout.blockRects(bx, by, grown)

    fun world(bx: Int, by: Int, r: UnitRect): WallRect = WallLayout.world(bx, by, r, metrics)

    fun rectAt(p: SlotPos): WallRect = world(p.bx, p.by, rectsOf(p.bx, p.by)[p.slot])

    fun tileAt(p: SlotPos): WallTile {
        val cells = rectsOf(p.bx, p.by)[p.slot]
        return WallTile(p, songAt(p.bx, p.by, p.slot), cells, world(p.bx, p.by, cells), p == grown)
    }

    /** 镜头 [cam] 下要画的块：屏幕四周各多留两个格距。 */
    fun visibleBlocks(cam: WallPoint, viewport: WallViewport): BlockRange {
        val ov = metrics.pitch * 2
        val bw = metrics.blockWidth
        val bh = metrics.blockHeight
        return BlockRange(
            floor((cam.x - ov) / bw).toInt(),
            floor((cam.x + viewport.width + ov) / bw).toInt(),
            floor((cam.y - ov) / bh).toInt(),
            floor((cam.y + viewport.height + ov) / bh).toInt(),
        )
    }

    /** 要画的格子：可见块里不是 -1、且和“屏幕外扩两个格距”相交的格。顺序：块先行后列，块内按位。 */
    fun visibleTiles(cam: WallPoint, viewport: WallViewport): List<WallTile> {
        val ov = metrics.pitch * 2
        val range = visibleBlocks(cam, viewport)
        val out = ArrayList<WallTile>()
        for (by in range.by0..range.by1) for (bx in range.bx0..range.bx1) {
            val rs = rectsOf(bx, by)
            for (s in 0 until WallLayout.SLOTS) {
                val song = songAt(bx, by, s)
                if (song < 0) continue
                val w = world(bx, by, rs[s])
                if (w.right < cam.x - ov || w.x > cam.x + viewport.width + ov ||
                    w.bottom < cam.y - ov || w.y > cam.y + viewport.height + ov
                ) continue
                val p = SlotPos(bx, by, s)
                out += WallTile(p, song, rs[s], w, p == grown)
            }
        }
        return out
    }

    /** [p] 所在块和周围 8 块里所有要画的格（方向键只在这里找）。 */
    fun tilesNear(p: SlotPos): List<WallTile> {
        val out = ArrayList<WallTile>()
        for (by in p.by - 1..p.by + 1) for (bx in p.bx - 1..p.bx + 1) {
            val rs = rectsOf(bx, by)
            for (s in 0 until WallLayout.SLOTS) {
                val song = songAt(bx, by, s)
                if (song < 0) continue
                val pos = SlotPos(bx, by, s)
                out += WallTile(pos, song, rs[s], world(bx, by, rs[s]), pos == grown)
            }
        }
        return out
    }

    /**
     * 这首歌离屏幕点 [screen]（相对镜头 [cam]）最近的一份。普通墙在中心块周围 ±2 块里找，
     * 按屏幕上实际的位置算（含放大块的重排）；听歌模式每首只有一份，直接算位置，占位格和越界返回 null。
     */
    fun nearestCopy(song: Int, cam: WallPoint, screen: WallPoint): SlotPos? {
        val f = field
        if (f is WallField.FeedBand) return if (song >= 0 && song < f.songs) f.posOf(song) else null
        val wx = cam.x + screen.x
        val wy = cam.y + screen.y
        val cbx = floor(wx / metrics.blockWidth).toInt()
        val cby = floor(wy / metrics.blockHeight).toInt()
        var best: SlotPos? = null
        var bd = Float.POSITIVE_INFINITY
        for (by in cby - 2..cby + 2) for (bx in cbx - 2..cbx + 2) {
            val rs = rectsOf(bx, by)
            for (s in 0 until WallLayout.SLOTS) {
                if (songAt(bx, by, s) != song) continue
                val w = world(bx, by, rs[s])
                val d = hypot(w.centerX - wx, w.centerY - wy)
                if (d < bd) {
                    bd = d
                    best = SlotPos(bx, by, s)
                }
            }
        }
        return best
    }

    /** 方向键第一下：选离可用区中心最近的一格。 */
    fun initialFocus(cam: WallPoint, viewport: WallViewport): SlotPos? {
        val cx = cam.x + viewport.width / 2
        val cy = cam.y + viewport.insetTop + viewport.usableHeight / 2
        val center = SlotPos(floor(cx / metrics.blockWidth).toInt(), floor(cy / metrics.blockHeight).toInt(), 0)
        var best: SlotPos? = null
        var bd = Float.POSITIVE_INFINITY
        for (c in tilesNear(center)) {
            val d = hypot(c.rect.centerX - cx, c.rect.centerY - cy)
            if (d < bd) {
                bd = d
                best = c.pos
            }
        }
        return best
    }

    /**
     * 方向键：从 [focus] 往 (dx, dy) 找下一格。候选必须整格在前方，得分见 [focusScore]；
     * 得分相差不到 0.5 时选横向偏移小的，再平就保留先找到的。没有候选返回 null（焦点不动）。
     */
    fun focusNeighbour(focus: SlotPos, dx: Int, dy: Int): SlotPos? {
        require((dx == 0) != (dy == 0)) { "方向只能是上下左右之一" }
        val cur = rectAt(focus)
        var best: SlotPos? = null
        var bs = Float.POSITIVE_INFINITY
        var bside = Float.POSITIVE_INFINITY
        for (c in tilesNear(focus)) {
            if (c.pos == focus) continue
            val sc = focusScore(cur, c.rect, dx, dy, metrics.pitch) ?: continue
            if (sc.score < bs - 0.5f || (abs(sc.score - bs) <= 0.5f && sc.side < bside)) {
                bs = sc.score
                bside = sc.side
                best = c.pos
            }
        }
        return best
    }

    /**
     * 听歌模式：带子铺满整屏、不露空边时镜头能去的范围。左边到第一块，右边到最后一个占位格所在的块；
     * 带子比屏幕矮时上下居中，比屏幕窄时贴左。普通墙无边，返回 null。
     */
    fun fillRange(viewport: WallViewport): WallBounds? {
        val f = field as? WallField.FeedBand ?: return null
        val h = f.rows * metrics.blockHeight
        val right = (f.posOf(f.tiles - 1).bx + 1) * metrics.blockWidth
        val y0: Float
        val y1: Float
        if (h >= viewport.height) {
            y0 = 0f
            y1 = h - viewport.height
        } else {
            y0 = (h - viewport.height) / 2
            y1 = y0
        }
        return WallBounds(0f, max(0f, right - viewport.width), y0, y1)
    }

    /** 拖动、甩动和漂移的边界：铺满范围再四周放宽一个格距。普通墙无边，返回 null。 */
    fun bounds(viewport: WallViewport): WallBounds? {
        val f = fillRange(viewport) ?: return null
        val m = metrics.pitch
        return WallBounds(f.x0 - m, f.x1 + m, f.y0 - m, f.y1 + m)
    }
}

/** 方向键候选的得分：[score] 越小越优先，[side] 是横向（垂直于方向）的中心偏移，平分时比它。 */
internal data class FocusScore(val score: Float, val side: Float)

/**
 * 从 [cur] 往 (dx, dy) 看 [cand]：不整格在前方（允许 1dp 误差）返回 null。
 * 得分 = 沿方向的间距 + 横向错开时的惩罚（错开距离 × 2 + 一个格距）。
 */
internal fun focusScore(cur: WallRect, cand: WallRect, dx: Int, dy: Int, pitch: Float): FocusScore? {
    val eps = 1f
    val gap: Float
    val miss: Float
    val side: Float
    if (dx != 0) {
        if (if (dx > 0) cand.x < cur.right - eps else cand.right > cur.x + eps) return null
        gap = if (dx > 0) cand.x - cur.right else cur.x - cand.right
        miss = max(0f, max(cand.y, cur.y) - min(cand.bottom, cur.bottom))
        side = abs(cand.centerY - cur.centerY)
    } else {
        if (if (dy > 0) cand.y < cur.bottom - eps else cand.bottom > cur.y + eps) return null
        gap = if (dy > 0) cand.y - cur.bottom else cur.y - cand.bottom
        miss = max(0f, max(cand.x, cur.x) - min(cand.right, cur.right))
        side = abs(cand.centerX - cur.centerX)
    }
    return FocusScore(gap + if (miss > 0f) 2 * miss + pitch else 0f, side)
}
