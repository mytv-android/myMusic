package com.mytv.mymusic.data.sync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Android 17（API 37）起的本地网络保护：targetSdk ≥ 37 的应用连「接受局域网入站连接」也要持有
 * ACCESS_LOCAL_NETWORK，否则手机扫了码也打不开电视上的配置面板——127.0.0.1 是 loopback，不算
 * 本地网络，本机永远能访问，只有局域网 IP 被内核丢包，服务端日志里什么异常都没有。
 *
 * targetSdk ≤ 36 时系统靠 INTERNET 隐式放行本地网络，也不认识这个权限，申请只会被直接拒绝，
 * 所以两个条件都满足才需要它（见 [isRequired]）。权限按 UID 生效，授予后已在监听的 socket
 * 立刻恢复可达，不用重启服务。
 */
object LocalNetworkAccess {

    // 用字面量而不是 Manifest.permission 字段：低版本系统上没有这个常量，避免运行期解析不到
    const val PERMISSION: String = "android.permission.ACCESS_LOCAL_NETWORK"

    private const val REQUIRED_SDK = 37

    fun isRequired(context: Context): Boolean =
        isRequired(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion)

    /** 系统是 37+ 且本应用 targetSdk ≥ 37 才被拦；targetSdk 低时 INTERNET 隐式放行。 */
    internal fun isRequired(sdkInt: Int, targetSdk: Int): Boolean =
        sdkInt >= REQUIRED_SDK && targetSdk >= REQUIRED_SDK

    fun isGranted(context: Context): Boolean =
        !isRequired(context) ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * 作为客户端连出去时，目标是不是“本地网络”：私网 IPv4、链路本地、IPv6 ULA（fc00::/7）。
     * 回环不算，本机永远能连。WebDAV 填了 NAS 的局域网地址时要先拿到权限，否则连接直接被丢包。
     */
    fun isLocalNetworkAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress) return false
        if (address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        return address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc
    }

    /**
     * 主机名是否指向本地网络。会做 DNS 解析，必须在 IO 线程调用；解析失败时按字面判断：
     * `.local`（mDNS）和不带点的单标签主机名（`nas`）基本都在局域网里。
     */
    fun isLocalNetworkHost(host: String): Boolean {
        val name = host.trim().trim('[', ']')
        if (name.isEmpty()) return false
        val resolved = runCatching { InetAddress.getAllByName(name).toList() }.getOrNull()
        if (!resolved.isNullOrEmpty()) return resolved.any(::isLocalNetworkAddress)
        val lower = name.lowercase()
        return lower.endsWith(".local") || lower.endsWith(".lan") || !lower.contains('.')
    }

    /** 权限属于 NEARBY_DEVICES 组，被拒后系统不再弹框，只能引导用户自己去应用详情页开 */
    fun openSettings(context: Context) {
        runCatching {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
