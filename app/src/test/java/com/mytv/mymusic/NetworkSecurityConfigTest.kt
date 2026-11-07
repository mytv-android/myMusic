package com.mytv.mymusic

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守住明文流量，防止被误改。
 *
 * 各音乐平台的接口和 CDN 大量只有 http://（酷狗 / 酷我 / QQ / 网易 / 咪咕 / 5sing），酷我封面和
 * 汽水播放地址的主机还是接口动态返回的，SodaM 签名服务地址由用户自填（默认补 http://，常是局域网
 * 机器）——域名根本列不全白名单，所以放行依据是 network_security_config 的 base-config 全局允许。
 *
 * 一旦有人把 base-config 改成 false、或给某个域名单独加上 cleartextTrafficPermitted="false"，
 * 症状是这些平台的搜索和播放静默失败（不抛异常，只有超时），极难排查，所以用测试兜住。
 * usesCleartextTraffic 也必须留着：minSdk 23 低于 NSC 生效的 API 24，那是 API 23 上的唯一依据。
 */
class NetworkSecurityConfigTest {

    @Test
    fun cleartextStaysPermitted() {
        val config = locate("src/main/res/xml/network_security_config.xml").readText()
        val baseConfig = Regex("""<base-config\b[^>]*>""").find(config)?.value
            ?: throw AssertionError("network_security_config.xml 里没有 base-config")
        assertTrue(
            "base-config 必须 cleartextTrafficPermitted=\"true\"，否则 http:// 的接口会全部失效",
            Regex("""cleartextTrafficPermitted\s*=\s*"true"""").containsMatchIn(baseConfig),
        )
        assertTrue(
            "不允许出现 cleartextTrafficPermitted=\"false\"：域名列不全，收紧必然漏掉某些平台",
            !Regex("""cleartextTrafficPermitted\s*=\s*"false"""").containsMatchIn(config),
        )
    }

    @Test
    fun manifestKeepsCleartextForApi23() {
        val manifest = locate("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "minSdk 23 低于 NSC 生效的 API 24，usesCleartextTraffic 必须留着兜底",
            Regex("""android:usesCleartextTraffic\s*=\s*"true"""").containsMatchIn(manifest),
        )
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
