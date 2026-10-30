package com.mytv.mymusic

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * 当前前台 Activity 的弱引用，由 [MyMusicApp] 的生命周期回调维护。
 * 数据层需要在窗口上挂隐藏 WebView（汽水扫码登录的签名页）时从这里取 Activity；
 * 取不到说明应用不在前台，调用方应提示用户回到应用内操作。
 */
object ForegroundActivity {
    @Volatile
    private var current: WeakReference<Activity>? = null

    fun set(activity: Activity) {
        current = WeakReference(activity)
    }

    fun clear(activity: Activity) {
        if (current?.get() === activity) current = null
    }

    fun get(): Activity? = current?.get()
}
