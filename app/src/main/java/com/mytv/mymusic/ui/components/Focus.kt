package com.mytv.mymusic.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 遥控器焦点：放大 4% 并画一圈 3dp 主色描边。只在电视上生效，放大用 draw 阶段的变换，不触发组合和布局。
 * 用 Modifier.Node 而不是 composed/内联动画：节点可复用，缩放值只在绘制时读取。
 */
@Composable
fun Modifier.tvFocusRing(shape: Shape = RoundedCornerShape(16.dp), scale: Float = 1.04f): Modifier {
    if (!LocalAppWindow.current.isTv) return this
    return this then TvFocusElement(
        shape = shape,
        scale = scale,
        ring = MaterialTheme.colorScheme.primary,
        spec = MaterialTheme.motionScheme.fastSpatialSpec(),
        onFocused = null,
    )
}

/** 电视卡片焦点：放大更明显（8%），用于海报和横向卡片行。 */
@Composable
fun Modifier.tvCardFocus(shape: Shape = RoundedCornerShape(20.dp), onFocused: () -> Unit = {}): Modifier {
    if (!LocalAppWindow.current.isTv) return this
    return this then TvFocusElement(
        shape = shape,
        scale = 1.08f,
        ring = MaterialTheme.colorScheme.primary,
        spec = MaterialTheme.motionScheme.fastSpatialSpec(),
        onFocused = onFocused,
    )
}

private data class TvFocusElement(
    val shape: Shape,
    val scale: Float,
    val ring: Color,
    val spec: AnimationSpec<Float>,
    val onFocused: (() -> Unit)?,
) : ModifierNodeElement<TvFocusNode>() {
    override fun create() = TvFocusNode(shape, scale, ring, spec, onFocused)

    override fun update(node: TvFocusNode) {
        node.shape = shape
        node.targetScale = scale
        node.ring = ring
        node.spec = spec
        node.onFocused = onFocused
    }
}

private class TvFocusNode(
    var shape: Shape,
    var targetScale: Float,
    var ring: Color,
    var spec: AnimationSpec<Float>,
    var onFocused: (() -> Unit)?,
) : Modifier.Node(), FocusEventModifierNode, DrawModifierNode {
    private val animated = Animatable(1f)
    private var focused = false

    // 轮廓和描边只随尺寸、形状、密度、布局方向变化；缩放动画帧直接复用
    private var cachedOutline: Outline? = null
    private var cachedStroke: Stroke? = null
    private var cachedShape: Shape? = null
    private var cachedSize: Size = Size.Zero
    private var cachedLayoutDirection: LayoutDirection? = null
    private var cachedOutlineDensity: Float = Float.NaN
    private var cachedOutlineFontScale: Float = Float.NaN
    private var cachedStrokeDensity: Float = Float.NaN

    override fun onFocusEvent(focusState: FocusState) {
        val now = focusState.isFocused || focusState.hasFocus
        if (now == focused) return
        focused = now
        if (now) onFocused?.invoke()
        coroutineScope.launch { animated.animateTo(if (now) targetScale else 1f, spec) }
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val current = animated.value
        scale(current, current) {
            this@draw.drawContent()
            if (focused) {
                val density = this.density
                val fontScale = this.fontScale
                if (cachedStroke == null || cachedStrokeDensity != density) {
                    cachedStroke = Stroke(width = 3.dp.toPx())
                    cachedStrokeDensity = density
                }
                val outline = if (
                    cachedOutline == null ||
                    cachedSize != size ||
                    cachedLayoutDirection != layoutDirection ||
                    cachedOutlineDensity != density ||
                    cachedOutlineFontScale != fontScale ||
                    cachedShape != shape
                ) {
                    shape.createOutline(size, layoutDirection, this).also {
                        cachedOutline = it
                        cachedShape = shape
                        cachedSize = size
                        cachedLayoutDirection = layoutDirection
                        cachedOutlineDensity = density
                        cachedOutlineFontScale = fontScale
                    }
                } else {
                    cachedOutline!!
                }
                drawOutline(outline, ring, style = cachedStroke!!)
            }
        }
    }
}
