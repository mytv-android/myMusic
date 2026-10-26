package com.mytv.mymusic.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * 电视环境模式右上角的时钟，跟随系统 12/24 小时制。
 * 12 小时制和锁屏时钟一样只显示“9:30”，上午/下午只写进读屏描述，画面上不再多一段小字。
 * 整分钟刷新一次，不按秒轮询；改时间、改时区、切换 12/24 小时制时立即刷新。
 */
@Composable
fun AmbientClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(context) {
        val refresh = { now = System.currentTimeMillis() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // 只收系统广播；设置里切 12/24 小时制会发 TIME_CHANGED，有的电视系统只改设置项不发广播，再监听设置项兜底
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.TIME_12_24), false, observer)
        onDispose {
            context.unregisterReceiver(receiver)
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    // 每次刷新后重新计时到下一个整分钟；广播提前刷新时这里也跟着重启
    LaunchedEffect(now) {
        delay(millisUntilNextMinute(now))
        now = System.currentTimeMillis()
    }
    val use24Hour = remember(context, now) { DateFormat.is24HourFormat(context) }
    val calendar = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    val typography = MaterialTheme.typography.displaySmall
    val style = remember(typography) {
        typography.copy(
            fontSize = 34.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 0.02.em,
            fontFeatureSettings = "tnum",
        )
    }
    val description = clockDescription(hour, minute, use24Hour)
    Text(
        clockText(hour, minute, use24Hour),
        modifier = modifier.semantics { contentDescription = description },
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
    )
}

/** 24 小时制补零（09:05、21:30）；12 小时制小时不补零（9:30），0 点和 12 点都显示 12。 */
internal fun clockText(hour: Int, minute: Int, use24Hour: Boolean): String {
    val minutes = minute.toString().padStart(2, '0')
    return if (use24Hour) {
        "${hour.toString().padStart(2, '0')}:$minutes"
    } else {
        "${(hour % 12).let { if (it == 0) 12 else it }}:$minutes"
    }
}

/** 读屏描述：12 小时制画面上省略了上午/下午，这里补上，避免读成有歧义的时间。 */
internal fun clockDescription(hour: Int, minute: Int, use24Hour: Boolean): String {
    val period = if (use24Hour) "" else if (hour < 12) "上午" else "下午"
    return "当前时间 $period${clockText(hour, minute, use24Hour)}"
}

/** 距下一个整分钟的毫秒数，范围 1..60000；时区偏移都是整分钟，按 UTC 毫秒取整即可。 */
internal fun millisUntilNextMinute(nowMillis: Long): Long = 60_000L - nowMillis.mod(60_000L)
