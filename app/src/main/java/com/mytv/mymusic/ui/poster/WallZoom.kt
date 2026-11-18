/*
 * 海报墙视角（远近）的几何：大卡显示多大、换倍数时镜头怎么换算。纯 Kotlin，方便单测。
 */
package com.mytv.mymusic.ui.poster

import kotlin.math.max
import kotlin.math.min

/** 大卡显示尺寸离可用区边缘至少留的距离（稿子「屏宽减 24dp」）。 */
private const val GROWN_EDGE = 24f

/**
 * 大卡显示边长：槽位是 6×6 格（[WallMetrics.grownSize]），倍数为 1 时原样；
 * 拉远时不小于 [WallKind.grownMin]（手机、平板 240dp，电视 300dp），歌词和按钮还能用；
 * 拉近时不超出可用区（[area] 短边减 24dp），但也不比倍数 1 时小。
 * 算例：手机 0.5× 槽位 166 → 240；电视 0.5× 225.5 → 300；平板 0.5× 303 → 303；手机 1.25× 415 → 336（360 宽）。
 */
internal fun grownDisplaySize(m: WallMetrics, area: WallRect): Float {
    val slot = m.grownSize
    val avail = min(area.w, area.h) - GROWN_EDGE
    return when {
        m.zoom < 1f -> max(slot, min(m.kind.grownMin, avail))
        m.zoom > 1f -> min(slot, max(avail, m.kind.baseGrownSize))
        else -> slot
    }
}

/** 以中心为准改成 [size] × [size]。 */
internal fun WallRect.resizedAround(size: Float): WallRect =
    if (w == size && h == size) this else WallRect(centerX - size / 2, centerY - size / 2, size, size)
