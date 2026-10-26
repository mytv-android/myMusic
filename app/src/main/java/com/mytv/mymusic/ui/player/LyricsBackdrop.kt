package com.mytv.mymusic.ui.player

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.LyricsBackgroundMode
import com.mytv.mymusic.playback.RhythmFrame
import com.mytv.mymusic.ui.theme.LocalAppearance
import androidx.compose.material3.MaterialTheme

@Composable
fun LyricsBackdrop(
    cover: String,
    playing: Boolean,
    mode: LyricsBackgroundMode,
    trackKey: String,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalAppearance.current.reduceMotion
    when {
        mode == LyricsBackgroundMode.Off -> Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        mode == LyricsBackgroundMode.Reactive && !reduceMotion -> ReactiveBackdrop(cover, playing, trackKey, wide, modifier)
        else -> AmbientBackdrop(cover, playing, modifier)
    }
}

@Composable
private fun ReactiveBackdrop(cover: String, playing: Boolean, trackKey: String, wide: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val analyzer = remember(context) { (context.applicationContext as? MyMusicApp)?.rhythmAnalyzer }
    if (analyzer == null) {
        AmbientBackdrop(cover, playing, modifier)
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, analyzer, playing) {
        fun updateCapture() {
            analyzer.setActive(
                playing && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            )
        }
        val observer = LifecycleEventObserver { _, _ -> updateCapture() }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateCapture()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            analyzer.setActive(false)
        }
    }

    // This state is read only in graphicsLayer/draw callbacks, so 30 fps updates redraw
    // the backdrop without recomposing the player and its lyrics list.
    val frame = remember { mutableStateOf(RhythmFrame()) }
    // 同步重置不需要协程；带 key 的 SideEffect 保留切歌执行顺序且不引入挂起开销
    SideEffect(analyzer, trackKey) { analyzer.reset() }
    LaunchedEffect(analyzer) { analyzer.frames.collect { frame.value = it } }

    val scheme = MaterialTheme.colorScheme
    val request = remember(context, cover) { ImageRequest.Builder(context).data(cover).size(24).build() }
    Box(modifier.fillMaxSize().clipToBounds().background(scheme.surface)) {
        if (cover.isNotBlank()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val value = frame.value
                        val scale = 1.06f + value.energy * 0.055f + value.pulse * 0.075f
                        scaleX = scale
                        scaleY = scale
                        translationX = value.energy * size.width * 0.018f
                        translationY = -value.energy * size.height * 0.012f
                        alpha = 0.76f + value.energy * 0.10f
                    },
            ) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(44.dp) else Modifier),
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val bassCenter = Offset(size.width * 0.10f, size.height * 0.16f)
                    val midCenter = Offset(size.width * 0.90f, size.height * 0.84f)
                    val trebleCenter = Offset(size.width * 0.80f, size.height * 0.12f)
                    val bassBrush = rhythmBrush(scheme.primary, bassCenter, size.maxDimension * 0.76f)
                    val midBrush = rhythmBrush(scheme.tertiary, midCenter, size.maxDimension * 0.66f)
                    val trebleBrush = rhythmBrush(scheme.secondary, trebleCenter, size.maxDimension * 0.48f)
                    val pulseBrush = Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.42f),
                        0.45f to Color.White.copy(alpha = 0.10f),
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.5f, size.height * 0.44f),
                        radius = size.maxDimension * 0.58f,
                    )
                    onDrawBehind {
                        val value = frame.value
                        val bass = value.bass
                        val mid = value.mid
                        val treble = value.treble
                        val pulse = value.pulse
                        drawRect(Color.Black.copy(alpha = 0.16f * (1f - value.energy) * (1f - pulse * 0.65f)))
                        withTransform({
                            translate(bass * size.width * 0.08f, bass * size.height * 0.045f)
                            val factor = 1f + bass * 0.18f + pulse * 0.08f
                            scale(factor, factor, bassCenter)
                        }) {
                            drawRect(
                                bassBrush,
                                alpha = (0.06f + bass * 0.32f + pulse * 0.06f).coerceAtMost(0.46f),
                                blendMode = BlendMode.Screen,
                            )
                        }
                        withTransform({
                            translate(-mid * size.width * 0.065f, -mid * size.height * 0.04f)
                            val factor = 1f + mid * 0.14f
                            scale(factor, factor, midCenter)
                        }) {
                            drawRect(
                                midBrush,
                                alpha = 0.04f + mid * 0.28f + pulse * 0.025f,
                                blendMode = BlendMode.Screen,
                            )
                        }
                        withTransform({
                            translate(treble * size.width * 0.035f, -treble * size.height * 0.03f)
                            val factor = 1f + pulse * 0.24f
                            scale(factor, factor, trebleCenter)
                        }) {
                            drawRect(
                                trebleBrush,
                                alpha = 0.025f + treble * 0.22f + pulse * 0.08f,
                                blendMode = BlendMode.Screen,
                            )
                        }
                        drawRect(
                            pulseBrush,
                            alpha = 0.015f + value.energy * 0.035f + pulse * 0.20f,
                            blendMode = BlendMode.Screen,
                        )
                    }
                },
        )
        Box(Modifier.fillMaxSize().background(reactiveShade(scheme.surface, wide)))
    }
}

private fun reactiveShade(surface: Color, wide: Boolean): Brush = if (wide) {
    Brush.horizontalGradient(
        0f to surface.copy(alpha = 0.08f),
        0.38f to surface.copy(alpha = 0.14f),
        0.68f to surface.copy(alpha = 0.34f),
        1f to surface.copy(alpha = 0.72f),
    )
} else {
    Brush.verticalGradient(
        0f to surface.copy(alpha = 0.08f),
        0.45f to surface.copy(alpha = 0.18f),
        1f to surface.copy(alpha = 0.74f),
    )
}

private fun rhythmBrush(color: Color, center: Offset, radius: Float): Brush = Brush.radialGradient(
    0f to color.copy(alpha = 0.78f),
    0.30f to color.copy(alpha = 0.48f),
    0.62f to color.copy(alpha = 0.13f),
    1f to Color.Transparent,
    center = center,
    radius = radius,
)
