package com.wuming.musicFW.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.wuming.musicFW.R
import com.wuming.musicFW.managers.AppSettings
import com.wuming.musicFW.utils.LogHelper

class FloatingLyricsService : Service() {
    private var wm: WindowManager? = null
    private var floatView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastX = 0f; private var lastY = 0f; private var isDragging = false
    private var isLocked = false
    private var rainbowJob: Runnable? = null
    private var typingJob: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fullLyric = ""
    private var lyricTv: TextView? = null
    private var titleTv: TextView? = null

    companion object {
        private var instance: FloatingLyricsService? = null
        private var songTitle = ""
        var onCloseCallback: (() -> Unit)? = null
        fun isRunning(): Boolean = instance != null
        fun requestShow(ctx: Context, lyric: String, title: String = "") {
            songTitle = title
            ctx.startService(Intent(ctx, FloatingLyricsService::class.java).apply {
                action = "SHOW"; putExtra("lyrics", lyric)
            })
        }
        fun requestHide(ctx: Context) {
            ctx.startService(Intent(ctx, FloatingLyricsService::class.java).apply { action = "HIDE" })
        }
        fun requestUpdate(ctx: Context, lyric: String, title: String = "") {
            songTitle = title
            ctx.startService(Intent(ctx, FloatingLyricsService::class.java).apply {
                action = "UPDATE"; putExtra("lyrics", lyric)
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        LogHelper.d("FloatingLyricsService 创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            LogHelper.d("无悬浮窗权限"); return START_NOT_STICKY
        }
        when (intent?.action) {
            "SHOW" -> showFloating(intent.getStringExtra("lyrics") ?: "")
            "UPDATE" -> updateLyrics(intent.getStringExtra("lyrics") ?: "")
            "HIDE" -> hideFloating()
            "LOCK" -> { isLocked = !isLocked; updateLockIcon() }
        }
        return START_NOT_STICKY
    }

    private fun showFloating(lyrics: String) {
        if (floatView != null) { updateLyrics(lyrics); return }
        floatView = LayoutInflater.from(this).inflate(R.layout.floating_lyrics, null)
        lyricTv = floatView!!.findViewById(R.id.floatingLyricText)
        titleTv = floatView!!.findViewById(R.id.floatingSongTitle)
        fullLyric = lyrics; titleTv?.text = songTitle.ifEmpty { "歌词悬浮窗" }

        floatView!!.findViewById<View>(R.id.floatingCloseBtn).setOnClickListener {
            hideFloating(); onCloseCallback?.invoke()
        }
        floatView!!.findViewById<View>(R.id.floatingLockBtn).setOnClickListener {
            isLocked = !isLocked; updateLockIcon()
        }
        floatView!!.setOnTouchListener { v, e -> if (!isLocked) onTouch(v, e) else true }
        updateLockIcon()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 200 }
        try {
            wm?.addView(floatView, params)
            applyStyle(lyrics)
            LogHelper.d("悬浮窗已显示")
        } catch (e: Exception) { LogHelper.e("显示失败: ${e.message}") }
    }

    private fun applyStyle(lyric: String) {
        val tv = lyricTv ?: return
        val size = AppSettings.fontSize
        tv.textSize = size.toFloat()

        when (AppSettings.textStyle) {
            1 -> { // 霓虹青
                tv.setTextColor(Color.parseColor("#00E5FF"))
                tv.setShadowLayer(12f, 0f, 0f, Color.parseColor("#00E5FF"))
                tv.setShadowLayer(24f, 0f, 0f, Color.parseColor("#0055FF"))
            }
            2 -> startRainbow() // 彩虹
            else -> { // 经典白
                tv.setTextColor(Color.WHITE)
                tv.setShadowLayer(0f, 0f, 0f, 0)
                stopRainbow()
            }
        }

        if (AppSettings.typingEffect) startTyping(lyric) else {
            stopTyping(); tv.text = lyric
        }
    }

    // ── 彩虹渐变 ──
    private val rainbowColors = intArrayOf(
        0xFFFF0000.toInt(), 0xFFFF8800.toInt(), 0xFFFFDD00.toInt(),
        0xFF00FF00.toInt(), 0xFF0088FF.toInt(), 0xFF8800FF.toInt()
    )
    private fun startRainbow() {
        stopRainbow()
        var idx = 0
        rainbowJob = Runnable {
            val tv = lyricTv ?: return@Runnable
            val c1 = rainbowColors[idx % rainbowColors.size]
            val c2 = rainbowColors[(idx + 1) % rainbowColors.size]
            tv.setTextColor(c1)
            tv.setShadowLayer(10f, 0f, 0f, c2)
            idx++; mainHandler.postDelayed(rainbowJob!!, 200)
        }
        mainHandler.post(rainbowJob!!)
    }
    private fun stopRainbow() { rainbowJob?.let { mainHandler.removeCallbacks(it) }; rainbowJob = null }

    // ── 打字效果 ──
    private fun startTyping(full: String) {
        stopTyping()
        var pos = 0
        typingJob = Runnable {
            lyricTv?.text = full.substring(0, pos + 1)
            pos++
            if (pos < full.length) mainHandler.postDelayed(typingJob!!, 60)
        }
        mainHandler.post(typingJob!!)
    }
    private fun stopTyping() { typingJob?.let { mainHandler.removeCallbacks(it) }; typingJob = null }

    private fun updateLockIcon() {
        floatView?.findViewById<TextView>(R.id.floatingLockBtn)?.text = if (isLocked) "\uD83D\uDD12" else "\uD83D\uDD13"
    }

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        val lp = params ?: return false
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY; isDragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - lastX; val dy = e.rawY - lastY
                if (!isDragging && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) isDragging = true
                if (isDragging) {
                    lp.x = (lp.x + dx).toInt(); lp.y = (lp.y + dy).toInt()
                    try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
                    lastX = e.rawX; lastY = e.rawY
                }
            }
            MotionEvent.ACTION_UP -> if (!isDragging) v.performClick()
        }
        return true
    }

    private fun updateLyrics(lyrics: String) {
        if (floatView == null) { showFloating(lyrics); return }
        fullLyric = lyrics
        titleTv?.text = songTitle.ifEmpty { "歌词悬浮窗" }
        applyStyle(lyrics)
        LogHelper.d("歌词更新: $lyrics")
    }

    private fun hideFloating() {
        stopRainbow(); stopTyping()
        floatView?.let {
            try { wm?.removeView(it) } catch (_: Exception) {}
            floatView = null; params = null
            LogHelper.d("悬浮窗已隐藏")
        }
    }

    override fun onDestroy() { hideFloating(); instance = null; super.onDestroy() }
}
