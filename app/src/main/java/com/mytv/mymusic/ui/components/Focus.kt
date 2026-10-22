package com.mytv.mymusic.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 遥控器焦点：放大 4% 并画一圈 3dp 主色描边。只在电视上生效，放大用 graphicsLayer，不触发重新布局。
 * 手机和平板上的硬件键盘焦点仍由组件自带的 focus indication 处理。
 */
@Composable
fun Modifier.tvFocusRing(shape: Shape = RoundedCornerShape(16.dp), scale: Float = 1.04f): Modifier {
    if (!LocalAppWindow.current.isTv) return this
    var focused by remember { mutableStateOf(false) }
    val animated by animateFloatAsState(if (focused) scale else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "tvScale")
    val ring = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .graphicsLayer {
            scaleX = animated
            scaleY = animated
        }
        .then(if (focused) Modifier.border(3.dp, ring, shape) else Modifier)
}

/** 电视卡片焦点：放大更明显（8%），用于海报和横向卡片行。 */
@Composable
fun Modifier.tvCardFocus(shape: Shape = RoundedCornerShape(20.dp), onFocused: () -> Unit = {}): Modifier {
    if (!LocalAppWindow.current.isTv) return this
    var focused by remember { mutableStateOf(false) }
    val animated by animateFloatAsState(if (focused) 1.08f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "tvCard")
    val ring = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged {
            val now = it.isFocused || it.hasFocus
            if (now && !focused) onFocused()
            focused = now
        }
        .graphicsLayer {
            scaleX = animated
            scaleY = animated
        }
        .then(if (focused) Modifier.border(3.dp, ring, shape) else Modifier)
}
