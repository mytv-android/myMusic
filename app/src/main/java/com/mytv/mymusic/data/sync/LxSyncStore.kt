package com.mytv.mymusic.data.sync

/**
 * 洛雪自定义音源的同步出入口。同步层不认识脚本引擎：建快照时取一份带脚本正文的音源表，
 * 合并完把它写回本机。MyMusicApp 建好 LxSources 后接上；没接时同步文件里没有这一块。
 */
interface LxSyncStore {
    /** 本机全部音源，连脚本正文；删除过的不要。读取失败的一项会跳过。 */
    suspend fun snapshot(): List<SyncLxSource>

    /**
     * 把合并结果写回本机。脚本正文为空的项（别的设备上没读到）只更新开关和顺序；
     * [removeAbsent] 为真时，[sources] 里没有的音源从本机删掉。
     */
    suspend fun apply(sources: List<SyncLxSource>, removeAbsent: Boolean)
}
