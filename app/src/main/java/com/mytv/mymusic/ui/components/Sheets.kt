package com.mytv.mymusic.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.coroutines.cancellation.CancellationException

/**
 * 自适应面板：手机是底部面板；平板是右侧 400dp 的浮动侧边面板（后面的内容仍可见）；
 * 电视是贴右边、整高 360dp 的面板，返回键关闭。
 */
@Composable
fun AdaptiveSheet(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String = "",
    subtitleProvider: (() -> String)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val window = LocalAppWindow.current
    if (window.isPhone) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                SheetHeader(title, subtitle, subtitleProvider, null)
                content()
            }
        }
        return
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val visible = remember { MutableTransitionState(false) }
        LaunchedEffect(Unit) { visible.targetState = true }
        // 面板的返回：手势过程中面板跟手右移淡出，松手才关闭
        var backProgress by remember { mutableFloatStateOf(0f) }
        PredictiveBackHandler { events ->
            try {
                events.collect { event -> backProgress = event.progress }
                onDismiss()
            } catch (_: CancellationException) {
            } finally {
                backProgress = 0f
            }
        }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    // 遮罩铺满全屏、没有焦点样式；能聚焦的话电视上焦点会落在它身上，方向键再也移不出来
                    .focusProperties { canFocus = false }
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
            AnimatedVisibility(
                visibleState = visible,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
            ) {
                val tv = window.isTv
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(if (tv) 360.dp else 400.dp)
                        .then(if (tv) Modifier else Modifier.statusBarsPadding().navigationBarsPadding().padding(12.dp))
                        .graphicsLayer {
                            alpha = 1f - backProgress
                            translationX = size.width * 0.6f * backProgress
                        },
                    shape = if (tv) RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp) else RoundedCornerShape(28.dp),
                    color = if (tv) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = 6.dp,
                ) {
                    Column(Modifier.fillMaxSize().padding(top = if (tv) 36.dp else 16.dp, bottom = 16.dp)) {
                        SheetHeader(title, subtitle, subtitleProvider, if (tv) null else onDismiss)
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String, subtitleProvider: (() -> String)?, onClose: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            // provider 在这个作用域里求值：每秒变化的倒计时只重组标题，不会带动整张面板
            val subtitleText = subtitleProvider?.invoke() ?: subtitle
            if (subtitleText.isNotBlank()) {
                Text(subtitleText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onClose != null) {
            IconButton(onClick = onClose, modifier = Modifier.tvFocusRing(CircleShape)) { Icon(Icons.Filled.Close, "关闭") }
        }
    }
}
