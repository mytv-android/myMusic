package com.mytv.mymusic.ui.player.stage

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.mytv.mymusic.ui.theme.isTelevision

/**
 * 随设备变的默认值。设置表的默认值必须固定（写入时等于默认值的项会被丢掉），所以按设备分档的默认值
 * 在第一次用到模式设置之前定一次，之后不再变：同一台设备上默认值前后一致，存下来的值才有意义。
 */
object StageDevice {
    @Volatile
    private var resolved = false

    /**
     * 实时模糊默认开不开：Android 12 起、不是电视、不是低内存设备，和应用里“模糊效果：自动”的规则一样。
     * 没定档之前（单测、预览）按开算。
     */
    @Volatile
    var liveBlur: Boolean = true
        private set

    fun resolve(context: Context) {
        if (resolved) return
        val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
        liveBlur = Build.VERSION.SDK_INT >= 31 && !context.isTelevision() && !lowRam
        resolved = true
    }
}

/** 读模式设置之前调一次（幂等，定过档就直接返回）。 */
@Composable
internal fun ResolveStageDevice() {
    StageDevice.resolve(LocalContext.current)
}
