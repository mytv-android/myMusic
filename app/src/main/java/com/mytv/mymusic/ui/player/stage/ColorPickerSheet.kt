package com.mytv.mymusic.ui.player.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.hct.Hct
import com.materialkolor.ktx.toColor
import com.materialkolor.ktx.toHct
import com.mytv.mymusic.data.RECENT_COLORS_LIMIT
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.FoldSide
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.LocalSheetClose
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.library.TvDefaultFocus
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 取色面板（设置表里的颜色项点开它）。从上到下：预览条（底色上写一句示例歌词）→ 从封面取 → 最近用过 →
 * 色相 / 彩度 / 明度三条滑块（HCT）→ 十六进制（电视上没有）→ 文字对比度（不到 4.5:1 时给“自动配文字色”）→ 取消、完成。
 *
 * 改动实时写进动画面板的草稿（[onChange]），舞台或大预览跟着变；下滑、返回、点外面都按“完成”算，
 * 要撤销可以用动画面板的“放弃”。“取消”把颜色改回打开时的 [initial]。
 *
 * @param background 这一对颜色里的底色、[text] 是字色（预览和对比度用）；[editingText] 为 true 时改的是字色。
 * @param pairs 这个颜色是一对（底色、字色）里的一个：显示对比度和“自动配文字色”。
 * @param onAutoText 改底色时点“自动配文字色”：把字色换成它。
 * @param onDone 完成时的颜色（记进“最近用过”）。
 * @param stageBeside 舞台在面板旁边：遮罩调淡，配色看得准。
 * @param chromeHint 改底色时底色偏亮要不要提示播放页顶上的浅色歌名、按钮会看不清（歌词分享卡片用不着）。
 */
@Composable
internal fun ColorPickerSheet(
    title: String,
    subtitle: String,
    initial: Color,
    background: Color,
    text: Color,
    editingText: Boolean,
    pairs: Boolean,
    coverSwatches: List<Color>,
    recent: List<Color>,
    stageBeside: Boolean,
    chromeHint: Boolean = true,
    onChange: (Color) -> Unit,
    onAutoText: (Color) -> Unit,
    onDone: (Color) -> Unit,
    onCancel: () -> Unit,
) {
    val tv = LocalAppWindow.current.isTv
    // HCT 三个数各存一份，不从颜色反推：超出色域时彩度会被收，反推回来滑块会自己跳
    val start = remember { initial.toHct() }
    var hue by remember { mutableDoubleStateOf(start.hue) }
    var chroma by remember { mutableDoubleStateOf(start.chroma) }
    var tone by remember { mutableDoubleStateOf(start.tone) }
    var current by remember { mutableStateOf(initial) }
    var hexField by remember { mutableStateOf(initial.toHex()) }
    var cancelled by remember { mutableStateOf(false) }
    val latestChange by rememberUpdatedState(onChange)
    fun apply(color: Color, fromHex: Boolean = false) {
        current = color
        if (!fromHex) hexField = color.toHex()
        latestChange(color)
    }
    fun applyHct() = apply(Hct.from(hue, chroma, tone).toColor())
    fun pick(color: Color, fromHex: Boolean = false) {
        val hct = color.toHct()
        hue = hct.hue
        chroma = hct.chroma
        tone = hct.tone
        apply(color, fromHex)
    }
    val doneState = rememberUpdatedState { onDone(current) }
    AdaptiveSheet(
        onDismiss = { if (cancelled) onCancel() else doneState.value() },
        title = title,
        subtitle = subtitle,
        foldSide = FoldSide.Start,
        scrimAlpha = if (stageBeside) 0.08f else 0.32f,
    ) {
        val close = LocalSheetClose.current
        val scheme = MaterialTheme.colorScheme
        val bg = if (editingText) background else current
        val fg = if (editingText) current else text
        // “自动配文字色”点完按钮就没了，电视上焦点交给“完成”，不让它掉回最上面的色块
        val doneFocus = remember { FocusRequester() }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // 预览条
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(bg)
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("for a signal in the rain", color = fg, fontSize = 20.sp, lineHeight = 23.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text("等雨里传来一个信号", color = fg.copy(alpha = 0.75f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }

            PickerLabel("从封面取")
            SwatchRow(coverSwatches, current) { pick(it) }
            if (recent.isNotEmpty()) {
                PickerLabel("最近用过")
                SwatchRow(recent, current) { pick(it) }
            }

            PickerLabel("调整", if (tv) "左右键每次 ±2，按住加速；上下键换一条" else "按 HCT 计算，明度相同的颜色看上去一样亮")
            val hueFocus = remember { FocusRequester() }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 色相条按至少中等彩度画：彩度拉到 0 时也看得出每一段是什么颜色
                val hueStops = remember(chroma, tone) { List(13) { Hct.from(it * 30.0, maxOf(chroma, 36.0), tone).toColor() } }
                val chromaStops = remember(hue, tone) { List(7) { Hct.from(hue, it * 25.0, tone).toColor() } }
                val toneStops = remember(hue, chroma) { List(11) { Hct.from(hue, chroma, it * 10.0).toColor() } }
                HctTrack("色相", hue, 360.0, hueStops, Modifier.focusRequester(hueFocus)) {
                    hue = it
                    applyHct()
                }
                HctTrack("彩度", chroma, MAX_CHROMA, chromaStops) {
                    chroma = it
                    applyHct()
                }
                HctTrack("明度", tone, 100.0, toneStops) {
                    tone = it
                    applyHct()
                }
            }
            TvDefaultFocus(hueFocus)

            if (!tv) {
                PickerLabel("十六进制")
                val valid = parseHexColor(hexField) != null
                OutlinedTextField(
                    value = hexField,
                    onValueChange = { value ->
                        val trimmed = value.take(7)
                        hexField = trimmed
                        if (HEX_PATTERN.matches(trimmed)) parseHexColor(trimmed)?.let { pick(it, fromHex = true) }
                    },
                    singleLine = true,
                    isError = !valid,
                    supportingText = if (valid) null else ({ Text("写成 #RRGGBB，比如 #2C5F5A") }),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (pairs) {
                val ratio = contrastRatio(bg, fg)
                val ok = ratio >= MIN_TEXT_CONTRAST
                val dark = scheme.surface.luminance() < 0.5f
                Row(
                    Modifier.padding(top = 14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "文字对比度 ${String.format(java.util.Locale.ROOT, "%.1f", ratio)}:1 · ${if (ok) "够清楚" else "不够清楚"}",
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    ok && dark -> Color(0xFF1D4D33)
                                    ok -> Color(0xFFC8F0D4)
                                    dark -> Color(0xFF5C1D1D)
                                    else -> Color(0xFFFFDAD6)
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        color = when {
                            ok && dark -> Color(0xFFB6F2C9)
                            ok -> Color(0xFF0B3B21)
                            dark -> Color(0xFFFFD0CC)
                            else -> Color(0xFF5C1D1D)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!ok) {
                        TextButton(
                            onClick = {
                                val auto = autoTextColor(bg)
                                if (editingText) pick(auto) else onAutoText(auto)
                                runCatching { doneFocus.requestFocus() }
                            },
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                        ) { Text("自动配文字色") }
                    }
                }
                // 播放页顶上的歌名、按钮一直是浅色的，底色太亮会把它们吃掉（歌词本身有上面的对比度管）
                if (chromeHint && !editingText && contrastRatio(bg, PLAYER_CHROME) < MIN_CHROME_CONTRAST) {
                    Text(
                        "底色偏亮：歌词页顶上的歌名和按钮是浅色的，可能看不清",
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = {
                        cancelled = true
                        apply(initial)
                        close()
                    },
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                ) { Text("取消") }
                Button(onClick = close, modifier = Modifier.focusRequester(doneFocus).tvFocusRing(RoundedCornerShape(20.dp))) { Text("完成") }
            }
        }
    }
}

@Composable
private fun PickerLabel(text: String, note: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 一排 40dp 的圆色点，和当前颜色一样的那个描一圈主色；电视上每个都能聚焦。 */
@Composable
private fun SwatchRow(colors: List<Color>, current: Color, onPick: (Color) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { color ->
            val on = color.toHex() == current.toHex()
            Box(
                Modifier
                    .size(46.dp)
                    .tvFocusRing(CircleShape)
                    .then(if (on) Modifier.border(3.dp, primary, CircleShape) else Modifier)
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, readableOn(color).copy(alpha = 0.2f), CircleShape)
                    .semantics { selected = on }
                    .clickable(role = Role.RadioButton, onClickLabel = "用这个颜色") { onPick(color) }
                    .semantics { contentDescription = color.toHex() },
            )
        }
    }
}

/**
 * HCT 的一条：名字、渐变轨道（[stops] 均匀铺开）和读数。拖、点都能改；电视上左右键每次 ±2、按住加速，上下键换条。
 */
@Composable
private fun HctTrack(
    label: String,
    value: Double,
    max: Double,
    stops: List<Color>,
    modifier: Modifier = Modifier,
    onChange: (Double) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val latest by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(40.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        BoxWithConstraints(
            modifier
                .weight(1f)
                .height(34.dp)
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentDescription = label
                    stateDescription = value.roundToInt().toString()
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..max.toFloat())
                    setProgress { target ->
                        change(target.toDouble().coerceIn(0.0, max))
                        true
                    }
                }
                .onPreviewKeyEvent { event ->
                    val down = event.type == KeyEventType.KeyDown
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            if (down) {
                                val boost = (1 + event.nativeKeyEvent.repeatCount / TV_SLIDER_REPEATS_PER_BOOST).coerceAtMost(TV_SLIDER_MAX_BOOST)
                                val sign = if (event.key == Key.DirectionRight) 1 else -1
                                change((latest + sign * HCT_KEY_STEP * boost).coerceIn(0.0, max))
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
                .focusable()
                .pointerInput(max) {
                    detectTapGestures { offset -> change((offset.x / size.width).coerceIn(0f, 1f) * max) }
                }
                .pointerInput(max) {
                    detectHorizontalDragGestures { pointer, _ ->
                        pointer.consume()
                        change((pointer.position.x / size.width).coerceIn(0f, 1f) * max)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(shape)
                    .background(Brush.horizontalGradient(stops))
                    .then(if (focused) Modifier.border(3.dp, scheme.primary, shape) else Modifier),
            )
            val fraction = (value / max).coerceIn(0.0, 1.0).toFloat()
            Box(
                Modifier
                    .offset(x = (maxWidth - 8.dp) * fraction)
                    .width(8.dp)
                    .height(34.dp)
                    .border(2.dp, Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                    .padding(2.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White),
            )
        }
        Text(
            value.roundToInt().toString(),
            Modifier.width(36.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * 底色 [background] 上自动配的字色：同色相、彩度最多 16，底色亮（相对亮度 > 0.18）就配色调 12 的深色，否则配 92 的浅色；
 * 对比度还不到 4.5:1 时退回黑白里对比度高的那个（黑白里总有一个至少 4.58:1）。
 */
internal fun autoTextColor(background: Color): Color {
    val hct = background.toHct()
    val tone = if (background.luminance() > 0.18f) 12.0 else 92.0
    val color = Hct.from(hct.hue, min(hct.chroma, 16.0), tone).toColor()
    return if (contrastRatio(color, background) >= MIN_TEXT_CONTRAST) color else readableOn(background)
}

/**
 * “从封面取”的 6 个色：舞台的封面量化色只有 4 个，前后加上鲜艳底色和柔和底色，按颜色去重；
 * 不够 6 个再用两种底色上的文字色补。
 */
internal fun coverSwatches(colors: StageColors): List<Color> {
    val out = LinkedHashMap<String, Color>()
    (listOf(colors.coverVibrant) + colors.cover + listOf(colors.coverMuted, colors.onCoverVibrant, colors.onCoverMuted))
        .forEach { color -> if (out.size < COVER_SWATCHES) out.putIfAbsent(color.toHex(), color) }
    return out.values.toList()
}

/** 记一个“最近用过”的颜色：放到最前面，去掉重复的，最多留 [RECENT_COLORS_LIMIT] 个。 */
internal fun pushRecentColor(recent: List<String>, color: Color): List<String> {
    val hex = color.toHex()
    return (listOf(hex) + recent.filter { !it.equals(hex, ignoreCase = true) }).take(RECENT_COLORS_LIMIT)
}

/** 文字对比度够清楚的线（WCAG AA）。 */
internal const val MIN_TEXT_CONTRAST = 4.5f

/** 播放页（一直是深色主题）顶上歌名、按钮的浅色，和它比对比度不到 [MIN_CHROME_CONTRAST] 就提示底色偏亮。 */
internal val PLAYER_CHROME = Color(0xFFE2E2E9)

/** 图标、按钮这类界面元素要的对比度（WCAG 非文字 3:1）。 */
internal const val MIN_CHROME_CONTRAST = 3f

private const val COVER_SWATCHES = 6
private const val MAX_CHROMA = 150.0
private const val HCT_KEY_STEP = 2.0
private val HEX_PATTERN = Regex("#?[0-9A-Fa-f]{6}")
