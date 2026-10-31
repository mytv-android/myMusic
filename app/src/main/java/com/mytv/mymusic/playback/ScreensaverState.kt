package com.mytv.mymusic.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

/**
 * 应用界面正被系统层盖住：屏保（DreamService）或息屏。
 *
 * 这两种情况都会让 activity 走一遍「离开」的生命周期，部分电视的屏保甚至是第三方应用拉起来的前台界面，
 * onUserLeaveHint 照样会来，但人其实还留在应用里，音乐该继续放。判据就是这几条系统广播：
 * DREAMING_STARTED / DREAMING_STOPPED、SCREEN_OFF / SCREEN_ON、USER_PRESENT。
 *
 * 由 [com.mytv.mymusic.MyMusicApp] 在进程里注册一次；只在主线程读写（广播默认投递到主线程）。
 */
object ScreensaverState {
    /** 屏保或息屏正盖着界面：这时候「离开应用」不是用户真的走了。 */
    @Volatile
    var covered: Boolean = false
        private set

    private var screenOn = true
    private var dreaming = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_DREAMING_STARTED -> dreaming = true
                // 解锁时屏保一定已经收了：放在这儿少一条分支
                Intent.ACTION_DREAMING_STOPPED, Intent.ACTION_USER_PRESENT -> dreaming = false
            }
            covered = !screenOn || dreaming
        }
    }

    /** 进程里注册一次；广播是受保护的系统广播，动态注册不受后台限制影响。 */
    fun start(context: Context) {
        screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        covered = !screenOn
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }
}
