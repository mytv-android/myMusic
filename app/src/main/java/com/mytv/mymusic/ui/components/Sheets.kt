package com.mytv.mymusic.ui.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mytv.mymusic.ui.adaptive.DialogFoldAnchor
import com.mytv.mymusic.ui.adaptive.dialogFoldAnchor
import com.mytv.mymusic.ui.adaptive.rememberDialogFoldSplit
import kotlin.coroutines.cancellation.CancellationException

/**
 * 关面板前先问一句：[blocked] 为 true 时（面板里有没保存的改动）下滑、点外面、按返回、点关闭都不关，改成调 [onBlocked]
 * （一般是弹“保存并应用这些改动？”）。
 */
@Stable
class SheetDismissGuard(val blocked: () -> Boolean, val onBlocked: () -> Unit)

/** 书本姿态 / 双竖时面板放哪一页。 */
enum class FoldSide { Start, End }

/**
 * 自适应面板：手机是底部面板；平板是右侧 400dp 的浮动侧边面板（后面的内容仍可见），
 * 折叠屏书本姿态只占右页（[foldSide] 为 [FoldSide.Start] 时占左页）、双屏横跨上下两块时只放上屏；
 * 电视是贴右边、整高 360dp 的面板，返回键关闭。
 *
 * @param dismissGuard 有没保存的改动时拦下关闭，见 [SheetDismissGuard]。
 * @param scrimAlpha 遮罩透明度；面板旁边要看清后面的内容（舞台预览的配色）时调淡。
 */
@Composable
fun AdaptiveSheet(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String = "",
    subtitleProvider: (() -> String)? = null,
    dismissGuard: SheetDismissGuard? = null,
    foldSide: FoldSide = FoldSide.End,
    scrimAlpha: Float = 0.32f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val window = LocalAppWindow.current
    val guard = rememberUpdatedState(dismissGuard)
    if (window.isPhone) {
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            // 下滑松手、点外面都会先问这里：有改动就弹回去，改成问要不要保存
            confirmValueChange = { value ->
                val current = guard.value
                if (value == SheetValue.Hidden && current?.blocked() == true) {
                    current.onBlocked()
                    false
                } else {
                    true
                }
            },
        )
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha),
        ) {
            // 返回键：比面板自带的返回处理注册得晚，先拿到；不依赖面板的返回键会不会问 confirmValueChange
            BackHandler(enabled = dismissGuard?.blocked() == true) { guard.value?.onBlocked() }
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                SheetHeader(title, subtitle, subtitleProvider, null)
                content()
            }
        }
        return
    }
    // 平板、电视：遮罩、关闭按钮、返回键都走这里，有改动时先问
    val requestDismiss: () -> Unit = {
        val current = guard.value
        if (current?.blocked() == true) current.onBlocked() else onDismiss()
    }
    Dialog(
        onDismissRequest = requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val visible = remember { MutableTransitionState(false) }
        LaunchedEffect(Unit) { visible.targetState = true }
        // 面板的返回：手势过程中面板跟手右移淡出，松手才关闭；有改动时不跟手，按返回直接问
        var backProgress by remember { mutableFloatStateOf(0f) }
        val blocked = dismissGuard?.blocked() == true
        PredictiveBackHandler(enabled = !blocked) { events ->
            try {
                events.collect { event -> backProgress = event.progress }
                requestDismiss()
            } catch (_: CancellationException) {
            } finally {
                backProgress = 0f
            }
        }
        BackHandler(enabled = blocked) { guard.value?.onBlocked() }
        // 折痕：书本 / 双竖时面板只占右页（右页比 400dp 窄就收窄；[foldSide] 为 Start 时占左页）；双横时铰链下面没有像素，
        // 面板只放上屏。半开的桌面姿态折痕不挡像素，面板照常整高
        val foldAnchor = remember { DialogFoldAnchor() }
        val split = rememberDialogFoldSplit(foldAnchor)
        val density = LocalDensity.current
        val pageSplit = split?.takeIf { it.vertical }
        // 没有竖折痕时，要放左页的面板照常放右边
        val start = foldSide == FoldSide.Start && pageSplit != null
        val pageWidth = pageSplit?.let { with(density) { (if (start) it.firstSize else it.secondSize).toDp() } }
        val topScreen = split?.takeIf { !it.vertical && it.occluding }?.let { with(density) { it.firstEnd.toDp() } }
        val toward = if (start) -1 else 1
        Box(Modifier.fillMaxSize().dialogFoldAnchor(foldAnchor)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                    // 遮罩铺满全屏、没有焦点样式；能聚焦的话电视上焦点会落在它身上，方向键再也移不出来
                    .focusProperties { canFocus = false }
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = requestDismiss),
            )
            AnimatedVisibility(
                visibleState = visible,
                modifier = Modifier
                    .align(
                        when {
                            topScreen != null -> Alignment.TopEnd
                            start -> Alignment.CenterStart
                            else -> Alignment.CenterEnd
                        },
                    )
                    .then(if (topScreen != null) Modifier.height(topScreen) else Modifier),
                enter = slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it * toward } + fadeIn(),
                exit = slideOutHorizontally { it * toward } + fadeOut(),
            ) {
                val tv = window.isTv
                val insets = when {
                    tv -> Modifier
                    // 上屏底下是铰链，不是导航栏
                    topScreen != null -> Modifier.statusBarsPadding().padding(12.dp)
                    else -> Modifier.statusBarsPadding().navigationBarsPadding().padding(12.dp)
                }
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(if (tv) 360.dp else pageWidth?.coerceAtMost(400.dp) ?: 400.dp)
                        .then(insets)
                        .graphicsLayer {
                            alpha = 1f - backProgress
                            translationX = size.width * 0.6f * backProgress * toward
                        },
                    shape = if (tv) RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp) else RoundedCornerShape(28.dp),
                    color = if (tv) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = 6.dp,
                ) {
                    Column(Modifier.fillMaxSize().padding(top = if (tv) 36.dp else 16.dp, bottom = 16.dp)) {
                        SheetHeader(title, subtitle, subtitleProvider, if (tv) null else requestDismiss)
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
