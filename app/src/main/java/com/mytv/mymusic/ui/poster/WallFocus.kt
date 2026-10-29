/*
 * 海报墙的方向键焦点、OK 长按、返回键。方向键评分改编自 chthollyphile/folia-major 的 Lattice（AGPL-3.0）：
 * https://github.com/chthollyphile/folia-major
 *
 * 墙是虚拟的、无限的，屏幕外的格子根本没有组合出来，Compose 的 focusSearch 找不到它们，所以墙自己收方向键、自己记焦点
 * （空间导航在 [WallScene.initialFocus] / [WallScene.focusNeighbour]）；浮层按钮仍走普通焦点。
 * 这里只放纯逻辑，按键事件的接线在 PosterWall.kt。
 */
package com.mytv.mymusic.ui.poster

import androidx.compose.ui.input.key.Key
import kotlin.math.floor

/** 墙关心的按键。[Passthrough] 是墙既不唤醒也不吃掉的键（返回、音量、媒体键）；[Other] 只算一次操作，不吃掉。 */
internal enum class WallKey(val dx: Int = 0, val dy: Int = 0) {
    Left(dx = -1),
    Right(dx = 1),
    Up(dy = -1),
    Down(dy = 1),
    Confirm,
    Menu,
    Other,
    Passthrough,
    ;

    val isArrow: Boolean get() = dx != 0 || dy != 0
}

/**
 * 按键换算。返回键交给页面的 BackHandler（它调 [PosterWallState.handleBack]，漂移中那一下同样只唤醒），
 * 所以这里不碰；音量和媒体键照常交给系统，漂移中按也不吞。
 */
internal fun wallKeyOf(key: Key): WallKey = when (key) {
    Key.DirectionLeft -> WallKey.Left
    Key.DirectionRight -> WallKey.Right
    Key.DirectionUp -> WallKey.Up
    Key.DirectionDown -> WallKey.Down
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> WallKey.Confirm
    Key.Menu -> WallKey.Menu
    Key.Back, Key.Escape, Key.Power,
    Key.VolumeUp, Key.VolumeDown, Key.VolumeMute,
    Key.MediaPlay, Key.MediaPause, Key.MediaPlayPause, Key.MediaStop,
    Key.MediaNext, Key.MediaPrevious, Key.MediaFastForward, Key.MediaRewind,
    -> WallKey.Passthrough
    else -> WallKey.Other
}

/** OK 键的结果：[Short] 松开时的短按，[Long] 按住到系统长按。 */
internal enum class ConfirmResult { None, Short, Long }

/**
 * OK 键按下不动作，松开才算短按；按住到系统的长按（第一次自动重复，Android 给它打 FLAG_LONG_PRESS）触发长按，
 * 之后的重复和松开都不再动作。和 Compose clickable 一样在松开时响应，这样长按不会先触发一次放大。
 */
internal class ConfirmPress {
    private var down = false
    private var longFired = false

    fun onDown(repeatCount: Int, longPress: Boolean): ConfirmResult {
        if (!down) {
            down = true
            longFired = false
        }
        if ((longPress || repeatCount > 0) && !longFired) {
            longFired = true
            return ConfirmResult.Long
        }
        return ConfirmResult.None
    }

    fun onUp(): ConfirmResult {
        val result = if (down && !longFired) ConfirmResult.Short else ConfirmResult.None
        down = false
        longFired = false
        return result
    }

    /** 焦点离开墙、页面被切走时丢掉按到一半的键。 */
    fun cancel() {
        down = false
        longFired = false
    }
}

/** 返回键在墙这一层做什么。 */
internal enum class WallBackStep {
    /** 漂移中：只唤醒，镜头弹回正在播放。 */
    Wake,

    /** Compose 焦点在大卡里面的按钮上：交回墙。 */
    ReturnFocus,

    /** 收起不是正在播放的大卡。 */
    Collapse,

    /** 清掉方向键焦点。 */
    ClearFocus,

    /** 墙这层没事可做，页面离开。 */
    Leave,
}

/**
 * 返回键的优先级。电视（交互表“先关面板，再收起非当前的大卡，再清掉焦点，最后离开”，面板由页面先关）：
 * 漂移中只唤醒 → 焦点在卡里先交回墙 → 收起非当前的大卡 → 清焦点 → 离开。
 * 手机 / 平板按交互表直接离开（面板同样由页面先关）；接了键盘、墙上有方向键焦点时先清焦点。
 */
internal fun wallBackStep(
    drifting: Boolean,
    focusInCard: Boolean,
    grownIsOther: Boolean,
    hasFocus: Boolean,
    isTv: Boolean,
): WallBackStep = when {
    drifting -> WallBackStep.Wake
    focusInCard -> WallBackStep.ReturnFocus
    isTv && grownIsOther -> WallBackStep.Collapse
    hasFocus -> WallBackStep.ClearFocus
    else -> WallBackStep.Leave
}

/**
 * 墙面上点 [p] 落在哪一格：只看点所在的块（格子不跨块），落在缝里、带子外返回 null。
 * 量的是放大重排后的目标位置；重排弹簧走到一半时点按的偏差可以忽略。
 */
internal fun WallScene.slotAt(p: WallPoint): SlotPos? {
    val bx = floor(p.x / metrics.blockWidth).toInt()
    val by = floor(p.y / metrics.blockHeight).toInt()
    val rects = rectsOf(bx, by)
    for (s in 0 until WallLayout.SLOTS) {
        val r = world(bx, by, rects[s])
        if (p.x >= r.x && p.x < r.right && p.y >= r.y && p.y < r.bottom) {
            return if (songAt(bx, by, s) >= 0) SlotPos(bx, by, s) else null
        }
    }
    return null
}

/** 切歌跟随、“定位”、唤醒时去的那一份：离整块屏幕中心最近的（原型取 vw/2、vh/2，不扣浮层）。 */
internal fun WallScene.followTarget(song: Int, cam: WallPoint, viewport: WallViewport): SlotPos? =
    if (song < 0) null else nearestCopy(song, cam, WallPoint(viewport.width / 2, viewport.height / 2))

/** 这一格坐的是还没出的歌（听歌模式队尾的占位格）。 */
internal fun WallField.isGhost(song: Int): Boolean = this is WallField.FeedBand && isGhost(song)
