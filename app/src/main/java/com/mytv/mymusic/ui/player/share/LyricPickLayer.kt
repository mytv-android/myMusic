package com.mytv.mymusic.ui.player.share

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.Posture
import com.mytv.mymusic.ui.player.playerFoldSplit
import com.mytv.mymusic.ui.player.rememberSystemAnimationsOff
import com.mytv.mymusic.ui.theme.LocalAppearance
import kotlinx.coroutines.launch

/**
 * 选句层：舞台压暗，上面铺一张按时间排的歌词列表，长按的那句先选上；顶栏「× 已选 N 句 … 分享」。
 * 点句子增减，最多 8 句；× 或返回键退出（不影响播放），「分享」进分享页。
 *
 * 摆法：手机竖屏列表左右 14dp、提示浮在底部；宽 ≥ 600dp 列表一行不超过 720dp、提示挪进顶栏；
 * 桌面姿态整套放在折痕下面那半屏（平放着才好点），上半屏只剩压暗的舞台。
 */
@Composable
internal fun LyricPickLayer(
    session: ShareSession,
    list: LazyListState,
    active: State<Int>,
    currentKey: String,
    onClose: () -> Unit,
    onShare: () -> Unit,
) {
    val window = LocalAppWindow.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val insets = WindowInsets.safeDrawing
    Box(Modifier.fillMaxSize()) {
        // 压暗舞台，同时把触摸挡在这一层（下面的播放页收不到），舞台照常在下面动
        Spacer(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                },
        )
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
            val split = if (window.posture == Posture.Tabletop) {
                playerFoldSplit(
                    window.fold, insets, density, direction, constraints.maxWidth, constraints.maxHeight,
                    minSegment = with(density) { ShareTabletopMinHalf.toPx() },
                )?.takeIf { !it.vertical }
            } else {
                null
            }
            val widthDp = maxWidth.value.toInt()
            val layout = shareLayoutOf(widthDp, split != null)
            val wide = widthDp >= 600
            val top = split?.let { with(density) { it.secondStart.toDp() } } ?: 0.dp
            val count = session.selected.size
            Column(Modifier.fillMaxSize().padding(top = top)) {
                PickTopBar(
                    count = count,
                    hintInBar = wide,
                    topPadding = if (layout == ShareLayout.TwoPane) 14.dp else 0.dp,
                    onClose = onClose,
                    onShare = onShare,
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    PickList(session, list, active, currentKey, pickSidePadding(widthDp)) { order ->
                        val (next, result) = toggleLine(session.selected, order)
                        session.selected = next
                        if (result == ToggleResult.Full) {
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar("最多选 $SHARE_MAX_LINES 句")
                            }
                        }
                    }
                    if (!wide) PickHint(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
                    SnackbarHost(
                        snackbar,
                        if (wide) {
                            Modifier.align(Alignment.BottomEnd).padding(16.dp).widthIn(max = 320.dp)
                        } else {
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickTopBar(count: Int, hintInBar: Boolean, topPadding: Dp, onClose: () -> Unit, onShare: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = topPadding).height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "退出选句", tint = MaterialTheme.colorScheme.onSurface) }
        Text(
            "已选 $count 句",
            Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (hintInBar) PickHint(Modifier.padding(end = 8.dp))
        Button(onClick = onShare, enabled = count > 0, modifier = Modifier.padding(end = 8.dp)) {
            Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("分享")
        }
    }
}

@Composable
private fun PickHint(modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(
            "点句子增减 · 最多 $SHARE_MAX_LINES 句",
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 按时间排的歌词列表，底部 15% 渐隐。 */
@Composable
private fun PickList(
    session: ShareSession,
    list: LazyListState,
    active: State<Int>,
    currentKey: String,
    side: Dp,
    onToggle: (Int) -> Unit,
) {
    LazyColumn(
        state = list,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0.85f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
        contentPadding = PaddingValues(start = side, end = side, top = 6.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(session.lines, key = { it.order }) { line ->
            val singing = session.synced && session.song.key == currentKey && line.source == active.value
            PickRow(line, line.order in session.selected, singing) { onToggle(line.order) }
        }
    }
}

@Composable
private fun PickRow(line: PickLine, selected: Boolean, singing: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val reduceMotion = LocalAppearance.current.reduceMotion || rememberSystemAnimationsOff()
    val corner by animateDpAsState(if (selected) 12.dp else 18.dp, if (reduceMotion) snap() else motion.fastSpatialSpec(), label = "pickCorner")
    val background by animateColorAsState(
        if (selected) scheme.secondaryContainer else Color.Transparent,
        if (reduceMotion) snap() else motion.fastEffectsSpec(),
        label = "pickBackground",
    )
    val text = if (selected) scheme.onSecondaryContainer else scheme.onSurface.copy(alpha = 0.5f)
    val sub = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant.copy(alpha = 0.6f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .background(background)
            .toggleable(selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(22.dp)
                .clip(CircleShape)
                .then(
                    if (selected) {
                        Modifier.background(scheme.primary)
                    } else {
                        Modifier.drawWithContent {
                            drawCircle(scheme.outline, radius = size.minDimension / 2f - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = scheme.onPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    append(line.text)
                    if (singing) {
                        append("  ")
                        // 小标整体换行（稿子 inline-flex）：不换行空格 + 词连接符，不会只剩一个圆点挂在行尾
                        withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = scheme.primary)) {
                            append("●\u00A0正\u2060在\u2060唱")
                        }
                    }
                },
                fontSize = 21.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = text,
            )
            if (line.sub.isNotBlank()) {
                Text(line.sub, Modifier.padding(top = 2.dp), fontSize = 13.5.sp, lineHeight = 18.sp, color = sub)
            }
        }
    }
}

/** 桌面姿态按折痕上下分时，哪一半比这还矮就不分（和桌面姿态播放页一样）。 */
internal val ShareTabletopMinHalf = 160.dp
