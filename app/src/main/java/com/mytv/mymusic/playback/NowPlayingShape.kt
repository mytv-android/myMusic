package com.mytv.mymusic.playback

import com.mytv.mymusic.data.CoverShapeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** 随机候选：除“随机”外的 6 种具体形状。 */
val RandomCoverShapes: List<CoverShapeChoice> = CoverShapeChoice.entries.filter { it != CoverShapeChoice.Random }

/** 切歌时抽下一种形状：不和上一首重复；previous 为空或“随机”时 6 种都可能。 */
fun nextRandomCoverShape(previous: CoverShapeChoice?, random: Random = Random.Default): CoverShapeChoice {
    val pool = RandomCoverShapes.filter { it != previous }
    return pool[random.nextInt(pool.size)]
}

/**
 * “随机”封面形状的播放会话：记住当前这首歌（按 key）抽到的形状。
 * 关掉再打开播放页、几处封面同时显示时都读这一份；换歌才重新抽，且不和上一首重复。
 * 固定形状也记下来：再切回“随机”时这首保持当前形状，下一首才开始抽。
 */
class CoverShapeSession(private val random: Random = Random.Default) {
    private var key: String? = null
    private var shape: CoverShapeChoice? = null

    fun resolve(choice: CoverShapeChoice, songKey: String): CoverShapeChoice {
        if (choice != CoverShapeChoice.Random) {
            key = songKey
            shape = choice
            return choice
        }
        val current = shape
        if (songKey == key && current != null) return current
        return nextRandomCoverShape(current, random).also {
            key = songKey
            shape = it
        }
    }

    /** 进程重启后接上次的形状：同一首歌不重新抽。 */
    fun restore(songKey: String, choice: CoverShapeChoice) {
        if (choice == CoverShapeChoice.Random) return
        key = songKey
        shape = choice
    }
}

/**
 * 正在播放这首的封面形状。切歌时由播放层（ViewModel 跟着当前曲目）抽好，播放页和桌面小组件读同一个值；
 * 随播放队列一起落盘（[QueueStore]），App 没打开时组件也知道该裁成什么形状。
 */
object NowPlayingShape {
    private val session = CoverShapeSession()
    private val _current = MutableStateFlow(Current("", CoverShapeChoice.Cookie12))

    /** 当前曲目（key）和它的形状；还没有曲目时 key 为空。 */
    val current: StateFlow<Current> = _current.asStateFlow()

    data class Current(val songKey: String, val shape: CoverShapeChoice)

    @Synchronized
    fun resolve(choice: CoverShapeChoice, songKey: String): CoverShapeChoice {
        val shape = session.resolve(choice, songKey)
        _current.value = Current(songKey, shape)
        return shape
    }

    @Synchronized
    fun restore(songKey: String, choice: CoverShapeChoice) {
        session.restore(songKey, choice)
        if (choice != CoverShapeChoice.Random) _current.value = Current(songKey, choice)
    }
}
