package com.mytv.mymusic.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 拖动位移：移动时直接累加，不每次 move 启动协程；松手后只跑一个回弹协程。
 * 新手势开始时取消旧回弹，保留当前位移，避免两次手势的回弹互相覆盖。
 */
@Stable
internal class SettlingOffsetState(
    private val scope: CoroutineScope,
    private val animateToZero: suspend (from: Float, onValue: (Float) -> Unit) -> Unit = { from, onValue ->
        animate(from, 0f, animationSpec = spring()) { value, _ -> onValue(value) }
    },
) {
    var offset by mutableFloatStateOf(0f)
        private set

    private var job: Job? = null

    val isSettling: Boolean get() = job?.isActive == true

    fun begin() {
        job?.cancel()
        job = null
    }

    fun add(delta: Float) {
        offset += delta
    }

    fun settle() {
        job?.cancel()
        val from = offset
        job = scope.launch { animateToZero(from) { offset = it } }
    }
}

@Composable
internal fun rememberSettlingOffsetState(): SettlingOffsetState {
    val scope = rememberCoroutineScope()
    return remember(scope) { SettlingOffsetState(scope) }
}
