package com.mytv.mymusic.ui.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope

/** 在 drawWithCache 中创建；保留渐变 shader 和 Paint，仅颜色变化时更新滤镜。 */
internal class TintedGradient(private val brush: Brush) {
    private val paint = Paint()
    private var lastTint = Color.Unspecified

    fun draw(scope: DrawScope, tint: Color) = with(scope) {
        if (tint != lastTint) {
            paint.colorFilter = ColorFilter.tint(tint)
            lastTint = tint
        }
        brush.applyTo(size, paint, 1f)
        drawContext.canvas.drawRect(0f, 0f, size.width, size.height, paint)
    }
}
