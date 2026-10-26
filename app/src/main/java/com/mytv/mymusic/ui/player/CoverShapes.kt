package com.mytv.mymusic.ui.player

import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.RoundedPolygon
import com.mytv.mymusic.data.CoverShapeChoice
import kotlin.random.Random

/** 设置里显示的名字。 */
val CoverShapeChoice.label: String
    get() = when (this) {
        CoverShapeChoice.Random -> "随机"
        CoverShapeChoice.Cookie12 -> "十二瓣"
        CoverShapeChoice.Cookie9 -> "九瓣"
        CoverShapeChoice.Cookie7 -> "七瓣"
        CoverShapeChoice.Sunny -> "太阳"
        CoverShapeChoice.SoftBurst -> "星芒"
        CoverShapeChoice.Clover8 -> "八叶"
    }

/** 对应的 MaterialShapes 原形；“随机”没有固定形状，由播放页按切歌抽取。 */
val CoverShapeChoice.polygon: RoundedPolygon?
    get() = when (this) {
        CoverShapeChoice.Random -> null
        CoverShapeChoice.Cookie12 -> MaterialShapes.Cookie12Sided
        CoverShapeChoice.Cookie9 -> MaterialShapes.Cookie9Sided
        CoverShapeChoice.Cookie7 -> MaterialShapes.Cookie7Sided
        CoverShapeChoice.Sunny -> MaterialShapes.Sunny
        CoverShapeChoice.SoftBurst -> MaterialShapes.SoftBurst
        CoverShapeChoice.Clover8 -> MaterialShapes.Clover8Leaf
    }

/** 随机候选：除“随机”外的 6 种具体形状。 */
val RandomCoverShapes: List<CoverShapeChoice> = CoverShapeChoice.entries.filter { it != CoverShapeChoice.Random }

/**
 * 暂停态统一用圆角方形看全图（形状会裁掉封面约一成的边角），不参与随机。
 * 用 getter 而不是初始化值：MaterialShapes 自己会缓存，也免得只用随机函数时提前构造多边形。
 */
val PausedCoverPolygon: RoundedPolygon get() = MaterialShapes.Square

/** 切歌时抽下一种形状：不和上一首重复；previous 为空或“随机”时 6 种都可能。 */
fun nextRandomCoverShape(previous: CoverShapeChoice?, random: Random = Random.Default): CoverShapeChoice {
    val pool = RandomCoverShapes.filter { it != previous }
    return pool[random.nextInt(pool.size)]
}
