package com.mytv.mymusic.playback

import com.mytv.mymusic.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 播放期要用的 Apple 登录态。[cookie] 由 `MyMusicApp` 在设置流里更新（和
 * `PlayReportHub.updateCookies` 同一个收集点），播放服务起来时直接读——
 * DRM 授权是阻塞调用，不能在那儿挂起去取设置。
 *
 * 值就是设置里那张 Apple Cookie 字符串，里面要带 `media-user-token`；为空表示没登录。
 */
object AppleSession {
    @Volatile
    var cookie: String = ""

    fun update(cookies: Map<String, String>) {
        cookie = cookies["apple"].orEmpty()
    }

    /**
     * 媒体按键 / 续播能在设置流首帧之前把播放服务拉起来，那时 [cookie] 还是空的，
     * 授权会白跑一次。服务启动时主动取一次补上。
     */
    fun refreshAsync(settings: SettingsStore, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val latest = runCatching { settings.state.first() }.getOrNull() ?: return@launch
            update(latest.cookies)
        }
    }
}
