package com.mytv.mymusic.data.lx

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * preload 里的平台白名单，是「宿主支持哪些落雪平台」的第二份定义：
 * 脚本 inited 时只遍历 allSources，不在里面的 key 会被静默丢掉；supportActions 缺 key 时
 * handleInit 取动作表会直接抛错，整个初始化失败。
 *
 * 2026-11-27 的汽水（qs）就栽在这里：Kotlin 侧映射加了，preload 没加，音源详情页始终少一个平台，
 * 汽水的歌也永远递不进脚本。这个测试把两份表钉在一起，以后加平台漏改会立刻红。
 */
class LxPreloadWhitelistTest {
    @Test
    fun preloadAcceptsEveryPlatformTheAppMaps() {
        val script = locate("src/main/assets/lx/user-api-preload.js").readText()
        val allSources = arrayEntries(script, "allSources")
        val supportActions = objectKeys(script, "supportActions")
        // local 是本地文件，不是落雪平台；其余每个平台两处都要有
        val platforms = LxPlatform.labels.keys.filter { it != "local" }
        assertTrue("没解析到平台列表，preload 结构可能变了", platforms.isNotEmpty())
        platforms.forEach { key ->
            assertTrue("preload 的 allSources 缺少平台 $key，脚本声明了也会被丢掉", key in allSources)
            assertTrue("preload 的 supportActions 缺少平台 $key，handleInit 会取不到动作表", key in supportActions)
        }
    }

    @Test
    fun qishuiStaysInBothWhitelists() {
        val script = locate("src/main/assets/lx/user-api-preload.js").readText()
        assertTrue("allSources 里必须有 qs", "qs" in arrayEntries(script, "allSources"))
        assertTrue("supportActions 里必须有 qs", "qs" in objectKeys(script, "supportActions"))
    }

    private fun arrayEntries(script: String, name: String): List<String> {
        val block = Regex("""const $name = \[([^\]]*)]""").find(script)?.groupValues?.get(1)
            ?: throw AssertionError("找不到 preload 的 $name")
        return Regex("""['"]([a-z]+)['"]""").findAll(block).map { it.groupValues[1] }.toList()
    }

    private fun objectKeys(script: String, name: String): List<String> {
        val block = Regex("""const $name = \{([^}]*)}""").find(script)?.groupValues?.get(1)
            ?: throw AssertionError("找不到 preload 的 $name")
        return Regex("""(?m)^\s*([a-z]+)\s*:""").findAll(block).map { it.groupValues[1] }.toList()
    }

    /** 单测的工作目录可能是 app/ 也可能是仓库根，逐级向上找。 */
    private fun locate(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val hit = listOf(File(dir, relative), File(dir, "app/$relative")).firstOrNull { it.isFile }
            if (hit != null) return hit
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $relative")
    }
}
