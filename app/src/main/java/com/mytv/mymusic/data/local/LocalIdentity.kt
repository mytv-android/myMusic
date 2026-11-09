package com.mytv.mymusic.data.local

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * 本地歌的身份：`sha256:` + hex(SHA-256(前 64KB ‖ 末 64KB ‖ 文件大小))。
 *
 * 只读首尾，扫描上万首也不会把整盘读一遍；文件大小参与，降低同一段前奏的碰撞。
 * 大小按 8 字节小端无符号整数拼在最后——规格只写了 uint64，这里定死小端，
 * 所有设备都按这个算，同一个文件在哪台设备上得到的 localId 都一样。
 * 小于 128KB 的文件首尾两段会重叠，照样各读 min(64KB, size) 字节，不做特殊处理。
 */
object LocalIdentity {
    const val CHUNK = 64 * 1024
    const val PREFIX = "sha256:"

    /** [read] 按 (偏移, 长度) 返回正好那么多字节。 */
    fun compute(size: Long, read: (offset: Long, length: Int) -> ByteArray): String {
        require(size >= 0) { "negative size" }
        val digest = MessageDigest.getInstance("SHA-256")
        val length = minOf(CHUNK.toLong(), size).toInt()
        digest.update(read(0L, length))
        digest.update(read(size - length, length))
        digest.update(sizeBytes(size))
        return PREFIX + digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun of(bytes: ByteArray): String = compute(bytes.size.toLong()) { offset, length ->
        bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    internal fun sizeBytes(size: Long): ByteArray = ByteArray(8) { index -> (size ushr (8 * index)).toByte() }

    /** 指纹的短写法，歌曲信息里显示 `sha256:9f3a…c41e`。 */
    fun short(localId: String): String {
        val hex = localId.removePrefix(PREFIX)
        return if (hex.length <= 8) localId else PREFIX + hex.take(4) + "…" + hex.takeLast(4)
    }

    /**
     * 读 [uri] 算身份。能拿到可定位的文件描述符就直接按偏移读；拿到的是管道（云端文档提供方常见）
     * 就退回顺序流跳着读。[sizeHint] 是扫描时查到的大小，描述符给不出大小时用它。
     */
    fun hash(context: Context, uri: Uri, sizeHint: Long): String {
        val resolver = context.contentResolver
        val seekable = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val size = pfd.statSize.takeIf { it >= 0 } ?: sizeHint
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val channel = input.channel
                    compute(size) { offset, length ->
                        val buffer = ByteBuffer.allocate(length)
                        var position = offset
                        while (buffer.hasRemaining()) {
                            val read = channel.read(buffer, position)
                            if (read < 0) throw IOException("文件比登记的短")
                            position += read
                        }
                        buffer.array()
                    }
                }
            }
        }.getOrNull()
        if (seekable != null) return seekable
        if (sizeHint < 0) throw IOException("不知道文件大小")
        // 顺序流：先读头，再跳到末尾 64KB；两段重叠时尾段从已经读到的头里截
        return resolver.openInputStream(uri)?.use { input -> hashStream(input, sizeHint) }
            ?: throw IOException("打不开 $uri")
    }

    internal fun hashStream(input: InputStream, size: Long): String {
        val length = minOf(CHUNK.toLong(), size).toInt()
        val head = readFully(input, length)
        val tailStart = size - length
        val tail = if (tailStart < length) {
            // 尾段和头段有重叠：缺的部分接着往后读
            val have = head.copyOfRange(tailStart.toInt(), length)
            have + readFully(input, length - have.size)
        } else {
            skipFully(input, tailStart - length)
            readFully(input, length)
        }
        return compute(size) { offset, _ -> if (offset == 0L) head else tail }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val read = input.read(out, done, length - done)
            if (read < 0) throw IOException("文件比登记的短")
            done += read
        }
        return out
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(8192)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            // skip 返回 0 不代表到头，读一下确认
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (read < 0) throw IOException("文件比登记的短")
            left -= read
        }
    }
}
