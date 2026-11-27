package com.mytv.mymusic.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.ui.player.LyricFontRuntime
import com.mytv.mymusic.ui.player.LyricFonts
import com.mytv.mymusic.ui.player.styledLyricTypeface
import com.mytv.mymusic.ui.theme.isTelevision
import com.mytv.mymusic.widget.PlaybackSnapshot
import com.mytv.mymusic.widget.WidgetHub
import com.mytv.mymusic.widget.WidgetLinks
import com.mytv.mymusic.widget.WidgetLyrics
import com.mytv.mymusic.widget.WidgetTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统悬浮窗歌词。普通 started service 与已有媒体前台服务同进程，不再叠一张前台服务通知；
 * 用户授权、拖动、关闭都是显式操作，不用无障碍，也不会抢输入法焦点。
 */
class FloatingLyricsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var root: View? = null
    private var primaryText: TextView? = null
    private var secondaryText: TextView? = null
    private var playButton: ImageButton? = null
    private var nextButton: ImageButton? = null
    private var controlsRow: View? = null
    private var controlsVisible = false
    private var hideControlsAt = Long.MAX_VALUE
    private var snapshot: PlaybackSnapshot? = null
    private var shown: FloatingLyricsContent? = null
    private var observersStarted = false
    /** 设置里导入的歌词字体；换字体时跟着刷新。 */
    private var lyricFont: LyricFontRuntime? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        startObservers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isTelevision() || !hasPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null && !showOverlay()) {
            stopSelf()
            return START_NOT_STICKY
        }
        render()
        return START_STICKY
    }

    private fun startObservers() {
        if (observersStarted) return
        observersStarted = true
        scope.launch {
            WidgetHub.playback.collect {
                snapshot = it
                shown = null
                render()
            }
        }
        scope.launch {
            while (isActive) {
                render()
                val current = snapshot
                val now = SystemClock.elapsedRealtime()
                val next = current?.let { WidgetLyrics.nextChangeAt(it, now) }
                // 到换句边界就醒；暂停 / 没歌词时半秒检查一次，快照收集器仍会立即刷新歌曲和按钮。
                delay(if (next == null) 500L else (next - now).coerceIn(40L, 500L))
            }
        }
        val app = application as? MyMusicApp ?: return
        scope.launch {
            app.settings.state.map { it.lyricFonts }.distinctUntilChanged().collect { prefs ->
                val font = withContext(Dispatchers.IO) { LyricFonts.resolve(this@FloatingLyricsService, prefs) }
                lyricFont = font
                applyLyricFont()
            }
        }
    }

    private fun applyLyricFont() {
        primaryText?.typeface = styledLyricTypeface(lyricFont, 700)
        secondaryText?.typeface = styledLyricTypeface(lyricFont, 400)
    }

    private fun showOverlay(): Boolean {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val overlayWidth = screenWidth * 9 / 10
        val centeredX = ((screenWidth - overlayWidth) / 2).coerceAtLeast(0)
        val preferences = getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = null
            isClickable = true
        }

        val primary = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(styledLyricTypeface(lyricFont, 700))
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), TEXT_SHADOW)
            gravity = Gravity.CENTER_HORIZONTAL
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val secondary = TextView(this).apply {
            textSize = 13f
            setTextColor(SECONDARY)
            setTypeface(styledLyricTypeface(lyricFont, 400))
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), TEXT_SHADOW)
            gravity = Gravity.CENTER_HORIZONTAL
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(primary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        bar.addView(secondary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        fun button(icon: Int, description: String, action: () -> Unit): ImageButton = ImageButton(this).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            background = null
            contentDescription = description
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { action() }
        }
        val play = button(android.R.drawable.ic_media_play, "播放或暂停") { transport("play") }
        val next = button(android.R.drawable.ic_media_next, "下一首") { transport("next") }
        val close = button(android.R.drawable.ic_menu_close_clear_cancel, "关闭悬浮歌词", ::closeFromOverlay)
        close.alpha = CONTROL_ALPHA
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(play, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(next, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(close, LinearLayout.LayoutParams(dp(34), dp(34)))
        }
        bar.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2) })

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        params = WindowManager.LayoutParams(
            overlayWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (preferences.getInt(POSITION_WIDTH, -1) == overlayWidth) {
                preferences.getInt(POSITION_X, centeredX)
            } else {
                centeredX
            }
            y = preferences.getInt(POSITION_Y, dp(80))
        }
        attachDragging(bar)
        bar.setOnClickListener {
            if (controlsVisible) {
                runCatching { startActivity(WidgetLinks.intent(this, WidgetTarget.Lyrics)) }
            } else {
                showControls()
            }
        }
        return runCatching {
            windowManager.addView(bar, params)
            root = bar
            primaryText = primary
            secondaryText = secondary
            playButton = play
            nextButton = next
            controlsRow = controls
            controlsVisible = false
            hideControlsAt = Long.MAX_VALUE
            bar.post {
                if (root !== bar) return@post
                clampIntoScreen(save = true)
            }
            true
        }.getOrElse {
            root = null
            false
        }
    }

    private fun showControls() {
        controlsRow?.visibility = View.VISIBLE
        controlsVisible = true
        hideControlsAt = SystemClock.elapsedRealtime() + CONTROLS_VISIBLE_MS
        updateOverlayDescription()
        root?.post { clampIntoScreen(save = false) }
    }

    private fun hideControls() {
        if (!controlsVisible) return
        controlsRow?.visibility = View.GONE
        controlsVisible = false
        hideControlsAt = Long.MAX_VALUE
        updateOverlayDescription()
        root?.post { clampIntoScreen(save = false) }
    }

    private fun updateOverlayDescription() {
        val content = shown ?: return
        val interaction = if (controlsVisible) "点按歌词打开应用" else "点按显示播放控制"
        root?.contentDescription = listOf(content.primary, content.secondary).filter { it.isNotBlank() }
            .joinToString("，") + "。$interaction，拖动调整位置"
    }

    private fun attachDragging(bar: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        bar.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (moved || kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                        moved = true
                        params.x = startX + dx
                        params.y = startY + dy
                        clampIntoScreen(save = false)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) savePosition() else view.performClick()
                }
                MotionEvent.ACTION_CANCEL -> if (moved) savePosition()
            }
            true
        }
    }

    private fun clampIntoScreen(save: Boolean) {
        val bar = root ?: return
        val position = clampFloatingLyricsPosition(
            params.x,
            params.y,
            bar.width,
            bar.height,
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels,
        )
        params.x = position.x
        params.y = position.y
        runCatching { windowManager.updateViewLayout(bar, params) }
        if (save) savePosition()
    }

    private fun savePosition() {
        if (!::params.isInitialized) return
        getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE).edit()
            .putInt(POSITION_X, params.x)
            .putInt(POSITION_Y, params.y)
            .putInt(POSITION_WIDTH, params.width)
            .apply()
    }

    private fun render() {
        if (root == null) return
        val now = SystemClock.elapsedRealtime()
        if (controlsVisible && now >= hideControlsAt) hideControls()
        val content = floatingLyricsContent(snapshot, now)
        if (content == shown) return
        shown = content
        primaryText?.text = content.primary
        secondaryText?.text = content.secondary
        secondaryText?.visibility = if (content.secondary.isBlank()) View.GONE else View.VISIBLE
        playButton?.apply {
            setImageResource(if (content.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            isEnabled = content.canControl
            alpha = if (content.canControl) CONTROL_ALPHA else 0.25f
        }
        nextButton?.apply {
            isEnabled = content.canControl
            alpha = if (content.canControl) CONTROL_ALPHA else 0.25f
        }
        updateOverlayDescription()
    }

    private fun transport(action: String) {
        showControls()
        val app = application as? MyMusicApp ?: return
        scope.launch {
            val ok = runCatching { app.musicViewModel.widgetTransport(action) }.getOrDefault(false)
            if (!ok) Toast.makeText(this@FloatingLyricsService, "暂时无法控制播放，请打开 myMusic 重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeFromOverlay() {
        val app = application as? MyMusicApp
        scope.launch {
            if (app != null) {
                withContext(Dispatchers.IO) { app.settings.floatingLyricsEnabled(false) }
            }
            Toast.makeText(this@FloatingLyricsService, "悬浮歌词已关闭", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val bar = root ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val overlayWidth = screenWidth * 9 / 10
        if (params.width != overlayWidth) {
            params.width = overlayWidth
            params.x = ((screenWidth - overlayWidth) / 2).coerceAtLeast(0)
        }
        bar.requestLayout()
        bar.post { clampIntoScreen(save = true) }
    }

    override fun onDestroy() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val POSITION_PREFS = "floating_lyrics_position"
        private const val POSITION_X = "x"
        private const val POSITION_Y = "y"
        private const val POSITION_WIDTH = "width"
        private const val CONTROLS_VISIBLE_MS = 3_000L
        private const val CONTROL_ALPHA = 0.72f
        private val TEXT_SHADOW = 0xE6000000.toInt()
        private const val SECONDARY = 0xF2FFFFFF.toInt()

        fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun permissionIntent(context: Context): Intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

        /** 返回系统是否接受了启动 / 停止；后台启动受限时由下次回到应用再补一次。 */
        fun sync(context: Context, enabled: Boolean): Boolean {
            val intent = Intent(context, FloatingLyricsService::class.java)
            if (!floatingLyricsServiceAllowed(context.isTelevision(), enabled, hasPermission(context))) {
                context.stopService(intent)
                return true
            }
            return runCatching { context.startService(intent); true }.getOrDefault(false)
        }
    }
}
