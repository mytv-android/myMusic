package com.mytv.mymusic.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.mytv.mymusic.ui.theme.LocalAppearance

/** 外壳的共享元素作用域；只有手机上迷你播放器 ↔ 播放页之间用到。 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** 当前这一侧（迷你播放器或播放页）所在的 AnimatedVisibility。 */
val LocalCoverVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

const val NowPlayingCoverKey = "now-playing-cover"

/**
 * 正在播放的封面在迷你播放器和播放页之间飞过去：从 48dp 的圆角封面变成播放页的形状封面，收起时再飞回来。
 * [clip] 是飞行途中浮层的裁切：迷你播放器一侧是固定圆角，播放页一侧传一个随形变和旋转实时取轮廓的 Shape，
 * 浮层每帧按封面此刻的形状裁切，落地时和原位的形状对得上。
 * 没有作用域（平板、电视）或打开了减少动画时不做共享元素。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedNowPlayingCover(clip: Shape): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalCoverVisibilityScope.current ?: return this
    if (LocalAppearance.current.reduceMotion) return this
    return with(shared) {
        this@sharedNowPlayingCover.sharedElement(
            sharedContentState = rememberSharedContentState(NowPlayingCoverKey),
            animatedVisibilityScope = visibility,
            // 与播放页展开同一条空间弹簧（阻尼 0.8，刚度 380）
            boundsTransform = BoundsTransform { _, _ -> spring(dampingRatio = 0.8f, stiffness = 380f) },
            clipInOverlayDuringTransition = OverlayClip(clip),
        )
    }
}
