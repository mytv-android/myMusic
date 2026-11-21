package com.mytv.mymusic.data.update

/**
 * 更新提示的三种形态。检查到新版本后不再自动下载，改成每次启动弹一次窗等用户确认：
 * 用户在弹窗里点「暂不更新」后，本次运行只留一条可以点开的提醒，重启进程照常重新弹窗。
 */
enum class UpdatePrompt { None, Dialog, Reminder }

/**
 * 由更新阶段、新版本号和用户已推迟的版本号推导该显示哪一种提示。
 * [postponed] 是本次运行里用户点过「暂不更新」的版本号，0 表示没有推迟过；
 * 推迟只对同一个版本生效，出现新版本或重启进程都会重新弹窗。
 */
internal fun updatePromptFor(phase: UpdatePhase, versionCode: Long?, postponed: Long): UpdatePrompt = when {
    phase != UpdatePhase.Available || versionCode == null -> UpdatePrompt.None
    versionCode == postponed -> UpdatePrompt.Reminder
    else -> UpdatePrompt.Dialog
}
