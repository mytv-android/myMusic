package com.mytv.mymusic.ui.player.stage

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mytv.mymusic.ui.player.stage.modes.AuroraVisualizer
import com.mytv.mymusic.ui.player.stage.modes.CineramaVisualizer
import com.mytv.mymusic.ui.player.stage.modes.JumpVisualizer
import com.mytv.mymusic.ui.player.stage.modes.MisideDropVisualizer
import com.mytv.mymusic.ui.player.stage.modes.Rings52HzVisualizer
import com.mytv.mymusic.ui.player.stage.modes.RushVisualizer
import com.mytv.mymusic.ui.player.stage.modes.StardriftVisualizer
import com.mytv.mymusic.ui.player.stage.modes.VinylDiscVisualizer
import com.mytv.mymusic.ui.player.stage.modes.WaveLightVisualizer

/** 编进 APK 的全部歌词动画模式，按 [LyricVisualizer.order] 排。 */
object LyricVisualizers {
    val all: List<LyricVisualizer> = listOf(
        ClassicVisualizer,
        RushVisualizer,
        AuroraVisualizer,
        Rings52HzVisualizer,
        MisideDropVisualizer,
        WaveLightVisualizer,
        VinylDiscVisualizer,
        CineramaVisualizer,
        StardriftVisualizer,
        JumpVisualizer,
    ).sortedBy { it.order }

    /** 认不出的 id（比如以后删掉的模式）按经典。 */
    fun byId(id: String): LyricVisualizer = all.firstOrNull { it.id == id } ?: ClassicVisualizer
}

/**
 * 经典：现在的整页歌词列表（LyricsList），宿主直接画它，参数原样传进去，默认情况下画面一个像素都不变。
 * 也是没有同步歌词、窗口太矮时的退路。
 */
object ClassicVisualizer : LyricVisualizer {
    override val id = "classic"
    override val label = "经典"
    override val origin: ModOrigin? = null
    override val order = 0

    @Composable
    override fun Stage(ctx: LyricStageContext, modifier: Modifier) = Unit
}
