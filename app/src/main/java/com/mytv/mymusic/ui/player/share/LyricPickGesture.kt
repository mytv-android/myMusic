package com.mytv.mymusic.ui.player.share

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

/**
 * 全屏歌词长按选句的探针：PlayerScreen 持有一个，经 [LocalLyricPickProbe] 发给各排法。
 * 舞台槽位装 [lyricPickGesture]（长按），列表类模式的行装 [lyricPickTarget]（登记自己是第几句，长按时才量位置）。
 * 电视不提供探针，整套都不进组合。
 */
@Stable
internal class LyricPickProbe {
    /** 此刻是不是「全屏歌词页」且能分享（有歌词、没在加载、没开着分享）：PlayerScreen 每次组合时写，手势和读屏操作都先看它。 */
    var enabled by mutableStateOf(false)

    /** 长按 / 读屏操作命中后回调；参数是全屏歌词行表的下标，认不出是哪句时为 null（用当前句）。 */
    internal var onRequest: (Int?) -> Unit = {}

    private val targets = HashMap<Any, Target>()

    private class Target(val line: Int, val coords: LayoutCoordinates)

    internal fun register(key: Any, line: Int, coords: LayoutCoordinates) {
        targets[key] = Target(line, coords)
    }

    internal fun unregister(key: Any) {
        targets.remove(key)
    }

    /** 根坐标 [root] 落在哪一句上（只量还挂着的行，滚出视口的行矩形为空）。 */
    fun lineAt(root: Offset): Int? = hitLine(
        targets.values.mapNotNull { t -> t.coords.takeIf { it.isAttached }?.let { t.line to it.boundsInRoot() } },
        root,
    )

    fun request(line: Int?) {
        if (enabled) onRequest(line)
    }
}

internal val LocalLyricPickProbe = staticCompositionLocalOf<LyricPickProbe?> { null }

/** 读屏的自定义操作「选这一句分享」：[line] 为 null 时用当前句。TalkBack 的双击长按本来也会走长按手势。 */
internal fun pickShareAction(probe: LyricPickProbe, line: Int?): CustomAccessibilityAction =
    CustomAccessibilityAction("选这一句分享") {
        probe.request(line)
        true
    }

/** 落在哪些行的矩形里，取最矮的那个（嵌套时取最里层）；矩形为空的（滚出视口、被裁掉）不算。 */
internal fun hitLine(targets: List<Pair<Int, Rect>>, at: Offset): Int? =
    targets.filter { (_, rect) -> rect.width > 0f && rect.height > 0f && rect.contains(at) }
        .minByOrNull { (_, rect) -> rect.height }
        ?.first

/** 这一行是全屏歌词行表的第 [line] 句：长按落在它上面时选它。[probe] 为 null 时什么都不做。 */
internal fun Modifier.lyricPickTarget(probe: LyricPickProbe?, line: Int): Modifier =
    if (probe == null) this else this then PickTargetElement(probe, line)

private class PickTargetElement(val probe: LyricPickProbe, val line: Int) : ModifierNodeElement<PickTargetNode>() {
    override fun create() = PickTargetNode(probe, line)
    override fun update(node: PickTargetNode) = node.update(probe, line)
    override fun equals(other: Any?) = other is PickTargetElement && other.probe === probe && other.line == line
    override fun hashCode() = 31 * System.identityHashCode(probe) + line
}

/** 摆放时只登记坐标对象的引用（滚动时只是一次 map 写），真正量矩形在长按触发那一下。 */
private class PickTargetNode(var probe: LyricPickProbe, var line: Int) : Modifier.Node(), LayoutAwareModifierNode {
    private var coords: LayoutCoordinates? = null

    override fun onPlaced(coordinates: LayoutCoordinates) {
        coords = coordinates
        probe.register(this, line, coordinates)
    }

    override fun onDetach() {
        probe.unregister(this)
        coords = null
    }

    fun update(probe: LyricPickProbe, line: Int) {
        if (probe !== this.probe) this.probe.unregister(this)
        this.probe = probe
        this.line = line
        coords?.let { probe.register(this, line, it) }
    }
}

/**
 * 舞台槽位上的长按：在 Initial 阶段只看不吃，直到按满长按时长、手指没动、也没被子项先吃掉（列表开始拖），
 * 才算长按；这时按命中的行（[LyricPickProbe.lineAt]）请求选句，并吃掉这次手势剩下的事件——子项的点按跳转
 * （clickable 看到被消费的抬起会取消）、列表拖动都不再响应。长按前点按、拖动、Rush 的点句跳转都照常。
 */
internal fun Modifier.lyricPickGesture(probe: LyricPickProbe?): Modifier =
    if (probe == null) this else this then PickGestureElement(probe)

private class PickGestureElement(val probe: LyricPickProbe) : ModifierNodeElement<PickGestureNode>() {
    override fun create() = PickGestureNode(probe)
    override fun update(node: PickGestureNode) {
        node.probe = probe
    }
    override fun equals(other: Any?) = other is PickGestureElement && other.probe === probe
    override fun hashCode() = System.identityHashCode(probe)
}

private class PickGestureNode(var probe: LyricPickProbe) : DelegatingNode() {
    init {
        delegate(SuspendingPointerInputModifierNode { detect() })
    }

    private suspend fun PointerInputScope.detect() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!probe.enabled) return@awaitEachGesture
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var cancelled = false
                while (!cancelled) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    cancelled = change == null || event.changes.size > 1 || !change.pressed || change.isConsumed ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
                false
            } == null
            if (!held || !probe.enabled) return@awaitEachGesture
            val at = requireLayoutCoordinates().localToRoot(down.position)
            probe.request(probe.lineAt(at))
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}
