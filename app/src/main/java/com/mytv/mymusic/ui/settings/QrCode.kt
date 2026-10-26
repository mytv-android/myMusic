package com.mytv.mymusic.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs
import kotlin.math.floor

/**
 * 把登录链接画成二维码。多数平台只返回链接、不给二维码图片，而电视上只能扫码登录，所以在本机生成。
 * 二维码必须是浅底深码，扫码器才认，所以这里不跟随主题配色。
 */
@Composable
internal fun QrCodeImage(text: String, modifier: Modifier = Modifier, description: String = "登录二维码") {
    val matrix = key(text) {
        val result by produceState<Array<BooleanArray>?>(null, text) {
            value = withContext(Dispatchers.Default) { QrEncoder.encode(text) }
        }
        result
    }
    Spacer(
        modifier
            .semantics { contentDescription = description }
            .drawWithCache {
                // 所有黑格合并成一条 Path，一次 drawPath；尺寸变化时才重建
                val cells = (matrix?.size ?: 0) + 8
                val cell = if (matrix == null) 0f else floor(size.minDimension / cells)
                val path = if (matrix != null && cell > 0f) {
                    // 四周留 4 格静区；格子取整像素，避免抗锯齿在格子之间留下细缝
                    val originX = (size.width - cell * matrix.size) / 2f
                    val originY = (size.height - cell * matrix.size) / 2f
                    Path().apply {
                        for (y in matrix.indices) {
                            for (x in matrix.indices) {
                                if (matrix[y][x]) {
                                    addRect(Rect(originX + x * cell, originY + y * cell, originX + (x + 1) * cell, originY + (y + 1) * cell))
                                }
                            }
                        }
                    }
                } else {
                    null
                }
                onDrawBehind {
                    drawRect(Color.White)
                    path?.let { drawPath(it, Color.Black) }
                }
            },
    )
}

/**
 * 二维码编码：只做字节模式、纠错等级 M，版本按内容长度自动选择（1–40）。
 * 算法按 Nayuki 的 QR Code generator（MIT）实现。
 */
internal object QrEncoder {
    // 纠错等级 M：每块纠错码字数、块数，下标是版本号
    private val ECC_PER_BLOCK = intArrayOf(
        -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26,
        26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
    )
    private val NUM_BLOCKS = intArrayOf(
        -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16,
        17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
    )
    private const val FORMAT_BITS_M = 0

    fun encode(text: String): Array<BooleanArray>? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var version = 1
        while (4 + countBits(version) + bytes.size * 8 > dataCodewords(version) * 8) {
            version++
            if (version > 40) return null
        }
        val capacity = dataCodewords(version) * 8
        val bits = BitBuffer()
        bits.append(0x4, 4)
        bits.append(bytes.size, countBits(version))
        bytes.forEach { bits.append(it.toInt() and 0xFF, 8) }
        bits.append(0, minOf(4, capacity - bits.size))
        bits.append(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < capacity) {
            bits.append(pad, 8)
            pad = pad xor (0xEC xor 0x11)
        }
        val data = ByteArray(capacity / 8)
        for (i in 0 until bits.size) {
            if (bits[i]) data[i ushr 3] = (data[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
        }
        return QrMatrix(version).build(data)
    }

    private fun countBits(version: Int): Int = if (version <= 9) 8 else 16

    internal fun rawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val align = version / 7 + 2
            result -= (25 * align - 10) * align - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    private fun dataCodewords(version: Int): Int =
        rawDataModules(version) / 8 - ECC_PER_BLOCK[version] * NUM_BLOCKS[version]

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size: Int get() = bits.size
        operator fun get(index: Int): Boolean = bits[index]
        fun append(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits.add((value ushr i) and 1 != 0)
        }
    }

    private class QrMatrix(private val version: Int) {
        private val size = version * 4 + 17
        private val modules = Array(size) { BooleanArray(size) }
        private val function = Array(size) { BooleanArray(size) }

        fun build(data: ByteArray): Array<BooleanArray> {
            drawFunctionPatterns()
            drawCodewords(addEccAndInterleave(data))
            var best = 0
            var minPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask)
                drawFormatBits(mask)
                val penalty = penaltyScore()
                if (penalty < minPenalty) {
                    minPenalty = penalty
                    best = mask
                }
                applyMask(mask)
            }
            applyMask(best)
            drawFormatBits(best)
            return modules
        }

        private fun set(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            function[y][x] = true
        }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)
            val positions = alignmentPositions()
            val count = positions.size
            for (i in 0 until count) {
                for (j in 0 until count) {
                    val corner = (i == 0 && j == 0) || (i == 0 && j == count - 1) || (i == count - 1 && j == 0)
                    if (!corner) drawAlignment(positions[i], positions[j])
                }
            }
            drawFormatBits(0)
            drawVersion()
        }

        private fun drawFinder(x: Int, y: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val distance = maxOf(abs(dx), abs(dy))
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until size && yy in 0 until size) set(xx, yy, distance != 2 && distance != 4)
                }
            }
        }

        private fun drawAlignment(x: Int, y: Int) {
            for (dy in -2..2) {
                for (dx in -2..2) set(x + dx, y + dy, maxOf(abs(dx), abs(dy)) != 1)
            }
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val count = version / 7 + 2
            val step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
            val result = IntArray(count)
            result[0] = 6
            var position = size - 7
            for (i in count - 1 downTo 1) {
                result[i] = position
                position -= step
            }
            return result
        }

        private fun drawFormatBits(mask: Int) {
            val data = FORMAT_BITS_M shl 3 or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = (data shl 10 or rem) xor 0x5412
            for (i in 0..5) set(8, i, bit(bits, i))
            set(8, 7, bit(bits, 6))
            set(8, 8, bit(bits, 7))
            set(7, 8, bit(bits, 8))
            for (i in 9 until 15) set(14 - i, 8, bit(bits, i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(bits, i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(bits, i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = version shl 12 or rem
            for (i in 0 until 18) {
                val dark = bit(bits, i)
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, dark)
                set(b, a, dark)
            }
        }

        private fun addEccAndInterleave(data: ByteArray): ByteArray {
            val blocks = NUM_BLOCKS[version]
            val eccLength = ECC_PER_BLOCK[version]
            val raw = rawDataModules(version) / 8
            val shortBlocks = blocks - raw % blocks
            val shortLength = raw / blocks
            val divisor = reedSolomonDivisor(eccLength)
            val all = ArrayList<ByteArray>(blocks)
            var k = 0
            for (i in 0 until blocks) {
                val dataLength = shortLength - eccLength + if (i < shortBlocks) 0 else 1
                val chunk = data.copyOfRange(k, k + dataLength)
                k += dataLength
                val block = ByteArray(shortLength + 1)
                chunk.copyInto(block)
                reedSolomonRemainder(chunk, divisor).copyInto(block, block.size - eccLength)
                all.add(block)
            }
            val result = ByteArray(raw)
            var index = 0
            for (i in 0 until shortLength + 1) {
                for (j in all.indices) {
                    // 短块在数据区末尾少一个字节，交织时跳过这个占位
                    if (i != shortLength - eccLength || j >= shortBlocks) result[index++] = all[j][i]
                }
            }
            return result
        }

        private fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vertical in 0 until size) {
                    for (j in 0 until 2) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vertical else vertical
                        if (!function[y][x] && i < data.size * 8) {
                            modules[y][x] = bit(data[i ushr 3].toInt(), 7 - (i and 7))
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val invert = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    if (invert && !function[y][x]) modules[y][x] = !modules[y][x]
                }
            }
        }

        // 掩码评分：连续同色、2×2 色块、类定位图形、深浅比例
        private fun penaltyScore(): Int {
            var result = 0
            for (line in 0 until size) {
                result += linePenalty { modules[line][it] }
                result += linePenalty { modules[it][line] }
            }
            for (y in 0 until size - 1) {
                for (x in 0 until size - 1) {
                    val color = modules[y][x]
                    if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1]) result += 3
                }
            }
            val dark = modules.sumOf { row -> row.count { it } }
            val total = size * size
            val k = (abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += k * 10
            return result
        }

        private inline fun linePenalty(at: (Int) -> Boolean): Int {
            var result = 0
            var run = 1
            for (i in 1 until size) {
                if (at(i) == at(i - 1)) {
                    run++
                } else {
                    if (run >= 5) result += run - 2
                    run = 1
                }
            }
            if (run >= 5) result += run - 2
            var pattern = 0
            for (i in 0 until size) {
                pattern = ((pattern shl 1) or if (at(i)) 1 else 0) and 0x7FF
                if (i >= 10 && (pattern == FINDER_A || pattern == FINDER_B)) result += 40
            }
            return result
        }

        private companion object {
            const val FINDER_A = 0b10111010000
            const val FINDER_B = 0b00001011101
        }
    }

    private fun bit(value: Int, index: Int): Boolean = (value ushr index) and 1 != 0

    private fun reedSolomonDivisor(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in result.indices) {
                result[j] = gfMultiply(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < result.size) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
        val result = ByteArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() xor result[0].toInt()) and 0xFF
            result.copyInto(result, 0, 1, result.size)
            result[result.size - 1] = 0
            for (i in result.indices) {
                result[i] = (result[i].toInt() xor gfMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }

    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }
}
