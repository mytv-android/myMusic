package com.mytv.mymusic.ui.player.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.data.LyricStagePrefs
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.player.blobColorCache
import com.mytv.mymusic.ui.player.coverBlobColors

/**
 * 动画面板里的缩略图：用模式自己的 [LyricVisualizer.Stage] 按“减少动态效果”画一张静帧（没有帧循环、不开律动），
 * 歌词是一句示例；宿主背景换成封面主色的静态渐变。经典画成三行的小列表。整块不进读屏，也不接收点按。
 * 字号随卡片宽度走（手机上 13sp），电视上卡片窄一些，示例句也不会折行压到副行。
 */
@Composable
internal fun StagePreview(mode: LyricVisualizer, song: Song?, prefs: LyricStagePrefs, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val cover = song?.cover.orEmpty()
    val coverColors by produceState(blobColorCache.get(cover), cover) { value = coverBlobColors(context, cover) }
    val colors = remember(scheme.onSurface, scheme.primary, scheme.tertiary, scheme.surface, coverColors) {
        val fallback = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primaryContainer)
        StageColors(
            text = scheme.onSurface,
            dim = scheme.onSurface.copy(alpha = 0.28f),
            accent = scheme.primary,
            accentAlt = scheme.tertiary,
            surface = scheme.surface,
            cover = List(4) { coverColors?.getOrNull(it) ?: fallback[it] },
        )
    }
    BoxWithConstraints(
        modifier
            .clearAndSetSemantics {}
            .background(scheme.surface),
    ) {
        val textSp = (maxWidth.value / CARD_DP_PER_TEXT_SP).coerceIn(MIN_PREVIEW_TEXT_SP, PREVIEW_TEXT_SP)
        if (mode.id == ClassicVisualizer.id) {
            PreviewBackdrop(colors)
            ClassicPreview(textSp / PREVIEW_TEXT_SP)
            return@BoxWithConstraints
        }
        val insets = remember { StageInsets() }
        val raw = prefs.pluginSettings[mode.id].orEmpty()
        val ctx = remember(mode, song, colors, raw, prefs.subline, textSp) {
            val clock = StageClock().apply { publish(SAMPLE_LINES[0].startMs + SAMPLE_LINES[0].durationMs * 6 / 10, 0L, 0f) }
            LyricStageContext(
                lines = SAMPLE_LINES,
                song = song ?: SAMPLE_SONG,
                seed = stableHash(mode.id),
                clock = clock,
                lineIndex = mutableIntStateOf(0),
                paused = mutableStateOf(false),
                colors = colors,
                display = StageDisplay(textSp, reduceMotion = true, tv = false, wide = true, insets = insets),
                settings = SettingValues(mode.settings, raw),
                audio = null,
                subline = prefs.subline,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                // 安全区留一圈边，模式按它排字
                .layout { measurable, constraints ->
                    val pad = 6.dp.toPx()
                    insets.set(pad, pad, pad, pad)
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
        ) {
            if (mode.hostBackground) PreviewBackdrop(colors) else mode.Background(ctx, Modifier.fillMaxSize())
            mode.Stage(ctx, Modifier.fillMaxSize())
            if (mode.hostSubtitles) HostSubtitle(ctx, 0.dp)
        }
    }
}

/** 宿主流光背景的静态替身：封面两个主色从左上、右下晕开。 */
@Composable
private fun PreviewBackdrop(colors: StageColors) {
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(colors.cover[0].copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * 0.1f),
                        radius = size.maxDimension * 0.75f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(colors.cover[1].copy(alpha = 0.45f), Color.Transparent),
                        center = Offset(size.width * 0.9f, size.height * 0.95f),
                        radius = size.maxDimension * 0.7f,
                    ),
                )
                drawRect(Color.Black.copy(alpha = 0.25f))
            },
    )
}

/** 经典：上一句、当前句（放大、亮）、下一句。 */
@Composable
private fun ClassicPreview(scale: Float) {
    val on = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier.fillMaxSize().padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Text(SAMPLE_PREVIOUS, color = on.copy(alpha = 0.45f), fontSize = 10.sp * scale, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            SAMPLE_LINES[0].text,
            color = on,
            fontSize = 14.sp * scale,
            lineHeight = 18.sp * scale,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            textAlign = TextAlign.Start,
        )
        Text(SAMPLE_LINES[1].text, color = on.copy(alpha = 0.45f), fontSize = 10.sp * scale, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val PREVIEW_TEXT_SP = 13f
private const val MIN_PREVIEW_TEXT_SP = 9f
/** 手机面板的卡片约 176dp 宽，配 13sp。 */
private const val CARD_DP_PER_TEXT_SP = 13.5f
private const val SAMPLE_PREVIOUS = "晚风把街灯一盏盏点亮"

private val SAMPLE_LINES = listOf(
    StageLine(0, 10_000L, 14_000L, "风吹过的地方都有光", emptyList(), "Where the wind goes, there is light", ""),
    StageLine(1, 14_500L, 18_500L, "我们沿着河走回家", emptyList(), "We walk home along the river", ""),
)

private val SAMPLE_SONG = Song(id = "preview", name = "示例")
