package com.mytv.mymusic.lxruntime

/** 给 QuickJS Wrapper 补上它未暴露到 Java 的执行中断；句柄由原生侧校验生命周期。 */
object LxQuickJsInterrupt {
    init {
        System.loadLibrary("lx-runtime")
    }

    @JvmStatic
    external fun install(runtime: Long): Long

    @JvmStatic
    external fun interrupt(handle: Long)

    @JvmStatic
    external fun release(handle: Long)
}
