/*
 * AMLL (Apple Music-like Lyrics), https://github.com/amll-dev/applemusic-like-lyrics
 * Copyright (C) the AMLL contributors (amll-dev)
 * Modified for myMusic on 2026-11-28 (Kotlin / Jetpack Compose rewrite); the changes are listed below.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
// 2026-11-28 myMusic 改写：取自 AMLL core 0.7.2（@4d8e0270）的 bg-render/mesh-renderer（index.ts、cp-presets.ts、
// cp-generate.ts、mesh.vert.glsl、mesh.frag.glsl）和 bg-render/img.ts 的 blurImage。
// 照原样：封面缩到 32×32 → 对比 0.4 → 饱和 ×3 → 对比 1.7 → 亮度 0.75（按 Uint8ClampedArray 取整）→ 盒式模糊（半径 2、4 遍）；
// 六套控制点预设和随机生成（两成概率），双三次 Hermite 曲面（不带扭转项）；纹理坐标绕 (0.2, 0.2) 慢转、随低频响度放大，
// 镜像平铺；四周按距中心的 smoothstep 压暗到 60%；整体再乘 max(0.5, 1 − 响度 / 2)（原版先乘进颜色再当透明度合成，等于乘两次）；
// 换封面时新网格每毫秒 alpha 加 1/500、按 easeInOutSine 叠在上面，到 1.1 删掉旧的；没有封面时全部淡出。
// 改动：原版每张封面随机挑网格，这里用封面地址做种子（同一张封面每次一样）；网格细分从每片 50 降到约 41×41 个顶点（纹理本来就是模糊小图）；
// Android 9 以下的硬件加速不支持 drawVertices，这里改成每帧在 CPU 上按旋转后的纹理坐标给每个顶点取色（双线性、镜像），
// 用 drawBitmapMesh 画顶点色网格，四周压暗也乘进顶点色；画在半分辨率（电视 1/4）的离屏图层里；
// 暂停时流动停住，但换封面的淡入照样走完（原版暂停时连淡入也停）；减少动画时直接换、不转。抖动噪声不搬。
package com.mytv.mymusic.ui.player.stage.modes

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.mytv.mymusic.ui.player.TvFrameGate
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import com.mytv.mymusic.ui.player.stage.StageRandom
import com.mytv.mymusic.ui.player.stage.stableHash
import com.mytv.mymusic.ui.theme.coverColorCacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 一个控制点的配置（cp-presets.ts 的 p()）：格子坐标、位置（−1..1，左下角为原点）、两条切线的旋转（度）和长度倍数。 */
internal class AmllControlPoint(
    val cx: Int,
    val cy: Int,
    val x: Double,
    val y: Double,
    val ur: Double = 0.0,
    val vr: Double = 0.0,
    val up: Double = 1.0,
    val vp: Double = 1.0,
)

internal class AmllMeshPreset(val width: Int, val height: Int, val points: List<AmllControlPoint>)

private fun p(cx: Int, cy: Int, x: Double, y: Double, ur: Double = 0.0, vr: Double = 0.0, up: Double = 1.0, vp: Double = 1.0) =
    AmllControlPoint(cx, cy, x, y, ur, vr, up, vp)

/** CONTROL_POINT_PRESETS，数值照抄。 */
internal val AMLL_MESH_PRESETS: List<AmllMeshPreset> = listOf(
    AmllMeshPreset(
        5, 5,
        listOf(
            p(0, 0, -1.0, -1.0), p(1, 0, -0.5, -1.0), p(2, 0, 0.0, -1.0), p(3, 0, 0.5, -1.0), p(4, 0, 1.0, -1.0),
            p(0, 1, -1.0, -0.5), p(1, 1, -0.5, -0.5),
            p(2, 1, -0.0052029684413368305, -0.6131420587090777),
            p(3, 1, 0.5884227308309977, -0.3990805107556692),
            p(4, 1, 1.0, -0.5),
            p(0, 2, -1.0, 0.0),
            p(1, 2, -0.4210024670505933, -0.11895058380429502),
            p(2, 2, -0.1019613423315412, -0.023812118047224606, 0.0, -47.0, 0.629, 0.849),
            p(3, 2, 0.40275125660925437, -0.06345314544600389),
            p(4, 2, 1.0, 0.0),
            p(0, 3, -1.0, 0.5),
            p(1, 3, 0.06801958477287173, 0.5205913248960121, -31.0, -45.0, 1.0, 1.0),
            p(2, 3, 0.21446469120128908, 0.29331610114301043, 6.0, -56.0, 0.566, 1.321),
            p(3, 3, 0.5, 0.5), p(4, 3, 1.0, 0.5),
            p(0, 4, -1.0, 1.0),
            p(1, 4, -0.31378372841550195, 1.0),
            p(2, 4, 0.26153633255328046, 1.0),
            p(3, 4, 0.5, 1.0), p(4, 4, 1.0, 1.0),
        ),
    ),
    AmllMeshPreset(
        4, 4,
        listOf(
            p(0, 0, -1.0, -1.0), p(1, 0, -0.33333333333333337, -1.0), p(2, 0, 0.33333333333333326, -1.0), p(3, 0, 1.0, -1.0),
            p(0, 1, -1.0, -0.04495399932657351),
            p(1, 1, -0.24056117520129328, -0.22465999020104),
            p(2, 1, 0.334758885767489, -0.00531297192779423),
            p(3, 1, 0.9989920470678106, -0.3382976020775408, 8.0, 0.0, 0.566, 1.792),
            p(0, 2, -1.0, 0.33333333333333326),
            p(1, 2, -0.3425497314639411, -0.000027501607956947893),
            p(2, 2, 0.3321437945812673, 0.1981776353859399),
            p(3, 2, 1.0, 0.0766118180296832),
            p(0, 3, -1.0, 1.0), p(1, 3, -0.33333333333333337, 1.0), p(2, 3, 0.33333333333333326, 1.0), p(3, 3, 1.0, 1.0),
        ),
    ),
    AmllMeshPreset(
        4, 4,
        listOf(
            p(0, 0, -1.0, -1.0, 0.0, 0.0, 1.0, 2.075),
            p(1, 0, -0.33333333333333337, -1.0), p(2, 0, 0.33333333333333326, -1.0), p(3, 0, 1.0, -1.0),
            p(0, 1, -1.0, -0.4545779491139603),
            p(1, 1, -0.33333333333333337, -0.33333333333333337),
            p(2, 1, 0.0889403142626457, -0.6025711180694033, -32.0, 45.0, 1.0, 1.0),
            p(3, 1, 1.0, -0.33333333333333337),
            p(0, 2, -1.0, -0.07402408608567845, 1.0, 0.0, 1.0, 0.094),
            p(1, 2, -0.2719422694359541, 0.09775369930903222, 25.0, -18.0, 1.321, 0.0),
            p(2, 2, 0.19877414408395877, 0.4307383294587789, 48.0, -40.0, 0.755, 0.975),
            p(3, 2, 1.0, 0.33333333333333326, -37.0, 0.0, 1.0, 1.0),
            p(0, 3, -1.0, 1.0), p(1, 3, -0.33333333333333337, 1.0),
            p(2, 3, 0.5125850864305672, 1.0, -20.0, -18.0, 0.0, 1.604),
            p(3, 3, 1.0, 1.0),
        ),
    ),
    AmllMeshPreset(
        5, 5,
        listOf(
            p(0, 0, -1.0, -1.0),
            p(1, 0, -0.4501953125, -1.0, 0.0, 55.0, 1.0, 2.075),
            p(2, 0, 0.1953125, -1.0),
            p(3, 0, 0.4580078125, -1.0, 0.0, -25.0, 1.0, 1.0),
            p(4, 0, 1.0, -1.0),
            p(0, 1, -1.0, -0.2514475377525607, -16.0, 0.0, 2.327, 0.943),
            p(1, 1, -0.55859375, -0.6609325945787148, 47.0, 0.0, 2.358, 0.377),
            p(2, 1, 0.232421875, -0.5244375756366635, -66.0, -25.0, 1.855, 1.164),
            p(3, 1, 0.685546875, -0.3753706470552125),
            p(4, 1, 1.0, -0.6699125300354287),
            p(0, 2, -1.0, 0.035910396862284255),
            p(1, 2, -0.4921875, 0.005378616309457018, 90.0, 23.0, 1.0, 1.981),
            p(2, 2, 0.021484375, -0.1365043639066228, 0.0, 42.0, 1.0, 1.0),
            p(3, 2, 0.4765625, 0.05925822904974043, -30.0, 0.0, 1.95, 0.44),
            p(4, 2, 1.0, 0.251428847823418),
            p(0, 3, -1.0, 0.6968336464764276, -68.0, 0.0, 1.0, 0.786),
            p(1, 3, -0.6904296875, 0.5890744209958608, -68.0, 0.0, 1.0, 1.0),
            p(2, 3, 0.1845703125, 0.3879238667654693, 61.0, 0.0, 1.0, 1.0),
            p(3, 3, 0.60546875, 0.4633553246018661, -47.0, -59.0, 0.849, 1.73),
            p(4, 3, 1.0, 0.6214021886400309, -33.0, 0.0, 0.377, 1.604),
            p(0, 4, -1.0, 1.0),
            p(1, 4, -0.5, 1.0, 0.0, -73.0, 1.0, 1.0),
            p(2, 4, -0.3271484375, 1.0, 0.0, -24.0, 0.314, 2.704),
            p(3, 4, 0.5, 1.0),
            p(4, 4, 1.0, 1.0),
        ),
    ),
    AmllMeshPreset(
        5, 5,
        listOf(
            p(0, 0, -1.0, -1.0),
            p(1, 0, -0.6393, -1.0, 0.0, 0.0, 1.0, 2.3884),
            p(2, 0, 0.0, -1.0), p(3, 0, 0.5, -1.0), p(4, 0, 1.0, -1.0),
            p(0, 1, -1.0, -0.2301),
            p(1, 1, -0.6934, -0.331, 0.0, -0.7188, 1.0, 1.063),
            p(2, 1, -0.0082, -0.6814, -0.2583, 0.0, 1.0964, 1.0),
            p(3, 1, 0.5836, -0.531, 0.7029, 0.0, 1.5466, 1.0),
            p(4, 1, 1.0, -0.6407),
            p(0, 2, -1.0, 0.2973, 0.0, 0.0, 1.8352, 1.0),
            p(1, 2, -0.4082, 0.0602),
            p(2, 2, -0.1803, -0.3646, -0.2998, 0.0, 1.1513, 1.0),
            p(3, 2, 0.477, -0.1027, 0.8903, -0.1882, 1.0807, 0.8551),
            p(4, 2, 1.0, -0.2973),
            p(0, 3, -1.0, 0.7628, 0.0, 0.0, 2.3868, 1.0),
            p(1, 3, -0.2525, 0.4814, -0.8406, -1.6199, 1.4093, 1.2215),
            p(2, 3, 0.3607, 0.2814, -1.0713, -0.0529, 1.0025, 0.7611),
            p(3, 3, 0.4885, 0.623, 0.0, 0.8184, 1.0, 1.2876),
            p(4, 3, 1.0, 0.5),
            p(0, 4, -1.0, 1.0), p(1, 4, -0.4033, 1.0), p(2, 4, 0.2672, 1.0), p(3, 4, 0.5967, 1.0), p(4, 4, 1.0, 1.0),
        ),
    ),
    AmllMeshPreset(
        5, 5,
        listOf(
            p(0, 0, -1.0, -1.0), p(1, 0, -0.2197, -1.0), p(2, 0, 0.0197, -1.0), p(3, 0, 0.8033, -1.0), p(4, 0, 1.0, -1.0),
            p(0, 1, -1.0, -0.5451),
            p(1, 1, -0.4885, -0.4035, -1.0246, -0.2268, 1.1936, 0.8005),
            p(2, 1, -0.1213, -0.2867, 0.0, -0.6981, 1.0, 0.809),
            p(3, 1, 0.3246, -0.5628, 0.0, -1.2188, 1.0, 1.044),
            p(4, 1, 1.0, -0.3292),
            p(0, 2, -1.0, 0.1416),
            p(1, 2, -0.341, -0.0142, 0.0, -0.4004, 1.0, 1.1293),
            p(2, 2, -0.0393, -0.023, 0.2915, -0.373, 1.044, 0.9879),
            p(3, 2, 0.3148, -0.0673, -0.7853, -0.8962, 1.4709, 1.0247),
            p(4, 2, 1.0, 0.1912),
            p(0, 3, -1.0, 0.5),
            p(1, 3, -0.2689, 0.2743, 0.3404, -0.5248, 1.0184, 0.4391),
            p(2, 3, 0.0721, 0.269, 0.5302, 0.1244, 0.6723, 0.3225),
            p(3, 3, 0.4148, 0.3894, -0.6977, -0.6783, 0.8094, 0.9247),
            p(4, 3, 1.0, 0.446),
            p(0, 4, -1.0, 1.0), p(1, 4, -0.7311, 1.0), p(2, 4, 0.323, 1.0), p(3, 4, 0.6393, 1.0), p(4, 4, 1.0, 1.0),
        ),
    ),
)

/**
 * 挑一套控制点（createMeshState）：先抽一次，大于 0.8 时随机生成 6×6，否则在六套预设里抽一套。[random] 返回 0 ≤ x < 1。
 */
internal fun amllPickPreset(random: () -> Double): AmllMeshPreset =
    if (random() > 0.8) amllGenerateControlPoints(6, 6, random) else AMLL_MESH_PRESETS[floor(random() * AMLL_MESH_PRESETS.size).toInt()]

/**
 * 随机生成控制点（cp-generate.ts 的 generateControlPoints，宽高都给定时）：默认参数按原版的求值顺序各抽一次，
 * 再逐点（边上的点不动）抽扰动、旋转、长度，沿平滑噪声的梯度推一把，最后做几轮 3×3 加权平滑。
 */
internal fun amllGenerateControlPoints(w: Int, h: Int, random: () -> Double): AmllMeshPreset {
    fun range(min: Double, max: Double) = random() * (max - min) + min
    val variationFraction = range(0.4, 0.6)
    val normalOffset = range(0.3, 0.6)
    val blendFactor = 0.8
    val smoothIters = floor(range(3.0, 5.0)).toInt()
    val smoothFactor = range(0.2, 0.3)
    val smoothModifier = range(-0.1, -0.05)
    val conf = ArrayList<AmllControlPoint>(w * h)
    val dx = if (w == 1) 0.0 else 2.0 / (w - 1)
    val dy = if (h == 1) 0.0 else 2.0 / (h - 1)
    for (j in 0 until h) {
        for (i in 0 until w) {
            val baseX = (if (w == 1) 0.0 else i.toDouble() / (w - 1)) * 2 - 1
            val baseY = (if (h == 1) 0.0 else j.toDouble() / (h - 1)) * 2 - 1
            val border = i == 0 || i == w - 1 || j == 0 || j == h - 1
            val pertX = if (border) 0.0 else range(-variationFraction * dx, variationFraction * dx)
            val pertY = if (border) 0.0 else range(-variationFraction * dy, variationFraction * dy)
            var x = baseX + pertX
            var y = baseY + pertY
            val ur = if (border) 0.0 else range(-60.0, 60.0)
            val vr = if (border) 0.0 else range(-60.0, 60.0)
            val up = if (border) 1.0 else range(0.8, 1.2)
            val vp = if (border) 1.0 else range(0.8, 1.2)
            if (!border) {
                val uNorm = (baseX + 1) / 2
                val vNorm = (baseY + 1) / 2
                val (nx, ny) = noiseGradient(uNorm, vNorm)
                var offsetX = nx * normalOffset
                var offsetY = ny * normalOffset
                val distToBorder = minOf(uNorm, 1 - uNorm, vNorm, 1 - vNorm)
                val weight = smoothstep(0.0, 1.0, distToBorder)
                offsetX *= weight
                offsetY *= weight
                x = x * (1 - blendFactor) + (x + offsetX) * blendFactor
                y = y * (1 - blendFactor) + (y + offsetY) * blendFactor
            }
            conf += AmllControlPoint(i, j, x, y, ur, vr, up, vp)
        }
    }
    smoothify(conf, w, h, smoothIters, smoothFactor, smoothModifier)
    return AmllMeshPreset(w, h, conf)
}

private fun smoothify(conf: MutableList<AmllControlPoint>, w: Int, h: Int, iterations: Int, factor: Double, modifier: Double) {
    var grid = Array(h) { j -> Array(w) { i -> conf[j * w + i] } }
    var f = factor
    val kernel = arrayOf(intArrayOf(1, 2, 1), intArrayOf(2, 4, 2), intArrayOf(1, 2, 1))
    repeat(iterations) {
        val next = Array(h) { j ->
            Array(w) { i ->
                if (i == 0 || i == w - 1 || j == 0 || j == h - 1) return@Array grid[j][i]
                var sx = 0.0
                var sy = 0.0
                var sur = 0.0
                var svr = 0.0
                var sup = 0.0
                var svp = 0.0
                for (dj in -1..1) {
                    for (di in -1..1) {
                        val weight = kernel[dj + 1][di + 1]
                        val nb = grid[j + dj][i + di]
                        sx += nb.x * weight
                        sy += nb.y * weight
                        sur += nb.ur * weight
                        svr += nb.vr * weight
                        sup += nb.up * weight
                        svp += nb.vp * weight
                    }
                }
                val cur = grid[j][i]
                AmllControlPoint(
                    i, j,
                    cur.x * (1 - f) + sx / 16 * f,
                    cur.y * (1 - f) + sy / 16 * f,
                    cur.ur * (1 - f) + sur / 16 * f,
                    cur.vr * (1 - f) + svr / 16 * f,
                    cur.up * (1 - f) + sup / 16 * f,
                    cur.vp * (1 - f) + svp / 16 * f,
                )
            }
        }
        grid = next
        f = amllClamp01(f + modifier)
    }
    for (j in 0 until h) for (i in 0 until w) conf[j * w + i] = grid[j][i]
}

private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
    val t = amllClamp01((x - edge0) / (edge1 - edge0))
    return t * t * (3 - 2 * t)
}

private fun fract(x: Double): Double = x - floor(x)

private fun noise(x: Double, y: Double): Double = fract(sin(x * 12.9898 + y * 78.233) * 43758.5453)

private fun smoothNoise(x: Double, y: Double): Double {
    val x0 = floor(x)
    val y0 = floor(y)
    val xf = x - x0
    val yf = y - y0
    val u = xf * xf * (3 - 2 * xf)
    val v = yf * yf * (3 - 2 * yf)
    val nx0 = noise(x0, y0) * (1 - u) + noise(x0 + 1, y0) * u
    val nx1 = noise(x0, y0 + 1) * (1 - u) + noise(x0 + 1, y0 + 1) * u
    return nx0 * (1 - v) + nx1 * v
}

private fun noiseGradient(x: Double, y: Double, epsilon: Double = 0.001): Pair<Double, Double> {
    val dx = (smoothNoise(x + epsilon, y) - smoothNoise(x - epsilon, y)) / (2 * epsilon)
    val dy = (smoothNoise(x, y + epsilon) - smoothNoise(x, y - epsilon)) / (2 * epsilon)
    val length = sqrt(dx * dx + dy * dy).takeIf { it != 0.0 } ?: 1.0
    return dx / length to dy / length
}

/**
 * 网格的顶点（BHPMesh.updateMesh），去掉片与片之间重复的那排后排成 [columns] × [rows] 的规则格子：
 * 第 j 行第 i 列的纹理坐标是 (i / (columns − 1), j / (rows − 1))，[x] / [y] 是裁剪空间的位置（−1..1，y 朝上）。
 * 每片按 [subdivisions] 细分（原版 50），片内的参数和原版一样是 i / (细分 − 1)。
 */
internal class AmllMeshGeometry(val columns: Int, val rows: Int, val x: DoubleArray, val y: DoubleArray)

internal fun amllMeshGeometry(preset: AmllMeshPreset, subdivisions: Int = amllSubdivisions(preset)): AmllMeshGeometry {
    val w = preset.width
    val h = preset.height
    // 控制点：位置、u 切线（cos, sin）× 长度、v 切线（−sin, cos）× 长度；没配的点按 resizeControlPoints 的默认值
    val lx = DoubleArray(w * h)
    val ly = DoubleArray(w * h)
    val ux = DoubleArray(w * h)
    val uy = DoubleArray(w * h)
    val vx = DoubleArray(w * h)
    val vy = DoubleArray(w * h)
    val uPower = 2.0 / (w - 1)
    val vPower = 2.0 / (h - 1)
    for (cy in 0 until h) {
        for (cx in 0 until w) {
            val k = cx + cy * w
            lx[k] = cx.toDouble() / (w - 1) * 2 - 1
            ly[k] = cy.toDouble() / (h - 1) * 2 - 1
            ux[k] = uPower
            vy[k] = vPower
        }
    }
    for (cp in preset.points) {
        val k = cp.cx + cp.cy * w
        val uRot = cp.ur * PI / 180
        val vRot = cp.vr * PI / 180
        val uScale = uPower * cp.up
        val vScale = vPower * cp.vp
        lx[k] = cp.x
        ly[k] = cp.y
        ux[k] = cos(uRot) * uScale
        uy[k] = sin(uRot) * uScale
        vx[k] = -sin(vRot) * vScale
        vy[k] = cos(vRot) * vScale
    }
    val step = subdivisions - 1
    val columns = (w - 1) * step + 1
    val rows = (h - 1) * step + 1
    val outX = DoubleArray(columns * rows)
    val outY = DoubleArray(columns * rows)
    val accX = Array((w - 1) * (h - 1)) { DoubleArray(16) }
    val accY = Array((w - 1) * (h - 1)) { DoubleArray(16) }
    val m = DoubleArray(16)
    for (px in 0 until w - 1) {
        for (py in 0 until h - 1) {
            val k00 = px + py * w
            val k01 = px + (py + 1) * w
            val k10 = px + 1 + py * w
            val k11 = px + 1 + (py + 1) * w
            coefficients(lx, vx, ux, k00, k01, k10, k11, m)
            precompute(m, accX[px + py * (w - 1)])
            coefficients(ly, vy, uy, k00, k01, k10, k11, m)
            precompute(m, accY[px + py * (w - 1)])
        }
    }
    val tu = DoubleArray(4)
    for (j in 0 until rows) {
        // uv.y = 1 − sY − u / 总细分：第 j 行对应曲面上从下往上数的第 rows − 1 − j 排
        val g = rows - 1 - j
        val py = min(g / step, h - 2)
        val u = (g - py * step).toDouble() / step
        for (i in 0 until columns) {
            val px = min(i / step, w - 2)
            val v = (i - px * step).toDouble() / step
            val index = j * columns + i
            outX[index] = evaluate(accX[px + py * (w - 1)], u, v, tu)
            outY[index] = evaluate(accY[px + py * (w - 1)], u, v, tu)
        }
    }
    return AmllMeshGeometry(columns, rows, outX, outY)
}

/** 每片细分多少：整张网格约 40 格。 */
internal fun amllSubdivisions(preset: AmllMeshPreset): Int = 40 / (max(preset.width, preset.height) - 1) + 1

/** meshCoefficients：一片的 4×4 系数矩阵（列主序，和 gl-matrix 一样）。 */
private fun coefficients(l: DoubleArray, v: DoubleArray, u: DoubleArray, k00: Int, k01: Int, k10: Int, k11: Int, out: DoubleArray) {
    out[0] = l[k00]
    out[1] = l[k01]
    out[2] = v[k00]
    out[3] = v[k01]
    out[4] = l[k10]
    out[5] = l[k11]
    out[6] = v[k10]
    out[7] = v[k11]
    out[8] = u[k00]
    out[9] = u[k01]
    out[10] = 0.0
    out[11] = 0.0
    out[12] = u[k10]
    out[13] = u[k11]
    out[14] = 0.0
    out[15] = 0.0
}

private val HERMITE = doubleArrayOf(2.0, -2.0, 1.0, 1.0, -3.0, 3.0, -2.0, -1.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0)
private val HERMITE_T = transpose(HERMITE)

/** precomputeMatrix：Hᵀ · Mᵀ · H。 */
private fun precompute(m: DoubleArray, out: DoubleArray) {
    val t = transpose(m)
    val a = multiply(t, HERMITE)
    multiply(HERMITE_T, a).copyInto(out)
}

/** 曲面上 (u, v) 处的值：[u³, u², u, 1] 经矩阵变换后和 [v³, v², v, 1] 点乘（Vec4.transformMat4 是列主序的 M · a）。 */
private fun evaluate(acc: DoubleArray, u: Double, v: Double, tmp: DoubleArray): Double {
    val a0 = u * u * u
    val a1 = u * u
    for (r in 0 until 4) tmp[r] = acc[r] * a0 + acc[4 + r] * a1 + acc[8 + r] * u + acc[12 + r]
    return v * v * v * tmp[0] + v * v * tmp[1] + v * tmp[2] + tmp[3]
}

/** gl-matrix 的 mat4.multiply：out = a · b（列主序）。 */
private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
    val out = DoubleArray(16)
    for (col in 0 until 4) {
        for (row in 0 until 4) {
            var sum = 0.0
            for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = sum
        }
    }
    return out
}

private fun transpose(m: DoubleArray): DoubleArray = DoubleArray(16) { m[(it % 4) * 4 + it / 4] }

/**
 * 处理封面（setAlbum 里那段）：[rgba] 是 32×32 的 RGBA 字节（0..255），原地改成对比 0.4 → 饱和 ×3 → 对比 1.7 → 亮度 0.75，
 * 每步之后不取整，最后按 Uint8ClampedArray 存（夹到 0..255、四舍六入五取偶）；再按 blurImage(半径 2, 4 遍) 模糊。
 */
internal fun amllProcessCover(rgba: IntArray, width: Int, height: Int) {
    var i = 0
    while (i < rgba.size) {
        var r = rgba[i].toDouble()
        var g = rgba[i + 1].toDouble()
        var b = rgba[i + 2].toDouble()
        r = (r - 128) * 0.4 + 128
        g = (g - 128) * 0.4 + 128
        b = (b - 128) * 0.4 + 128
        val gray = r * 0.3 + g * 0.59 + b * 0.11
        r = gray * -2.0 + r * 3.0
        g = gray * -2.0 + g * 3.0
        b = gray * -2.0 + b * 3.0
        r = (r - 128) * 1.7 + 128
        g = (g - 128) * 1.7 + 128
        b = (b - 128) * 1.7 + 128
        rgba[i] = amllClampByte(r * 0.75)
        rgba[i + 1] = amllClampByte(g * 0.75)
        rgba[i + 2] = amllClampByte(b * 0.75)
        i += 4
    }
    amllBlurImage(rgba, width, height, 2, 4)
}

/** JS 的 Uint8ClampedArray 赋值（ToUint8Clamp）：夹到 0..255，正好在 .5 上时取偶数。 */
internal fun amllClampByte(value: Double): Int {
    if (value.isNaN() || value <= 0.0) return 0
    if (value >= 255.0) return 255
    val f = floor(value)
    return when {
        f + 0.5 < value -> f.toInt() + 1
        value < f + 0.5 -> f.toInt()
        f.toInt() % 2 == 0 -> f.toInt()
        else -> f.toInt() + 1
    }
}

/** img.ts 的 blurImage：横竖各一遍滑动窗口盒式模糊，重复 [quality] 遍；结果按 (和 × 系数 + 0.5) | 0 取整。 */
internal fun amllBlurImage(pixels: IntArray, width: Int, height: Int, radius: Int, quality: Int) {
    val wm = width - 1
    val hm = height - 1
    val rad1x = radius + 1
    val divx = radius + rad1x
    val rad1y = radius + 1
    val divy = radius + rad1y
    val div2 = 1.0 / (divx * divy)
    val size = width * height
    val r = IntArray(size)
    val g = IntArray(size)
    val b = IntArray(size)
    val a = IntArray(size)
    val vmin = IntArray(max(width, height))
    val vmax = IntArray(max(width, height))
    repeat(quality) {
        var yw = 0
        var yi = 0
        for (y in 0 until height) {
            var rsum = pixels[yw] * rad1x
            var gsum = pixels[yw + 1] * rad1x
            var bsum = pixels[yw + 2] * rad1x
            var asum = pixels[yw + 3] * rad1x
            for (i in 1..radius) {
                val p = yw + ((if (i > wm) wm else i) shl 2)
                rsum += pixels[p]
                gsum += pixels[p + 1]
                bsum += pixels[p + 2]
                asum += pixels[p + 3]
            }
            for (x in 0 until width) {
                r[yi] = rsum
                g[yi] = gsum
                b[yi] = bsum
                a[yi] = asum
                if (y == 0) {
                    vmin[x] = min(x + rad1x, wm) shl 2
                    vmax[x] = max(x - radius, 0) shl 2
                }
                val p1 = yw + vmin[x]
                val p2 = yw + vmax[x]
                rsum += pixels[p1] - pixels[p2]
                gsum += pixels[p1 + 1] - pixels[p2 + 1]
                bsum += pixels[p1 + 2] - pixels[p2 + 2]
                asum += pixels[p1 + 3] - pixels[p2 + 3]
                yi++
            }
            yw += width shl 2
        }
        for (x in 0 until width) {
            var yp = x
            var rsum = r[yp] * rad1y
            var gsum = g[yp] * rad1y
            var bsum = b[yp] * rad1y
            var asum = a[yp] * rad1y
            for (i in 1..radius) {
                yp += if (i > hm) 0 else width
                rsum += r[yp]
                gsum += g[yp]
                bsum += b[yp]
                asum += a[yp]
            }
            yi = x shl 2
            for (y in 0 until height) {
                pixels[yi] = (rsum * div2 + 0.5).toInt()
                pixels[yi + 1] = (gsum * div2 + 0.5).toInt()
                pixels[yi + 2] = (bsum * div2 + 0.5).toInt()
                pixels[yi + 3] = (asum * div2 + 0.5).toInt()
                if (x == 0) {
                    vmin[y] = min(y + rad1y, hm) * width
                    vmax[y] = max(y - radius, 0) * width
                }
                val p1 = x + vmin[y]
                val p2 = x + vmax[y]
                rsum += r[p1] - r[p2]
                gsum += g[p1] - g[p2]
                bsum += b[p1] - b[p2]
                asum += a[p1] - a[p2]
                yi += width shl 2
            }
        }
    }
}

/** 一张处理好的封面：32×32 的 RGBA 字节和它挑中的网格。 */
internal class AmllMeshTexture(val rgba: IntArray, val width: Int, val height: Int, val geometry: AmllMeshGeometry)

/**
 * 片元着色器里顶点用到的那部分：纹理坐标 (u, v) 绕 (0.2, 0.2) 转 [angle]、按 max(0.001, 1 − 2 × 响度) 缩放、移到 0.5，
 * 双线性、镜像平铺取样；乘整体系数 [factor]（max(0.5, 1 − 响度 / 2)）和四周压暗 0.6 + 0.4 × smoothstep(0.8, 0.3, 距中心)。
 * 结果写进 [out] 的 0..2（0..255）。
 */
internal fun amllMeshShade(texture: AmllMeshTexture, u: Double, v: Double, cosA: Double, sinA: Double, scale: Double, factor: Double, out: DoubleArray) {
    val cx = u - 0.2
    val cy = v - 0.2
    val fu = (cosA * cx - sinA * cy) * scale + 0.5
    val fv = (sinA * cx + cosA * cy) * scale + 0.5
    sample(texture, fu, fv, out)
    val dist = sqrt((u - 0.5) * (u - 0.5) + (v - 0.5) * (v - 0.5))
    val mask = (0.6 + smoothstep(0.8, 0.3, dist) * 0.4) * factor
    out[0] *= mask
    out[1] *= mask
    out[2] *= mask
}

/** GL 的 LINEAR + MIRRORED_REPEAT 取样。 */
private fun sample(texture: AmllMeshTexture, s: Double, t: Double, out: DoubleArray) {
    val w = texture.width
    val h = texture.height
    val x = s * w - 0.5
    val y = t * h - 0.5
    val x0 = floor(x)
    val y0 = floor(y)
    val fx = x - x0
    val fy = y - y0
    val i0 = mirror(x0.toInt(), w)
    val i1 = mirror(x0.toInt() + 1, w)
    val j0 = mirror(y0.toInt(), h)
    val j1 = mirror(y0.toInt() + 1, h)
    val px = texture.rgba
    for (c in 0 until 3) {
        val a = px[(j0 * w + i0) * 4 + c] * (1 - fx) + px[(j0 * w + i1) * 4 + c] * fx
        val b = px[(j1 * w + i0) * 4 + c] * (1 - fx) + px[(j1 * w + i1) * 4 + c] * fx
        out[c] = a * (1 - fy) + b * fy
    }
}

private fun mirror(i: Int, n: Int): Int {
    val period = n * 2
    val m = ((i % period) + period) % period
    return if (m < n) m else period - 1 - m
}

/** 一张网格的淡入状态（MeshState）：[alpha] 在 −0.1..1.1 之间走，画的时候按 easeInOutSine(clamp01(alpha))。 */
private class MeshLayer(val texture: AmllMeshTexture) {
    var alpha = 0.0
    var colors = IntArray(texture.geometry.columns * texture.geometry.rows)
    var verts = FloatArray(colors.size * 2)
    var vertsFor = 0L
}

/**
 * 网格背景的状态和绘制。[advance] 每帧推进淡入淡出和流动时间，[draw] 画黑底和每层网格（旧的在下）。
 */
internal class AmllMeshRenderer {
    private val layers = ArrayList<MeshLayer>()
    private var noCover = true
    private var timeMs = 0.0
    private val white: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
    private val paint = android.graphics.Paint()
    private val layerPaint = android.graphics.Paint()
    private val shade = DoubleArray(3)

    /** 低频响度（AMLL 的 setLowFreqVolume 除以 10 之后的值）。 */
    var volume = 0.0

    /** 换封面：null 是没有封面（全部淡出）。[immediate] 时直接到位、删掉旧的（减少动画）。 */
    fun setCover(texture: AmllMeshTexture?, immediate: Boolean) {
        if (texture == null) {
            noCover = true
            if (immediate) layers.clear()
            return
        }
        noCover = false
        val layer = MeshLayer(texture)
        if (immediate) {
            layers.clear()
            layer.alpha = 1.1
        }
        layers += layer
    }

    /** 淡入淡出都走完了，静止时不用再跑帧。 */
    val settled: Boolean
        get() = if (noCover) layers.isEmpty() else layers.size == 1 && layers[0].alpha >= 1.1

    /** 没有网格可画（没有封面、旧的也淡完了）：播放中也不用跑帧，换封面时再起。 */
    val empty: Boolean
        get() = layers.isEmpty()

    /** 推进 [deltaMs]；[flowing] 时纹理继续转（播放中、没有减少动画）。 */
    fun advance(deltaMs: Double, flowing: Boolean) {
        if (flowing) timeMs += deltaMs
        val step = deltaMs / 500
        val latest = layers.lastOrNull() ?: return
        if (noCover) {
            for (i in layers.indices.reversed()) {
                val layer = layers[i]
                if (layer.alpha <= -0.1) layers.removeAt(i) else layer.alpha = max(-0.1, layer.alpha - step)
            }
        } else if (latest.alpha >= 1.1) {
            if (layers.size > 1) {
                layers.retainAll(listOf(latest))
            }
        } else {
            latest.alpha = min(1.1, latest.alpha + step)
        }
    }

    fun draw(scope: DrawScope) {
        val w = scope.size.width
        val h = scope.size.height
        scope.drawRect(Color.Black)
        if (layers.isEmpty() || w <= 0f || h <= 0f) return
        val angle = (timeMs / 10_000 + volume) * 2.0
        val cosA = cos(angle)
        val sinA = sin(angle)
        val scale = max(0.001, 1 - volume * 2)
        val factor = max(0.5, 1 - volume * 0.5)
        val sizeKey = (w.toBits().toLong() shl 32) or (h.toBits().toLong() and 0xFFFFFFFFL)
        scope.drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            for (layer in layers) {
                val geometry = layer.texture.geometry
                val shown = easeInOutSine(amllClamp01(layer.alpha))
                val alpha = (shown * factor).toFloat()
                if (alpha <= 0.002f) continue
                if (layer.vertsFor != sizeKey) {
                    val side = max(w, h)
                    for (k in 0 until geometry.columns * geometry.rows) {
                        layer.verts[k * 2] = (w / 2 + geometry.x[k] * side / 2).toFloat()
                        layer.verts[k * 2 + 1] = (h / 2 - geometry.y[k] * side / 2).toFloat()
                    }
                    layer.vertsFor = sizeKey
                }
                val colors = layer.colors
                for (j in 0 until geometry.rows) {
                    val v = j.toDouble() / (geometry.rows - 1)
                    for (i in 0 until geometry.columns) {
                        val u = i.toDouble() / (geometry.columns - 1)
                        amllMeshShade(layer.texture, u, v, cosA, sinA, scale, factor, shade)
                        colors[j * geometry.columns + i] = (0xFF shl 24) or
                            (shade[0].roundToInt().coerceIn(0, 255) shl 16) or
                            (shade[1].roundToInt().coerceIn(0, 255) shl 8) or
                            shade[2].roundToInt().coerceIn(0, 255)
                    }
                }
                // 原版先把网格画进帧缓冲（不混合），再整张按透明度叠上来：网格自己折叠的地方不会叠两次
                val layered = alpha < 0.999f
                if (layered) {
                    layerPaint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
                    native.saveLayer(0f, 0f, w, h, layerPaint)
                }
                native.drawBitmapMesh(white, geometry.columns - 1, geometry.rows - 1, layer.verts, 0, colors, 0, paint)
                if (layered) native.restore()
            }
        }
    }
}

private fun easeInOutSine(x: Double): Double = -(cos(PI * x) - 1) / 2

/** 取封面、缩到 32×32、处理，再按封面地址挑网格；取不到时返回 null。 */
internal suspend fun loadAmllMeshTexture(context: Context, url: String): AmllMeshTexture? {
    if (url.isBlank()) return null
    // 和取色共用 128px 的软件位图和缓存键，通常直接命中刚取过色的那张
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(AMLL_COVER_SAMPLE_PX)
        .allowHardware(false)
        .memoryCacheKey(coverColorCacheKey(url))
        .build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    return withContext(Dispatchers.Default) {
        runCatching {
            val source = result.image.toBitmap()
            val small = Bitmap.createScaledBitmap(source, AMLL_COVER_PX, AMLL_COVER_PX, true)
            val argb = IntArray(AMLL_COVER_PX * AMLL_COVER_PX)
            small.getPixels(argb, 0, AMLL_COVER_PX, 0, 0, AMLL_COVER_PX, AMLL_COVER_PX)
            if (small !== source) small.recycle()
            val rgba = IntArray(argb.size * 4)
            for (k in argb.indices) {
                val c = argb[k]
                rgba[k * 4] = (c shr 16) and 0xFF
                rgba[k * 4 + 1] = (c shr 8) and 0xFF
                rgba[k * 4 + 2] = c and 0xFF
                rgba[k * 4 + 3] = (c ushr 24) and 0xFF
            }
            amllProcessCover(rgba, AMLL_COVER_PX, AMLL_COVER_PX)
            val random = StageRandom(stableHash(url))
            val preset = amllPickPreset { random.next().toDouble() }
            AmllMeshTexture(rgba, AMLL_COVER_PX, AMLL_COVER_PX, amllMeshGeometry(preset))
        }.getOrNull()
    }
}

/**
 * 网格背景：黑底上叠封面揉成的网格。播放中纹理慢慢转、随低频响度放大；换封面时新网格 0.5 秒淡入。
 * 画在 1/2（电视 1/4）分辨率的离屏图层里再放大；帧循环只在播放中（或淡入淡出没走完）时跑，电视上限 30 帧。
 */
@Composable
internal fun AmllMeshBackground(ctx: LyricStageContext, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val renderer = remember { AmllMeshRenderer() }
    val tick = remember { mutableIntStateOf(0) }
    val generation = remember { mutableIntStateOf(0) }
    val reduceMotion = ctx.display.reduceMotion
    val tv = ctx.display.tv
    val cover = ctx.song.cover
    val reduce by rememberUpdatedState(reduceMotion)
    LaunchedEffect(cover) {
        val texture = loadAmllMeshTexture(context, cover)
        renderer.setCover(texture, immediate = reduce)
        generation.intValue++
        tick.intValue++
    }
    val playing = !ctx.paused.value
    val audio = ctx.audio
    val flowing = playing && !reduceMotion
    LaunchedEffect(renderer, flowing, tv, generation.intValue) {
        if ((!flowing && renderer.settled) || renderer.empty) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val done = withFrameNanos { now ->
                if (tv && !TvFrameGate.due(now)) return@withFrameNanos false
                val deltaMs = ((now - last) / 1e6).coerceIn(0.0, 100.0)
                last = now
                renderer.volume = if (audio != null && flowing) audio.bass.coerceIn(0f, 1f) / 10.0 else 0.0
                renderer.advance(deltaMs, flowing)
                tick.intValue++
                (!flowing && renderer.settled) || renderer.empty
            }
            if (done) break
        }
    }
    val downscale = if (tv) 4 else 2
    Box(modifier.fillMaxSize().clipToBounds()) {
        Spacer(
            Modifier
                .fillMaxSize()
                .amllReducedResolution(downscale)
                .drawBehind {
                    tick.intValue
                    renderer.draw(this)
                },
        )
    }
}

/** 按 1/[factor] 的尺寸布局、再用离屏图层放大回原尺寸（和流光背景同一个做法）。 */
private fun Modifier.amllReducedResolution(factor: Int): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val placeable = measurable.measure(Constraints.fixed(((width + factor - 1) / factor).coerceAtLeast(1), ((height + factor - 1) / factor).coerceAtLeast(1)))
    layout(width, height) {
        placeable.placeWithLayer(0, 0) {
            scaleX = factor.toFloat()
            scaleY = factor.toFloat()
            transformOrigin = TransformOrigin(0f, 0f)
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }
}

private const val AMLL_COVER_PX = 32
private const val AMLL_COVER_SAMPLE_PX = 128
