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
// 2026-11-18 myMusic 改写：分享页照 Rush（shub39/Rush@5a952bf）的 SharePage（预览缩放到放得下、可以双指放大，
// 卡片和全屏画布各录一个图层，配色、圆角照它做过渡）和 SharePageEdit（选项：样式、配色和自定义两色、圆角、封面形状、
// 全屏；叠卡 / 封面换背景图、乱排重新打乱、随机一套）。改动：选项一直铺在预览下面（宽屏在右栏），样式是一排真卡片的
// 缩略图，形状是 7 列网格；不引 zoomable、FileKit，缩放和存图自己写；导出前等过渡停、图片取完；全部免费。
// 选句、副行、文字格式、三种设备摆法是 myMusic 自己的。
package com.mytv.mymusic.ui.player.share

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.materialkolor.ktx.blend
import com.materialkolor.ktx.darken
import com.materialkolor.ktx.lighten
import com.mytv.mymusic.data.LyricStagePrefs
import com.mytv.mymusic.data.LyricSubline
import com.mytv.mymusic.data.ShareCardCorners
import com.mytv.mymusic.data.ShareCardPalette
import com.mytv.mymusic.data.ShareCardShape
import com.mytv.mymusic.data.ShareCardStyle
import com.mytv.mymusic.data.SharePrefs
import com.mytv.mymusic.data.ShareTextFormat
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.adaptive.FoldSplit
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.Posture
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentedGroup
import com.mytv.mymusic.ui.components.SwitchRow
import com.mytv.mymusic.ui.player.playerFoldSplit
import com.mytv.mymusic.ui.player.rememberSystemAnimationsOff
import com.mytv.mymusic.ui.player.stage.ColorPickerSheet
import com.mytv.mymusic.ui.player.stage.StageColors
import com.mytv.mymusic.ui.player.stage.coverSwatches
import com.mytv.mymusic.ui.player.stage.modes.RUSH_HYPNOTIC_COLORS
import com.mytv.mymusic.ui.player.stage.modes.rushGradientColors
import com.mytv.mymusic.ui.player.stage.parseHexColor
import com.mytv.mymusic.ui.player.stage.pushRecentColor
import com.mytv.mymusic.ui.player.stage.readableOn
import com.mytv.mymusic.ui.player.stage.rememberStageColors
import com.mytv.mymusic.ui.theme.LocalAppearance
import com.mytv.mymusic.ui.theme.PlayerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 一次分享的覆盖层：选句层 ↔ 分享页，淡入淡出切换。返回键先从分享页回到选句层（选择保留），再按才退出整个分享。
 * 分享页按会话打开那一刻的种子色建 PlayerTheme，切歌后配色不漂。
 */
@Composable
internal fun ShareOverlay(
    session: ShareSession,
    state: MusicUiState,
    actions: MusicActions,
    active: State<Int>,
    currentKey: String,
    onClose: () -> Unit,
) {
    val reduceMotion = LocalAppearance.current.reduceMotion || rememberSystemAnimationsOff()
    val effects = if (reduceMotion) snap() else MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // 列表状态放在外面：从分享页回到选句层时还停在原来的位置；长按的那句落在上面三分之一
    val pickList = remember(session) { LazyListState((session.initial - 2).coerceAtLeast(0)) }
    BackHandler {
        if (session.page == SharePage.Share) session.page = SharePage.Pick else onClose()
    }
    AnimatedContent(
        session.page,
        Modifier.fillMaxSize(),
        transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
        label = "sharePage",
    ) { page ->
        when (page) {
            SharePage.Pick -> LyricPickLayer(session, pickList, active, currentKey, onClose) { session.page = SharePage.Share }
            SharePage.Share -> PlayerTheme(seed = session.seed) {
                ShareLyricsScreen(session, state.share, state.lyricStage, actions) { session.page = SharePage.Pick }
            }
        }
    }
}

private enum class ShareAction { Copy, Save, Share }

/**
 * 分享页：预览、选项、三个操作（复制文字、保存图片、分享）。手机上下排；宽 ≥ 600dp 左预览、右边 420dp 的选项栏，
 * 操作挪到顶栏右边（书本姿态按折痕分左右页）；桌面姿态上半屏预览、下半屏选项和操作条。
 */
@Composable
private fun ShareLyricsScreen(
    session: ShareSession,
    initialPrefs: SharePrefs,
    lyricStage: LyricStagePrefs,
    actions: MusicActions,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val window = LocalAppWindow.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val reduceMotion = LocalAppearance.current.reduceMotion || rememberSystemAnimationsOff()
    val song = session.song

    // 点了先改本地值再落盘，不等 DataStore 回流
    var prefs by remember { mutableStateOf(initialPrefs) }
    fun update(next: SharePrefs) {
        prefs = next
        actions.setSharePrefs { next }
    }
    fun snack(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    val colors = rememberStageColors(song.cover)
    val imagesState = rememberShareImages(song.cover, session.picture) {
        session.picture = null
        snack("这张图读不出来")
    }
    val images = imagesState.value
    val allRows = remember(session.lines, session.selected) { shareRows(session.lines, session.selected) }
    val withSub = prefs.translation && session.hasSubline

    // 底色、字色、圆角照 Rush 做过渡；用 Animatable 才能在导出前等它们停下
    val (bgTarget, fgTarget) = cardColors(prefs, colors)
    val bg = remember { Animatable(bgTarget) }
    val fg = remember { Animatable(fgTarget) }
    val corner = remember { Animatable(prefs.corners.px.toFloat()) }
    LaunchedEffect(bgTarget) { if (reduceMotion) bg.snapTo(bgTarget) else bg.animateTo(bgTarget, motion.fastEffectsSpec()) }
    LaunchedEffect(fgTarget) { if (reduceMotion) fg.snapTo(fgTarget) else fg.animateTo(fgTarget, motion.fastEffectsSpec()) }
    LaunchedEffect(prefs.corners) {
        val target = prefs.corners.px.toFloat()
        if (reduceMotion) corner.snapTo(target) else corner.animateTo(target, motion.fastEffectsSpec())
    }

    val playerColors = PlayerCardColors(
        surface = scheme.surface,
        text = scheme.onSurface,
        accent = scheme.primary,
        glow = if (prefs.palette == ShareCardPalette.Player) colors.coverVibrant else bg.value,
        track = scheme.secondaryContainer,
    )
    val look = cardLook(prefs.style, prefs, bg.value, fg.value, corner.value, withSub, playerColors)
    val data = ShareCardData(song.title, song.artist, rowsForStyle(prefs.style, allRows), session.messySeed)
    val canvasColor = bg.value.blend(scheme.surface, 0.5f)
    val cardLayer = rememberGraphicsLayer()
    val canvasLayer = rememberGraphicsLayer()
    var busy by remember { mutableStateOf<ShareAction?>(null) }
    val text = shareText(allRows, withSub, prefs.textFormat, song.title, song.artist, session.link)

    fun export(kind: ShareAction) {
        if (busy != null) return
        busy = kind
        scope.launch {
            try {
                snapshotFlow { bg.isRunning || fg.isRunning || corner.isRunning }.first { !it }
                snapshotFlow { imagesState.value.ready }.first { it }
                val bitmap = captureLayer(if (prefs.fullScreen) canvasLayer else cardLayer)
                if (bitmap == null) {
                    snack("卡片还没画好，稍等再试")
                    return@launch
                }
                val name = shareFileName(song.title, song.artist, System.currentTimeMillis())
                if (kind == ShareAction.Save) {
                    if (saveToGallery(context, bitmap, name) != null) snack("已保存到相册 Pictures/$SHARE_ALBUM") else snack("保存失败")
                } else {
                    val file = writeShareCache(context, bitmap, name)
                    runCatching { context.startActivity(shareImageIntent(context, file, text)) }
                        .onFailure { snack("没有能分享图片的应用") }
                }
            } finally {
                busy = null
            }
        }
    }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) export(ShareAction.Save) else snack("没有存储权限，图片没存上；可以用「分享」发给自己")
    }
    val onSave = {
        val needs = Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needs) storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else export(ShareAction.Save)
    }
    val onCopy: () -> Unit = {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("歌词", text)))
            // Android 13 起系统自己会弹「已复制」
            if (Build.VERSION.SDK_INT < 33) snack("已复制 ${session.selected.size} 句歌词")
        }
    }
    val buttons = ShareButtons(
        busy = busy,
        canExport = images.ready,
        onCopy = onCopy,
        onSave = onSave,
        onShare = { export(ShareAction.Share) },
    )
    val onRandom = {
        update(randomLook(prefs, Random.Default))
        session.messySeed++
        session.paletteBeforeBrat = null
    }

    // 预览里的卡片要随时录进图层（导出读图层），三种布局都只放一份
    val preview: @Composable (Modifier) -> Unit = { modifier ->
        SharePreview(prefs, look, data, images, canvasColor, cardLayer, canvasLayer, modifier)
    }
    var editingColor by remember { mutableStateOf<Boolean?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) session.picture = uri
    }
    val options: @Composable (Dp, Modifier) -> Unit = { thumbWidth, modifier ->
        ShareOptions(
            prefs = prefs,
            session = session,
            subline = lyricStage.subline,
            thumbWidth = thumbWidth,
            modifier = modifier,
            thumb = { style ->
                ThumbCard(style, prefs, session, allRows, colors, playerColors, images, withSub)
            },
            onChange = ::update,
            onPickPicture = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onEditColor = { editingColor = it },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.surface)
            // 不透明的整页：空白处的点按也挡住，下面的播放页收不到
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val insets = WindowInsets.safeDrawing
            val tabletopSplit = if (window.posture == Posture.Tabletop) {
                playerFoldSplit(
                    window.fold, insets, density, direction, constraints.maxWidth, constraints.maxHeight,
                    minSegment = with(density) { ShareTabletopMinHalf.toPx() },
                )?.takeIf { !it.vertical }
            } else {
                null
            }
            val bookSplit = if (window.posture == Posture.Book) {
                playerFoldSplit(window.fold, insets, density, direction, constraints.maxWidth, constraints.maxHeight)?.takeIf { it.vertical }
            } else {
                null
            }
            val layout = shareLayoutOf(maxWidth.value.toInt(), tabletopSplit != null)
            when (layout) {
                ShareLayout.Phone -> Column(Modifier.fillMaxSize()) {
                    ShareTopBar(onBack, onRandom, null)
                    preview(Modifier.fillMaxWidth().fillMaxHeight(0.42f).padding(12.dp))
                    options(66.dp, Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                    ActionBar(buttons, Modifier.fillMaxWidth().background(scheme.surfaceContainerLow).padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 16.dp))
                }
                ShareLayout.TwoPane -> Column(Modifier.fillMaxSize()) {
                    ShareTopBar(onBack, onRandom, buttons)
                    TwoPaneBody(bookSplit, preview) { modifier -> options(96.dp, modifier) }
                }
                ShareLayout.Tabletop -> TabletopBody(tabletopSplit!!, onBack, onRandom, buttons, preview, options)
            }
            SnackbarHost(
                snackbar,
                when (layout) {
                    ShareLayout.Phone -> Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp)
                    ShareLayout.TwoPane -> Modifier.align(Alignment.BottomEnd).padding(24.dp).widthIn(max = 360.dp)
                    ShareLayout.Tabletop -> Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)
                },
            )
        }
    }

    editingColor?.let { editingText ->
        val before = remember(editingText) { prefs }
        val background = Color(prefs.customBackground)
        val content = Color(prefs.customContent)
        ColorPickerSheet(
            title = if (editingText) "文字色" else "背景色",
            subtitle = "分享卡片 · 自定义配色",
            initial = if (editingText) content else background,
            background = background,
            text = content,
            editingText = editingText,
            pairs = true,
            coverSwatches = coverSwatches(colors),
            recent = lyricStage.recentColors.mapNotNull(::parseHexColor),
            stageBeside = window.widthDp >= 600,
            chromeHint = false,
            onChange = { color -> prefs = prefs.withCustom(editingText, color) },
            onAutoText = { color -> prefs = prefs.copy(customContent = color.toArgb()) },
            onDone = { color ->
                val done = prefs.withCustom(editingText, color)
                update(done)
                actions.setLyricStage { it.copy(recentColors = pushRecentColor(it.recentColors, color)) }
                editingColor = null
            },
            onCancel = {
                prefs = before
                editingColor = null
            },
        )
    }
}

private fun SharePrefs.withCustom(text: Boolean, color: Color): SharePrefs =
    if (text) copy(customContent = color.toArgb()) else copy(customBackground = color.toArgb())

/** 配色对应的底色、字色：鲜艳 / 柔和是封面色（Rush 的 dominant / muted），播放页是 PlayerTheme 的表面色和文字色。 */
private fun cardColors(prefs: SharePrefs, colors: StageColors): Pair<Color, Color> = when (prefs.palette) {
    ShareCardPalette.Vibrant -> colors.coverVibrant to colors.onCoverVibrant
    ShareCardPalette.Muted -> colors.coverMuted to colors.onCoverMuted
    ShareCardPalette.Player -> colors.surface to colors.text
    ShareCardPalette.Custom -> Color(prefs.customBackground) to Color(prefs.customContent)
}

private fun cardLook(
    style: ShareCardStyle,
    prefs: SharePrefs,
    container: Color,
    content: Color,
    corner: Float,
    withSub: Boolean,
    player: PlayerCardColors,
) = ShareCardLook(
    style = style,
    container = container,
    content = content,
    corner = corner.dp,
    artShape = prefs.shape.toShape(),
    translation = withSub,
    player = player,
    // Rush 分享卡这里是 darken(1)，歌词页背景是 darken(2)
    hypnotic = rushGradientColors(container.lighten(2f), container.darken(1f), RUSH_HYPNOTIC_COLORS),
)

/** 样式条里的一张缩略卡：真卡片，按当前选项（目标值，不跟过渡），不录制。 */
@Composable
private fun ThumbCard(
    style: ShareCardStyle,
    prefs: SharePrefs,
    session: ShareSession,
    rows: List<ShareRow>,
    colors: StageColors,
    player: PlayerCardColors,
    images: ShareImages,
    withSub: Boolean,
) {
    val (bg, fg) = cardColors(prefs, colors)
    val look = cardLook(style, prefs, bg, fg, prefs.corners.px.toFloat(), withSub, player)
    val data = ShareCardData(session.song.title, session.song.artist, rowsForStyle(style, rows), session.messySeed)
    ScaledToFit(allowUpscale = true) {
        CardPixels { ShareCard(data, look, images) }
    }
}

// ---------- 预览 ----------

/**
 * 卡片预览：缩放到放得下（全屏画布不放大），双指放大 1–4 倍、平移夹在内容范围内、双击复位，换样式或开关全屏时复位。
 * 缩放都在录制之外（Rush 原话：ScaledToFit 在摆放时缩放，不影响录进图层的内容）。
 */
@Composable
private fun SharePreview(
    prefs: SharePrefs,
    look: ShareCardLook,
    data: ShareCardData,
    images: ShareImages,
    canvasColor: Color,
    cardLayer: GraphicsLayer,
    canvasLayer: GraphicsLayer,
    modifier: Modifier,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(prefs.style, prefs.fullScreen) {
        zoom = 1f
        offset = Offset.Zero
    }
    Box(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                    val maxX = size.width * (zoom - 1f) / 2f
                    val maxY = size.height * (zoom - 1f) / 2f
                    offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    zoom = 1f
                    offset = Offset.Zero
                })
            }
            .semantics { contentDescription = "卡片预览，双指放大，双击复位" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = offset.x
                translationY = offset.y
            },
        ) {
            ScaledToFit(allowUpscale = !prefs.fullScreen) {
                CardPixels {
                    ShareCanvas(prefs.fullScreen, canvasColor, canvasLayer) {
                        ShareCard(data, look, images, Modifier.recordInto(cardLayer))
                    }
                }
            }
        }
    }
}

// ---------- 三种布局 ----------

@Composable
private fun ShareTopBar(onBack: () -> Unit, onRandom: () -> Unit, buttons: ShareButtons?) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回选句") }
        Text(
            "分享歌词",
            Modifier.weight(1f).padding(start = 4.dp),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
        )
        IconButton(onClick = onRandom) { Icon(Icons.Filled.Casino, "随机换一套") }
        if (buttons != null) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = buttons.onCopy, enabled = buttons.busy == null) {
                ActionLabel(Icons.Filled.ContentCopy, "复制文字", buttons.busy == ShareAction.Copy)
            }
            TextButton(onClick = buttons.onSave, enabled = buttons.busy == null && buttons.canExport) {
                ActionLabel(Icons.Filled.Save, "保存图片", buttons.busy == ShareAction.Save)
            }
            Button(onClick = buttons.onShare, enabled = buttons.busy == null && buttons.canExport, modifier = Modifier.padding(start = 4.dp, end = 8.dp)) {
                ActionLabel(Icons.Filled.Share, "分享", buttons.busy == ShareAction.Share)
            }
        }
    }
}

private class ShareButtons(
    val busy: ShareAction?,
    val canExport: Boolean,
    val onCopy: () -> Unit,
    val onSave: () -> Unit,
    val onShare: () -> Unit,
)

@Composable
private fun ActionLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, busy: Boolean) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    } else {
        Icon(icon, null, Modifier.size(18.dp))
    }
    Spacer(Modifier.width(6.dp))
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** 手机、桌面姿态底部的操作条：复制文字、保存图片（tonal）、分享（实心），各占三分之一。 */
@Composable
private fun ActionBar(buttons: ShareButtons, modifier: Modifier) {
    val padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = buttons.onCopy, enabled = buttons.busy == null, modifier = Modifier.weight(1f), contentPadding = padding) {
            ActionLabel(Icons.Filled.ContentCopy, "复制文字", buttons.busy == ShareAction.Copy)
        }
        FilledTonalButton(
            onClick = buttons.onSave,
            enabled = buttons.busy == null && buttons.canExport,
            modifier = Modifier.weight(1f),
            contentPadding = padding,
        ) {
            ActionLabel(Icons.Filled.Save, "保存图片", buttons.busy == ShareAction.Save)
        }
        Button(
            onClick = buttons.onShare,
            enabled = buttons.busy == null && buttons.canExport,
            modifier = Modifier.weight(1f),
            contentPadding = padding,
        ) {
            ActionLabel(Icons.Filled.Share, "分享", buttons.busy == ShareAction.Share)
        }
    }
}

/**
 * 两栏：左边预览、右边 420dp 的选项栏（圆角 28dp、surfaceContainerLow）。书本姿态（[book]）按折痕分：
 * 预览占左页、选项栏占右页（宽度就是右页减 12dp），都不跨折痕。
 */
@Composable
private fun ColumnScope.TwoPaneBody(
    book: FoldSplit?,
    preview: @Composable (Modifier) -> Unit,
    options: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    Row(Modifier.weight(1f).fillMaxWidth()) {
        if (book != null) {
            val left = with(density) { book.firstEnd.toDp() }
            val gap = with(density) { (book.secondStart - book.firstEnd).toDp() }
            val right = with(density) { book.secondSize.toDp() }
            preview(Modifier.width(left).fillMaxHeight().padding(24.dp))
            Spacer(Modifier.width(gap))
            OptionsPane(right, options)
        } else {
            preview(Modifier.weight(1f).fillMaxHeight().padding(24.dp))
            OptionsPane(420.dp + 12.dp, options)
        }
    }
}

@Composable
private fun RowScope.OptionsPane(width: Dp, options: @Composable (Modifier) -> Unit) {
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .padding(end = 12.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        options(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp))
    }
}

/** 桌面姿态：上半屏顶栏 + 预览（立着看），下半屏选项 + 操作条（平放着点），折痕两侧什么都不放。 */
@Composable
private fun TabletopBody(
    split: FoldSplit,
    onBack: () -> Unit,
    onRandom: () -> Unit,
    buttons: ShareButtons,
    preview: @Composable (Modifier) -> Unit,
    options: @Composable (Dp, Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val top = with(density) { split.firstEnd.toDp() }
    val gap = with(density) { (split.secondStart - split.firstEnd).toDp() }
    val bottom = with(density) { split.secondSize.toDp() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().height(top)) {
            ShareTopBar(onBack, onRandom, null)
            preview(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
        Spacer(Modifier.height(gap))
        Column(Modifier.fillMaxWidth().height(bottom).padding(top = 10.dp)) {
            options(80.dp, Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp))
            ActionBar(buttons, Modifier.fillMaxWidth().height(72.dp).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}

// ---------- 选项 ----------

/**
 * 选项区（三种布局共用，顺序照稿子）：样式缩略图一排 → 用句提示 → 配色（自定义时两个色块）→ 卡片圆角 → 封面形状 →
 * 全屏 9:16、背景图（叠卡 / 封面）、重新打乱（乱排）、副行（有翻译或罗马音时）、文字格式。
 */
@Composable
private fun ShareOptions(
    prefs: SharePrefs,
    session: ShareSession,
    subline: LyricSubline,
    thumbWidth: Dp,
    modifier: Modifier,
    thumb: @Composable (ShareCardStyle) -> Unit,
    onChange: (SharePrefs) -> Unit,
    onPickPicture: () -> Unit,
    onEditColor: (text: Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        StyleStrip(prefs.style, thumbWidth, thumb) { style ->
            val (next, memo) = applyStyle(prefs, style, session.paletteBeforeBrat)
            session.paletteBeforeBrat = memo
            onChange(next)
        }
        unusedLinesNote(prefs.style, session.selected.size)?.let { note ->
            Text(note, Modifier.padding(top = 6.dp, start = 4.dp), fontSize = 14.sp, color = scheme.onSurfaceVariant)
        }

        SectionLabel("配色")
        ConnectedToggleGroup(
            options = ShareCardPalette.entries,
            selected = prefs.palette,
            onSelect = { palette ->
                // 在粗糙卡上手动点了配色：离开粗糙卡时不再换回
                session.paletteBeforeBrat = null
                onChange(prefs.copy(palette = palette))
            },
            label = { it.label },
            compact = true,
        )
        if (prefs.palette == ShareCardPalette.Custom) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorSwatchButton("背景", Color(prefs.customBackground), Modifier.weight(1f)) { onEditColor(false) }
                ColorSwatchButton("文字", Color(prefs.customContent), Modifier.weight(1f)) { onEditColor(true) }
            }
        }

        SectionLabel("卡片圆角")
        ConnectedToggleGroup(ShareCardCorners.entries, prefs.corners, { onChange(prefs.copy(corners = it)) }, { it.label })

        val noArt = prefs.style == ShareCardStyle.Brat
        SectionLabel(if (noArt) "封面形状 · 粗糙样式不放封面" else "封面形状") {
            Text(prefs.shape.label, fontSize = 13.sp, color = scheme.onSurfaceVariant)
        }
        ShapeGrid(prefs.shape, Modifier.alpha(if (noArt) 0.38f else 1f)) { onChange(prefs.copy(shape = it)) }

        Spacer(Modifier.height(16.dp))
        val rows = buildList<@Composable (Int, Int) -> Unit> {
            add { i, n ->
                SwitchRow(
                    "全屏 9:16",
                    prefs.fullScreen,
                    { onChange(prefs.copy(fullScreen = it)) },
                    i,
                    n,
                    summary = "卡片放进 1080×1920 的画布，适合发动态",
                )
            }
            if (prefs.style == ShareCardStyle.Rushed || prefs.style == ShareCardStyle.AlbumArt) {
                add { i, n ->
                    val chosen = session.picture != null
                    ListRow(
                        "背景图",
                        i,
                        n,
                        onClick = { if (chosen) session.picture = null else onPickPicture() },
                        summary = if (chosen) "已换成自选图片" else "默认用封面；可以从相册换一张",
                        trailing = {
                            IconButton(onClick = { if (chosen) session.picture = null else onPickPicture() }) {
                                Icon(if (chosen) Icons.Filled.Close else Icons.Filled.Image, if (chosen) "清掉自选图片" else "从相册选一张")
                            }
                        },
                    )
                }
            }
            if (prefs.style == ShareCardStyle.Messy) {
                add { i, n ->
                    ListRow(
                        "重新打乱",
                        i,
                        n,
                        onClick = { session.messySeed++ },
                        summary = "大小写、粗细、字宽、角度、反色每个词重新随机",
                        trailing = { Icon(Icons.Filled.Casino, null, Modifier.padding(end = 12.dp), tint = scheme.onSurfaceVariant) },
                    )
                }
            }
            if (session.hasSubline) {
                add { i, n ->
                    val label = sublineLabel(subline)
                    SwitchRow(label, prefs.translation, { onChange(prefs.copy(translation = it)) }, i, n, summary = "每句下面带一行$label")
                }
            }
            add { i, n -> TextFormatRow(prefs.textFormat, session.link.isNotBlank(), i, n) { onChange(prefs.copy(textFormat = it)) } }
        }
        SegmentedGroup {
            rows.forEachIndexed { i, row -> row(i, rows.size) }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** 副行开关的叫法，跟着「歌词下面那一行」的设置。 */
private fun sublineLabel(subline: LyricSubline): String = when (subline) {
    LyricSubline.Translation -> "译文"
    LyricSubline.Romanization -> "罗马音"
    LyricSubline.Both -> "译文和罗马音"
}

@Composable
private fun StyleStrip(selected: ShareCardStyle, width: Dp, thumb: @Composable (ShareCardStyle) -> Unit, onSelect: (ShareCardStyle) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
    ) {
        items(ShareCardStyle.entries, key = { it.name }) { style ->
            val on = style == selected
            val corner by animateDpAsState(if (on) 18.dp else 12.dp, motion.fastSpatialSpec(), label = "styleThumb")
            val shape = RoundedCornerShape(corner)
            Column(
                Modifier
                    .width(width)
                    .selectable(on, role = Role.RadioButton, onClick = { onSelect(style) })
                    .semantics(mergeDescendants = true) { contentDescription = "${style.label}（${style.rushName}）" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(92.dp)
                        .clip(shape)
                        .background(scheme.surfaceContainer)
                        .then(if (on) Modifier.border(2.5.dp, scheme.primary, shape) else Modifier)
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) { thumb(style) }
                Text(
                    style.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ColorSwatchButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(24.dp),
        color = color,
        contentColor = readableOn(color),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Edit, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 21 种封面形状，7 列 × 3 行；格子里是 58% 大小的剪影。 */
@Composable
private fun ShapeGrid(selected: ShareCardShape, modifier: Modifier, onSelect: (ShareCardShape) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ShareCardShape.entries.chunked(SHAPE_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { shape ->
                    val on = shape == selected
                    val corner by animateDpAsState(if (on) 18.dp else 12.dp, motion.fastSpatialSpec(), label = "shapeCell")
                    val cell = RoundedCornerShape(corner)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(cell)
                            .background(if (on) scheme.secondaryContainer else scheme.surfaceContainer)
                            .then(if (on) Modifier.border(2.dp, scheme.primary, cell) else Modifier)
                            .selectable(on, role = Role.RadioButton, onClick = { onSelect(shape) })
                            .semantics { contentDescription = shape.label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize(0.58f)
                                .background(if (on) scheme.primary else scheme.onSurfaceVariant, shape.toShape()),
                        )
                    }
                }
            }
        }
    }
}

private const val SHAPE_COLUMNS = 7

/** 文字格式：点开下拉三项；这首没有平台链接时第三项禁用。 */
@Composable
private fun TextFormatRow(format: ShareTextFormat, hasLink: Boolean, index: Int, count: Int, onSelect: (ShareTextFormat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListRow(
        "文字格式",
        index,
        count,
        onClick = { open = true },
        summary = "复制文字、分享时附带的文字：${format.label}",
        trailing = {
            Box {
                Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(16.dp)) {
                    ShareTextFormat.entries.forEach { option ->
                        val enabled = option != ShareTextFormat.WithLink || hasLink
                        DropdownMenuItem(
                            text = { Text(if (enabled) option.label else "${option.label}（这首没有链接）") },
                            onClick = {
                                open = false
                                onSelect(option)
                            },
                            enabled = enabled,
                        )
                    }
                }
            }
        },
    )
}
