package com.mytv.mymusic

import com.mytv.mymusic.data.lx.LxStorageRoot
import com.mytv.mymusic.data.lx.confineToRoots
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LxTvStorageTest {
    /**
     * 面板「电视文件夹」渠道的安全边界：手机只能碰到电视共享存储根自身和它的子孙，
     * `..` 穿越、根以外的绝对路径、前缀相仿的目录都要挡住。
     */
    @Test
    fun pathsAreConfinedToStorageRoots() {
        val root = Files.createTempDirectory("tvstore").toFile()
        val sub = File(root, "scripts").apply { mkdirs() }
        val outside = Files.createTempDirectory("outside").toFile()
        val roots = listOf(LxStorageRoot("0", "内部存储", root.canonicalPath))

        assertEquals(root.canonicalFile, confineToRoots(root.path, roots))
        assertEquals(sub.canonicalFile, confineToRoots(sub.path, roots))
        assertEquals(sub.canonicalFile, confineToRoots("${root.path}${File.separator}.${File.separator}scripts", roots))
        // 拼出来的路径规范化后落到根外面
        assertNull(confineToRoots("${root.path}/../outside", roots))
        assertNull(confineToRoots(outside.path, roots))
        assertNull(confineToRoots("/etc", roots))
        assertNull(confineToRoots("", roots))
        assertNull(confineToRoots("   ", roots))
        // 前缀相仿但不是子孙：/sdcard-evil 不是 /sdcard 的里面
        assertNull(confineToRoots("${root.path}-evil${File.separator}x.js", roots))
    }
}
