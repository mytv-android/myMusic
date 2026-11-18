/*
 * Copyright (C) 2026  Shubham Gorai
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
// 2026-11-18 myMusic 改写：21 种封面形状的映射照 Rush（shub39/Rush@5a952bf）EnumExt.kt 的 AlbumArtShape.toMaterialShape()；
// 改动：枚举放在 data 层（只存名字），这里给中文名，多边形和 Shape 分开取（选项格子画剪影要用多边形）。
package com.mytv.mymusic.ui.player.share

import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.mytv.mymusic.data.ShareCardShape

/** 选项里显示的名字。 */
internal val ShareCardShape.label: String
    get() = when (this) {
        ShareCardShape.Circle -> "圆形"
        ShareCardShape.Sunny -> "太阳"
        ShareCardShape.VerySunny -> "烈日"
        ShareCardShape.Square -> "方形"
        ShareCardShape.Slanted -> "斜方"
        ShareCardShape.Arch -> "拱门"
        ShareCardShape.Pill -> "药丸"
        ShareCardShape.Pentagon -> "五边形"
        ShareCardShape.Gem -> "宝石"
        ShareCardShape.Cookie4 -> "四瓣饼干"
        ShareCardShape.Cookie6 -> "六瓣饼干"
        ShareCardShape.Cookie9 -> "九瓣饼干"
        ShareCardShape.Cookie12 -> "十二瓣饼干"
        ShareCardShape.Clover4 -> "四叶草"
        ShareCardShape.Clover8 -> "八叶草"
        ShareCardShape.Ghostish -> "幽灵"
        ShareCardShape.Flower -> "花"
        ShareCardShape.SoftBurst -> "柔光"
        ShareCardShape.PuffyDiamond -> "蓬松菱形"
        ShareCardShape.Bun -> "面包"
        ShareCardShape.Rectangle -> "长方"
    }

/** 对应的 MaterialShapes 原形；长方（Rush 的 RECTANGLE）是直角矩形，没有多边形。 */
internal val ShareCardShape.polygon: RoundedPolygon?
    get() = when (this) {
        ShareCardShape.Circle -> MaterialShapes.Circle
        ShareCardShape.Sunny -> MaterialShapes.Sunny
        ShareCardShape.VerySunny -> MaterialShapes.VerySunny
        ShareCardShape.Square -> MaterialShapes.Square
        ShareCardShape.Slanted -> MaterialShapes.Slanted
        ShareCardShape.Arch -> MaterialShapes.Arch
        ShareCardShape.Pill -> MaterialShapes.Pill
        ShareCardShape.Pentagon -> MaterialShapes.Pentagon
        ShareCardShape.Gem -> MaterialShapes.Gem
        ShareCardShape.Cookie4 -> MaterialShapes.Cookie4Sided
        ShareCardShape.Cookie6 -> MaterialShapes.Cookie6Sided
        ShareCardShape.Cookie9 -> MaterialShapes.Cookie9Sided
        ShareCardShape.Cookie12 -> MaterialShapes.Cookie12Sided
        ShareCardShape.Clover4 -> MaterialShapes.Clover4Leaf
        ShareCardShape.Clover8 -> MaterialShapes.Clover8Leaf
        ShareCardShape.Ghostish -> MaterialShapes.Ghostish
        ShareCardShape.Flower -> MaterialShapes.Flower
        ShareCardShape.SoftBurst -> MaterialShapes.SoftBurst
        ShareCardShape.PuffyDiamond -> MaterialShapes.PuffyDiamond
        ShareCardShape.Bun -> MaterialShapes.Bun
        ShareCardShape.Rectangle -> null
    }

/** 卡片里裁封面用的形状（不是 Composable，导出、缩略图、单测都能直接取）。 */
internal fun ShareCardShape.toShape(): Shape = synchronized(shapeCache) {
    shapeCache.getOrPut(this) { polygon?.let(::PolygonShape) ?: RectangleShape }
}

private val shapeCache = HashMap<ShareCardShape, Shape>()

/** 把单位正方形里的多边形拉伸到组件大小（同 material3 的 RoundedPolygon.toShape，那个只能在组合里调）。 */
private class PolygonShape(polygon: RoundedPolygon) : Shape {
    private val unit = polygon.normalized().toPath().asComposePath()

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = androidx.compose.ui.graphics.Path().apply { addPath(unit) }
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}
