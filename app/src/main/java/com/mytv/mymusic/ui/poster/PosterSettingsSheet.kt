package com.mytv.mymusic.ui.poster

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.PosterPrefs
import com.mytv.mymusic.data.PosterTint
import com.mytv.mymusic.data.PosterZoom
import com.mytv.mymusic.data.ZoomScope
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SwitchRow
import com.mytv.mymusic.ui.components.tvFocusRing
import kotlin.math.roundToInt

/**
 * 墙内设置：染色、视角、关灯、序号、翻译、跟随，电视上再加空闲漂移。队列海报墙和详情页海报视图共用一份，改了立即生效。
 * 视角按 [scope]（墙这一次的格距档）读写那一份。
 * 手机是底部面板，平板是侧板，电视是右侧面板（[AdaptiveSheet]）。
 */
@Composable
internal fun PosterSettingsSheet(
    prefs: PosterPrefs,
    scope: ZoomScope,
    onChange: ((PosterPrefs) -> PosterPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    val tv = LocalAppWindow.current.isTv
    AdaptiveSheet(
        onDismiss = onDismiss,
        title = "海报墙设置",
        subtitle = if (tv) "←→ 调视角 · 返回关闭" else "队列和歌单详情共用，只影响本机",
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SectionLabel("海报染色", Modifier.padding(horizontal = 24.dp))
            ConnectedToggleGroup(
                options = PosterTint.entries,
                selected = prefs.tint,
                onSelect = { tint -> onChange { it.copy(tint = tint) } },
                label = { it.label },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth(),
            )
            val zoom = prefs.zoom.of(scope)
            val setZoom: (Float) -> Unit = { v -> onChange { it.copy(zoom = it.zoom.with(scope, v)) } }
            SectionLabel(
                "视角",
                Modifier.padding(horizontal = 24.dp),
                trailing = {
                    Text(
                        "${zoomLabel(zoom)}×",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            ZoomSlider(zoom, setZoom, Modifier.padding(horizontal = 24.dp).fillMaxWidth())
            ZoomPresets(zoom, setZoom, Modifier.padding(horizontal = 20.dp).fillMaxWidth())
            Text(
                when (scope) {
                    ZoomScope.Phone -> "拉远能一屏看到整个歌单，适合截图给朋友看；双指捏合也能调。"
                    ZoomScope.Tablet -> "平板上也能双指捏合，松手吸附到最近的刻度。"
                    ZoomScope.Tv -> "电视上格子本来是 76dp，拉到 0.5× 时一屏的封面约是默认的 4 倍。"
                },
                Modifier.padding(start = 28.dp, end = 24.dp, top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val rows = buildList<Triple<String, String, Pair<Boolean, (Boolean) -> Unit>>> {
                add(Triple("关灯", "除了正在播放的那首，其余格子都压暗", prefs.lightsOut to { v: Boolean -> onChange { it.copy(lightsOut = v) } }))
                add(Triple("显示序号", "格子左上角标出第几首（队列里的位置，歌单里的曲目序号）", prefs.showIndex to { v: Boolean -> onChange { it.copy(showIndex = v) } }))
                add(Triple("显示歌词翻译", "大卡里当前行下面跟一行翻译", prefs.translation to { v: Boolean -> onChange { it.copy(translation = v) } }))
                add(Triple("切歌时自动跟随", "镜头滑到新歌最近的那一份并放大", prefs.follow to { v: Boolean -> onChange { it.copy(follow = v) } }))
                if (tv) {
                    add(Triple("空闲时镜头漂移", "30 秒不操作，镜头缓慢漂移，减少电视残影", prefs.tvDrift to { v: Boolean -> onChange { it.copy(tvDrift = v) } }))
                }
            }
            SectionLabel("显示", Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp))
            Column(Modifier.padding(horizontal = 16.dp)) {
                rows.forEachIndexed { index, (title, summary, value) ->
                    SwitchRow(
                        title = title,
                        checked = value.first,
                        onCheckedChange = value.second,
                        index = index,
                        count = rows.size,
                        summary = summary,
                    )
                }
            }
        }
    }
}

/** 倍数的写法：0.5、0.75、1、1.25（不写成 1.0）。 */
internal fun zoomLabel(value: Float): String {
    val hundredths = (value * 100).roundToInt()
    return when {
        hundredths % 100 == 0 -> (hundredths / 100).toString()
        hundredths % 10 == 0 -> "${hundredths / 100}.${hundredths % 100 / 10}"
        else -> "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }
}

/**
 * 视角滑块：只停在四个刻度上（steps = 2 正好是 0.5 / 0.75 / 1 / 1.25），拖过一档就写一次，墙立刻换。
 * 方向键：左右按档跳（到头了也吃掉，焦点不横跳），上下换到上一行 / 下一行——M3 滑块自己会拿上下键改值，这里不让。
 */
@Composable
private fun ZoomSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val current = rememberUpdatedState(value)
    val change = rememberUpdatedState(onChange)
    Slider(
        value = value,
        onValueChange = { v -> PosterZoom.snap(v).let { if (it != current.value) change.value(it) } },
        valueRange = PosterZoom.MIN..PosterZoom.MAX,
        steps = PosterZoom.STEPS.size - 2,
        modifier = modifier
            .onPreviewKeyEvent { event ->
                val down = event.type == KeyEventType.KeyDown
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (down) {
                            val next = PosterZoom.step(current.value, if (event.key == Key.DirectionRight) 1 else -1)
                            if (next != current.value) change.value(next)
                        }
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (down) focus.moveFocus(if (event.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                        true
                    }
                    else -> false
                }
            }
            .semantics { stateDescription = "${zoomLabel(value)} 倍" }
            .tvFocusRing(RoundedCornerShape(12.dp)),
    )
}

/** 滑块下面的「远 / 默认 / 近」：各自对着自己的刻度（两头的贴边），点了直接跳到那一档。 */
@Composable
private fun ZoomPresets(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val presets = listOf(PosterZoom.MIN to "远", PosterZoom.DEFAULT to "默认", PosterZoom.MAX to "近")
    Layout(
        content = {
            presets.forEach { (zoom, label) ->
                val on = zoom == value
                TextButton(
                    onClick = { if (!on) onChange(zoom) },
                    colors = if (on) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.textButtonColors(),
                    modifier = Modifier
                        .semantics { selected = on }
                        .tvFocusRing(RoundedCornerShape(20.dp)),
                ) { Text(label) }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { i, placeable ->
                val fraction = (presets[i].first - PosterZoom.MIN) / (PosterZoom.MAX - PosterZoom.MIN)
                val x = (fraction * width - placeable.width / 2f).roundToInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                placeable.place(x, 0)
            }
        }
    }
}

internal val PosterTint.label: String
    get() = when (this) {
        PosterTint.Off -> "关"
        PosterTint.Weak -> "弱"
        PosterTint.Medium -> "中"
        PosterTint.Strong -> "强"
    }
