package com.mytv.mymusic

import android.content.Context
import android.os.Build
import android.os.UserManager
import androidx.work.WorkManager

/**
 * WorkManager 2.9+ 在设备保护存储（DE）上下文里、以及解锁前的进程里都会拒绝初始化。
 * 这两种进程里应用数据本身可读，主界面照常能跑，只是不适合做后台调度：
 * 这里统一判断，跳过组件刷新、榜单定时任务和同步重试，避免 WorkManager.getInstance 把进程带崩。
 */
object BackgroundWork {
    fun available(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val app = context.applicationContext
        return !app.isDeviceProtectedStorage &&
            app.getSystemService(UserManager::class.java)?.isUserUnlocked != false
    }

    /** 不可用时返回 null，调用方直接跳过这次调度。 */
    fun get(context: Context): WorkManager? =
        if (available(context)) WorkManager.getInstance(context) else null
}
